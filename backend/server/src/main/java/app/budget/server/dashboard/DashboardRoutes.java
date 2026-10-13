package app.budget.server.dashboard;

import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.dashboard.DashboardService.Range;
import io.javalin.router.JavalinDefaultRoutingApi;

/**
 * The dashboard (spec §8): {@code GET /api/dashboard?range=}, where {@code range} says which
 * dates "Spending by category" covers: {@code period}, {@code three_months} (the default) or {@code year}.
 */
public final class DashboardRoutes {

    private final DashboardService dashboard;

    public DashboardRoutes(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/dashboard", ctx -> {
            String range = ctx.queryParam("range");
            ApiResponse.ok(ctx, dashboard.dashboard(
                    range == null || range.isBlank() ? Range.THREE_MONTHS : Inputs.choice(Range.class, range, "the dates")));
        });
    }
}
