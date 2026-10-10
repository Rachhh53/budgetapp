package app.budget.engine.assets;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.assets.AssetValue.Entered;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Home and asset values (spec §5.7). */
class AssetValueTest {

    private static final List<Entered> HOME = List.of(
            new Entered(date("2026-01-01"), Money.ofCents(40_000_000)),
            new Entered(date("2025-01-01"), Money.ofCents(38_000_000)));

    @Test
    void withoutARateItHoldsTheLatestValueEntered() {
        assertThat(AssetValue.on(date("2026-10-09"), HOME, null)).contains(Money.ofCents(40_000_000));
        assertThat(AssetValue.on(date("2025-06-30"), HOME, null)).contains(Money.ofCents(38_000_000));
        assertThat(AssetValue.on(date("2026-10-09"), HOME, BasisPoints.of(0))).contains(Money.ofCents(40_000_000));
    }

    @Test
    void nothingIsKnownBeforeTheFirstValue() {
        assertThat(AssetValue.on(date("2024-12-31"), HOME, BasisPoints.of(300))).isEmpty();
        assertThat(AssetValue.on(date("2026-10-09"), List.of(), BasisPoints.of(300))).isEmpty();
    }

    @Test
    void aYearAtTheRateGrowsItByExactlyTheRate() {
        // $400,000 at 3% a year, twelve whole months on.
        assertThat(AssetValue.on(date("2027-01-01"), HOME, BasisPoints.of(300))).contains(Money.ofCents(41_200_000));
    }

    @Test
    void itGrowsByWholeMonthsSinceTheLatestValue() {
        // Nine whole months from Jan 1 to Oct 9: 400,000 × 1.03^(9/12) = 400,000 × 1.0224166… = 408,966.66
        assertThat(AssetValue.on(date("2026-10-09"), HOME, BasisPoints.of(300))).contains(Money.ofCents(40_896_666));
        // Not yet a whole month: still what was entered.
        assertThat(AssetValue.on(date("2026-01-31"), HOME, BasisPoints.of(300))).contains(Money.ofCents(40_000_000));
    }

    @Test
    void aNewValueReplacesTheEstimate() {
        // At the end of 2025 the first value has grown for eleven months; the next day the new one stands.
        assertThat(AssetValue.on(date("2025-12-31"), HOME, BasisPoints.of(300)).orElseThrow().cents()).isGreaterThan(38_000_000);
        assertThat(AssetValue.on(date("2026-01-01"), HOME, BasisPoints.of(300))).contains(Money.ofCents(40_000_000));
    }

    @Test
    void equityIsTheValueLessWhatIsOwed() {
        assertThat(AssetValue.equity(Money.ofCents(40_000_000), Money.ofCents(28_500_000))).isEqualTo(Money.ofCents(11_500_000));
        // Owing more than it is worth is negative equity, not zero.
        assertThat(AssetValue.equity(Money.ofCents(1_500_000), Money.ofCents(1_800_000))).isEqualTo(Money.ofCents(-300_000));
    }

    private static LocalDate date(String text) {
        return LocalDate.parse(text);
    }
}
