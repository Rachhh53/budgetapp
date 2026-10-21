package app.budget.engine.items;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.budget.engine.Money;
import app.budget.engine.items.UsageBill.Per;
import app.budget.engine.items.UsageBill.Usage;
import app.budget.engine.schedule.ScheduleRule;
import app.budget.engine.schedule.WeekendRule;
import java.time.LocalDate;
import java.time.Month;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class UsageBillTest {

    private static final Money PLOW = Money.ofCents(4500);

    /** Plowing billed on the 10th, possible December through March. */
    private static final ScheduleRule WINTER = ScheduleRule.monthly(10, LocalDate.of(2026, 1, 1), WeekendRule.NONE)
            .inMonths(EnumSet.of(Month.DECEMBER, Month.JANUARY, Month.FEBRUARY, Month.MARCH));

    @Test
    void aMonthsBillCountsAMonthOfUses() {
        // Jan 10 – Feb 9 inside the season, 4 plows a month: 22/31 of January's and 9/28 of February's.
        Money bill = UsageBill.between(PLOW, new Usage(400, Per.MONTH), WINTER, LocalDate.of(2026, 1, 9), LocalDate.of(2026, 2, 9));

        // 4 × (22/31 + 9/28) = 4.124… plows at $45.00.
        assertThat(bill).isEqualTo(Money.ofCents(18560));
    }

    @Test
    void aWeeksUsesAreSpreadOverItsDays() {
        // Twice a week for the 28 days of February is 8 plows.
        Money bill = UsageBill.between(PLOW, new Usage(200, Per.WEEK), WINTER, LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28));

        assertThat(bill).isEqualTo(Money.ofCents(36000));
    }

    @Test
    void onlyDaysInTheSeasonCount() {
        // The April 10 bill covers March 11 – April 10: only March's 21 days can have plows.
        Money april = UsageBill.between(PLOW, new Usage(310, Per.MONTH), WINTER, LocalDate.of(2026, 3, 10), LocalDate.of(2026, 4, 10));
        Money may = UsageBill.between(PLOW, new Usage(310, Per.MONTH), WINTER, LocalDate.of(2026, 4, 10), LocalDate.of(2026, 5, 10));

        // 3.1 × 21/31 = 2.1 plows.
        assertThat(april).isEqualTo(Money.ofCents(9450));
        assertThat(may).isEqualTo(Money.ZERO);
    }

    @Test
    void aBillCoversEverythingSinceTheLastOne() {
        assertThat(UsageBill.coversAfter(WINTER, LocalDate.of(2026, 3, 10))).isEqualTo(LocalDate.of(2026, 2, 10));
        // Nothing was used before the schedule began.
        assertThat(UsageBill.coversAfter(WINTER, LocalDate.of(2026, 1, 10))).isEqualTo(LocalDate.of(2025, 12, 31));
        // The 31st falls back to a shorter month's last day.
        ScheduleRule lastDay = ScheduleRule.monthly(31, LocalDate.of(2025, 1, 1), WeekendRule.NONE);
        assertThat(UsageBill.coversAfter(lastDay, LocalDate.of(2026, 3, 31))).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void aTypicalMonthAndAYear() {
        // Twice a week is 52 × 2 ÷ 12 ≈ 8.67 plows a month, over the season's 4 months.
        assertThat(UsageBill.typicalMonth(PLOW, new Usage(200, Per.WEEK))).isEqualTo(Money.ofCents(39000));
        assertThat(UsageBill.yearly(PLOW, new Usage(200, Per.WEEK), 4)).isEqualTo(Money.ofCents(156000));
        assertThat(UsageBill.yearly(PLOW, new Usage(400, Per.MONTH), 4)).isEqualTo(Money.ofCents(72000));
    }

    @Test
    void usesMustBeMoreThanNone() {
        assertThatThrownBy(() -> new Usage(0, Per.WEEK)).isInstanceOf(IllegalArgumentException.class);
    }
}
