package app.budget.server.files;

import app.budget.server.api.Requests;
import io.javalin.http.Context;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Puts a file the user asked for (an exported report, a copy of a receipt) straight into this
 * PC's Downloads folder, and says what it was called there.
 *
 * <p>The desktop window saves a file handed to it without showing anything, and refuses the ones
 * after the first, so from this PC the backend writes the file itself and the screen says where
 * it went. A request from anywhere else (the phone) has no folder of this PC to save into: it is
 * answered with the file, for its own browser to keep.
 *
 * <p>Nothing here is logged: a file's name can say what it is about.
 */
public final class SavedCopies {

    /** What a copy was saved as, and the folder it is in, for the screen to say. */
    public record Saved(String fileName, String folder) {}

    /** Names Windows keeps for devices, which no file can have whatever its extension. */
    private static final Set<String> RESERVED = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    /** Windows allows 255; shorter leaves room for " (12)" and keeps paths sensible. */
    private static final int LONGEST_NAME = 120;

    /** How many files of the same name may sit side by side before giving up. */
    private static final int MOST_COPIES = 999;

    private final Optional<Path> folder;

    /** @param folder this PC's Downloads folder; empty when there is none to save into */
    public SavedCopies(Optional<Path> folder) {
        this.folder = folder;
    }

    /** Whether this request can be answered by saving here: it came from this PC, and the folder is there. */
    public boolean canSaveFor(Context ctx) {
        return Requests.isFromThisPc(ctx) && folder.filter(Files::isDirectory).isPresent();
    }

    /** Saves {@code bytes} under {@code name}, or the nearest name that is free: nothing already there is replaced. */
    public Saved save(String name, byte[] bytes) {
        return write(name, out -> out.write(bytes));
    }

    /** Saves a copy of the file at {@code source} under {@code name}, or the nearest name that is free. */
    public Saved copy(String name, Path source) {
        return write(name, out -> {
            try (InputStream in = Files.newInputStream(source)) {
                in.transferTo(out);
            }
        });
    }

    private interface Contents {
        void writeTo(OutputStream out) throws IOException;
    }

    private Saved write(String name, Contents contents) {
        Path into = folder.orElseThrow(() -> new IllegalStateException("There is no folder to save into"));
        String safe = safeName(name);
        int dot = safe.lastIndexOf('.');
        String stem = dot > 0 ? safe.substring(0, dot) : safe;
        String extension = dot > 0 ? safe.substring(dot) : "";
        for (int copy = 1; copy <= MOST_COPIES; copy++) {
            // "Report.pdf", then "Report (2).pdf", as Windows itself names a second download.
            Path target = into.resolve(copy == 1 ? safe : stem + " (" + copy + ")" + extension);
            try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                contents.writeTo(out);
                return new Saved(target.getFileName().toString(), into.toString());
            } catch (FileAlreadyExistsException taken) {
                // Someone's file already has that name: try the next.
            } catch (IOException e) {
                try {
                    // Half a file is worse than none.
                    Files.deleteIfExists(target);
                } catch (IOException ignored) {
                    // Nothing more can be done about it here; the failure below is what gets reported.
                }
                throw new UncheckedIOException("Could not save a file into the Downloads folder", e);
            }
        }
        throw new UncheckedIOException(new IOException("Too many files of the same name in the Downloads folder"));
    }

    /** {@code name} as a file can be called on Windows: nothing that is a path, a device, or too long. */
    static String safeName(String name) {
        String cleaned = name.replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", " ").strip();
        // Windows drops dots and spaces from the end of a name, which would change what was asked for.
        cleaned = cleaned.replaceAll("[. ]+$", "");
        if (cleaned.isEmpty()) {
            cleaned = "File";
        }
        int dot = cleaned.lastIndexOf('.');
        String stem = dot > 0 ? cleaned.substring(0, dot) : cleaned;
        String extension = dot > 0 ? cleaned.substring(dot) : "";
        if (RESERVED.contains(stem.strip().toLowerCase(Locale.ROOT))) {
            stem = stem + " file";
        }
        int room = Math.max(1, LONGEST_NAME - extension.length());
        if (stem.length() > room) {
            stem = stem.substring(0, room).strip();
        }
        return stem + extension;
    }
}
