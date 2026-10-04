package app.budget.server.db;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
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
public final class Database {

    public static final String DB_FILE = "budget.db";
    public static final String BACKUP_DIR = "backups";
    static final String PRE_MIGRATION_PREFIX = "pre-migration-";
    static final int PRE_MIGRATION_BACKUPS_KEPT = 5;
    private static final String DEFAULT_MIGRATIONS = "classpath:db/migration";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");
    private static final Logger log = LoggerFactory.getLogger(Database.class);

    private final Jdbi jdbi;

    private Database(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public static Database open(Path dataDir) {
        return open(dataDir, Clock.systemDefaultZone(), DEFAULT_MIGRATIONS);
    }

    /** Test seam: lets a test add extra migration locations to simulate an upgrade. */
    static Database open(Path dataDir, Clock clock, String... migrationLocations) {
        Path dbFile = dataDir.resolve(DB_FILE);
        boolean existed = Files.exists(dbFile);
        DataSource dataSource = dataSource(dbFile);

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(migrationLocations)
                .load();
        if (existed && flyway.info().pending().length > 0) {
            backupBeforeMigration(dataSource, dataDir.resolve(BACKUP_DIR), clock);
        }
        flyway.migrate();
        return new Database(Jdbi.create(dataSource));
    }

    public Jdbi jdbi() {
        return jdbi;
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
