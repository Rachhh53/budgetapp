package app.budget.server.files;

import app.budget.server.db.Database;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The receipts and documents themselves, kept in the data folder's {@code files/} (spec §3,
 * §4.8). A file is named by the SHA-256 of its contents, so the same one saved twice is
 * kept once and a name the user chose never touches the disk. Originals are never modified.
 *
 * <p>Nothing here knows what a file is attached to; that is {@link AttachmentsRepository}.
 * The folder is looked up on every call because restoring a backup and erasing all data
 * both replace it while the app runs.
 */
public final class FileStore {

    private static final Logger log = LoggerFactory.getLogger(FileStore.class);

    /** What can be attached. Decided by the file's first bytes, never by its name or what the browser claimed. */
    public enum Kind {
        PDF("application/pdf"),
        JPEG("image/jpeg"),
        PNG("image/png");

        private final String mime;

        Kind(String mime) {
            this.mime = mime;
        }

        public String mime() {
            return mime;
        }

        public static Kind ofMime(String mime) {
            for (Kind kind : values()) {
                if (kind.mime.equals(mime)) {
                    return kind;
                }
            }
            throw new IllegalStateException("A stored file has a type this version does not know");
        }
    }

    /** Why a file could not be kept. Each has its own kind words in {@code AttachmentsRoutes}. */
    public enum Problem { EMPTY, TOO_LARGE, NOT_SUPPORTED, UNREADABLE, LOCKED_PDF }

    public static final class RefusedFileException extends RuntimeException {
        private final Problem problem;

        RefusedFileException(Problem problem) {
            super("File refused: " + problem);
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }

    /**
     * A file now on disk.
     *
     * @param pages how many pages a PDF has; {@code null} for a picture
     */
    public record Stored(String hash, Kind kind, long size, Integer pages) {}

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SHARD = Pattern.compile("[0-9a-f]{2}");
    private static final String PART_PREFIX = "upload-";
    private static final String PART_SUFFIX = ".part";

    /** A preview is for reading on screen, not printing: about a page width on a laptop. */
    private static final float PREVIEW_DPI = 110;

    private final Path dataDir;
    private final long maxBytes;

    public FileStore(Path dataDir, long maxBytes) {
        this.dataDir = dataDir;
        this.maxBytes = maxBytes;
    }

    public long maxBytes() {
        return maxBytes;
    }

    /**
     * Copies {@code content} into the store and checks it is something that can be shown again.
     *
     * @throws RefusedFileException when it is empty, too large, not a PDF, JPEG or PNG, or cannot be read
     */
    public Stored save(InputStream content) {
        Path part = null;
        try {
            Files.createDirectories(folder());
            part = Files.createTempFile(folder(), PART_PREFIX, PART_SUFFIX);
            MessageDigest digest = sha256();
            long size = copy(new DigestInputStream(content, digest), part);
            if (size == 0) {
                throw new RefusedFileException(Problem.EMPTY);
            }
            Kind kind = sniff(part);
            Integer pages = kind == Kind.PDF ? pdfPages(part) : null;
            if (kind != Kind.PDF) {
                requireReadableImage(part);
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            Path target = path(hash);
            if (Files.exists(target)) {
                // The same contents are already kept: this copy is not needed.
                Files.delete(part);
            } else {
                Files.createDirectories(target.getParent());
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            }
            part = null;
            return new Stored(hash, kind, size, pages);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not keep an attached file", e);
        } finally {
            deleteQuietly(part);
        }
    }

    /** Where the file with this hash is, whether or not it is there. */
    public Path path(String hash) {
        if (!HASH.matcher(hash).matches()) {
            throw new IllegalArgumentException("Not a file hash");
        }
        return folder().resolve(hash.substring(0, 2)).resolve(hash);
    }

    public boolean exists(String hash) {
        return Files.isRegularFile(path(hash));
    }

    /**
     * One page of a PDF as a PNG picture, for showing on screen.
     *
     * @param page 1 for the first page
     * @throws RefusedFileException when the PDF cannot be drawn
     */
    public byte[] previewOfPdf(String hash, int page) {
        try (PDDocument document = Loader.loadPDF(path(hash).toFile())) {
            if (page < 1 || page > document.getNumberOfPages()) {
                throw new RefusedFileException(Problem.UNREADABLE);
            }
            BufferedImage image = new PDFRenderer(document).renderImageWithDPI(page - 1, PREVIEW_DPI, ImageType.RGB);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(image, "png", png);
            return png.toByteArray();
        } catch (IOException | RuntimeException e) {
            if (e instanceof RefusedFileException refused) {
                throw refused;
            }
            // A PDF that opened when it was attached but cannot be drawn: odd fonts, damage.
            log.warn("A PDF page could not be drawn for its preview", e);
            throw new RefusedFileException(Problem.UNREADABLE);
        }
    }

    /**
     * Deletes what is on disk and no longer wanted: every stored file whose hash is not in
     * {@code keep}, and uploads that never finished. Anything else in the folder is left alone.
     *
     * @return how many files were deleted
     */
    public int sweep(Set<String> keep) {
        Path folder = folder();
        if (!Files.isDirectory(folder)) {
            return 0;
        }
        int deleted = 0;
        try (DirectoryStream<Path> top = Files.newDirectoryStream(folder)) {
            for (Path entry : top) {
                String name = entry.getFileName().toString();
                if (Files.isRegularFile(entry) && name.startsWith(PART_PREFIX) && name.endsWith(PART_SUFFIX)) {
                    Files.deleteIfExists(entry);
                    deleted++;
                } else if (Files.isDirectory(entry) && SHARD.matcher(name).matches()) {
                    deleted += sweepShard(entry, keep);
                }
            }
        } catch (IOException e) {
            // Leftover files cost disk space, nothing more; the next start tries again.
            log.warn("Could not finish tidying the attachments folder", e);
        }
        return deleted;
    }

    private static int sweepShard(Path shard, Set<String> keep) throws IOException {
        int deleted = 0;
        boolean empty = true;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(shard)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (Files.isRegularFile(file) && HASH.matcher(name).matches() && !keep.contains(name)) {
                    Files.deleteIfExists(file);
                    deleted++;
                } else {
                    empty = false;
                }
            }
        }
        if (empty) {
            Files.deleteIfExists(shard);
        }
        return deleted;
    }

    private Path folder() {
        return dataDir.resolve(Database.FILES_DIR);
    }

    /** Copies at most {@link #maxBytes}; one byte more and the file is refused. */
    private long copy(InputStream in, Path target) throws IOException {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new RefusedFileException(Problem.TOO_LARGE);
                }
                out.write(buffer, 0, read);
            }
        }
        return total;
    }

    private static Kind sniff(Path file) throws IOException {
        byte[] start = new byte[PNG_MAGIC.length];
        int read;
        try (InputStream in = Files.newInputStream(file)) {
            read = in.readNBytes(start, 0, start.length);
        }
        if (startsWith(start, read, PDF_MAGIC)) {
            return Kind.PDF;
        }
        if (startsWith(start, read, JPEG_MAGIC)) {
            return Kind.JPEG;
        }
        if (startsWith(start, read, PNG_MAGIC)) {
            return Kind.PNG;
        }
        throw new RefusedFileException(Problem.NOT_SUPPORTED);
    }

    private static boolean startsWith(byte[] bytes, int length, byte[] magic) {
        if (length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (bytes[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private static int pdfPages(Path file) {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            int pages = document.getNumberOfPages();
            if (pages < 1) {
                throw new RefusedFileException(Problem.UNREADABLE);
            }
            return pages;
        } catch (InvalidPasswordException e) {
            throw new RefusedFileException(Problem.LOCKED_PDF);
        } catch (IOException e) {
            throw new RefusedFileException(Problem.UNREADABLE);
        }
    }

    /** Reads the picture's size only, which is enough to know a reader understands the file. */
    private static void requireReadableImage(Path file) {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw new RefusedFileException(Problem.UNREADABLE);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                if (reader.getWidth(0) < 1 || reader.getHeight(0) < 1) {
                    throw new RefusedFileException(Problem.UNREADABLE);
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof RefusedFileException refused) {
                throw refused;
            }
            throw new RefusedFileException(Problem.UNREADABLE);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not remove an unfinished upload; it is removed at the next start", e);
        }
    }

    /** The extension to give a download of this kind when the name the user chose has none. */
    public static String extension(Kind kind) {
        return kind.name().toLowerCase(Locale.ROOT).replace("jpeg", "jpg");
    }
}
