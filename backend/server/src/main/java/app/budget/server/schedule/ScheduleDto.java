package app.budget.server.schedule;

import app.budget.engine.schedule.Frequency;
import app.budget.engine.schedule.PayPeriodGenerator;
import app.budget.engine.schedule.ScheduleRule;
import app.budget.engine.schedule.WeekendRule;
import app.budget.server.api.ApiException;
import app.budget.server.api.Inputs;
import java.time.LocalDate;
import java.util.List;

/**
 * A schedule as it travels through the API, in both directions. Frequency and weekend rule
 * are the lower-case names used in the database (e.g. {@code semi_monthly}).
 *
 * @param day1 day of the month, 1–31; 31 means "last day"
 */
public record ScheduleDto(
        String frequency,
        Integer day1,
        Integer day2,
        LocalDate startDate,
        LocalDate endDate,
        String weekendRule,
        List<LocalDate> manualDates) {

    /** Enough hand-picked paydays for several years of weekly pay. */
    private static final int MAX_MANUAL_DATES = 500;

    public static ScheduleDto of(ScheduleRule rule) {
        return new ScheduleDto(
                Inputs.code(rule.frequency()),
                rule.day1(),
                rule.day2(),
                rule.startDate(),
                rule.endDate(),
                Inputs.code(rule.weekendRule()),
                rule.manualDates());
    }

    /**
     * Checks a schedule sent for a paycheck and turns it into a rule. Every problem is
     * reported in words the form can show next to the schedule fields.
     */
    public ScheduleRule toPayRule() {
        Frequency chosen = Inputs.choice(Frequency.class, frequency, "how often you're paid");
        if (!PayPeriodGenerator.PAY_FREQUENCIES.contains(chosen)) {
            throw ApiException.badRequest("Please choose how often you're paid from the list.");
        }
        WeekendRule weekend = Inputs.choice(WeekendRule.class, weekendRule, "what happens on a weekend or holiday");

        if (chosen == Frequency.MANUAL_DATES) {
            if (manualDates == null || manualDates.size() < 2 || manualDates.contains(null)) {
                throw ApiException.badRequest("Please add at least two paydays, so there's a pay period between them.");
            }
            if (manualDates.size() > MAX_MANUAL_DATES) {
                throw ApiException.badRequest("That's more paydays than Budget can keep in one list.");
            }
            return ScheduleRule.manual(manualDates, weekend);
        }

        if (startDate == null) {
            throw ApiException.badRequest("Please choose the date of a payday to start from.");
        }
        if (endDate != null && endDate.isBefore(startDate)) {
            throw ApiException.badRequest("The last payday can't be before the first one.");
        }
        Integer first = null;
        Integer second = null;
        if (chosen == Frequency.SEMI_MONTHLY || chosen == Frequency.MONTHLY) {
            first = dayOfMonth(day1);
        }
        if (chosen == Frequency.SEMI_MONTHLY) {
            second = dayOfMonth(day2);
            if (first.equals(second)) {
                throw ApiException.badRequest("Please choose two different days of the month.");
            }
        }
        return new ScheduleRule(chosen, 1, first, second, null, startDate, endDate, weekend, List.of());
    }

    private static int dayOfMonth(Integer day) {
        if (day == null || day < 1 || day > ScheduleRule.LAST_DAY) {
            throw ApiException.badRequest("Please choose the day of the month from the list.");
        }
        return day;
    }
}
