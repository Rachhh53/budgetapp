package app.budget.server.uploads;

import app.budget.engine.schedule.PayPeriod;
import app.budget.engine.statements.CsvStatement;
import app.budget.engine.statements.Duplicates;
import app.budget.engine.statements.LineKinds;
import app.budget.engine.statements.OfxStatement;
import app.budget.engine.statements.PayeeKey;
import app.budget.engine.statements.PdfStatement;
import app.budget.engine.statements.Replacement;
import app.budget.engine.statements.Replacement.Change;
import app.budget.engine.statements.Replacement.Charge;
import app.budget.engine.statements.Replacement.Coverage;
import app.budget.engine.statements.Replacement.Group;
import app.budget.engine.statements.Replacement.GroupTotal;
import app.budget.engine.statements.Replacement.GroupType;
import app.budget.engine.statements.Replacement.Mode;
import app.budget.engine.statements.Replacement.Plan;
import app.budget.engine.statements.Replacement.Slot;
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
import app.budget.server.categories.CategoriesRepository.Category;
import app.budget.server.categories.TagsRepository;
import app.budget.server.categories.TagsRepository.Tag;
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
import app.budget.server.items.ItemsRepository.Item;
import app.budget.server.items.ItemsRepository.Override;
import app.budget.server.uploads.StatementUploadsRepository.Format;
import app.budget.server.uploads.StatementUploadsRepository.ItemDate;
import app.budget.server.uploads.StatementUploadsRepository.Line;
import app.budget.server.uploads.StatementUploadsRepository.NewLine;
import app.budget.server.uploads.StatementUploadsRepository.PayeeRule;
import app.budget.server.uploads.StatementUploadsRepository.Replaced;
import app.budget.server.uploads.StatementUploadsRepository.Target;
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
 * Statement uploads (Rachel, 2026-10-25): a card or bank statement is added to its account, read on this PC, its
 * charges filed by category and tag (remembered by payee), and totalled by pay period, the day each was spent deciding
 * which. For each total Rachel chooses the budget or recurring item of that pay period it changes, if any, and whether it
 * takes the item's amount's place or is added to it; nothing is chosen for her. Applying changes "just this one" amounts
 * and nothing else: dates, paid ticks and the statement's lines themselves never count as money.
 *
 * <p>Nothing a statement says is logged: it says where someone spends.
 */
public final class StatementUploadsRoutes {

    private static final Logger log = LoggerFactory.getLogger(StatementUploadsRoutes.class);

    /** A statement is a dozen pages at most; anything longer is some other document. */
    private static final int MOST_PAGES = 40;
    private static final int DESCRIPTION_MAX = 120;
    private static final int MAX_TAGS = 20;
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
        routes.put("/api/statement-uploads/{id}/lines/{lineId}/match", this::match);
        routes.delete("/api/statement-uploads/{id}/lines/{lineId}/match", this::unmatch);
        routes.post("/api/statement-uploads/{id}/lines/{lineId}/one-off", this::oneOff);
        routes.get("/api/statement-uploads/{id}/changes", ctx -> ApiResponse.ok(ctx, changes(found(ctx))));
        routes.put("/api/statement-uploads/{id}/targets", this::target);
        routes.post("/api/statement-uploads/{id}/apply", this::apply);
        routes.get("/api/statement-uploads/{id}/file", this::download);
        routes.get("/api/statement-uploads/{id}/preview", this::preview);
    }

    // ---------------------------------------------------------------- views

    /** An upload in an account's list. */
    public record UploadSummary(
            long id, String name, Format format, LocalDate from, LocalDate to, String appliedAt, int lineCount, int toCheck) {}

    /** A category or a tag, by its kind and id, as the screens send and receive it. */
    public record GroupView(GroupType type, long id) {

        static GroupView of(Group group) {
            return group == null ? null : new GroupView(group.type(), group.id());
        }
    }

    /**
     * One line of a statement.
     *
     * @param periodStart  the pay period the day it was spent falls in; null outside every period
     * @param duplicateOf  the line of an earlier upload it repeats, which is counted instead
     * @param countsToward the group chosen when its category and a tag are both pointed at items
     * @param matched      what Budget already had that it is, as its pay period's {@code matchable} names it; null for none
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
            GroupView countsToward,
            Long oneOffEntryId,
            LocalDate periodStart,
            boolean newPayee,
            MatchedView matched) {}

    /** What a line was matched to: a recurring item's date, or a one-off entry. */
    public record MatchedView(Long itemId, LocalDate scheduledDate, Long entryId) {

        static MatchedView of(StatementUploadsRepository.Match match) {
            return match == null ? null : new MatchedView(match.itemId(), match.scheduledDate(), match.entryId());
        }
    }

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
            List<Integer> skippedRows) {}

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
        long id = uploads.create(account.id(), kept, name, format, from.isAfter(to) ? to : from, to, toKeep);
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

    /**
     * Request body for one line.
     *
     * @param countsToward when its category and a tag are both pointed at items, the one it counts toward; null to choose
     *                     later
     */
    public record LineInput(
            LocalDate txnDate,
            String description,
            Long amountCents,
            String kind,
            Boolean leftOut,
            Long categoryId,
            List<Long> tagIds,
            GroupInput countsToward) {}

    /** A category or a tag as sent: {@code type} is {@code category} or {@code tag}. */
    public record GroupInput(String type, Long id) {

        Group group() {
            GroupType chosen = Inputs.choice(GroupType.class, type, "its category or one of its tags");
            if (id == null) {
                throw ApiException.badRequest("Please choose its category or one of its tags for it to count toward.");
            }
            return new Group(chosen, id);
        }
    }

    /** The upload after a line was changed, and how many other lines from its payee were filed the same way. */
    public record LineChanged(UploadView upload, int alsoFiled) {}

    private void changeLine(Context ctx) {
        Upload upload = found(ctx);
        Line line = uploads.line(Inputs.id(ctx, "lineId"))
                .filter(found -> found.uploadId() == upload.id())
                .orElseThrow(ApiException::notFound);
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
        Group countsToward = input.countsToward() == null ? null : input.countsToward().group();
        if (countsToward != null && !(countsToward.type() == GroupType.CATEGORY
                ? Objects.equals(countsToward.id(), categoryId)
                : tagIds.contains(countsToward.id()))) {
            throw ApiException.badRequest("Please choose its own category or one of its own tags for it to count toward.");
        }

        String key = PayeeKey.of(description);
        uploads.updateLine(line.id(), new StatementUploadsRepository.LineFields(
                txnDate, description, key, amount, kind, leftOut, categoryId, tagIds, countsToward));
        boolean filed = !Objects.equals(categoryId, line.categoryId()) || !new HashSet<>(tagIds).equals(new HashSet<>(line.tagIds()));
        int alsoFiled = 0;
        if (filed) {
            // Remembered for the next statement, and given now to this payee's other lines with nothing on them yet.
            uploads.rememberPayee(key, categoryId, tagIds);
            alsoFiled = uploads.fillFromPayee(upload.accountId(), line.id(), key, categoryId, tagIds);
        }
        ApiResponse.ok(ctx, new LineChanged(view(uploads.find(upload.id()).orElseThrow(), List.of()), alsoFiled));
    }

    // ---------------------------------------------------------------- what changes

    /** A category or tag's total in one period, with the item it is pointed at (if any) and a suggestion. */
    public record GroupTotalView(
            GroupType type, long id, String name, String color, long totalCents, List<Long> lineIds, TargetView target, Long hintItemId) {}

    public record TargetView(long itemId, Mode mode) {}

    /** One date of an item that can be chosen in a period. */
    public record SlotView(LocalDate scheduledDate, LocalDate countsOn, long currentCents, boolean estimate) {}

    /** A budget or recurring item paid from the account with a date in the period: what a total can be pointed at. */
    public record ChoiceView(long itemId, String name, ItemKind kind, Long categoryId, List<Long> tagIds, List<SlotView> dates) {}

    /** One date's amount now and what the totals pointed at it make it. */
    public record ChangeView(long itemId, LocalDate scheduledDate, long currentCents, long newCents, List<Long> lineIds) {}

    /**
     * Something already in Budget, paid from the account in the period, that a line can be matched to: a date of a recurring
     * item other than a budget (a budget is what the totals are for), or a one-off spent from the account.
     *
     * @param matchedLineId the line of this account's statements matched to it already; null for none
     */
    public record MatchableView(
            Long itemId, LocalDate scheduledDate, Long entryId, String name, LocalDate date, long amountCents, boolean paid, Long matchedLineId) {}

    /**
     * One pay period the upload reaches into.
     *
     * @param coverage  the days of it this account's statements cover; {@code partly} when some are on none of them
     * @param matchable what a line dated in it can be matched to, by date
     * @param upToDate  every change is already the amount it would set: nothing to apply
     */
    public record PeriodView(
            LocalDate start,
            LocalDate end,
            boolean current,
            CoverageView coverage,
            List<GroupTotalView> groups,
            List<ChoiceView> items,
            List<MatchableView> matchable,
            List<ChangeView> changes,
            List<Long> undecided,
            List<Long> unmatched,
            boolean upToDate) {}

    public record CoverageView(LocalDate from, LocalDate to, boolean partly) {}

    /** A line of any upload of the account that a period's totals count, for showing where a total comes from. */
    public record CountedLine(
            long id, long uploadId, String uploadName, LocalDate txnDate, String description, long amountCents, Long categoryId,
            List<Long> tagIds, GroupView countsToward) {}

    public record ChangesView(long uploadId, long accountId, List<PeriodView> periods, List<Long> outside, List<CountedLine> lines) {}

    private ChangesView changes(Upload upload) {
        return worked(upload).view();
    }

    /** Everything worked out for an upload's pay periods, kept together so applying uses exactly what was shown. */
    private record Worked(ChangesView view, Map<LocalDate, List<Change>> changes, Map<LocalDate, List<Long>> undecided,
            Map<ItemDate, Long> bases, Map<ItemDate, Long> currents, List<Line> counted) {}

    private Worked worked(Upload upload) {
        long accountId = upload.accountId();
        List<PayPeriod> periods = pay.payPeriods(upload.from(), upload.to());
        List<Line> counted = uploads.linesOfAccount(accountId).stream().filter(Line::counted).toList();
        List<Charge> charges = counted.stream()
                .filter(line -> Replacement.periodOf(line.txnDate(), periods).isPresent())
                .map(line -> new Charge(line.id(), line.txnDate(), line.amountCents(), line.categoryId(), line.tagIds(), line.countsToward()))
                .toList();
        // This upload's own lines in no pay period, to say so.
        List<Long> outside = uploads.lines(upload.id()).stream()
                .filter(Line::counted)
                .filter(line -> Replacement.periodOf(line.txnDate(), periods).isEmpty())
                .map(Line::id)
                .toList();

        // The budgets and bills paid from this account with a date in each period.
        LocalDate from = periods.isEmpty() ? upload.from() : periods.getFirst().start();
        LocalDate to = periods.isEmpty() ? upload.to() : periods.getLast().end();
        Set<LocalDate> starts = periods.stream().map(PayPeriod::start).collect(Collectors.toSet());
        Map<Long, Item> itemsById = new HashMap<>();
        items.all().forEach(item -> itemsById.put(item.id(), item));
        List<Occurrence> paidFromAccount = calendar.expand(from, to).occurrences().stream()
                .filter(occurrence -> occurrence.periodStart() != null && starts.contains(occurrence.periodStart()))
                .filter(occurrence -> occurrence.payFromAccountId() == accountId)
                .filter(occurrence -> !occurrence.skipped() && occurrence.invoiceId() == null && !occurrence.plannedPack())
                .toList();
        List<Occurrence> choices = paidFromAccount.stream()
                .filter(occurrence -> occurrence.kind() == ItemKind.BILL || occurrence.kind() == ItemKind.BUDGET)
                .toList();
        Map<LocalDate, List<MatchableView>> matchable = matchable(accountId, periods, paidFromAccount);
        Map<ItemDate, Replaced> replaced = uploads.replaced(choices.stream().map(Occurrence::itemId).distinct().toList());
        Map<ItemDate, Long> bases = new HashMap<>();
        Map<ItemDate, Long> currents = new HashMap<>();
        List<Slot> slots = new ArrayList<>();
        for (Occurrence occurrence : choices) {
            ItemDate key = new ItemDate(occurrence.itemId(), occurrence.scheduledDate());
            Replaced before = replaced.get(key);
            // Changed by hand since a statement set it, what it is now is where Add starts from.
            long base = before != null && before.appliedCents() == occurrence.amountCents() ? before.baseCents() : occurrence.amountCents();
            bases.put(key, base);
            currents.put(key, occurrence.amountCents());
            slots.add(new Slot(occurrence.itemId(), occurrence.scheduledDate(), occurrence.periodStart(), occurrence.payBy(),
                    occurrence.amountCents(), base));
        }

        List<Target> targets = uploads.targets(accountId).stream().filter(target -> starts.contains(target.periodStart())).toList();
        Plan plan = Replacement.plan(charges, periods, targets.stream()
                .map(target -> new Replacement.Target(target.periodStart(), target.group(), target.itemId(), target.mode()))
                .toList(), slots);
        List<GroupTotal> totals = Replacement.totals(charges, periods);
        Map<LocalDate, Coverage> coverage = new HashMap<>();
        Replacement.coverage(periods, uploads.spans(accountId)).forEach(covered -> coverage.put(covered.periodStart(), covered));
        Map<Group, Long> hints = uploads.hints(accountId);
        Map<Long, Category> categoryById = new HashMap<>();
        categories.all().forEach(category -> categoryById.put(category.id(), category));
        Map<Long, Tag> tagById = new HashMap<>();
        tags.all().forEach(tag -> tagById.put(tag.id(), tag));
        Set<Long> undecided = new HashSet<>(plan.undecided());
        Set<Long> unmatched = new HashSet<>(plan.unmatched());

        List<PeriodView> views = new ArrayList<>();
        Map<LocalDate, List<Change>> changesByPeriod = new LinkedHashMap<>();
        Map<LocalDate, List<Long>> undecidedByPeriod = new HashMap<>();
        LocalDate today = pay.today();
        for (PayPeriod period : periods) {
            List<Occurrence> inPeriod = choices.stream().filter(occurrence -> occurrence.periodStart().equals(period.start())).toList();
            Set<Long> choosable = inPeriod.stream().map(Occurrence::itemId).collect(Collectors.toSet());
            List<GroupTotalView> groups = totals.stream()
                    .filter(total -> total.periodStart().equals(period.start()))
                    .map(total -> {
                        Group group = total.group();
                        Target target = targets.stream()
                                .filter(chosen -> chosen.periodStart().equals(period.start()) && chosen.group().equals(group))
                                .findFirst()
                                .orElse(null);
                        Long hint = hints.get(group);
                        boolean category = group.type() == GroupType.CATEGORY;
                        Category asCategory = category ? categoryById.get(group.id()) : null;
                        Tag asTag = category ? null : tagById.get(group.id());
                        return new GroupTotalView(
                                group.type(),
                                group.id(),
                                category ? asCategory == null ? "" : asCategory.name() : asTag == null ? "" : asTag.name(),
                                category ? asCategory == null ? null : asCategory.color() : asTag == null ? null : asTag.color(),
                                total.cents(),
                                total.lineIds(),
                                target == null || !choosable.contains(target.itemId()) ? null : new TargetView(target.itemId(), target.mode()),
                                hint != null && choosable.contains(hint) ? hint : null);
                    })
                    .toList();
            Map<Long, List<Occurrence>> byItem = new LinkedHashMap<>();
            inPeriod.stream()
                    .sorted(Comparator.comparing(Occurrence::name).thenComparing(Occurrence::payBy))
                    .forEach(occurrence -> byItem.computeIfAbsent(occurrence.itemId(), id -> new ArrayList<>()).add(occurrence));
            List<ChoiceView> choiceViews = byItem.entrySet().stream()
                    .map(entry -> {
                        Occurrence first = entry.getValue().getFirst();
                        Item item = itemsById.get(entry.getKey());
                        return new ChoiceView(entry.getKey(), first.name(), first.kind(), first.categoryId(),
                                item == null ? List.of() : item.tagIds(),
                                entry.getValue().stream()
                                        .map(occurrence -> new SlotView(occurrence.scheduledDate(), occurrence.payBy(), occurrence.amountCents(),
                                                occurrence.estimate()))
                                        .toList());
                    })
                    .toList();
            List<Change> changes = plan.changes().stream().filter(change -> change.periodStart().equals(period.start())).toList();
            changesByPeriod.put(period.start(), changes);
            Set<Long> periodLines = charges.stream()
                    .filter(charge -> period.contains(charge.date()))
                    .map(Charge::lineId)
                    .collect(Collectors.toSet());
            List<Long> periodUndecided = periodLines.stream().filter(undecided::contains).sorted().toList();
            undecidedByPeriod.put(period.start(), periodUndecided);
            Coverage covered = coverage.get(period.start());
            views.add(new PeriodView(
                    period.start(),
                    period.end(),
                    period.contains(today),
                    covered == null ? null : new CoverageView(covered.from(), covered.to(), covered.partly()),
                    groups,
                    choiceViews,
                    matchable.getOrDefault(period.start(), List.of()),
                    changes.stream()
                            .map(change -> new ChangeView(change.itemId(), change.scheduledDate(), change.currentCents(), change.newCents(),
                                    change.lineIds()))
                            .toList(),
                    periodUndecided,
                    periodLines.stream().filter(unmatched::contains).sorted().toList(),
                    changes.stream().allMatch(change -> change.newCents() == change.currentCents())));
        }

        Map<Long, String> uploadNames = new HashMap<>();
        uploads.ofAccount(accountId).forEach(each -> uploadNames.put(each.id(), each.name()));
        Set<Long> inPeriods = charges.stream().map(Charge::lineId).collect(Collectors.toSet());
        List<CountedLine> lines = counted.stream()
                .filter(line -> inPeriods.contains(line.id()))
                .map(line -> new CountedLine(line.id(), line.uploadId(), uploadNames.get(line.uploadId()), line.txnDate(), line.description(),
                        line.amountCents(), line.categoryId(), line.tagIds(), GroupView.of(line.countsToward())))
                .toList();
        return new Worked(new ChangesView(upload.id(), accountId, views, outside, lines), changesByPeriod, undecidedByPeriod, bases,
                currents, counted);
    }

    /**
     * What each period's lines can be matched to: the dates of recurring items paid from the account other than budgets and
     * set-asides (money that never leaves it), and one-offs spent from it, each with the line already matched to it.
     */
    private Map<LocalDate, List<MatchableView>> matchable(long accountId, List<PayPeriod> periods, List<Occurrence> paidFromAccount) {
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
                itemTaken.put(new ItemDate(match.itemId(), match.scheduledDate()), line.id());
            }
        }
        Map<LocalDate, List<MatchableView>> byPeriod = new HashMap<>();
        for (Occurrence occurrence : paidFromAccount) {
            if (occurrence.kind() == ItemKind.BUDGET || occurrence.kind() == ItemKind.BUCKET_MOVE) {
                continue;
            }
            byPeriod.computeIfAbsent(occurrence.periodStart(), start -> new ArrayList<>()).add(new MatchableView(
                    occurrence.itemId(), occurrence.scheduledDate(), null, occurrence.name(), occurrence.payBy(), occurrence.amountCents(),
                    occurrence.paid(), itemTaken.get(new ItemDate(occurrence.itemId(), occurrence.scheduledDate()))));
        }
        if (!periods.isEmpty()) {
            for (EntriesRepository.Entry entry : entries.between(periods.getFirst().start(), periods.getLast().end())) {
                if (entry.accountId() != accountId || entry.kind() != EntryKind.MONEY_OUT) {
                    continue;
                }
                Replacement.periodOf(entry.date(), periods).ifPresent(period -> byPeriod.computeIfAbsent(period.start(), key -> new ArrayList<>())
                        .add(new MatchableView(null, null, entry.id(), entry.description(), entry.date(), entry.amountCents(), true,
                                entryTaken.get(entry.id()))));
            }
        }
        byPeriod.values().forEach(list -> list.sort(Comparator.comparing(MatchableView::date).thenComparing(MatchableView::name)));
        return byPeriod;
    }

    /** Request body for matching a line: a recurring item's date ({@code itemId}, {@code scheduledDate}) or a one-off ({@code entryId}). */
    public record MatchInput(Long itemId, LocalDate scheduledDate, Long entryId) {}

    /**
     * Matches a line to what it already is in Budget (Rachel, 2026-10-30). A recurring item's date takes the charge as its
     * amount and is ticked paid on the day it was spent; a one-off is only linked. Either way the line stops counting toward
     * its totals, so nothing is counted twice. One that was matched to something else is let go of first.
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
            throw ApiException.badRequest("Only money spent can be matched to a bill or a one-off. Money back counts toward its totals.");
        }
        if (line.duplicateOf() != null || line.oneOffEntryId() != null) {
            throw ApiException.conflict(line.duplicateOf() != null
                    ? "This charge is on an earlier statement too; match it there."
                    : "This charge was added as a one-off already.");
        }
        List<PayPeriod> periods = pay.payPeriods(upload.from(), upload.to());
        PayPeriod period = periods.stream().filter(each -> each.contains(line.txnDate())).findFirst()
                .orElseThrow(() -> ApiException.badRequest("This charge is in no pay period, so there is nothing to match it to."));
        List<Occurrence> paidFromAccount = calendar.expand(period.start(), period.end()).occurrences().stream()
                .filter(occurrence -> period.start().equals(occurrence.periodStart()) && occurrence.payFromAccountId() == upload.accountId())
                .filter(occurrence -> !occurrence.skipped() && occurrence.invoiceId() == null && !occurrence.plannedPack())
                .toList();
        MatchableView chosen = matchable(upload.accountId(), List.of(period), paidFromAccount).getOrDefault(period.start(), List.of()).stream()
                .filter(each -> toEntry
                        ? input.entryId().equals(each.entryId())
                        : input.itemId().equals(each.itemId()) && input.scheduledDate().equals(each.scheduledDate()))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("Please choose something from this charge's pay period."));
        if (chosen.matchedLineId() != null && chosen.matchedLineId() != line.id()) {
            throw ApiException.conflict("Another charge is already matched to " + chosen.name() + ". Unmatch that one first.");
        }
        if (!toEntry && uploads.targets(upload.accountId()).stream()
                .anyMatch(target -> target.periodStart().equals(period.start()) && target.itemId() == input.itemId())) {
            throw ApiException.conflict(chosen.name() + " is what a total replaces in this pay period. Choose Nothing for that total first.");
        }
        restore(line);
        if (toEntry) {
            uploads.setMatch(line.id(), StatementUploadsRepository.Match.entry(input.entryId()));
        } else {
            Override stored = storedOverride(input.itemId(), input.scheduledDate());
            Override paid = stored.withAmount(line.amountCents(), chosen.amountCents());
            items.saveOverride(new Override(paid.itemId(), paid.originalDueDate(), paid.amountCents(), paid.dueDate(), paid.periodOverride(),
                    true, line.txnDate(), paid.confirmationCode(), paid.note(), paid.skipped(), paid.projectedCents(), paid.checkNumber(),
                    paid.payFromAccountId()));
            uploads.setMatch(line.id(), StatementUploadsRepository.Match.item(input.itemId(), input.scheduledDate(),
                    new StatementUploadsRepository.Before(stored.amountCents(), stored.paid(), stored.paidDate(), stored.projectedCents())));
        }
        ApiResponse.ok(ctx, view(upload, List.of()));
    }

    /** Lets go of what a line was matched to, putting a recurring item's date back as it was before. */
    private void unmatch(Context ctx) {
        Upload upload = found(ctx);
        Line line = ownLine(ctx, upload);
        restore(line);
        ApiResponse.ok(ctx, view(upload, List.of()));
    }

    /** Puts back the date a line was matched to, as it was before the match, and forgets the match. */
    private void restore(Line line) {
        StatementUploadsRepository.Match match = line.match();
        if (match == null) {
            return;
        }
        if (match.itemId() != null) {
            Override now = storedOverride(match.itemId(), match.scheduledDate());
            StatementUploadsRepository.Before before = match.before();
            items.saveOverride(new Override(now.itemId(), now.originalDueDate(), before.amountCents(), now.dueDate(), now.periodOverride(),
                    before.paid(), before.paidDate(), now.confirmationCode(), now.note(), now.skipped(), before.projectedCents(),
                    now.checkNumber(), now.payFromAccountId()));
        }
        uploads.clearMatch(line.id());
    }

    private Override storedOverride(long itemId, LocalDate scheduledDate) {
        return items.overrides(scheduledDate, scheduledDate).stream()
                .filter(edit -> edit.itemId() == itemId)
                .findFirst()
                .orElse(Override.untouched(itemId, scheduledDate));
    }

    /** Adds a line as a one-off of its own on the day it was spent, with its category and tags: one click, nothing else to tick. */
    private void oneOff(Context ctx) {
        Upload upload = found(ctx);
        Line line = ownLine(ctx, upload);
        if (line.duplicateOf() != null || line.oneOffEntryId() != null || line.match() != null) {
            throw ApiException.conflict(line.match() != null
                    ? "This charge is matched to something already in Budget. Unmatch it first."
                    : line.duplicateOf() != null ? "This charge is on an earlier statement too; add it from there."
                    : "This charge was added as a one-off already.");
        }
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
        ApiResponse.send(ctx, 201, view(upload, List.of()));
    }

    /** The line named in the path, which must be this upload's own. */
    private Line ownLine(Context ctx, Upload upload) {
        long lineId = Inputs.id(ctx, "lineId");
        return uploads.line(lineId).filter(line -> line.uploadId() == upload.id()).orElseThrow(ApiException::notFound);
    }

    /** Request body for pointing a total at an item: {@code itemId} null points it at nothing; {@code mode} replace or add. */
    public record TargetInput(LocalDate periodStart, String groupType, Long groupId, Long itemId, String mode) {}

    private void target(Context ctx) {
        Upload upload = found(ctx);
        TargetInput input = Inputs.body(ctx, TargetInput.class);
        if (input.periodStart() == null || pay.payPeriods(upload.from(), upload.to()).stream()
                .noneMatch(period -> period.start().equals(input.periodStart()))) {
            throw ApiException.badRequest("Please choose one of this statement's pay periods.");
        }
        GroupType type = Inputs.choice(GroupType.class, input.groupType(), "a category or a tag");
        if (input.groupId() == null || (type == GroupType.CATEGORY ? !categoryExists(input.groupId()) : tags.find(input.groupId()).isEmpty())) {
            throw ApiException.badRequest("Please choose a category or a tag from the list.");
        }
        Mode mode = input.mode() == null ? Mode.REPLACE : Inputs.choice(Mode.class, input.mode(), "replace or add");
        if (input.itemId() != null) {
            // Only a budget or bill paid from this account, with a date in that period, can be chosen.
            boolean choosable = worked(upload).view().periods().stream()
                    .filter(period -> period.start().equals(input.periodStart()))
                    .flatMap(period -> period.items().stream())
                    .anyMatch(choice -> choice.itemId() == input.itemId());
            if (!choosable) {
                throw ApiException.badRequest("Please choose a budget or recurring item from this pay period's list.");
            }
            boolean matched = uploads.linesOfAccount(upload.accountId()).stream()
                    .map(Line::match)
                    .anyMatch(match -> match != null && input.itemId().equals(match.itemId())
                            && pay.payPeriods(match.scheduledDate(), match.scheduledDate()).stream()
                                    .anyMatch(each -> each.start().equals(input.periodStart())));
            if (matched) {
                throw ApiException.conflict("A charge is matched to that item in this pay period. Unmatch it first.");
            }
        }
        uploads.setTarget(upload.accountId(), input.periodStart(), new Group(type, input.groupId()), input.itemId(), mode);
        ApiResponse.ok(ctx, changes(upload));
    }

    /** Request body for applying: the pay periods whose changes to make. */
    public record ApplyInput(List<LocalDate> periods) {}

    /** What was applied, and how things stand after. */
    public record Applied(int amountsChanged, ChangesView changes) {}

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
        Worked worked = worked(upload);
        for (LocalDate period : chosen) {
            if (!worked.changes().containsKey(period)) {
                throw ApiException.badRequest("Please choose the pay periods to apply from the list.");
            }
            if (!worked.undecided().getOrDefault(period, List.of()).isEmpty()) {
                throw ApiException.conflict("Some charges in the pay period starting " + period
                        + " could count toward two items. Please choose which for each one first.");
            }
        }

        int changed = 0;
        for (LocalDate period : chosen) {
            for (Change change : worked.changes().get(period)) {
                if (change.newCents() == change.currentCents()) {
                    continue;
                }
                ItemDate key = new ItemDate(change.itemId(), change.scheduledDate());
                Override stored = storedOverride(change.itemId(), change.scheduledDate());
                items.saveOverride(stored.withAmount(change.newCents(), change.currentCents()));
                uploads.saveReplaced(key, new Replaced(worked.bases().get(key), change.newCents()));
                changed++;
            }
        }
        uploads.markApplied(upload.id());
        ApiResponse.ok(ctx, new Applied(changed, changes(upload)));
    }

    // ---------------------------------------------------------------- helpers

    private UploadView view(Upload upload, List<Integer> skippedRows) {
        Account account = accounts.find(upload.accountId()).orElseThrow(ApiException::notFound);
        List<PayPeriod> periods = pay.payPeriods(upload.from(), upload.to());
        Map<String, PayeeRule> rules = uploads.payeeRules();
        List<Line> lines = uploads.lines(upload.id());
        List<LineView> views = lines.stream()
                .map(line -> new LineView(line.id(), line.txnDate(), line.postedDate(), line.description(), line.amountCents(), line.kind(),
                        line.leftOut(), line.sure(), line.duplicateOf(), line.categoryId(), line.tagIds(), GroupView.of(line.countsToward()),
                        line.oneOffEntryId(), Replacement.periodOf(line.txnDate(), periods).map(PayPeriod::start).orElse(null),
                        rule(rules, line.payeeKey()).isEmpty(), MatchedView.of(line.match())))
                .toList();
        int toCheck = (int) lines.stream().filter(line -> !line.sure()).count();
        return new UploadView(upload.id(), account.id(), account.name(), onCard(account), upload.name(), upload.format(), upload.from(),
                upload.to(), upload.appliedAt(), upload.pages(), views, toCheck, skippedRows);
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
