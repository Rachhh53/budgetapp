package app.budget.server.ledger;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.buckets.BucketProjector;
import app.budget.engine.buckets.BucketProjector.BucketBalance;
import app.budget.engine.buckets.BucketProjector.InterestDestination;
import app.budget.engine.buckets.BucketProjector.InterestRule;
import app.budget.engine.buckets.BucketProjector.PeriodBuckets;
import app.budget.engine.interest.InterestTerms;
import app.budget.engine.interest.InterestTerms.RateChange;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    private final AccountsRepository accounts;
    private final EntriesRepository entries;
    private final ItemsRepository items;
    private final ItemCalendar itemCalendar;
    private final PayCalendar pay;
    private final BucketsRepository buckets;
    private final InterestRepository interest;

    public Ledger(
            AccountsRepository accounts,
            EntriesRepository entries,
            ItemsRepository items,
            ItemCalendar itemCalendar,
            PayCalendar pay,
            BucketsRepository buckets,
            InterestRepository interest) {
        this.accounts = accounts;
        this.entries = entries;
        this.items = items;
        this.itemCalendar = itemCalendar;
        this.pay = pay;
        this.buckets = buckets;
        this.interest = interest;
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

    /** A month's estimated interest arriving in an account. */
    public record InterestLine(long accountId, LocalDate date, long amountCents, int rateBp) {}

    /**
     * A pay period in the list of them.
     *
     * @param projectedEndingCents the primary paycheck's account at the end of the period;
     *                             {@code null} until a balance has been entered for it
     * @param availableCents       that ending balance less what is in the account's buckets
     * @param lowBalance           true when that account dips below the warning the user set
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
            boolean lowBalance) {}

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
            List<Move> bucketMoves) {}

    /** One account at the end of one pay period: a point on its chart. */
    public record AccountPoint(LocalDate start, LocalDate end, long endingCents, long lowestCents, long availableCents) {}

    /** The pay periods overlapping {@code from}–{@code through}, each with its projected ending balance. */
    public Periods periods(LocalDate from, LocalDate through) {
        PayCalendar.Periods found = pay.periods(from, through);
        Long primaryAccountId = pay.primaryAccountId().orElse(null);
        Map<LocalDate, AccountPeriod> primary = new HashMap<>();
        if (primaryAccountId != null) {
            Workings workings = workings(payPeriods(found.periods()));
            List<PeriodBalance> balances = workings.projection(primaryAccountId).balances();
            List<AccountPeriod> figures = accountPeriods(workings, accounts.find(primaryAccountId).orElseThrow());
            for (int i = 0; i < balances.size(); i++) {
                primary.put(balances.get(i).period().start(), figures.get(i));
            }
        }
        List<PeriodSummary> summaries = found.periods().stream()
                .map(period -> {
                    AccountPeriod account = primary.get(period.start());
                    return new PeriodSummary(
                            period.start(), period.end(), period.scheduledPayday(), period.current(),
                            period.incomeCents(), period.paychecks(),
                            account == null ? null : (Long) account.endingCents(),
                            account == null ? null : (Long) account.lowestCents(),
                            account == null ? null : (Long) account.availableCents(),
                            account != null && account.lowBalance());
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
                // Interest dated before a balance entered this period is already in that balance.
                for (InterestCredit credit : workings.projection(account.id()).interest()) {
                    if (!credit.date().isBefore(figures.startingOn()) && !credit.date().isAfter(period.end())) {
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
                workings.bucketMoves().stream().filter(move -> within(move.date(), period.start(), period.end())).toList()));
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
     * still be set aside. Empty until a balance has been entered for it: a balance added up from
     * nothing is only as complete as what has been set up so far, too little to refuse anything on.
     */
    public Optional<Long> availableOn(Account account, LocalDate day) {
        List<AccountPeriod> figures = accountPeriods(workings(List.of(new PayPeriod(day, day, day))), account);
        return figures.isEmpty() || figures.getFirst().builtFromZero()
                ? Optional.empty()
                : Optional.of(figures.getFirst().availableCents());
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

    /** One account at the end of each pay period overlapping {@code from}–{@code through}; empty until it has a balance. */
    public List<AccountPoint> accountSeries(Account account, LocalDate from, LocalDate through) {
        Workings workings = workings(pay.payPeriods(from, through));
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
            Map<Long, List<PeriodBuckets>> buckets,
            List<Move> bucketMoves) {

        static final Workings NONE = new Workings(List.of(), List.of(), List.of(), Map.of(), Set.of(), Map.of(), List.of());

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
        if (periods.isEmpty()) {
            return Workings.NONE;
        }
        Map<Long, List<BalanceEntry>> entered = accounts.allBalances();
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

        for (Paycheck paycheck : pay.paychecks(from, last)) {
            add(movements, types, paycheck.depositAccountId(), paycheck.payDate(), paycheck.netCents(), true, false);
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
        // A transfer into a bucket also has to arrive in the bucket's account. The account only
        // adds up from the first balance entered for it, so the bucket takes the transfer from that
        // day too: otherwise a backdated transfer would fill the bucket with money the account's
        // balance never shows, and the buckets would hold more than the account does. An account
        // with no balance entered is added up from nothing, so it takes every transfer.
        Map<Long, LocalDate> firstBalance = new HashMap<>();
        entered.forEach((accountId, forAccount) -> firstBalance.put(accountId, forAccount.getFirst().date()));
        List<Occurrence> occurrences = expansion.occurrences().stream()
                .map(occurrence -> reachesItsBucket(occurrence, openedOn) && arrivesWithItsAccount(occurrence, firstBalance)
                        ? occurrence
                        : occurrence.withoutBucket())
                .filter(occurrence -> occurrence.kind() != ItemKind.BUCKET_MOVE || occurrence.bucketId() != null)
                .toList();
        List<Carried> carried = new ArrayList<>();
        for (Occurrence occurrence : occurrences) {
            // A bucket move sets money aside inside its account: the account's balance does not change.
            if (occurrence.skipped() || occurrence.kind() == ItemKind.BUCKET_MOVE) {
                continue;
            }
            long payFrom = occurrence.payFromAccountId();
            StillToPay paidFrom = stillToPay.get(payFrom);
            boolean unpaid = outstanding(occurrence, paidFrom);
            if (unpaid && occurrence.payBy().isBefore(paidFrom.newestEntered())) {
                // The engine moves it to the day of that balance; remember it so the period can say so.
                carried.add(new Carried(occurrence, paidFrom.newestEntered()));
            }
            add(movements, types, payFrom, occurrence.payBy(), occurrence.amountCents(), false, unpaid);
            Long payTo = occurrence.payToAccountId();
            if (payTo != null) {
                add(movements, types, payTo, occurrence.payBy(), occurrence.amountCents(), true,
                        outstanding(occurrence, stillToPay.get(payTo)));
            }
        }

        List<Entry> oneOffs = entries.between(from, last);
        for (Entry entry : oneOffs) {
            add(movements, types, entry.accountId(), entry.date(), entry.amountCents(), entry.kind() == EntryKind.MONEY_IN, false);
            if (entry.toAccountId() != null) {
                add(movements, types, entry.toAccountId(), entry.date(), entry.amountCents(), true, false);
            }
        }

        Map<Long, Settings> interestSettings = interest.allSettings();
        Map<Long, List<Rate>> apys = interest.allRates(RateKind.APY);
        Map<Long, Projection> projections = new HashMap<>();
        entered.forEach((accountId, forAccount) -> {
            List<BalanceAnchor> anchors = forAccount.stream()
                    .map(entry -> new BalanceAnchor(entry.date(), Money.ofCents(entry.amountCents())))
                    .toList();
            InterestTerms terms = cash.contains(accountId)
                    ? interestTerms(interestSettings.get(accountId), apys.get(accountId))
                    : null;
            projections.put(accountId, BalanceProjector.project(anchors, movements.getOrDefault(accountId, List.of()), periods, terms));
        });
        for (long accountId : builtFromZero) {
            List<Movement> counted = movements.getOrDefault(accountId, List.of());
            LocalDate since = counted.stream().map(Movement::date).min(Comparator.naturalOrder()).orElse(first);
            InterestTerms terms = cash.contains(accountId)
                    ? interestTerms(interestSettings.get(accountId), apys.get(accountId))
                    : null;
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
                        : BucketMoves.derive(open, buckets.allChanges(), entries.withBucketSplits(), active, occurrences, expansion.periods()),
                stopAt);
        List<Move> bucketMoves = new ArrayList<>(capped.moves());
        List<Occurrence> listed = occurrences.stream()
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
        return new Workings(listed, carried, oneOffs, projections, builtFromZero, bucketsByAccount, bucketMoves);
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
     * True unless this is a transfer into a bucket dated before the first balance entered for
     * its account. Money set aside inside an account (a bucket move) moves nothing between
     * accounts, so it is never held back by this; nor is anything arriving in an account with
     * no balance entered, which is added up from nothing and so counts all of it.
     */
    private static boolean arrivesWithItsAccount(Occurrence occurrence, Map<Long, LocalDate> firstBalance) {
        if (occurrence.bucketId() == null || occurrence.payToAccountId() == null) {
            return true;
        }
        LocalDate first = firstBalance.get(occurrence.payToAccountId());
        return first == null || !occurrence.payBy().isBefore(first);
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

    /**
     * How an account earns interest; {@code null} when no APY has been entered for it. Until
     * the user says otherwise, interest is credited on the last day of the month.
     */
    private static InterestTerms interestTerms(Settings settings, List<Rate> apys) {
        if (apys == null || apys.isEmpty()) {
            return null;
        }
        return new InterestTerms(
                (settings == null ? Settings.DEFAULT : settings).creditDay(),
                apys.stream().map(rate -> new RateChange(rate.effectiveDate(), BasisPoints.of(rate.rateBp()))).toList());
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
    private static void add(
            Map<Long, List<Movement>> movements,
            Map<Long, String> types,
            long accountId,
            LocalDate date,
            long cents,
            boolean incoming,
            boolean outstanding) {
        boolean raisesBalance = OWED.contains(types.get(accountId)) != incoming;
        Money amount = Money.ofCents(cents);
        movements.computeIfAbsent(accountId, id -> new ArrayList<>())
                .add(new Movement(date, raisesBalance ? amount : amount.negate(), outstanding));
    }

    private static boolean within(LocalDate date, LocalDate from, LocalDate to) {
        return !date.isBefore(from) && !date.isAfter(to);
    }

    private static List<PayPeriod> payPeriods(List<PayCalendar.Period> periods) {
        return periods.stream().map(period -> new PayPeriod(period.start(), period.end(), period.scheduledPayday())).toList();
    }
}
