package app.budget.server.uploads;

import app.budget.engine.schedule.PayPeriod;
import app.budget.engine.Money;
import app.budget.engine.statements.BudgetActuals;
import app.budget.engine.statements.BudgetActuals.BudgetDate;
import app.budget.engine.statements.BudgetActuals.Charge;
import app.budget.engine.statements.BudgetActuals.Coverage;
import app.budget.engine.statements.BudgetActuals.Covered;
import app.budget.engine.statements.BudgetActuals.Result;
import app.budget.engine.statements.CsvStatement;
import app.budget.engine.statements.Duplicates;
import app.budget.engine.statements.LineKinds;
import app.budget.engine.statements.OfxStatement;
import app.budget.engine.statements.PayeeKey;
import app.budget.engine.statements.PdfStatement;
import app.budget.engine.statements.StatementCheck;
import app.budget.engine.statements.StatementRead;
import app.budget.engine.statements.StatementRead.Kind;
import app.budget.engine.statements.StatementText.DateOrder;
import app.budget.engine.stubs.PayStub;
import app.budget.server.accounts.Account;
import app.budget.server.accounts.AccountType;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.categories.CategoriesRepository;
import app.budget.server.categories.TagsRepository;
import app.budget.server.entries.EntriesRepository;
import app.budget.server.entries.EntryKind;
import app.budget.server.files.FileStore;
import app.budget.server.files.FileStore.Stored;
import app.budget.server.files.PdfWords;
import app.budget.server.files.StoredFiles;
import app.budget.server.income.PayCalendar;
import app.budget.server.items.ItemCalendar;
import app.budget.server.items.ItemCalendar.Occurrence;
import app.budget.server.items.ItemKind;
import app.budget.server.items.ItemsRepository;
import app.budget.server.items.ItemsRepository.Override;
import app.budget.server.uploads.StatementUploadsRepository.Format;
import app.budget.server.uploads.StatementUploadsRepository.ItemDate;
import app.budget.server.uploads.StatementUploadsRepository.Line;
import app.budget.server.uploads.StatementUploadsRepository.NewLine;
import app.budget.server.uploads.StatementUploadsRepository.PayeeRule;
import app.budget.server.uploads.StatementUploadsRepository.Replaced;
import app.budget.server.uploads.StatementUploadsRepository.Upload;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Statement uploads (Rachel, 2026-10-25, made simpler 2026-10-30): a card or bank statement is added to its account and
 * read on this PC. Each charge is put on a budget of its pay period (the day it was spent decides which), matched to a
 * bill or one-off already in Budget, or chosen to be added as a one-off; a budget becomes what was spent on it plus what
 * is still to come for days no statement covers yet. Nothing is chosen for her, and nothing changes in the pay periods
 * until Apply. The statement's lines themselves never count as money.
 *
 * <p>Nothing a statement says is logged: it says where someone spends.
 */
public final class StatementUploadsRoutes {

    private static final Logger log = LoggerFactory.getLogger(StatementUploadsRoutes.class);

    /** A statement is a dozen pages at most; anything longer is some other document. */
    private static final int MOST_PAGES = 40;
    private static final int DESCRIPTION_MAX = 120;
    private static final int MAX_TAGS = 20;
    /** As many charges as a statement can have, to put on a budget together. */
    private static final int MAX_LINES_AT_ONCE = CsvStatement.MAX_LINES;
    /** How many rows of a CSV the column question shows, enough to recognise the columns by. */
    private static final int SAMPLE_ROWS = 5;
    private static final String CHOOSE_TAGS = "Please choose tags from the list.";

    private static final String NOT_A_STATEMENT =
            "Budget can read a statement saved as CSV, OFX or QFX (from your bank's \"download transactions\"), or a PDF statement.";
    private static final String NO_TEXT =
            "This PDF has no text Budget can read; it looks like a scan or a photo. A PDF downloaded from your bank's website will work,"
                    + " or a CSV or OFX download.";
    private static final String NO_LINES =
            "We couldn't find any charges in this statement. If it's a CSV, please check which columns hold the date and the amount.";

    private final StatementUploadsRepository uploads;
    private final StoredFiles files;
    private final AccountsRepository accounts;
    private final CategoriesRepository categories;
    private final TagsRepository tags;
    private final ItemsRepository items;
    private final ItemCalendar calendar;
    private final PayCalendar pay;
    private final EntriesRepository entries;
    private final CsvLayouts layouts;
    private final ObjectMapper json = new ObjectMapper();

    public StatementUploadsRoutes(
            StatementUploadsRepository uploads,
            StoredFiles files,
            AccountsRepository accounts,
            CategoriesRepository categories,
            TagsRepository tags,
            ItemsRepository items,
            ItemCalendar calendar,
            PayCalendar pay,
            EntriesRepository entries,
            CsvLayouts layouts) {
        this.uploads = uploads;
        this.files = files;
        this.accounts = accounts;
        this.categories = categories;
        this.tags = tags;
        this.items = items;
        this.calendar = calendar;
        this.pay = pay;
        this.entries = entries;
        this.layouts = layouts;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/accounts/{id}/statement-uploads", this::list);
        routes.post("/api/accounts/{id}/statement-uploads/columns", this::columns);
        routes.post("/api/accounts/{id}/statement-uploads", this::upload);
        routes.get("/api/statement-uploads/{id}", ctx -> ApiResponse.ok(ctx, view(found(ctx), List.of())));
        routes.delete("/api/statement-uploads/{id}", this::delete);
        routes.put("/api/statement-uploads/{id}/lines/{lineId}", this::changeLine);
        routes.put("/api/statement-uploads/{id}/budget", this::putOnBudget);
        routes.put("/api/statement-uploads/{id}/filing", this::file);
        routes.put("/api/statement-uploads/{id}/lines/{lineId}/match", this::match);
        routes.delete("/api/statement-uploads/{id}/lines/{lineId}/match", this::unmatch);
        routes.post("/api/statement-uploads/{id}/lines/{lineId}/one-off", this::oneOff);
        routes.delete("/api/statement-uploads/{id}/lines/{lineId}/one-off", this::noOneOff);
        routes.put("/api/statement-uploads/{id}/balances", this::balances);
        routes.put("/api/statement-uploads/{id}/still-to-come", this::stillToCome);
        routes.get("/api/statement-uploads/{id}/changes", ctx -> ApiResponse.ok(ctx, changes(found(ctx))));
        routes.post("/api/statement-uploads/{id}/apply", this::apply);
        routes.get("/api/statement-budgets", this::statementBudgets);
        routes.put("/api/statement-budgets/{itemId}/{date}/still-to-come", this::stillOnPeriod);
        routes.get("/api/statement-uploads/{id}/file", this::download);
        routes.get("/api/statement-uploads/{id}/preview", this::preview);
    }

    // ---------------------------------------------------------------- views

    /** An upload in an account's list. */
    public record UploadSummary(
            long id, String name, Format format, LocalDate from, LocalDate to, String appliedAt, int lineCount, int toCheck) {}

    /**
     * One line of a statement.
     *
     * @param periodStart      the pay period the day it was spent falls in; null outside every period
     * @param duplicateOf      the line of an earlier upload it repeats, which is counted instead
     * @param oneOffEntryId    the one-off Apply added it as; null until then
     * @param addOneOff        chosen to be added as a one-off on the next Apply
     * @param matched          what Budget already had that it is, as its pay period's {@code matchable} names it; null for none
     * @param budgetItemId     the budget it is put on; null for none
     * @param suggestedItemId  the budget its payee's charges went on last time, when that budget has a date in its pay period
     *                         and it is on none yet; a suggestion only
     */
    public record LineView(
            long id,
            LocalDate txnDate,
            LocalDate postedDate,
            String description,
            long amountCents,
            Kind kind,
            boolean leftOut,
            boolean sure,
            Long duplicateOf,
            Long categoryId,
            List<Long> tagIds,
            Long oneOffEntryId,
            boolean addOneOff,
            LocalDate periodStart,
            MatchedView matched,
            Long budgetItemId,
            Long suggestedItemId) {}

    /** What a line was matched to: a recurring item's date, or a one-off entry; {@code applied} once Apply wrote it. */
    public record MatchedView(Long itemId, LocalDate scheduledDate, Long entryId, boolean applied) {

        static MatchedView of(StatementUploadsRepository.Match match) {
            return match == null ? null : new MatchedView(match.itemId(), match.scheduledDate(), match.entryId(), match.applied());
        }
    }

    /**
     * Whether every row read adds up to the statement's balances (Rachel, 2026-10-30).
     *
     * @param previousCents   the balance it starts from, read or typed (on a card, what was owed); null when unsaid
     * @param newCents        the balance it ends with; null when unsaid
     * @param statementChargesCents the statement's own total of charges (withdrawals on a bank account); null when not typed
     * @param statementCreditsCents the statement's own total of payments and credits (deposits); null when not typed
     * @param spentCents      every row read that is money spent, added up by Budget
     * @param backCents       every row read that is money back, added up by Budget, as a positive amount
     * @param expectedNewCents the previous balance moved by every row; null without a previous balance
     * @param differenceCents the new balance less that; zero when it adds up, null without both balances
     */
    public record CheckView(
            Long previousCents, Long newCents, Long statementChargesCents, Long statementCreditsCents, long spentCents, long backCents,
            Long expectedNewCents, Long differenceCents) {}

    /**
     * An upload with every line read from it.
     *
     * @param skippedRows CSV rows that could not be read (their numbers, counted from 1), said once, when it is added
     */
    public record UploadView(
            long id,
            long accountId,
            String accountName,
            boolean onCard,
            String name,
            Format format,
            LocalDate from,
            LocalDate to,
            String appliedAt,
            Integer pages,
            List<LineView> lines,
            int toCheck,
            List<Integer> skippedRows,
            CheckView check) {}

    /** What a CSV's columns look like, to say which is which before it is read. */
    public record ColumnsView(List<String> headers, List<List<String>> sample, LayoutView guess, boolean known) {}

    /** A CSV layout as the screens send it; columns counted from 0. */
    public record LayoutView(
            boolean hasHeader,
            int dateColumn,
            Integer postedColumn,
            int descriptionColumn,
            Integer amountColumn,
            Integer debitColumn,
            Integer creditColumn,
            String dateOrder,
            boolean chargesNegative) {

        static LayoutView of(CsvStatement.Layout layout) {
            return new LayoutView(layout.hasHeader(), layout.dateColumn(), layout.postedColumn(), layout.descriptionColumn(),
                    layout.amountColumn(), layout.debitColumn(), layout.creditColumn(), Inputs.code(layout.dateOrder()),
                    layout.chargesNegative());
        }

        CsvStatement.Layout layout() {
            return new CsvStatement.Layout(hasHeader, dateColumn, postedColumn, descriptionColumn, amountColumn, debitColumn, creditColumn,
                    Inputs.choice(DateOrder.class, dateOrder, "how the dates are written"), chargesNegative);
        }
    }

    // ---------------------------------------------------------------- list and upload

    private void list(Context ctx) {
        Account account = account(ctx);
        ApiResponse.ok(ctx, uploads.ofAccount(account.id()).stream().map(upload -> {
            List<Line> lines = uploads.lines(upload.id());
            return new UploadSummary(upload.id(), upload.name(), upload.format(), upload.from(), upload.to(), upload.appliedAt(),
                    lines.size(), (int) lines.stream().filter(line -> !line.sure()).count());
        }).toList());
    }

    /** A CSV's columns and a first guess at which is which, keeping nothing. */
    private void columns(Context ctx) {
        Account account = account(ctx);
        UploadedFile sent = oneFile(ctx);
        String text = text(bytes(sent));
        if (OfxStatement.looksLikeOfx(text)) {
            throw ApiException.badRequest("This is an OFX file, which says what each column is by itself. Please add it as it is.");
        }
        List<List<String>> rows = CsvStatement.rows(text);
        if (rows.isEmpty()) {
            throw new ApiException(422, NO_LINES);
        }
        Optional<CsvStatement.Layout> known = layouts.find(account.id(), rows.get(0));
        CsvStatement.Layout guess = known.orElseGet(() -> CsvStatement.guess(rows));
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < columns; i++) {
            headers.add(guess.hasHeader() && i < rows.get(0).size() && !rows.get(0).get(i).isBlank() ? rows.get(0).get(i) : "Column " + (i + 1));
        }
        List<List<String>> sample = rows.subList(guess.hasHeader() ? 1 : 0, rows.size()).stream().limit(SAMPLE_ROWS).toList();
        ApiResponse.ok(ctx, new ColumnsView(headers, sample, LayoutView.of(guess), known.isPresent()));
    }

    private void upload(Context ctx) {
        Account account = account(ctx);
        if (!spends(account)) {
            throw ApiException.badRequest("Statements can be added to a checking, savings or credit card account.");
        }
        boolean onCard = onCard(account);
        UploadedFile sent = oneFile(ctx);
        String name = StoredFiles.name(sent.filename(), FileStore.Kind.PDF);
        byte[] content = bytes(sent);

        Stored kept = null;
        Format format;
        StatementRead read;
        List<Integer> skipped = List.of();
        if (startsWithPdf(content)) {
            format = Format.PDF;
            kept = files.keep(sent);
            read = readPdf(kept, onCard);
        } else {
            String text = text(content);
            if (OfxStatement.looksLikeOfx(text)) {
                format = Format.OFX;
                read = OfxStatement.read(text, onCard);
            } else {
                format = Format.CSV;
                List<List<String>> rows = CsvStatement.rows(text);
                if (rows.isEmpty()) {
                    throw new ApiException(422, NO_LINES);
                }
                CsvStatement.Layout layout = layout(ctx, account, rows);
                CsvStatement.Read csv = CsvStatement.read(rows, layout, onCard);
                read = csv.statement();
                skipped = csv.skipped().stream().map(CsvStatement.Skipped::row).toList();
                layouts.remember(account.id(), rows.get(0), layout);
            }
        }
        List<StatementRead.Line> lines = read.lines().stream().filter(line -> Inputs.inSupportedYears(line.txnDate())).toList();
        if (lines.isEmpty()) {
            throw new ApiException(422, NO_LINES);
        }
        LocalDate from = read.from() != null && Inputs.inSupportedYears(read.from()) ? read.from() : lines.getFirst().txnDate();
        LocalDate to = read.to() != null && Inputs.inSupportedYears(read.to()) ? read.to() : lines.getLast().txnDate();

        // A line an earlier upload of this account already has is kept, left out and saying so, never counted twice.
        List<Long> repeats = Duplicates.of(lines, uploads.known(account.id()));
        Map<String, PayeeRule> rules = uploads.payeeRules();
        List<NewLine> toKeep = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            StatementRead.Line line = lines.get(i);
            String description = trimmed(line.description());
            String key = PayeeKey.of(description);
            Optional<PayeeRule> rule = rule(rules, key);
            toKeep.add(new NewLine(
                    line.txnDate(),
                    line.postedDate(),
                    description,
                    key,
                    line.amount().cents(),
                    line.kind(),
                    LineKinds.leftOutByDefault(line.kind()),
                    line.sure(),
                    line.externalId(),
                    repeats.get(i),
                    rule.map(PayeeRule::categoryId).filter(this::categoryExists).orElse(null),
                    rule.map(PayeeRule::tagIds).orElse(List.of())));
        }
        long id = uploads.create(account.id(), kept, name, format, from.isAfter(to) ? to : from, to, cents(read.previousBalance()),
                cents(read.newBalance()), cents(read.charges()), cents(read.credits()), toKeep);
        ApiResponse.send(ctx, 201, view(uploads.find(id).orElseThrow(), skipped));
    }

    /**
     * What a payee was given before: under its own key, or else under another key with the same first word when every
     * such payee was given the same ("kroger dayton" filed, "kroger cincinnati" new).
     */
    private static Optional<PayeeRule> rule(Map<String, PayeeRule> rules, String key) {
        if (key.isEmpty()) {
            return Optional.empty();
        }
        PayeeRule exact = rules.get(key);
        if (exact != null) {
            return Optional.of(exact);
        }
        String first = PayeeKey.firstWord(key);
        List<PayeeRule> alike = rules.entrySet().stream()
                .filter(entry -> PayeeKey.firstWord(entry.getKey()).equals(first))
                .map(Map.Entry::getValue)
                .distinct()
                .toList();
        return alike.size() == 1 ? Optional.of(alike.getFirst()) : Optional.empty();
    }

    /** The layout sent with the file, or the one remembered for its header row; a CSV with neither asks first. */
    private CsvStatement.Layout layout(Context ctx, Account account, List<List<String>> rows) {
        String sent = ctx.formParam("layout");
        CsvStatement.Layout layout;
        if (sent != null && !sent.isBlank()) {
            try {
                layout = json.readValue(sent, LayoutView.class).layout();
            } catch (IOException e) {
                throw ApiException.badRequest("Please say again which column holds what.");
            }
        } else {
            layout = layouts.find(account.id(), rows.get(0)).orElseThrow(() -> ApiException.conflict(
                    "Please say which column holds the date, the description and the amount first."));
        }
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        if (!layout.fits(columns)) {
            throw ApiException.badRequest("Please choose a date, a description and an amount column, or a money out and a money in column.");
        }
        return layout;
    }

    private StatementRead readPdf(Stored kept, boolean onCard) {
        List<PayStub.Row> rows;
        try {
            rows = PdfWords.rows(files.path(kept.hash()), MOST_PAGES);
        } catch (IOException | RuntimeException e) {
            // The cause says nothing of the statement's contents: only why the PDF library gave up.
            log.warn("A statement's text could not be read: {}", e.getClass().getSimpleName());
            throw new ApiException(422, "We couldn't read that PDF. It may be damaged; please try downloading it again.");
        }
        if (rows.isEmpty()) {
            throw new ApiException(422, NO_TEXT);
        }
        return PdfStatement.read(rows, onCard, pay.today());
    }

    private void delete(Context ctx) {
        uploads.delete(found(ctx).id());
        ApiResponse.ok(ctx, Map.of("done", true));
    }

    private void download(Context ctx) {
        Upload upload = keptPdf(ctx);
        files.download(ctx, upload.name(), FileStore.Kind.PDF, upload.sizeBytes(), upload.hash());
    }

    private void preview(Context ctx) {
        Upload upload = keptPdf(ctx);
        files.preview(ctx, upload.hash(), FileStore.Kind.PDF, upload.pages());
    }

    private Upload keptPdf(Context ctx) {
        Upload upload = found(ctx);
        if (upload.hash() == null) {
            throw ApiException.notFound();
        }
        files.requireOnDisk(upload.hash());
        return upload;
    }

    // ---------------------------------------------------------------- lines

    /** Request body for one line. */
    public record LineInput(
            LocalDate txnDate,
            String description,
            Long amountCents,
            String kind,
            Boolean leftOut,
            Long categoryId,
            List<Long> tagIds) {}

    /** The upload after a line was changed, and how many other lines from its payee were filed the same way. */
    public record LineChanged(UploadView upload, int alsoFiled) {}

    private void changeLine(Context ctx) {
        Upload upload = found(ctx);
        Line line = ownLine(ctx, upload);
        LineInput input = Inputs.body(ctx, LineInput.class);

        LocalDate txnDate = input.txnDate() == null ? line.txnDate() : input.txnDate();
        if (!Inputs.inSupportedYears(txnDate)) {
            throw ApiException.badRequest("Please choose the day this was spent.");
        }
        String description = input.description() == null
                ? line.description()
                : Inputs.text(input.description(), "what this was", DESCRIPTION_MAX);
        long amount = input.amountCents() == null ? line.amountCents() : Inputs.signedAmount(input.amountCents(), "The amount");
        Kind kind = input.kind() == null ? line.kind() : Inputs.choice(Kind.class, input.kind(), "what this line is");
        boolean leftOut = input.leftOut() == null ? line.leftOut() : input.leftOut();
        Long categoryId = input.categoryId();
        if (categoryId != null && !categoryExists(categoryId)) {
            throw ApiException.badRequest("Please choose a category from the list.");
        }
        List<Long> tagIds = Inputs.ids(input.tagIds(), MAX_TAGS, CHOOSE_TAGS);
        if (!tags.allExist(tagIds)) {
            throw ApiException.badRequest(CHOOSE_TAGS);
        }

        String key = PayeeKey.of(description);
        uploads.updateLine(line.id(), new StatementUploadsRepository.LineFields(
                txnDate, description, key, amount, kind, leftOut, categoryId, tagIds));
        boolean filed = !Objects.equals(categoryId, line.categoryId()) || !new HashSet<>(tagIds).equals(new HashSet<>(line.tagIds()));
        int alsoFiled = 0;
        if (filed) {
            // Remembered for the next statement, and given now to this payee's other lines with nothing on them yet.
            uploads.rememberPayee(key, categoryId, tagIds);
            alsoFiled = uploads.fillFromPayee(upload.accountId(), line.id(), key, categoryId, tagIds);
        }
        ApiResponse.ok(ctx, new LineChanged(view(uploads.find(upload.id()).orElseThrow(), List.of()), alsoFiled));
    }

    /** Request body for putting charges on a budget: {@code itemId} null takes them off whatever budget they are on. */
    public record BudgetInput(List<Long> lineIds, Long itemId) {}

    /**
     * Puts one charge or several on a budget (Rachel, 2026-10-30: "if I could just select to add it to that budget"). Each
     * counts toward the date of the budget its day falls under, in its own pay period, once applied. The budget is
     * remembered for each payee as a suggestion.
     */
    private void putOnBudget(Context ctx) {
        Upload upload = found(ctx);
        BudgetInput input = Inputs.body(ctx, BudgetInput.class);
        List<Long> lineIds = Inputs.ids(input.lineIds(), MAX_LINES_AT_ONCE, "Please choose the charges from the list.");
        if (lineIds.isEmpty()) {
            throw ApiException.badRequest("Please choose at least one charge.");
        }
        Map<Long, Line> own = new HashMap<>();
        uploads.lines(upload.id()).forEach(line -> own.put(line.id(), line));
        List<Line> chosen = new ArrayList<>();
        for (long lineId : lineIds) {
            Line line = own.get(lineId);
            if (line == null) {
                throw ApiException.notFound();
            }
            if (!line.open()) {
                throw ApiException.conflict("\"" + line.description() + "\" is " + whatItIs(line) + " already, so it can't go on a budget too.");
            }
            chosen.add(line);
        }
        if (input.itemId() != null) {
            List<PayPeriod> periods = pay.payPeriods(upload.from(), upload.to());
            List<BudgetDate> dates = budgetDates(periods);
            for (Line line : chosen) {
                if (BudgetActuals.dateFor(line.txnDate(), input.itemId(), periods, dates).isEmpty()) {
                    throw ApiException.badRequest("That budget has no date in the pay period of \"" + line.description()
                            + "\". Please choose one of that pay period's budgets.");
                }
            }
            chosen.forEach(line -> uploads.rememberBudget(upload.accountId(), line.payeeKey(), input.itemId()));
        }
        uploads.setBudget(lineIds, input.itemId());
        ApiResponse.ok(ctx, view(upload, List.of()));
    }

    /**
     * Request body for filing several charges at once: the category (null for none) and tags they all get, and
     * {@code oneOffs} true to add them all as one-offs on Apply too.
     */
    public record FilingInput(List<Long> lineIds, Long categoryId, List<Long> tagIds, Boolean oneOffs) {}

    /**
     * Gives the ticked charges the same category and tags (Rachel, 2026-10-30: "I can't just mass categorize and tag
     * them"), remembered for each payee as filing one does.
     */
    private void file(Context ctx) {
        Upload upload = found(ctx);
        FilingInput input = Inputs.body(ctx, FilingInput.class);
        List<Long> lineIds = Inputs.ids(input.lineIds(), MAX_LINES_AT_ONCE, "Please choose the charges from the list.");
        if (lineIds.isEmpty()) {
            throw ApiException.badRequest("Please choose at least one charge.");
        }
        Map<Long, Line> own = new HashMap<>();
        uploads.lines(upload.id()).forEach(line -> own.put(line.id(), line));
        if (!own.keySet().containsAll(lineIds)) {
            throw ApiException.notFound();
        }
        Long categoryId = input.categoryId();
        if (categoryId != null && !categoryExists(categoryId)) {
            throw ApiException.badRequest("Please choose a category from the list.");
        }
        List<Long> tagIds = Inputs.ids(input.tagIds(), MAX_TAGS, CHOOSE_TAGS);
        if (!tags.allExist(tagIds)) {
            throw ApiException.badRequest(CHOOSE_TAGS);
        }
        // Rachel, 2026-10-30: "when I select multiple to add categorie and tags I should also be able to then add them all
        // as one offs". Checked for every line before anything changes, so it is all of them or none.
        boolean oneOffs = Boolean.TRUE.equals(input.oneOffs());
        if (oneOffs) {
            for (long lineId : lineIds) {
                Line line = own.get(lineId);
                if (line.duplicateOf() != null) {
                    throw ApiException.conflict(line.description() + " is on an earlier statement too; add it from there.");
                }
                if (line.oneOffEntryId() != null) {
                    throw ApiException.conflict(line.description() + " was added as a one-off already.");
                }
            }
        }
        uploads.file(lineIds, categoryId, tagIds);
        lineIds.stream().map(own::get).map(Line::payeeKey).distinct().forEach(key -> uploads.rememberPayee(key, categoryId, tagIds));
        if (oneOffs) {
            // As Add as a one-off does for one: let go of any match or budget, then wait for Apply.
            lineIds.forEach(lineId -> letGo(upload, own.get(lineId)));
            uploads.setBudget(lineIds, null);
            lineIds.forEach(lineId -> uploads.setAddOneOff(lineId, true));
        }
        ApiResponse.ok(ctx, view(uploads.find(upload.id()).orElseThrow(), List.of()));
    }

    /** What a line that isn't open already is, in words. */
    private static String whatItIs(Line line) {
        if (line.duplicateOf() != null) {
            return "on an earlier statement";
        }
        if (line.leftOut()) {
            return "left out";
        }
        if (line.oneOffEntryId() != null || line.addOneOff()) {
            return "a one-off";
        }
        return "matched to something in Budget";
    }

    // ---------------------------------------------------------------- what changes

    /**
     * A budget with a date in the period: what a charge can be put on. One paid from another account takes the charge as
     * spent elsewhere, a one-off on this account (Rachel, 2026-10-30).
     */
    public record BudgetChoiceView(long itemId, String name, long accountId, String accountName) {}

    /** What was spent on a budget date from an account other than the one it is paid from: a one-off there on Apply. */
    public record ElsewhereView(long accountId, String accountName, long cents) {}

    /**
     * One budget date and what Apply makes it: what was spent on it plus what is still to come.
     *
     * @param coveredThrough the last day of it the statements cover, when they cover only some
     * @param actualCents    what was spent on it from the account it is paid from
     * @param stillTyped     still to come is what was typed for it, not worked out
     * @param newCents       what the account it is paid from pays for it
     * @param elsewhere      what was spent on it from each other account, a one-off there
     */
    public record BudgetDateView(
            long itemId,
            String name,
            long accountId,
            String accountName,
            LocalDate scheduledDate,
            LocalDate from,
            LocalDate to,
            Covered covered,
            LocalDate coveredThrough,
            long currentCents,
            long budgetedCents,
            long actualCents,
            long stillToComeCents,
            boolean stillTyped,
            long newCents,
            List<Long> lineIds,
            List<ElsewhereView> elsewhere) {}

    /**
     * Something already in Budget, paid from the account in the period or one either side, that a line can be matched to: a
     * date of a recurring item other than a budget, or a one-off spent from the account.
     *
     * @param periodStart   the pay period it is in now
     * @param matchedLineId the line of this account's statements matched to it already; null for none
     */
    public record MatchableView(
            Long itemId, LocalDate scheduledDate, Long entryId, String name, LocalDate date, long amountCents, boolean paid, LocalDate periodStart,
            Long matchedLineId) {}

    /** A line of the period matched to something already in Budget, and whether Apply has written it yet. */
    public record MatchView(long lineId, String description, long amountCents, LocalDate txnDate, String name, boolean bill, boolean applied) {}

    /**
     * One pay period the upload reaches into.
     *
     * @param coverage  the days of it this account's statements cover; {@code partly} when some are on none of them
     * @param budgets   the budgets a charge in it can be put on
     * @param changes   each budget date with charges on it, and any a statement set before with none on it now
     * @param matchable what a line dated in it can be matched to
     * @param matches   its lines matched to something already in Budget
     * @param oneOffs   its lines chosen to be added as one-offs, not added yet
     * @param restores  bill dates whose match was let go of, to be put back
     * @param open      this statement's lines in it not yet put anywhere
     * @param upToDate  nothing to apply
     */
    public record PeriodView(
            LocalDate start,
            LocalDate end,
            boolean current,
            CoverageView coverage,
            List<BudgetChoiceView> budgets,
            List<BudgetDateView> changes,
            List<MatchableView> matchable,
            List<MatchView> matches,
            List<Long> oneOffs,
            int restores,
            List<Long> open,
            boolean upToDate) {}

    public record CoverageView(LocalDate from, LocalDate to, boolean partly) {}

    /**
     * A charge of any statement put on a budget, for showing where a budget's actual comes from; {@code accountId} is the
     * statement's account, which for a budget paid from another account is not the budget's.
     */
    public record ChargeLine(
            long id, long uploadId, String uploadName, long accountId, LocalDate txnDate, String description, long amountCents) {}

    /** A line as a {@link ChargeLine}, looking each statement up once through {@code seen}. */
    private ChargeLine chargeLine(Line line, Map<Long, Optional<Upload>> seen) {
        Optional<Upload> upload = seen.computeIfAbsent(line.uploadId(), uploads::find);
        return new ChargeLine(line.id(), line.uploadId(), upload.map(Upload::name).orElse(""), upload.map(Upload::accountId).orElse(0L),
                line.txnDate(), line.description(), line.amountCents());
    }

    public record ChangesView(long uploadId, long accountId, List<PeriodView> periods, List<Long> outside, List<ChargeLine> lines) {}

    private ChangesView changes(Upload upload) {
        return worked(upload).view();
    }

    /** Everything worked out for an upload's pay periods, kept together so applying uses exactly what was shown. */
    private record Worked(ChangesView view, Map<LocalDate, List<Result>> results) {}

    /**
     * Every budget's dates in these periods, whatever account pays it, each with what it was budgeted and any still to come
     * typed: a charge can go on a budget paid from another account.
     */
    private List<BudgetDate> budgetDates(List<PayPeriod> periods) {
        return budgetOccurrences(periods).dates();
    }

    private record Budgets(List<Occurrence> occurrences, List<BudgetDate> dates) {}

    private Budgets budgetOccurrences(List<PayPeriod> periods) {
        if (periods.isEmpty()) {
            return new Budgets(List.of(), List.of());
        }
        Set<LocalDate> starts = periods.stream().map(PayPeriod::start).collect(Collectors.toSet());
        List<Occurrence> occurrences = calendar.expand(periods.getFirst().start(), periods.getLast().end()).occurrences().stream()
                .filter(occurrence -> occurrence.kind() == ItemKind.BUDGET)
                .filter(occurrence -> occurrence.periodStart() != null && starts.contains(occurrence.periodStart()))
                .filter(occurrence -> !occurrence.skipped() && occurrence.invoiceId() == null && !occurrence.plannedPack())
                .toList();
        List<Long> itemIds = occurrences.stream().map(Occurrence::itemId).distinct().toList();
        Map<ItemDate, Replaced> replaced = uploads.replaced(itemIds);
        Map<ItemDate, Long> typed = uploads.typedStill(itemIds);
        List<BudgetDate> dates = occurrences.stream().map(occurrence -> {
            ItemDate key = new ItemDate(occurrence.itemId(), occurrence.scheduledDate());
            Replaced before = replaced.get(key);
            // Changed by hand since a statement set it, what it is now is what was budgeted.
            boolean applied = before != null && before.appliedCents() == occurrence.amountCents();
            return new BudgetDate(occurrence.itemId(), occurrence.scheduledDate(), occurrence.periodStart(), occurrence.payBy(),
                    occurrence.amountCents(), applied ? before.baseCents() : occurrence.amountCents(), typed.get(key), applied,
                    occurrence.payFromAccountId());
        }).toList();
        return new Budgets(occurrences, dates);
    }

    /** What every budget date in these periods becomes from the charges put on it, from any account's statements. */
    private List<Result> results(List<PayPeriod> periods, List<BudgetDate> dates) {
        List<Charge> charges = uploads.onBudgets().stream()
                .filter(each -> each.line().onBudget())
                .filter(each -> BudgetActuals.periodOf(each.line().txnDate(), periods).isPresent())
                .map(each -> new Charge(each.line().id(), each.line().txnDate(), each.line().amountCents(), each.line().budgetItemId(),
                        each.accountId()))
                .toList();
        return BudgetActuals.work(charges, periods, dates, uploads.spansByAccount());
    }

    /**
     * Whether a budget date's result belongs on an account's statements: one it pays, one with charges from it, or one with
     * a one-off on it that may now have to go.
     */
    private static boolean concerns(Result result, long accountId, Set<ItemDate> elsewhereHere) {
        return result.accountId() == accountId
                || result.elsewhere().stream().anyMatch(each -> each.accountId() == accountId)
                || elsewhereHere.contains(new ItemDate(result.itemId(), result.scheduledDate()));
    }

    /** Whether Apply would leave a date's one-offs on other accounts as they are. */
    private boolean elsewhereUpToDate(Result result) {
        Map<Long, Long> links = uploads.elsewhere(new ItemDate(result.itemId(), result.scheduledDate()));
        for (BudgetActuals.Elsewhere each : result.elsewhere()) {
            long cents = Math.max(0, each.cents());
            Long entryId = links.remove(each.accountId());
            Optional<EntriesRepository.Entry> entry = entryId == null ? Optional.empty() : entries.find(entryId);
            if (cents == 0 ? entry.isPresent() : entry.isEmpty() || entry.get().amountCents() != cents || !entry.get().date().equals(each.lastDay())) {
                return false;
            }
        }
        return links.isEmpty();
    }

    private Worked worked(Upload upload) {
        long accountId = upload.accountId();
        List<PayPeriod> periods = pay.payPeriods(upload.from(), upload.to());
        Budgets budgets = budgetOccurrences(periods);
        Set<ItemDate> elsewhereHere = Set.copyOf(uploads.elsewhereOn(accountId));
        List<Result> results = results(periods, budgets.dates()).stream()
                .filter(result -> concerns(result, accountId, elsewhereHere))
                .toList();
        Map<Long, String> accountNames = new HashMap<>();
        accounts.all().forEach(account -> accountNames.put(account.id(), account.name()));
        List<Line> accountLines = uploads.linesOfAccount(accountId);
        List<Line> ownLines = uploads.lines(upload.id());
        // This upload's own open lines in no pay period, to say so.
        List<Long> outside = ownLines.stream()
                .filter(Line::open)
                .filter(line -> BudgetActuals.periodOf(line.txnDate(), periods).isEmpty())
                .map(Line::id)
                .toList();

        Map<LocalDate, List<MatchableView>> matchable = matchable(accountId, periods);
        Map<LocalDate, Coverage> coverage = new HashMap<>();
        BudgetActuals.coverage(periods, uploads.spans(accountId)).forEach(covered -> coverage.put(covered.periodStart(), covered));
        Map<Long, String> names = new HashMap<>();
        budgets.occurrences().forEach(occurrence -> names.put(occurrence.itemId(), occurrence.name()));
        List<StatementUploadsRepository.Restore> restores = uploads.restores(accountId);

        List<PeriodView> views = new ArrayList<>();
        Map<LocalDate, List<Result>> resultsByPeriod = new LinkedHashMap<>();
        LocalDate today = pay.today();
        for (PayPeriod period : periods) {
            List<Result> its = results.stream().filter(result -> result.periodStart().equals(period.start())).toList();
            resultsByPeriod.put(period.start(), its);
            // This account's budgets first, then those paid from other accounts.
            List<BudgetChoiceView> choices = budgets.occurrences().stream()
                    .filter(occurrence -> occurrence.periodStart().equals(period.start()))
                    .map(occurrence -> new BudgetChoiceView(occurrence.itemId(), occurrence.name(), occurrence.payFromAccountId(),
                            accountNames.getOrDefault(occurrence.payFromAccountId(), "")))
                    .distinct()
                    .sorted(Comparator.comparing((BudgetChoiceView choice) -> choice.accountId() != accountId)
                            .thenComparing(BudgetChoiceView::accountName)
                            .thenComparing(BudgetChoiceView::name))
                    .toList();
            List<Line> inPeriod = accountLines.stream().filter(line -> period.contains(line.txnDate())).toList();
            List<MatchableView> periodMatchable = matchable.getOrDefault(period.start(), List.of());
            List<MatchView> matches = inPeriod.stream()
                    .filter(line -> line.match() != null)
                    .map(line -> {
                        StatementUploadsRepository.Match match = line.match();
                        String name = periodMatchable.stream()
                                .filter(each -> match.entryId() != null
                                        ? match.entryId().equals(each.entryId())
                                        : match.itemId().equals(each.itemId()) && match.scheduledDate().equals(each.scheduledDate()))
                                .map(MatchableView::name)
                                .findFirst()
                                .orElse("");
                        return new MatchView(line.id(), line.description(), line.amountCents(), line.txnDate(), name, match.itemId() != null,
                                match.applied());
                    })
                    .toList();
            List<Long> oneOffs = inPeriod.stream().filter(line -> line.addOneOff() && line.oneOffEntryId() == null).map(Line::id).toList();
            int restoring = (int) restores.stream().filter(restore -> restore.periodStart().equals(period.start())).count();
            List<Long> open = ownLines.stream()
                    .filter(line -> line.open() && line.budgetItemId() == null && period.contains(line.txnDate()))
                    .map(Line::id)
                    .toList();
            Coverage covered = coverage.get(period.start());
            views.add(new PeriodView(
                    period.start(),
                    period.end(),
                    period.contains(today),
                    covered == null ? null : new CoverageView(covered.from(), covered.to(), covered.partly()),
                    choices,
                    its.stream()
                            .map(result -> new BudgetDateView(result.itemId(), names.getOrDefault(result.itemId(), ""), result.accountId(),
                                    accountNames.getOrDefault(result.accountId(), ""), result.scheduledDate(), result.from(), result.to(),
                                    result.covered(), result.coveredThrough(), result.currentCents(), result.budgetedCents(), result.actualCents(),
                                    result.stillToComeCents(), result.stillTyped(), result.newCents(), result.lineIds(),
                                    result.elsewhere().stream()
                                            .map(each -> new ElsewhereView(each.accountId(), accountNames.getOrDefault(each.accountId(), ""),
                                                    Math.max(0, each.cents())))
                                            .toList()))
                            .toList(),
                    periodMatchable,
                    matches,
                    oneOffs,
                    restoring,
                    open,
                    its.stream().allMatch(result -> result.newCents() == result.currentCents() && elsewhereUpToDate(result))
                            && matches.stream().allMatch(MatchView::applied) && oneOffs.isEmpty() && restoring == 0));
        }

        // The charges behind each budget date, from whichever statement they are on.
        Map<Long, Optional<Upload>> seen = new HashMap<>();
        Set<Long> counted = results.stream().flatMap(result -> result.lineIds().stream()).collect(Collectors.toSet());
        List<ChargeLine> lines = uploads.onBudgets().stream()
                .map(StatementUploadsRepository.AccountLine::line)
                .filter(line -> counted.contains(line.id()))
                .map(line -> chargeLine(line, seen))
                .toList();
        return new Worked(new ChangesView(upload.id(), accountId, views, outside, lines), resultsByPeriod);
    }

    /**
     * What each period's lines can be matched to: the dates of recurring items paid from the account other than budgets and
     * set-asides (money that never leaves it), and one-offs spent from it, each with the line already matched to it. A
     * period's list takes in the pay period either side too, as a card charge often comes a day or two before or after the
     * bill's own date (Rachel, 2026-10-30: a charge on 9/14 for a bill dated 9/15).
     */
    private Map<LocalDate, List<MatchableView>> matchable(long accountId, List<PayPeriod> periods) {
        if (periods.isEmpty()) {
            return Map.of();
        }
        List<PayPeriod> nearby = pay.payPeriods(periods.getFirst().start().minusDays(1), periods.getLast().end().plusDays(1));
        if (nearby.isEmpty()) {
            return Map.of();
        }
        Set<LocalDate> starts = nearby.stream().map(PayPeriod::start).collect(Collectors.toSet());
        Map<ItemDate, Long> itemTaken = new HashMap<>();
        Map<Long, Long> entryTaken = new HashMap<>();
        for (Line line : uploads.linesOfAccount(accountId)) {
            StatementUploadsRepository.Match match = line.match();
            if (match == null) {
                continue;
            }
            if (match.entryId() != null) {
                entryTaken.put(match.entryId(), line.id());
            } else {
                itemTaken.put(match.itemDate(), line.id());
            }
        }
        // Everything there is to match, by the pay period it is in now.
        Map<LocalDate, List<MatchableView>> byNearby = new HashMap<>();
        for (Occurrence occurrence : calendar.expand(nearby.getFirst().start(), nearby.getLast().end()).occurrences()) {
            if (occurrence.periodStart() == null || !starts.contains(occurrence.periodStart()) || occurrence.payFromAccountId() != accountId
                    || occurrence.skipped() || occurrence.invoiceId() != null || occurrence.plannedPack()
                    || occurrence.kind() == ItemKind.BUDGET || occurrence.kind() == ItemKind.BUCKET_MOVE) {
                continue;
            }
            byNearby.computeIfAbsent(occurrence.periodStart(), start -> new ArrayList<>()).add(new MatchableView(
                    occurrence.itemId(), occurrence.scheduledDate(), null, occurrence.name(), occurrence.payBy(), occurrence.amountCents(),
                    occurrence.paid(), occurrence.periodStart(), itemTaken.get(new ItemDate(occurrence.itemId(), occurrence.scheduledDate()))));
        }
        for (EntriesRepository.Entry entry : entries.between(nearby.getFirst().start(), nearby.getLast().end())) {
            if (entry.accountId() != accountId || entry.kind() != EntryKind.MONEY_OUT) {
                continue;
            }
            BudgetActuals.periodOf(entry.date(), nearby).ifPresent(period -> byNearby.computeIfAbsent(period.start(), key -> new ArrayList<>())
                    .add(new MatchableView(null, null, entry.id(), entry.description(), entry.date(), entry.amountCents(), true, period.start(),
                            entryTaken.get(entry.id()))));
        }
        // Each period's own, with the one before and the one after.
        List<LocalDate> nearbyStarts = nearby.stream().map(PayPeriod::start).toList();
        Map<LocalDate, List<MatchableView>> byPeriod = new HashMap<>();
        for (PayPeriod period : periods) {
            int at = nearbyStarts.indexOf(period.start());
            List<MatchableView> list = new ArrayList<>();
            for (int each = Math.max(0, at - 1); at >= 0 && each <= Math.min(nearby.size() - 1, at + 1); each++) {
                list.addAll(byNearby.getOrDefault(nearbyStarts.get(each), List.of()));
            }
            list.sort(Comparator.comparing(MatchableView::date).thenComparing(MatchableView::name));
            byPeriod.put(period.start(), list);
        }
        return byPeriod;
    }

    /** Request body for matching a line: a recurring item's date ({@code itemId}, {@code scheduledDate}) or a one-off ({@code entryId}). */
    public record MatchInput(Long itemId, LocalDate scheduledDate, Long entryId) {}

    /**
     * Chooses what a line already is in Budget (Rachel, 2026-10-30). On Apply, a recurring item's date takes the charge as
     * its amount and is ticked paid on the day it was spent; a one-off is only linked. Either way the line is no longer on a
     * budget or to be added, so nothing is counted twice.
     */
    private void match(Context ctx) {
        Upload upload = found(ctx);
        Line line = ownLine(ctx, upload);
        MatchInput input = Inputs.body(ctx, MatchInput.class);
        boolean toEntry = input.entryId() != null;
        if (toEntry == (input.itemId() != null || input.scheduledDate() != null) || !toEntry && (input.itemId() == null || input.scheduledDate() == null)) {
            throw ApiException.badRequest("Please choose what this charge is from the list.");
        }
        if (line.amountCents() <= 0) {
            throw ApiException.badRequest("Only money spent can be matched to a bill or a one-off.");
        }
        if (line.duplicateOf() != null || line.oneOffEntryId() != null) {
            throw ApiException.conflict(line.duplicateOf() != null
                    ? "This charge is on an earlier statement too; match it there."
                    : "This charge was added as a one-off already.");
        }
        PayPeriod period = periodOfLine(upload, line);
        MatchableView chosen = matchable(upload.accountId(), List.of(period)).getOrDefault(period.start(), List.of()).stream()
                .filter(each -> toEntry
                        ? input.entryId().equals(each.entryId())
                        : input.itemId().equals(each.itemId()) && input.scheduledDate().equals(each.scheduledDate()))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("Please choose something from this charge's pay period or the one either side."));
        if (chosen.matchedLineId() != null && chosen.matchedLineId() != line.id()) {
            throw ApiException.conflict("Another charge is already matched to " + chosen.name() + ". Unmatch that one first.");
        }
        letGo(upload, line);
        uploads.setBudget(List.of(line.id()), null);
        uploads.setAddOneOff(line.id(), false);
        uploads.setMatch(line.id(), toEntry
                ? StatementUploadsRepository.Match.entry(input.entryId())
                : StatementUploadsRepository.Match.item(input.itemId(), input.scheduledDate()));
        ApiResponse.ok(ctx, view(upload, List.of()));
    }

    /** Lets go of what a line was matched to; a bill's date already written is put back on the next Apply. */
    private void unmatch(Context ctx) {
        Upload upload = found(ctx);
        Line line = ownLine(ctx, upload);
        letGo(upload, line);
        ApiResponse.ok(ctx, view(upload, List.of()));
    }

    /** Forgets a line's match, remembering a bill's date that Apply wrote so the next Apply puts it back. */
    private void letGo(Upload upload, Line line) {
        StatementUploadsRepository.Match match = line.match();
        if (match == null) {
            return;
        }
        if (match.applied() && match.itemId() != null) {
            uploads.saveRestore(upload.accountId(),
                    new StatementUploadsRepository.Restore(match.itemDate(), periodOfLine(upload, line).start(), match.before()));
        }
        uploads.clearMatch(line.id());
    }

    private PayPeriod periodOfLine(Upload upload, Line line) {
        return BudgetActuals.periodOf(line.txnDate(), pay.payPeriods(upload.from(), upload.to()))
                .orElseThrow(() -> ApiException.badRequest("This charge is in no pay period."));
    }

    /** Chooses a line to be added as a one-off on the day it was spent, with its category and tags, on the next Apply. */
    private void oneOff(Context ctx) {
        Upload upload = found(ctx);
        Line line = ownLine(ctx, upload);
        if (line.duplicateOf() != null || line.oneOffEntryId() != null) {
            throw ApiException.conflict(line.duplicateOf() != null
                    ? "This charge is on an earlier statement too; add it from there."
                    : "This charge was added as a one-off already.");
        }
        letGo(upload, line);
        uploads.setBudget(List.of(line.id()), null);
        uploads.setAddOneOff(line.id(), true);
        ApiResponse.ok(ctx, view(upload, List.of()));
    }

    /** Takes back the choice to add a line as a one-off, before Apply has added it. */
    private void noOneOff(Context ctx) {
        Upload upload = found(ctx);
        Line line = ownLine(ctx, upload);
        if (line.oneOffEntryId() != null) {
            throw ApiException.conflict("This charge was added as a one-off already. To take it back, delete that one-off.");
        }
        uploads.setAddOneOff(line.id(), false);
        ApiResponse.ok(ctx, view(upload, List.of()));
    }

    private Override storedOverride(long itemId, LocalDate scheduledDate) {
        return items.overrides(scheduledDate, scheduledDate).stream()
                .filter(edit -> edit.itemId() == itemId)
                .findFirst()
                .orElse(Override.untouched(itemId, scheduledDate));
    }

    /** The line named in the path, which must be this upload's own. */
    private Line ownLine(Context ctx, Upload upload) {
        long lineId = Inputs.id(ctx, "lineId");
        return uploads.line(lineId).filter(line -> line.uploadId() == upload.id()).orElseThrow(ApiException::notFound);
    }

    /**
     * Request body for what the statement says: its balances (on a card what is owed, on a bank account what it holds) and
     * its own totals of charges and of payments and credits, each zero or more; null for unsaid.
     */
    public record BalancesInput(Long previousCents, Long newCents, Long chargesCents, Long creditsCents) {}

    private void balances(Context ctx) {
        Upload upload = found(ctx);
        BalancesInput input = Inputs.body(ctx, BalancesInput.class);
        uploads.setBalances(upload.id(),
                input.previousCents() == null ? null : Inputs.signedAmount(input.previousCents(), "The previous balance"),
                input.newCents() == null ? null : Inputs.signedAmount(input.newCents(), "The new balance"),
                input.chargesCents() == null ? null : Inputs.amount(input.chargesCents(), "The total of charges"),
                input.creditsCents() == null ? null : Inputs.amount(input.creditsCents(), "The total of payments and credits"));
        ApiResponse.ok(ctx, view(uploads.find(upload.id()).orElseThrow(), List.of()));
    }

    /** Request body for still to come on a budget date: {@code cents} null works it out again. */
    public record StillInput(Long itemId, LocalDate scheduledDate, Long cents) {}

    /** Types still to come for one of the statement's budget dates; like everything here it changes nothing until Apply. */
    private void stillToCome(Context ctx) {
        Upload upload = found(ctx);
        StillInput input = Inputs.body(ctx, StillInput.class);
        if (input.itemId() == null || input.scheduledDate() == null || budgetDates(pay.payPeriods(upload.from(), upload.to()))
                .stream().noneMatch(date -> date.itemId() == input.itemId() && date.scheduledDate().equals(input.scheduledDate()))) {
            throw ApiException.badRequest("Please choose one of this statement's budget dates.");
        }
        uploads.setTypedStill(new ItemDate(input.itemId(), input.scheduledDate()),
                input.cents() == null ? null : Inputs.amount(input.cents(), "Still to come"));
        ApiResponse.ok(ctx, changes(upload));
    }

    /** Request body for applying: the pay periods whose changes to make. */
    public record ApplyInput(List<LocalDate> periods) {}

    /** What was applied, and how things stand after. */
    public record Applied(int amountsChanged, ChangesView changes) {}

    /**
     * Makes every choice in the chosen pay periods real, in one Undo (Rachel, 2026-10-30: everything waits for Apply). Each
     * budget date becomes what was spent plus still to come, a date with every charge taken off goes back to its budget,
     * matched bills take their charge and are ticked paid on its day, bills let go of are put back, and one-offs are added.
     */
    private void apply(Context ctx) {
        Upload upload = found(ctx);
        ApplyInput input = Inputs.body(ctx, ApplyInput.class);
        List<LocalDate> chosen = input.periods() == null ? List.of() : input.periods().stream().distinct().toList();
        if (chosen.contains(null)) {
            throw ApiException.badRequest("Please choose the pay periods to apply from the list.");
        }
        if (chosen.isEmpty()) {
            throw ApiException.badRequest("Please choose at least one pay period to apply.");
        }
        List<PayPeriod> periods = pay.payPeriods(upload.from(), upload.to());
        Worked worked = worked(upload);
        for (LocalDate period : chosen) {
            if (!worked.results().containsKey(period)) {
                throw ApiException.badRequest("Please choose the pay periods to apply from the list.");
            }
        }

        int changed = 0;
        for (LocalDate start : chosen) {
            for (Result result : worked.results().get(start)) {
                ItemDate key = new ItemDate(result.itemId(), result.scheduledDate());
                Override stored = storedOverride(result.itemId(), result.scheduledDate());
                if (result.newCents() != result.currentCents()) {
                    items.saveOverride(stored.withAmount(result.newCents(), result.currentCents()));
                    changed++;
                }
                if (result.lineIds().isEmpty()) {
                    // Every charge taken off: back to what was budgeted, no longer set by a statement.
                    uploads.deleteReplaced(key);
                } else {
                    uploads.saveReplaced(key, new Replaced(result.budgetedCents(), result.newCents(), result.actualCents(), result.stillToComeCents()));
                    uploads.setBudgetDate(result.lineIds(), result.scheduledDate());
                }
                changed += applyElsewhere(result);
            }
        }
        Set<LocalDate> chosenStarts = Set.copyOf(chosen);
        Map<ItemDate, StatementUploadsRepository.Restore> restores = new HashMap<>();
        uploads.restores(upload.accountId()).forEach(restore -> restores.put(restore.date(), restore));
        for (Line line : uploads.linesOfAccount(upload.accountId())) {
            Optional<PayPeriod> period = BudgetActuals.periodOf(line.txnDate(), periods);
            if (period.isEmpty() || !chosenStarts.contains(period.get().start())) {
                continue;
            }
            StatementUploadsRepository.Match match = line.match();
            if (match != null && !match.applied()) {
                if (match.itemId() == null) {
                    uploads.setMatchApplied(line.id(), StatementUploadsRepository.Before.NOTHING);
                } else {
                    // A date let go of by another charge and not yet put back keeps what it was before either.
                    Override stored = storedOverride(match.itemId(), match.scheduledDate());
                    StatementUploadsRepository.Restore waiting = restores.remove(match.itemDate());
                    StatementUploadsRepository.Before before = waiting != null
                            ? waiting.before()
                            : new StatementUploadsRepository.Before(stored.amountCents(), stored.paid(), stored.paidDate(), stored.projectedCents());
                    if (waiting != null) {
                        uploads.deleteRestore(match.itemDate());
                    }
                    long shown = items.find(match.itemId()).flatMap(item -> calendar.find(item, match.scheduledDate()))
                            .map(Occurrence::amountCents).orElse(line.amountCents());
                    Override paid = stored.withAmount(line.amountCents(), shown);
                    items.saveOverride(new Override(paid.itemId(), paid.originalDueDate(), paid.amountCents(), paid.dueDate(), paid.periodOverride(),
                            true, line.txnDate(), paid.confirmationCode(), paid.note(), paid.skipped(), paid.projectedCents(), paid.checkNumber(),
                            paid.payFromAccountId()));
                    uploads.setMatchApplied(line.id(), before);
                    changed++;
                }
            }
            if (line.addOneOff() && line.oneOffEntryId() == null) {
                addOneOff(upload, line);
                changed++;
            }
        }
        for (StatementUploadsRepository.Restore restore : restores.values()) {
            if (!chosenStarts.contains(restore.periodStart())) {
                continue;
            }
            Override now = storedOverride(restore.date().itemId(), restore.date().scheduledDate());
            StatementUploadsRepository.Before before = restore.before();
            items.saveOverride(new Override(now.itemId(), now.originalDueDate(), before.amountCents(), now.dueDate(), now.periodOverride(),
                    before.paid(), before.paidDate(), now.confirmationCode(), now.note(), now.skipped(), before.projectedCents(),
                    now.checkNumber(), now.payFromAccountId()));
            uploads.deleteRestore(restore.date());
            changed++;
        }
        uploads.markApplied(upload.id());
        ApiResponse.ok(ctx, new Applied(changed, changes(upload)));
    }

    /**
     * Writes what was spent on a budget date from each other account as one one-off there, with the budget's name,
     * category and tags, dated the last of those charges; updates it when it is there already, and removes those whose
     * charges were all taken off (Rachel, 2026-10-30).
     *
     * @return how many one-offs were added, changed or removed
     */
    private int applyElsewhere(Result result) {
        ItemDate key = new ItemDate(result.itemId(), result.scheduledDate());
        Map<Long, Long> links = uploads.elsewhere(key);
        if (result.elsewhere().isEmpty() && links.isEmpty()) {
            return 0;
        }
        Occurrence budget = items.find(result.itemId()).flatMap(item -> calendar.find(item, result.scheduledDate())).orElse(null);
        List<Long> tagIds = items.find(result.itemId()).map(ItemsRepository.Item::tagIds).orElse(List.of());
        int changed = 0;
        for (BudgetActuals.Elsewhere each : result.elsewhere()) {
            long cents = Math.max(0, each.cents());
            Long entryId = links.remove(each.accountId());
            Optional<EntriesRepository.Entry> entry = entryId == null ? Optional.empty() : entries.find(entryId);
            if (cents == 0 || budget == null) {
                // More given back than spent there leaves nothing to record on that account.
                if (entry.isPresent()) {
                    entries.delete(entryId);
                    changed++;
                }
                uploads.deleteElsewhere(key, each.accountId());
                continue;
            }
            EntriesRepository.Fields fields = new EntriesRepository.Fields(EntryKind.MONEY_OUT, each.lastDay(), cents, false, budget.name(), null,
                    budget.categoryId(), each.accountId(), null, null, tagIds, List.of(), null, null, null, null);
            if (entry.isPresent()) {
                if (entry.get().amountCents() != cents || !entry.get().date().equals(each.lastDay())) {
                    entries.update(entryId, fields);
                    changed++;
                }
            } else {
                uploads.saveElsewhere(key, each.accountId(), entries.create(fields));
                changed++;
            }
        }
        // An account none of its charges are from any more.
        for (Map.Entry<Long, Long> gone : links.entrySet()) {
            if (entries.find(gone.getValue()).isPresent()) {
                entries.delete(gone.getValue());
                changed++;
            }
            uploads.deleteElsewhere(key, gone.getKey());
        }
        return changed;
    }

    /** Adds a line as a one-off of its own on the day it was spent, with its category and tags. */
    private void addOneOff(Upload upload, Line line) {
        boolean back = line.amountCents() < 0;
        long entryId = entries.create(new EntriesRepository.Fields(
                back ? EntryKind.MONEY_IN : EntryKind.MONEY_OUT,
                line.txnDate(),
                Math.abs(line.amountCents()),
                false,
                line.description(),
                null,
                line.categoryId(),
                upload.accountId(),
                null,
                null,
                line.tagIds(),
                List.of(),
                null,
                null,
                null,
                null));
        uploads.setOneOff(line.id(), entryId);
    }

    // ---------------------------------------------------------------- on the pay period

    /**
     * A budget date a statement set, as the pay period shows it: what was budgeted, what was spent and what is still to
     * come, with the charges behind it (Rachel, 2026-10-30: "on pay period, hover on a budget shows budgeted amount. maybe we
     * can click into it to see the actual row level items").
     *
     * @param appliedCents what the statement set it to; the pay period shows these figures only while it still is that
     * @param elsewhereCents what was spent on it from other accounts, as one-offs there
     */
    public record StatementBudgetView(
            long itemId, LocalDate scheduledDate, long budgetedCents, long actualCents, long stillToComeCents, long appliedCents,
            List<ChargeLine> charges, long elsewhereCents) {}

    private void statementBudgets(Context ctx) {
        LocalDate from = Inputs.dateParam(ctx, "from", pay.today());
        LocalDate to = Inputs.dateParam(ctx, "to", from);
        if (to.isBefore(from) || from.plusDays(400).isBefore(to)) {
            throw ApiException.badRequest("Please choose a range of a year or less.");
        }
        Map<Long, Optional<Upload>> seen = new HashMap<>();
        List<StatementBudgetView> views = new ArrayList<>();
        uploads.replacedBetween(from, to).forEach((key, replaced) -> {
            if (replaced.actualCents() == null) {
                return;
            }
            List<ChargeLine> charges = uploads.onBudgetDate(key.itemId(), key.scheduledDate()).stream()
                    .map(line -> chargeLine(line, seen))
                    .toList();
            long elsewhere = uploads.elsewhere(key).values().stream()
                    .map(entries::find)
                    .flatMap(Optional::stream)
                    .mapToLong(EntriesRepository.Entry::amountCents)
                    .sum();
            views.add(new StatementBudgetView(key.itemId(), key.scheduledDate(), replaced.baseCents(), replaced.actualCents(),
                    replaced.stillCents() == null ? 0 : replaced.stillCents(), replaced.appliedCents(), charges, elsewhere));
        });
        views.sort(Comparator.comparing(StatementBudgetView::scheduledDate).thenComparing(StatementBudgetView::itemId));
        ApiResponse.ok(ctx, views);
    }

    /** Request body for still to come on the pay period: {@code cents} null works it out again. */
    public record StillOnPeriodInput(Long cents) {}

    /**
     * Types still to come for a budget date a statement set, from the pay period: the date's amount becomes what was spent
     * plus it straight away, as typing an amount on the pay period does.
     */
    private void stillOnPeriod(Context ctx) {
        long itemId = Inputs.id(ctx, "itemId");
        LocalDate date = Inputs.date(ctx.pathParam("date"));
        Long cents = Inputs.body(ctx, StillOnPeriodInput.class).cents();
        ItemDate key = new ItemDate(itemId, date);
        Replaced replaced = uploads.replaced(List.of(itemId)).get(key);
        if (replaced == null || replaced.actualCents() == null) {
            throw ApiException.notFound();
        }
        Occurrence occurrence = items.find(itemId).flatMap(item -> calendar.find(item, date)).orElseThrow(ApiException::notFound);
        if (occurrence.periodStart() == null) {
            throw ApiException.notFound();
        }
        uploads.setTypedStill(key, cents == null ? null : Inputs.amount(cents, "Still to come"));
        List<PayPeriod> periods = pay.payPeriods(occurrence.periodStart(), occurrence.periodStart());
        Result result = results(periods, budgetDates(periods)).stream()
                .filter(each -> each.itemId() == itemId && each.scheduledDate().equals(date))
                .findFirst()
                .orElseThrow(ApiException::notFound);
        if (result.newCents() != occurrence.amountCents()) {
            items.saveOverride(storedOverride(itemId, date).withAmount(result.newCents(), occurrence.amountCents()));
        }
        uploads.saveReplaced(key, new Replaced(result.budgetedCents(), result.newCents(), result.actualCents(), result.stillToComeCents()));
        ApiResponse.ok(ctx, new StatementBudgetView(itemId, date, result.budgetedCents(), result.actualCents(), result.stillToComeCents(),
                result.newCents(), List.of(), Math.max(0, result.elsewhereCents())));
    }

    // ---------------------------------------------------------------- helpers

    private UploadView view(Upload upload, List<Integer> skippedRows) {
        Account account = accounts.find(upload.accountId()).orElseThrow(ApiException::notFound);
        List<PayPeriod> periods = pay.payPeriods(upload.from(), upload.to());
        List<BudgetDate> dates = budgetDates(periods);
        Map<String, Long> hints = uploads.budgetHints(account.id());
        List<Line> lines = uploads.lines(upload.id());
        List<LineView> views = lines.stream()
                .map(line -> {
                    Long hint = hints.get(line.payeeKey());
                    boolean offered = hint != null && line.open() && line.budgetItemId() == null
                            && BudgetActuals.dateFor(line.txnDate(), hint, periods, dates).isPresent();
                    return new LineView(line.id(), line.txnDate(), line.postedDate(), line.description(), line.amountCents(), line.kind(),
                            line.leftOut(), line.sure(), line.duplicateOf(), line.categoryId(), line.tagIds(), line.oneOffEntryId(),
                            line.addOneOff(), BudgetActuals.periodOf(line.txnDate(), periods).map(PayPeriod::start).orElse(null),
                            MatchedView.of(line.match()), line.budgetItemId(), offered ? hint : null);
                })
                .toList();
        int toCheck = (int) lines.stream().filter(line -> !line.sure()).count();
        StatementCheck.Result check = StatementCheck.of(onCard(account), upload.previousCents(), upload.newCents(),
                lines.stream().map(Line::amountCents).toList());
        return new UploadView(upload.id(), account.id(), account.name(), onCard(account), upload.name(), upload.format(), upload.from(),
                upload.to(), upload.appliedAt(), upload.pages(), views, toCheck, skippedRows,
                new CheckView(upload.previousCents(), upload.newCents(), upload.chargesCents(), upload.creditsCents(), check.spentCents(),
                        check.backCents(), check.expectedNewCents(), check.differenceCents()));
    }

    private static Long cents(Money money) {
        return money == null ? null : money.cents();
    }

    private boolean categoryExists(Long id) {
        return id != null && categories.find(id).isPresent();
    }

    private static boolean onCard(Account account) {
        return account.type().equals(Inputs.code(AccountType.CREDIT_CARD));
    }

    /** Spending happens from checking, savings and cards; a loan's or an investment's statement isn't spending. */
    private static boolean spends(Account account) {
        return onCard(account)
                || account.type().equals(Inputs.code(AccountType.CHECKING))
                || account.type().equals(Inputs.code(AccountType.SAVINGS));
    }

    private static String trimmed(String description) {
        String plain = description.replaceAll("\\s+", " ").trim();
        return plain.length() > DESCRIPTION_MAX ? plain.substring(0, DESCRIPTION_MAX).trim() : plain;
    }

    private UploadedFile oneFile(Context ctx) {
        List<UploadedFile> sent = files.uploads(ctx, 1);
        if (sent.size() != 1) {
            throw ApiException.badRequest("Please choose one statement at a time.");
        }
        return sent.get(0);
    }

    /** The file's bytes, up to the upload limit. */
    private byte[] bytes(UploadedFile sent) {
        try (InputStream content = sent.content()) {
            byte[] bytes = content.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, files.maxBytes() + 1));
            if (bytes.length > files.maxBytes()) {
                throw files.tooLarge();
            }
            if (bytes.length == 0) {
                throw ApiException.badRequest("That file is empty. Please choose the statement again.");
            }
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read an uploaded statement", e);
        }
    }

    private static boolean startsWithPdf(byte[] content) {
        return content.length >= 5 && content[0] == '%' && content[1] == 'P' && content[2] == 'D' && content[3] == 'F' && content[4] == '-';
    }

    /**
     * The file as text: UTF-8 when it is that, otherwise the Windows code page older bank downloads use. A file with
     * nothing but text in it is a CSV or OFX; anything with binary in it is neither.
     */
    private static String text(byte[] content) {
        for (byte b : content) {
            if (b == 0) {
                throw new ApiException(415, NOT_A_STATEMENT);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(content, Charset.forName("windows-1252"));
        }
    }

    private Account account(Context ctx) {
        return accounts.find(Inputs.id(ctx, "id")).orElseThrow(ApiException::notFound);
    }

    private Upload found(Context ctx) {
        return uploads.find(Inputs.id(ctx, "id")).orElseThrow(ApiException::notFound);
    }
}
