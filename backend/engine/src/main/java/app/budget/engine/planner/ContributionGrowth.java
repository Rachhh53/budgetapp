package app.budget.engine.planner;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.interest.SavingsInterest;
import java.math.BigDecimal;
import java.math.MathContext;

/**
 * What putting the same amount aside every month comes to at an assumed yearly rate
 * (spec §5.9): each amount goes in at the end of its month and grows from then on.
 *
 * <p>A month's growth is the twelfth root of the yearly rate, as for savings interest and
 * assumed growth, so twelve months come to exactly the yearly rate. It is worked out in
 * {@link BigDecimal} and rounded to cents once, at the end.
 */
public final class ContributionGrowth {

    private static final MathContext PRECISION = MathContext.DECIMAL128;

    /** A hundred years: only there to refuse a number that cannot be a comparison period. */
    private static final int MAX_MONTHS = 1200;

    private ContributionGrowth() {}

    /**
     * @param putIn  everything that went in
     * @param growth what the assumed rate added to it
     */
    public record Value(Money putIn, Money growth) {

        public Money total() {
            return putIn.plus(growth);
        }
    }

    /**
     * @param monthly what goes in at the end of every month
     * @param yearly  the assumed rate, zero or more
     * @param months  how long for
     */
    public static Value of(Money monthly, BasisPoints yearly, int months) {
        if (monthly.isNegative()) {
            throw new IllegalArgumentException("The amount put in cannot be negative");
        }
        if (months < 0 || months > MAX_MONTHS) {
            throw new IllegalArgumentException("The period is between 0 and 100 years");
        }
        BigDecimal growthFactor = BigDecimal.ONE.add(SavingsInterest.monthlyRate(yearly));
        BigDecimal each = monthly.toDecimal();
        BigDecimal value = BigDecimal.ZERO;
        for (int month = 0; month < months; month++) {
            value = value.multiply(growthFactor, PRECISION).add(each, PRECISION);
        }
        Money putIn = Money.ofCents(Math.multiplyExact(monthly.cents(), months));
        return new Value(putIn, Money.fromDecimal(value).minus(putIn));
    }
}
