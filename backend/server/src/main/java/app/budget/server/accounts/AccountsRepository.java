package app.budget.server.accounts;

import app.budget.server.accounts.Account.BalanceEntry;
import app.budget.server.api.Inputs;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jdbi.v3.core.Jdbi;

/** Reads and writes {@code accounts} and {@code balance_entries}. */
public final class AccountsRepository {

    /**
     * Every account column plus its newest balance entry as of today (NULLs when none has been entered). A balance planned
     * for a day still to come is not its balance yet.
     */
    private static final String SELECT_WITH_LATEST_BALANCE = """
            SELECT a.id, a.name, a.institution, a.type, a.subtype_label, a.color, a.archived, a.archived_on, a.low_balance_threshold,
                   b.id AS balance_id, b.date AS balance_date, b.amount AS balance_amount, b.note AS balance_note
            FROM accounts a
            LEFT JOIN balance_entries b ON b.id = (
                SELECT id FROM balance_entries WHERE account_id = a.id AND date <= :today ORDER BY date DESC LIMIT 1)
            """;

    private final Jdbi jdbi;
    private final Clock clock;

    public AccountsRepository(Jdbi jdbi, Clock clock) {
        this.jdbi = jdbi;
        this.clock = clock;
    }

    /**
     * What the user can set on an account.
     *
     * @param archivedOn               the day it is archived as of; {@code null} when it is not archived
     * @param lowBalanceThresholdCents {@code null} for no low-balance warning
     */
    public record Fields(
            String name,
            String institution,
            AccountType type,
            String subtypeLabel,
            String color,
            LocalDate archivedOn,
            Long lowBalanceThresholdCents) {}

    public List<Account> all() {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT_WITH_LATEST_BALANCE + "ORDER BY a.sort_order, a.id")
                .bind("today", LocalDate.now(clock).toString())
                .map((rs, ctx) -> account(rs))
                .list());
    }

    public Optional<Account> find(long id) {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT_WITH_LATEST_BALANCE + "WHERE a.id = :id")
                .bind("today", LocalDate.now(clock).toString())
                .bind("id", id)
                .map((rs, ctx) -> account(rs))
                .findOne());
    }

    public boolean exists(long id) {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT EXISTS (SELECT 1 FROM accounts WHERE id = :id)")
                .bind("id", id)
                .mapTo(Boolean.class)
                .one());
    }

    /** Adds an account at the end of the list and returns its id. */
    public long create(Fields fields) {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        INSERT INTO accounts (name, institution, type, subtype_label, color, archived, archived_on,
                                              low_balance_threshold, sort_order, created_at)
                        VALUES (:name, :institution, :type, :subtypeLabel, :color, :archived, :archivedOn, :lowBalanceThreshold,
                                (SELECT COALESCE(MAX(sort_order), 0) + 1 FROM accounts), :now)
                        RETURNING id
                        """)
                .bind("name", fields.name())
                .bind("institution", fields.institution())
                .bind("type", Inputs.code(fields.type()))
                .bind("subtypeLabel", fields.subtypeLabel())
                .bind("color", fields.color())
                .bind("archived", fields.archivedOn() != null)
                .bind("archivedOn", fields.archivedOn() == null ? null : fields.archivedOn().toString())
                .bind("lowBalanceThreshold", fields.lowBalanceThresholdCents())
                .bind("now", clock.instant().toString())
                .mapTo(Long.class)
                .one());
    }

    /** @return false when there is no such account */
    public boolean update(long id, Fields fields) {
        return jdbi.withHandle(handle -> handle
                .createUpdate("""
                        UPDATE accounts
                        SET name = :name, institution = :institution, type = :type,
                            subtype_label = :subtypeLabel, color = :color, archived = :archived, archived_on = :archivedOn,
                            low_balance_threshold = :lowBalanceThreshold
                        WHERE id = :id
                        """)
                .bind("id", id)
                .bind("name", fields.name())
                .bind("institution", fields.institution())
                .bind("type", Inputs.code(fields.type()))
                .bind("subtypeLabel", fields.subtypeLabel())
                .bind("color", fields.color())
                .bind("archived", fields.archivedOn() != null)
                .bind("archivedOn", fields.archivedOn() == null ? null : fields.archivedOn().toString())
                .bind("lowBalanceThreshold", fields.lowBalanceThresholdCents())
                .execute()) > 0;
    }

    /**
     * True when a paycheck is deposited to, or a deduction or employer match goes into, this account; a paycheck
     * planned for next year in a draft plan counts too.
     */
    public boolean isUsedByIncome(long id) {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        SELECT EXISTS (SELECT 1 FROM income_sources WHERE deposit_account_id = :id)
                            OR EXISTS (SELECT 1 FROM paycheck_deductions WHERE to_account_id = :id)
                            OR EXISTS (SELECT 1 FROM one_off_paycheck_deductions WHERE to_account_id = :id)
                            OR EXISTS (SELECT 1 FROM employer_match WHERE to_account_id = :id)
                            OR EXISTS (SELECT 1 FROM income_pay_changes WHERE match_to_account_id = :id)
                            OR EXISTS (SELECT 1 FROM plan_year_pay p JOIN plan_years y ON y.id = p.plan_year_id
                                       WHERE y.status = 'draft' AND (p.match_to_account_id = :id OR EXISTS (
                                           SELECT 1 FROM plan_year_pay_deductions d WHERE d.plan_year_pay_id = p.id AND d.to_account_id = :id)))
                        """)
                .bind("id", id)
                .mapTo(Boolean.class)
                .one());
    }

    /** True when a recurring item or a one-off entry pays from, or into, this account. */
    public boolean isUsedByItemsOrEntries(long id) {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        SELECT EXISTS (SELECT 1 FROM item_versions WHERE pay_from_account_id = :id OR pay_to_account_id = :id)
                            OR EXISTS (SELECT 1 FROM entries WHERE account_id = :id OR to_account_id = :id)
                            OR EXISTS (SELECT 1 FROM item_reimbursement_settings WHERE into_account_id = :id OR from_account_id = :id)
                            OR EXISTS (SELECT 1 FROM payment_reimbursements WHERE from_account_id = :id)
                            OR EXISTS (SELECT 1 FROM pack_reimbursements WHERE from_account_id = :id)
                            OR EXISTS (SELECT 1 FROM entries
                                       WHERE reimbursement_into_account_id = :id OR reimbursement_from_account_id = :id)
                        """)
                .bind("id", id)
                .mapTo(Boolean.class)
                .one());
    }

    /** Deletes the account and, with it, the balances entered for it. */
    public boolean delete(long id) {
        return jdbi.withHandle(handle -> handle
                .createUpdate("DELETE FROM accounts WHERE id = :id")
                .bind("id", id)
                .execute()) > 0;
    }

    /** Balances entered for an account, newest first. */
    public List<BalanceEntry> balances(long accountId) {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        SELECT id, date, amount, note FROM balance_entries
                        WHERE account_id = :accountId ORDER BY date DESC
                        """)
                .bind("accountId", accountId)
                .map((rs, ctx) -> new BalanceEntry(
                        rs.getLong("id"), LocalDate.parse(rs.getString("date")), rs.getLong("amount"), rs.getString("note")))
                .list());
    }

    /** Every balance entered, by account, oldest first: what projections start from. */
    public Map<Long, List<BalanceEntry>> allBalances() {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT id, account_id, date, amount, note FROM balance_entries ORDER BY date")
                .map((rs, ctx) -> Map.entry(rs.getLong("account_id"), new BalanceEntry(
                        rs.getLong("id"), LocalDate.parse(rs.getString("date")), rs.getLong("amount"), rs.getString("note"))))
                .stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList()))));
    }

    /** Records the balance for a day. Entering the same day again replaces what was there. */
    public void saveBalance(long accountId, LocalDate date, long amountCents, String note) {
        jdbi.useHandle(handle -> handle
                .createUpdate("""
                        INSERT INTO balance_entries (account_id, date, amount, note)
                        VALUES (:accountId, :date, :amount, :note)
                        ON CONFLICT (account_id, date) DO UPDATE SET amount = excluded.amount, note = excluded.note
                        """)
                .bind("accountId", accountId)
                .bind("date", date.toString())
                .bind("amount", amountCents)
                .bind("note", note)
                .execute());
    }

    /** A balance for one account, as part of several entered together. */
    public record NewBalance(long accountId, long amountCents) {}

    /** Records several accounts' balances for the same day, all of them or none. */
    public void saveBalances(LocalDate date, List<NewBalance> balances) {
        jdbi.useTransaction(handle -> {
            for (NewBalance balance : balances) {
                handle.createUpdate("""
                                INSERT INTO balance_entries (account_id, date, amount, note)
                                VALUES (:accountId, :date, :amount, NULL)
                                ON CONFLICT (account_id, date) DO UPDATE SET amount = excluded.amount
                                """)
                        .bind("accountId", balance.accountId())
                        .bind("date", date.toString())
                        .bind("amount", balance.amountCents())
                        .execute();
            }
        });
    }

    /** True when an employer match is paid into this account, or is planned to be in a draft plan. */
    public boolean receivesEmployerMatch(long id) {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        SELECT EXISTS (SELECT 1 FROM employer_match WHERE to_account_id = :id)
                            OR EXISTS (SELECT 1 FROM income_pay_changes WHERE match_to_account_id = :id)
                            OR EXISTS (SELECT 1 FROM plan_year_pay p JOIN plan_years y ON y.id = p.plan_year_id
                                       WHERE y.status = 'draft' AND p.match_to_account_id = :id)
                        """)
                .bind("id", id)
                .mapTo(Boolean.class)
                .one());
    }

    public boolean deleteBalance(long accountId, long entryId) {
        return jdbi.withHandle(handle -> handle
                .createUpdate("DELETE FROM balance_entries WHERE id = :entryId AND account_id = :accountId")
                .bind("entryId", entryId)
                .bind("accountId", accountId)
                .execute()) > 0;
    }

    private static Account account(ResultSet rs) throws SQLException {
        String balanceDate = rs.getString("balance_date");
        BalanceEntry latest = balanceDate == null ? null : new BalanceEntry(
                rs.getLong("balance_id"), LocalDate.parse(balanceDate), rs.getLong("balance_amount"), rs.getString("balance_note"));
        long threshold = rs.getLong("low_balance_threshold");
        boolean noThreshold = rs.wasNull();
        return new Account(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("institution"),
                rs.getString("type"),
                rs.getString("subtype_label"),
                rs.getString("color"),
                rs.getBoolean("archived"),
                rs.getString("archived_on") == null ? null : LocalDate.parse(rs.getString("archived_on")),
                noThreshold ? null : threshold,
                latest);
    }
}
