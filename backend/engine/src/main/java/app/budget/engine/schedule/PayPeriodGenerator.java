package app.budget.engine.schedule;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Builds pay periods from the primary income source's schedule (spec §5.1). Weekend and
 * holiday rules move paydays, and therefore the period boundaries.
 */
public final class PayPeriodGenerator {

    /** Schedules a paycheck can follow. The others describe bills, not pay. */
    public static final Set<Frequency> PAY_FREQUENCIES = EnumSet.of(
            Frequency.WEEKLY, Frequency.BIWEEKLY, Frequency.SEMI_MONTHLY, Frequency.MONTHLY, Frequency.MANUAL_DATES);

    /**
     * How far beyond the asked-for range to look for the paydays that open the first period
     * and close the last: two months covers the longest gap (monthly pay) plus any move.
     */
    private static final int NEIGHBOUR_MONTHS = 2;

    private final ScheduleGenerator schedule;

    public PayPeriodGenerator(ScheduleGenerator schedule) {
        this.schedule = schedule;
    }

    /**
     * Every period that overlaps {@code from}–{@code through} (both inclusive), in order.
     *
     * <p>A period needs the next payday to know where it ends, so nothing is returned before
     * the first payday or after the last one (a schedule with an end date, or the final
     * manual date).
     */
    public List<PayPeriod> periods(ScheduleRule rule, LocalDate from, LocalDate through) {
        if (!PAY_FREQUENCIES.contains(rule.frequency())) {
            throw new IllegalArgumentException("Pay periods cannot follow a " + rule.frequency() + " schedule");
        }
        List<Occurrence> paydays = rule.frequency() == Frequency.MANUAL_DATES
                // Hand-picked dates can be any distance apart, so take them all (with room
                // either side for the first or last one being moved off a weekend).
                ? schedule.occurrences(rule,
                        rule.manualDates().getFirst().minusMonths(NEIGHBOUR_MONTHS),
                        rule.manualDates().getLast().plusMonths(NEIGHBOUR_MONTHS))
                : schedule.occurrences(rule, from.minusMonths(NEIGHBOUR_MONTHS), through.plusMonths(NEIGHBOUR_MONTHS));

        List<PayPeriod> periods = new ArrayList<>();
        for (int i = 0; i + 1 < paydays.size(); i++) {
            Occurrence payday = paydays.get(i);
            LocalDate end = paydays.get(i + 1).date().minusDays(1);
            if (!end.isBefore(from) && !payday.date().isAfter(through)) {
                periods.add(new PayPeriod(payday.date(), end, payday.scheduled()));
            }
        }
        return periods;
    }
}
