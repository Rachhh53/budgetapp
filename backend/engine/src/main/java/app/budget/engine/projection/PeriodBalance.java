package app.budget.engine.projection;

import app.budget.engine.Money;
import app.budget.engine.schedule.PayPeriod;
import java.time.LocalDate;
import java.util.List;

/**
 * One account across one pay period. {@code ending = starting + in − out + adjusted} always holds, and a period
 * starts with what the one before ended on (Rachel, 2026-10-21: "previous pay period available after buckets always
 * equals next period starting available").
 *
 * @param starting   the balance the period's figures start from: the period before's ending, or for the period
 *                   holding the first balance ever entered, that balance
 * @param startingOn the period's first day, or the day of the first balance ever entered when it falls part way through
 * @param entered    true when {@code starting} is the first balance ever entered; {@code in} and {@code out} then
 *                   only count that day onwards
 * @param out        money leaving, as a positive amount
 * @param adjusted   what balances entered during the period moved it, signed: the sum of {@code adjustments}
 * @param adjustments each balance entered during the period, earliest first, with what it moved the account
 * @param lowest     the lowest end-of-day balance in the period (the starting balance included)
 */
public record PeriodBalance(
        PayPeriod period,
        Money starting,
        LocalDate startingOn,
        boolean entered,
        Money in,
        Money out,
        Money adjusted,
        List<Adjustment> adjustments,
        Money ending,
        Money lowest,
        LocalDate lowestOn) {

    /**
     * A balance the user entered during a period that it did not start from.
     *
     * @param balance    what was entered: what the account held as {@code date} ended, or for one entered before
     *                   2026-10-30, as it began
     * @param difference that less what the account was expected to hold then, signed
     * @param endOfDay   true when {@code balance} is what the day ended with
     */
    public record Adjustment(LocalDate date, Money balance, Money difference, boolean endOfDay) {}
}
