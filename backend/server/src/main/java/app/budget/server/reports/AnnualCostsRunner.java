package app.budget.server.reports;

import app.budget.engine.Money;
import app.budget.engine.reports.AnnualCosts;
import app.budget.engine.reports.AnnualCosts.Basis;
import app.budget.engine.reports.AnnualCosts.Line;
import app.budget.engine.reports.AnnualCosts.Window;
import app.budget.server.api.ApiException;
import app.budget.server.api.Inputs;
import app.budget.server.categories.CategoriesRepository;
import app.budget.server.categories.CategoriesRepository.Category;
import app.budget.server.income.PayCalendar;
import app.budget.server.items.ItemCalendar;
import app.budget.server.items.ItemCalendar.Occurrence;
import app.budget.server.items.ItemKind;
import app.budget.server.items.ItemsRepository.Item;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Annual costs (spec §5.8, §8): what every recurring bill and budget comes to a year, how that
 * has changed, and one item month by month. The sums are the engine's ({@link AnnualCosts});
 * this gathers what goes into them.
 *
 * <p>An amount is what it actually was: what a bill was paid at, and for a budget what was typed
 * over it (decided 2026-10-12); anything untouched counts as planned. What is still to come is
 * counted as budgeted. Archived items are left out, as they are from reports and pay periods.
 */
public final class AnnualCostsRunner {

    /** How many years, or twelve-month stretches, are set side by side. */
    private static final int WINDOWS = 3;
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US);

    private final ItemCalendar calendar;
    private final PayCalendar pay;
    private final CategoriesRepository categories;

    public AnnualCostsRunner(ItemCalendar calendar, PayCalendar pay, CategoriesRepository categories) {
        this.calendar = calendar;
        this.pay = pay;
        this.categories = categories;
    }

    /** @param label "2026" for a calendar year, "Oct 2025 – Sep 2026" for twelve rolling months */
    public record WindowView(LocalDate from, LocalDate to, String label) {}

    /**
     * One recurring item across the windows.
     *
     * @param kind         {@code bill} or {@code budget}
     * @param categoryName "Utilities › Electric" for a sub-category; {@code null} without a category
     * @param color        the category's color; {@code null} without one
     * @param totalsCents  one per window, oldest first
     * @param changeBp     the newest window against the one before, in basis points; {@code null} with nothing to compare
     */
    public record RowView(
            long itemId,
            String name,
            String kind,
            Long categoryId,
            String categoryName,
            String color,
            List<Long> totalsCents,
            Integer changeBp,
            long perMonthCents) {}

    /** The item whose cost rose by the largest share, and by how much money. */
    public record Increase(long itemId, String name, int changeBp, long increaseCents) {}

    /**
     * @param budgetedFrom calendar years only: the first day of what is still to come in this year, counted as
     *                     budgeted; {@code null} for rolling months, which are all over
     */
    public record Costs(
            Basis basis,
            List<WindowView> windows,
            List<RowView> rows,
            List<Long> totalsCents,
            Integer changeBp,
            long perMonthCents,
            Increase biggestIncrease,
            LocalDate budgetedFrom) {}

    public Costs run(Basis basis) {
        LocalDate today = pay.today();
        List<Window> windows = AnnualCosts.windows(basis, today, WINDOWS);
        List<Line> lines = new ArrayList<>();
        // The newest occurrence seen of each item says what it is called and filed under now.
        Map<Long, Occurrence> latest = new HashMap<>();
        for (Occurrence occurrence : calendar.expand(windows.getFirst().from(), windows.getLast().to()).occurrences()) {
            if (!counts(occurrence)) {
                continue;
            }
            lines.add(new Line(occurrence.itemId(), date(occurrence), Money.ofCents(occurrence.amountCents())));
            latest.merge(occurrence.itemId(), occurrence, (a, b) -> date(b).isAfter(date(a)) ? b : a);
        }
        AnnualCosts.Result result = AnnualCosts.summarize(lines, windows);

        Map<Long, Category> categoryById = new HashMap<>();
        categories.all().forEach(category -> categoryById.put(category.id(), category));
        List<RowView> rows = new ArrayList<>();
        for (AnnualCosts.Row row : result.rows()) {
            Occurrence occurrence = latest.get(row.itemId());
            Category category = occurrence.categoryId() == null ? null : categoryById.get(occurrence.categoryId());
            rows.add(new RowView(
                    row.itemId(), occurrence.name(), Inputs.code(occurrence.kind()),
                    category == null ? null : (Long) category.id(),
                    category == null ? null : name(category, categoryById),
                    category == null ? null : category.color(),
                    cents(row.totals()), row.changeBp(), row.perMonth().cents()));
        }
        AnnualCosts.Row biggest = result.biggestIncrease();
        return new Costs(
                basis,
                windows.stream().map(window -> new WindowView(window.from(), window.to(), label(basis, window))).toList(),
                rows,
                cents(result.totals()),
                result.changeBp(),
                result.perMonth().cents(),
                biggest == null
                        ? null
                        : new Increase(biggest.itemId(), latest.get(biggest.itemId()).name(), biggest.changeBp(), biggest.increase().cents()),
                basis == Basis.CALENDAR && today.getMonthValue() < 12 ? YearMonth.from(today).plusMonths(1).atDay(1) : null);
    }

    /** One month of an item's year. {@code toCome} months hold what is budgeted. */
    public record MonthView(YearMonth month, long amountCents, long beforeCents, boolean toCome) {}

    /** The month a year's highest or lowest amount fell in. */
    public record Extreme(YearMonth month, long amountCents) {}

    /**
     * One item's year, month by month, beside the year before.
     *
     * @param averageCents the year shared over twelve months
     * @param budgetCents  what the item comes to a month at today's amount
     */
    public record ByMonth(
            int year,
            List<MonthView> months,
            Extreme highest,
            Extreme lowest,
            long totalCents,
            long averageCents,
            long budgetCents,
            boolean budgetTooLow) {}

    /** @throws ApiException when {@code item} is not a bill or a budget: nothing else is a cost */
    public ByMonth byMonth(Item item) {
        if (item.kind() != ItemKind.BILL && item.kind() != ItemKind.BUDGET) {
            throw ApiException.badRequest("Month-by-month costs are kept for bills and budgets.");
        }
        LocalDate today = pay.today();
        LocalDate from = LocalDate.of(today.getYear() - 1, 1, 1);
        LocalDate to = LocalDate.of(today.getYear(), 12, 31);
        List<Line> lines = new ArrayList<>();
        for (Occurrence occurrence : calendar.expand(List.of(item), from, to).occurrences()) {
            if (counts(occurrence) && !date(occurrence).isBefore(from) && !date(occurrence).isAfter(to)) {
                lines.add(new Line(item.id(), date(occurrence), Money.ofCents(occurrence.amountCents())));
            }
        }
        long perYear = calendar.amounts(item.versionOn(today).terms()).perYearCents();
        AnnualCosts.ByMonth year = AnnualCosts.byMonth(lines, today, Money.ofCents(perYear));
        return new ByMonth(
                today.getYear(),
                year.months().stream()
                        .map(month -> new MonthView(month.month(), month.amount().cents(), month.before().cents(), month.toCome()))
                        .toList(),
                extreme(year.highest()),
                extreme(year.lowest()),
                year.total().cents(),
                year.average().cents(),
                year.budget().cents(),
                year.budgetTooLow());
    }

    /** The lines of the annual costs file, in the order of the screen. */
    List<CsvExport.AnnualRow> csvRows(Costs costs) {
        return costs.rows().stream()
                .map(row -> new CsvExport.AnnualRow(
                        row.name(), CsvExport.kind(row.kind()), row.categoryName(), row.totalsCents(), row.changeBp(), row.perMonthCents()))
                .toList();
    }

    private static boolean counts(Occurrence occurrence) {
        return (occurrence.kind() == ItemKind.BILL || occurrence.kind() == ItemKind.BUDGET) && !occurrence.skipped();
    }

    /** The day it counts on: the day a bill was paid once it has been, its due date until then. */
    private static LocalDate date(Occurrence occurrence) {
        return occurrence.paid() && occurrence.paidDate() != null ? occurrence.paidDate() : occurrence.dueDate();
    }

    private static Extreme extreme(AnnualCosts.Month month) {
        return month == null ? null : new Extreme(month.month(), month.amount().cents());
    }

    private static List<Long> cents(List<Money> amounts) {
        return amounts.stream().map(Money::cents).toList();
    }

    private static String label(Basis basis, Window window) {
        return basis == Basis.CALENDAR
                ? String.valueOf(window.from().getYear())
                : window.from().format(MONTH) + " – " + window.to().format(MONTH);
    }

    /** "Medical › Dental" for a sub-category. */
    private static String name(Category category, Map<Long, Category> byId) {
        Category parent = category.parentId() == null ? null : byId.get(category.parentId());
        return parent == null ? category.name() : parent.name() + " › " + category.name();
    }
}
