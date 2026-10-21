package app.budget.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Whole-number roots in {@link BigDecimal}. */
class RootsTest {

    @Test
    void findsAnExactRoot() {
        assertThat(Roots.nth(BigDecimal.valueOf(8), 3)).isEqualByComparingTo("2");
    }

    @Test
    void findsARootBelowOne() {
        // A loss spread over parts: 0.81^(1/2) = 0.9.
        assertThat(Roots.nth(new BigDecimal("0.81"), 2)).isEqualByComparingTo("0.9");
    }

    @Test
    void aRootRaisedBackComesToTheValue() {
        BigDecimal root = Roots.nth(new BigDecimal("1.07"), 3_650);
        assertThat(root.pow(3_650, Roots.PRECISION).subtract(new BigDecimal("1.07")).abs())
                .isLessThan(new BigDecimal("1e-25"));
    }

    @Test
    void refusesWhatHasNoRoot() {
        assertThatThrownBy(() -> Roots.nth(BigDecimal.ZERO, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Roots.nth(BigDecimal.ONE, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
