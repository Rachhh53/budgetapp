package app.budget.server.planner;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.cards.CardInterest;
import app.budget.engine.cards.CardPayment;
import app.budget.engine.interest.SavingsInterest;
import app.budget.engine.investments.EmployerMatch;
import app.budget.engine.loans.Amortization;
import app.budget.engine.planner.ContributionGrowth;
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
            boolean planned) {}

    /**
     * Every debt with the extra going to one at a time and each payoff rolling into the next.
     *
     * @param debts              in the order they are gone
     * @param debtFreeDate       when the last is gone with the extra; {@code null} when one never is
     * @param interestSavedCents across all of them; {@code null} when one is never paid off
     */
    public record Payoff(Order order, List<PayoffDebt> debts, LocalDate debtFreeDate, LocalDate debtFreeDateBefore, Long interestSavedCents) {}

    /** An account left out of the comparison. */
    public record Hidden(long accountId, String name, String type) {}

    public record Comparison(
            LocalDate today, long extraCents, int years, List<Flag> flags, List<Option> options, Payoff payoff, List<Hidden> hidden) {}

    /**
     * @param growthBp the yearly rate to assume for an investment or savings account in place of its own, by account id
     */
    public Comparison compare(long extraCents, int years, Order order, Map<Long, Integer> growthBp) {
        // Balances are worked out from many reads; one open connection serves them all.
        return jdbi.withHandle(handle -> gather(extraCents, years, order, growthBp));
    }

    /** True for an account of a kind the comparison can hold: anything but checking. */
    public static boolean compared(Account account) {
        return type(account) != AccountType.CHECKING;
    }

    /** A debt ready to be followed, or the reason it cannot be. */
    private record Owed(Account account, long balanceCents, Integer rateBp, PayoffPlan.Debt debt) {}

    private Comparison gather(long extraCents, int years, Order order, Map<Long, Integer> growthBp) {
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
                    with.payoffDate(), without.payoffDate(), with.paysOff(), without.paysOff(), false));
        }
        // In the order they are gone; one that never is goes last.
        timeline.sort(Comparator.comparing((PayoffDebt debt) -> debt.payoffDate() == null ? LocalDate.MAX : debt.payoffDate())
                .thenComparing(PayoffDebt::name, String.CASE_INSENSITIVE_ORDER));
        Payoff payoff = new Payoff(
                order, timeline, after.debtFreeDate(), before.debtFreeDate(),
                before.paysOff() && after.paysOff() ? (Long) before.totalInterest().minus(after.totalInterest()).cents() : null);

        return new Comparison(today, extraCents, years, flags, options, payoff, hidden);
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
                    account.id(), account.name(), account.color(), balance, rateBp, null, null, plannedBy, plannedBy, true, true, true));
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

        Matched matched = Matched.NONE;
        for (Source source : sources) {
            Pay terms = source.termsOn(today).pay();
            Match match = terms.match();
            if (match == null || match.toAccountId() != account.id() || match.mode() != MatchMode.FORMULA || terms.grossCents() == null) {
                continue;
            }
            Matched here = MatchOnExtra.perMonth(
                    EmployerMatch.formula(BasisPoints.of(match.matchBp()), BasisPoints.of(match.capBpOfGross())),
                    Money.ofCents(terms.grossCents()), putIn(terms, match), extra, paychecksAYear.getOrDefault(source.id(), 0));
            // The extra goes in through one paycheck: the one where it earns the most.
            if (here.match().compareTo(matched.match()) > 0) {
                matched = here;
            }
        }
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
        pay.paychecks(today.plusDays(1), today.plusYears(1)).forEach(paycheck -> counts.merge(paycheck.incomeSourceId(), 1, Integer::sum));
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
