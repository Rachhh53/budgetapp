package app.budget.engine.investments;

import app.budget.engine.Money;
import app.budget.engine.Roots;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * What an investment really grew by a year, from two balances the user entered (spec §5.5),
 * to set beside the growth she assumed.
 *
 * <p>Money put in or taken out between the two is not growth, but it was there to grow for
 * part of the time, so the rate is a Modified Dietz return: the gain or loss over the
 * starting balance plus each deposit weighted by the share of the span it was in the account.
 * That is then put as a rate a year, {@code (1 + return)^(365 / days) − 1}, the same way an
 * assumed rate is spread over the days (compounded).
 *
 * <p>Only entered balances are ever passed in: a projection that turned out to be off says
 * nothing about how the account really did.
 */
public final class ActualGrowth {

    /**
     * Under this many days between the two balances no yearly rate is given. A few weeks of
     * a market's ups and downs, put as a year, gives a figure nobody should plan by.
     */
    public static final int MIN_DAYS_FOR_RATE = 90;

    private static final int DAYS_IN_YEAR = 365;

    /** 100%, in basis points. */
    private static final BigDecimal WHOLE_BP = BigDecimal.valueOf(10_000);

    private ActualGrowth() {}

    /** Money that moved the account on a day: positive put in, negative taken out. */
    public record Flow(LocalDate date, Money amount) {}

    /**
     * @param growth       the change that what was put in does not explain ({@link GainLoss})
     * @param yearlyRateBp that as a rate a year, in basis points (below zero for a loss);
     *                     {@code null} when the balances are under {@link #MIN_DAYS_FOR_RATE}
     *                     days apart, or nothing was in the account for long enough to give one
     */
    public record Result(Money growth, Integer yearlyRateBp) {}

    /**
     * @param from     the day of the earlier balance; it stands for the start of that day
     * @param starting the earlier balance entered
     * @param to       the day of the later balance, after {@code from}
     * @param ending   the later balance entered
     * @param flows    what moved the account from {@code from} to the day before {@code to}
     */
    public static Result between(LocalDate from, Money starting, LocalDate to, Money ending, List<Flow> flows) {
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("The later balance must be on a later day");
        }
        Money net = flows.stream().map(Flow::amount).reduce(Money.ZERO, Money::plus);
        Money growth = GainLoss.between(starting, ending, net);
        long days = ChronoUnit.DAYS.between(from, to);
        if (days < MIN_DAYS_FOR_RATE) {
            return new Result(growth, null);
        }

        // A balance holds the start of its day, so money moved on day d has (to − d) of the span's days to grow.
        BigDecimal span = BigDecimal.valueOf(days);
        BigDecimal invested = starting.toDecimal();
        for (Flow flow : flows) {
            BigDecimal share = BigDecimal.valueOf(ChronoUnit.DAYS.between(flow.date(), to)).divide(span, Roots.PRECISION);
            invested = invested.add(flow.amount().toDecimal().multiply(share, Roots.PRECISION), Roots.PRECISION);
        }
        if (invested.signum() <= 0) {
            // Nothing (or less than nothing) was there to grow: there is no rate to speak of.
            return new Result(growth, null);
        }
        BigDecimal factor = BigDecimal.ONE.add(growth.toDecimal().divide(invested, Roots.PRECISION));
        if (factor.signum() <= 0) {
            // Lost all of it, or more: a rate a year cannot say that.
            return new Result(growth, null);
        }
        BigDecimal yearly = Roots.nth(factor, Math.toIntExact(days)).pow(DAYS_IN_YEAR, Roots.PRECISION).subtract(BigDecimal.ONE);
        BigDecimal bp = yearly.multiply(WHOLE_BP).setScale(0, Money.ROUNDING);
        if (bp.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            // Many times over in a few months (a balance typed with a digit too many, most likely):
            // no rate a year could be read sensibly, and it would not fit in basis points.
            return new Result(growth, null);
        }
        return new Result(growth, bp.intValueExact());
    }
}
