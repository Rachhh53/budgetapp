package app.budget.server.planner;

import app.budget.engine.planner.PayoffPlan.Order;
import app.budget.server.accounts.Account;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.util.HashMap;
import java.util.Map;

/**
 * Debt &amp; decisions (spec §5.9): {@code POST /api/planner} works the comparison out, and
 * {@code PUT} / {@code DELETE /api/planner/hidden/{accountId}} leave an account out of it or
 * bring it back.
 */
public final class PlannerRoutes {

    /** The path of the comparison: a look-up sent as a POST, so nothing is recorded for it (see {@code ChangeRoutes}). */
    public static final String COMPARE = "/api/planner";

    private final PlannerService service;
    private final PlannerRepository planner;
    private final AccountsRepository accounts;

    public PlannerRoutes(PlannerService service, PlannerRepository planner, AccountsRepository accounts) {
        this.service = service;
        this.planner = planner;
        this.accounts = accounts;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.post(COMPARE, this::compare);
        routes.put("/api/planner/hidden/{accountId}", ctx -> setHidden(ctx, true));
        routes.delete("/api/planner/hidden/{accountId}", ctx -> setHidden(ctx, false));
    }

    /**
     * @param extraCents the extra amount each month
     * @param years      how long to compare over: 1, 3, 5, 10, 20 or 30
     * @param order      {@code highest_rate} (the default) or {@code smallest_balance}
     * @param growthBp   a yearly rate to assume for an investment or savings account in place of its own,
     *                   by account id, in basis points (7% is 700)
     * @param plans      how "Pay off debt or invest?" is worked out; left out, the usual
     */
    public record CompareInput(Long extraCents, Integer years, String order, Map<String, Integer> growthBp, PlansInput plans) {}

    /** See {@link PlannerService.PlanSettings}; every field may be left out. */
    public record PlansInput(Long investAccountId, Integer splitBp, Integer growthBp, Integer years, Boolean freedMoves) {}

    public record Done(boolean done) {}

    private void compare(Context ctx) {
        CompareInput input = Inputs.body(ctx, CompareInput.class);
        long extra = Inputs.amount(input.extraCents(), "The extra amount each month");
        if (input.years() == null || !PlannerService.YEARS.contains(input.years())) {
            throw ApiException.badRequest("Please choose how long to compare over from the list.");
        }
        Order order = input.order() == null ? Order.HIGHEST_RATE : Inputs.choice(Order.class, input.order(), "the payoff order");
        Map<Long, Integer> growth = new HashMap<>();
        if (input.growthBp() != null) {
            input.growthBp().forEach((accountId, rateBp) -> growth.put(accountId(accountId), growthRate(rateBp)));
        }
        ApiResponse.ok(ctx, service.compare(extra, input.years(), order, growth, plans(input.plans())));
    }

    private static PlannerService.PlanSettings plans(PlansInput input) {
        if (input == null) {
            return PlannerService.PlanSettings.USUAL;
        }
        if (input.splitBp() != null && (input.splitBp() < 0 || input.splitBp() > 10_000)) {
            throw ApiException.badRequest("Please choose how much of the extra goes to debts, from 0% to 100%.");
        }
        if (input.years() != null && (input.years() < 1 || input.years() > PlannerService.MOST_PLAN_YEARS)) {
            throw ApiException.badRequest("Please choose from 1 to 30 years.");
        }
        return new PlannerService.PlanSettings(
                input.investAccountId(), input.splitBp(), input.growthBp() == null ? null : growthRate(input.growthBp()), input.years(),
                input.freedMoves());
    }

    private static long accountId(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("We couldn't tell which account that growth rate is for. Please try again.");
        }
    }

    private static int growthRate(Integer rateBp) {
        if (rateBp == null || rateBp < 0 || rateBp > PlannerService.MAX_GROWTH_BP) {
            throw ApiException.badRequest("Please enter an assumed growth rate between 0% and 100% a year.");
        }
        return rateBp;
    }

    private void setHidden(Context ctx, boolean hidden) {
        Account account = accounts.find(Inputs.id(ctx, "accountId")).orElseThrow(ApiException::notFound);
        if (!PlannerService.compared(account)) {
            throw ApiException.badRequest("A checking account isn't part of this comparison, so there is nothing to hide or show.");
        }
        if (hidden) {
            planner.hide(account.id());
        } else {
            planner.show(account.id());
        }
        ApiResponse.ok(ctx, new Done(true));
    }
}
