package app.budget.engine.investments;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;

/**
 * What an employer adds to a paycheck's retirement contribution (spec §5.5): either the same
 * amount every paycheck, or a share of what the employee puts in, up to a share of gross pay
 * ("50% of what you contribute, up to 6% of your pay").
 *
 * @param fixed      the amount every paycheck; only for {@link Mode#FIXED}
 * @param match      the share of the employee's contribution that is matched; only for {@link Mode#FORMULA}
 * @param capOfGross the share of gross pay beyond which contributions are no longer matched
 */
public record EmployerMatch(Mode mode, Money fixed, BasisPoints match, BasisPoints capOfGross) {

    public enum Mode {
        FIXED,
        FORMULA
    }

    public static EmployerMatch fixed(Money amount) {
        if (amount.isNegative()) {
            throw new IllegalArgumentException("A match cannot be negative");
        }
        return new EmployerMatch(Mode.FIXED, amount, null, null);
    }

    public static EmployerMatch formula(BasisPoints match, BasisPoints capOfGross) {
        if (match.value() < 0 || capOfGross.value() < 0) {
            throw new IllegalArgumentException("A match and its cap cannot be negative");
        }
        return new EmployerMatch(Mode.FORMULA, null, match, capOfGross);
    }

    /**
     * The match on one paycheck.
     *
     * @param gross    the paycheck before anything is taken out
     * @param employee what the employee puts in from this paycheck
     */
    public Money forPaycheck(Money gross, Money employee) {
        if (mode == Mode.FIXED) {
            return fixed;
        }
        Money matched = employee.compareTo(matchedUpTo(gross)) < 0 ? employee : matchedUpTo(gross);
        return matched.isNegative() ? Money.ZERO : matched.times(match.toFraction());
    }

    /**
     * The match on one paycheck when gross pay is not known, and the employee has confirmed that
     * what they put in is within the matched share of it: all of it is matched.
     */
    public Money forContributionWithinCap(Money employee) {
        if (mode == Mode.FIXED) {
            return fixed;
        }
        return employee.isNegative() ? Money.ZERO : employee.times(match.toFraction());
    }

    /** The most the employer would add to one paycheck: what contributing up to the cap earns. */
    public Money mostPossible(Money gross) {
        return mode == Mode.FIXED ? fixed : matchedUpTo(gross).times(match.toFraction());
    }

    /** How much more the employee would have to put in, per paycheck, to earn the whole match. Zero at or over the cap. */
    public Money contributionShortOfCap(Money gross, Money employee) {
        if (mode == Mode.FIXED) {
            return Money.ZERO;
        }
        Money shortBy = matchedUpTo(gross).minus(employee);
        return shortBy.isNegative() ? Money.ZERO : shortBy;
    }

    /** The largest contribution that is still matched: the cap's share of gross pay. */
    private Money matchedUpTo(Money gross) {
        return gross.times(capOfGross.toFraction());
    }
}
