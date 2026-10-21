package app.budget.server.income;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.investments.EmployerMatch;
import app.budget.engine.schedule.BusinessCalendar;
import app.budget.engine.schedule.Occurrence;
import app.budget.engine.schedule.PayPeriod;
import app.budget.engine.schedule.PayPeriodGenerator;
import app.budget.engine.schedule.ScheduleGenerator;
import app.budget.engine.schedule.ScheduleRule;
import app.budget.server.income.IncomeRepository.Deduction;
import app.budget.server.income.IncomeRepository.Match;
import app.budget.server.income.IncomeRepository.MatchMode;
import app.budget.server.income.IncomeRepository.Pay;
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
    private final OneOffPaychecksRepository oneOffs;
    private final HolidaysRepository holidays;
    private final SettingsRepository settings;
    private final Clock clock;

    public PayCalendar(
            IncomeRepository income, OneOffPaychecksRepository oneOffs, HolidaysRepository holidays, SettingsRepository settings, Clock clock) {
        this.income = income;
        this.oneOffs = oneOffs;
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
     * @param contributions  what goes straight into other accounts with this paycheck, never passing
     *                       through the deposit account: deductions that name an account, and the employer's match
     * @param grossCents     its gross pay; {@code null} when only take-home pay is known
     * @param deductions     what was taken out of it, its contributions among them
     * @param oneOffId       set for a one-off paycheck (a bonus), which is on no schedule; {@code null} for
     *                       one the schedule names. A one-off's scheduled date is its pay date.
     * @param name           a one-off paycheck's own name ("Q3 bonus"); {@code null} for a regular one
     */
    public record Paycheck(
            long incomeSourceId,
            String sourceName,
            long depositAccountId,
            LocalDate scheduledDate,
            LocalDate payDate,
            long netCents,
            boolean estimate,
            Long projectedCents,
            List<Contribution> contributions,
            Long grossCents,
            List<Deduction> deductions,
            Long oneOffId,
            String name) {

        /** A regular paycheck, the one its source's schedule names on that day. */
        public boolean scheduled() {
            return oneOffId == null;
        }

        /** "Northwind Health", or "Q3 bonus · Northwind Health" for a one-off: how it is listed. */
        public String label() {
            return name == null ? sourceName : name + " · " + sourceName;
        }
    }

    /** A contribution's {@code kind} when it is the employee's own money. */
    public static final String CONTRIBUTION = "contribution";

    /** A contribution's {@code kind} when the employer adds it. */
    public static final String EMPLOYER_MATCH = "employer_match";

    private static final String MATCH_LABEL = "Employer match";

    /**
     * Money a paycheck puts into another account before take-home pay is worked out.
     *
     * @param kind {@link #CONTRIBUTION} or {@link #EMPLOYER_MATCH}
     */
    public record Contribution(long toAccountId, String label, String kind, long amountCents) {}

    /**
     * What a source's employer match comes to, for showing beside it.
     *
     * @param perPaycheckCents    what the employer adds to each paycheck as things stand
     * @param mostPossibleCents   what they would add if the employee contributed up to the cap
     * @param contributeMoreCents how much more the employee would have to put in, per paycheck, to
     *                            earn all of it; zero at or over the cap, and for a fixed match
     */
    public record MatchFigures(long perPaycheckCents, long mostPossibleCents, long contributeMoreCents) {}

    /**
     * The figures for the match on a paycheck that comes to {@code pay}; empty when it has none. A formula with no gross pay to
     * work from stands only on the user's word that their contribution is within the matched
     * share: all of it is then matched, and that is also the most it is known to come to.
     */
    public static Optional<MatchFigures> matchFigures(Pay pay) {
        Match match = pay.match();
        boolean grossUnknown = match != null && match.mode() == MatchMode.FORMULA && pay.grossCents() == null;
        if (match == null || (grossUnknown && !match.contributionWithinCap())) {
            return Optional.empty();
        }
        EmployerMatch terms = match.mode() == MatchMode.FIXED
                ? EmployerMatch.fixed(Money.ofCents(match.fixedCents()))
                : EmployerMatch.formula(BasisPoints.of(match.matchBp()), BasisPoints.of(match.capBpOfGross()));
        Money gross = Money.ofCents(pay.grossCents() == null ? 0 : pay.grossCents());
        // What the employee puts in is whatever their deductions send to the account the match goes to.
        Money employee = pay.deductions().stream()
                .filter(deduction -> deduction.toAccountId() != null && deduction.toAccountId() == match.toAccountId())
                .map(deduction -> Money.ofCents(deduction.amountCents()))
                .reduce(Money.ZERO, Money::plus);
        if (grossUnknown) {
            long matched = terms.forContributionWithinCap(employee).cents();
            return Optional.of(new MatchFigures(matched, matched, 0));
        }
        return Optional.of(new MatchFigures(
                terms.forPaycheck(gross, employee).cents(),
                terms.mostPossible(gross).cents(),
                terms.contributionShortOfCap(gross, employee).cents()));
    }

    /** Everything a paycheck that comes to {@code pay} sends straight to other accounts. */
    static List<Contribution> contributions(Pay pay) {
        List<Contribution> contributions = new ArrayList<>();
        for (Deduction deduction : pay.deductions()) {
            if (deduction.toAccountId() != null && deduction.amountCents() > 0) {
                contributions.add(new Contribution(deduction.toAccountId(), deduction.name(), CONTRIBUTION, deduction.amountCents()));
            }
        }
        matchFigures(pay)
                .filter(figures -> figures.perPaycheckCents() > 0)
                .ifPresent(figures -> contributions.add(
                        new Contribution(pay.match().toAccountId(), MATCH_LABEL, EMPLOYER_MATCH, figures.perPaycheckCents())));
        return List.copyOf(contributions);
    }

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
                // A raise takes over from the first paycheck whose schedule names its date or a later one.
                Pay pay = source.termsOn(occurrence.scheduled()).pay();
                // A typed-over take-home amount does not change what was deducted.
                List<Contribution> contributions = contributions(pay);
                PaycheckOverride override = edited.get(occurrence.scheduled());
                paychecks.add(override == null
                        ? new Paycheck(source.id(), source.name(), source.depositAccountId(), occurrence.scheduled(), occurrence.date(),
                                pay.netCents(), pay.netIsEstimate(), null, contributions, pay.grossCents(), pay.deductions(), null, null)
                        // An amount the user typed is the amount: it is no longer an estimate.
                        : new Paycheck(source.id(), source.name(), source.depositAccountId(), occurrence.scheduled(), occurrence.date(),
                                override.netCents(), false, override.projectedCents(), contributions, pay.grossCents(), pay.deductions(),
                                null, null));
            }
        }
        Map<Long, Source> byId = sources.stream().collect(Collectors.toMap(Source::id, source -> source));
        for (OneOffPaychecksRepository.OneOff oneOff : oneOffs.between(from, to)) {
            Source source = byId.get(oneOff.incomeSourceId());
            if (source != null) {
                Pay pay = payOf(oneOff, source);
                paychecks.add(new Paycheck(source.id(), source.name(), source.depositAccountId(), oneOff.payDate(), oneOff.payDate(),
                        pay.netCents(), false, null, contributions(pay), pay.grossCents(), pay.deductions(), oneOff.id(), oneOff.name()));
            }
        }
        // Sources arrive primary-first, and the sort is stable, so the primary leads on a shared payday, and a
        // one-off paycheck follows the regular one it shares a day with.
        paychecks.sort(Comparator.comparing(Paycheck::payDate));
        return paychecks;
    }

    /**
     * What a one-off paycheck comes to, as a paycheck's figures: its own gross, take-home and
     * deductions, and the employer's match by the source's own rule on the day it is paid, when it
     * is matched. A formula is then worked out on this paycheck's gross and what it puts in.
     */
    public static Pay payOf(OneOffPaychecksRepository.OneOff oneOff, Source source) {
        Match match = oneOff.matched() ? source.termsOn(oneOff.payDate()).pay().match() : null;
        return new Pay(oneOff.grossCents(), oneOff.netCents(), false, oneOff.deductions(), match);
    }

    /** A schedule generator that knows the closed days around the range, a year either side. */
    public ScheduleGenerator scheduleFor(LocalDate from, LocalDate to) {
        List<LocalDate> closed = holidays.closedDates(
                LocalDate.of(from.getYear() - 1, 1, 1), LocalDate.of(to.getYear() + 1, 12, 31));
        return new ScheduleGenerator(new BusinessCalendar(closed));
    }
}
