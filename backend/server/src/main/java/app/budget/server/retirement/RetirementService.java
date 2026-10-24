package app.budget.server.retirement;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.retirement.Drawdown;
import app.budget.engine.retirement.Drawdown.Ceiling;
import app.budget.engine.retirement.Drawdown.Strategy;
import app.budget.engine.retirement.RetirementPlanner;
import app.budget.engine.retirement.RetirementPlanner.Balances;
import app.budget.engine.retirement.RetirementPlanner.Early;
import app.budget.engine.retirement.RetirementPlanner.Pot;
import app.budget.engine.retirement.RetirementPlanner.Result;
import app.budget.engine.retirement.RetirementPlanner.Target;
import app.budget.engine.retirement.RetirementPlanner.TaxType;
import app.budget.engine.retirement.TaxFigures;
import app.budget.server.accounts.Account;
import app.budget.server.accounts.AccountType;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.Inputs;
import app.budget.server.income.IncomeRepository;
import app.budget.server.income.IncomeRepository.Source;
import app.budget.server.income.PayCalendar;
import app.budget.server.ledger.Ledger;
import app.budget.server.ledger.Ledger.AccountBalance;
import app.budget.server.retirement.RetirementRepository.Fields;
import app.budget.server.retirement.RetirementRepository.Income;
import app.budget.server.retirement.RetirementRepository.OneOff;
import app.budget.server.retirement.RetirementRepository.Scenario;
import app.budget.server.retirement.RetirementRepository.Taxes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;

/**
 * Retirement planning (spec §4.12, §5.11): the accounts counted, each with its balance today, its
 * rate and what goes in a year, and every scenario worked out against them. Nothing worked out is
 * kept; it is an estimate for comparing, not advice.
 */
public final class RetirementService {

    /** What the page still needs before anything can be worked out. */
    public static final String NEEDS_BIRTH_DATE = "birth_date";
    public static final String NEEDS_ACCOUNTS = "accounts";

    private static final BigDecimal MONTHS = BigDecimal.valueOf(12);

    /** Figures are kept as the JSON the page edits. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** With no age chosen and none enough, taxes are worked out for retiring at this one. */
    static final int ASSUMED_AGE = 67;

    /** Worked out, taxes are taken out of traditional money at their share of it, never more than this. */
    private static final int MOST_TAX_SHARE_BP = 6000;

    private final Jdbi jdbi;
    private final RetirementRepository retirement;
    private final AccountsRepository accounts;
    private final IncomeRepository income;
    private final Ledger ledger;
    private final PayCalendar pay;

    public RetirementService(
            Jdbi jdbi,
            RetirementRepository retirement,
            AccountsRepository accounts,
            IncomeRepository income,
            Ledger ledger,
            PayCalendar pay) {
        this.jdbi = jdbi;
        this.retirement = retirement;
        this.accounts = accounts;
        this.income = income;
        this.ledger = ledger;
        this.pay = pay;
    }

    /**
     * An investment or savings account that could be counted. It grows at each scenario's rates, not its own.
     *
     * @param taxType       {@code traditional}, {@code roth} or {@code taxable}; {@code null} when not counted
     * @param yearlyInCents what goes in over the coming year, less what comes out
     */
    public record AccountView(
            long id, String name, String color, String type, String subtypeLabel, String taxType, long balanceCents, long yearlyInCents) {}

    /**
     * One whole age.
     *
     * @param projectedCents     at the expected growth, in today's dollars
     * @param projectedThenCents the same in the dollars of that year, before inflation is taken off
     */
    public record AgeView(
            int age, long projectedCents, long projectedLowCents, long projectedHighCents, long projectedThenCents, long neededCents,
            boolean enough) {}

    /** Retiring before 60: how many of the years until then taxable money covers. */
    public record EarlyView(int years, int covered, long neededCents, long taxableCents) {}

    /**
     * The chosen age.
     *
     * @param extraMonthlyCents     more to save a month, today's dollars; {@code null} when it's enough or can't be reached
     * @param extraPerPaycheckCents the same a paycheck of the primary source; {@code null} without one
     * @param extraTaxable          true before 60, when the extra is counted as taxable money
     */
    public record TargetView(
            int age, long neededCents, long projectedCents, boolean enough, long shortfallCents, Long extraMonthlyCents,
            Long extraPerPaycheckCents, boolean reachable, boolean extraTaxable, EarlyView early) {}

    /** @param earliestAge {@code null} when no age up to 100 is enough at the expected growth; the low and high the same at theirs */
    public record Outcome(Integer earliestAge, Integer earliestLowAge, Integer earliestHighAge, int ageNow, List<AgeView> byAge, TargetView target) {}

    /** A scenario as saved, with what it works out to; {@code outcome} is {@code null} until the page has what it needs. */
    /**
     * One strategy tried, over the whole of retirement.
     *
     * @param code     {@code none}, {@code bracket:N} or {@code medicare:N}
     * @param label    what it does, in words
     * @param runsOutAt the first age the money doesn't cover; {@code null} when it lasts
     */
    public record StrategyView(
            String code, String label, long federalCents, long stateCents, long medicareCents, long surchargesCents, Integer runsOutAt,
            long leftAfterTaxCents, long shortfallCents) {}

    /** One person's Medicare for a year, a month unless it says a year. */
    public record MedicareView(
            int age, int bracket, long lookBackCents, long partBCents, long partDCents, long plansCents, long yearlyCents) {}

    /** One year of retirement, in today's dollars. */
    public record YearView(
            int age, int calendarYear, long needCents, long otherIncomeCents, long fromTraditionalCents, long requiredCents,
            long fromTaxableCents, long fromRothCents, long toRothCents, long magiCents, long taxedSocialSecurityCents, long federalCents,
            long stateCents, int bracketBp, List<MedicareView> medicare, long shortfallCents, long traditionalLeftCents,
            long rothLeftCents, long taxableLeftCents) {}

    /**
     * Retirement taxes and Medicare, worked out (§5.12).
     *
     * @param retireAge          the age it is worked out for
     * @param ageFrom            {@code chosen} (the scenario's age), {@code earliest} (the first that is enough) or {@code assumed} ({@link #ASSUMED_AGE})
     * @param workingMagiCents   the income Medicare looks back to while still working
     * @param chosen             the strategy used
     * @param cheapest           the one that leaves the most
     * @param pinned             true when the strategy was chosen in place of the cheapest
     * @param strategies         every one tried, best first
     * @param taxShareBp         tax over retirement as a share of traditional money taken out, which stands in for the flat rate
     * @param medicareCents      Medicare a year from 65 at today's premiums for the bracket then, which stands in for health from 65
     * @param years              year by year with the strategy used
     */
    public record TaxesView(
            int retireAge,
            String ageFrom,
            long traditionalCents,
            long rothCents,
            long taxableCents,
            long workingMagiCents,
            String chosen,
            String cheapest,
            boolean pinned,
            List<StrategyView> strategies,
            int taxShareBp,
            long medicareCents,
            List<YearView> years) {}

    public record ScenarioView(
            long id,
            String name,
            long salaryCents,
            int inflationBp,
            int withdrawalBp,
            int taxBp,
            long healthBeforeCents,
            long healthAfterCents,
            int healthInflationBp,
            int growthBp,
            int growthLowBp,
            int growthHighBp,
            int raiseBp,
            long extraMonthlyCents,
            String extraInto,
            Integer retireAge,
            boolean compared,
            Taxes taxSettings,
            List<Income> incomes,
            List<OneOff> oneOffs,
            Outcome outcome,
            TaxesView taxes) {}

    /**
     * @param missing         {@link #NEEDS_BIRTH_DATE} or {@link #NEEDS_ACCOUNTS} while something is still needed; {@code null} once not
     * @param paychecksAYear  the primary source's paychecks in the coming year; {@code null} without one
     * @param payAYearCents   gross pay (take-home where there is none) over the coming year, from every income source
     * @param figures         the tax and Medicare figures in use
     * @param builtInFigures  true while they are the built-in ones
     */
    public record View(
            LocalDate today, LocalDate birthDate, LocalDate spouseBirthDate, String missing, Integer paychecksAYear, long payAYearCents,
            FiguresView figures, boolean builtInFigures, List<AccountView> accounts, List<ScenarioView> scenarios) {}

    /** The tax and Medicare figures in use: as changed, or the built-in ones. */
    public TaxFigures figures() {
        return retirement.taxFigures().map(RetirementService::readFigures).orElse(TaxFigures.Y2026);
    }

    /** Keeps changed figures; {@code null} goes back to the built-in ones. */
    public void setFigures(FiguresView figures) {
        if (figures == null) {
            retirement.setTaxFigures(null);
            return;
        }
        // Checked whole before it is kept.
        figures.toFigures();
        try {
            retirement.setTaxFigures(JSON.writeValueAsString(figures));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The tax figures could not be kept", e);
        }
    }

    private static TaxFigures readFigures(String json) {
        try {
            return JSON.readValue(json, FiguresView.class).toFigures();
        } catch (JsonProcessingException | RuntimeException e) {
            // Figures kept by an older version that no longer read are not lost: the built-in ones stand in.
            return TaxFigures.Y2026;
        }
    }

    public View view() {
        // Balances are worked out from many reads; one open connection serves them all.
        return jdbi.withHandle(handle -> gather());
    }

    /** True for an account whose money can be counted for retirement: investment or savings. */
    public static boolean countable(Account account) {
        AccountType type = AccountType.valueOf(account.type().toUpperCase(Locale.ROOT));
        return type == AccountType.INVESTMENT || type == AccountType.SAVINGS;
    }

    private View gather() {
        LocalDate today = pay.today();
        Optional<LocalDate> born = retirement.birthDate();
        Map<Long, String> taxTypes = retirement.taxTypes();
        Map<Long, Long> balances = new HashMap<>();
        for (AccountBalance balance : ledger.balancesOn(today)) {
            balances.put(balance.accountId(), balance.balanceCents());
        }
        // The coming year's, as what goes in every year: raises are not assumed (§5.11).
        Map<Long, Long> flows = ledger.flowsWithin(today.plusDays(1), today.plusYears(1));

        List<AccountView> views = new ArrayList<>();
        List<Pot> pots = new ArrayList<>();
        for (Account account : accounts.all()) {
            if (account.archived() || !countable(account)) {
                continue;
            }
            String taxType = taxTypes.get(account.id());
            long balance = balances.getOrDefault(account.id(), 0L);
            long yearlyIn = flows.getOrDefault(account.id(), 0L);
            views.add(new AccountView(account.id(), account.name(), account.color(), account.type(), account.subtypeLabel(), taxType,
                    balance, yearlyIn));
            if (taxType != null) {
                pots.add(new Pot(taxType(taxType), Money.ofCents(balance), Money.ofCents(yearlyIn)));
            }
        }

        String missing = born.isEmpty() ? NEEDS_BIRTH_DATE : pots.isEmpty() ? NEEDS_ACCOUNTS : null;
        Integer paychecks = paychecksAYear(today);
        long payAYear = payAYear(today);
        Optional<LocalDate> spouse = retirement.spouseBirthDate();
        TaxFigures figures = figures();
        List<ScenarioView> scenarios = new ArrayList<>();
        for (Scenario scenario : retirement.scenarios()) {
            Fields f = scenario.fields();
            Outcome outcome = null;
            TaxesView taxes = null;
            if (missing == null) {
                RetirementPlanner.Scenario flat = plannerScenario(f, f.taxBp(), f.healthAfterCents());
                Result result = RetirementPlanner.plan(born.get(), today, pots, flat);
                if ("worked_out".equals(f.taxes().mode())) {
                    taxes = taxes(f, figures, born.get(), spouse.orElse(null), today, pots, flat, result, payAYear);
                    if (taxes != null) {
                        // The rules' tax share and Medicare stand in for the flat rate and the health cost from 65.
                        result = RetirementPlanner.plan(born.get(), today, pots, plannerScenario(f, taxes.taxShareBp(), taxes.medicareCents()));
                    }
                }
                outcome = outcome(result, paychecks);
            }
            scenarios.add(new ScenarioView(scenario.id(), f.name(), f.salaryCents(), f.inflationBp(), f.withdrawalBp(), f.taxBp(),
                    f.healthBeforeCents(), f.healthAfterCents(), f.healthInflationBp(), f.growthBp(), f.growthLowBp(), f.growthHighBp(),
                    f.raiseBp(), f.extraMonthlyCents(), f.extraInto(), f.retireAge(), f.compared(), f.taxes(), f.incomes(), f.oneOffs(),
                    outcome, taxes));
        }
        return new View(today, born.orElse(null), spouse.orElse(null), missing, paychecks, payAYear, FiguresView.of(figures),
                retirement.taxFigures().isEmpty(), views, scenarios);
    }

    /**
     * Works a scenario's retirement out year by year with the tax rules, at its chosen age, or else the first that is
     * enough at the flat rate, or else {@link #ASSUMED_AGE}. {@code null} when that age is already past.
     */
    private static TaxesView taxes(Fields f, TaxFigures figures, LocalDate born, LocalDate spouseBorn, LocalDate today, List<Pot> pots,
            RetirementPlanner.Scenario flat, Result result, long payAYear) {
        Integer age = f.retireAge();
        String ageFrom = "chosen";
        if (age == null) {
            age = result.earliestAge();
            ageFrom = "earliest";
        }
        if (age == null) {
            age = ASSUMED_AGE;
            ageFrom = "assumed";
        }
        int firstAge = result.byAge().isEmpty() ? RetirementPlanner.LAST_AGE + 1 : result.byAge().getFirst().age();
        if (age < firstAge || age > RetirementPlanner.LAST_AGE) {
            return null;
        }
        Taxes settings = f.taxes();
        Balances balances = RetirementPlanner.balancesAt(born, today, pots, flat, age);
        TaxFigures.Status status = "joint".equals(settings.filing()) ? TaxFigures.Status.JOINT : TaxFigures.Status.SINGLE;
        Integer spouseGap = spouseBorn == null ? null : born.getYear() - spouseBorn.getYear();
        long workingMagi = settings.workingMagiCents() == null ? payAYear : settings.workingMagiCents();
        Drawdown.Setup setup = new Drawdown.Setup(
                figures, status, age, age - result.ageNow(), born.getYear() + age, born.getYear(), spouseGap, settings.medicarePeople(),
                balances.traditional(), balances.roth(), balances.taxable(),
                BasisPoints.of(f.growthBp()), BasisPoints.of(f.inflationBp()), BasisPoints.of(f.healthInflationBp()),
                Money.ofCents(f.salaryCents()), Money.ofCents(f.healthBeforeCents()),
                Money.ofCents(settings.partDPlanCents()), Money.ofCents(settings.supplementCents()),
                f.incomes().stream().map(i -> new Drawdown.Income(Money.ofCents(i.yearlyCents()), i.startAge(), i.socialSecurity(),
                        BasisPoints.of(i.countedBp()))).toList(),
                f.oneOffs().stream().map(o -> new RetirementPlanner.OneOff(Money.ofCents(o.amountCents()), o.age())).toList(),
                BasisPoints.of(settings.gainsShareBp()), BasisPoints.of(settings.stateTaxBp()), Money.ofCents(workingMagi),
                settings.magiOverrideCents() == null ? null : Money.ofCents(settings.magiOverrideCents()),
                strategy(settings.strategy()));
        Drawdown.Result drawdown = Drawdown.plan(setup);
        int taxShare = drawdown.taxShare() == null ? 0 : Math.min(drawdown.taxShare().value(), MOST_TAX_SHARE_BP);
        return new TaxesView(age, ageFrom, balances.traditional().cents(), balances.roth().cents(), balances.taxable().cents(), workingMagi,
                code(drawdown.chosen()), code(drawdown.cheapest()), !drawdown.chosen().equals(drawdown.cheapest()),
                drawdown.outcomes().stream().map(o -> new StrategyView(code(o.strategy()), label(o.strategy(), figures, status),
                        o.federal().cents(), o.state().cents(), o.medicare().cents(), o.surcharges().cents(), o.runsOutAt(),
                        o.leftAfterTax().cents(), o.shortfall().cents())).toList(),
                taxShare, medicareToday(drawdown.years(), figures, settings),
                drawdown.years().stream().map(RetirementService::yearView).toList());
    }

    /**
     * Medicare a year from 65 at today's premiums, for the planner's health cost from 65, which it raises by health
     * inflation itself: the bracket of the first year with Medicare, for each person who pays it.
     */
    private static long medicareToday(List<Drawdown.Year> years, TaxFigures figures, Taxes settings) {
        for (Drawdown.Year year : years) {
            if (!year.medicare().isEmpty()) {
                int bracket = year.medicare().getFirst().bracket();
                long monthly = figures.partB().get(bracket).cents() + figures.partD().get(bracket).cents()
                        + settings.partDPlanCents() + settings.supplementCents();
                return monthly * 12 * settings.medicarePeople();
            }
        }
        return 0;
    }

    private static YearView yearView(Drawdown.Year year) {
        return new YearView(year.age(), year.calendarYear(), year.need().cents(), year.otherIncome().cents(), year.fromTraditional().cents(),
                year.required().cents(), year.fromTaxable().cents(), year.fromRoth().cents(), year.toRoth().cents(), year.magi().cents(),
                year.taxedSocialSecurity().cents(), year.federal().cents(), year.state().cents(), year.bracket().value(),
                year.medicare().stream().map(m -> new MedicareView(m.age(), m.bracket(), m.lookBack().cents(), m.partB().cents(),
                        m.partD().cents(), m.plans().cents(), m.yearly().cents())).toList(),
                year.shortfall().cents(), year.traditionalLeft().cents(), year.rothLeft().cents(), year.taxableLeft().cents());
    }

    /** A strategy as kept: {@code none}, {@code bracket:N} or {@code medicare:N}. */
    static String code(Strategy strategy) {
        return switch (strategy.ceiling()) {
            case NONE -> "none";
            case BRACKET -> "bracket:" + strategy.index();
            case MEDICARE -> "medicare:" + strategy.index();
        };
    }

    /** A kept strategy; {@code null}, or one that no longer reads, for the cheapest. */
    static Strategy strategy(String code) {
        if (code == null) {
            return null;
        }
        if (code.equals("none")) {
            return new Strategy(Ceiling.NONE, 0);
        }
        String[] parts = code.split(":");
        if (parts.length != 2) {
            return null;
        }
        try {
            int index = Integer.parseInt(parts[1]);
            return switch (parts[0]) {
                case "bracket" -> new Strategy(Ceiling.BRACKET, index);
                case "medicare" -> new Strategy(Ceiling.MEDICARE, index);
                default -> null;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** What a strategy does, in words: "Fill the 12% bracket", "Stay under Medicare's $109,000". */
    static String label(Strategy strategy, TaxFigures figures, TaxFigures.Status status) {
        TaxFigures.Filing filing = figures.filing(status);
        return switch (strategy.ceiling()) {
            case NONE -> "Only what's required from traditional; taxable first, then Roth";
            case BRACKET -> "Fill the " + percent(filing.brackets().get(strategy.index()).rate().value()) + " bracket from traditional";
            case MEDICARE -> "Traditional up to Medicare's " + dollars(filing.medicareFrom().get(strategy.index()).cents()) + " limit";
        };
    }

    private static String percent(int bp) {
        return bp % 100 == 0 ? bp / 100 + "%" : String.format(Locale.ROOT, "%d.%02d%%", bp / 100, bp % 100);
    }

    private static String dollars(long cents) {
        return String.format(Locale.US, "$%,d", cents / 100);
    }

    /** Gross pay over the coming year from every income source, take-home where a paycheck has no gross. */
    private long payAYear(LocalDate today) {
        long total = 0;
        for (PayCalendar.Paycheck paycheck : pay.paychecks(today.plusDays(1), today.plusYears(1))) {
            total += paycheck.grossCents() != null ? paycheck.grossCents() : paycheck.netCents();
        }
        return total;
    }

    private static RetirementPlanner.Scenario plannerScenario(Fields fields, int taxBp, long healthAfterCents) {
        return new RetirementPlanner.Scenario(
                Money.ofCents(fields.salaryCents()),
                BasisPoints.of(fields.inflationBp()),
                BasisPoints.of(fields.withdrawalBp()),
                BasisPoints.of(taxBp),
                Money.ofCents(fields.healthBeforeCents()),
                Money.ofCents(healthAfterCents),
                BasisPoints.of(fields.healthInflationBp()),
                BasisPoints.of(fields.growthBp()),
                BasisPoints.of(fields.growthLowBp()),
                BasisPoints.of(fields.growthHighBp()),
                BasisPoints.of(fields.raiseBp()),
                Money.ofCents(fields.extraMonthlyCents()),
                taxType(fields.extraInto()),
                fields.retireAge(),
                fields.incomes().stream().map(i -> new RetirementPlanner.Income(Money.ofCents(i.yearlyCents()), i.startAge())).toList(),
                fields.oneOffs().stream().map(o -> new RetirementPlanner.OneOff(Money.ofCents(o.amountCents()), o.age())).toList());
    }

    private static Outcome outcome(Result result, Integer paychecks) {
        List<AgeView> byAge = result.byAge().stream()
                .map(point -> new AgeView(point.age(), point.projected().cents(), point.projectedLow().cents(), point.projectedHigh().cents(),
                        point.projectedThen().cents(), point.needed().cents(), point.enough()))
                .toList();
        Target target = result.target();
        TargetView targetView = null;
        if (target != null) {
            Early early = target.early();
            Long monthly = target.extraMonthly() == null ? null : target.extraMonthly().cents();
            // A year's worth (12 months) shared over the year's paychecks.
            Long perPaycheck = monthly == null || paychecks == null || paychecks == 0 ? null
                    : Money.ofCents(monthly).times(MONTHS.divide(BigDecimal.valueOf(paychecks), MathContext.DECIMAL128)).cents();
            targetView = new TargetView(target.age(), target.needed().cents(), target.projected().cents(), target.enough(),
                    target.shortfall().cents(), monthly, perPaycheck, target.reachable(), target.extraTaxable(),
                    early == null ? null : new EarlyView(early.years(), early.covered(), early.needed().cents(), early.taxable().cents()));
        }
        return new Outcome(result.earliestAge(), result.earliestLow(), result.earliestHigh(), result.ageNow(), byAge, targetView);
    }

    /** The primary source's regular paychecks in the year from tomorrow; {@code null} without a primary source. */
    private Integer paychecksAYear(LocalDate today) {
        Optional<Source> primary = income.all().stream().filter(Source::primary).findFirst();
        if (primary.isEmpty()) {
            return null;
        }
        return (int) pay.paychecks(today.plusDays(1), today.plusYears(1)).stream()
                .filter(PayCalendar.Paycheck::scheduled)
                .filter(paycheck -> paycheck.incomeSourceId() == primary.get().id())
                .count();
    }

    static TaxType taxType(String code) {
        return Inputs.choice(TaxType.class, code, "how its money is taxed");
    }
}
