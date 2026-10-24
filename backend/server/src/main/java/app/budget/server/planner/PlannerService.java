package app.budget.server.planner;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.cards.CardInterest;
import app.budget.engine.cards.CardPayment;
import app.budget.engine.interest.SavingsInterest;
import app.budget.engine.investments.EmployerMatch;
import app.budget.engine.loans.Amortization;
import app.budget.engine.planner.ContributionGrowth;
import app.budget.engine.planner.DebtOrInvest;
import app.budget.engine.planner.MatchOnExtra;
import app.budget.engine.planner.MatchOnExtra.Matched;
import app.budget.engine.planner.PayoffPlan;
import app.budget.engine.planner.PayoffPlan.Order;
import app.budget.engine.planner.PayoffPlan.Outcome;
import app.budget.engine.planner.PayoffPlan.Result;
import app.budget.server.accounts.Account;
import app.budget.server.accounts.AccountType;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.accounts.InterestRepository;
import app.budget.server.accounts.InterestRepository.RateKind;
import app.budget.server.cards.CardsRepository;
import app.budget.server.cards.CardsRepository.Promo;
import app.budget.server.income.IncomeRepository;
import app.budget.server.income.IncomeRepository.Match;
import app.budget.server.income.IncomeRepository.MatchMode;
import app.budget.server.income.IncomeRepository.Pay;
import app.budget.server.income.IncomeRepository.Source;
import app.budget.server.income.PayCalendar;
import app.budget.server.income.PayCalendar.MatchFigures;
import app.budget.server.items.ItemsRepository;
import app.budget.server.items.ItemsRepository.Item;
import app.budget.server.ledger.Ledger;
import app.budget.server.ledger.Ledger.AccountBalance;
import app.budget.server.ledger.Ledger.Transaction;
import app.budget.server.loans.LoansRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;
import org.jdbi.v3.core.Jdbi;

/**
 * Debt &amp; decisions (spec §5.9): where an extra amount each month could go, compared across the
 * debts and the accounts that could take it. Nothing is saved and nothing is changed: every
 * figure is worked out from the accounts as they stand today, and all of it is an estimate for
 * comparing, not advice.
 */
public final class PlannerService {

    /** The comparison periods on offer, in years. */
    public static final Set<Integer> YEARS = Set.of(1, 3, 5, 10, 20, 30);

    /** 100% a year: beyond any growth worth assuming, and a slip of the decimal point still fits to be seen and fixed. */
    public static final int MAX_GROWTH_BP = 10_000;

    public static final String DEBT = "debt";
    public static final String INVEST = "invest";
    public static final String SAVE = "save";

    /** A loan with no monthly payment entered: there is nothing to add the extra to yet. */
    public static final String NO_PAYMENT = "no_payment";

    private static final String UNCLAIMED_MATCH = "unclaimed_match";
    private static final String PROMO_ENDING = "promo_ending";

    private static final BasisPoints NO_RATE = BasisPoints.of(0);
    private static final int MONTHS_IN_YEAR = 12;
    private static final BigDecimal WHOLE_BP = BigDecimal.valueOf(10_000);

    private final Jdbi jdbi;
    private final PlannerRepository planner;
    private final AccountsRepository accounts;
    private final InterestRepository interest;
    private final LoansRepository loans;
    private final CardsRepository cards;
    private final IncomeRepository income;
    private final ItemsRepository items;
    private final Ledger ledger;
    private final PayCalendar pay;

    public PlannerService(
            Jdbi jdbi,
            PlannerRepository planner,
            AccountsRepository accounts,
            InterestRepository interest,
            LoansRepository loans,
            CardsRepository cards,
            IncomeRepository income,
            ItemsRepository items,
            Ledger ledger,
            PayCalendar pay) {
        this.jdbi = jdbi;
        this.planner = planner;
        this.accounts = accounts;
        this.interest = interest;
        this.loans = loans;
        this.cards = cards;
        this.income = income;
        this.items = items;
        this.ledger = ledger;
        this.pay = pay;
    }

    /**
     * Something worth knowing before choosing, shown first. Which fields say something depends on {@code kind}:
     *
     * <ul>
     *   <li>{@code unclaimed_match}: {@code name} of the income source, {@code accountName} the match is paid into,
     *       {@code amountCents} more a month that would still be matched, {@code matchCents} a month it would bring,
     *       {@code capBp} the share of pay that is matched, {@code putInBp} the share put in now.
     *   <li>{@code promo_ending}: {@code name} of the promotion, {@code accountName} of its card, {@code date} it ends,
     *       {@code amountCents} still on it, {@code planned} when a payoff is among the recurring payments (then
     *       {@code perPaycheckCents} is what it pays), {@code urgent} for deferred interest.
     * </ul>
     */
    public record Flag(
            String kind,
            String name,
            String accountName,
            Long accountId,
            Long sourceId,
            LocalDate date,
            Long amountCents,
            Long matchCents,
            Integer capBp,
            Integer putInBp,
            Long perPaycheckCents,
            boolean planned,
            boolean urgent) {}

    /**
     * One place the extra could go. A debt has the debt figures, an account to invest or save in
     * has the others; the rest are {@code null}.
     *
     * @param kind                 {@code debt}, {@code invest} or {@code save}
     * @param type                 the account's type
     * @param balanceCents         what is owed, or held, today
     * @param rateBp               a debt's rate in force today, or the yearly rate the value is worked out at;
     *                             {@code null} when none is known (it is then taken as 0%)
     * @param accountRateBp        for investing or saving, the account's own assumed growth or APY; {@code null} when it has none
     * @param problem              {@code no_payment} when it cannot be compared yet; {@code null} otherwise
     * @param paymentCents         what goes to the debt each month now
     * @param interestSavedCents   interest no longer paid with the extra; {@code null} when the debt is never paid off without it
     * @param payoffDate           when it is gone with the extra
     * @param payoffDateBefore     when it is gone without
     * @param monthsSooner         the months between the two
     * @param freedCents           what is free each month once it is gone: its payment and the extra
     * @param rateAfterBp          the rate once a promotional rate ends, on {@code rateChangesOn}
     * @param valueCents           what the extra comes to after the period, the match and assumed growth included
     * @param putInCents           the extra itself over the period
     * @param growthCents          what the assumed rate adds
     * @param matchCents           what an employer adds over the period
     * @param matchedPerMonthCents how much of a month's extra is matched
     * @param matchPerMonthCents   what the employer adds for it each month
     */
    public record Option(
            long accountId,
            String name,
            String color,
            String type,
            String subtypeLabel,
            String kind,
            long balanceCents,
            Integer rateBp,
            Integer accountRateBp,
            String problem,
            Long paymentCents,
            Long interestSavedCents,
            LocalDate payoffDate,
            LocalDate payoffDateBefore,
            Integer monthsSooner,
            Long freedCents,
            Boolean paysOff,
            Boolean paysOffBefore,
            Integer rateAfterBp,
            LocalDate rateChangesOn,
            Long valueCents,
            Long putInCents,
            Long growthCents,
            Long matchCents,
            Long matchedPerMonthCents,
            Long matchPerMonthCents) {}

    /**
     * A debt on the payoff timeline.
     *
     * @param interestSavedCents interest no longer paid on this debt with the extra; {@code null} when it is never paid off
     *                           one way or the other, or its payoff is already planned
     * @param planned true for a card whose promotional balance already has a payoff among the recurring
     *                payments: it is shown as gone when the promotion ends, and takes none of the extra
     */
    public record PayoffDebt(
            long accountId,
            String name,
            String color,
            long balanceCents,
            Integer rateBp,
            Integer rateAfterBp,
            LocalDate rateChangesOn,
            LocalDate payoffDate,
            LocalDate payoffDateBefore,
            boolean paysOff,
            boolean paysOffBefore,
            boolean planned,
            Long interestSavedCents) {}

    /**
     * Every debt with the extra going to one at a time and each payoff rolling into the next.
     *
     * @param debts              in the order they are gone
     * @param debtFreeDate       when the last is gone with the extra; {@code null} when one never is
     * @param interestSavedCents across all of them; {@code null} when one is never paid off
     * @param owedOverTime       what they all owe together at each month's end, with the extra and without, from
     *                           this month until the last is gone both ways (at most {@value #CHART_MONTHS} months)
     */
    public record Payoff(
            Order order, List<PayoffDebt> debts, LocalDate debtFreeDate, LocalDate debtFreeDateBefore, Long interestSavedCents,
            List<OwedPoint> owedOverTime) {}

    /** One month on the "Debt over time" chart; {@code month} is its first day. */
    public record OwedPoint(LocalDate month, long withCents, long withoutCents) {}

    /** How far the chart runs at most, when a debt is never paid off one way or the other: thirty years. */
    static final int CHART_MONTHS = 360;

    /** An account left out of the comparison. */
    public record Hidden(long accountId, String name, String type) {}

    public record Comparison(
            LocalDate today, long extraCents, int years, List<Flag> flags, List<Option> options, Payoff payoff, List<Hidden> hidden,
            DebtOrInvestView debtOrInvest) {}

    /** The longest "Pay off debt or invest?" runs, in years. */
    public static final int MOST_PLAN_YEARS = 30;

    /** The split tried for the best one, in steps of 5%. */
    private static final int SPLIT_STEP_BP = 500;

    /** All of it, in basis points. */
    private static final int ALL_BP = 10_000;

    public static final String INVEST_ALL = "invest_all";
    public static final String PAY_OFF_FIRST = "pay_off_first";
    public static final String SPLIT = "split";

    /** Nothing to weigh: no debt that can be followed, or nowhere to invest. */
    public static final String NO_DEBTS = "no_debts";
    public static final String NOWHERE_TO_INVEST = "nowhere_to_invest";

    /**
     * How "Pay off debt or invest?" is to be worked out; any of it left out takes the usual.
     *
     * @param investAccountId where the money not going to debts goes; {@code null} for the account the extra does most in
     * @param splitBp         the share of the spare money for debts in the split, 0 to 10000 ({@code null}: 5000)
     * @param growthBp        the yearly growth to assume there; {@code null} for the account's (or the one typed on its card)
     * @param years           how long, 1 to {@value #MOST_PLAN_YEARS}; {@code null} for the comparison's own period
     * @param freedMoves      whether a paid-off debt's payment stays in the plan; {@code null}: true
     */
    public record PlanSettings(Long investAccountId, Integer splitBp, Integer growthBp, Integer years, Boolean freedMoves) {

        public static final PlanSettings USUAL = new PlanSettings(null, null, null, null, null);
    }

    /**
     * One plan, at the end of the period.
     *
     * @param kind         {@code invest_all}, {@code pay_off_first} or {@code split}
     * @param debtShareBp    the share of the spare money that went to debts while any were owed
     * @param betterOffCents how much better off than today it leaves you: what is invested plus the debt paid down. Not
     *                       net worth, which counts everything already owned (Rachel, 2026-10-23: "how can I have a
     *                       networth on accounts screen of like 500k today and then that graph shows me as negative")
     * @param paidDownCents  what is owed today less what is still owed at the end
     * @param debtFreeDate   when the last debt is gone; {@code null} when one is still owed at the end
     */
    public record PlanView(
            String kind, int debtShareBp, long betterOffCents, long investedCents, long putInCents, long matchCents, long growthCents,
            long paidDownCents, long owedCents, long interestCents, LocalDate debtFreeDate) {}

    /**
     * One month's end on the chart: how much better off than today each plan leaves you so far ({@code ...Cents}), and
     * what it is made of, what is invested and what is still owed, so the chart can say how each point is reached.
     */
    public record PlanPoint(
            LocalDate month, long investAllCents, long payOffFirstCents, long splitCents, long investAllInvestedCents,
            long payOffFirstInvestedCents, long splitInvestedCents, long investAllOwedCents, long payOffFirstOwedCents, long splitOwedCents) {}

    /** An account the money could be invested in, with the growth it is worked out at. */
    public record InvestChoice(long accountId, String name, String color, String kind, Integer growthBp) {}

    /**
     * Pay off debt or invest, every plan spending the same each month and measured on the same day (§5.9; Rachel,
     * 2026-10-23). {@code reason} says why there is nothing to weigh ({@code no_debts} or {@code nowhere_to_invest});
     * the rest is then empty.
     *
     * @param owedTodayCents what the debts in the plans owe today
     * @param monthlyCents  what every plan spends each month at the start: the debts' payments and the extra
     * @param best          the plan that comes to the most
     * @param bestSplitBp   the share for debts, in steps of 5%, that comes to the most over the period
     * @param points        every month's end, the first month first
     */
    public record DebtOrInvestView(
            String reason, Long investAccountId, Integer growthBp, Integer accountGrowthBp, Integer years, Integer splitBp,
            Boolean freedMoves, Long owedTodayCents, Long monthlyCents, List<InvestChoice> choices, List<PlanView> plans, String best, Integer bestSplitBp,
            Integer bestSplitNetDiffCents, List<PlanPoint> points) {

        static DebtOrInvestView nothing(String reason) {
            return new DebtOrInvestView(reason, null, null, null, null, null, null, null, null, List.of(), List.of(), null, null, null, List.of());
        }
    }

    /**
     * @param growthBp the yearly rate to assume for an investment or savings account in place of its own, by account id
     */
    public Comparison compare(long extraCents, int years, Order order, Map<Long, Integer> growthBp, PlanSettings plans) {
        // Balances are worked out from many reads; one open connection serves them all.
        return jdbi.withHandle(handle -> gather(extraCents, years, order, growthBp, plans));
    }

    /** True for an account of a kind the comparison can hold: anything but checking. */
    public static boolean compared(Account account) {
        return type(account) != AccountType.CHECKING;
    }

    /** A debt ready to be followed, or the reason it cannot be. */
    private record Owed(Account account, long balanceCents, Integer rateBp, PayoffPlan.Debt debt) {}

    private Comparison gather(long extraCents, int years, Order order, Map<Long, Integer> growthBp, PlanSettings settings) {
        LocalDate today = pay.today();
        Money extra = Money.ofCents(extraCents);
        Set<Long> hiddenIds = planner.hidden();
        Map<Long, Long> balances = new HashMap<>();
        for (AccountBalance balance : ledger.balancesOn(today)) {
            balances.put(balance.accountId(), balance.balanceCents());
        }
        List<Account> open = accounts.all().stream().filter(account -> !account.archived()).toList();
        Map<Long, String> names = new HashMap<>();
        open.forEach(account -> names.put(account.id(), account.name()));
        List<Source> sources = income.all();
        Map<Long, Integer> paychecksAYear = paychecksAYear(today);

        List<Flag> flags = new ArrayList<>(unclaimedMatches(sources, paychecksAYear, names, today));
        List<Hidden> hidden = new ArrayList<>();
        List<Owed> owed = new ArrayList<>();
        List<PayoffDebt> plannedCards = new ArrayList<>();
        List<Option> growing = new ArrayList<>();

        Map<Long, LoansRepository.Settings> loanSettings = loans.allSettings();
        for (Account account : open) {
            AccountType type = type(account);
            long balance = balances.getOrDefault(account.id(), 0L);
            boolean isHidden = hiddenIds.contains(account.id());
            if (isHidden && type != AccountType.CHECKING) {
                hidden.add(new Hidden(account.id(), account.name(), account.type()));
            }
            switch (type) {
                case LOAN -> {
                    if (!isHidden && balance > 0) {
                        owed.add(loan(account, balance, loanSettings.get(account.id()), today));
                    }
                }
                case CREDIT_CARD -> card(account, balance, isHidden, today, flags, plannedCards).ifPresent(owed::add);
                case INVESTMENT, SAVINGS -> {
                    if (!isHidden) {
                        growing.add(growing(account, type, balance, extra, years, growthBp.get(account.id()), sources, paychecksAYear, today));
                    }
                }
                default -> { }
            }
        }

        List<PayoffPlan.Debt> followed = owed.stream().map(Owed::debt).filter(debt -> debt != null).toList();
        Result before = PayoffPlan.asTheyAre(followed);
        Result after = PayoffPlan.withExtra(followed, extra, order);

        List<Option> options = new ArrayList<>();
        owed.stream()
                // The dearest first, as they would be paid; one with no rate entered last.
                .sorted(Comparator.comparing((Owed debt) -> debt.rateBp() == null ? -1 : debt.rateBp()).reversed()
                        .thenComparing(debt -> debt.account().name(), String.CASE_INSENSITIVE_ORDER))
                .forEach(debt -> options.add(debtOption(debt, extra, order, before)));
        options.addAll(growing);

        List<PayoffDebt> timeline = new ArrayList<>(plannedCards);
        for (Owed debt : owed) {
            if (debt.debt() == null) {
                continue;
            }
            Outcome with = after.of(debt.account().id());
            Outcome without = before.of(debt.account().id());
            timeline.add(new PayoffDebt(
                    debt.account().id(), debt.account().name(), debt.account().color(), debt.balanceCents(), debt.rateBp(),
                    debt.debt().rateAfter() == null ? null : (Integer) debt.debt().rateAfter().value(), debt.debt().rateChangesOn(),
                    with.payoffDate(), without.payoffDate(), with.paysOff(), without.paysOff(), false,
                    with.paysOff() && without.paysOff() ? (Long) without.interest().minus(with.interest()).cents() : null));
        }
        // In the order they are gone; one that never is goes last.
        timeline.sort(Comparator.comparing((PayoffDebt debt) -> debt.payoffDate() == null ? LocalDate.MAX : debt.payoffDate())
                .thenComparing(PayoffDebt::name, String.CASE_INSENSITIVE_ORDER));
        Payoff payoff = new Payoff(
                order, timeline, after.debtFreeDate(), before.debtFreeDate(),
                before.paysOff() && after.paysOff() ? (Long) before.totalInterest().minus(after.totalInterest()).cents() : null,
                owedOverTime(after, before));

        DebtOrInvestView debtOrInvest = debtOrInvest(
                followed, growing, extra, years, order, settings, sources, paychecksAYear, today);
        return new Comparison(today, extraCents, years, flags, options, payoff, hidden, debtOrInvest);
    }

    /**
     * The three plans side by side (§5.9): invest it all, pay the debts off first, and the split chosen; and the split
     * that comes to the most. A card's typed growth is the one used unless a growth is given here.
     */
    private DebtOrInvestView debtOrInvest(
            List<PayoffPlan.Debt> debts, List<Option> growing, Money extra, int years, Order order, PlanSettings settings,
            List<Source> sources, Map<Long, Integer> paychecksAYear, LocalDate today) {
        if (debts.isEmpty()) {
            return DebtOrInvestView.nothing(NO_DEBTS);
        }
        if (growing.isEmpty()) {
            return DebtOrInvestView.nothing(NOWHERE_TO_INVEST);
        }
        // The account the extra does most in, unless another is chosen (or the one chosen is no longer on offer).
        Option into = growing.stream()
                .filter(option -> settings.investAccountId() != null && option.accountId() == settings.investAccountId())
                .findFirst()
                .orElseGet(() -> growing.stream().max(Comparator.comparingLong(Option::valueCents)).orElseThrow());
        int growthBp = settings.growthBp() != null ? settings.growthBp() : into.rateBp() == null ? 0 : into.rateBp();
        int planYears = settings.years() != null ? settings.years() : years;
        int splitBp = settings.splitBp() != null ? settings.splitBp() : ALL_BP / 2;
        boolean freedMoves = settings.freedMoves() == null || settings.freedMoves();

        Map<Long, Money> matchByAmount = new HashMap<>();
        DebtOrInvest.Investment investment = new DebtOrInvest.Investment(
                BasisPoints.of(growthBp),
                amount -> matchByAmount.computeIfAbsent(
                        amount.cents(), cents -> matched(into.accountId(), Money.ofCents(cents), sources, paychecksAYear, today).match()));
        YearMonth from = debts.stream().map(debt -> YearMonth.from(debt.firstPayment())).min(YearMonth::compareTo).orElseThrow();
        int months = planYears * MONTHS_IN_YEAR;

        IntFunction<DebtOrInvest.Outcome> follow = shareBp -> DebtOrInvest.follow(
                debts, extra, order, investment, new DebtOrInvest.Plan(BasisPoints.of(shareBp), freedMoves), from, months);
        DebtOrInvest.Outcome all = follow.apply(0);
        DebtOrInvest.Outcome first = follow.apply(ALL_BP);
        DebtOrInvest.Outcome split = follow.apply(splitBp);

        // Every split in steps of 5%; the smaller share wins a tie, as money invested can still be reached.
        int bestSplit = 0;
        Money bestNet = all.net();
        for (int share = SPLIT_STEP_BP; share <= ALL_BP; share += SPLIT_STEP_BP) {
            Money net = share == ALL_BP ? first.net() : follow.apply(share).net();
            if (net.compareTo(bestNet) > 0) {
                bestSplit = share;
                bestNet = net;
            }
        }

        // Every plan starts from the same debts, so better off than today ranks them as the end figure would.
        Money owedToday = debts.stream().map(debt -> debt.balance().isNegative() ? Money.ZERO : debt.balance()).reduce(Money.ZERO, Money::plus);
        long owedTodayCents = owedToday.cents();
        List<PlanView> plans = List.of(
                planView(INVEST_ALL, 0, all, owedToday), planView(PAY_OFF_FIRST, ALL_BP, first, owedToday), planView(SPLIT, splitBp, split, owedToday));
        String best = plans.stream().max(Comparator.comparingLong(PlanView::betterOffCents)).orElseThrow().kind();
        List<PlanPoint> points = new ArrayList<>(months);
        for (int i = 0; i < months; i++) {
            DebtOrInvest.Point a = all.months().get(i);
            DebtOrInvest.Point b = first.months().get(i);
            DebtOrInvest.Point c = split.months().get(i);
            points.add(new PlanPoint(
                    a.month().atDay(1), a.net().cents() + owedTodayCents, b.net().cents() + owedTodayCents, c.net().cents() + owedTodayCents, a.invested().cents(),
                    b.invested().cents(), c.invested().cents(), a.owed().cents(), b.owed().cents(), c.owed().cents()));
        }
        long monthly = debts.stream().mapToLong(debt -> debt.payment().cents()).sum() + extra.cents();
        List<InvestChoice> choices = growing.stream()
                .map(option -> new InvestChoice(option.accountId(), option.name(), option.color(), option.kind(), option.rateBp()))
                .toList();
        return new DebtOrInvestView(
                null, into.accountId(), growthBp, into.accountRateBp(), planYears, splitBp, freedMoves, owedTodayCents, monthly, choices, plans, best,
                bestSplit, (int) Math.min(Integer.MAX_VALUE, bestNet.minus(split.net()).cents()), List.copyOf(points));
    }

    private static PlanView planView(String kind, int shareBp, DebtOrInvest.Outcome outcome, Money owedToday) {
        Money paidDown = owedToday.minus(outcome.owed());
        return new PlanView(
                kind, shareBp, outcome.invested().plus(paidDown).cents(), outcome.invested().cents(), outcome.putIn().cents(),
                outcome.match().cents(), outcome.growth().cents(), paidDown.cents(), outcome.owed().cents(), outcome.interest().cents(),
                outcome.debtFreeDate());
    }

    /** What an employer adds to a month's amount put into an account: through the paycheck where it earns the most. */
    private static Matched matched(long accountId, Money amount, List<Source> sources, Map<Long, Integer> paychecksAYear, LocalDate today) {
        Matched matched = Matched.NONE;
        for (Source source : sources) {
            Pay terms = source.termsOn(today).pay();
            Match match = terms.match();
            if (match == null || match.toAccountId() != accountId || match.mode() != MatchMode.FORMULA || terms.grossCents() == null) {
                continue;
            }
            Matched here = MatchOnExtra.perMonth(
                    EmployerMatch.formula(BasisPoints.of(match.matchBp()), BasisPoints.of(match.capBpOfGross())),
                    Money.ofCents(terms.grossCents()), putIn(terms, match), amount, paychecksAYear.getOrDefault(source.id(), 0));
            if (here.match().compareTo(matched.match()) > 0) {
                matched = here;
            }
        }
        return matched;
    }

    /**
     * The two month-by-month totals side by side. The one that ends sooner stays at what it ended on
     * (nothing, once paid off) until the other ends too.
     */
    private static List<OwedPoint> owedOverTime(Result with, Result without) {
        List<PayoffPlan.Owed> a = with.owed();
        List<PayoffPlan.Owed> b = without.owed();
        if (a.isEmpty() || b.isEmpty()) {
            return List.of();
        }
        int months = Math.min(Math.max(a.size(), b.size()), CHART_MONTHS + 1);
        List<OwedPoint> points = new ArrayList<>(months);
        for (int i = 0; i < months; i++) {
            PayoffPlan.Owed withOwed = a.get(Math.min(i, a.size() - 1));
            PayoffPlan.Owed withoutOwed = b.get(Math.min(i, b.size() - 1));
            // Both start in the same month, so the i-th month is the same on each side.
            YearMonth month = a.getFirst().month().plusMonths(i);
            points.add(new OwedPoint(month.atDay(1), withOwed.total().cents(), withoutOwed.total().cents()));
        }
        return List.copyOf(points);
    }

    /** A loan as its own page works it out: today's balance and rate, the payment less escrow, on its payment day. */
    private Owed loan(Account account, long balance, LoansRepository.Settings settings, LocalDate today) {
        Optional<BasisPoints> rate = rateToday(account.id(), RateKind.LOAN_RATE, today);
        Integer rateBp = rate.map(BasisPoints::value).orElse(null);
        long toTheLoan = settings == null ? 0 : settings.paymentCents() - settings.escrowCents();
        if (toTheLoan <= 0) {
            return new Owed(account, balance, rateBp, null);
        }
        LocalDate next = SavingsInterest.creditDates(settings.paymentDay(), today, today.plusMonths(1)).getFirst();
        return new Owed(account, balance, rateBp,
                new PayoffPlan.Debt(account.id(), Money.ofCents(balance), rate.orElse(NO_RATE), Money.ofCents(toTheLoan), next));
    }

    /**
     * A card that is carrying a balance. Empty for one that owes nothing, is hidden, is paid in
     * full every month (there is nothing to pay down), or whose promotional balance already has a
     * payoff planned; that last one goes on the timeline as it is. Its promotions are flagged
     * whatever else is true of it.
     */
    private Optional<Owed> card(
            Account account, long balance, boolean isHidden, LocalDate today, List<Flag> flags, List<PayoffDebt> plannedCards) {
        if (balance <= 0) {
            return Optional.empty();
        }
        Money owedNow = Money.ofCents(balance);
        Optional<BasisPoints> apr = rateToday(account.id(), RateKind.APR, today);
        BasisPoints regular = apr.orElse(NO_RATE);
        List<Promo> promos = cards.promos(account.id());
        List<app.budget.engine.cards.Promo> terms = promos.stream().map(Promo::terms).toList();

        Promo covering = null;
        LocalDate plannedBy = null;
        for (Promo promo : promos) {
            app.budget.engine.cards.Promo own = promo.terms();
            Money remaining = CardInterest.remaining(own, owedNow, terms, today, regular);
            if (!own.activeOn(today) || remaining.isZero() || remaining.isNegative()) {
                continue;
            }
            Optional<Item> payoff = Optional.ofNullable(promo.payoffItemId()).flatMap(items::find).filter(item -> !item.archived());
            flags.add(new Flag(
                    PROMO_ENDING, promo.label(), account.name(), account.id(), null, promo.endDate(), remaining.cents(), null, null, null,
                    payoff.map(item -> (Long) item.versionOn(today).terms().amountCents()).orElse(null),
                    payoff.isPresent(), promo.deferredInterest()));
            if (payoff.isPresent() && (plannedBy == null || promo.endDate().isAfter(plannedBy))) {
                plannedBy = promo.endDate();
            }
            // A promotion on only part of the balance leaves the rest at the regular APR; the card is then compared at that.
            if (remaining.compareTo(owedNow) >= 0 && (covering == null || promo.endDate().isBefore(covering.endDate()))) {
                covering = promo;
            }
        }
        if (isHidden) {
            return Optional.empty();
        }
        Integer rateBp = covering != null ? (Integer) covering.rateBp() : apr.map(BasisPoints::value).orElse(null);
        if (plannedBy != null) {
            plannedCards.add(new PayoffDebt(
                    account.id(), account.name(), account.color(), balance, rateBp, null, null, plannedBy, plannedBy, true, true, true, null));
            return Optional.empty();
        }
        CardsRepository.Settings settings = cards.settings(account.id()).orElse(null);
        boolean paidInFull = settings != null && settings.paymentItemId() != null
                && (settings.paymentMode() == CardPayment.Mode.FULL_BALANCE || settings.paymentMode() == CardPayment.Mode.STATEMENT_BALANCE);
        if (paidInFull) {
            return Optional.empty();
        }

        // What is paid into it is what next month's payments come to, held level; with none set up, its estimated minimum.
        YearMonth nextMonth = YearMonth.from(today).plusMonths(1);
        List<Transaction> payments = ledger.transactions(account, nextMonth.atDay(1), nextMonth.atEndOfMonth()).stream()
                .filter(line -> line.amountCents() < 0 && !"balance".equals(line.kind()))
                .toList();
        long paid = payments.stream().mapToLong(line -> -line.amountCents()).sum();
        LocalDate first = payments.stream().map(Transaction::date).min(LocalDate::compareTo)
                .orElse(nextMonth.atDay(Math.min(today.getDayOfMonth(), nextMonth.lengthOfMonth())));
        BasisPoints rateNow = covering != null ? BasisPoints.of(covering.rateBp()) : regular;
        if (paid == 0) {
            paid = CardPayment.estimatedMinimum(
                    owedNow, Amortization.monthlyInterest(owedNow, rateNow),
                    cards.minimumRules().getOrDefault(account.id(), CardPayment.MinimumRule.USUAL)).cents();
        }
        PayoffPlan.Debt debt = covering == null
                ? new PayoffPlan.Debt(account.id(), owedNow, rateNow, Money.ofCents(paid), first)
                : new PayoffPlan.Debt(
                        account.id(), owedNow, rateNow, Money.ofCents(paid), first,
                        // The promotion's last day still has its rate; the next day has what follows.
                        covering.endDate().plusDays(1),
                        covering.rateAfterBp() != null ? BasisPoints.of(covering.rateAfterBp()) : regular);
        return Optional.of(new Owed(account, balance, rateBp, debt));
    }

    private Option debtOption(Owed owed, Money extra, Order order, Result before) {
        Account account = owed.account();
        PayoffPlan.Debt debt = owed.debt();
        if (debt == null) {
            return new Option(
                    account.id(), account.name(), account.color(), account.type(), account.subtypeLabel(), DEBT, owed.balanceCents(),
                    owed.rateBp(), null, NO_PAYMENT, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null);
        }
        Outcome without = before.of(account.id());
        Outcome with = PayoffPlan.withExtra(List.of(debt), extra, order).of(account.id());
        boolean both = with.paysOff() && without.paysOff();
        Integer monthsSooner = both && with.payoffDate() != null && without.payoffDate() != null
                ? (Integer) (int) ChronoUnit.MONTHS.between(YearMonth.from(with.payoffDate()), YearMonth.from(without.payoffDate()))
                : null;
        return new Option(
                account.id(), account.name(), account.color(), account.type(), account.subtypeLabel(), DEBT, owed.balanceCents(),
                owed.rateBp(), null, null,
                debt.payment().cents(),
                both ? (Long) without.interest().minus(with.interest()).cents() : null,
                with.payoffDate(), without.payoffDate(), monthsSooner,
                debt.payment().plus(extra).cents(),
                with.paysOff(), without.paysOff(),
                debt.rateAfter() == null ? null : (Integer) debt.rateAfter().value(), debt.rateChangesOn(),
                null, null, null, null, null, null);
    }

    /** What the extra comes to in an investment or savings account, with any employer match it would earn. */
    private Option growing(
            Account account, AccountType type, long balance, Money extra, int years, Integer assumedBp, List<Source> sources,
            Map<Long, Integer> paychecksAYear, LocalDate today) {
        RateKind kind = type == AccountType.INVESTMENT ? RateKind.GROWTH : RateKind.APY;
        Integer own = rateToday(account.id(), kind, today).map(BasisPoints::value).orElse(null);
        Integer used = assumedBp != null ? assumedBp : own;
        int months = years * MONTHS_IN_YEAR;

        // The extra goes in through one paycheck: the one where it earns the most.
        Matched matched = matched(account.id(), extra, sources, paychecksAYear, today);
        ContributionGrowth.Value value = ContributionGrowth.of(extra.plus(matched.match()), BasisPoints.of(used == null ? 0 : used), months);
        long putIn = extra.cents() * months;
        long matchTotal = matched.match().cents() * months;
        return new Option(
                account.id(), account.name(), account.color(), account.type(), account.subtypeLabel(),
                type == AccountType.INVESTMENT ? INVEST : SAVE, balance, used, own, null,
                null, null, null, null, null, null, null, null, null, null,
                value.total().cents(), putIn, value.growth().cents(), matchTotal, matched.matchedPart().cents(), matched.match().cents());
    }

    /** A match that is not being earned in full: the first thing to know about where extra money could go. */
    private static List<Flag> unclaimedMatches(List<Source> sources, Map<Long, Integer> paychecksAYear, Map<Long, String> names, LocalDate today) {
        List<Flag> flags = new ArrayList<>();
        for (Source source : sources) {
            Pay terms = source.termsOn(today).pay();
            Match match = terms.match();
            Optional<MatchFigures> figures = PayCalendar.matchFigures(terms);
            // An archived account is not one to send more money to.
            if (match == null || figures.isEmpty() || figures.get().contributeMoreCents() <= 0 || !names.containsKey(match.toAccountId())) {
                continue;
            }
            BigDecimal aMonth = BigDecimal.valueOf(paychecksAYear.getOrDefault(source.id(), 0))
                    .divide(BigDecimal.valueOf(MONTHS_IN_YEAR), MathContext.DECIMAL128);
            long more = Money.ofCents(figures.get().contributeMoreCents()).times(aMonth).cents();
            long brings = Money.ofCents(figures.get().mostPossibleCents() - figures.get().perPaycheckCents()).times(aMonth).cents();
            if (more <= 0) {
                continue;
            }
            Integer putInBp = terms.grossCents() == null || terms.grossCents() <= 0
                    ? null
                    : (Integer) putIn(terms, match).toDecimal().multiply(WHOLE_BP)
                            .divide(Money.ofCents(terms.grossCents()).toDecimal(), 0, Money.ROUNDING).intValueExact();
            flags.add(new Flag(
                    UNCLAIMED_MATCH, source.name(), names.get(match.toAccountId()), match.toAccountId(), source.id(), null, more, brings,
                    match.capBpOfGross(), putInBp, null, false, false));
        }
        return flags;
    }

    /** What the employee puts into the account the match goes to, from one paycheck. */
    private static Money putIn(Pay terms, Match match) {
        return terms.deductions().stream()
                .filter(deduction -> deduction.toAccountId() != null && deduction.toAccountId() == match.toAccountId())
                .map(deduction -> Money.ofCents(deduction.amountCents()))
                .reduce(Money.ZERO, Money::plus);
    }

    /** How many paychecks each income source brings in the year from tomorrow. */
    private Map<Long, Integer> paychecksAYear(LocalDate today) {
        Map<Long, Integer> counts = new HashMap<>();
        // Regular paychecks only: the extra is shared over the paychecks that come every time, not a bonus.
        pay.paychecks(today.plusDays(1), today.plusYears(1)).stream()
                .filter(PayCalendar.Paycheck::scheduled)
                .forEach(paycheck -> counts.merge(paycheck.incomeSourceId(), 1, Integer::sum));
        return counts;
    }

    private Optional<BasisPoints> rateToday(long accountId, RateKind kind, LocalDate today) {
        // Newest first, so the first one that has started is the one in force.
        return interest.rates(accountId).stream()
                .filter(rate -> rate.kind() == kind && !rate.effectiveDate().isAfter(today))
                .findFirst()
                .map(rate -> BasisPoints.of(rate.rateBp()));
    }

    private static AccountType type(Account account) {
        return AccountType.valueOf(account.type().toUpperCase(Locale.ROOT));
    }
}
