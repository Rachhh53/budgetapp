package app.budget.server.plans;

import app.budget.engine.plans.PlanSuggestions.Basis;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.income.IncomeRoutes.DeductionInput;
import app.budget.server.income.IncomeRoutes.MatchInput;
import app.budget.server.income.PayInputs;
import app.budget.server.items.ItemsRepository;
import app.budget.server.plans.PlanService.Step;
import app.budget.server.plans.PlansRepository.Raise;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.util.List;

/**
 * Plan next year (spec §8): {@code /api/plans} for the plans themselves, the amounts typed into a
 * draft, a whole paycheck planned for next year, and first-time setup; {@code GET
 * /api/items/{id}/plan-suggestion} for what a plan would suggest for one bill or budget.
 */
public final class PlansRoutes {

    /** What the request must send to delete a draft, so it cannot happen by accident. */
    static final String DELETE_CONFIRMATION = "delete-draft";

    private final PlanService service;
    private final ItemsRepository items;
    private final PayInputs payInputs;

    public PlansRoutes(PlanService service, ItemsRepository items, PayInputs payInputs) {
        this.service = service;
        this.items = items;
        this.payInputs = payInputs;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/plans", ctx -> ApiResponse.ok(ctx, service.overview()));
        routes.post("/api/plans", this::start);
        routes.get("/api/plans/{id}", this::one);
        routes.put("/api/plans/{id}", this::update);
        routes.delete("/api/plans/{id}", this::delete);
        routes.put("/api/plans/{id}/amounts", this::setAmount);
        routes.put("/api/plans/{id}/income/{sourceId}/pay", this::setPay);
        routes.post("/api/plans/{id}/accept-suggestions", this::acceptSuggestions);
        routes.post("/api/plans/{id}/finish", this::finish);
        routes.get("/api/items/{id}/plan-suggestion", this::suggestion);
    }

    /** @param kind {@code next_year} (the default) for a plan of the coming year, {@code setup} for first-time setup */
    public record StartInput(String kind) {}

    /**
     * @param basis   {@code last_12}, {@code last_12_trend} or {@code prior_year}
     * @param refresh true to work the suggestions out again as of today
     */
    public record UpdateInput(String basis, Boolean refresh) {}

    /**
     * @param kind        {@code item} or {@code income}
     * @param id          the recurring item or income source
     * @param amountCents the amount for the year (an item) or one paycheck (income); {@code null} to follow the suggestion again
     */
    public record AmountInput(String kind, Long id, Long amountCents) {}

    /**
     * A whole paycheck for the plan's year, read as an income source's amounts are: whole cents, a
     * deduction as dollars or a share of gross, and the match left out for none. To go back to the
     * pay as it stands, the amount is cleared ({@code PUT /amounts} with {@code amountCents: null}).
     */
    public record PayInput(
            Long grossCents, Long netCents, Boolean netIsEstimate, List<DeductionInput> deductions, MatchInput match, RaiseInput raise) {}

    /**
     * A raise partway through the plan's year; left out for none.
     *
     * @param from the first day of the raise
     * @param bp   the raise in basis points, 300 for 3%
     */
    public record RaiseInput(String from, Integer bp) {}

    /** @param step the step to take the typed amounts out of; left out for every step */
    public record AcceptInput(String step) {}

    public record DeleteInput(String confirm) {}

    public record Started(long id) {}

    public record Done(boolean done) {}

    private void start(Context ctx) {
        StartInput input = Inputs.body(ctx, StartInput.class);
        boolean setup = switch (input.kind() == null ? "next_year" : input.kind()) {
            case "next_year" -> false;
            case "setup" -> true;
            default -> throw ApiException.badRequest("Please choose next year's plan or first-time setup.");
        };
        long id = setup ? service.startSetup() : service.startNextYear();
        ApiResponse.send(ctx, 201, new Started(id));
    }

    private void one(Context ctx) {
        ApiResponse.ok(ctx, service.find(Inputs.id(ctx, "id")).orElseThrow(ApiException::notFound));
    }

    private void update(Context ctx) {
        long id = Inputs.id(ctx, "id");
        UpdateInput input = Inputs.body(ctx, UpdateInput.class);
        Basis basis = Inputs.choice(Basis.class, input.basis(), "what to suggest from");
        service.setBasis(id, basis, Boolean.TRUE.equals(input.refresh()));
        ApiResponse.ok(ctx, service.find(id).orElseThrow(ApiException::notFound));
    }

    private void setAmount(Context ctx) {
        long id = Inputs.id(ctx, "id");
        AmountInput input = Inputs.body(ctx, AmountInput.class);
        if (input.id() == null || !(PlanService.ITEM.equals(input.kind()) || PlanService.INCOME.equals(input.kind()))) {
            throw ApiException.badRequest("We couldn't tell which line that amount is for. Please try again.");
        }
        if (input.amountCents() != null) {
            Inputs.amount(input.amountCents(), "The amount");
        }
        service.setAmount(id, input.kind(), input.id(), input.amountCents());
        ApiResponse.ok(ctx, service.find(id).orElseThrow(ApiException::notFound));
    }

    private void setPay(Context ctx) {
        long id = Inputs.id(ctx, "id");
        long sourceId = Inputs.id(ctx, "sourceId");
        PayInput input = Inputs.body(ctx, PayInput.class);
        RaiseInput raise = input.raise();
        if (raise != null && raise.from() == null) {
            throw ApiException.badRequest("Please choose the day the raise starts.");
        }
        if (raise != null && raise.bp() == null) {
            throw ApiException.badRequest("Please enter the raise as a percent above 0% and up to 100%.");
        }
        service.setPay(
                id,
                sourceId,
                payInputs.read(input.grossCents(), input.netCents(), input.netIsEstimate(), input.deductions(), input.match()),
                raise == null ? null : new Raise(Inputs.date(raise.from()), raise.bp()));
        ApiResponse.ok(ctx, service.find(id).orElseThrow(ApiException::notFound));
    }

    private void acceptSuggestions(Context ctx) {
        long id = Inputs.id(ctx, "id");
        AcceptInput input = Inputs.body(ctx, AcceptInput.class);
        Step step = input.step() == null ? null : Inputs.choice(Step.class, input.step(), "a step");
        service.acceptSuggestions(id, step);
        ApiResponse.ok(ctx, service.find(id).orElseThrow(ApiException::notFound));
    }

    private void delete(Context ctx) {
        long id = Inputs.id(ctx, "id");
        if (!DELETE_CONFIRMATION.equals(Inputs.body(ctx, DeleteInput.class).confirm())) {
            throw ApiException.badRequest("Please confirm that you want to delete this draft.");
        }
        service.deleteDraft(id);
        ApiResponse.ok(ctx, new Done(true));
    }

    private void finish(Context ctx) {
        long id = Inputs.id(ctx, "id");
        service.finishSetup(id);
        ApiResponse.ok(ctx, service.find(id).orElseThrow(ApiException::notFound));
    }

    /** @param suggestion {@code null} for anything that is not a bill or a budget still in use */
    public record SuggestionView(PlanService.ItemSuggestion suggestion) {}

    private void suggestion(Context ctx) {
        ItemsRepository.Item item = items.find(Inputs.id(ctx, "id")).orElseThrow(ApiException::notFound);
        ApiResponse.ok(ctx, new SuggestionView(service.suggestionFor(item).orElse(null)));
    }
}
