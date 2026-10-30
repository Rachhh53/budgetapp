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
import app.budget.engine.investments.ActualGrowth;
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
import app.budget.server.api.Archiving;
import app.budget.server.api.Inputs;
import app.budget.server.buckets.BucketsRepository;
import app.budget.server.buckets.BucketsRepository.Bucket;
import app.budget.server.cards.CardsRepository;
import app.budget.server.entries.EntriesRepository;
import app.budget.server.entries.EntriesRepository.Entry;
import app.budget.server.entries.EntriesRepository.Repayment;
import app.budget.server.entries.EntryKind;
import app.budget.server.income.PayCalendar;
import app.budget.server.income.PayCalendar.Paycheck;
import app.budget.server.items.ItemCalendar;
import app.budget.server.items.ReimbursementViews;
import app.budget.server.items.ReimbursementViews.PackView;
import app.budget.server.items.ReimbursementViews.PaymentView;
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
    private static final String BALANCE_KIND = "balance";
    private static final String ONE_OFF_LABEL = "One-off entry";
    /** Money planned to come back for a recurring payment flagged to be paid back (Rachel, 2026-10-24). */
    public static final String PAID_BACK_KIND = "paid_back";

    /** What a transaction's {@code kind} says for the amounts worked out for a credit card or a loan. */
    public static final String CARD_INTEREST_KIND = "card_interest";
    public static final String DEFERRED_INTEREST_KIND = "deferred_interest";
    public static final String CARD_ESTIMATE_KIND = "card_estimate";
    public static final String CARD_REWARDS_KIND = "card_rewards";
    /** Built-up rewards going on as a statement credit, by a rule or a redemption. */
    public static final String CARD_REWARDS_REDEEMED_KIND = "card_rewards_redeemed";
    public static final String LOAN_INTEREST_KIND = "loan_interest";
    public static final String ESCROW_KIND = "escrow";
    public static final String ASSUMED_PAYMENT_KIND = "loan_payment";

    private static final String CARD_INTEREST_LABEL = "Estimated interest";
    private static final String DEFERRED_INTEREST_LABEL = "Deferred interest";
    private static final String CARD_ESTIMATE_LABEL = "Estimated spending";
    private static final String CARD_REWARDS_LABEL = "Rewards";
    private static final String CARD_REWARDS_REDEEMED_LABEL = "Rewards redeemed";
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
    private final ReimbursementViews reimbursements;

    public Ledger(
            AccountsRepository accounts,
            EntriesRepository entries,
            ItemsRepository items,
            ItemCalendar itemCalendar,
            PayCalendar pay,
            BucketsRepository buckets,
            InterestRepository interest,
            CardsRepository cards,
            LoansRepository loans,
            ReimbursementViews reimbursements) {
        this.reimbursements = reimbursements;
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
     * One account across one pay period; {@code ending = starting + in − out + adjusted}. Each period starts with
     * what the one before ended on, buckets included (Rachel, 2026-10-21).
     *
     * <p>For a credit card or loan the balance is what is owed, so {@code inCents} is what was
     * added to it (charges) and {@code outCents} what came off it (payments).
     *
     * @param startingOn     the period's first day, or the day of the first balance ever entered when it falls during it
     * @param entered        true when the starting balance is the first one the user ever entered
     * @param adjustedCents  what balances entered during the period moved it, signed: the sum of {@code adjustments}
     * @param adjustments    each balance entered during the period, earliest first, to change or remove
     * @param lowestCents    the lowest end-of-day balance in the period
     * @param bucketsCents   what the account's buckets hold at the end of the period
     * @param availableCents the ending balance less what is in buckets
     * @param startingBucketsCents  what the account's buckets hold as the period starts, on {@code startingOn}: what
     *                       they ended the period before on; anything moved that day is part of the period
     * @param startingAvailableCents the starting balance less {@code startingBucketsCents}: what the period starts
     *                       with to spend
     * @param lowBalance    true when the user asked for a warning and the balance dips below it
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
            long adjustedCents,
            List<Adjustment> adjustments,
            long endingCents,
            long lowestCents,
            LocalDate lowestOn,
            long bucketsCents,
            long availableCents,
            long startingBucketsCents,
            long startingAvailableCents,
            boolean lowBalance,
            boolean builtFromZero) {}

    /**
     * A balance entered during a pay period, shown in it as an adjustment: what the account turned out to hold as
     * {@code date} ended (or for one entered before 2026-10-30, began), against what Budget expected (Rachel,
     * 2026-10-21: "that adjustment should be editable and deletable"). Changing or removing it changes or removes that
     * balance.
     *
     * @param entryId         the balance entry it is, for {@code DELETE /api/accounts/{id}/balances/{entryId}}
     * @param balanceCents    the balance entered
     * @param differenceCents that less what was expected, signed
     * @param note            the note entered with it, kept when it is changed
     * @param since           the balance entered before it, when the records last matched: what moved the account
     *                        between the two is what the difference is in; {@code null} for none
     * @param endOfDay        true when it is what {@code date} ended with, so that day's movements are in it
     * @param sinceEndOfDay   true when {@code since} is what that day ended with, so that day's movements were already in it
     */
    public record Adjustment(
            long entryId,
            LocalDate date,
            long balanceCents,
            long differenceCents,
            String note,
            LocalDate since,
            boolean endOfDay,
            boolean sinceEndOfDay) {}

    /** One bucket across one pay period; {@code endingCents − startingCents} is what the period moved. */
    public record BucketPeriod(long bucketId, long accountId, long startingCents, long endingCents) {}

    /**
     * A month's estimated interest arriving in a checking or savings account, or an investment
     * account's assumed growth over one pay period. Growth builds up every day, so its line is
     * the period's days added together, dated the last of them.
     *
     * @param estimate false once the amount was typed over (for growth, every day of it)
     */
    public record InterestLine(long accountId, LocalDate date, long amountCents, int rateBp, boolean estimate) {}

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
     * @param buckets     every bucket open at some point in this period
     * @param bucketMoves what moved those buckets during the period, earliest first
     * @param promosEnding promotional rates that end in this period with a balance left on them
     * @param statementsPaid which statement each card payment listed here was sized from, for those
     *                    that pay a statement's balance or its minimum
     * @param unbilled    the current period only: each billed-later item's visits that no invoice covers yet,
     *                    up to today (spec §4.6); empty for any other period
     * @param paidAheadRunningOut paid-ahead items whose money runs out in this period: the first session
     *                    it does not cover falls in it, or, on the current period, has already passed (spec §4.6)
     * @param movesOut    money moved out of a bucket to money not in a bucket, dated in this period: listed under
     *                    Income. It changes no balance, only what is available (Rachel, 2026-10-20)
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
            List<Unbilled> unbilled,
            List<RunningOut> paidAheadRunningOut,
            List<BucketsRepository.MoveOut> movesOut,
            List<MoneyBack> moneyBack) {}

    /**
     * Money planned to come back (Rachel, 2026-10-24/25): for a flagged recurring payment, a planned pack paid ahead flagged
     * to be paid back, or a one-off expense marked to be paid back. It counts in the account it comes back into on the day
     * it is planned, and like a bill not marked paid it is outstanding: late, it still counts.
     *
     * @param name          what was paid, to name the line
     * @param owedCents     what is still to come back
     * @param fromAccountId the account it comes out of, like an FSA, so it is a transfer between the two; {@code null}
     *                      when it comes from outside (Rachel, 2026-10-25: "take it out of the fsa")
     * @param source        what to open to change it: a {@code paid_back} source naming the expense ({@code entryId}), the
     *                      payment ({@code itemId} and {@code scheduledDate}) or the pack ({@code itemId} and the pack's
     *                      {@code dueDate} only)
     */
    public record MoneyBack(String name, LocalDate expectedOn, long intoAccountId, long owedCents, Long fromAccountId, Source source) {}

    /**
     * Visits of something billed later that have built up with no invoice yet.
     *
     * @param since   the first of them
     * @param through the last of them
     */
    public record Unbilled(
            long itemId, String name, long payFromAccountId, int visits, long amountCents, LocalDate since, LocalDate through) {}

    /**
     * Something paid ahead whose money runs out (spec §4.6). Budget only says so (Rachel, 2026-10-19:
     * "just warn me"); it plans no payment.
     *
     * @param leftCents      what is still there today; negative once sessions went past what was paid
     * @param coversThrough  the last session the money covers; {@code null} when it covers none
     * @param firstUncovered the first session it does not cover
     * @param payFromAccountId the account it is paid from today, offered first for another payment
     */
    public record RunningOut(
            long itemId, String name, long leftCents, LocalDate coversThrough, LocalDate firstUncovered, long payFromAccountId) {}

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

    /** Today, as the pay calendar sees it: a balance dated after it is planned, not entered yet. */
    public LocalDate today() {
        return pay.today();
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
            // An archived account is listed in the periods before the day it was archived as of, not from then on.
            if (account.awayOn(period.start())) {
                continue;
            }
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
                                counted.getLast().apy().value(),
                                counted.stream().anyMatch(credit -> !credit.typed())));
                    }
                } else {
                    for (InterestCredit credit : counted) {
                        interestLines.add(new InterestLine(
                                account.id(), credit.date(), credit.amount().cents(), credit.apy().value(), !credit.typed()));
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
                period.current() ? unbilled(pay.today()) : List.of(),
                runningOut(period),
                buckets.movesOut(period.start(), period.end()),
                moneyBack(period)));
    }

    /**
     * Flagged payments whose money is planned to come back in {@code period}; in the current one, also any that were due back
     * before it and have not all come yet, as the projection counts them.
     */
    /** What a one-off is called on a line: its description, or else its payee. */
    private static String entryLabel(Entry entry) {
        return entry.description() != null && !entry.description().isBlank()
                ? entry.description()
                : entry.payee() != null && !entry.payee().isBlank() ? entry.payee() : ONE_OFF_LABEL;
    }

    private List<MoneyBack> moneyBack(PayCalendar.Period period) {
        return plannedBack().stream()
                .filter(back -> within(back.expectedOn(), period.start(), period.end())
                        || period.current() && back.expectedOn().isBefore(period.start()))
                .toList();
    }

    /**
     * Everything with money still planned to come back, and somewhere to come back into, by the day it is planned: flagged
     * payments not skipped, flagged packs still to buy, and one-off expenses being paid back.
     */
    private List<MoneyBack> plannedBack() {
        List<MoneyBack> all = new ArrayList<>();
        for (PaymentView payment : reimbursements.all()) {
            if (!payment.skipped() && payment.expectedOn() != null && payment.reimbursement().owedCents() > 0) {
                all.add(new MoneyBack(payment.name(), payment.expectedOn(), payment.intoAccountId(),
                        payment.reimbursement().owedCents(), payment.fromAccountId(), Source.paidBack(payment)));
            }
        }
        for (PackView pack : reimbursements.packs()) {
            all.add(new MoneyBack(pack.name(), pack.expectedOn(), pack.intoAccountId(), pack.amountCents(), pack.fromAccountId(),
                    Source.paidBack(pack)));
        }
        for (Entry expense : entries.plannedBack()) {
            Repayment back = expense.reimbursement();
            if (back.owedCents() > 0) {
                all.add(new MoneyBack(entryLabel(expense), back.expectedOn(), back.intoAccountId(), back.owedCents(),
                        back.fromAccountId(), Source.paidBack(expense)));
            }
        }
        all.sort(Comparator.comparing(MoneyBack::expectedOn).thenComparing(MoneyBack::name, String.CASE_INSENSITIVE_ORDER));
        return all;
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
     * The paid-ahead items whose money runs out in {@code period}: the first session it does not cover
     * falls in the period. On the current period, one that already ran out is listed too, so it is not
     * missed until a payment is recorded. Archived items are left out.
     */
    private List<RunningOut> runningOut(PayCalendar.Period period) {
        LocalDate today = pay.today();
        // A pack due when the last one runs out is not warned about: it is planned as a bill instead. One on a
        // schedule of its own is, when a session would come before the next pack (spec §4.6).
        List<Item> active = items.all().stream()
                .filter(item -> !item.archived())
                .filter(item -> item.versionOn(today).terms().pack().isEmpty() || item.versionOn(today).terms().packSchedule() != null)
                .toList();
        List<RunningOut> found = new ArrayList<>();
        itemCalendar.standings(active, today).forEach((itemId, standing) -> {
            Item item = active.stream().filter(candidate -> candidate.id() == itemId).findFirst().orElseThrow();
            LocalDate uncovered = item.versionOn(today).terms().pack().isPresent()
                    ? itemCalendar.shortBeforeNextPack(item).orElse(null)
                    : standing.result().firstUncovered();
            boolean inPeriod = uncovered != null && within(uncovered, period.start(), period.end());
            boolean alreadyOut = uncovered != null && period.current() && uncovered.isBefore(period.start());
            if (inPeriod || alreadyOut) {
                found.add(new RunningOut(
                        itemId, item.name(), standing.result().left().cents(), standing.result().coversThrough(), uncovered,
                        item.versionOn(today).terms().payFromAccountId()));
            }
        });
        found.sort(Comparator.comparing(RunningOut::name, String.CASE_INSENSITIVE_ORDER));
        return found;
    }

    /**
     * The promotions that run out within {@code start}–{@code end} with part of their balance
     * projected to be still there. One that will be paid off in time is not worth a flag.
     */
    private static List<PromoEnd> promosEnding(Workings workings, List<Account> allAccounts, LocalDate start, LocalDate end) {
        List<PromoEnd> ending = new ArrayList<>();
        for (Account account : allAccounts) {
            List<CardsRepository.Promo> ofCard = workings.promos().getOrDefault(account.id(), List.of());
            if (account.awayOn(start) || ofCard.isEmpty()) {
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
    /** A card's rewards set to build up, as the engine takes them; {@code null} when they come off each statement. */
    private static CardProjector.BuildUp buildUp(CardsRepository.BuildUp settings, List<CardsRepository.Redemption> redemptions) {
        if (settings == null) {
            return null;
        }
        List<CardProjector.Redemption> asked = new ArrayList<>();
        for (CardsRepository.Redemption redemption : redemptions) {
            asked.add(new CardProjector.Redemption(
                    redemption.date(), redemption.amountCents() == null ? null : Money.ofCents(redemption.amountCents()),
                    Math.toIntExact(redemption.id())));
        }
        return new CardProjector.BuildUp(
                settings.yearlyMonth(),
                settings.thresholdCents() == null ? null : Money.ofCents(settings.thresholdCents()),
                settings.startCents() == null ? Money.ZERO : Money.ofCents(settings.startCents()),
                settings.startDate(),
                asked);
    }

    /**
     * A card followed through {@code through}: its statements, what was worked out from its balance, and, for rewards
     * that build up, what had built up by each day something happened to them. {@link CardProjector.Result#NONE} when
     * nothing is known about it.
     */
    public CardProjector.Result cardProjection(Account card, LocalDate from, LocalDate through) {
        return workings(List.of(new PayPeriod(from, through, from))).cards().getOrDefault(card.id(), CardProjector.Result.NONE);
    }

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
     * @param estimatedCents for interest or card rewards typed over the estimate: what was estimated, signed like the amount; {@code null}
     *                    for everything else
     * @param source      what it came from, to open it and change it: {@code null} for what Budget works out itself
     *                    (interest, estimates) and for balances entered
     */
    public record Transaction(
            LocalDate date, String label, String kind, long amountCents, boolean unpaid, Long estimatedCents, Source source) {

        public Transaction(LocalDate date, String label, String kind, long amountCents, boolean unpaid) {
            this(date, label, kind, amountCents, unpaid, null, null);
        }

        Transaction withEstimated(Long estimated) {
            return new Transaction(date, label, kind, amountCents, unpaid, estimated, source);
        }

        Transaction withSource(Source found) {
            return new Transaction(date, label, kind, amountCents, unpaid, estimatedCents, found);
        }
    }

    /**
     * Where a line of an account's transactions came from, so it can be opened to change it, as reconciling against a
     * statement or balance needs (Rachel, 2026-10-21: "so I could adjust or add/delete things if I wanted to
     * reconcile").
     *
     * @param type           {@code entry} (a one-off), {@code occurrence} (one date of a recurring item),
     *                       {@code paycheck} or {@code paid_back} (money planned to come back for a flagged payment)
     * @param entryId        the one-off's id
     * @param itemId         the recurring item's or the income source's id
     * @param scheduledDate  the date the item's or paycheck's schedule names
     * @param dueDate        an occurrence's due date, which its row in the pay period is found by
     * @param periodStart    the pay period it is listed in; {@code null} until the list is made
     * @param categoryId     the one-off's or the occurrence's category; {@code null} for none, and for a paycheck
     */
    public record Source(
            String type, Long entryId, Long itemId, LocalDate scheduledDate, LocalDate dueDate, LocalDate periodStart, Long categoryId) {

        static Source entry(Entry entry) {
            return new Source("entry", entry.id(), null, null, null, null, entry.categoryId());
        }

        static Source occurrence(Occurrence occurrence) {
            return new Source(
                    "occurrence", null, occurrence.itemId(), occurrence.scheduledDate(), occurrence.dueDate(), null, occurrence.categoryId());
        }

        /** Money planned to come back for a flagged payment: found by the payment's item and scheduled date. */
        static Source paidBack(PaymentView payment) {
            return new Source("paid_back", null, payment.itemId(), payment.scheduledDate(), null, null, null);
        }

        /** Money planned to come back for a planned pack: found by its item, and its due day to find its row. */
        static Source paidBack(PackView pack) {
            return new Source("paid_back", null, pack.itemId(), null, pack.dueDate(), null, null);
        }

        /** Money planned to come back for a one-off expense: found by the expense. */
        static Source paidBack(Entry expense) {
            return new Source("paid_back", expense.id(), null, null, null, null, null);
        }

        static Source paycheck(long sourceId, LocalDate scheduledDate) {
            return new Source("paycheck", null, sourceId, scheduledDate, null, null, null);
        }

        Source in(LocalDate start) {
            return new Source(type, entryId, itemId, scheduledDate, dueDate, start, categoryId);
        }
    }

    /** The kinds of line that are interest worked out by Budget, and so can be typed over (spec §5.4). */
    public static final Set<String> TYPED_OVER_KINDS = Set.of(INTEREST_KIND, GROWTH_KIND, CARD_INTEREST_KIND, LOAN_INTEREST_KIND);

    /**
     * The date an amount typed over {@code account}'s interest on {@code date} is kept under: the
     * day itself, or for an investment, whose growth is listed a month at a time, the month's first day.
     */
    public static LocalDate typedOverKey(Account account, LocalDate date) {
        return isInvestment(account.type()) ? date.withDayOfMonth(1) : date;
    }

    /** The rewards line of a card on {@code date}, its statement close, if there is one. */
    public Optional<Transaction> rewardsLine(Account account, LocalDate date) {
        return transactions(account, date, date).stream().filter(line -> CARD_REWARDS_KIND.equals(line.kind())).findFirst();
    }

    /** The interest line of {@code account} on {@code date} (for an investment, that month's growth), if there is one. */
    public Optional<Transaction> interestLine(Account account, LocalDate date) {
        LocalDate from = typedOverKey(account, date);
        LocalDate to = isInvestment(account.type()) ? YearMonth.from(date).atEndOfMonth() : date;
        return transactions(account, from, to).stream()
                .filter(line -> TYPED_OVER_KINDS.contains(line.kind()))
                .findFirst();
    }

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
        Set<LocalDate> endedDays = new HashSet<>();
        for (BalanceEntry entry : accounts.balances(account.id())) {
            if (within(entry.date(), from, to)) {
                found.add(new Transaction(entry.date(), BALANCE_LABEL, BALANCE_KIND, entry.amountCents(), false));
                if (entry.endOfDay()) {
                    endedDays.add(entry.date());
                }
            }
        }
        // Interest typed over says what had been estimated, which also tells the page it is not an estimate.
        Map<LocalDate, InterestRepository.Typed> typed = interest.allTyped().getOrDefault(account.id(), Map.of());
        if (!typed.isEmpty()) {
            found.replaceAll(line -> {
                InterestRepository.Typed over = TYPED_OVER_KINDS.contains(line.kind()) ? typed.get(typedOverKey(account, line.date())) : null;
                return over == null ? line : line.withEstimated(over.estimatedCents());
            });
        }
        // So are a card's rewards typed over; what was estimated is kept as it came off, a negative amount.
        Map<LocalDate, CardsRepository.TypedRewards> rewards = cards.allTypedRewards().getOrDefault(account.id(), Map.of());
        if (!rewards.isEmpty()) {
            found.replaceAll(line -> {
                CardsRepository.TypedRewards over = CARD_REWARDS_KIND.equals(line.kind()) ? rewards.get(line.date()) : null;
                return over == null ? line : line.withEstimated(-over.estimatedCents());
            });
        }
        // Each line opened goes to the pay period it is listed in.
        List<PayCalendar.Period> listedIn = pay.periods(from, to).periods();
        found.replaceAll(line -> line.source() == null ? line : listedIn.stream()
                .filter(period -> within(line.date(), period.start(), period.end()))
                .findFirst()
                .map(period -> line.withSource(line.source().in(period.start())))
                .orElse(line));
        // Newest first, and within a day in the order things happened: a balance the day ended with after (above) the
        // day's movements, one it began with before (below) them.
        found.sort(Comparator.comparing(Transaction::date).reversed()
                .thenComparingInt(line -> !BALANCE_KIND.equals(line.kind()) ? 1 : endedDays.contains(line.date()) ? 0 : 2));
        return found;
    }

    /**
     * One balance the user entered, set against the one before it.
     *
     * @param changeCents   how far it moved from the entry before; {@code null} for the first entry
     * @param depositsCents what was put in between the two, less what was taken out
     * @param growthCents   the change that deposits do not explain: a gain when positive, a loss when
     *                      negative (spec §5.5). Only ever worked out from entered balances
     * @param recordsCents  what the records came to at the moment it stands for (as its day ended, or for one entered
     *                      before 2026-10-30, began), from the entry before and everything since:
     *                      the same figure its pay period adjusts from, to reconcile against (Rachel, 2026-10-21:
     *                      "where's my recon and difference in accounts?"); {@code null} for the first entry
     */
    public record BalanceChange(
            long entryId, LocalDate date, long amountCents, Long changeCents, Long depositsCents, Long growthCents,
            Long recordsCents) {}

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
     * What an investment really grew by, from the first balance entered to the latest, beside
     * what its assumed growth added over the same days (spec §5.5). Both start again from each
     * balance entered in between, so the two are worked out on the same footing.
     *
     * @param from         the day of the first balance entered
     * @param to           the day of the latest balance entered
     * @param growthCents  the change that what was put in does not explain
     * @param assumedCents what the assumed growth added between the two; nothing when no rate was set
     * @param yearlyRateBp the growth as a rate a year; {@code null} when the balances are under
     *                     {@link ActualGrowth#MIN_DAYS_FOR_RATE} days apart or there was nothing to grow
     */
    public record ActualGrowthSoFar(LocalDate from, LocalDate to, long growthCents, long assumedCents, Integer yearlyRateBp) {}

    /**
     * @param thisYear     {@code null} for an account that is not added up at all (archived, with no balance)
     * @param balances     every balance entered, newest first
     * @param actualGrowth {@code null} for anything but an investment, or one with fewer than two balances entered
     */
    public record Performance(YearSoFar thisYear, List<BalanceChange> balances, ActualGrowthSoFar actualGrowth) {}

    /**
     * How {@code account} has changed: this year so far, and from each balance entered to the
     * next. "Deposits" are everything set up that moved the account — paychecks and what is
     * taken out of them, recurring items, one-off entries, transfers — but never interest or
     * assumed growth, which are estimates: those belong to the part deposits do not explain.
     */
    public Performance performance(Account account) {
        LocalDate today = pay.today();
        // Only balances entered so far: one planned for a day still to come says nothing about how it has done.
        List<BalanceEntry> entered = accounts.balances(account.id()).reversed().stream().filter(entry -> !entry.date().isAfter(today)).toList();
        LocalDate yearStart = today.withDayOfYear(1);
        LocalDate earliest = entered.isEmpty() || entered.getFirst().date().isAfter(yearStart) ? yearStart : entered.getFirst().date();
        Workings workings = workings(List.of(new PayPeriod(earliest, today, earliest)));
        List<Transaction> lines = workings.lines().getOrDefault(account.id(), List.of());
        // Every balance after the first is an adjustment in this one stretch, by what it differs from the records.
        Map<LocalDate, Long> differences = new HashMap<>();
        workings.projection(account.id()).balances().forEach(balance -> balance.adjustments()
                .forEach(adjustment -> differences.put(adjustment.date(), adjustment.difference().cents())));

        List<BalanceChange> changes = new ArrayList<>();
        for (int i = 0; i < entered.size(); i++) {
            BalanceEntry entry = entered.get(i);
            if (i == 0) {
                changes.add(new BalanceChange(entry.id(), entry.date(), entry.amountCents(), null, null, null, null));
                continue;
            }
            BalanceEntry before = entered.get(i - 1);
            // Only what neither balance already holds: a balance the day began with leaves that day's movements to what
            // follows it, one the day ended with already has them.
            long deposits = depositsWithin(lines, before.settlesFrom(), entry.settlesFrom().minusDays(1));
            long growth = GainLoss.between(
                    Money.ofCents(before.amountCents()), Money.ofCents(entry.amountCents()), Money.ofCents(deposits)).cents();
            changes.add(new BalanceChange(
                    entry.id(), entry.date(), entry.amountCents(), entry.amountCents() - before.amountCents(), deposits, growth,
                    differences.containsKey(entry.date()) ? (Long) (entry.amountCents() - differences.get(entry.date())) : null));
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
            // A first balance that ended its day already holds that day's deposits.
            long deposits = depositsWithin(lines, first != null && first.date().equals(since) ? first.settlesFrom() : since, today);
            long held = now.getFirst().endingCents();
            thisYear = new YearSoFar(
                    since, start, held, held - start, deposits,
                    GainLoss.between(Money.ofCents(start), Money.ofCents(held), Money.ofCents(deposits)).cents());
        }
        ActualGrowthSoFar actual = isInvestment(account.type()) && entered.size() >= 2
                ? actualGrowth(entered.getFirst(), entered.getLast(), lines, workings.projection(account.id()).interest())
                : null;
        return new Performance(thisYear, changes.reversed(), actual);
    }

    /**
     * {@link ActualGrowthSoFar} from {@code first} to {@code latest}. {@code growthCredits} are the
     * account's assumed growth day by day; none is added on a day a balance was entered, so the
     * credits from one balance up to the day before the next are what the assumption said that
     * stretch would bring.
     */
    private static ActualGrowthSoFar actualGrowth(
            BalanceEntry first, BalanceEntry latest, List<Transaction> lines, List<InterestCredit> growthCredits) {
        LocalDate lastDay = latest.date().minusDays(1);
        List<ActualGrowth.Flow> flows = lines.stream()
                .filter(line -> within(line.date(), first.date(), lastDay))
                .map(line -> new ActualGrowth.Flow(line.date(), Money.ofCents(line.amountCents())))
                .toList();
        long assumed = growthCredits.stream()
                .filter(credit -> within(credit.date(), first.date(), lastDay))
                .mapToLong(credit -> credit.amount().cents())
                .sum();
        ActualGrowth.Result result = ActualGrowth.between(
                first.date(), Money.ofCents(first.amountCents()), latest.date(), Money.ofCents(latest.amountCents()), flows);
        return new ActualGrowthSoFar(first.date(), latest.date(), result.growth().cents(), assumed, result.yearlyRateBp());
    }

    /** What the listed movements did to the balance within {@code from}–{@code to}, both inclusive. */
    private static long depositsWithin(List<Transaction> lines, LocalDate from, LocalDate to) {
        return lines.stream().filter(line -> within(line.date(), from, to)).mapToLong(Transaction::amountCents).sum();
    }

    /**
     * What is put into each account within {@code from}–{@code to}, less what is taken out, by account id:
     * paychecks, contributions and the match, recurring items and one-offs. Interest, growth and the balances
     * entered are left out. Retirement (spec §5.11) takes the coming year's as what goes in every year.
     */
    public Map<Long, Long> flowsWithin(LocalDate from, LocalDate to) {
        Map<Long, Long> flows = new HashMap<>();
        workings(List.of(new PayPeriod(from, to, from))).lines()
                .forEach((accountId, lines) -> flows.put(accountId, depositsWithin(lines, from, to)));
        return flows;
    }

    /** What every bucket holds at the end of {@code day}, by bucket id; one archived as of that day or earlier is not listed. */
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

    /** What one category came to in a pay period; {@code categoryId} is {@code null} for what has none. */
    public record CategoryCents(Long categoryId, long cents) {}

    /**
     * Everything that left a checking or savings account in one pay period (Rachel, 2026-10-24: "an average spend line on the
     * graph", split by category): bills, budgets, one-offs, card payments and transfers out alike, biggest category first.
     * On a credit card it is everything charged to it ("it needs to be on THOSE too"): spending, bills it pays, its monthly
     * estimate and interest; a payment to the card or rewards coming off are not spending. Before today it is what happened,
     * as the account's transactions list it; after, what is scheduled.
     */
    public record PeriodSpending(LocalDate start, LocalDate end, long outCents, List<CategoryCents> categories) {}

    /**
     * What went out of {@code account} in each pay period overlapping {@code from}–{@code through}, from the same lines as its
     * Transactions, so the two always agree. Empty for a loan or investment: what "going out" means there is another thing.
     */
    public List<PeriodSpending> spending(Account account, LocalDate from, LocalDate through) {
        boolean card = Inputs.code(AccountType.CREDIT_CARD).equals(account.type());
        if (!AccountType.holdsCash(account) && !card) {
            return List.of();
        }
        // On a card what is charged raises what is owed, so its lines are positive; elsewhere money out is negative.
        int outward = card ? 1 : -1;
        List<PayPeriod> periods = pay.payPeriods(from, through);
        List<Transaction> lines = workings(periods).lines().getOrDefault(account.id(), List.of());
        List<PeriodSpending> found = new ArrayList<>();
        for (PayPeriod period : periods) {
            // A HashMap, as what has no category is kept under a null key.
            Map<Long, Long> byCategory = new HashMap<>();
            long out = 0;
            for (Transaction line : lines) {
                long cents = outward * line.amountCents();
                if (cents > 0 && within(line.date(), period.start(), period.end())) {
                    byCategory.merge(line.source() == null ? null : line.source().categoryId(), cents, Long::sum);
                    out += cents;
                }
            }
            List<CategoryCents> categories = byCategory.entrySet().stream()
                    .map(category -> new CategoryCents(category.getKey(), category.getValue()))
                    .sorted(Comparator.comparingLong(CategoryCents::cents).reversed())
                    .toList();
            found.add(new PeriodSpending(period.start(), period.end(), out, categories));
        }
        return found;
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
            List<StatementPaid> statementsPaid,
            Map<Long, List<BalanceEntry>> entered) {

        static final Workings NONE = new Workings(
                List.of(), List.of(), List.of(), Map.of(), Set.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of(), Map.of(),
                List.of(), Map.of());

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
            long startingBucketsCents = held == null ? 0 : startingBuckets(held, balance.startingOn(), workings.bucketMoves());
            Long threshold = account.lowBalanceThresholdCents();
            figures.add(new AccountPeriod(
                    account.id(), balance.starting().cents(), balance.startingOn(), balance.entered(),
                    balance.in().cents(), balance.out().cents(), balance.adjusted().cents(), adjustments(balance, workings.entered().getOrDefault(account.id(), List.of())),
                    balance.ending().cents(),
                    balance.lowest().cents(), balance.lowestOn(),
                    bucketsCents,
                    balance.ending().cents() - bucketsCents,
                    startingBucketsCents,
                    balance.starting().cents() - startingBucketsCents,
                    threshold != null && balance.lowest().cents() < threshold,
                    workings.builtFromZero().contains(account.id())));
        }
        return figures;
    }

    /** The period's adjustments, each with the balance entry it came from. */
    private static List<Adjustment> adjustments(PeriodBalance balance, List<BalanceEntry> entries) {
        return balance.adjustments().stream()
                .flatMap(adjustment -> entries.stream()
                        .filter(entry -> entry.date().equals(adjustment.date()) && entry.endOfDay() == adjustment.endOfDay())
                        .findFirst()
                        .map(entry -> {
                            // The balance entered before it: the latest whose money stops before this one's does.
                            Optional<BalanceEntry> before = entries.stream()
                                    .filter(earlier -> earlier.settlesFrom().isBefore(entry.settlesFrom()))
                                    .reduce((earlier, later) -> later);
                            return new Adjustment(
                                    entry.id(), adjustment.date(), adjustment.balance().cents(), adjustment.difference().cents(), entry.note(),
                                    before.map(BalanceEntry::date).orElse(null), entry.endOfDay(),
                                    before.map(BalanceEntry::endOfDay).orElse(false));
                        })
                        .stream())
                .toList();
    }

    /**
     * What an account's buckets hold as its period starts, on {@code startingOn}: what they held at the end of the
     * period before, and what moved them before that day when a balance entered part way through starts there.
     * Anything on the day itself, by hand or scheduled, belongs to the period like any other day, so a period
     * always starts with what the one before ended on (Rachel, 2026-10-21: "available after buckets = starting
     * available for next"). Money set aside by hand on the first day was once counted into the start instead.
     */
    private static long startingBuckets(PeriodBuckets held, LocalDate startingOn, List<Move> moves) {
        Set<Long> ids = new HashSet<>();
        long total = 0;
        for (BucketBalance bucket : held.buckets()) {
            ids.add(bucket.bucketId());
            total += bucket.starting().cents();
        }
        LocalDate periodStart = held.period().start();
        for (Move move : moves) {
            boolean beforeTheDay = !move.date().isBefore(periodStart) && move.date().isBefore(startingOn);
            if (ids.contains(move.bucketId()) && beforeTheDay) {
                total += move.amountCents();
            }
        }
        return total;
    }

    private Workings workings(List<PayPeriod> periods) {
        return workings(periods, null);
    }

    private static List<BucketProjector.Move> engineMoves(List<Move> moves) {
        return moves.stream().map(move -> new BucketProjector.Move(move.bucketId(), move.date(), Money.ofCents(move.amountCents()))).toList();
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
                // A balance entered for the end of the closing day stands at the same moment, so it stands too.
                if (merged.stream().noneMatch(entry -> entry.settlesFrom().equals(dayAfter))) {
                    // A negative id can never be a balance entry's, so it is never mistaken for one.
                    merged.add(new BalanceEntry(-statement.id(), dayAfter, statement.balanceCents(), null, false));
                }
            }
            merged.sort(Comparator.comparing(BalanceEntry::date));
            entered.put(accountId, merged);
        });
        LocalDate first = periods.getFirst().start();
        LocalDate last = periods.getLast().end();

        // A projection rolls forward from the balance entered before the first period, however
        // long ago that was, so everything since the oldest such balance is needed. One entered on the
        // first day itself is not where the period starts, only an adjustment in it, so it is the one before.
        LocalDate from = first;
        for (List<BalanceEntry> balances : entered.values()) {
            LocalDate startsFrom = balances.stream()
                    .map(BalanceEntry::date)
                    .filter(date -> date.isBefore(first))
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
        // Archived items too: each counts until the day it was archived as of, and the calendar leaves out the rest.
        List<Item> active = items.all();
        Set<Long> builtFromZero = allAccounts.stream()
                .filter(account -> !entered.containsKey(account.id()))
                .map(Account::id)
                .collect(Collectors.toSet());
        if (!builtFromZero.isEmpty()) {
            LocalDate earliest = earliestMovement(active);
            if (earliest != null && earliest.isBefore(from)) {
                from = earliest;
            }
        }

        // Cards and loans start from the same day as everything else, whichever pay periods were asked for, so a
        // payment worked out from a card comes to the same whether its own period or a later one is shown (Rachel,
        // 2026-10-21: Mar 31 started $4,500.88 above where Mar 13 ended).
        LocalDate windowStart = from;
        Map<Long, String> types = allAccounts.stream().collect(Collectors.toMap(Account::id, Account::type));
        Set<Long> cash = allAccounts.stream().filter(AccountType::holdsCash).map(Account::id).collect(Collectors.toSet());
        Map<Long, List<Movement>> movements = new HashMap<>();
        // The same movements with their names kept, for the list of an account's transactions.
        Map<Long, List<Transaction>> lines = new HashMap<>();

        for (Paycheck paycheck : pay.paychecks(from, last)) {
            add(movements, lines, types, paycheck.depositAccountId(), paycheck.payDate(), paycheck.netCents(), true, false,
                    paycheck.label(), "paycheck", Source.paycheck(paycheck.incomeSourceId(), paycheck.scheduledDate()));
            // Taken out before take-home pay, or added by the employer: it lands in its own account on payday.
            for (PayCalendar.Contribution contribution : paycheck.contributions()) {
                if (types.containsKey(contribution.toAccountId())) {
                    add(movements, lines, types, contribution.toAccountId(), paycheck.payDate(), contribution.amountCents(), true, false,
                            contribution.label() + " · " + paycheck.label(), contribution.kind());
                }
            }
        }

        Map<Long, StillToPay> stillToPay = new HashMap<>();
        LocalDate today = pay.today();
        entered.forEach((accountId, forAccount) -> {
            // A balance planned for a day still to come settles nothing yet: bills before it are still to pay.
            Optional<BalanceEntry> newestSoFar = forAccount.stream().filter(entry -> !entry.date().isAfter(today)).reduce((earlier, later) -> later);
            if (newestSoFar.isEmpty()) {
                return;
            }
            LocalDate newest = newestSoFar.get().date();
            LocalDate currentPeriod = pay.payPeriods(newest, newest).stream()
                    .filter(period -> period.contains(newest))
                    .map(PayPeriod::start)
                    .findFirst()
                    .orElse(null);
            stillToPay.put(accountId, new StillToPay(newestSoFar.get().settlesFrom(), currentPeriod));
        });

        // A bucket holds everything moved into it since it was opened, and a bill saved up for
        // looks ahead to when it is next due, so buckets widen the stretch occurrences are needed for.
        // Archived buckets too: each holds what it did until the day it was archived as of (Rachel, 2026-10-22: "the
        // previous activity needs to remain").
        List<Bucket> allBuckets = buckets.all();
        LocalDate expandFrom = allBuckets.stream().map(Bucket::openedOn).filter(from::isAfter).min(Comparator.naturalOrder()).orElse(from);
        LocalDate expandTo = allBuckets.isEmpty() ? last : last.plusMonths(BucketMoves.lookAheadMonths(active, last));
        ItemCalendar.Expansion expansion = itemCalendar.expand(active, expandFrom, expandTo);

        // A schedule only reaches a bucket from the day it was opened, so adding a bucket never
        // back-fills it. Before that day a transfer still happens, into no bucket; a bucket
        // move has nothing to do at all, so it is not listed.
        Map<Long, Bucket> byId = allBuckets.stream().collect(Collectors.toMap(Bucket::id, bucket -> bucket));
        // A transfer into a bucket fills it from the day the bucket counts from, whenever the
        // account's balance was entered: that balance already holds the money that arrived
        // earlier, and what was set aside out of it is still set aside.
        List<Occurrence> occurrences = expansion.occurrences().stream()
                .map(occurrence -> reachesItsBucket(occurrence, byId)
                        ? occurrence
                        : occurrence.withoutBucket())
                .filter(occurrence -> occurrence.kind() != ItemKind.BUCKET_MOVE || occurrence.bucketId() != null)
                .toList();
        // A card's own payment follows the card (its statement balance, say) unless it is a fixed
        // amount, so what it comes to is only known once the card itself has been followed.
        Map<Long, CardsRepository.Settings> cardSettings = new HashMap<>(cards.allSettings());
        Map<Long, CardPayment.MinimumRule> minimumRules = cards.minimumRules();
        Map<Long, Integer> rewardsRates = cards.rewardsRates();
        Map<Long, Map<LocalDate, CardsRepository.TypedRewards>> typedRewards = cards.allTypedRewards();
        Map<Long, CardsRepository.BuildUp> rewardsBuildUps = cards.rewardsBuildUps();
        Map<Long, List<CardsRepository.Redemption>> redemptions = cards.allRedemptions();
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
                        outstanding(occurrence, stillToPay.get(loan)), occurrence.name(), Inputs.code(occurrence.kind()),
                        Source.occurrence(occurrence));
                scheduledInto.computeIfAbsent(loan, id -> new ArrayList<>()).add(arriving);
            }
        }
        List<Carried> carried = counting.carried();

        List<Entry> oneOffs = entries.between(from, last);
        for (Entry entry : oneOffs) {
            String label = entryLabel(entry);
            add(movements, lines, types, entry.accountId(), entry.date(), entry.amountCents(), entry.kind() == EntryKind.MONEY_IN, false,
                    label, Inputs.code(entry.kind()), Source.entry(entry));
            if (entry.toAccountId() != null) {
                add(movements, lines, types, entry.toAccountId(), entry.date(), entry.amountCents(), true, false,
                        label, Inputs.code(entry.kind()), Source.entry(entry));
            }
        }

        // What is still owed back on each payment, pack or expense being paid back comes in on the day it is planned. Like a
        // bill not marked paid it is outstanding: late, it still counts, from the newest balance entered (Rachel, 2026-10-24).
        for (MoneyBack back : plannedBack()) {
            if (types.containsKey(back.intoAccountId()) && !back.expectedOn().isAfter(last)) {
                add(movements, lines, types, back.intoAccountId(), back.expectedOn(), back.owedCents(), true, true,
                        "Paid back: " + back.name(), PAID_BACK_KIND, back.source());
            }
            // Out of one of her own accounts it is a transfer: it leaves that one the same day.
            if (back.fromAccountId() != null && types.containsKey(back.fromAccountId()) && !back.expectedOn().isAfter(last)) {
                add(movements, lines, types, back.fromAccountId(), back.expectedOn(), back.owedCents(), false, true,
                        "Pays back: " + back.name(), PAID_BACK_KIND, back.source());
            }
        }

        // Credit cards: the month's estimated spending and interest, and what each payment that
        // follows the card comes to. All of it depends on the card's own balance, so it is worked
        // out here, once everything else that moves the card is known.
        Map<Long, List<CardsRepository.Promo>> promos = cards.allPromos();
        Map<Long, InterestTerms> aprs = new HashMap<>();
        // Amounts typed over an estimate (spec §5.4) ride along with each account's rate, so the month's interest is what was typed.
        Map<Long, Map<LocalDate, InterestRepository.Typed>> typed = interest.allTyped();
        interest.allRates(RateKind.APR).forEach((cardId, rates) -> aprs.put(cardId, typed(interestTerms(null, rates), typed.get(cardId))));
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
                    anchors(cardId, entered, builtFromZero, since, windowStart),
                    fixed,
                    toWorkOut,
                    new CardProjector.Terms(
                            settings.statementCloseDay(), settings.dueDay(), settings.paymentMode(),
                            settings.monthlyEstimateCents() == null ? null : Money.ofCents(settings.monthlyEstimateCents()),
                            aprs.get(cardId),
                            promos.getOrDefault(cardId, List.of()).stream().map(CardsRepository.Promo::terms).toList(),
                            real,
                            minimumRules.getOrDefault(cardId, CardPayment.MinimumRule.USUAL))
                            .withRewards(
                                    rewardsRates.containsKey(cardId) ? BasisPoints.of(rewardsRates.get(cardId)) : null,
                                    typedRewards.getOrDefault(cardId, Map.of()).values().stream().collect(Collectors.toMap(
                                            CardsRepository.TypedRewards::closeDate, rewards -> Money.ofCents(rewards.amountCents()))))
                            .withRewardsBuildUp(buildUp(rewardsBuildUps.get(cardId), redemptions.getOrDefault(cardId, List.of()))),
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
                    case REWARDS -> add(movements, lines, types, cardId, derived.date(), derived.amount().cents(), false, false,
                            CARD_REWARDS_LABEL, CARD_REWARDS_KIND);
                    case REWARDS_REDEEMED -> add(movements, lines, types, cardId, derived.date(), derived.amount().cents(), false, false,
                            CARD_REWARDS_REDEEMED_LABEL, CARD_REWARDS_REDEEMED_KIND);
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
                    anchors(loanId, entered, builtFromZero, since, windowStart),
                    List.copyOf(scheduledInto.getOrDefault(loanId, List.of())),
                    all.stream().filter(movement -> !regular.contains(movement)).toList(),
                    new LoanProjector.Terms(
                            settings.paymentDay(), Money.ofCents(settings.escrowCents()),
                            typed(interestTerms(null, loanRates.get(loanId)), typed.get(loanId)),
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
                earning.put(account.id(), typed(terms, typed.get(account.id())));
            }
        }
        Map<Long, Projection> projections = new HashMap<>();
        entered.forEach((accountId, forAccount) -> {
            List<BalanceAnchor> anchors = forAccount.stream()
                    .map(entry -> new BalanceAnchor(entry.date(), Money.ofCents(entry.amountCents()), entry.endOfDay()))
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
        Map<Long, Long> stopAt = allBuckets.stream()
                .filter(bucket -> bucket.stopAtGoal() && bucket.goalCents() != null)
                .collect(Collectors.toMap(Bucket::id, Bucket::goalCents));
        BucketMoves.Capped capped = BucketMoves.stopAtGoals(
                allBuckets.isEmpty()
                        ? List.<Move>of()
                        : BucketMoves.derive(
                                allBuckets, buckets.allChanges(), entries.withBucketSplits(), active, settled, expansion.visits(), expansion.periods()),
                stopAt);
        // Amounts the user set for a day are worked out last, from everything before them as it stands now.
        List<Move> bucketMoves = new ArrayList<>(BucketMoves.settle(BucketMoves.neverOverdrawn(capped.moves())));
        List<Occurrence> listed = settled.stream()
                .map(occurrence -> {
                    Long room = occurrence.kind() == ItemKind.BUCKET_MOVE && !occurrence.skipped()
                            ? capped.changed().get(new BucketMoves.MoveKey(occurrence.itemId(), occurrence.payBy()))
                            : null;
                    return room == null ? occurrence : room == 0 ? null : occurrence.withAmount(room);
                })
                .filter(Objects::nonNull)
                // A card's own payment that follows the card and comes to nothing, because nothing is owed or nothing
                // is known about the card yet, is not listed (Rachel, 2026-10-22: "it shows the statement balance for
                // a specific month on every month before that - it should be 0").
                .filter(occurrence -> !(occurrence.amountCents() == 0 && !occurrence.paid() && occurrence.projectedCents() == null
                        && followsCard.containsKey(occurrence.itemId())))
                .toList();
        Map<Long, List<PeriodBuckets>> bucketsByAccount = new HashMap<>();
        allBuckets.stream().collect(Collectors.groupingBy(Bucket::accountId)).forEach((accountId, ofAccount) -> {
            Set<Long> ids = ofAccount.stream().map(Bucket::id).collect(Collectors.toSet());
            List<BucketProjector.Bucket> followed =
                    ofAccount.stream().map(bucket -> new BucketProjector.Bucket(bucket.id(), bucket.openedOn(), bucket.archivedOn())).toList();
            List<Move> ofTheseBuckets = bucketMoves.stream().filter(move -> ids.contains(move.bucketId())).toList();
            List<InterestCredit> interest = projections.getOrDefault(accountId, Projection.NONE).interest();
            BucketProjector.InterestRule rule = interestRule(interestSettings.get(accountId), ids);
            BucketProjector.Result result = BucketProjector.project(followed, engineMoves(ofTheseBuckets), interest, rule, periods);
            // Interest that reached a bucket before an amount set for it is part of that amount: worked out again with it.
            List<Move> withInterest = BucketMoves.absorbInterest(ofTheseBuckets, result.interestShares().stream()
                    .map(share -> new Move(share.bucketId(), share.date(), share.amount().cents(), BucketMoves.Kind.INTEREST, INTEREST_LABEL, null))
                    .toList());
            if (withInterest != ofTheseBuckets) {
                bucketMoves.removeAll(ofTheseBuckets);
                bucketMoves.addAll(withInterest);
                result = BucketProjector.project(followed, engineMoves(withInterest), interest, rule, periods);
            }
            bucketsByAccount.put(accountId, result.periods());
            for (BucketProjector.Move share : result.interestShares()) {
                bucketMoves.add(new Move(
                        share.bucketId(), share.date(), share.amount().cents(), BucketMoves.Kind.INTEREST, INTEREST_LABEL, null));
            }
        });
        bucketMoves.sort(Comparator.comparing(Move::date));
        return new Workings(
                listed, carried, oneOffs, projections, builtFromZero, lines, bucketsByAccount, bucketMoves, cardResults, promos, aprs,
                statementsPaid, entered);
    }

    /**
     * The balances a card or loan is followed from. With balances entered: the one in force when
     * the stretch being worked out starts ({@code first}), and any after it (nothing is known about what moved it before
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
                .map(entry -> new BalanceAnchor(entry.date(), Money.ofCents(entry.amountCents()), entry.endOfDay()))
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
            Source source = Source.occurrence(occurrence);
            add(movements, lines, types, payFrom, occurrence.payBy(), cents, false, unpaid,
                    occurrence.name(), Inputs.code(occurrence.kind()), source);
            Long payTo = occurrence.payToAccountId();
            if (payTo != null) {
                Movement arriving = add(movements, lines, types, payTo, occurrence.payBy(), cents, true,
                        outstanding(occurrence, stillToPay.get(payTo)), occurrence.name(), Inputs.code(occurrence.kind()), source);
                scheduledInto.computeIfAbsent(payTo, id -> new ArrayList<>()).add(arriving);
            }
        }
    }

    /** True unless the occurrence names a bucket that was not open yet, or was archived, on the day it counts. */
    private static boolean reachesItsBucket(Occurrence occurrence, Map<Long, Bucket> buckets) {
        if (occurrence.bucketId() == null) {
            return true;
        }
        Bucket bucket = buckets.get(occurrence.bucketId());
        return bucket != null && !occurrence.payBy().isBefore(bucket.openedOn()) && !Archiving.awayOn(bucket.archivedOn(), occurrence.payBy());
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

    /** {@code terms} with the amounts typed over for its account; {@code null} stays {@code null}: with no rate there is nothing estimated. */
    private static InterestTerms typed(InterestTerms terms, Map<LocalDate, InterestRepository.Typed> typed) {
        if (terms == null || typed == null) {
            return terms;
        }
        Map<LocalDate, Money> amounts = new HashMap<>();
        typed.forEach((date, line) -> amounts.put(date, Money.ofCents(line.amountCents())));
        return terms.withTyped(amounts);
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
     * @param newestEntered      the first day that balance does not already hold: its own day for one the day began
     *                           with, the next for one it ended with
     * @param currentPeriodStart {@code null} when that day is outside every pay period
     */
    private record StillToPay(LocalDate newestEntered, LocalDate currentPeriodStart) {}

    /**
     * Whether an occurrence is still to leave the account even though a balance was entered
     * after the day it counts on. That is so when it is paid by hand, has not been ticked as
     * paid, and either is not due yet or belongs to the pay period the balance was entered
     * in — the unticked bills of the list being worked through. Anything older is taken as
     * paid, so nobody has to tick off months of history before the numbers make sense. A budget is never ticked as paid,
     * so like autopay it is taken as spent on its day (Rachel, 2026-10-25: budgets were listed as still unpaid under Pay
     * manually).
     */
    private static boolean outstanding(Occurrence occurrence, StillToPay account) {
        if (account == null || occurrence.autopay() || occurrence.paid() || occurrence.kind() == ItemKind.BUDGET) {
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
        return add(movements, lines, types, accountId, date, cents, incoming, outstanding, label, kind, null);
    }

    /** As above, for something the user entered or scheduled: {@code source} is what to open to change it. */
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
            String kind,
            Source source) {
        boolean raisesBalance = OWED.contains(types.get(accountId)) != incoming;
        Money amount = Money.ofCents(cents);
        Movement movement = new Movement(date, raisesBalance ? amount : amount.negate(), outstanding);
        movements.computeIfAbsent(accountId, id -> new ArrayList<>()).add(movement);
        lines.computeIfAbsent(accountId, id -> new ArrayList<>())
                .add(new Transaction(date, label, kind, raisesBalance ? cents : -cents, outstanding, null, source));
        return movement;
    }

    private static boolean within(LocalDate date, LocalDate from, LocalDate to) {
        return !date.isBefore(from) && !date.isAfter(to);
    }

    private static List<PayPeriod> payPeriods(List<PayCalendar.Period> periods) {
        return periods.stream().map(period -> new PayPeriod(period.start(), period.end(), period.scheduledPayday())).toList();
    }
}
