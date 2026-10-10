package app.budget.server.items;

import app.budget.engine.items.Placement;
import app.budget.engine.schedule.ScheduleRule;
import app.budget.server.api.Inputs;
import app.budget.server.schedule.ScheduleRules;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/** Reads and writes recurring items, their versions, their tags and single-occurrence edits. */
public final class ItemsRepository {

    private final Jdbi jdbi;
    private final Clock clock;

    public ItemsRepository(Jdbi jdbi, Clock clock) {
        this.jdbi = jdbi;
        this.clock = clock;
    }

    /**
     * A recurring item with every version of it.
     *
     * @param createdOn the day it was added: saving up for it cannot have started any earlier
     * @param versions  oldest first; never empty
     */
    public record Item(
            long id,
            String name,
            ItemKind kind,
            String note,
            boolean archived,
            LocalDate createdOn,
            List<Long> tagIds,
            List<Version> versions) {

        /** The version in force on {@code date}. The earliest one also covers every date before it. */
        public Version versionOn(LocalDate date) {
            Version inForce = versions.getFirst();
            for (Version version : versions) {
                if (!version.effectiveFrom().isAfter(date)) {
                    inForce = version;
                }
            }
            return inForce;
        }
    }

    /** One version of an item: what was true from {@code effectiveFrom} until the next version. */
    public record Version(long id, LocalDate effectiveFrom, Terms terms) {}

    /**
     * What a "this and all future" edit can change. Amounts are whole cents.
     *
     * @param bucketId       the bucket a bucket move, or a transfer, puts its money in; {@code null} otherwise
     * @param saveUpBucketId the bucket a share of the bill is set aside in every paycheck; {@code null} when it is not saved up for
     * @param fromBucketId   the bucket of the paying account this comes out of; {@code null} for money that is not in a bucket
     */
    public record Terms(
            long amountCents,
            AmountMode amountMode,
            boolean estimate,
            Long categoryId,
            long payFromAccountId,
            Long payToAccountId,
            boolean autopay,
            Placement placement,
            ScheduleRule schedule,
            int daysAfterPayday,
            Long bucketId,
            Long saveUpBucketId,
            Long fromBucketId) {

        Terms withSchedule(ScheduleRule rule) {
            return new Terms(amountCents, amountMode, estimate, categoryId, payFromAccountId, payToAccountId,
                    autopay, placement, rule, daysAfterPayday, bucketId, saveUpBucketId, fromBucketId);
        }
    }

    /** What stays the same across an item's whole life. */
    public record Identity(String name, ItemKind kind, String note, boolean archived, List<Long> tagIds) {}

    /**
     * A "just this one" edit. {@code null} means that part of the occurrence was not changed.
     *
     * @param originalDueDate the date the schedule names for this occurrence
     * @param periodOverride  first day of the pay period it was moved into
     * @param projectedCents  what the app showed before the amount was first typed over
     * @param checkNumber     the check it was paid with, when it is paid from a checking account that way
     */
    public record Override(
            long itemId,
            LocalDate originalDueDate,
            Long amountCents,
            LocalDate dueDate,
            LocalDate periodOverride,
            boolean paid,
            LocalDate paidDate,
            String confirmationCode,
            String note,
            boolean skipped,
            Long projectedCents,
            String checkNumber) {

        /** An occurrence nobody has touched yet. */
        public static Override untouched(long itemId, LocalDate originalDueDate) {
            return new Override(itemId, originalDueDate, null, null, null, false, null, null, null, false, null, null);
        }
    }

    /** Every item, by name. */
    public List<Item> all() {
        return jdbi.withHandle(handle -> {
            List<Item> items = new ArrayList<>();
            for (ItemRow row : rows(handle, "ORDER BY name COLLATE NOCASE, id", null)) {
                items.add(complete(handle, row));
            }
            return items;
        });
    }

    public Optional<Item> find(long id) {
        return jdbi.withHandle(handle -> rows(handle, "WHERE id = :id", id).stream()
                .findFirst()
                .map(row -> complete(handle, row)));
    }

    /** Adds an item with its first version and returns its id. */
    public long create(Identity identity, Terms terms, LocalDate effectiveFrom) {
        return jdbi.inTransaction(handle -> {
            long id = handle.createQuery("""
                            INSERT INTO items (name, kind, note, archived, created_at)
                            VALUES (:name, :kind, :note, :archived, :now)
                            RETURNING id
                            """)
                    .bind("name", identity.name())
                    .bind("kind", Inputs.code(identity.kind()))
                    .bind("note", identity.note())
                    .bind("archived", identity.archived())
                    .bind("now", clock.instant().toString())
                    .mapTo(Long.class)
                    .one();
            insertVersion(handle, id, effectiveFrom, terms);
            replaceTags(handle, id, identity.tagIds());
            return id;
        });
    }

    /**
     * Changes an item "from this date on". Versions starting on or after {@code effectiveFrom}
     * are replaced by one new version; earlier ones are left exactly as they were, so past
     * occurrences keep what they had. Nothing is written when the terms did not change.
     * The caller has checked that the item exists.
     *
     * <p>The one exception is a change of kind. An item has one kind for its whole life, so
     * earlier versions cannot keep accounts and buckets that meant something else (a bucket
     * move turned into a transfer would be left with nowhere to go). Those versions keep their
     * dates, amounts and schedules, and take where the money comes from and goes to from the
     * new terms.
     */
    public void update(long id, Identity identity, Terms terms, LocalDate effectiveFrom) {
        jdbi.useTransaction(handle -> {
            boolean kindChanged = !handle.createQuery("SELECT kind FROM items WHERE id = :id")
                    .bind("id", id)
                    .mapTo(String.class)
                    .one()
                    .equals(Inputs.code(identity.kind()));
            if (kindChanged) {
                handle.createUpdate("""
                                UPDATE item_versions
                                SET pay_from_account_id = :payFrom, pay_to_account_id = :payTo, autopay = :autopay,
                                    placement = :placement, bucket_id = :bucketId, save_up_bucket_id = :saveUpBucketId,
                                    from_bucket_id = :fromBucketId
                                WHERE item_id = :id AND effective_from < :effectiveFrom
                                """)
                        .bind("id", id)
                        .bind("effectiveFrom", effectiveFrom.toString())
                        .bind("payFrom", terms.payFromAccountId())
                        .bind("payTo", terms.payToAccountId())
                        .bind("autopay", terms.autopay())
                        .bind("placement", Inputs.code(terms.placement()))
                        .bind("bucketId", terms.bucketId())
                        .bind("saveUpBucketId", terms.saveUpBucketId())
                        .bind("fromBucketId", terms.fromBucketId())
                        .execute();
            }
            handle.createUpdate("UPDATE items SET name = :name, kind = :kind, note = :note, archived = :archived WHERE id = :id")
                    .bind("id", id)
                    .bind("name", identity.name())
                    .bind("kind", Inputs.code(identity.kind()))
                    .bind("note", identity.note())
                    .bind("archived", identity.archived())
                    .execute();
            replaceTags(handle, id, identity.tagIds());

            List<Version> versions = versions(handle, id);
            Version latest = versions.getLast();
            boolean nothingLater = !latest.effectiveFrom().isAfter(effectiveFrom);
            if (nothingLater && latest.terms().equals(terms)) {
                return;
            }
            for (Version version : versions) {
                if (!version.effectiveFrom().isBefore(effectiveFrom)) {
                    deleteVersion(handle, version.id());
                }
            }
            insertVersion(handle, id, effectiveFrom, terms);
        });
    }

    /**
     * Archives one item and adds another in its place, in one step: for changing what an item
     * is from now on while leaving the old one's history exactly as it was. Returns the new id.
     * The caller has checked that the item exists.
     */
    public long replace(long id, Identity identity, Terms terms) {
        return jdbi.inTransaction(handle -> {
            handle.createUpdate("UPDATE items SET archived = 1 WHERE id = :id").bind("id", id).execute();
            long created = handle.createQuery("""
                            INSERT INTO items (name, kind, note, archived, created_at)
                            VALUES (:name, :kind, :note, 0, :now)
                            RETURNING id
                            """)
                    .bind("name", identity.name())
                    .bind("kind", Inputs.code(identity.kind()))
                    .bind("note", identity.note())
                    .bind("now", clock.instant().toString())
                    .mapTo(Long.class)
                    .one();
            insertVersion(handle, created, terms.schedule().startDate(), terms);
            replaceTags(handle, created, identity.tagIds());
            return created;
        });
    }

    /** Deletes an item with its versions, schedules, tags and single-occurrence edits. */
    public void delete(long id) {
        jdbi.useTransaction(handle -> {
            for (Version version : versions(handle, id)) {
                deleteVersion(handle, version.id());
            }
            handle.createUpdate("DELETE FROM items WHERE id = :id").bind("id", id).execute();
        });
    }

    /**
     * A stretch during which an item does nothing.
     *
     * @param until the first day it runs again; {@code null} while paused until turned back on
     */
    public record Pause(long itemId, LocalDate from, LocalDate until) {

        public boolean covers(LocalDate date) {
            return !date.isBefore(from) && (until == null || date.isBefore(until));
        }
    }

    /** Every pause of every item, by item. */
    public Map<Long, List<Pause>> pauses() {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT item_id, from_date, until_date FROM item_pauses ORDER BY from_date")
                .map((rs, ctx) -> new Pause(
                        rs.getLong("item_id"),
                        LocalDate.parse(rs.getString("from_date")),
                        rs.getString("until_date") == null ? null : LocalDate.parse(rs.getString("until_date"))))
                .stream()
                .collect(Collectors.groupingBy(Pause::itemId)));
    }

    /**
     * Pauses an item from {@code from} until {@code until} ({@code null}: until it is resumed).
     * Whatever pause was running or still to come is replaced; the days already paused stay paused.
     */
    public void pause(long itemId, LocalDate from, LocalDate until) {
        jdbi.useTransaction(handle -> {
            endPauses(handle, itemId, from);
            handle.createUpdate("INSERT INTO item_pauses (item_id, from_date, until_date) VALUES (:itemId, :from, :until)")
                    .bind("itemId", itemId)
                    .bind("from", from.toString())
                    .bind("until", until == null ? null : until.toString())
                    .execute();
        });
    }

    /** Lets an item run again from {@code on}. The days it was paused before that stay paused. */
    public void resume(long itemId, LocalDate on) {
        jdbi.useTransaction(handle -> endPauses(handle, itemId, on));
    }

    private static void endPauses(Handle handle, long itemId, LocalDate on) {
        handle.createUpdate("DELETE FROM item_pauses WHERE item_id = :itemId AND from_date >= :on")
                .bind("itemId", itemId)
                .bind("on", on.toString())
                .execute();
        handle.createUpdate("""
                        UPDATE item_pauses SET until_date = :on
                        WHERE item_id = :itemId AND from_date < :on AND (until_date IS NULL OR until_date > :on)
                        """)
                .bind("itemId", itemId)
                .bind("on", on.toString())
                .execute();
    }

    /** Single-occurrence edits whose scheduled date falls within {@code from}–{@code to}. */
    public List<Override> overrides(LocalDate from, LocalDate to) {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT * FROM occurrence_overrides WHERE original_due_date BETWEEN :from AND :to")
                .bind("from", from.toString())
                .bind("to", to.toString())
                .map((rs, ctx) -> override(rs))
                .list());
    }

    /** Saves the whole edit for one occurrence, replacing what was stored for it. */
    public void saveOverride(Override edit) {
        jdbi.useHandle(handle -> handle
                .createUpdate("""
                        INSERT INTO occurrence_overrides
                            (item_id, original_due_date, amount, due_date, period_override, paid, paid_date,
                             confirmation_code, note, skipped, projected_amount, check_number)
                        VALUES (:itemId, :originalDueDate, :amount, :dueDate, :periodOverride, :paid, :paidDate,
                                :confirmationCode, :note, :skipped, :projected, :checkNumber)
                        ON CONFLICT (item_id, original_due_date) DO UPDATE SET
                            amount = excluded.amount, due_date = excluded.due_date,
                            period_override = excluded.period_override, paid = excluded.paid,
                            paid_date = excluded.paid_date, confirmation_code = excluded.confirmation_code,
                            note = excluded.note, skipped = excluded.skipped,
                            projected_amount = excluded.projected_amount, check_number = excluded.check_number
                        """)
                .bind("itemId", edit.itemId())
                .bind("originalDueDate", edit.originalDueDate().toString())
                .bind("amount", edit.amountCents())
                .bind("dueDate", text(edit.dueDate()))
                .bind("periodOverride", text(edit.periodOverride()))
                .bind("paid", edit.paid())
                .bind("paidDate", text(edit.paidDate()))
                .bind("confirmationCode", edit.confirmationCode())
                .bind("note", edit.note())
                .bind("skipped", edit.skipped())
                .bind("projected", edit.projectedCents())
                .bind("checkNumber", edit.checkNumber())
                .execute());
    }

    /** The columns of {@code items}, before versions and tags are attached. */
    private record ItemRow(long id, String name, ItemKind kind, String note, boolean archived, LocalDate createdOn) {}

    private List<ItemRow> rows(Handle handle, String clause, Long id) {
        var query = handle.createQuery("SELECT id, name, kind, note, archived, created_at FROM items " + clause);
        if (id != null) {
            query.bind("id", id);
        }
        return query.map((rs, ctx) -> new ItemRow(
                rs.getLong("id"),
                rs.getString("name"),
                ItemKind.valueOf(rs.getString("kind").toUpperCase(Locale.ROOT)),
                rs.getString("note"),
                rs.getBoolean("archived"),
                // Stored as an instant; the day it was on this PC is what counts.
                Instant.parse(rs.getString("created_at")).atZone(clock.getZone()).toLocalDate())).list();
    }

    private static Item complete(Handle handle, ItemRow row) {
        List<Long> tagIds = handle.createQuery("SELECT tag_id FROM item_tags WHERE item_id = :id ORDER BY tag_id")
                .bind("id", row.id())
                .mapTo(Long.class)
                .list();
        return new Item(
                row.id(), row.name(), row.kind(), row.note(), row.archived(), row.createdOn(), tagIds, versions(handle, row.id()));
    }

    /** A version row before its schedule is loaded. */
    private record VersionRow(long id, LocalDate effectiveFrom, long scheduleRuleId, Terms withoutSchedule) {}

    private static List<Version> versions(Handle handle, long itemId) {
        List<VersionRow> rows = handle
                .createQuery("SELECT * FROM item_versions WHERE item_id = :itemId ORDER BY effective_from")
                .bind("itemId", itemId)
                .map((rs, ctx) -> new VersionRow(
                        rs.getLong("id"),
                        LocalDate.parse(rs.getString("effective_from")),
                        rs.getLong("schedule_rule_id"),
                        new Terms(
                                rs.getLong("amount"),
                                AmountMode.valueOf(rs.getString("amount_mode").toUpperCase(Locale.ROOT)),
                                rs.getBoolean("is_estimate"),
                                nullableLong(rs, "category_id"),
                                rs.getLong("pay_from_account_id"),
                                nullableLong(rs, "pay_to_account_id"),
                                rs.getBoolean("autopay"),
                                Placement.valueOf(rs.getString("placement").toUpperCase(Locale.ROOT)),
                                null,
                                rs.getInt("days_after_payday"),
                                nullableLong(rs, "bucket_id"),
                                nullableLong(rs, "save_up_bucket_id"),
                                nullableLong(rs, "from_bucket_id"))))
                .list();
        List<Version> versions = new ArrayList<>();
        for (VersionRow row : rows) {
            Terms terms = row.withoutSchedule().withSchedule(ScheduleRules.load(handle, row.scheduleRuleId()));
            versions.add(new Version(row.id(), row.effectiveFrom(), terms));
        }
        return versions;
    }

    private static void insertVersion(Handle handle, long itemId, LocalDate effectiveFrom, Terms terms) {
        long ruleId = ScheduleRules.insert(handle, terms.schedule());
        handle.createUpdate("""
                        INSERT INTO item_versions
                            (item_id, effective_from, amount, amount_mode, is_estimate, category_id, pay_from_account_id,
                             pay_to_account_id, autopay, placement, schedule_rule_id, days_after_payday,
                             bucket_id, save_up_bucket_id, from_bucket_id)
                        VALUES (:itemId, :effectiveFrom, :amount, :amountMode, :estimate, :categoryId, :payFrom,
                                :payTo, :autopay, :placement, :ruleId, :daysAfterPayday, :bucketId, :saveUpBucketId,
                                :fromBucketId)
                        """)
                .bind("itemId", itemId)
                .bind("effectiveFrom", effectiveFrom.toString())
                .bind("amount", terms.amountCents())
                .bind("amountMode", Inputs.code(terms.amountMode()))
                .bind("estimate", terms.estimate())
                .bind("categoryId", terms.categoryId())
                .bind("payFrom", terms.payFromAccountId())
                .bind("payTo", terms.payToAccountId())
                .bind("autopay", terms.autopay())
                .bind("placement", Inputs.code(terms.placement()))
                .bind("ruleId", ruleId)
                .bind("daysAfterPayday", terms.daysAfterPayday())
                .bind("bucketId", terms.bucketId())
                .bind("saveUpBucketId", terms.saveUpBucketId())
                .bind("fromBucketId", terms.fromBucketId())
                .execute();
    }

    /** Deletes a version and then its schedule: the version points at the schedule, so it has to go first. */
    private static void deleteVersion(Handle handle, long versionId) {
        long ruleId = handle.createQuery("SELECT schedule_rule_id FROM item_versions WHERE id = :id")
                .bind("id", versionId)
                .mapTo(Long.class)
                .one();
        handle.createUpdate("DELETE FROM item_versions WHERE id = :id").bind("id", versionId).execute();
        ScheduleRules.delete(handle, ruleId);
    }

    private static void replaceTags(Handle handle, long itemId, List<Long> tagIds) {
        handle.createUpdate("DELETE FROM item_tags WHERE item_id = :id").bind("id", itemId).execute();
        for (Long tagId : tagIds) {
            handle.createUpdate("INSERT INTO item_tags (item_id, tag_id) VALUES (:itemId, :tagId)")
                    .bind("itemId", itemId)
                    .bind("tagId", tagId)
                    .execute();
        }
    }

    private static Override override(ResultSet rs) throws SQLException {
        return new Override(
                rs.getLong("item_id"),
                LocalDate.parse(rs.getString("original_due_date")),
                nullableLong(rs, "amount"),
                date(rs.getString("due_date")),
                date(rs.getString("period_override")),
                rs.getBoolean("paid"),
                date(rs.getString("paid_date")),
                rs.getString("confirmation_code"),
                rs.getString("note"),
                rs.getBoolean("skipped"),
                nullableLong(rs, "projected_amount"),
                rs.getString("check_number"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDate date(String text) {
        return text == null ? null : LocalDate.parse(text);
    }

    private static String text(LocalDate date) {
        return date == null ? null : date.toString();
    }
}
