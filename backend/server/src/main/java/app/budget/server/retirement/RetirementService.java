package app.budget.server.retirement;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.retirement.RetirementPlanner;
import app.budget.engine.retirement.RetirementPlanner.Early;
import app.budget.engine.retirement.RetirementPlanner.Pot;
import app.budget.engine.retirement.RetirementPlanner.Result;
import app.budget.engine.retirement.RetirementPlanner.Target;
import app.budget.engine.retirement.RetirementPlanner.TaxType;
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
            List<Income> incomes,
            List<OneOff> oneOffs,
            Outcome outcome) {}

    /**
     * @param missing         {@link #NEEDS_BIRTH_DATE} or {@link #NEEDS_ACCOUNTS} while something is still needed; {@code null} once not
     * @param paychecksAYear  the primary source's paychecks in the coming year; {@code null} without one
     */
    public record View(
            LocalDate today, LocalDate birthDate, String missing, Integer paychecksAYear, List<AccountView> accounts,
            List<ScenarioView> scenarios) {}

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
        List<ScenarioView> scenarios = new ArrayList<>();
        for (Scenario scenario : retirement.scenarios()) {
            Outcome outcome = missing != null ? null : outcome(RetirementPlanner.plan(born.get(), today, pots, plannerScenario(scenario.fields())), paychecks);
            Fields f = scenario.fields();
            scenarios.add(new ScenarioView(scenario.id(), f.name(), f.salaryCents(), f.inflationBp(), f.withdrawalBp(), f.taxBp(),
                    f.healthBeforeCents(), f.healthAfterCents(), f.healthInflationBp(), f.growthBp(), f.growthLowBp(), f.growthHighBp(),
                    f.raiseBp(), f.extraMonthlyCents(), f.extraInto(), f.retireAge(), f.compared(), f.incomes(), f.oneOffs(), outcome));
        }
        return new View(today, born.orElse(null), missing, paychecks, views, scenarios);
    }

    private static RetirementPlanner.Scenario plannerScenario(Fields fields) {
        return new RetirementPlanner.Scenario(
                Money.ofCents(fields.salaryCents()),
                BasisPoints.of(fields.inflationBp()),
                BasisPoints.of(fields.withdrawalBp()),
                BasisPoints.of(fields.taxBp()),
                Money.ofCents(fields.healthBeforeCents()),
                Money.ofCents(fields.healthAfterCents()),
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
