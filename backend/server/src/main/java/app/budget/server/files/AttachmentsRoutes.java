package app.budget.server.files;

import app.budget.server.accounts.AccountType;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.cards.CardsRepository;
import app.budget.server.entries.EntriesRepository;
import app.budget.server.files.AttachmentsRepository.Attachment;
import app.budget.server.files.AttachmentsRepository.Owner;
import app.budget.server.files.FileStore.Stored;
import app.budget.server.items.ItemCalendar;
import app.budget.server.items.ItemsRepository;
import app.budget.server.items.ItemsRepository.Item;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Receipts and documents (spec §4.8): attached to a one-off entry, to one time a recurring
 * item came due, or to a card statement; listed, shown, saved again and removed.
 *
 * <p>A file is only ever reached through the id of an attachment, behind the session like
 * everything else. Taking a file in and handing one back is {@link StoredFiles}, shared with pay stubs.
 */
public final class AttachmentsRoutes {

    /** Plenty for one receipt, its invoice and the insurer's letters. */
    public static final int MAX_PER_OWNER = 20;

    private final AttachmentsRepository attachments;
    private final StoredFiles files;
    private final EntriesRepository entries;
    private final ItemsRepository items;
    private final ItemCalendar calendar;
    private final CardsRepository cards;
    private final AccountsRepository accounts;
    private final Clock clock;

    public AttachmentsRoutes(
            AttachmentsRepository attachments,
            StoredFiles files,
            EntriesRepository entries,
            ItemsRepository items,
            ItemCalendar calendar,
            CardsRepository cards,
            AccountsRepository accounts,
            Clock clock) {
        this.attachments = attachments;
        this.files = files;
        this.entries = entries;
        this.items = items;
        this.calendar = calendar;
        this.cards = cards;
        this.accounts = accounts;
        this.clock = clock;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/entries/{id}/attachments", ctx -> list(ctx, entry(ctx)));
        routes.post("/api/entries/{id}/attachments", ctx -> attach(ctx, entry(ctx)));
        routes.get("/api/items/{id}/occurrences/{scheduledDate}/attachments", ctx -> list(ctx, occurrence(ctx)));
        routes.post("/api/items/{id}/occurrences/{scheduledDate}/attachments", ctx -> attach(ctx, occurrence(ctx)));
        routes.get("/api/cards/{id}/statements/{statementId}/attachments", ctx -> list(ctx, statement(ctx)));
        routes.post("/api/cards/{id}/statements/{statementId}/attachments", ctx -> attach(ctx, statement(ctx)));
        routes.get("/api/attachments/{id}/file", this::download);
        routes.post("/api/attachments/{id}/copy", this::saveCopy);
        routes.get("/api/attachments/{id}/preview", this::preview);
        routes.delete("/api/attachments/{id}", this::remove);
        routes.get("/api/attachment-counts", this::counts);
    }

    /**
     * One attached file as the API returns it.
     *
     * @param mime  {@code application/pdf}, {@code image/jpeg} or {@code image/png}
     * @param pages how many pages a PDF has; {@code null} for a picture
     */
    public record AttachmentView(long id, String name, String mime, long sizeBytes, Integer pages) {}

    /** @param maxBytes the largest file that can be attached, so the screen can say so before sending one */
    public record Attached(List<AttachmentView> attachments, long maxBytes, int maxFiles) {}

    /** How many files one time of a recurring item has. */
    public record OccurrenceFiles(long itemId, LocalDate scheduledDate, int count) {}

    /** How many files one card statement has. */
    public record StatementFiles(long statementId, int count) {}

    /** Only what has files is listed, so a screen can mark those rows without asking about each one. */
    public record Counts(List<OccurrenceFiles> occurrences, List<StatementFiles> statements) {}

    /** The recurring items scheduled within {@code from}–{@code to}, and the statements, that have files. */
    private void counts(Context ctx) {
        LocalDate today = LocalDate.now(clock);
        LocalDate from = Inputs.dateParam(ctx, "from", today.minusYears(1));
        LocalDate to = Inputs.dateParam(ctx, "to", today.plusYears(1));
        Inputs.requireSensibleRange(from, to);
        List<OccurrenceFiles> occurrences = attachments.countsByOccurrence(from, to).entrySet().stream()
                .map(entry -> new OccurrenceFiles(entry.getKey().itemId(), entry.getKey().scheduledDate(), entry.getValue()))
                .sorted(Comparator.comparing(OccurrenceFiles::scheduledDate).thenComparing(OccurrenceFiles::itemId))
                .toList();
        List<StatementFiles> statements = attachments.countsByStatement().entrySet().stream()
                .map(entry -> new StatementFiles(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(StatementFiles::statementId))
                .toList();
        ApiResponse.ok(ctx, new Counts(occurrences, statements));
    }

    private void list(Context ctx, Owner owner) {
        ApiResponse.ok(ctx, attached(owner));
    }

    private void attach(Context ctx, Owner owner) {
        List<UploadedFile> uploads = files.uploads(ctx, MAX_PER_OWNER);
        if (uploads.isEmpty()) {
            throw ApiException.badRequest("Please choose a file to attach.");
        }
        if (attachments.count(owner) + uploads.size() > MAX_PER_OWNER) {
            throw ApiException.conflict("That would be more than " + MAX_PER_OWNER
                    + " files here. Please remove one you no longer need first.");
        }
        // Every file is checked before any is attached: one that is refused leaves nothing half done.
        List<Stored> kept = new ArrayList<>();
        for (UploadedFile upload : uploads) {
            kept.add(files.keep(upload));
        }
        for (int i = 0; i < kept.size(); i++) {
            attachments.add(owner, kept.get(i), StoredFiles.name(uploads.get(i).filename(), kept.get(i).kind()));
        }
        ApiResponse.send(ctx, 201, attached(owner));
    }

    private void download(Context ctx) {
        Attachment attachment = found(ctx);
        files.download(ctx, attachment.name(), attachment.kind(), attachment.sizeBytes(), attachment.hash());
    }

    private void saveCopy(Context ctx) {
        Attachment attachment = found(ctx);
        ApiResponse.ok(ctx, files.copy(ctx, attachment.name(), attachment.hash()));
    }

    private void preview(Context ctx) {
        Attachment attachment = found(ctx);
        files.preview(ctx, attachment.hash(), attachment.kind(), attachment.pages());
    }

    private void remove(Context ctx) {
        Attachment attachment = attachments.find(Inputs.id(ctx, "id")).orElseThrow(ApiException::notFound);
        attachments.remove(attachment.id());
        ApiResponse.ok(ctx, attached(attachment.owner()));
    }

    private Attached attached(Owner owner) {
        List<AttachmentView> views = attachments.of(owner).stream()
                .map(attachment -> new AttachmentView(
                        attachment.id(), attachment.name(), attachment.kind().mime(), attachment.sizeBytes(), attachment.pages()))
                .toList();
        return new Attached(views, files.maxBytes(), MAX_PER_OWNER);
    }

    /** The attachment, when its file is really there. One whose file has gone missing is said to be gone, not a crash. */
    private Attachment found(Context ctx) {
        Attachment attachment = attachments.find(Inputs.id(ctx, "id")).orElseThrow(ApiException::notFound);
        files.requireOnDisk(attachment.hash());
        return attachment;
    }

    private Owner entry(Context ctx) {
        long id = Inputs.id(ctx, "id");
        entries.find(id).orElseThrow(ApiException::notFound);
        return new Owner.Entry(id);
    }

    private Owner occurrence(Context ctx) {
        Item item = items.find(Inputs.id(ctx, "id")).orElseThrow(ApiException::notFound);
        LocalDate scheduledDate;
        try {
            scheduledDate = Inputs.date(ctx.pathParam("scheduledDate"));
        } catch (ApiException e) {
            throw ApiException.notFound();
        }
        if (!Inputs.inSupportedYears(scheduledDate) || calendar.find(item, scheduledDate).isEmpty()) {
            throw ApiException.notFound();
        }
        return new Owner.Occurrence(item.id(), scheduledDate);
    }

    private Owner statement(Context ctx) {
        long cardId = Inputs.id(ctx, "id");
        long statementId = Inputs.id(ctx, "statementId");
        accounts.find(cardId)
                .filter(account -> account.type().equals(Inputs.code(AccountType.CREDIT_CARD)))
                .orElseThrow(ApiException::notFound);
        if (cards.statements(cardId).stream().noneMatch(statement -> statement.id() == statementId)) {
            throw ApiException.notFound();
        }
        return new Owner.Statement(statementId);
    }
}
