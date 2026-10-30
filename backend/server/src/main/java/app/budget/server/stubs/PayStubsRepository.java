package app.budget.server.stubs;

import app.budget.engine.Money;
import app.budget.engine.stubs.PayStub;
import app.budget.engine.stubs.PayStub.Section;
import app.budget.engine.stubs.PayStub.Tracking;
import app.budget.server.api.Inputs;
import app.budget.server.files.AttachmentsRepository;
import app.budget.server.files.FileStore.Stored;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/**
 * Reads and writes {@code pay_stubs}, {@code pay_stub_lines} and {@code stub_layouts}
 * (spec §4.3): the stubs of each income source, what was read from them, and how each line
 * was filed, so the next stub from the same employer files itself.
 */
public final class PayStubsRepository {

    private static final String SELECT = """
            SELECT s.id, s.income_source_id, s.name, s.pay_date, s.status, s.applied, s.saved_applied, f.hash, f.size, f.pages
            FROM pay_stubs s JOIN files f ON f.id = s.file_id
            """;

    private final Jdbi jdbi;
    private final Clock clock;

    public PayStubsRepository(Jdbi jdbi, Clock clock) {
        this.jdbi = jdbi;
        this.clock = clock;
    }

    /** A stub waits in review until its lines have been looked over. */
    public enum Status { REVIEW, SAVED }

    /** What saving a stub was asked to change (spec Phase 8; decided 2026-10-12: asked each time). */
    public enum Applied {
        /** The amounts in force today, as a correction. */
        CORRECT,
        /** The pay from the stub's date on: a raise. */
        PAY_CHANGE,
        /** Only the paycheck the stub is for. */
        PAYCHECK,
        /** A one-off paycheck of its own, such as a bonus paid on its own day (2026-10-19). */
        ONE_OFF,
        /** Nothing: the stub is only kept. */
        NONE
    }

    /**
     * One line of a stub, with the account it goes to.
     *
     * @param id        null for a line not saved yet
     * @param accountId where a contribution or the employer's match goes; null for anything else, or until chosen
     */
    public record StubLine(Long id, PayStub.Line line, Long accountId) {}

    /**
     * A stub.
     *
     * @param name    what its file was called when it was added
     * @param payDate null until the stub's date is read or typed
     * @param applied null while in review
     * @param savedApplied while a saved stub is being edited, what saving it had changed; null otherwise, so Cancel is
     *                     there only when there is a saved stub to go back to
     */
    public record Stub(
            long id,
            long incomeSourceId,
            String name,
            LocalDate payDate,
            Status status,
            Applied applied,
            Applied savedApplied,
            String hash,
            long sizeBytes,
            int pages,
            List<StubLine> lines) {}

    /** How a line was filed on the last stub saved, and the account it went to. */
    public record Layout(PayStub.Remembered remembered, Long accountId) {}

    public Optional<Stub> find(long id) {
        return jdbi.withHandle(handle -> handle.createQuery(SELECT + "WHERE s.id = :id")
                .bind("id", id)
                .map((rs, ctx) -> stub(handle, rs))
                .findOne());
    }

    /** A source's stubs, newest pay date first; ones still without a date come first of all. */
    public List<Stub> ofSource(long incomeSourceId) {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT + "WHERE s.income_source_id = :id ORDER BY s.pay_date IS NOT NULL, s.pay_date DESC, s.id DESC")
                .bind("id", incomeSourceId)
                .map((rs, ctx) -> stub(handle, rs))
                .list());
    }

    /** Adds a stub, in review, for a file {@code FileStore} has just kept. */
    public long create(long incomeSourceId, Stored file, String name, LocalDate payDate, List<StubLine> lines) {
        return jdbi.inTransaction(handle -> {
            String now = clock.instant().toString();
            long id = handle.createQuery("""
                            INSERT INTO pay_stubs (income_source_id, file_id, name, pay_date, status, created_at)
                            VALUES (:sourceId, :fileId, :name, :payDate, 'review', :now)
                            RETURNING id
                            """)
                    .bind("sourceId", incomeSourceId)
                    .bind("fileId", AttachmentsRepository.fileId(handle, file, now))
                    .bind("name", name)
                    .bind("payDate", payDate == null ? null : payDate.toString())
                    .bind("now", now)
                    .mapTo(Long.class)
                    .one();
            insertLines(handle, id, lines);
            return id;
        });
    }

    /** Replaces a stub's date and all of its lines with what the review screen sent. */
    public void replace(long id, LocalDate payDate, List<StubLine> lines) {
        jdbi.useTransaction(handle -> {
            handle.createUpdate("UPDATE pay_stubs SET pay_date = :payDate WHERE id = :id")
                    .bind("payDate", payDate == null ? null : payDate.toString())
                    .bind("id", id)
                    .execute();
            handle.createUpdate("DELETE FROM pay_stub_lines WHERE pay_stub_id = :id").bind("id", id).execute();
            insertLines(handle, id, lines);
        });
    }

    /** The columns of a stub's lines, the same in {@code pay_stub_lines} and the copy kept while it is edited. */
    private static final String LINE_COLUMNS = "position, section, label, amount, ytd_amount, maps_to, maps_to_account_id, sure";

    /**
     * Puts a saved stub back in review, to be changed and saved again. What it changed when it
     * was saved stays as it is until then. The stub as it was saved is copied aside, so the edit
     * can be cancelled.
     */
    public void reopen(long id) {
        jdbi.useTransaction(handle -> {
            handle.createUpdate("DELETE FROM pay_stub_saved_lines WHERE pay_stub_id = :id").bind("id", id).execute();
            handle.createUpdate("INSERT INTO pay_stub_saved_lines (pay_stub_id, " + LINE_COLUMNS + ") SELECT pay_stub_id, " + LINE_COLUMNS
                            + " FROM pay_stub_lines WHERE pay_stub_id = :id")
                    .bind("id", id)
                    .execute();
            handle.createUpdate("""
                            UPDATE pay_stubs SET saved_applied = applied, saved_pay_date = pay_date, status = 'review', applied = NULL
                            WHERE id = :id
                            """)
                    .bind("id", id)
                    .execute();
        });
    }

    /**
     * Puts a stub being edited back as it was saved: its lines, its date and what saving it changed, which were never
     * taken back (Rachel, 2026-10-24). False when it is not a saved stub being edited.
     */
    public boolean cancelEdit(long id) {
        return jdbi.inTransaction(handle -> {
            boolean editing = handle.createQuery("SELECT saved_applied IS NOT NULL FROM pay_stubs WHERE id = :id AND status = 'review'")
                    .bind("id", id)
                    .mapTo(Boolean.class)
                    .findOne()
                    .orElse(false);
            if (!editing) {
                return false;
            }
            handle.createUpdate("DELETE FROM pay_stub_lines WHERE pay_stub_id = :id").bind("id", id).execute();
            handle.createUpdate("INSERT INTO pay_stub_lines (pay_stub_id, " + LINE_COLUMNS + ") SELECT pay_stub_id, " + LINE_COLUMNS
                            + " FROM pay_stub_saved_lines WHERE pay_stub_id = :id")
                    .bind("id", id)
                    .execute();
            handle.createUpdate("""
                            UPDATE pay_stubs
                            SET status = 'saved', applied = saved_applied, pay_date = saved_pay_date, saved_applied = NULL, saved_pay_date = NULL
                            WHERE id = :id
                            """)
                    .bind("id", id)
                    .execute();
            handle.createUpdate("DELETE FROM pay_stub_saved_lines WHERE pay_stub_id = :id").bind("id", id).execute();
            return true;
        });
    }

    /**
     * Marks a stub saved, and remembers how each of its lines was filed for the next stub from
     * the same source. A line filed differently this time replaces what was remembered.
     */
    public void save(Stub stub, Applied applied) {
        jdbi.useTransaction(handle -> {
            handle.createUpdate("""
                            UPDATE pay_stubs SET status = 'saved', applied = :applied, saved_applied = NULL, saved_pay_date = NULL
                            WHERE id = :id
                            """)
                    .bind("applied", Inputs.code(applied))
                    .bind("id", stub.id())
                    .execute();
            // Saved again, so there is nothing to cancel back to.
            handle.createUpdate("DELETE FROM pay_stub_saved_lines WHERE pay_stub_id = :id").bind("id", stub.id()).execute();
            for (StubLine line : stub.lines()) {
                handle.createUpdate("""
                                INSERT INTO stub_layouts (income_source_id, label_key, section, maps_to, maps_to_account_id)
                                VALUES (:sourceId, :key, :section, :mapsTo, :accountId)
                                ON CONFLICT (income_source_id, label_key) DO UPDATE SET
                                    section = excluded.section, maps_to = excluded.maps_to,
                                    maps_to_account_id = excluded.maps_to_account_id
                                """)
                        .bind("sourceId", stub.incomeSourceId())
                        .bind("key", PayStub.key(line.line().label()))
                        .bind("section", Inputs.code(line.line().section()))
                        .bind("mapsTo", Inputs.code(line.line().tracking()))
                        .bind("accountId", line.accountId())
                        .execute();
            }
        });
    }

    /** Removes a stub and its lines. Its file stays until nothing, Undo included, can want it. */
    public boolean delete(long id) {
        return jdbi.withHandle(handle -> handle.createUpdate("DELETE FROM pay_stubs WHERE id = :id").bind("id", id).execute()) > 0;
    }

    /** How a source's lines were filed before, by {@link PayStub#key}. */
    public Map<String, Layout> layout(long incomeSourceId) {
        Map<String, Layout> layout = new HashMap<>();
        jdbi.useHandle(handle -> handle
                .createQuery("SELECT label_key, section, maps_to, maps_to_account_id FROM stub_layouts WHERE income_source_id = :id")
                .bind("id", incomeSourceId)
                .map((rs, ctx) -> layout.put(rs.getString("label_key"), new Layout(
                        new PayStub.Remembered(
                                choice(Section.class, rs.getString("section")),
                                choice(Tracking.class, rs.getString("maps_to"))),
                        nullableLong(rs, "maps_to_account_id"))))
                .list());
        return layout;
    }

    private static void insertLines(Handle handle, long stubId, List<StubLine> lines) {
        for (int position = 0; position < lines.size(); position++) {
            StubLine stubLine = lines.get(position);
            PayStub.Line line = stubLine.line();
            handle.createUpdate("""
                            INSERT INTO pay_stub_lines
                                (pay_stub_id, position, section, label, amount, ytd_amount, maps_to, maps_to_account_id, sure)
                            VALUES (:stubId, :position, :section, :label, :amount, :ytd, :mapsTo, :accountId, :sure)
                            """)
                    .bind("stubId", stubId)
                    .bind("position", position)
                    .bind("section", Inputs.code(line.section()))
                    .bind("label", line.label())
                    .bind("amount", line.amount().cents())
                    .bind("ytd", line.ytd() == null ? null : line.ytd().cents())
                    .bind("mapsTo", Inputs.code(line.tracking()))
                    .bind("accountId", stubLine.accountId())
                    .bind("sure", line.sure())
                    .execute();
        }
    }

    private static Stub stub(Handle handle, ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String payDate = rs.getString("pay_date");
        String applied = rs.getString("applied");
        String savedApplied = rs.getString("saved_applied");
        List<StubLine> lines = handle
                .createQuery("""
                        SELECT id, section, label, amount, ytd_amount, maps_to, maps_to_account_id, sure
                        FROM pay_stub_lines WHERE pay_stub_id = :id ORDER BY position, id
                        """)
                .bind("id", id)
                .map((line, ctx) -> {
                    Long ytd = nullableLong(line, "ytd_amount");
                    return new StubLine(
                            line.getLong("id"),
                            new PayStub.Line(
                                    choice(Section.class, line.getString("section")),
                                    line.getString("label"),
                                    Money.ofCents(line.getLong("amount")),
                                    ytd == null ? null : Money.ofCents(ytd),
                                    choice(Tracking.class, line.getString("maps_to")),
                                    line.getBoolean("sure")),
                            nullableLong(line, "maps_to_account_id"));
                })
                .list();
        return new Stub(
                id,
                rs.getLong("income_source_id"),
                rs.getString("name"),
                payDate == null ? null : LocalDate.parse(payDate),
                choice(Status.class, rs.getString("status")),
                applied == null ? null : choice(Applied.class, applied),
                savedApplied == null ? null : choice(Applied.class, savedApplied),
                rs.getString("hash"),
                rs.getLong("size"),
                rs.getInt("pages"),
                new ArrayList<>(lines));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /** A stored code back as its enum; the table's own checks keep anything else out. */
    private static <E extends Enum<E>> E choice(Class<E> type, String code) {
        for (E constant : type.getEnumConstants()) {
            if (Inputs.code(constant).equals(code)) {
                return constant;
            }
        }
        throw new IllegalStateException("A stored pay stub has a value this version does not know");
    }
}
