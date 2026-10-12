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
import app.budget.server.files.FileStore.Kind;
import app.budget.server.files.FileStore.RefusedFileException;
import app.budget.server.files.FileStore.Stored;
import app.budget.server.items.ItemCalendar;
import app.budget.server.items.ItemsRepository;
import app.budget.server.items.ItemsRepository.Item;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Receipts and documents (spec §4.8): attached to a one-off entry, to one time a recurring
 * item came due, or to a card statement; listed, shown, saved again and removed.
 *
 * <p>A file is only ever reached through the id of an attachment, behind the session like
 * everything else. Its name on disk is never sent, and a name the user gave is never logged.
 */
public final class AttachmentsRoutes {

    /** Plenty for one receipt, its invoice and the insurer's letters. */
    public static final int MAX_PER_OWNER = 20;
    private static final int NAME_MAX = 120;
    private static final String FILE_FIELD = "file";
    /** Pages recently drawn, so paging back and forth through a PDF does not draw each page again. */
    private static final int PREVIEWS_REMEMBERED = 12;

    private final AttachmentsRepository attachments;
    private final FileStore store;
    private final EntriesRepository entries;
    private final ItemsRepository items;
    private final ItemCalendar calendar;
    private final CardsRepository cards;
    private final AccountsRepository accounts;
    private final Clock clock;
    private final SavedCopies savedCopies;

    private final Map<String, byte[]> previews = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > PREVIEWS_REMEMBERED;
        }
    });

    public AttachmentsRoutes(
            AttachmentsRepository attachments,
            FileStore store,
            EntriesRepository entries,
            ItemsRepository items,
            ItemCalendar calendar,
            CardsRepository cards,
            AccountsRepository accounts,
            Clock clock,
            SavedCopies savedCopies) {
        this.attachments = attachments;
        this.store = store;
        this.entries = entries;
        this.items = items;
        this.calendar = calendar;
        this.cards = cards;
        this.accounts = accounts;
        this.clock = clock;
        this.savedCopies = savedCopies;
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
        List<UploadedFile> uploads = uploads(ctx);
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
            try (InputStream content = upload.content()) {
                kept.add(store.save(content));
            } catch (RefusedFileException e) {
                throw refused(e);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read an uploaded file", e);
            }
        }
        for (int i = 0; i < kept.size(); i++) {
            attachments.add(owner, kept.get(i), name(uploads.get(i).filename(), kept.get(i).kind()));
        }
        ApiResponse.send(ctx, 201, attached(owner));
    }

    /** Sends the file exactly as it was attached, to be saved: never shown in place of the app. */
    private void download(Context ctx) {
        Attachment attachment = found(ctx);
        ctx.contentType(attachment.kind().mime());
        ctx.header("Content-Disposition", "attachment; filename*=UTF-8''" + encoded(attachment.name()));
        ctx.header("Content-Length", String.valueOf(attachment.sizeBytes()));
        try {
            ctx.result(Files.newInputStream(store.path(attachment.hash())));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open an attached file", e);
        }
    }

    /**
     * "Save a copy", asked from this PC: the file goes into the Downloads folder and the answer says what
     * it is called there. Asked from anywhere else, the answer is that nothing was saved, and the screen
     * falls back to a download of {@code /file}.
     */
    private void saveCopy(Context ctx) {
        Attachment attachment = found(ctx);
        if (!savedCopies.canSaveFor(ctx)) {
            ApiResponse.ok(ctx, new CopyResult(null));
            return;
        }
        ApiResponse.ok(ctx, new CopyResult(savedCopies.copy(attachment.name(), store.path(attachment.hash()))));
    }

    /** @param saved what the copy was saved as; null when it could not be saved from here */
    public record CopyResult(SavedCopies.Saved saved) {}

    /** A picture of the file for the screen: the picture itself, or one page of a PDF as a PNG. */
    private void preview(Context ctx) {
        Attachment attachment = found(ctx);
        if (attachment.kind() != Kind.PDF) {
            ctx.contentType(attachment.kind().mime());
            try {
                ctx.result(Files.newInputStream(store.path(attachment.hash())));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not open an attached file", e);
            }
            return;
        }
        int page = page(ctx, attachment);
        byte[] png;
        try {
            png = previews.computeIfAbsent(attachment.hash() + "#" + page, key -> store.previewOfPdf(attachment.hash(), page));
        } catch (RefusedFileException e) {
            throw new ApiException(422, "We couldn't draw this page, but the file itself is safe. You can still save a copy and open it.");
        }
        ctx.contentType("image/png").result(png);
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
        return new Attached(views, store.maxBytes(), MAX_PER_OWNER);
    }

    /** The attachment, when its file is really there. One whose file has gone missing is said to be gone, not a crash. */
    private Attachment found(Context ctx) {
        Attachment attachment = attachments.find(Inputs.id(ctx, "id")).orElseThrow(ApiException::notFound);
        if (!store.exists(attachment.hash())) {
            throw new ApiException(410, "We couldn't find this file on this PC. It may have been removed from Budget's folder.");
        }
        return attachment;
    }

    private static int page(Context ctx, Attachment attachment) {
        String value = ctx.queryParam("page");
        int page;
        try {
            page = value == null || value.isBlank() ? 1 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw ApiException.notFound();
        }
        if (page < 1 || attachment.pages() == null || page > attachment.pages()) {
            throw ApiException.notFound();
        }
        return page;
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

    /** The files sent, or a kind refusal when the request was not an upload or was over the server's limit. */
    private List<UploadedFile> uploads(Context ctx) {
        if (ctx.contentLength() > store.maxBytes() * MAX_PER_OWNER) {
            throw tooLarge();
        }
        String type = ctx.contentType();
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            throw ApiException.badRequest("Please choose a file to attach.");
        }
        try {
            return ctx.uploadedFiles(FILE_FIELD);
        } catch (RuntimeException e) {
            // The server's own limit on an upload was passed, or the upload was cut short.
            throw tooLarge();
        }
    }

    private ApiException refused(RefusedFileException e) {
        return switch (e.problem()) {
            case EMPTY -> ApiException.badRequest("That file is empty. Please choose another.");
            case TOO_LARGE -> tooLarge();
            case NOT_SUPPORTED -> new ApiException(415,
                    "Budget can keep PDFs and JPEG or PNG pictures. Please save this as one of those and try again.");
            case LOCKED_PDF -> new ApiException(422,
                    "That PDF is locked with a password. Please save a copy without one and try again.");
            case UNREADABLE -> new ApiException(422,
                    "We couldn't read that file. It may be damaged; please try saving it again.");
        };
    }

    private ApiException tooLarge() {
        return new ApiException(413, "That file is larger than " + store.maxBytes() / (1024 * 1024)
                + " MB. Please choose a smaller one, or save it at a lower quality.");
    }

    /**
     * The name to show for an upload: the last part of whatever path the browser sent, with
     * nothing that could not be a file name, and never empty.
     */
    static String name(String sent, Kind kind) {
        String name = sent == null ? "" : sent.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}<>:\"|?*]", "")
                .strip();
        if (name.length() > NAME_MAX) {
            // Keep the end, where the extension is.
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 && name.length() - dot <= 8 ? name.substring(dot) : "";
            name = name.substring(0, NAME_MAX - extension.length()).strip() + extension;
        }
        if (name.isEmpty() || name.chars().allMatch(c -> c == '.')) {
            name = (kind == Kind.PDF ? "Document." : "Picture.") + FileStore.extension(kind);
        }
        return name;
    }

    /** RFC 5987: a name in any language survives the trip in a header. */
    private static String encoded(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
