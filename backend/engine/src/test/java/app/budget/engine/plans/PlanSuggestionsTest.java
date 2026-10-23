package app.budget.engine.plans;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.Money;
import app.budget.engine.items.AnnualAmount.PerYear;
import app.budget.engine.plans.PlanSuggestions.Basis;
import app.budget.engine.plans.PlanSuggestions.History;
import app.budget.engine.plans.PlanSuggestions.Month;
import app.budget.engine.plans.PlanSuggestions.Reason;
import app.budget.engine.plans.PlanSuggestions.Suggestion;
import app.budget.engine.reports.AnnualCosts.Window;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Next year's suggested amounts (spec §5.8). */
class PlanSuggestionsTest {

    private static final LocalDate OCTOBER = LocalDate.of(2026, 10, 12);

    private static Money dollars(long dollars) {
        return Money.ofCents(dollars * 100);
    }

    /** A bill with all its history: $1,710 in the last 12 months, $1,567 the 12 before, $1,600 last calendar year. */
    private static History electric() {
        return new History(dollars(1_710), dollars(1_567), dollars(1_600), dollars(1_680), false);
    }

    @Test
    void planningInOctoberLooksAtTheTwelveMonthsEndingInSeptember() {
        assertThat(PlanSuggestions.rollingWindows(OCTOBER)).containsExactly(
                new Window(LocalDate.of(2024, 10, 1), LocalDate.of(2025, 9, 30)),
                new Window(LocalDate.of(2025, 10, 1), LocalDate.of(2026, 9, 30)));
        assertThat(PlanSuggestions.priorYear(OCTOBER)).isEqualTo(new Window(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)));
    }

    @Test
    void theLastTwelveMonthsAreSuggestedAsTheyWere() {
        Suggestion suggestion = PlanSuggestions.suggest(Basis.LAST_12, electric());

        assertThat(suggestion.amount()).isEqualTo(dollars(1_710));
        assertThat(suggestion.reason()).isEqualTo(Reason.HISTORY);
        // The trend is still told, though it is not applied.
        assertThat(suggestion.trendBp()).isEqualTo(913);
    }

    @Test
    void theTrendMovesTheLastTwelveMonthsOnToTheDollar() {
        Suggestion suggestion = PlanSuggestions.suggest(Basis.LAST_12_TREND, electric());

        // (1,710 − 1,567) ÷ 1,567 = 9.13%; 1,710 × 1.0913 = 1,866.12.
        assertThat(suggestion.trendBp()).isEqualTo(913);
        assertThat(suggestion.amount()).isEqualTo(dollars(1_866));
        assertThat(suggestion.reason()).isEqualTo(Reason.HISTORY);
    }

    @Test
    void aPriceThatHasNotMovedIsSuggestedUnchanged() {
        Suggestion suggestion = PlanSuggestions.suggest(
                Basis.LAST_12_TREND, new History(dollars(1_020), dollars(1_020), dollars(1_020), dollars(1_020), false));

        assertThat(suggestion.trendBp()).isZero();
        assertThat(suggestion.amount()).isEqualTo(dollars(1_020));
    }

    @Test
    void aFallingCostIsSuggestedLowerButNeverBelowNothing() {
        Suggestion lower = PlanSuggestions.suggest(
                Basis.LAST_12_TREND, new History(dollars(900), dollars(1_000), null, dollars(900), false));
        Suggestion collapsed = PlanSuggestions.suggest(
                Basis.LAST_12_TREND, new History(Money.ZERO, dollars(1_000), null, Money.ZERO, false));

        assertThat(lower.trendBp()).isEqualTo(-1_000);
        assertThat(lower.amount()).isEqualTo(dollars(810));
        assertThat(collapsed.amount()).isEqualTo(Money.ZERO);
    }

    @Test
    void withOnlyOneYearBehindItThereIsNoTrendToApply() {
        Suggestion suggestion = PlanSuggestions.suggest(
                Basis.LAST_12_TREND, new History(dollars(18_000), null, null, dollars(18_000), false));

        assertThat(suggestion.trendBp()).isNull();
        assertThat(suggestion.amount()).isEqualTo(dollars(18_000));
        assertThat(suggestion.reason()).isEqualTo(Reason.HISTORY);
    }

    @Test
    void nothingTheYearBeforeMeansNoTrendRatherThanAnInfiniteOne() {
        Suggestion suggestion = PlanSuggestions.suggest(
                Basis.LAST_12_TREND, new History(dollars(600), Money.ZERO, null, dollars(600), false));

        assertThat(suggestion.trendBp()).isNull();
        assertThat(suggestion.amount()).isEqualTo(dollars(600));
    }

    @Test
    void lastCalendarYearIsSuggestedAsItWas() {
        Suggestion suggestion = PlanSuggestions.suggest(Basis.PRIOR_YEAR, electric());

        assertThat(suggestion.amount()).isEqualTo(dollars(1_600));
        assertThat(suggestion.reason()).isEqualTo(Reason.HISTORY);
    }

    @Test
    void withLessThanTwelveMonthsBehindItTodaysAmountOverAYearIsSuggested() {
        // Four months at $85 so far; stretched to twelve it would say the same here, but a few odd months must not decide a year.
        History phone = new History(null, null, null, dollars(1_020), false);

        for (Basis basis : Basis.values()) {
            Suggestion suggestion = PlanSuggestions.suggest(basis, phone);

            assertThat(suggestion.amount()).as(basis.name()).isEqualTo(dollars(1_020));
            assertThat(suggestion.reason()).as(basis.name()).isEqualTo(Reason.NOT_ENOUGH_HISTORY);
            assertThat(suggestion.trendBp()).as(basis.name()).isNull();
        }
    }

    @Test
    void aBillWithTwelveMonthsButNoWholeCalendarYearFallsBackOnlyForThatBasis() {
        History sinceMarchLastYear = new History(dollars(1_200), null, null, dollars(1_260), false);

        assertThat(PlanSuggestions.suggest(Basis.LAST_12, sinceMarchLastYear).amount()).isEqualTo(dollars(1_200));
        Suggestion priorYear = PlanSuggestions.suggest(Basis.PRIOR_YEAR, sinceMarchLastYear);
        assertThat(priorYear.amount()).isEqualTo(dollars(1_260));
        assertThat(priorYear.reason()).isEqualTo(Reason.NOT_ENOUGH_HISTORY);
    }

    @Test
    void aFixedAmountIsSuggestedUnchangedWhateverItsHistory() {
        // A loan payment: what was paid over the year says nothing about what is owed next year.
        History carLoan = new History(dollars(4_700), dollars(4_100), dollars(4_500), dollars(4_944), true);

        for (Basis basis : Basis.values()) {
            Suggestion suggestion = PlanSuggestions.suggest(basis, carLoan);

            assertThat(suggestion.amount()).as(basis.name()).isEqualTo(dollars(4_944));
            assertThat(suggestion.reason()).as(basis.name()).isEqualTo(Reason.FIXED);
            assertThat(suggestion.trendBp()).as(basis.name()).isNull();
        }
    }

    private static final PerYear MONTHLY = new PerYear(12, 1);

    /** {@code count} months of a monthly bill of {@code dollars}. */
    private static List<Month> months(int count, long dollars) {
        return new ArrayList<>(Collections.nCopies(count, new Month(dollars(dollars), 1)));
    }

    /** A monthly bill begun in January, planned in October: no 12 months to look at. */
    private static History begunInJanuary(List<Month> months) {
        return new History(null, null, null, dollars(1_080), false, months, MONTHLY);
    }

    @Test
    void fewerThanTwelveMonthsAreScaledUpToAYear() {
        Suggestion suggestion = PlanSuggestions.suggest(Basis.LAST_12, begunInJanuary(months(9, 100)));

        // $900 in nine months is $100 a month: $1,200 a year, not today's $1,080 and not $900.
        assertThat(suggestion.amount()).isEqualTo(dollars(1_200));
        assertThat(suggestion.reason()).isEqualTo(Reason.SHORT_HISTORY);
        assertThat(suggestion.months()).isEqualTo(9);
    }

    @Test
    void withoutTwentyFourMonthsTheTrendIsTheLaterHalfAgainstTheEarlier() {
        List<Month> months = months(4, 100);
        months.addAll(months(4, 110));

        Suggestion suggestion = PlanSuggestions.suggest(Basis.LAST_12_TREND, begunInJanuary(months));

        // 440 against 400 is 10%; $840 in eight months is $1,260 a year, moved on 10% to $1,386.
        assertThat(suggestion.trendBp()).isEqualTo(1_000);
        assertThat(suggestion.amount()).isEqualTo(dollars(1_386));
        assertThat(suggestion.reason()).isEqualTo(Reason.SHORT_HISTORY);
    }

    @Test
    void fiveMonthsAreScaledButGiveNoTrend() {
        Suggestion suggestion = PlanSuggestions.suggest(Basis.LAST_12_TREND, begunInJanuary(months(5, 90)));

        assertThat(suggestion.trendBp()).isNull();
        assertThat(suggestion.amount()).isEqualTo(dollars(1_080));
        assertThat(suggestion.reason()).isEqualTo(Reason.SHORT_HISTORY);
    }

    @Test
    void oneOrTwoMonthsAreTooFewToScale() {
        Suggestion suggestion = PlanSuggestions.suggest(Basis.LAST_12_TREND, begunInJanuary(months(2, 300)));

        assertThat(suggestion.amount()).isEqualTo(dollars(1_080));
        assertThat(suggestion.reason()).isEqualTo(Reason.NOT_ENOUGH_HISTORY);
        assertThat(suggestion.months()).isNull();
    }

    @Test
    void theLastCalendarYearIsNeverMadeUpFromPartOfAnother() {
        Suggestion suggestion = PlanSuggestions.suggest(Basis.PRIOR_YEAR, begunInJanuary(months(9, 100)));

        assertThat(suggestion.amount()).isEqualTo(dollars(1_080));
        assertThat(suggestion.reason()).isEqualTo(Reason.NOT_ENOUGH_HISTORY);
    }

    @Test
    void aWeeklyBillIsScaledByTheTimesItCameNotByItsMonths() {
        // $25 every Wednesday, April to September 2026: 13 Wednesdays in the first three months, 14 in the last three.
        List<Month> months = List.of(
                new Month(dollars(125), 5), new Month(dollars(100), 4), new Month(dollars(100), 4),
                new Month(dollars(125), 5), new Month(dollars(100), 4), new Month(dollars(125), 5));
        History history = new History(null, null, null, dollars(1_300), false, months, new PerYear(52, 1));

        Suggestion suggestion = PlanSuggestions.suggest(Basis.LAST_12_TREND, history);

        // The same $25 each time: no trend, and $25 × 52, not $675 in six months × 2.
        assertThat(suggestion.trendBp()).isZero();
        assertThat(suggestion.amount()).isEqualTo(dollars(1_300));
    }

    @Test
    void billedByUseWithNoScheduleToSayHowOftenTheMonthsAreScaledAsMonths() {
        History history = new History(null, null, null, dollars(500), false, months(4, 60), null);

        assertThat(PlanSuggestions.suggest(Basis.LAST_12, history).amount()).isEqualTo(dollars(720));
    }

    @Test
    void twelveMonthsButNotTwentyFourGiveATrendFromTheirHalves() {
        // Fifteen months: $100 a month, then $120 for the last seven. Seven against the seven before is 840 against 700.
        List<Month> months = months(8, 100);
        months.addAll(months(7, 120));
        History history = new History(dollars(1_340), null, null, dollars(1_440), false, months, MONTHLY);

        Suggestion suggestion = PlanSuggestions.suggest(Basis.LAST_12_TREND, history);

        assertThat(suggestion.trendBp()).isEqualTo(2_000);
        assertThat(suggestion.amount()).isEqualTo(dollars(1_608));
        assertThat(suggestion.reason()).isEqualTo(Reason.HISTORY);
    }
}
