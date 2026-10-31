package app.budget.engine.statements;

import app.budget.engine.schedule.PayPeriod;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a budget's dates become once statement charges are put on it (Rachel, 2026-10-30: "I basically want the budget to
 * become the actual in aggregate by adding together items from the statement"; "I might need the ability to have a
 * placeholder amount too - like if the second half of the budget is on a statement I don't have yet").
 *
 * <p>Each charge put on a budget counts toward the date of it that its day falls under: from that date's day up to the
 * next date's, the first date taking any before it in the pay period. A date is what was spent plus what is still to come:
 * nothing once the account's statements cover every day it spans, otherwise what was typed for it, or else what was
 * budgeted less what was spent (never below nothing). Statements of the same account add together, so a later statement
 * fills in the rest of a date. All amounts are whole cents.
 *
 * <p>A charge from another account can go on a budget too (Rachel, 2026-10-30: "decrese the budget on the account it was
 * budgeted on and add as one-offs or even an aggregate with the same category and tags ... keep the total budget aligned
 * until all statements are in on the budgeted account"). It is spent elsewhere: it takes off the budget's still to come,
 * and becomes a one-off on its own account, so the budget's account and the other both stay right. Only the budget's own
 * account's statements decide whether a date is covered, and a later statement of either still adds to it.
 */
public final class BudgetActuals {

    private BudgetActuals() {}

    /**
     * One charge put on a budget; money back is negative and comes off what was spent.
     *
     * @param accountId the account whose statement it is on
     */
    public record Charge(long lineId, LocalDate date, long cents, long itemId, long accountId) {

        /** A charge on the budget's own account, where every account is the same. */
        public Charge(long lineId, LocalDate date, long cents, long itemId) {
            this(lineId, date, cents, itemId, 0);
        }
    }

    /**
     * One date of a budget paid from the account.
     *
     * @param countsOn       the day it counts on in its pay period, which decides the charges it takes
     * @param currentCents   what it is now
     * @param budgetedCents  what it was before any statement changed it
     * @param typedStillCents still to come as typed for it; null to work it out
     * @param applied        a statement set its amount before, so with no charges left on it, it goes back to what was budgeted
     * @param accountId      the account it is paid from
     */
    public record BudgetDate(
            long itemId, LocalDate scheduledDate, LocalDate periodStart, LocalDate countsOn, long currentCents, long budgetedCents,
            Long typedStillCents, boolean applied, long accountId) {

        /** A date of a budget paid from the account, where every account is the same. */
        public BudgetDate(
                long itemId, LocalDate scheduledDate, LocalDate periodStart, LocalDate countsOn, long currentCents, long budgetedCents,
                Long typedStillCents, boolean applied) {
            this(itemId, scheduledDate, periodStart, countsOn, currentCents, budgetedCents, typedStillCents, applied, 0);
        }
    }

    /** How much of a date's days the statements cover. */
    public enum Covered { FULLY, PARTLY, NONE }

    /**
     * What one budget date becomes.
     *
     * @param from          the first day whose charges it takes
     * @param to            the last
     * @param coveredThrough the last day of it the statements cover, when they cover some but not all; null otherwise
     * @param actualCents   what was spent on it from its own account
     * @param stillTyped    the still to come is what was typed, not worked out
     * @param newCents      what its own account pays: what was spent there plus still to come
     * @param lineIds       every charge on it, from any account
     * @param accountId     the account it is paid from
     * @param elsewhere     what was spent on it from each other account, each a one-off there
     */
    public record Result(
            long itemId,
            LocalDate scheduledDate,
            LocalDate periodStart,
            LocalDate from,
            LocalDate to,
            Covered covered,
            LocalDate coveredThrough,
            long currentCents,
            long budgetedCents,
            long actualCents,
            long stillToComeCents,
            boolean stillTyped,
            long newCents,
            List<Long> lineIds,
            long accountId,
            List<Elsewhere> elsewhere) {

        /** What was spent on it from every other account together. */
        public long elsewhereCents() {
            return elsewhere.stream().mapToLong(Elsewhere::cents).sum();
        }
    }

    /**
     * What was spent on a budget date from an account other than the one it is paid from.
     *
     * @param cents   added up, money back taken off
     * @param lastDay the latest of those charges' days, which the one-off for them is dated
     */
    public record Elsewhere(long accountId, long cents, LocalDate lastDay, List<Long> lineIds) {}

    /** The days each statement of the account covers. */
    public record Span(LocalDate from, LocalDate to) {

        boolean covers(LocalDate day) {
            return !day.isBefore(from) && !day.isAfter(to);
        }
    }

    /** The days of a period the statements cover; {@code partly} when some of its days are on none of them. */
    public record Coverage(LocalDate periodStart, LocalDate from, LocalDate to, boolean partly) {}

    /** The period a day falls in. */
    public static Optional<PayPeriod> periodOf(LocalDate date, List<PayPeriod> periods) {
        return periods.stream().filter(period -> period.contains(date)).findFirst();
    }

    /** How much of each period the statements cover; statements of the same account add together. */
    public static List<Coverage> coverage(List<PayPeriod> periods, List<Span> spans) {
        List<Coverage> coverage = new ArrayList<>();
        for (PayPeriod period : periods) {
            LocalDate from = null;
            LocalDate to = null;
            boolean partly = false;
            for (LocalDate day = period.start(); !day.isAfter(period.end()); day = day.plusDays(1)) {
                if (covered(day, spans)) {
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
     * The date of an item a charge on that day counts toward: of the item's dates in the charge's pay period, the latest
     * on or before the day, or the first when the day comes before them all. Empty when the item has no date in that period.
     */
    public static Optional<BudgetDate> dateFor(LocalDate day, long itemId, List<PayPeriod> periods, List<BudgetDate> dates) {
        Optional<PayPeriod> period = periodOf(day, periods);
        if (period.isEmpty()) {
            return Optional.empty();
        }
        List<BudgetDate> its = inPeriod(itemId, period.get().start(), dates);
        if (its.isEmpty()) {
            return Optional.empty();
        }
        BudgetDate taking = its.getFirst();
        for (BudgetDate date : its) {
            if (!date.countsOn().isAfter(day)) {
                taking = date;
            }
        }
        return Optional.of(taking);
    }

    /**
     * What each date of a budget with charges on it in a period becomes, and what any date a statement set before, now with
     * none on it, goes back to. A budget with no charges in a period is left alone: its other dates are not spent to nothing
     * just because the statement covers them.
     */
    public static List<Result> work(List<Charge> charges, List<PayPeriod> periods, List<BudgetDate> dates, List<Span> spans) {
        return work(charges, periods, dates, Map.of(0L, spans));
    }

    /**
     * The same, with charges from any account: {@code spansByAccount} are the days each account's statements cover, and a
     * date is covered by its own account's alone.
     */
    public static List<Result> work(List<Charge> charges, List<PayPeriod> periods, List<BudgetDate> dates, Map<Long, List<Span>> spansByAccount) {
        // Charges by the date they count toward.
        Map<BudgetDate, List<Charge>> byDate = new LinkedHashMap<>();
        for (Charge charge : charges) {
            dateFor(charge.date(), charge.itemId(), periods, dates)
                    .ifPresent(date -> byDate.computeIfAbsent(date, key -> new ArrayList<>()).add(charge));
        }
        Set<ItemInPeriod> spent = new java.util.HashSet<>();
        byDate.keySet().forEach(date -> spent.add(new ItemInPeriod(date.itemId(), date.periodStart())));

        List<Result> results = new ArrayList<>();
        for (PayPeriod period : periods) {
            List<BudgetDate> its = dates.stream()
                    .filter(date -> date.periodStart().equals(period.start()))
                    .sorted(Comparator.comparing(BudgetDate::itemId).thenComparing(BudgetDate::countsOn).thenComparing(BudgetDate::scheduledDate))
                    .toList();
            for (int index = 0; index < its.size(); index++) {
                BudgetDate date = its.get(index);
                boolean first = index == 0 || its.get(index - 1).itemId() != date.itemId();
                boolean last = index == its.size() - 1 || its.get(index + 1).itemId() != date.itemId();
                LocalDate from = first ? period.start() : date.countsOn();
                LocalDate to = last ? period.end() : its.get(index + 1).countsOn().minusDays(1);
                List<Charge> taken = byDate.getOrDefault(date, List.of());
                List<Span> spans = spansByAccount.getOrDefault(date.accountId(), List.of());
                if (!spent.contains(new ItemInPeriod(date.itemId(), period.start()))) {
                    if (date.applied()) {
                        // Every charge was taken off it: back to what was budgeted.
                        results.add(new Result(date.itemId(), date.scheduledDate(), period.start(), from, to, covered(from, to, spans),
                                coveredThrough(from, to, spans), date.currentCents(), date.budgetedCents(), 0, date.budgetedCents(), false,
                                date.budgetedCents(), List.of(), date.accountId(), List.of()));
                    }
                    continue;
                }
                long actual = taken.stream().filter(charge -> charge.accountId() == date.accountId()).mapToLong(Charge::cents).sum();
                List<Elsewhere> elsewhere = elsewhere(taken, date.accountId());
                long spentElsewhere = elsewhere.stream().mapToLong(Elsewhere::cents).sum();
                Covered covered = covered(from, to, spans);
                boolean typed = covered != Covered.FULLY && date.typedStillCents() != null;
                // Spent on another card counts against the budget too, so the total stays what was budgeted until the
                // budget's own statements are all in.
                long still = covered == Covered.FULLY ? 0
                        : typed ? date.typedStillCents()
                        : Math.max(0, date.budgetedCents() - Math.max(0, actual + spentElsewhere));
                results.add(new Result(
                        date.itemId(),
                        date.scheduledDate(),
                        period.start(),
                        from,
                        to,
                        covered,
                        coveredThrough(from, to, spans),
                        date.currentCents(),
                        date.budgetedCents(),
                        actual,
                        still,
                        typed,
                        // More given back than spent leaves nothing to pay, never money coming in from a budget.
                        Math.max(0, actual + still),
                        taken.stream().map(Charge::lineId).toList(),
                        date.accountId(),
                        elsewhere));
            }
        }
        return results;
    }

    /** A date's charges from accounts other than its own, by account, in the order the accounts first come. */
    private static List<Elsewhere> elsewhere(List<Charge> taken, long ownAccountId) {
        Map<Long, List<Charge>> byAccount = new LinkedHashMap<>();
        taken.stream()
                .filter(charge -> charge.accountId() != ownAccountId)
                .forEach(charge -> byAccount.computeIfAbsent(charge.accountId(), key -> new ArrayList<>()).add(charge));
        return byAccount.entrySet().stream()
                .map(entry -> new Elsewhere(
                        entry.getKey(),
                        entry.getValue().stream().mapToLong(Charge::cents).sum(),
                        entry.getValue().stream().map(Charge::date).max(LocalDate::compareTo).orElseThrow(),
                        entry.getValue().stream().map(Charge::lineId).toList()))
                .toList();
    }

    private static List<BudgetDate> inPeriod(long itemId, LocalDate periodStart, List<BudgetDate> dates) {
        return dates.stream()
                .filter(date -> date.itemId() == itemId && date.periodStart().equals(periodStart))
                .sorted(Comparator.comparing(BudgetDate::countsOn).thenComparing(BudgetDate::scheduledDate))
                .toList();
    }

    private static boolean covered(LocalDate day, List<Span> spans) {
        return spans.stream().anyMatch(span -> span.covers(day));
    }

    private static Covered covered(LocalDate from, LocalDate to, List<Span> spans) {
        boolean any = false;
        boolean all = true;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            boolean on = covered(day, spans);
            any = any || on;
            all = all && on;
        }
        return all ? Covered.FULLY : any ? Covered.PARTLY : Covered.NONE;
    }

    /** The last covered day of a date the statements cover only partly, which says where "still to come" starts. */
    private static LocalDate coveredThrough(LocalDate from, LocalDate to, List<Span> spans) {
        if (covered(from, to, spans) != Covered.PARTLY) {
            return null;
        }
        LocalDate last = null;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (covered(day, spans)) {
                last = day;
            }
        }
        return last;
    }

    private record ItemInPeriod(long itemId, LocalDate periodStart) {}
}
