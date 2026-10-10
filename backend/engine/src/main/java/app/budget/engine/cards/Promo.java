package app.budget.engine.cards;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import java.time.LocalDate;

/**
 * A promotional rate on part of a card's balance (spec §4.9): "0% on this balance transfer
 * until March 15".
 *
 * @param end       the last day the promotional rate applies
 * @param original  the balance the promotion started with; it never covers more than this
 * @param rateAfter the rate on what is left afterwards; {@code null} for the card's regular APR
 * @param deferred  true for deferred interest: unless the balance is cleared by {@code end},
 *                  interest for the whole promotion is charged at once
 */
public record Promo(
        long id, BasisPoints rate, LocalDate start, LocalDate end, Money original, BasisPoints rateAfter, boolean deferred) {

    public Promo {
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("A promotion has to end after it starts");
        }
        if (original.isNegative() || original.isZero()) {
            throw new IllegalArgumentException("A promotion needs the balance it started with");
        }
    }

    public boolean hasStarted(LocalDate on) {
        return !start.isAfter(on);
    }

    /** True while the promotional rate still applies on {@code on}. */
    public boolean activeOn(LocalDate on) {
        return hasStarted(on) && !on.isAfter(end);
    }

    /** The rate its balance is charged on {@code on}: the promotional one until it ends, then what follows. */
    public BasisPoints rateOn(LocalDate on, BasisPoints regularApr) {
        if (!on.isAfter(end)) {
            return rate;
        }
        return rateAfter != null ? rateAfter : regularApr;
    }
}
