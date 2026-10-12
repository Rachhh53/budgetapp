package app.budget.engine.reports;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.Money;
import app.budget.engine.reports.IncomeReport.Group;
import app.budget.engine.reports.IncomeReport.Kind;
import app.budget.engine.reports.IncomeReport.Line;
import app.budget.engine.reports.IncomeReport.Result;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Adding up an income report (spec §4.11). */
class IncomeReportTest {

    private static Money cents(long cents) {
        return Money.ofCents(cents);
    }

    private static Line paycheck(String key, long net, Long gross, Long taxes, Long deductions) {
        return new Line(Kind.PAYCHECK, key, cents(net),
                gross == null ? null : cents(gross), taxes == null ? null : cents(taxes), deductions == null ? null : cents(deductions));
    }

    private static Line other(Kind kind, String key, long amount) {
        return new Line(kind, key, cents(amount), null, null, null);
    }

    @Test
    void addsUpEachGroupAndTheWholeKeepingTheMatchOutOfWhatWasReceived() {
        Result result = IncomeReport.summarize(List.of(
                paycheck("2026-10", 248_620, 360_000L, 81_510L, 29_870L),
                other(Kind.MATCH, "2026-10", 7_500),
                paycheck("2026-10", 248_620, 360_000L, 81_510L, 29_870L),
                other(Kind.ONE_OFF, "2026-09", 50_000),
                other(Kind.INTEREST, "2026-09", 1_234)), true);

        // Months in order, whatever order the lines came in.
        assertThat(result.groups()).extracting(Group::key).containsExactly("2026-09", "2026-10");
        Group october = result.groups().get(1);
        assertThat(october.count()).isEqualTo(3);
        assertThat(october.sums().gross()).isEqualTo(cents(720_000));
        assertThat(october.sums().taxes()).isEqualTo(cents(163_020));
        assertThat(october.sums().deductions()).isEqualTo(cents(59_740));
        assertThat(october.sums().takeHome()).isEqualTo(cents(497_240));
        assertThat(october.sums().match()).isEqualTo(cents(7_500));
        assertThat(october.sums().received()).isEqualTo(cents(497_240));

        assertThat(result.count()).isEqualTo(5);
        assertThat(result.total().oneOffs()).isEqualTo(cents(50_000));
        assertThat(result.total().interest()).isEqualTo(cents(1_234));
        assertThat(result.total().received()).isEqualTo(cents(497_240 + 50_000 + 1_234));
        assertThat(result.paychecksWithoutGross()).isZero();
    }

    @Test
    void countsThePaychecksWhoseGrossIsNotKnownAndLeavesThemOutOfTheGross() {
        Result result = IncomeReport.summarize(List.of(
                paycheck("1", 248_620, 360_000L, 81_510L, 29_870L),
                paycheck("1", 248_620, null, null, null)), false);

        assertThat(result.total().gross()).isEqualTo(cents(360_000));
        assertThat(result.total().takeHome()).isEqualTo(cents(497_240));
        assertThat(result.paychecksWithoutGross()).isEqualTo(1);
    }

    @Test
    void putsTheGroupThatBroughtInMostFirst() {
        Result result = IncomeReport.summarize(List.of(
                other(Kind.ONE_OFF, null, 900_000),
                paycheck("2", 31_840, null, null, null),
                paycheck("1", 248_620, null, null, null)), false);

        assertThat(result.groups()).extracting(Group::key).containsExactly(null, "1", "2");
        assertThat(IncomeReport.summarize(List.of(), true).groups()).isEmpty();
    }
}
