package app.budget.server.income;

import app.budget.engine.Money;
import app.budget.engine.schedule.BusinessCalendar;
import app.budget.engine.schedule.Occurrence;
import app.budget.engine.schedule.PayPeriod;
import app.budget.engine.schedule.PayPeriodGenerator;
import app.budget.engine.schedule.ScheduleGenerator;
import app.budget.engine.schedule.ScheduleRule;
import app.budget.server.income.IncomeRepository.PaycheckOverride;
import app.budget.server.income.IncomeRepository.Source;
import app.budget.server.schedule.HolidaysRepository;
import app.budget.server.settings.SettingKey;
import app.budget.server.settings.SettingsRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Works out paychecks and pay periods from what is stored: the income sources, the holidays
 * left switched on, and single-paycheck edits. Nothing here is saved — it is computed on
 * every request by the engine, so it always reflects the current schedules.
 */
public final class PayCalendar {

    private final IncomeRepository income;
    private final HolidaysRepository holidays;
    private final SettingsRepository settings;
    private final Clock clock;

    public PayCalendar(IncomeRepository income, HolidaysRepository holidays, SettingsRepository settings, Clock clock) {
        this.income = income;
        this.holidays = holidays;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * One paycheck.
     *
     * @param depositAccountId the account it lands in
     * @param scheduledDate  the date the schedule names; identifies this paycheck for edits
     * @param payDate        when it actually arrives, after any weekend or holiday move
     * @param estimate       true while the amount is the source's estimate and has not been typed over
     * @param projectedCents what the app showed before the user typed over it; {@code null} if untouched
     */
    public record Paycheck(
            long incomeSourceId,
            String sourceName,
            long depositAccountId,
            LocalDate scheduledDate,
            LocalDate payDate,
            long netCents,
            boolean estimate,
            Long projectedCents) {}

    /** A pay period with the paychecks that land in it. */
    public record Period(
            LocalDate start,
            LocalDate end,
            LocalDate scheduledPayday,
            boolean current,
            long incomeCents,
            List<Paycheck> paychecks) {}

    /** @param primarySourceId {@code null} until an income source exists; then there are no periods either */
    public record Periods(Long primarySourceId, List<Period> periods) {}

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** The last day projections reach: today plus the projection horizon setting. */
    public LocalDate horizonEnd() {
        return today().plusMonths(Integer.parseInt(settings.get(SettingKey.PROJECTION_HORIZON_MONTHS)));
    }

    /** Every paycheck from every source arriving within {@code from}–{@code to}, in date order. */
    public List<Paycheck> paychecks(LocalDate from, LocalDate to) {
        return paychecks(income.all(), scheduleFor(from, to), from, to);
    }

    /** The pay periods overlapping {@code from}–{@code through}, defined by the primary source. */
    public Periods periods(LocalDate from, LocalDate through) {
        List<Source> sources = income.all();
        Optional<Source> primary = sources.stream().filter(Source::primary).findFirst();
        if (primary.isEmpty()) {
            return new Periods(null, List.of());
        }
        ScheduleGenerator schedule = scheduleFor(from, through);
        List<PayPeriod> periods = new PayPeriodGenerator(schedule).periods(primary.get().schedule(), from, through);
        if (periods.isEmpty()) {
            return new Periods(primary.get().id(), List.of());
        }

        // Other paychecks (a second job, say) land in whichever period of the primary source they fall in.
        List<Paycheck> paychecks = paychecks(sources, schedule, periods.getFirst().start(), periods.getLast().end());
        LocalDate today = today();
        List<Period> result = new ArrayList<>();
        for (PayPeriod period : periods) {
            List<Paycheck> inPeriod = paychecks.stream().filter(paycheck -> period.contains(paycheck.payDate())).toList();
            Money total = inPeriod.stream().map(paycheck -> Money.ofCents(paycheck.netCents())).reduce(Money.ZERO, Money::plus);
            result.add(new Period(
                    period.start(), period.end(), period.scheduledPayday(), period.contains(today), total.cents(), inPeriod));
        }
        return new Periods(primary.get().id(), result);
    }

    /** The day each income source's pay schedule starts. */
    public List<LocalDate> sourceStarts() {
        return income.all().stream().map(source -> source.schedule().startDate()).toList();
    }

    /** The primary source's schedule, which defines the pay periods; empty until a source exists. */
    public Optional<ScheduleRule> payRule() {
        return income.all().stream().filter(Source::primary).findFirst().map(Source::schedule);
    }

    /** The account the primary paycheck lands in: the one the pay period view is about. */
    public Optional<Long> primaryAccountId() {
        return income.all().stream().filter(Source::primary).findFirst().map(Source::depositAccountId);
    }

    /** The bare pay periods overlapping {@code from}–{@code through}, without their paychecks. */
    public List<PayPeriod> payPeriods(LocalDate from, LocalDate through) {
        return payRule()
                .map(rule -> new PayPeriodGenerator(scheduleFor(from, through)).periods(rule, from, through))
                .orElse(List.of());
    }

    /** True when {@code scheduledDate} is one of the dates the source's schedule names. */
    public boolean isScheduled(Source source, LocalDate scheduledDate) {
        // A move never spans more than a few days, so a month either side is ample.
        LocalDate from = scheduledDate.minusMonths(1);
        LocalDate to = scheduledDate.plusMonths(1);
        return scheduleFor(from, to).occurrences(source.schedule(), from, to).stream()
                .anyMatch(occurrence -> occurrence.scheduled().equals(scheduledDate));
    }

    private List<Paycheck> paychecks(List<Source> sources, ScheduleGenerator schedule, LocalDate from, LocalDate to) {
        // Edits are keyed by scheduled date, which can sit just outside the range its payday moved into.
        Map<Long, Map<LocalDate, PaycheckOverride>> overrides = income.overrides(from.minusMonths(1), to.plusMonths(1)).stream()
                .collect(Collectors.groupingBy(
                        PaycheckOverride::incomeSourceId,
                        Collectors.toMap(PaycheckOverride::scheduledDate, override -> override)));

        List<Paycheck> paychecks = new ArrayList<>();
        for (Source source : sources) {
            Map<LocalDate, PaycheckOverride> edited = overrides.getOrDefault(source.id(), Map.of());
            for (Occurrence occurrence : schedule.occurrences(source.schedule(), from, to)) {
                PaycheckOverride override = edited.get(occurrence.scheduled());
                paychecks.add(override == null
                        ? new Paycheck(source.id(), source.name(), source.depositAccountId(), occurrence.scheduled(), occurrence.date(),
                                source.netCents(), source.netIsEstimate(), null)
                        // An amount the user typed is the amount: it is no longer an estimate.
                        : new Paycheck(source.id(), source.name(), source.depositAccountId(), occurrence.scheduled(), occurrence.date(),
                                override.netCents(), false, override.projectedCents()));
            }
        }
        // Sources arrive primary-first, and the sort is stable, so the primary leads on a shared payday.
        paychecks.sort(Comparator.comparing(Paycheck::payDate));
        return paychecks;
    }

    /** A schedule generator that knows the closed days around the range, a year either side. */
    public ScheduleGenerator scheduleFor(LocalDate from, LocalDate to) {
        List<LocalDate> closed = holidays.closedDates(
                LocalDate.of(from.getYear() - 1, 1, 1), LocalDate.of(to.getYear() + 1, 12, 31));
        return new ScheduleGenerator(new BusinessCalendar(closed));
    }
}
