package app.budget.engine.items;

/** Which paycheck pays a recurring item (spec §5.2). */
public enum Placement {
    /** The pay period the due date falls in. */
    DUE_PERIOD,
    /** The pay period before that one, so the money is set aside a paycheck ahead. */
    ONE_EARLY
}
