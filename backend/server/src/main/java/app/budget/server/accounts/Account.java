package app.budget.server.accounts;

import java.time.LocalDate;
import java.util.List;

/**
 * An account as the API returns it.
 *
 * @param type                     lower-case {@link AccountType}
 * @param lowBalanceThresholdCents warn when the balance is projected to dip below this;
 *                                 {@code null} when the user has not asked for a warning
 * @param latestBalance            the most recent balance the user entered, or {@code null} if none yet
 */
public record Account(
        long id,
        String name,
        String institution,
        String type,
        String subtypeLabel,
        String color,
        boolean archived,
        Long lowBalanceThresholdCents,
        BalanceEntry latestBalance) {

    /** A balance the user typed in for one day. Amounts are whole cents. */
    public record BalanceEntry(long id, LocalDate date, long amountCents, String note) {}

    /**
     * One account with everything entered for it.
     *
     * @param balances newest first
     * @param interest when interest is credited and where it goes; the defaults until the user chooses
     * @param rates    newest first
     */
    public record Detail(
            Account account, List<BalanceEntry> balances, InterestRepository.Settings interest, List<InterestRepository.Rate> rates) {}
}
