package app.budget.engine.schedule;

/** How often something repeats (spec §4.4). */
public enum Frequency {
    /** Follows the paydays of the primary income source, so it needs the pay periods to resolve. */
    EVERY_PAY_PERIOD,
    WEEKLY,
    BIWEEKLY,
    SEMI_MONTHLY,
    MONTHLY,
    EVERY_N_MONTHS,
    YEARLY,
    ONE_TIME,
    MANUAL_DATES
}
