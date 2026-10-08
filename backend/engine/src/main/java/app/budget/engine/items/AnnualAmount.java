package app.budget.engine.items;

import app.budget.engine.Money;
import app.budget.engine.schedule.Frequency;
import app.budget.engine.schedule.ScheduleRule;
import java.math.BigDecimal;

/**
 * Converts between a yearly total and the amount of one occurrence, for items the user
 * enters as an annual amount and for the "per year" figure shown beside every item.
 *
 * <p>The count is the schedule's nominal one (52 weeks, 26 fortnights, 12 months), not a
 * tally of a particular calendar year, so an amount does not wobble in a 53-week year.
 */
public final class AnnualAmount {

    private AnnualAmount() {}

    /** How often a schedule comes round: {@code occurrences} times every {@code years} years. */
    public record PerYear(int occurrences, int years) {

        public static final PerYear ONCE = new PerYear(1, 1);

        public PerYear {
            if (occurrences < 1 || years < 1) {
                throw new IllegalArgumentException("occurrences and years must be at least 1");
            }
        }
    }

    /**
     * @param payRule the primary income schedule, which an {@code EVERY_PAY_PERIOD} rule follows; may be {@code null}
     * @param year    the calendar year to count hand-picked dates in
     */
    public static PerYear perYear(ScheduleRule rule, ScheduleRule payRule, int year) {
        return switch (rule.frequency()) {
            case WEEKLY -> new PerYear(52, 1);
            case BIWEEKLY -> new PerYear(26, 1);
            case SEMI_MONTHLY -> new PerYear(24, 1);
            case MONTHLY -> new PerYear(12, 1);
            // Every 5 months is 12 times in 5 years.
            case EVERY_N_MONTHS -> new PerYear(12, rule.interval());
            case YEARLY, ONE_TIME -> PerYear.ONCE;
            case MANUAL_DATES -> new PerYear(
                    (int) Math.max(1, rule.manualDates().stream().filter(date -> date.getYear() == year).count()), 1);
            case EVERY_PAY_PERIOD -> payRule == null || payRule.frequency() == Frequency.EVERY_PAY_PERIOD
                    ? PerYear.ONCE
                    : perYear(payRule, null, year);
        };
    }

    /** One occurrence's share of a yearly total, rounded half-even to the cent. */
    public static Money perOccurrence(Money annual, PerYear perYear) {
        return scale(annual, perYear.years(), perYear.occurrences());
    }

    /** What one occurrence's amount adds up to over a year. */
    public static Money yearly(Money perOccurrence, PerYear perYear) {
        return scale(perOccurrence, perYear.occurrences(), perYear.years());
    }

    private static Money scale(Money amount, int multiply, int divide) {
        BigDecimal cents = BigDecimal.valueOf(amount.cents())
                .multiply(BigDecimal.valueOf(multiply))
                .divide(BigDecimal.valueOf(divide), 0, Money.ROUNDING);
        return Money.ofCents(cents.longValueExact());
    }
}
