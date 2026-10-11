package app.budget.engine.cards;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * When a credit card's statements close and fall due. Both are days of the month, where 31
 * means "the last day" and a month without the day uses its last one, as for schedules.
 */
public final class StatementCycle {

    public static final int LAST_DAY = 31;

    private StatementCycle() {}

    /** The closing dates within {@code from}–{@code to} (both inclusive), earliest first. */
    public static List<LocalDate> closeDates(int closeDay, LocalDate from, LocalDate to) {
        requireDay(closeDay);
        List<LocalDate> dates = new ArrayList<>();
        for (YearMonth month = YearMonth.from(from); !month.atDay(1).isAfter(to); month = month.plusMonths(1)) {
            LocalDate date = onDay(month, closeDay);
            if (!date.isBefore(from) && !date.isAfter(to)) {
                dates.add(date);
            }
        }
        return dates;
    }

    /** The latest closing date on or before {@code day}. */
    public static LocalDate lastCloseOnOrBefore(int closeDay, LocalDate day) {
        requireDay(closeDay);
        LocalDate thisMonth = onDay(YearMonth.from(day), closeDay);
        return thisMonth.isAfter(day) ? onDay(YearMonth.from(day).minusMonths(1), closeDay) : thisMonth;
    }

    /** The first closing date after {@code day}. */
    public static LocalDate nextCloseAfter(int closeDay, LocalDate day) {
        requireDay(closeDay);
        LocalDate thisMonth = onDay(YearMonth.from(day), closeDay);
        return thisMonth.isAfter(day) ? thisMonth : onDay(YearMonth.from(day).plusMonths(1), closeDay);
    }

    /**
     * The closing date nearest to {@code day}. A real statement can close a day or two off its
     * usual day (a weekend, a holiday); this says which month's statement it is.
     */
    public static LocalDate nearestClose(int closeDay, LocalDate day) {
        LocalDate before = lastCloseOnOrBefore(closeDay, day);
        LocalDate after = nextCloseAfter(closeDay, day);
        return ChronoUnit.DAYS.between(before, day) <= ChronoUnit.DAYS.between(day, after) ? before : after;
    }

    /** When the statement that closed on {@code closeDate} falls due: the first due day after it. */
    public static LocalDate dueDate(LocalDate closeDate, int dueDay) {
        requireDay(dueDay);
        LocalDate sameMonth = onDay(YearMonth.from(closeDate), dueDay);
        return sameMonth.isAfter(closeDate) ? sameMonth : onDay(YearMonth.from(closeDate).plusMonths(1), dueDay);
    }

    private static LocalDate onDay(YearMonth month, int day) {
        return month.atDay(Math.min(day, month.lengthOfMonth()));
    }

    private static void requireDay(int day) {
        if (day < 1 || day > LAST_DAY) {
            throw new IllegalArgumentException("A statement day is a day of the month, 1 to 31");
        }
    }
}
