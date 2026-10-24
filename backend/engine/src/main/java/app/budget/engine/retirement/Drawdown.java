package app.budget.engine.retirement;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.retirement.RetirementTax.Tax;
import app.budget.engine.retirement.TaxFigures.Filing;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Retirement year by year, taking money from whichever accounts keep tax and Medicare premiums lowest over a lifetime
 * (spec §5.12; Rachel, 2026-10-23: "make sure you decide which account my income comes from based on what will minimize
 * my tax burden"). Everything is in today's dollars.
 *
 * <p>Each year: what the year needs (the salary, health insurance before Medicare, and Medicare's premiums, set by the
 * income of two years before), less other income, comes first from any required minimum distribution, then from
 * traditional money up to a ceiling, then from taxable money, then Roth, and last from traditional money above the
 * ceiling. Traditional money taken to reach the ceiling beyond what the year needs is converted to Roth. Every ceiling
 * is tried: none (taxable first), the top of each tax bracket, and keeping income under each Medicare bracket; the one
 * that leaves the most after tax at the end, without running out sooner, is the one used. Before 60 only taxable money
 * is spent, as IRA and 401(k) money usually carries a penalty until 59½. An estimate for comparing, not tax advice.
 */
public final class Drawdown {

    private static final MathContext MATH = MathContext.DECIMAL64;
    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);
    private static final int SEARCH_STEPS = 48;

    private Drawdown() {}

    /** Other money coming in, a year in today's dollars, from an age on. */
    public record Income(Money yearly, int startAge, boolean socialSecurity, BasisPoints counted) {}

    /** How traditional money is taken: a ceiling it is taken up to each year, ahead of the other accounts. */
    public enum Ceiling {
        /** No ceiling: only what is required, then taxable money, then Roth. */
        NONE,
        /** Up to the top of a tax bracket, as taxable income. */
        BRACKET,
        /** Keeping income at or under where a Medicare income bracket starts. */
        MEDICARE
    }

    /**
     * @param index for {@link Ceiling#BRACKET} the bracket (0 the lowest); for {@link Ceiling#MEDICARE} the bracket
     *              kept under (1 the first with a surcharge)
     */
    public record Strategy(Ceiling ceiling, int index) {}

    /**
     * Everything a drawdown is worked out from, in today's dollars.
     *
     * @param retireAge        the first year of retirement
     * @param yearsToRetire    whole years from today to it
     * @param calendarYear     the calendar year of that birthday
     * @param birthYear        decides when required minimum distributions start
     * @param spouseGap        a spouse's age less one's own; {@code null} for none. A joint return counts a spouse of the same age without it
     * @param medicarePeople   how many pay Medicare premiums: 1, or 2 with a spouse
     * @param traditional      traditional money at retirement
     * @param growth           the expected growth a year, before inflation
     * @param healthInflation  how fast health costs and Medicare premiums rise a year
     * @param salary           what is wanted a year
     * @param healthBefore     health insurance a year before Medicare
     * @param partDPlan        a Part D drug plan, a month a person
     * @param supplement       Medigap or a Medicare Advantage plan, a month a person
     * @param gainsShare       how much of each dollar taken from taxable accounts is growth, taxed as a gain
     * @param stateRate        state income tax on income other than Social Security
     * @param workingMagi      income a year while still working, for Medicare's look back two years at the start
     * @param magiOverride     an income typed for every year in retirement instead of the one worked out; {@code null} to work it out
     * @param pinned           a strategy chosen instead of the cheapest; {@code null} for the cheapest
     */
    public record Setup(
            TaxFigures figures,
            TaxFigures.Status status,
            int retireAge,
            int yearsToRetire,
            int calendarYear,
            int birthYear,
            Integer spouseGap,
            int medicarePeople,
            Money traditional,
            Money roth,
            Money taxable,
            BasisPoints growth,
            BasisPoints inflation,
            BasisPoints healthInflation,
            Money salary,
            Money healthBefore,
            Money partDPlan,
            Money supplement,
            List<Income> incomes,
            List<RetirementPlanner.OneOff> oneOffs,
            BasisPoints gainsShare,
            BasisPoints stateRate,
            Money workingMagi,
            Money magiOverride,
            Strategy pinned) {

        public Setup {
            Objects.requireNonNull(figures);
            Objects.requireNonNull(status);
            incomes = List.copyOf(incomes);
            oneOffs = List.copyOf(oneOffs);
            if (medicarePeople < 1 || medicarePeople > 2) {
                throw new IllegalArgumentException("Medicare is for one person or two.");
            }
        }

        Filing filing() {
            return figures.filing(status);
        }

        /** A spouse's age less one's own, when there is a spouse; a joint return or a second person on Medicare has one. */
        Integer spouse() {
            if (spouseGap != null) {
                return spouseGap;
            }
            return status == TaxFigures.Status.JOINT || medicarePeople == 2 ? 0 : null;
        }
    }

    /**
     * One person's Medicare for a year.
     *
     * @param bracket   the Medicare income bracket, 0 the standard one
     * @param lookBack  the income it went by: from two years before
     * @param partB     Part B a month, with any income-related amount
     * @param partD     the Part D income-related amount a month
     * @param plans     the Part D plan and Medigap or Advantage, a month
     * @param yearly    all of it over the year
     */
    public record Medicare(int age, int bracket, Money lookBack, Money partB, Money partD, Money plans, Money yearly) {}

    /**
     * One year of retirement, in today's dollars.
     *
     * @param need            the salary, health insurance and Medicare for the year
     * @param otherIncome     Social Security, pensions and the rest
     * @param required        the required minimum distribution, part of {@code fromTraditional}
     * @param toRoth          traditional money converted to Roth beyond what the year needed
     * @param magi            adjusted gross income, which sets Medicare's premium two years on
     * @param shortfall       what the accounts couldn't cover
     */
    public record Year(
            int age,
            int calendarYear,
            Money need,
            Money otherIncome,
            Money fromTraditional,
            Money required,
            Money fromTaxable,
            Money fromRoth,
            Money toRoth,
            Money magi,
            Money taxedSocialSecurity,
            Money federal,
            Money state,
            BasisPoints bracket,
            List<Medicare> medicare,
            Money shortfall,
            Money traditionalLeft,
            Money rothLeft,
            Money taxableLeft) {}

    /**
     * One strategy over the whole of retirement.
     *
     * @param runsOutAt      the first age the accounts can't cover; {@code null} when they last to the end
     * @param leftAfterTax   at the end: traditional money after the tax of its last year, Roth and taxable money
     * @param surcharges     what Medicare's income-related amounts came to: the part of premiums income decides
     * @param shortfall      what the accounts couldn't cover, over all the years
     */
    public record Outcome(
            Strategy strategy, Money federal, Money state, Money medicare, Money surcharges, Integer runsOutAt, Money leftAfterTax,
            Money fromTraditional, Money shortfall) {

        /** Federal and state tax and Medicare's income-related amounts: what the order money is taken in changes. */
        public Money cost() {
            return federal.plus(state).plus(surcharges);
        }
    }

    /**
     * @param chosen     the strategy used: the cheapest, or the pinned one
     * @param cheapest   the one that leaves the most after tax without running out sooner
     * @param outcomes   every strategy tried, best first
     * @param years      the chosen one, year by year
     * @param taxShare   federal and state tax over retirement as a share of traditional money taken out; {@code null} when none is
     */
    public record Result(Strategy chosen, Strategy cheapest, List<Outcome> outcomes, List<Year> years, BasisPoints taxShare) {}

    /** The strategies tried: no ceiling, each bracket's top, and each Medicare bracket's start. */
    public static List<Strategy> strategies(Filing filing, List<Money> medicareFrom) {
        List<Strategy> strategies = new ArrayList<>();
        strategies.add(new Strategy(Ceiling.NONE, 0));
        for (int i = 0; i < RetirementTax.bracketTops(filing).size(); i++) {
            strategies.add(new Strategy(Ceiling.BRACKET, i));
        }
        for (int i = 1; i < medicareFrom.size(); i++) {
            strategies.add(new Strategy(Ceiling.MEDICARE, i));
        }
        return strategies;
    }

    public static Result plan(Setup setup) {
        Filing filing = setup.filing();
        List<Strategy> strategies = strategies(filing, filing.medicareFrom());
        List<Run> runs = new ArrayList<>();
        for (Strategy strategy : strategies) {
            runs.add(new Run(setup, strategy).go());
        }
        // Lasting longest comes first; then what is left after tax less anything not covered, which is the same as paying
        // least over the years; then what tax and Medicare's surcharges came to.
        Comparator<Run> best = Comparator
                .comparing((Run run) -> run.runsOutAt == null ? Integer.MAX_VALUE : run.runsOutAt).reversed()
                .thenComparing(run -> run.leftAfterTax().subtract(run.shortfall), Comparator.reverseOrder())
                .thenComparing(run -> run.federal.add(run.state).add(run.surcharges))
                .thenComparing(run -> strategies.indexOf(run.strategy));
        List<Run> ranked = runs.stream().sorted(best).toList();
        Strategy cheapest = ranked.getFirst().strategy;
        Strategy chosen = setup.pinned() != null && strategies.contains(setup.pinned()) ? setup.pinned() : cheapest;
        Run used = runs.get(strategies.indexOf(chosen));
        BigDecimal taken = used.fromTraditional;
        BasisPoints share = taken.signum() <= 0 ? null : BasisPoints.of(used.federal.add(used.state)
                .multiply(BigDecimal.valueOf(10_000)).divide(taken, 0, Money.ROUNDING).min(BigDecimal.valueOf(10_000)).intValueExact());
        return new Result(chosen, cheapest, ranked.stream().map(Run::outcome).toList(), used.years, share);
    }

    /** The age required minimum distributions start, by year of birth (SECURE 2.0). */
    public static int requiredFrom(int birthYear) {
        if (birthYear <= 1950) {
            return 72;
        }
        return birthYear <= 1959 ? 73 : 75;
    }

    /** The IRS Uniform Lifetime Table: what a balance is divided by for the year's required distribution, from 72. */
    private static final double[] DIVISORS = {
        27.4, 26.5, 25.5, 24.6, 23.7, 22.9, 22.0, 21.1, 20.2, 19.4, 18.5, 17.7, 16.8, 16.0, 15.2, 14.4, 13.7, 12.9, 12.2,
        11.5, 10.8, 10.1, 9.5, 8.9, 8.4, 7.8, 7.3, 6.8, 6.4
    };

    static BigDecimal divisor(int age) {
        int at = Math.min(Math.max(age, 72), 100) - 72;
        return BigDecimal.valueOf(DIVISORS[at]);
    }

    /** One strategy, run year by year. */
    private static final class Run {
        private final Setup setup;
        private final Strategy strategy;
        private final Filing filing;
        private final BigDecimal realGrowth;
        private final BigDecimal yearlyInflation;
        private final BigDecimal healthBeyond;
        private final List<Year> years = new ArrayList<>();
        private final List<BigDecimal> magiByAge = new ArrayList<>();
        private BigDecimal traditional;
        private BigDecimal roth;
        private BigDecimal taxable;
        private BigDecimal federal = BigDecimal.ZERO;
        private BigDecimal state = BigDecimal.ZERO;
        private BigDecimal medicare = BigDecimal.ZERO;
        private BigDecimal surcharges = BigDecimal.ZERO;
        private BigDecimal fromTraditional = BigDecimal.ZERO;
        private BigDecimal shortfall = BigDecimal.ZERO;
        private Integer runsOutAt;
        private BigDecimal lastMarginal = BigDecimal.ZERO;

        Run(Setup setup, Strategy strategy) {
            this.setup = setup;
            this.strategy = strategy;
            this.filing = setup.filing();
            BigDecimal inflation = BigDecimal.ONE.add(setup.inflation().toFraction());
            this.yearlyInflation = inflation;
            this.realGrowth = BigDecimal.ONE.add(setup.growth().toFraction()).divide(inflation, MATH);
            this.healthBeyond = BigDecimal.ONE.add(setup.healthInflation().toFraction()).divide(inflation, MATH);
            this.traditional = cents(setup.traditional());
            this.roth = cents(setup.roth());
            this.taxable = cents(setup.taxable());
        }

        Run go() {
            int requiredFrom = requiredFrom(setup.birthYear());
            for (int age = setup.retireAge(); age <= RetirementPlanner.LAST_AGE; age++) {
                year(age, requiredFrom);
            }
            return this;
        }

        BigDecimal leftAfterTax() {
            BigDecimal kept = BigDecimal.ONE.subtract(lastMarginal).subtract(setup.stateRate().toFraction()).max(BigDecimal.ZERO);
            return traditional.multiply(kept, MATH).add(roth).add(taxable);
        }

        Outcome outcome() {
            return new Outcome(strategy, money(federal), money(state), money(medicare), money(surcharges), runsOutAt, money(leftAfterTax()),
                    money(fromTraditional), money(shortfall));
        }

        private void year(int age, int requiredFrom) {
            int fromNow = setup.yearsToRetire() + age - setup.retireAge();
            int calendarYear = setup.calendarYear() + age - setup.retireAge();
            for (RetirementPlanner.OneOff oneOff : setup.oneOffs()) {
                if (oneOff.age() == age) {
                    taxable = taxable.add(cents(oneOff.amount()));
                }
            }
            taxable = taxable.max(BigDecimal.ZERO);
            BigDecimal health = healthBeyond.pow(Math.max(0, fromNow), MATH);
            Integer spouse = setup.spouse();
            int people65 = (age >= RetirementPlanner.MEDICARE_AGE ? 1 : 0)
                    + (spouse != null && setup.status() == TaxFigures.Status.JOINT && age + spouse >= RetirementPlanner.MEDICARE_AGE ? 1 : 0);
            RetirementTax.Year taxYear = new RetirementTax.Year(setup.figures(), setup.status(), calendarYear, people65,
                    BigDecimal.ONE.divide(yearlyInflation.pow(Math.max(0, fromNow), MATH), MATH));

            // What the year needs: the salary, health insurance until Medicare, and each person's Medicare from 65.
            List<Medicare> premiums = new ArrayList<>();
            BigDecimal need = cents(setup.salary());
            BigDecimal lookBack = lookBack(age);
            int bracket = medicareBracket(lookBack);
            List<Integer> ages = new ArrayList<>(List.of(age));
            if (setup.medicarePeople() == 2) {
                ages.add(age + (spouse == null ? 0 : spouse));
            }
            for (int personAge : ages) {
                if (personAge >= RetirementPlanner.MEDICARE_AGE) {
                    BigDecimal partB = cents(setup.figures().partB().get(bracket)).multiply(health, MATH);
                    BigDecimal partD = cents(setup.figures().partD().get(bracket)).multiply(health, MATH);
                    BigDecimal plans = cents(setup.partDPlan()).add(cents(setup.supplement())).multiply(health, MATH);
                    BigDecimal yearly = partB.add(partD).add(plans).multiply(TWELVE, MATH);
                    premiums.add(new Medicare(personAge, bracket, money(lookBack), money(partB), money(partD), money(plans), money(yearly)));
                    need = need.add(yearly);
                    medicare = medicare.add(yearly);
                    BigDecimal standard = cents(setup.figures().partB().getFirst()).multiply(health, MATH);
                    surcharges = surcharges.add(partB.subtract(standard).add(partD).multiply(TWELVE, MATH));
                } else if (personAge == age) {
                    need = need.add(cents(setup.healthBefore()).multiply(health, MATH));
                }
            }

            // Other income, and how much of it is taxed.
            BigDecimal socialSecurity = BigDecimal.ZERO;
            BigDecimal ordinaryOther = BigDecimal.ZERO;
            BigDecimal allOther = BigDecimal.ZERO;
            for (Income income : setup.incomes()) {
                if (income.startAge() <= age) {
                    BigDecimal yearly = cents(income.yearly());
                    allOther = allOther.add(yearly);
                    if (income.socialSecurity()) {
                        socialSecurity = socialSecurity.add(yearly);
                    } else {
                        ordinaryOther = ordinaryOther.add(yearly.multiply(income.counted().toFraction(), MATH));
                    }
                }
            }
            Money otherIncome = money(allOther);
            BigDecimal ss = socialSecurity;
            BigDecimal ordinary = ordinaryOther;
            BigDecimal other = allOther;
            BigDecimal needed = need;

            boolean early = age < RetirementPlanner.PENALTY_FREE_AGE;
            BigDecimal required = !early && age >= requiredFrom ? traditional.divide(divisor(age), MATH) : BigDecimal.ZERO;
            // Up to the ceiling: the most traditional money the strategy allows, never less than what is required.
            BigDecimal fromTrad = early ? BigDecimal.ZERO : filled(taxYear, ordinary, ss, required);
            BigDecimal fromTax = BigDecimal.ZERO;
            BigDecimal fromRothNow = BigDecimal.ZERO;
            BigDecimal toRothNow = BigDecimal.ZERO;
            BigDecimal shortNow = BigDecimal.ZERO;

            BigDecimal cash = cash(taxYear, ordinary, ss, other, fromTrad, BigDecimal.ZERO);
            if (cash.compareTo(need) >= 0) {
                // Beyond the year's need: what the ceiling took past what was required is converted; the rest is kept as taxable.
                BigDecimal spare = cash.subtract(need);
                toRothNow = spare.min(fromTrad.subtract(required).max(BigDecimal.ZERO));
                taxable = taxable.add(spare.subtract(toRothNow));
            } else {
                BigDecimal upToCeiling = fromTrad;
                fromTax = least(taxable, take -> cash(taxYear, ordinary, ss, other, upToCeiling, take).compareTo(needed) >= 0);
                BigDecimal afterTaxable = cash(taxYear, ordinary, ss, other, fromTrad, fromTax);
                BigDecimal still = need.subtract(afterTaxable).max(BigDecimal.ZERO);
                if (still.signum() > 0 && !early) {
                    fromRothNow = still.min(roth);
                    still = still.subtract(fromRothNow);
                }
                if (still.signum() > 0 && !early) {
                    // Last, traditional money above the ceiling, with the tax it brings.
                    BigDecimal base = fromTrad;
                    BigDecimal taxes = fromTax;
                    BigDecimal rothTaken = fromRothNow;
                    BigDecimal more = least(traditional.subtract(base), extra ->
                            cash(taxYear, ordinary, ss, other, base.add(extra), taxes).add(rothTaken).compareTo(needed) >= 0);
                    fromTrad = base.add(more);
                    still = need.subtract(cash(taxYear, ordinary, ss, other, fromTrad, fromTax).add(fromRothNow)).max(BigDecimal.ZERO);
                }
                if (still.compareTo(BigDecimal.valueOf(100)) > 0) {
                    shortNow = still;
                    shortfall = shortfall.add(still);
                    if (runsOutAt == null) {
                        runsOutAt = age;
                    }
                }
            }

            Tax tax = RetirementTax.of(taxYear, new RetirementTax.Income(ordinary.add(fromTrad), ss, gains(fromTax)));
            BigDecimal stateTax = stateTax(ordinary.add(fromTrad), gains(fromTax));
            federal = federal.add(tax.federal());
            state = state.add(stateTax);
            fromTraditional = fromTraditional.add(fromTrad);
            lastMarginal = tax.marginal().toFraction();
            magiByAge.add(tax.agi());

            traditional = traditional.subtract(fromTrad).max(BigDecimal.ZERO).multiply(realGrowth, MATH);
            roth = roth.subtract(fromRothNow).add(toRothNow).max(BigDecimal.ZERO).multiply(realGrowth, MATH);
            taxable = taxable.subtract(fromTax).max(BigDecimal.ZERO).multiply(realGrowth, MATH);

            years.add(new Year(age, calendarYear, money(need), otherIncome, money(fromTrad), money(required.min(fromTrad)), money(fromTax),
                    money(fromRothNow), money(toRothNow), money(tax.agi()), money(tax.taxedSocialSecurity()), money(tax.federal()),
                    money(stateTax), tax.marginal(), premiums, money(shortNow), money(traditional), money(roth), money(taxable)));
        }

        /** The income Medicare's premium goes by at an age: the year two years before, or while still working, or as typed. */
        private BigDecimal lookBack(int age) {
            if (setup.magiOverride() != null) {
                return cents(setup.magiOverride());
            }
            int index = age - 2 - setup.retireAge();
            return index < 0 ? cents(setup.workingMagi()) : magiByAge.get(index);
        }

        /** Which Medicare income bracket an income falls in: the last whose start it is more than. */
        private int medicareBracket(BigDecimal income) {
            int bracket = 0;
            List<Money> from = filing.medicareFrom();
            for (int i = 1; i < from.size(); i++) {
                if (income.compareTo(cents(from.get(i))) > 0) {
                    bracket = i;
                }
            }
            return bracket;
        }

        /** Traditional money taken up to the strategy's ceiling, at least {@code required} and no more than there is. */
        private BigDecimal filled(RetirementTax.Year taxYear, BigDecimal ordinary, BigDecimal ss, BigDecimal required) {
            BigDecimal least = required.min(traditional);
            if (strategy.ceiling() == Ceiling.NONE) {
                return least;
            }
            BigDecimal ceiling = strategy.ceiling() == Ceiling.BRACKET
                    ? cents(RetirementTax.bracketTops(filing).get(strategy.index()))
                    : cents(filing.medicareFrom().get(strategy.index()));
            Predicate<BigDecimal> within = take -> {
                Tax tax = RetirementTax.of(taxYear, new RetirementTax.Income(ordinary.add(take), ss, BigDecimal.ZERO));
                return (strategy.ceiling() == Ceiling.BRACKET ? tax.taxable() : tax.agi()).compareTo(ceiling) <= 0;
            };
            if (!within.test(least)) {
                return least;
            }
            // The most that stays within: halving between one that does and one that doesn't.
            BigDecimal low = least;
            BigDecimal high = traditional;
            if (within.test(high)) {
                return high;
            }
            for (int step = 0; step < SEARCH_STEPS && high.subtract(low).compareTo(BigDecimal.ONE) > 0; step++) {
                BigDecimal middle = low.add(high).divide(BigDecimal.TWO, MATH);
                if (within.test(middle)) {
                    low = middle;
                } else {
                    high = middle;
                }
            }
            return low;
        }

        /** The least amount up to {@code most} for which {@code enough} holds; {@code most} when none does. */
        private static BigDecimal least(BigDecimal most, Predicate<BigDecimal> enough) {
            if (most.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            if (!enough.test(most)) {
                return most;
            }
            BigDecimal low = BigDecimal.ZERO;
            BigDecimal high = most;
            for (int step = 0; step < SEARCH_STEPS && high.subtract(low).compareTo(BigDecimal.ONE) > 0; step++) {
                BigDecimal middle = low.add(high).divide(BigDecimal.TWO, MATH);
                if (enough.test(middle)) {
                    high = middle;
                } else {
                    low = middle;
                }
            }
            return high;
        }

        /** What the year has to spend: other income and what is taken out, less federal and state tax on it. */
        private BigDecimal cash(RetirementTax.Year taxYear, BigDecimal ordinary, BigDecimal ss, BigDecimal other, BigDecimal fromTrad, BigDecimal fromTax) {
            Tax tax = RetirementTax.of(taxYear, new RetirementTax.Income(ordinary.add(fromTrad), ss, gains(fromTax)));
            return other.add(fromTrad).add(fromTax).subtract(tax.federal()).subtract(stateTax(ordinary.add(fromTrad), gains(fromTax)));
        }

        private BigDecimal gains(BigDecimal fromTaxable) {
            return fromTaxable.multiply(setup.gainsShare().toFraction(), MATH);
        }

        /** State tax: a flat rate on income other than Social Security, which most states don't tax. */
        private BigDecimal stateTax(BigDecimal ordinary, BigDecimal gains) {
            return ordinary.add(gains).max(BigDecimal.ZERO).multiply(setup.stateRate().toFraction(), MATH);
        }
    }

    private static BigDecimal cents(Money money) {
        return new BigDecimal(money.cents());
    }

    private static Money money(BigDecimal cents) {
        return Money.ofCents(cents.setScale(0, Money.ROUNDING).longValueExact());
    }
}
