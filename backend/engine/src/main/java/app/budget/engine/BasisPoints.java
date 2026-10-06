package app.budget.engine;

import java.math.BigDecimal;

/**
 * A rate held as whole basis points: 4.10% is {@code 410}.
 *
 * <p>Storing rates as integers keeps them exact in the database; {@link #toFraction()}
 * is the only place they become a decimal, ready for {@link BigDecimal} maths.
 */
public record BasisPoints(int value) {

    private static final int FRACTION_SCALE = 4;

    public static BasisPoints of(int value) {
        return new BasisPoints(value);
    }

    /** The rate as a plain fraction: 410 bp becomes {@code 0.0410}. */
    public BigDecimal toFraction() {
        return BigDecimal.valueOf(value, FRACTION_SCALE);
    }
}
