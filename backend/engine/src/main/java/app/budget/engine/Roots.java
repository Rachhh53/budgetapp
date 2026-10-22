package app.budget.engine;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Whole-number roots in {@link BigDecimal}, for spreading a yearly rate over parts of a year and
 * for turning a rate over some days into a rate a year. {@code BigDecimal} has no fractional
 * power, and a {@code double} is never used where money depends on the answer.
 */
public final class Roots {

    /** 34 significant digits: far more than cents on any household balance can need. */
    public static final MathContext PRECISION = MathContext.DECIMAL128;

    /** Newton's method settles in a handful of steps; this only stops a root that never would. */
    private static final int MAX_STEPS = 60;

    private Roots() {}

    /**
     * The positive {@code n}th root of {@code value}: the {@code x} with {@code x^n = value}.
     *
     * @throws IllegalArgumentException for a value that is not above zero, or {@code n} below 1
     */
    public static BigDecimal nth(BigDecimal value, int n) {
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("Only a value above zero has a root here");
        }
        if (n < 1) {
            throw new IllegalArgumentException("A root is of 1 or more");
        }
        if (n == 1) {
            return value;
        }
        BigDecimal count = BigDecimal.valueOf(n);
        // Newton's method on x^n = value, from the simple-interest guess 1 + (value − 1) / n. That
        // guess is never below the root (Bernoulli's inequality), and from above every step stays
        // above it, so the answer is reached from one side and a step never lands at or below zero.
        BigDecimal root = BigDecimal.ONE.add(value.subtract(BigDecimal.ONE).divide(count, PRECISION));
        for (int step = 0; step < MAX_STEPS; step++) {
            BigDecimal oneFewer = root.pow(n - 1, PRECISION);
            BigDecimal next = root.subtract(
                    oneFewer.multiply(root, PRECISION).subtract(value).divide(count.multiply(oneFewer, PRECISION), PRECISION),
                    PRECISION);
            if (next.compareTo(root) == 0) {
                break;
            }
            root = next;
        }
        return root;
    }
}
