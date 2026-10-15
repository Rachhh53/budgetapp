package app.budget.engine.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.loans.Amortization;
import app.budget.engine.loans.Amortization.Schedule;
import app.budget.engine.planner.PayoffPlan.Debt;
import app.budget.engine.planner.PayoffPlan.Order;
import app.budget.engine.planner.PayoffPlan.Outcome;
import app.budget.engine.planner.PayoffPlan.Result;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PayoffPlanTest {

    private static final LocalDate NOV_1 = LocalDate.parse("2026-11-01");

    private static LocalDate date(String text) {
        return LocalDate.parse(text);
    }

    private static Money cents(long cents) {
        return Money.ofCents(cents);
    }

    private static Debt debt(long id, long balanceCents, int rateBp, long paymentCents) {
        return new Debt(id, cents(balanceCents), BasisPoints.of(rateBp), cents(paymentCents), NOV_1);
    }

    /** The phase's "done when": the car loan and the mortgage each come out as their own loan pages work them out. */
    @Test
    void aDebtOnItsOwnIsPaidOffExactlyAsItsLoanScheduleSays() {
        Debt car = debt(1, 1486000, 690, 41200);
        Debt mortgage = new Debt(2, cents(28640000), BasisPoints.of(625), cents(210000), date("2026-11-15"));

        Result asTheyAre = PayoffPlan.asTheyAre(List.of(car, mortgage));

        Schedule carAlone = Amortization.schedule(cents(1486000), BasisPoints.of(690), cents(41200), NOV_1);
        Schedule mortgageAlone = Amortization.schedule(cents(28640000), BasisPoints.of(625), cents(210000), date("2026-11-15"));
        assertThat(asTheyAre.of(1)).isEqualTo(new Outcome(1, carAlone.payoffDate(), carAlone.totalInterest(), true));
        assertThat(asTheyAre.of(2)).isEqualTo(new Outcome(2, mortgageAlone.payoffDate(), mortgageAlone.totalInterest(), true));
        assertThat(asTheyAre.totalInterest()).isEqualTo(carAlone.totalInterest().plus(mortgageAlone.totalInterest()));
        assertThat(asTheyAre.debtFreeDate()).isEqualTo(mortgageAlone.payoffDate());
    }

    @Test
    void extraOnOneDebtIsTheSameAsALargerPayment() {
        Debt car = debt(1, 1486000, 690, 41200);

        Outcome withExtra = PayoffPlan.withExtra(List.of(car), cents(30000), Order.HIGHEST_RATE).of(1);

        Schedule larger = Amortization.schedule(cents(1486000), BasisPoints.of(690), cents(71200), NOV_1);
        assertThat(withExtra).isEqualTo(new Outcome(1, larger.payoffDate(), larger.totalInterest(), true));
        Outcome without = PayoffPlan.asTheyAre(List.of(car)).of(1);
        assertThat(withExtra.payoffDate()).isBefore(without.payoffDate());
        assertThat(withExtra.interest()).isLessThan(without.interest());
    }

    @Test
    void noExtraChangesNothing() {
        List<Debt> car = List.of(debt(1, 1486000, 690, 41200));

        assertThat(PayoffPlan.withExtra(car, Money.ZERO, Order.HIGHEST_RATE)).isEqualTo(PayoffPlan.asTheyAre(car));
    }

    @Test
    void theExtraGoesToTheSmallestBalanceFirstAndItsPaymentRollsOn() {
        // No interest, so every figure can be followed by hand.
        List<Debt> debts = List.of(debt(1, 100000, 0, 10000), debt(2, 30000, 0, 10000));

        Result result = PayoffPlan.withExtra(debts, cents(10000), Order.SMALLEST_BALANCE);

        // Nov: both pay 100, the extra 100 goes to the small one (100 left). Dec: it is gone, and the extra moves on.
        assertThat(result.of(2).payoffDate()).isEqualTo(date("2026-12-01"));
        // From Jan the large one gets its own 100, the extra 100 and the 100 that was freed: gone in March.
        assertThat(result.of(1).payoffDate()).isEqualTo(date("2027-03-01"));
        assertThat(result.debtFreeDate()).isEqualTo(date("2027-03-01"));

        Result asTheyAre = PayoffPlan.asTheyAre(debts);
        assertThat(asTheyAre.of(2).payoffDate()).isEqualTo(date("2027-01-01"));
        assertThat(asTheyAre.of(1).payoffDate()).isEqualTo(date("2027-08-01"));
    }

    @Test
    void theExtraGoesToTheHighestRateFirst() {
        List<Debt> debts = List.of(debt(1, 100000, 1200, 10000), debt(2, 30000, 0, 10000));

        Result result = PayoffPlan.withExtra(debts, cents(10000), Order.HIGHEST_RATE);

        // The small one only ever gets its own payment: three months.
        assertThat(result.of(2)).isEqualTo(new Outcome(2, date("2027-01-01"), Money.ZERO, true));
        // 1% a month on 1,000.00, 810.00, 618.10, 424.28 and 128.52: 10.00 + 8.10 + 6.18 + 4.24 + 1.29.
        assertThat(result.of(1)).isEqualTo(new Outcome(1, date("2027-03-01"), cents(2981), true));

        assertThat(PayoffPlan.withExtra(debts, cents(10000), Order.SMALLEST_BALANCE).of(2).payoffDate()).isEqualTo(date("2026-12-01"));
    }

    @Test
    void aPromotionalRateEndingChargesTheNewRateFromThatDay() {
        Debt card = new Debt(1, cents(100000), BasisPoints.of(0), cents(10000), NOV_1, date("2027-01-01"), BasisPoints.of(1200));

        Outcome outcome = PayoffPlan.asTheyAre(List.of(card)).of(1);

        // Two months free, then 1% a month on what is left: 8.00, 7.08, 6.15, 5.21, 4.26, 3.31, 2.34, 1.36 and 0.38.
        assertThat(outcome).isEqualTo(new Outcome(1, date("2027-09-01"), cents(3809), true));
    }

    @Test
    void whenAPromotionEndsTheHighestRateIsWorkedOutAgain() {
        Debt card = new Debt(1, cents(500000), BasisPoints.of(0), cents(5000), NOV_1, date("2027-01-01"), BasisPoints.of(2449));
        Debt loan = debt(2, 500000, 690, 5000);

        Result promoFirst = PayoffPlan.withExtra(List.of(card, loan), cents(100000), Order.HIGHEST_RATE);

        // While the card costs nothing the extra goes to the loan; from January it goes to the card, which then ends first.
        assertThat(promoFirst.of(1).payoffDate()).isBefore(promoFirst.of(2).payoffDate());
        Result loanOnly = PayoffPlan.withExtra(List.of(loan), cents(100000), Order.HIGHEST_RATE);
        assertThat(promoFirst.of(2).payoffDate()).isAfter(loanOnly.of(2).payoffDate());
    }

    @Test
    void aPaymentThatDoesNotCoverTheInterestNeverPaysOff() {
        Debt card = debt(1, 100000, 2400, 1000);

        Result asTheyAre = PayoffPlan.asTheyAre(List.of(card));

        assertThat(asTheyAre.of(1).paysOff()).isFalse();
        assertThat(asTheyAre.of(1).payoffDate()).isNull();
        assertThat(asTheyAre.paysOff()).isFalse();
        assertThat(asTheyAre.debtFreeDate()).isNull();

        // Enough extra gets ahead of it.
        assertThat(PayoffPlan.withExtra(List.of(card), cents(20000), Order.HIGHEST_RATE).of(1).paysOff()).isTrue();
    }

    @Test
    void aDebtWhoseFirstPaymentIsNextMonthWaitsForIt() {
        Debt later = new Debt(1, cents(20000), BasisPoints.of(0), cents(10000), date("2026-12-05"));
        Debt sooner = new Debt(2, cents(20000), BasisPoints.of(0), cents(10000), date("2026-11-20"));

        Result result = PayoffPlan.asTheyAre(List.of(later, sooner));

        assertThat(result.of(1).payoffDate()).isEqualTo(date("2027-01-05"));
        assertThat(result.of(2).payoffDate()).isEqualTo(date("2026-12-20"));
    }

    @Test
    void nothingOwedMeansNothingToPay() {
        Result result = PayoffPlan.withExtra(List.of(debt(1, 0, 690, 41200)), cents(30000), Order.HIGHEST_RATE);

        assertThat(result.of(1)).isEqualTo(new Outcome(1, null, Money.ZERO, true));
        assertThat(result.debtFreeDate()).isNull();
        assertThat(PayoffPlan.asTheyAre(List.of()).debts()).isEmpty();
    }

    @Test
    void negativeAmountsAndRatesAreRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> debt(1, 100000, -1, 1000));
        assertThatIllegalArgumentException().isThrownBy(() -> debt(1, 100000, 100, -1));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new Debt(1, cents(100000), BasisPoints.of(0), cents(1000), NOV_1, date("2027-01-01"), null));
        assertThatIllegalArgumentException().isThrownBy(
                () -> PayoffPlan.withExtra(List.of(debt(1, 100000, 100, 1000)), cents(-1), Order.HIGHEST_RATE));
    }

    /** For the "Debt over time" chart: everything owed at each month's end, from today until the last debt is gone. */
    @Test
    void followsWhatIsOwedAltogetherMonthByMonth() {
        Debt card = debt(1, 30000, 0, 10000);
        Debt loan = debt(2, 20000, 0, 10000);

        Result without = PayoffPlan.asTheyAre(List.of(card, loan));
        Result with = PayoffPlan.withExtra(List.of(card, loan), cents(10000), Order.SMALLEST_BALANCE);

        // October as it stands, then each month's payments off, at 0% so the sums are plain.
        assertThat(without.owed()).extracting(PayoffPlan.Owed::total)
                .containsExactly(cents(50000), cents(30000), cents(10000), cents(0));
        assertThat(without.owed().getFirst().month()).hasToString("2026-10");
        assertThat(with.owed()).extracting(PayoffPlan.Owed::total)
                .containsExactly(cents(50000), cents(20000), cents(0));
        assertThat(with.owed().getLast().month()).hasToString("2026-12");
    }
}
