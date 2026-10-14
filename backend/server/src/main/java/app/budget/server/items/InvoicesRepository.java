package app.budget.server.items;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;

/**
 * Invoices for billed-later items (spec §4.6): what was billed for the visits up to a day, and
 * whether it has been paid.
 */
public final class InvoicesRepository {

    private static final String SELECT = """
            SELECT id, item_id, invoice_date, due_date, covers_through, amount, projected_amount, paid, paid_date,
                   confirmation_code, check_number, note
            FROM item_invoices
            """;

    private final Jdbi jdbi;
    private final Clock clock;

    public InvoicesRepository(Jdbi jdbi, Clock clock) {
        this.jdbi = jdbi;
        this.clock = clock;
    }

    /**
     * One invoice. Amounts are whole cents.
     *
     * @param coversThrough  the last visit it covers: every visit after the invoice before, up to this day
     * @param projectedCents what those visits came to when it was entered
     */
    public record Invoice(
            long id,
            long itemId,
            LocalDate invoiceDate,
            LocalDate dueDate,
            LocalDate coversThrough,
            long amountCents,
            long projectedCents,
            boolean paid,
            LocalDate paidDate,
            String confirmationCode,
            String checkNumber,
            String note) {}

    /** What can be set on an invoice. */
    public record Fields(
            LocalDate invoiceDate,
            LocalDate dueDate,
            LocalDate coversThrough,
            long amountCents,
            long projectedCents,
            boolean paid,
            LocalDate paidDate,
            String confirmationCode,
            String checkNumber,
            String note) {}

    /** Every invoice, by item, oldest first. */
    public Map<Long, List<Invoice>> byItem() {
        Map<Long, List<Invoice>> byItem = new HashMap<>();
        for (Invoice invoice : jdbi.withHandle(handle -> handle
                .createQuery(SELECT + "ORDER BY covers_through, id")
                .map((rs, ctx) -> invoice(rs))
                .list())) {
            byItem.computeIfAbsent(invoice.itemId(), unused -> new ArrayList<>()).add(invoice);
        }
        return byItem;
    }

    /** One item's invoices, oldest first. */
    public List<Invoice> forItem(long itemId) {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT + "WHERE item_id = :itemId ORDER BY covers_through, id")
                .bind("itemId", itemId)
                .map((rs, ctx) -> invoice(rs))
                .list());
    }

    public Optional<Invoice> find(long id) {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT + "WHERE id = :id")
                .bind("id", id)
                .map((rs, ctx) -> invoice(rs))
                .findOne());
    }

    public long create(long itemId, Fields fields) {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        INSERT INTO item_invoices
                            (item_id, invoice_date, due_date, covers_through, amount, projected_amount, paid, paid_date,
                             confirmation_code, check_number, note, created_at)
                        VALUES (:itemId, :invoiceDate, :dueDate, :coversThrough, :amount, :projected, :paid, :paidDate,
                                :confirmationCode, :checkNumber, :note, :now)
                        RETURNING id
                        """)
                .bind("itemId", itemId)
                .bindMap(binds(fields))
                .bind("now", clock.instant().toString())
                .mapTo(Long.class)
                .one());
    }

    /**
     * Changes an invoice. Its receipts are kept by its item and due date, as an occurrence's are,
     * so a new due date takes them along.
     */
    public void update(long id, Fields fields) {
        jdbi.useTransaction(handle -> {
            Invoice before = handle.createQuery(SELECT + "WHERE id = :id").bind("id", id).map((rs, ctx) -> invoice(rs)).one();
            if (!before.dueDate().equals(fields.dueDate())) {
                handle.createUpdate("""
                                UPDATE attachments SET scheduled_date = :to
                                WHERE item_id = :itemId AND scheduled_date = :from
                                """)
                        .bind("itemId", before.itemId())
                        .bind("from", before.dueDate().toString())
                        .bind("to", fields.dueDate().toString())
                        .execute();
            }
            handle.createUpdate("""
                        UPDATE item_invoices
                        SET invoice_date = :invoiceDate, due_date = :dueDate, covers_through = :coversThrough, amount = :amount,
                            projected_amount = :projected, paid = :paid, paid_date = :paidDate,
                            confirmation_code = :confirmationCode, check_number = :checkNumber, note = :note
                        WHERE id = :id
                        """)
                    .bind("id", id)
                    .bindMap(binds(fields))
                    .execute();
        });
    }

    /** Removes an invoice and the receipts kept with it; Undo brings both back. */
    public void delete(long id) {
        jdbi.useTransaction(handle -> {
            Invoice invoice = handle.createQuery(SELECT + "WHERE id = :id").bind("id", id).map((rs, ctx) -> invoice(rs)).one();
            handle.createUpdate("DELETE FROM attachments WHERE item_id = :itemId AND scheduled_date = :dueDate")
                    .bind("itemId", invoice.itemId())
                    .bind("dueDate", invoice.dueDate().toString())
                    .execute();
            handle.createUpdate("DELETE FROM item_invoices WHERE id = :id").bind("id", id).execute();
        });
    }

    private static Map<String, Object> binds(Fields fields) {
        Map<String, Object> binds = new HashMap<>();
        binds.put("invoiceDate", fields.invoiceDate().toString());
        binds.put("dueDate", fields.dueDate().toString());
        binds.put("coversThrough", fields.coversThrough().toString());
        binds.put("amount", fields.amountCents());
        binds.put("projected", fields.projectedCents());
        binds.put("paid", fields.paid());
        binds.put("paidDate", fields.paidDate() == null ? null : fields.paidDate().toString());
        binds.put("confirmationCode", fields.confirmationCode());
        binds.put("checkNumber", fields.checkNumber());
        binds.put("note", fields.note());
        return binds;
    }

    private static Invoice invoice(ResultSet rs) throws SQLException {
        String paidDate = rs.getString("paid_date");
        return new Invoice(
                rs.getLong("id"),
                rs.getLong("item_id"),
                LocalDate.parse(rs.getString("invoice_date")),
                LocalDate.parse(rs.getString("due_date")),
                LocalDate.parse(rs.getString("covers_through")),
                rs.getLong("amount"),
                rs.getLong("projected_amount"),
                rs.getBoolean("paid"),
                paidDate == null ? null : LocalDate.parse(paidDate),
                rs.getString("confirmation_code"),
                rs.getString("check_number"),
                rs.getString("note"));
    }
}
