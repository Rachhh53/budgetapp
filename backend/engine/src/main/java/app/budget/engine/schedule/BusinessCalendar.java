package app.budget.engine.schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Set;

/** Knows which days are business days: not a Saturday, a Sunday, or one of the given holidays. */
public final class BusinessCalendar {

    private final Set<LocalDate> holidays;

    public BusinessCalendar(Collection<LocalDate> holidays) {
        this.holidays = Set.copyOf(holidays);
    }

    public boolean isBusinessDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && !holidays.contains(date);
    }

    /** Moves a date off a weekend or holiday in the rule's direction; business days stay put. */
    public LocalDate adjust(LocalDate date, WeekendRule rule) {
        if (rule == WeekendRule.NONE) {
            return date;
        }
        int step = rule == WeekendRule.PREVIOUS_BUSINESS_DAY ? -1 : 1;
        LocalDate adjusted = date;
        while (!isBusinessDay(adjusted)) {
            adjusted = adjusted.plusDays(step);
        }
        return adjusted;
    }
}
