package app.budget.server.uploads;

import app.budget.engine.statements.Duplicates;
import app.budget.engine.statements.Replacement.Group;
import app.budget.engine.statements.Replacement.GroupType;
import app.budget.engine.statements.Replacement.Mode;
import app.budget.engine.statements.Replacement.Span;
import app.budget.engine.statements.StatementRead.Kind;
import app.budget.server.api.Inputs;
import app.budget.server.files.AttachmentsRepository;
import app.budget.server.files.FileStore.Stored;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/**
 * Reads and writes the statement uploads of each account (migration {@code V49}): their lines, the category and tags
 * each payee was last given, the item each category's or tag's total is pointed at in a pay period, and which item
 * dates a statement set. Every query is bound; nothing in a line is logged, since it says where someone spends.
 */
public final class StatementUploadsRepository {

    private final Jdbi jdbi;
    private final Clock clock;

    public StatementUploadsRepository(Jdbi jdbi, Clock clock) {
        this.jdbi = jdbi;
        this.clock = clock;
    }

    /** The kind of file it was read from. */
    public enum Format { CSV, OFX, PDF }

    /**
     * An upload.
     *
     * @param hash      the kept PDF; null for a CSV or OFX file, which is not kept
     * @param appliedAt when its totals were last applied; null until then
     */
    public record Upload(
            long id, long accountId, String name, Format format, LocalDate from, LocalDate to, String createdAt, String appliedAt,
            String hash, Long sizeBytes, Integer pages) {}

    /**
     * One line, as kept.
     *
     * @param amountCents  spent as positive, money back negative
     * @param duplicateOf  the line of an earlier upload it repeats; null when it is new
     * @param countsToward the group chosen when two of its groups are pointed at items; null until then
     */
    public record Line(
            long id,
            long uploadId,
            LocalDate txnDate,
            LocalDate postedDate,
            String description,
            String payeeKey,
            long amountCents,
            Kind kind,
            boolean leftOut,
            boolean sure,
            String externalId,
            Long duplicateOf,
            Long categoryId,
            List<Long> tagIds,
            Group countsToward,
            Long oneOffEntryId) {

        /** Counted toward totals: not left out, not a repeat, and not a one-off of its own. */
        public boolean counted() {
            return !leftOut && duplicateOf == null && oneOffEntryId == null;
        }
    }

    /** A line to add with a new upload. */
    public record NewLine(
            LocalDate txnDate, LocalDate postedDate, String description, String payeeKey, long amountCents, Kind kind, boolean leftOut,
            boolean sure, String externalId, Long duplicateOf, Long categoryId, List<Long> tagIds) {}

    /** What a payee was last given. */
    public record PayeeRule(Long categoryId, List<Long> tagIds) {}

    /** A total pointed at an item in one pay period of an account. */
    public record Target(LocalDate periodStart, Group group, long itemId, Mode mode) {}

    /** What a statement set an item's date to, and what it was before any statement did. */
    public record Replaced(long baseCents, long appliedCents) {}

    /** An item's date, as occurrence_overrides keys it. */
    public record ItemDate(long itemId, LocalDate scheduledDate) {}

    private static final String UPLOAD_SELECT = """
            SELECT u.id, u.account_id, u.name, u.format, u.from_date, u.to_date, u.created_at, u.applied_at, f.hash, f.size, f.pages
            FROM statement_uploads u LEFT JOIN files f ON f.id = u.file_id
            """;

    private static final String LINE_SELECT = """
            SELECT l.id, l.upload_id, l.txn_date, l.posted_date, l.description, l.payee_key, l.amount, l.kind, l.left_out, l.sure,
                   l.external_id, l.duplicate_of, l.category_id, l.counts_toward, l.counts_toward_tag_id, l.one_off_entry_id
            FROM statement_upload_lines l
            """;

    /** Adds an upload with its lines. {@code file} is the kept PDF, or null for a file that is not kept. */
    public long create(long accountId, Stored file, String name, Format format, LocalDate from, LocalDate to, List<NewLine> lines) {
        return jdbi.inTransaction(handle -> {
            String now = clock.instant().toString();
            long id = handle.createQuery("""
                            INSERT INTO statement_uploads (account_id, file_id, name, format, from_date, to_date, created_at)
                            VALUES (:accountId, :fileId, :name, :format, :from, :to, :now)
                            RETURNING id
                            """)
                    .bind("accountId", accountId)
                    .bind("fileId", file == null ? null : AttachmentsRepository.fileId(handle, file, now))
                    .bind("name", name)
                    .bind("format", Inputs.code(format))
                    .bind("from", from.toString())
                    .bind("to", to.toString())
                    .bind("now", now)
                    .mapTo(Long.class)
                    .one();
            for (int position = 0; position < lines.size(); position++) {
                NewLine line = lines.get(position);
                long lineId = handle.createQuery("""
                                INSERT INTO statement_upload_lines
                                    (upload_id, position, txn_date, posted_date, description, payee_key, amount, kind, left_out, sure,
                                     external_id, duplicate_of, category_id)
                                VALUES (:uploadId, :position, :txnDate, :postedDate, :description, :payeeKey, :amount, :kind, :leftOut,
                                        :sure, :externalId, :duplicateOf, :categoryId)
                                RETURNING id
                                """)
                        .bind("uploadId", id)
                        .bind("position", position)
                        .bind("txnDate", line.txnDate().toString())
                        .bind("postedDate", line.postedDate() == null ? null : line.postedDate().toString())
                        .bind("description", line.description())
                        .bind("payeeKey", line.payeeKey())
                        .bind("amount", line.amountCents())
                        .bind("kind", Inputs.code(line.kind()))
                        .bind("leftOut", line.leftOut())
                        .bind("sure", line.sure())
                        .bind("externalId", line.externalId())
                        .bind("duplicateOf", line.duplicateOf())
                        .bind("categoryId", line.categoryId())
                        .mapTo(Long.class)
                        .one();
                insertTags(handle, lineId, line.tagIds());
            }
            return id;
        });
    }

    public Optional<Upload> find(long id) {
        return jdbi.withHandle(handle -> handle.createQuery(UPLOAD_SELECT + "WHERE u.id = :id")
                .bind("id", id)
                .map((rs, ctx) -> upload(rs))
                .findOne());
    }

    /** An account's uploads, the latest statement first. */
    public List<Upload> ofAccount(long accountId) {
        return jdbi.withHandle(handle -> handle.createQuery(UPLOAD_SELECT + "WHERE u.account_id = :id ORDER BY u.to_date DESC, u.id DESC")
                .bind("id", accountId)
                .map((rs, ctx) -> upload(rs))
                .list());
    }

    /** One upload's lines, in the order the statement had them. */
    public List<Line> lines(long uploadId) {
        return jdbi.withHandle(handle -> withTags(handle, handle
                .createQuery(LINE_SELECT + "WHERE l.upload_id = :id ORDER BY l.position, l.id")
                .bind("id", uploadId)
                .map((rs, ctx) -> line(rs))
                .list()));
    }

    /** Every line of an account's uploads: its statements add together within a pay period. */
    public List<Line> linesOfAccount(long accountId) {
        return jdbi.withHandle(handle -> withTags(handle, handle
                .createQuery(LINE_SELECT + """
                        JOIN statement_uploads u ON u.id = l.upload_id
                        WHERE u.account_id = :id ORDER BY l.txn_date, l.upload_id, l.position
                        """)
                .bind("id", accountId)
                .map((rs, ctx) -> line(rs))
                .list()));
    }

    public Optional<Line> line(long lineId) {
        return jdbi.withHandle(handle -> withTags(handle, handle
                .createQuery(LINE_SELECT + "WHERE l.id = :id")
                .bind("id", lineId)
                .map((rs, ctx) -> line(rs))
                .list()).stream().findFirst());
    }

    /** The lines already kept for an account that are not repeats themselves, for noticing them on a new statement. */
    public List<Duplicates.Known> known(long accountId) {
        return linesOfAccount(accountId).stream()
                .filter(line -> line.duplicateOf() == null)
                .map(line -> new Duplicates.Known(line.id(), line.txnDate(), line.amountCents(), line.description(), line.externalId()))
                .toList();
    }

    /** The days each of an account's uploads covers. */
    public List<Span> spans(long accountId) {
        return ofAccount(accountId).stream().map(upload -> new Span(upload.from(), upload.to())).toList();
    }

    /** A line as it is changed on the review page. */
    public record LineFields(
            LocalDate txnDate, String description, String payeeKey, long amountCents, Kind kind, boolean leftOut, Long categoryId,
            List<Long> tagIds, Group countsToward) {}

    /** Changes a line; looked at, it is no longer one to check. */
    public void updateLine(long lineId, LineFields fields) {
        jdbi.useTransaction(handle -> {
            handle.createUpdate("""
                            UPDATE statement_upload_lines
                            SET txn_date = :txnDate, description = :description, payee_key = :payeeKey, amount = :amount, kind = :kind,
                                left_out = :leftOut, category_id = :categoryId, counts_toward = :countsToward,
                                counts_toward_tag_id = :countsTowardTag, sure = 1
                            WHERE id = :id
                            """)
                    .bind("txnDate", fields.txnDate().toString())
                    .bind("description", fields.description())
                    .bind("payeeKey", fields.payeeKey())
                    .bind("amount", fields.amountCents())
                    .bind("kind", Inputs.code(fields.kind()))
                    .bind("leftOut", fields.leftOut())
                    .bind("categoryId", fields.categoryId())
                    .bind("countsToward", fields.countsToward() == null ? null : Inputs.code(fields.countsToward().type()))
                    .bind("countsTowardTag", fields.countsToward() != null && fields.countsToward().type() == GroupType.TAG
                            ? fields.countsToward().id() : null)
                    .bind("id", lineId)
                    .execute();
            handle.createUpdate("DELETE FROM statement_upload_line_tags WHERE line_id = :id").bind("id", lineId).execute();
            insertTags(handle, lineId, fields.tagIds());
        });
    }

    /**
     * Gives the same category and tags to the account's other lines from the payee that have neither yet, so filing one
     * charge files the rest of them.
     *
     * @return how many other lines were filled in
     */
    public int fillFromPayee(long accountId, long exceptLineId, String payeeKey, Long categoryId, List<Long> tagIds) {
        if (payeeKey.isEmpty() || categoryId == null && tagIds.isEmpty()) {
            return 0;
        }
        return jdbi.inTransaction(handle -> {
            List<Long> lineIds = handle.createQuery("""
                            SELECT l.id FROM statement_upload_lines l JOIN statement_uploads u ON u.id = l.upload_id
                            WHERE u.account_id = :accountId AND l.payee_key = :key AND l.id <> :except AND l.category_id IS NULL
                              AND NOT EXISTS (SELECT 1 FROM statement_upload_line_tags t WHERE t.line_id = l.id)
                            """)
                    .bind("accountId", accountId)
                    .bind("key", payeeKey)
                    .bind("except", exceptLineId)
                    .mapTo(Long.class)
                    .list();
            for (long lineId : lineIds) {
                handle.createUpdate("UPDATE statement_upload_lines SET category_id = :categoryId WHERE id = :id")
                        .bind("categoryId", categoryId)
                        .bind("id", lineId)
                        .execute();
                insertTags(handle, lineId, tagIds);
            }
            return lineIds.size();
        });
    }

    /** Marks a line as added as a one-off of its own, so it counts as that and no longer toward any total. */
    public void setOneOff(long lineId, long entryId) {
        jdbi.useHandle(handle -> handle.createUpdate("UPDATE statement_upload_lines SET one_off_entry_id = :entryId WHERE id = :id")
                .bind("entryId", entryId)
                .bind("id", lineId)
                .execute());
    }

    /** Removes an upload and its lines. What it changed stays, as for a pay stub; Undo brings it back. */
    public boolean delete(long id) {
        return jdbi.withHandle(handle -> handle.createUpdate("DELETE FROM statement_uploads WHERE id = :id").bind("id", id).execute()) > 0;
    }

    public void markApplied(long uploadId) {
        jdbi.useHandle(handle -> handle.createUpdate("UPDATE statement_uploads SET applied_at = :now WHERE id = :id")
                .bind("now", clock.instant().toString())
                .bind("id", uploadId)
                .execute());
    }

    /** Every payee's remembered category and tags, by key. */
    public Map<String, PayeeRule> payeeRules() {
        return jdbi.withHandle(handle -> {
            Map<String, List<Long>> tags = new HashMap<>();
            handle.createQuery("SELECT payee_key, tag_id FROM payee_rule_tags ORDER BY tag_id")
                    .map((rs, ctx) -> tags.computeIfAbsent(rs.getString("payee_key"), key -> new ArrayList<>()).add(rs.getLong("tag_id")))
                    .list();
            Map<String, PayeeRule> rules = new HashMap<>();
            handle.createQuery("SELECT payee_key, category_id FROM payee_rules")
                    .map((rs, ctx) -> rules.put(rs.getString("payee_key"),
                            new PayeeRule(nullableLong(rs, "category_id"), List.copyOf(tags.getOrDefault(rs.getString("payee_key"), List.of())))))
                    .list();
            return rules;
        });
    }

    /** Remembers what a payee was given; given nothing, it is forgotten. */
    public void rememberPayee(String payeeKey, Long categoryId, List<Long> tagIds) {
        if (payeeKey.isEmpty()) {
            return;
        }
        jdbi.useTransaction(handle -> {
            handle.createUpdate("DELETE FROM payee_rules WHERE payee_key = :key").bind("key", payeeKey).execute();
            if (categoryId == null && tagIds.isEmpty()) {
                return;
            }
            handle.createUpdate("INSERT INTO payee_rules (payee_key, category_id) VALUES (:key, :categoryId)")
                    .bind("key", payeeKey)
                    .bind("categoryId", categoryId)
                    .execute();
            for (long tagId : tagIds) {
                handle.createUpdate("INSERT INTO payee_rule_tags (payee_key, tag_id) VALUES (:key, :tagId)")
                        .bind("key", payeeKey)
                        .bind("tagId", tagId)
                        .execute();
            }
        });
    }

    /** The items an account's totals are pointed at, in every pay period. */
    public List<Target> targets(long accountId) {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT period_start, category_id, tag_id, item_id, mode FROM statement_targets WHERE account_id = :id")
                .bind("id", accountId)
                .map((rs, ctx) -> new Target(
                        LocalDate.parse(rs.getString("period_start")),
                        group(rs),
                        rs.getLong("item_id"),
                        choice(Mode.class, rs.getString("mode"))))
                .list());
    }

    /** Points a total at an item, or with a null {@code itemId} at nothing. */
    public void setTarget(long accountId, LocalDate periodStart, Group group, Long itemId, Mode mode) {
        jdbi.useTransaction(handle -> {
            handle.createUpdate("DELETE FROM statement_targets WHERE account_id = :accountId AND period_start = :period AND "
                            + groupColumn(group) + " = :groupId")
                    .bind("accountId", accountId)
                    .bind("period", periodStart.toString())
                    .bind("groupId", group.id())
                    .execute();
            if (itemId == null) {
                return;
            }
            handle.createUpdate("""
                            INSERT INTO statement_targets (account_id, period_start, category_id, tag_id, item_id, mode)
                            VALUES (:accountId, :period, :categoryId, :tagId, :itemId, :mode)
                            """)
                    .bind("accountId", accountId)
                    .bind("period", periodStart.toString())
                    .bind("categoryId", group.type() == GroupType.CATEGORY ? group.id() : null)
                    .bind("tagId", group.type() == GroupType.TAG ? group.id() : null)
                    .bind("itemId", itemId)
                    .bind("mode", Inputs.code(mode))
                    .execute();
            // Remembered as a suggestion for the next pay period; never chosen by itself.
            handle.createUpdate("DELETE FROM statement_target_hints WHERE account_id = :accountId AND " + groupColumn(group) + " = :groupId")
                    .bind("accountId", accountId)
                    .bind("groupId", group.id())
                    .execute();
            handle.createUpdate("""
                            INSERT INTO statement_target_hints (account_id, category_id, tag_id, item_id)
                            VALUES (:accountId, :categoryId, :tagId, :itemId)
                            """)
                    .bind("accountId", accountId)
                    .bind("categoryId", group.type() == GroupType.CATEGORY ? group.id() : null)
                    .bind("tagId", group.type() == GroupType.TAG ? group.id() : null)
                    .bind("itemId", itemId)
                    .execute();
        });
    }

    /** The item each category or tag was last pointed at on an account. */
    public Map<Group, Long> hints(long accountId) {
        Map<Group, Long> hints = new HashMap<>();
        jdbi.useHandle(handle -> handle
                .createQuery("SELECT category_id, tag_id, item_id FROM statement_target_hints WHERE account_id = :id")
                .bind("id", accountId)
                .map((rs, ctx) -> hints.put(group(rs), rs.getLong("item_id")))
                .list());
        return hints;
    }

    /** Which of these item dates a statement set, and to what. */
    public Map<ItemDate, Replaced> replaced(Collection<Long> itemIds) {
        Map<ItemDate, Replaced> replaced = new HashMap<>();
        if (itemIds.isEmpty()) {
            return replaced;
        }
        jdbi.useHandle(handle -> handle
                .createQuery("SELECT item_id, original_due_date, base_amount, applied_amount FROM statement_replacements WHERE item_id IN (<ids>)")
                .bindList("ids", List.copyOf(itemIds))
                .map((rs, ctx) -> replaced.put(
                        new ItemDate(rs.getLong("item_id"), LocalDate.parse(rs.getString("original_due_date"))),
                        new Replaced(rs.getLong("base_amount"), rs.getLong("applied_amount"))))
                .list());
        return replaced;
    }

    public void saveReplaced(ItemDate date, Replaced replaced) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        INSERT INTO statement_replacements (item_id, original_due_date, base_amount, applied_amount, applied_at)
                        VALUES (:itemId, :date, :base, :applied, :now)
                        ON CONFLICT (item_id, original_due_date) DO UPDATE SET
                            base_amount = excluded.base_amount, applied_amount = excluded.applied_amount, applied_at = excluded.applied_at
                        """)
                .bind("itemId", date.itemId())
                .bind("date", date.scheduledDate().toString())
                .bind("base", replaced.baseCents())
                .bind("applied", replaced.appliedCents())
                .bind("now", clock.instant().toString())
                .execute());
    }

    private static String groupColumn(Group group) {
        return group.type() == GroupType.CATEGORY ? "category_id" : "tag_id";
    }

    private static Group group(ResultSet rs) throws SQLException {
        Long category = nullableLong(rs, "category_id");
        return category != null ? new Group(GroupType.CATEGORY, category) : new Group(GroupType.TAG, rs.getLong("tag_id"));
    }

    private static void insertTags(Handle handle, long lineId, List<Long> tagIds) {
        for (long tagId : tagIds) {
            handle.createUpdate("INSERT OR IGNORE INTO statement_upload_line_tags (line_id, tag_id) VALUES (:lineId, :tagId)")
                    .bind("lineId", lineId)
                    .bind("tagId", tagId)
                    .execute();
        }
    }

    /** Fills in each line's tags, in one query for all of them. */
    private static List<Line> withTags(Handle handle, List<Line> lines) {
        if (lines.isEmpty()) {
            return lines;
        }
        Map<Long, List<Long>> tags = new HashMap<>();
        handle.createQuery("SELECT line_id, tag_id FROM statement_upload_line_tags WHERE line_id IN (<ids>) ORDER BY tag_id")
                .bindList("ids", lines.stream().map(Line::id).toList())
                .map((rs, ctx) -> tags.computeIfAbsent(rs.getLong("line_id"), id -> new ArrayList<>()).add(rs.getLong("tag_id")))
                .list();
        return lines.stream()
                .map(line -> new Line(line.id(), line.uploadId(), line.txnDate(), line.postedDate(), line.description(), line.payeeKey(),
                        line.amountCents(), line.kind(), line.leftOut(), line.sure(), line.externalId(), line.duplicateOf(),
                        line.categoryId(), List.copyOf(tags.getOrDefault(line.id(), List.of())), line.countsToward(), line.oneOffEntryId()))
                .toList();
    }

    private static Upload upload(ResultSet rs) throws SQLException {
        String hash = rs.getString("hash");
        return new Upload(
                rs.getLong("id"),
                rs.getLong("account_id"),
                rs.getString("name"),
                choice(Format.class, rs.getString("format")),
                LocalDate.parse(rs.getString("from_date")),
                LocalDate.parse(rs.getString("to_date")),
                rs.getString("created_at"),
                rs.getString("applied_at"),
                hash,
                hash == null ? null : rs.getLong("size"),
                hash == null ? null : nullableInt(rs, "pages"));
    }

    private static Line line(ResultSet rs) throws SQLException {
        String posted = rs.getString("posted_date");
        String countsToward = rs.getString("counts_toward");
        Group chosen = null;
        if ("category".equals(countsToward)) {
            Long category = nullableLong(rs, "category_id");
            chosen = category == null ? null : new Group(GroupType.CATEGORY, category);
        } else if ("tag".equals(countsToward)) {
            Long tag = nullableLong(rs, "counts_toward_tag_id");
            chosen = tag == null ? null : new Group(GroupType.TAG, tag);
        }
        return new Line(
                rs.getLong("id"),
                rs.getLong("upload_id"),
                LocalDate.parse(rs.getString("txn_date")),
                posted == null ? null : LocalDate.parse(posted),
                rs.getString("description"),
                rs.getString("payee_key"),
                rs.getLong("amount"),
                choice(Kind.class, rs.getString("kind")),
                rs.getBoolean("left_out"),
                rs.getBoolean("sure"),
                rs.getString("external_id"),
                nullableLong(rs, "duplicate_of"),
                nullableLong(rs, "category_id"),
                List.of(),
                chosen,
                nullableLong(rs, "one_off_entry_id"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    /** A stored code back as its enum; the table's own checks keep anything else out. */
    private static <E extends Enum<E>> E choice(Class<E> type, String code) {
        for (E constant : type.getEnumConstants()) {
            if (Inputs.code(constant).equals(code)) {
                return constant;
            }
        }
        throw new IllegalStateException("A stored statement upload has a value this version does not know");
    }
}
