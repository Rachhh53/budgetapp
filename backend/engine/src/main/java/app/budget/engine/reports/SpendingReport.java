package app.budget.engine.reports;

import app.budget.engine.Money;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds up spending for a report (spec §4.11): the lines the caller has already chosen, put
 * into groups, with totals and how much of it has been or will be paid back.
 *
 * <p>Choosing which lines belong (dates, filters) is the caller's job; this only counts.
 */
public final class SpendingReport {

    private SpendingReport() {}

    public enum GroupBy { MONTH, YEAR, CATEGORY, TAG, PAYEE, ACCOUNT }

    /**
     * One thing money was spent on.
     *
     * @param categoryId    {@code null} when it has no category
     * @param tagIds        its tags; may be empty
     * @param payee         who was paid; {@code null} when not said
     * @param reimbursement where it stands on being paid back ({@link Reimbursement.Share#NONE} for most)
     */
    public record Line(
            LocalDate date,
            Money amount,
            Long categoryId,
            List<Long> tagIds,
            String payee,
            long accountId,
            Reimbursement.Share reimbursement) {}

    /**
     * One row of the report.
     *
     * @param key what the group is: {@code 2026-03} for a month, {@code 2026} for a year, the id of a
     *            category, tag or account, or the payee's name; {@code null} for lines with none
     */
    public record Group(String key, int count, Money total) {}

    /**
     * @param groups months and years in date order, every one in the dates asked for, empty ones
     *               included; anything else largest first
     * @param count  how many lines there are; with tags a line can be in several groups, so this is
     *               not always the sum of the groups
     */
    public record Result(List<Group> groups, int count, Money total, Reimbursement.Share reimbursement) {}

    /**
     * @param from first day the report covers; with {@code to}, names the months or years to list
     * @param to   last day the report covers
     */
    public static Result summarize(List<Line> lines, GroupBy groupBy, LocalDate from, LocalDate to) {
        Map<String, Tally> tallies = new LinkedHashMap<>();
        if (groupBy == GroupBy.MONTH) {
            for (YearMonth month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
                tallies.put(month.toString(), new Tally());
            }
        } else if (groupBy == GroupBy.YEAR) {
            for (int year = from.getYear(); year <= to.getYear(); year++) {
                tallies.put(String.valueOf(year), new Tally());
            }
        }

        Money total = Money.ZERO;
        Reimbursement.Share reimbursement = Reimbursement.Share.NONE;
        for (Line line : lines) {
            total = total.plus(line.amount());
            reimbursement = reimbursement.plus(line.reimbursement());
            for (String key : keys(line, groupBy)) {
                tallies.computeIfAbsent(key, unused -> new Tally()).add(line.amount());
            }
        }

        List<Group> groups = new ArrayList<>();
        tallies.forEach((key, tally) -> groups.add(new Group(key, tally.count, tally.total)));
        if (groupBy != GroupBy.MONTH && groupBy != GroupBy.YEAR) {
            // Largest first; the same amount twice goes by name so the order never jumps about.
            groups.sort(Comparator.comparing(Group::total).reversed()
                    .thenComparing(Group::key, Comparator.nullsLast(Comparator.naturalOrder())));
        }
        return new Result(List.copyOf(groups), lines.size(), total, reimbursement);
    }

    /** The groups one line belongs to: one, except by tag, where it is in each of its tags. */
    private static List<String> keys(Line line, GroupBy groupBy) {
        return switch (groupBy) {
            case MONTH -> List.of(YearMonth.from(line.date()).toString());
            case YEAR -> List.of(String.valueOf(line.date().getYear()));
            case CATEGORY -> single(line.categoryId() == null ? null : line.categoryId().toString());
            case ACCOUNT -> List.of(String.valueOf(line.accountId()));
            case PAYEE -> single(line.payee());
            case TAG -> line.tagIds().isEmpty()
                    ? single(null)
                    : line.tagIds().stream().distinct().map(String::valueOf).toList();
        };
    }

    /** {@code List.of} refuses a null; a group for "none" needs one. */
    private static List<String> single(String key) {
        List<String> keys = new ArrayList<>(1);
        keys.add(key);
        return keys;
    }

    private static final class Tally {
        private int count;
        private Money total = Money.ZERO;

        void add(Money amount) {
            count++;
            total = total.plus(amount);
        }
    }
}
