package app.budget.engine.cards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class StatementCycleTest {

    private static LocalDate date(String text) {
        return LocalDate.parse(text);
    }

    @Test
    void aStatementClosesOnItsDayEveryMonth() {
        assertThat(StatementCycle.closeDates(20, date("2026-10-01"), date("2026-12-31")))
                .containsExactly(date("2026-10-20"), date("2026-11-20"), date("2026-12-20"));
    }

    @Test
    void onlyClosingDatesInsideTheDatesAskedForAreListed() {
        assertThat(StatementCycle.closeDates(20, date("2026-10-21"), date("2026-11-19"))).isEmpty();
        assertThat(StatementCycle.closeDates(20, date("2026-10-20"), date("2026-10-20"))).containsExactly(date("2026-10-20"));
    }

    @Test
    void aMonthWithoutTheDayClosesOnItsLastDay() {
        assertThat(StatementCycle.closeDates(31, date("2027-02-01"), date("2027-04-30")))
                .containsExactly(date("2027-02-28"), date("2027-03-31"), date("2027-04-30"));
    }

    @Test
    void theLastAndNextClosingDatesAreFoundFromAnyDay() {
        assertThat(StatementCycle.lastCloseOnOrBefore(20, date("2026-10-04"))).isEqualTo(date("2026-09-20"));
        assertThat(StatementCycle.lastCloseOnOrBefore(20, date("2026-10-20"))).isEqualTo(date("2026-10-20"));
        assertThat(StatementCycle.nextCloseAfter(20, date("2026-10-04"))).isEqualTo(date("2026-10-20"));
        assertThat(StatementCycle.nextCloseAfter(20, date("2026-10-20"))).isEqualTo(date("2026-11-20"));
    }

    @Test
    void aStatementThatClosedADayOrTwoOffBelongsToTheNearestClosingDate() {
        // The 20th fell on a Sunday, so the real statement closed on the 18th.
        assertThat(StatementCycle.nearestClose(20, date("2026-09-18"))).isEqualTo(date("2026-09-20"));
        assertThat(StatementCycle.nearestClose(20, date("2026-09-21"))).isEqualTo(date("2026-09-20"));
        assertThat(StatementCycle.nearestClose(20, date("2026-09-20"))).isEqualTo(date("2026-09-20"));
    }

    @Test
    void aStatementIsDueOnTheFirstDueDayAfterItCloses() {
        // Closes the 20th, due the 15th: the 15th of the month after.
        assertThat(StatementCycle.dueDate(date("2026-09-20"), 15)).isEqualTo(date("2026-10-15"));
        // Closes the 5th, due the 28th: later the same month.
        assertThat(StatementCycle.dueDate(date("2026-09-05"), 28)).isEqualTo(date("2026-09-28"));
        // Never the closing day itself.
        assertThat(StatementCycle.dueDate(date("2026-09-20"), 20)).isEqualTo(date("2026-10-20"));
    }

    @Test
    void aDayHasToBeADayOfTheMonth() {
        assertThatIllegalArgumentException().isThrownBy(() -> StatementCycle.closeDates(0, date("2026-10-01"), date("2026-10-31")));
        assertThatIllegalArgumentException().isThrownBy(() -> StatementCycle.dueDate(date("2026-10-01"), 32));
    }
}
