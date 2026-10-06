package app.budget.engine.schedule;

import java.time.LocalDate;

/**
 * One date produced by a schedule.
 *
 * @param scheduled the date the rule names (e.g. the 1st), before any weekend or holiday move.
 *                  It never changes when holidays do, so it is the stable key for a single
 *                  occurrence (per-paycheck edits are stored against it).
 * @param date      the date it actually happens on
 */
public record Occurrence(LocalDate scheduled, LocalDate date) {

    /** True when a weekend or holiday moved this occurrence off its scheduled date. */
    public boolean moved() {
        return !scheduled.equals(date);
    }
}
