package app.budget.engine.planner;

import app.budget.engine.Money;
import app.budget.engine.investments.EmployerMatch;
import java.math.BigDecimal;
import java.math.MathContext;

/**
 * What an employer adds when more is put into a matched account (spec §5.9): only the part of
 * the extra that is still under the match's cap earns anything.
 */
public final class MatchOnExtra {

    private static final MathContext PRECISION = MathContext.DECIMAL128;
    private static final BigDecimal MONTHS = BigDecimal.valueOf(12);

    private MatchOnExtra() {}

    /**
     * @param matchedPart how much of the extra is matched
     * @param match       what the employer adds for it
     */
    public record Matched(Money matchedPart, Money match) {

        public static final Matched NONE = new Matched(Money.ZERO, Money.ZERO);
    }

    /**
     * For one paycheck.
     *
     * @param gross    the paycheck before anything is taken out
     * @param employee what the employee already puts in from it
     * @param extra    what they would add
     */
    public static Matched perPaycheck(EmployerMatch terms, Money gross, Money employee, Money extra) {
        if (extra.isNegative()) {
            throw new IllegalArgumentException("The extra amount cannot be negative");
        }
        Money room = terms.contributionShortOfCap(gross, employee);
        Money matchedPart = extra.compareTo(room) > 0 ? room : extra;
        Money match = terms.forPaycheck(gross, employee.plus(extra)).minus(terms.forPaycheck(gross, employee));
        return new Matched(matchedPart, match.isNegative() ? Money.ZERO : match);
    }

    /**
     * For a month's extra, shared evenly over the year's paychecks: both figures come back as
     * amounts a month.
     *
     * @param paychecksPerYear how many paychecks the extra is spread over; zero or fewer earns nothing
     */
    public static Matched perMonth(EmployerMatch terms, Money gross, Money employee, Money extraPerMonth, int paychecksPerYear) {
        if (paychecksPerYear <= 0) {
            return Matched.NONE;
        }
        BigDecimal paychecks = BigDecimal.valueOf(paychecksPerYear);
        Matched each = perPaycheck(terms, gross, employee, extraPerMonth.times(MONTHS.divide(paychecks, PRECISION)));
        BigDecimal aMonth = paychecks.divide(MONTHS, PRECISION);
        Money matchedPart = each.matchedPart().times(aMonth);
        // Sharing a month over the paychecks and back can leave a cent over: never more than was put in.
        return new Matched(matchedPart.compareTo(extraPerMonth) > 0 ? extraPerMonth : matchedPart, each.match().times(aMonth));
    }
}
