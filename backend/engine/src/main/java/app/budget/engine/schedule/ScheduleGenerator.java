package app.budget.engine.schedule;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/** Turns a {@link ScheduleRule} into the actual dates it lands on, weekends and holidays applied. */
public final class ScheduleGenerator {

    /**
     * How far outside the asked-for range to look for scheduled dates that a weekend or
     * holiday could move into it. Two weeks is far longer than any real run of closed days.
     */
    private static final int MOVE_MARGIN_DAYS = 14;

    private final BusinessCalendar calendar;

    public ScheduleGenerator(BusinessCalendar calendar) {
        this.calendar = calendar;
    }

    /**
     * Every occurrence whose actual date falls within {@code from}–{@code to} (both inclusive),
     * in date order. If two scheduled dates are moved onto the same day, the earlier one is kept.
     */
    public List<Occurrence> occurrences(ScheduleRule rule, LocalDate from, LocalDate to) {
        TreeMap<LocalDate, Occurrence> byActualDate = new TreeMap<>();
        for (LocalDate scheduled : scheduledDates(rule, from.minusDays(MOVE_MARGIN_DAYS), to.plusDays(MOVE_MARGIN_DAYS))) {
            LocalDate actual = calendar.adjust(scheduled, rule.weekendRule());
            if (!actual.isBefore(from) && !actual.isAfter(to)) {
                byActualDate.putIfAbsent(actual, new Occurrence(scheduled, actual));
            }
        }
        return List.copyOf(byActualDate.values());
    }

    /** The dates the rule itself names within {@code lo}–{@code hi}, before any weekend move. */
    private static TreeSet<LocalDate> scheduledDates(ScheduleRule rule, LocalDate lo, LocalDate hi) {
        TreeSet<LocalDate> dates = new TreeSet<>();
        if (rule.frequency() == Frequency.MANUAL_DATES) {
            dates.addAll(rule.manualDates());
            return new TreeSet<>(dates.subSet(lo, true, hi, true));
        }

        // Everything else lives between the rule's own start and end.
        LocalDate first = lo.isBefore(rule.startDate()) ? rule.startDate() : lo;
        LocalDate last = rule.endDate() != null && rule.endDate().isBefore(hi) ? rule.endDate() : hi;
        if (first.isAfter(last)) {
            return dates;
        }

        switch (rule.frequency()) {
            case WEEKLY -> addEveryDays(dates, rule.startDate(), 7, first, last);
            case BIWEEKLY -> addEveryDays(dates, rule.startDate(), 14, first, last);
            case SEMI_MONTHLY -> {
                for (YearMonth month : months(first, last)) {
                    dates.add(dayOf(month, rule.day1()));
                    dates.add(dayOf(month, rule.day2()));
                }
            }
            case MONTHLY -> {
                for (YearMonth month : months(first, last)) {
                    dates.add(dayOf(month, rule.day1()));
                }
            }
            case EVERY_N_MONTHS -> {
                YearMonth anchor = YearMonth.from(rule.startDate());
                for (YearMonth month : months(first, last)) {
                    if (ChronoUnit.MONTHS.between(anchor, month) % rule.interval() == 0) {
                        dates.add(dayOf(month, rule.day1()));
                    }
                }
            }
            case YEARLY -> {
                for (int year = first.getYear(); year <= last.getYear(); year++) {
                    dates.add(dayOf(YearMonth.of(year, rule.month()), rule.day1()));
                }
            }
            case ONE_TIME -> dates.add(rule.startDate());
            case EVERY_PAY_PERIOD -> throw new IllegalArgumentException(
                    "every_pay_period follows the paydays; resolve it against the pay periods instead");
            case MANUAL_DATES -> throw new IllegalStateException("handled above");
        }
        // Whole months were added above; trim the days that fall outside the window.
        return new TreeSet<>(dates.subSet(first, true, last, true));
    }

    private static void addEveryDays(TreeSet<LocalDate> dates, LocalDate anchor, int step, LocalDate first, LocalDate last) {
        // Jump straight to the first repeat on or after `first` instead of walking from the anchor.
        long stepsToFirst = Math.ceilDiv(ChronoUnit.DAYS.between(anchor, first), step);
        for (LocalDate date = anchor.plusDays(stepsToFirst * step); !date.isAfter(last); date = date.plusDays(step)) {
            dates.add(date);
        }
    }

    private static List<YearMonth> months(LocalDate first, LocalDate last) {
        List<YearMonth> months = new ArrayList<>();
        YearMonth end = YearMonth.from(last);
        for (YearMonth month = YearMonth.from(first); !month.isAfter(end); month = month.plusMonths(1)) {
            months.add(month);
        }
        return months;
    }

    /** The given day of the month, or the month's last day when it is shorter than that. */
    private static LocalDate dayOf(YearMonth month, int day) {
        return month.atDay(Math.min(day, month.lengthOfMonth()));
    }
}
