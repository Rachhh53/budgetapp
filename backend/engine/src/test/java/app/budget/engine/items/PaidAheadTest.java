package app.budget.engine.items;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.Money;
import app.budget.engine.items.PaidAhead.Result;
import app.budget.engine.items.PaidAhead.Session;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** $100 paid ahead for a $25 weekly course, on Mondays from Oct 5, 2026. */
class PaidAheadTest {

    private static final List<Session> WEEKLY = List.of(
            session("2026-10-05"), session("2026-10-12"), session("2026-10-19"), session("2026-10-26"),
            session("2026-11-02"), session("2026-11-09"));

    private static Session session(String date) {
        return new Session(LocalDate.parse(date), Money.ofCents(2500));
    }

    @Test
    void eachSessionUsesSomeOfWhatWasPaidAndItRunsOutAfterTheFourth() {
        Result result = PaidAhead.drawDown(Money.ofCents(10000), WEEKLY, LocalDate.parse("2026-10-14"));

        assertThat(result.used()).isEqualTo(Money.ofCents(5000));
        assertThat(result.left()).isEqualTo(Money.ofCents(5000));
        assertThat(result.coversThrough()).isEqualTo(LocalDate.parse("2026-10-26"));
        assertThat(result.firstUncovered()).isEqualTo(LocalDate.parse("2026-11-02"));
    }

    @Test
    void sessionsPastWhatWasPaidLeaveItShort() {
        Result result = PaidAhead.drawDown(Money.ofCents(10000), WEEKLY, LocalDate.parse("2026-11-09"));

        assertThat(result.used()).isEqualTo(Money.ofCents(15000));
        assertThat(result.left()).isEqualTo(Money.ofCents(-5000));
    }

    @Test
    void aSessionOnlyPartCoveredIsNotCovered() {
        Result result = PaidAhead.drawDown(Money.ofCents(6000), WEEKLY, LocalDate.parse("2026-10-01"));

        assertThat(result.coversThrough()).isEqualTo(LocalDate.parse("2026-10-12"));
        assertThat(result.firstUncovered()).isEqualTo(LocalDate.parse("2026-10-19"));
        assertThat(result.left()).isEqualTo(Money.ofCents(6000));
    }

    @Test
    void enoughForEverySessionGivenRunsOutOnNone() {
        Result result = PaidAhead.drawDown(Money.ofCents(20000), WEEKLY, LocalDate.parse("2026-10-01"));

        assertThat(result.coversThrough()).isEqualTo(LocalDate.parse("2026-11-09"));
        assertThat(result.firstUncovered()).isNull();
    }

    /** $1,275 for 20 sessions, twice a week. */
    private static final PaidAhead.Pack PACK = new PaidAhead.Pack(Money.ofCents(127500), 20);

    /** Mondays and Thursdays from Oct 5, 2026: 30 sessions. */
    private static List<LocalDate> twiceAWeek() {
        List<LocalDate> days = new java.util.ArrayList<>();
        for (LocalDate monday = LocalDate.parse("2026-10-05"); days.size() < 30; monday = monday.plusWeeks(1)) {
            days.add(monday);
            days.add(monday.plusDays(3));
        }
        return days;
    }

    @Test
    void aPackCoversExactlyItsSessionsAndEachCostsItsShare() {
        Result result = PaidAhead.drawDown(Money.ofCents(127500), twiceAWeek(), LocalDate.parse("2026-10-15"), PACK);

        // Oct 5, 8, 12 and 15 so far: four of twenty.
        assertThat(result.used()).isEqualTo(Money.ofCents(25500));
        assertThat(result.left()).isEqualTo(Money.ofCents(102000));
        assertThat(result.coversThrough()).isEqualTo(twiceAWeek().get(19));
        assertThat(result.firstUncovered()).isEqualTo(LocalDate.parse("2026-12-14"));
    }

    @Test
    void theNextPackIsDueOnTheFirstSessionNothingCoversThenEveryTwentySessions() {
        List<LocalDate> sessions = new java.util.ArrayList<>(twiceAWeek());

        assertThat(PaidAhead.nextPacks(Money.ofCents(127500), sessions, PACK)).containsExactly(LocalDate.parse("2026-12-14"));
        assertThat(PaidAhead.nextPacks(Money.ZERO, sessions, PACK)).containsExactly(LocalDate.parse("2026-10-05"), LocalDate.parse("2026-12-14"));
        // A skipped session uses nothing, so the next pack is needed one session later.
        sessions.remove(LocalDate.parse("2026-10-08"));
        assertThat(PaidAhead.nextPacks(Money.ofCents(127500), sessions, PACK)).containsExactly(LocalDate.parse("2026-12-17"));
    }

    @Test
    void aSessionPartPaidForIsNotCovered() {
        // $100 is 1.57 sessions at $63.75.
        Result result = PaidAhead.drawDown(Money.ofCents(10000), twiceAWeek(), LocalDate.parse("2026-10-01"), PACK);

        assertThat(result.coversThrough()).isEqualTo(LocalDate.parse("2026-10-05"));
        assertThat(result.firstUncovered()).isEqualTo(LocalDate.parse("2026-10-08"));
    }

    @Test
    void nothingPaidCoversNothing() {
        Result result = PaidAhead.drawDown(Money.ZERO, WEEKLY, LocalDate.parse("2026-10-01"));

        assertThat(result.coversThrough()).isNull();
        assertThat(result.firstUncovered()).isEqualTo(LocalDate.parse("2026-10-05"));
    }
}
