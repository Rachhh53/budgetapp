package app.budget.server.items;

import app.budget.engine.Money;
import app.budget.engine.reports.Reimbursement;
import app.budget.engine.reports.Reimbursement.Share;
import app.budget.engine.reports.Reimbursement.Status;
import app.budget.server.api.Inputs;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/**
 * Recurring payments that are paid back (Rachel, 2026-10-24: "only payments I flag and a set amount defaulted to the
 * payment being flagged"): how each item is paid back, and each payment flagged for it (migration V46).
 */
public final class ReimbursementsRepository {

    /**
     * What is to come back is found from the money that came in for it and the money moved out of a bucket for it (a move
     * out is stored as a negative change, so it is taken away to count it up), as for a one-off expense.
     */
    private static final String SELECT = """
            SELECT r.id, r.item_id, r.original_due_date, r.amount, r.status, r.submitted_on, r.reimbursement_to, r.from_account_id,
                   coalesce((SELECT sum(amount) FROM entries WHERE reimburses_payment_id = r.id), 0)
                   - coalesce((SELECT sum(amount) FROM bucket_changes WHERE reimburses_payment_id = r.id), 0) AS paid_back
            FROM payment_reimbursements r
            """;

    private final Jdbi jdbi;

    public ReimbursementsRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /**
     * How an item is paid back, set once on the item.
     *
     * @param reimbursedBy  who pays it back ("HSA", "Work"); {@code null} when not said
     * @param daysAfter     about how many days after each payment the money comes back
     * @param intoAccountId the account it comes back into
     * @param fromAccountId the account it comes out of, like an FSA; {@code null} when it comes from outside (migration V48)
     */
    public record Settings(long itemId, String reimbursedBy, int daysAfter, long intoAccountId, Long fromAccountId) {}

    /**
     * One payment flagged to be paid back.
     *
     * @param scheduledDate the date the item's schedule names, which identifies the payment
     * @param amountCents   what is to come back: the payment's amount when it was flagged, unless changed by hand
     * @param submittedOn   the day it was sent in; {@code null} when not said
     * @param to            who it was sent to; {@code null} when not said
     * @param paidBackCents what has come back for it so far
     * @param fromAccountId the account it comes out of; {@code null} from outside. The item's when flagged, unless changed
     */
    public record Flagged(
            long id, long itemId, LocalDate scheduledDate, long amountCents, Status status, LocalDate submittedOn, String to,
            long paidBackCents, Long fromAccountId) {

        /** How much of it counts as having come back, and how much is still to come. */
        public Share share() {
            return Reimbursement.of(status, Money.ofCents(amountCents), Money.ofCents(paidBackCents));
        }
    }

    /**
     * A planned pack flagged to be paid back.
     *
     * @param amountCents   what comes back
     * @param fromAccountId the account it comes out of; {@code null} from outside
     */
    public record PackFlag(long amountCents, Long fromAccountId) {}

    /** Identifies a payment: an item and the date its schedule names. */
    public record PaymentKey(long itemId, LocalDate scheduledDate) {}

    public Optional<Settings> settings(long itemId) {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT * FROM item_reimbursement_settings WHERE item_id = :id")
                .bind("id", itemId)
                .map((rs, ctx) -> settings(rs))
                .findOne());
    }

    /** Every item's settings, by item. */
    public Map<Long, Settings> allSettings() {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT * FROM item_reimbursement_settings")
                .map((rs, ctx) -> settings(rs))
                .list()
                .stream()
                .collect(Collectors.toMap(Settings::itemId, Function.identity())));
    }

    public void saveSettings(Settings settings) {
        jdbi.useHandle(handle -> handle
                .createUpdate("""
                        INSERT INTO item_reimbursement_settings (item_id, reimbursed_by, days_after, into_account_id, from_account_id)
                        VALUES (:itemId, :by, :days, :into, :from)
                        ON CONFLICT (item_id) DO UPDATE
                        SET reimbursed_by = excluded.reimbursed_by, days_after = excluded.days_after,
                            into_account_id = excluded.into_account_id, from_account_id = excluded.from_account_id
                        """)
                .bind("itemId", settings.itemId())
                .bind("by", settings.reimbursedBy())
                .bind("days", settings.daysAfter())
                .bind("into", settings.intoAccountId())
                .bind("from", settings.fromAccountId())
                .execute());
    }

    /** Every payment flagged, oldest first. */
    public List<Flagged> all() {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT + "ORDER BY r.original_due_date, r.id")
                .map((rs, ctx) -> flagged(rs))
                .list());
    }

    /** Every payment flagged, by the payment. */
    public Map<PaymentKey, Flagged> byPayment() {
        return all().stream().collect(Collectors.toMap(flagged -> new PaymentKey(flagged.itemId(), flagged.scheduledDate()), Function.identity()));
    }

    /** An item's payments flagged, oldest first. */
    public List<Flagged> ofItem(long itemId) {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT + "WHERE r.item_id = :id ORDER BY r.original_due_date")
                .bind("id", itemId)
                .map((rs, ctx) -> flagged(rs))
                .list());
    }

    public Optional<Flagged> find(long id) {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT + "WHERE r.id = :id")
                .bind("id", id)
                .map((rs, ctx) -> flagged(rs))
                .findOne());
    }

    public Optional<Flagged> find(long itemId, LocalDate scheduledDate) {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT + "WHERE r.item_id = :itemId AND r.original_due_date = :date")
                .bind("itemId", itemId)
                .bind("date", scheduledDate.toString())
                .map((rs, ctx) -> flagged(rs))
                .findOne());
    }

    /**
     * Flags a payment to be paid back, or changes how much is to come back when it already is. A newly flagged one is still
     * to be sent in. @param fromAccountId the account it comes out of; {@code null} from outside @return its id
     */
    public long flag(long itemId, LocalDate scheduledDate, long amountCents, Long fromAccountId) {
        return jdbi.inTransaction(handle -> {
            long id = handle.createQuery("""
                            INSERT INTO payment_reimbursements (item_id, original_due_date, amount, status, from_account_id)
                            VALUES (:itemId, :date, :amount, 'to_submit', :from)
                            ON CONFLICT (item_id, original_due_date) DO UPDATE
                            SET amount = excluded.amount, from_account_id = excluded.from_account_id
                            RETURNING id
                            """)
                    .bind("itemId", itemId)
                    .bind("date", scheduledDate.toString())
                    .bind("amount", amountCents)
                    .bind("from", fromAccountId)
                    .mapTo(Long.class)
                    .one();
            // A smaller amount may now be all paid back.
            settle(handle, id);
            return id;
        });
    }

    /** Sets where a flagged payment stands. Not for {@link Status#NONE}: that is {@link #unflag}. @return false when not there */
    public boolean setStatus(long id, Status status, LocalDate submittedOn, String to) {
        return jdbi.withHandle(handle -> handle
                .createUpdate("""
                        UPDATE payment_reimbursements SET status = :status, submitted_on = :submittedOn, reimbursement_to = :to
                        WHERE id = :id
                        """)
                .bind("status", Inputs.code(status))
                .bind("submittedOn", submittedOn == null ? null : submittedOn.toString())
                .bind("to", to)
                .bind("id", id)
                .execute()) > 0;
    }

    /**
     * Nobody pays this payment back after all. Money that came back for it stays where it is, paying nothing back (the
     * links are let go by the database). @return false when it was not flagged
     */
    public boolean unflag(long id) {
        return jdbi.withHandle(handle -> handle
                .createUpdate("DELETE FROM payment_reimbursements WHERE id = :id")
                .bind("id", id)
                .execute()) > 0;
    }

    /**
     * The planned packs of each paid-ahead item flagged to be paid back (migration V47): by item, then by place in line (1
     * is the next pack to buy), what comes back and from where.
     */
    public Map<Long, Map<Integer, PackFlag>> packFlags() {
        Map<Long, Map<Integer, PackFlag>> flags = new HashMap<>();
        jdbi.useHandle(handle -> handle
                .createQuery("SELECT item_id, pack_number, amount, from_account_id FROM pack_reimbursements")
                .map((rs, ctx) -> {
                    flags.computeIfAbsent(rs.getLong("item_id"), id -> new TreeMap<>())
                            .put(rs.getInt("pack_number"), packFlag(rs));
                    return null;
                })
                .list());
        return flags;
    }

    /** Flags the planned pack at {@code packNumber} in line, or changes what comes back for it and from where. */
    public void flagPack(long itemId, int packNumber, PackFlag flag) {
        jdbi.useHandle(handle -> handle
                .createUpdate("""
                        INSERT INTO pack_reimbursements (item_id, pack_number, amount, from_account_id)
                        VALUES (:itemId, :number, :amount, :from)
                        ON CONFLICT (item_id, pack_number) DO UPDATE
                        SET amount = excluded.amount, from_account_id = excluded.from_account_id
                        """)
                .bind("itemId", itemId)
                .bind("number", packNumber)
                .bind("amount", flag.amountCents())
                .bind("from", flag.fromAccountId())
                .execute());
    }

    /** @return false when that pack was not flagged */
    public boolean unflagPack(long itemId, int packNumber) {
        return jdbi.withHandle(handle -> handle
                .createUpdate("DELETE FROM pack_reimbursements WHERE item_id = :itemId AND pack_number = :number")
                .bind("itemId", itemId)
                .bind("number", packNumber)
                .execute()) > 0;
    }

    /**
     * The next pack has been bought: its flag, if it had one, is handed back to go onto the payment, and every pack after it
     * moves up one in line, so each flag stays with its own pack. @return the bought pack's flag, when it had one
     */
    public Optional<PackFlag> packBought(long itemId) {
        return jdbi.inTransaction(handle -> {
            Optional<PackFlag> flagged = handle
                    .createQuery("SELECT amount, from_account_id FROM pack_reimbursements WHERE item_id = :itemId AND pack_number = 1")
                    .bind("itemId", itemId)
                    .map((rs, ctx) -> packFlag(rs))
                    .findOne();
            handle.createUpdate("DELETE FROM pack_reimbursements WHERE item_id = :itemId AND pack_number = 1")
                    .bind("itemId", itemId)
                    .execute();
            // One at a time, front to back, so no two share a place while they move.
            List<Integer> after = handle
                    .createQuery("SELECT pack_number FROM pack_reimbursements WHERE item_id = :itemId ORDER BY pack_number")
                    .bind("itemId", itemId)
                    .mapTo(Integer.class)
                    .list();
            for (int number : after) {
                handle.createUpdate("""
                                UPDATE pack_reimbursements SET pack_number = :number - 1 WHERE item_id = :itemId AND pack_number = :number
                                """)
                        .bind("itemId", itemId)
                        .bind("number", number)
                        .execute();
            }
            return flagged;
        });
    }

    /** {@link #settle(Handle, Long)} on its own, for money that came back from a bucket. */
    public void settle(Long id) {
        jdbi.useTransaction(handle -> settle(handle, id));
    }

    /**
     * Moves a flagged payment's status forward to match the money that has come back for it, as for a one-off expense:
     * reimbursed once all of it is covered, at least submitted once any of it is. Never moves it back.
     */
    public static void settle(Handle handle, Long id) {
        if (id == null) {
            return;
        }
        handle.createQuery(SELECT + "WHERE r.id = :id")
                .bind("id", id)
                .map((rs, ctx) -> flagged(rs))
                .findOne()
                .ifPresent(flagged -> {
                    Status next = Reimbursement.afterPayback(
                            flagged.status(), Money.ofCents(flagged.amountCents()), Money.ofCents(flagged.paidBackCents()));
                    if (next != flagged.status()) {
                        handle.createUpdate("UPDATE payment_reimbursements SET status = :status WHERE id = :id")
                                .bind("status", Inputs.code(next))
                                .bind("id", id)
                                .execute();
                    }
                });
    }

    private static Settings settings(ResultSet rs) throws SQLException {
        return new Settings(rs.getLong("item_id"), rs.getString("reimbursed_by"), rs.getInt("days_after"), rs.getLong("into_account_id"),
                fromAccount(rs));
    }

    private static PackFlag packFlag(ResultSet rs) throws SQLException {
        return new PackFlag(rs.getLong("amount"), fromAccount(rs));
    }

    private static Long fromAccount(ResultSet rs) throws SQLException {
        long id = rs.getLong("from_account_id");
        return rs.wasNull() ? null : id;
    }

    private static Flagged flagged(ResultSet rs) throws SQLException {
        String submittedOn = rs.getString("submitted_on");
        return new Flagged(
                rs.getLong("id"),
                rs.getLong("item_id"),
                LocalDate.parse(rs.getString("original_due_date")),
                rs.getLong("amount"),
                Status.valueOf(rs.getString("status").toUpperCase(Locale.ROOT)),
                submittedOn == null ? null : LocalDate.parse(submittedOn),
                rs.getString("reimbursement_to"),
                rs.getLong("paid_back"),
                fromAccount(rs));
    }
}
