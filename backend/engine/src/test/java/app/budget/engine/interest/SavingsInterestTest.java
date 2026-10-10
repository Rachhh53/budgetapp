package app.budget.engine.interest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.interest.InterestTerms.RateChange;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Savings interest (spec §5.4). */
class SavingsInterestTest {

    private static final BasisPoints APY_410 = BasisPoints.of(410);

    @Test
    void twelveMonthsOfTheMonthlyRateCompoundToTheApy() {
        BigDecimal monthly = SavingsInterest.monthlyRate(APY_410);

        BigDecimal year = BigDecimal.ONE.add(monthly).pow(12, MathContext.DECIMAL128);
        assertThat(year).isCloseTo(new BigDecimal("1.0410"), within(new BigDecimal("1e-25")));
    }

    @Test
    void aMonthAtFourPointOnePercentOnTenThousandDollars() {
        // 10,000.00 × ((1.041)^(1/12) − 1) = 33.5409…
        assertThat(SavingsInterest.forMonth(Money.ofCents(1_000_000), APY_410)).isEqualTo(Money.ofCents(3354));
    }

    @Test
    void aZeroRateEarnsNothing() {
        assertThat(SavingsInterest.monthlyRate(BasisPoints.of(0))).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(SavingsInterest.forMonth(Money.ofCents(1_000_000), BasisPoints.of(0))).isEqualTo(Money.ZERO);
    }

    @Test
    void anOverdrawnOrEmptyAccountEarnsNothing() {
        assertThat(SavingsInterest.forMonth(Money.ofCents(-50_000), APY_410)).isEqualTo(Money.ZERO);
        assertThat(SavingsInterest.forMonth(Money.ZERO, APY_410)).isEqualTo(Money.ZERO);
    }

    @Test
    void aNegativeRateIsRefused() {
        assertThatThrownBy(() -> SavingsInterest.monthlyRate(BasisPoints.of(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void creditDatesFallOnTheChosenDayEachMonth() {
        assertThat(SavingsInterest.creditDates(15, date("2026-10-01"), date("2026-12-31")))
                .containsExactly(date("2026-10-15"), date("2026-11-15"), date("2026-12-15"));
    }

    @Test
    void theLastDayFollowsTheLengthOfEachMonth() {
        assertThat(SavingsInterest.creditDates(SavingsInterest.LAST_DAY, date("2027-01-01"), date("2027-04-30")))
                .containsExactly(date("2027-01-31"), date("2027-02-28"), date("2027-03-31"), date("2027-04-30"));
        // A leap year.
        assertThat(SavingsInterest.creditDates(30, date("2028-02-01"), date("2028-02-29"))).containsExactly(date("2028-02-29"));
    }

    @Test
    void creditDatesOutsideTheRangeAreLeftOut() {
        assertThat(SavingsInterest.creditDates(10, date("2026-10-11"), date("2026-11-09"))).isEmpty();
        assertThat(SavingsInterest.creditDates(10, date("2026-10-10"), date("2026-10-10"))).containsExactly(date("2026-10-10"));
    }

    @Test
    void theRateInForceIsTheLatestOneOnOrBeforeTheDate() {
        InterestTerms terms = new InterestTerms(31, List.of(
                new RateChange(date("2026-11-01"), BasisPoints.of(385)),
                new RateChange(date("2026-06-01"), APY_410)));

        assertThat(terms.rateOn(date("2026-05-31"))).isEmpty();
        assertThat(terms.rateOn(date("2026-06-01"))).contains(APY_410);
        assertThat(terms.rateOn(date("2026-10-31"))).contains(APY_410);
        assertThat(terms.rateOn(date("2026-11-01"))).contains(BasisPoints.of(385));
    }

    private static LocalDate date(String text) {
        return LocalDate.parse(text);
    }
}
