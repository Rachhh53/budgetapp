package app.budget.server.items;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jdbi.v3.core.Jdbi;

/**
 * What was paid ahead for paid-ahead items (spec §4.6; migration V28): which one-off entries pay
 * for which item. The entry is the payment itself, so its date, amount and account are read from it.
 */
public final class PrepaymentsRepository {

    private static final String SELECT = """
            SELECT p.entry_id, p.item_id, e.date, e.amount, e.account_id
            FROM item_prepayments p JOIN entries e ON e.id = p.entry_id
            """;

    private final Jdbi jdbi;

    public PrepaymentsRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /** One payment made ahead: a one-off entry of money going out, in whole cents. */
    public record Prepayment(long entryId, long itemId, LocalDate date, long amountCents, long accountId) {}

    /** Every payment made ahead, by item, oldest first. */
    public Map<Long, List<Prepayment>> byItem() {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT + "ORDER BY e.date, p.entry_id")
                .map((rs, ctx) -> prepayment(rs.getLong("entry_id"), rs.getLong("item_id"), rs.getString("date"),
                        rs.getLong("amount"), rs.getLong("account_id")))
                .stream()
                .collect(Collectors.groupingBy(Prepayment::itemId)));
    }

    /** One item's payments made ahead, oldest first. */
    public List<Prepayment> forItem(long itemId) {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT + "WHERE p.item_id = :itemId ORDER BY e.date, p.entry_id")
                .bind("itemId", itemId)
                .map((rs, ctx) -> prepayment(rs.getLong("entry_id"), rs.getLong("item_id"), rs.getString("date"),
                        rs.getLong("amount"), rs.getLong("account_id")))
                .list());
    }

    /** Says that {@code entryId} was paid ahead for {@code itemId}. */
    public void link(long entryId, long itemId) {
        jdbi.useHandle(handle -> handle
                .createUpdate("INSERT INTO item_prepayments (entry_id, item_id) VALUES (:entryId, :itemId)")
                .bind("entryId", entryId)
                .bind("itemId", itemId)
                .execute());
    }

    private static Prepayment prepayment(long entryId, long itemId, String date, long amountCents, long accountId) {
        return new Prepayment(entryId, itemId, LocalDate.parse(date), amountCents, accountId);
    }
}
