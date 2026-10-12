package app.budget.server.files;

import app.budget.server.api.ApiException;
import app.budget.server.files.FileStore.Kind;
import app.budget.server.files.FileStore.RefusedFileException;
import app.budget.server.files.FileStore.Stored;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Taking a file in from the browser and handing one back, the same way wherever files are
 * kept: receipts on entries, bills and statements ({@link AttachmentsRoutes}) and pay stubs.
 *
 * <p>A file is only ever sent as a download, or as a picture the backend drew itself, so
 * nothing inside an uploaded file is run by the window (spec §7). Its name on disk is never
 * sent, and a name the user gave is never logged.
 */
public final class StoredFiles {

    private static final String FILE_FIELD = "file";
    private static final int NAME_MAX = 120;
    /** Pages recently drawn, so paging back and forth through a PDF does not draw each page again. */
    private static final int PREVIEWS_REMEMBERED = 12;

    private final FileStore store;
    private final SavedCopies savedCopies;

    private final Map<String, byte[]> previews = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > PREVIEWS_REMEMBERED;
        }
    });

    public StoredFiles(FileStore store, SavedCopies savedCopies) {
        this.store = store;
        this.savedCopies = savedCopies;
    }

    /** @param saved what the copy was saved as; null when it could not be saved from here */
    public record CopyResult(SavedCopies.Saved saved) {}

    public long maxBytes() {
        return store.maxBytes();
    }

    /**
     * The files sent under "file", or a kind refusal when the request was not an upload or was
     * over what {@code mostFiles} files could come to.
     */
    public List<UploadedFile> uploads(Context ctx, int mostFiles) {
        if (ctx.contentLength() > store.maxBytes() * mostFiles) {
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

    /** Keeps an upload in the store, or refuses it in words the person can act on. */
    public Stored keep(UploadedFile upload) {
        try (InputStream content = upload.content()) {
            return store.save(content);
        } catch (RefusedFileException e) {
            throw refused(e);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read an uploaded file", e);
        }
    }

    /** Where a kept file is on disk, for reading it here; never sent to anyone. */
    public Path path(String hash) {
        return store.path(hash);
    }

    /** Refuses with "gone" a file that is on record and no longer on disk, rather than failing on it. */
    public void requireOnDisk(String hash) {
        if (!store.exists(hash)) {
            throw new ApiException(410, "We couldn't find this file on this PC. It may have been removed from Budget's folder.");
        }
    }

    /** Sends the file exactly as it was added, to be saved: never shown in place of the app. */
    public void download(Context ctx, String name, Kind kind, long sizeBytes, String hash) {
        ctx.contentType(kind.mime());
        ctx.header("Content-Disposition", "attachment; filename*=UTF-8''" + encoded(name));
        ctx.header("Content-Length", String.valueOf(sizeBytes));
        try {
            ctx.result(Files.newInputStream(store.path(hash)));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open a stored file", e);
        }
    }

    /**
     * "Save a copy", asked from this PC: the file goes into the Downloads folder and the answer
     * says what it is called there. Asked from anywhere else, the answer is that nothing was
     * saved, and the screen falls back to a download.
     */
    public CopyResult copy(Context ctx, String name, String hash) {
        if (!savedCopies.canSaveFor(ctx)) {
            return new CopyResult(null);
        }
        return new CopyResult(savedCopies.copy(name, store.path(hash)));
    }

    /**
     * A picture of the file for the screen: the picture itself, or the page of a PDF asked for
     * with {@code ?page=} as a PNG.
     *
     * @param pages how many pages a PDF has; null for a picture
     */
    public void preview(Context ctx, String hash, Kind kind, Integer pages) {
        if (kind != Kind.PDF) {
            ctx.contentType(kind.mime());
            try {
                ctx.result(Files.newInputStream(store.path(hash)));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not open a stored file", e);
            }
            return;
        }
        int page = page(ctx, pages);
        byte[] png;
        try {
            png = previews.computeIfAbsent(hash + "#" + page, key -> store.previewOfPdf(hash, page));
        } catch (RefusedFileException e) {
            throw new ApiException(422, "We couldn't draw this page, but the file itself is safe. You can still save a copy and open it.");
        }
        ctx.contentType("image/png").result(png);
    }

    public ApiException tooLarge() {
        return new ApiException(413, "That file is larger than " + store.maxBytes() / (1024 * 1024)
                + " MB. Please choose a smaller one, or save it at a lower quality.");
    }

    /**
     * The name to show for an upload: the last part of whatever path the browser sent, with
     * nothing that could not be a file name, and never empty.
     */
    public static String name(String sent, Kind kind) {
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

    private static int page(Context ctx, Integer pages) {
        String value = ctx.queryParam("page");
        int page;
        try {
            page = value == null || value.isBlank() ? 1 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw ApiException.notFound();
        }
        if (page < 1 || pages == null || page > pages) {
            throw ApiException.notFound();
        }
        return page;
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

    /** RFC 5987: a name in any language survives the trip in a header. */
    private static String encoded(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
