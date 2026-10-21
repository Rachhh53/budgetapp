package app.budget.engine.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScheduleGeneratorTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 1);

    /** November through March: snowplowing. */
    private static final Set<Month> WINTER = EnumSet.of(Month.NOVEMBER, Month.DECEMBER, Month.JANUARY, Month.FEBRUARY, Month.MARCH);

    @Test
    void aSeasonalRuleNamesNoDateOutsideItsMonthsAndRunsAcrossTheNewYear() {
        ScheduleRule plowing = ScheduleRule.weekly(date("2026-01-05"), WeekendRule.NONE).inMonths(WINTER);

        assertThat(dates(plowing, "2026-03-20", "2026-11-10"))
                .containsExactly(date("2026-03-23"), date("2026-03-30"), date("2026-11-02"), date("2026-11-09"));
        assertThat(dates(plowing, "2026-12-25", "2027-01-10")).containsExactly(date("2026-12-28"), date("2027-01-04"));
    }

    @Test
    void aSeasonalRuleGoesByTheDayItNamesNotTheDayAWeekendMovesItTo() {
        // Sat Mar 31, 2029 moves to Mon Apr 2: it is March's date, so it happens. Apr 30 is not winter, so it does not.
        ScheduleRule monthly = ScheduleRule.monthly(31, START, WeekendRule.NEXT_BUSINESS_DAY).inMonths(WINTER);

        assertThat(dates(monthly, "2029-03-01", "2029-04-30")).containsExactly(date("2029-04-02"));
    }

    @Test
    void everyMonthIsTheSameAsNoMonthsChosen() {
        ScheduleRule plain = ScheduleRule.monthly(5, START, WeekendRule.NONE);

        assertThat(plain.inMonths(EnumSet.allOf(Month.class)).seasonal()).isFalse();
        assertThat(plain.inMonths(Set.of()).seasonal()).isFalse();
        assertThat(plain.inMonths(WINTER).seasonal()).isTrue();
    }

    @Test
    void aYearlyRuleMustRunInItsOwnMonthAndOneTimeDatesIgnoreMonths() {
        ScheduleRule april = new ScheduleRule(Frequency.YEARLY, 1, 15, null, 4, START, null, WeekendRule.NONE, List.of());

        assertThatIllegalArgumentException().isThrownBy(() -> april.inMonths(WINTER));
        assertThat(new ScheduleRule(Frequency.ONE_TIME, 1, null, null, null, date("2026-07-04"), null, WeekendRule.NONE, List.of())
                        .inMonths(WINTER).seasonal()).isFalse();
    }

    @Test
    void twiceAWeekFallsOnEachChosenDay() {
        ScheduleRule mondaysAndThursdays = ScheduleRule.weekly(date("2026-10-19"), WeekendRule.NONE)
                .onWeekdays(EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.THURSDAY));

        assertThat(dates(mondaysAndThursdays, "2026-10-01", "2026-10-31"))
                .containsExactly(date("2026-10-19"), date("2026-10-22"), date("2026-10-26"), date("2026-10-29"));
    }

    @Test
    void nothingBeforeTheStartEvenOnAnEarlierChosenDayOfItsWeek() {
        // Starts on a Wednesday: that week's Monday is before it, so the first is Thursday.
        ScheduleRule rule = ScheduleRule.weekly(date("2026-10-21"), WeekendRule.NONE)
                .onWeekdays(EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.THURSDAY));

        assertThat(dates(rule, "2026-10-01", "2026-10-31")).containsExactly(date("2026-10-22"), date("2026-10-26"), date("2026-10-29"));
    }

    @Test
    void fortnightlyOnTwoDaysKeepsEveryOtherWeek() {
        ScheduleRule rule = ScheduleRule.biweekly(date("2026-10-19"), WeekendRule.NONE)
                .onWeekdays(EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.THURSDAY));

        assertThat(dates(rule, "2026-10-19", "2026-11-08"))
                .containsExactly(date("2026-10-19"), date("2026-10-22"), date("2026-11-02"), date("2026-11-05"));
    }

    @Test
    void onlyTheStartsOwnDayIsTheSameAsNoneChosen() {
        ScheduleRule rule = ScheduleRule.weekly(date("2026-10-19"), WeekendRule.NONE).onWeekdays(EnumSet.of(DayOfWeek.MONDAY));

        assertThat(rule.weekdays()).isEmpty();
        assertThat(ScheduleRule.monthly(5, START, WeekendRule.NONE).onWeekdays(EnumSet.of(DayOfWeek.FRIDAY)).weekdays()).isEmpty();
    }

    @Test
    void aDayTheMonthDoesNotHaveFallsToItsLastDay() {
        ScheduleRule rule = ScheduleRule.monthly(31, START, WeekendRule.NONE);

        assertThat(dates(rule, "2027-01-01", "2027-04-30"))
                .containsExactly(date("2027-01-31"), date("2027-02-28"), date("2027-03-31"), date("2027-04-30"));
    }

    @Test
    void biweeklyKeepsItsRhythmFromTheStartDate() {
        ScheduleRule rule = ScheduleRule.biweekly(date("2026-11-06"), WeekendRule.NONE);

        assertThat(dates(rule, "2027-03-01", "2027-03-31")).containsExactly(date("2027-03-12"), date("2027-03-26"));
    }

    @Test
    void nothingHappensBeforeTheStartOrAfterTheEnd() {
        ScheduleRule rule = new ScheduleRule(Frequency.WEEKLY, 1, null, null, null,
                date("2026-10-02"), date("2026-10-16"), WeekendRule.NONE, List.of());

        assertThat(dates(rule, "2026-09-01", "2026-12-31"))
                .containsExactly(date("2026-10-02"), date("2026-10-09"), date("2026-10-16"));
    }

    @Test
    void everyFewWeeksCountsFromTheStart() {
        // A pack every 10 weeks (Rachel, 2026-10-20).
        ScheduleRule everyTenWeeks = new ScheduleRule(Frequency.WEEKLY, 10, null, null, null,
                date("2026-09-01"), null, WeekendRule.NONE, List.of());

        assertThat(dates(everyTenWeeks, "2026-08-01", "2027-03-31"))
                .containsExactly(date("2026-09-01"), date("2026-11-10"), date("2027-01-19"), date("2027-03-30"));
    }

    @Test
    void everyNMonthsCountsFromTheStartMonth() {
        ScheduleRule quarterly = new ScheduleRule(Frequency.EVERY_N_MONTHS, 3, 10, null, null,
                date("2026-02-01"), null, WeekendRule.NONE, List.of());

        assertThat(dates(quarterly, "2026-01-01", "2026-12-31"))
                .containsExactly(date("2026-02-10"), date("2026-05-10"), date("2026-08-10"), date("2026-11-10"));
    }

    @Test
    void yearlyOnFebruary29thFallsBackInOtherYears() {
        ScheduleRule rule = new ScheduleRule(Frequency.YEARLY, 1, 29, null, 2,
                START, null, WeekendRule.NONE, List.of());

        assertThat(dates(rule, "2027-01-01", "2028-12-31")).containsExactly(date("2027-02-28"), date("2028-02-29"));
    }

    @Test
    void oneTimeHappensOnce() {
        ScheduleRule rule = new ScheduleRule(Frequency.ONE_TIME, 1, null, null, null,
                date("2026-10-20"), null, WeekendRule.NONE, List.of());

        assertThat(dates(rule, "2026-01-01", "2027-12-31")).containsExactly(date("2026-10-20"));
    }

    @Test
    void anOccurrenceRemembersWhereItWasScheduled() {
        ScheduleRule rule = ScheduleRule.monthly(1, START, WeekendRule.PREVIOUS_BUSINESS_DAY);

        List<Occurrence> occurrences = generator().occurrences(rule, date("2026-10-30"), date("2026-10-30"));

        assertThat(occurrences).containsExactly(new Occurrence(date("2026-11-01"), date("2026-10-30")));
        assertThat(occurrences.getFirst().moved()).isTrue();
    }

    @Test
    void withNoWeekendRuleAWeekendDateStays() {
        ScheduleRule rule = ScheduleRule.monthly(1, START, WeekendRule.NONE);

        assertThat(dates(rule, "2026-11-01", "2026-11-01")).containsExactly(date("2026-11-01"));
    }

    @Test
    void twoDatesMovedOntoTheSameDayCountOnce() {
        // Sat Oct 3 and Sun Oct 4 both move back to Fri Oct 2.
        ScheduleRule rule = ScheduleRule.manual(
                List.of(date("2026-10-03"), date("2026-10-04")), WeekendRule.PREVIOUS_BUSINESS_DAY);

        assertThat(generator().occurrences(rule, date("2026-10-01"), date("2026-10-31")))
                .containsExactly(new Occurrence(date("2026-10-03"), date("2026-10-02")));
    }

    @Test
    void everyPayPeriodNeedsThePayPeriodsToResolve() {
        ScheduleRule rule = new ScheduleRule(Frequency.EVERY_PAY_PERIOD, 1, null, null, null,
                START, null, WeekendRule.NONE, List.of());

        assertThatIllegalArgumentException().isThrownBy(() -> dates(rule, "2026-01-01", "2026-12-31"));
    }

    @Test
    void aRuleRejectsDaysThatMakeNoSense() {
        assertThatIllegalArgumentException().isThrownBy(() -> ScheduleRule.semiMonthly(15, 15, START, WeekendRule.NONE));
        assertThatIllegalArgumentException().isThrownBy(() -> ScheduleRule.monthly(0, START, WeekendRule.NONE));
        assertThatIllegalArgumentException().isThrownBy(() -> ScheduleRule.manual(List.of(), WeekendRule.NONE));
    }

    private static ScheduleGenerator generator() {
        return new ScheduleGenerator(new BusinessCalendar(List.of()));
    }

    private static List<LocalDate> dates(ScheduleRule rule, String from, String to) {
        return generator().occurrences(rule, date(from), date(to)).stream().map(Occurrence::date).toList();
    }

    private static LocalDate date(String iso) {
        return LocalDate.parse(iso);
    }
}
