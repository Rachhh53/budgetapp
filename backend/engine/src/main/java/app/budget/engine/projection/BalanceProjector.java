package app.budget.engine.projection;

import app.budget.engine.Money;
import app.budget.engine.schedule.PayPeriod;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Projects one account's balance through the pay periods (spec §5.3): start from the latest
 * balance the user entered, then apply everything dated from that day on, in date order.
 *
 * <p>The caller decides what moves and on which day (paychecks, bills, budgets, transfers,
 * one-offs; later interest and growth). This class only does the arithmetic, so new kinds
 * of movement need no change here.
 */
public final class BalanceProjector {

    private BalanceProjector() {}

    /**
     * @param anchors   balances the user entered for this account, in any order
     * @param movements everything that moves this account's balance, in any order
     * @param periods   consecutive pay periods, earliest first
     * @return one entry per period from the first one a balance is known for; empty when
     *         no balance has been entered yet
     */
    public static List<PeriodBalance> project(List<BalanceAnchor> anchors, List<Movement> movements, List<PayPeriod> periods) {
        if (anchors.isEmpty()) {
            return List.of();
        }
        TreeMap<LocalDate, Money> entered = new TreeMap<>();
        for (BalanceAnchor anchor : anchors) {
            entered.put(anchor.date(), anchor.balance());
        }
        TreeMap<LocalDate, List<Money>> applied = applied(movements, entered.lastKey());

        List<PeriodBalance> result = new ArrayList<>();
        for (PayPeriod period : periods) {
            Map.Entry<LocalDate, Money> latest = entered.floorEntry(period.end());
            if (latest == null) {
                // Nothing has been entered yet as of this period: there is no number to show.
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
        return result;
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
