package app.budget.engine.projection;

import app.budget.engine.Money;
import java.time.LocalDate;

/**
 * A balance the user entered: "the real number on this day is X". Everything dated before it is
 * already in that number, except what is still outstanding.
 *
 * @param endOfDay true when it is the balance the day ended with, so what is dated that day is
 *                 already in it too (every balance entered from 2026-10-30 on). False when it is
 *                 the balance the day began with, so what is dated that day is still to come
 */
public record BalanceAnchor(LocalDate date, Money balance, boolean endOfDay) {

    /** A balance the day began with. */
    public BalanceAnchor(LocalDate date, Money balance) {
        this(date, balance, false);
    }

    /**
     * The first day whose money is not already in this balance. The end of one day and the start
     * of the next are the same moment, so this is where it stands as a balance the day began with.
     */
    public LocalDate settlesFrom() {
        return endOfDay ? date.plusDays(1) : date;
    }

    /** The same balance, as the balance {@link #settlesFrom()} began with. */
    public BalanceAnchor asStartOfDay() {
        return endOfDay ? new BalanceAnchor(settlesFrom(), balance) : this;
    }
}
