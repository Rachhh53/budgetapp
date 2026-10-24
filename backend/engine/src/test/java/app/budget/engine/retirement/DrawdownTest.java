package app.budget.engine.retirement;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.retirement.Drawdown.Ceiling;
import app.budget.engine.retirement.Drawdown.Income;
import app.budget.engine.retirement.Drawdown.Outcome;
import app.budget.engine.retirement.Drawdown.Result;
import app.budget.engine.retirement.Drawdown.Setup;
import app.budget.engine.retirement.Drawdown.Strategy;
import app.budget.engine.retirement.Drawdown.Year;
import app.budget.engine.retirement.TaxFigures.Status;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Taking money out in retirement so tax and Medicare cost least (spec §5.12). No growth and no inflation unless said, so
 * each year can be worked by hand; 2030 on, so there is no senior deduction.
 */
class DrawdownTest {

    private static Money dollars(long dollars) {
        return Money.ofCents(dollars * 100);
    }

    private static Setup setup(int retireAge, long traditional, long roth, long taxable, long salary, Strategy pinned) {
        return setup(retireAge, traditional, roth, taxable, salary, pinned, List.of(), 0, Status.SINGLE, 1);
    }

    private static Setup setup(int retireAge, long traditional, long roth, long taxable, long salary, Strategy pinned,
            List<Income> incomes, long workingMagi, Status status, int people) {
        return new Setup(TaxFigures.Y2026, status, retireAge, 0, 2030, 1960, null, people,
                dollars(traditional), dollars(roth), dollars(taxable),
                BasisPoints.of(0), BasisPoints.of(0), BasisPoints.of(0),
                dollars(salary), Money.ZERO, Money.ZERO, Money.ZERO, incomes, List.of(),
                BasisPoints.of(0), BasisPoints.of(0), dollars(workingMagi), null, pinned);
    }

    @Test
    void rothIsKeptForLastWhileTaxableMoneyLasts() {
        Result result = Drawdown.plan(setup(66, 0, 500_000, 500_000, 40_000, new Strategy(Ceiling.NONE, 0)));

        Year first = result.years().getFirst();
        assertThat(first.fromRoth()).isEqualTo(Money.ZERO);
        assertThat(first.fromTaxable().cents()).isPositive();
        // Gains are none of it here, so no tax: the salary and Medicare's standard $202.90 a month.
        assertThat(first.federal()).isEqualTo(Money.ZERO);
        assertThat(first.need()).isEqualTo(dollars(40_000).plus(Money.ofCents(20_290 * 12)));
    }

    @Test
    void fillingTheTwelvePercentBracketConvertsWhatTheYearDoesNotNeedToRoth() {
        Result result = Drawdown.plan(setup(66, 500_000, 0, 500_000, 30_000, new Strategy(Ceiling.BRACKET, 1)));

        Year first = result.years().getFirst();
        // Taxable income up to the 12% top of $50,400, after $16,100 + $2,050 of deductions: $68,550 taken.
        assertThat(first.fromTraditional().cents()).isBetween(6_855_000L - 2, 6_855_000L);
        // Tax $1,240 + $4,560; the year needs $30,000 + $2,434.80; the rest goes to Roth.
        assertThat(first.federal().cents()).isBetween(580_000L - 1, 580_000L);
        assertThat(first.toRoth().cents()).isBetween(3_031_520L - 3, 3_031_520L);
        assertThat(first.fromTaxable()).isEqualTo(Money.ZERO);
        assertThat(first.bracket().value()).isEqualTo(1200);
    }

    @Test
    void theStrategyUsedIsTheOneThatLeavesTheMostAfterTax() {
        // 6% growth and 3% inflation, so the money lasts and the strategies differ only in what they pay.
        Setup lasting = new Setup(TaxFigures.Y2026, Status.SINGLE, 62, 0, 2030, 1960, null, 1,
                dollars(1_500_000), dollars(100_000), dollars(300_000), BasisPoints.of(600), BasisPoints.of(300), BasisPoints.of(300),
                dollars(60_000), Money.ZERO, Money.ZERO, Money.ZERO, List.of(), List.of(),
                BasisPoints.of(5000), BasisPoints.of(0), Money.ZERO, null, null);
        Result result = Drawdown.plan(lasting);

        assertThat(result.chosen()).isEqualTo(result.cheapest());
        List<Outcome> outcomes = result.outcomes();
        assertThat(outcomes).hasSize(1 + 6 + 5);
        for (Outcome outcome : outcomes) {
            assertThat(outcomes.getFirst().leftAfterTax()).isGreaterThanOrEqualTo(outcome.leftAfterTax());
        }
        assertThat(outcomes.getFirst().runsOutAt()).isNull();
        // With this much traditional money, taking only what is required, late, leaves less than spreading it out.
        Outcome none = outcomes.stream().filter(outcome -> outcome.strategy().ceiling() == Ceiling.NONE).findFirst().orElseThrow();
        assertThat(none.leftAfterTax()).isLessThan(outcomes.getFirst().leftAfterTax());
        assertThat(result.chosen().ceiling()).isNotEqualTo(Ceiling.NONE);
    }

    @Test
    void medicareGoesByTheIncomeOfTwoYearsBefore() {
        Result result = Drawdown.plan(setup(65, 0, 0, 2_000_000, 40_000, new Strategy(Ceiling.NONE, 0), List.of(), 300_000, Status.SINGLE, 1));

        // At 65 and 66 the look back is to years still working: $300,000 is the fifth bracket, $649.20 for Part B.
        assertThat(result.years().get(0).medicare().getFirst().bracket()).isEqualTo(4);
        assertThat(result.years().get(1).medicare().getFirst().partB()).isEqualTo(Money.ofCents(64_920));
        assertThat(result.years().get(1).medicare().getFirst().partD()).isEqualTo(Money.ofCents(8_330));
        // At 67 it is the income of 65 in retirement: nothing taxed, the standard premium.
        assertThat(result.years().get(2).medicare().getFirst().bracket()).isZero();
    }

    @Test
    void twoPeopleEachPayFromTheirOwn65() {
        Setup setup = new Setup(TaxFigures.Y2026, Status.JOINT, 65, 0, 2030, 1960, -2, 2,
                Money.ZERO, Money.ZERO, dollars(2_000_000), BasisPoints.of(0), BasisPoints.of(0), BasisPoints.of(0),
                dollars(50_000), dollars(9_000), Money.ZERO, Money.ZERO, List.of(), List.of(),
                BasisPoints.of(0), BasisPoints.of(0), Money.ZERO, null, new Strategy(Ceiling.NONE, 0));

        List<Year> years = Drawdown.plan(setup).years();

        // At 65 the spouse is 63: one premium. At 67, two.
        assertThat(years.get(0).medicare()).hasSize(1);
        assertThat(years.get(2).medicare()).hasSize(2);
    }

    @Test
    void requiredDistributionsStartAt75ForThoseBornIn1960() {
        Result result = Drawdown.plan(setup(70, 1_000_000, 0, 5_000_000, 40_000, new Strategy(Ceiling.NONE, 0)));

        assertThat(result.years().get(4).required()).isEqualTo(Money.ZERO);
        // $1,000,000 ÷ 24.6 at 75.
        assertThat(result.years().get(5).required().cents()).isBetween(4_065_040L - 1, 4_065_041L);
        assertThat(Drawdown.requiredFrom(1955)).isEqualTo(73);
    }

    @Test
    void beforeSixtyOnlyTaxableMoneyIsSpent() {
        Result result = Drawdown.plan(setup(55, 2_000_000, 0, 0, 40_000, new Strategy(Ceiling.NONE, 0)));

        assertThat(result.outcomes().stream().filter(outcome -> outcome.strategy().ceiling() == Ceiling.NONE).findFirst().orElseThrow()
                .runsOutAt()).isEqualTo(55);
        assertThat(result.years().get(5).fromTraditional().cents()).isPositive();
    }

    @Test
    void socialSecurityAndOtherIncomeCountAsFarAsTheyAreTaxed() {
        List<Income> incomes = List.of(
                new Income(dollars(30_000), 66, true, BasisPoints.of(10_000)),
                new Income(dollars(12_000), 66, false, BasisPoints.of(0)));
        Result result = Drawdown.plan(setup(66, 0, 0, 1_000_000, 50_000, new Strategy(Ceiling.NONE, 0), incomes, 0, Status.SINGLE, 1));

        Year first = result.years().getFirst();
        // Untaxed income and nothing else taxed: provisional $15,000, so no Social Security is taxed.
        assertThat(first.taxedSocialSecurity()).isEqualTo(Money.ZERO);
        assertThat(first.magi()).isEqualTo(Money.ZERO);
        assertThat(first.otherIncome()).isEqualTo(dollars(42_000));
    }

    @Test
    void runningOutIsSaidAndCountsAgainstAStrategy() {
        Result result = Drawdown.plan(setup(66, 100_000, 0, 100_000, 50_000, null));

        assertThat(result.outcomes().getFirst().runsOutAt()).isNotNull();
        assertThat(result.years().stream().anyMatch(year -> year.shortfall().cents() > 0)).isTrue();
    }
}
