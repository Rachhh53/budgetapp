package app.budget.engine.loans;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.loans.Amortization.Payment;
import app.budget.engine.loans.Amortization.Schedule;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AmortizationTest {

    private static LocalDate date(String text) {
        return LocalDate.parse(text);
    }

    private static Money cents(long cents) {
        return Money.ofCents(cents);
    }

    @Test
    void aMonthsInterestIsATwelfthOfTheYearlyRate() {
        // 286,400.00 × 6.25% ÷ 12 = 1,491.666… → 1,491.67
        assertThat(Amortization.monthlyInterest(cents(28640000), BasisPoints.of(625))).isEqualTo(cents(149167));
        assertThat(Amortization.monthlyInterest(Money.ZERO, BasisPoints.of(625))).isEqualTo(Money.ZERO);
        assertThat(Amortization.monthlyInterest(cents(-100), BasisPoints.of(625))).isEqualTo(Money.ZERO);
    }

    @Test
    void theStandardPaymentPaysTheLoanOffOverItsTerm() {
        // The textbook case: $300,000 at 6% over 30 years is $1,798.65 a month.
        assertThat(Amortization.standardPayment(cents(30000000), BasisPoints.of(600), 360)).isEqualTo(cents(179865));
    }

    @Test
    void withNoInterestTheStandardPaymentIsAnEvenShare() {
        assertThat(Amortization.standardPayment(cents(1200000), BasisPoints.of(0), 12)).isEqualTo(cents(100000));
        assertThatIllegalArgumentException().isThrownBy(() -> Amortization.standardPayment(cents(1200000), BasisPoints.of(600), 0));
    }

    @Test
    void eachPaymentCoversTheInterestFirstAndTheRestLowersTheLoan() {
        Schedule schedule = Amortization.schedule(cents(100000), BasisPoints.of(1200), cents(50000), date("2026-11-01"));

        assertThat(schedule.payments()).containsExactly(
                new Payment(date("2026-11-01"), cents(50000), cents(1000), cents(49000), cents(51000)),
                new Payment(date("2026-12-01"), cents(50000), cents(510), cents(49490), cents(1510)),
                // The last payment is only what is left, with its interest: 15.10 + 0.15
                new Payment(date("2027-01-01"), cents(1525), cents(15), cents(1510), Money.ZERO));
        assertThat(schedule.paysOff()).isTrue();
        assertThat(schedule.payoffDate()).isEqualTo(date("2027-01-01"));
        assertThat(schedule.totalInterest()).isEqualTo(cents(1525));
    }

    @Test
    void aPaymentThatDoesNotCoverTheInterestNeverPaysTheLoanOff() {
        Schedule schedule = Amortization.schedule(cents(100000), BasisPoints.of(1200), cents(1000), date("2026-11-01"));

        assertThat(schedule.paysOff()).isFalse();
        assertThat(schedule.payoffDate()).isNull();
        assertThat(schedule.payments()).isEmpty();
    }

    @Test
    void aPaymentDayAMonthDoesNotHaveFallsOnItsLastDay() {
        Schedule schedule = Amortization.schedule(cents(30000), BasisPoints.of(0), cents(10000), date("2027-01-31"));

        assertThat(schedule.payments()).extracting(Payment::date)
                .containsExactly(date("2027-01-31"), date("2027-02-28"), date("2027-03-31"));
    }

    @Test
    void aLoanThatIsAlreadyPaidOffHasNoPaymentsLeft() {
        Schedule schedule = Amortization.schedule(Money.ZERO, BasisPoints.of(600), cents(10000), date("2026-11-01"));

        assertThat(schedule.payments()).isEmpty();
        assertThat(schedule.paysOff()).isTrue();
        assertThat(schedule.payoffDate()).isNull();
    }
}
