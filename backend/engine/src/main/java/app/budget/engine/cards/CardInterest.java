package app.budget.engine.cards;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Estimated credit card interest (spec §5.6): a month's interest is a twelfth of the yearly
 * rate on the balance carried, with each promotion's share of that balance charged at the
 * promotion's own rate.
 *
 * <p>A card charges a periodic rate (APR ÷ 12), unlike a savings APY, which already includes
 * compounding. Everything is worked out in {@link BigDecimal} and rounded to cents once, with
 * the app's one rounding rule ({@link Money#ROUNDING}).
 */
public final class CardInterest {

    private static final MathContext PRECISION = MathContext.DECIMAL128;
    private static final BigDecimal MONTHS = BigDecimal.valueOf(12);

    private CardInterest() {}

    /**
     * Part of a card's balance and the rate it is charged.
     *
     * @param promoId the promotion this part belongs to; {@code null} for the rest of the balance
     */
    public record Portion(Long promoId, Money amount, BasisPoints rate) {}

    /** One month's share of a yearly rate. */
    public static BigDecimal monthlyRate(BasisPoints apr) {
        if (apr.value() < 0) {
            throw new IllegalArgumentException("An APR cannot be negative");
        }
        return apr.toFraction().divide(MONTHS, PRECISION);
    }

    /**
     * Splits {@code balance} between the promotions that have started and the rest.
     *
     * <p>A card issuer puts what is paid beyond the minimum toward the highest rate first, so
     * the lowest rate keeps its balance longest: the balance is given to promotions in order
     * of the rate they charge on {@code on}, lowest first, each up to the balance it started
     * with, and whatever is left over is at the regular APR. A promotion that has ended is
     * still its own part, at the rate that follows it.
     */
    public static List<Portion> allocate(Money balance, List<Promo> promos, LocalDate on, BasisPoints regularApr) {
        List<Portion> portions = new ArrayList<>();
        Money left = balance.isNegative() ? Money.ZERO : balance;
        List<Promo> started = promos.stream()
                .filter(promo -> promo.hasStarted(on))
                .sorted(Comparator.comparingInt((Promo promo) -> promo.rateOn(on, regularApr).value())
                        .thenComparing(Promo::end, Comparator.reverseOrder())
                        .thenComparingLong(Promo::id))
                .toList();
        for (Promo promo : started) {
            Money share = left.compareTo(promo.original()) < 0 ? left : promo.original();
            portions.add(new Portion(promo.id(), share, promo.rateOn(on, regularApr)));
            left = left.minus(share);
        }
        portions.add(new Portion(null, left, regularApr));
        return portions;
    }

    /** What is left of one promotion's balance on {@code on}; zero before it starts. */
    public static Money remaining(Promo promo, Money balance, List<Promo> promos, LocalDate on, BasisPoints regularApr) {
        return allocate(balance, promos, on, regularApr).stream()
                .filter(portion -> portion.promoId() != null && portion.promoId() == promo.id())
                .map(Portion::amount)
                .findFirst()
                .orElse(Money.ZERO);
    }

    /** One month's interest on {@code carried}, each part of it at its own rate. */
    public static Money forMonth(Money carried, List<Promo> promos, LocalDate on, BasisPoints regularApr) {
        Money interest = Money.ZERO;
        for (Portion portion : allocate(carried, promos, on, regularApr)) {
            interest = interest.plus(portion.amount().times(monthlyRate(portion.rate())));
        }
        return interest;
    }

    /**
     * What a deferred-interest promotion charges when it ends with {@code remaining} still owed:
     * interest on the balance it started with, for every month it ran, at the rate that follows
     * it. Nothing when it was cleared in time, or when the promotion is not deferred interest.
     */
    public static Money deferredCharge(Promo promo, Money remaining, BasisPoints regularApr) {
        if (!promo.deferred() || remaining.isNegative() || remaining.isZero()) {
            return Money.ZERO;
        }
        BasisPoints rate = promo.rateAfter() != null ? promo.rateAfter() : regularApr;
        long months = Math.max(1, ChronoUnit.MONTHS.between(promo.start(), promo.end()));
        return promo.original().times(monthlyRate(rate).multiply(BigDecimal.valueOf(months), PRECISION));
    }
}
