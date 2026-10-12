package app.budget.server.reports;

import app.budget.engine.reports.AnnualCosts.Basis;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.files.SavedCopies;
import app.budget.server.items.ItemsRepository;
import app.budget.server.reports.AnnualCostsRunner.Costs;
import app.budget.server.reports.AnnualCostsRunner.WindowView;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

/**
 * Annual costs (spec §8): {@code GET /api/annual-costs} for every bill and budget by year,
 * {@code GET /api/items/{id}/by-month} for one of them month by month, and
 * {@code POST /api/annual-costs/export/csv} to take the table away.
 */
public final class AnnualCostsRoutes {

    /** The export's address; a POST that changes nothing, so {@code ChangeRoutes} is told not to record it. */
    public static final String EXPORT_PATH = "/api/annual-costs/export/csv";

    private final AnnualCostsRunner runner;
    private final ItemsRepository items;
    private final SavedCopies savedCopies;

    public AnnualCostsRoutes(AnnualCostsRunner runner, ItemsRepository items, SavedCopies savedCopies) {
        this.runner = runner;
        this.items = items;
        this.savedCopies = savedCopies;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/annual-costs", ctx -> ApiResponse.ok(ctx, runner.run(basis(ctx.queryParam("basis")))));
        routes.get("/api/items/{id}/by-month", this::byMonth);
        routes.post(EXPORT_PATH, this::exportCsv);
    }

    /**
     * Request body for the export.
     *
     * @param basis {@code calendar} (the default) or {@code rolling}
     * @param save  true to have the file put in this PC's Downloads folder and be told its name, when the
     *              request comes from this PC; otherwise, and from anywhere else, the answer is the file itself
     */
    public record ExportInput(String basis, Boolean save) {}

    private void byMonth(Context ctx) {
        ApiResponse.ok(ctx, runner.byMonth(items.find(Inputs.id(ctx, "id")).orElseThrow(ApiException::notFound)));
    }

    private void exportCsv(Context ctx) {
        ExportInput input = Inputs.body(ctx, ExportInput.class);
        Costs costs = runner.run(basis(input.basis()));
        byte[] csv = CsvExport.ofAnnual(costs.windows().stream().map(WindowView::label).toList(), runner.csvRows(costs));
        String fileName = "Annual costs.csv";
        if (Boolean.TRUE.equals(input.save()) && savedCopies.canSaveFor(ctx)) {
            ApiResponse.ok(ctx, new ReportsRoutes.Exported(savedCopies.save(fileName, csv)));
            return;
        }
        ReportsRoutes.download(ctx, "text/csv; charset=utf-8", fileName, csv);
    }

    /** Calendar years unless the request says otherwise. */
    private static Basis basis(String value) {
        return value == null || value.isBlank() ? Basis.CALENDAR : Inputs.choice(Basis.class, value, "calendar years or rolling months");
    }
}
