package app.budget.engine.cards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.cards.PromoPayoff.Warning;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PromoPayoffTest {

    private static final int LEAD_DAYS = 60;

    private static LocalDate date(String text) {
        return LocalDate.parse(text);
    }

    private static Promo promo(boolean deferred) {
        return new Promo(1, BasisPoints.of(0), date("2026-03-15"), date("2027-03-15"), Money.ofCents(300000), null, deferred);
    }

    @Test
    void theBalanceIsSharedOverThePaydaysLeft() {
        // Spec §5.6: $2,400 before Mar 15, 2027 with 10 paydays left → $240.00.
        assertThat(PromoPayoff.perPayday(Money.ofCents(240000), 10)).contains(Money.ofCents(24000));
    }

    @Test
    void anUnevenShareIsRoundedUpSoItIsNeverACentShort() {
        assertThat(PromoPayoff.perPayday(Money.ofCents(100000), 3)).contains(Money.ofCents(33334));
    }

    @Test
    void nothingLeftNeedsNothingPaid() {
        assertThat(PromoPayoff.perPayday(Money.ZERO, 10)).contains(Money.ZERO);
    }

    @Test
    void withNoPaydayLeftThereIsNothingToSuggest() {
        assertThat(PromoPayoff.perPayday(Money.ofCents(240000), 0)).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> PromoPayoff.perPayday(Money.ofCents(-1), 3));
    }

    @Test
    void daysLeftCountsToThePromotionsLastDay() {
        assertThat(PromoPayoff.daysLeft(date("2026-10-04"), date("2027-03-15"))).isEqualTo(162);
        assertThat(PromoPayoff.daysLeft(date("2027-03-15"), date("2027-03-15"))).isZero();
        assertThat(PromoPayoff.daysLeft(date("2027-03-16"), date("2027-03-15"))).isEqualTo(-1);
    }

    @Test
    void theWarningStartsTheLeadTimeBeforeTheEnd() {
        Money left = Money.ofCents(240000);

        // Sixty days before March 15 is January 14.
        assertThat(PromoPayoff.warning(promo(false), left, date("2027-01-13"), LEAD_DAYS)).isEqualTo(Warning.NONE);
        assertThat(PromoPayoff.warning(promo(false), left, date("2027-01-14"), LEAD_DAYS)).isEqualTo(Warning.SOON);
        assertThat(PromoPayoff.warning(promo(false), left, date("2027-03-15"), LEAD_DAYS)).isEqualTo(Warning.SOON);
    }

    @Test
    void deferredInterestGetsTheStrongerWarning() {
        assertThat(PromoPayoff.warning(promo(true), Money.ofCents(240000), date("2027-02-01"), LEAD_DAYS))
                .isEqualTo(Warning.SOON_DEFERRED);
    }

    @Test
    void onceItHasEndedWithABalanceItSaysSo() {
        assertThat(PromoPayoff.warning(promo(false), Money.ofCents(50000), date("2027-03-16"), LEAD_DAYS)).isEqualTo(Warning.ENDED);
    }

    @Test
    void aPromotionThatIsPaidOffOrNotStartedNeedsNoWarning() {
        assertThat(PromoPayoff.warning(promo(true), Money.ZERO, date("2027-03-01"), LEAD_DAYS)).isEqualTo(Warning.NONE);
        assertThat(PromoPayoff.warning(promo(true), Money.ZERO, date("2027-04-01"), LEAD_DAYS)).isEqualTo(Warning.NONE);
        assertThat(PromoPayoff.warning(promo(true), Money.ofCents(240000), date("2026-03-01"), LEAD_DAYS)).isEqualTo(Warning.NONE);
    }
}
