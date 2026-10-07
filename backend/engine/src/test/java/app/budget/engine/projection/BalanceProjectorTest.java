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
    void aBalanceEnteredDuringAPeriodReplacesTheProjectionFromThatDay() {
        List<Movement> movements = List.of(
                move("2026-10-01", 248620),
                // Already in the number the bank showed on Oct 6.
                move("2026-10-02", -20000),
                // Dated the day the balance was entered: still to come.
                move("2026-10-06", -1000),
                move("2026-10-10", -5000));

        List<PeriodBalance> balances = BalanceProjector.project(
                List.of(anchor("2026-09-30", 121438), anchor("2026-10-06", 90000)), movements, PERIODS);

        PeriodBalance first = balances.getFirst();
        assertThat(first.entered()).isTrue();
        assertThat(first.starting()).isEqualTo(cents(90000));
        assertThat(first.startingOn()).isEqualTo(date("2026-10-06"));
        assertThat(first.in()).isEqualTo(Money.ZERO);
        assertThat(first.out()).isEqualTo(cents(6000));
        assertThat(first.ending()).isEqualTo(cents(84000));
        assertThat(balances.get(1).starting()).isEqualTo(cents(84000));
        assertThat(balances.get(1).entered()).isFalse();
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

        // Oct 1–14 ended before any balance was known.
        assertThat(balances).extracting(balance -> balance.period().start()).containsExactly(date("2026-10-15"), date("2026-11-01"));
        assertThat(balances.getFirst().out()).isEqualTo(cents(10000));
        assertThat(balances.getFirst().ending()).isEqualTo(cents(40000));
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
