package app.budget.server.buckets;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;

/** Reads and writes {@code buckets} and what the user moved in and out of them by hand ({@code bucket_changes}). */
public final class BucketsRepository {

    private static final String SELECT =
            "SELECT id, account_id, name, color, goal_amount, note, archived, opened_on FROM buckets ";

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
     */
    public record Bucket(
            long id, long accountId, String name, String color, Long goalCents, String note, boolean archived, LocalDate openedOn) {}

    /**
     * What the user can set on a bucket. The account is chosen once, when it is added.
     *
     * @param openedOn {@code null} for today when adding, and to leave it as it is when changing
     */
    public record Fields(String name, String color, Long goalCents, String note, boolean archived, LocalDate openedOn) {}

    /** Money put into (+) or taken out of (−) a bucket by hand. */
    public record Change(long id, long bucketId, LocalDate date, long amountCents, String note) {}

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
                        INSERT INTO buckets (account_id, name, color, goal_amount, note, archived, sort_order, opened_on, created_at)
                        VALUES (:accountId, :name, :color, :goal, :note, :archived,
                                (SELECT COALESCE(MAX(sort_order), 0) + 1 FROM buckets WHERE account_id = :accountId),
                                :openedOn, :now)
                        RETURNING id
                        """)
                .bind("accountId", accountId)
                .bind("name", fields.name())
                .bind("color", fields.color())
                .bind("goal", fields.goalCents())
                .bind("note", fields.note())
                .bind("archived", fields.archived())
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
                        SET name = :name, color = :color, goal_amount = :goal, note = :note, archived = :archived,
                            opened_on = COALESCE(:openedOn, opened_on)
                        WHERE id = :id
                        """)
                .bind("id", id)
                .bind("openedOn", fields.openedOn() == null ? null : fields.openedOn().toString())
                .bind("name", fields.name())
                .bind("color", fields.color())
                .bind("goal", fields.goalCents())
                .bind("note", fields.note())
                .bind("archived", fields.archived())
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

    /** True when a recurring item moves money into this bucket or saves up in it. */
    public boolean isUsedByItems(long id) {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT EXISTS (SELECT 1 FROM item_versions WHERE bucket_id = :id OR save_up_bucket_id = :id)")
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
                        SELECT id, bucket_id, date, amount, note FROM bucket_changes
                        WHERE bucket_id = :bucketId ORDER BY date DESC, id DESC
                        """)
                .bind("bucketId", bucketId)
                .map((rs, ctx) -> change(rs))
                .list());
    }

    /** Every change made by hand, to any bucket, oldest first. */
    public List<Change> allChanges() {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT id, bucket_id, date, amount, note FROM bucket_changes ORDER BY date, id")
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
                LocalDate.parse(rs.getString("opened_on")));
    }

    private static Change change(ResultSet rs) throws SQLException {
        return new Change(
                rs.getLong("id"),
                rs.getLong("bucket_id"),
                LocalDate.parse(rs.getString("date")),
                rs.getLong("amount"),
                rs.getString("note"));
    }
}
