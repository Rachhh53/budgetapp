package app.budget.engine.cards;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.interest.InterestTerms;
import app.budget.engine.projection.BalanceAnchor;
import app.budget.engine.projection.Movement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Follows one credit card day by day to work out the amounts that depend on its balance
 * (spec §5.6): the month's estimated spending, estimated interest, deferred interest, each
 * statement, and what a payment that follows the card comes to.
 *
 * <p>Charges and typed payments are the caller's; they are given here as plain movements.
 * What comes back are the extra movements to add to them, so the balance itself is still
 * projected in one place ({@link app.budget.engine.projection.BalanceProjector}).
 */
public final class CardProjector {

    private CardProjector() {}

    /**
     * How a card bills and is paid.
     *
     * @param mode            how a payment that follows the card is sized
     * @param monthlyEstimate what a statement month's spending is expected to come to, for a card
     *                        whose charges are not entered one by one; {@code null} otherwise
     * @param apr             the regular APR over time; {@code null} when none was entered
     * @param entered         the statements the user entered, by closing date
     * @param minimum         how a statement's minimum is estimated while the real one is not entered
     */
    public record Terms(
            int closeDay,
            int dueDay,
            CardPayment.Mode mode,
            Money monthlyEstimate,
            InterestTerms apr,
            List<Promo> promos,
            Map<LocalDate, Statement> entered,
            CardPayment.MinimumRule minimum) {

        /** A card whose minimum is estimated the usual way. */
        public Terms(
                int closeDay, int dueDay, CardPayment.Mode mode, Money monthlyEstimate, InterestTerms apr, List<Promo> promos,
                Map<LocalDate, Statement> entered) {
            this(closeDay, dueDay, mode, monthlyEstimate, apr, promos, entered, CardPayment.MinimumRule.USUAL);
        }

        BasisPoints aprOn(LocalDate date) {
            return apr == null ? BasisPoints.of(0) : apr.rateOn(date).orElse(BasisPoints.of(0));
        }
    }

    /**
     * A statement: what was owed when it closed.
     *
     * @param interest the interest estimated for its month; zero on a statement the user entered,
     *                 whose balance already holds the real figure
     * @param entered  true when these are the user's numbers rather than an estimate
     */
    public record Statement(LocalDate closeDate, Money balance, Money minimumDue, LocalDate dueDate, Money interest, boolean entered) {}

    /**
     * A payment whose amount follows the card. {@code key} is the caller's, to find it again.
     *
     * @param outstanding true when it is paid by hand and not marked paid, as for a {@link Movement}
     */
    public record Payment(LocalDate date, int key, boolean outstanding) {}

    public enum Kind {
        ESTIMATED_SPENDING,
        INTEREST,
        DEFERRED_INTEREST,
        PAYMENT
    }

    /**
     * Something worked out from the balance.
     *
     * @param amount what it does to the amount owed: positive for a charge, negative for a payment
     * @param key    the {@link Payment#key()} it answers; {@code -1} for anything else
     */
    public record Derived(LocalDate date, Kind kind, Money amount, int key) {}

    /**
     * @param statements every statement closed within the dates followed, earliest first
     * @param exact      the {@link Payment#key()} of each payment sized from a statement the user
     *                   entered, so its amount is the real one rather than an estimate
     * @param pays       by {@link Payment#key()}, the closing date of the statement a payment was
     *                   sized from. Only payments that pay a statement (its balance or its
     *                   minimum) are here, and only once a statement stands behind them: one sized
     *                   from the balance the card starts with has no statement to name
     */
    public record Result(List<Derived> derived, List<Statement> statements, Set<Integer> exact, Map<Integer, LocalDate> pays) {

        public static final Result NONE = new Result(List.of(), List.of(), Set.of(), Map.of());
    }

    /**
     * @param anchors   balances the user entered (what was owed at the start of that day), in any order
     * @param fixed     charges (positive) and typed payments (negative), in any order
     * @param following the payments whose amount is to be worked out
     * @param through   the last day to follow
     */
    public static Result project(
            List<BalanceAnchor> anchors, List<Movement> fixed, List<Payment> following, Terms terms, LocalDate through) {
        if (anchors.isEmpty()) {
            return Result.NONE;
        }
        TreeMap<LocalDate, Money> entered = new TreeMap<>();
        for (BalanceAnchor anchor : anchors) {
            entered.put(anchor.date(), anchor.balance());
        }
        LocalDate start = entered.firstKey();
        if (start.isAfter(through)) {
            return Result.NONE;
        }
        // What is still outstanding when the newest balance was entered happens on that day, as in the projection.
        LocalDate newest = entered.lastKey();
        TreeMap<LocalDate, List<Money>> movements = new TreeMap<>();
        for (Movement movement : fixed) {
            movements.computeIfAbsent(movement.countsOn(newest), day -> new ArrayList<>()).add(movement.amount());
        }
        TreeMap<LocalDate, List<Payment>> payments = new TreeMap<>();
        for (Payment payment : following) {
            LocalDate countsOn = new Movement(payment.date(), Money.ZERO, payment.outstanding()).countsOn(newest);
            payments.computeIfAbsent(countsOn, day -> new ArrayList<>()).add(payment);
        }
        TreeSet<LocalDate> closes = new TreeSet<>(StatementCycle.closeDates(terms.closeDay(), start, through));

        // Only the days something happens on need visiting.
        TreeSet<LocalDate> days = new TreeSet<>(entered.keySet());
        days.addAll(movements.keySet());
        days.addAll(payments.keySet());
        days.addAll(closes);
        for (Promo promo : terms.promos()) {
            if (promo.deferred()) {
                days.add(promo.end());
            }
        }

        List<Derived> derived = new ArrayList<>();
        List<Statement> statements = new ArrayList<>();
        Money owed = entered.firstEntry().getValue();
        // Until the first statement closes, the balance the card starts with stands in for one:
        // it is what a "statement balance" payment pays, and what interest is carried on.
        Money statementBalance = owed;
        Money minimumDue = CardPayment.estimatedMinimum(owed, Money.ZERO, terms.minimum());
        Money paidSinceClose = Money.ZERO;
        boolean statementEntered = false;
        // The closing date of the statement those figures come from; none while the starting balance stands in.
        LocalDate statementClosed = null;
        // Unless the user entered the statement that closed last before these dates: then that is
        // the one still to be paid, whatever was charged since. An older one says nothing about now.
        Statement before = terms.entered().get(StatementCycle.lastCloseOnOrBefore(terms.closeDay(), start.minusDays(1)));
        if (before != null && before.closeDate().isBefore(start)) {
            statementBalance = before.balance();
            minimumDue = before.minimumDue() != null ? before.minimumDue() : CardPayment.estimatedMinimum(before.balance(), Money.ZERO, terms.minimum());
            statementEntered = true;
            statementClosed = before.closeDate();
            // Payments made since it closed, before the balance these dates start from, already count toward it.
            for (Map.Entry<LocalDate, List<Money>> earlier : movements.subMap(before.closeDate(), false, start, false).entrySet()) {
                for (Money amount : earlier.getValue()) {
                    if (amount.isNegative()) {
                        paidSinceClose = paidSinceClose.minus(amount);
                    }
                }
            }
        }
        Set<Integer> exact = new HashSet<>();
        Map<Integer, LocalDate> pays = new HashMap<>();
        boolean paysAStatement = terms.mode() == CardPayment.Mode.STATEMENT_BALANCE || terms.mode() == CardPayment.Mode.MINIMUM;

        for (LocalDate day : days.subSet(start, true, through, true)) {
            Money anchored = entered.get(day);
            if (anchored != null) {
                owed = anchored;
            }
            for (Money amount : movements.getOrDefault(day, List.of())) {
                owed = owed.plus(amount);
                if (amount.isNegative()) {
                    paidSinceClose = paidSinceClose.minus(amount);
                }
            }
            for (Payment payment : payments.getOrDefault(day, List.of())) {
                Money paid = CardPayment.amount(terms.mode(), owed, statementBalance, minimumDue, paidSinceClose, Money.ZERO);
                owed = owed.minus(paid);
                paidSinceClose = paidSinceClose.plus(paid);
                derived.add(new Derived(day, Kind.PAYMENT, paid.negate(), payment.key()));
                // The whole balance moves with every charge still to come; a statement that was entered does not.
                if (statementEntered && paysAStatement) {
                    exact.add(payment.key());
                }
                if (statementClosed != null && paysAStatement) {
                    pays.put(payment.key(), statementClosed);
                }
            }
            BasisPoints apr = terms.aprOn(day);
            for (Promo promo : terms.promos()) {
                if (promo.deferred() && promo.end().equals(day)) {
                    Money charge = CardInterest.deferredCharge(
                            promo, CardInterest.remaining(promo, owed, terms.promos(), day, apr), apr);
                    if (!charge.isZero()) {
                        owed = owed.plus(charge);
                        derived.add(new Derived(day, Kind.DEFERRED_INTEREST, charge, -1));
                    }
                }
            }
            if (closes.contains(day)) {
                Statement real = terms.entered().get(day);
                if (real != null) {
                    // The real statement already holds the month's spending and interest.
                    statements.add(real);
                    statementBalance = real.balance();
                    minimumDue = real.minimumDue() != null ? real.minimumDue() : CardPayment.estimatedMinimum(real.balance(), Money.ZERO, terms.minimum());
                    statementEntered = true;
                    statementClosed = real.closeDate();
                } else {
                    statementEntered = false;
                    statementClosed = day;
                    if (terms.monthlyEstimate() != null && !terms.monthlyEstimate().isZero()) {
                        owed = owed.plus(terms.monthlyEstimate());
                        derived.add(new Derived(day, Kind.ESTIMATED_SPENDING, terms.monthlyEstimate(), -1));
                    }
                    // Interest is only charged on what was left unpaid from the statement before.
                    Money carried = statementBalance.minus(paidSinceClose);
                    if (carried.compareTo(owed) > 0) {
                        carried = owed;
                    }
                    Money interest = carried.isNegative() ? Money.ZERO : CardInterest.forMonth(carried, terms.promos(), day, apr);
                    if (!interest.isZero()) {
                        owed = owed.plus(interest);
                        derived.add(new Derived(day, Kind.INTEREST, interest, -1));
                    }
                    statementBalance = owed.isNegative() ? Money.ZERO : owed;
                    minimumDue = CardPayment.estimatedMinimum(statementBalance, interest, terms.minimum());
                    statements.add(new Statement(
                            day, statementBalance, minimumDue, StatementCycle.dueDate(day, terms.dueDay()), interest, false));
                }
                paidSinceClose = Money.ZERO;
            }
        }
        return new Result(derived, statements, exact, pays);
    }
}
