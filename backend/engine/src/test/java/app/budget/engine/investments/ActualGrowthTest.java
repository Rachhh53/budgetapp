package app.budget.engine.investments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import app.budget.engine.Money;
import app.budget.engine.investments.ActualGrowth.Flow;
import app.budget.engine.investments.ActualGrowth.Result;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What an investment really grew by a year, from two entered balances (spec §5.5). */
class ActualGrowthTest {

    private static final LocalDate JAN_1 = LocalDate.of(2025, 1, 1);
    private static final LocalDate A_YEAR_LATER = LocalDate.of(2026, 1, 1);

    @Test
    void aYearWithNothingPutInIsItsOwnRate() {
        // $10,000 → $10,700 over exactly 365 days: 7.00% a year.
        Result result = ActualGrowth.between(JAN_1, Money.ofCents(1_000_000), A_YEAR_LATER, Money.ofCents(1_070_000), List.of());
        assertThat(result.growth()).isEqualTo(Money.ofCents(70_000));
        assertThat(result.yearlyRateBp()).isEqualTo(700);
    }

    @Test
    void moneyPutInIsNotGrowth() {
        // $1,000 put in on the first day and the balance rose by exactly that: 0%.
        Result result = ActualGrowth.between(
                JAN_1, Money.ofCents(1_000_000), A_YEAR_LATER, Money.ofCents(1_100_000),
                List.of(new Flow(JAN_1, Money.ofCents(100_000))));
        assertThat(result.growth()).isEqualTo(Money.ZERO);
        assertThat(result.yearlyRateBp()).isZero();
    }

    @Test
    void aLossIsBelowZero() {
        Result result = ActualGrowth.between(JAN_1, Money.ofCents(1_000_000), A_YEAR_LATER, Money.ofCents(900_000), List.of());
        assertThat(result.growth()).isEqualTo(Money.ofCents(-100_000));
        assertThat(result.yearlyRateBp()).isEqualTo(-1_000);
    }

    @Test
    void halfAYearIsPutAsAYear() {
        // 182 days at what 7% a year comes to over them ($10,000 → about $10,343) reads as about 7%.
        Result result = ActualGrowth.between(
                LocalDate.of(2026, 1, 1), Money.ofCents(1_000_000), LocalDate.of(2026, 7, 2), Money.ofCents(1_034_300), List.of());
        assertThat(result.yearlyRateBp()).isCloseTo(700, within(1));
    }

    @Test
    void moneyPutInPartWayThroughCountsForThePartItWasThere() {
        // $10,000 more arrives with 183 of the 365 days left, and the year gains $1,000 beyond it:
        // $1,000 ÷ ($10,000 + $10,000 × 183/365) = 6.66%. Counted for the whole year it would say 5%.
        Result result = ActualGrowth.between(
                JAN_1, Money.ofCents(1_000_000), A_YEAR_LATER, Money.ofCents(2_100_000),
                List.of(new Flow(LocalDate.of(2025, 7, 2), Money.ofCents(1_000_000))));
        assertThat(result.growth()).isEqualTo(Money.ofCents(100_000));
        assertThat(result.yearlyRateBp()).isEqualTo(666);
    }

    @Test
    void balancesUnderNinetyDaysApartGiveNoRate() {
        LocalDate to = JAN_1.plusDays(ActualGrowth.MIN_DAYS_FOR_RATE - 1);
        Result result = ActualGrowth.between(JAN_1, Money.ofCents(1_000_000), to, Money.ofCents(1_030_000), List.of());
        assertThat(result.growth()).isEqualTo(Money.ofCents(30_000));
        assertThat(result.yearlyRateBp()).isNull();
    }

    @Test
    void ninetyDaysApartIsEnough() {
        LocalDate to = JAN_1.plusDays(ActualGrowth.MIN_DAYS_FOR_RATE);
        assertThat(ActualGrowth.between(JAN_1, Money.ofCents(1_000_000), to, Money.ofCents(1_010_000), List.of()).yearlyRateBp())
                .isNotNull();
    }

    @Test
    void nothingToGrowGivesNoRate() {
        Result result = ActualGrowth.between(JAN_1, Money.ZERO, A_YEAR_LATER, Money.ofCents(10_000), List.of());
        assertThat(result.growth()).isEqualTo(Money.ofCents(10_000));
        assertThat(result.yearlyRateBp()).isNull();
    }

    @Test
    void growthMatchesGainLoss() {
        List<Flow> flows = List.of(
                new Flow(LocalDate.of(2025, 3, 1), Money.ofCents(50_000)), new Flow(LocalDate.of(2025, 9, 1), Money.ofCents(-20_000)));
        Result result = ActualGrowth.between(JAN_1, Money.ofCents(1_000_000), A_YEAR_LATER, Money.ofCents(1_100_000), flows);
        assertThat(result.growth())
                .isEqualTo(GainLoss.between(Money.ofCents(1_000_000), Money.ofCents(1_100_000), Money.ofCents(30_000)));
    }

    @Test
    void aLaterBalanceMustBeOnALaterDay() {
        assertThatThrownBy(() -> ActualGrowth.between(JAN_1, Money.ZERO, JAN_1, Money.ZERO, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
