package app.budget.server.items;

import app.budget.engine.schedule.Frequency;
import app.budget.engine.schedule.ScheduleRule;
import app.budget.engine.schedule.WeekendRule;
import app.budget.server.api.ApiException;
import app.budget.server.api.Inputs;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A recurring item's schedule as it travels through the API, in both directions. Frequency
 * and weekend rule are the lower-case names used in the database (e.g. {@code every_pay_period}).
 *
 * @param interval months apart, for {@code every_n_months}
 * @param day1     day of the month, 1–31; 31 means "last day"
 * @param month    1–12, for {@code yearly}
 */
public record ItemScheduleDto(
        String frequency,
        Integer interval,
        Integer day1,
        Integer day2,
        Integer month,
        LocalDate startDate,
        LocalDate endDate,
        String weekendRule) {

    /** Hand-picked dates are for paydays; an item that falls on odd dates is a set of one-offs. */
    private static final Set<Frequency> ITEM_FREQUENCIES = EnumSet.complementOf(EnumSet.of(Frequency.MANUAL_DATES));

    private static final int MAX_MONTHS_APART = 60;

    public static ItemScheduleDto of(ScheduleRule rule) {
        return new ItemScheduleDto(
                Inputs.code(rule.frequency()),
                rule.interval(),
                rule.day1(),
                rule.day2(),
                rule.month(),
                rule.startDate(),
                rule.endDate(),
                Inputs.code(rule.weekendRule()));
    }

    /** Checks the schedule and turns it into a rule, reporting any problem in words the form can show. */
    public ScheduleRule toRule() {
        Frequency chosen = Inputs.choice(Frequency.class, frequency, "how often it repeats");
        if (!ITEM_FREQUENCIES.contains(chosen)) {
            throw ApiException.badRequest("Please choose how often it repeats from the list.");
        }
        WeekendRule weekend = weekendRule == null
                ? WeekendRule.NONE
                : Inputs.choice(WeekendRule.class, weekendRule, "what happens on a weekend or holiday");
        if (startDate == null || !Inputs.inSupportedYears(startDate)) {
            throw ApiException.badRequest("Please choose the date it starts.");
        }
        if (endDate != null && (endDate.isBefore(startDate) || !Inputs.inSupportedYears(endDate))) {
            throw ApiException.badRequest("The last date can't be before the first one.");
        }

        Integer first = null;
        Integer second = null;
        Integer monthOfYear = null;
        int monthsApart = 1;
        switch (chosen) {
            case SEMI_MONTHLY -> {
                first = dayOfMonth(day1);
                second = dayOfMonth(day2);
                if (first.equals(second)) {
                    throw ApiException.badRequest("Please choose two different days of the month.");
                }
            }
            case MONTHLY -> first = dayOfMonth(day1);
            case EVERY_N_MONTHS -> {
                first = dayOfMonth(day1);
                if (interval == null || interval < 2 || interval > MAX_MONTHS_APART) {
                    throw ApiException.badRequest("Please choose how many months apart, from 2 to " + MAX_MONTHS_APART + ".");
                }
                monthsApart = interval;
            }
            case YEARLY -> {
                first = dayOfMonth(day1);
                if (month == null || month < 1 || month > 12) {
                    throw ApiException.badRequest("Please choose the month from the list.");
                }
                monthOfYear = month;
            }
            default -> { }
        }
        return new ScheduleRule(chosen, monthsApart, first, second, monthOfYear, startDate, endDate, weekend, List.of());
    }

    private static int dayOfMonth(Integer day) {
        if (day == null || day < 1 || day > ScheduleRule.LAST_DAY) {
            throw ApiException.badRequest("Please choose the day of the month from the list.");
        }
        return day;
    }
}
