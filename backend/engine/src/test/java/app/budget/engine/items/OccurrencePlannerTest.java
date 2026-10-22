package app.budget.engine.items;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.items.OccurrencePlanner.Planned;
import app.budget.engine.items.OccurrencePlanner.Version;
import app.budget.engine.schedule.BusinessCalendar;
import app.budget.engine.schedule.Frequency;
import app.budget.engine.schedule.PayPeriod;
import app.budget.engine.schedule.PayPeriodGenerator;
import app.budget.engine.schedule.ScheduleGenerator;
import app.budget.engine.schedule.ScheduleRule;
import app.budget.engine.schedule.WeekendRule;
import java.time.LocalDate;
import java.time.Month;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** When recurring items fall due, with paydays on the 1st and 15th. */
class OccurrencePlannerTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final ScheduleGenerator SCHEDULE = new ScheduleGenerator(new BusinessCalendar(List.of()));
    private static final List<PayPeriod> PERIODS = new PayPeriodGenerator(SCHEDULE)
            .periods(ScheduleRule.semiMonthly(1, 15, START, WeekendRule.NONE), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31));

    private final OccurrencePlanner planner = new OccurrencePlanner(SCHEDULE, PERIODS);

    @Test
    void aSeasonalBudgetIsOnlyInThePayPeriodsOfItsMonths() {
        ScheduleRule winter = new ScheduleRule(Frequency.EVERY_PAY_PERIOD, 1, null, null, null, START, null, WeekendRule.NONE, List.of())
                .inMonths(EnumSet.of(Month.NOVEMBER, Month.DECEMBER, Month.JANUARY, Month.FEBRUARY, Month.MARCH));

        List<Planned<String>> occurrences = planner.occurrences(List.of(new Version<>(START, winter, 0, "plowing")), date("2026-10-01"), date("2026-11-30"));

        assertThat(occurrences).extracting(Planned::due).containsExactly(date("2026-11-01"), date("2026-11-15"));
    }

    @Test
    void eachOccurrenceTakesTheVersionInForceOnItsScheduledDate() {
        ScheduleRule fifth = ScheduleRule.monthly(5, START, WeekendRule.NONE);
        List<Version<String>> versions = List.of(
                new Version<>(date("2026-11-01"), fifth, 0, "$430"),
                new Version<>(START, fifth, 0, "$412"));

        List<Planned<String>> occurrences = planner.occurrences(versions, date("2026-10-01"), date("2026-12-31"));

        assertThat(occurrences).extracting(Planned::due).containsExactly(date("2026-10-05"), date("2026-11-05"), date("2026-12-05"));
        assertThat(occurrences).extracting(occurrence -> occurrence.version().details()).containsExactly("$412", "$430", "$430");
    }

    @Test
    void aNewVersionCanChangeTheScheduleFromItsDateOn() {
        List<Version<String>> versions = List.of(
                new Version<>(START, ScheduleRule.monthly(5, START, WeekendRule.NONE), 0, "5th"),
                new Version<>(date("2026-11-01"), ScheduleRule.monthly(20, START, WeekendRule.NONE), 0, "20th"));

        assertThat(planner.occurrences(versions, date("2026-10-01"), date("2026-12-31")))
                .extracting(Planned::due)
                .containsExactly(date("2026-10-05"), date("2026-11-20"), date("2026-12-20"));
    }

    @Test
    void theEarliestVersionAlsoCoversDatesBeforeIt() {
        // The only version was saved "from Nov 1", but nothing else exists for October.
        List<Version<String>> versions = List.of(
                new Version<>(date("2026-11-01"), ScheduleRule.monthly(5, START, WeekendRule.NONE), 0, "only"));

        assertThat(planner.occurrences(versions, date("2026-10-01"), date("2026-11-30")))
                .extracting(Planned::due)
                .containsExactly(date("2026-10-05"), date("2026-11-05"));
    }

    @Test
    void aWeekendMoveKeepsTheScheduledDateAsTheOccurrencesIdentity() {
        ScheduleRule first = ScheduleRule.monthly(1, START, WeekendRule.PREVIOUS_BUSINESS_DAY);

        List<Planned<String>> occurrences =
                planner.occurrences(List.of(new Version<>(START, first, 0, "rent")), date("2026-10-15"), date("2026-11-15"));

        // Nov 1, 2026 is a Sunday.
        assertThat(occurrences).singleElement().satisfies(occurrence -> {
            assertThat(occurrence.scheduled()).isEqualTo(date("2026-11-01"));
            assertThat(occurrence.due()).isEqualTo(date("2026-10-30"));
            assertThat(occurrence.ownPeriod()).isNull();
        });
    }

    @Test
    void everyPayPeriodGivesOneOccurrencePerPeriodOnPayday() {
        List<Planned<String>> occurrences =
                planner.occurrences(List.of(everyPayPeriod(START, null, 0)), date("2026-10-01"), date("2026-10-31"));

        assertThat(occurrences).extracting(Planned::due).containsExactly(date("2026-10-01"), date("2026-10-15"));
        assertThat(occurrences).extracting(occurrence -> occurrence.ownPeriod().end())
                .containsExactly(date("2026-10-14"), date("2026-10-31"));
    }

    @Test
    void aBudgetCanBeDatedSomeDaysAfterPaydayButNeverPastItsOwnPeriod() {
        assertThat(planner.occurrences(List.of(everyPayPeriod(START, null, 3)), date("2026-10-01"), date("2026-10-31")))
                .extracting(Planned::due)
                .containsExactly(date("2026-10-04"), date("2026-10-18"));

        List<Planned<String>> capped =
                planner.occurrences(List.of(everyPayPeriod(START, null, 60)), date("2026-10-01"), date("2026-10-31"));
        assertThat(capped).extracting(Planned::due).containsExactly(date("2026-10-14"), date("2026-10-31"));
        // Still identified by the payday, whatever day it is dated.
        assertThat(capped).extracting(Planned::scheduled).containsExactly(date("2026-10-01"), date("2026-10-15"));
    }

    @Test
    void everyPayPeriodStartingMidPeriodIncludesThePeriodAlreadyRunning() {
        assertThat(planner.occurrences(List.of(everyPayPeriod(date("2026-10-06"), null, 0)), date("2026-09-01"), date("2026-10-31")))
                .extracting(Planned::due)
                .containsExactly(date("2026-10-01"), date("2026-10-15"));
    }

    @Test
    void everyPayPeriodStopsAfterItsEndDate() {
        assertThat(planner.occurrences(List.of(everyPayPeriod(START, date("2026-10-20"), 0)), date("2026-10-01"), date("2026-11-30")))
                .extracting(Planned::due)
                .containsExactly(date("2026-10-01"), date("2026-10-15"));
    }

    private static Version<String> everyPayPeriod(LocalDate start, LocalDate end, int daysAfterPayday) {
        ScheduleRule rule = new ScheduleRule(Frequency.EVERY_PAY_PERIOD, 1, null, null, null, start, end, WeekendRule.NONE, List.of());
        return new Version<>(start, rule, daysAfterPayday, "budget");
    }

    private static LocalDate date(String iso) {
        return LocalDate.parse(iso);
    }
}
