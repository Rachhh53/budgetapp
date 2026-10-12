package app.budget.server.reports;

import app.budget.server.accounts.Account;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.categories.CategoriesRepository;
import app.budget.server.categories.CategoriesRepository.Category;
import app.budget.server.categories.TagsRepository;
import app.budget.server.categories.TagsRepository.Tag;
import app.budget.server.files.AttachmentsRepository;
import app.budget.server.files.AttachmentsRepository.Attachment;
import app.budget.server.files.AttachmentsRepository.Owner;
import app.budget.server.files.FileStore;
import app.budget.server.files.SavedCopies;
import app.budget.server.reports.PdfExport.Receipt;
import app.budget.server.reports.ReportDefinition.Include;
import app.budget.server.reports.ReportRunner.Report;
import app.budget.server.reports.ReportRunner.Row;
import app.budget.server.reports.ReportsRepository.SavedReport;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Reports (spec §4.11, §8): {@code /api/reports} for the ones saved, {@code /api/reports/run}
 * to work one out, and {@code /api/reports/export/csv} and {@code /pdf} to take one away.
 *
 * <p>Running and exporting are POSTs although they change nothing: what a report asks for
 * names payees, and names never go in an address (they would end up in logs and history).
 */
public final class ReportsRoutes {

    private static final int NAME_MAX = 80;
    /** As many saved reports as anyone could find their way around. */
    private static final int MAX_SAVED = 100;
    /** A PDF with every receipt of a busy year is large; past this it is better made in parts. */
    private static final int MAX_RECEIPTS = 300;

    private final ReportsRepository reports;
    private final ReportRunner runner;
    private final IncomeReportRunner incomeRunner;
    private final AttachmentsRepository attachments;
    private final FileStore store;
    private final AccountsRepository accounts;
    private final CategoriesRepository categories;
    private final TagsRepository tags;
    private final Optional<Path> fontsDir;
    private final SavedCopies savedCopies;

    public ReportsRoutes(
            ReportsRepository reports,
            ReportRunner runner,
            IncomeReportRunner incomeRunner,
            AttachmentsRepository attachments,
            FileStore store,
            AccountsRepository accounts,
            CategoriesRepository categories,
            TagsRepository tags,
            Optional<Path> fontsDir,
            SavedCopies savedCopies) {
        this.reports = reports;
        this.runner = runner;
        this.incomeRunner = incomeRunner;
        this.attachments = attachments;
        this.store = store;
        this.accounts = accounts;
        this.categories = categories;
        this.tags = tags;
        this.fontsDir = fontsDir;
        this.savedCopies = savedCopies;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/reports", ctx -> ApiResponse.ok(ctx, reports.all()));
        routes.post("/api/reports", this::create);
        routes.put("/api/reports/{id}", this::update);
        routes.delete("/api/reports/{id}", this::delete);
        routes.post("/api/reports/run", this::run);
        routes.post("/api/reports/export/csv", this::exportCsv);
        routes.post("/api/reports/export/pdf", this::exportPdf);
    }

    /** Request body for saving a report. */
    public record SaveInput(String name, ReportDefinition.Input definition) {}

    /** Request body for running a report that may not be saved. */
    public record RunInput(ReportDefinition.Input definition) {}

    /**
     * Request body for an export.
     *
     * @param name         the report's name, for the heading and the file's name; "Report" when left out
     * @param withReceipts PDF only: true to add the receipts and documents after the report
     */
    /**
     * @param save true to have the file put in this PC's Downloads folder and be told its name, when the
     *             request comes from this PC; otherwise, and from anywhere else, the answer is the file itself
     */
    public record ExportInput(String name, ReportDefinition.Input definition, Boolean withReceipts, Boolean save) {}

    /** The answer to an export that was saved on this PC in place of being sent back. */
    public record Exported(SavedCopies.Saved saved) {}

    public record Deleted(boolean deleted) {}

    private void create(Context ctx) {
        SaveInput input = Inputs.body(ctx, SaveInput.class);
        String name = name(input.name(), null);
        ReportDefinition definition = checked(input.definition());
        if (reports.all().size() >= MAX_SAVED) {
            throw ApiException.conflict("That is as many reports as Budget keeps. Please delete one you no longer use first.");
        }
        long id = reports.create(name, definition);
        ApiResponse.send(ctx, 201, saved(id));
    }

    private void update(Context ctx) {
        long id = Inputs.id(ctx, "id");
        reports.find(id).orElseThrow(ApiException::notFound);
        SaveInput input = Inputs.body(ctx, SaveInput.class);
        reports.update(id, name(input.name(), id), checked(input.definition()));
        ApiResponse.ok(ctx, saved(id));
    }

    private void delete(Context ctx) {
        if (!reports.delete(Inputs.id(ctx, "id"))) {
            throw ApiException.notFound();
        }
        ApiResponse.ok(ctx, new Deleted(true));
    }

    private void run(Context ctx) {
        ReportDefinition definition = ReportDefinition.of(Inputs.body(ctx, RunInput.class).definition());
        // The two kinds answer with different figures; each says which it is in its {@code kind}.
        ApiResponse.ok(ctx, definition.kind() == ReportDefinition.Kind.INCOME ? incomeRunner.run(definition) : runner.run(definition));
    }

    private void exportCsv(Context ctx) {
        ExportInput input = Inputs.body(ctx, ExportInput.class);
        String name = exportName(input.name());
        ReportDefinition definition = ReportDefinition.of(input.definition());
        if (definition.kind() == ReportDefinition.Kind.INCOME) {
            IncomeReportRunner.Report income = incomeRunner.run(definition);
            hand(ctx, input, "text/csv; charset=utf-8", name + ".csv", CsvExport.ofIncome(income, incomeRunner.accountNames(income)));
            return;
        }
        Report report = runner.run(definition);
        hand(ctx, input, "text/csv; charset=utf-8", name + ".csv", CsvExport.of(report, runner.names(report)));
    }

    private void exportPdf(Context ctx) {
        ExportInput input = Inputs.body(ctx, ExportInput.class);
        String name = exportName(input.name());
        ReportDefinition definition = ReportDefinition.of(input.definition());
        if (definition.kind() == ReportDefinition.Kind.INCOME) {
            // Income has no receipts to add: a pay stub is kept with its income, and saved from there.
            IncomeReportRunner.Report income = incomeRunner.run(definition);
            byte[] pdf = PdfExport.ofIncome(name, aboutIncome(definition, income), income, incomeRunner.accountNames(income), fontsDir);
            hand(ctx, input, "application/pdf", name + ".pdf", pdf);
            return;
        }
        Report report = runner.run(definition);
        List<Receipt> receipts = Boolean.TRUE.equals(input.withReceipts()) ? receipts(report) : List.of();
        if (receipts.size() > MAX_RECEIPTS) {
            throw new ApiException(413, "This report has " + receipts.size() + " receipts, which is more than one PDF can hold ("
                    + MAX_RECEIPTS + "). Please choose fewer dates, or export it without receipts.");
        }
        byte[] pdf = PdfExport.of(name, about(definition, report), report, runner.names(report), receipts, fontsDir);
        hand(ctx, input, "application/pdf", name + ".pdf", pdf);
    }

    /** Every file attached to a line of the report, in the report's order, with what it belongs to. */
    private List<Receipt> receipts(Report report) {
        List<Receipt> receipts = new ArrayList<>();
        for (Row row : report.rows()) {
            if (row.fileCount() == 0) {
                continue;
            }
            Owner owner = row.entryId() != null
                    ? new Owner.Entry(row.entryId())
                    : new Owner.Occurrence(row.itemId(), row.scheduledDate());
            for (Attachment attachment : attachments.of(owner)) {
                String what = row.payee() == null ? row.description() : row.payee();
                receipts.add(new Receipt(
                        PdfExport.day(row.date()) + " · " + what + " · " + PdfExport.money(row.amountCents()) + " · " + attachment.name(),
                        store.path(attachment.hash()),
                        attachment.kind()));
            }
        }
        return receipts;
    }

    /** The lines under a PDF's title: the dates, what is included, and each filter in words. */
    private List<String> about(ReportDefinition definition, Report report) {
        List<String> lines = new ArrayList<>();
        lines.add(PdfExport.day(report.from()) + " – " + PdfExport.day(report.to()));
        lines.add("Includes " + definition.include().stream().map(ReportsRoutes::words).collect(Collectors.joining(", ")) + ".");
        List<String> only = new ArrayList<>();
        if (!definition.tagIds().isEmpty()) {
            only.add("tagged " + tags.all().stream()
                    .filter(tag -> definition.tagIds().contains(tag.id()))
                    .map(Tag::name)
                    .collect(Collectors.joining(" or ")));
        }
        if (!definition.categoryIds().isEmpty()) {
            only.add("in " + categories.all().stream()
                    .filter(category -> definition.categoryIds().contains(category.id()))
                    .map(Category::name)
                    .collect(Collectors.joining(" or ")));
        }
        if (!definition.accountIds().isEmpty()) {
            only.add("paid from " + accounts.all().stream()
                    .filter(account -> definition.accountIds().contains(account.id()))
                    .map(Account::name)
                    .collect(Collectors.joining(" or ")));
        }
        if (!definition.payees().isEmpty()) {
            only.add("paid to " + String.join(" or ", definition.payees()));
        }
        if (!definition.reimbursement().isEmpty()) {
            only.add("reimbursement " + definition.reimbursement().stream()
                    .map(status -> CsvExport.words(Inputs.code(status)).toLowerCase())
                    .collect(Collectors.joining(" or ")));
        }
        if (!only.isEmpty()) {
            lines.add("Only what is " + String.join("; ", only) + ".");
        }
        return lines;
    }

    /** The lines under an income report's title: the dates, what counts, and each filter in words. */
    private List<String> aboutIncome(ReportDefinition definition, IncomeReportRunner.Report report) {
        List<String> lines = new ArrayList<>();
        lines.add(PdfExport.day(report.from()) + " – " + PdfExport.day(report.to()));
        lines.add("Income from " + definition.incomeInclude().stream().map(IncomeReportRunner::words).collect(Collectors.joining(", ")) + ".");
        if (!definition.accountIds().isEmpty()) {
            lines.add("Only what landed in " + accounts.all().stream()
                    .filter(account -> definition.accountIds().contains(account.id()))
                    .map(Account::name)
                    .collect(Collectors.joining(" or ")) + ".");
        }
        if (!definition.sourceIds().isEmpty()) {
            lines.add("Only the income sources chosen for this report.");
        }
        return lines;
    }

    private static String words(Include include) {
        return switch (include) {
            case ONE_OFFS -> "one-off spending";
            case BILLS -> "bills and expenses";
            case BUDGETS -> "budgets";
        };
    }

    private SavedReport saved(long id) {
        return reports.find(id).orElseThrow(ApiException::notFound);
    }

    private String name(String value, Long exceptId) {
        String name = Inputs.text(value, "a name for this report", NAME_MAX);
        if (reports.nameTaken(name, exceptId)) {
            throw ApiException.conflict("There is already a report called that. Please choose another name.");
        }
        return name;
    }

    /** A definition whose tags, categories and accounts all exist, so nothing unusable is ever saved. */
    private ReportDefinition checked(ReportDefinition.Input input) {
        ReportDefinition definition = ReportDefinition.of(input);
        if (definition.kind() == ReportDefinition.Kind.INCOME) {
            incomeRunner.run(definition);
        } else {
            runner.run(definition);
        }
        return definition;
    }

    private static String exportName(String value) {
        String name = Inputs.optionalText(value, "the report's name", NAME_MAX);
        return name == null ? "Report" : name;
    }

    /** Sends {@code bytes} as a file to save. The name is cleaned of anything a file name cannot hold. */
    /** Saves the export on this PC when that was asked for and can be done; otherwise sends it back. */
    private void hand(Context ctx, ExportInput input, String contentType, String fileName, byte[] bytes) {
        if (Boolean.TRUE.equals(input.save()) && savedCopies.canSaveFor(ctx)) {
            ApiResponse.ok(ctx, new Exported(savedCopies.save(fileName, bytes)));
            return;
        }
        download(ctx, contentType, fileName, bytes);
    }

    private static void download(Context ctx, String contentType, String fileName, byte[] bytes) {
        String safe = fileName.replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", " ").strip();
        ctx.contentType(contentType);
        ctx.header("Content-Disposition",
                "attachment; filename*=UTF-8''" + URLEncoder.encode(safe, StandardCharsets.UTF_8).replace("+", "%20"));
        ctx.result(bytes);
    }
}
