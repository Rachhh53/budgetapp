package app.budget.engine.items;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.Money;
import app.budget.engine.items.AnnualAmount.PerYear;
import app.budget.engine.schedule.Frequency;
import app.budget.engine.schedule.ScheduleRule;
import app.budget.engine.schedule.WeekendRule;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnnualAmountTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 1);

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
