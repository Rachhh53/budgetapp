package app.budget.engine.schedule;

import java.time.LocalDate;

/**
 * One pay period: from a payday of the primary income source up to the day before the next.
 *
 * @param start           the payday that opens the period (after any weekend or holiday move)
 * @param end             the last day of the period, inclusive
 * @param scheduledPayday the payday as the schedule names it, before any move
 */
public record PayPeriod(LocalDate start, LocalDate end, LocalDate scheduledPayday) {

    public boolean contains(LocalDate date) {
        return !date.isBefore(start) && !date.isAfter(end);
    }
}
