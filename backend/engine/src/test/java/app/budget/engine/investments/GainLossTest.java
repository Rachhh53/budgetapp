package app.budget.engine.investments;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.Money;
import org.junit.jupiter.api.Test;

/** Gain or loss between two entered balances (spec §5.5): ending − starting − net contributions. */
class GainLossTest {

    @Test
    void growthBeyondWhatWasPutInIsAGain() {
        // $40,000 → $43,000 with $2,400 put in: $600 gained.
        assertThat(GainLoss.between(Money.ofCents(4_000_000), Money.ofCents(4_300_000), Money.ofCents(240_000)))
                .isEqualTo(Money.ofCents(60_000));
    }

    @Test
    void aBalanceThatRoseByLessThanWasPutInIsALoss() {
        assertThat(GainLoss.between(Money.ofCents(4_000_000), Money.ofCents(4_100_000), Money.ofCents(240_000)))
                .isEqualTo(Money.ofCents(-140_000));
    }

    @Test
    void moneyTakenOutIsNotALoss() {
        // $5,000 withdrawn and the balance fell by exactly that: nothing gained or lost.
        assertThat(GainLoss.between(Money.ofCents(4_000_000), Money.ofCents(3_500_000), Money.ofCents(-500_000)))
                .isEqualTo(Money.ZERO);
    }
}
