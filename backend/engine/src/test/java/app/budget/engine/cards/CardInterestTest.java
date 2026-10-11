package app.budget.engine.cards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.cards.CardInterest.Portion;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CardInterestTest {

    private static final BasisPoints APR = BasisPoints.of(2449);

    private static LocalDate date(String text) {
        return LocalDate.parse(text);
    }

    private static Promo zeroPercent(long id, long originalCents, boolean deferred) {
        return new Promo(id, BasisPoints.of(0), date("2026-03-15"), date("2027-03-15"), Money.ofCents(originalCents), null, deferred);
    }

    @Test
    void aMonthsInterestIsATwelfthOfTheYearlyRate() {
        // 2,400.00 × 24.49% ÷ 12 = 48.98
        assertThat(CardInterest.forMonth(Money.ofCents(240000), List.of(), date("2026-10-20"), APR)).isEqualTo(Money.ofCents(4898));
    }

    @Test
    void nothingCarriedCostsNothing() {
        assertThat(CardInterest.forMonth(Money.ZERO, List.of(), date("2026-10-20"), APR)).isEqualTo(Money.ZERO);
        assertThat(CardInterest.forMonth(Money.ofCents(-5000), List.of(), date("2026-10-20"), APR)).isEqualTo(Money.ZERO);
    }

    @Test
    void aPromotionsShareOfTheBalanceIsChargedItsOwnRate() {
        // 3,000.00 owed, 2,400.00 of it at 0%: only the other 600.00 costs anything. 600 × 24.49% ÷ 12 = 12.245 → 12.24
        Promo promo = zeroPercent(1, 240000, false);

        assertThat(CardInterest.allocate(Money.ofCents(300000), List.of(promo), date("2026-10-20"), APR)).containsExactly(
                new Portion(1L, Money.ofCents(240000), BasisPoints.of(0)),
                new Portion(null, Money.ofCents(60000), APR));
        assertThat(CardInterest.forMonth(Money.ofCents(300000), List.of(promo), date("2026-10-20"), APR)).isEqualTo(Money.ofCents(1224));
    }

    @Test
    void aPromotionNeverCoversMoreThanIsOwed() {
        Promo promo = zeroPercent(1, 240000, false);

        assertThat(CardInterest.remaining(promo, Money.ofCents(200000), List.of(promo), date("2026-10-20"), APR))
                .isEqualTo(Money.ofCents(200000));
        assertThat(CardInterest.remaining(promo, Money.ZERO, List.of(promo), date("2026-10-20"), APR)).isEqualTo(Money.ZERO);
    }

    @Test
    void theLowestRateKeepsItsBalanceLongest() {
        Promo free = zeroPercent(1, 100000, false);
        Promo cheap = new Promo(2, BasisPoints.of(599), date("2026-01-01"), date("2027-01-01"), Money.ofCents(100000), null, false);

        // 1,500.00 owed: payments went to the dearer balances first, so the 0% one is still whole.
        assertThat(CardInterest.remaining(free, Money.ofCents(150000), List.of(cheap, free), date("2026-10-20"), APR))
                .isEqualTo(Money.ofCents(100000));
        assertThat(CardInterest.remaining(cheap, Money.ofCents(150000), List.of(cheap, free), date("2026-10-20"), APR))
                .isEqualTo(Money.ofCents(50000));
    }

    @Test
    void aPromotionThatHasNotStartedCoversNothing() {
        Promo later = new Promo(1, BasisPoints.of(0), date("2026-12-01"), date("2027-12-01"), Money.ofCents(100000), null, false);

        assertThat(CardInterest.remaining(later, Money.ofCents(150000), List.of(later), date("2026-10-20"), APR)).isEqualTo(Money.ZERO);
    }

    @Test
    void onceItEndsWhatIsLeftIsChargedTheRateThatFollows() {
        Promo regularAfter = zeroPercent(1, 240000, false);
        Promo ownRateAfter = new Promo(
                2, BasisPoints.of(0), date("2026-03-15"), date("2027-03-15"), Money.ofCents(240000), BasisPoints.of(1200), false);

        // The last day of the promotion is still free.
        assertThat(CardInterest.forMonth(Money.ofCents(240000), List.of(regularAfter), date("2027-03-15"), APR)).isEqualTo(Money.ZERO);
        assertThat(CardInterest.forMonth(Money.ofCents(240000), List.of(regularAfter), date("2027-03-20"), APR)).isEqualTo(Money.ofCents(4898));
        // 2,400.00 × 12% ÷ 12 = 24.00
        assertThat(CardInterest.forMonth(Money.ofCents(240000), List.of(ownRateAfter), date("2027-03-20"), APR)).isEqualTo(Money.ofCents(2400));
    }

    @Test
    void deferredInterestChargesTheWholePromotionWhenItIsNotClearedInTime() {
        // 3,000.00 for twelve months at 24.49%: 3,000 × 24.49% = 734.70
        Promo deferred = zeroPercent(1, 300000, true);

        assertThat(CardInterest.deferredCharge(deferred, Money.ofCents(1), APR)).isEqualTo(Money.ofCents(73470));
        assertThat(CardInterest.deferredCharge(deferred, Money.ZERO, APR)).isEqualTo(Money.ZERO);
        assertThat(CardInterest.deferredCharge(zeroPercent(2, 300000, false), Money.ofCents(100000), APR)).isEqualTo(Money.ZERO);
    }

    @Test
    void aPromotionOnTheWholeBalanceTakesEverythingOwedHoweverMuch() {
        // No amount of its own: 0% on the card, so charges made after it started are covered too.
        Promo everything = new Promo(1, BasisPoints.of(0), date("2026-03-15"), date("2027-03-15"), null, null, false);

        assertThat(CardInterest.allocate(Money.ofCents(400200), List.of(everything), date("2026-10-20"), APR)).containsExactly(
                new Portion(1L, Money.ofCents(400200), BasisPoints.of(0)),
                new Portion(null, Money.ZERO, APR));
        assertThat(CardInterest.forMonth(Money.ofCents(400200), List.of(everything), date("2026-10-20"), APR)).isEqualTo(Money.ZERO);
        assertThat(CardInterest.remaining(everything, Money.ofCents(400200), List.of(everything), date("2026-10-20"), APR))
                .isEqualTo(Money.ofCents(400200));
        // Once it ends, everything is at the rate that follows: 4,002.00 × 24.49% ÷ 12 = 81.674 → 81.67
        assertThat(CardInterest.forMonth(Money.ofCents(400200), List.of(everything), date("2027-03-20"), APR)).isEqualTo(Money.ofCents(8167));
    }

    @Test
    void deferredInterestNeedsASetAmount() {
        assertThatThrownBy(() -> new Promo(1, BasisPoints.of(0), date("2026-03-15"), date("2027-03-15"), null, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
