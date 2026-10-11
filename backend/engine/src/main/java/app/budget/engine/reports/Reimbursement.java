package app.budget.engine.reports;

import app.budget.engine.Money;

/**
 * Where an expense stands on being paid back (spec §4.7): how much has come back, how much
 * is still waited for, and how much has not been sent in yet.
 *
 * <p>The status is what the user says; the money paid back is what the money-in entries
 * linked to the expense add up to. The two are kept apart on purpose: someone who never
 * records the money coming in can still mark an expense reimbursed, and a part payment
 * does not close an expense by itself.
 */
public final class Reimbursement {

    private Reimbursement() {}

    public enum Status {
        /** Not something anyone pays back. */
        NONE,
        /** To be sent in. */
        TO_SUBMIT,
        /** Sent in; the money has not all arrived. */
        SUBMITTED,
        /** Done. */
        REIMBURSED
    }

    /**
     * One expense's amount, split by where it stands. Whatever is in none of the three was
     * never to be paid back.
     *
     * @param reimbursed   has come back
     * @param waiting      sent in and still to come
     * @param notSubmitted still to be sent in
     */
    public record Share(Money reimbursed, Money waiting, Money notSubmitted) {

        public static final Share NONE = new Share(Money.ZERO, Money.ZERO, Money.ZERO);

        /** What is still to come back, sent in or not. */
        public Money owed() {
            return waiting.plus(notSubmitted);
        }

        public Share plus(Share other) {
            return new Share(
                    reimbursed.plus(other.reimbursed), waiting.plus(other.waiting), notSubmitted.plus(other.notSubmitted));
        }
    }

    /**
     * @param amount   what the expense cost
     * @param paidBack what the money-in entries linked to it add up to
     */
    public static Share of(Status status, Money amount, Money paidBack) {
        // More can arrive than was spent (a rounded-up refund); only the expense itself is ever "reimbursed".
        Money covered = paidBack.compareTo(amount) > 0 ? amount : paidBack;
        Money owed = amount.minus(covered);
        return switch (status) {
            // Marked done with nothing linked: the user's word is taken for the whole of it.
            case REIMBURSED -> new Share(paidBack.isZero() ? amount : covered, Money.ZERO, Money.ZERO);
            case SUBMITTED -> new Share(covered, owed, Money.ZERO);
            case TO_SUBMIT -> new Share(covered, Money.ZERO, owed);
            case NONE -> new Share(covered, Money.ZERO, Money.ZERO);
        };
    }

    /**
     * The status an expense should have once money paying it back has been linked: reimbursed
     * when all of it is covered, and at least submitted when any of it is, since money does
     * not come back for something never sent in. A status is only ever moved forward.
     */
    public static Status afterPayback(Status status, Money amount, Money paidBack) {
        if (paidBack.isZero() || status == Status.REIMBURSED) {
            return status;
        }
        return paidBack.compareTo(amount) >= 0 ? Status.REIMBURSED : Status.SUBMITTED;
    }
}
