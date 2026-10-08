package app.budget.server.accounts;

import java.util.Locale;

/** The five kinds of account (spec §4.2). Finer distinctions are the user's own subtype label. */
public enum AccountType {
    CHECKING,
    SAVINGS,
    CREDIT_CARD,
    INVESTMENT,
    LOAN;

    /** True for checking and savings: the accounts that can hold buckets and earn savings interest. */
    public boolean holdsCash() {
        return this == CHECKING || this == SAVINGS;
    }

    /** As {@link #holdsCash()}, for an account as the API returns it. */
    public static boolean holdsCash(Account account) {
        return valueOf(account.type().toUpperCase(Locale.ROOT)).holdsCash();
    }
}
