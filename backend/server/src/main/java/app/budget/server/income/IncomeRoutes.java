package app.budget.server.income;

import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.income.IncomeRepository.Deduction;
import app.budget.server.income.IncomeRepository.Match;
import app.budget.server.income.IncomeRepository.Pay;
import app.budget.server.income.IncomeRepository.Terms;
import java.time.Clock;
import app.budget.server.income.IncomeRepository.Source;
import app.budget.server.schedule.ScheduleDto;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.time.LocalDate;
import java.util.List;

/**
 * Income sources and the paychecks they produce: {@code /api/income-sources} and
 * {@code /api/paychecks}. The pay periods the primary source defines are served by the ledger.
 */
public final class IncomeRoutes {

    private static final int NAME_MAX = 80;

    private final IncomeRepository income;
    private final AccountsRepository accounts;
    private final PayCalendar calendar;
    private final Clock clock;
    private final PayInputs payInputs;

    public IncomeRoutes(IncomeRepository income, AccountsRepository accounts, PayCalendar calendar, Clock clock) {
        this.income = income;
        this.accounts = accounts;
        this.calendar = calendar;
        this.clock = clock;
        this.payInputs = new PayInputs(accounts);
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/income-sources", ctx -> {
            LocalDate today = LocalDate.now(clock);
            ApiResponse.ok(ctx, income.all().stream().map(source -> SourceView.of(source, today)).toList());
        });
        routes.post("/api/income-sources", this::create);
        routes.put("/api/income-sources/{id}", this::update);
        routes.delete("/api/income-sources/{id}", this::delete);
        routes.put("/api/income-sources/{id}/primary", this::makePrimary);
        routes.delete("/api/income-sources/{id}/pay-changes/{changeId}", this::deletePayChange);
        routes.get("/api/income-sources/{id}/this-year", this::thisYear);
        routes.put("/api/income-sources/{id}/paychecks/{scheduledDate}", this::editPaycheck);
        routes.get("/api/paychecks", this::paychecks);
    }

    /**
     * Request body for creating or changing an income source. Amounts are whole cents.
     *
     * @param effectiveFrom   only when changing: the amounts are a change of pay from this day on
     *                        (a raise), and earlier paychecks keep what they had
     * @param originalAmounts only when changing, and without {@code effectiveFrom}: the amounts
     *                        correct the ones the source started with, rather than the ones in force today
     */
    public record SourceInput(
            String name,
            Long depositAccountId,
            Long grossCents,
            Long netCents,
            Boolean netIsEstimate,
            Boolean primary,
            ScheduleDto schedule,
            List<DeductionInput> deductions,
            MatchInput match,
            LocalDate effectiveFrom,
            Boolean originalAmounts) {}

    /**
     * One deduction: a dollar amount ({@code amountCents}), or a share of gross pay
     * ({@code percentBp}: 6% is 600), which needs the source's gross amount.
     */
    public record DeductionInput(String name, Long amountCents, Long toAccountId, Integer percentBp) {}

    /**
     * Request body for the employer's match; left out (or {@code null}) for none. {@code mode} is
     * {@code fixed} (with {@code fixedCents}) or {@code formula} (with {@code matchBp} and
     * {@code capBpOfGross}: 50% up to 6% of pay is 5000 and 600). A formula needs the source's
     * gross amount to check the contribution against; without one it is only taken with
     * {@code contributionWithinCap}, the user's word that what they put in is within that share.
     */
    public record MatchInput(
            Long toAccountId, String mode, Long fixedCents, Integer matchBp, Integer capBpOfGross, Boolean contributionWithinCap) {}

    /**
     * An income source as the API returns it.
     *
     * @param match        {@code null} when the employer adds nothing
     * @param matchFigures what the match comes to per paycheck; {@code null} without a match
     */
    public record SourceView(
            long id,
            String name,
            long depositAccountId,
            Long grossCents,
            long netCents,
            boolean netIsEstimate,
            boolean primary,
            ScheduleDto schedule,
            List<Deduction> deductions,
            Match match,
            PayCalendar.MatchFigures matchFigures,
            List<PayTermsView> payHistory) {

        /** The source with the amounts in force on {@code today} at the top level, and all of them in {@code payHistory}. */
        static SourceView of(Source source, LocalDate today) {
            Terms current = source.termsOn(today);
            Pay pay = current.pay();
            return new SourceView(
                    source.id(), source.name(), source.depositAccountId(), pay.grossCents(), pay.netCents(),
                    pay.netIsEstimate(), source.primary(), ScheduleDto.of(source.schedule()), pay.deductions(),
                    pay.match(), PayCalendar.matchFigures(pay).orElse(null),
                    source.terms().stream().map(terms -> PayTermsView.of(terms, terms == current)).toList());
        }
    }

    /**
     * What a source's paychecks come to from a date on: the amounts it started with, or a later
     * change of pay (a raise).
     *
     * @param id            the change of pay; {@code null} for the amounts the source started with
     * @param effectiveDate the first day these apply; {@code null} for the amounts the source started with
     * @param current       these are the amounts in force today
     */
    public record PayTermsView(
            Long id,
            LocalDate effectiveDate,
            Long grossCents,
            long netCents,
            boolean netIsEstimate,
            List<Deduction> deductions,
            Match match,
            PayCalendar.MatchFigures matchFigures,
            boolean current) {

        static PayTermsView of(Terms terms, boolean current) {
            Pay pay = terms.pay();
            return new PayTermsView(
                    terms.changeId(), terms.effectiveDate(), pay.grossCents(), pay.netCents(), pay.netIsEstimate(),
                    pay.deductions(), pay.match(), PayCalendar.matchFigures(pay).orElse(null), current);
        }
    }

    /**
     * What a source brings home in the calendar year of today, counting each paycheck in the
     * year it arrives. Raises and typed-over paychecks are in the figures, since they are in the
     * paychecks.
     *
     * @param soFarCents     take-home from the paychecks that have arrived, today's included
     * @param wholeYearCents take-home from every paycheck of the year, those still to come included
     * @param estimate       at least one paycheck still to come is an estimate
     * @param grossSoFarCents      gross pay of the paychecks that have arrived and have a gross amount entered
     * @param grossPaychecksSoFar  how many of them that is: fewer than {@code paychecksSoFar} when some have none
     * @param grossWholeYearCents  gross pay of every paycheck of the year that has a gross amount entered
     * @param grossPaychecksInYear how many of them that is
     */
    public record ThisYear(
            int year,
            long soFarCents,
            int paychecksSoFar,
            long wholeYearCents,
            int paychecksInYear,
            boolean estimate,
            long grossSoFarCents,
            int grossPaychecksSoFar,
            long grossWholeYearCents,
            int grossPaychecksInYear) {}

    /** Request body for typing over one paycheck's amount. */
    public record PaycheckInput(Long netCents) {}

    public record Done(boolean done) {}

    private void create(Context ctx) {
        long id = income.create(fields(Inputs.body(ctx, SourceInput.class)));
        ApiResponse.send(ctx, 201, view(id));
    }

    /**
     * Changes a source. Its name, account and schedule always change as a whole; which paychecks
     * the amounts reach depends on what the request says they are:
     * {@code effectiveFrom} — a change of pay from that day on, earlier paychecks keeping theirs;
     * {@code originalAmounts} — a correction of the amounts the source started with;
     * neither — a correction of the amounts in force today.
     */
    private void update(Context ctx) {
        long id = Inputs.id(ctx, "id");
        SourceInput input = Inputs.body(ctx, SourceInput.class);
        IncomeRepository.Fields fields = fields(input);
        LocalDate from = input.effectiveFrom();
        if (from != null && !Inputs.inSupportedYears(from)) {
            throw ApiException.badRequest("Please choose the date your pay changes from.");
        }
        Source existing = income.find(id).orElseThrow(ApiException::notFound);
        if (existing.primary() && !fields.primary()) {
            // Pay periods come from the primary source, so there must always be one.
            throw ApiException.badRequest(
                    "One income source needs to define your pay periods. To change which, make another source the primary one.");
        }
        if (from == null) {
            Terms corrected = Boolean.TRUE.equals(input.originalAmounts())
                    ? existing.terms().get(0)
                    : existing.termsOn(LocalDate.now(clock));
            income.update(id, fields, corrected.changeId());
        } else if (existing.termsOn(from).pay().equals(fields.pay())) {
            // Nothing about the pay differs from what that day already has, so there is no change of pay to record.
            income.update(id, fields, existing.termsOn(from).changeId());
        } else {
            income.updateFrom(id, fields, from);
        }
        ApiResponse.ok(ctx, view(id));
    }

    private void thisYear(Context ctx) {
        long id = Inputs.id(ctx, "id");
        income.find(id).orElseThrow(ApiException::notFound);
        LocalDate today = LocalDate.now(clock);
        List<PayCalendar.Paycheck> inYear = calendar.paychecks(today.withDayOfYear(1), today.withDayOfYear(today.lengthOfYear())).stream()
                .filter(paycheck -> paycheck.incomeSourceId() == id)
                .toList();
        List<PayCalendar.Paycheck> arrived = inYear.stream().filter(paycheck -> !paycheck.payDate().isAfter(today)).toList();
        ApiResponse.ok(ctx, new ThisYear(
                today.getYear(),
                arrived.stream().mapToLong(PayCalendar.Paycheck::netCents).sum(),
                arrived.size(),
                inYear.stream().mapToLong(PayCalendar.Paycheck::netCents).sum(),
                inYear.size(),
                inYear.stream().anyMatch(paycheck -> paycheck.payDate().isAfter(today) && paycheck.estimate()),
                gross(arrived).stream().mapToLong(Long::longValue).sum(),
                gross(arrived).size(),
                gross(inYear).stream().mapToLong(Long::longValue).sum(),
                gross(inYear).size()));
    }

    /**
     * The gross pay of each of these paychecks that has one, at the gross amount in force on the
     * day its schedule names (so a raise counts from its date). A paycheck from before a gross
     * amount was entered is left out rather than guessed at.
     */
    private static List<Long> gross(List<PayCalendar.Paycheck> paychecks) {
        // A one-off paycheck (a bonus) carries its own gross; a regular one the amount in force on its day.
        return paychecks.stream().map(PayCalendar.Paycheck::grossCents).filter(java.util.Objects::nonNull).toList();
    }

    /** "Pay periods follow" in Settings: hands the role to this source without touching anything else about it. */
    private void makePrimary(Context ctx) {
        long id = Inputs.id(ctx, "id");
        if (!income.makePrimary(id)) {
            throw ApiException.notFound();
        }
        ApiResponse.ok(ctx, view(id));
    }

    private void deletePayChange(Context ctx) {
        long id = Inputs.id(ctx, "id");
        if (!income.deletePayChange(id, Inputs.id(ctx, "changeId"))) {
            throw ApiException.notFound();
        }
        ApiResponse.ok(ctx, view(id));
    }

    private void delete(Context ctx) {
        long id = Inputs.id(ctx, "id");
        Source existing = income.find(id).orElseThrow(ApiException::notFound);
        if (existing.primary() && income.count() > 1) {
            throw ApiException.conflict(
                    "This source defines your pay periods. Make another source the primary one first, then remove this one.");
        }
        income.delete(id);
        ApiResponse.ok(ctx, new Done(true));
    }

    private void editPaycheck(Context ctx) {
        long id = Inputs.id(ctx, "id");
        LocalDate scheduledDate = Inputs.date(ctx.pathParam("scheduledDate"));
        long net = Inputs.amount(Inputs.body(ctx, PaycheckInput.class).netCents(), "The paycheck amount");
        Source source = income.find(id).orElseThrow(ApiException::notFound);
        if (!Inputs.inSupportedYears(scheduledDate) || !calendar.isScheduled(source, scheduledDate)) {
            throw ApiException.notFound();
        }
        income.saveOverride(id, scheduledDate, net, source.termsOn(scheduledDate).pay().netCents());
        ApiResponse.ok(ctx, new Done(true));
    }

    /** Paychecks between {@code from} and {@code to}; by default from today to the projection horizon. */
    private void paychecks(Context ctx) {
        LocalDate from = Inputs.dateParam(ctx, "from", calendar.today());
        LocalDate to = Inputs.dateParam(ctx, "to", calendar.horizonEnd());
        Inputs.requireSensibleRange(from, to);
        ApiResponse.ok(ctx, calendar.paychecks(from, to));
    }

    private SourceView view(long id) {
        LocalDate today = LocalDate.now(clock);
        return income.find(id).map(source -> SourceView.of(source, today)).orElseThrow(ApiException::notFound);
    }

    private IncomeRepository.Fields fields(SourceInput input) {
        String name = Inputs.text(input.name(), "a name for this income", NAME_MAX);
        if (input.depositAccountId() == null || !accounts.exists(input.depositAccountId())) {
            throw ApiException.badRequest("Please choose the account this pay is deposited to.");
        }
        Pay pay = payInputs.read(input.grossCents(), input.netCents(), input.netIsEstimate(), input.deductions(), input.match());
        if (input.schedule() == null) {
            throw ApiException.badRequest("Please choose how often you're paid.");
        }
        return new IncomeRepository.Fields(
                name,
                input.depositAccountId(),
                Boolean.TRUE.equals(input.primary()),
                input.schedule().toPayRule(),
                pay);
    }
}
