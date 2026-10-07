package app.budget.engine.projection;

import app.budget.engine.Money;
import app.budget.engine.schedule.PayPeriod;
import java.time.LocalDate;

/**
 * One account across one pay period. {@code ending = starting + in − out} always holds.
 *
 * @param starting   the balance the period's figures start from
 * @param startingOn the period's first day, or the day of the balance the user entered during it
 * @param entered    true when {@code starting} is a balance the user entered during the period;
 *                   {@code in} and {@code out} then only count that day onwards
 * @param out        money leaving, as a positive amount
 * @param lowest     the lowest end-of-day balance in the period (the starting balance included)
 */
public record PeriodBalance(
        PayPeriod period,
        Money starting,
        LocalDate startingOn,
        boolean entered,
        Money in,
        Money out,
        Money ending,
        Money lowest,
        LocalDate lowestOn) {}
