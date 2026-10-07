package app.budget.server.entries;

import app.budget.server.api.Inputs;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.SqlStatement;

/** Reads and writes one-off entries (money in, money out, transfers) and their tags. */
public final class EntriesRepository {

    private static final String SELECT = """
            SELECT id, kind, date, amount, is_estimate, description, payee, category_id, account_id, to_account_id, note
            FROM entries
            """;

    private final Jdbi jdbi;
    private final Clock clock;

    public EntriesRepository(Jdbi jdbi, Clock clock) {
        this.jdbi = jdbi;
        this.clock = clock;
    }

    /**
     * A one-off entry as stored and as the API returns it. Amounts are whole cents, always positive:
     * the kind says which way the money goes.
     *
     * @param accountId   the account it leaves (money out, transfer) or arrives in (money in)
     * @param toAccountId where a transfer goes; {@code null} otherwise
     */
    public record Entry(
            long id,
            EntryKind kind,
            LocalDate date,
            long amountCents,
            boolean estimate,
            String description,
            String payee,
            Long categoryId,
            long accountId,
            Long toAccountId,
            String note,
            List<Long> tagIds) {}

    /** What the user can set on an entry. */
    public record Fields(
            EntryKind kind,
            LocalDate date,
            long amountCents,
            boolean estimate,
            String description,
            String payee,
            Long categoryId,
            long accountId,
            Long toAccountId,
            String note,
            List<Long> tagIds) {}

    /** Entries dated within {@code from}–{@code to}, oldest first. */
    public List<Entry> between(LocalDate from, LocalDate to) {
        return jdbi.withHandle(handle -> {
            List<Entry> entries = new ArrayList<>();
            List<Entry> rows = handle.createQuery(SELECT + "WHERE date BETWEEN :from AND :to ORDER BY date, id")
                    .bind("from", from.toString())
                    .bind("to", to.toString())
                    .map((rs, ctx) -> entry(rs, List.of()))
                    .list();
            for (Entry row : rows) {
                entries.add(withTags(handle, row));
            }
            return entries;
        });
    }

    public Optional<Entry> find(long id) {
        return jdbi.withHandle(handle -> handle.createQuery(SELECT + "WHERE id = :id")
                .bind("id", id)
                .map((rs, ctx) -> entry(rs, List.of()))
                .findOne()
                .map(row -> withTags(handle, row)));
    }

    public long create(Fields fields) {
        return jdbi.inTransaction(handle -> {
            long id = bind(handle.createQuery("""
                            INSERT INTO entries
                                (kind, date, amount, is_estimate, description, payee, category_id, account_id,
                                 to_account_id, note, created_at)
                            VALUES (:kind, :date, :amount, :estimate, :description, :payee, :categoryId, :accountId,
                                    :toAccountId, :note, :now)
                            RETURNING id
                            """), fields)
                    .bind("now", clock.instant().toString())
                    .mapTo(Long.class)
                    .one();
            replaceTags(handle, id, fields.tagIds());
            return id;
        });
    }

    /** @return false when there is no such entry */
    public boolean update(long id, Fields fields) {
        return jdbi.inTransaction(handle -> {
            int changed = bind(handle.createUpdate("""
                            UPDATE entries
                            SET kind = :kind, date = :date, amount = :amount, is_estimate = :estimate,
                                description = :description, payee = :payee, category_id = :categoryId,
                                account_id = :accountId, to_account_id = :toAccountId, note = :note
                            WHERE id = :id
                            """), fields)
                    .bind("id", id)
                    .execute();
            if (changed > 0) {
                replaceTags(handle, id, fields.tagIds());
            }
            return changed > 0;
        });
    }

    public boolean delete(long id) {
        return jdbi.withHandle(handle -> handle.createUpdate("DELETE FROM entries WHERE id = :id").bind("id", id).execute()) > 0;
    }

    private static <S extends SqlStatement<S>> S bind(S statement, Fields fields) {
        return statement
                .bind("kind", Inputs.code(fields.kind()))
                .bind("date", fields.date().toString())
                .bind("amount", fields.amountCents())
                .bind("estimate", fields.estimate())
                .bind("description", fields.description())
                .bind("payee", fields.payee())
                .bind("categoryId", fields.categoryId())
                .bind("accountId", fields.accountId())
                .bind("toAccountId", fields.toAccountId())
                .bind("note", fields.note());
    }

    private static void replaceTags(Handle handle, long entryId, List<Long> tagIds) {
        handle.createUpdate("DELETE FROM entry_tags WHERE entry_id = :id").bind("id", entryId).execute();
        for (Long tagId : tagIds) {
            handle.createUpdate("INSERT INTO entry_tags (entry_id, tag_id) VALUES (:entryId, :tagId)")
                    .bind("entryId", entryId)
                    .bind("tagId", tagId)
                    .execute();
        }
    }

    private static Entry withTags(Handle handle, Entry row) {
        List<Long> tagIds = handle.createQuery("SELECT tag_id FROM entry_tags WHERE entry_id = :id ORDER BY tag_id")
                .bind("id", row.id())
                .mapTo(Long.class)
                .list();
        return new Entry(
                row.id(), row.kind(), row.date(), row.amountCents(), row.estimate(), row.description(), row.payee(),
                row.categoryId(), row.accountId(), row.toAccountId(), row.note(), tagIds);
    }

    private static Entry entry(ResultSet rs, List<Long> tagIds) throws SQLException {
        return new Entry(
                rs.getLong("id"),
                EntryKind.valueOf(rs.getString("kind").toUpperCase(Locale.ROOT)),
                LocalDate.parse(rs.getString("date")),
                rs.getLong("amount"),
                rs.getBoolean("is_estimate"),
                rs.getString("description"),
                rs.getString("payee"),
                nullableLong(rs, "category_id"),
                rs.getLong("account_id"),
                nullableLong(rs, "to_account_id"),
                rs.getString("note"),
                tagIds);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
