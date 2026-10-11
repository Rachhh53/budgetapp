package app.budget.engine.cards;

import app.budget.engine.Money;
import java.math.BigDecimal;

/**
 * How much a card payment comes to when it follows the card instead of a typed amount
 * (spec §5.6). A payment never exceeds what is owed, and is never negative.
 */
public final class CardPayment {

    private static final BigDecimal BASIS_POINTS = new BigDecimal("10000");

    private CardPayment() {}

    /**
     * How a card's minimum payment is estimated. Issuers differ, so each card has its own.
     *
     * @param shareBp      the share of the statement balance asked for, in basis points (1% is 100)
     * @param floor        the least asked for, when the balance is at least that much
     * @param plusInterest true when that month's interest is added on top of the share
     */
    public record MinimumRule(int shareBp, Money floor, boolean plusInterest) {

        /** What an issuer usually asks for: 1% of the balance plus the month's interest, and at least $25. */
        public static final MinimumRule USUAL = new MinimumRule(100, Money.ofCents(2500), true);
    }

    public enum Mode {
        /** Everything owed on the day of the payment. */
        FULL_BALANCE,
        /** The last statement's balance, less what was paid since it closed. */
        STATEMENT_BALANCE,
        /** The last statement's minimum, less what was paid since it closed. */
        MINIMUM,
        /** The same amount every time. */
        FIXED
    }

    /**
     * @param owed             what the card is owed on the day of the payment
     * @param statementBalance the last statement's balance
     * @param minimumDue       the last statement's minimum
     * @param paidSinceClose   what has been paid since that statement closed
     * @param fixed            the amount for {@link Mode#FIXED}; ignored otherwise
     */
    public static Money amount(Mode mode, Money owed, Money statementBalance, Money minimumDue, Money paidSinceClose, Money fixed) {
        Money wanted = switch (mode) {
            case FULL_BALANCE -> owed;
            case STATEMENT_BALANCE -> statementBalance.minus(paidSinceClose);
            case MINIMUM -> minimumDue.minus(paidSinceClose);
            case FIXED -> fixed;
        };
        if (wanted.isNegative() || owed.isNegative()) {
            return Money.ZERO;
        }
        return wanted.compareTo(owed) > 0 ? owed : wanted;
    }

    /**
     * A stand-in for a statement's minimum until the real one is entered: the larger of the
     * rule's floor and its share of the balance (plus that month's interest, when the rule says
     * so), and never more than the balance itself.
     */
    public static Money estimatedMinimum(Money statementBalance, Money interest, MinimumRule rule) {
        if (statementBalance.isNegative() || statementBalance.isZero()) {
            return Money.ZERO;
        }
        Money share = statementBalance.times(BigDecimal.valueOf(rule.shareBp()).divide(BASIS_POINTS));
        if (rule.plusInterest()) {
            share = share.plus(interest);
        }
        Money minimum = share.compareTo(rule.floor()) > 0 ? share : rule.floor();
        return minimum.compareTo(statementBalance) > 0 ? statementBalance : minimum;
    }
}
