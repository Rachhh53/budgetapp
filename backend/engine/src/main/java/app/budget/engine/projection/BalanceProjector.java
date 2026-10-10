package app.budget.engine.projection;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.interest.InterestTerms;
import app.budget.engine.interest.SavingsInterest;
import app.budget.engine.schedule.PayPeriod;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Projects one account's balance through the pay periods (spec §5.3): start from the latest
 * balance the user entered, then apply everything dated from that day on, in date order.
 *
 * <p>The caller decides what moves and on which day (paychecks, bills, budgets, transfers,
 * one-offs). This class only does the arithmetic, so new kinds of movement need no change
 * here. Interest is the exception: it depends on the balance itself, so it is worked out here.
 */
public final class BalanceProjector {

    private BalanceProjector() {}

    /**
     * @param anchors   balances the user entered for this account, in any order
     * @param movements everything that moves this account's balance, in any order
     * @param periods   consecutive pay periods, earliest first
     * @return one entry per period; a period that ends before the first balance entered stands at
     *         that balance, dated the day it was entered, with nothing counted. Empty when
     *         no balance has been entered yet
     */
    public static List<PeriodBalance> project(List<BalanceAnchor> anchors, List<Movement> movements, List<PayPeriod> periods) {
        return project(anchors, movements, periods, null).balances();
    }

    /**
     * As {@link #project(List, List, List)}, for an account that earns interest (spec §5.4).
     * Each month's interest is estimated from the balance its credit day starts with and
     * then counts like any other money arriving, so later months earn interest on it too.
     *
     * @param interest how the account earns interest; {@code null} when it does not
     */
    public static Projection project(
            List<BalanceAnchor> anchors, List<Movement> movements, List<PayPeriod> periods, InterestTerms interest) {
        if (anchors.isEmpty()) {
            return Projection.NONE;
        }
        TreeMap<LocalDate, Money> entered = new TreeMap<>();
        for (BalanceAnchor anchor : anchors) {
            entered.put(anchor.date(), anchor.balance());
        }
        TreeMap<LocalDate, List<Money>> applied = applied(movements, entered.lastKey());
        List<InterestCredit> credits = interest == null || periods.isEmpty()
                ? List.of()
                : creditInterest(interest, entered, applied, periods.getLast().end());

        List<PeriodBalance> result = new ArrayList<>();
        for (PayPeriod period : periods) {
            Map.Entry<LocalDate, Money> latest = entered.floorEntry(period.end());
            if (latest == null) {
                // Before the first balance entered. Nothing earlier than a balance is counted, so there is
                // nothing to add up yet; the account is still shown, at that first balance, rather than
                // seeming not to exist. Its date (after this period) tells the caller which case this is.
                Map.Entry<LocalDate, Money> first = entered.firstEntry();
                result.add(new PeriodBalance(
                        period, first.getValue(), first.getKey(), false, Money.ZERO, Money.ZERO,
                        first.getValue(), first.getValue(), first.getKey()));
                continue;
            }
            boolean enteredDuring = !latest.getKey().isBefore(period.start());
            Money starting = enteredDuring ? latest.getValue() : balanceAtStartOf(period.start(), entered, applied);
            // From an entered balance only that day onwards counts; otherwise the whole period does.
            LocalDate startingOn = enteredDuring ? latest.getKey() : period.start();
            Money in = Money.ZERO;
            Money out = Money.ZERO;
            Money running = starting;
            Money lowest = starting;
            LocalDate lowestOn = startingOn;
            for (Map.Entry<LocalDate, List<Money>> day : applied.subMap(startingOn, true, period.end(), true).entrySet()) {
                for (Money amount : day.getValue()) {
                    if (amount.isNegative()) {
                        out = out.minus(amount);
                    } else {
                        in = in.plus(amount);
                    }
                    running = running.plus(amount);
                }
                // Judged at the end of each day: a paycheck and a bill on the same day net out.
                if (running.compareTo(lowest) < 0) {
                    lowest = running;
                    lowestOn = day.getKey();
                }
            }
            result.add(new PeriodBalance(period, starting, startingOn, enteredDuring, in, out, running, lowest, lowestOn));
        }
        return new Projection(result, credits);
    }

    /**
     * Works out each month's interest, earliest first, and adds it to {@code applied} as it
     * goes, so that every month is estimated on a balance that includes the months before.
     * A month that falls before a later entered balance is still estimated (a bucket may be
     * owed its share), but that entered balance, not the estimate, is what the account holds.
     */
    private static List<InterestCredit> creditInterest(
            InterestTerms terms, TreeMap<LocalDate, Money> entered, TreeMap<LocalDate, List<Money>> applied, LocalDate through) {
        List<InterestCredit> credits = new ArrayList<>();
        for (LocalDate day : SavingsInterest.creditDates(terms.creditDay(), entered.firstKey(), through)) {
            Optional<BasisPoints> apy = terms.rateOn(day);
            if (apy.isEmpty()) {
                continue;
            }
            Money balance = balanceAtStartOf(day, entered, applied);
            Money amount = SavingsInterest.forMonth(balance, apy.get());
            if (amount.isZero()) {
                continue;
            }
            applied.computeIfAbsent(day, key -> new ArrayList<>()).add(amount);
            credits.add(new InterestCredit(day, amount, balance, apy.get()));
        }
        return credits;
    }

    /**
     * Movements by the day they take effect. Something still outstanding when the newest
     * balance was entered has yet to leave the account, so it is carried forward to that day.
     */
    private static TreeMap<LocalDate, List<Money>> applied(List<Movement> movements, LocalDate newestEntered) {
        TreeMap<LocalDate, List<Money>> byDay = new TreeMap<>();
        movements.stream()
                .sorted(Comparator.comparing(Movement::date))
                .forEach(movement -> {
                    LocalDate day = movement.outstanding() && movement.date().isBefore(newestEntered)
                            ? newestEntered
                            : movement.date();
                    byDay.computeIfAbsent(day, key -> new ArrayList<>()).add(movement.amount());
                });
        return byDay;
    }

    /** The balance {@code date} starts with: the latest one entered on or before it, plus everything applied since. */
    private static Money balanceAtStartOf(LocalDate date, TreeMap<LocalDate, Money> entered, TreeMap<LocalDate, List<Money>> applied) {
        Map.Entry<LocalDate, Money> anchor = entered.floorEntry(date);
        Money balance = anchor.getValue();
        for (List<Money> day : applied.subMap(anchor.getKey(), true, date, false).values()) {
            for (Money amount : day) {
                balance = balance.plus(amount);
            }
        }
        return balance;
    }
}
