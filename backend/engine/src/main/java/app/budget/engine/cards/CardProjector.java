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
import java.util.Optional;
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
     * @param monthlyEstimate what a statement month's spending is expected to come to in all; charges entered
     *                        for the month count toward it, and only the rest is added at the close;
     *                        {@code null} when charges are only entered one by one
     * @param apr             the regular APR over time; {@code null} when none was entered
     * @param entered         the statements the user entered, by closing date
     * @param minimum         how a statement's minimum is estimated while the real one is not entered
     * @param rewardsRate     what the card gives back on what is charged; {@code null} for no rewards
     * @param rewardsTyped    a statement's rewards as they really came, by the close date they are added on
     * @param rewardsBuildUp  how rewards that build up are used; {@code null} when they come off each statement
     */
    public record Terms(
            int closeDay,
            int dueDay,
            CardPayment.Mode mode,
            Money monthlyEstimate,
            InterestTerms apr,
            List<Promo> promos,
            Map<LocalDate, Statement> entered,
            CardPayment.MinimumRule minimum,
            BasisPoints rewardsRate,
            Map<LocalDate, Money> rewardsTyped,
            BuildUp rewardsBuildUp) {

        /** A card whose rewards, if any, come off each statement. */
        public Terms(
                int closeDay, int dueDay, CardPayment.Mode mode, Money monthlyEstimate, InterestTerms apr, List<Promo> promos,
                Map<LocalDate, Statement> entered, CardPayment.MinimumRule minimum, BasisPoints rewardsRate, Map<LocalDate, Money> rewardsTyped) {
            this(closeDay, dueDay, mode, monthlyEstimate, apr, promos, entered, minimum, rewardsRate, rewardsTyped, null);
        }

        /** A card with no rewards. */
        public Terms(
                int closeDay, int dueDay, CardPayment.Mode mode, Money monthlyEstimate, InterestTerms apr, List<Promo> promos,
                Map<LocalDate, Statement> entered, CardPayment.MinimumRule minimum) {
            this(closeDay, dueDay, mode, monthlyEstimate, apr, promos, entered, minimum, null, Map.of());
        }

        /** A card whose minimum is estimated the usual way, with no rewards. */
        public Terms(
                int closeDay, int dueDay, CardPayment.Mode mode, Money monthlyEstimate, InterestTerms apr, List<Promo> promos,
                Map<LocalDate, Statement> entered) {
            this(closeDay, dueDay, mode, monthlyEstimate, apr, promos, entered, CardPayment.MinimumRule.USUAL);
        }

        /** The same card, giving back {@code rate} on what is charged, with the months typed over. */
        public Terms withRewards(BasisPoints rate, Map<LocalDate, Money> typed) {
            return new Terms(closeDay, dueDay, mode, monthlyEstimate, apr, promos, entered, minimum, rate, Map.copyOf(typed), rewardsBuildUp);
        }

        /** The same card, its rewards building up to be used as {@code buildUp} says; {@code null} to take them off each statement. */
        public Terms withRewardsBuildUp(BuildUp buildUp) {
            return new Terms(closeDay, dueDay, mode, monthlyEstimate, apr, promos, entered, minimum, rewardsRate, rewardsTyped, buildUp);
        }

        /**
         * The rewards a statement closing on {@code close} brings: the amount typed over it, or the
         * rate on what was charged since the last close. Empty when the card has no rewards and
         * none were typed.
         */
        Optional<Money> rewardsOn(LocalDate close, Money charged) {
            Money typed = rewardsTyped.get(close);
            if (typed != null) {
                return Optional.of(typed);
            }
            return rewardsRate == null ? Optional.empty() : Optional.of(charged.times(rewardsRate.toFraction()));
        }

        BasisPoints aprOn(LocalDate date) {
            return apr == null ? BasisPoints.of(0) : apr.rateOn(date).orElse(BasisPoints.of(0));
        }
    }

    /**
     * Rewards left to build up instead of coming off each statement (Rachel, 2026-10-20: "you should also be able to
     * select don't apply and they just accumulate and budget tracks that. I want to say when they apply in some cases").
     * They go on as a statement credit by a rule, by a redemption, or both.
     *
     * @param yearlyMonth  every year, at the statement that closes in this month (1–12), all built up goes on; {@code null} for no such rule
     * @param threshold    at a statement close where at least this has built up, all of it goes on; {@code null} for no such rule
     * @param startBalance what had built up on {@code startDate}, as the card says; earnings before that day are in it
     * @param startDate    the day {@code startBalance} is from; {@code null} to start from nothing
     * @param redemptions  credits asked for on a day, in any order
     */
    public record BuildUp(Integer yearlyMonth, Money threshold, Money startBalance, LocalDate startDate, List<Redemption> redemptions) {
        public BuildUp {
            redemptions = List.copyOf(redemptions);
            startBalance = startBalance == null ? Money.ZERO : startBalance;
        }
    }

    /**
     * A statement credit asked for on a day. {@code key} is the caller's, to find it again.
     *
     * @param amount how much; {@code null} for everything built up by then. Never more than has built up.
     */
    public record Redemption(LocalDate date, Money amount, int key) {}

    /**
     * Built-up rewards on a day something happened to them.
     *
     * @param earned   what a statement close added
     * @param redeemed what went on as a credit
     * @param builtUp  what is left built up afterwards
     */
    public record RewardsPoint(LocalDate date, Money earned, Money redeemed, Money builtUp) {}

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
        PAYMENT,
        /** Cash back taken off the balance at a statement close: a negative amount. */
        REWARDS,
        /** Built-up rewards going on as a statement credit, by a rule or a redemption: a negative amount. */
        REWARDS_REDEEMED
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
     * @param rewards    for rewards that build up, every day something happened to them, earliest first; empty otherwise
     */
    public record Result(
            List<Derived> derived, List<Statement> statements, Set<Integer> exact, Map<Integer, LocalDate> pays, List<RewardsPoint> rewards) {

        public static final Result NONE = new Result(List.of(), List.of(), Set.of(), Map.of(), List.of());
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
        BuildUp buildUp = terms.rewardsBuildUp();
        TreeMap<LocalDate, List<Redemption>> redemptions = new TreeMap<>();
        // What has built up: the starting amount from its day, or nothing.
        Money builtUp = Money.ZERO;
        List<RewardsPoint> rewardsPoints = new ArrayList<>();
        if (buildUp != null) {
            for (Redemption redemption : buildUp.redemptions()) {
                redemptions.computeIfAbsent(redemption.date(), day -> new ArrayList<>()).add(redemption);
            }
            days.addAll(redemptions.keySet());
            if (buildUp.startDate() != null) {
                days.add(buildUp.startDate());
            }
            if (buildUp.startDate() == null || !buildUp.startDate().isAfter(start)) {
                builtUp = buildUp.startBalance();
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
        // What was charged since the last close, which the rewards at the next one are earned on.
        Money chargedSinceClose = Money.ZERO;
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
                } else {
                    chargedSinceClose = chargedSinceClose.plus(amount);
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
            if (buildUp != null) {
                if (day.equals(buildUp.startDate()) && day.isAfter(start)) {
                    // The card's own figure on that day stands in for everything earned before it.
                    builtUp = buildUp.startBalance();
                    rewardsPoints.add(new RewardsPoint(day, Money.ZERO, Money.ZERO, builtUp));
                }
                for (Redemption redemption : redemptions.getOrDefault(day, List.of())) {
                    Money asked = redemption.amount() == null ? builtUp : redemption.amount();
                    Money credit = asked.compareTo(builtUp) > 0 ? builtUp : asked;
                    if (credit.isNegative() || credit.isZero()) {
                        continue;
                    }
                    // A credit counts toward what the statement asks to be paid, as a payment would.
                    builtUp = builtUp.minus(credit);
                    owed = owed.minus(credit);
                    paidSinceClose = paidSinceClose.plus(credit);
                    derived.add(new Derived(day, Kind.REWARDS_REDEEMED, credit.negate(), redemption.key()));
                    rewardsPoints.add(new RewardsPoint(day, Money.ZERO, credit, builtUp));
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
                    // The estimate is the month's whole spending: charges entered for the month count toward it,
                    // and only the part they don't cover is added, so the two never stack (Rachel, 2026-10-19:
                    // "can I both estimate and put real charges … there and not have them compound?").
                    if (terms.monthlyEstimate() != null && terms.monthlyEstimate().compareTo(chargedSinceClose) > 0) {
                        Money rest = terms.monthlyEstimate().minus(chargedSinceClose);
                        owed = owed.plus(rest);
                        chargedSinceClose = chargedSinceClose.plus(rest);
                        derived.add(new Derived(day, Kind.ESTIMATED_SPENDING, rest, -1));
                    }
                    // Interest is only charged on what was left unpaid from the statement before.
                    Money carried = statementBalance.minus(paidSinceClose);
                    if (carried.compareTo(owed) > 0) {
                        carried = owed;
                    }
                    // An amount typed over the estimate simply becomes the month's interest, zero included.
                    Optional<Money> typed = terms.apr() == null ? Optional.empty() : terms.apr().typedOn(day);
                    Money interest = typed.isPresent() ? typed.get()
                            : carried.isNegative() ? Money.ZERO : CardInterest.forMonth(carried, terms.promos(), day, apr);
                    if (!interest.isZero() || typed.isPresent()) {
                        owed = owed.plus(interest);
                        derived.add(new Derived(day, Kind.INTEREST, interest, -1));
                    }
                    // Rewards come off at the close, after interest, so the statement and its payment are smaller (§5.6).
                    // A typed zero still shows, so it can be put back.
                    Optional<Money> rewards = terms.rewardsOn(day, chargedSinceClose);
                    if (buildUp != null) {
                        // Earned onto what has built up, unless the card's own figure from a later day already holds it.
                        Money earned = rewards.orElse(Money.ZERO);
                        if (buildUp.startDate() != null && day.isBefore(buildUp.startDate())) {
                            earned = Money.ZERO;
                        }
                        builtUp = builtUp.plus(earned);
                        Money credit = ruleCredit(buildUp, day, builtUp);
                        if (!credit.isZero()) {
                            builtUp = builtUp.minus(credit);
                            owed = owed.minus(credit);
                            derived.add(new Derived(day, Kind.REWARDS_REDEEMED, credit.negate(), -1));
                        }
                        if (!earned.isZero() || !credit.isZero()) {
                            rewardsPoints.add(new RewardsPoint(day, earned, credit, builtUp));
                        }
                    } else if (rewards.isPresent() && (!rewards.get().isZero() || terms.rewardsTyped().containsKey(day))) {
                        owed = owed.minus(rewards.get());
                        derived.add(new Derived(day, Kind.REWARDS, rewards.get().negate(), -1));
                    }
                    statementBalance = owed.isNegative() ? Money.ZERO : owed;
                    minimumDue = CardPayment.estimatedMinimum(statementBalance, interest, terms.minimum());
                    statements.add(new Statement(
                            day, statementBalance, minimumDue, StatementCycle.dueDate(day, terms.dueDay()), interest, false));
                }
                paidSinceClose = Money.ZERO;
                chargedSinceClose = Money.ZERO;
            }
        }
        return new Result(derived, statements, exact, pays, rewardsPoints);
    }

    /** At a statement close, what a rule puts on: everything built up, in the month chosen or once it reaches the amount; else zero. */
    private static Money ruleCredit(BuildUp buildUp, LocalDate close, Money builtUp) {
        if (builtUp.isNegative() || builtUp.isZero()) {
            return Money.ZERO;
        }
        boolean yearly = buildUp.yearlyMonth() != null && close.getMonthValue() == buildUp.yearlyMonth();
        boolean reached = buildUp.threshold() != null && builtUp.compareTo(buildUp.threshold()) >= 0;
        return yearly || reached ? builtUp : Money.ZERO;
    }
}
