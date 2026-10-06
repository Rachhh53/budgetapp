package app.budget.server.income;

import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.income.IncomeRepository.Deduction;
import app.budget.server.income.IncomeRepository.Source;
import app.budget.server.schedule.ScheduleDto;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Income sources, the paychecks they produce and the pay periods the primary one defines:
 * {@code /api/income-sources}, {@code /api/paychecks} and {@code /api/pay-periods}.
 */
public final class IncomeRoutes {

    private static final int NAME_MAX = 80;
    private static final int MAX_DEDUCTIONS = 30;

    private final IncomeRepository income;
    private final AccountsRepository accounts;
    private final PayCalendar calendar;

    public IncomeRoutes(IncomeRepository income, AccountsRepository accounts, PayCalendar calendar) {
        this.income = income;
        this.accounts = accounts;
        this.calendar = calendar;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/income-sources", ctx -> ApiResponse.ok(ctx, income.all().stream().map(SourceView::of).toList()));
        routes.post("/api/income-sources", this::create);
        routes.put("/api/income-sources/{id}", this::update);
        routes.delete("/api/income-sources/{id}", this::delete);
        routes.put("/api/income-sources/{id}/paychecks/{scheduledDate}", this::editPaycheck);
        routes.get("/api/paychecks", this::paychecks);
        routes.get("/api/pay-periods", this::payPeriods);
    }

    /** Request body for creating or changing an income source. Amounts are whole cents. */
    public record SourceInput(
            String name,
            Long depositAccountId,
            Long grossCents,
            Long netCents,
            Boolean netIsEstimate,
            Boolean primary,
            ScheduleDto schedule,
            List<DeductionInput> deductions) {}

    public record DeductionInput(String name, Long amountCents, Long toAccountId) {}

    /** An income source as the API returns it. */
    public record SourceView(
            long id,
            String name,
            long depositAccountId,
            Long grossCents,
            long netCents,
            boolean netIsEstimate,
            boolean primary,
            ScheduleDto schedule,
            List<Deduction> deductions) {

        static SourceView of(Source source) {
            return new SourceView(
                    source.id(), source.name(), source.depositAccountId(), source.grossCents(), source.netCents(),
                    source.netIsEstimate(), source.primary(), ScheduleDto.of(source.schedule()), source.deductions());
        }
    }

    /** Request body for typing over one paycheck's amount. */
    public record PaycheckInput(Long netCents) {}

    public record Done(boolean done) {}

    private void create(Context ctx) {
        long id = income.create(fields(Inputs.body(ctx, SourceInput.class)));
        ApiResponse.send(ctx, 201, view(id));
    }

    private void update(Context ctx) {
        long id = Inputs.id(ctx, "id");
        IncomeRepository.Fields fields = fields(Inputs.body(ctx, SourceInput.class));
        Source existing = income.find(id).orElseThrow(ApiException::notFound);
        if (existing.primary() && !fields.primary()) {
            // Pay periods come from the primary source, so there must always be one.
            throw ApiException.badRequest(
                    "One income source needs to define your pay periods. To change which, make another source the primary one.");
        }
        income.update(id, fields);
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
        income.saveOverride(id, scheduledDate, net, source.netCents());
        ApiResponse.ok(ctx, new Done(true));
    }

    /** Paychecks between {@code from} and {@code to}; by default from today to the projection horizon. */
    private void paychecks(Context ctx) {
        LocalDate from = Inputs.dateParam(ctx, "from", calendar.today());
        LocalDate to = Inputs.dateParam(ctx, "to", calendar.horizonEnd());
        Inputs.requireSensibleRange(from, to);
        ApiResponse.ok(ctx, calendar.paychecks(from, to));
    }

    /** Pay periods overlapping {@code from}–{@code to}; by default the current one through the horizon. */
    private void payPeriods(Context ctx) {
        LocalDate from = Inputs.dateParam(ctx, "from", calendar.today());
        LocalDate to = Inputs.dateParam(ctx, "to", calendar.horizonEnd());
        Inputs.requireSensibleRange(from, to);
        ApiResponse.ok(ctx, calendar.periods(from, to));
    }

    private SourceView view(long id) {
        return income.find(id).map(SourceView::of).orElseThrow(ApiException::notFound);
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
                gross,
                net,
                Boolean.TRUE.equals(input.netIsEstimate()),
                Boolean.TRUE.equals(input.primary()),
                input.schedule().toPayRule(),
                deductions(input.deductions()));
    }

    private List<Deduction> deductions(List<DeductionInput> inputs) {
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
            deductions.add(new Deduction(
                    Inputs.text(input.name(), "a name for each deduction", NAME_MAX),
                    Inputs.amount(input.amountCents(), "A deduction amount"),
                    input.toAccountId()));
        }
        return deductions;
    }
}
