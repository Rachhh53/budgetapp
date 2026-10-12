package app.budget.engine.reports;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.Money;
import app.budget.engine.reports.ProjectedVsActual.Group;
import app.budget.engine.reports.ProjectedVsActual.GroupBy;
import app.budget.engine.reports.ProjectedVsActual.Line;
import app.budget.engine.reports.ProjectedVsActual.Result;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What was projected beside what it turned out to be (spec §1, §5.10). */
class ProjectedVsActualTest {

    private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);
    private static final long UTILITIES = 10;
    private static final long FOOD = 11;

    private static Line line(String date, long projected, long actual, Long categoryId, String itemKey) {
        return new Line(LocalDate.parse(date), Money.ofCents(projected), Money.ofCents(actual), categoryId, itemKey);
    }

    private static Group group(String key, int count, int changed, long projected, long actual) {
        return new Group(key, count, changed, Money.ofCents(projected), Money.ofCents(actual));
    }

    private static final List<Line> LINES = List.of(
            line("2026-01-16", 14_000, 15_250, UTILITIES, "item:1"),
            line("2026-02-16", 14_000, 14_000, UTILITIES, "item:1"),
            line("2026-02-01", 30_000, 27_500, FOOD, "item:2"),
            line("2026-03-01", 30_000, 30_000, null, "item:3"));

    @Test
    void totalsWhatWasProjectedWhatItWasAndHowManyChanged() {
        Result result = ProjectedVsActual.summarize(LINES, GroupBy.MONTH, JAN_1, LocalDate.of(2026, 3, 31));

        assertThat(result.count()).isEqualTo(4);
        assertThat(result.changed()).isEqualTo(2);
        assertThat(result.projected()).isEqualTo(Money.ofCents(88_000));
        assertThat(result.actual()).isEqualTo(Money.ofCents(86_750));
        assertThat(result.difference()).isEqualTo(Money.ofCents(-1_250));
    }

    @Test
    void byMonthListsEveryMonthAskedForInOrder() {
        Result result = ProjectedVsActual.summarize(LINES, GroupBy.MONTH, JAN_1, LocalDate.of(2026, 4, 30));

        assertThat(result.groups()).containsExactly(
                group("2026-01", 1, 1, 14_000, 15_250),
                group("2026-02", 2, 1, 44_000, 41_500),
                group("2026-03", 1, 0, 30_000, 30_000),
                group("2026-04", 0, 0, 0, 0));
    }

    @Test
    void byYearListsEveryYearAskedFor() {
        Result result = ProjectedVsActual.summarize(LINES, GroupBy.YEAR, LocalDate.of(2025, 6, 1), LocalDate.of(2026, 12, 31));

        assertThat(result.groups()).containsExactly(group("2025", 0, 0, 0, 0), group("2026", 4, 2, 88_000, 86_750));
    }

    @Test
    void byCategoryPutsWhatMovedFurthestFirstWhicheverWayItMoved() {
        Result result = ProjectedVsActual.summarize(LINES, GroupBy.CATEGORY, JAN_1, LocalDate.of(2026, 12, 31));

        assertThat(result.groups()).containsExactly(
                group(String.valueOf(FOOD), 1, 1, 30_000, 27_500),
                group(String.valueOf(UTILITIES), 2, 1, 28_000, 29_250),
                group(null, 1, 0, 30_000, 30_000));
        assertThat(result.groups().getFirst().difference()).isEqualTo(Money.ofCents(-2_500));
    }

    @Test
    void byItemUsesTheCallersKey() {
        Result result = ProjectedVsActual.summarize(LINES, GroupBy.ITEM, JAN_1, LocalDate.of(2026, 12, 31));

        assertThat(result.groups()).extracting(Group::key).containsExactly("item:2", "item:1", "item:3");
    }

    @Test
    void noLinesIsAllZeros() {
        Result result = ProjectedVsActual.summarize(List.of(), GroupBy.CATEGORY, JAN_1, JAN_1);

        assertThat(result.groups()).isEmpty();
        assertThat(result.count()).isZero();
        assertThat(result.difference()).isEqualTo(Money.ZERO);
    }
}
