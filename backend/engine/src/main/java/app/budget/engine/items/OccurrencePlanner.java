package app.budget.engine.items;

import app.budget.engine.schedule.Frequency;
import app.budget.engine.schedule.Occurrence;
import app.budget.engine.schedule.PayPeriod;
import app.budget.engine.schedule.ScheduleGenerator;
import app.budget.engine.schedule.ScheduleRule;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Works out when a recurring item falls due, across the versions of it the user has saved.
 *
 * <p>A "this and all future" edit starts a new version from a date. Each occurrence takes
 * the version in force on the date its schedule names, so history keeps what it had.
 */
public final class OccurrencePlanner {

    /**
     * One version of an item.
     *
     * @param effectiveFrom   first scheduled date this version covers; the earliest version also covers everything before
     * @param daysAfterPayday for an {@code EVERY_PAY_PERIOD} rule: how many days into the period it is dated (0 = payday)
     * @param details         whatever the caller keeps per version (amount, accounts, …); the engine only passes it through
     */
    public record Version<T>(LocalDate effectiveFrom, ScheduleRule rule, int daysAfterPayday, T details) {}

    /**
     * One occurrence.
     *
     * @param scheduled the date the schedule names; identifies the occurrence when it is edited
     * @param due       when it actually falls, after any weekend or holiday move
     * @param ownPeriod for an {@code EVERY_PAY_PERIOD} rule, the period it belongs to; otherwise {@code null}
     */
    public record Planned<T>(LocalDate scheduled, LocalDate due, PayPeriod ownPeriod, Version<T> version) {}

    private final ScheduleGenerator schedule;
    private final List<PayPeriod> periods;

    /** @param periods the pay periods {@code EVERY_PAY_PERIOD} rules follow, earliest first */
    public OccurrencePlanner(ScheduleGenerator schedule, List<PayPeriod> periods) {
        this.schedule = schedule;
        this.periods = List.copyOf(periods);
    }

    /** Every occurrence due within {@code from}–{@code to} (both inclusive), in date order. */
    public <T> List<Planned<T>> occurrences(List<Version<T>> versions, LocalDate from, LocalDate to) {
        List<Version<T>> ordered = versions.stream().sorted(Comparator.comparing(Version<T>::effectiveFrom)).toList();
        List<Planned<T>> planned = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            Version<T> version = ordered.get(i);
            // The earliest version has no lower bound: nothing came before it.
            LocalDate covers = i == 0 ? LocalDate.MIN : version.effectiveFrom();
            LocalDate until = i + 1 < ordered.size() ? ordered.get(i + 1).effectiveFrom() : LocalDate.MAX;
            for (Planned<T> occurrence : occurrences(version, from, to)) {
                if (!occurrence.scheduled().isBefore(covers) && occurrence.scheduled().isBefore(until)) {
                    planned.add(occurrence);
                }
            }
        }
        planned.sort(Comparator.comparing(Planned<T>::due).thenComparing(Planned::scheduled));
        return planned;
    }

    private <T> List<Planned<T>> occurrences(Version<T> version, LocalDate from, LocalDate to) {
        ScheduleRule rule = version.rule();
        List<Planned<T>> planned = new ArrayList<>();
        if (rule.frequency() != Frequency.EVERY_PAY_PERIOD) {
            for (Occurrence occurrence : schedule.occurrences(rule, from, to)) {
                planned.add(new Planned<>(occurrence.scheduled(), occurrence.date(), null, version));
            }
            return planned;
        }

        for (PayPeriod period : periods) {
            // A rule that starts part-way through a period still applies to it: a grocery
            // budget added today belongs to the paycheck already being spent.
            boolean started = !period.end().isBefore(rule.startDate());
            boolean ended = rule.endDate() != null && period.start().isAfter(rule.endDate());
            if (!started || ended) {
                continue;
            }
            LocalDate due = period.start().plusDays(version.daysAfterPayday());
            if (due.isAfter(period.end())) {
                due = period.end();
            }
            if (!due.isBefore(from) && !due.isAfter(to)) {
                planned.add(new Planned<>(period.scheduledPayday(), due, period, version));
            }
        }
        return planned;
    }
}
