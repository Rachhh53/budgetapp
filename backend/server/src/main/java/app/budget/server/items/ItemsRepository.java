package app.budget.server.items;

import app.budget.engine.Money;
import app.budget.engine.items.PaidAhead;
import app.budget.engine.items.Placement;
import app.budget.engine.items.UsageBill;
import app.budget.engine.schedule.ScheduleRule;
import app.budget.server.api.Inputs;
import app.budget.server.schedule.ScheduleRules;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
     * @param archivedOn the day it was archived as of: nothing of it falls on that day or later; {@code null} when it
     *                   is not archived
     * @param createdOn  the day it was added: saving up for it cannot have started any earlier
     * @param versions   oldest first; never empty
     */
    /**
     * A recurring item with every version of it.
     *
     * @param countsFromPaid true when its next dates count on from the day it was last paid (migration V44)
     * @param fromLastYear   true when what is still to come is estimated from what it came to in the same month a year
     *                       before (migration V45)
     */
    public record Item(
            long id,
            String name,
            ItemKind kind,
            String note,
            boolean archived,
            LocalDate archivedOn,
            LocalDate createdOn,
            List<Long> tagIds,
            List<Version> versions,
            boolean countsFromPaid,
            boolean fromLastYear) {

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
     * @param billedLater    a bill whose schedule names visits, paid when an invoice for them arrives (spec §4.6)
     * @param paidAhead      a bill whose schedule names sessions that use up what was paid ahead for them (spec §4.6)
     * @param packSessions   paid ahead as a pack: how many sessions the amount (the pack's price) buys, and the next
     *                       pack is planned as a bill; {@code null} for an amount per session
     * @param usage          billed by use: about how often it is used, the amount being the cost of each use and the
     *                       schedule the monthly bill day and the months it can happen in; {@code null} otherwise
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
            Long fromBucketId,
            boolean billedLater,
            boolean paidAhead,
            Integer packSessions,
            ScheduleRule packSchedule,
            UsageBill.Usage usage) {

        /** Terms that are not billed by use. */
        public Terms(
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
                Long fromBucketId,
                boolean billedLater,
                boolean paidAhead,
                Integer packSessions,
                ScheduleRule packSchedule) {
            this(amountCents, amountMode, estimate, categoryId, payFromAccountId, payToAccountId, autopay, placement, schedule,
                    daysAfterPayday, bucketId, saveUpBucketId, fromBucketId, billedLater, paidAhead, packSessions, packSchedule, null);
        }

        /** Terms with no pack schedule: a pack, if any, is due when the sessions use up what was paid. */
        public Terms(
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
                Long fromBucketId,
                boolean billedLater,
                boolean paidAhead,
                Integer packSessions) {
            this(amountCents, amountMode, estimate, categoryId, payFromAccountId, payToAccountId, autopay, placement, schedule,
                    daysAfterPayday, bucketId, saveUpBucketId, fromBucketId, billedLater, paidAhead, packSessions, null);
        }

        /** Terms with no pack: everything but something paid ahead as a pack. */
        public Terms(
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
                Long fromBucketId,
                boolean billedLater,
                boolean paidAhead) {
            this(amountCents, amountMode, estimate, categoryId, payFromAccountId, payToAccountId, autopay, placement, schedule,
                    daysAfterPayday, bucketId, saveUpBucketId, fromBucketId, billedLater, paidAhead, null, null);
        }

        Terms withAmount(long cents) {
            return new Terms(cents, amountMode, estimate, categoryId, payFromAccountId, payToAccountId, autopay, placement, schedule,
                    daysAfterPayday, bucketId, saveUpBucketId, fromBucketId, billedLater, paidAhead, packSessions, packSchedule, usage);
        }

        Terms withUsage(UsageBill.Usage chosen) {
            return new Terms(amountCents, amountMode, estimate, categoryId, payFromAccountId, payToAccountId, autopay, placement, schedule,
                    daysAfterPayday, bucketId, saveUpBucketId, fromBucketId, billedLater, paidAhead, packSessions, packSchedule, chosen);
        }

        Terms withSchedule(ScheduleRule rule) {
            return withSchedules(rule, packSchedule);
        }

        Terms withSchedules(ScheduleRule rule, ScheduleRule packRule) {
            return new Terms(amountCents, amountMode, estimate, categoryId, payFromAccountId, payToAccountId, autopay, placement, rule,
                    daysAfterPayday, bucketId, saveUpBucketId, fromBucketId, billedLater, paidAhead, packSessions, packRule, usage);
        }

        /** True when it is billed by use: an estimate of each month's uses at the cost of each. */
        public boolean billedByUse() {
            return usage != null;
        }

        /** The pack it is bought in, when it is paid ahead as one; empty otherwise. */
        public Optional<PaidAhead.Pack> pack() {
            return paidAhead && packSessions != null ? Optional.of(new PaidAhead.Pack(Money.ofCents(amountCents), packSessions)) : Optional.empty();
        }
    }

    /**
     * What stays the same across an item's whole life.
     *
     * @param archivedOn the day it is archived as of; {@code null} when it is not archived
     */
    public record Identity(
            String name, ItemKind kind, String note, LocalDate archivedOn, List<Long> tagIds, boolean countsFromPaid, boolean fromLastYear) {

        /** An item whose dates keep to its schedule, whenever it is paid, at its own amount. */
        public Identity(String name, ItemKind kind, String note, LocalDate archivedOn, List<Long> tagIds) {
            this(name, kind, note, archivedOn, tagIds, false, false);
        }
    }

    /**
     * A "just this one" edit. {@code null} means that part of the occurrence was not changed.
     *
     * @param originalDueDate the date the schedule names for this occurrence
     * @param periodOverride  first day of the pay period it was moved into
     * @param projectedCents  what the app showed before the amount was first typed over
     * @param checkNumber     the check it was paid with, when it is paid from a checking account that way
     * @param payFromAccountId the account this one is paid from instead of the item's own (migration V35)
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
            String checkNumber,
            Long payFromAccountId) {

        /** An occurrence nobody has touched yet. */
        public static Override untouched(long itemId, LocalDate originalDueDate) {
            return new Override(itemId, originalDueDate, null, null, null, false, null, null, null, false, null, null, null);
        }

        /**
         * This occurrence at a typed amount, everything else as it was. What it showed before is kept the first time
         * only, as "just this one" keeps it, so the original stays on record however often it is changed.
         */
        public Override withAmount(long cents, long shownBefore) {
            return new Override(itemId, originalDueDate, cents, dueDate, periodOverride, paid, paidDate, confirmationCode, note, skipped,
                    projectedCents != null ? projectedCents : shownBefore, checkNumber, payFromAccountId);
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
                            INSERT INTO items (name, kind, note, archived, archived_on, counts_from_paid, estimates_from_last_year, created_at)
                            VALUES (:name, :kind, :note, :archived, :archivedOn, :countsFromPaid, :fromLastYear, :now)
                            RETURNING id
                            """)
                    .bind("name", identity.name())
                    .bind("kind", Inputs.code(identity.kind()))
                    .bind("note", identity.note())
                    .bind("archived", identity.archivedOn() != null)
                    .bind("archivedOn", identity.archivedOn() == null ? null : identity.archivedOn().toString())
                    .bind("countsFromPaid", identity.countsFromPaid())
                    .bind("fromLastYear", identity.fromLastYear())
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
        update(id, identity, terms, effectiveFrom, false);
    }

    /**
     * The same, and with {@code categoryEveryDate} the category reaches back over every earlier version too (Rachel,
     * 2026-10-25: "a way to retroactively update tags on a recurring item ... and categories"). Tags belong to the whole
     * item already. Packs bought ahead are one-off expenses of their own, so they follow the item: a new category from
     * the change's date on (every date, when asked), and new tags on every one.
     */
    public void update(long id, Identity identity, Terms terms, LocalDate effectiveFrom, boolean categoryEveryDate) {
        jdbi.useTransaction(handle -> {
            // The category in force on the change's date, or the first one when the item only starts after it.
            List<Version> before = versions(handle, id);
            Long categoryWas = before.stream()
                    .filter(version -> !version.effectiveFrom().isAfter(effectiveFrom))
                    .reduce((earlier, later) -> later)
                    .orElse(before.getFirst())
                    .terms()
                    .categoryId();
            List<Long> tagsWere = handle.createQuery("SELECT tag_id FROM item_tags WHERE item_id = :id ORDER BY tag_id")
                    .bind("id", id)
                    .mapTo(Long.class)
                    .list();
            if (categoryEveryDate) {
                handle.createUpdate("UPDATE item_versions SET category_id = :category WHERE item_id = :id AND effective_from < :from")
                        .bind("category", terms.categoryId())
                        .bind("id", id)
                        .bind("from", effectiveFrom.toString())
                        .execute();
            }
            if (categoryEveryDate || !Objects.equals(categoryWas, terms.categoryId())) {
                handle.createUpdate("""
                                UPDATE entries SET category_id = :category
                                WHERE id IN (SELECT entry_id FROM item_prepayments WHERE item_id = :id)
                                  AND (:every OR date >= :from)
                                """)
                        .bind("category", terms.categoryId())
                        .bind("id", id)
                        .bind("every", categoryEveryDate)
                        .bind("from", effectiveFrom.toString())
                        .execute();
            }
            if (!tagsWere.equals(identity.tagIds().stream().distinct().sorted().toList())) {
                List<Long> packs = handle.createQuery("SELECT entry_id FROM item_prepayments WHERE item_id = :id")
                        .bind("id", id)
                        .mapTo(Long.class)
                        .list();
                for (long entryId : packs) {
                    handle.createUpdate("DELETE FROM entry_tags WHERE entry_id = :entryId").bind("entryId", entryId).execute();
                    for (Long tagId : identity.tagIds().stream().distinct().toList()) {
                        handle.createUpdate("INSERT INTO entry_tags (entry_id, tag_id) VALUES (:entryId, :tagId)")
                                .bind("entryId", entryId)
                                .bind("tagId", tagId)
                                .execute();
                    }
                }
            }
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
                                    from_bucket_id = :fromBucketId, billed_later = :billedLater, paid_ahead = :paidAhead,
                                    pack_sessions = :packSessions, uses_hundredths = :usesHundredths, uses_per = :usesPer
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
                        .bind("billedLater", terms.billedLater())
                        .bind("paidAhead", terms.paidAhead())
                        .bind("packSessions", terms.packSessions())
                        .bind("usesHundredths", usesHundredths(terms))
                        .bind("usesPer", usesPer(terms))
                        .execute();
            }
            handle.createUpdate("""
                            UPDATE items SET name = :name, kind = :kind, note = :note, archived = :archived, archived_on = :archivedOn,
                                counts_from_paid = :countsFromPaid, estimates_from_last_year = :fromLastYear
                            WHERE id = :id
                            """)
                    .bind("id", id)
                    .bind("name", identity.name())
                    .bind("kind", Inputs.code(identity.kind()))
                    .bind("note", identity.note())
                    .bind("archived", identity.archivedOn() != null)
                    .bind("archivedOn", identity.archivedOn() == null ? null : identity.archivedOn().toString())
                    .bind("countsFromPaid", identity.countsFromPaid())
                    .bind("fromLastYear", identity.fromLastYear())
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
     * Sets what an item's amount is from {@code from} on and changes nothing else about it: how
     * a yearly plan's amount takes effect on the day its year starts. A version that starts that
     * very day has its amount changed; otherwise one is added that day, a copy of the one in
     * force then. A version that starts later is left alone, so a change already set for later
     * in the year stands. For an item that only begins after {@code from}, the version it begins
     * with is the one changed. The caller has checked that the item exists.
     */
    public void changeAmountFrom(long id, LocalDate from, long amountCents) {
        jdbi.useTransaction(handle -> {
            List<Version> versions = versions(handle, id);
            Version inForce = versions.getFirst();
            for (Version version : versions) {
                if (!version.effectiveFrom().isAfter(from)) {
                    inForce = version;
                }
            }
            if (inForce.terms().amountCents() == amountCents) {
                return;
            }
            if (inForce.effectiveFrom().isBefore(from)) {
                insertVersion(handle, id, from, inForce.terms().withAmount(amountCents));
                return;
            }
            handle.createUpdate("UPDATE item_versions SET amount = :amount WHERE id = :id")
                    .bind("id", inForce.id())
                    .bind("amount", amountCents)
                    .execute();
        });
    }

    /**
     * Archives one item and adds another in its place, in one step: for changing what an item
     * is from now on while leaving the old one's history exactly as it was: the old one is archived as of the day the
     * new one starts. Returns the new id. The caller has checked that the item exists.
     */
    /**
     * The archived item that {@code id} was added in place of, by "Archive this and add a new item" (taken out on
     * 2026-10-24: Rachel chose that a change of type always changes the one item). That left two items for one thing:
     * the old one archived on the very day the new one starts, under the same name and of another kind. Empty when
     * {@code id} is not such an item.
     */
    public Optional<Item> replaced(long id) {
        Optional<Item> item = find(id);
        if (item.isEmpty() || item.get().archived()) {
            return Optional.empty();
        }
        Item added = item.get();
        LocalDate starts = added.versions().getFirst().effectiveFrom();
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT id FROM items
                        WHERE archived = 1 AND id <> :id AND name = :name COLLATE NOCASE AND kind <> :kind AND archived_on = :starts
                        ORDER BY id DESC LIMIT 1
                        """)
                        .bind("id", id)
                        .bind("name", added.name())
                        .bind("kind", Inputs.code(added.kind()))
                        .bind("starts", starts.toString())
                        .mapTo(Long.class)
                        .findOne())
                .flatMap(this::find);
    }

    /**
     * Makes {@code added} and the archived item it replaced one item again, as if the type had been changed in place
     * (Rachel, 2026-10-24: "make it follow the new flow"). The old item is kept, since its past (paid dates, receipts,
     * single-date changes) belongs to it; it takes the added one's name, kind, note and tags, is no longer archived,
     * and its earlier versions take the added one's accounts and buckets, as a change of type does. Everything of the
     * added one then moves onto it, where the old one had something on the same date the added one's wins, and the
     * added one is deleted. One transaction, so Undo takes it all back at once.
     *
     * @return the item that is left
     */
    public long mergeReplaced(long added, long old) {
        jdbi.useTransaction(handle -> {
            String starts = handle.createQuery("SELECT MIN(effective_from) FROM item_versions WHERE item_id = :id")
                    .bind("id", added)
                    .mapTo(String.class)
                    .one();
            // Earlier versions follow the change of type, from the added item's newest version.
            handle.createUpdate("""
                            UPDATE item_versions
                            SET pay_from_account_id = n.pay_from_account_id, pay_to_account_id = n.pay_to_account_id,
                                autopay = n.autopay, placement = n.placement, bucket_id = n.bucket_id,
                                save_up_bucket_id = n.save_up_bucket_id, from_bucket_id = n.from_bucket_id,
                                billed_later = n.billed_later, paid_ahead = n.paid_ahead, pack_sessions = n.pack_sessions,
                                uses_hundredths = n.uses_hundredths, uses_per = n.uses_per
                            FROM (SELECT * FROM item_versions WHERE item_id = :added ORDER BY effective_from DESC, id DESC LIMIT 1) AS n
                            WHERE item_versions.item_id = :old
                            """)
                    .bind("added", added)
                    .bind("old", old)
                    .execute();
            // The added one's amounts carry on from its start; anything the old one had from then on gives way.
            for (Version version : versions(handle, old)) {
                if (!version.effectiveFrom().isBefore(LocalDate.parse(starts))) {
                    deleteVersion(handle, version.id());
                }
            }
            handle.createUpdate("UPDATE item_versions SET item_id = :old WHERE item_id = :added").bind("old", old).bind("added", added).execute();

            // Rows keyed by a date: the added one's wins over the old one's on the same date.
            moveByKey(handle, "occurrence_overrides", "original_due_date", added, old);
            moveByKey(handle, "item_invoices", "due_date", added, old);
            moveByKey(handle, "item_invoices", "covers_through", added, old);
            moveByKey(handle, "item_pack_moves", "planned_date", added, old);
            moveByKey(handle, "plan_year_amounts", "plan_year_id", added, old);
            for (String table : List.of("item_pauses", "item_prepayments", "attachments")) {
                handle.createUpdate("UPDATE " + table + " SET item_id = :old WHERE item_id = :added").bind("old", old).bind("added", added).execute();
            }
            for (String[] link : new String[][] {
                {"card_settings", "payment_item_id"}, {"loan_settings", "payment_item_id"}, {"card_promos", "payoff_item_id"}}) {
                handle.createUpdate("UPDATE " + link[0] + " SET " + link[1] + " = :old WHERE " + link[1] + " = :added")
                        .bind("old", old)
                        .bind("added", added)
                        .execute();
            }
            handle.createUpdate("DELETE FROM item_tags WHERE item_id = :old").bind("old", old).execute();
            handle.createUpdate("UPDATE item_tags SET item_id = :old WHERE item_id = :added").bind("old", old).bind("added", added).execute();

            handle.createUpdate("""
                            UPDATE items
                            SET name = n.name, kind = n.kind, note = n.note, counts_from_paid = n.counts_from_paid,
                                estimates_from_last_year = n.estimates_from_last_year, archived = 0, archived_on = NULL
                            FROM (SELECT name, kind, note, counts_from_paid, estimates_from_last_year FROM items WHERE id = :added) AS n
                            WHERE items.id = :old
                            """)
                    .bind("added", added)
                    .bind("old", old)
                    .execute();
            handle.createUpdate("DELETE FROM items WHERE id = :added").bind("added", added).execute();
        });
        return old;
    }

    /**
     * Moves a table's rows from one item to another where the rows are told apart by {@code key} as well: the old
     * item's row with the same key as one of the added item's goes first. Table and column names are this class's own.
     */
    private static void moveByKey(Handle handle, String table, String key, long added, long old) {
        handle.createUpdate("DELETE FROM " + table + " WHERE item_id = :old AND " + key + " IN (SELECT " + key + " FROM " + table
                        + " WHERE item_id = :added AND " + key + " IS NOT NULL)")
                .bind("old", old)
                .bind("added", added)
                .execute();
        handle.createUpdate("UPDATE " + table + " SET item_id = :old WHERE item_id = :added").bind("old", old).bind("added", added).execute();
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
    /** One payment of an item: the date its schedule named, and the day it was paid. */
    public record Paid(LocalDate scheduled, LocalDate paidOn) {}

    /**
     * For each item that counts its dates from the day it was paid, every payment with a paid date, in the order its
     * schedule named them. Each one's paid date is what the dates after it count on from, up to the next.
     */
    public Map<Long, List<Paid>> paymentsCountedFrom() {
        Map<Long, List<Paid>> found = new HashMap<>();
        jdbi.useHandle(handle -> handle
                .createQuery("""
                        SELECT o.item_id, o.original_due_date, o.paid_date
                        FROM occurrence_overrides o JOIN items i ON i.id = o.item_id
                        WHERE i.counts_from_paid = 1 AND o.paid = 1 AND o.paid_date IS NOT NULL
                        ORDER BY o.item_id, o.original_due_date
                        """)
                .map((rs, ctx) -> found.computeIfAbsent(rs.getLong("item_id"), id -> new ArrayList<>()).add(new Paid(
                        LocalDate.parse(rs.getString("original_due_date")), LocalDate.parse(rs.getString("paid_date")))))
                .list());
        return found;
    }

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
                             confirmation_code, note, skipped, projected_amount, check_number, pay_from_account_id)
                        VALUES (:itemId, :originalDueDate, :amount, :dueDate, :periodOverride, :paid, :paidDate,
                                :confirmationCode, :note, :skipped, :projected, :checkNumber, :payFrom)
                        ON CONFLICT (item_id, original_due_date) DO UPDATE SET
                            amount = excluded.amount, due_date = excluded.due_date,
                            period_override = excluded.period_override, paid = excluded.paid,
                            paid_date = excluded.paid_date, confirmation_code = excluded.confirmation_code,
                            note = excluded.note, skipped = excluded.skipped,
                            projected_amount = excluded.projected_amount, check_number = excluded.check_number,
                            pay_from_account_id = excluded.pay_from_account_id
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
                .bind("payFrom", edit.payFromAccountId())
                .execute());
    }

    /** The columns of {@code items}, before versions and tags are attached. */
    private record ItemRow(
            long id,
            String name,
            ItemKind kind,
            String note,
            boolean archived,
            LocalDate archivedOn,
            LocalDate createdOn,
            boolean countsFromPaid,
            boolean fromLastYear) {}

    private List<ItemRow> rows(Handle handle, String clause, Long id) {
        var query = handle.createQuery("SELECT id, name, kind, note, archived, archived_on, created_at, counts_from_paid, estimates_from_last_year FROM items " + clause);
        if (id != null) {
            query.bind("id", id);
        }
        return query.map((rs, ctx) -> new ItemRow(
                rs.getLong("id"),
                rs.getString("name"),
                ItemKind.valueOf(rs.getString("kind").toUpperCase(Locale.ROOT)),
                rs.getString("note"),
                rs.getBoolean("archived"),
                rs.getString("archived_on") == null ? null : LocalDate.parse(rs.getString("archived_on")),
                // Stored as an instant; the day it was on this PC is what counts.
                Instant.parse(rs.getString("created_at")).atZone(clock.getZone()).toLocalDate(),
                rs.getBoolean("counts_from_paid"),
                rs.getBoolean("estimates_from_last_year"))).list();
    }

    private static Item complete(Handle handle, ItemRow row) {
        List<Long> tagIds = handle.createQuery("SELECT tag_id FROM item_tags WHERE item_id = :id ORDER BY tag_id")
                .bind("id", row.id())
                .mapTo(Long.class)
                .list();
        return new Item(
                row.id(), row.name(), row.kind(), row.note(), row.archived(), row.archivedOn(), row.createdOn(), tagIds, versions(handle, row.id()),
                row.countsFromPaid(), row.fromLastYear());
    }

    /** A version row before its schedule is loaded. */
    private record VersionRow(long id, LocalDate effectiveFrom, long scheduleRuleId, Long packScheduleRuleId, Terms withoutSchedule) {}

    private static List<Version> versions(Handle handle, long itemId) {
        List<VersionRow> rows = handle
                .createQuery("SELECT * FROM item_versions WHERE item_id = :itemId ORDER BY effective_from")
                .bind("itemId", itemId)
                .map((rs, ctx) -> new VersionRow(
                        rs.getLong("id"),
                        LocalDate.parse(rs.getString("effective_from")),
                        rs.getLong("schedule_rule_id"),
                        nullableLong(rs, "pack_schedule_rule_id"),
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
                                nullableLong(rs, "from_bucket_id"),
                                rs.getBoolean("billed_later"),
                                rs.getBoolean("paid_ahead"),
                                nullableInt(rs, "pack_sessions"))
                                .withUsage(usage(rs))))
                .list();
        List<Version> versions = new ArrayList<>();
        for (VersionRow row : rows) {
            Terms terms = row.withoutSchedule().withSchedules(
                    ScheduleRules.load(handle, row.scheduleRuleId()),
                    row.packScheduleRuleId() == null ? null : ScheduleRules.load(handle, row.packScheduleRuleId()));
            versions.add(new Version(row.id(), row.effectiveFrom(), terms));
        }
        return versions;
    }

    private static void insertVersion(Handle handle, long itemId, LocalDate effectiveFrom, Terms terms) {
        long ruleId = ScheduleRules.insert(handle, terms.schedule());
        Long packRuleId = terms.packSchedule() == null ? null : ScheduleRules.insert(handle, terms.packSchedule());
        handle.createUpdate("""
                        INSERT INTO item_versions
                            (item_id, effective_from, amount, amount_mode, is_estimate, category_id, pay_from_account_id,
                             pay_to_account_id, autopay, placement, schedule_rule_id, days_after_payday,
                             bucket_id, save_up_bucket_id, from_bucket_id, billed_later, paid_ahead, pack_sessions,
                             pack_schedule_rule_id, uses_hundredths, uses_per)
                        VALUES (:itemId, :effectiveFrom, :amount, :amountMode, :estimate, :categoryId, :payFrom,
                                :payTo, :autopay, :placement, :ruleId, :daysAfterPayday, :bucketId, :saveUpBucketId,
                                :fromBucketId, :billedLater, :paidAhead, :packSessions, :packRuleId, :usesHundredths, :usesPer)
                        """)
                .bind("packRuleId", packRuleId)
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
                .bind("billedLater", terms.billedLater())
                .bind("paidAhead", terms.paidAhead())
                .bind("packSessions", terms.packSessions())
                .bind("usesHundredths", usesHundredths(terms))
                .bind("usesPer", usesPer(terms))
                .execute();
    }

    private static Long usesHundredths(Terms terms) {
        return terms.billedByUse() ? terms.usage().hundredths() : null;
    }

    private static String usesPer(Terms terms) {
        return terms.billedByUse() ? Inputs.code(terms.usage().per()) : null;
    }

    /** A version row's uses, when it is billed by use; {@code null} otherwise. */
    private static UsageBill.Usage usage(ResultSet rs) throws SQLException {
        long hundredths = rs.getLong("uses_hundredths");
        if (rs.wasNull()) {
            return null;
        }
        return new UsageBill.Usage(hundredths, UsageBill.Per.valueOf(rs.getString("uses_per").toUpperCase(Locale.ROOT)));
    }

    /** The planned packs of {@code itemId} moved to another day: the day Budget planned each on → the day it was moved to. */
    public Map<LocalDate, LocalDate> packMoves(long itemId) {
        Map<LocalDate, LocalDate> moves = new HashMap<>();
        jdbi.useHandle(handle -> handle
                .createQuery("SELECT planned_date, moved_to FROM item_pack_moves WHERE item_id = :id")
                .bind("id", itemId)
                .map((rs, ctx) -> Map.entry(LocalDate.parse(rs.getString("planned_date")), LocalDate.parse(rs.getString("moved_to"))))
                .forEach(move -> moves.put(move.getKey(), move.getValue())));
        return moves;
    }

    /** Moves the pack planned on {@code planned} to {@code movedTo}; moving it again replaces the move. */
    public void movePack(long itemId, LocalDate planned, LocalDate movedTo) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        INSERT INTO item_pack_moves (item_id, planned_date, moved_to) VALUES (:id, :planned, :movedTo)
                        ON CONFLICT (item_id, planned_date) DO UPDATE SET moved_to = excluded.moved_to
                        """)
                .bind("id", itemId)
                .bind("planned", planned.toString())
                .bind("movedTo", movedTo.toString())
                .execute());
    }

    /** Puts the pack planned on {@code planned} back on that day. @return false when it wasn't moved */
    public boolean unmovePack(long itemId, LocalDate planned) {
        return jdbi.withHandle(handle -> handle
                .createUpdate("DELETE FROM item_pack_moves WHERE item_id = :id AND planned_date = :planned")
                .bind("id", itemId)
                .bind("planned", planned.toString())
                .execute()) > 0;
    }

    /** What removing one version of an item came to. */
    public enum Removed {
        DONE,
        /** No such version of that item. */
        NOT_FOUND,
        /** It is the item's only version: an item always keeps one. */
        LAST
    }

    /**
     * Removes one version of an item, from its Amount history (Rachel, 2026-10-20: "amount history record in
     * recurring items should be able to be deleted"). The version before it then carries on through its dates; the
     * first one removed, the next covers everything before it, as the earliest version always does.
     */
    public Removed removeVersion(long itemId, long versionId) {
        return jdbi.inTransaction(handle -> {
            List<Long> ids = handle.createQuery("SELECT id FROM item_versions WHERE item_id = :itemId")
                    .bind("itemId", itemId)
                    .mapTo(Long.class)
                    .list();
            if (!ids.contains(versionId)) {
                return Removed.NOT_FOUND;
            }
            if (ids.size() < 2) {
                return Removed.LAST;
            }
            deleteVersion(handle, versionId);
            return Removed.DONE;
        });
    }

    /** Deletes a version and then its schedules: the version points at them, so it has to go first. */
    private static void deleteVersion(Handle handle, long versionId) {
        List<Long> ruleIds = handle.createQuery("""
                        SELECT schedule_rule_id FROM item_versions WHERE id = :id
                        UNION ALL
                        SELECT pack_schedule_rule_id FROM item_versions WHERE id = :id AND pack_schedule_rule_id IS NOT NULL
                        """)
                .bind("id", versionId)
                .mapTo(Long.class)
                .list();
        handle.createUpdate("DELETE FROM item_versions WHERE id = :id").bind("id", versionId).execute();
        ruleIds.forEach(ruleId -> ScheduleRules.delete(handle, ruleId));
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
                rs.getString("check_number"),
                nullableLong(rs, "pay_from_account_id"));
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
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
