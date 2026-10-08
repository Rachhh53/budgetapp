package app.budget.server.items;

/** How the user entered an item's amount. Stored and sent in lower case. */
public enum AmountMode {
    /** The amount of each occurrence. */
    PER_OCCURRENCE,
    /** A yearly total, shared out over the schedule's occurrences. */
    ANNUAL
}
