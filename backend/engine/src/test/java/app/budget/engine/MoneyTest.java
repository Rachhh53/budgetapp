package app.budget.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void storesDollarsAsWholeCents() {
        assertThat(Money.fromDecimal(new BigDecimal("14.99")).cents()).isEqualTo(1499);
        assertThat(Money.ofCents(1499).toDecimal()).isEqualByComparingTo("14.99");
    }

    @Test
    void roundsHalfCentsToTheEvenCent() {
        assertThat(Money.fromDecimal(new BigDecimal("0.125")).cents()).isEqualTo(12);
        assertThat(Money.fromDecimal(new BigDecimal("0.135")).cents()).isEqualTo(14);
        assertThat(Money.fromDecimal(new BigDecimal("-0.125")).cents()).isEqualTo(-12);
    }

    @Test
    void addsAndSubtractsExactly() {
        Money starting = Money.ofCents(121_438);
        Money paycheck = Money.ofCents(248_620);
        Money bills = Money.ofCents(199_548);

        assertThat(starting.plus(paycheck).minus(bills)).isEqualTo(Money.ofCents(170_510));
        assertThat(bills.negate().isNegative()).isTrue();
        assertThat(starting.minus(starting).isZero()).isTrue();
    }

    @Test
    void multipliesByARateAndRoundsHalfEven() {
        // $1,000.00 at 4.10% is exactly $41.00; $0.50 x 0.05 is 2.5 cents, which rounds to 2.
        assertThat(Money.ofCents(100_000).times(BasisPoints.of(410).toFraction())).isEqualTo(Money.ofCents(4_100));
        assertThat(Money.ofCents(50).times(new BigDecimal("0.05"))).isEqualTo(Money.ofCents(2));
    }

    @Test
    void refusesToOverflowSilently() {
        assertThatThrownBy(() -> Money.ofCents(Long.MAX_VALUE).plus(Money.ofCents(1)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void ordersByAmount() {
        assertThat(Money.ofCents(-1)).isLessThan(Money.ZERO);
        assertThat(Money.ofCents(500)).isGreaterThan(Money.ofCents(499));
    }
}
