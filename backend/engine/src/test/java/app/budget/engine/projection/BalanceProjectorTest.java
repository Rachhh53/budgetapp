package app.budget.engine.projection;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.Money;
import app.budget.engine.schedule.PayPeriod;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Balance projection (spec §5.3), with pay periods Oct 1–14, Oct 15–31 and Nov 1–14. */
class BalanceProjectorTest {

    private static final List<PayPeriod> PERIODS = List.of(
            period("2026-10-01", "2026-10-14"),
            period("2026-10-15", "2026-10-31"),
            period("2026-11-01", "2026-11-14"));

    @Test
    void nothingCanBeProjectedUntilABalanceIsEntered() {
        assertThat(BalanceProjector.project(List.of(), List.of(move("2026-10-01", 248620)), PERIODS)).isEmpty();
    }

    @Test
    void rollsForwardFromTheEnteredBalanceThroughEachPeriod() {
        List<Movement> movements = List.of(
                move("2026-10-01", 248620),
                move("2026-10-02", -20000),
                move("2026-10-05", -41200),
                move("2026-10-15", 248620),
                move("2026-10-16", -155000),
                // Given out of order on purpose.
                move("2026-10-08", -8500));

        List<PeriodBalance> balances = BalanceProjector.project(List.of(anchor("2026-09-30", 121438)), movements, PERIODS);

        assertThat(balances).hasSize(3);
        PeriodBalance first = balances.get(0);
        assertThat(first.starting()).isEqualTo(cents(121438));
        assertThat(first.startingOn()).isEqualTo(date("2026-10-01"));
        assertThat(first.entered()).isFalse();
        assertThat(first.in()).isEqualTo(cents(248620));
        assertThat(first.out()).isEqualTo(cents(69700));
        assertThat(first.ending()).isEqualTo(cents(300358));

        PeriodBalance second = balances.get(1);
        assertThat(second.starting()).isEqualTo(first.ending());
        assertThat(second.ending()).isEqualTo(cents(393978));

        PeriodBalance third = balances.get(2);
        assertThat(third.starting()).isEqualTo(second.ending());
        assertThat(third.in()).isEqualTo(Money.ZERO);
        assertThat(third.out()).isEqualTo(Money.ZERO);
        assertThat(third.ending()).isEqualTo(second.ending());
    }

    @Test
    void endingIsAlwaysStartingPlusInMinusOut() {
        List<Movement> movements = List.of(move("2026-10-03", -5000), move("2026-10-20", 7500), move("2026-11-02", -125));

        for (PeriodBalance balance : BalanceProjector.project(List.of(anchor("2026-10-10", 90000)), movements, PERIODS)) {
            assertThat(balance.ending()).isEqualTo(balance.starting().plus(balance.in()).minus(balance.out()));
        }
    }

    @Test
    void findsTheLowestPointAndTheDayItFallsOn() {
        List<Movement> movements = List.of(move("2026-10-03", -45000), move("2026-10-10", 100000));

        PeriodBalance balance = BalanceProjector.project(List.of(anchor("2026-09-30", 50000)), movements, PERIODS).getFirst();

        assertThat(balance.lowest()).isEqualTo(cents(5000));
        assertThat(balance.lowestOn()).isEqualTo(date("2026-10-03"));
        assertThat(balance.ending()).isEqualTo(cents(105000));
    }

    @Test
    void theLowestPointIsJudgedAtTheEndOfEachDay() {
        // The bill is listed before the paycheck that covers it, both on Oct 1.
        List<Movement> movements = List.of(move("2026-10-01", -120000), move("2026-10-01", 100000));

        PeriodBalance balance = BalanceProjector.project(List.of(anchor("2026-09-30", 50000)), movements, PERIODS).getFirst();

        assertThat(balance.lowest()).isEqualTo(cents(30000));
        assertThat(balance.lowestOn()).isEqualTo(date("2026-10-01"));
    }

    @Test
    void whenNothingMovesTheLowestPointIsTheStartingBalance() {
        PeriodBalance balance = BalanceProjector.project(List.of(anchor("2026-09-30", 50000)), List.of(), PERIODS).getFirst();

        assertThat(balance.lowest()).isEqualTo(cents(50000));
        assertThat(balance.lowestOn()).isEqualTo(date("2026-10-01"));
    }

    @Test
    void aBalanceEnteredDuringAPeriodAdjustsItButTheChainHolds() {
        List<Movement> movements = List.of(
                move("2026-10-01", 248620),
                // Already in the number the bank showed on Oct 6.
                move("2026-10-02", -20000),
                // Dated the day the balance was entered: still to come.
                move("2026-10-06", -1000),
                move("2026-10-10", -5000));

        List<PeriodBalance> balances = BalanceProjector.project(
                List.of(anchor("2026-09-30", 121438), anchor("2026-10-06", 90000)), movements, PERIODS);

        // The period starts where the one before ended and counts everything in it; Oct 6's balance shows as the
        // difference from the 3,500.58 expected that morning (Rachel, 2026-10-21).
        PeriodBalance first = balances.getFirst();
        assertThat(first.entered()).isFalse();
        assertThat(first.starting()).isEqualTo(cents(121438));
        assertThat(first.startingOn()).isEqualTo(date("2026-10-01"));
        assertThat(first.in()).isEqualTo(cents(248620));
        assertThat(first.out()).isEqualTo(cents(26000));
        assertThat(first.adjusted()).isEqualTo(cents(-260058));
        assertThat(first.adjustments()).containsExactly(
                new PeriodBalance.Adjustment(date("2026-10-06"), cents(90000), cents(-260058), false));
        assertThat(first.ending()).isEqualTo(cents(84000));
        assertThat(first.lowest()).isEqualTo(cents(84000));
        assertThat(balances.get(1).starting()).isEqualTo(first.ending());
        assertThat(balances.get(1).entered()).isFalse();
    }

    @Test
    void aBalanceEnteredOnAPeriodsFirstDayIsAnAdjustmentToo() {
        List<PeriodBalance> balances = BalanceProjector.project(
                List.of(anchor("2026-09-20", 100000), anchor("2026-10-15", 70000)), List.of(move("2026-10-10", -20000)), PERIODS);

        PeriodBalance second = balances.get(1);
        assertThat(second.starting()).isEqualTo(balances.getFirst().ending());
        assertThat(second.startingOn()).isEqualTo(date("2026-10-15"));
        assertThat(second.adjusted()).isEqualTo(cents(-10000));
        assertThat(second.ending()).isEqualTo(cents(70000));
    }

    @Test
    void somethingStillUnpaidIsNotInTheEnteredBalanceSoItStillComesOut() {
        List<Movement> movements = List.of(
                new Movement(date("2026-10-03"), cents(-14000), true),
                // Paid by hand and marked paid before the balance was entered.
                new Movement(date("2026-10-04"), cents(-4600), false));

        PeriodBalance balance = BalanceProjector.project(List.of(anchor("2026-10-06", 90000)), movements, PERIODS).getFirst();

        assertThat(balance.out()).isEqualTo(cents(14000));
        assertThat(balance.ending()).isEqualTo(cents(76000));
        assertThat(balance.lowestOn()).isEqualTo(date("2026-10-06"));
    }

    @Test
    void aBalanceEnteredOnPaydayComesBeforeThatDaysPaycheck() {
        PeriodBalance balance = BalanceProjector
                .project(List.of(anchor("2026-10-01", 121438)), List.of(move("2026-10-01", 248620)), PERIODS)
                .getFirst();

        assertThat(balance.entered()).isTrue();
        assertThat(balance.startingOn()).isEqualTo(date("2026-10-01"));
        assertThat(balance.in()).isEqualTo(cents(248620));
        assertThat(balance.ending()).isEqualTo(cents(370058));
    }

    @Test
    void anUnpaidItemFromAnEarlierPeriodLandsOnTheDayOfTheNewestEnteredBalance() {
        List<Movement> movements = List.of(new Movement(date("2026-10-10"), cents(-10000), true));

        List<PeriodBalance> balances = BalanceProjector.project(List.of(anchor("2026-10-16", 50000)), movements, PERIODS);

        // Oct 1–14 ended before any balance was known, so it is skipped over here: see the test below.
        PeriodBalance entered = balances.get(1);
        assertThat(entered.period().start()).isEqualTo(date("2026-10-15"));
        assertThat(entered.out()).isEqualTo(cents(10000));
        assertThat(entered.ending()).isEqualTo(cents(40000));
    }

    @Test
    void aPeriodBeforeTheFirstBalanceShowsThatBalanceWithNothingCountedYet() {
        List<Movement> movements = List.of(move("2026-10-05", 248620), move("2026-10-08", -10000));

        List<PeriodBalance> balances = BalanceProjector.project(List.of(anchor("2026-10-16", 50000)), movements, PERIODS);

        // The account does not go missing from the earlier period: it stands at what was entered
        // later, dated then, with nothing added up before it.
        assertThat(balances).hasSize(PERIODS.size());
        PeriodBalance before = balances.getFirst();
        assertThat(before.period().start()).isEqualTo(date("2026-10-01"));
        assertThat(before.entered()).isFalse();
        assertThat(before.startingOn()).isEqualTo(date("2026-10-16"));
        assertThat(before.starting()).isEqualTo(cents(50000));
        assertThat(before.in()).isEqualTo(cents(0));
        assertThat(before.out()).isEqualTo(cents(0));
        assertThat(before.ending()).isEqualTo(cents(50000));
    }

    @Test
    void anUnpaidItemDatedAfterTheEnteredBalanceKeepsItsOwnDate() {
        List<Movement> movements = List.of(new Movement(date("2026-10-20"), cents(-10000), true));

        List<PeriodBalance> balances = BalanceProjector.project(List.of(anchor("2026-09-30", 50000)), movements, PERIODS);

        assertThat(balances.get(0).ending()).isEqualTo(cents(50000));
        assertThat(balances.get(1).lowestOn()).isEqualTo(date("2026-10-20"));
        assertThat(balances.get(1).ending()).isEqualTo(cents(40000));
    }

    @Test
    void aBalanceCanGoBelowZero() {
        PeriodBalance balance = BalanceProjector
                .project(List.of(anchor("2026-09-30", 10000)), List.of(move("2026-10-02", -25000)), PERIODS)
                .getFirst();

        assertThat(balance.ending()).isEqualTo(cents(-15000));
        assertThat(balance.lowest().isNegative()).isTrue();
    }

    // ---------------------------------------------------------------- balances the day ended with (Rachel, 2026-10-30)

    @Test
    void aBalanceTheDayEndedWithAlreadyHoldsThatDaysPaycheckAndPayments() {
        List<Movement> movements = List.of(move("2026-10-01", 348227), move("2026-10-01", -8700), move("2026-10-03", -5000));

        List<PeriodBalance> balances = BalanceProjector.project(
                List.of(anchor("2026-09-30", 422489), endOfDay("2026-10-01", 762016)), movements, PERIODS);

        // The period still starts as Oct 1 begins; the balance is set against starting + paycheck − that day's payment.
        PeriodBalance first = balances.getFirst();
        assertThat(first.starting()).isEqualTo(cents(422489));
        assertThat(first.in()).isEqualTo(cents(348227));
        assertThat(first.out()).isEqualTo(cents(13700));
        assertThat(first.adjustments()).containsExactly(
                new PeriodBalance.Adjustment(date("2026-10-01"), cents(762016), cents(762016 - (422489 + 348227 - 8700)), true));
        assertThat(first.ending()).isEqualTo(cents(762016 - 5000));
    }

    @Test
    void aBalanceThePeriodsLastDayEndedWithStaysInItAndTheNextStartsFromIt() {
        List<Movement> movements = List.of(move("2026-10-14", -2500), move("2026-10-15", 248620));

        List<PeriodBalance> balances = BalanceProjector.project(
                List.of(anchor("2026-09-30", 100000), endOfDay("2026-10-14", 90000)), movements, PERIODS);

        assertThat(balances.get(0).adjustments()).containsExactly(
                new PeriodBalance.Adjustment(date("2026-10-14"), cents(90000), cents(90000 - (100000 - 2500)), true));
        assertThat(balances.get(0).ending()).isEqualTo(cents(90000));
        assertThat(balances.get(1).starting()).isEqualTo(cents(90000));
        assertThat(balances.get(1).adjustments()).isEmpty();
        assertThat(balances.get(1).ending()).isEqualTo(cents(90000 + 248620));
    }

    @Test
    void somethingPaidByHandAndNotTickedOffThatDayStillComesAfterIt() {
        List<Movement> movements = List.of(move("2026-10-01", 248620), new Movement(date("2026-10-01"), cents(-12000), true));

        List<PeriodBalance> balances = BalanceProjector.project(
                List.of(anchor("2026-09-30", 100000), endOfDay("2026-10-01", 348620)), movements, PERIODS);

        // Not yet gone when the day ended, so the balance matches; the bill leaves after it.
        PeriodBalance first = balances.getFirst();
        assertThat(first.adjustments().getFirst().difference()).isEqualTo(Money.ZERO);
        assertThat(first.ending()).isEqualTo(cents(348620 - 12000));
    }

    @Test
    void theFirstBalanceEverEnteredAtTheEndOfItsDayAlreadyHoldsThatDay() {
        List<Movement> movements = List.of(move("2026-10-05", 248620), move("2026-10-06", -1000));

        PeriodBalance first = BalanceProjector.project(List.of(endOfDay("2026-10-05", 300000)), movements, PERIODS).getFirst();

        assertThat(first.entered()).isTrue();
        assertThat(first.starting()).isEqualTo(cents(300000));
        assertThat(first.startingOn()).isEqualTo(date("2026-10-05"));
        assertThat(first.in()).isEqualTo(Money.ZERO);
        assertThat(first.out()).isEqualTo(cents(1000));
        assertThat(first.ending()).isEqualTo(cents(299000));
    }

    @Test
    void aBalanceEnteredBeforeTheChangeStillStandsForTheStartOfItsDay() {
        List<Movement> movements = List.of(move("2026-10-01", 248620));

        PeriodBalance first = BalanceProjector.project(
                List.of(anchor("2026-09-30", 100000), anchor("2026-10-01", 100000)), movements, PERIODS).getFirst();

        // The paycheck that day comes after it.
        assertThat(first.adjustments()).containsExactly(new PeriodBalance.Adjustment(date("2026-10-01"), cents(100000), Money.ZERO, false));
        assertThat(first.ending()).isEqualTo(cents(348620));
    }

    private static BalanceAnchor endOfDay(String day, long cents) {
        return new BalanceAnchor(date(day), cents(cents), true);
    }

    private static Movement move(String day, long cents) {
        return new Movement(date(day), cents(cents), false);
    }

    private static BalanceAnchor anchor(String day, long cents) {
        return new BalanceAnchor(date(day), cents(cents));
    }

    private static Money cents(long cents) {
        return Money.ofCents(cents);
    }

    private static LocalDate date(String iso) {
        return LocalDate.parse(iso);
    }

    private static PayPeriod period(String start, String end) {
        return new PayPeriod(date(start), date(end), date(start));
    }
}
