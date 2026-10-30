package app.budget.server.stubs;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.stubs.PayStub;
import app.budget.engine.stubs.PayStub.Section;
import app.budget.engine.stubs.PayStub.Totals;
import app.budget.engine.stubs.PayStub.Tracking;
import app.budget.engine.stubs.StubReader;
import app.budget.server.accounts.Account;
import app.budget.server.accounts.AccountType;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.files.FileStore.Kind;
import app.budget.server.files.FileStore.Stored;
import app.budget.server.files.StoredFiles;
import app.budget.server.income.IncomeRepository;
import app.budget.server.income.IncomeRepository.Deduction;
import app.budget.server.income.IncomeRepository.Match;
import app.budget.server.income.IncomeRepository.MatchMode;
import app.budget.server.income.IncomeRepository.Pay;
import app.budget.server.income.IncomeRepository.Source;
import app.budget.server.income.OneOffPaychecksRepository;
import app.budget.server.income.OneOffPaychecksRepository.OneOff;
import app.budget.server.income.PayCalendar;
import app.budget.server.income.PayCalendar.Paycheck;
import app.budget.server.stubs.PayStubsRepository.Applied;
import app.budget.server.stubs.PayStubsRepository.Layout;
import app.budget.server.stubs.PayStubsRepository.Status;
import app.budget.server.stubs.PayStubsRepository.Stub;
import app.budget.server.stubs.PayStubsRepository.StubLine;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pay stubs (spec Phase 8): a stub's PDF is added to an income source, read on this PC, looked
 * over, and saved. Saving asks what it should change (decided 2026-10-12): the amounts in
 * force, the pay from its date on, that one paycheck, a one-off paycheck of its own (a bonus;
 * 2026-10-19), or nothing.
 *
 * <p>Nothing a stub says is used until it is saved, and nothing in it is logged: a stub
 * names an employer and what someone earns.
 */
public final class PayStubsRoutes {

    private static final Logger log = LoggerFactory.getLogger(PayStubsRoutes.class);

    /** More lines than any stub has; a PDF that reads as more is not a stub. */
    private static final int MAX_LINES = 80;
    /** The same as a deduction's name, which a line can become. */
    private static final int LABEL_MAX = 80;
    /** The same limit the income form keeps. */
    private static final int MAX_DEDUCTIONS = 30;
    /** A payday moved for a weekend or a holiday is still within this many days of the date on its stub. */
    private static final int DAYS_AROUND_PAYDAY = 4;
    /** What a one-off paycheck saved from a stub is called until it is given a name. */
    private static final String DEFAULT_ONE_OFF_NAME = "Bonus";

    private static final String NOT_A_PDF =
            "A pay stub needs to be a PDF, the kind your payroll site lets you download. Photos and scans can't be read yet.";
    private static final String NO_TEXT =
            "This PDF has no text Budget can read; it looks like a scan or a photo. Reading those isn't built yet."
                    + " A PDF downloaded from your payroll site will work.";

    private final PayStubsRepository stubs;
    private final StoredFiles files;
    private final IncomeRepository income;
    private final OneOffPaychecksRepository oneOffs;
    private final PayCalendar calendar;
    private final AccountsRepository accounts;
    private final Clock clock;

    public PayStubsRoutes(
            PayStubsRepository stubs,
            StoredFiles files,
            IncomeRepository income,
            OneOffPaychecksRepository oneOffs,
            PayCalendar calendar,
            AccountsRepository accounts,
            Clock clock) {
        this.stubs = stubs;
        this.files = files;
        this.income = income;
        this.oneOffs = oneOffs;
        this.calendar = calendar;
        this.accounts = accounts;
        this.clock = clock;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/income-sources/{id}/pay-stubs", this::list);
        routes.post("/api/income-sources/{id}/pay-stubs", this::upload);
        routes.post("/api/pay-stubs/read", this::readOnly);
        routes.get("/api/pay-stubs/{id}", ctx -> ApiResponse.ok(ctx, view(found(ctx))));
        routes.put("/api/pay-stubs/{id}", this::change);
        routes.post("/api/pay-stubs/{id}/save", this::save);
        routes.post("/api/pay-stubs/{id}/reopen", this::reopen);
        routes.post("/api/pay-stubs/{id}/cancel-edit", this::cancelEdit);
        routes.delete("/api/pay-stubs/{id}", this::delete);
        routes.get("/api/pay-stubs/{id}/file", this::download);
        routes.post("/api/pay-stubs/{id}/copy", this::saveCopy);
        routes.get("/api/pay-stubs/{id}/preview", this::preview);
    }

    /**
     * One line of a stub. Amounts are whole cents; what is taken out is a positive amount.
     *
     * @param id        null for a line read from a stub that is not kept
     * @param tracksAs  what the budget follows it as
     * @param accountId where a contribution or the employer's match goes; null until chosen, and for anything else
     * @param sure      false for a line the reader had to guess at, until someone has looked at it
     */
    public record LineView(
            Long id, Section section, String label, long amountCents, Long ytdCents, Tracking tracksAs, Long accountId, boolean sure) {

        static LineView of(StubLine stored) {
            PayStub.Line line = stored.line();
            return new LineView(stored.id(), line.section(), line.label(), line.amount().cents(),
                    line.ytd() == null ? null : line.ytd().cents(), line.tracking(), stored.accountId(), line.sure());
        }
    }

    /**
     * Whether the stub adds up: gross less everything taken out should be the net pay.
     *
     * @param differenceCents what is left over; zero when it adds up
     */
    public record TotalsView(
            long grossCents,
            long preTaxCents,
            long taxesCents,
            long afterTaxCents,
            long deductionsCents,
            long netCents,
            long employerCents,
            long differenceCents,
            boolean balanced) {

        static TotalsView of(List<StubLine> lines) {
            Totals totals = Totals.of(lines.stream().map(StubLine::line).toList());
            return new TotalsView(totals.gross().cents(), totals.preTax().cents(), totals.taxes().cents(),
                    totals.afterTax().cents(), totals.deductions().cents(), totals.net().cents(), totals.employer().cents(),
                    totals.difference().cents(), totals.balanced());
        }
    }

    /** The paycheck a stub is for: the source's own, paid on or within a few days of the stub's date. */
    public record PaycheckView(LocalDate scheduledDate, LocalDate payDate, long netCents) {}

    /** What saving the stub would make the source's pay. */
    public record PayView(Long grossCents, long netCents, List<Deduction> deductions, Match match) {

        static PayView of(Pay pay) {
            return new PayView(pay.grossCents(), pay.netCents(), pay.deductions(), pay.match());
        }
    }

    /**
     * A stub with everything read from it.
     *
     * @param toCheck  how many lines still want a second look
     * @param paycheck the paycheck it is for; null when the source has none near its date, or it has no date yet
     * @param wouldSet what the source's pay becomes if the stub is saved onto it; null until it has a net pay line
     */
    public record StubView(
            long id,
            long incomeSourceId,
            String sourceName,
            String name,
            LocalDate payDate,
            Status status,
            Applied applied,
            boolean editingSaved,
            int pages,
            long sizeBytes,
            List<LineView> lines,
            TotalsView totals,
            int toCheck,
            PaycheckView paycheck,
            PayView wouldSet) {}

    /** A stub in a source's list. */
    public record StubSummary(
            long id, String name, LocalDate payDate, Status status, Applied applied, long grossCents, long netCents, int toCheck) {}

    /** What a stub says, read without keeping it: for filling in an income source that does not exist yet. */
    public record ReadView(LocalDate payDate, List<LineView> lines, TotalsView totals) {}

    /** Request body for one line. {@code section} and {@code tracksAs} are spelled as in {@link LineView}. */
    public record LineInput(
            String section, String label, Long amountCents, Long ytdCents, String tracksAs, Long accountId, Boolean sure) {}

    /** Request body for changing a stub in review: its date and every line, as they should now be. */
    public record StubInput(LocalDate payDate, List<LineInput> lines) {}

    /**
     * Request body for saving: {@code apply} is {@code correct}, {@code pay_change}, {@code paycheck},
     * {@code one_off} or {@code none}. {@code name} is what a one-off paycheck is called ("Q3 bonus");
     * left out, it keeps the name it already has, or is called "Bonus".
     */
    public record SaveInput(String apply, String name) {}

    public record Done(boolean done) {}

    private void list(Context ctx) {
        Source source = source(ctx);
        ApiResponse.ok(ctx, stubs.ofSource(source.id()).stream().map(PayStubsRoutes::summary).toList());
    }

    private void upload(Context ctx) {
        Source source = source(ctx);
        UploadedFile sent = oneFile(ctx);
        Stored kept = keptPdf(sent);
        Map<String, Layout> layout = stubs.layout(source.id());
        PayStub.Read read = read(kept, layout);
        long id = stubs.create(source.id(), kept, StoredFiles.name(sent.filename(), kept.kind()), supported(read.payDate()),
                withAccounts(read.lines(), layout, source));
        ApiResponse.send(ctx, 201, view(stubs.find(id).orElseThrow()));
    }

    /**
     * Reads a stub and answers with what it says, keeping no record of it. The file itself is
     * removed at the next start, like any upload nothing came of.
     */
    private void readOnly(Context ctx) {
        PayStub.Read read = read(keptPdf(oneFile(ctx)), Map.of());
        List<StubLine> lines = read.lines().stream().limit(MAX_LINES).map(line -> new StubLine(null, line, null)).toList();
        ApiResponse.ok(ctx, new ReadView(
                supported(read.payDate()), lines.stream().map(LineView::of).toList(), TotalsView.of(lines)));
    }

    private void change(Context ctx) {
        Stub stub = inReview(ctx);
        StubInput input = Inputs.body(ctx, StubInput.class);
        if (input.payDate() != null && !Inputs.inSupportedYears(input.payDate())) {
            throw ApiException.badRequest("Please choose the day this stub was paid.");
        }
        stubs.replace(stub.id(), input.payDate(), lines(input.lines()));
        ApiResponse.ok(ctx, view(stubs.find(stub.id()).orElseThrow()));
    }

    private void save(Context ctx) {
        Stub stub = inReview(ctx);
        SaveInput input = Inputs.body(ctx, SaveInput.class);
        Applied applied = Inputs.choice(Applied.class, input.apply(), "what this stub should change");
        if (stub.payDate() == null) {
            throw ApiException.badRequest("Please fill in the day this stub was paid first.");
        }
        String oneOffName = Inputs.optionalText(input.name(), "a name for the paycheck", LABEL_MAX);
        Source source = income.find(stub.incomeSourceId()).orElseThrow(ApiException::notFound);
        // Applied before the stub is marked saved, so a refusal leaves it in review with nothing changed.
        apply(stub, source, applied, oneOffName);
        stubs.save(stub, applied);
        ApiResponse.ok(ctx, view(stubs.find(stub.id()).orElseThrow()));
    }

    /**
     * Opens a saved stub to be changed (Rachel, 2026-10-12: a line filed wrongly is noticed after
     * saving, and removing the stub to add its PDF again loses every line already put right).
     * Nothing it changed is taken back: saving it again puts its figures where that save says.
     */
    private void reopen(Context ctx) {
        Stub stub = found(ctx);
        if (stub.status() == Status.SAVED) {
            stubs.reopen(stub.id());
        }
        ApiResponse.ok(ctx, view(stubs.find(stub.id()).orElseThrow()));
    }

    /**
     * Stops editing a saved stub without saving: it is put back exactly as it was saved (Rachel, 2026-10-24). 409 for a
     * stub that is not a saved one being edited, such as one added and never saved, which Discard is for.
     */
    private void cancelEdit(Context ctx) {
        Stub stub = found(ctx);
        if (!stubs.cancelEdit(stub.id())) {
            throw ApiException.conflict("This stub isn't being edited after it was saved, so there is nothing to go back to.");
        }
        ApiResponse.ok(ctx, view(stubs.find(stub.id()).orElseThrow()));
    }

    private void delete(Context ctx) {
        stubs.delete(found(ctx).id());
        ApiResponse.ok(ctx, new Done(true));
    }

    private void download(Context ctx) {
        Stub stub = onDisk(ctx);
        files.download(ctx, stub.name(), Kind.PDF, stub.sizeBytes(), stub.hash());
    }

    private void saveCopy(Context ctx) {
        Stub stub = onDisk(ctx);
        ApiResponse.ok(ctx, files.copy(ctx, stub.name(), stub.hash()));
    }

    private void preview(Context ctx) {
        Stub stub = onDisk(ctx);
        files.preview(ctx, stub.hash(), Kind.PDF, stub.pages());
    }

    /** Puts the stub's figures where saving was asked to put them. */
    private void apply(Stub stub, Source source, Applied applied, String oneOffName) {
        if (applied == Applied.NONE) {
            return;
        }
        if (applied == Applied.ONE_OFF) {
            saveOneOff(stub, source, oneOffName);
            return;
        }
        Optional<Paycheck> paycheck = paycheckFor(source, stub.payDate());
        switch (applied) {
            case CORRECT -> {
                IncomeRepository.Terms inForce = source.termsOn(LocalDate.now(clock));
                income.update(source.id(), fields(source, payFrom(stub.lines(), inForce.pay())), inForce.changeId());
            }
            case PAY_CHANGE -> {
                // A change of pay reaches a paycheck by the day its schedule names, which a weekend can
                // put after the day it was really paid.
                LocalDate from = paycheck.map(Paycheck::scheduledDate).orElse(stub.payDate());
                Pay before = source.termsOn(from).pay();
                Pay pay = payFrom(stub.lines(), before);
                if (!before.equals(pay)) {
                    income.updateFrom(source.id(), fields(source, pay), from);
                }
            }
            case PAYCHECK -> {
                Paycheck one = paycheck.orElseThrow(() -> ApiException.conflict(
                        "We couldn't find a paycheck from this income within a few days of the stub's date."
                                + " Please check the date, or choose something else for this stub to change."));
                long net = netOf(stub.lines()).orElseThrow(PayStubsRoutes::noNetPay);
                income.saveOverride(source.id(), one.scheduledDate(), net, source.termsOn(one.scheduledDate()).pay().netCents());
            }
            case ONE_OFF, NONE -> { }
        }
    }

    /**
     * A stub for extra pay of its own (a bonus) becomes a one-off paycheck on its pay date: its gross,
     * take-home and what it takes out are the stub's. Saving the same stub again changes that paycheck
     * rather than adding another. The match follows the source's own rule, as a typed one-off's does.
     */
    private void saveOneOff(Stub stub, Source source, String name) {
        // Nothing of the regular pay stands in for what a bonus stub leaves out: its gross is its own or none.
        Pay pay = payFrom(stub.lines(), new Pay(null, 0, false, List.of(), null));
        Optional<OneOff> existing = oneOffs.forStub(stub.id());
        String called = name != null ? name : existing.map(OneOff::name).orElse(DEFAULT_ONE_OFF_NAME);
        boolean matched = existing.map(OneOff::matched).orElse(true);
        OneOffPaychecksRepository.Fields fields = new OneOffPaychecksRepository.Fields(
                stub.payDate(), called, pay.grossCents(), pay.netCents(), pay.deductions(), matched);
        if (existing.isPresent()) {
            oneOffs.update(existing.get().id(), fields);
        } else {
            oneOffs.create(source.id(), fields, stub.id());
        }
    }

    private static IncomeRepository.Fields fields(Source source, Pay pay) {
        return new IncomeRepository.Fields(source.name(), source.depositAccountId(), source.primary(), source.schedule(), pay);
    }

    /**
     * A source's pay as the stub has it. Gross and net are the stub's; what is taken out before
     * and after tax becomes the deductions; taxes stay on the stub. {@code before} is the pay being
     * replaced, for what a stub cannot say.
     */
    private static Pay payFrom(List<StubLine> lines, Pay before) {
        long net = netOf(lines).orElseThrow(PayStubsRoutes::noNetPay);
        boolean hasEarnings = lines.stream().anyMatch(line -> line.line().section() == Section.EARNINGS);
        Long gross = hasEarnings
                ? (Long) Totals.of(lines.stream().map(StubLine::line).toList()).gross().cents()
                : before.grossCents();
        if (gross != null && gross < 0) {
            throw ApiException.badRequest("The stub's earnings come to less than zero. Please check its earnings lines.");
        }

        List<Deduction> deductions = new ArrayList<>();
        for (StubLine stored : lines) {
            PayStub.Line line = stored.line();
            boolean takenOut = line.section() == Section.PRE_TAX || line.section() == Section.AFTER_TAX;
            if (!takenOut || line.amount().isZero()) {
                continue;
            }
            Long toAccountId = line.tracking() == Tracking.CONTRIBUTION ? stored.accountId() : null;
            deductions.add(new Deduction(line.label(), line.amount().cents(), toAccountId, percentKept(line, toAccountId, before, gross)));
        }
        if (deductions.size() > MAX_DEDUCTIONS) {
            throw ApiException.badRequest("This stub has more than " + MAX_DEDUCTIONS
                    + " lines taken out of pay, which is more than an income source can hold. Please remove ones you don't follow.");
        }
        return new Pay(gross, net, false, deductions, matchFrom(lines, before.match()));
    }

    /**
     * A deduction entered as a share of pay stays one when the stub's amount is exactly that
     * share of its gross, so the next raise still moves it.
     */
    private static Integer percentKept(PayStub.Line line, Long toAccountId, Pay before, Long gross) {
        if (gross == null) {
            return null;
        }
        for (Deduction existing : before.deductions()) {
            if (existing.percentBp() != null
                    && PayStub.key(existing.name()).equals(PayStub.key(line.label()))
                    && Objects.equals(existing.toAccountId(), toAccountId)
                    && Money.ofCents(gross).times(BasisPoints.of(existing.percentBp()).toFraction()).equals(line.amount())) {
                return existing.percentBp();
            }
        }
        return null;
    }

    /**
     * The employer's match as the stub has it: its match lines, once they name an account. A match
     * worked out by a rule is left as it is, since the rule says more than one stub's amount can.
     */
    private static Match matchFrom(List<StubLine> lines, Match before) {
        if (before != null && before.mode() == MatchMode.FORMULA) {
            return before;
        }
        Long toAccountId = null;
        long total = 0;
        for (StubLine stored : lines) {
            if (stored.line().tracking() != Tracking.MATCH || stored.accountId() == null || stored.line().amount().isZero()) {
                continue;
            }
            if (toAccountId == null) {
                toAccountId = stored.accountId();
            }
            // A match goes into one account; lines for another are left for the person to enter by hand.
            if (toAccountId.equals(stored.accountId())) {
                total += stored.line().amount().cents();
            }
        }
        return toAccountId == null ? before : new Match(toAccountId, MatchMode.FIXED, total, null, null, false);
    }

    private static Optional<Long> netOf(List<StubLine> lines) {
        if (lines.stream().noneMatch(line -> line.line().section() == Section.NET)) {
            return Optional.empty();
        }
        return Optional.of(Totals.of(lines.stream().map(StubLine::line).toList()).net().cents());
    }

    private static ApiException noNetPay() {
        return ApiException.badRequest("This stub has no net pay line yet. Please add one, or choose to change nothing.");
    }

    /** The source's paycheck paid nearest the stub's date, within a few days of it. */
    private Optional<Paycheck> paycheckFor(Source source, LocalDate payDate) {
        if (payDate == null) {
            return Optional.empty();
        }
        return calendar.paychecks(payDate.minusDays(DAYS_AROUND_PAYDAY), payDate.plusDays(DAYS_AROUND_PAYDAY)).stream()
                // A one-off paycheck has no schedule to be typed over; "just that paycheck" means a regular one.
                .filter(paycheck -> paycheck.incomeSourceId() == source.id() && paycheck.scheduled())
                .min(Comparator.comparingLong(paycheck -> Math.abs(ChronoUnit.DAYS.between(payDate, paycheck.payDate()))));
    }

    private StubView view(Stub stub) {
        Source source = income.find(stub.incomeSourceId()).orElseThrow(ApiException::notFound);
        PaycheckView paycheck = paycheckFor(source, stub.payDate())
                .map(found -> new PaycheckView(found.scheduledDate(), found.payDate(), found.netCents()))
                .orElse(null);
        PayView wouldSet = null;
        if (netOf(stub.lines()).isPresent()) {
            try {
                wouldSet = PayView.of(payFrom(stub.lines(), source.termsOn(LocalDate.now(clock)).pay()));
            } catch (ApiException e) {
                // Lines that cannot become pay as they stand: saving says why; looking at the stub still works.
                wouldSet = null;
            }
        }
        return new StubView(
                stub.id(), source.id(), source.name(), stub.name(), stub.payDate(), stub.status(), stub.applied(),
                stub.savedApplied() != null, stub.pages(), stub.sizeBytes(), stub.lines().stream().map(LineView::of).toList(), TotalsView.of(stub.lines()),
                toCheck(stub), paycheck, wouldSet);
    }

    private static StubSummary summary(Stub stub) {
        TotalsView totals = TotalsView.of(stub.lines());
        return new StubSummary(stub.id(), stub.name(), stub.payDate(), stub.status(), stub.applied(),
                totals.grossCents(), totals.netCents(), toCheck(stub));
    }

    private static int toCheck(Stub stub) {
        return (int) stub.lines().stream().filter(line -> !line.line().sure()).count();
    }

    /** The stub's words, made sense of. A PDF with no text in it is refused: it is a scan. */
    private PayStub.Read read(Stored kept, Map<String, Layout> layout) {
        List<PayStub.Row> rows;
        try {
            rows = StubPdf.rows(files.path(kept.hash()));
        } catch (IOException | RuntimeException e) {
            // The cause says nothing of the stub's contents: only why the PDF library gave up.
            log.warn("A pay stub's text could not be read: {}", e.getClass().getSimpleName());
            throw new ApiException(422, "We couldn't read that PDF. It may be damaged; please try downloading it again.");
        }
        if (rows.isEmpty()) {
            throw new ApiException(422, NO_TEXT);
        }
        Map<String, PayStub.Remembered> remembered = new HashMap<>();
        layout.forEach((key, filed) -> remembered.put(key, filed.remembered()));
        return StubReader.read(rows, remembered);
    }

    /** A contribution to a retirement plan, by its label: the kind of saving an employer matches. */
    private static final Pattern RETIREMENT = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(401 ?\\(?k\\)?|403 ?\\(?b\\)?|457|roth|retirement|tsp|pension|deferred comp)(?![\\p{L}\\p{N}])",
            Pattern.CASE_INSENSITIVE);

    /**
     * Gives each line its account: the one it went to on the last stub saved, or, the first
     * time, the one a deduction of the same name (or the match) already goes to on the source.
     * A retirement contribution nothing else places goes where the employer's match goes: the
     * stub rarely words it as the person did ("401K EE PRETAX" for their "401(k)"), and left
     * with no account it looks as though nothing is put in and the whole match is going unclaimed.
     */
    private List<StubLine> withAccounts(List<PayStub.Line> lines, Map<String, Layout> layout, Source source) {
        Pay pay = source.termsOn(LocalDate.now(clock)).pay();
        List<StubLine> stored = new ArrayList<>();
        for (PayStub.Line line : lines.stream().limit(MAX_LINES).toList()) {
            Long accountId = null;
            Layout filed = layout.get(PayStub.key(line.label()));
            if (line.tracking() == Tracking.CONTRIBUTION) {
                accountId = filed != null
                        ? filed.accountId()
                        : pay.deductions().stream()
                                .filter(deduction -> deduction.toAccountId() != null
                                        && PayStub.key(deduction.name()).equals(PayStub.key(line.label())))
                                .map(Deduction::toAccountId)
                                .findFirst()
                                .orElse(pay.match() != null && RETIREMENT.matcher(line.label()).find() ? pay.match().toAccountId() : null);
            } else if (line.tracking() == Tracking.MATCH) {
                accountId = filed != null ? filed.accountId() : pay.match() == null ? null : pay.match().toAccountId();
            }
            // An account remembered and since deleted is simply not there to choose.
            stored.add(new StubLine(null, line, accountId != null && accounts.exists(accountId) ? accountId : null));
        }
        return stored;
    }

    private List<StubLine> lines(List<LineInput> inputs) {
        if (inputs == null) {
            return List.of();
        }
        if (inputs.size() > MAX_LINES || inputs.contains(null)) {
            throw ApiException.badRequest("Please keep a stub to " + MAX_LINES + " lines or fewer.");
        }
        List<StubLine> lines = new ArrayList<>();
        for (LineInput input : inputs) {
            Section section = Inputs.choice(Section.class, input.section(), "where each line sits on the stub");
            Tracking tracking = Inputs.choice(Tracking.class, input.tracksAs(), "what each line is tracked as");
            if (!allowed(section).contains(tracking)) {
                throw ApiException.badRequest("Please choose what each line is tracked as from the list for its part of the stub.");
            }
            String label = Inputs.text(input.label(), "a name for each line", LABEL_MAX);
            // Earnings can hold a correction that takes pay back; everything else is an amount taken or paid.
            long amount = section == Section.EARNINGS
                    ? Inputs.signedAmount(input.amountCents(), "A line's amount")
                    : Inputs.amount(input.amountCents(), "A line's amount");
            Long ytd = input.ytdCents() == null ? null : (Long) Inputs.signedAmount(input.ytdCents(), "A line's year to date");
            lines.add(new StubLine(null,
                    new PayStub.Line(section, label, Money.ofCents(amount), ytd == null ? null : Money.ofCents(ytd), tracking,
                            !Boolean.FALSE.equals(input.sure())),
                    account(input.accountId(), tracking)));
        }
        return lines;
    }

    /** What a line in each part of the stub can be tracked as. */
    private static List<Tracking> allowed(Section section) {
        return switch (section) {
            case EARNINGS -> List.of(Tracking.GROSS);
            case PRE_TAX, AFTER_TAX -> List.of(Tracking.CONTRIBUTION, Tracking.BENEFIT);
            case TAX -> List.of(Tracking.TAX);
            case NET -> List.of(Tracking.NET);
            case EMPLOYER -> List.of(Tracking.MATCH, Tracking.IGNORE);
        };
    }

    /** Only a contribution and the employer's match go to an account, and a match only into an investment account. */
    private Long account(Long accountId, Tracking tracking) {
        if (accountId == null || (tracking != Tracking.CONTRIBUTION && tracking != Tracking.MATCH)) {
            return null;
        }
        Account account = accounts.find(accountId)
                .orElseThrow(() -> ApiException.badRequest("Please choose the account each line goes to from the list."));
        if (tracking == Tracking.MATCH && !Inputs.code(AccountType.INVESTMENT).equals(account.type())) {
            throw ApiException.badRequest("Please choose the investment account the employer match is paid into.");
        }
        return account.id();
    }

    private UploadedFile oneFile(Context ctx) {
        List<UploadedFile> uploads = files.uploads(ctx, 1);
        if (uploads.size() != 1) {
            throw ApiException.badRequest("Please choose one pay stub at a time.");
        }
        return uploads.get(0);
    }

    private Stored keptPdf(UploadedFile upload) {
        Stored kept = files.keep(upload);
        if (kept.kind() != Kind.PDF) {
            throw new ApiException(415, NOT_A_PDF);
        }
        return kept;
    }

    /** A date read off a stub is only taken when it is one the app works with. */
    private static LocalDate supported(LocalDate payDate) {
        return payDate != null && Inputs.inSupportedYears(payDate) ? payDate : null;
    }

    private Source source(Context ctx) {
        return income.find(Inputs.id(ctx, "id")).orElseThrow(ApiException::notFound);
    }

    private Stub found(Context ctx) {
        return stubs.find(Inputs.id(ctx, "id")).orElseThrow(ApiException::notFound);
    }

    private Stub onDisk(Context ctx) {
        Stub stub = found(ctx);
        files.requireOnDisk(stub.hash());
        return stub;
    }

    /** A saved stub is changed by opening it again first, so nothing about it shifts by accident. */
    private Stub inReview(Context ctx) {
        Stub stub = found(ctx);
        if (stub.status() != Status.REVIEW) {
            throw ApiException.conflict("This stub has been saved. To change it, choose Edit stub first.");
        }
        return stub;
    }
}
