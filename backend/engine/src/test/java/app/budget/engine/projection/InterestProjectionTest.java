package app.budget.engine.projection;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.interest.InterestTerms;
import app.budget.engine.interest.InterestTerms.RateChange;
import app.budget.engine.schedule.PayPeriod;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Interest inside the balance projection (spec §5.4), with pay periods Oct 1–14, Oct 15–31, Nov 1–14 and Nov 15–30. */
class InterestProjectionTest {

    private static final List<PayPeriod> PERIODS = List.of(
            period("2026-10-01", "2026-10-14"),
            period("2026-10-15", "2026-10-31"),
            period("2026-11-01", "2026-11-14"),
            period("2026-11-15", "2026-11-30"));

    private static final InterestTerms LAST_DAY_AT_410 =
            new InterestTerms(31, List.of(new RateChange(date("2026-01-01"), BasisPoints.of(410))));

    private static final InterestTerms GROWING_AT_7 =
            InterestTerms.growingDaily(List.of(new RateChange(date("2026-01-01"), BasisPoints.of(700))));

    @Test
    void assumedGrowthBuildsUpEveryDaySoEveryPeriodHasItsShare() {
        Projection projection = BalanceProjector.project(
                List.of(anchor("2026-10-01", 4_000_000)), List.of(), PERIODS, GROWING_AT_7);

        // Nothing on the day of the balance, which already holds what came before; then every day after it.
        assertThat(projection.interest().getFirst().date()).isEqualTo(date("2026-10-02"));
        assertThat(projection.interest()).hasSize(60);
        // 40,000.00 × ((1.07)^(1/365) − 1) = 7.415… a day: whole cents are added, the rest carried.
        assertThat(projection.interest().getFirst().amount()).isEqualTo(cents(741));
        assertThat(projection.balances()).allSatisfy(period -> assertThat(period.in().isZero()).isFalse());
        // Thirteen days of it in the first period (the balance's own day has none): 13 × 7.42… = 96.50.
        assertThat(projection.balances().get(0).in()).isEqualTo(cents(9650));
        // Seventeen in the second, each on a balance that holds the days before.
        assertThat(projection.balances().get(1).in().cents()).isBetween(12_630L, 12_660L);
    }

    @Test
    void aYearOfDailyGrowthComesToTheYearlyRate() {
        Projection projection = BalanceProjector.project(
                List.of(anchor("2026-10-01", 4_000_000)), List.of(), List.of(period("2026-10-01", "2027-10-01")), GROWING_AT_7);

        // 365 days at 7% a year on 40,000.00 is 2,800.00, less the fraction of a cent still being carried.
        assertThat(projection.balances().getFirst().ending()).isEqualTo(cents(4_279_999));
    }

    @Test
    void aSmallBalanceIsNotRoundedUpIntoGrowingFaster() {
        Projection projection = BalanceProjector.project(
                List.of(anchor("2026-10-01", 10_000)), List.of(), List.of(period("2026-10-01", "2027-10-01")), GROWING_AT_7);

        // 100.00 earns under two cents a day. A year still comes to 7.00 (less the fraction being carried), not two cents for every day.
        assertThat(projection.balances().getFirst().ending()).isEqualTo(cents(10_699));
    }

    @Test
    void aBalanceEnteredLaterAlreadyHoldsTheGrowthBeforeIt() {
        Projection projection = BalanceProjector.project(
                List.of(anchor("2026-10-01", 4_000_000), anchor("2026-10-20", 4_100_000)), List.of(), PERIODS, GROWING_AT_7);

        // Nothing is added on the day of the second balance, and the days after it grow on that balance.
        assertThat(projection.interest()).extracting(InterestCredit::date).doesNotContain(date("2026-10-20"));
        assertThat(projection.interest().stream().filter(credit -> credit.date().equals(date("2026-10-21"))).findFirst().orElseThrow().balance())
                .isEqualTo(cents(4_100_000));
    }

    @Test
    void interestIsCreditedOnTheCreditDayAndCountsAsMoneyIn() {
        Projection projection = BalanceProjector.project(
                List.of(anchor("2026-10-01", 1_000_000)), List.of(), PERIODS, LAST_DAY_AT_410);

        assertThat(projection.interest()).extracting(InterestCredit::date).containsExactly(date("2026-10-31"), date("2026-11-30"));
        assertThat(projection.interest().getFirst().amount()).isEqualTo(cents(3354));
        assertThat(projection.interest().getFirst().balance()).isEqualTo(cents(1_000_000));

        PeriodBalance secondHalfOfOctober = projection.balances().get(1);
        assertThat(secondHalfOfOctober.in()).isEqualTo(cents(3354));
        assertThat(secondHalfOfOctober.ending()).isEqualTo(cents(1_003_354));
    }

    @Test
    void laterMonthsEarnInterestOnEarlierInterest() {
        Projection projection = BalanceProjector.project(
                List.of(anchor("2026-10-01", 1_000_000)), List.of(), PERIODS, LAST_DAY_AT_410);

        // 10,033.54 × 0.0033540… = 33.65
        assertThat(projection.interest().get(1).amount()).isEqualTo(cents(3365));
        assertThat(projection.balances().getLast().ending()).isEqualTo(cents(1_006_719));
    }

    @Test
    void interestIsWorkedOutOnTheBalanceTheCreditDayStartsWith() {
        List<Movement> movements = List.of(
                new Movement(date("2026-10-20"), cents(500_000), false),
                // Arrives on the credit day itself, so it has not earned anything yet.
                new Movement(date("2026-10-31"), cents(900_000), false));

        Projection projection = BalanceProjector.project(
                List.of(anchor("2026-10-01", 1_000_000)), movements, PERIODS, LAST_DAY_AT_410);

        assertThat(projection.interest().getFirst().balance()).isEqualTo(cents(1_500_000));
        // 15,000.00 × 0.0033540… = 50.31
        assertThat(projection.interest().getFirst().amount()).isEqualTo(cents(5031));
    }

    @Test
    void aBalanceEnteredLaterReplacesTheEstimate() {
        Projection projection = BalanceProjector.project(
                List.of(anchor("2026-10-01", 1_000_000), anchor("2026-11-02", 1_003_400)), List.of(), PERIODS, LAST_DAY_AT_410);

        // October's estimate is still on record, but November starts from the real number.
        assertThat(projection.interest().getFirst().amount()).isEqualTo(cents(3354));
        PeriodBalance november = projection.balances().get(2);
        assertThat(november.entered()).isTrue();
        assertThat(november.starting()).isEqualTo(cents(1_003_400));
        assertThat(projection.interest().get(1).balance()).isEqualTo(cents(1_003_400));
    }

    @Test
    void aBalanceEnteredOnTheCreditDayAlreadyHoldsThatMonthsInterest() {
        Projection projection = BalanceProjector.project(
                List.of(anchor("2026-10-01", 1_000_000), anchor("2026-10-31", 1_003_400)), List.of(), PERIODS, LAST_DAY_AT_410);

        // Still on record, worked out on what October was projected to hold, so a bucket gets its share…
        InterestCredit october = projection.interest().getFirst();
        assertThat(october.date()).isEqualTo(date("2026-10-31"));
        assertThat(october.inEnteredBalance()).isTrue();
        assertThat(october.balance()).isEqualTo(cents(1_000_000));
        assertThat(october.amount()).isEqualTo(cents(3354));

        // …but the account holds what was typed in, not that plus the estimate.
        PeriodBalance secondHalfOfOctober = projection.balances().get(1);
        assertThat(secondHalfOfOctober.in()).isEqualTo(cents(0));
        assertThat(secondHalfOfOctober.ending()).isEqualTo(cents(1_003_400));
        assertThat(projection.interest().get(1).inEnteredBalance()).isFalse();
        assertThat(projection.interest().get(1).balance()).isEqualTo(cents(1_003_400));
    }

    @Test
    void nothingIsWorkedOutForACreditDayThatIsTheFirstBalanceEntered() {
        Projection projection = BalanceProjector.project(
                List.of(anchor("2026-10-31", 1_000_000)), List.of(), PERIODS, LAST_DAY_AT_410);

        assertThat(projection.interest()).extracting(InterestCredit::date).containsExactly(date("2026-11-30"));
        assertThat(projection.balances().get(1).ending()).isEqualTo(cents(1_000_000));
    }

    @Test
    void nothingIsCreditedBeforeARateTakesEffect() {
        InterestTerms fromNovember = new InterestTerms(31, List.of(new RateChange(date("2026-11-01"), BasisPoints.of(410))));

        Projection projection = BalanceProjector.project(
                List.of(anchor("2026-10-01", 1_000_000)), List.of(), PERIODS, fromNovember);

        assertThat(projection.interest()).extracting(InterestCredit::date).containsExactly(date("2026-11-30"));
    }

    @Test
    void aRateChangeAppliesFromItsDate() {
        InterestTerms terms = new InterestTerms(31, List.of(
                new RateChange(date("2026-01-01"), BasisPoints.of(410)),
                new RateChange(date("2026-11-15"), BasisPoints.of(0))));

        Projection projection = BalanceProjector.project(List.of(anchor("2026-10-01", 1_000_000)), List.of(), PERIODS, terms);

        assertThat(projection.interest()).hasSize(1);
        assertThat(projection.balances().getLast().ending()).isEqualTo(cents(1_003_354));
    }

    @Test
    void anAccountWithoutInterestTermsProjectsAsBefore() {
        Projection projection = BalanceProjector.project(List.of(anchor("2026-10-01", 1_000_000)), List.of(), PERIODS, null);

        assertThat(projection.interest()).isEmpty();
        assertThat(projection.balances().getLast().ending()).isEqualTo(cents(1_000_000));
    }

    @Test
    void endingStillEqualsStartingPlusInMinusOut() {
        List<Movement> movements = List.of(
                new Movement(date("2026-10-03"), cents(-25_000), false), new Movement(date("2026-11-20"), cents(40_000), false));

        for (PeriodBalance balance : BalanceProjector.project(
                List.of(anchor("2026-10-01", 1_000_000)), movements, PERIODS, LAST_DAY_AT_410).balances()) {
            assertThat(balance.ending()).isEqualTo(balance.starting().plus(balance.in()).minus(balance.out()));
        }
    }

    private static PayPeriod period(String start, String end) {
        return new PayPeriod(date(start), date(end), date(start));
    }

    private static BalanceAnchor anchor(String day, long cents) {
        return new BalanceAnchor(date(day), cents(cents));
    }

    private static Money cents(long cents) {
        return Money.ofCents(cents);
    }

    private static LocalDate date(String text) {
        return LocalDate.parse(text);
    }
}
