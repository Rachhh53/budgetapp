package app.budget.engine.reports;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.Money;
import app.budget.engine.reports.AnnualCosts.Basis;
import app.budget.engine.reports.AnnualCosts.ByMonth;
import app.budget.engine.reports.AnnualCosts.Line;
import app.budget.engine.reports.AnnualCosts.Result;
import app.budget.engine.reports.AnnualCosts.Window;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Yearly totals, their change and one item month by month (spec §5.8). */
class AnnualCostsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);
    private static final long RENT = 1;
    private static final long ELECTRIC = 2;
    private static final long PHONE = 3;

    private static Line line(long itemId, String date, long cents) {
        return new Line(itemId, LocalDate.parse(date), Money.ofCents(cents));
    }

    private static Window window(String from, String to) {
        return new Window(LocalDate.parse(from), LocalDate.parse(to));
    }

    @Test
    void calendarWindowsAreWholeYearsEndingWithThisOne() {
        assertThat(AnnualCosts.windows(Basis.CALENDAR, TODAY, 3)).containsExactly(
                window("2024-01-01", "2024-12-31"),
                window("2025-01-01", "2025-12-31"),
                window("2026-01-01", "2026-12-31"));
    }

    @Test
    void rollingWindowsEndWithLastMonth() {
        // Planning happens in October: the newest twelve months are Oct 2025 – Sep 2026 (spec §5.8).
        assertThat(AnnualCosts.windows(Basis.ROLLING, TODAY, 2)).containsExactly(
                window("2024-10-01", "2025-09-30"),
                window("2025-10-01", "2026-09-30"));
    }

    @Test
    void rollingWindowsEndOnTheLastDayOfAShortMonth() {
        assertThat(AnnualCosts.windows(Basis.ROLLING, LocalDate.of(2024, 3, 5), 1))
                .containsExactly(window("2023-03-01", "2024-02-29"));
    }

    @Test
    void totalsEachItemPerWindowLargestFirstWithItsChange() {
        List<Window> windows = AnnualCosts.windows(Basis.CALENDAR, TODAY, 2);
        Result result = AnnualCosts.summarize(
                List.of(
                        line(ELECTRIC, "2025-03-10", 159_600),
                        line(ELECTRIC, "2026-03-10", 100_000),
                        line(ELECTRIC, "2026-09-10", 74_200),
                        line(RENT, "2025-06-01", 1_800_000),
                        line(RENT, "2026-06-01", 1_860_000),
                        line(PHONE, "2025-01-05", 102_000),
                        line(PHONE, "2026-01-05", 102_000),
                        // Outside both years: not counted anywhere.
                        line(PHONE, "2023-01-05", 99_999)),
                windows);

        assertThat(result.rows()).extracting(AnnualCosts.Row::itemId).containsExactly(RENT, ELECTRIC, PHONE);
        AnnualCosts.Row electric = result.rows().get(1);
        assertThat(electric.totals()).containsExactly(Money.ofCents(159_600), Money.ofCents(174_200));
        // (1,742 − 1,596) ÷ 1,596 = 9.147…%
        assertThat(electric.changeBp()).isEqualTo(915);
        assertThat(electric.increase()).isEqualTo(Money.ofCents(14_600));
        assertThat(electric.perMonth()).isEqualTo(Money.ofCents(14_517));
        assertThat(result.rows().get(2).changeBp()).isZero();

        assertThat(result.totals()).containsExactly(Money.ofCents(2_061_600), Money.ofCents(2_136_200));
        assertThat(result.changeBp()).isEqualTo(362);
        assertThat(result.biggestIncrease().itemId()).isEqualTo(ELECTRIC);
    }

    @Test
    void anItemThatIsNewHasNoChangeAndIsNeverTheBiggestIncrease() {
        Result result = AnnualCosts.summarize(
                List.of(line(RENT, "2026-06-01", 1_860_000)), AnnualCosts.windows(Basis.CALENDAR, TODAY, 2));

        assertThat(result.rows().getFirst().changeBp()).isNull();
        assertThat(result.changeBp()).isNull();
        assertThat(result.biggestIncrease()).isNull();
    }

    @Test
    void nothingRoseMeansNoBiggestIncrease() {
        Result result = AnnualCosts.summarize(
                List.of(line(RENT, "2025-06-01", 2_000), line(RENT, "2026-06-01", 1_000)),
                AnnualCosts.windows(Basis.CALENDAR, TODAY, 2));

        assertThat(result.rows().getFirst().changeBp()).isEqualTo(-5_000);
        assertThat(result.biggestIncrease()).isNull();
    }

    @Test
    void noLinesGivesZeroTotalsAndNoRows() {
        Result result = AnnualCosts.summarize(List.of(), AnnualCosts.windows(Basis.CALENDAR, TODAY, 3));

        assertThat(result.rows()).isEmpty();
        assertThat(result.totals()).containsOnly(Money.ZERO).hasSize(3);
        assertThat(result.perMonth()).isEqualTo(Money.ZERO);
    }

    @Test
    void perMonthRoundsHalfToTheEvenCent() {
        // $0.06 ÷ 12 = half a cent → 0; $0.18 ÷ 12 = a cent and a half → 2.
        assertThat(AnnualCosts.perMonth(Money.ofCents(6))).isEqualTo(Money.ZERO);
        assertThat(AnnualCosts.perMonth(Money.ofCents(18))).isEqualTo(Money.ofCents(2));
    }

    @Test
    void byMonthSetsThisYearBesideLastAndSaysWhatIsStillToCome() {
        List<Line> lines = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            lines.add(line(ELECTRIC, YearMonth.of(2025, month).atDay(10).toString(), 13_300));
            lines.add(line(ELECTRIC, YearMonth.of(2026, month).atDay(10).toString(), 14_000));
        }
        // July was typed over at more, April at less.
        lines.add(line(ELECTRIC, "2026-07-20", 7_200));
        lines.set(7, line(ELECTRIC, "2026-04-10", 9_800));

        ByMonth year = AnnualCosts.byMonth(lines, TODAY, Money.ofCents(168_000));

        assertThat(year.months()).hasSize(12);
        assertThat(year.months().get(0).before()).isEqualTo(Money.ofCents(13_300));
        assertThat(year.months().get(9).toCome()).isFalse();
        assertThat(year.months().get(10).toCome()).isTrue();
        assertThat(year.highest().month()).isEqualTo(YearMonth.of(2026, 7));
        assertThat(year.highest().amount()).isEqualTo(Money.ofCents(21_200));
        assertThat(year.lowest().month()).isEqualTo(YearMonth.of(2026, 4));
        assertThat(year.total()).isEqualTo(Money.ofCents(171_000));
        assertThat(year.average()).isEqualTo(Money.ofCents(14_250));
        assertThat(year.budget()).isEqualTo(Money.ofCents(14_000));
        assertThat(year.budgetTooLow()).isTrue();
    }

    @Test
    void aYearThatComesToTheBudgetIsNotTooLow() {
        // One bill a year: the month it falls in is both the highest and the lowest.
        ByMonth year = AnnualCosts.byMonth(List.of(line(RENT, "2026-03-12", 120_000)), TODAY, Money.ofCents(120_000));

        assertThat(year.highest().month()).isEqualTo(YearMonth.of(2026, 3));
        assertThat(year.lowest().month()).isEqualTo(YearMonth.of(2026, 3));
        assertThat(year.average()).isEqualTo(Money.ofCents(10_000));
        assertThat(year.budgetTooLow()).isFalse();
    }

    @Test
    void aYearWithNothingInItHasNoHighestOrLowest() {
        ByMonth year = AnnualCosts.byMonth(List.of(), TODAY, Money.ZERO);

        assertThat(year.highest()).isNull();
        assertThat(year.lowest()).isNull();
        assertThat(year.total()).isEqualTo(Money.ZERO);
        assertThat(year.budgetTooLow()).isFalse();
    }
}
