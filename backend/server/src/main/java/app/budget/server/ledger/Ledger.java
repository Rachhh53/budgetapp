package app.budget.server.ledger;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.buckets.BucketProjector;
import app.budget.engine.buckets.BucketProjector.BucketBalance;
import app.budget.engine.buckets.BucketProjector.InterestDestination;
import app.budget.engine.buckets.BucketProjector.InterestRule;
import app.budget.engine.buckets.BucketProjector.PeriodBuckets;
import app.budget.engine.cards.CardInterest;
import app.budget.engine.cards.CardPayment;
import app.budget.engine.cards.CardProjector;
import app.budget.engine.cards.StatementCycle;
import app.budget.engine.interest.InterestTerms;
import app.budget.engine.interest.InterestTerms.RateChange;
import app.budget.engine.investments.GainLoss;
import app.budget.engine.loans.LoanProjector;
import app.budget.engine.projection.BalanceAnchor;
import app.budget.engine.projection.BalanceProjector;
import app.budget.engine.projection.InterestCredit;
import app.budget.engine.projection.Movement;
import app.budget.engine.projection.PeriodBalance;
import app.budget.engine.projection.Projection;
import app.budget.engine.schedule.PayPeriod;
import app.budget.server.accounts.Account;
import app.budget.server.accounts.Account.BalanceEntry;
import app.budget.server.accounts.AccountType;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.accounts.InterestRepository;
import app.budget.server.accounts.InterestRepository.Destination;
import app.budget.server.accounts.InterestRepository.Rate;
import app.budget.server.accounts.InterestRepository.RateKind;
import app.budget.server.accounts.InterestRepository.Settings;
import app.budget.server.api.Inputs;
import app.budget.server.buckets.BucketsRepository;
import app.budget.server.buckets.BucketsRepository.Bucket;
import app.budget.server.cards.CardsRepository;
import app.budget.server.entries.EntriesRepository;
import app.budget.server.entries.EntriesRepository.Entry;
import app.budget.server.entries.EntryKind;
import app.budget.server.income.PayCalendar;
import app.budget.server.income.PayCalendar.Paycheck;
import app.budget.server.items.ItemCalendar;
import app.budget.server.items.ItemCalendar.Occurrence;
import app.budget.server.items.ItemKind;
import app.budget.server.items.ItemsRepository;
import app.budget.server.items.ItemsRepository.Item;
import app.budget.server.ledger.BucketMoves.Move;
import app.budget.server.loans.LoansRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Puts everything that moves money together, pay period by pay period: paychecks, recurring
 * items, one-off entries, interest, buckets and the balances the user entered. The arithmetic
 * is the engine's ({@link BalanceProjector}, {@link BucketProjector}); this class decides what
 * moves, in which account or bucket, on which day. Like the calendars it reads from, nothing
 * here is saved.
 */
public final class Ledger {

    /** Accounts whose balance is what is owed: spending raises it and a payment lowers it. */
    private static final Set<String> OWED = Set.of(Inputs.code(AccountType.CREDIT_CARD), Inputs.code(AccountType.LOAN));

    private static final String INTEREST_LABEL = "Interest";
    private static final String GROWTH_LABEL = "Assumed growth";

    /** What a transaction's {@code kind} says for savings interest, and for an investment's assumed growth. */
    private static final String INTEREST_KIND = "interest";
    private static final String GROWTH_KIND = "growth";
    private static final String BALANCE_LABEL = "Balance you entered";
    private static final String ONE_OFF_LABEL = "One-off entry";

    /** What a transaction's {@code kind} says for the amounts worked out for a credit card or a loan. */
    public static final String CARD_INTEREST_KIND = "card_interest";
    public static final String DEFERRED_INTEREST_KIND = "deferred_interest";
    public static final String CARD_ESTIMATE_KIND = "card_estimate";
    public static final String LOAN_INTEREST_KIND = "loan_interest";
    public static final String ESCROW_KIND = "escrow";
    public static final String ASSUMED_PAYMENT_KIND = "loan_payment";

    private static final String CARD_INTEREST_LABEL = "Estimated interest";
    private static final String DEFERRED_INTEREST_LABEL = "Deferred interest";
    private static final String CARD_ESTIMATE_LABEL = "Estimated spending";
    private static final String ESCROW_LABEL = "Escrow (insurance and taxes)";
    private static final String ASSUMED_PAYMENT_LABEL = "Monthly payment (taken as made)";

    /** How far ahead to look for a card's next payment: past the next statement and its due date. */
    private static final int NEXT_PAYMENT_MONTHS = 2;

    private final AccountsRepository accounts;
    private final EntriesRepository entries;
    private final ItemsRepository items;
    private final ItemCalendar itemCalendar;
    private final PayCalendar pay;
    private final BucketsRepository buckets;
    private final InterestRepository interest;
    private final CardsRepository cards;
    private final LoansRepository loans;

    public Ledger(
            AccountsRepository accounts,
            EntriesRepository entries,
            ItemsRepository items,
            ItemCalendar itemCalendar,
            PayCalendar pay,
            BucketsRepository buckets,
            InterestRepository interest,
            CardsRepository cards,
            LoansRepository loans) {
        this.accounts = accounts;
        this.entries = entries;
        this.items = items;
        this.itemCalendar = itemCalendar;
        this.pay = pay;
        this.buckets = buckets;
        this.interest = interest;
        this.cards = cards;
        this.loans = loans;
    }

    /**
     * One account across one pay period; {@code ending = starting + in − out}.
     *
     * <p>For a credit card or loan the balance is what is owed, so {@code inCents} is what was
     * added to it (charges) and {@code outCents} what came off it (payments).
     *
     * @param startingOn     the period's first day, or the day of a balance entered during it
     * @param entered        true when the starting balance is one the user entered during the period
     * @param lowestCents    the lowest end-of-day balance in the period
     * @param bucketsCents   what the account's buckets hold at the end of the period
     * @param availableCents the ending balance less what is in buckets
     * @param lowBalance     true when the user asked for a warning and the balance dips below it
     * @param builtFromZero  true when no balance was ever entered for the account, so it is added
     *                       up from nothing since the first money that moved in or out of it
     */
    public record AccountPeriod(
            long accountId,
            long startingCents,
            LocalDate startingOn,
            boolean entered,
            long inCents,
            long outCents,
            long endingCents,
            long lowestCents,
            LocalDate lowestOn,
            long bucketsCents,
            long availableCents,
            boolean lowBalance,
            boolean builtFromZero) {}

    /** One bucket across one pay period; {@code endingCents − startingCents} is what the period moved. */
    public record BucketPeriod(long bucketId, long accountId, long startingCents, long endingCents) {}

    /**
     * A month's estimated interest arriving in a checking or savings account, or an investment
     * account's assumed growth over one pay period. Growth builds up every day, so its line is
     * the period's days added together, dated the last of them.
     */
    public record InterestLine(long accountId, LocalDate date, long amountCents, int rateBp) {}

    /**
     * A card's promotional rate running out during a pay period with some of its balance still there.
     *
     * @param remainingCents what is projected to be left of it at the end of that pay period
     */
    public record PromoEnd(
            long accountId, long promoId, String label, LocalDate endDate, boolean deferredInterest, long remainingCents) {}

    /**
     * A pay period in the list of them.
     *
     * @param projectedEndingCents the primary paycheck's account at the end of the period;
     *                             {@code null} until a balance has been entered for it
     * @param availableCents       that ending balance less what is in the account's buckets
     * @param lowBalance           true when that account dips below the warning the user set
     * @param promosEnding         promotional rates that end in this period with a balance left on them
     */
    public record PeriodSummary(
            LocalDate start,
            LocalDate end,
            LocalDate scheduledPayday,
            boolean current,
            long incomeCents,
            List<Paycheck> paychecks,
            Long projectedEndingCents,
            Long lowestCents,
            Long availableCents,
            boolean lowBalance,
            List<PromoEnd> promosEnding) {}

    /** @param primarySourceId {@code null} until an income source exists; then there are no periods either */
    public record Periods(Long primarySourceId, Long primaryAccountId, List<PeriodSummary> periods) {}

    /**
     * Everything in one pay period.
     *
     * @param occurrences recurring items paid from this period, skipped ones included (and flagged)
     * @param carriedOver items from an earlier period that are still unpaid, so their money comes
     *                    out of this one: what makes the figures differ from the list above
     * @param entries     one-off entries dated in this period
     * @param interest    estimated interest counted in this period's figures
     * @param accounts    every account a balance is known for
     * @param buckets     every open bucket
     * @param bucketMoves what moved those buckets during the period, earliest first
     * @param promosEnding promotional rates that end in this period with a balance left on them
     * @param statementsPaid which statement each card payment listed here was sized from, for those
     *                    that pay a statement's balance or its minimum
     * @param unbilled    the current period only: each billed-later item's visits that no invoice covers yet,
     *                    up to today (spec §4.6); empty for any other period
     */
    public record PeriodDetail(
            LocalDate start,
            LocalDate end,
            LocalDate scheduledPayday,
            boolean current,
            Long primaryAccountId,
            List<Paycheck> paychecks,
            List<Occurrence> occurrences,
            List<Occurrence> carriedOver,
            List<Entry> entries,
            List<InterestLine> interest,
            List<AccountPeriod> accounts,
            List<BucketPeriod> buckets,
            List<Move> bucketMoves,
            List<PromoEnd> promosEnding,
            List<StatementPaid> statementsPaid,
            List<Unbilled> unbilled) {}

    /**
     * Visits of something billed later that have built up with no invoice yet.
     *
     * @param since   the first of them
     * @param through the last of them
     */
    public record Unbilled(
            long itemId, String name, long payFromAccountId, int visits, long amountCents, LocalDate since, LocalDate through) {}

    /**
     * The statement a card payment that follows its card was sized from, so the screen can say
     * what the amount is for.
     *
     * @param scheduledDate with {@code itemId}, the occurrence this is about
     * @param closeDate     the day that statement closed, or will close
     * @param entered       true when the statement is one the user entered; false for one the app projected
     */
    public record StatementPaid(long itemId, LocalDate scheduledDate, LocalDate closeDate, boolean entered) {}

    /** One account at the end of one pay period: a point on its chart. */
    public record AccountPoint(LocalDate start, LocalDate end, long endingCents, long lowestCents, long availableCents) {}

    /** The pay periods overlapping {@code from}–{@code through}, each with its projected ending balance. */
    public Periods periods(LocalDate from, LocalDate through) {
        PayCalendar.Periods found = pay.periods(from, through);
        Long primaryAccountId = pay.primaryAccountId().orElse(null);
        Map<LocalDate, AccountPeriod> primary = new HashMap<>();
        Workings workings = workings(payPeriods(found.periods()));
        if (primaryAccountId != null) {
            List<PeriodBalance> balances = workings.projection(primaryAccountId).balances();
            List<AccountPeriod> figures = accountPeriods(workings, accounts.find(primaryAccountId).orElseThrow());
            for (int i = 0; i < balances.size(); i++) {
                primary.put(balances.get(i).period().start(), figures.get(i));
            }
        }
        List<Account> allAccounts = accounts.all();
        List<PeriodSummary> summaries = found.periods().stream()
                .map(period -> {
                    AccountPeriod account = primary.get(period.start());
                    return new PeriodSummary(
                            period.start(), period.end(), period.scheduledPayday(), period.current(),
                            period.incomeCents(), period.paychecks(),
                            account == null ? null : (Long) account.endingCents(),
                            account == null ? null : (Long) account.lowestCents(),
                            account == null ? null : (Long) account.availableCents(),
                            account != null && account.lowBalance(),
                            promosEnding(workings, allAccounts, period.start(), period.end()));
                })
                .toList();
        return new Periods(found.primarySourceId(), primaryAccountId, summaries);
    }

    /** The pay period that opens on {@code start}; empty when no period starts that day. */
    public Optional<PeriodDetail> period(LocalDate start) {
        Optional<PayCalendar.Period> found = pay.periods(start, start).periods().stream()
                .filter(period -> period.start().equals(start))
                .findFirst();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PayCalendar.Period period = found.get();
        Workings workings = workings(payPeriods(List.of(period)));

        List<AccountPeriod> accountPeriods = new ArrayList<>();
        List<InterestLine> interestLines = new ArrayList<>();
        for (Account account : accounts.all()) {
            for (AccountPeriod figures : accountPeriods(workings, account)) {
                accountPeriods.add(figures);
                // Interest dated before a balance entered this period, or on the day of one, is already in that balance.
                List<InterestCredit> counted = workings.projection(account.id()).interest().stream()
                        .filter(credit -> !credit.inEnteredBalance()
                                && !credit.date().isBefore(figures.startingOn())
                                && !credit.date().isAfter(period.end()))
                        .toList();
                if (isInvestment(account.type())) {
                    // Growth is added a little every day: the period's days are one line, not fourteen.
                    if (!counted.isEmpty()) {
                        interestLines.add(new InterestLine(
                                account.id(), counted.getLast().date(),
                                counted.stream().mapToLong(credit -> credit.amount().cents()).sum(),
                                counted.getLast().apy().value()));
                    }
                } else {
                    for (InterestCredit credit : counted) {
                        interestLines.add(new InterestLine(account.id(), credit.date(), credit.amount().cents(), credit.apy().value()));
                    }
                }
            }
        }
        List<BucketPeriod> bucketPeriods = new ArrayList<>();
        workings.buckets().forEach((accountId, byPeriod) -> {
            for (BucketBalance bucket : byPeriod.getFirst().buckets()) {
                bucketPeriods.add(new BucketPeriod(bucket.bucketId(), accountId, bucket.starting().cents(), bucket.ending().cents()));
            }
        });
        bucketPeriods.sort(Comparator.comparing(BucketPeriod::accountId).thenComparing(BucketPeriod::bucketId));

        return Optional.of(new PeriodDetail(
                period.start(),
                period.end(),
                period.scheduledPayday(),
                period.current(),
                pay.primaryAccountId().orElse(null),
                period.paychecks(),
                workings.occurrences().stream().filter(occurrence -> start.equals(occurrence.periodStart())).toList(),
                workings.carried().stream()
                        .filter(carried -> within(carried.countsOn(), period.start(), period.end()))
                        .map(Carried::occurrence)
                        .toList(),
                workings.entries().stream().filter(entry -> within(entry.date(), period.start(), period.end())).toList(),
                interestLines,
                accountPeriods,
                bucketPeriods,
                workings.bucketMoves().stream().filter(move -> within(move.date(), period.start(), period.end())).toList(),
                promosEnding(workings, accounts.all(), period.start(), period.end()),
                // Both lists on the screen can hold a card payment: this period's own, and those carried into it.
                workings.statementsPaid().stream()
                        .filter(paid -> Stream.concat(
                                        workings.occurrences().stream().filter(occurrence -> start.equals(occurrence.periodStart())),
                                        workings.carried().stream()
                                                .filter(carried -> within(carried.countsOn(), period.start(), period.end()))
                                                .map(Carried::occurrence))
                                .anyMatch(occurrence ->
                                        occurrence.itemId() == paid.itemId() && occurrence.scheduledDate().equals(paid.scheduledDate())))
                        .toList(),
                period.current() ? unbilled(pay.today()) : List.of()));
    }

    /**
     * What has built up on each billed-later item with no invoice yet, up to {@code today}. It stays
     * on the current pay period, whichever that is, until an invoice is entered for it (Rachel,
     * 2026-10-14). An item that stopped being billed later still lists what built up before.
     */
    public List<Unbilled> unbilled(LocalDate today) {
        List<Unbilled> unbilled = new ArrayList<>();
        for (Item item : items.all()) {
            boolean everBilledLater = item.versions().stream().anyMatch(version -> version.terms().billedLater());
            if (item.archived() || !everBilledLater) {
                continue;
            }
            List<Occurrence> visits = itemCalendar.unbilled(item, today);
            if (!visits.isEmpty()) {
                unbilled.add(new Unbilled(
                        item.id(), item.name(), item.versionOn(today).terms().payFromAccountId(), visits.size(),
                        visits.stream().mapToLong(Occurrence::amountCents).sum(),
                        visits.getFirst().dueDate(), visits.getLast().dueDate()));
            }
        }
        unbilled.sort(Comparator.comparing(Unbilled::name, String.CASE_INSENSITIVE_ORDER));
        return unbilled;
    }

    /**
     * The promotions that run out within {@code start}–{@code end} with part of their balance
     * projected to be still there. One that will be paid off in time is not worth a flag.
     */
    private static List<PromoEnd> promosEnding(Workings workings, List<Account> allAccounts, LocalDate start, LocalDate end) {
        List<PromoEnd> ending = new ArrayList<>();
        for (Account account : allAccounts) {
            List<CardsRepository.Promo> ofCard = workings.promos().getOrDefault(account.id(), List.of());
            if (account.archived() || ofCard.isEmpty()) {
                continue;
            }
            Optional<PeriodBalance> balance = workings.projection(account.id()).balances().stream()
                    .filter(at -> at.period().start().equals(start))
                    .findFirst();
            if (balance.isEmpty()) {
                continue;
            }
            List<app.budget.engine.cards.Promo> terms = ofCard.stream().map(CardsRepository.Promo::terms).toList();
            BasisPoints apr = aprOn(workings.aprs().get(account.id()), end);
            for (CardsRepository.Promo promo : ofCard) {
                if (!within(promo.endDate(), start, end)) {
                    continue;
                }
                long remaining = CardInterest.remaining(promo.terms(), balance.get().ending(), terms, promo.endDate(), apr).cents();
                if (remaining > 0) {
                    ending.add(new PromoEnd(
                            account.id(), promo.id(), promo.label(), promo.endDate(), promo.deferredInterest(), remaining));
                }
            }
        }
        return ending;
    }

    /** A card's regular APR on {@code date}; zero until one has been entered. */
    private static BasisPoints aprOn(InterestTerms apr, LocalDate date) {
        return apr == null ? BasisPoints.of(0) : apr.rateOn(date).orElse(BasisPoints.of(0));
    }

    /**
     * The statement a card is projected to close with on {@code closeDate}; empty when the card
     * has no settings or no balance to work from.
     */
    public Optional<CardProjector.Statement> plannedStatement(Account card, LocalDate closeDate) {
        LocalDate today = pay.today();
        LocalDate from = closeDate.isBefore(today) ? closeDate : today;
        return workings(List.of(new PayPeriod(from, closeDate, from))).cards()
                .getOrDefault(card.id(), CardProjector.Result.NONE)
                .statements().stream()
                .filter(statement -> statement.closeDate().equals(closeDate))
                .findFirst();
    }

    /**
     * What one occurrence of a recurring item really comes to: the amount worked out for a
     * payment that follows its card, or what a bucket move still has room for. Empty when the
     * item has no such occurrence, or it is not listed at all.
     */
    public Optional<Long> amountOf(long itemId, LocalDate scheduledDate) {
        return workings(List.of(new PayPeriod(scheduledDate, scheduledDate, scheduledDate))).occurrences().stream()
                .filter(occurrence -> occurrence.itemId() == itemId && occurrence.scheduledDate().equals(scheduledDate))
                .map(occurrence -> (Long) occurrence.amountCents())
                .findFirst();
    }

    /**
     * For each account that has one: the next payment into it that is still to be made, from
     * today on, with the amount it is projected to come to.
     */
    public Map<Long, Occurrence> nextPayments() {
        LocalDate today = pay.today();
        LocalDate through = today.plusMonths(NEXT_PAYMENT_MONTHS);
        Map<Long, Occurrence> next = new HashMap<>();
        // Occurrences come back in date order, so the first one seen for an account is its next.
        for (Occurrence occurrence : workings(List.of(new PayPeriod(today, through, today))).occurrences()) {
            if (occurrence.payToAccountId() != null && !occurrence.skipped() && !occurrence.paid()
                    && within(occurrence.payBy(), today, through)) {
                next.putIfAbsent(occurrence.payToAccountId(), occurrence);
            }
        }
        return next;
    }

    /** What one account holds (or owes) at the end of a day, and what of that is outside its buckets. */
    public record AccountBalance(long accountId, LocalDate date, long balanceCents, long availableCents) {}

    /**
     * Every account at the end of {@code day}: the balance last entered with everything since
     * added up, the same figure its pay period works from. An archived account nobody entered
     * a balance for is left out, as it is everywhere else.
     */
    public List<AccountBalance> balancesOn(LocalDate day) {
        Workings workings = workings(List.of(new PayPeriod(day, day, day)));
        List<AccountBalance> held = new ArrayList<>();
        for (Account account : accounts.all()) {
            for (AccountPeriod figures : accountPeriods(workings, account)) {
                held.add(new AccountBalance(account.id(), day, figures.endingCents(), figures.availableCents()));
            }
        }
        return held;
    }

    /**
     * One thing that moved an account's balance, or a balance the user entered for it.
     *
     * @param kind        {@code paycheck}, {@code contribution} (taken out of a paycheck),
     *                    {@code employer_match}, {@code interest}, {@code growth} (an investment's
     *                    assumed growth), {@code balance}, or the lower-case kind of the recurring
     *                    item or one-off entry it came from
     * @param amountCents what it did to the balance, signed; for {@code balance}, the balance entered
     * @param unpaid      true for something paid by hand that has not been ticked off yet
     */
    public record Transaction(LocalDate date, String label, String kind, long amountCents, boolean unpaid) {}

    /**
     * Everything that moved {@code account} within {@code from}–{@code to}, newest first:
     * paychecks, recurring items, one-off entries, transfers arriving, estimated interest, and
     * the balances entered along the way. What is dated before the first balance entered is
     * listed too: it happened, even though that balance already takes it into account.
     */
    public List<Transaction> transactions(Account account, LocalDate from, LocalDate to) {
        Workings workings = workings(List.of(new PayPeriod(from, to, from)));
        List<Transaction> found = new ArrayList<>();
        for (Transaction line : workings.lines().getOrDefault(account.id(), List.of())) {
            if (within(line.date(), from, to)) {
                found.add(line);
            }
        }
        if (isInvestment(account.type())) {
            // Growth is added a little every day. A line a day would bury everything else, so each
            // month's days are one line, dated the last of them that falls within the dates asked for.
            Map<YearMonth, List<InterestCredit>> byMonth = new TreeMap<>();
            for (InterestCredit credit : workings.projection(account.id()).interest()) {
                if (within(credit.date(), from, to)) {
                    byMonth.computeIfAbsent(YearMonth.from(credit.date()), month -> new ArrayList<>()).add(credit);
                }
            }
            byMonth.values().forEach(month -> found.add(new Transaction(
                    month.getLast().date(), GROWTH_LABEL, GROWTH_KIND,
                    month.stream().mapToLong(credit -> credit.amount().cents()).sum(), false)));
        } else {
            for (InterestCredit credit : workings.projection(account.id()).interest()) {
                if (within(credit.date(), from, to)) {
                    found.add(new Transaction(credit.date(), INTEREST_LABEL, INTEREST_KIND, credit.amount().cents(), false));
                }
            }
        }
        for (BalanceEntry entry : accounts.balances(account.id())) {
            if (within(entry.date(), from, to)) {
                found.add(new Transaction(entry.date(), BALANCE_LABEL, "balance", entry.amountCents(), false));
            }
        }
        found.sort(Comparator.comparing(Transaction::date).reversed());
        return found;
    }

    /**
     * One balance the user entered, set against the one before it.
     *
     * @param changeCents   how far it moved from the entry before; {@code null} for the first entry
     * @param depositsCents what was put in between the two, less what was taken out
     * @param growthCents   the change that deposits do not explain: a gain when positive, a loss when
     *                      negative (spec §5.5). Only ever worked out from entered balances
     */
    public record BalanceChange(
            long entryId, LocalDate date, long amountCents, Long changeCents, Long depositsCents, Long growthCents) {}

    /**
     * How an account has done this year.
     *
     * @param since         January 1, or the day of the first balance entered if that is later
     * @param startCents    what it held at the start of that day
     * @param nowCents      what it holds at the end of today
     * @param changeCents   the difference between the two
     * @param depositsCents what was put in since, less what was taken out
     * @param growthCents   the rest of the change: interest, growth, and gains or losses
     */
    public record YearSoFar(
            LocalDate since, long startCents, long nowCents, long changeCents, long depositsCents, long growthCents) {}

    /**
     * @param thisYear {@code null} for an account that is not added up at all (archived, with no balance)
     * @param balances every balance entered, newest first
     */
    public record Performance(YearSoFar thisYear, List<BalanceChange> balances) {}

    /**
     * How {@code account} has changed: this year so far, and from each balance entered to the
     * next. "Deposits" are everything set up that moved the account — paychecks and what is
     * taken out of them, recurring items, one-off entries, transfers — but never interest or
     * assumed growth, which are estimates: those belong to the part deposits do not explain.
     */
    public Performance performance(Account account) {
        LocalDate today = pay.today();
        List<BalanceEntry> entered = accounts.balances(account.id()).reversed();
        LocalDate yearStart = today.withDayOfYear(1);
        LocalDate earliest = entered.isEmpty() || entered.getFirst().date().isAfter(yearStart) ? yearStart : entered.getFirst().date();
        Workings workings = workings(List.of(new PayPeriod(earliest, today, earliest)));
        List<Transaction> lines = workings.lines().getOrDefault(account.id(), List.of());

        List<BalanceChange> changes = new ArrayList<>();
        for (int i = 0; i < entered.size(); i++) {
            BalanceEntry entry = entered.get(i);
            if (i == 0) {
                changes.add(new BalanceChange(entry.id(), entry.date(), entry.amountCents(), null, null, null));
                continue;
            }
            BalanceEntry before = entered.get(i - 1);
            // A balance stands for the start of its day, so that day's own movements belong to what follows it.
            long deposits = depositsWithin(lines, before.date(), entry.date().minusDays(1));
            long growth = GainLoss.between(
                    Money.ofCents(before.amountCents()), Money.ofCents(entry.amountCents()), Money.ofCents(deposits)).cents();
            changes.add(new BalanceChange(
                    entry.id(), entry.date(), entry.amountCents(), entry.amountCents() - before.amountCents(), deposits, growth));
        }

        List<AccountPeriod> now = accountPeriods(workings(List.of(new PayPeriod(today, today, today))), account);
        YearSoFar thisYear = null;
        if (!now.isEmpty()) {
            // From the first balance entered, when that came after New Year: nothing is known before it.
            BalanceEntry first = entered.isEmpty() ? null : entered.getFirst();
            LocalDate since = first != null && first.date().isAfter(yearStart) ? first.date() : yearStart;
            long start = first != null && first.date().equals(since)
                    ? first.amountCents()
                    : accountPeriods(workings(List.of(new PayPeriod(since.minusDays(1), since.minusDays(1), since.minusDays(1)))), account)
                            .stream().findFirst().map(AccountPeriod::endingCents).orElse(0L);
            long deposits = depositsWithin(lines, since, today);
            long held = now.getFirst().endingCents();
            thisYear = new YearSoFar(
                    since, start, held, held - start, deposits,
                    GainLoss.between(Money.ofCents(start), Money.ofCents(held), Money.ofCents(deposits)).cents());
        }
        return new Performance(thisYear, changes.reversed());
    }

    /** What the listed movements did to the balance within {@code from}–{@code to}, both inclusive. */
    private static long depositsWithin(List<Transaction> lines, LocalDate from, LocalDate to) {
        return lines.stream().filter(line -> within(line.date(), from, to)).mapToLong(Transaction::amountCents).sum();
    }

    /** What every open bucket holds at the end of {@code day}, by bucket id. */
    public Map<Long, Long> bucketBalances(LocalDate day) {
        Map<Long, Long> held = new HashMap<>();
        for (List<PeriodBuckets> byPeriod : workings(List.of(new PayPeriod(day, day, day))).buckets().values()) {
            for (BucketBalance bucket : byPeriod.getFirst().buckets()) {
                held.put(bucket.bucketId(), bucket.ending().cents());
            }
        }
        return held;
    }

    /**
     * What an account has left outside its buckets at the end of {@code day}: the most that can
     * still be set aside, or spent without touching a bucket. Empty only for an archived account
     * nobody entered a balance for, which is not added up at all.
     */
    public Optional<Long> availableOn(Account account, LocalDate day) {
        return availableOn(account, day, null);
    }

    /** As {@link #availableOn(Account, LocalDate)}, as if the balance entry {@code withoutBalanceId} had never been entered. */
    public Optional<Long> availableOn(Account account, LocalDate day, Long withoutBalanceId) {
        List<AccountPeriod> figures = accountPeriods(workings(List.of(new PayPeriod(day, day, day)), withoutBalanceId), account);
        return figures.isEmpty() ? Optional.empty() : Optional.of(figures.getFirst().availableCents());
    }

    /** How many paydays fall within {@code from}–{@code through}, both inclusive. */
    public int paydaysBetween(LocalDate from, LocalDate through) {
        return (int) pay.payPeriods(from, through).stream()
                .map(PayPeriod::start)
                .filter(payday -> !payday.isBefore(from) && !payday.isAfter(through))
                .count();
    }

    /**
     * For each recurring item being saved up for: what the next paycheck sets aside for it.
     * An item is left out once its next bill is fully saved for.
     */
    public Map<Long, Long> nextSaveUps() {
        LocalDate today = pay.today();
        Map<Long, Long> next = new HashMap<>();
        // Moves come back in date order, so the first one seen for an item is its next.
        for (Move move : workings(List.of(new PayPeriod(today, today, today))).bucketMoves()) {
            if (move.kind() == BucketMoves.Kind.SAVE_UP && move.itemId() != null && !move.date().isBefore(today)) {
                next.putIfAbsent(move.itemId(), move.amountCents());
            }
        }
        return next;
    }

    /** What every account held (or owed) at the end of one day. */
    public record DayBalances(LocalDate date, List<AccountBalance> balances) {}

    /**
     * Every account at the end of each of {@code days}, which must be in date order: the same
     * figures as {@link #balancesOn(LocalDate)}, worked out in one pass in place of one per day.
     * A day before an account's first balance has no entry for that account.
     */
    public List<DayBalances> balancesOn(List<LocalDate> days) {
        if (days.isEmpty()) {
            return List.of();
        }
        // Stretches that follow on from each other, each ending on one of the days asked for.
        List<PayPeriod> stretches = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            LocalDate start = i == 0 ? days.getFirst() : days.get(i - 1).plusDays(1);
            stretches.add(new PayPeriod(start, days.get(i), start));
        }
        Workings workings = workings(stretches);
        Map<LocalDate, List<AccountBalance>> byDay = new LinkedHashMap<>();
        days.forEach(day -> byDay.put(day, new ArrayList<>()));
        for (Account account : accounts.all()) {
            List<PeriodBalance> balances = workings.projection(account.id()).balances();
            List<AccountPeriod> figures = accountPeriods(workings, account);
            for (int i = 0; i < balances.size(); i++) {
                LocalDate day = balances.get(i).period().end();
                byDay.get(day).add(new AccountBalance(account.id(), day, figures.get(i).endingCents(), figures.get(i).availableCents()));
            }
        }
        List<DayBalances> held = new ArrayList<>();
        byDay.forEach((day, balances) -> held.add(new DayBalances(day, balances)));
        return held;
    }

    /**
     * Every account at the end of each pay period overlapping {@code from}–{@code through}, by
     * account id: {@link #accountSeries} for all of them in one pass. An account with no balance has no entry.
     */
    public Map<Long, List<AccountPoint>> allSeries(LocalDate from, LocalDate through) {
        Workings workings = workings(pay.payPeriods(from, through));
        Map<Long, List<AccountPoint>> series = new LinkedHashMap<>();
        for (Account account : accounts.all()) {
            List<AccountPoint> points = points(workings, account);
            if (!points.isEmpty()) {
                series.put(account.id(), points);
            }
        }
        return series;
    }

    /** One account at the end of each pay period overlapping {@code from}–{@code through}; empty until it has a balance. */
    public List<AccountPoint> accountSeries(Account account, LocalDate from, LocalDate through) {
        return points(workings(pay.payPeriods(from, through)), account);
    }

    private static List<AccountPoint> points(Workings workings, Account account) {
        List<PeriodBalance> balances = workings.projection(account.id()).balances();
        List<AccountPeriod> figures = accountPeriods(workings, account);
        List<AccountPoint> points = new ArrayList<>();
        for (int i = 0; i < balances.size(); i++) {
            PayPeriod period = balances.get(i).period();
            AccountPeriod at = figures.get(i);
            points.add(new AccountPoint(period.start(), period.end(), at.endingCents(), at.lowestCents(), at.availableCents()));
        }
        return points;
    }

    /** What was gathered to project {@code periods}: wider than the periods themselves. */
    private record Workings(
            List<Occurrence> occurrences,
            List<Carried> carried,
            List<Entry> entries,
            Map<Long, Projection> projections,
            Set<Long> builtFromZero,
            Map<Long, List<Transaction>> lines,
            Map<Long, List<PeriodBuckets>> buckets,
            List<Move> bucketMoves,
            Map<Long, CardProjector.Result> cards,
            Map<Long, List<CardsRepository.Promo>> promos,
            Map<Long, InterestTerms> aprs,
            List<StatementPaid> statementsPaid) {

        static final Workings NONE = new Workings(
                List.of(), List.of(), List.of(), Map.of(), Set.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of(), Map.of(),
                List.of());

        Projection projection(long accountId) {
            return projections.getOrDefault(accountId, Projection.NONE);
        }
    }

    /** An unpaid occurrence whose money was carried forward to the day a balance was entered. */
    private record Carried(Occurrence occurrence, LocalDate countsOn) {}

    /** One account's figures for each period it has a balance in, in period order. */
    private static List<AccountPeriod> accountPeriods(Workings workings, Account account) {
        Map<LocalDate, PeriodBuckets> inBuckets = new HashMap<>();
        for (PeriodBuckets period : workings.buckets().getOrDefault(account.id(), List.of())) {
            inBuckets.put(period.period().start(), period);
        }
        List<AccountPeriod> figures = new ArrayList<>();
        for (PeriodBalance balance : workings.projection(account.id()).balances()) {
            PeriodBuckets held = inBuckets.get(balance.period().start());
            long bucketsCents = held == null ? 0 : held.total().cents();
            Long threshold = account.lowBalanceThresholdCents();
            figures.add(new AccountPeriod(
                    account.id(), balance.starting().cents(), balance.startingOn(), balance.entered(),
                    balance.in().cents(), balance.out().cents(), balance.ending().cents(),
                    balance.lowest().cents(), balance.lowestOn(),
                    bucketsCents,
                    balance.ending().cents() - bucketsCents,
                    threshold != null && balance.lowest().cents() < threshold,
                    workings.builtFromZero().contains(account.id())));
        }
        return figures;
    }

    private Workings workings(List<PayPeriod> periods) {
        return workings(periods, null);
    }

    /** @param withoutBalanceId a balance entry to leave out, to see what removing it would do; {@code null} for none */
    private Workings workings(List<PayPeriod> periods, Long withoutBalanceId) {
        if (periods.isEmpty()) {
            return Workings.NONE;
        }
        Map<Long, List<BalanceEntry>> entered = new HashMap<>();
        accounts.allBalances().forEach((accountId, forAccount) -> {
            List<BalanceEntry> kept = forAccount.stream()
                    .filter(entry -> withoutBalanceId == null || entry.id() != withoutBalanceId)
                    .toList();
            if (!kept.isEmpty()) {
                entered.put(accountId, kept);
            }
        });
        // A statement says what was really owed when it closed, so it takes over from the projection
        // the day after, the way a balance entered for that day would. A balance the user did enter
        // for that day stands.
        Map<Long, List<CardsRepository.Statement>> statements = cards.allStatements();
        statements.forEach((accountId, ofCard) -> {
            List<BalanceEntry> merged = new ArrayList<>(entered.getOrDefault(accountId, List.of()));
            for (CardsRepository.Statement statement : ofCard) {
                LocalDate dayAfter = statement.closeDate().plusDays(1);
                if (merged.stream().noneMatch(entry -> entry.date().equals(dayAfter))) {
                    // A negative id can never be a balance entry's, so it is never mistaken for one.
                    merged.add(new BalanceEntry(-statement.id(), dayAfter, statement.balanceCents(), null));
                }
            }
            merged.sort(Comparator.comparing(BalanceEntry::date));
            entered.put(accountId, merged);
        });
        LocalDate first = periods.getFirst().start();
        LocalDate last = periods.getLast().end();

        // A projection rolls forward from the balance entered before the first period, however
        // long ago that was, so everything since the oldest such balance is needed.
        LocalDate from = first;
        for (List<BalanceEntry> balances : entered.values()) {
            LocalDate startsFrom = balances.stream()
                    .map(BalanceEntry::date)
                    .filter(date -> !date.isAfter(first))
                    .reduce((earlier, later) -> later)
                    .orElse(first);
            if (startsFrom.isBefore(from)) {
                from = startsFrom;
            }
        }

        // An account nobody has entered a balance for starts at nothing and is added up from the
        // first money that moved in or out of it, the way an account builds from the day it is
        // opened. That needs everything there is, not just what follows the oldest balance.
        List<Account> allAccounts = accounts.all();
        List<Item> active = items.all().stream().filter(item -> !item.archived()).toList();
        Set<Long> builtFromZero = allAccounts.stream()
                .filter(account -> !account.archived() && !entered.containsKey(account.id()))
                .map(Account::id)
                .collect(Collectors.toSet());
        if (!builtFromZero.isEmpty()) {
            LocalDate earliest = earliestMovement(active);
            if (earliest != null && earliest.isBefore(from)) {
                from = earliest;
            }
        }

        Map<Long, String> types = allAccounts.stream().collect(Collectors.toMap(Account::id, Account::type));
        Set<Long> cash = allAccounts.stream().filter(AccountType::holdsCash).map(Account::id).collect(Collectors.toSet());
        Map<Long, List<Movement>> movements = new HashMap<>();
        // The same movements with their names kept, for the list of an account's transactions.
        Map<Long, List<Transaction>> lines = new HashMap<>();

        for (Paycheck paycheck : pay.paychecks(from, last)) {
            add(movements, lines, types, paycheck.depositAccountId(), paycheck.payDate(), paycheck.netCents(), true, false,
                    paycheck.sourceName(), "paycheck");
            // Taken out before take-home pay, or added by the employer: it lands in its own account on payday.
            for (PayCalendar.Contribution contribution : paycheck.contributions()) {
                if (types.containsKey(contribution.toAccountId())) {
                    add(movements, lines, types, contribution.toAccountId(), paycheck.payDate(), contribution.amountCents(), true, false,
                            contribution.label() + " · " + paycheck.sourceName(), contribution.kind());
                }
            }
        }

        Map<Long, StillToPay> stillToPay = new HashMap<>();
        entered.forEach((accountId, forAccount) -> {
            LocalDate newest = forAccount.getLast().date();
            LocalDate currentPeriod = pay.payPeriods(newest, newest).stream()
                    .filter(period -> period.contains(newest))
                    .map(PayPeriod::start)
                    .findFirst()
                    .orElse(null);
            stillToPay.put(accountId, new StillToPay(newest, currentPeriod));
        });

        // A bucket holds everything moved into it since it was opened, and a bill saved up for
        // looks ahead to when it is next due, so buckets widen the stretch occurrences are needed for.
        List<Bucket> open = buckets.all().stream().filter(bucket -> !bucket.archived()).toList();
        LocalDate expandFrom = open.stream().map(Bucket::openedOn).filter(from::isAfter).min(Comparator.naturalOrder()).orElse(from);
        LocalDate expandTo = open.isEmpty() ? last : last.plusMonths(BucketMoves.lookAheadMonths(active, last));
        ItemCalendar.Expansion expansion = itemCalendar.expand(active, expandFrom, expandTo);

        // A schedule only reaches a bucket from the day it was opened, so adding a bucket never
        // back-fills it. Before that day a transfer still happens, into no bucket; a bucket
        // move has nothing to do at all, so it is not listed.
        Map<Long, LocalDate> openedOn = open.stream().collect(Collectors.toMap(Bucket::id, Bucket::openedOn));
        // A transfer into a bucket fills it from the day the bucket counts from, whenever the
        // account's balance was entered: that balance already holds the money that arrived
        // earlier, and what was set aside out of it is still set aside.
        List<Occurrence> occurrences = expansion.occurrences().stream()
                .map(occurrence -> reachesItsBucket(occurrence, openedOn)
                        ? occurrence
                        : occurrence.withoutBucket())
                .filter(occurrence -> occurrence.kind() != ItemKind.BUCKET_MOVE || occurrence.bucketId() != null)
                .toList();
        // A card's own payment follows the card (its statement balance, say) unless it is a fixed
        // amount, so what it comes to is only known once the card itself has been followed.
        Map<Long, CardsRepository.Settings> cardSettings = new HashMap<>(cards.allSettings());
        Map<Long, CardPayment.MinimumRule> minimumRules = cards.minimumRules();
        Map<Long, Long> followsCard = new HashMap<>();
        // Settings left behind on an account that was since changed to another type mean nothing.
        cardSettings.keySet().removeIf(cardId -> !Inputs.code(AccountType.CREDIT_CARD).equals(types.get(cardId)));
        cardSettings.forEach((cardId, settings) -> {
            if (settings.paymentItemId() != null && settings.paymentMode() != CardPayment.Mode.FIXED) {
                followsCard.put(settings.paymentItemId(), cardId);
            }
        });
        Map<Long, List<Occurrence>> following = new HashMap<>();
        // Scheduled payments into each loan: the escrow part of the monthly payment comes out of these.
        Map<Long, List<Movement>> scheduledInto = new HashMap<>();
        Counting counting = new Counting(movements, lines, types, stillToPay, new ArrayList<>(), scheduledInto);
        // Terms left behind on an account that was since changed to another type mean nothing.
        Map<Long, LoansRepository.Settings> loanSettings = new HashMap<>(loans.allSettings());
        loanSettings.keySet().removeIf(loanId -> !Inputs.code(AccountType.LOAN).equals(types.get(loanId)));
        Map<Long, Long> paysLoan = new HashMap<>();
        loanSettings.forEach((loanId, settings) -> {
            if (settings.paymentItemId() != null) {
                paysLoan.put(settings.paymentItemId(), loanId);
            }
        });
        for (Occurrence occurrence : occurrences) {
            // A bucket move sets money aside inside its account: the account's balance does not change.
            if (occurrence.skipped() || occurrence.kind() == ItemKind.BUCKET_MOVE) {
                continue;
            }
            Long card = followsCard.get(occurrence.itemId());
            // Typed over, it is that amount and no longer follows anything.
            if (card != null && card.equals(occurrence.payToAccountId()) && occurrence.projectedCents() == null) {
                following.computeIfAbsent(card, id -> new ArrayList<>()).add(occurrence);
                continue;
            }
            counting.count(occurrence, occurrence.amountCents());
            // A bill linked as a loan's payment goes nowhere as an item, yet it is what pays the loan down.
            Long loan = paysLoan.get(occurrence.itemId());
            if (loan != null && occurrence.payToAccountId() == null && loan != occurrence.payFromAccountId()) {
                Movement arriving = add(movements, lines, types, loan, occurrence.payBy(), occurrence.amountCents(), true,
                        outstanding(occurrence, stillToPay.get(loan)), occurrence.name(), Inputs.code(occurrence.kind()));
                scheduledInto.computeIfAbsent(loan, id -> new ArrayList<>()).add(arriving);
            }
        }
        List<Carried> carried = counting.carried();

        List<Entry> oneOffs = entries.between(from, last);
        for (Entry entry : oneOffs) {
            String label = entry.description() != null && !entry.description().isBlank()
                    ? entry.description()
                    : entry.payee() != null && !entry.payee().isBlank() ? entry.payee() : ONE_OFF_LABEL;
            add(movements, lines, types, entry.accountId(), entry.date(), entry.amountCents(), entry.kind() == EntryKind.MONEY_IN, false,
                    label, Inputs.code(entry.kind()));
            if (entry.toAccountId() != null) {
                add(movements, lines, types, entry.toAccountId(), entry.date(), entry.amountCents(), true, false,
                        label, Inputs.code(entry.kind()));
            }
        }

        // Credit cards: the month's estimated spending and interest, and what each payment that
        // follows the card comes to. All of it depends on the card's own balance, so it is worked
        // out here, once everything else that moves the card is known.
        Map<Long, List<CardsRepository.Promo>> promos = cards.allPromos();
        Map<Long, InterestTerms> aprs = new HashMap<>();
        interest.allRates(RateKind.APR).forEach((cardId, rates) -> aprs.put(cardId, interestTerms(null, rates)));
        Map<Long, CardProjector.Result> cardResults = new HashMap<>();
        Map<Long, Map<LocalDate, Long>> followedAmounts = new HashMap<>();
        // Those of them sized from a projected statement rather than one the user entered.
        Map<Long, Set<LocalDate>> followedEstimates = new HashMap<>();
        List<StatementPaid> statementsPaid = new ArrayList<>();
        cardSettings.forEach((cardId, settings) -> {
            List<Occurrence> payments = following.getOrDefault(cardId, List.of());
            List<Movement> fixed = movements.getOrDefault(cardId, List.of());
            LocalDate since = Stream.concat(fixed.stream().map(Movement::date), payments.stream().map(Occurrence::payBy))
                    .min(Comparator.naturalOrder())
                    .orElse(first);
            List<CardProjector.Payment> toWorkOut = new ArrayList<>();
            for (int i = 0; i < payments.size(); i++) {
                Occurrence payment = payments.get(i);
                toWorkOut.add(new CardProjector.Payment(payment.payBy(), i, outstanding(payment, stillToPay.get(cardId))));
            }
            Map<LocalDate, CardProjector.Statement> real = new HashMap<>();
            for (CardsRepository.Statement statement : statements.getOrDefault(cardId, List.of())) {
                // A real statement can close a day or two off the usual day; it is still that month's statement.
                real.put(StatementCycle.nearestClose(settings.statementCloseDay(), statement.closeDate()), new CardProjector.Statement(
                        statement.closeDate(), Money.ofCents(statement.balanceCents()),
                        statement.minimumDueCents() == null ? null : Money.ofCents(statement.minimumDueCents()),
                        statement.dueDate(), Money.ZERO, true));
            }
            CardProjector.Result result = CardProjector.project(
                    anchors(cardId, entered, builtFromZero, since, first),
                    fixed,
                    toWorkOut,
                    new CardProjector.Terms(
                            settings.statementCloseDay(), settings.dueDay(), settings.paymentMode(),
                            settings.monthlyEstimateCents() == null ? null : Money.ofCents(settings.monthlyEstimateCents()),
                            aprs.get(cardId),
                            promos.getOrDefault(cardId, List.of()).stream().map(CardsRepository.Promo::terms).toList(),
                            real,
                            minimumRules.getOrDefault(cardId, CardPayment.MinimumRule.USUAL)),
                    last);
            cardResults.put(cardId, result);
            Set<Integer> answered = new HashSet<>();
            for (CardProjector.Derived derived : result.derived()) {
                switch (derived.kind()) {
                    case PAYMENT -> {
                        Occurrence payment = payments.get(derived.key());
                        long cents = -derived.amount().cents();
                        counting.count(payment, cents);
                        followedAmounts.computeIfAbsent(payment.itemId(), id -> new HashMap<>()).put(payment.scheduledDate(), cents);
                        boolean exact = result.exact().contains(derived.key());
                        if (!exact) {
                            followedEstimates.computeIfAbsent(payment.itemId(), id -> new HashSet<>()).add(payment.scheduledDate());
                        }
                        LocalDate closed = result.pays().get(derived.key());
                        if (closed != null) {
                            statementsPaid.add(new StatementPaid(payment.itemId(), payment.scheduledDate(), closed, exact));
                        }
                        answered.add(derived.key());
                    }
                    case INTEREST -> add(movements, lines, types, cardId, derived.date(), derived.amount().cents(), false, false,
                            CARD_INTEREST_LABEL, CARD_INTEREST_KIND);
                    case DEFERRED_INTEREST -> add(movements, lines, types, cardId, derived.date(), derived.amount().cents(), false, false,
                            DEFERRED_INTEREST_LABEL, DEFERRED_INTEREST_KIND);
                    case ESTIMATED_SPENDING -> add(movements, lines, types, cardId, derived.date(), derived.amount().cents(), false, false,
                            CARD_ESTIMATE_LABEL, CARD_ESTIMATE_KIND);
                }
            }
            // A card nothing is known about yet (no balance, nothing moving it) cannot say what its payment is.
            for (int i = 0; i < payments.size(); i++) {
                if (!answered.contains(i)) {
                    counting.count(payments.get(i), payments.get(i).amountCents());
                }
            }
        });

        // Loans: a month's interest on the payment day, the escrow part of the monthly payment, and the
        // payment itself for the months before any item was scheduled to pay it,
        // which is paid but never reaches the loan.
        Map<Long, List<Rate>> loanRates = interest.allRates(RateKind.LOAN_RATE);
        loanSettings.forEach((loanId, settings) -> {
            List<Movement> all = movements.getOrDefault(loanId, List.of());
            Set<Movement> regular = Collections.newSetFromMap(new IdentityHashMap<>());
            regular.addAll(scheduledInto.getOrDefault(loanId, List.of()));
            LocalDate since = all.stream().map(Movement::date).min(Comparator.naturalOrder()).orElse(first);
            List<LoanProjector.Derived> worked = LoanProjector.project(
                    anchors(loanId, entered, builtFromZero, since, first),
                    List.copyOf(scheduledInto.getOrDefault(loanId, List.of())),
                    all.stream().filter(movement -> !regular.contains(movement)).toList(),
                    new LoanProjector.Terms(
                            settings.paymentDay(), Money.ofCents(settings.escrowCents()), interestTerms(null, loanRates.get(loanId)),
                            Money.ofCents(settings.paymentCents() - settings.escrowCents())),
                    last);
            for (LoanProjector.Derived derived : worked) {
                switch (derived.kind()) {
                    case INTEREST -> add(movements, lines, types, loanId, derived.date(), derived.amount().cents(), false, false,
                            INTEREST_LABEL, LOAN_INTEREST_KIND);
                    case ESCROW -> add(movements, lines, types, loanId, derived.date(), derived.amount().cents(), false, false,
                            ESCROW_LABEL, ESCROW_KIND);
                    // Arrives in the loan like any payment, but leaves no account: nothing was scheduled to pay it yet.
                    case ASSUMED_PAYMENT -> add(movements, lines, types, loanId, derived.date(), derived.amount().cents(), true, false,
                            ASSUMED_PAYMENT_LABEL, ASSUMED_PAYMENT_KIND);
                }
            }
        });

        // From here on every occurrence carries the amount it really comes to.
        List<Occurrence> settled = occurrences.stream()
                .map(occurrence -> {
                    Long cents = followedAmounts.getOrDefault(occurrence.itemId(), Map.of()).get(occurrence.scheduledDate());
                    return cents == null
                            ? occurrence
                            : occurrence.withAmount(
                                    cents, followedEstimates.getOrDefault(occurrence.itemId(), Set.of()).contains(occurrence.scheduledDate()));
                })
                .toList();

        Map<Long, Settings> interestSettings = interest.allSettings();
        Map<Long, List<Rate>> apys = interest.allRates(RateKind.APY);
        Map<Long, List<Rate>> growthRates = interest.allRates(RateKind.GROWTH);
        // Checking and savings earn interest at their APY, on the day the user chose. An investment
        // grows by the rate the user assumes, a little every day, so every pay period has its share.
        Map<Long, InterestTerms> earning = new HashMap<>();
        for (Account account : allAccounts) {
            InterestTerms terms = cash.contains(account.id())
                    ? interestTerms(interestSettings.get(account.id()), apys.get(account.id()))
                    : isInvestment(account.type()) ? growthTerms(growthRates.get(account.id())) : null;
            if (terms != null) {
                earning.put(account.id(), terms);
            }
        }
        Map<Long, Projection> projections = new HashMap<>();
        entered.forEach((accountId, forAccount) -> {
            List<BalanceAnchor> anchors = forAccount.stream()
                    .map(entry -> new BalanceAnchor(entry.date(), Money.ofCents(entry.amountCents())))
                    .toList();
            InterestTerms terms = earning.get(accountId);
            projections.put(accountId, BalanceProjector.project(anchors, movements.getOrDefault(accountId, List.of()), periods, terms));
        });
        for (long accountId : builtFromZero) {
            List<Movement> counted = movements.getOrDefault(accountId, List.of());
            LocalDate since = counted.stream().map(Movement::date).min(Comparator.naturalOrder()).orElse(first);
            InterestTerms terms = earning.get(accountId);
            projections.put(accountId, BalanceProjector.project(
                    List.of(new BalanceAnchor(since, Money.ZERO)), counted, periods, terms));
        }

        // A bucket that stops at its goal takes only what it still has room for; a move that puts
        // nothing in is not listed in its pay period, and one cut down shows what it really moves.
        Map<Long, Long> stopAt = open.stream()
                .filter(bucket -> bucket.stopAtGoal() && bucket.goalCents() != null)
                .collect(Collectors.toMap(Bucket::id, Bucket::goalCents));
        BucketMoves.Capped capped = BucketMoves.stopAtGoals(
                open.isEmpty()
                        ? List.<Move>of()
                        : BucketMoves.derive(
                                open, buckets.allChanges(), entries.withBucketSplits(), active, settled, expansion.visits(), expansion.periods()),
                stopAt);
        List<Move> bucketMoves = new ArrayList<>(BucketMoves.neverOverdrawn(capped.moves()));
        List<Occurrence> listed = settled.stream()
                .map(occurrence -> {
                    Long room = occurrence.kind() == ItemKind.BUCKET_MOVE && !occurrence.skipped()
                            ? capped.changed().get(new BucketMoves.MoveKey(occurrence.itemId(), occurrence.payBy()))
                            : null;
                    return room == null ? occurrence : room == 0 ? null : occurrence.withAmount(room);
                })
                .filter(Objects::nonNull)
                .toList();
        Map<Long, List<PeriodBuckets>> bucketsByAccount = new HashMap<>();
        open.stream().collect(Collectors.groupingBy(Bucket::accountId)).forEach((accountId, ofAccount) -> {
            Set<Long> ids = ofAccount.stream().map(Bucket::id).collect(Collectors.toSet());
            BucketProjector.Result result = BucketProjector.project(
                    ofAccount.stream().map(bucket -> new BucketProjector.Bucket(bucket.id(), bucket.openedOn())).toList(),
                    bucketMoves.stream()
                            .filter(move -> ids.contains(move.bucketId()))
                            .map(move -> new BucketProjector.Move(move.bucketId(), move.date(), Money.ofCents(move.amountCents())))
                            .toList(),
                    projections.getOrDefault(accountId, Projection.NONE).interest(),
                    interestRule(interestSettings.get(accountId), ids),
                    periods);
            bucketsByAccount.put(accountId, result.periods());
            for (BucketProjector.Move share : result.interestShares()) {
                bucketMoves.add(new Move(
                        share.bucketId(), share.date(), share.amount().cents(), BucketMoves.Kind.INTEREST, INTEREST_LABEL, null));
            }
        });
        bucketMoves.sort(Comparator.comparing(Move::date));
        return new Workings(
                listed, carried, oneOffs, projections, builtFromZero, lines, bucketsByAccount, bucketMoves, cardResults, promos, aprs,
                statementsPaid);
    }

    /**
     * The balances a card or loan is followed from. With balances entered: the one in force when
     * the first period opens, and any after it (nothing is known about what moved it before
     * that). With none: nothing owed on the first day something moved it.
     */
    private static List<BalanceAnchor> anchors(
            long accountId, Map<Long, List<BalanceEntry>> entered, Set<Long> builtFromZero, LocalDate since, LocalDate first) {
        List<BalanceEntry> ofAccount = entered.get(accountId);
        if (ofAccount == null) {
            return builtFromZero.contains(accountId) ? List.of(new BalanceAnchor(since, Money.ZERO)) : List.of();
        }
        LocalDate startsFrom = ofAccount.stream()
                .map(BalanceEntry::date)
                .filter(date -> !date.isAfter(first))
                .reduce((earlier, later) -> later)
                .orElse(ofAccount.getFirst().date());
        return ofAccount.stream()
                .filter(entry -> !entry.date().isBefore(startsFrom))
                .map(entry -> new BalanceAnchor(entry.date(), Money.ofCents(entry.amountCents())))
                .toList();
    }

    /**
     * Counts recurring occurrences into the accounts they move, remembering what was carried
     * over and which payments were scheduled into each account.
     */
    private record Counting(
            Map<Long, List<Movement>> movements,
            Map<Long, List<Transaction>> lines,
            Map<Long, String> types,
            Map<Long, StillToPay> stillToPay,
            List<Carried> carried,
            Map<Long, List<Movement>> scheduledInto) {

        /** Takes {@code cents} out of the account the occurrence is paid from and, for a transfer or payment, puts it where it goes. */
        void count(Occurrence occurrence, long cents) {
            long payFrom = occurrence.payFromAccountId();
            StillToPay paidFrom = stillToPay.get(payFrom);
            boolean unpaid = outstanding(occurrence, paidFrom);
            if (unpaid && occurrence.payBy().isBefore(paidFrom.newestEntered())) {
                // The engine moves it to the day of that balance; remember it so the period can say so.
                carried.add(new Carried(occurrence, paidFrom.newestEntered()));
            }
            add(movements, lines, types, payFrom, occurrence.payBy(), cents, false, unpaid,
                    occurrence.name(), Inputs.code(occurrence.kind()));
            Long payTo = occurrence.payToAccountId();
            if (payTo != null) {
                Movement arriving = add(movements, lines, types, payTo, occurrence.payBy(), cents, true,
                        outstanding(occurrence, stillToPay.get(payTo)), occurrence.name(), Inputs.code(occurrence.kind()));
                scheduledInto.computeIfAbsent(payTo, id -> new ArrayList<>()).add(arriving);
            }
        }
    }

    /** True unless the occurrence names a bucket that is archived, or was not open yet on the day it counts. */
    private static boolean reachesItsBucket(Occurrence occurrence, Map<Long, LocalDate> openedOn) {
        if (occurrence.bucketId() == null) {
            return true;
        }
        LocalDate opened = openedOn.get(occurrence.bucketId());
        return opened != null && !occurrence.payBy().isBefore(opened);
    }

    /**
     * The first day anything set up could move money: the earliest start of a paycheck or
     * recurring item, or the earliest one-off entry. {@code null} when nothing is set up.
     */
    private LocalDate earliestMovement(List<Item> active) {
        Stream<LocalDate> starts = Stream.concat(
                pay.sourceStarts().stream(),
                active.stream().flatMap(item -> item.versions().stream()).map(version -> version.terms().schedule().startDate()));
        return Stream.concat(starts, entries.earliestDate().stream()).min(Comparator.naturalOrder()).orElse(null);
    }

    private static boolean isInvestment(String type) {
        return Inputs.code(AccountType.INVESTMENT).equals(type);
    }

    /**
     * How an account earns interest (or grows); {@code null} when no rate has been entered for
     * it. Until the user says otherwise, it is credited on the last day of the month.
     */
    private static InterestTerms interestTerms(Settings settings, List<Rate> apys) {
        if (apys == null || apys.isEmpty()) {
            return null;
        }
        return new InterestTerms(
                (settings == null ? Settings.DEFAULT : settings).creditDay(),
                apys.stream().map(rate -> new RateChange(rate.effectiveDate(), BasisPoints.of(rate.rateBp()))).toList());
    }

    /** How an investment account is assumed to grow; {@code null} when no rate has been entered for it. */
    private static InterestTerms growthTerms(List<Rate> rates) {
        if (rates == null || rates.isEmpty()) {
            return null;
        }
        return InterestTerms.growingDaily(
                rates.stream().map(rate -> new RateChange(rate.effectiveDate(), BasisPoints.of(rate.rateBp()))).toList());
    }

    /** Where an account's interest goes. A bucket that has since been archived no longer receives any. */
    private static InterestRule interestRule(Settings settings, Set<Long> openBuckets) {
        if (settings == null || settings.destination() == Destination.UNASSIGNED) {
            return InterestRule.UNASSIGNED;
        }
        if (settings.destination() == Destination.PROPORTIONAL) {
            return new InterestRule(InterestDestination.PROPORTIONAL, null);
        }
        return openBuckets.contains(settings.destinationBucketId())
                ? new InterestRule(InterestDestination.BUCKET, settings.destinationBucketId())
                : InterestRule.UNASSIGNED;
    }

    /**
     * The newest balance entered for an account, and the pay period that day falls in.
     *
     * @param currentPeriodStart {@code null} when that day is outside every pay period
     */
    private record StillToPay(LocalDate newestEntered, LocalDate currentPeriodStart) {}

    /**
     * Whether an occurrence is still to leave the account even though a balance was entered
     * after the day it counts on. That is so when it is paid by hand, has not been ticked as
     * paid, and either is not due yet or belongs to the pay period the balance was entered
     * in — the unticked bills of the list being worked through. Anything older is taken as
     * paid, so nobody has to tick off months of history before the numbers make sense.
     */
    private static boolean outstanding(Occurrence occurrence, StillToPay account) {
        if (account == null || occurrence.autopay() || occurrence.paid()) {
            return false;
        }
        return !occurrence.dueDate().isBefore(account.newestEntered())
                || (occurrence.periodStart() != null && occurrence.periodStart().equals(account.currentPeriodStart()));
    }

    /**
     * Records money arriving in ({@code incoming}) or leaving one account. What that does to
     * the balance depends on the account: money put into a card or loan pays it down.
     */
    private static Movement add(
            Map<Long, List<Movement>> movements,
            Map<Long, List<Transaction>> lines,
            Map<Long, String> types,
            long accountId,
            LocalDate date,
            long cents,
            boolean incoming,
            boolean outstanding,
            String label,
            String kind) {
        boolean raisesBalance = OWED.contains(types.get(accountId)) != incoming;
        Money amount = Money.ofCents(cents);
        Movement movement = new Movement(date, raisesBalance ? amount : amount.negate(), outstanding);
        movements.computeIfAbsent(accountId, id -> new ArrayList<>()).add(movement);
        lines.computeIfAbsent(accountId, id -> new ArrayList<>())
                .add(new Transaction(date, label, kind, raisesBalance ? cents : -cents, outstanding));
        return movement;
    }

    private static boolean within(LocalDate date, LocalDate from, LocalDate to) {
        return !date.isBefore(from) && !date.isAfter(to);
    }

    private static List<PayPeriod> payPeriods(List<PayCalendar.Period> periods) {
        return periods.stream().map(period -> new PayPeriod(period.start(), period.end(), period.scheduledPayday())).toList();
    }
}
