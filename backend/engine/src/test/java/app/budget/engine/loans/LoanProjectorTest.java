package app.budget.engine.loans;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.interest.InterestTerms;
import app.budget.engine.interest.InterestTerms.RateChange;
import app.budget.engine.loans.LoanProjector.Derived;
import app.budget.engine.loans.LoanProjector.Kind;
import app.budget.engine.loans.LoanProjector.Terms;
import app.budget.engine.projection.BalanceAnchor;
import app.budget.engine.projection.Movement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A loan at 6%, paid on the 1st, with $400.00 of each month's payment going to escrow. */
class LoanProjectorTest {

    private static final InterestTerms SIX_PERCENT =
            new InterestTerms(1, List.of(new RateChange(LocalDate.parse("2020-01-01"), BasisPoints.of(600))));
    private static final Terms TERMS = new Terms(1, Money.ofCents(40000), SIX_PERCENT);

    private static LocalDate date(String text) {
        return LocalDate.parse(text);
    }

    private static Money cents(long cents) {
        return Money.ofCents(cents);
    }

    private static Movement payment(String on, long amountCents) {
        return new Movement(date(on), cents(-amountCents), false);
    }

    private static List<BalanceAnchor> owed(String on, long amountCents) {
        return List.of(new BalanceAnchor(date(on), cents(amountCents)));
    }

    @Test
    void eachMonthAddsInterestAndKeepsEscrowOutOfTheLoan() {
        List<Derived> derived = LoanProjector.project(
                owed("2026-10-15", 10000000),
                List.of(payment("2026-11-01", 100000), payment("2026-12-01", 100000)),
                List.of(payment("2026-11-10", 50000)),
                TERMS,
                date("2026-12-31"));

        // Nov 1: 100,000.00 × 6% ÷ 12 = 500.00. After the payment, its escrow and the extra 500.00, 99,400.00 is owed.
        // Dec 1: 99,400.00 × 6% ÷ 12 = 497.00.
        assertThat(derived).containsExactly(
                new Derived(date("2026-11-01"), Kind.INTEREST, cents(50000)),
                new Derived(date("2026-11-01"), Kind.ESCROW, cents(40000)),
                new Derived(date("2026-12-01"), Kind.INTEREST, cents(49700)),
                new Derived(date("2026-12-01"), Kind.ESCROW, cents(40000)));
    }

    @Test
    void aMonthsInterestTypedOverIsWhatIsAddedAndLaterMonthsFollowIt() {
        Terms typed = new Terms(1, Money.ofCents(40000), SIX_PERCENT.withTyped(Map.of(date("2026-11-01"), cents(45000))));
        List<Derived> derived = LoanProjector.project(
                owed("2026-10-15", 10000000),
                List.of(payment("2026-11-01", 100000), payment("2026-12-01", 100000)),
                List.of(payment("2026-11-10", 50000)),
                typed,
                date("2026-12-31"));

        // Nov 1 is 450.00 as typed, leaving 99,350.00 after the payments; Dec 1 is 99,350.00 × 6% ÷ 12 = 496.75.
        assertThat(derived).containsExactly(
                new Derived(date("2026-11-01"), Kind.INTEREST, cents(45000)),
                new Derived(date("2026-11-01"), Kind.ESCROW, cents(40000)),
                new Derived(date("2026-12-01"), Kind.INTEREST, cents(49675)),
                new Derived(date("2026-12-01"), Kind.ESCROW, cents(40000)));
    }

    @Test
    void untilAPaymentIsScheduledTheMonthlyPaymentIsTakenAsMade() {
        // 600.00 of the payment reaches the loan. Nothing is scheduled until December.
        Terms assuming = new Terms(1, cents(40000), SIX_PERCENT, cents(60000));
        List<Derived> derived = LoanProjector.project(
                owed("2026-09-15", 10000000),
                List.of(payment("2026-12-01", 100000)),
                List.of(),
                assuming,
                date("2026-12-31"));

        // Oct 1: 500.00 interest, 600.00 paid: 99,900.00. Nov 1: 499.50 interest, 600.00 paid: 99,799.50.
        // Dec 1: 499.00 interest (rounded half-even from 498.9975), then the real payment and its escrow.
        assertThat(derived).containsExactly(
                new Derived(date("2026-10-01"), Kind.INTEREST, cents(50000)),
                new Derived(date("2026-10-01"), Kind.ASSUMED_PAYMENT, cents(60000)),
                new Derived(date("2026-11-01"), Kind.INTEREST, cents(49950)),
                new Derived(date("2026-11-01"), Kind.ASSUMED_PAYMENT, cents(60000)),
                new Derived(date("2026-12-01"), Kind.INTEREST, cents(49900)),
                new Derived(date("2026-12-01"), Kind.ESCROW, cents(40000)));
    }

    @Test
    void anAssumedPaymentNeverPaysMoreThanIsOwed() {
        List<Derived> derived = LoanProjector.project(
                owed("2026-09-15", 50000), List.of(), List.of(), new Terms(1, Money.ZERO, null, cents(60000)), date("2026-12-31"));

        assertThat(derived).containsExactly(new Derived(date("2026-10-01"), Kind.ASSUMED_PAYMENT, cents(50000)));
    }

    @Test
    void anExtraPaymentGoesWhollyToTheLoan() {
        List<Derived> derived = LoanProjector.project(
                owed("2026-10-15", 10000000), List.of(), List.of(payment("2026-10-20", 50000)), TERMS, date("2026-10-31"));

        assertThat(derived).isEmpty();
    }

    @Test
    void escrowIsTakenOncePerMonthHoweverThePaymentIsSplit() {
        List<Derived> derived = LoanProjector.project(
                owed("2026-11-02", 10000000),
                List.of(payment("2026-11-06", 30000), payment("2026-11-20", 30000)),
                List.of(),
                TERMS,
                date("2026-11-30"));

        assertThat(derived).containsExactly(
                new Derived(date("2026-11-06"), Kind.ESCROW, cents(30000)),
                new Derived(date("2026-11-20"), Kind.ESCROW, cents(10000)));
    }

    @Test
    void aBalanceEnteredOnThePaymentDayAlreadyHoldsThatMonthsInterest() {
        List<Derived> derived = LoanProjector.project(owed("2026-11-01", 10000000), List.of(), List.of(), TERMS, date("2026-11-30"));

        assertThat(derived).isEmpty();
    }

    @Test
    void withoutARateOnlyEscrowIsWorkedOut() {
        List<Derived> derived = LoanProjector.project(
                owed("2026-10-15", 10000000), List.of(payment("2026-11-01", 100000)), List.of(),
                new Terms(1, cents(40000), null), date("2026-11-30"));

        assertThat(derived).containsExactly(new Derived(date("2026-11-01"), Kind.ESCROW, cents(40000)));
    }

    @Test
    void nothingIsWorkedOutBeforeABalanceIsKnown() {
        assertThat(LoanProjector.project(List.of(), List.of(payment("2026-11-01", 100000)), List.of(), TERMS, date("2026-11-30"))).isEmpty();
    }
}
