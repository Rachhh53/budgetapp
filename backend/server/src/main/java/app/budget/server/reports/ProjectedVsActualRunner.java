package app.budget.server.reports;

import app.budget.engine.Money;
import app.budget.engine.reports.ProjectedVsActual;
import app.budget.engine.reports.ProjectedVsActual.Group;
import app.budget.engine.reports.ProjectedVsActual.GroupBy;
import app.budget.engine.reports.ProjectedVsActual.Line;
import app.budget.server.accounts.Account;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.categories.CategoriesRepository;
import app.budget.server.categories.CategoriesRepository.Category;
import app.budget.server.categories.TagsRepository;
import app.budget.server.categories.TagsRepository.Tag;
import app.budget.server.income.PayCalendar;
import app.budget.server.income.PayCalendar.Paycheck;
import app.budget.server.items.ItemCalendar;
import app.budget.server.items.ItemCalendar.Occurrence;
import app.budget.server.items.ItemKind;
import app.budget.server.items.ItemsRepository;
import app.budget.server.items.ItemsRepository.Item;
import app.budget.server.reports.ReportDefinition.CompareInclude;
import app.budget.server.reports.ReportDefinition.Dates;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs a projected vs. actual report (spec §1, §5.10): every bill, budget and paycheck the
 * report asks for, at what Budget showed before it was typed over and at what it was.
 *
 * <p>Something never typed over was as projected. A budget has no payment to tick off, so what
 * was typed over it is what was spent (decided 2026-10-12). Spending and paychecks are added up
 * apart: costing more and being paid more are opposite news.
 */
public final class ProjectedVsActualRunner {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US);
    private static final String ITEM_KEY = "item:";
    private static final String SOURCE_KEY = "source:";

    private final ItemsRepository items;
    private final ItemCalendar calendar;
    private final PayCalendar pay;
    private final AccountsRepository accounts;
    private final CategoriesRepository categories;
    private final TagsRepository tags;

    public ProjectedVsActualRunner(
            ItemsRepository items,
            ItemCalendar calendar,
            PayCalendar pay,
            AccountsRepository accounts,
            CategoriesRepository categories,
            TagsRepository tags) {
        this.items = items;
        this.calendar = calendar;
        this.pay = pay;
        this.accounts = accounts;
        this.categories = categories;
        this.tags = tags;
    }

    /**
     * One amount, as projected and as it was.
     *
     * @param kind            {@code bill}, {@code budget} or {@code paycheck}
     * @param itemId          the recurring item it is one time of; {@code null} for a paycheck
     * @param sourceId        the income source of a paycheck; {@code null} for anything else
     * @param date            the day it counts on: the day a bill was paid once it has been, its due date until then; a paycheck's payday
     * @param accountId       the account it is paid from, or the one a paycheck lands in
     * @param projectedCents  what Budget showed before the amount was typed over; the same as {@code actualCents} when it never was
     * @param differenceCents what it was, less what was projected
     * @param toCome          true while the day has not come yet (and a bill has not been marked paid)
     */
    public record Row(
            String kind,
            Long itemId,
            Long sourceId,
            LocalDate scheduledDate,
            LocalDate date,
            String description,
            Long categoryId,
            long accountId,
            long projectedCents,
            long actualCents,
            long differenceCents,
            boolean changed,
            boolean toCome) {}

    /**
     * One group of one side of the report.
     *
     * @param label   ready to show: "March 2026", a category's or an item's name
     * @param color   the category's color; {@code null} for anything else
     * @param changed how many of its lines came to something other than projected
     */
    public record GroupView(
            String key, String label, String color, int count, int changed, long projectedCents, long actualCents, long differenceCents) {}

    /** Money going out, or paychecks coming in: its groups and what they come to. */
    public record Side(List<GroupView> groups, int count, int changed, long projectedCents, long actualCents, long differenceCents) {}

    /**
     * A projected vs. actual report that was run.
     *
     * @param kind      always {@code projected_vs_actual}: tells it from the other reports
     * @param spending  bills and budgets
     * @param paychecks paychecks; never added to {@code spending}
     * @param rows      every line counted, oldest first
     */
    public record Report(String kind, LocalDate from, LocalDate to, GroupBy groupBy, Side spending, Side paychecks, List<Row> rows) {}

    /** @throws ApiException when the report names a tag, category or account that is not there */
    public Report run(ReportDefinition definition) {
        List<Category> allCategories = categories.all();
        List<Account> allAccounts = accounts.all();
        requireKnown(definition.tagIds(), tags.all().stream().map(Tag::id).collect(Collectors.toSet()), "a tag");
        requireKnown(definition.categoryIds(), allCategories.stream().map(Category::id).collect(Collectors.toSet()), "a category");
        requireKnown(definition.accountIds(), allAccounts.stream().map(Account::id).collect(Collectors.toSet()), "an account");

        LocalDate today = pay.today();
        Dates dates = definition.dates(today);
        Set<CompareInclude> include = Set.copyOf(definition.compareInclude());
        // A category chosen also stands for the categories under it, as in a spending report.
        Set<Long> categoryIds = new HashSet<>(definition.categoryIds());
        for (Category category : allCategories) {
            if (category.parentId() != null && definition.categoryIds().contains(category.parentId())) {
                categoryIds.add(category.id());
            }
        }

        List<Row> rows = new ArrayList<>();
        if (include.contains(CompareInclude.BILLS) || include.contains(CompareInclude.BUDGETS)) {
            Map<Long, List<Long>> tagsByItem = items.all().stream().collect(Collectors.toMap(Item::id, Item::tagIds));
            for (Occurrence occurrence : calendar.expand(dates.from(), dates.to()).occurrences()) {
                boolean wanted = occurrence.kind() == ItemKind.BILL && include.contains(CompareInclude.BILLS)
                        || occurrence.kind() == ItemKind.BUDGET && include.contains(CompareInclude.BUDGETS);
                LocalDate date = occurrence.paid() && occurrence.paidDate() != null ? occurrence.paidDate() : occurrence.dueDate();
                if (!wanted || occurrence.skipped() || date.isBefore(dates.from()) || date.isAfter(dates.to())) {
                    continue;
                }
                if (!definition.tagIds().isEmpty()
                        && tagsByItem.getOrDefault(occurrence.itemId(), List.of()).stream().noneMatch(definition.tagIds()::contains)) {
                    continue;
                }
                if (!categoryIds.isEmpty() && (occurrence.categoryId() == null || !categoryIds.contains(occurrence.categoryId()))) {
                    continue;
                }
                long projected = occurrence.projectedCents() == null ? occurrence.amountCents() : occurrence.projectedCents();
                rows.add(new Row(
                        occurrence.kind() == ItemKind.BILL ? "bill" : "budget",
                        occurrence.itemId(), null, occurrence.scheduledDate(), date, occurrence.name(), occurrence.categoryId(),
                        occurrence.payFromAccountId(), projected, occurrence.amountCents(), occurrence.amountCents() - projected,
                        occurrence.amountCents() != projected, !occurrence.paid() && date.isAfter(today)));
            }
        }
        // A paycheck has no tag or category: a report that asks for one of those is not about paychecks.
        if (include.contains(CompareInclude.PAYCHECKS) && definition.tagIds().isEmpty() && categoryIds.isEmpty()) {
            for (Paycheck paycheck : pay.paychecks(dates.from(), dates.to())) {
                // A one-off paycheck (a bonus) was never projected, as one-off spending was not: it has nothing to compare.
                if (!paycheck.scheduled()) {
                    continue;
                }
                long projected = paycheck.projectedCents() == null ? paycheck.netCents() : paycheck.projectedCents();
                rows.add(new Row(
                        "paycheck", null, paycheck.incomeSourceId(), paycheck.scheduledDate(), paycheck.payDate(),
                        paycheck.sourceName() + " paycheck", null, paycheck.depositAccountId(), projected, paycheck.netCents(),
                        paycheck.netCents() - projected, paycheck.netCents() != projected, paycheck.payDate().isAfter(today)));
            }
        }
        rows.removeIf(row -> !definition.accountIds().isEmpty() && !definition.accountIds().contains(row.accountId())
                || definition.changedOnly() && !row.changed());
        rows.sort(Comparator.comparing(Row::date).thenComparing(Row::description, String.CASE_INSENSITIVE_ORDER));

        GroupBy groupBy = definition.compareGroupBy();
        Labels labels = new Labels(allCategories, rows);
        return new Report(
                "projected_vs_actual",
                dates.from(),
                dates.to(),
                groupBy,
                side(rows.stream().filter(row -> !isPaycheck(row)).toList(), groupBy, dates, labels, false),
                side(rows.stream().filter(ProjectedVsActualRunner::isPaycheck).toList(), groupBy, dates, labels, true),
                rows);
    }

    private static boolean isPaycheck(Row row) {
        return row.kind().equals("paycheck");
    }

    private static Side side(List<Row> rows, GroupBy groupBy, Dates dates, Labels labels, boolean paychecks) {
        List<Line> lines = rows.stream()
                .map(row -> new Line(
                        row.date(), Money.ofCents(row.projectedCents()), Money.ofCents(row.actualCents()), row.categoryId(),
                        row.itemId() != null ? ITEM_KEY + row.itemId() : SOURCE_KEY + row.sourceId()))
                .toList();
        ProjectedVsActual.Result result = ProjectedVsActual.summarize(lines, groupBy, dates.from(), dates.to());
        return new Side(
                result.groups().stream().map(group -> labels.view(group, groupBy, paychecks)).toList(),
                result.count(), result.changed(), result.projected().cents(), result.actual().cents(), result.difference().cents());
    }

    private static void requireKnown(List<Long> ids, Set<Long> known, String what) {
        if (!known.containsAll(ids)) {
            throw ApiException.badRequest("This report filters by " + what
                    + " that is no longer there. Please choose its filters again.");
        }
    }

    /** Turns a group's key into words and a color. */
    private static final class Labels {
        private final Map<Long, Category> categories = new HashMap<>();
        /** What each item or income source is called, by the key its lines carry. */
        private final Map<String, String> names = new HashMap<>();

        Labels(List<Category> allCategories, List<Row> rows) {
            allCategories.forEach(category -> categories.put(category.id(), category));
            for (Row row : rows) {
                names.put(row.itemId() != null ? ITEM_KEY + row.itemId() : SOURCE_KEY + row.sourceId(), row.description());
            }
        }

        GroupView view(Group group, GroupBy groupBy, boolean paychecks) {
            String label;
            String color = null;
            switch (groupBy) {
                case MONTH -> label = YearMonth.parse(group.key()).format(MONTH);
                case YEAR -> label = group.key();
                case CATEGORY -> {
                    Category category = group.key() == null ? null : categories.get(Long.valueOf(group.key()));
                    label = paychecks ? "Paychecks" : category == null ? "No category" : name(category);
                    color = category == null ? null : category.color();
                }
                case ITEM -> label = names.getOrDefault(group.key(), "");
                default -> throw new IllegalStateException("Unknown grouping");
            }
            return new GroupView(
                    group.key(), label, color, group.count(), group.changed(), group.projected().cents(), group.actual().cents(),
                    group.difference().cents());
        }

        /** "Medical › Dental" for a sub-category. */
        String name(Category category) {
            Category parent = category.parentId() == null ? null : categories.get(category.parentId());
            return parent == null ? category.name() : parent.name() + " › " + category.name();
        }

        String category(Long id) {
            Category category = id == null ? null : categories.get(id);
            return category == null ? "" : name(category);
        }
    }

    /** The names a row's ids stand for, for the exports. */
    public record Named(String category, String account) {}

    /** Looks up the names for every row of {@code report}, in order. */
    public List<Named> names(Report report) {
        Labels labels = new Labels(categories.all(), List.of());
        Map<Long, String> accountNames = accounts.all().stream().collect(Collectors.toMap(Account::id, Account::name));
        return report.rows().stream()
                .map(row -> new Named(labels.category(row.categoryId()), accountNames.getOrDefault(row.accountId(), "")))
                .toList();
    }

    /** What an include choice is called in a sentence. */
    static String words(CompareInclude include) {
        return switch (include) {
            case BILLS -> "bills and expenses";
            case BUDGETS -> "budgets";
            case PAYCHECKS -> "paychecks";
        };
    }

    /** A row's kind in words, for the exports. */
    static String kind(String kind) {
        return switch (kind) {
            case "bill" -> "Bill or expense";
            case "budget" -> "Budget";
            default -> "Paycheck";
        };
    }
}
