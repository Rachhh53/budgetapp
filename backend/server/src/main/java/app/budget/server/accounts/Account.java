package app.budget.server.accounts;

import java.time.LocalDate;
import java.util.List;

/**
 * An account as the API returns it.
 *
 * @param type          lower-case {@link AccountType}
 * @param latestBalance the most recent balance the user entered, or {@code null} if none yet
 */
public record Account(
        long id,
        String name,
        String institution,
        String type,
        String subtypeLabel,
        String color,
        boolean archived,
        BalanceEntry latestBalance) {

    /** A balance the user typed in for one day. Amounts are whole cents. */
    public record BalanceEntry(long id, LocalDate date, long amountCents, String note) {}

    /** One account with every balance entered for it, newest first. */
    public record Detail(Account account, List<BalanceEntry> balances) {}
}
