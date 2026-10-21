package app.budget.server.items;

import app.budget.engine.schedule.Frequency;
import app.budget.engine.schedule.ScheduleRule;
import app.budget.engine.schedule.WeekendRule;
import app.budget.server.api.ApiException;
import app.budget.server.api.Inputs;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A recurring item's schedule as it travels through the API, in both directions. Frequency
 * and weekend rule are the lower-case names used in the database (e.g. {@code every_pay_period}).
 *
 * @param interval months apart, for {@code every_n_months}; weeks apart, for {@code weekly} (1 when left out)
 * @param day1     day of the month, 1–31; 31 means "last day"
 * @param month    1–12, for {@code yearly}
 * @param months   the months it runs in, 1–12, in any order; empty or left out, every month. A seasonal
 *                 schedule (Rachel, 2026-10-19: snowplowing in winter only; spec §4.6)
 * @param weekdays for {@code weekly} and {@code biweekly}, the days of the week, 1 (Monday) to 7 (Sunday);
 *                 empty or left out, just the start date's day (Rachel, 2026-10-19: a class "twice per week")
 */
public record ItemScheduleDto(
        String frequency,
        Integer interval,
        Integer day1,
        Integer day2,
        Integer month,
        LocalDate startDate,
        LocalDate endDate,
        String weekendRule,
        List<Integer> months,
        List<Integer> weekdays) {

    /** Hand-picked dates are for paydays; an item that falls on odd dates is a set of one-offs. */
    private static final Set<Frequency> ITEM_FREQUENCIES = EnumSet.complementOf(EnumSet.of(Frequency.MANUAL_DATES));

    private static final int MAX_MONTHS_APART = 60;
    private static final int MAX_WEEKS_APART = 52;

    public static ItemScheduleDto of(ScheduleRule rule) {
        return new ItemScheduleDto(
                Inputs.code(rule.frequency()),
                rule.interval(),
                rule.day1(),
                rule.day2(),
                rule.month(),
                rule.startDate(),
                rule.endDate(),
                Inputs.code(rule.weekendRule()),
                rule.seasonal() ? rule.months().stream().map(Month::getValue).sorted().toList() : List.of(),
                rule.weekdays().stream().map(DayOfWeek::getValue).sorted().toList());
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
        // The rule's interval: months apart, or weeks apart for weekly.
        int apart = 1;
        switch (chosen) {
            case SEMI_MONTHLY -> {
                first = dayOfMonth(day1);
                second = dayOfMonth(day2);
                if (first.equals(second)) {
                    throw ApiException.badRequest("Please choose two different days of the month.");
                }
            }
            case WEEKLY -> {
                // Every few weeks (Rachel, 2026-10-20); left out, every week.
                if (interval != null && (interval < 1 || interval > MAX_WEEKS_APART)) {
                    throw ApiException.badRequest("Please choose how many weeks apart, from 1 to " + MAX_WEEKS_APART + ".");
                }
                apart = interval == null ? 1 : interval;
            }
            case MONTHLY -> first = dayOfMonth(day1);
            case EVERY_N_MONTHS -> {
                first = dayOfMonth(day1);
                if (interval == null || interval < 2 || interval > MAX_MONTHS_APART) {
                    throw ApiException.badRequest("Please choose how many months apart, from 2 to " + MAX_MONTHS_APART + ".");
                }
                apart = interval;
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
        Set<Month> runsIn = months(chosen, monthOfYear);
        return new ScheduleRule(chosen, apart, first, second, monthOfYear, startDate, endDate, weekend, List.of(), runsIn, weekdays(chosen));
    }

    /** The days of the week a weekly or fortnightly schedule falls on, checked; empty for just the start date's day. */
    private Set<DayOfWeek> weekdays(Frequency chosen) {
        if (weekdays == null || weekdays.isEmpty()) {
            return Set.of();
        }
        if (weekdays.stream().anyMatch(day -> day == null || day < 1 || day > 7)) {
            throw ApiException.badRequest("Please choose the days of the week from the list.");
        }
        if (chosen != Frequency.WEEKLY && chosen != Frequency.BIWEEKLY) {
            throw ApiException.badRequest("Days of the week are for something weekly or every two weeks. Please choose how often again.");
        }
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        weekdays.forEach(day -> days.add(DayOfWeek.of(day)));
        return days;
    }

    /** The months it runs in, checked; empty for every month. */
    private Set<Month> months(Frequency chosen, Integer monthOfYear) {
        if (months == null) {
            return Set.of();
        }
        if (months.stream().anyMatch(chosenMonth -> chosenMonth == null || chosenMonth < 1 || chosenMonth > 12)) {
            throw ApiException.badRequest("Please choose the months from the list.");
        }
        Set<Month> chosenMonths = EnumSet.noneOf(Month.class);
        months.forEach(chosenMonth -> chosenMonths.add(Month.of(chosenMonth)));
        // None chosen, or all twelve, is every month.
        if (chosenMonths.isEmpty() || chosenMonths.size() == Month.values().length) {
            return Set.of();
        }
        if (chosen == Frequency.ONE_TIME) {
            throw ApiException.badRequest("Something that happens once can't be limited to some months. Please choose every month.");
        }
        if (monthOfYear != null && !chosenMonths.contains(Month.of(monthOfYear))) {
            throw ApiException.badRequest("Something yearly has to run in the month it falls in. Please include " + Month.of(monthOfYear)
                    .getDisplayName(TextStyle.FULL, Locale.US) + ".");
        }
        return chosenMonths;
    }

    private static int dayOfMonth(Integer day) {
        if (day == null || day < 1 || day > ScheduleRule.LAST_DAY) {
            throw ApiException.badRequest("Please choose the day of the month from the list.");
        }
        return day;
    }
}
