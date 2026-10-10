package app.budget.engine.interest;

import app.budget.engine.BasisPoints;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * How one account earns interest: the day of the month it is credited, and its APY over time.
 *
 * @param creditDay day of the month, 1–31 (31 = the last day)
 * @param rates     every rate the user entered, in any order
 */
public record InterestTerms(int creditDay, List<RateChange> rates) {

    /** An APY that applies from {@code effectiveFrom} until the next change. */
    public record RateChange(LocalDate effectiveFrom, BasisPoints apy) {}

    public InterestTerms {
        rates = rates.stream().sorted(Comparator.comparing(RateChange::effectiveFrom)).toList();
    }

    /** The APY in force on {@code date}; empty before the first rate takes effect. */
    public Optional<BasisPoints> rateOn(LocalDate date) {
        BasisPoints inForce = null;
        for (RateChange change : rates) {
            if (!change.effectiveFrom().isAfter(date)) {
                inForce = change.apy();
            }
        }
        return Optional.ofNullable(inForce);
    }
}
