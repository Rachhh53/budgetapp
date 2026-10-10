package app.budget.engine.buckets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import app.budget.engine.Money;
import org.junit.jupiter.api.Test;

class GoalPaceTest {

    @Test
    void theShortfallIsWhatIsStillMissing() {
        assertThat(GoalPace.shortfall(Money.ofCents(60000), Money.ofCents(45000))).isEqualTo(Money.ofCents(15000));
    }

    @Test
    void beingAheadOfTheGoalIsNoShortfall() {
        assertThat(GoalPace.shortfall(Money.ofCents(60000), Money.ofCents(65000))).isEqualTo(Money.ofCents(0));
        assertThat(GoalPace.shortfall(Money.ofCents(60000), Money.ofCents(60000))).isEqualTo(Money.ofCents(0));
    }

    @Test
    void theShortfallIsSharedEvenlyOverTheTimesLeft() {
        assertThat(GoalPace.each(Money.ofCents(15000), 5)).isEqualTo(Money.ofCents(3000));
    }

    @Test
    void anUnevenShareIsRoundedUpSoTheGoalIsNeverMissedByACent() {
        // 100.00 over three paydays: 33.34 each reaches it; 33.33 would fall a cent short.
        assertThat(GoalPace.each(Money.ofCents(10000), 3)).isEqualTo(Money.ofCents(3334));
    }

    @Test
    void nothingMissingNeedsNothingSetAside() {
        assertThat(GoalPace.each(Money.ofCents(0), 4)).isEqualTo(Money.ofCents(0));
    }

    @Test
    void thereHasToBeATimeLeftToSaveOn() {
        assertThatIllegalArgumentException().isThrownBy(() -> GoalPace.each(Money.ofCents(100), 0));
        assertThatIllegalArgumentException().isThrownBy(() -> GoalPace.each(Money.ofCents(-1), 2));
    }
}
