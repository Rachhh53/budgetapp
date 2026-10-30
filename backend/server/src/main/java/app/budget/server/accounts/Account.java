package app.budget.server.accounts;

import app.budget.server.api.Archiving;
import java.time.LocalDate;
import java.util.List;

/**
 * An account as the API returns it.
 *
 * @param type                     lower-case {@link AccountType}
 * @param lowBalanceThresholdCents warn when the balance is projected to dip below this;
 *                                 {@code null} when the user has not asked for a warning
 * @param archivedOn               the day it was archived as of: it counts before it and is put away from it on;
 *                                 {@code null} when it is not archived
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
        LocalDate archivedOn,
        Long lowBalanceThresholdCents,
        BalanceEntry latestBalance) {

    /** True when the account is put away on {@code day}: archived as of that day or earlier. */
    public boolean awayOn(LocalDate day) {
        return Archiving.awayOn(archivedOn, day);
    }

    /**
     * A balance the user typed in for one day. Amounts are whole cents.
     *
     * @param endOfDay true when it is what the day ended with, so everything dated that day is already in it: every
     *                 balance entered from 2026-10-30 on. False for one entered before, which is what the day began with
     */
    public record BalanceEntry(long id, LocalDate date, long amountCents, String note, boolean endOfDay) {

        /** The first day whose money is not already in this balance. */
        public LocalDate settlesFrom() {
            return endOfDay ? date.plusDays(1) : date;
        }
    }

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
