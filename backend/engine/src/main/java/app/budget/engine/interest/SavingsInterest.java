package app.budget.engine.interest;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Estimated savings interest (spec §5.4): once a month, on the account's credit day,
 * {@code balance × ((1 + APY)^(1/12) − 1)}.
 *
 * <p>An APY already includes compounding, so a month's share of it is the twelfth root, not
 * a twelfth. Everything is worked out in {@link BigDecimal} and rounded to cents once, with
 * the app's one rounding rule ({@link Money#ROUNDING}).
 */
public final class SavingsInterest {

    /** A credit day of 31 means "the last day of the month", as it does for schedules. */
    public static final int LAST_DAY = 31;

    /** 34 significant digits: far more than cents on any household balance can need. */
    private static final MathContext PRECISION = MathContext.DECIMAL128;

    private static final int MONTHS_IN_YEAR = 12;

    private static final int DAYS_IN_YEAR = 365;

    /** Newton's method settles in a handful of steps; this only stops a rate that never would. */
    private static final int MAX_STEPS = 60;

    /** 100%, in basis points. */
    private static final int WHOLE_BP = 10_000;

    private SavingsInterest() {}

    /** One month's rate for a yearly APY: {@code (1 + APY)^(1/12) − 1}. */
    public static BigDecimal monthlyRate(BasisPoints apy) {
        requireNotNegative(apy);
        return partOfAYearRate(apy, MONTHS_IN_YEAR);
    }

    /**
     * One month's share of a yearly change that may be a loss: {@code (1 + rate)^(1/12) − 1},
     * which is below zero for a negative rate. For something whose worth can fall, such as a
     * car; interest is never negative, so {@link #monthlyRate} refuses one.
     *
     * @throws IllegalArgumentException for a loss of 100% a year or more, which has no monthly share
     */
    public static BigDecimal monthlyChange(BasisPoints yearly) {
        return partOfAYearRate(yearly, MONTHS_IN_YEAR);
    }

    /**
     * One day's rate for a yearly rate: {@code (1 + rate)^(1/365) − 1}. A year of these, each on
     * a balance that holds the days before, comes to the yearly rate; a leap year's extra day
     * adds one more, which an assumption can bear.
     */
    public static BigDecimal dailyRate(BasisPoints apy) {
        requireNotNegative(apy);
        return partOfAYearRate(apy, DAYS_IN_YEAR);
    }

    private static void requireNotNegative(BasisPoints apy) {
        if (apy.value() < 0) {
            throw new IllegalArgumentException("An APY cannot be negative");
        }
    }

    /** The rate for one of {@code parts} equal parts of a year: {@code (1 + APY)^(1/parts) − 1}. */
    private static BigDecimal partOfAYearRate(BasisPoints apy, int parts) {
        if (apy.value() <= -WHOLE_BP) {
            throw new IllegalArgumentException("A yearly loss must be less than 100%");
        }
        BigDecimal count = BigDecimal.valueOf(parts);
        BigDecimal target = BigDecimal.ONE.add(apy.toFraction());
        // Newton's method on x^parts = target, starting from the simple-interest guess.
        BigDecimal root = BigDecimal.ONE.add(apy.toFraction().divide(count, PRECISION));
        for (int step = 0; step < MAX_STEPS; step++) {
            BigDecimal oneFewer = root.pow(parts - 1, PRECISION);
            BigDecimal next = root.subtract(
                    oneFewer.multiply(root, PRECISION).subtract(target).divide(count.multiply(oneFewer, PRECISION), PRECISION),
                    PRECISION);
            if (next.compareTo(root) == 0) {
                break;
            }
            root = next;
        }
        return root.subtract(BigDecimal.ONE);
    }

    /** The interest one month earns on {@code balance}. An overdrawn or empty account earns nothing. */
    public static Money forMonth(Money balance, BasisPoints apy) {
        if (balance.isNegative() || balance.isZero()) {
            return Money.ZERO;
        }
        return balance.times(monthlyRate(apy));
    }

    /**
     * The days interest is credited within {@code from}–{@code to} (both inclusive), earliest first.
     *
     * @param creditDay day of the month, 1–31; a month without that day uses its last day
     */
    public static List<LocalDate> creditDates(int creditDay, LocalDate from, LocalDate to) {
        if (creditDay < 1 || creditDay > LAST_DAY) {
            throw new IllegalArgumentException("A credit day is a day of the month, 1 to 31");
        }
        List<LocalDate> dates = new ArrayList<>();
        for (YearMonth month = YearMonth.from(from); !month.atDay(1).isAfter(to); month = month.plusMonths(1)) {
            LocalDate date = month.atDay(Math.min(creditDay, month.lengthOfMonth()));
            if (!date.isBefore(from) && !date.isAfter(to)) {
                dates.add(date);
            }
        }
        return dates;
    }
}
