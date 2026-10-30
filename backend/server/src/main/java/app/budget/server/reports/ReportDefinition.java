package app.budget.server.reports;

import app.budget.engine.reports.ProjectedVsActual;
import app.budget.engine.reports.Reimbursement;
import app.budget.engine.reports.SpendingReport.GroupBy;
import app.budget.server.api.ApiException;
import app.budget.server.api.Inputs;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * What a report asks for (spec §4.11), checked and tidied: the dates, what to include, the
 * filters, how to group and how to show it. This is what is saved, and what is run.
 *
 * <p>Within one filter any of the choices matches; across filters all must. An empty filter
 * lets everything through.
 *
 * @param from          first day, for {@link Range#CUSTOM} only
 * @param to            last day, for {@link Range#CUSTOM} only
 * @param include       never empty
 * @param categoryIds   a category chosen here also stands for the categories under it
 * @param reimbursement expenses that stand at any of these; a bill or budget only when that payment is flagged to be paid back
 * @param kind          what the report is of: spending, income, or what was projected against what it was
 *                      (decided 2026-10-12: a report is one of them, never a mix)
 * @param incomeInclude for an income report: what counts as income; never empty then
 * @param sourceIds     for an income report: only these income sources; what has no source is then left out
 * @param incomeGroupBy for an income report: how it is grouped
 * @param compareInclude for a projected vs. actual report: what to set side by side; never empty then
 * @param compareGroupBy for a projected vs. actual report: how it is grouped
 * @param changedOnly   for a projected vs. actual report: true to leave out whatever came to exactly what was projected
 */
public record ReportDefinition(
        Range range,
        LocalDate from,
        LocalDate to,
        List<Include> include,
        List<Long> tagIds,
        List<Long> categoryIds,
        List<Long> accountIds,
        List<String> payees,
        List<Reimbursement.Status> reimbursement,
        GroupBy groupBy,
        Show show,
        Kind kind,
        List<IncomeInclude> incomeInclude,
        List<Long> sourceIds,
        IncomeGroupBy incomeGroupBy,
        List<CompareInclude> compareInclude,
        ProjectedVsActual.GroupBy compareGroupBy,
        boolean changedOnly) {

    private static final int MAX_CHOICES = 50;
    private static final int PAYEE_MAX = 120;

    /** The dates a report covers. All but {@code CUSTOM} move with today, so a saved report stays current. */
    public enum Range { THIS_YEAR, LAST_YEAR, LAST_12_MONTHS, LAST_90_DAYS, CUSTOM }

    /** What kinds of spending to count. Transfers and card payments move money; they are not spending. */
    public enum Include {
        /** One-off money out, card charges among them. */
        ONE_OFFS,
        /** Recurring bills. */
        BILLS,
        /** Recurring spending budgets. */
        BUDGETS
    }

    public enum Show { CHART_AND_TABLE, TABLE }

    /**
     * What a report is of. For an income report the spending-only parts (what to include, tags, categories, payees,
     * reimbursement) say nothing; a projected vs. actual report keeps the tag, category and account filters.
     */
    public enum Kind { SPENDING, INCOME, PROJECTED_VS_ACTUAL }

    /**
     * What a projected vs. actual report sets side by side: the things whose amount Budget works
     * out and the user can type over. A one-off is entered as what it was, so it has no projection.
     */
    public enum CompareInclude {
        /** Recurring bills. */
        BILLS,
        /** Recurring spending budgets: what was typed over one is what was spent. */
        BUDGETS,
        /** Paychecks, against the take-home their income source expected. */
        PAYCHECKS
    }

    /** What counts as income in an income report. */
    public enum IncomeInclude {
        /** Every paycheck, at what it actually was. */
        PAYCHECKS,
        /** One-off money in, except money that pays back an expense. */
        ONE_OFFS,
        /** What an employer adds to a retirement account: shown beside the total, not in it. */
        MATCH,
        /** Interest a checking or savings account earned, as Budget works it out. */
        INTEREST
    }

    /** How an income report is grouped. */
    public enum IncomeGroupBy { MONTH, YEAR, SOURCE, ACCOUNT, PAY_PERIOD }

    /** The first and last day a report covers. */
    public record Dates(LocalDate from, LocalDate to) {}

    /** The request body: the same parts, as they arrive. */
    public record Input(
            String range,
            LocalDate from,
            LocalDate to,
            List<String> include,
            List<Long> tagIds,
            List<Long> categoryIds,
            List<Long> accountIds,
            List<String> payees,
            List<String> reimbursement,
            String groupBy,
            String show,
            String kind,
            List<String> incomeInclude,
            List<Long> sourceIds,
            String incomeGroupBy,
            List<String> compareInclude,
            String compareGroupBy,
            Boolean changedOnly) {}

    /** Checks everything that can be checked without looking anything up. Whether ids exist is for the caller. */
    public static ReportDefinition of(Input input) {
        if (input == null) {
            throw ApiException.badRequest("Please choose what this report shows.");
        }
        Range range = Inputs.choice(Range.class, input.range(), "the dates");
        LocalDate from = null;
        LocalDate to = null;
        if (range == Range.CUSTOM) {
            if (input.from() == null || input.to() == null) {
                throw ApiException.badRequest("Please choose both the first and the last day.");
            }
            if (input.to().isBefore(input.from())) {
                throw ApiException.badRequest("The last day is before the first. Please check the two dates.");
            }
            Inputs.requireSensibleRange(input.from(), input.to());
            from = input.from();
            to = input.to();
        }
        // A report saved before income reports existed has no kind: it is a spending report.
        Kind kind = input.kind() == null ? Kind.SPENDING : Inputs.choice(Kind.class, input.kind(), "what the report is of");
        List<Include> include = choices(Include.class, input.include(), "what to include");
        List<IncomeInclude> incomeInclude = choices(IncomeInclude.class, input.incomeInclude(), "what counts as income");
        List<CompareInclude> compareInclude = choices(CompareInclude.class, input.compareInclude(), "what to compare");
        boolean nothingIncluded = switch (kind) {
            case SPENDING -> include.isEmpty();
            case INCOME -> incomeInclude.isEmpty();
            case PROJECTED_VS_ACTUAL -> compareInclude.isEmpty();
        };
        if (nothingIncluded) {
            throw ApiException.badRequest("Please choose at least one thing to include.");
        }
        return new ReportDefinition(
                range,
                from,
                to,
                include,
                Inputs.ids(input.tagIds(), MAX_CHOICES, "Please choose tags from the list."),
                Inputs.ids(input.categoryIds(), MAX_CHOICES, "Please choose categories from the list."),
                Inputs.ids(input.accountIds(), MAX_CHOICES, "Please choose accounts from the list."),
                payees(input.payees()),
                choices(Reimbursement.Status.class, input.reimbursement(), "where the reimbursement stands"),
                input.groupBy() == null && kind != Kind.SPENDING ? GroupBy.MONTH : Inputs.choice(GroupBy.class, input.groupBy(), "how to group it"),
                Inputs.choice(Show.class, input.show(), "how to show it"),
                kind,
                incomeInclude,
                Inputs.ids(input.sourceIds(), MAX_CHOICES, "Please choose income sources from the list."),
                input.incomeGroupBy() == null
                        ? IncomeGroupBy.MONTH
                        : Inputs.choice(IncomeGroupBy.class, input.incomeGroupBy(), "how to group it"),
                compareInclude,
                input.compareGroupBy() == null
                        ? ProjectedVsActual.GroupBy.MONTH
                        : Inputs.choice(ProjectedVsActual.GroupBy.class, input.compareGroupBy(), "how to group it"),
                Boolean.TRUE.equals(input.changedOnly()));
    }

    /** The days this report covers when it is run on {@code today}. */
    public Dates dates(LocalDate today) {
        return switch (range) {
            case THIS_YEAR -> new Dates(today.withDayOfYear(1), LocalDate.of(today.getYear(), 12, 31));
            case LAST_YEAR -> new Dates(LocalDate.of(today.getYear() - 1, 1, 1), LocalDate.of(today.getYear() - 1, 12, 31));
            case LAST_12_MONTHS -> new Dates(today.minusMonths(12).plusDays(1), today);
            case LAST_90_DAYS -> new Dates(today.minusDays(89), today);
            case CUSTOM -> new Dates(from, to);
        };
    }

    private static <E extends Enum<E>> List<E> choices(Class<E> type, List<String> values, String label) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > type.getEnumConstants().length || values.stream().anyMatch(Objects::isNull)) {
            throw ApiException.badRequest("Please choose " + label + " from the list.");
        }
        return values.stream().distinct().map(value -> Inputs.choice(type, value, label)).toList();
    }

    private static List<String> payees(List<String> values) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > MAX_CHOICES) {
            throw ApiException.badRequest("Please choose up to " + MAX_CHOICES + " payees.");
        }
        return values.stream()
                .map(value -> Inputs.text(value, "each payee's name", PAYEE_MAX))
                .distinct()
                .toList();
    }
}
