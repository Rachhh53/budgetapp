package app.budget.engine.reports;

import app.budget.engine.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What each recurring item costs a year, and how that has changed (spec §5.8): totals for
 * calendar years or for rolling 12 months, the change from one to the next, and one item
 * month by month.
 *
 * <p>Which lines belong (bills and budgets, at what they actually were) is the caller's job;
 * this only counts.
 */
public final class AnnualCosts {

    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);
    private static final BigDecimal BP_PER_WHOLE = BigDecimal.valueOf(10_000);

    private AnnualCosts() {}

    public enum Basis {
        /** January to December; the year in hand holds what is still to come as budgeted. */
        CALENDAR,
        /** Twelve whole months ending with last month, so every figure is of months that are over. */
        ROLLING
    }

    /** Twelve months that are added up together: a calendar year, or a rolling one. */
    public record Window(LocalDate from, LocalDate to) {

        boolean holds(LocalDate date) {
            return !date.isBefore(from) && !date.isAfter(to);
        }
    }

    /** One time an item was, or will be, paid. */
    public record Line(long itemId, LocalDate date, Money amount) {}

    /**
     * One item across the windows.
     *
     * @param totals   one per window, oldest first
     * @param changeBp the newest window against the one before, in basis points (9.1% is 910);
     *                 {@code null} when there is nothing before to compare with
     * @param perMonth the newest window's total shared over its twelve months
     */
    public record Row(long itemId, List<Money> totals, Integer changeBp, Money perMonth) {

        /** What the newest window came to. */
        public Money latest() {
            return totals.getLast();
        }

        /** How much more the newest window came to than the one before; below zero when it came to less. */
        public Money increase() {
            return totals.size() < 2 ? Money.ZERO : latest().minus(totals.get(totals.size() - 2));
        }
    }

    /**
     * @param rows            largest in the newest window first; an item with nothing in any window is left out
     * @param totals          every item together, one per window
     * @param biggestIncrease the item whose cost rose by the largest share; {@code null} when nothing rose
     */
    public record Result(List<Window> windows, List<Row> rows, List<Money> totals, Integer changeBp, Money perMonth, Row biggestIncrease) {}

    /**
     * The {@code count} windows that end with the one {@code today} is in, oldest first.
     *
     * @param count at least 1
     */
    public static List<Window> windows(Basis basis, LocalDate today, int count) {
        List<Window> windows = new ArrayList<>();
        for (int back = count - 1; back >= 0; back--) {
            if (basis == Basis.CALENDAR) {
                int year = today.getYear() - back;
                windows.add(new Window(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)));
            } else {
                YearMonth last = YearMonth.from(today).minusMonths(1 + 12L * back);
                windows.add(new Window(last.minusMonths(11).atDay(1), last.atEndOfMonth()));
            }
        }
        return windows;
    }

    /** @param windows oldest first; lines outside every window are not counted */
    public static Result summarize(List<Line> lines, List<Window> windows) {
        Map<Long, Money[]> byItem = new LinkedHashMap<>();
        Money[] all = zeros(windows.size());
        for (Line line : lines) {
            for (int i = 0; i < windows.size(); i++) {
                if (windows.get(i).holds(line.date())) {
                    Money[] sums = byItem.computeIfAbsent(line.itemId(), unused -> zeros(windows.size()));
                    sums[i] = sums[i].plus(line.amount());
                    all[i] = all[i].plus(line.amount());
                }
            }
        }

        List<Row> rows = new ArrayList<>();
        byItem.forEach((itemId, sums) -> rows.add(row(itemId, List.of(sums))));
        // Largest first; the same amount twice goes by id so the order never jumps about.
        rows.sort(Comparator.comparing(Row::latest).reversed().thenComparing(Row::itemId));

        Row biggest = null;
        for (Row row : rows) {
            if (row.changeBp() != null && row.changeBp() > 0 && (biggest == null || row.changeBp() > biggest.changeBp())) {
                biggest = row;
            }
        }
        Row everything = row(0, List.of(all));
        return new Result(List.copyOf(windows), List.copyOf(rows), everything.totals(), everything.changeBp(), everything.perMonth(), biggest);
    }

    private static Row row(long itemId, List<Money> totals) {
        Money latest = totals.getLast();
        Integer changeBp = totals.size() < 2 ? null : changeBp(totals.get(totals.size() - 2), latest);
        return new Row(itemId, totals, changeBp, perMonth(latest));
    }

    /** {@code now} against {@code before} in basis points; {@code null} when there was nothing before. */
    static Integer changeBp(Money before, Money now) {
        if (before.isZero() || before.isNegative()) {
            return null;
        }
        return now.minus(before).toDecimal()
                .multiply(BP_PER_WHOLE)
                .divide(before.toDecimal(), 0, Money.ROUNDING)
                .intValueExact();
    }

    /** A year's amount shared over twelve months, to the cent. */
    public static Money perMonth(Money perYear) {
        return Money.fromDecimal(perYear.toDecimal().divide(TWELVE, 2, Money.ROUNDING));
    }

    private static Money[] zeros(int count) {
        Money[] sums = new Money[count];
        Arrays.fill(sums, Money.ZERO);
        return sums;
    }

    /**
     * One month of an item's year.
     *
     * @param amount what the month came to this year
     * @param before what the same month came to the year before
     * @param toCome true for a month after the one {@code today} is in: its amount is what is budgeted
     */
    public record Month(YearMonth month, Money amount, Money before, boolean toCome) {}

    /**
     * One item's year, month by month.
     *
     * @param months      January to December of the year {@code today} is in
     * @param highest     the month that came to most; {@code null} when the year holds nothing
     * @param lowest      the month that came to least among those with anything in them
     * @param total       the whole year: what has been, and what is budgeted for the rest
     * @param average     {@code total} shared over twelve months
     * @param budget      what the item comes to a month at today's amount
     * @param budgetTooLow true when the year comes to more than today's amount would over a year
     */
    public record ByMonth(
            List<Month> months, Month highest, Month lowest, Money total, Money average, Money budget, boolean budgetTooLow) {}

    /**
     * @param lines         one item's lines; only this year's and last year's are counted
     * @param budgetPerYear what the item comes to over a year at the amount in force today
     */
    public static ByMonth byMonth(List<Line> lines, LocalDate today, Money budgetPerYear) {
        Map<YearMonth, Money> sums = new LinkedHashMap<>();
        for (Line line : lines) {
            sums.merge(YearMonth.from(line.date()), line.amount(), Money::plus);
        }
        YearMonth now = YearMonth.from(today);
        List<Month> months = new ArrayList<>();
        Money total = Money.ZERO;
        Month highest = null;
        Month lowest = null;
        for (int number = 1; number <= 12; number++) {
            YearMonth at = YearMonth.of(today.getYear(), number);
            Month month = new Month(
                    at, sums.getOrDefault(at, Money.ZERO), sums.getOrDefault(at.minusYears(1), Money.ZERO), at.isAfter(now));
            months.add(month);
            total = total.plus(month.amount());
            if (!month.amount().isZero()) {
                // The first of two equal months stands, so the answer does not move with the order of the lines.
                highest = highest == null || month.amount().compareTo(highest.amount()) > 0 ? month : highest;
                lowest = lowest == null || month.amount().compareTo(lowest.amount()) < 0 ? month : lowest;
            }
        }
        return new ByMonth(
                List.copyOf(months), highest, lowest, total, perMonth(total), perMonth(budgetPerYear),
                total.compareTo(budgetPerYear) > 0);
    }
}
