package app.budget.server.reports;

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
 * @param reimbursement expenses that stand at any of these; bills and budgets never match one
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
        Show show) {

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
            String show) {}

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
        List<Include> include = choices(Include.class, input.include(), "what to include");
        if (include.isEmpty()) {
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
                Inputs.choice(GroupBy.class, input.groupBy(), "how to group it"),
                Inputs.choice(Show.class, input.show(), "how to show it"));
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
