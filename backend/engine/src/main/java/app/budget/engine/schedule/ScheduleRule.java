package app.budget.engine.schedule;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * When something repeats: a paycheck, a bill, a transfer.
 *
 * <p>Which fields matter depends on the {@link Frequency}:
 * <ul>
 *   <li>{@code WEEKLY}, {@code BIWEEKLY} — repeat from {@code startDate}, on its day of the week.
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
 * @param endDate nullable: no end
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
        List<LocalDate> manualDates) {

    /** Day-of-month value meaning "last day of the month" (shorter months clamp to it). */
    public static final int LAST_DAY = 31;

    public ScheduleRule {
        Objects.requireNonNull(frequency, "frequency");
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(weekendRule, "weekendRule");
        manualDates = manualDates == null ? List.of() : manualDates.stream().distinct().sorted().toList();
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
            }
            case MANUAL_DATES -> {
                if (manualDates.isEmpty()) {
                    throw new IllegalArgumentException("manualDates must not be empty");
                }
            }
            default -> { }
        }
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
