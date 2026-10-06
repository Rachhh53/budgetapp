package app.budget.engine.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The pay period cases from spec §5.1. */
class PayPeriodGeneratorTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 1);

    @Test
    void semiMonthlyOnThe1stAnd15th() {
        ScheduleRule rule = ScheduleRule.semiMonthly(1, 15, START, WeekendRule.NONE);

        assertThat(periods(rule, "2026-10-01", "2026-10-31")).containsExactly(
                period("2026-10-01", "2026-10-14"),
                period("2026-10-15", "2026-10-31"));
    }

    @Test
    void semiMonthlyOnThe15thAndLastDayInANonLeapFebruary() {
        ScheduleRule rule = ScheduleRule.semiMonthly(15, ScheduleRule.LAST_DAY, START, WeekendRule.NONE);

        assertThat(periods(rule, "2027-02-15", "2027-02-28")).containsExactly(
                period("2027-02-15", "2027-02-27"),
                period("2027-02-28", "2027-03-14"));
    }

    @Test
    void semiMonthlyOnThe15thAndLastDayInALeapFebruary() {
        ScheduleRule rule = ScheduleRule.semiMonthly(15, ScheduleRule.LAST_DAY, START, WeekendRule.NONE);

        assertThat(periods(rule, "2028-02-15", "2028-02-29")).containsExactly(
                period("2028-02-15", "2028-02-28"),
                period("2028-02-29", "2028-03-14"));
    }

    @Test
    void biweeklyAcrossAYearBoundary() {
        ScheduleRule rule = ScheduleRule.biweekly(LocalDate.of(2026, 12, 11), WeekendRule.NONE);

        assertThat(periods(rule, "2026-12-20", "2027-01-10")).containsExactly(
                period("2026-12-11", "2026-12-24"),
                period("2026-12-25", "2027-01-07"),
                period("2027-01-08", "2027-01-21"));
    }

    @Test
    void weekly() {
        ScheduleRule rule = ScheduleRule.weekly(LocalDate.of(2026, 10, 2), WeekendRule.NONE);

        assertThat(periods(rule, "2026-10-02", "2026-10-15")).containsExactly(
                period("2026-10-02", "2026-10-08"),
                period("2026-10-09", "2026-10-15"));
    }

    @Test
    void monthly() {
        ScheduleRule rule = ScheduleRule.monthly(1, START, WeekendRule.NONE);

        assertThat(periods(rule, "2026-10-10", "2026-11-10")).containsExactly(
                period("2026-10-01", "2026-10-31"),
                period("2026-11-01", "2026-11-30"));
    }

    @Test
    void manualDates() {
        ScheduleRule rule = ScheduleRule.manual(
                List.of(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 20)),
                WeekendRule.NONE);

        // The last date opens no period: there is no next payday to close it.
        assertThat(periods(rule, "2026-01-01", "2027-12-31")).containsExactly(
                period("2026-10-03", "2026-10-19"),
                period("2026-10-20", "2026-11-11"));
    }

    @Test
    void aSundayPaydayMovesToThePreviousFriday() {
        ScheduleRule rule = ScheduleRule.semiMonthly(1, 15, START, WeekendRule.PREVIOUS_BUSINESS_DAY);

        // Nov 1, 2026 is a Sunday, so that payday — and the period boundary — is Fri Oct 30.
        assertThat(periods(rule, "2026-10-15", "2026-11-01")).containsExactly(
                new PayPeriod(date("2026-10-15"), date("2026-10-29"), date("2026-10-15")),
                new PayPeriod(date("2026-10-30"), date("2026-11-12"), date("2026-11-01")));
    }

    @Test
    void aHolidayOnThatFridayMovesThePaydayToThursday() {
        ScheduleRule rule = ScheduleRule.semiMonthly(1, 15, START, WeekendRule.PREVIOUS_BUSINESS_DAY);
        PayPeriodGenerator generator = generator(List.of(date("2026-10-30")));

        assertThat(generator.periods(rule, date("2026-10-15"), date("2026-11-01"))).containsExactly(
                new PayPeriod(date("2026-10-15"), date("2026-10-28"), date("2026-10-15")),
                new PayPeriod(date("2026-10-29"), date("2026-11-12"), date("2026-11-01")));
    }

    @Test
    void aPaydayCanMoveForwardInstead() {
        ScheduleRule rule = ScheduleRule.semiMonthly(1, 15, START, WeekendRule.NEXT_BUSINESS_DAY);

        assertThat(periods(rule, "2026-11-02", "2026-11-02")).containsExactly(
                new PayPeriod(date("2026-11-02"), date("2026-11-15"), date("2026-11-01")));
    }

    @Test
    void nothingBeforeTheFirstPayday() {
        ScheduleRule rule = ScheduleRule.biweekly(LocalDate.of(2026, 11, 6), WeekendRule.NONE);

        assertThat(periods(rule, "2026-10-01", "2026-11-05")).isEmpty();
        assertThat(periods(rule, "2026-10-01", "2026-11-06")).containsExactly(period("2026-11-06", "2026-11-19"));
    }

    @Test
    void periodsNeverOverlapOrLeaveGaps() {
        ScheduleRule rule = ScheduleRule.semiMonthly(15, ScheduleRule.LAST_DAY, START, WeekendRule.PREVIOUS_BUSINESS_DAY);

        List<PayPeriod> periods = periods(rule, "2026-10-01", "2028-10-01");

        for (int i = 0; i + 1 < periods.size(); i++) {
            assertThat(periods.get(i + 1).start()).isEqualTo(periods.get(i).end().plusDays(1));
        }
        assertThat(periods.getFirst().contains(date("2026-10-01"))).isTrue();
        assertThat(periods.getLast().contains(date("2028-10-01"))).isTrue();
    }

    @Test
    void aBillStyleScheduleCannotDefinePayPeriods() {
        ScheduleRule yearly = new ScheduleRule(
                Frequency.YEARLY, 1, 1, null, 6, START, null, WeekendRule.NONE, List.of());

        assertThatIllegalArgumentException().isThrownBy(() -> periods(yearly, "2026-01-01", "2026-12-31"));
    }

    private static PayPeriodGenerator generator(List<LocalDate> holidays) {
        return new PayPeriodGenerator(new ScheduleGenerator(new BusinessCalendar(holidays)));
    }

    private static List<PayPeriod> periods(ScheduleRule rule, String from, String through) {
        return generator(List.of()).periods(rule, date(from), date(through));
    }

    /** A period whose payday was not moved. */
    private static PayPeriod period(String start, String end) {
        return new PayPeriod(date(start), date(end), date(start));
    }

    private static LocalDate date(String iso) {
        return LocalDate.parse(iso);
    }
}
