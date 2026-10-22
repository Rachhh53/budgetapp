package app.budget.engine.projection;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.interest.InterestTerms;
import app.budget.engine.interest.SavingsInterest;
import app.budget.engine.schedule.PayPeriod;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
                : interest.daily()
                        ? growDaily(interest, entered, applied, periods.getLast().end())
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
                        period, first.getValue(), first.getKey(), false, Money.ZERO, Money.ZERO, Money.ZERO, List.of(),
                        first.getValue(), first.getValue(), first.getKey()));
                continue;
            }
            // Only the first balance ever entered starts a period, since nothing came before it. Any later one,
            // on the first day or part way through, is where the account turned out to be: the period still starts
            // with what the one before ended on, and the difference shows as adjusted (Rachel, 2026-10-21).
            Map.Entry<LocalDate, Money> first = entered.firstEntry();
            boolean startsAtFirst = !first.getKey().isBefore(period.start());
            // What the period before ended on: a balance entered on the first day is not the start, but an adjustment to it.
            Money starting = startsAtFirst ? first.getValue() : rolledForward(entered.lowerEntry(period.start()), period.start(), applied);
            LocalDate startingOn = startsAtFirst ? first.getKey() : period.start();
            Money in = Money.ZERO;
            Money out = Money.ZERO;
            Money adjusted = Money.ZERO;
            List<PeriodBalance.Adjustment> adjustments = new ArrayList<>();
            Money running = starting;
            Money lowest = starting;
            LocalDate lowestOn = startingOn;
            TreeMap<LocalDate, Boolean> days = new TreeMap<>();
            applied.subMap(startingOn, true, period.end(), true).keySet().forEach(day -> days.put(day, true));
            entered.subMap(startingOn, !startsAtFirst, period.end(), true).keySet().forEach(day -> days.put(day, true));
            for (LocalDate day : days.keySet()) {
                // A balance entered is what the day began with.
                Money typed = startsAtFirst && day.equals(startingOn) ? null : entered.get(day);
                if (typed != null) {
                    Money difference = typed.minus(running);
                    adjusted = adjusted.plus(difference);
                    adjustments.add(new PeriodBalance.Adjustment(day, typed, difference));
                    running = typed;
                }
                for (Money amount : applied.getOrDefault(day, List.of())) {
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
                    lowestOn = day;
                }
            }
            result.add(new PeriodBalance(period, starting, startingOn, startsAtFirst, in, out, adjusted, adjustments, running, lowest, lowestOn));
        }
        return new Projection(result, credits);
    }

    /**
     * Works out each month's interest, earliest first, and adds it to {@code applied} as it
     * goes, so that every month is estimated on a balance that includes the months before.
     * A month that falls before a later entered balance is still estimated (a bucket may be
     * owed its share), but that entered balance, not the estimate, is what the account holds.
     *
     * <p>The same goes for a credit day the user entered a balance for: people type in the
     * balance they see once the interest has posted, so the estimate is not added on top of
     * it. It is worked out on what the account was projected to hold before that balance; on
     * the day of the very first balance there is nothing earlier to work it out on.
     */
    private static List<InterestCredit> creditInterest(
            InterestTerms terms, TreeMap<LocalDate, Money> entered, TreeMap<LocalDate, List<Money>> applied, LocalDate through) {
        List<InterestCredit> credits = new ArrayList<>();
        for (LocalDate day : SavingsInterest.creditDates(terms.creditDay(), entered.firstKey(), through)) {
            Optional<BasisPoints> apy = terms.rateOn(day);
            if (apy.isEmpty()) {
                continue;
            }
            boolean inEnteredBalance = entered.containsKey(day);
            Map.Entry<LocalDate, Money> anchor = inEnteredBalance ? entered.lowerEntry(day) : entered.floorEntry(day);
            if (anchor == null) {
                continue;
            }
            Money balance = rolledForward(anchor, day, applied);
            // An amount typed over the estimate simply becomes the amount, zero included, so it stays listed to change back.
            Optional<Money> typed = terms.typedOn(day);
            Money amount = typed.orElseGet(() -> SavingsInterest.forMonth(balance, apy.get()));
            if (amount.isZero() && typed.isEmpty()) {
                continue;
            }
            if (!inEnteredBalance) {
                applied.computeIfAbsent(day, key -> new ArrayList<>()).add(amount);
            }
            credits.add(new InterestCredit(day, amount, balance, apy.get(), inEnteredBalance, typed.isPresent()));
        }
        return credits;
    }

    /**
     * Builds an account up a little every day, earliest first, adding each day's share to
     * {@code applied} as it goes so the next day grows on it too. This is an investment's
     * assumed growth: nothing posts it on a day of the month, so it is spread over every day,
     * and every stretch of time gets its share.
     *
     * <p>A day's share is rarely a whole number of cents. Only whole cents are added; the rest
     * is carried to the next day, so a small balance is not rounded up into growing faster than
     * its rate (nor a large one, a little, slower).
     *
     * <p>A balance the user entered already holds whatever the account really did up to that
     * day, so nothing is added on that day, and what was being carried is dropped.
     *
     * <p>A month whose growth was typed over grows by exactly that: nothing a day, and the whole
     * amount on its last day (Rachel, 2026-10-19: a month is one line in the account's
     * transactions, so a month is what is typed over). A balance entered on that last day
     * already holds it.
     */
    private static List<InterestCredit> growDaily(
            InterestTerms terms, TreeMap<LocalDate, Money> entered, TreeMap<LocalDate, List<Money>> applied, LocalDate through) {
        List<InterestCredit> credits = new ArrayList<>();
        Map<BasisPoints, BigDecimal> dailyRates = new HashMap<>();
        Money running = Money.ZERO;
        BigDecimal carriedCents = BigDecimal.ZERO;
        for (LocalDate day = entered.firstKey(); !day.isAfter(through); day = day.plusDays(1)) {
            Money anchored = entered.get(day);
            Optional<Money> typedMonth = terms.typedFor(YearMonth.from(day));
            if (typedMonth.isPresent()) {
                if (anchored != null) {
                    running = anchored;
                }
                carriedCents = BigDecimal.ZERO;
                if (day.equals(YearMonth.from(day).atEndOfMonth())) {
                    Money amount = typedMonth.get();
                    if (anchored == null) {
                        applied.computeIfAbsent(day, key -> new ArrayList<>()).add(amount);
                    }
                    credits.add(new InterestCredit(
                            day, amount, running, terms.rateOn(day).orElse(BasisPoints.of(0)), anchored != null, true));
                }
            } else if (anchored != null) {
                running = anchored;
                carriedCents = BigDecimal.ZERO;
            } else {
                // Here `running` is what the account holds as the day starts.
                Optional<BasisPoints> rate = terms.rateOn(day);
                if (rate.isPresent() && !running.isNegative() && !running.isZero()) {
                    BigDecimal share = BigDecimal.valueOf(running.cents())
                            .multiply(dailyRates.computeIfAbsent(rate.get(), SavingsInterest::dailyRate))
                            .add(carriedCents);
                    long whole = share.setScale(0, RoundingMode.DOWN).longValueExact();
                    carriedCents = share.subtract(BigDecimal.valueOf(whole));
                    if (whole > 0) {
                        Money amount = Money.ofCents(whole);
                        applied.computeIfAbsent(day, key -> new ArrayList<>()).add(amount);
                        credits.add(new InterestCredit(day, amount, running, rate.get(), false));
                    }
                }
            }
            for (Money amount : applied.getOrDefault(day, List.of())) {
                running = running.plus(amount);
            }
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
                .forEach(movement -> byDay
                        .computeIfAbsent(movement.countsOn(newestEntered), key -> new ArrayList<>())
                        .add(movement.amount()));
        return byDay;
    }

    /** An entered balance with everything applied from its day up to, but not on, {@code date}. */
    private static Money rolledForward(Map.Entry<LocalDate, Money> anchor, LocalDate date, TreeMap<LocalDate, List<Money>> applied) {
        Money balance = anchor.getValue();
        for (List<Money> day : applied.subMap(anchor.getKey(), true, date, false).values()) {
            for (Money amount : day) {
                balance = balance.plus(amount);
            }
        }
        return balance;
    }
}
