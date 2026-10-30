package app.budget.server.buckets;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Query;

/** Reads and writes {@code buckets} and what the user moved in and out of them by hand ({@code bucket_changes}). */
public final class BucketsRepository {

    private static final String SELECT =
            "SELECT id, account_id, name, color, goal_amount, note, archived, opened_on, goal_date, stop_at_goal, archived_on FROM buckets ";

    private final Jdbi jdbi;
    private final Clock clock;

    public BucketsRepository(Jdbi jdbi, Clock clock) {
        this.jdbi = jdbi;
        this.clock = clock;
    }

    /**
     * A bucket as stored. Amounts are whole cents.
     *
     * @param goalCents {@code null} when the bucket has no goal
     * @param openedOn  the day it counts from: recurring moves, save-ups and interest reach it from then on.
     *                  The day it was added, unless the user chose an earlier one.
     * @param goalDate  the day the goal is to be reached by; {@code null} when there is none
     * @param stopAtGoal true when recurring bucket moves stop once it holds its goal
     * @param archivedOn the day it was archived as of: it holds what it did before it, and lets go of whatever is
     *                   left that day; {@code null} when it is not archived
     */
    public record Bucket(
            long id,
            long accountId,
            String name,
            String color,
            Long goalCents,
            String note,
            boolean archived,
            LocalDate openedOn,
            LocalDate goalDate,
            boolean stopAtGoal,
            LocalDate archivedOn) {}

    /**
     * What the user can set on a bucket. The account is chosen once, when it is added.
     *
     * @param openedOn {@code null} for today when adding, and to leave it as it is when changing
     * @param goalDate {@code null} for no date
     * @param stopAtGoal {@code null} for "keep going" when adding, and to leave it as it is when changing
     * @param archivedOn the day it is archived as of; {@code null} when it is not archived
     */
    public record Fields(
            String name,
            String color,
            Long goalCents,
            String note,
            LocalDate archivedOn,
            LocalDate openedOn,
            LocalDate goalDate,
            Boolean stopAtGoal) {}

    /**
     * Money put into (+) or taken out of (−) a bucket by hand, an amount set for a day, or money moved out of it.
     *
     * @param amountCents what was put in (+) or taken out (−); 0 on an amount set
     * @param setToCents  an amount set for the day: the bucket holds exactly this at the end of it, however what came
     *                    before changes; {@code null} for money put in or taken out
     * @param movedOut    money moved out of the bucket to spend (V33), changed as such rather than here
     */
    public record Change(long id, long bucketId, LocalDate date, long amountCents, String note, Long setToCents, boolean movedOut) {}

    /** What changing a bucket change came to. */
    public enum ChangeEdit {
        DONE,
        /** No such change in that bucket, or one moved out to spend, which is changed as a move out. */
        NOT_FOUND,
        /** Another amount is already set for that day. */
        SAME_DAY
    }

    /**
     * Changes money put in or taken out by hand, or an amount set for a day, in place (Rachel, 2026-10-21: "I want the
     * ability to edit basically everything I enter"). For an amount set, {@code amountCents} is what it is set to and
     * the note stays.
     */
    public ChangeEdit updateChange(long bucketId, long changeId, LocalDate date, long amountCents, String note) {
        return jdbi.inTransaction(handle -> {
            Optional<Change> found = handle.createQuery("""
                            SELECT id, bucket_id, date, amount, note, set_to, to_available FROM bucket_changes
                            WHERE id = :id AND bucket_id = :bucketId
                            """)
                    .bind("id", changeId)
                    .bind("bucketId", bucketId)
                    .map((rs, ctx) -> change(rs))
                    .findOne();
            if (found.isEmpty() || found.get().movedOut()) {
                return ChangeEdit.NOT_FOUND;
            }
            if (found.get().setToCents() != null) {
                boolean taken = handle.createQuery("""
                                SELECT count(*) FROM bucket_changes
                                WHERE bucket_id = :bucketId AND date = :date AND set_to IS NOT NULL AND id <> :id
                                """)
                        .bind("bucketId", bucketId)
                        .bind("date", date.toString())
                        .bind("id", changeId)
                        .mapTo(Integer.class)
                        .one() > 0;
                if (taken) {
                    return ChangeEdit.SAME_DAY;
                }
                handle.createUpdate("UPDATE bucket_changes SET date = :date, set_to = :setTo WHERE id = :id")
                        .bind("date", date.toString())
                        .bind("setTo", amountCents)
                        .bind("id", changeId)
                        .execute();
            } else {
                handle.createUpdate("UPDATE bucket_changes SET date = :date, amount = :amount, note = :note WHERE id = :id")
                        .bind("date", date.toString())
                        .bind("amount", amountCents)
                        .bind("note", note)
                        .bind("id", changeId)
                        .execute();
            }
            return ChangeEdit.DONE;
        });
    }

    /** Every bucket, in the order the user sees them. */
    public List<Bucket> all() {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT + "ORDER BY account_id, sort_order, id")
                .map((rs, ctx) -> bucket(rs))
                .list());
    }

    public Optional<Bucket> find(long id) {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT + "WHERE id = :id")
                .bind("id", id)
                .map((rs, ctx) -> bucket(rs))
                .findOne());
    }

    /** True when another bucket in the account already has this name ("Gifts" and "gifts" are the same). */
    public boolean nameTaken(long accountId, String name, Long exceptId) {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        SELECT EXISTS (SELECT 1 FROM buckets
                                       WHERE account_id = :accountId AND name = :name COLLATE NOCASE
                                         AND (:exceptId IS NULL OR id <> :exceptId))
                        """)
                .bind("accountId", accountId)
                .bind("name", name)
                .bind("exceptId", exceptId)
                .mapTo(Boolean.class)
                .one());
    }

    /** Adds a bucket at the end of its account's list, counting from today unless another day is given, and returns its id. */
    public long create(long accountId, Fields fields) {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        INSERT INTO buckets (account_id, name, color, goal_amount, goal_date, stop_at_goal, note, archived, archived_on,
                                             sort_order, opened_on, created_at)
                        VALUES (:accountId, :name, :color, :goal, :goalDate, :stopAtGoal, :note, :archived, :archivedOn,
                                (SELECT COALESCE(MAX(sort_order), 0) + 1 FROM buckets WHERE account_id = :accountId),
                                :openedOn, :now)
                        RETURNING id
                        """)
                .bind("accountId", accountId)
                .bind("name", fields.name())
                .bind("color", fields.color())
                .bind("goal", fields.goalCents())
                .bind("goalDate", fields.goalDate() == null ? null : fields.goalDate().toString())
                .bind("stopAtGoal", Boolean.TRUE.equals(fields.stopAtGoal()))
                .bind("note", fields.note())
                .bind("archived", fields.archivedOn() != null)
                .bind("archivedOn", fields.archivedOn() == null ? null : fields.archivedOn().toString())
                .bind("openedOn", (fields.openedOn() == null ? LocalDate.now(clock) : fields.openedOn()).toString())
                .bind("now", clock.instant().toString())
                .mapTo(Long.class)
                .one());
    }

    /** @return false when there is no such bucket */
    public boolean update(long id, Fields fields) {
        return jdbi.withHandle(handle -> handle
                .createUpdate("""
                        UPDATE buckets
                        SET name = :name, color = :color, goal_amount = :goal, goal_date = :goalDate, note = :note, archived = :archived,
                            archived_on = :archivedOn, opened_on = COALESCE(:openedOn, opened_on), stop_at_goal = COALESCE(:stopAtGoal, stop_at_goal)
                        WHERE id = :id
                        """)
                .bind("id", id)
                .bind("stopAtGoal", fields.stopAtGoal())
                .bind("openedOn", fields.openedOn() == null ? null : fields.openedOn().toString())
                .bind("goalDate", fields.goalDate() == null ? null : fields.goalDate().toString())
                .bind("name", fields.name())
                .bind("color", fields.color())
                .bind("goal", fields.goalCents())
                .bind("note", fields.note())
                .bind("archived", fields.archivedOn() != null)
                .bind("archivedOn", fields.archivedOn() == null ? null : fields.archivedOn().toString())
                .execute()) > 0;
    }

    /** Sets the day a bucket counts from, leaving everything else about it alone. */
    public void countFrom(long id, LocalDate openedOn) {
        jdbi.useHandle(handle -> handle
                .createUpdate("UPDATE buckets SET opened_on = :openedOn WHERE id = :id")
                .bind("id", id)
                .bind("openedOn", openedOn.toString())
                .execute());
    }

    /** True when a recurring item moves money into this bucket, saves up in it or is paid out of it. */
    public boolean isUsedByItems(long id) {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT EXISTS (SELECT 1 FROM item_versions WHERE bucket_id = :id OR save_up_bucket_id = :id OR from_bucket_id = :id)")
                .bind("id", id)
                .mapTo(Boolean.class)
                .one());
    }

    /**
     * Deletes a bucket with everything moved in and out of it. Interest that was going into it
     * goes back to being unassigned.
     *
     * @return false when there is no such bucket
     */
    public boolean delete(long id) {
        return jdbi.inTransaction(handle -> {
            handle.createUpdate("""
                            UPDATE interest_settings SET destination = 'unassigned', destination_bucket_id = NULL
                            WHERE destination_bucket_id = :id
                            """)
                    .bind("id", id)
                    .execute();
            return handle.createUpdate("DELETE FROM buckets WHERE id = :id").bind("id", id).execute() > 0;
        });
    }

    /** What the user moved in and out of one bucket by hand, newest first. */
    public List<Change> changes(long bucketId) {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        SELECT id, bucket_id, date, amount, note, set_to, to_available FROM bucket_changes
                        WHERE bucket_id = :bucketId ORDER BY date DESC, id DESC
                        """)
                .bind("bucketId", bucketId)
                .map((rs, ctx) -> change(rs))
                .list());
    }

    /** Every change made by hand, to any bucket, oldest first. */
    public List<Change> allChanges() {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT id, bucket_id, date, amount, note, set_to, to_available FROM bucket_changes ORDER BY date, id")
                .map((rs, ctx) -> change(rs))
                .list());
    }

    public void addChange(long bucketId, LocalDate date, long amountCents, String note) {
        jdbi.useHandle(handle -> handle
                .createUpdate("""
                        INSERT INTO bucket_changes (bucket_id, date, amount, source, note)
                        VALUES (:bucketId, :date, :amount, 'manual', :note)
                        """)
                .bind("bucketId", bucketId)
                .bind("date", date.toString())
                .bind("amount", amountCents)
                .bind("note", note)
                .execute());
    }

    /**
     * Sets what a bucket holds at the end of {@code date}. Kept as the amount, so it still holds after anything is
     * entered or moved before that day (spec §4.2). It replaces an amount set for the same day before, including one
     * kept the old way, as a difference under {@code adjustedNote}.
     */
    public void setHeld(long bucketId, LocalDate date, long amountCents, String note, String adjustedNote) {
        jdbi.useTransaction(handle -> {
            handle.createUpdate("""
                            DELETE FROM bucket_changes
                            WHERE bucket_id = :bucketId AND date = :date AND to_available = 0
                              AND (set_to IS NOT NULL OR note = :adjustedNote)
                            """)
                    .bind("bucketId", bucketId)
                    .bind("date", date.toString())
                    .bind("adjustedNote", adjustedNote)
                    .execute();
            handle.createUpdate("""
                            INSERT INTO bucket_changes (bucket_id, date, amount, source, note, set_to)
                            VALUES (:bucketId, :date, 0, 'manual', :note, :setTo)
                            """)
                    .bind("bucketId", bucketId)
                    .bind("date", date.toString())
                    .bind("note", note)
                    .bind("setTo", amountCents)
                    .execute();
        });
    }

    /**
     * Money moved out of a bucket to money not in a bucket (migration V33): listed under Income on
     * the pay period, with a category and tags like a one-off.
     *
     * @param amountCents        what was moved out, positive (stored as a negative change)
     * @param reimbursesEntryId the expense it pays back (migration V38); {@code null} when it pays none back
     * @param reimbursesPaymentId the flagged recurring payment it pays back instead (migration V46); {@code null} otherwise
     */
    public record MoveOut(
            long id,
            long bucketId,
            long accountId,
            LocalDate date,
            long amountCents,
            String note,
            Long categoryId,
            List<Long> tagIds,
            Long reimbursesEntryId,
            Long reimbursesPaymentId) {}

    /** Records a move out of a bucket. @return its id */
    public long addMoveOut(
            long bucketId, LocalDate date, long amountCents, String note, Long categoryId, List<Long> tagIds, Long reimbursesEntryId,
            Long reimbursesPaymentId) {
        return jdbi.inTransaction(handle -> {
            long id = handle.createUpdate("""
                            INSERT INTO bucket_changes
                                (bucket_id, date, amount, source, note, to_available, category_id, reimburses_entry_id,
                                 reimburses_payment_id)
                            VALUES (:bucketId, :date, :amount, 'manual', :note, 1, :categoryId, :reimburses, :reimbursesPayment)
                            """)
                    .bind("bucketId", bucketId)
                    .bind("date", date.toString())
                    .bind("amount", -amountCents)
                    .bind("note", note)
                    .bind("categoryId", categoryId)
                    .bind("reimburses", reimbursesEntryId)
                    .bind("reimbursesPayment", reimbursesPaymentId)
                    .executeAndReturnGeneratedKeys("id")
                    .mapTo(Long.class)
                    .one();
            saveMoveOutTags(handle, id, tagIds);
            return id;
        });
    }

    /** Changes a move out of a bucket. @return false when there is no such move in that bucket */
    public boolean updateMoveOut(
            long bucketId,
            long changeId,
            LocalDate date,
            long amountCents,
            String note,
            Long categoryId,
            List<Long> tagIds,
            Long reimbursesEntryId,
            Long reimbursesPaymentId) {
        return jdbi.inTransaction(handle -> {
            boolean found = handle.createUpdate("""
                            UPDATE bucket_changes SET date = :date, amount = :amount, note = :note, category_id = :categoryId,
                                reimburses_entry_id = :reimburses, reimburses_payment_id = :reimbursesPayment
                            WHERE id = :id AND bucket_id = :bucketId AND to_available = 1
                            """)
                    .bind("reimburses", reimbursesEntryId)
                    .bind("reimbursesPayment", reimbursesPaymentId)
                    .bind("id", changeId)
                    .bind("bucketId", bucketId)
                    .bind("date", date.toString())
                    .bind("amount", -amountCents)
                    .bind("note", note)
                    .bind("categoryId", categoryId)
                    .execute() > 0;
            if (found) {
                saveMoveOutTags(handle, changeId, tagIds);
            }
            return found;
        });
    }

    private static void saveMoveOutTags(Handle handle, long changeId, List<Long> tagIds) {
        handle.createUpdate("DELETE FROM bucket_change_tags WHERE change_id = :id").bind("id", changeId).execute();
        for (Long tagId : tagIds) {
            handle.createUpdate("INSERT INTO bucket_change_tags (change_id, tag_id) VALUES (:changeId, :tagId)")
                    .bind("changeId", changeId)
                    .bind("tagId", tagId)
                    .execute();
        }
    }

    private static final String MOVES_OUT = """
            SELECT c.id, c.bucket_id, b.account_id, c.date, c.amount, c.note, c.category_id, c.reimburses_entry_id,
                   c.reimburses_payment_id
            FROM bucket_changes c JOIN buckets b ON b.id = c.bucket_id
            WHERE c.to_available = 1 AND\s""";

    /** Every move out of a bucket dated within {@code from}–{@code to}, oldest first. */
    public List<MoveOut> movesOut(LocalDate from, LocalDate to) {
        return jdbi.withHandle(handle -> movesOut(handle, handle
                .createQuery(MOVES_OUT + "c.date BETWEEN :from AND :to ORDER BY c.date, c.id")
                .bind("from", from.toString())
                .bind("to", to.toString())));
    }

    /** The moves out of a bucket that pay {@code expenseId} back, oldest first. */
    public List<MoveOut> movesOutPayingBack(long expenseId) {
        return jdbi.withHandle(handle -> movesOut(handle, handle
                .createQuery(MOVES_OUT + "c.reimburses_entry_id = :id ORDER BY c.date, c.id")
                .bind("id", expenseId)));
    }

    /** The moves out of a bucket that pay back the flagged recurring payment {@code paymentId}, oldest first. */
    public List<MoveOut> movesOutPayingBackPayment(long paymentId) {
        return jdbi.withHandle(handle -> movesOut(handle, handle
                .createQuery(MOVES_OUT + "c.reimburses_payment_id = :id ORDER BY c.date, c.id")
                .bind("id", paymentId)));
    }

    private static List<MoveOut> movesOut(Handle handle, Query query) {
        return query
                .map((rs, ctx) -> {
                    long categoryId = rs.getLong("category_id");
                    Long category = rs.wasNull() ? null : categoryId;
                    long reimburses = rs.getLong("reimburses_entry_id");
                    Long paysBack = rs.wasNull() ? null : reimburses;
                    long payment = rs.getLong("reimburses_payment_id");
                    Long paysBackPayment = rs.wasNull() ? null : payment;
                    return new MoveOut(rs.getLong("id"), rs.getLong("bucket_id"), rs.getLong("account_id"),
                            LocalDate.parse(rs.getString("date")), -rs.getLong("amount"), rs.getString("note"), category, List.of(),
                            paysBack, paysBackPayment);
                })
                .list()
                .stream()
                .map(move -> new MoveOut(move.id(), move.bucketId(), move.accountId(), move.date(), move.amountCents(), move.note(),
                        move.categoryId(), handle.createQuery("SELECT tag_id FROM bucket_change_tags WHERE change_id = :id ORDER BY tag_id")
                                .bind("id", move.id())
                                .mapTo(Long.class)
                                .list(),
                        move.reimbursesEntryId(), move.reimbursesPaymentId()))
                .toList();
    }

    public boolean deleteChange(long bucketId, long changeId) {
        return jdbi.withHandle(handle -> handle
                .createUpdate("DELETE FROM bucket_changes WHERE id = :changeId AND bucket_id = :bucketId")
                .bind("changeId", changeId)
                .bind("bucketId", bucketId)
                .execute()) > 0;
    }

    private static Bucket bucket(ResultSet rs) throws SQLException {
        long goal = rs.getLong("goal_amount");
        boolean noGoal = rs.wasNull();
        return new Bucket(
                rs.getLong("id"),
                rs.getLong("account_id"),
                rs.getString("name"),
                rs.getString("color"),
                noGoal ? null : goal,
                rs.getString("note"),
                rs.getBoolean("archived"),
                LocalDate.parse(rs.getString("opened_on")),
                rs.getString("goal_date") == null ? null : LocalDate.parse(rs.getString("goal_date")),
                rs.getBoolean("stop_at_goal"),
                rs.getString("archived_on") == null ? null : LocalDate.parse(rs.getString("archived_on")));
    }

    /** An amount set for the day, or {@code null} for money put in or taken out. */
    private static Long setTo(ResultSet rs) throws SQLException {
        long cents = rs.getLong("set_to");
        return rs.wasNull() ? null : cents;
    }

    private static Change change(ResultSet rs) throws SQLException {
        return new Change(
                rs.getLong("id"),
                rs.getLong("bucket_id"),
                LocalDate.parse(rs.getString("date")),
                rs.getLong("amount"),
                rs.getString("note"),
                setTo(rs),
                rs.getBoolean("to_available"));
    }
}
