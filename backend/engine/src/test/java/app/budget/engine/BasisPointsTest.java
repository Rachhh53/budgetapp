package app.budget.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BasisPointsTest {

    @Test
    void convertsToAFraction() {
        assertThat(BasisPoints.of(410).toFraction()).isEqualByComparingTo("0.0410");
        assertThat(BasisPoints.of(0).toFraction()).isEqualByComparingTo("0");
        assertThat(BasisPoints.of(10_000).toFraction()).isEqualByComparingTo("1");
    }
}
