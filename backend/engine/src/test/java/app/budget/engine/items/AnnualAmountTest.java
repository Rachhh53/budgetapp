package app.budget.engine.items;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.Money;
import app.budget.engine.items.AnnualAmount.PerYear;
import app.budget.engine.schedule.Frequency;
import app.budget.engine.schedule.ScheduleRule;
import app.budget.engine.schedule.WeekendRule;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnnualAmountTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 1);

    @Test
    void aYearlyTotalIsSpreadOverTheSeasonsDatesAlone() {
        // $900 of plowing a winter, weekly November through March: 52 × 5 / 12 ≈ 21.67 times.
        ScheduleRule plowing = ScheduleRule.weekly(START, WeekendRule.NONE)
                .inMonths(EnumSet.of(Month.NOVEMBER, Month.DECEMBER, Month.JANUARY, Month.FEBRUARY, Month.MARCH));

        PerYear perYear = AnnualAmount.perYear(plowing, null, 2026);

        assertThat(AnnualAmount.perOccurrence(Money.ofCents(90000), perYear)).isEqualTo(Money.ofCents(4154));
        assertThat(AnnualAmount.yearly(Money.ofCents(4154), perYear)).isEqualTo(Money.ofCents(90003));
    }

    @Test
    void everyTenWeeksIsAFifthOfTheWeeklyCount() {
        ScheduleRule everyTenWeeks = new ScheduleRule(Frequency.WEEKLY, 10, null, null, null, START, null, WeekendRule.NONE, List.of());

        assertThat(AnnualAmount.perYear(everyTenWeeks, null, 2026)).isEqualTo(new PerYear(52, 10));
        // 52 ÷ 10 = 5.2 packs of $1,275.00 a year.
        assertThat(AnnualAmount.yearly(Money.ofCents(127500), AnnualAmount.perYear(everyTenWeeks, null, 2026))).isEqualTo(Money.ofCents(663000));
    }

    @Test
    void twiceAWeekIs104TimesAYear() {
        ScheduleRule twice = ScheduleRule.weekly(START, WeekendRule.NONE).onWeekdays(EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.THURSDAY));

        assertThat(AnnualAmount.yearly(Money.ofCents(6375), AnnualAmount.perYear(twice, null, 2026))).isEqualTo(Money.ofCents(663000));
    }

    @Test
    void aSeasonalMonthlyBudgetCountsOnlyItsMonths() {
        ScheduleRule summer = ScheduleRule.monthly(1, START, WeekendRule.NONE).inMonths(EnumSet.of(Month.JUNE, Month.JULY, Month.AUGUST));

        assertThat(AnnualAmount.perOccurrence(Money.ofCents(60000), AnnualAmount.perYear(summer, null, 2026))).isEqualTo(Money.ofCents(20000));
    }

    @Test
    void aYearlyBillIsItsWholeAnnualAmount() {
        ScheduleRule yearly = new ScheduleRule(Frequency.YEARLY, 1, 12, null, 3, START, null, WeekendRule.NONE, List.of());

        PerYear perYear = AnnualAmount.perYear(yearly, null, 2026);

        assertThat(AnnualAmount.perOccurrence(Money.ofCents(120000), perYear)).isEqualTo(Money.ofCents(120000));
    }

    @Test
    void anAnnualAmountPaidMonthlyIsSplitInTwelveAndRoundedToTheCent() {
        PerYear perYear = AnnualAmount.perYear(ScheduleRule.monthly(5, START, WeekendRule.NONE), null, 2026);

        assertThat(AnnualAmount.perOccurrence(Money.ofCents(100000), perYear)).isEqualTo(Money.ofCents(8333));
        assertThat(AnnualAmount.perOccurrence(Money.ofCents(120000), perYear)).isEqualTo(Money.ofCents(10000));
    }

    @Test
    void aPerOccurrenceAmountAddsUpOverTheYear() {
        assertThat(AnnualAmount.yearly(Money.ofCents(5000), AnnualAmount.perYear(ScheduleRule.biweekly(START, WeekendRule.NONE), null, 2026)))
                .isEqualTo(Money.ofCents(130000));
        assertThat(AnnualAmount.yearly(Money.ofCents(1000), AnnualAmount.perYear(ScheduleRule.weekly(START, WeekendRule.NONE), null, 2026)))
                .isEqualTo(Money.ofCents(52000));
    }

    @Test
    void everyFewMonthsCountsAsAFractionOfAYear() {
        ScheduleRule quarterly = new ScheduleRule(Frequency.EVERY_N_MONTHS, 3, 10, null, null, START, null, WeekendRule.NONE, List.of());
        ScheduleRule everyFive = new ScheduleRule(Frequency.EVERY_N_MONTHS, 5, 10, null, null, START, null, WeekendRule.NONE, List.of());

        assertThat(AnnualAmount.perOccurrence(Money.ofCents(120000), AnnualAmount.perYear(quarterly, null, 2026)))
                .isEqualTo(Money.ofCents(30000));
        // 12 times in 5 years: $100 each is $240 a year.
        assertThat(AnnualAmount.yearly(Money.ofCents(10000), AnnualAmount.perYear(everyFive, null, 2026)))
                .isEqualTo(Money.ofCents(24000));
    }

    @Test
    void everyPayPeriodFollowsHowOftenThePrimaryPaycheckComes() {
        ScheduleRule everyPayPeriod =
                new ScheduleRule(Frequency.EVERY_PAY_PERIOD, 1, null, null, null, START, null, WeekendRule.NONE, List.of());
        ScheduleRule semiMonthlyPay = ScheduleRule.semiMonthly(1, 15, START, WeekendRule.NONE);

        assertThat(AnnualAmount.perYear(everyPayPeriod, semiMonthlyPay, 2026)).isEqualTo(new PerYear(24, 1));
        assertThat(AnnualAmount.yearly(Money.ofCents(30000), AnnualAmount.perYear(everyPayPeriod, semiMonthlyPay, 2026)))
                .isEqualTo(Money.ofCents(720000));
        // With no paycheck set up yet there is nothing to follow.
        assertThat(AnnualAmount.perYear(everyPayPeriod, null, 2026)).isEqualTo(PerYear.ONCE);
    }

    @Test
    void handPickedDatesAreCountedInTheYearAsked() {
        ScheduleRule manual = ScheduleRule.manual(
                List.of(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 9, 1), LocalDate.of(2027, 3, 1)), WeekendRule.NONE);

        assertThat(AnnualAmount.perYear(manual, null, 2026)).isEqualTo(new PerYear(2, 1));
        assertThat(AnnualAmount.perYear(manual, null, 2028)).isEqualTo(PerYear.ONCE);
    }
}
