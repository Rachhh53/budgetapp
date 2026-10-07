package app.budget.server.ledger;

import app.budget.engine.Money;
import app.budget.engine.projection.BalanceAnchor;
import app.budget.engine.projection.BalanceProjector;
import app.budget.engine.projection.Movement;
import app.budget.engine.projection.PeriodBalance;
import app.budget.engine.schedule.PayPeriod;
import app.budget.server.accounts.Account;
import app.budget.server.accounts.Account.BalanceEntry;
import app.budget.server.accounts.AccountType;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.Inputs;
import app.budget.server.entries.EntriesRepository;
import app.budget.server.entries.EntriesRepository.Entry;
import app.budget.server.entries.EntryKind;
import app.budget.server.income.PayCalendar;
import app.budget.server.income.PayCalendar.Paycheck;
import app.budget.server.items.ItemCalendar;
import app.budget.server.items.ItemCalendar.Occurrence;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Puts everything that moves money together, pay period by pay period: paychecks, recurring
 * items, one-off entries and the balances the user entered. The arithmetic is the engine's
 * ({@link BalanceProjector}); this class decides what moves, in which account, on which day.
 * Like the calendars it reads from, nothing here is saved.
 */
public final class Ledger {

    /** Accounts whose balance is what is owed: spending raises it and a payment lowers it. */
    private static final Set<String> OWED = Set.of(Inputs.code(AccountType.CREDIT_CARD), Inputs.code(AccountType.LOAN));

    private final AccountsRepository accounts;
    private final EntriesRepository entries;
    private final ItemCalendar itemCalendar;
    private final PayCalendar pay;

    public Ledger(AccountsRepository accounts, EntriesRepository entries, ItemCalendar itemCalendar, PayCalendar pay) {
        this.accounts = accounts;
        this.entries = entries;
        this.itemCalendar = itemCalendar;
        this.pay = pay;
    }

    /**
     * One account across one pay period; {@code ending = starting + in − out}.
     *
     * <p>For a credit card or loan the balance is what is owed, so {@code inCents} is what was
     * added to it (charges) and {@code outCents} what came off it (payments).
     *
     * @param startingOn the period's first day, or the day of a balance entered during it
     * @param entered    true when the starting balance is one the user entered during the period
     * @param lowestCents the lowest end-of-day balance in the period
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
            LocalDate lowestOn) {}

    /**
     * A pay period in the list of them.
     *
     * @param projectedEndingCents the primary paycheck's account at the end of the period;
     *                             {@code null} until a balance has been entered for it
     */
    public record PeriodSummary(
            LocalDate start,
            LocalDate end,
            LocalDate scheduledPayday,
            boolean current,
            long incomeCents,
            List<Paycheck> paychecks,
            Long projectedEndingCents,
            Long lowestCents) {}

    /** @param primarySourceId {@code null} until an income source exists; then there are no periods either */
    public record Periods(Long primarySourceId, Long primaryAccountId, List<PeriodSummary> periods) {}

    /**
     * Everything in one pay period.
     *
     * @param occurrences recurring items paid from this period, skipped ones included (and flagged)
     * @param carriedOver items from an earlier period that are still unpaid, so their money comes
     *                    out of this one: what makes the figures differ from the list above
     * @param entries     one-off entries dated in this period
     * @param accounts    every account a balance is known for
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
            List<AccountPeriod> accounts) {}

    /** The pay periods overlapping {@code from}–{@code through}, each with its projected ending balance. */
    public Periods periods(LocalDate from, LocalDate through) {
        PayCalendar.Periods found = pay.periods(from, through);
        Long primaryAccountId = pay.primaryAccountId().orElse(null);
        Map<LocalDate, PeriodBalance> primary = new HashMap<>();
        if (primaryAccountId != null) {
            for (PeriodBalance balance : workings(payPeriods(found.periods())).balances().getOrDefault(primaryAccountId, List.of())) {
                primary.put(balance.period().start(), balance);
            }
        }
        List<PeriodSummary> summaries = found.periods().stream()
                .map(period -> {
                    PeriodBalance balance = primary.get(period.start());
                    return new PeriodSummary(
                            period.start(), period.end(), period.scheduledPayday(), period.current(),
                            period.incomeCents(), period.paychecks(),
                            balance == null ? null : (Long) balance.ending().cents(),
                            balance == null ? null : (Long) balance.lowest().cents());
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
        for (Account account : accounts.all()) {
            for (PeriodBalance balance : workings.balances().getOrDefault(account.id(), List.of())) {
                accountPeriods.add(new AccountPeriod(
                        account.id(), balance.starting().cents(), balance.startingOn(), balance.entered(),
                        balance.in().cents(), balance.out().cents(), balance.ending().cents(),
                        balance.lowest().cents(), balance.lowestOn()));
            }
        }
        return Optional.of(new PeriodDetail(
                period.start(),
                period.end(),
                period.scheduledPayday(),
                period.current(),
                pay.primaryAccountId().orElse(null),
                period.paychecks(),
                workings.occurrences().stream().filter(occurrence -> start.equals(occurrence.periodStart())).toList(),
                workings.carried().stream()
                        .filter(carried -> !carried.countsOn().isBefore(period.start()) && !carried.countsOn().isAfter(period.end()))
                        .map(Carried::occurrence)
                        .toList(),
                workings.entries().stream()
                        .filter(entry -> !entry.date().isBefore(period.start()) && !entry.date().isAfter(period.end()))
                        .toList(),
                accountPeriods));
    }

    /** What was gathered to project {@code periods}: wider than the periods themselves. */
    private record Workings(
            List<Occurrence> occurrences, List<Carried> carried, List<Entry> entries, Map<Long, List<PeriodBalance>> balances) {}

    /** An unpaid occurrence whose money was carried forward to the day a balance was entered. */
    private record Carried(Occurrence occurrence, LocalDate countsOn) {}

    private Workings workings(List<PayPeriod> periods) {
        if (periods.isEmpty()) {
            return new Workings(List.of(), List.of(), List.of(), Map.of());
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

        Map<Long, String> types = accounts.all().stream().collect(Collectors.toMap(Account::id, Account::type));
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

        List<Occurrence> occurrences = itemCalendar.expand(from, last).occurrences();
        List<Carried> carried = new ArrayList<>();
        for (Occurrence occurrence : occurrences) {
            if (occurrence.skipped()) {
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

        Map<Long, List<PeriodBalance>> balances = new HashMap<>();
        entered.forEach((accountId, forAccount) -> {
            List<BalanceAnchor> anchors = forAccount.stream()
                    .map(entry -> new BalanceAnchor(entry.date(), Money.ofCents(entry.amountCents())))
                    .toList();
            balances.put(accountId, BalanceProjector.project(anchors, movements.getOrDefault(accountId, List.of()), periods));
        });
        return new Workings(occurrences, carried, oneOffs, balances);
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

    private static List<PayPeriod> payPeriods(List<PayCalendar.Period> periods) {
        return periods.stream().map(period -> new PayPeriod(period.start(), period.end(), period.scheduledPayday())).toList();
    }
}
