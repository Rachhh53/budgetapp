package app.budget.engine.buckets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.budget.engine.Money;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Saving up for a bill over the paydays before it is due (spec §5.8). */
class SaveUpTest {

    @Test
    void anAmountThatDividesEvenlyIsSharedEqually() {
        // $1,200.00 of car insurance over 24 paydays.
        List<Money> shares = SaveUp.shares(Money.ofCents(120_000), 24);

        assertThat(shares).hasSize(24).containsOnly(Money.ofCents(5_000));
    }

    @Test
    void oddCentsGoOnTheEarliestPaydaysSoTheTotalIsExact() {
        List<Money> shares = SaveUp.shares(Money.ofCents(100_000), 7);

        assertThat(shares).containsExactly(
                Money.ofCents(14_286), Money.ofCents(14_286), Money.ofCents(14_286), Money.ofCents(14_286),
                Money.ofCents(14_286), Money.ofCents(14_285), Money.ofCents(14_285));
        assertThat(shares.stream().mapToLong(Money::cents).sum()).isEqualTo(100_000);
    }

    @Test
    void onePaydayCarriesItAll() {
        assertThat(SaveUp.shares(Money.ofCents(45_999), 1)).containsExactly(Money.ofCents(45_999));
    }

    @Test
    void noPaydaysMeansNothingCanBeSaved() {
        assertThat(SaveUp.shares(Money.ofCents(45_999), 0)).isEmpty();
    }

    @Test
    void aNegativeTargetIsRefused() {
        assertThatThrownBy(() -> SaveUp.shares(Money.ofCents(-1), 3)).isInstanceOf(IllegalArgumentException.class);
    }
}
