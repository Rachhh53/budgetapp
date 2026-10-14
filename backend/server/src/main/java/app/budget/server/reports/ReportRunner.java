package app.budget.server.reports;

import app.budget.engine.Money;
import app.budget.engine.reports.Reimbursement;
import app.budget.engine.reports.Reimbursement.Share;
import app.budget.engine.reports.SpendingReport;
import app.budget.engine.reports.SpendingReport.Group;
import app.budget.engine.reports.SpendingReport.GroupBy;
import app.budget.engine.reports.SpendingReport.Line;
import app.budget.server.accounts.Account;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.categories.CategoriesRepository;
import app.budget.server.categories.CategoriesRepository.Category;
import app.budget.server.categories.TagsRepository;
import app.budget.server.categories.TagsRepository.Tag;
import app.budget.server.entries.EntriesRepository;
import app.budget.server.entries.EntriesRepository.Entry;
import app.budget.server.entries.EntryKind;
import app.budget.server.files.AttachmentsRepository;
import app.budget.server.files.AttachmentsRepository.Owner;
import app.budget.server.income.PayCalendar;
import app.budget.server.items.ItemCalendar;
import app.budget.server.items.ItemCalendar.Occurrence;
import app.budget.server.items.ItemKind;
import app.budget.server.items.ItemsRepository;
import app.budget.server.items.ItemsRepository.Item;
import app.budget.server.reports.ReportDefinition.Dates;
import app.budget.server.reports.ReportDefinition.Include;
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
 * Runs a report (spec §4.11): gathers the spending it asks for, one-off and recurring, and
 * hands it to the engine to be grouped and added up.
 *
 * <p>Spending here means one-off money out, bills and budgets. Transfers and card payments
 * are left out: they move money that was already counted when it was spent.
 */
public final class ReportRunner {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US);

    private final EntriesRepository entries;
    private final ItemsRepository items;
    private final ItemCalendar calendar;
    private final PayCalendar pay;
    private final AccountsRepository accounts;
    private final CategoriesRepository categories;
    private final TagsRepository tags;
    private final AttachmentsRepository attachments;

    public ReportRunner(
            EntriesRepository entries,
            ItemsRepository items,
            ItemCalendar calendar,
            PayCalendar pay,
            AccountsRepository accounts,
            CategoriesRepository categories,
            TagsRepository tags,
            AttachmentsRepository attachments) {
        this.entries = entries;
        this.items = items;
        this.calendar = calendar;
        this.pay = pay;
        this.accounts = accounts;
        this.categories = categories;
        this.tags = tags;
        this.attachments = attachments;
    }

    /**
     * One thing spent, as a report lists it.
     *
     * @param kind            {@code one_off}, {@code bill} or {@code budget}
     * @param entryId         the one-off entry it is; {@code null} for a recurring item
     * @param itemId          the recurring item it is one time of; {@code null} for a one-off
     * @param scheduledDate   with {@code itemId}: the date the item's schedule names for this time
     * @param date            the day it counts on: the day a bill was paid once it has been, its due date until then
     * @param estimate        true while the amount is not final, or the day has not come yet
     * @param unpaid          true while the day has not come yet and it has not been marked paid
     * @param reimbursement   where a one-off expense stands; {@code null} for anything nobody pays back
     * @param reimbursedCents how much of it counts as having come back
     * @param owedCents       how much is still to come back
     */
    public record Row(
            String kind,
            Long entryId,
            Long itemId,
            LocalDate scheduledDate,
            LocalDate date,
            String description,
            String payee,
            Long categoryId,
            List<Long> tagIds,
            long accountId,
            long amountCents,
            boolean estimate,
            boolean unpaid,
            Reimbursement.Status reimbursement,
            long reimbursedCents,
            long owedCents,
            int fileCount) {}

    /**
     * One group of the report.
     *
     * @param key   as in the engine's {@link Group}; {@code null} for lines with no category, tag or payee
     * @param label ready to show: "March 2026", a category's or tag's name, "No category"
     * @param color the category's, tag's or account's color; {@code null} for anything else
     */
    public record GroupView(String key, String label, String color, int count, long totalCents) {}

    /**
     * A report that was run.
     *
     * @param kind               always {@code spending}: tells it from an income report
     * @param rows               every line counted, oldest first
     * @param reimbursedCents    how much of the total has come back
     * @param waitingCents       how much was sent in and is still to come
     * @param notSubmittedCents  how much is still to be sent in
     * @param estimatedCents     how much of the total is an estimate, or dated after today
     * @param hasReimbursements  true when anything in it is, or was, to be paid back: when false the three figures say nothing
     */
    public record Report(
            String kind,
            LocalDate from,
            LocalDate to,
            GroupBy groupBy,
            List<GroupView> groups,
            List<Row> rows,
            int count,
            long totalCents,
            long reimbursedCents,
            long waitingCents,
            long notSubmittedCents,
            long estimatedCents,
            boolean hasReimbursements) {}

    /** @throws ApiException when the report names a tag, category or account that is not there */
    public Report run(ReportDefinition definition) {
        List<Category> allCategories = categories.all();
        List<Tag> allTags = tags.all();
        List<Account> allAccounts = accounts.all();
        requireKnown(definition.tagIds(), allTags.stream().map(Tag::id).collect(Collectors.toSet()), "a tag");
        requireKnown(definition.categoryIds(), allCategories.stream().map(Category::id).collect(Collectors.toSet()), "a category");
        requireKnown(definition.accountIds(), allAccounts.stream().map(Account::id).collect(Collectors.toSet()), "an account");

        LocalDate today = pay.today();
        Dates dates = definition.dates(today);
        Filter filter = new Filter(definition, allCategories);

        List<Row> rows = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        if (definition.include().contains(Include.ONE_OFFS)) {
            for (Entry entry : entries.between(dates.from(), dates.to())) {
                if (entry.kind() != EntryKind.MONEY_OUT) {
                    continue;
                }
                Share share = entry.reimbursement().share(entry.amountCents());
                Row row = new Row(
                        "one_off", entry.id(), null, null, entry.date(), entry.description(), entry.payee(), entry.categoryId(),
                        entry.tagIds(), entry.accountId(), entry.amountCents(), entry.estimate() || entry.date().isAfter(today),
                        entry.date().isAfter(today),
                        entry.reimbursement().status(), share.reimbursed().cents(), share.owed().cents(), entry.fileCount());
                if (filter.lets(row)) {
                    rows.add(row);
                    lines.add(line(row, share));
                }
            }
        }
        if (definition.include().contains(Include.BILLS) || definition.include().contains(Include.BUDGETS)) {
            Map<Long, List<Long>> tagsByItem = items.all().stream().collect(Collectors.toMap(Item::id, Item::tagIds));
            Map<Owner.Occurrence, Integer> files = attachments.countsByOccurrence(
                    dates.from().minusYears(1), dates.to().plusYears(1));
            // A visit not billed yet is listed as still to come, at what it costs (spec §4.6).
            ItemCalendar.Expansion expansion = calendar.expand(dates.from(), dates.to());
            List<Occurrence> spent = new ArrayList<>(expansion.occurrences());
            Set<Occurrence> notYetBilled = new HashSet<>(calendar.notYetBilled(expansion));
            spent.addAll(notYetBilled);
            for (Occurrence occurrence : spent) {
                boolean wanted = occurrence.kind() == ItemKind.BILL && definition.include().contains(Include.BILLS)
                        || occurrence.kind() == ItemKind.BUDGET && definition.include().contains(Include.BUDGETS);
                LocalDate date = occurrence.paid() && occurrence.paidDate() != null ? occurrence.paidDate() : occurrence.dueDate();
                if (!wanted || occurrence.skipped() || date.isBefore(dates.from()) || date.isAfter(dates.to())) {
                    continue;
                }
                Row row = new Row(
                        occurrence.kind() == ItemKind.BILL ? "bill" : "budget",
                        null, occurrence.itemId(), occurrence.scheduledDate(), date, occurrence.name(), occurrence.name(),
                        occurrence.categoryId(), tagsByItem.getOrDefault(occurrence.itemId(), List.of()),
                        occurrence.payFromAccountId(), occurrence.amountCents(),
                        occurrence.estimate() || !occurrence.paid() && date.isAfter(today) || notYetBilled.contains(occurrence),
                        !occurrence.paid() && (date.isAfter(today) || notYetBilled.contains(occurrence)),
                        null, 0, 0,
                        // A visit keeps no receipts: they are on its invoice.
                        notYetBilled.contains(occurrence) ? 0 : files.getOrDefault(new Owner.Occurrence(occurrence.itemId(), occurrence.scheduledDate()), 0));
                if (filter.lets(row)) {
                    rows.add(row);
                    lines.add(line(row, Share.NONE));
                }
            }
        }
        rows.sort(Comparator.comparing(Row::date).thenComparing(Row::description, String.CASE_INSENSITIVE_ORDER));

        SpendingReport.Result result = SpendingReport.summarize(lines, definition.groupBy(), dates.from(), dates.to());
        Labels labels = new Labels(allCategories, allTags, allAccounts);
        return new Report(
                "spending",
                dates.from(),
                dates.to(),
                definition.groupBy(),
                result.groups().stream().map(group -> labels.view(group, definition.groupBy())).toList(),
                rows,
                result.count(),
                result.total().cents(),
                result.reimbursement().reimbursed().cents(),
                result.reimbursement().waiting().cents(),
                result.reimbursement().notSubmitted().cents(),
                rows.stream().filter(Row::estimate).mapToLong(Row::amountCents).sum(),
                rows.stream().anyMatch(row -> row.reimbursement() != null && row.reimbursement() != Reimbursement.Status.NONE
                        || row.reimbursedCents() > 0));
    }

    private static Line line(Row row, Share share) {
        return new Line(
                row.date(), Money.ofCents(row.amountCents()), row.categoryId(), row.tagIds(), row.payee(), row.accountId(), share);
    }

    private static void requireKnown(List<Long> ids, Set<Long> known, String what) {
        if (!known.containsAll(ids)) {
            throw ApiException.badRequest("This report filters by " + what
                    + " that is no longer there. Please choose its filters again.");
        }
    }

    /** The report's filters, ready to ask of each row. */
    private static final class Filter {
        private final ReportDefinition definition;
        /** The categories chosen and every category under one of them. */
        private final Set<Long> categoryIds;
        private final Set<String> payees;

        Filter(ReportDefinition definition, List<Category> allCategories) {
            this.definition = definition;
            this.categoryIds = new HashSet<>(definition.categoryIds());
            for (Category category : allCategories) {
                if (category.parentId() != null && definition.categoryIds().contains(category.parentId())) {
                    categoryIds.add(category.id());
                }
            }
            this.payees = definition.payees().stream().map(Filter::folded).collect(Collectors.toSet());
        }

        boolean lets(Row row) {
            if (!definition.tagIds().isEmpty() && row.tagIds().stream().noneMatch(definition.tagIds()::contains)) {
                return false;
            }
            if (!categoryIds.isEmpty() && (row.categoryId() == null || !categoryIds.contains(row.categoryId()))) {
                return false;
            }
            if (!definition.accountIds().isEmpty() && !definition.accountIds().contains(row.accountId())) {
                return false;
            }
            if (!payees.isEmpty() && (row.payee() == null || !payees.contains(folded(row.payee())))) {
                return false;
            }
            return definition.reimbursement().isEmpty()
                    || row.reimbursement() != null && definition.reimbursement().contains(row.reimbursement());
        }

        /** "CVS pharmacy" and "CVS Pharmacy" are one payee. */
        private static String folded(String name) {
            return name.strip().toLowerCase(Locale.ROOT);
        }
    }

    /** Turns a group's key into words and a color. */
    private static final class Labels {
        private final Map<Long, Category> categories = new HashMap<>();
        private final Map<Long, Tag> tags = new HashMap<>();
        private final Map<Long, Account> accounts = new HashMap<>();

        Labels(List<Category> allCategories, List<Tag> allTags, List<Account> allAccounts) {
            allCategories.forEach(category -> categories.put(category.id(), category));
            allTags.forEach(tag -> tags.put(tag.id(), tag));
            allAccounts.forEach(account -> accounts.put(account.id(), account));
        }

        GroupView view(Group group, GroupBy groupBy) {
            String label;
            String color = null;
            switch (groupBy) {
                case MONTH -> label = YearMonth.parse(group.key()).format(MONTH);
                case YEAR -> label = group.key();
                case PAYEE -> label = group.key() == null ? "No payee" : group.key();
                case CATEGORY -> {
                    Category category = group.key() == null ? null : categories.get(Long.valueOf(group.key()));
                    label = category == null ? "No category" : name(category);
                    color = category == null ? null : category.color();
                }
                case TAG -> {
                    Tag tag = group.key() == null ? null : tags.get(Long.valueOf(group.key()));
                    label = tag == null ? "No tags" : tag.name();
                    color = tag == null ? null : tag.color();
                }
                case ACCOUNT -> {
                    Account account = accounts.get(Long.valueOf(group.key()));
                    label = account == null ? "An account that was removed" : account.name();
                    color = account == null ? null : account.color();
                }
                default -> throw new IllegalStateException("Unknown grouping");
            }
            return new GroupView(group.key(), label, color, group.count(), group.total().cents());
        }

        /** "Medical › Dental" for a sub-category. */
        private String name(Category category) {
            Category parent = category.parentId() == null ? null : categories.get(category.parentId());
            return parent == null ? category.name() : parent.name() + " › " + category.name();
        }

        String category(Long id) {
            Category category = id == null ? null : categories.get(id);
            return category == null ? "" : name(category);
        }

        String tags(List<Long> ids) {
            return ids.stream().map(tags::get).filter(tag -> tag != null).map(Tag::name).sorted().collect(Collectors.joining(", "));
        }

        String account(long id) {
            Account account = accounts.get(id);
            return account == null ? "" : account.name();
        }
    }

    /** The names a row's ids stand for, for the exports. */
    public record Named(String category, String tags, String account) {}

    /** Looks up the names for every row of {@code report}, in order. */
    public List<Named> names(Report report) {
        Labels labels = new Labels(categories.all(), tags.all(), accounts.all());
        return report.rows().stream()
                .map(row -> new Named(labels.category(row.categoryId()), labels.tags(row.tagIds()), labels.account(row.accountId())))
                .toList();
    }
}
