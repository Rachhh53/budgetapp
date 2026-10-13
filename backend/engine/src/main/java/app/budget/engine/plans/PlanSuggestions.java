package app.budget.engine.plans;

import app.budget.engine.Money;
import app.budget.engine.reports.AnnualCosts;
import app.budget.engine.reports.AnnualCosts.Window;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * What to suggest for next year's amount of one bill or budget (spec §5.8).
 *
 * <p>Which lines of history belong to an item, and what it comes to at today's amount, is the
 * caller's job; this only applies the rules.
 */
public final class PlanSuggestions {

    private static final BigDecimal BP_PER_WHOLE = BigDecimal.valueOf(10_000);

    private PlanSuggestions() {}

    /** What a plan's suggestions are worked out from. */
    public enum Basis {
        /** The total of the last 12 whole months. */
        LAST_12,
        /** The last 12 months, moved on by how much they changed against the 12 before. The default. */
        LAST_12_TREND,
        /** The most recent whole calendar year. */
        PRIOR_YEAR
    }

    /** Why a suggestion is what it is, so the screen can say so. */
    public enum Reason {
        /** Worked out from what the item actually came to. */
        HISTORY,
        /** An amount that is not a price: a loan payment, pay, a transfer. Suggested unchanged. */
        FIXED,
        /** Less history than the basis needs, so today's amount over a year is suggested. */
        NOT_ENOUGH_HISTORY
    }

    /**
     * What is known about one item.
     *
     * @param last12         what it came to in the 12 months ending last month; {@code null} when its history starts later than they do
     * @param before12       the 12 months before those; {@code null} when its history does not reach back that far
     * @param priorYear      the last whole calendar year; {@code null} when its history does not cover it
     * @param currentPerYear what it comes to over a year at the amount set now
     * @param fixed          true for an amount that is suggested unchanged whatever the history
     */
    public record History(Money last12, Money before12, Money priorYear, Money currentPerYear, boolean fixed) {}

    /**
     * @param amount  the suggested amount for the whole year
     * @param trendBp how much the last 12 months changed against the 12 before, in basis points
     *                (3.3% is 330); {@code null} when there is nothing to compare, or the amount is fixed
     */
    public record Suggestion(Money amount, Integer trendBp, Reason reason) {}

    /** The 12 months ending with the month before {@code asOf}, and the 12 before those, oldest first. */
    public static List<Window> rollingWindows(LocalDate asOf) {
        return AnnualCosts.windows(AnnualCosts.Basis.ROLLING, asOf, 2);
    }

    /** The whole calendar year before the one {@code asOf} is in. */
    public static Window priorYear(LocalDate asOf) {
        int year = asOf.getYear() - 1;
        return new Window(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }

    public static Suggestion suggest(Basis basis, History history) {
        if (history.fixed()) {
            return new Suggestion(history.currentPerYear(), null, Reason.FIXED);
        }
        Integer trendBp = trendBp(history);
        Money fromHistory = switch (basis) {
            case LAST_12 -> history.last12();
            case LAST_12_TREND -> history.last12() == null ? null : withTrend(history.last12(), trendBp);
            case PRIOR_YEAR -> history.priorYear();
        };
        if (fromHistory == null) {
            // Today's amount is honest; a few months stretched to twelve would not be.
            return new Suggestion(history.currentPerYear(), null, Reason.NOT_ENOUGH_HISTORY);
        }
        return new Suggestion(fromHistory, trendBp, Reason.HISTORY);
    }

    /** The last 12 months against the 12 before; {@code null} without both, or when the earlier ones held nothing. */
    private static Integer trendBp(History history) {
        if (history.last12() == null || history.before12() == null || !isPositive(history.before12())) {
            return null;
        }
        return history.last12().minus(history.before12()).toDecimal()
                .multiply(BP_PER_WHOLE)
                .divide(history.before12().toDecimal(), 0, Money.ROUNDING)
                .intValueExact();
    }

    /** {@code last12} moved on by its trend, to the whole dollar; unchanged when there is no trend to apply. */
    private static Money withTrend(Money last12, Integer trendBp) {
        if (trendBp == null) {
            return last12;
        }
        BigDecimal factor = BigDecimal.ONE.add(BigDecimal.valueOf(trendBp).divide(BP_PER_WHOLE));
        BigDecimal dollars = last12.toDecimal().multiply(factor).setScale(0, RoundingMode.HALF_EVEN);
        // A cost that is falling fast must never be suggested below nothing.
        return Money.fromDecimal(dollars.max(BigDecimal.ZERO));
    }

    private static boolean isPositive(Money amount) {
        return !amount.isZero() && !amount.isNegative();
    }
}
