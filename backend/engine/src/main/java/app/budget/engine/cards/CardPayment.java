package app.budget.engine.cards;

import app.budget.engine.Money;
import java.math.BigDecimal;

/**
 * How much a card payment comes to when it follows the card instead of a typed amount
 * (spec §5.6). A payment never exceeds what is owed, and is never negative.
 */
public final class CardPayment {

    /** The least an issuer usually asks for, when the balance is at least that much. */
    private static final Money MINIMUM_FLOOR = Money.ofCents(2500);

    /** The share of the statement balance an issuer usually asks for, before interest. */
    private static final BigDecimal MINIMUM_SHARE = new BigDecimal("0.01");

    private CardPayment() {}

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
     * A stand-in for a statement's minimum until the real one is entered: the larger of $25 and
     * 1% of the balance plus that month's interest, and never more than the balance itself.
     */
    public static Money estimatedMinimum(Money statementBalance, Money interest) {
        if (statementBalance.isNegative() || statementBalance.isZero()) {
            return Money.ZERO;
        }
        Money share = statementBalance.times(MINIMUM_SHARE).plus(interest);
        Money minimum = share.compareTo(MINIMUM_FLOOR) > 0 ? share : MINIMUM_FLOOR;
        return minimum.compareTo(statementBalance) > 0 ? statementBalance : minimum;
    }
}
