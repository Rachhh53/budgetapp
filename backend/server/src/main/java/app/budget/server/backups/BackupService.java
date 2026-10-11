package app.budget.server.backups;

import app.budget.server.auth.AuthRepository;
import app.budget.server.db.Database;
import app.budget.server.settings.SettingKey;
import app.budget.server.settings.SettingsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backups and restore (spec §7): a backup is one dated zip holding a consistent copy of the
 * database and the {@code files/} folder (receipts, pay stubs). One is made each day the app
 * runs, the newest N are kept, and any of them can be restored.
 *
 * <p>Backups go to the folder chosen in Settings, or into the app's own data folder until
 * one is chosen. They are plain copies, not encrypted: where they are kept is the user's
 * choice, and Settings says so.
 *
 * <p>Folder paths can hold a person's name, so they are never written to the log.
 */
public final class BackupService {

    /** A backup as Settings lists it. */
    public record Backup(String name, LocalDateTime createdAt, long sizeBytes) {}

    /** Why a backup could not be restored; each maps to its own kind message in the routes. */
    public enum RestoreProblem {
        NOT_FOUND,
        /** Not a zip this app made, or damaged. */
        UNREADABLE,
        /** Made by a newer version of the app than this one. */
        TOO_NEW
    }

    public static final class RestoreException extends RuntimeException {
        private final RestoreProblem problem;

        RestoreException(RestoreProblem problem) {
            super(problem.name());
            this.problem = problem;
        }

        public RestoreProblem problem() {
            return problem;
        }
    }

    /** The folder cannot be written to (a drive that is not plugged in, say). */
    public static final class FolderUnavailableException extends RuntimeException {
        FolderUnavailableException(Throwable cause) {
            super("The backup folder could not be written to", cause);
        }
    }

    static final String PREFIX = "budget-backup-";
    /** Taken just before a restore, so the restore itself can be taken back. Kept apart from the daily ones. */
    static final String BEFORE_RESTORE_PREFIX = "budget-before-restore-";
    static final int BEFORE_RESTORE_KEPT = 3;

    private static final String ZIP = ".zip";
    private static final String PART = ".part";
    private static final String MANIFEST_ENTRY = "backup.json";
    private static final String FILES_ENTRY = Database.FILES_DIR + "/";
    private static final String APP = "Budget";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern DAILY_NAME = Pattern.compile(Pattern.quote(PREFIX) + "(\\d{8}-\\d{6})\\.zip");
    private static final Pattern BEFORE_RESTORE_NAME =
            Pattern.compile(Pattern.quote(BEFORE_RESTORE_PREFIX) + "(\\d{8}-\\d{6})\\.zip");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    /** What a backup says about itself, so a restore can tell whether this app can read it. */
    public record Manifest(String app, String createdAt, int schemaVersion) {}

    private final Database database;
    private final SettingsRepository settings;
    private final AuthRepository auth;
    private final Clock clock;

    /** Set when the daily backup fails, cleared by the next one that works; shown in Settings. */
    private volatile boolean lastDailyBackupFailed;

    public BackupService(Database database, SettingsRepository settings, AuthRepository auth, Clock clock) {
        this.database = database;
        this.settings = settings;
        this.auth = auth;
        this.clock = clock;
    }

    /** Where backups go: the folder chosen in Settings, or the app's own until one is. */
    public Path folder() {
        String chosen = settings.get(SettingKey.BACKUP_FOLDER);
        return chosen.isBlank() ? defaultFolder() : Path.of(chosen);
    }

    public boolean usingDefaultFolder() {
        return settings.get(SettingKey.BACKUP_FOLDER).isBlank();
    }

    public boolean lastDailyBackupFailed() {
        return lastDailyBackupFailed;
    }

    private Path defaultFolder() {
        return database.dataDir().resolve(Database.BACKUP_DIR);
    }

    /**
     * Chooses the folder backups are saved to; {@code null} goes back to the app's own.
     *
     * @return false when the path is not a folder that exists and can be written to
     */
    public boolean chooseFolder(String path) {
        if (path == null || path.isBlank()) {
            settings.put(SettingKey.BACKUP_FOLDER, "");
            return true;
        }
        Path folder;
        try {
            folder = Path.of(path).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return false;
        }
        if (!Files.isDirectory(folder) || !Files.isWritable(folder)) {
            return false;
        }
        settings.put(SettingKey.BACKUP_FOLDER, folder.toString());
        return true;
    }

    /** The daily backups in the folder, newest first. An unreachable folder lists nothing. */
    public List<Backup> list() {
        return named(folder(), DAILY_NAME);
    }

    /** "Back up now": makes a backup and removes the oldest beyond the number kept. */
    public synchronized Backup backUpNow() {
        Path folder = folder();
        Backup made = write(folder, PREFIX);
        prune(folder, DAILY_NAME, Integer.parseInt(settings.get(SettingKey.BACKUP_KEEP_COUNT)));
        lastDailyBackupFailed = false;
        return made;
    }

    /**
     * The daily backup: made once per calendar day, the first time this is called that day.
     * Never throws: it runs on a timer, and a folder that is away must not stop the app.
     *
     * @return true when a backup was made
     */
    public synchronized boolean backUpIfDue() {
        try {
            LocalDate today = LocalDate.now(clock);
            if (list().stream().anyMatch(backup -> backup.createdAt().toLocalDate().equals(today))) {
                return false;
            }
            backUpNow();
            return true;
        } catch (RuntimeException e) {
            lastDailyBackupFailed = true;
            // The exception's message can hold the folder's path, so only its kind is logged.
            log.warn("The daily backup could not be saved ({})", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Restores the backup with this name from the backup folder, replacing everything here.
     * What is here now is backed up first, into the app's own folder, so a restore can itself
     * be taken back. The PIN and the backup folder stay as they are now: restoring is about
     * the budget, and an old PIN coming back could lock its owner out.
     */
    public synchronized void restore(String name) {
        Path zip = find(name).orElseThrow(() -> new RestoreException(RestoreProblem.NOT_FOUND));
        Path work = workFolder("restore-work");
        try {
            Unpacked unpacked = unpack(zip, work);
            AuthRepository.AuthState lock = auth.read();
            String backupFolder = settings.get(SettingKey.BACKUP_FOLDER);

            write(defaultFolder(), BEFORE_RESTORE_PREFIX);
            prune(defaultFolder(), BEFORE_RESTORE_NAME, BEFORE_RESTORE_KEPT);

            database.replaceWith(unpacked.database(), unpacked.files());
            auth.write(lock);
            settings.put(SettingKey.BACKUP_FOLDER, backupFolder);
        } finally {
            deleteRecursively(work);
        }
    }

    private record Unpacked(Path database, Path files) {}

    /** Only a name from the list is ever opened, so a request cannot point this at any other file. */
    private Optional<Path> find(String name) {
        if (name == null || !DAILY_NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        Path zip = folder().resolve(name);
        return Files.isRegularFile(zip) ? Optional.of(zip) : Optional.empty();
    }

    /** Checks a backup and lays it out in {@code work}, ready to be moved into place. */
    private Unpacked unpack(Path zip, Path work) {
        Path db = work.resolve(Database.DB_FILE);
        Path files = work.resolve(Database.FILES_DIR);
        Manifest manifest = null;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                String entryName = entry.getName();
                if (entryName.equals(MANIFEST_ENTRY)) {
                    manifest = JSON.readValue(in.readAllBytes(), Manifest.class);
                } else if (entryName.equals(Database.DB_FILE)) {
                    Files.copy(in, db);
                } else if (entryName.startsWith(FILES_ENTRY) && !entry.isDirectory()) {
                    // A zip can name any path it likes; anything that would land outside the folder is left out.
                    Path target = files.resolve(entryName.substring(FILES_ENTRY.length())).normalize();
                    if (target.startsWith(files)) {
                        Files.createDirectories(target.getParent());
                        Files.copy(in, target);
                    }
                }
            }
        } catch (IOException | InvalidPathException e) {
            throw new RestoreException(RestoreProblem.UNREADABLE);
        }
        if (manifest == null || !APP.equals(manifest.app()) || !Files.exists(db)) {
            throw new RestoreException(RestoreProblem.UNREADABLE);
        }
        int version;
        try {
            version = Database.schemaVersionOf(db);
        } catch (Database.NotABudgetDatabaseException e) {
            throw new RestoreException(RestoreProblem.UNREADABLE);
        }
        if (version > database.schemaVersion()) {
            throw new RestoreException(RestoreProblem.TOO_NEW);
        }
        return new Unpacked(db, Files.isDirectory(files) ? files : null);
    }

    /** Writes one backup into {@code folder}. It appears under its real name only once it is complete. */
    private Backup write(Path folder, String prefix) {
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        String name = prefix + now.format(STAMP) + ZIP;
        Path work = workFolder("backup-work");
        try {
            Path snapshot = work.resolve(Database.DB_FILE);
            database.snapshotTo(snapshot);
            Path part;
            try {
                Files.createDirectories(folder);
                part = folder.resolve(name + PART);
                try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(part))) {
                    zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
                    zip.write(JSON.writeValueAsBytes(new Manifest(APP, now.toString(), database.schemaVersion())));
                    zip.closeEntry();
                    add(zip, Database.DB_FILE, snapshot);
                    addFiles(zip, database.dataDir().resolve(Database.FILES_DIR));
                }
                Path finished = folder.resolve(name);
                Files.move(part, finished, StandardCopyOption.REPLACE_EXISTING);
                return new Backup(name, now, Files.size(finished));
            } catch (IOException e) {
                throw new FolderUnavailableException(e);
            }
        } finally {
            deleteRecursively(work);
        }
    }

    private static void addFiles(ZipOutputStream zip, Path filesDir) throws IOException {
        if (!Files.isDirectory(filesDir)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(filesDir)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path file : files) {
            // Forward slashes, whatever the operating system: that is what a zip entry name uses.
            add(zip, FILES_ENTRY + filesDir.relativize(file).toString().replace('\\', '/'), file);
        }
    }

    private static void add(ZipOutputStream zip, String entryName, Path file) throws IOException {
        zip.putNextEntry(new ZipEntry(entryName));
        try (InputStream in = Files.newInputStream(file)) {
            in.transferTo((OutputStream) zip);
        }
        zip.closeEntry();
    }

    /** The backups in a folder whose names match, newest first. The time comes from the name, never the file's date. */
    private static List<Backup> named(Path folder, Pattern pattern) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        List<Backup> found = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*" + ZIP)) {
            for (Path file : files) {
                Matcher matcher = pattern.matcher(file.getFileName().toString());
                if (matcher.matches() && Files.isRegularFile(file)) {
                    found.add(new Backup(
                            file.getFileName().toString(), LocalDateTime.parse(matcher.group(1), STAMP), Files.size(file)));
                }
            }
        } catch (IOException | DateTimeParseException e) {
            return List.of();
        }
        found.sort(Comparator.comparing(Backup::createdAt).reversed());
        return List.copyOf(found);
    }

    private static void prune(Path folder, Pattern pattern, int keep) {
        List<Backup> newestFirst = named(folder, pattern);
        for (Backup stale : newestFirst.subList(Math.min(keep, newestFirst.size()), newestFirst.size())) {
            try {
                Files.deleteIfExists(folder.resolve(stale.name()));
            } catch (IOException e) {
                // Left for the next backup to remove; one extra old file is no harm.
            }
        }
    }

    /** A scratch folder inside the data folder, so moving out of it never crosses drives. */
    private Path workFolder(String prefix) {
        try {
            return Files.createTempDirectory(database.dataDir(), prefix);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not make a working folder", e);
        }
    }

    private static void deleteRecursively(Path folder) {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> contents = Files.walk(folder)) {
            for (Path path : contents.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            // A scratch folder left behind holds a copy of data that is already on this disk.
            log.warn("A working folder could not be removed");
        }
    }
}
