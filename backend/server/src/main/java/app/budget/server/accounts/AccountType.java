package app.budget.server.accounts;

/** The five kinds of account (spec §4.2). Finer distinctions are the user's own subtype label. */
public enum AccountType {
    CHECKING,
    SAVINGS,
    CREDIT_CARD,
    INVESTMENT,
    LOAN
}
