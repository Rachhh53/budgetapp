package app.budget.server.entries;

/** What a one-off entry is. Stored and sent in lower case. */
public enum EntryKind {
    MONEY_OUT,
    MONEY_IN,
    TRANSFER
}
