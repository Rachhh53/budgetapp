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
 * What Budget projected against what things turned out to be (spec §1, §5.10): every amount
 * that was typed over keeps the figure shown before it, and this puts the two side by side.
 *
 * <p>Money going out and money coming in are never added together: a bill that cost more and
 * a paycheck that paid more are opposite news. The caller sums each side on its own.
 */
public final class ProjectedVsActual {

    private ProjectedVsActual() {}

    public enum GroupBy { MONTH, YEAR, CATEGORY, ITEM }

    /**
     * One amount, as projected and as it was.
     *
     * @param projected  what Budget showed before the amount was typed over; the same as {@code actual} when it never was
     * @param categoryId {@code null} when it has no category
     * @param itemKey    what it is one time of, for grouping: the caller's own key for a recurring item or an income source
     */
    public record Line(LocalDate date, Money projected, Money actual, Long categoryId, String itemKey) {

        /** What it was, less what was projected: above zero when it came to more. */
        public Money difference() {
            return actual.minus(projected);
        }

        public boolean changed() {
            return !projected.equals(actual);
        }
    }

    /**
     * One row of the report.
     *
     * @param key     {@code 2026-03} for a month, {@code 2026} for a year, a category's id, or the
     *                line's {@code itemKey}; {@code null} for lines with no category
     * @param changed how many of its lines came to something other than projected
     */
    public record Group(String key, int count, int changed, Money projected, Money actual) {

        public Money difference() {
            return actual.minus(projected);
        }
    }

    /**
     * @param groups months and years in date order, every one in the dates asked for; anything
     *               else by the largest difference first, either way
     */
    public record Result(List<Group> groups, int count, int changed, Money projected, Money actual) {

        public Money difference() {
            return actual.minus(projected);
        }
    }

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

        Tally all = new Tally();
        for (Line line : lines) {
            all.add(line);
            tallies.computeIfAbsent(key(line, groupBy), unused -> new Tally()).add(line);
        }

        List<Group> groups = new ArrayList<>();
        tallies.forEach((key, tally) -> groups.add(new Group(key, tally.count, tally.changed, tally.projected, tally.actual)));
        if (groupBy != GroupBy.MONTH && groupBy != GroupBy.YEAR) {
            // What moved furthest from its projection first; ties go by key so the order never jumps about.
            groups.sort(Comparator.comparingLong((Group group) -> Math.abs(group.difference().cents())).reversed()
                    .thenComparing(Group::key, Comparator.nullsLast(Comparator.naturalOrder())));
        }
        return new Result(List.copyOf(groups), all.count, all.changed, all.projected, all.actual);
    }

    private static String key(Line line, GroupBy groupBy) {
        return switch (groupBy) {
            case MONTH -> YearMonth.from(line.date()).toString();
            case YEAR -> String.valueOf(line.date().getYear());
            case CATEGORY -> line.categoryId() == null ? null : line.categoryId().toString();
            case ITEM -> line.itemKey();
        };
    }

    private static final class Tally {
        private int count;
        private int changed;
        private Money projected = Money.ZERO;
        private Money actual = Money.ZERO;

        void add(Line line) {
            count++;
            if (line.changed()) {
                changed++;
            }
            projected = projected.plus(line.projected());
            actual = actual.plus(line.actual());
        }
    }
}
