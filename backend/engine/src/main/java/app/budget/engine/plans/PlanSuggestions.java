package app.budget.engine.plans;

import app.budget.engine.Money;
import app.budget.engine.items.AnnualAmount.PerYear;
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

    /**
     * The fewest whole months that are scaled up to a year (Rachel, 2026-10-23: "I know I don't have a full 12 months of
     * data in there but that's the deal right now. maybe it adjusts if there is less?"). One or two months say too
     * little about a whole year, so with fewer today's amount is suggested.
     */
    public static final int MIN_MONTHS = 3;

    /** The fewest whole months that are split in two halves to give a trend, without 24 months to compare. */
    public static final int MIN_TREND_MONTHS = 6;

    private static final int MONTHS_A_YEAR = 12;

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
        /** Fewer than 12 whole months of history, scaled up to a year: what they came to a month, times 12. */
        SHORT_HISTORY,
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
     * @param months         each whole month of its history, oldest first, ending with the month before the plan is
     *                       drawn up, when it has fewer than 24 such months. Empty when it has 24 or more, and when its
     *                       schedule cannot be scaled to a year: one that runs only some months, or comes less often
     *                       than monthly, would come out wrong from a few months.
     * @param perYear        how often its schedule comes round, to scale what it costs each time up to a year;
     *                       {@code null} when that is not the schedule's to say (billed by use), and the months are
     *                       scaled as months
     */
    public record History(
            Money last12, Money before12, Money priorYear, Money currentPerYear, boolean fixed, List<Month> months, PerYear perYear) {

        public History {
            months = List.copyOf(months);
        }

        /** With 24 months of history, or a schedule that is not scaled. */
        public History(Money last12, Money before12, Money priorYear, Money currentPerYear, boolean fixed) {
            this(last12, before12, priorYear, currentPerYear, fixed, List.of(), null);
        }
    }

    /**
     * One whole month of an item's history.
     *
     * @param amount what it came to
     * @param times  how many times it came round: a weekly bill has four Wednesdays one month and five the next, which
     *               is no change in its price
     */
    public record Month(Money amount, int times) {}

    /**
     * @param amount  the suggested amount for the whole year
     * @param trendBp how much the last 12 months changed against the 12 before, in basis points
     *                (3.3% is 330), or without 24 months the later half of its months against the earlier half;
     *                {@code null} when there is nothing to compare, or the amount is fixed
     * @param months  for {@link Reason#SHORT_HISTORY}, how many months were scaled up to a year; {@code null} otherwise
     */
    public record Suggestion(Money amount, Integer trendBp, Reason reason, Integer months) {

        public Suggestion(Money amount, Integer trendBp, Reason reason) {
            this(amount, trendBp, reason, null);
        }
    }

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
        // Without 12 months, the months there are, at what they came to a month: a bill begun in March is not a
        // quarter cheaper for having no January or February.
        boolean scaled = history.last12() == null && history.months().size() >= MIN_MONTHS && basis != Basis.PRIOR_YEAR;
        Money last12 = scaled ? scaledToYear(history.months(), history.perYear()) : history.last12();
        Money fromHistory = switch (basis) {
            case LAST_12 -> last12;
            case LAST_12_TREND -> last12 == null ? null : withTrend(last12, trendBp);
            // The last calendar year is that year: it is not made up from part of another.
            case PRIOR_YEAR -> history.priorYear();
        };
        if (fromHistory == null) {
            // Today's amount is honest; a month or two stretched to twelve would not be.
            return new Suggestion(history.currentPerYear(), null, Reason.NOT_ENOUGH_HISTORY);
        }
        return scaled
                ? new Suggestion(fromHistory, trendBp, Reason.SHORT_HISTORY, history.months().size())
                : new Suggestion(fromHistory, trendBp, Reason.HISTORY);
    }

    /**
     * The last 12 months against the 12 before. Without 24 months, the later half of the months there are against the
     * earlier half, up to 12 against 12, once there are {@link #MIN_TREND_MONTHS}. {@code null} with less, or when the
     * earlier ones held nothing.
     */
    private static Integer trendBp(History history) {
        if (history.last12() != null && history.before12() != null) {
            return change(history.last12(), history.before12());
        }
        List<Month> months = history.months();
        if (months.size() < MIN_TREND_MONTHS) {
            return null;
        }
        int half = Math.min(MONTHS_A_YEAR, months.size() / 2);
        List<Month> later = months.subList(months.size() - half, months.size());
        List<Month> earlier = months.subList(months.size() - 2 * half, months.size() - half);
        int laterTimes = times(later);
        int earlierTimes = times(earlier);
        if (laterTimes == 0 || earlierTimes == 0) {
            return change(sum(later), sum(earlier));
        }
        // What it cost each time, later against earlier: three months of thirteen Wednesdays against three of fourteen
        // are the same price.
        BigDecimal laterEach = sum(later).toDecimal().divide(BigDecimal.valueOf(laterTimes), 10, Money.ROUNDING);
        BigDecimal earlierEach = sum(earlier).toDecimal().divide(BigDecimal.valueOf(earlierTimes), 10, Money.ROUNDING);
        if (earlierEach.signum() <= 0) {
            return null;
        }
        return laterEach.subtract(earlierEach).multiply(BP_PER_WHOLE).divide(earlierEach, 0, Money.ROUNDING).intValueExact();
    }

    /** How much {@code later} differs from {@code earlier}, in basis points; {@code null} when the earlier held nothing. */
    private static Integer change(Money later, Money earlier) {
        if (!isPositive(earlier)) {
            return null;
        }
        return later.minus(earlier).toDecimal()
                .multiply(BP_PER_WHOLE)
                .divide(earlier.toDecimal(), 0, Money.ROUNDING)
                .intValueExact();
    }

    /**
     * Some months over a year, to the cent: what it cost each time, as many times as its schedule comes round in a year;
     * without a schedule to say so, what the months came to × 12 ÷ how many.
     */
    private static Money scaledToYear(List<Month> months, PerYear perYear) {
        long cents = sum(months).cents();
        int times = times(months);
        BigDecimal scaled = perYear != null && times > 0
                ? BigDecimal.valueOf(cents).multiply(BigDecimal.valueOf(perYear.occurrences()))
                        .divide(BigDecimal.valueOf((long) times * perYear.years()), 0, Money.ROUNDING)
                : BigDecimal.valueOf(cents).multiply(BigDecimal.valueOf(MONTHS_A_YEAR))
                        .divide(BigDecimal.valueOf(months.size()), 0, Money.ROUNDING);
        return Money.ofCents(scaled.longValueExact());
    }

    private static Money sum(List<Month> months) {
        return months.stream().map(Month::amount).reduce(Money.ofCents(0), Money::plus);
    }

    private static int times(List<Month> months) {
        return months.stream().mapToInt(Month::times).sum();
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
