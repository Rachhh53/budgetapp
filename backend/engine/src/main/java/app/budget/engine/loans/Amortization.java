package app.budget.engine.loans;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Loan amortization (spec §5.7): each monthly payment first covers a month's interest on what
 * is still owed, and the rest lowers the loan. Escrow (insurance, taxes) is part of what is
 * paid but never touches the loan, so the caller takes it off before coming here.
 *
 * <p>A month's interest is a twelfth of the yearly rate. Everything is worked out in
 * {@link BigDecimal} and rounded to cents with the app's one rounding rule ({@link Money#ROUNDING}).
 */
public final class Amortization {

    private static final MathContext PRECISION = MathContext.DECIMAL128;
    private static final BigDecimal MONTHS = BigDecimal.valueOf(12);

    /** Fifty years of payments: longer than any loan, and only there to stop one that never ends. */
    public static final int MAX_PAYMENTS = 600;

    private Amortization() {}

    /**
     * One payment.
     *
     * @param payment      interest plus principal: what went to the loan, escrow not included
     * @param balanceAfter what is still owed once it is made
     */
    public record Payment(LocalDate date, Money payment, Money interest, Money principal, Money balanceAfter) {}

    /**
     * @param payments every payment until the loan is gone, earliest first
     * @param paysOff  false when the payment does not cover the interest, so the loan never ends
     */
    public record Schedule(List<Payment> payments, boolean paysOff) {

        /** The day of the last payment; {@code null} when the loan never ends or nothing is owed. */
        public LocalDate payoffDate() {
            return paysOff && !payments.isEmpty() ? payments.getLast().date() : null;
        }

        public Money totalInterest() {
            Money total = Money.ZERO;
            for (Payment payment : payments) {
                total = total.plus(payment.interest());
            }
            return total;
        }
    }

    /** One month's interest on {@code balance}. Nothing is charged on a loan that is paid off. */
    public static Money monthlyInterest(Money balance, BasisPoints rate) {
        if (rate.value() < 0) {
            throw new IllegalArgumentException("A loan rate cannot be negative");
        }
        if (balance.isNegative() || balance.isZero()) {
            return Money.ZERO;
        }
        return balance.times(rate.toFraction().divide(MONTHS, PRECISION));
    }

    /**
     * The level monthly payment (interest and principal) that pays {@code principal} off in
     * {@code termMonths}: {@code P × r ÷ (1 − (1 + r)^−n)}, or an even share when there is no interest.
     */
    public static Money standardPayment(Money principal, BasisPoints rate, int termMonths) {
        if (termMonths <= 0) {
            throw new IllegalArgumentException("A loan runs for at least one month");
        }
        if (principal.isNegative()) {
            throw new IllegalArgumentException("The amount borrowed cannot be negative");
        }
        BigDecimal monthly = rate.toFraction().divide(MONTHS, PRECISION);
        if (monthly.signum() == 0) {
            return Money.fromDecimal(principal.toDecimal().divide(BigDecimal.valueOf(termMonths), PRECISION));
        }
        BigDecimal growth = BigDecimal.ONE.add(monthly).pow(termMonths, PRECISION);
        BigDecimal factor = monthly.multiply(growth, PRECISION).divide(growth.subtract(BigDecimal.ONE), PRECISION);
        return Money.fromDecimal(principal.toDecimal().multiply(factor, PRECISION));
    }

    /**
     * The payments from {@code firstPayment} on, one a month on its day of the month, until
     * nothing is owed. The last one is only as large as it needs to be.
     *
     * @param principalAndInterest what goes to the loan each month, escrow already taken off
     */
    public static Schedule schedule(Money balance, BasisPoints rate, Money principalAndInterest, LocalDate firstPayment) {
        List<Payment> payments = new ArrayList<>();
        Money owed = balance;
        int day = firstPayment.getDayOfMonth();
        YearMonth month = YearMonth.from(firstPayment);
        while (!owed.isNegative() && !owed.isZero()) {
            Money interest = monthlyInterest(owed, rate);
            // A payment that does not cover the interest leaves the loan growing for ever.
            if (payments.size() >= MAX_PAYMENTS || principalAndInterest.compareTo(interest) <= 0) {
                return new Schedule(payments, false);
            }
            Money due = owed.plus(interest);
            Money paid = principalAndInterest.compareTo(due) > 0 ? due : principalAndInterest;
            Money principal = paid.minus(interest);
            owed = owed.minus(principal);
            payments.add(new Payment(month.atDay(Math.min(day, month.lengthOfMonth())), paid, interest, principal, owed));
            month = month.plusMonths(1);
        }
        return new Schedule(payments, true);
    }
}
