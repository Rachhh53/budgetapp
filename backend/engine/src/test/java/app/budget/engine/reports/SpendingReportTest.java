package app.budget.engine.reports;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.Money;
import app.budget.engine.reports.Reimbursement.Share;
import app.budget.engine.reports.Reimbursement.Status;
import app.budget.engine.reports.SpendingReport.Group;
import app.budget.engine.reports.SpendingReport.GroupBy;
import app.budget.engine.reports.SpendingReport.Line;
import app.budget.engine.reports.SpendingReport.Result;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Grouping and totals for a report (spec §4.11). */
class SpendingReportTest {

    private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate DEC_31 = LocalDate.of(2026, 12, 31);
    private static final long CHECKING = 1;
    private static final long VISA = 2;
    private static final long MEDICAL = 10;
    private static final long PETS = 11;
    private static final long TAG_OUT_OF_POCKET = 20;
    private static final long TAG_TAX = 21;

    private static Line line(String date, long cents, Long categoryId, List<Long> tagIds, String payee, long accountId) {
        return new Line(LocalDate.parse(date), Money.ofCents(cents), categoryId, tagIds, payee, accountId, Share.NONE);
    }

    private static Group group(String key, int count, long cents) {
        return new Group(key, count, Money.ofCents(cents));
    }

    private static final List<Line> LINES = List.of(
            line("2026-01-20", 12_000, MEDICAL, List.of(TAG_OUT_OF_POCKET), "Urgent care", CHECKING),
            line("2026-03-17", 41_000, MEDICAL, List.of(TAG_OUT_OF_POCKET, TAG_TAX), "Brightside Dental", CHECKING),
            line("2026-03-29", 6_450, PETS, List.of(), "Maple Street Vets", VISA),
            line("2026-09-28", 8_500, null, List.of(TAG_TAX), null, VISA));

    @Test
    void byMonthListsEveryMonthAskedForInOrderEvenTheEmptyOnes() {
        Result result = SpendingReport.summarize(LINES, GroupBy.MONTH, JAN_1, LocalDate.of(2026, 4, 30));

        assertThat(result.groups()).containsExactly(
                group("2026-01", 1, 12_000),
                group("2026-02", 0, 0),
                group("2026-03", 2, 47_450),
                group("2026-04", 0, 0),
                // Outside the dates asked for, but handed in: counted, after the months that were asked for.
                group("2026-09", 1, 8_500));
        assertThat(result.count()).isEqualTo(4);
        assertThat(result.total()).isEqualTo(Money.ofCents(67_950));
    }

    @Test
    void byYearListsEveryYearAskedFor() {
        Result result = SpendingReport.summarize(LINES, GroupBy.YEAR, LocalDate.of(2025, 6, 1), DEC_31);

        assertThat(result.groups()).containsExactly(group("2025", 0, 0), group("2026", 4, 67_950));
    }

    @Test
    void byCategoryPutsTheLargestFirstAndGroupsLinesWithNone() {
        Result result = SpendingReport.summarize(LINES, GroupBy.CATEGORY, JAN_1, DEC_31);

        assertThat(result.groups()).containsExactly(
                group(String.valueOf(MEDICAL), 2, 53_000),
                group(null, 1, 8_500),
                group(String.valueOf(PETS), 1, 6_450));
    }

    @Test
    void byTagCountsALineUnderEachOfItsTagsButOnceInTheTotal() {
        Result result = SpendingReport.summarize(LINES, GroupBy.TAG, JAN_1, DEC_31);

        assertThat(result.groups()).containsExactly(
                group(String.valueOf(TAG_OUT_OF_POCKET), 2, 53_000),
                group(String.valueOf(TAG_TAX), 2, 49_500),
                group(null, 1, 6_450));
        assertThat(result.count()).isEqualTo(4);
        assertThat(result.total()).isEqualTo(Money.ofCents(67_950));
    }

    @Test
    void byPayeeAndByAccount() {
        assertThat(SpendingReport.summarize(LINES, GroupBy.PAYEE, JAN_1, DEC_31).groups()).containsExactly(
                group("Brightside Dental", 1, 41_000),
                group("Urgent care", 1, 12_000),
                group(null, 1, 8_500),
                group("Maple Street Vets", 1, 6_450));
        assertThat(SpendingReport.summarize(LINES, GroupBy.ACCOUNT, JAN_1, DEC_31).groups()).containsExactly(
                group(String.valueOf(CHECKING), 2, 53_000),
                group(String.valueOf(VISA), 2, 14_950));
    }

    @Test
    void equalTotalsGoByKeySoTheOrderIsSteady() {
        List<Line> lines = List.of(
                line("2026-01-01", 500, null, List.of(), "Zora's", CHECKING),
                line("2026-01-02", 500, null, List.of(), "Amara's", CHECKING));

        assertThat(SpendingReport.summarize(lines, GroupBy.PAYEE, JAN_1, DEC_31).groups())
                .extracting(Group::key)
                .containsExactly("Amara's", "Zora's");
    }

    @Test
    void addsUpWhatHasComeBackWhatIsWaitedForAndWhatIsNotSentInYet() {
        Money cost = Money.ofCents(10_000);
        List<Line> lines = List.of(
                new Line(JAN_1, cost, null, List.of(), null, CHECKING, Reimbursement.of(Status.REIMBURSED, cost, Money.ZERO)),
                new Line(JAN_1, cost, null, List.of(), null, CHECKING, Reimbursement.of(Status.SUBMITTED, cost, Money.ofCents(4_000))),
                new Line(JAN_1, cost, null, List.of(), null, CHECKING, Reimbursement.of(Status.TO_SUBMIT, cost, Money.ZERO)),
                new Line(JAN_1, cost, null, List.of(), null, CHECKING, Share.NONE));

        Result result = SpendingReport.summarize(lines, GroupBy.MONTH, JAN_1, JAN_1);

        assertThat(result.total()).isEqualTo(Money.ofCents(40_000));
        assertThat(result.reimbursement())
                .isEqualTo(new Share(Money.ofCents(14_000), Money.ofCents(6_000), Money.ofCents(10_000)));
    }

    @Test
    void nothingToReportIsZeroesNotAnError() {
        Result result = SpendingReport.summarize(List.of(), GroupBy.CATEGORY, JAN_1, DEC_31);

        assertThat(result.groups()).isEmpty();
        assertThat(result.count()).isZero();
        assertThat(result.total()).isEqualTo(Money.ZERO);
        assertThat(result.reimbursement()).isEqualTo(Share.NONE);
    }
}
