package app.budget.engine.items;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.schedule.BusinessCalendar;
import app.budget.engine.schedule.PayPeriod;
import app.budget.engine.schedule.PayPeriodGenerator;
import app.budget.engine.schedule.ScheduleGenerator;
import app.budget.engine.schedule.ScheduleRule;
import app.budget.engine.schedule.WeekendRule;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The period assignment cases from spec §5.2, with paydays on the 1st and 15th. */
class PeriodAssignerTest {

    private static final List<PayPeriod> PERIODS = new PayPeriodGenerator(new ScheduleGenerator(new BusinessCalendar(List.of())))
            .periods(
                    ScheduleRule.semiMonthly(1, 15, LocalDate.of(2026, 1, 1), WeekendRule.NONE),
                    LocalDate.of(2026, 9, 1),
                    LocalDate.of(2026, 11, 30));

    private final PeriodAssigner assigner = new PeriodAssigner(PERIODS);

    @Test
    void rentDueOnPaydayAndPaidByHandComesOutOfThePaycheckBefore() {
        assertThat(assigner.assign(date("2026-10-01"), Placement.DUE_PERIOD, false)).hasValue(period("2026-09-15", "2026-09-30"));
    }

    @Test
    void theSameRentOnAutopayStaysInThePeriodItIsDueIn() {
        assertThat(assigner.assign(date("2026-10-01"), Placement.DUE_PERIOD, true)).hasValue(period("2026-10-01", "2026-10-14"));
    }

    @Test
    void electricDueOnThe16thPlacedOneEarlyLandsInTheFirstHalf() {
        assertThat(assigner.assign(date("2026-10-16"), Placement.ONE_EARLY, false)).hasValue(period("2026-10-01", "2026-10-14"));
    }

    @Test
    void electricDueOnThe16thInItsDuePeriodLandsInTheSecondHalf() {
        assertThat(assigner.assign(date("2026-10-16"), Placement.DUE_PERIOD, false)).hasValue(period("2026-10-15", "2026-10-31"));
    }

    @Test
    void oneEarlyAndDueOnPaydayMovesBackOnePeriodNotTwo() {
        assertThat(assigner.assign(date("2026-10-01"), Placement.ONE_EARLY, false)).hasValue(period("2026-09-15", "2026-09-30"));
        assertThat(assigner.assign(date("2026-10-01"), Placement.ONE_EARLY, true)).hasValue(period("2026-09-15", "2026-09-30"));
    }

    @Test
    void aDateOutsideTheKnownPeriodsHasNoPeriod() {
        assertThat(assigner.assign(date("2025-01-10"), Placement.DUE_PERIOD, true)).isEmpty();
        assertThat(assigner.assign(date("2030-01-10"), Placement.DUE_PERIOD, true)).isEmpty();
    }

    @Test
    void movingBackFromTheFirstKnownPeriodHasNowhereToGo() {
        LocalDate inFirstPeriod = PERIODS.getFirst().start().plusDays(2);

        assertThat(assigner.assign(inFirstPeriod, Placement.ONE_EARLY, true)).isEmpty();
        assertThat(assigner.assign(inFirstPeriod, Placement.DUE_PERIOD, true)).hasValue(PERIODS.getFirst());
    }

    @Test
    void findsAPeriodByItsFirstDayOnly() {
        assertThat(assigner.startingOn(date("2026-10-15"))).hasValue(period("2026-10-15", "2026-10-31"));
        assertThat(assigner.startingOn(date("2026-10-16"))).isEmpty();
        assertThat(assigner.containing(date("2026-10-16"))).hasValue(period("2026-10-15", "2026-10-31"));
    }

    private static LocalDate date(String iso) {
        return LocalDate.parse(iso);
    }

    private static PayPeriod period(String start, String end) {
        return new PayPeriod(date(start), date(end), date(start));
    }
}
