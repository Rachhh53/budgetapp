package app.budget.server.dashboard;

import app.budget.engine.reports.AnnualCosts.Basis;
import app.budget.server.accounts.Account;
import app.budget.server.accounts.AccountType;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.assets.NetWorthCalculator;
import app.budget.server.assets.NetWorthCalculator.Summary;
import app.budget.server.buckets.BucketsRepository;
import app.budget.server.buckets.BucketsRepository.Bucket;
import app.budget.server.categories.CategoriesRepository;
import app.budget.server.categories.CategoriesRepository.Category;
import app.budget.server.income.IncomeRepository;
import app.budget.server.income.IncomeRepository.Source;
import app.budget.server.income.PayCalendar;
import app.budget.server.items.ItemCalendar.Occurrence;
import app.budget.server.items.ItemKind;
import app.budget.server.ledger.Ledger;
import app.budget.server.ledger.Ledger.AccountPoint;
import app.budget.server.ledger.Ledger.PeriodDetail;
import app.budget.server.ledger.Ledger.PeriodSummary;
import app.budget.server.ledger.Ledger.PromoEnd;
import app.budget.server.plans.PlanService;
import app.budget.server.reports.AnnualCostsRunner;
import app.budget.server.reports.ReportDefinition;
import app.budget.server.reports.ReportRunner;
import app.budget.server.settings.SettingKey;
import app.budget.server.settings.SettingsRepository;
import app.budget.server.stubs.PayStubsRepository;
import app.budget.server.stubs.PayStubsRepository.Status;
import app.budget.server.stubs.PayStubsRepository.Stub;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jdbi.v3.core.Jdbi;

/**
 * The dashboard (spec §8): four figures, spending by category, net worth over time, and what
 * is coming up or needs attention. Nothing here is saved or worked out afresh: every number is
 * one another screen already shows, gathered in one place.
 */
public final class DashboardService {

    /** How far back the net worth line reaches. It runs forward to the look-ahead setting. */
    private static final int MONTHS_BACK = 12;
    /** A balance not entered for this long is worth a nudge. */
    private static final int STALE_AFTER_DAYS = 30;

    private final Jdbi jdbi;
    private final Ledger ledger;
    private final NetWorthCalculator netWorth;
    private final PayCalendar pay;
    private final AccountsRepository accounts;
    private final CategoriesRepository categories;
    private final BucketsRepository buckets;
    private final IncomeRepository income;
    private final PayStubsRepository stubs;
    private final SettingsRepository settings;
    private final ReportRunner reports;
    private final AnnualCostsRunner annualCosts;
    private final PlanService plans;

    public DashboardService(
            Jdbi jdbi,
            Ledger ledger,
            NetWorthCalculator netWorth,
            PayCalendar pay,
            AccountsRepository accounts,
            CategoriesRepository categories,
            BucketsRepository buckets,
            IncomeRepository income,
            PayStubsRepository stubs,
            SettingsRepository settings,
            ReportRunner reports,
            AnnualCostsRunner annualCosts,
            PlanService plans) {
        this.plans = plans;
        this.jdbi = jdbi;
        this.ledger = ledger;
        this.netWorth = netWorth;
        this.pay = pay;
        this.accounts = accounts;
        this.categories = categories;
        this.buckets = buckets;
        this.income = income;
        this.stubs = stubs;
        this.settings = settings;
        this.reports = reports;
        this.annualCosts = annualCosts;
    }

    /** The dates "Spending by category" covers. */
    public enum Range {
        /** The pay period in hand, what is still to come in it included. */
        PERIOD,
        /** The three months up to today. */
        THREE_MONTHS,
        /** January 1 to today. */
        YEAR
    }

    /**
     * @param changeCents how far it has moved since the end of last month; {@code null} when that is not known
     * @param since       the first day of this month; {@code null} with {@code changeCents}
     */
    public record NetWorthFigure(long cents, Long changeCents, LocalDate since) {}

    /**
     * The lowest any checking or savings account is projected to go, from today to the look-ahead.
     *
     * @param periodStart the pay period it happens in
     * @param low         true when that is under the warning set for the account
     */
    public record LowestAhead(long cents, LocalDate periodStart, long accountId, String accountName, boolean low) {}

    /**
     * What went into savings and investment accounts this year.
     *
     * @param transfersCents     transfers into those accounts, less transfers out of them
     * @param contributionsCents taken out of paychecks into them
     * @param matchCents         added by an employer
     */
    public record Saved(long totalCents, long transfersCents, long contributionsCents, long matchCents) {}

    public record Debt(long totalCents, long cardsCents, long loansCents) {}

    /** @param categoryId {@code null} for spending with no category */
    public record CategorySpending(Long categoryId, String name, String color, long totalCents) {}

    /** @param categories top-level categories, largest first; a sub-category counts under the one above it */
    public record Spending(LocalDate from, LocalDate to, long totalCents, List<CategorySpending> categories) {}

    /** @param projected true for a day after today */
    public record WorthPoint(LocalDate date, long netWorthCents, boolean projected) {}

    /**
     * One thing coming up or wanting attention. Which of the fields say something depends on
     * {@code kind}; the screen puts them into words and links to the right place.
     *
     * <ul>
     *   <li>{@code past_due}, {@code due_soon}: a bill paid by hand. {@code name}, {@code date} it is due, {@code amountCents},
     *       {@code itemId}, {@code periodStart}.
     *   <li>{@code low_balance}: {@code name} of the account, {@code amountCents} it dips to, {@code accountId}, {@code periodStart}.
     *   <li>{@code promo_ending}: {@code name} of the promotion, {@code date} it ends, {@code amountCents} still on it then,
     *       {@code accountId}; {@code urgent} for deferred interest.
     *   <li>{@code stub_review}: {@code name} of the income source, {@code date} of the stub (may be null), {@code count} of
     *       lines to check, {@code sourceId}, {@code stubId}.
     *   <li>{@code goal_short}: {@code name} of the bucket, {@code date} of its goal, {@code amountCents} it is projected to be
     *       short by, {@code goalCents}, {@code accountId}.
     *   <li>{@code price_up}: {@code name} of the item, {@code count} its rise in basis points, {@code amountCents} more than
     *       last year, {@code itemId}.
     *   <li>{@code balance_stale}: {@code name} of the account longest without a balance, {@code date} it was last entered,
     *       {@code count} of accounts in the same state, {@code accountId}.
     *   <li>{@code plan_reminder}: time to plan next year. {@code name} is the year, {@code date} the day it starts.
     *   <li>{@code plan_active}: a plan has just taken effect. {@code name} is its year, {@code date} the day it did.
     * </ul>
     */
    public record Attention(
            String kind,
            String name,
            LocalDate date,
            Long amountCents,
            Long goalCents,
            Integer count,
            boolean urgent,
            Long accountId,
            Long itemId,
            Long sourceId,
            Long stubId,
            LocalDate periodStart) {

        private static Attention of(String kind, String name, LocalDate date, Long amountCents) {
            return new Attention(kind, name, date, amountCents, null, null, false, null, null, null, null, null);
        }

        private Attention forAccount(long id) {
            return new Attention(kind, name, date, amountCents, goalCents, count, urgent, id, itemId, sourceId, stubId, periodStart);
        }

        private Attention forItem(long id) {
            return new Attention(kind, name, date, amountCents, goalCents, count, urgent, accountId, id, sourceId, stubId, periodStart);
        }

        private Attention inPeriod(LocalDate start) {
            return new Attention(kind, name, date, amountCents, goalCents, count, urgent, accountId, itemId, sourceId, stubId, start);
        }

        private Attention asUrgent() {
            return new Attention(kind, name, date, amountCents, goalCents, count, true, accountId, itemId, sourceId, stubId, periodStart);
        }

        private Attention towards(long cents) {
            return new Attention(kind, name, date, amountCents, cents, count, urgent, accountId, itemId, sourceId, stubId, periodStart);
        }

        private Attention forStub(long source, long stub) {
            return new Attention(kind, name, date, amountCents, goalCents, count, urgent, accountId, itemId, source, stub, periodStart);
        }

        private Attention counting(int howMany) {
            return new Attention(kind, name, date, amountCents, goalCents, howMany, urgent, accountId, itemId, sourceId, stubId, periodStart);
        }
    }

    /** @param lowestAhead {@code null} until a checking or savings account has a balance to project */
    public record Dashboard(
            LocalDate today,
            Range range,
            NetWorthFigure netWorth,
            LowestAhead lowestAhead,
            Saved saved,
            Debt debt,
            Spending spending,
            List<WorthPoint> netWorthOverTime,
            List<Attention> attention) {}

    public Dashboard dashboard(Range range) {
        // Gathering this reads the same tables many hundreds of times. Each read opens the database
        // file afresh unless one is already open on this thread, so one is held open for all of them:
        // that alone is the difference between a few seconds and a fraction of one.
        return jdbi.withHandle(handle -> gather(range));
    }

    private Dashboard gather(Range range) {
        LocalDate today = pay.today();
        List<Account> open = accounts.all().stream().filter(account -> !account.archived()).toList();
        Map<Long, List<AccountPoint>> ahead = ledger.allSeries(today, pay.horizonEnd());

        Summary now = netWorth.summary(today);
        List<Summary> series = netWorth.series(days(today));
        LocalDate lastMonthEnd = YearMonth.from(today).minusMonths(1).atEndOfMonth();
        Optional<Summary> before = series.stream().filter(point -> point.date().equals(lastMonthEnd)).findFirst();

        return new Dashboard(
                today,
                range,
                new NetWorthFigure(
                        now.netWorthCents(),
                        before.map(point -> now.netWorthCents() - point.netWorthCents()).orElse(null),
                        before.map(point -> today.withDayOfMonth(1)).orElse(null)),
                lowestAhead(open, ahead),
                saved(open, today),
                new Debt(now.liabilitiesCents(), now.cardsCents(), now.loansCents()),
                spending(range, today),
                series.stream().map(point -> new WorthPoint(point.date(), point.netWorthCents(), point.date().isAfter(today))).toList(),
                attention(open, ahead, today));
    }

    /** Each month's last day from a year back to the look-ahead, and today among them. */
    private List<LocalDate> days(LocalDate today) {
        TreeSet<LocalDate> days = new TreeSet<>();
        days.add(today);
        YearMonth last = YearMonth.from(pay.horizonEnd());
        for (YearMonth month = YearMonth.from(today).minusMonths(MONTHS_BACK); !month.isAfter(last); month = month.plusMonths(1)) {
            days.add(month.atEndOfMonth());
        }
        return List.copyOf(days);
    }

    private static LowestAhead lowestAhead(List<Account> open, Map<Long, List<AccountPoint>> ahead) {
        LowestAhead lowest = null;
        for (Account account : open) {
            if (!AccountType.holdsCash(account)) {
                continue;
            }
            for (AccountPoint point : ahead.getOrDefault(account.id(), List.of())) {
                if (lowest == null || point.lowestCents() < lowest.cents()) {
                    lowest = new LowestAhead(
                            point.lowestCents(), point.start(), account.id(), account.name(),
                            account.lowBalanceThresholdCents() != null && point.lowestCents() < account.lowBalanceThresholdCents());
                }
            }
        }
        return lowest;
    }

    private Saved saved(List<Account> open, LocalDate today) {
        long transfers = 0;
        long contributions = 0;
        long match = 0;
        for (Account account : open) {
            AccountType type = AccountType.valueOf(account.type().toUpperCase(Locale.ROOT));
            if (type != AccountType.SAVINGS && type != AccountType.INVESTMENT) {
                continue;
            }
            for (Ledger.Transaction line : ledger.transactions(account, today.withDayOfYear(1), today)) {
                switch (line.kind()) {
                    // Signed: money moved back out comes off, and a move between two of these accounts counts for nothing.
                    case "transfer" -> transfers += line.amountCents();
                    case PayCalendar.CONTRIBUTION -> contributions += line.amountCents();
                    case PayCalendar.EMPLOYER_MATCH -> match += line.amountCents();
                    default -> { }
                }
            }
        }
        return new Saved(transfers + contributions + match, transfers, contributions, match);
    }

    private Spending spending(Range range, LocalDate today) {
        LocalDate from;
        LocalDate to = today;
        switch (range) {
            case PERIOD -> {
                Optional<PayCalendar.Period> current = pay.periods(today, today).periods().stream()
                        .filter(PayCalendar.Period::current)
                        .findFirst();
                // With no paycheck set up there are no pay periods: this month stands in for one.
                from = current.map(PayCalendar.Period::start).orElse(today.withDayOfMonth(1));
                to = current.map(PayCalendar.Period::end).orElse(YearMonth.from(today).atEndOfMonth());
            }
            case THREE_MONTHS -> from = today.minusMonths(3).plusDays(1);
            case YEAR -> from = today.withDayOfYear(1);
            default -> throw new IllegalStateException("Unknown range");
        }
        ReportRunner.Report report = reports.run(ReportDefinition.of(new ReportDefinition.Input(
                "custom", from, to, List.of("one_offs", "bills", "budgets"), null, null, null, null, null, "category", "table",
                "spending", null, null, null, null, null, null)));

        Map<Long, Category> byId = new HashMap<>();
        categories.all().forEach(category -> byId.put(category.id(), category));
        Map<Long, Long> totals = new LinkedHashMap<>();
        for (ReportRunner.Row row : report.rows()) {
            Category category = row.categoryId() == null ? null : byId.get(row.categoryId());
            Long top = category == null ? null : category.parentId() == null ? (Long) category.id() : category.parentId();
            totals.merge(top, row.amountCents(), Long::sum);
        }
        List<CategorySpending> spent = new ArrayList<>();
        totals.forEach((id, cents) -> {
            Category category = id == null ? null : byId.get(id);
            spent.add(new CategorySpending(
                    id, category == null ? "No category" : category.name(), category == null ? null : category.color(), cents));
        });
        spent.sort(Comparator.comparingLong(CategorySpending::totalCents).reversed().thenComparing(CategorySpending::name));
        return new Spending(from, to, report.totalCents(), spent);
    }

    /** Most pressing first: what is late, what is about to go wrong, what is due, then what could do with a look. */
    private List<Attention> attention(List<Account> open, Map<Long, List<AccountPoint>> ahead, LocalDate today) {
        List<Attention> list = new ArrayList<>();
        int highlightDays = Integer.parseInt(settings.get(SettingKey.UNPAID_HIGHLIGHT_DAYS));
        List<Occurrence> byHand = unpaidByHand(today, today.plusDays(highlightDays));
        for (Occurrence bill : byHand) {
            if (bill.dueDate().isBefore(today)) {
                list.add(Attention.of("past_due", bill.name(), bill.dueDate(), bill.amountCents()).forItem(bill.itemId()).inPeriod(bill.periodStart()));
            }
        }

        for (Account account : open) {
            Long threshold = account.lowBalanceThresholdCents();
            if (threshold == null || !AccountType.holdsCash(account)) {
                continue;
            }
            // The first pay period it dips in: the one to do something about.
            ahead.getOrDefault(account.id(), List.of()).stream()
                    .filter(point -> point.lowestCents() < threshold)
                    .findFirst()
                    .ifPresent(point -> list.add(
                            Attention.of("low_balance", account.name(), point.start(), point.lowestCents()).forAccount(account.id()).inPeriod(point.start())));
        }

        int promoDays = Integer.parseInt(settings.get(SettingKey.PROMO_WARNING_DAYS));
        Set<Long> seenPromos = new HashSet<>();
        for (PeriodSummary period : ledger.periods(today, today.plusDays(promoDays)).periods()) {
            for (PromoEnd promo : period.promosEnding()) {
                if (seenPromos.add(promo.promoId()) && !promo.endDate().isBefore(today) && !promo.endDate().isAfter(today.plusDays(promoDays))) {
                    Attention ending = Attention.of("promo_ending", promo.label(), promo.endDate(), promo.remainingCents()).forAccount(promo.accountId());
                    list.add(promo.deferredInterest() ? ending.asUrgent() : ending);
                }
            }
        }

        if (highlightDays > 0) {
            for (Occurrence bill : byHand) {
                if (!bill.dueDate().isBefore(today) && !bill.dueDate().isAfter(today.plusDays(highlightDays))) {
                    list.add(Attention.of("due_soon", bill.name(), bill.dueDate(), bill.amountCents()).forItem(bill.itemId()).inPeriod(bill.periodStart()));
                }
            }
        }

        for (Source source : income.all()) {
            for (Stub stub : stubs.ofSource(source.id())) {
                if (stub.status() == Status.REVIEW) {
                    int toCheck = (int) stub.lines().stream().filter(line -> !line.line().sure()).count();
                    list.add(Attention.of("stub_review", source.name(), stub.payDate(), null).counting(toCheck).forStub(source.id(), stub.id()));
                }
            }
        }

        Map<Long, Account> openById = new HashMap<>();
        open.forEach(account -> openById.put(account.id(), account));
        Map<LocalDate, Map<Long, Long>> heldOn = new HashMap<>();
        for (Bucket bucket : buckets.all()) {
            if (bucket.archived() || bucket.goalCents() == null || bucket.goalDate() == null || bucket.goalDate().isBefore(today)
                    || !openById.containsKey(bucket.accountId())) {
                continue;
            }
            long projected = heldOn.computeIfAbsent(bucket.goalDate(), ledger::bucketBalances).getOrDefault(bucket.id(), 0L);
            if (projected < bucket.goalCents()) {
                list.add(Attention.of("goal_short", bucket.name(), bucket.goalDate(), bucket.goalCents() - projected)
                        .towards(bucket.goalCents()).forAccount(bucket.accountId()));
            }
        }

        AnnualCostsRunner.Increase increase = annualCosts.run(Basis.CALENDAR).biggestIncrease();
        if (increase != null) {
            list.add(Attention.of("price_up", increase.name(), null, increase.increaseCents()).counting(increase.changeBp()).forItem(increase.itemId()));
        }

        List<Account> stale = open.stream()
                .filter(account -> account.latestBalance() != null && account.latestBalance().date().isBefore(today.minusDays(STALE_AFTER_DAYS)))
                .sorted(Comparator.comparing(account -> account.latestBalance().date()))
                .toList();
        if (!stale.isEmpty()) {
            Account oldest = stale.getFirst();
            list.add(Attention.of("balance_stale", oldest.name(), oldest.latestBalance().date(), null).counting(stale.size()).forAccount(oldest.id()));
        }

        PlanService.Overview planning = plans.overview();
        if (planning.reminderDue()) {
            list.add(Attention.of("plan_reminder", String.valueOf(planning.nextYear()), planning.nextStartsOn(), null));
        }
        plans.newlyActive().ifPresent(plan ->
                list.add(Attention.of("plan_active", String.valueOf(plan.year()), plan.activatedOn(), null)));
        return list;
    }

    /**
     * Every bill paid by hand that has not been ticked off, each once, earliest first: from the
     * pay period before the one in hand up to {@code through}, with whatever was carried into
     * them. Those are the lists being worked through; anything older and unticked is taken as
     * paid, as it is when a balance is entered (spec §5.3). A budget has no payment to tick, and
     * autopay is taken as paid.
     */
    private List<Occurrence> unpaidByHand(LocalDate today, LocalDate through) {
        LocalDate from = pay.periods(today, today).periods().stream()
                .filter(PayCalendar.Period::current)
                .findFirst()
                .map(current -> current.start().minusDays(1))
                .orElse(today);
        Map<String, Occurrence> found = new LinkedHashMap<>();
        for (PayCalendar.Period period : pay.periods(from, through).periods()) {
            Optional<PeriodDetail> detail = ledger.period(period.start());
            if (detail.isEmpty()) {
                continue;
            }
            List<Occurrence> listed = new ArrayList<>(detail.get().carriedOver());
            listed.addAll(detail.get().occurrences());
            for (Occurrence occurrence : listed) {
                boolean byHand = !occurrence.paid() && !occurrence.skipped() && !occurrence.autopay()
                        && occurrence.kind() != ItemKind.BUDGET && occurrence.kind() != ItemKind.BUCKET_MOVE;
                if (byHand) {
                    found.putIfAbsent(occurrence.itemId() + "-" + occurrence.scheduledDate(), occurrence);
                }
            }
        }
        return found.values().stream()
                .sorted(Comparator.comparing(Occurrence::dueDate).thenComparing(Occurrence::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
}
