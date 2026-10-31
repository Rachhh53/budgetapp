package app.budget.engine.statements;

import app.budget.engine.Money;
import app.budget.engine.statements.StatementRead.Kind;
import java.util.regex.Pattern;

/**
 * Guesses what a statement line is from its words and which way the money went. Payments to a card and transfers are
 * left out of the totals (Rachel, 2026-10-25), since Budget already plans them; a guess can always be changed on the
 * review page.
 */
public final class LineKinds {

    private LineKinds() {}

    /** Words a card's statement uses for a payment made toward it. */
    private static final Pattern PAYMENT = Pattern.compile(
            "(?<![\\p{L}])(payment|pymt|pmt|thank you|autopay|auto pay|epay|e-payment)(?![\\p{L}])", Pattern.CASE_INSENSITIVE);

    /** Words for money moved between accounts, a deposit of pay, or a payment sent to a card from a bank account. */
    private static final Pattern TRANSFER = Pattern.compile(
            "(?<![\\p{L}])(transfer|xfer|trnsfr|direct dep|direct deposit|payroll|deposit|card payment|credit card|crd pmt)"
                    + "(?![\\p{L}])",
            Pattern.CASE_INSENSITIVE);

    /** Words for money given back for something bought. */
    private static final Pattern REFUND = Pattern.compile(
            "(?<![\\p{L}])(refund|return|returned|credit|reversal|adjustment|rebate)(?![\\p{L}])", Pattern.CASE_INSENSITIVE);

    /**
     * @param amount spent as positive, money back as negative
     * @param onCard true for a credit card's statement, where money back is mostly a payment or a refund; on a bank
     *               account money in is mostly pay or a transfer
     */
    public static Kind guess(String description, Money amount, boolean onCard) {
        String words = description == null ? "" : description;
        if (amount.isNegative()) {
            if (onCard) {
                return PAYMENT.matcher(words).find() && !REFUND.matcher(words).find() ? Kind.PAYMENT : Kind.REFUND;
            }
            return REFUND.matcher(words).find() && !TRANSFER.matcher(words).find() ? Kind.REFUND : Kind.TRANSFER;
        }
        // On a card everything spent is a charge; from a bank account, a payment to a card or a move to savings is not.
        return !onCard && TRANSFER.matcher(words).find() ? Kind.TRANSFER : Kind.CHARGE;
    }

    /** True for words that say money came back: a payment, a refund, a deposit or a transfer in. */
    static boolean soundsLikeMoneyBack(String description) {
        String words = description == null ? "" : description;
        return PAYMENT.matcher(words).find() || REFUND.matcher(words).find() || TRANSFER.matcher(words).find();
    }

    /** Payments and transfers are left out of the totals until put back. */
    public static boolean leftOutByDefault(Kind kind) {
        return kind == Kind.PAYMENT || kind == Kind.TRANSFER;
    }
}
