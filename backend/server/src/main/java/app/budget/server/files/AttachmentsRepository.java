package app.budget.server.files;

import app.budget.server.files.FileStore.Kind;
import app.budget.server.files.FileStore.Stored;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Query;

/**
 * Reads and writes {@code files} and {@code attachments}: which stored file is attached to
 * what, and under which name (spec §4.8).
 */
public final class AttachmentsRepository {

    private static final String SELECT = """
            SELECT a.id, a.name, a.entry_id, a.item_id, a.scheduled_date, a.statement_id,
                   f.hash, f.mime, f.size, f.pages
            FROM attachments a JOIN files f ON f.id = a.file_id
            """;

    private final Jdbi jdbi;
    private final Clock clock;

    public AttachmentsRepository(Jdbi jdbi, Clock clock) {
        this.jdbi = jdbi;
        this.clock = clock;
    }

    /** What a file is attached to. */
    public sealed interface Owner {
        /** A one-off entry. */
        record Entry(long entryId) implements Owner {}

        /** One time a recurring item came due, named by the date its schedule gives it. */
        record Occurrence(long itemId, LocalDate scheduledDate) implements Owner {}

        /** A card statement. */
        record Statement(long statementId) implements Owner {}
    }

    /**
     * One attached file.
     *
     * @param name  what the user's file was called; shown, and offered again on download
     * @param pages how many pages a PDF has; {@code null} for a picture
     */
    public record Attachment(long id, String name, Owner owner, String hash, Kind kind, long sizeBytes, Integer pages) {}

    /** Everything attached to {@code owner}, in the order it was added. */
    public List<Attachment> of(Owner owner) {
        return jdbi.withHandle(handle -> where(handle.createQuery(SELECT + "WHERE " + condition(owner) + " ORDER BY a.id"), owner)
                .map((rs, ctx) -> attachment(rs))
                .list());
    }

    public Optional<Attachment> find(long id) {
        return jdbi.withHandle(handle -> handle.createQuery(SELECT + "WHERE a.id = :id")
                .bind("id", id)
                .map((rs, ctx) -> attachment(rs))
                .findOne());
    }

    public int count(Owner owner) {
        return jdbi.withHandle(handle -> where(handle.createQuery("SELECT count(*) FROM attachments a WHERE " + condition(owner)), owner)
                .mapTo(Integer.class)
                .one());
    }

    /** Attaches a file that {@link FileStore} has just kept. */
    public long add(Owner owner, Stored file, String name) {
        return jdbi.inTransaction(handle -> {
            String now = clock.instant().toString();
            // The same contents attached again reuse the one row and the one file on disk.
            handle.createUpdate("""
                            INSERT INTO files (hash, mime, size, pages, created_at)
                            VALUES (:hash, :mime, :size, :pages, :now)
                            ON CONFLICT (hash) DO NOTHING
                            """)
                    .bind("hash", file.hash())
                    .bind("mime", file.kind().mime())
                    .bind("size", file.size())
                    .bind("pages", file.pages())
                    .bind("now", now)
                    .execute();
            long fileId = handle.createQuery("SELECT id FROM files WHERE hash = :hash")
                    .bind("hash", file.hash())
                    .mapTo(Long.class)
                    .one();
            return handle.createQuery("""
                            INSERT INTO attachments (file_id, name, entry_id, item_id, scheduled_date, statement_id, created_at)
                            VALUES (:fileId, :name, :entryId, :itemId, :scheduledDate, :statementId, :now)
                            RETURNING id
                            """)
                    .bind("fileId", fileId)
                    .bind("name", name)
                    .bind("entryId", owner instanceof Owner.Entry entry ? (Long) entry.entryId() : null)
                    .bind("itemId", owner instanceof Owner.Occurrence occurrence ? (Long) occurrence.itemId() : null)
                    .bind("scheduledDate", owner instanceof Owner.Occurrence occurrence ? occurrence.scheduledDate().toString() : null)
                    .bind("statementId", owner instanceof Owner.Statement statement ? (Long) statement.statementId() : null)
                    .bind("now", now)
                    .mapTo(Long.class)
                    .one();
        });
    }

    /**
     * Takes a file off what it was attached to. The file itself stays until {@link #forgetUnused}
     * finds nothing that could still want it, so Undo can put the attachment back.
     */
    public boolean remove(long id) {
        return jdbi.withHandle(handle -> handle.createUpdate("DELETE FROM attachments WHERE id = :id").bind("id", id).execute()) > 0;
    }

    /** How many files each entry has, for the entries that have any. */
    public Map<Long, Integer> countsByEntry() {
        Map<Long, Integer> counts = new HashMap<>();
        jdbi.useHandle(handle -> handle
                .createQuery("SELECT entry_id, count(*) AS files FROM attachments WHERE entry_id IS NOT NULL GROUP BY entry_id")
                .map((rs, ctx) -> counts.put(rs.getLong("entry_id"), rs.getInt("files")))
                .list());
        return counts;
    }

    /** How many files each occurrence scheduled within {@code from}–{@code to} has, for those that have any. */
    public Map<Owner.Occurrence, Integer> countsByOccurrence(LocalDate from, LocalDate to) {
        Map<Owner.Occurrence, Integer> counts = new HashMap<>();
        jdbi.useHandle(handle -> handle
                .createQuery("""
                        SELECT item_id, scheduled_date, count(*) AS files FROM attachments
                        WHERE item_id IS NOT NULL AND scheduled_date BETWEEN :from AND :to
                        GROUP BY item_id, scheduled_date
                        """)
                .bind("from", from.toString())
                .bind("to", to.toString())
                .map((rs, ctx) -> counts.put(
                        new Owner.Occurrence(rs.getLong("item_id"), LocalDate.parse(rs.getString("scheduled_date"))),
                        rs.getInt("files")))
                .list());
        return counts;
    }

    /** How many files each statement has, for the statements that have any. */
    public Map<Long, Integer> countsByStatement() {
        Map<Long, Integer> counts = new HashMap<>();
        jdbi.useHandle(handle -> handle
                .createQuery("SELECT statement_id, count(*) AS files FROM attachments WHERE statement_id IS NOT NULL GROUP BY statement_id")
                .map((rs, ctx) -> counts.put(rs.getLong("statement_id"), rs.getInt("files")))
                .list());
        return counts;
    }

    /**
     * Forgets every file that is attached to nothing and that Undo could not attach again,
     * and says which files are still wanted, so the rest can be deleted from disk.
     *
     * <p>Must not run while a change is being recorded: forgetting a file is housekeeping,
     * not something to undo.
     *
     * @return the hash of every file still on record
     */
    public Set<String> forgetUnused() {
        return jdbi.inTransaction(handle -> {
            handle.execute("""
                    DELETE FROM files
                    WHERE id NOT IN (SELECT file_id FROM attachments)
                      AND id NOT IN (
                          SELECT json_extract(before, '$.file_id') FROM change_log
                          WHERE table_name = 'attachments' AND before IS NOT NULL)
                    """);
            return new HashSet<>(handle.createQuery("SELECT hash FROM files").mapTo(String.class).list());
        });
    }

    /** The SQL for "attached to this owner"; every value in it is bound by {@link #where}. */
    private static String condition(Owner owner) {
        return switch (owner) {
            case Owner.Entry unused -> "a.entry_id = :entryId";
            case Owner.Occurrence unused -> "a.item_id = :itemId AND a.scheduled_date = :scheduledDate";
            case Owner.Statement unused -> "a.statement_id = :statementId";
        };
    }

    private static Query where(Query query, Owner owner) {
        return switch (owner) {
            case Owner.Entry entry -> query.bind("entryId", entry.entryId());
            case Owner.Occurrence occurrence -> query
                    .bind("itemId", occurrence.itemId())
                    .bind("scheduledDate", occurrence.scheduledDate().toString());
            case Owner.Statement statement -> query.bind("statementId", statement.statementId());
        };
    }

    private static Attachment attachment(ResultSet rs) throws SQLException {
        Owner owner;
        long entryId = rs.getLong("entry_id");
        if (!rs.wasNull()) {
            owner = new Owner.Entry(entryId);
        } else if (rs.getString("scheduled_date") != null) {
            owner = new Owner.Occurrence(rs.getLong("item_id"), LocalDate.parse(rs.getString("scheduled_date")));
        } else {
            owner = new Owner.Statement(rs.getLong("statement_id"));
        }
        int pageCount = rs.getInt("pages");
        Integer pages = rs.wasNull() ? null : (Integer) pageCount;
        return new Attachment(
                rs.getLong("id"),
                rs.getString("name"),
                owner,
                rs.getString("hash"),
                Kind.ofMime(rs.getString("mime")),
                rs.getLong("size"),
                pages);
    }
}
