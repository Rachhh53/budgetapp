package app.budget.engine.cards;

import app.budget.engine.Money;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Clearing a promotional balance before its rate ends (spec §5.6): what it takes from each
 * paycheck, and how close the end is.
 */
public final class PromoPayoff {

    private PromoPayoff() {}

    /** How urgent a promotion's end is. Never shown by color alone: each has its own words. */
    public enum Warning {
        /** Nothing left on it, or its end is further off than the lead time. */
        NONE,
        /** Its end is within the lead time. */
        SOON,
        /** Its end is within the lead time and unpaid interest for the whole promotion would be charged. */
        SOON_DEFERRED,
        /** It has ended with a balance still on it. */
        ENDED
    }

    /**
     * What to pay from each paycheck so {@code remaining} is gone by the end date: shared
     * evenly over the paydays left and rounded up to the cent, so it is never a cent short.
     * Empty when there is no payday left to pay it on.
     */
    public static Optional<Money> perPayday(Money remaining, int paydaysLeft) {
        if (remaining.isNegative()) {
            throw new IllegalArgumentException("What is left of a promotion cannot be negative");
        }
        if (paydaysLeft <= 0) {
            return Optional.empty();
        }
        return Optional.of(Money.ofCents((remaining.cents() + paydaysLeft - 1) / paydaysLeft));
    }

    /** Days from {@code today} until the promotion's last day; negative once it has ended. */
    public static long daysLeft(LocalDate today, LocalDate end) {
        return ChronoUnit.DAYS.between(today, end);
    }

    /** @param leadDays how many days before the end the user wants to be warned */
    public static Warning warning(Promo promo, Money remaining, LocalDate today, int leadDays) {
        if (remaining.isNegative() || remaining.isZero() || !promo.hasStarted(today)) {
            return Warning.NONE;
        }
        long left = daysLeft(today, promo.end());
        if (left < 0) {
            return Warning.ENDED;
        }
        if (left > leadDays) {
            return Warning.NONE;
        }
        return promo.deferred() ? Warning.SOON_DEFERRED : Warning.SOON;
    }
}
