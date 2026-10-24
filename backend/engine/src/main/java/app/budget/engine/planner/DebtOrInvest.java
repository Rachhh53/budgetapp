package app.budget.engine.planner;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.interest.SavingsInterest;
import app.budget.engine.loans.Amortization;
import app.budget.engine.planner.PayoffPlan.Debt;
import app.budget.engine.planner.PayoffPlan.Order;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Pay off debt or invest, compared on the same day (spec §5.9; Rachel, 2026-10-23: "if a debt is paid after 8 years and
 * I set the dropdown to 10 then the investment is accruing for longer than the debt so it's not really apples to
 * apples"). Each plan spends the same each month, the debts' own payments and the extra, and is followed to the same
 * last month; what it comes to is what the investment is worth then less whatever is still owed.
 *
 * <p>A plan is a share of the spare money for the debts: every month the extra, and what debts already gone used to
 * take, is split, the debts' part going to one debt at a time in the {@link Order} (as {@link PayoffPlan#withExtra}),
 * the rest into the investment. 0% invests it all and leaves the debts on their own payments; 100% pays the debts off
 * first. Once nothing is owed, everything spare is invested.
 *
 * <p>The investment grows by the twelfth root of its yearly rate, each month's amount going in at the month's end
 * with any employer match it earns, as {@link ContributionGrowth} does; it is followed in {@link BigDecimal} and
 * rounded to cents once, at the end.
 */
public final class DebtOrInvest {

    private static final MathContext PRECISION = MathContext.DECIMAL128;

    /** Thirty years: the longest the comparison runs. */
    public static final int MAX_MONTHS = 360;

    /** 100%, in basis points. */
    private static final int WHOLE_BP = 10_000;

    private DebtOrInvest() {}

    /**
     * Where the money not going to debts goes.
     *
     * @param yearly   its assumed growth or APY
     * @param matchFor what an employer adds for a month's amount; {@link #NO_MATCH} for none
     */
    public record Investment(BasisPoints yearly, UnaryOperator<Money> matchFor) {

        public static final UnaryOperator<Money> NO_MATCH = amount -> Money.ZERO;

        public Investment {
            if (yearly.value() < 0) {
                throw new IllegalArgumentException("Growth cannot be negative");
            }
        }
    }

    /**
     * One way of sharing the money.
     *
     * @param debtShare   of the spare money each month, the part for the debts (0 to 10000)
     * @param freedMoves  true when a paid-off debt's payment stays in the plan (to the other debts or the investment);
     *                    false when it goes back to the budget and only the extra moves on
     */
    public record Plan(BasisPoints debtShare, boolean freedMoves) {

        public Plan {
            if (debtShare.value() < 0 || debtShare.value() > WHOLE_BP) {
                throw new IllegalArgumentException("The share for debts is between 0% and 100%");
            }
        }
    }

    /**
     * One month's end.
     *
     * @param invested what the investment is worth
     * @param owed     what every debt together still owes
     */
    public record Point(YearMonth month, Money invested, Money owed) {

        /** What the plan comes to so far: the investment less the debts. */
        public Money net() {
            return invested.minus(owed);
        }
    }

    /**
     * What a plan comes to at the end.
     *
     * @param putIn        what went into the investment from the budget
     * @param match        what an employer added to it
     * @param growth       what its growth added
     * @param interest     the interest the debts were charged on the way
     * @param debtFreeDate the day the last debt is gone; {@code null} when one is still owed at the end, or none was
     * @param months       the plan month by month, the last being the end
     */
    public record Outcome(Money putIn, Money match, Money growth, Money interest, LocalDate debtFreeDate, List<Point> months) {

        public Point end() {
            return months.getLast();
        }

        public Money invested() {
            return end().invested();
        }

        public Money owed() {
            return end().owed();
        }

        public Money net() {
            return end().net();
        }
    }

    /**
     * @param debts  the debts as they stand
     * @param extra  the extra each month
     * @param order  which debt the debts' part goes to first
     * @param from   the first month
     * @param months how many months, 1 to {@value #MAX_MONTHS}
     */
    public static Outcome follow(List<Debt> debts, Money extra, Order order, Investment investment, Plan plan, YearMonth from, int months) {
        if (extra.isNegative()) {
            throw new IllegalArgumentException("The extra amount cannot be negative");
        }
        if (months < 1 || months > MAX_MONTHS) {
            throw new IllegalArgumentException("The comparison runs 1 month to 30 years");
        }
        int count = debts.size();
        Money[] owed = new Money[count];
        LocalDate[] paidOff = new LocalDate[count];
        for (int i = 0; i < count; i++) {
            owed[i] = debts.get(i).balance().isNegative() ? Money.ZERO : debts.get(i).balance();
        }
        BigDecimal growth = BigDecimal.ONE.add(SavingsInterest.monthlyRate(investment.yearly()));
        BigDecimal share = plan.debtShare().toFraction();
        BigDecimal value = BigDecimal.ZERO;
        Money putIn = Money.ZERO;
        Money matched = Money.ZERO;
        Money interest = Money.ZERO;
        // What used to be paid into debts that are gone.
        Money freed = Money.ZERO;
        List<Point> points = new ArrayList<>(months);

        YearMonth month = from;
        for (int step = 0; step < months; step++, month = month.plusMonths(1)) {
            Money spare = extra;
            if (plan.freedMoves()) {
                spare = spare.plus(freed);
            }
            LocalDate[] days = new LocalDate[count];
            for (int i = 0; i < count; i++) {
                Debt debt = debts.get(i);
                days[i] = debt.paymentDay(month);
                if (owed[i].isZero() || days[i] == null) {
                    continue;
                }
                Money charged = Amortization.monthlyInterest(owed[i], debt.rateOn(days[i]));
                interest = interest.plus(charged);
                owed[i] = owed[i].plus(charged);
                Money paid = debt.payment().compareTo(owed[i]) > 0 ? owed[i] : debt.payment();
                owed[i] = owed[i].minus(paid);
                if (owed[i].isZero()) {
                    paidOff[i] = days[i];
                    if (plan.freedMoves()) {
                        // The last payment is only as large as it needs to be: the rest is spare this month already.
                        spare = spare.plus(debt.payment().minus(paid));
                        freed = freed.plus(debt.payment());
                    }
                }
            }

            // The debts' part, one debt at a time; whatever they cannot take is invested with the rest.
            Money forDebts = anyOwed(owed) ? spare.times(share) : Money.ZERO;
            Money toInvest = spare.minus(forDebts);
            while (!forDebts.isZero()) {
                int target = PayoffPlan.next(debts, owed, days, order);
                if (target < 0) {
                    break;
                }
                Money paid = forDebts.compareTo(owed[target]) > 0 ? owed[target] : forDebts;
                owed[target] = owed[target].minus(paid);
                forDebts = forDebts.minus(paid);
                if (owed[target].isZero()) {
                    paidOff[target] = days[target];
                    if (plan.freedMoves()) {
                        freed = freed.plus(debts.get(target).payment());
                    }
                }
            }
            toInvest = toInvest.plus(forDebts);

            Money match = toInvest.isZero() ? Money.ZERO : investment.matchFor().apply(toInvest);
            value = value.multiply(growth, PRECISION).add(toInvest.plus(match).toDecimal(), PRECISION);
            putIn = putIn.plus(toInvest);
            matched = matched.plus(match);
            points.add(new Point(month, Money.fromDecimal(value), total(owed)));
        }

        Money invested = points.getLast().invested();
        LocalDate debtFree = null;
        if (count > 0 && !anyOwed(owed)) {
            for (LocalDate day : paidOff) {
                if (day != null && (debtFree == null || day.isAfter(debtFree))) {
                    debtFree = day;
                }
            }
        }
        return new Outcome(putIn, matched, invested.minus(putIn).minus(matched), interest, debtFree, List.copyOf(points));
    }

    private static Money total(Money[] owed) {
        Money sum = Money.ZERO;
        for (Money amount : owed) {
            sum = sum.plus(amount);
        }
        return sum;
    }

    private static boolean anyOwed(Money[] owed) {
        for (Money amount : owed) {
            if (!amount.isZero()) {
                return true;
            }
        }
        return false;
    }
}
