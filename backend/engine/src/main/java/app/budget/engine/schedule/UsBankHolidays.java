package app.budget.engine.schedule;

import static java.time.temporal.TemporalAdjusters.dayOfWeekInMonth;
import static java.time.temporal.TemporalAdjusters.lastInMonth;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The US Federal Reserve bank holidays, worked out from the calendar — no download, no
 * network. They are only a starting point: the user can switch any of them off, change
 * them, or add their own.
 */
public final class UsBankHolidays {

    private UsBankHolidays() {}

    /**
     * The days banks are closed for a holiday in {@code year}, in date order.
     *
     * <p>Follows the Federal Reserve's rule: a holiday on a Sunday is observed the following
     * Monday; a holiday on a Saturday is not moved (banks are open the Friday before), so it
     * stays on its own date, where it is a weekend anyway.
     */
    public static List<Holiday> in(int year) {
        return Stream.of(
                        fixed(year, Month.JANUARY, 1, "New Year's Day"),
                        nth(year, Month.JANUARY, 3, DayOfWeek.MONDAY, "Martin Luther King Jr. Day"),
                        nth(year, Month.FEBRUARY, 3, DayOfWeek.MONDAY, "Presidents' Day"),
                        new Holiday(LocalDate.of(year, Month.MAY, 1).with(lastInMonth(DayOfWeek.MONDAY)), "Memorial Day"),
                        fixed(year, Month.JUNE, 19, "Juneteenth"),
                        fixed(year, Month.JULY, 4, "Independence Day"),
                        nth(year, Month.SEPTEMBER, 1, DayOfWeek.MONDAY, "Labor Day"),
                        nth(year, Month.OCTOBER, 2, DayOfWeek.MONDAY, "Indigenous Peoples' Day"),
                        fixed(year, Month.NOVEMBER, 11, "Veterans Day"),
                        nth(year, Month.NOVEMBER, 4, DayOfWeek.THURSDAY, "Thanksgiving Day"),
                        fixed(year, Month.DECEMBER, 25, "Christmas Day"))
                .sorted(Comparator.comparing(Holiday::date))
                .toList();
    }

    private static Holiday fixed(int year, Month month, int day, String name) {
        LocalDate date = LocalDate.of(year, month, day);
        return new Holiday(date.getDayOfWeek() == DayOfWeek.SUNDAY ? date.plusDays(1) : date, name);
    }

    private static Holiday nth(int year, Month month, int ordinal, DayOfWeek day, String name) {
        return new Holiday(LocalDate.of(year, month, 1).with(dayOfWeekInMonth(ordinal, day)), name);
    }
}
