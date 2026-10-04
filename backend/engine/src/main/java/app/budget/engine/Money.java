package app.budget.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * An amount of money held as whole cents.
 *
 * <p>Cents are stored as a {@code long} so sums are exact; {@code double} is never used.
 * Anything fractional (interest, growth, amortization) is worked out in {@link BigDecimal}
 * and comes back through {@link #fromDecimal} or {@link #times}, which apply the one
 * rounding rule the whole app uses: {@link #ROUNDING}.
 */
public record Money(long cents) implements Comparable<Money> {

    /** The single documented rounding rule for turning fractional cents into whole cents. */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    public static final Money ZERO = new Money(0);

    private static final int CENT_SCALE = 2;

    public static Money ofCents(long cents) {
        return cents == 0 ? ZERO : new Money(cents);
    }

    /** Converts a dollar amount (e.g. {@code 14.995}) to cents, rounding half-even. */
    public static Money fromDecimal(BigDecimal dollars) {
        return ofCents(dollars.setScale(CENT_SCALE, ROUNDING).movePointRight(CENT_SCALE).longValueExact());
    }

    // The *Exact methods fail loudly on overflow rather than silently wrapping a balance.
    public Money plus(Money other) {
        return ofCents(Math.addExact(cents, other.cents));
    }

    public Money minus(Money other) {
        return ofCents(Math.subtractExact(cents, other.cents));
    }

    public Money negate() {
        return ofCents(Math.negateExact(cents));
    }

    /** Multiplies by a rate or factor and rounds the result to whole cents. */
    public Money times(BigDecimal factor) {
        return ofCents(BigDecimal.valueOf(cents).multiply(factor).setScale(0, ROUNDING).longValueExact());
    }

    public boolean isNegative() {
        return cents < 0;
    }

    public boolean isZero() {
        return cents == 0;
    }

    /** The amount in dollars with exactly two decimal places — for display and export only. */
    public BigDecimal toDecimal() {
        return BigDecimal.valueOf(cents, CENT_SCALE);
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(cents, other.cents);
    }
}
