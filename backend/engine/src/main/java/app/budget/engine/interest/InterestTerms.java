package app.budget.engine.interest;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How one account earns (or is charged) interest: the day of the month it is credited, its
 * rate over time, and any amounts the user typed over.
 *
 * @param creditDay day of the month, 1–31 (31 = the last day); not used when {@code daily}
 * @param rates     every rate the user entered, in any order
 * @param daily     true when it builds up a little every day instead of arriving once a month:
 *                  an investment's assumed growth, which no bank posts on a day of its own
 * @param typed     amounts the user typed over the estimate (spec §5.4), which simply become the
 *                  amount. Keyed by the day the interest is added; for {@code daily} growth, by the
 *                  first day of the month, and the amount is that whole month's growth
 */
public record InterestTerms(int creditDay, List<RateChange> rates, boolean daily, Map<LocalDate, Money> typed) {

    /** An account credited once a month, as a bank pays interest. */
    public InterestTerms(int creditDay, List<RateChange> rates) {
        this(creditDay, rates, false, Map.of());
    }

    public InterestTerms(int creditDay, List<RateChange> rates, boolean daily) {
        this(creditDay, rates, daily, Map.of());
    }

    /** An account that builds up every day at the yearly rate given. */
    public static InterestTerms growingDaily(List<RateChange> rates) {
        return new InterestTerms(SavingsInterest.LAST_DAY, rates, true);
    }

    /** An APY that applies from {@code effectiveFrom} until the next change. */
    public record RateChange(LocalDate effectiveFrom, BasisPoints apy) {}

    public InterestTerms {
        rates = rates.stream().sorted(Comparator.comparing(RateChange::effectiveFrom)).toList();
        typed = Map.copyOf(typed);
    }

    /** The same terms, with these amounts typed over. */
    public InterestTerms withTyped(Map<LocalDate, Money> amounts) {
        return new InterestTerms(creditDay, rates, daily, amounts);
    }

    /** The amount typed over the interest added on {@code date}; empty when it is estimated. */
    public Optional<Money> typedOn(LocalDate date) {
        return Optional.ofNullable(typed.get(date));
    }

    /** For daily growth: the whole month's growth as typed over; empty when it is estimated. */
    public Optional<Money> typedFor(YearMonth month) {
        return Optional.ofNullable(typed.get(month.atDay(1)));
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
