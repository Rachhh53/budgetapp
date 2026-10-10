package app.budget.engine.cards;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.cards.CardPayment.Mode;
import app.budget.engine.cards.CardProjector.Derived;
import app.budget.engine.cards.CardProjector.Kind;
import app.budget.engine.cards.CardProjector.Payment;
import app.budget.engine.cards.CardProjector.Result;
import app.budget.engine.cards.CardProjector.Statement;
import app.budget.engine.cards.CardProjector.Terms;
import app.budget.engine.interest.InterestTerms;
import app.budget.engine.interest.InterestTerms.RateChange;
import app.budget.engine.projection.BalanceAnchor;
import app.budget.engine.projection.Movement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A card that closes on the 20th and is due on the 15th, at 24.49%. */
class CardProjectorTest {

    private static final InterestTerms APR =
            new InterestTerms(31, List.of(new RateChange(LocalDate.parse("2026-01-01"), BasisPoints.of(2449))));

    private static LocalDate date(String text) {
        return LocalDate.parse(text);
    }

    private static Money cents(long cents) {
        return Money.ofCents(cents);
    }

    private static Terms terms(Mode mode, Long monthlyEstimateCents, List<Promo> promos, Map<LocalDate, Statement> entered) {
        return new Terms(20, 15, mode, monthlyEstimateCents == null ? null : cents(monthlyEstimateCents), APR, promos, entered);
    }

    private static Terms terms(Mode mode) {
        return terms(mode, null, List.of(), Map.of());
    }

    private static BalanceAnchor owed(String on, long amountCents) {
        return new BalanceAnchor(date(on), cents(amountCents));
    }

    private static Movement charge(String on, long amountCents) {
        return new Movement(date(on), cents(amountCents), false);
    }

    @Test
    void aCardPaidInFullEveryMonthIsNeverChargedInterest() {
        Result result = CardProjector.project(
                List.of(owed("2026-10-01", 68248)),
                List.of(charge("2026-10-05", 10000)),
                List.of(new Payment(date("2026-10-13"), 7, false)),
                terms(Mode.STATEMENT_BALANCE),
                date("2026-10-31"));

        // The payment is what was owed when the card was entered, not the charge made since.
        assertThat(result.derived()).containsExactly(new Derived(date("2026-10-13"), Kind.PAYMENT, cents(-68248), 7));
        assertThat(result.statements()).containsExactly(
                new Statement(date("2026-10-20"), cents(10000), cents(2500), date("2026-11-15"), Money.ZERO, false));
    }

    @Test
    void aBalanceCarriedFromOneStatementToTheNextIsChargedInterest() {
        Result result = CardProjector.project(
                List.of(owed("2026-10-01", 100000)), List.of(), List.of(), terms(Mode.STATEMENT_BALANCE), date("2026-11-30"));

        // 1,000.00 × 24.49% ÷ 12 = 20.41, then 1,020.41 × 24.49% ÷ 12 = 20.82
        assertThat(result.derived()).containsExactly(
                new Derived(date("2026-10-20"), Kind.INTEREST, cents(2041), -1),
                new Derived(date("2026-11-20"), Kind.INTEREST, cents(2082), -1));
        assertThat(result.statements()).extracting(Statement::balance).containsExactly(cents(102041), cents(104123));
    }

    @Test
    void onlyWhatWasLeftUnpaidIsChargedInterest() {
        Result result = CardProjector.project(
                List.of(owed("2026-10-01", 100000)),
                List.of(charge("2026-10-10", -60000), charge("2026-10-12", 30000)),
                List.of(),
                terms(Mode.STATEMENT_BALANCE),
                date("2026-10-31"));

        // 400.00 of the 1,000.00 was left: 400 × 24.49% ÷ 12 = 8.16. The new 300.00 charge is still in its grace period.
        assertThat(result.derived()).containsExactly(new Derived(date("2026-10-20"), Kind.INTEREST, cents(816), -1));
        assertThat(result.statements().getFirst().balance()).isEqualTo(cents(70816));
    }

    @Test
    void aCardThatIsNotItemizedTakesOneEstimateEachStatementMonth() {
        Result result = CardProjector.project(
                List.of(owed("2026-10-01", 0)),
                List.of(),
                List.of(new Payment(date("2026-11-15"), 1, false), new Payment(date("2026-12-15"), 2, false)),
                terms(Mode.FULL_BALANCE, 45000L, List.of(), Map.of()),
                date("2026-12-31"));

        assertThat(result.derived()).containsExactly(
                new Derived(date("2026-10-20"), Kind.ESTIMATED_SPENDING, cents(45000), -1),
                new Derived(date("2026-11-15"), Kind.PAYMENT, cents(-45000), 1),
                new Derived(date("2026-11-20"), Kind.ESTIMATED_SPENDING, cents(45000), -1),
                new Derived(date("2026-12-15"), Kind.PAYMENT, cents(-45000), 2),
                new Derived(date("2026-12-20"), Kind.ESTIMATED_SPENDING, cents(45000), -1));
    }

    @Test
    void aStatementTheUserEnteredTakesOverFromTheEstimate() {
        Statement real = new Statement(date("2026-10-20"), cents(51230), cents(3500), date("2026-11-15"), Money.ZERO, true);

        Result result = CardProjector.project(
                // The caller also gives the statement's balance as what is owed the day after it closed.
                List.of(owed("2026-10-01", 0), owed("2026-10-21", 51230)),
                List.of(),
                List.of(new Payment(date("2026-11-15"), 1, false)),
                terms(Mode.STATEMENT_BALANCE, 45000L, List.of(), Map.of(date("2026-10-20"), real)),
                date("2026-11-19"));

        // No estimate is added for October, and the payment is the real statement balance.
        assertThat(result.derived()).containsExactly(new Derived(date("2026-11-15"), Kind.PAYMENT, cents(-51230), 1));
        assertThat(result.statements()).containsExactly(real);
    }

    @Test
    void aMinimumPaymentIsTheStatementsMinimum() {
        Statement real = new Statement(date("2026-10-20"), cents(240000), cents(3500), date("2026-11-15"), Money.ZERO, true);

        Result result = CardProjector.project(
                List.of(owed("2026-10-01", 240000), owed("2026-10-21", 240000)),
                List.of(),
                List.of(new Payment(date("2026-11-15"), 1, false)),
                terms(Mode.MINIMUM, null, List.of(), Map.of(date("2026-10-20"), real)),
                date("2026-11-19"));

        assertThat(result.derived()).containsExactly(new Derived(date("2026-11-15"), Kind.PAYMENT, cents(-3500), 1));
    }

    @Test
    void untilAStatementClosesTheMinimumIsEstimatedFromTheBalanceTheCardStartsWith() {
        Result result = CardProjector.project(
                List.of(owed("2026-10-01", 240000)),
                List.of(),
                List.of(new Payment(date("2026-10-15"), 1, false)),
                terms(Mode.MINIMUM),
                date("2026-10-19"));

        // 1% of 2,400.00 is under the usual $25.00 floor.
        assertThat(result.derived()).containsExactly(new Derived(date("2026-10-15"), Kind.PAYMENT, cents(-2500), 1));
    }

    @Test
    void aZeroPercentPromotionCostsNothingUntilItEnds() {
        Promo promo = new Promo(1, BasisPoints.of(0), date("2026-03-15"), date("2027-03-15"), cents(300000), null, false);

        Result result = CardProjector.project(
                List.of(owed("2027-02-01", 240000)), List.of(), List.of(),
                terms(Mode.FIXED, null, List.of(promo), Map.of()), date("2027-03-31"));

        // February's statement is free; March's closes after the promotion ended: 2,400 × 24.49% ÷ 12 = 48.98
        assertThat(result.derived()).containsExactly(new Derived(date("2027-03-20"), Kind.INTEREST, cents(4898), -1));
    }

    @Test
    void deferredInterestIsChargedOnTheLastDayWhenABalanceIsStillThere() {
        Promo promo = new Promo(1, BasisPoints.of(0), date("2026-03-15"), date("2027-03-15"), cents(300000), null, true);

        Result unpaid = CardProjector.project(
                List.of(owed("2027-03-01", 240000)), List.of(), List.of(),
                terms(Mode.FIXED, null, List.of(promo), Map.of()), date("2027-03-19"));
        Result cleared = CardProjector.project(
                List.of(owed("2027-03-01", 240000)), List.of(charge("2027-03-10", -240000)), List.of(),
                terms(Mode.FIXED, null, List.of(promo), Map.of()), date("2027-03-19"));

        assertThat(unpaid.derived()).containsExactly(new Derived(date("2027-03-15"), Kind.DEFERRED_INTEREST, cents(73470), -1));
        assertThat(cleared.derived()).isEmpty();
    }

    @Test
    void aBalanceEnteredLaterReplacesWhatWasWorkedOut() {
        Result result = CardProjector.project(
                List.of(owed("2026-10-01", 100000), owed("2026-11-01", 20000)),
                List.of(),
                List.of(new Payment(date("2026-11-15"), 1, false)),
                terms(Mode.FULL_BALANCE),
                date("2026-11-19"));

        assertThat(result.derived()).containsExactly(
                new Derived(date("2026-10-20"), Kind.INTEREST, cents(2041), -1),
                new Derived(date("2026-11-15"), Kind.PAYMENT, cents(-20000), 1));
    }

    @Test
    void anUnpaidPaymentFromBeforeTheNewestBalanceIsMadeOnThatDay() {
        Result result = CardProjector.project(
                List.of(owed("2026-10-01", 100000), owed("2026-11-01", 20000)),
                List.of(),
                List.of(new Payment(date("2026-10-28"), 1, true)),
                terms(Mode.FULL_BALANCE),
                date("2026-11-19"));

        // It has not been paid yet, so it pays what the newer balance says is owed.
        assertThat(result.derived()).contains(new Derived(date("2026-11-01"), Kind.PAYMENT, cents(-20000), 1));
    }

    @Test
    void aStatementEnteredBeforeTheBalanceItStartsFromIsStillTheOneToPay() {
        // September's statement said 725.00; by October 1 more had been charged and 3,125.00 was owed.
        Statement september = new Statement(date("2026-09-20"), cents(72500), cents(2500), date("2026-10-15"), Money.ZERO, true);
        Result result = CardProjector.project(
                List.of(owed("2026-10-01", 312500)),
                List.of(),
                List.of(new Payment(date("2026-10-15"), 1, false), new Payment(date("2026-11-15"), 2, false)),
                terms(Mode.STATEMENT_BALANCE, null, List.of(), Map.of(date("2026-09-20"), september)),
                date("2026-11-19"));

        // Only the statement is paid; the rest was charged after it closed, so nothing is carried and no interest is charged.
        assertThat(result.derived()).containsExactly(
                new Derived(date("2026-10-15"), Kind.PAYMENT, cents(-72500), 1),
                new Derived(date("2026-11-15"), Kind.PAYMENT, cents(-240000), 2));
        assertThat(result.statements()).extracting(Statement::balance).containsExactly(cents(240000));
        // The first is the amount on a real statement; the second is sized from a projected one.
        assertThat(result.exact()).containsExactly(1);
    }

    @Test
    void anOlderStatementSaysNothingAboutWhatIsDueNow() {
        Statement august = new Statement(date("2026-08-20"), cents(72500), cents(2500), date("2026-09-15"), Money.ZERO, true);
        Result result = CardProjector.project(
                List.of(owed("2026-10-01", 312500)),
                List.of(),
                List.of(new Payment(date("2026-10-15"), 1, false)),
                terms(Mode.STATEMENT_BALANCE, null, List.of(), Map.of(date("2026-08-20"), august)),
                date("2026-10-19"));

        assertThat(result.derived()).containsExactly(new Derived(date("2026-10-15"), Kind.PAYMENT, cents(-312500), 1));
        assertThat(result.exact()).isEmpty();
    }

    @Test
    void nothingIsWorkedOutBeforeABalanceIsKnown() {
        assertThat(CardProjector.project(List.of(), List.of(charge("2026-10-05", 10000)), List.of(), terms(Mode.FULL_BALANCE), date("2026-10-31")))
                .isEqualTo(Result.NONE);
        assertThat(CardProjector.project(List.of(owed("2026-11-01", 100)), List.of(), List.of(), terms(Mode.FULL_BALANCE), date("2026-10-31")))
                .isEqualTo(Result.NONE);
    }
}
