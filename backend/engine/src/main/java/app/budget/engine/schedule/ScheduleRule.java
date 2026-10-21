package app.budget.engine.schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * When something repeats: a paycheck, a bill, a transfer.
 *
 * <p>Which fields matter depends on the {@link Frequency}:
 * <ul>
 *   <li>{@code WEEKLY}, {@code BIWEEKLY} — repeat from {@code startDate}'s week, on its day of the week, or on each
 *       of {@code weekdays} when some are chosen (Rachel, 2026-10-19: a class "twice per week"). {@code WEEKLY}
 *       is every {@code interval} weeks: 1 is every week, 10 every ten (Rachel, 2026-10-20).
 *   <li>{@code SEMI_MONTHLY} — {@code day1} and {@code day2} of every month.
 *   <li>{@code MONTHLY} — {@code day1} of every month.
 *   <li>{@code EVERY_N_MONTHS} — {@code day1}, every {@code interval} months counted from {@code startDate}'s month.
 *   <li>{@code YEARLY} — {@code day1} of {@code month}.
 *   <li>{@code ONE_TIME} — {@code startDate} only.
 *   <li>{@code MANUAL_DATES} — exactly {@code manualDates}; start and end dates are ignored.
 * </ul>
 *
 * <p>A day of the month that a month does not have falls to that month's last day, so
 * {@link #LAST_DAY} means "the last day of every month".
 *
 * <p>A rule can run only in some {@code months} (Rachel, 2026-10-19: snowplowing, "several times per
 * week in the winter and not at all in the summer"): a date it names in any other month does not happen.
 * For an {@code EVERY_PAY_PERIOD} rule the month is the paycheck's. A one-time or hand-picked rule
 * always runs in every month: its dates are chosen one by one.
 *
 * @param endDate nullable: no end
 * @param months   the months it runs in; {@code null} or empty means every month
 * @param weekdays for a weekly or fortnightly rule, the days of the week it falls on; empty means just
 *                 {@code startDate}'s day. Empty for every other frequency.
 */
public record ScheduleRule(
        Frequency frequency,
        int interval,
        Integer day1,
        Integer day2,
        Integer month,
        LocalDate startDate,
        LocalDate endDate,
        WeekendRule weekendRule,
        List<LocalDate> manualDates,
        Set<Month> months,
        Set<DayOfWeek> weekdays) {

    /** Day-of-month value meaning "last day of the month" (shorter months clamp to it). */
    public static final int LAST_DAY = 31;

    private static final Set<Month> ALL_MONTHS = Collections.unmodifiableSet(EnumSet.allOf(Month.class));

    /** A rule on one day of the week (or none chosen): {@code startDate}'s, when it is weekly. */
    public ScheduleRule(
            Frequency frequency,
            int interval,
            Integer day1,
            Integer day2,
            Integer month,
            LocalDate startDate,
            LocalDate endDate,
            WeekendRule weekendRule,
            List<LocalDate> manualDates,
            Set<Month> months) {
        this(frequency, interval, day1, day2, month, startDate, endDate, weekendRule, manualDates, months, null);
    }

    /** A rule that runs every month. */
    public ScheduleRule(
            Frequency frequency,
            int interval,
            Integer day1,
            Integer day2,
            Integer month,
            LocalDate startDate,
            LocalDate endDate,
            WeekendRule weekendRule,
            List<LocalDate> manualDates) {
        this(frequency, interval, day1, day2, month, startDate, endDate, weekendRule, manualDates, null);
    }

    public ScheduleRule {
        Objects.requireNonNull(frequency, "frequency");
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(weekendRule, "weekendRule");
        manualDates = manualDates == null ? List.of() : manualDates.stream().distinct().sorted().toList();
        months = months == null || months.isEmpty() || frequency == Frequency.ONE_TIME || frequency == Frequency.MANUAL_DATES
                ? ALL_MONTHS
                : Collections.unmodifiableSet(EnumSet.copyOf(months));
        // Only the start's own day is the same as none chosen, so a rule has one way to say it.
        boolean byWeek = frequency == Frequency.WEEKLY || frequency == Frequency.BIWEEKLY;
        weekdays = !byWeek || weekdays == null || weekdays.isEmpty() || weekdays.equals(Set.of(startDate.getDayOfWeek()))
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(weekdays));
        if (endDate != null && endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate is before startDate");
        }
        switch (frequency) {
            case SEMI_MONTHLY -> {
                requireDayOfMonth(day1, "day1");
                requireDayOfMonth(day2, "day2");
                if (day1.equals(day2)) {
                    throw new IllegalArgumentException("day1 and day2 must differ");
                }
            }
            case MONTHLY -> requireDayOfMonth(day1, "day1");
            case EVERY_N_MONTHS -> {
                requireDayOfMonth(day1, "day1");
                if (interval < 1) {
                    throw new IllegalArgumentException("interval must be at least 1");
                }
            }
            case YEARLY -> {
                requireDayOfMonth(day1, "day1");
                if (month == null || month < 1 || month > 12) {
                    throw new IllegalArgumentException("month must be 1-12");
                }
                if (!months.contains(Month.of(month))) {
                    throw new IllegalArgumentException("a yearly rule must run in its own month");
                }
            }
            case MANUAL_DATES -> {
                if (manualDates.isEmpty()) {
                    throw new IllegalArgumentException("manualDates must not be empty");
                }
            }
            default -> { }
        }
    }

    /** True when it runs in only some months. */
    public boolean seasonal() {
        return months.size() < ALL_MONTHS.size();
    }

    /** True when a date it names on {@code day} happens: {@code day} is in one of its months. */
    public boolean runsIn(LocalDate day) {
        return months.contains(day.getMonth());
    }

    /** This rule, running only in {@code chosen} months ({@code null} or empty: every month). */
    public ScheduleRule inMonths(Set<Month> chosen) {
        return new ScheduleRule(frequency, interval, day1, day2, month, startDate, endDate, weekendRule, manualDates, chosen, weekdays);
    }

    /** This weekly or fortnightly rule, on each of {@code chosen} days of the week ({@code null} or empty: the start's day). */
    public ScheduleRule onWeekdays(Set<DayOfWeek> chosen) {
        return new ScheduleRule(frequency, interval, day1, day2, month, startDate, endDate, weekendRule, manualDates, months, chosen);
    }

    /** How many days of the week a weekly or fortnightly rule falls on: one unless several were chosen. */
    public int daysAWeek() {
        return weekdays.isEmpty() ? 1 : weekdays.size();
    }

    public static ScheduleRule weekly(LocalDate startDate, WeekendRule weekendRule) {
        return new ScheduleRule(Frequency.WEEKLY, 1, null, null, null, startDate, null, weekendRule, List.of());
    }

    public static ScheduleRule biweekly(LocalDate startDate, WeekendRule weekendRule) {
        return new ScheduleRule(Frequency.BIWEEKLY, 1, null, null, null, startDate, null, weekendRule, List.of());
    }

    public static ScheduleRule semiMonthly(int day1, int day2, LocalDate startDate, WeekendRule weekendRule) {
        return new ScheduleRule(Frequency.SEMI_MONTHLY, 1, day1, day2, null, startDate, null, weekendRule, List.of());
    }

    public static ScheduleRule monthly(int day, LocalDate startDate, WeekendRule weekendRule) {
        return new ScheduleRule(Frequency.MONTHLY, 1, day, null, null, startDate, null, weekendRule, List.of());
    }

    /** @param dates at least one date */
    public static ScheduleRule manual(List<LocalDate> dates, WeekendRule weekendRule) {
        LocalDate first = dates.stream().min(LocalDate::compareTo)
                .orElseThrow(() -> new IllegalArgumentException("manualDates must not be empty"));
        return new ScheduleRule(Frequency.MANUAL_DATES, 1, null, null, null, first, null, weekendRule, dates);
    }

    private static void requireDayOfMonth(Integer day, String name) {
        if (day == null || day < 1 || day > LAST_DAY) {
            throw new IllegalArgumentException(name + " must be 1-31");
        }
    }
}
