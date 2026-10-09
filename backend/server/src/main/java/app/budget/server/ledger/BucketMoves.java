package app.budget.server.ledger;

import app.budget.engine.Money;
import app.budget.engine.buckets.SaveUp;
import app.budget.engine.schedule.PayPeriod;
import app.budget.engine.schedule.ScheduleRule;
import app.budget.server.buckets.BucketsRepository.Bucket;
import app.budget.server.buckets.BucketsRepository.Change;
import app.budget.server.entries.EntriesRepository.Entry;
import app.budget.server.entries.EntriesRepository.Split;
import app.budget.server.entries.EntryKind;
import app.budget.server.items.ItemCalendar.Occurrence;
import app.budget.server.items.ItemKind;
import app.budget.server.items.ItemsRepository.Item;
import app.budget.server.items.ItemsRepository.Version;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Works out everything that moves money in and out of buckets, apart from interest: what the
 * user did by hand, splits of one-offs, recurring bucket moves, transfers into a bucket, and
 * saving up for bills. Like occurrences, none of the recurring part is stored.
 */
public final class BucketMoves {

    /** How far past the last period to look when nothing is saved up for: nothing further out matters. */
    private static final int NO_LOOK_AHEAD = 0;

    /** A bill due further out than this is not saved up for until it comes within reach. */
    private static final int MAX_LOOK_AHEAD_MONTHS = 120;

    private BucketMoves() {}

    /** Why a bucket's balance moved. */
    public enum Kind {
        /** The user put money in or took it out by hand. */
        MANUAL,
        /** A recurring bucket move ("$50 every paycheck to Holiday gifts"). */
        BUCKET_MOVE,
        /** A recurring transfer that arrives in this bucket. */
        TRANSFER,
        /** Part of a one-off set aside here. */
        SPLIT,
        /** A one-off or recurring item paid out of this bucket: the money leaves the bucket and the account together. */
        SPENT,
        /** A paycheck's share of a bill being saved up for. */
        SAVE_UP,
        /** The bill that was saved up for is paid, so the money leaves the bucket with it. */
        SAVE_UP_PAID,
        /** This bucket's share of the account's interest. */
        INTEREST
    }

    /**
     * @param amountCents signed: positive goes into the bucket
     * @param label       what to call it on screen: the item's name, the entry's description, or the user's note
     * @param itemId      the recurring item behind it, if there is one
     */
    public record Move(long bucketId, LocalDate date, long amountCents, Kind kind, String label, Long itemId) {}

    /**
     * How many months past the last period occurrences are needed for. A bill saved up for
     * decides today's share by when it is next due, which can be most of a year away.
     */
    static int lookAheadMonths(List<Item> items, LocalDate last) {
        int months = NO_LOOK_AHEAD;
        for (Item item : items) {
            for (Version version : item.versions()) {
                if (version.terms().saveUpBucketId() != null) {
                    months = Math.max(months, cycleMonths(version.terms().schedule(), last) + 1);
                }
            }
        }
        return Math.min(months, MAX_LOOK_AHEAD_MONTHS);
    }

    /** The longest gap, in months, between one occurrence and the next (for a one-off: until it happens). */
    private static int cycleMonths(ScheduleRule rule, LocalDate last) {
        return switch (rule.frequency()) {
            case YEARLY -> 12;
            case EVERY_N_MONTHS -> rule.interval();
            case ONE_TIME -> (int) Math.max(0, ChronoUnit.MONTHS.between(last, rule.startDate()) + 1);
            case MANUAL_DATES -> rule.manualDates().stream()
                    .filter(date -> date.isAfter(last))
                    .findFirst()
                    .map(date -> (int) ChronoUnit.MONTHS.between(last, date) + 1)
                    .orElse(0);
            default -> 1;
        };
    }

    /**
     * @param buckets     the buckets to follow (archived ones left out): a move into any other is dropped
     * @param occurrences every occurrence since the earliest bucket was opened, and far enough
     *                    ahead to include the next due date of every bill saved up for; one
     *                    dated before its bucket was opened no longer names that bucket
     * @param periods     the pay periods across that same stretch, earliest first
     */
    static List<Move> derive(
            List<Bucket> buckets,
            List<Change> changes,
            List<Entry> entriesWithSplits,
            List<Item> items,
            List<Occurrence> occurrences,
            List<PayPeriod> periods) {
        Map<Long, Bucket> open = new HashMap<>();
        buckets.forEach(bucket -> open.put(bucket.id(), bucket));
        List<Move> moves = new ArrayList<>();

        // What the user entered is taken as entered, whatever its date.
        for (Change change : changes) {
            if (open.containsKey(change.bucketId())) {
                moves.add(new Move(change.bucketId(), change.date(), change.amountCents(), Kind.MANUAL, change.note(), null));
            }
        }
        for (Entry entry : entriesWithSplits) {
            for (Split split : entry.bucketSplits()) {
                Bucket bucket = open.get(split.bucketId());
                if (bucket == null) {
                    continue;
                }
                // A bucket in the account the money leaves is where it came from; one in the
                // account it arrives in is where part of it is set aside.
                boolean leaves = entry.kind() == EntryKind.MONEY_OUT
                        || (entry.kind() == EntryKind.TRANSFER && bucket.accountId() == entry.accountId());
                moves.add(new Move(
                        bucket.id(), entry.date(), leaves ? -split.amountCents() : split.amountCents(),
                        leaves ? Kind.SPENT : Kind.SPLIT, entry.description(), null));
            }
        }

        Map<Long, List<Occurrence>> savedUpFor = new HashMap<>();
        for (Occurrence occurrence : occurrences) {
            if (occurrence.skipped()) {
                continue;
            }
            // The caller has already dropped the bucket from anything dated before it was opened.
            Bucket into = occurrence.bucketId() == null ? null : open.get(occurrence.bucketId());
            if (into != null) {
                Kind kind = occurrence.kind() == ItemKind.BUCKET_MOVE ? Kind.BUCKET_MOVE : Kind.TRANSFER;
                moves.add(new Move(into.id(), occurrence.payBy(), occurrence.amountCents(), kind, occurrence.name(), occurrence.itemId()));
            }
            // Paid out of a bucket: the money leaves the bucket on the day it leaves the account.
            Bucket paidFrom = occurrence.fromBucketId() == null ? null : open.get(occurrence.fromBucketId());
            if (paidFrom != null && !occurrence.payBy().isBefore(paidFrom.openedOn())) {
                moves.add(new Move(
                        paidFrom.id(), occurrence.payBy(), -occurrence.amountCents(), Kind.SPENT, occurrence.name(), occurrence.itemId()));
            }
            if (occurrence.saveUpBucketId() != null) {
                savedUpFor.computeIfAbsent(occurrence.itemId(), id -> new ArrayList<>()).add(occurrence);
            }
        }

        Map<Long, Item> itemsById = new HashMap<>();
        items.forEach(item -> itemsById.put(item.id(), item));
        savedUpFor.forEach((itemId, due) -> saveUps(itemsById.get(itemId), due, open, periods, moves));

        moves.sort(Comparator.comparing(Move::date));
        return moves;
    }

    /**
     * A bucket is never overdrawn. A one-off is refused when its bucket does not hold enough,
     * but a schedule runs on by itself: going through the moves in date order, a recurring
     * item takes from its bucket only what is in it, and the rest of what it costs comes out
     * of the account's money that is not in a bucket.
     *
     * @param moves earliest first
     */
    static List<Move> neverOverdrawn(List<Move> moves) {
        Map<Long, Long> held = new HashMap<>();
        List<Move> kept = new ArrayList<>();
        for (Move move : moves) {
            long amount = move.amountCents();
            if (move.kind() == Kind.SPENT && move.itemId() != null) {
                amount = -Math.min(-amount, Math.max(0, held.getOrDefault(move.bucketId(), 0L)));
            }
            if (amount == move.amountCents()) {
                kept.add(move);
            } else if (amount != 0) {
                kept.add(new Move(move.bucketId(), move.date(), amount, move.kind(), move.label(), move.itemId()));
            }
            held.merge(move.bucketId(), amount, Long::sum);
        }
        return kept;
    }

    /** One recurring move, as an occurrence and the move derived from it both know it. */
    record MoveKey(long itemId, LocalDate date) {}

    /**
     * @param moves   every move, with recurring bucket moves cut down to what their bucket had room for
     * @param changed what each cut-down move puts in instead; zero when the bucket was already full
     */
    record Capped(List<Move> moves, Map<MoveKey, Long> changed) {}

    /**
     * Stops recurring bucket moves at a bucket's goal, for the buckets that asked for it. Going
     * through the moves in date order, a bucket move only puts in what is still missing: the
     * one that reaches the goal is cut down to fit, and those after it put in nothing. Anything
     * else (money moved by hand, a transfer, a save-up) is left alone, so a bucket can still
     * end up over its goal if the user puts more in.
     *
     * @param moves earliest first
     * @param goals goal in cents by bucket id, for the buckets that stop at their goal
     */
    static Capped stopAtGoals(List<Move> moves, Map<Long, Long> goals) {
        if (goals.isEmpty()) {
            return new Capped(moves, Map.of());
        }
        Map<Long, Long> held = new HashMap<>();
        Map<MoveKey, Long> changed = new HashMap<>();
        List<Move> kept = new ArrayList<>();
        for (Move move : moves) {
            Long goal = goals.get(move.bucketId());
            long amount = move.amountCents();
            if (goal != null && move.kind() == Kind.BUCKET_MOVE && amount > 0) {
                long room = Math.max(0, goal - held.getOrDefault(move.bucketId(), 0L));
                if (amount > room) {
                    amount = room;
                    changed.put(new MoveKey(move.itemId(), move.date()), room);
                }
            }
            if (amount == move.amountCents()) {
                kept.add(move);
            } else if (amount > 0) {
                kept.add(new Move(move.bucketId(), move.date(), amount, move.kind(), move.label(), move.itemId()));
            }
            held.merge(move.bucketId(), amount, Long::sum);
        }
        return new Capped(kept, changed);
    }

    /**
     * Saving up for one item's bills. Each bill is shared out over the paydays between the
     * one before it and its own; the money leaves the bucket on the day the bill counts.
     */
    private static void saveUps(Item item, List<Occurrence> due, Map<Long, Bucket> open, List<PayPeriod> periods, List<Move> moves) {
        due.sort(Comparator.comparing(Occurrence::payBy));
        LocalDate afterPrevious = LocalDate.MIN;
        for (Occurrence bill : due) {
            Bucket bucket = open.get(bill.saveUpBucketId());
            if (bucket != null) {
                LocalDate from = latest(afterPrevious, paydayInHand(savingBegan(item, bill), periods), bucket.openedOn());
                List<LocalDate> paydays = periods.stream()
                        .map(PayPeriod::start)
                        .filter(payday -> !payday.isBefore(from) && !payday.isAfter(bill.payBy()))
                        .toList();
                List<Money> shares = SaveUp.shares(Money.ofCents(bill.amountCents()), paydays.size());
                for (int i = 0; i < paydays.size(); i++) {
                    moves.add(new Move(bucket.id(), paydays.get(i), shares.get(i).cents(), Kind.SAVE_UP, bill.name(), bill.itemId()));
                }
                // With no payday left to save on, nothing was set aside, so nothing comes back out.
                if (!paydays.isEmpty()) {
                    moves.add(new Move(bucket.id(), bill.payBy(), -bill.amountCents(), Kind.SAVE_UP_PAID, bill.name(), bill.itemId()));
                }
            }
            afterPrevious = bill.payBy().plusDays(1);
        }
    }

    /**
     * The day saving up for this bill could have begun: when the item was added, or when a
     * later edit first pointed it at this bucket. Edits that kept the bucket (a new price,
     * say) do not restart it.
     */
    private static LocalDate savingBegan(Item item, Occurrence bill) {
        List<Version> versions = item.versions();
        int index = versions.indexOf(item.versionOn(bill.scheduledDate()));
        while (index > 0 && Objects.equals(versions.get(index - 1).terms().saveUpBucketId(), bill.saveUpBucketId())) {
            index--;
        }
        return index == 0 ? item.createdOn() : versions.get(index).effectiveFrom();
    }

    /**
     * The payday of the pay period {@code day} falls in. Saving set up part-way through a
     * period starts with the paycheck in hand, the same as an "every paycheck" item does,
     * instead of quietly waiting for the next one.
     */
    private static LocalDate paydayInHand(LocalDate day, List<PayPeriod> periods) {
        return periods.stream()
                .filter(period -> !day.isBefore(period.start()) && !day.isAfter(period.end()))
                .map(PayPeriod::start)
                .findFirst()
                .orElse(day);
    }

    private static LocalDate latest(LocalDate first, LocalDate second, LocalDate third) {
        LocalDate latest = first.isAfter(second) ? first : second;
        return latest.isAfter(third) ? latest : third;
    }
}
