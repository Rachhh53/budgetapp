package app.budget.server.income;

import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.income.IncomeRepository.Deduction;
import app.budget.server.income.IncomeRepository.Pay;
import app.budget.server.income.IncomeRepository.Source;
import app.budget.server.income.IncomeRoutes.DeductionInput;
import app.budget.server.income.OneOffPaychecksRepository.OneOff;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.time.LocalDate;
import java.util.List;

/**
 * One-off paychecks (spec §4.3): {@code /api/income-sources/{id}/one-off-paychecks}. A bonus, or any
 * extra pay, from an income source that arrives once on its own day. Once added it is a paycheck
 * like the others everywhere money is followed: its take-home lands in the source's account, its
 * deductions and match in theirs, and it counts in the source's year and in income reports.
 */
public final class OneOffPaychecksRoutes {

    private static final int NAME_MAX = DeductionInputs.NAME_MAX;

    private final IncomeRepository income;
    private final OneOffPaychecksRepository oneOffs;
    private final DeductionInputs deductionInputs;

    public OneOffPaychecksRoutes(IncomeRepository income, OneOffPaychecksRepository oneOffs, AccountsRepository accounts) {
        this.income = income;
        this.oneOffs = oneOffs;
        this.deductionInputs = new DeductionInputs(accounts);
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/income-sources/{id}/one-off-paychecks", this::list);
        routes.post("/api/income-sources/{id}/one-off-paychecks", this::create);
        routes.put("/api/income-sources/{id}/one-off-paychecks/{oneOffId}", this::update);
        routes.delete("/api/income-sources/{id}/one-off-paychecks/{oneOffId}", this::delete);
    }

    /**
     * Request body for adding or changing a one-off paycheck. Amounts are whole cents.
     *
     * @param grossCents optional, as for a source; a deduction given as a share of pay needs it
     * @param matched    add the employer's match by the source's own rule; left out means yes
     */
    public record OneOffInput(
            LocalDate payDate, String name, Long grossCents, Long netCents, List<DeductionInput> deductions, Boolean matched) {}

    /**
     * A one-off paycheck as the API returns it.
     *
     * @param matchCents what the employer adds to it by the source's rule; {@code null} when nothing
     *                   is matched (not asked for, no match on the source, or no gross pay to work a
     *                   percentage out on)
     * @param payStubId  the stub it was saved from, if any
     */
    public record OneOffView(
            long id,
            long incomeSourceId,
            LocalDate payDate,
            String name,
            Long grossCents,
            long netCents,
            List<Deduction> deductions,
            boolean matched,
            Long matchCents,
            Long payStubId) {

        static OneOffView of(OneOff oneOff, Source source) {
            Pay pay = PayCalendar.payOf(oneOff, source);
            Long matchCents = PayCalendar.matchFigures(pay).map(PayCalendar.MatchFigures::perPaycheckCents).orElse(null);
            return new OneOffView(
                    oneOff.id(), source.id(), oneOff.payDate(), oneOff.name(), oneOff.grossCents(), oneOff.netCents(),
                    oneOff.deductions(), oneOff.matched(), matchCents, oneOff.payStubId());
        }
    }

    public record Done(boolean done) {}

    private void list(Context ctx) {
        Source source = source(ctx);
        ApiResponse.ok(ctx, oneOffs.forSource(source.id()).stream().map(oneOff -> OneOffView.of(oneOff, source)).toList());
    }

    private void create(Context ctx) {
        Source source = source(ctx);
        long id = oneOffs.create(source.id(), fields(Inputs.body(ctx, OneOffInput.class)), null);
        ApiResponse.send(ctx, 201, OneOffView.of(oneOffs.find(id).orElseThrow(), source));
    }

    private void update(Context ctx) {
        Source source = source(ctx);
        OneOff existing = oneOff(ctx, source);
        oneOffs.update(existing.id(), fields(Inputs.body(ctx, OneOffInput.class)));
        ApiResponse.ok(ctx, OneOffView.of(oneOffs.find(existing.id()).orElseThrow(), source));
    }

    private void delete(Context ctx) {
        Source source = source(ctx);
        oneOffs.delete(oneOff(ctx, source).id());
        ApiResponse.ok(ctx, new Done(true));
    }

    private Source source(Context ctx) {
        return income.find(Inputs.id(ctx, "id")).orElseThrow(ApiException::notFound);
    }

    /** The one-off paycheck named in the path, which must belong to {@code source}. */
    private OneOff oneOff(Context ctx, Source source) {
        return oneOffs.find(Inputs.id(ctx, "oneOffId"))
                .filter(found -> found.incomeSourceId() == source.id())
                .orElseThrow(ApiException::notFound);
    }

    private OneOffPaychecksRepository.Fields fields(OneOffInput input) {
        if (input.payDate() == null || !Inputs.inSupportedYears(input.payDate())) {
            throw ApiException.badRequest("Please choose the day it was paid.");
        }
        String name = Inputs.text(input.name(), "a name for this paycheck, like Year-end bonus", NAME_MAX);
        Long gross = input.grossCents() == null ? null : Inputs.amount(input.grossCents(), "The gross amount");
        long net = Inputs.amount(input.netCents(), "The take-home amount");
        return new OneOffPaychecksRepository.Fields(
                input.payDate(), name, gross, net, deductionInputs.read(input.deductions(), gross), !Boolean.FALSE.equals(input.matched()));
    }
}
