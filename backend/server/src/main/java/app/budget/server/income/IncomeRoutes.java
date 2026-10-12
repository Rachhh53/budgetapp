package app.budget.server.income;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.server.accounts.Account;
import app.budget.server.accounts.AccountType;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.income.IncomeRepository.Deduction;
import app.budget.server.income.IncomeRepository.Match;
import app.budget.server.income.IncomeRepository.MatchMode;
import app.budget.server.income.IncomeRepository.Pay;
import app.budget.server.income.IncomeRepository.Terms;
import java.time.Clock;
import app.budget.server.income.IncomeRepository.Source;
import app.budget.server.schedule.ScheduleDto;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Income sources and the paychecks they produce: {@code /api/income-sources} and
 * {@code /api/paychecks}. The pay periods the primary source defines are served by the ledger.
 */
public final class IncomeRoutes {

    private static final int NAME_MAX = 80;
    private static final int MAX_DEDUCTIONS = 30;

    /** 1,000%: beyond any real match, and a typo still fits so it can be shown and fixed. */
    private static final int MAX_MATCH_BP = 100_000;

    /** Nobody can be matched on more than all of their pay. */
    private static final int MAX_CAP_BP = 10_000;

    private final IncomeRepository income;
    private final AccountsRepository accounts;
    private final PayCalendar calendar;
    private final Clock clock;

    public IncomeRoutes(IncomeRepository income, AccountsRepository accounts, PayCalendar calendar, Clock clock) {
        this.income = income;
        this.accounts = accounts;
        this.calendar = calendar;
        this.clock = clock;
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
        IncomeRepository.Source source = income.find(id).orElseThrow(ApiException::notFound);
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
                gross(source, arrived).stream().mapToLong(Long::longValue).sum(),
                gross(source, arrived).size(),
                gross(source, inYear).stream().mapToLong(Long::longValue).sum(),
                gross(source, inYear).size()));
    }

    /**
     * The gross pay of each of these paychecks that has one, at the gross amount in force on the
     * day its schedule names (so a raise counts from its date). A paycheck from before a gross
     * amount was entered is left out rather than guessed at.
     */
    private static List<Long> gross(IncomeRepository.Source source, List<PayCalendar.Paycheck> paychecks) {
        return paychecks.stream()
                .map(paycheck -> source.termsOn(paycheck.scheduledDate()).pay().grossCents())
                .filter(java.util.Objects::nonNull)
                .toList();
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
        Long gross = input.grossCents() == null ? null : Inputs.amount(input.grossCents(), "The gross amount");
        long net = Inputs.amount(input.netCents(), "The take-home amount");
        if (input.schedule() == null) {
            throw ApiException.badRequest("Please choose how often you're paid.");
        }
        return new IncomeRepository.Fields(
                name,
                input.depositAccountId(),
                Boolean.TRUE.equals(input.primary()),
                input.schedule().toPayRule(),
                new Pay(
                        gross,
                        net,
                        Boolean.TRUE.equals(input.netIsEstimate()),
                        deductions(input.deductions(), gross),
                        match(input.match(), gross)));
    }

    private Match match(MatchInput input, Long grossCents) {
        if (input == null) {
            return null;
        }
        Account account = input.toAccountId() == null ? null : accounts.find(input.toAccountId()).orElse(null);
        // A match is retirement money: it goes into an investment account, never into spending money.
        if (account == null || !Inputs.code(AccountType.INVESTMENT).equals(account.type())) {
            throw ApiException.badRequest("Please choose the investment account the employer match is paid into.");
        }
        MatchMode mode = Inputs.choice(MatchMode.class, input.mode(), "how the match is worked out");
        if (mode == MatchMode.FIXED) {
            return new Match(account.id(), mode, Inputs.amount(input.fixedCents(), "The match amount"), null, null, false);
        }
        if (input.matchBp() == null || input.matchBp() < 0 || input.matchBp() > MAX_MATCH_BP) {
            throw ApiException.badRequest("Please enter how much of your contribution is matched, between 0% and 1,000%.");
        }
        if (input.capBpOfGross() == null || input.capBpOfGross() < 0 || input.capBpOfGross() > MAX_CAP_BP) {
            throw ApiException.badRequest("Please enter how much of your pay is matched up to, between 0% and 100%.");
        }
        // Without gross pay there is no checking a contribution against the cap, so the user's word is asked for.
        boolean confirmed = grossCents == null && Boolean.TRUE.equals(input.contributionWithinCap());
        if (grossCents == null && !confirmed) {
            throw ApiException.badRequest(
                    "Without the gross amount of a paycheck, there is no telling whether what you put in is within the share of"
                            + " your pay that is matched. Please fill in the gross amount, or confirm that it is.");
        }
        return new Match(account.id(), mode, null, input.matchBp(), input.capBpOfGross(), confirmed);
    }

    private List<Deduction> deductions(List<DeductionInput> inputs, Long grossCents) {
        if (inputs == null) {
            return List.of();
        }
        if (inputs.size() > MAX_DEDUCTIONS || inputs.contains(null)) {
            throw ApiException.badRequest("Please keep to " + MAX_DEDUCTIONS + " paycheck deductions or fewer.");
        }
        List<Deduction> deductions = new ArrayList<>();
        for (DeductionInput input : inputs) {
            if (input.toAccountId() != null && !accounts.exists(input.toAccountId())) {
                throw ApiException.badRequest("Please choose the account each deduction goes to from the list.");
            }
            String name = Inputs.text(input.name(), "a name for each deduction", NAME_MAX);
            if (input.percentBp() == null) {
                deductions.add(new Deduction(name, Inputs.amount(input.amountCents(), "A deduction amount"), input.toAccountId(), null));
                continue;
            }
            if (input.percentBp() < 0 || input.percentBp() > MAX_CAP_BP) {
                throw ApiException.badRequest("Please enter each deduction's share of your pay between 0% and 100%.");
            }
            if (grossCents == null) {
                throw ApiException.badRequest(
                        "A deduction given as a percent of your pay needs the gross amount of a paycheck. Please fill that in as well.");
            }
            // Kept in cents as well, so a paycheck is worked out the same way whichever way it was typed.
            long amount = Money.ofCents(grossCents).times(BasisPoints.of(input.percentBp()).toFraction()).cents();
            deductions.add(new Deduction(name, amount, input.toAccountId(), input.percentBp()));
        }
        return deductions;
    }
}
