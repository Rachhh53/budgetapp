package app.budget.engine.schedule;

/** What happens to a date that lands on a weekend or a holiday. */
public enum WeekendRule {
    /** Leave the date where it is. */
    NONE,
    PREVIOUS_BUSINESS_DAY,
    NEXT_BUSINESS_DAY
}
