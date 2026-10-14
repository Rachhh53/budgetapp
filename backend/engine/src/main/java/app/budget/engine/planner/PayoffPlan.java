package app.budget.engine.planner;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.loans.Amortization;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Paying debts down, with or without extra money each month (spec §5.9): when each one is gone
 * and what it costs in interest on the way.
 *
 * <p>Every debt is followed a month at a time the way a loan is ({@link Amortization}): a month's
 * interest is added to what is owed, then the payment comes off. Extra money goes to one debt at
 * a time, chosen by the {@link Order}; when a debt is gone, what was being paid into it goes to
 * the next one as well.
 */
public final class PayoffPlan {

    private PayoffPlan() {}

    /** Which debt the extra money goes to first. */
    public enum Order {
        /** The one costing the most in interest: the least paid overall. */
        HIGHEST_RATE,
        /** The one that is gone soonest: a payment freed up early. */
        SMALLEST_BALANCE
    }

    /**
     * One debt as it stands today.
     *
     * @param id            the caller's, to find its outcome again
     * @param balance       what is owed
     * @param payment       what goes to the debt each month, escrow not included
     * @param firstPayment  the next day a payment is made; there is one a month after it, on that day of the month
     * @param rateChangesOn the day the rate changes (a promotional rate ending); {@code null} when it does not
     * @param rateAfter     the rate from that day on; {@code null} with {@code rateChangesOn}
     */
    public record Debt(
            long id, Money balance, BasisPoints rate, Money payment, LocalDate firstPayment, LocalDate rateChangesOn, BasisPoints rateAfter) {

        public Debt {
            if (rate.value() < 0 || (rateAfter != null && rateAfter.value() < 0)) {
                throw new IllegalArgumentException("A debt's rate cannot be negative");
            }
            if (payment.isNegative()) {
                throw new IllegalArgumentException("A payment cannot be negative");
            }
            if ((rateChangesOn == null) != (rateAfter == null)) {
                throw new IllegalArgumentException("A change of rate needs both its day and its rate");
            }
        }

        /** A debt whose rate stays as it is. */
        public Debt(long id, Money balance, BasisPoints rate, Money payment, LocalDate firstPayment) {
            this(id, balance, rate, payment, firstPayment, null, null);
        }

        BasisPoints rateOn(LocalDate day) {
            return rateChangesOn != null && !day.isBefore(rateChangesOn) ? rateAfter : rate;
        }

        /** The day its payment falls on in {@code month}; {@code null} for a month before its first payment. */
        LocalDate paymentDay(YearMonth month) {
            LocalDate day = month.atDay(Math.min(firstPayment.getDayOfMonth(), month.lengthOfMonth()));
            return day.isBefore(firstPayment) ? null : day;
        }
    }

    /**
     * What became of one debt.
     *
     * @param payoffDate the day of its last payment; {@code null} when nothing was owed, or it is never paid off
     * @param interest   the interest added to it until then
     * @param paysOff    false when what is paid never gets ahead of the interest
     */
    public record Outcome(long id, LocalDate payoffDate, Money interest, boolean paysOff) {}

    /** @param debts one outcome per debt, in the order the debts were given */
    public record Result(List<Outcome> debts) {

        public Outcome of(long id) {
            return debts.stream().filter(outcome -> outcome.id() == id).findFirst().orElseThrow();
        }

        /** True when every debt is paid off in the end. */
        public boolean paysOff() {
            return debts.stream().allMatch(Outcome::paysOff);
        }

        public Money totalInterest() {
            return debts.stream().map(Outcome::interest).reduce(Money.ZERO, Money::plus);
        }

        /** The day the last debt is gone; {@code null} when one never is, or nothing was owed. */
        public LocalDate debtFreeDate() {
            if (!paysOff()) {
                return null;
            }
            return debts.stream().map(Outcome::payoffDate).filter(day -> day != null).max(LocalDate::compareTo).orElse(null);
        }
    }

    /** Each debt on its own payment, as things stand: nothing extra, and nothing passed on when one is gone. */
    public static Result asTheyAre(List<Debt> debts) {
        return follow(debts, Money.ZERO, Order.HIGHEST_RATE, false);
    }

    /**
     * The debts with {@code extra} added every month, to one debt at a time in {@code order}. When a
     * debt is gone, its payment joins the extra from the next month on.
     */
    public static Result withExtra(List<Debt> debts, Money extra, Order order) {
        if (extra.isNegative()) {
            throw new IllegalArgumentException("The extra amount cannot be negative");
        }
        return follow(debts, extra, order, true);
    }

    private static Result follow(List<Debt> debts, Money extra, Order order, boolean passesOn) {
        int count = debts.size();
        Money[] owed = new Money[count];
        Money[] interest = new Money[count];
        LocalDate[] paidOff = new LocalDate[count];
        YearMonth month = null;
        for (int i = 0; i < count; i++) {
            Debt debt = debts.get(i);
            owed[i] = debt.balance().isNegative() ? Money.ZERO : debt.balance();
            interest[i] = Money.ZERO;
            YearMonth first = YearMonth.from(debt.firstPayment());
            if (month == null || first.isBefore(month)) {
                month = first;
            }
        }

        // What used to be paid into debts that are gone: it goes wherever the extra goes.
        Money freed = Money.ZERO;
        for (int step = 0; step < Amortization.MAX_PAYMENTS && anyOwed(owed); step++, month = month.plusMonths(1)) {
            Money spare = passesOn ? extra.plus(freed) : Money.ZERO;
            LocalDate[] days = new LocalDate[count];
            for (int i = 0; i < count; i++) {
                Debt debt = debts.get(i);
                days[i] = debt.paymentDay(month);
                if (owed[i].isZero() || days[i] == null) {
                    continue;
                }
                Money charged = Amortization.monthlyInterest(owed[i], debt.rateOn(days[i]));
                interest[i] = interest[i].plus(charged);
                owed[i] = owed[i].plus(charged);
                Money paid = debt.payment().compareTo(owed[i]) > 0 ? owed[i] : debt.payment();
                owed[i] = owed[i].minus(paid);
                if (owed[i].isZero()) {
                    paidOff[i] = days[i];
                    // The last payment is only as large as it needs to be: the rest of it is spare this month already.
                    if (passesOn) {
                        spare = spare.plus(debt.payment().minus(paid));
                        freed = freed.plus(debt.payment());
                    }
                }
            }
            while (!spare.isZero()) {
                int target = next(debts, owed, days, order);
                if (target < 0) {
                    break;
                }
                Money paid = spare.compareTo(owed[target]) > 0 ? owed[target] : spare;
                owed[target] = owed[target].minus(paid);
                spare = spare.minus(paid);
                if (owed[target].isZero()) {
                    paidOff[target] = days[target];
                    freed = freed.plus(debts.get(target).payment());
                }
            }
        }

        List<Outcome> outcomes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            outcomes.add(new Outcome(debts.get(i).id(), paidOff[i], interest[i], owed[i].isZero()));
        }
        return new Result(outcomes);
    }

    private static boolean anyOwed(Money[] owed) {
        for (Money amount : owed) {
            if (!amount.isZero()) {
                return true;
            }
        }
        return false;
    }

    /** The debt the spare money goes to this month, among those with a payment in it; -1 when there is none. */
    private static int next(List<Debt> debts, Money[] owed, LocalDate[] days, Order order) {
        int best = -1;
        for (int i = 0; i < owed.length; i++) {
            if (owed[i].isZero() || days[i] == null) {
                continue;
            }
            if (best < 0 || comesBefore(debts.get(i).rateOn(days[i]), owed[i], debts.get(best).rateOn(days[best]), owed[best], order)) {
                best = i;
            }
        }
        return best;
    }

    /** Ties go to the other measure, so the choice never depends on the order the debts were listed in. */
    private static boolean comesBefore(BasisPoints rate, Money owed, BasisPoints otherRate, Money otherOwed, Order order) {
        int byRate = Integer.compare(otherRate.value(), rate.value());
        int byBalance = owed.compareTo(otherOwed);
        return order == Order.HIGHEST_RATE
                ? byRate < 0 || (byRate == 0 && byBalance < 0)
                : byBalance < 0 || (byBalance == 0 && byRate < 0);
    }
}
