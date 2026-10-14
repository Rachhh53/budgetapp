package app.budget.engine.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.planner.ContributionGrowth.Value;
import org.junit.jupiter.api.Test;

class ContributionGrowthTest {

    private static Money cents(long cents) {
        return Money.ofCents(cents);
    }

    @Test
    void withNoGrowthItIsOnlyWhatWasPutIn() {
        Value value = ContributionGrowth.of(cents(30000), BasisPoints.of(0), 60);

        assertThat(value.putIn()).isEqualTo(cents(1800000));
        assertThat(value.growth()).isEqualTo(Money.ZERO);
        assertThat(value.total()).isEqualTo(cents(1800000));
    }

    @Test
    void aYearOfMonthlyAmountsGrowsByTheMonthlyShareOfTheYearlyRate() {
        // 100.00 at the end of each month at 6% a year: 100 × 0.06 ÷ (1.06^(1/12) − 1) = 1,232.65
        Value value = ContributionGrowth.of(cents(10000), BasisPoints.of(600), 12);

        assertThat(value.putIn()).isEqualTo(cents(120000));
        assertThat(value.total()).isEqualTo(cents(123265));
        assertThat(value.growth()).isEqualTo(cents(3265));
    }

    @Test
    void theFirstAmountHasNotGrownYetAfterOneMonth() {
        assertThat(ContributionGrowth.of(cents(30000), BasisPoints.of(700), 1).total()).isEqualTo(cents(30000));
        assertThat(ContributionGrowth.of(cents(30000), BasisPoints.of(700), 0).total()).isEqualTo(Money.ZERO);
    }

    @Test
    void aHigherRateAndALongerTimeBothComeToMore() {
        Money sixPercent = ContributionGrowth.of(cents(30000), BasisPoints.of(600), 60).total();
        Money sevenPercent = ContributionGrowth.of(cents(30000), BasisPoints.of(700), 60).total();
        Money tenYears = ContributionGrowth.of(cents(30000), BasisPoints.of(600), 120).total();

        assertThat(sevenPercent).isGreaterThan(sixPercent);
        assertThat(tenYears).isGreaterThan(sixPercent.plus(sixPercent));
    }

    @Test
    void negativeAmountsRatesAndPeriodsAreRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> ContributionGrowth.of(cents(-1), BasisPoints.of(600), 12));
        assertThatIllegalArgumentException().isThrownBy(() -> ContributionGrowth.of(cents(100), BasisPoints.of(-1), 12));
        assertThatIllegalArgumentException().isThrownBy(() -> ContributionGrowth.of(cents(100), BasisPoints.of(600), -1));
        assertThatIllegalArgumentException().isThrownBy(() -> ContributionGrowth.of(cents(100), BasisPoints.of(600), 1201));
    }
}
