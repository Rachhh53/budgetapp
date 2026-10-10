package app.budget.engine.networth;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.Money;
import org.junit.jupiter.api.Test;

/** Net worth (spec §5.7). */
class NetWorthTest {

    @Test
    void isEverythingOwnedLessEverythingOwed() {
        NetWorth worth = new NetWorth(
                Money.ofCents(1_534_512), Money.ofCents(8_250_000), Money.ofCents(40_000_000),
                Money.ofCents(184_300), Money.ofCents(28_500_000));

        assertThat(worth.assets()).isEqualTo(Money.ofCents(49_784_512));
        assertThat(worth.liabilities()).isEqualTo(Money.ofCents(28_684_300));
        assertThat(worth.total()).isEqualTo(Money.ofCents(21_100_212));
    }

    @Test
    void canBeNegative() {
        NetWorth worth = new NetWorth(Money.ofCents(50_000), Money.ZERO, Money.ZERO, Money.ofCents(120_000), Money.ZERO);

        assertThat(worth.total()).isEqualTo(Money.ofCents(-70_000));
    }
}
