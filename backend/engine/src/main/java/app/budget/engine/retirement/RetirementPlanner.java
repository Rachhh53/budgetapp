package app.budget.engine.retirement;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.Roots;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * When the money set aside for retirement would be enough, and how much more it would take to
 * retire at a chosen age (spec §5.11; Rachel, 2026-10-19). Everything is in today's dollars:
 * each month is worked out as it would happen, compounding a month at a time, then brought back by
 * inflation. Each scenario is worked out at an expected growth and at a lower and a higher one
 * beside it (Rachel, 2026-10-20: "we expect 6% growth and it would show 6%, 4% and 8%").
 * Enough follows the 4% rule at the scenario's withdrawal rate, with the years before everything
 * has started (other income, Medicare) counted in full, and the years before 59½ covered by
 * taxable money alone. An estimate for comparing scenarios, not advice.
 */
public final class RetirementPlanner {

    /** How old the plan looks: nobody is asked to plan past it. */
    public static final int LAST_AGE = 100;

    /** Medicare starts here; the health cost changes from before to after. */
    public static final int MEDICARE_AGE = 65;

    /** IRA and 401(k) money taken out before 59½ usually carries a penalty: the year someone turns 59 counts as early. */
    public static final int PENALTY_FREE_AGE = 60;

    /** The most "extra a month" looked for: beyond it, the plan can't be reached by saving more. */
    public static final long MOST_EXTRA_CENTS = 100_000_000L;

    private static final MathContext MATH = MathContext.DECIMAL64;
    private static final int MONTHS_IN_YEAR = 12;
    private static final BigDecimal TWELVE = BigDecimal.valueOf(MONTHS_IN_YEAR);

    private RetirementPlanner() {}

    /** How money comes out in retirement: pre-tax (taxed then), Roth, or taxable and savings (in full). */
    public enum TaxType { TRADITIONAL, ROTH, TAXABLE }

    /**
     * One account counted for retirement. It grows at the scenario's rate.
     *
     * @param yearlyIn what goes into it over the coming year, less what comes out; it rises by the scenario's raise each year after
     */
    public record Pot(TaxType type, Money balance, Money yearlyIn) {
        public Pot {
            Objects.requireNonNull(type);
            Objects.requireNonNull(balance);
            Objects.requireNonNull(yearlyIn);
        }
    }

    /** Other money coming in, a year in today's dollars, from an age on (Social Security, a pension). */
    public record Income(Money yearly, int startAge) {}

    /** Money in (+) or out (−) once, at an age, in today's dollars. */
    public record OneOff(Money amount, int age) {}

    /**
     * One way retirement could go. Amounts are today's dollars.
     *
     * @param salary          what is wanted a year in retirement
     * @param withdrawal      the share taken out a year once everything has started (4% rule: 400)
     * @param tax             the tax on traditional money when it comes out
     * @param healthBefore    health insurance a year before Medicare; zero when not counted
     * @param healthAfter     health costs a year from Medicare on; zero when not counted
     * @param growth          the expected growth a year, for every account
     * @param growthLow       a slower growth shown beside it; at most {@code growth}
     * @param growthHigh      a faster growth shown beside it; at least {@code growth}
     * @param raise           how much more goes into every account each year than the year before; zero for none
     * @param extraMonthly    saved a month on top of what already goes in, rising with prices
     * @param retireAge       the age to work out the extra for; {@code null} for none
     */
    public record Scenario(
            Money salary,
            BasisPoints inflation,
            BasisPoints withdrawal,
            BasisPoints tax,
            Money healthBefore,
            Money healthAfter,
            BasisPoints healthInflation,
            BasisPoints growth,
            BasisPoints growthLow,
            BasisPoints growthHigh,
            BasisPoints raise,
            Money extraMonthly,
            TaxType extraInto,
            Integer retireAge,
            List<Income> incomes,
            List<OneOff> oneOffs) {

        public Scenario {
            incomes = List.copyOf(incomes);
            oneOffs = List.copyOf(oneOffs);
            if (withdrawal.value() <= 0) {
                throw new IllegalArgumentException("A withdrawal rate has to be more than 0%.");
            }
            if (growthLow.value() > growth.value() || growthHigh.value() < growth.value()) {
                throw new IllegalArgumentException("The lower growth can't be above the expected, nor the higher below it.");
            }
        }

        private boolean countsHealth() {
            return !healthBefore.isZero() || !healthAfter.isZero();
        }
    }

    /**
     * Projected (after tax) and needed at one whole age, and whether that age is enough at the expected growth.
     *
     * @param projected     at the expected growth, in today's dollars
     * @param projectedLow  at the lower growth, in today's dollars
     * @param projectedHigh at the higher growth, in today's dollars
     * @param projectedThen at the expected growth in the dollars of that year, before inflation is taken off
     */
    public record AgePoint(
            int age, Money projected, Money projectedLow, Money projectedHigh, Money projectedThen, Money needed, boolean enough) {}

    /**
     * Retiring before 60: how many of the years until then taxable money alone covers.
     *
     * @param years   the years from the age up to 60
     * @param covered how many of them, from the first, taxable money covers in full
     */
    public record Early(int years, int covered, Money needed, Money taxable) {
        public boolean ok() {
            return covered >= years;
        }
    }

    /**
     * The chosen age, at the expected growth.
     *
     * @param extraMonthly  more to save a month, in today's dollars, to make it enough; {@code null} when it already
     *                      is, or when no amount up to {@link #MOST_EXTRA_CENTS} would
     * @param reachable     false when no amount up to {@link #MOST_EXTRA_CENTS} a month would make it enough
     * @param extraTaxable  true before 60: the extra is counted as taxable money, the only kind that bridges the years until then
     * @param early         the years before 60; {@code null} from 60 on
     */
    public record Target(
            int age, Money needed, Money projected, boolean enough, Money shortfall, Money extraMonthly, boolean reachable,
            boolean extraTaxable, Early early) {}

    /**
     * @param earliestAge  the first whole age that is enough at the expected growth; {@code null} when none is, up to {@link #LAST_AGE}
     * @param earliestLow  the same at the lower growth
     * @param earliestHigh the same at the higher growth
     * @param ageNow       whole years old today
     * @param byAge        every whole age from the next birthday (or today, on one) to {@link #LAST_AGE}
     * @param target       the scenario's chosen age; {@code null} when it has none, or it is past or beyond the plan
     */
    public record Result(Integer earliestAge, Integer earliestLow, Integer earliestHigh, int ageNow, List<AgePoint> byAge, Target target) {}

    public static Result plan(LocalDate birthDate, LocalDate today, List<Pot> pots, Scenario scenario) {
        int monthsOld = (int) ChronoUnit.MONTHS.between(birthDate, today);
        int ageNow = monthsOld / MONTHS_IN_YEAR;
        int firstAge = monthsOld % MONTHS_IN_YEAR == 0 ? ageNow : ageNow + 1;
        Plan plan = new Plan(monthsOld, firstAge, pots, scenario);
        if (firstAge > LAST_AGE) {
            return new Result(null, null, null, ageNow, List.of(), null);
        }

        Simulation base = plan.simulate(BigDecimal.ZERO, scenario.extraInto(), scenario.growth());
        Simulation low = plan.simulate(BigDecimal.ZERO, scenario.extraInto(), scenario.growthLow());
        Simulation high = plan.simulate(BigDecimal.ZERO, scenario.extraInto(), scenario.growthHigh());
        List<AgePoint> byAge = new ArrayList<>();
        Integer earliest = null;
        Integer earliestLow = null;
        Integer earliestHigh = null;
        for (int age = firstAge; age <= LAST_AGE; age++) {
            BigDecimal needed = plan.needed(age);
            boolean enough = plan.enough(base, age, needed);
            if (enough && earliest == null) {
                earliest = age;
            }
            if (earliestLow == null && plan.enough(low, age, needed)) {
                earliestLow = age;
            }
            if (earliestHigh == null && plan.enough(high, age, needed)) {
                earliestHigh = age;
            }
            byAge.add(new AgePoint(age, cents(base.projected(age)), cents(low.projected(age)), cents(high.projected(age)),
                    cents(base.projectedThen(age)), cents(needed), enough));
        }

        Integer chosen = scenario.retireAge();
        Target target = chosen == null || chosen < firstAge || chosen > LAST_AGE ? null : plan.target(base, chosen);
        return new Result(earliest, earliestLow, earliestHigh, ageNow, byAge, target);
    }

    /**
     * Month by month to {@link #LAST_AGE}, at each whole age: after tax and taxable alone in today's dollars, and after tax
     * in the dollars of that year.
     */
    private record Simulation(int firstAge, BigDecimal[] afterTax, BigDecimal[] taxable, BigDecimal[] afterTaxThen) {
        BigDecimal projected(int age) {
            return afterTax[age - firstAge];
        }

        BigDecimal projectedThen(int age) {
            return afterTaxThen[age - firstAge];
        }

        BigDecimal taxable(int age) {
            return taxable[age - firstAge];
        }
    }

    /** One scenario against the accounts, with the sums that don't change between runs worked out once. */
    private static final class Plan {

        private final int monthsOld;
        private final int firstAge;
        private final List<Pot> pots;
        private final Scenario scenario;
        private final BigDecimal monthlyInflation;
        private final BigDecimal afterTaxShare;
        private final BigDecimal yearlyRaise;

        Plan(int monthsOld, int firstAge, List<Pot> pots, Scenario scenario) {
            this.monthsOld = monthsOld;
            this.firstAge = firstAge;
            this.pots = List.copyOf(pots);
            this.scenario = scenario;
            this.monthlyInflation = monthly(scenario.inflation());
            this.afterTaxShare = BigDecimal.ONE.subtract(scenario.tax().toFraction());
            this.yearlyRaise = BigDecimal.ONE.add(scenario.raise().toFraction());
        }

        /** The month (from today) of a birthday. */
        int monthOf(int age) {
            return age * MONTHS_IN_YEAR - monthsOld;
        }

        /**
         * @param extraMonthly more saved a month on top of the scenario's own, in today's cents
         * @param extraInto    where that extra goes
         * @param rate         the growth a year every account compounds at, a month at a time
         */
        Simulation simulate(BigDecimal extraMonthly, TaxType extraInto, BasisPoints rate) {
            BigDecimal growth = monthly(rate);
            List<Account> accounts = new ArrayList<>();
            for (Pot pot : pots) {
                accounts.add(new Account(pot.type(), new BigDecimal(pot.balance().cents()), new BigDecimal(pot.yearlyIn().cents()).divide(TWELVE, MATH)));
            }
            List<Share> ownExtra = sharesOf(accounts, scenario.extraInto());
            List<Share> moreExtra = sharesOf(accounts, extraInto);
            List<Share> oneOffs = sharesOf(accounts, TaxType.TAXABLE);
            BigDecimal ownMonthly = new BigDecimal(scenario.extraMonthly().cents());

            int ages = LAST_AGE - firstAge + 1;
            BigDecimal[] afterTax = new BigDecimal[ages];
            BigDecimal[] taxable = new BigDecimal[ages];
            BigDecimal[] afterTaxThen = new BigDecimal[ages];
            BigDecimal prices = BigDecimal.ONE;
            // Months 1–12 get the coming year's; each year after gets a raise more than the year before.
            BigDecimal raised = BigDecimal.ONE;
            int lastMonth = monthOf(LAST_AGE);
            for (int month = 0; month <= lastMonth; month++) {
                if (month > 0) {
                    prices = prices.multiply(monthlyInflation, MATH);
                    if (month > 1 && (month - 1) % MONTHS_IN_YEAR == 0) {
                        raised = raised.multiply(yearlyRaise, MATH);
                    }
                    for (Account account : accounts) {
                        account.balance = account.balance.multiply(growth, MATH).add(account.monthlyIn.multiply(raised, MATH), MATH);
                    }
                    add(ownExtra, ownMonthly.multiply(prices, MATH));
                    add(moreExtra, extraMonthly.multiply(prices, MATH));
                }
                if ((month + monthsOld) % MONTHS_IN_YEAR != 0) {
                    continue;
                }
                int age = (month + monthsOld) / MONTHS_IN_YEAR;
                for (OneOff oneOff : scenario.oneOffs()) {
                    if (oneOff.age() == age) {
                        add(oneOffs, new BigDecimal(oneOff.amount().cents()).multiply(prices, MATH));
                    }
                }
                BigDecimal total = BigDecimal.ZERO;
                BigDecimal taxableTotal = BigDecimal.ZERO;
                for (Account account : accounts) {
                    switch (account.type) {
                        case TRADITIONAL -> total = total.add(account.balance.multiply(afterTaxShare, MATH));
                        case ROTH -> total = total.add(account.balance);
                        case TAXABLE -> {
                            total = total.add(account.balance);
                            taxableTotal = taxableTotal.add(account.balance);
                        }
                    }
                }
                afterTax[age - firstAge] = total.divide(prices, MATH);
                taxable[age - firstAge] = taxableTotal.divide(prices, MATH);
                afterTaxThen[age - firstAge] = total;
            }
            return new Simulation(firstAge, afterTax, taxable, afterTaxThen);
        }

        /** One year's need at an age, in today's cents: salary + health − other income already started. */
        BigDecimal needIn(int age) {
            BigDecimal need = new BigDecimal(scenario.salary().cents());
            Money health = age < MEDICARE_AGE ? scenario.healthBefore() : scenario.healthAfter();
            if (!health.isZero()) {
                // Health costs rise faster than prices: by the difference between the two, from today.
                BigDecimal beyondPrices = monthly(scenario.healthInflation()).divide(monthlyInflation, MATH)
                        .pow(Math.max(0, monthOf(age)), MATH);
                need = need.add(new BigDecimal(health.cents()).multiply(beyondPrices, MATH));
            }
            for (Income income : scenario.incomes()) {
                if (income.startAge() <= age) {
                    need = need.subtract(new BigDecimal(income.yearly().cents()));
                }
            }
            return need;
        }

        /** What retiring at {@code age} needs saved, in today's cents (§5.11). */
        BigDecimal needed(int age) {
            int settled = age;
            if (scenario.countsHealth()) {
                settled = Math.max(settled, MEDICARE_AGE);
            }
            for (Income income : scenario.incomes()) {
                settled = Math.max(settled, income.startAge());
            }
            BigDecimal needed = BigDecimal.ZERO;
            for (int year = age; year < settled; year++) {
                needed = needed.add(needIn(year));
            }
            BigDecimal lasting = needIn(settled).max(BigDecimal.ZERO);
            needed = needed.add(lasting.divide(scenario.withdrawal().toFraction(), MATH));
            for (OneOff oneOff : scenario.oneOffs()) {
                if (oneOff.age() > age) {
                    needed = needed.subtract(new BigDecimal(oneOff.amount().cents()));
                }
            }
            return needed.max(BigDecimal.ZERO);
        }

        /** Before 60: how many of the years until then taxable money covers; {@code null} from 60 on. */
        Early early(Simulation simulation, int age) {
            if (age >= PENALTY_FREE_AGE) {
                return null;
            }
            BigDecimal taxable = simulation.taxable(age);
            BigDecimal spent = BigDecimal.ZERO;
            int covered = 0;
            boolean stillCovered = true;
            for (int year = age; year < PENALTY_FREE_AGE; year++) {
                spent = spent.add(needIn(year).max(BigDecimal.ZERO));
                if (stillCovered && spent.compareTo(taxable) <= 0) {
                    covered++;
                } else {
                    stillCovered = false;
                }
            }
            return new Early(PENALTY_FREE_AGE - age, covered, cents(spent), cents(taxable));
        }

        boolean enough(Simulation simulation, int age, BigDecimal needed) {
            Early early = early(simulation, age);
            return simulation.projected(age).compareTo(needed) >= 0 && (early == null || early.ok());
        }

        Target target(Simulation base, int age) {
            BigDecimal needed = needed(age);
            BigDecimal projected = base.projected(age);
            Early early = early(base, age);
            Money shortfall = cents(needed.subtract(projected).max(BigDecimal.ZERO));
            if (enough(base, age, needed)) {
                return new Target(age, cents(needed), cents(projected), true, shortfall, null, true, false, early);
            }
            // Before 60 only taxable money can bridge the years until then, so that is where the extra is counted.
            TaxType into = age < PENALTY_FREE_AGE ? TaxType.TAXABLE : scenario.extraInto();
            if (!enough(simulate(new BigDecimal(MOST_EXTRA_CENTS), into, scenario.growth()), age, needed)) {
                return new Target(age, cents(needed), cents(projected), false, shortfall, null, false, age < PENALTY_FREE_AGE, early);
            }
            // The least amount, to the cent, that is enough: halving between one that isn't and one that is.
            long notEnough = 0;
            long isEnough = MOST_EXTRA_CENTS;
            while (isEnough - notEnough > 1) {
                long middle = notEnough + (isEnough - notEnough) / 2;
                if (enough(simulate(new BigDecimal(middle), into, scenario.growth()), age, needed)) {
                    isEnough = middle;
                } else {
                    notEnough = middle;
                }
            }
            return new Target(age, cents(needed), cents(projected), false, shortfall, Money.ofCents(isEnough), true,
                    age < PENALTY_FREE_AGE, early);
        }

        /** The accounts of one kind, each with its share of money put into that kind (by balance; evenly when all are empty). */
        private static List<Share> sharesOf(List<Account> accounts, TaxType type) {
            List<Account> ofType = accounts.stream().filter(account -> account.type == type).toList();
            if (ofType.isEmpty()) {
                Account added = new Account(type, BigDecimal.ZERO, BigDecimal.ZERO);
                accounts.add(added);
                ofType = List.of(added);
            }
            BigDecimal held = ofType.stream().map(account -> account.balance.max(BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
            List<Share> shares = new ArrayList<>();
            for (Account account : ofType) {
                BigDecimal share = held.signum() == 0
                        ? BigDecimal.ONE.divide(BigDecimal.valueOf(ofType.size()), MATH)
                        : account.balance.max(BigDecimal.ZERO).divide(held, MATH);
                shares.add(new Share(account, share));
            }
            return shares;
        }

        private static void add(List<Share> shares, BigDecimal amount) {
            if (amount.signum() == 0) {
                return;
            }
            for (Share share : shares) {
                share.account.balance = share.account.balance.add(amount.multiply(share.part, MATH), MATH);
            }
        }
    }

    /** An account as the months pass, in dollars of that month (cents). Every account grows at the scenario's rate. */
    private static final class Account {
        final TaxType type;
        BigDecimal balance;
        /** What goes in a month over the coming year, before any raise. */
        final BigDecimal monthlyIn;

        Account(TaxType type, BigDecimal balance, BigDecimal monthlyIn) {
            this.type = type;
            this.balance = balance;
            this.monthlyIn = monthlyIn;
        }
    }

    private record Share(Account account, BigDecimal part) {}

    /** 1 + a yearly rate, as a month's: its 12th root, so 12 months compounded come to the yearly rate exactly. */
    private static BigDecimal monthly(BasisPoints yearly) {
        return Roots.nth(BigDecimal.ONE.add(yearly.toFraction()), MONTHS_IN_YEAR).round(MATH);
    }

    private static Money cents(BigDecimal cents) {
        return Money.ofCents(cents.setScale(0, Money.ROUNDING).longValueExact());
    }
}
