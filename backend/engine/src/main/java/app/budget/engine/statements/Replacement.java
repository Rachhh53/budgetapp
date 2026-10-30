package app.budget.engine.statements;

import app.budget.engine.schedule.PayPeriod;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a statement's spending comes to in each pay period, by category and by tag, and what the budgets and recurring
 * items Rachel points those totals at become (Rachel, 2026-10-25: "aggregate charges by category and tag and have it
 * replace certain budget or recurring items on a pay period"; "I want the option to select a budget or recurring item
 * from a pay period - that shouldnt just happen"; "replace or add should be options").
 *
 * <p>The day a charge was made decides its pay period. Nothing here chooses an item: the totals are worked out for
 * every category and tag, and only the targets given turn into changes. All amounts are whole cents.
 */
public final class Replacement {

    private Replacement() {}

    public enum GroupType { CATEGORY, TAG }

    /** A category or a tag, which charges are totalled by. */
    public record Group(GroupType type, long id) {}

    /** Whether a total takes an item's amount's place or is added to it. */
    public enum Mode { REPLACE, ADD }

    /**
     * One line of spending to be counted.
     *
     * @param cents        spent as positive; a refund negative, coming off what was spent
     * @param countsToward when the charge's category and one of its tags are both pointed at items, the one Rachel
     *                     chose it counts toward; null until then
     */
    public record Charge(long lineId, LocalDate date, long cents, Long categoryId, List<Long> tagIds, Group countsToward) {

        List<Group> groups() {
            List<Group> groups = new ArrayList<>();
            if (categoryId != null) {
                groups.add(new Group(GroupType.CATEGORY, categoryId));
            }
            tagIds.forEach(tag -> groups.add(new Group(GroupType.TAG, tag)));
            return groups;
        }
    }

    /** What a category's or tag's charges come to in one pay period. A charge with a category and two tags is in all three. */
    public record GroupTotal(LocalDate periodStart, Group group, long cents, List<Long> lineIds) {}

    /** The days of a period the statements cover; {@code partly} when some of its days are on none of them. */
    public record Coverage(LocalDate periodStart, LocalDate from, LocalDate to, boolean partly) {}

    /** The first and last day one statement covers. */
    public record Span(LocalDate from, LocalDate to) {}

    /** A total pointed at an item, in one pay period. */
    public record Target(LocalDate periodStart, Group group, long itemId, Mode mode) {}

    /**
     * One date of an item that lands in a pay period.
     *
     * @param countsOn     the day it counts on in the period, which decides which charges it takes when the item comes
     *                     more than once in the period
     * @param currentCents what it is now
     * @param baseCents    what it was before any statement changed it, which Add adds to so applying twice never adds
     *                     twice
     */
    public record Slot(long itemId, LocalDate scheduledDate, LocalDate periodStart, LocalDate countsOn, long currentCents, long baseCents) {}

    /** One date's amount as the targets would make it, and the charges that make it up. */
    public record Change(long itemId, LocalDate scheduledDate, LocalDate periodStart, long currentCents, long newCents, List<Long> lineIds) {}

    /**
     * @param undecided charges two targets claim (one by category, one by a tag) with no choice made of which: they
     *                  are left out of the changes until there is one
     * @param unmatched charges no target claims, in a pay period
     * @param outside   charges in no pay period at all
     */
    public record Plan(List<Change> changes, List<Long> undecided, List<Long> unmatched, List<Long> outside) {}

    /** The period a day falls in. */
    public static Optional<PayPeriod> periodOf(LocalDate date, List<PayPeriod> periods) {
        return periods.stream().filter(period -> period.contains(date)).findFirst();
    }

    /** Every category's and tag's total in each period, periods in date order and groups as first met. */
    public static List<GroupTotal> totals(List<Charge> charges, List<PayPeriod> periods) {
        Map<LocalDate, Map<Group, List<Charge>>> byPeriod = new java.util.TreeMap<>();
        for (Charge charge : charges) {
            Optional<PayPeriod> period = periodOf(charge.date(), periods);
            if (period.isEmpty()) {
                continue;
            }
            for (Group group : charge.groups()) {
                byPeriod.computeIfAbsent(period.get().start(), start -> new LinkedHashMap<>())
                        .computeIfAbsent(group, key -> new ArrayList<>())
                        .add(charge);
            }
        }
        List<GroupTotal> totals = new ArrayList<>();
        byPeriod.forEach((start, groups) -> groups.forEach((group, counted) -> totals.add(new GroupTotal(
                start, group, counted.stream().mapToLong(Charge::cents).sum(), counted.stream().map(Charge::lineId).toList()))));
        return totals;
    }

    /**
     * How much of each period the statements cover. Statements of the same account add together (Rachel, 2026-10-25), so
     * a card statement closing on the 9th and the next one together cover the whole period.
     */
    public static List<Coverage> coverage(List<PayPeriod> periods, List<Span> spans) {
        List<Coverage> coverage = new ArrayList<>();
        for (PayPeriod period : periods) {
            LocalDate from = null;
            LocalDate to = null;
            boolean partly = false;
            for (LocalDate day = period.start(); !day.isAfter(period.end()); day = day.plusDays(1)) {
                LocalDate current = day;
                if (spans.stream().anyMatch(span -> !current.isBefore(span.from()) && !current.isAfter(span.to()))) {
                    from = from == null ? day : from;
                    to = day;
                } else {
                    partly = true;
                }
            }
            if (from != null) {
                coverage.add(new Coverage(period.start(), from, to, partly));
            }
        }
        return coverage;
    }

    /**
     * What the targets make of each item's dates. Each charge counts toward one target: its category's or a tag's,
     * whichever is pointed at an item in its period, or the one chosen when both are. An item that comes more than once
     * in a period gives each date the charges from its day up to the next date's, and the first date those before it.
     *
     * <p>An item's dates in a period start from what they were before any statement (Add) or from nothing (Replace).
     * When two totals are pointed at the same item, it is Add only if both are; one Replace starts it from nothing.
     */
    public static Plan plan(List<Charge> charges, List<PayPeriod> periods, List<Target> targets, List<Slot> slots) {
        List<Long> undecided = new ArrayList<>();
        List<Long> unmatched = new ArrayList<>();
        List<Long> outside = new ArrayList<>();
        // Charges claimed by each item in each period.
        Map<ItemInPeriod, List<Charge>> claimed = new LinkedHashMap<>();
        Map<ItemInPeriod, Boolean> allAdd = new LinkedHashMap<>();
        for (Target target : targets) {
            ItemInPeriod key = new ItemInPeriod(target.itemId(), target.periodStart());
            if (slots.stream().anyMatch(slot -> slot.itemId() == key.itemId() && slot.periodStart().equals(key.periodStart()))) {
                claimed.putIfAbsent(key, new ArrayList<>());
                allAdd.merge(key, target.mode() == Mode.ADD, Boolean::logicalAnd);
            }
        }
        for (Charge charge : charges) {
            Optional<PayPeriod> period = periodOf(charge.date(), periods);
            if (period.isEmpty()) {
                outside.add(charge.lineId());
                continue;
            }
            List<Target> claiming = targets.stream()
                    .filter(target -> target.periodStart().equals(period.get().start()) && charge.groups().contains(target.group()))
                    .filter(target -> claimed.containsKey(new ItemInPeriod(target.itemId(), target.periodStart())))
                    .toList();
            Set<Long> items = new HashSet<>(claiming.stream().map(Target::itemId).toList());
            Target counted;
            if (claiming.isEmpty()) {
                unmatched.add(charge.lineId());
                continue;
            } else if (items.size() == 1) {
                // Pointed at one item, by however many of its groups: there is nothing to choose.
                counted = claiming.get(0);
            } else {
                counted = claiming.stream().filter(target -> target.group().equals(charge.countsToward())).findFirst().orElse(null);
                if (counted == null) {
                    undecided.add(charge.lineId());
                    continue;
                }
            }
            claimed.get(new ItemInPeriod(counted.itemId(), counted.periodStart())).add(charge);
        }

        List<Change> changes = new ArrayList<>();
        claimed.forEach((key, counted) -> {
            List<Slot> dates = slots.stream()
                    .filter(slot -> slot.itemId() == key.itemId() && slot.periodStart().equals(key.periodStart()))
                    .sorted(Comparator.comparing(Slot::countsOn).thenComparing(Slot::scheduledDate))
                    .toList();
            boolean add = allAdd.getOrDefault(key, false);
            Map<Slot, List<Charge>> bySlot = new LinkedHashMap<>();
            dates.forEach(slot -> bySlot.put(slot, new ArrayList<>()));
            for (Charge charge : counted) {
                Slot taking = dates.get(0);
                for (Slot slot : dates) {
                    if (!slot.countsOn().isAfter(charge.date())) {
                        taking = slot;
                    }
                }
                bySlot.get(taking).add(charge);
            }
            bySlot.forEach((slot, its) -> changes.add(new Change(
                    slot.itemId(),
                    slot.scheduledDate(),
                    slot.periodStart(),
                    slot.currentCents(),
                    // More refunded than spent leaves nothing to pay, never money coming in from a bill.
                    Math.max(0, (add ? slot.baseCents() : 0) + its.stream().mapToLong(Charge::cents).sum()),
                    its.stream().map(Charge::lineId).toList())));
        });
        return new Plan(changes, undecided, unmatched, outside);
    }

    private record ItemInPeriod(long itemId, LocalDate periodStart) {}
}
