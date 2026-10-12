package app.budget.engine.reports;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.Money;
import app.budget.engine.reports.Reimbursement.Share;
import app.budget.engine.reports.Reimbursement.Status;
import org.junit.jupiter.api.Test;

/** Where an expense stands on being paid back (spec §4.7). */
class ReimbursementTest {

    private static final Money COST = Money.ofCents(8_500);

    private static Share share(long reimbursed, long waiting, long notSubmitted) {
        return new Share(Money.ofCents(reimbursed), Money.ofCents(waiting), Money.ofCents(notSubmitted));
    }

    @Test
    void anExpenseNobodyPaysBackHasNothingInAnyShare() {
        assertThat(Reimbursement.of(Status.NONE, COST, Money.ZERO)).isEqualTo(Share.NONE);
    }

    @Test
    void anExpenseStillToSendInIsAllNotSubmitted() {
        assertThat(Reimbursement.of(Status.TO_SUBMIT, COST, Money.ZERO)).isEqualTo(share(0, 0, 8_500));
    }

    @Test
    void anExpenseSentInIsWaitedForLessWhatHasAlreadyCome() {
        // $85.00 sent in, $60.00 back so far: $25.00 still to come.
        Share share = Reimbursement.of(Status.SUBMITTED, COST, Money.ofCents(6_000));

        assertThat(share).isEqualTo(share(6_000, 2_500, 0));
        assertThat(share.owed()).isEqualTo(Money.ofCents(2_500));
    }

    @Test
    void markedReimbursedWithNoMoneyLinkedCountsInFull() {
        assertThat(Reimbursement.of(Status.REIMBURSED, COST, Money.ZERO)).isEqualTo(share(8_500, 0, 0));
    }

    @Test
    void markedReimbursedAfterAPartPaymentCountsOnlyWhatCameBackAndOwesNothing() {
        // The insurer paid $60.00 of $85.00 and that is the end of it.
        Share share = Reimbursement.of(Status.REIMBURSED, COST, Money.ofCents(6_000));

        assertThat(share).isEqualTo(share(6_000, 0, 0));
        assertThat(share.owed()).isEqualTo(Money.ZERO);
    }

    @Test
    void moreComingBackThanWasSpentNeverCountsAsMoreThanTheExpense() {
        assertThat(Reimbursement.of(Status.SUBMITTED, COST, Money.ofCents(9_000))).isEqualTo(share(8_500, 0, 0));
    }

    @Test
    void sharesAddUp() {
        assertThat(share(100, 20, 3).plus(share(1, 2, 3))).isEqualTo(share(101, 22, 6));
    }

    @Test
    void moneyCoveringTheWholeExpenseMakesItReimbursed() {
        assertThat(Reimbursement.afterPayback(Status.SUBMITTED, COST, COST)).isEqualTo(Status.REIMBURSED);
        assertThat(Reimbursement.afterPayback(Status.NONE, COST, Money.ofCents(9_000))).isEqualTo(Status.REIMBURSED);
    }

    @Test
    void aPartPaymentMakesItSubmittedAtLeast() {
        assertThat(Reimbursement.afterPayback(Status.NONE, COST, Money.ofCents(100))).isEqualTo(Status.SUBMITTED);
        assertThat(Reimbursement.afterPayback(Status.TO_SUBMIT, COST, Money.ofCents(100))).isEqualTo(Status.SUBMITTED);
        assertThat(Reimbursement.afterPayback(Status.SUBMITTED, COST, Money.ofCents(100))).isEqualTo(Status.SUBMITTED);
    }

    @Test
    void aStatusIsNeverMovedBack() {
        assertThat(Reimbursement.afterPayback(Status.REIMBURSED, COST, Money.ofCents(100))).isEqualTo(Status.REIMBURSED);
        assertThat(Reimbursement.afterPayback(Status.TO_SUBMIT, COST, Money.ZERO)).isEqualTo(Status.TO_SUBMIT);
    }
}
