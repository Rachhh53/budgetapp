package app.budget.server.reports;

import app.budget.engine.Money;
import app.budget.engine.reports.IncomeReport;
import app.budget.engine.reports.IncomeReport.Group;
import app.budget.engine.reports.IncomeReport.Kind;
import app.budget.engine.reports.IncomeReport.Line;
import app.budget.engine.reports.IncomeReport.Sums;
import app.budget.engine.stubs.PayStub;
import app.budget.engine.stubs.PayStub.Totals;
import app.budget.server.accounts.Account;
import app.budget.server.accounts.AccountType;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.entries.EntriesRepository;
import app.budget.server.entries.EntriesRepository.Entry;
import app.budget.server.entries.EntryKind;
import app.budget.server.income.IncomeRepository;
import app.budget.server.income.IncomeRepository.Deduction;
import app.budget.server.income.IncomeRepository.Pay;
import app.budget.server.income.IncomeRepository.Source;
import app.budget.server.income.PayCalendar;
import app.budget.server.income.PayCalendar.Contribution;
import app.budget.server.income.PayCalendar.Paycheck;
import app.budget.server.income.PayCalendar.Period;
import app.budget.server.ledger.Ledger;
import app.budget.server.reports.ReportDefinition.Dates;
import app.budget.server.reports.ReportDefinition.IncomeGroupBy;
import app.budget.server.reports.ReportDefinition.IncomeInclude;
import app.budget.server.stubs.PayStubsRepository;
import app.budget.server.stubs.PayStubsRepository.Status;
import app.budget.server.stubs.PayStubsRepository.Stub;
import app.budget.server.stubs.PayStubsRepository.StubLine;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs an income report (spec §4.11): every paycheck, one-off money in, employer match and
 * interest the report asks for, handed to the engine to be grouped and added up.
 *
 * <p>A paycheck's take-home is what it actually was: a raise counts from its date and an amount
 * typed over one paycheck counts for that one. What came out of it is the stub's, when a stub
 * was saved for that paycheck; otherwise it is the income source's own figures for that date,
 * with taxes taken as whatever is left between gross, deductions and take-home.
 */
public final class IncomeReportRunner {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US);
    private static final DateTimeFormatter DAY_AND_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
    /** As for saving a stub onto a paycheck: a stub is for the paycheck paid within this many days of it. */
    private static final int DAYS_AROUND_PAYDAY = 4;
    /** The kind {@link Ledger} gives a savings account's interest. */
    private static final String INTEREST_KIND = "interest";

    private final PayCalendar pay;
    private final IncomeRepository income;
    private final PayStubsRepository stubs;
    private final EntriesRepository entries;
    private final AccountsRepository accounts;
    private final Ledger ledger;

    public IncomeReportRunner(
            PayCalendar pay,
            IncomeRepository income,
            PayStubsRepository stubs,
            EntriesRepository entries,
            AccountsRepository accounts,
            Ledger ledger) {
        this.pay = pay;
        this.income = income;
        this.stubs = stubs;
        this.entries = entries;
        this.accounts = accounts;
        this.ledger = ledger;
    }

    /**
     * One thing that came in.
     *
     * @param kind            {@code paycheck}, {@code one_off}, {@code interest} or {@code match}
     * @param sourceId        the income source of a paycheck or a match; {@code null} for anything else
     * @param accountId       the account it landed in
     * @param amountCents     the take-home, the money in, the interest or the match
     * @param grossCents      a paycheck's gross pay; {@code null} when not known, and for anything else
     * @param taxesCents      a paycheck's taxes; {@code null} when not known
     * @param deductionsCents what else came out of a paycheck; {@code null} when not known
     * @param fromStub        true when a paycheck's figures are a saved pay stub's
     * @param estimate        true while the amount is not final, or the day has not come yet
     */
    public record Row(
            String kind,
            LocalDate date,
            String description,
            Long sourceId,
            long accountId,
            long amountCents,
            Long grossCents,
            Long taxesCents,
            Long deductionsCents,
            boolean fromStub,
            boolean estimate) {}

    /**
     * One group of the report.
     *
     * @param label         ready to show: "March 2026", a source's or an account's name, a pay period's dates
     * @param receivedCents take-home, one-offs and interest together; the match is beside it, not in it
     */
    public record GroupView(
            String key,
            String label,
            String color,
            int count,
            long grossCents,
            long taxesCents,
            long deductionsCents,
            long takeHomeCents,
            long oneOffsCents,
            long interestCents,
            long matchCents,
            long receivedCents) {}

    /**
     * An income report that was run.
     *
     * @param kind                  always {@code income}: tells it from a spending report
     * @param rows                  every line counted, oldest first
     * @param receivedCents         take-home, one-offs and interest together
     * @param estimatedCents        how much of what was received is an estimate, or dated after today
     * @param paychecksWithoutGross paychecks with no gross amount to count: the gross and taxes are then of fewer paychecks than the take-home
     */
    public record Report(
            String kind,
            LocalDate from,
            LocalDate to,
            IncomeGroupBy groupBy,
            List<GroupView> groups,
            List<Row> rows,
            int count,
            long grossCents,
            long taxesCents,
            long deductionsCents,
            long takeHomeCents,
            long oneOffsCents,
            long interestCents,
            long matchCents,
            long receivedCents,
            long estimatedCents,
            int paychecksWithoutGross) {}

    /** @throws ApiException when the report names an income source or an account that is not there */
    public Report run(ReportDefinition definition) {
        List<Source> sources = income.all();
        List<Account> allAccounts = accounts.all();
        requireKnown(definition.sourceIds(), sources.stream().map(Source::id).collect(Collectors.toSet()), "an income source");
        requireKnown(definition.accountIds(), allAccounts.stream().map(Account::id).collect(Collectors.toSet()), "an account");

        LocalDate today = pay.today();
        Dates dates = definition.dates(today);
        Set<IncomeInclude> include = Set.copyOf(definition.incomeInclude());
        Map<Long, Source> sourceById = sources.stream().collect(Collectors.toMap(Source::id, source -> source));

        List<Row> rows = new ArrayList<>();
        if (include.contains(IncomeInclude.PAYCHECKS) || include.contains(IncomeInclude.MATCH)) {
            List<Paycheck> paychecks = pay.paychecks(dates.from(), dates.to());
            Map<Paycheck, Stub> stubOf = stubsFor(paychecks, sources);
            for (Paycheck paycheck : paychecks) {
                boolean estimate = paycheck.estimate() || paycheck.payDate().isAfter(today);
                if (include.contains(IncomeInclude.PAYCHECKS)) {
                    rows.add(paycheckRow(paycheck, sourceById.get(paycheck.incomeSourceId()), stubOf.get(paycheck), estimate));
                }
                if (include.contains(IncomeInclude.MATCH)) {
                    for (Contribution contribution : paycheck.contributions()) {
                        if (PayCalendar.EMPLOYER_MATCH.equals(contribution.kind())) {
                            rows.add(new Row(
                                    "match", paycheck.payDate(), "Employer match · " + paycheck.sourceName(), paycheck.incomeSourceId(),
                                    contribution.toAccountId(), contribution.amountCents(), null, null, null, false,
                                    paycheck.payDate().isAfter(today)));
                        }
                    }
                }
            }
        }
        if (include.contains(IncomeInclude.ONE_OFFS)) {
            for (Entry entry : entries.between(dates.from(), dates.to())) {
                // Money that pays back an expense is that expense coming back, not income.
                if (entry.kind() == EntryKind.MONEY_IN && entry.reimbursesEntryId() == null) {
                    rows.add(new Row(
                            "one_off", entry.date(), entry.description(), null, entry.accountId(), entry.amountCents(), null, null, null,
                            false, entry.estimate() || entry.date().isAfter(today)));
                }
            }
        }
        if (include.contains(IncomeInclude.INTEREST)) {
            for (Account account : allAccounts) {
                if (!AccountType.holdsCash(account)) {
                    continue;
                }
                for (Ledger.Transaction transaction : ledger.transactions(account, dates.from(), dates.to())) {
                    if (INTEREST_KIND.equals(transaction.kind()) && transaction.amountCents() > 0) {
                        // Interest is worked out from the balance and the rate; the bank's own figure replaces it when a balance is entered.
                        rows.add(new Row(
                                "interest", transaction.date(), "Interest · " + account.name(), null, account.id(),
                                transaction.amountCents(), null, null, null, false, true));
                    }
                }
            }
        }
        rows.removeIf(row -> !definition.sourceIds().isEmpty() && (row.sourceId() == null || !definition.sourceIds().contains(row.sourceId()))
                || !definition.accountIds().isEmpty() && !definition.accountIds().contains(row.accountId()));
        rows.sort(Comparator.comparing(Row::date).thenComparing(Row::description, String.CASE_INSENSITIVE_ORDER));

        IncomeGroupBy groupBy = definition.incomeGroupBy();
        List<Period> periods = groupBy == IncomeGroupBy.PAY_PERIOD ? pay.periods(dates.from(), dates.to()).periods() : List.of();
        List<Line> lines = rows.stream().map(row -> line(row, key(row, groupBy, periods))).toList();
        boolean inKeyOrder = groupBy == IncomeGroupBy.MONTH || groupBy == IncomeGroupBy.YEAR || groupBy == IncomeGroupBy.PAY_PERIOD;
        IncomeReport.Result result = IncomeReport.summarize(lines, inKeyOrder);

        Labels labels = new Labels(sources, allAccounts, periods);
        Sums total = result.total();
        return new Report(
                "income",
                dates.from(),
                dates.to(),
                groupBy,
                result.groups().stream().map(group -> labels.view(group, groupBy)).toList(),
                rows,
                result.count(),
                total.gross().cents(),
                total.taxes().cents(),
                total.deductions().cents(),
                total.takeHome().cents(),
                total.oneOffs().cents(),
                total.interest().cents(),
                total.match().cents(),
                total.received().cents(),
                rows.stream().filter(row -> row.estimate() && !row.kind().equals("match")).mapToLong(Row::amountCents).sum(),
                result.paychecksWithoutGross());
    }

    /** A paycheck with what was earned and what came out: the stub's figures when it has one, the source's own otherwise. */
    private static Row paycheckRow(Paycheck paycheck, Source source, Stub stub, boolean estimate) {
        String description = paycheck.sourceName() + " paycheck";
        if (stub != null) {
            Totals totals = Totals.of(stub.lines().stream().map(StubLine::line).toList());
            boolean hasEarnings = stub.lines().stream().anyMatch(line -> line.line().section() == PayStub.Section.EARNINGS);
            return new Row(
                    "paycheck", paycheck.payDate(), description, paycheck.incomeSourceId(), paycheck.depositAccountId(),
                    paycheck.netCents(),
                    hasEarnings ? (Long) totals.gross().cents() : null,
                    hasEarnings ? (Long) totals.taxes().cents() : null,
                    hasEarnings ? (Long) totals.preTax().plus(totals.afterTax()).cents() : null,
                    true, estimate);
        }
        Pay terms = source == null ? null : source.termsOn(paycheck.scheduledDate()).pay();
        Long gross = terms == null ? null : terms.grossCents();
        if (gross == null) {
            return new Row(
                    "paycheck", paycheck.payDate(), description, paycheck.incomeSourceId(), paycheck.depositAccountId(),
                    paycheck.netCents(), null, null, null, false, estimate);
        }
        long deductions = terms.deductions().stream().mapToLong(Deduction::amountCents).sum();
        // Whatever is not take-home and not a deduction went to taxes; never less than nothing, when the figures do not agree.
        long taxes = Math.max(0, gross - deductions - paycheck.netCents());
        return new Row(
                "paycheck", paycheck.payDate(), description, paycheck.incomeSourceId(), paycheck.depositAccountId(),
                paycheck.netCents(), gross, taxes, deductions, false, estimate);
    }

    /** The saved stub each paycheck has, if any: the one dated nearest it, within a few days. */
    private Map<Paycheck, Stub> stubsFor(List<Paycheck> paychecks, List<Source> sources) {
        Map<Paycheck, Stub> found = new HashMap<>();
        for (Source source : sources) {
            List<Paycheck> own = paychecks.stream().filter(paycheck -> paycheck.incomeSourceId() == source.id()).toList();
            if (own.isEmpty()) {
                continue;
            }
            for (Stub stub : stubs.ofSource(source.id())) {
                if (stub.status() != Status.SAVED || stub.payDate() == null) {
                    continue;
                }
                own.stream()
                        .filter(paycheck -> Math.abs(ChronoUnit.DAYS.between(stub.payDate(), paycheck.payDate())) <= DAYS_AROUND_PAYDAY)
                        .min(Comparator.comparingLong(paycheck -> Math.abs(ChronoUnit.DAYS.between(stub.payDate(), paycheck.payDate()))))
                        // Stubs come newest first: the first one found for a paycheck is kept.
                        .ifPresent(paycheck -> found.putIfAbsent(paycheck, stub));
            }
        }
        return found;
    }

    private static Line line(Row row, String key) {
        Kind kind = switch (row.kind()) {
            case "paycheck" -> Kind.PAYCHECK;
            case "one_off" -> Kind.ONE_OFF;
            case "interest" -> Kind.INTEREST;
            default -> Kind.MATCH;
        };
        return new Line(kind, key, Money.ofCents(row.amountCents()), money(row.grossCents()), money(row.taxesCents()),
                money(row.deductionsCents()));
    }

    private static Money money(Long cents) {
        return cents == null ? null : Money.ofCents(cents);
    }

    private static String key(Row row, IncomeGroupBy groupBy, List<Period> periods) {
        return switch (groupBy) {
            case MONTH -> YearMonth.from(row.date()).toString();
            case YEAR -> String.valueOf(row.date().getYear());
            case SOURCE -> row.sourceId() == null ? null : String.valueOf(row.sourceId());
            case ACCOUNT -> String.valueOf(row.accountId());
            case PAY_PERIOD -> periods.stream()
                    .filter(period -> !row.date().isBefore(period.start()) && !row.date().isAfter(period.end()))
                    .map(period -> period.start().toString())
                    .findFirst()
                    .orElse(null);
        };
    }

    private static void requireKnown(List<Long> ids, Set<Long> known, String what) {
        if (!known.containsAll(ids)) {
            throw ApiException.badRequest("This report filters by " + what
                    + " that is no longer there. Please choose its filters again.");
        }
    }

    /** Turns a group's key into words and a color. */
    private static final class Labels {
        private final Map<Long, Source> sources = new HashMap<>();
        private final Map<Long, Account> accounts = new HashMap<>();
        private final Map<String, Period> periods = new HashMap<>();

        Labels(List<Source> allSources, List<Account> allAccounts, List<Period> allPeriods) {
            allSources.forEach(source -> sources.put(source.id(), source));
            allAccounts.forEach(account -> accounts.put(account.id(), account));
            allPeriods.forEach(period -> periods.put(period.start().toString(), period));
        }

        GroupView view(Group group, IncomeGroupBy groupBy) {
            String label;
            String color = null;
            switch (groupBy) {
                case MONTH -> label = YearMonth.parse(group.key()).format(MONTH);
                case YEAR -> label = group.key();
                case SOURCE -> {
                    Source source = group.key() == null ? null : sources.get(Long.valueOf(group.key()));
                    label = group.key() == null ? "Not from a paycheck" : source == null ? "An income that was removed" : source.name();
                }
                case ACCOUNT -> {
                    Account account = accounts.get(Long.valueOf(group.key()));
                    label = account == null ? "An account that was removed" : account.name();
                    color = account == null ? null : account.color();
                }
                case PAY_PERIOD -> {
                    Period period = group.key() == null ? null : periods.get(group.key());
                    label = period == null
                            ? "Outside your pay periods"
                            : period.start().format(DAY) + " – " + period.end().format(DAY_AND_YEAR);
                }
                default -> throw new IllegalStateException("Unknown grouping");
            }
            Sums sums = group.sums();
            return new GroupView(
                    group.key(), label, color, group.count(), sums.gross().cents(), sums.taxes().cents(), sums.deductions().cents(),
                    sums.takeHome().cents(), sums.oneOffs().cents(), sums.interest().cents(), sums.match().cents(),
                    sums.received().cents());
        }
    }

    /** The account each row landed in, by name, for the exports; in the rows' order. */
    public List<String> accountNames(Report report) {
        Map<Long, String> names = accounts.all().stream().collect(Collectors.toMap(Account::id, Account::name));
        return report.rows().stream().map(row -> names.getOrDefault(row.accountId(), "")).toList();
    }

    /** What an include choice is called in a sentence. */
    static String words(IncomeInclude include) {
        return switch (include) {
            case PAYCHECKS -> "paychecks";
            case ONE_OFFS -> "one-off money in";
            case MATCH -> "employer match";
            case INTEREST -> "interest";
        };
    }

    /** A row's kind in words, for the exports. */
    static String kind(String kind) {
        return switch (kind) {
            case "paycheck" -> "Paycheck";
            case "one_off" -> "One-off";
            case "interest" -> "Interest";
            default -> "Employer match";
        };
    }
}
