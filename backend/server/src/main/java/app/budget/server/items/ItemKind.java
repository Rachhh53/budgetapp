package app.budget.server.items;

/** What a recurring item is. Stored and sent in lower case. */
public enum ItemKind {
    BILL,
    TRANSFER,
    BUDGET,
    /** Moves money into a bucket inside one account. Arrives with buckets (Phase 3). */
    BUCKET_MOVE,
    CARD_PAYMENT
}
