package app.budget.server.db;

import app.budget.server.changes.ChangeLog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * Opens the single local SQLite file and brings its schema up to date.
 *
 * <p>If an existing database is about to be migrated (the app was upgraded), a full copy is
 * taken first, so an upgrade can never be the reason someone loses their data.
 */
public final class Database implements AutoCloseable {

    public static final String DB_FILE = "budget.db";
    public static final String BACKUP_DIR = "backups";
    /** Attachments (receipts, pay stubs) — spec §3. */
    public static final String FILES_DIR = "files";
    static final String PRE_MIGRATION_PREFIX = "pre-migration-";
    static final int PRE_MIGRATION_BACKUPS_KEPT = 5;
    private static final String DEFAULT_MIGRATIONS = "classpath:db/migration";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");
    private static final Logger log = LoggerFactory.getLogger(Database.class);

    /** Folders inside the data folder that hold the user's data besides the database itself. */
    private static final List<String> DATA_FOLDERS = List.of(BACKUP_DIR, FILES_DIR);

    /** The database file plus SQLite's write-ahead log and shared-memory companions. */
    private static final List<String> DB_FILE_SUFFIXES = List.of("", "-wal", "-shm");

    private final Path dataDir;
    private final KeptOpenDataSource dataSource;
    private final Flyway flyway;
    private final Jdbi jdbi;

    private Database(Path dataDir, KeptOpenDataSource dataSource, Flyway flyway) {
        this.dataDir = dataDir;
        this.dataSource = dataSource;
        this.flyway = flyway;
        this.jdbi = Jdbi.create(dataSource);
    }

    public static Database open(Path dataDir) {
        return open(dataDir, Clock.systemDefaultZone(), DEFAULT_MIGRATIONS);
    }

    /** Test seam: lets a test add extra migration locations to simulate an upgrade. */
    static Database open(Path dataDir, Clock clock, String... migrationLocations) {
        Path dbFile = dataDir.resolve(DB_FILE);
        boolean existed = Files.exists(dbFile);
        KeptOpenDataSource dataSource = new KeptOpenDataSource(dataSource(dbFile));

        long started = System.nanoTime();
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(migrationLocations)
                .load();
        if (existed && flyway.info().pending().length > 0) {
            backupBeforeMigration(dataSource, dataDir.resolve(BACKUP_DIR), clock);
        }
        flyway.migrate();
        long migratedMs = (System.nanoTime() - started) / 1_000_000;
        Database database = new Database(dataDir, dataSource, flyway);
        // After every migration, so Undo covers the tables and columns as they are now.
        ChangeLog.installTracking(database.jdbi);
        log.info("Database opened: migrations {} ms, change tracking {} ms",
                migratedMs, (System.nanoTime() - started) / 1_000_000 - migratedMs);
        return database;
    }

    public Jdbi jdbi() {
        return jdbi;
    }

    public Path dataDir() {
        return dataDir;
    }

    /** Lets go of the database file, when the app is closing. */
    @Override
    public void close() {
        dataSource.close();
    }

    /** The newest migration this version of the app knows: the schema it leaves a database at. */
    public int schemaVersion() {
        return Arrays.stream(flyway.info().all())
                .map(MigrationInfo::getVersion)
                .filter(version -> version != null)
                .mapToInt(version -> version.getMajor().intValueExact())
                .max()
                .orElse(0);
    }

    /**
     * Writes a complete, consistent copy of the database to {@code target}, which must not
     * exist yet. Safe while the app is in use.
     */
    public synchronized void snapshotTo(Path target) {
        // VACUUM INTO writes a consistent snapshot even with WAL pages outstanding.
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("VACUUM INTO ?")) {
            statement.setString(1, target.toAbsolutePath().toString());
            statement.execute();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not copy the database", e);
        }
    }

    /**
     * Looks inside a database file that is not the live one (a backup about to be restored).
     *
     * @return the newest migration applied to it
     * @throws NotABudgetDatabaseException when the file is damaged or is not one of ours
     */
    public static int schemaVersionOf(Path dbFile) {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        try (Connection connection = config.createConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
                Statement statement = connection.createStatement()) {
            try (ResultSet check = statement.executeQuery("PRAGMA integrity_check")) {
                if (!check.next() || !"ok".equalsIgnoreCase(check.getString(1))) {
                    throw new NotABudgetDatabaseException();
                }
            }
            try (ResultSet version = statement.executeQuery(
                    "SELECT MAX(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success = 1")) {
                if (!version.next() || version.getInt(1) < 1) {
                    throw new NotABudgetDatabaseException();
                }
                return version.getInt(1);
            }
        } catch (SQLException e) {
            throw new NotABudgetDatabaseException();
        }
    }

    /** The file is not a database this app made, or it is damaged. */
    public static final class NotABudgetDatabaseException extends RuntimeException {
        NotABudgetDatabaseException() {
            super("Not a Budget database");
        }
    }

    /**
     * Puts another database, and its attachments, in place of everything here (Restore).
     * The files are moved, not copied. An older database is brought up to this version's
     * schema afterwards. Callers must have backed up what is being replaced.
     *
     * @param newFiles the attachments that go with it, or {@code null} when it has none
     */
    public synchronized void replaceWith(Path newDatabase, Path newFiles) {
        dataSource.closeIdle();
        try {
            // The main file first, as in eraseEverything: if it cannot be removed, nothing has changed.
            for (String suffix : DB_FILE_SUFFIXES) {
                Files.deleteIfExists(dataDir.resolve(DB_FILE + suffix));
            }
            Files.move(newDatabase, dataDir.resolve(DB_FILE));
            deleteFolder(dataDir.resolve(FILES_DIR));
            if (newFiles != null) {
                Files.move(newFiles, dataDir.resolve(FILES_DIR));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not put the restored data in place", e);
        }
        flyway.migrate();
        ChangeLog.forgetUnfinished(jdbi);
        ChangeLog.installTracking(jdbi);
        log.info("Data restored from a backup at the user's request");
    }

    /**
     * Permanently erases everything the user has entered and leaves a fresh, empty app:
     * every table is dropped and rebuilt, and attachments and in-app backups are deleted.
     * Logs stay (they hold no financial data), as do backups saved to a folder elsewhere.
     *
     * <p>There is no undo, which is the point — callers must have confirmed with the user.
     */
    public synchronized void eraseEverything() {
        // Delete the database files outright and rebuild from the migrations, rather than
        // emptying tables: deleted rows otherwise linger in the file's free pages and in the
        // write-ahead log. The connections kept open between requests are closed first, so
        // nothing holds the file open here. The main file goes first — if it cannot be
        // deleted, nothing has changed yet.
        dataSource.closeIdle();
        try {
            for (String suffix : DB_FILE_SUFFIXES) {
                Files.deleteIfExists(dataDir.resolve(DB_FILE + suffix));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not erase the database", e);
        }
        flyway.migrate();
        ChangeLog.installTracking(jdbi);
        for (String folder : DATA_FOLDERS) {
            deleteFolder(dataDir.resolve(folder));
        }
        log.info("All data erased at the user's request");
    }

    private static void deleteFolder(Path folder) {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> contents = Files.walk(folder)) {
            // Deepest paths first, so every folder is empty by the time it is deleted.
            for (Path path : contents.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not erase " + folder.getFileName(), e);
        }
    }

    private static DataSource dataSource(Path dbFile) {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        // WAL lets the UI keep reading while a write is in flight.
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setBusyTimeout(5_000);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        return dataSource;
    }

    private static void backupBeforeMigration(DataSource dataSource, Path backupDir, Clock clock) {
        try {
            Files.createDirectories(backupDir);
            Path target = backupDir.resolve(PRE_MIGRATION_PREFIX + LocalDateTime.now(clock).format(STAMP) + ".db");
            // VACUUM INTO writes a consistent snapshot even with WAL pages outstanding.
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement("VACUUM INTO ?")) {
                statement.setString(1, target.toAbsolutePath().toString());
                statement.execute();
            }
            log.info("Backed up the database before upgrading it");
            pruneOldBackups(backupDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not back up the database before upgrading it", e);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not back up the database before upgrading it", e);
        }
    }

    private static void pruneOldBackups(Path backupDir) throws IOException {
        List<Path> newestFirst;
        try (Stream<Path> files = Files.list(backupDir)) {
            // The timestamp in the name sorts chronologically, so no need to read file times.
            newestFirst = files
                    .filter(file -> file.getFileName().toString().startsWith(PRE_MIGRATION_PREFIX))
                    .sorted(Comparator.comparing((Path file) -> file.getFileName().toString()).reversed())
                    .toList();
        }
        for (Path stale : newestFirst.subList(Math.min(PRE_MIGRATION_BACKUPS_KEPT, newestFirst.size()), newestFirst.size())) {
            Files.delete(stale);
        }
    }
}
