package app.budget.engine.items;

import app.budget.engine.Money;
import app.budget.engine.schedule.ScheduleRule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * A bill for something used an uncertain number of times, billed once a month at most for every use since
 * the last bill (Rachel, 2026-10-20: snowplowing, "it may happen multiple times per day or week during the
 * winter. each plow costs a certain dollar amount ... billed once per month based on those two things").
 *
 * <p>The amount is an estimate: the cost of each use × about how many uses fall on the days the bill
 * covers, counting only days in the months it can happen in. A week's uses are spread evenly over its
 * seven days, a month's over that month's days, so a bill that covers half of January counts half of
 * January's uses.
 */
public final class UsageBill {

    private UsageBill() {}

    /** What the number of uses is counted per. */
    public enum Per {
        WEEK,
        MONTH
    }

    /**
     * About how often it is used.
     *
     * @param hundredths uses in hundredths, so 2.5 times a week is 250; more than 0
     */
    public record Usage(long hundredths, Per per) {

        public Usage {
            if (hundredths <= 0) {
                throw new IllegalArgumentException("uses must be more than 0");
            }
            if (per == null) {
                throw new IllegalArgumentException("per is required");
            }
        }
    }

    /** Average weeks in a month: 52 ÷ 12, the same nominal count every yearly figure uses. */
    private static final BigDecimal WEEKS_A_MONTH = BigDecimal.valueOf(52).divide(BigDecimal.valueOf(12), 20, RoundingMode.HALF_EVEN);

    /** Enough places that adding up a bill's days loses nothing before the one rounding to the cent. */
    private static final int SCALE = 20;

    /**
     * The estimated bill for the uses after {@code after} up to and including {@code through}, on days
     * {@code season} runs in (its months; its other terms are ignored). Rounded half-even to the cent.
     */
    public static Money between(Money each, Usage usage, ScheduleRule season, LocalDate after, LocalDate through) {
        BigDecimal uses = BigDecimal.ZERO;
        // A month at a time: every day in one month carries the same share of uses.
        LocalDate day = after.plusDays(1);
        while (!day.isAfter(through)) {
            YearMonth month = YearMonth.from(day);
            LocalDate last = month.atEndOfMonth().isBefore(through) ? month.atEndOfMonth() : through;
            if (season.runsIn(day)) {
                long days = last.toEpochDay() - day.toEpochDay() + 1;
                uses = uses.add(usesPerDay(usage, month).multiply(BigDecimal.valueOf(days)));
            }
            day = last.plusDays(1);
        }
        return cents(each, uses);
    }

    /**
     * The day before the first use the monthly bill on {@code scheduled} covers: the previous month's bill
     * day, or the day before {@code rule} starts when that is later (nothing was used before it began).
     */
    public static LocalDate coversAfter(ScheduleRule rule, LocalDate scheduled) {
        YearMonth before = YearMonth.from(scheduled).minusMonths(1);
        int day = rule.day1() == null ? scheduled.getDayOfMonth() : rule.day1();
        LocalDate previous = before.atDay(Math.min(day, before.lengthOfMonth()));
        LocalDate dayBeforeStart = rule.startDate().minusDays(1);
        return previous.isBefore(dayBeforeStart) ? dayBeforeStart : previous;
    }

    /** The bill for a whole month it can happen in: one month's uses, or a week's × 52 ÷ 12. */
    public static Money typicalMonth(Money each, Usage usage) {
        BigDecimal times = hundredths(usage);
        return cents(each, usage.per() == Per.WEEK ? times.multiply(WEEKS_A_MONTH) : times);
    }

    /** What a year of it comes to: a typical month for each of the {@code months} it can happen in. */
    public static Money yearly(Money each, Usage usage, int months) {
        BigDecimal times = hundredths(usage);
        BigDecimal perMonth = usage.per() == Per.WEEK ? times.multiply(WEEKS_A_MONTH) : times;
        return cents(each, perMonth.multiply(BigDecimal.valueOf(months)));
    }

    private static BigDecimal usesPerDay(Usage usage, YearMonth month) {
        int days = usage.per() == Per.WEEK ? 7 : month.lengthOfMonth();
        return hundredths(usage).divide(BigDecimal.valueOf(days), SCALE, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal hundredths(Usage usage) {
        return BigDecimal.valueOf(usage.hundredths()).movePointLeft(2);
    }

    private static Money cents(Money each, BigDecimal uses) {
        return Money.ofCents(BigDecimal.valueOf(each.cents()).multiply(uses).setScale(0, Money.ROUNDING).longValueExact());
    }
}
