package app.budget.engine.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScheduleGeneratorTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 1);

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
