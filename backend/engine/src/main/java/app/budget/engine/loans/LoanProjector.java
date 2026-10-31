package app.budget.engine.loans;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.interest.InterestTerms;
import app.budget.engine.interest.SavingsInterest;
import app.budget.engine.projection.BalanceAnchor;
import app.budget.engine.projection.Movement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Follows one loan day by day to work out what its payments really do to it (spec §5.7):
 * a month's interest is added on the payment day, and the escrow part of a regular payment
 * never reaches the loan.
 *
 * <p>Payments are the caller's; what comes back are the extra movements to add to them, so
 * the balance itself is still projected in one place.
 */
public final class LoanProjector {

    private LoanProjector() {}

    /**
     * @param paymentDay    day of the month a month's interest is added, 1–31 (31 = the last day)
     * @param escrowMonthly the part of a month's regular payments that goes to insurance and taxes
     * @param rate          the loan's rate over time; {@code null} when none was entered
     * @param assumedPayment what a month's payment takes off the loan (the payment less escrow) in a
     *                      month nothing was scheduled for it yet; zero to assume nothing
     */
    public record Terms(int paymentDay, Money escrowMonthly, InterestTerms rate, Money assumedPayment) {

        /** Terms that assume no payment was made unless one is given. */
        public Terms(int paymentDay, Money escrowMonthly, InterestTerms rate) {
            this(paymentDay, escrowMonthly, rate, Money.ZERO);
        }
    }

    public enum Kind {
        /** A month's interest, added to what is owed. */
        INTEREST,
        /** The part of a regular payment that went to escrow, so it did not lower the loan. */
        ESCROW,
        /**
         * A month's payment taken as made, from before any payment was scheduled: a loan entered
         * as it stood years ago is paid down to today, not left to grow.
         */
        ASSUMED_PAYMENT
    }

    /** @param amount always positive: what it adds to the amount owed, or for an assumed payment what it takes off */
    public record Derived(LocalDate date, Kind kind, Money amount) {}

    /**
     * @param anchors balances the user entered (what was owed as that day began, or ended), in any order
     * @param regular the scheduled payments, as movements (negative); escrow comes out of these,
     *                up to a month's escrow in each calendar month
     * @param other   anything else that moves the loan: an extra payment goes wholly to the loan
     * @param through the last day to follow
     */
    public static List<Derived> project(
            List<BalanceAnchor> anchors, List<Movement> regular, List<Movement> other, Terms terms, LocalDate through) {
        if (anchors.isEmpty()) {
            return List.of();
        }
        // A balance a day ended with is what the next day began with, which is how the walk below reads every balance.
        // Where both were entered for that moment, the later day's stands.
        TreeMap<LocalDate, Money> entered = new TreeMap<>();
        anchors.stream()
                .sorted(Comparator.comparing(BalanceAnchor::settlesFrom).thenComparing(anchor -> !anchor.endOfDay()))
                .forEach(anchor -> entered.put(anchor.settlesFrom(), anchor.balance()));
        LocalDate start = entered.firstKey();
        if (start.isAfter(through)) {
            return List.of();
        }
        // What is still outstanding when the newest balance was entered happens on that day, as in the projection.
        LocalDate newest = entered.lastKey();
        TreeMap<LocalDate, List<Money>> scheduled = byDay(regular, newest);
        TreeMap<LocalDate, List<Money>> others = byDay(other, newest);
        YearMonth firstScheduled = scheduled.isEmpty() ? null : YearMonth.from(scheduled.firstKey());
        TreeSet<LocalDate> interestDays = new TreeSet<>(SavingsInterest.creditDates(terms.paymentDay(), start, through));

        TreeSet<LocalDate> days = new TreeSet<>(entered.keySet());
        days.addAll(scheduled.keySet());
        days.addAll(others.keySet());
        days.addAll(interestDays);

        List<Derived> derived = new ArrayList<>();
        Map<YearMonth, Money> escrowTaken = new HashMap<>();
        Money owed = entered.firstEntry().getValue();
        for (LocalDate day : days.subSet(start, true, through, true)) {
            Money anchored = entered.get(day);
            if (anchored != null) {
                owed = anchored;
            }
            // A balance entered for the payment day is taken to hold that month's interest already.
            if (interestDays.contains(day) && anchored == null && terms.rate() != null) {
                BasisPoints rate = terms.rate().rateOn(day).orElse(null);
                // An amount typed over the estimate simply becomes the month's interest, zero included.
                Money typed = terms.rate().typedOn(day).orElse(null);
                Money interest = typed != null ? typed : rate == null ? Money.ZERO : Amortization.monthlyInterest(owed, rate);
                if (!interest.isZero() || typed != null) {
                    owed = owed.plus(interest);
                    derived.add(new Derived(day, Kind.INTEREST, interest));
                }
            }
            // Until the first scheduled payment's month, the payment is taken as made on its day. A
            // balance entered that day already says what was owed after it.
            if (interestDays.contains(day) && anchored == null && !terms.assumedPayment().isZero() && !terms.assumedPayment().isNegative()
                    && (firstScheduled == null || YearMonth.from(day).isBefore(firstScheduled)) && !owed.isNegative() && !owed.isZero()) {
                Money paid = terms.assumedPayment().compareTo(owed) > 0 ? owed : terms.assumedPayment();
                owed = owed.minus(paid);
                derived.add(new Derived(day, Kind.ASSUMED_PAYMENT, paid));
            }
            for (Money amount : scheduled.getOrDefault(day, List.of())) {
                owed = owed.plus(amount);
                if (!amount.isNegative()) {
                    continue;
                }
                YearMonth month = YearMonth.from(day);
                Money taken = escrowTaken.getOrDefault(month, Money.ZERO);
                Money wanted = terms.escrowMonthly().minus(taken);
                Money paid = amount.negate();
                Money escrow = wanted.compareTo(paid) > 0 ? paid : wanted;
                if (!escrow.isNegative() && !escrow.isZero()) {
                    owed = owed.plus(escrow);
                    escrowTaken.put(month, taken.plus(escrow));
                    derived.add(new Derived(day, Kind.ESCROW, escrow));
                }
            }
            for (Money amount : others.getOrDefault(day, List.of())) {
                owed = owed.plus(amount);
            }
        }
        return derived;
    }

    private static TreeMap<LocalDate, List<Money>> byDay(List<Movement> movements, LocalDate newestEntered) {
        TreeMap<LocalDate, List<Money>> byDay = new TreeMap<>();
        for (Movement movement : movements) {
            byDay.computeIfAbsent(movement.countsOn(newestEntered), day -> new ArrayList<>()).add(movement.amount());
        }
        return byDay;
    }
}
