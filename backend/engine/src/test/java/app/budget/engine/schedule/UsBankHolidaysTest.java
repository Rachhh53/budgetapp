package app.budget.engine.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class UsBankHolidaysTest {

    @Test
    void listsTheElevenHolidaysOf2026InOrder() {
        assertThat(UsBankHolidays.in(2026)).containsExactly(
                holiday("2026-01-01", "New Year's Day"),
                holiday("2026-01-19", "Martin Luther King Jr. Day"),
                holiday("2026-02-16", "Presidents' Day"),
                holiday("2026-05-25", "Memorial Day"),
                holiday("2026-06-19", "Juneteenth"),
                holiday("2026-07-04", "Independence Day"),
                holiday("2026-09-07", "Labor Day"),
                holiday("2026-10-12", "Indigenous Peoples' Day"),
                holiday("2026-11-11", "Veterans Day"),
                holiday("2026-11-26", "Thanksgiving Day"),
                holiday("2026-12-25", "Christmas Day"));
    }

    @Test
    void aSundayHolidayIsObservedOnMonday() {
        // July 4, 2027 is a Sunday.
        assertThat(UsBankHolidays.in(2027)).contains(holiday("2027-07-05", "Independence Day"));
    }

    @Test
    void aSaturdayHolidayIsNotMoved() {
        // Christmas 2027 is a Saturday; banks are open on the Friday.
        assertThat(UsBankHolidays.in(2027)).contains(holiday("2027-12-25", "Christmas Day"));
    }

    private static Holiday holiday(String date, String name) {
        return new Holiday(LocalDate.parse(date), name);
    }
}
