package app.budget.server.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {

    private static final String MIGRATIONS = "classpath:db/migration";
    private static final String UPGRADE = "classpath:db/testmigration";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dataDir;

    @Test
    void firstLaunchCreatesTheDatabaseWithWalAndForeignKeys() {
        Database database = Database.open(dataDir);

        assertThat(dataDir.resolve(Database.DB_FILE)).exists();
        assertThat(pragma(database, "journal_mode")).isEqualToIgnoringCase("wal");
        assertThat(pragma(database, "foreign_keys")).isEqualTo("1");
        assertThat(tableExists(database, "settings")).isTrue();
    }

    @Test
    void firstLaunchTakesNoBackupBecauseThereIsNothingToLose() {
        Database.open(dataDir);

        assertThat(dataDir.resolve(Database.BACKUP_DIR)).doesNotExist();
    }

    @Test
    void reopeningAnUpToDateDatabaseTakesNoBackup() {
        Database.open(dataDir);
        Database.open(dataDir);

        assertThat(dataDir.resolve(Database.BACKUP_DIR)).doesNotExist();
    }

    @Test
    void anUpgradeBacksUpTheDataBeforeMigrating() throws IOException {
        Database before = Database.open(dataDir, CLOCK, MIGRATIONS);
        before.jdbi().useHandle(handle -> handle
                .createUpdate("INSERT INTO settings (key, value, updated_at) VALUES (:key, :value, :now)")
                .bind("key", "theme")
                .bind("value", "dark")
                .bind("now", "2026-10-01T12:00:00Z")
                .execute());

        Database after = Database.open(dataDir, CLOCK, MIGRATIONS, UPGRADE);

        List<Path> backups = backups();
        assertThat(backups).hasSize(1);
        assertThat(tableExists(after, "simulated_upgrade")).isTrue();
        // The backup is the database as it was: the user's data, without the new table.
        Path restored = Files.createDirectory(dataDir.resolve("restored"));
        Files.copy(backups.get(0), restored.resolve(Database.DB_FILE));
        Database backup = Database.open(restored, CLOCK, MIGRATIONS);
        assertThat(tableExists(backup, "simulated_upgrade")).isFalse();
        String restoredTheme = backup.jdbi().withHandle(handle -> handle
                .createQuery("SELECT value FROM settings WHERE key = :key")
                .bind("key", "theme")
                .mapTo(String.class)
                .one());
        assertThat(restoredTheme).isEqualTo("dark");
    }

    @Test
    void keepsOnlyTheMostRecentUpgradeBackups() throws IOException {
        Database.open(dataDir, CLOCK, MIGRATIONS);
        Path backupDir = Files.createDirectory(dataDir.resolve(Database.BACKUP_DIR));
        for (int day = 1; day <= Database.PRE_MIGRATION_BACKUPS_KEPT + 2; day++) {
            Files.createFile(backupDir.resolve(Database.PRE_MIGRATION_PREFIX + "2026090" + day + "-000000000.db"));
        }

        Database.open(dataDir, Clock.offset(CLOCK, Duration.ofDays(1)), MIGRATIONS, UPGRADE);

        assertThat(backups())
                .hasSize(Database.PRE_MIGRATION_BACKUPS_KEPT)
                .anyMatch(file -> file.getFileName().toString().contains("20261002"))
                .noneMatch(file -> file.getFileName().toString().contains("20260901"));
    }

    @Test
    void erasingRemovesTheDataFromTheFileItselfNotJustFromView() throws IOException {
        Database database = Database.open(dataDir);
        String marker = "MARKER-" + "x".repeat(40);
        database.jdbi().useHandle(handle -> handle
                .createUpdate("INSERT INTO settings (key, value, updated_at) VALUES (:key, :value, :now)")
                .bind("key", "note")
                .bind("value", marker)
                .bind("now", "2026-10-01T12:00:00Z")
                .execute());

        database.eraseEverything();

        assertThat(tableExists(database, "settings")).isTrue();
        Integer rows = database.jdbi().withHandle(handle ->
                handle.createQuery("SELECT count(*) FROM settings").mapTo(Integer.class).one());
        assertThat(rows).isZero();
        // Scan the raw files: a dropped-but-not-vacuumed row would still be readable here.
        try (Stream<Path> files = Files.list(dataDir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertThat(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1))
                        .as(file.getFileName().toString())
                        .doesNotContain(marker);
            }
        }
    }

    private List<Path> backups() throws IOException {
        try (Stream<Path> files = Files.list(dataDir.resolve(Database.BACKUP_DIR))) {
            return files.sorted().toList();
        }
    }

    @Test
    void repairsEarlierVersionsOfAnItemThatWasTurnedIntoATransfer() throws IOException {
        Database database = Database.open(dataDir);
        // A bucket move inside savings (2) that was changed into a transfer from checking (1) to
        // savings on Oct 9, as the app used to save it: the earlier version kept the old accounts.
        database.jdbi().useHandle(handle -> {
            for (String name : List.of("Checking", "Savings")) {
                handle.createUpdate("INSERT INTO accounts (name, type, color, created_at) VALUES (:name, 'savings', '#0f766e', 'now')")
                        .bind("name", name)
                        .execute();
            }
            handle.execute("INSERT INTO buckets (account_id, name, color, opened_on, created_at) VALUES (2, 'Fund', '#0f766e', '2026-01-01', 'now')");
            handle.execute("INSERT INTO items (id, name, kind, archived, created_at) VALUES (1, 'Fund', 'transfer', 0, 'now'), (2, 'Rent', 'bill', 0, 'now')");
            for (int rule = 1; rule <= 3; rule++) {
                handle.createUpdate("""
                                INSERT INTO schedule_rules (id, frequency, interval, day_1, start_date, weekend_rule)
                                VALUES (:id, 'monthly', 1, 1, '2026-01-01', 'none')
                                """)
                        .bind("id", rule)
                        .execute();
            }
            handle.execute("""
                    INSERT INTO item_versions
                        (item_id, effective_from, amount, amount_mode, is_estimate, pay_from_account_id, pay_to_account_id,
                         autopay, placement, schedule_rule_id, days_after_payday, bucket_id)
                    VALUES (1, '2026-01-01', 40000, 'per_occurrence', 0, 2, NULL, 1, 'due_period', 1, 0, 1),
                           (1, '2026-10-09', 45000, 'per_occurrence', 0, 1, 2, 1, 'due_period', 2, 0, 1),
                           (2, '2026-01-01', 90000, 'per_occurrence', 0, 1, NULL, 0, 'due_period', 3, 0, NULL)
                    """);
        });

        // The migration has already run on this database, on nothing; running its statement again is the repair.
        String repair = new String(
                getClass().getResourceAsStream("/db/migration/V8__repair_versions_of_items_that_changed_kind.sql").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        database.jdbi().useHandle(handle -> handle.createScript(repair).execute());

        List<String> versions = database.jdbi().withHandle(handle -> handle
                .createQuery("""
                        SELECT item_id || ' ' || effective_from || ' ' || amount || ' from ' || pay_from_account_id
                               || ' to ' || coalesce(pay_to_account_id, 'nowhere')
                        FROM item_versions ORDER BY item_id, effective_from
                        """)
                .mapTo(String.class)
                .list());
        assertThat(versions).containsExactly(
                // The earlier version keeps its date and amount, and now goes where the item goes.
                "1 2026-01-01 40000 from 1 to 2",
                "1 2026-10-09 45000 from 1 to 2",
                // A bill names nowhere by its nature and is left alone.
                "2 2026-01-01 90000 from 1 to nowhere");
    }

    private static String pragma(Database database, String name) {
        // PRAGMA names cannot be bound as parameters; these are fixed strings from this test only.
        return database.jdbi().withHandle(handle ->
                handle.createQuery("PRAGMA " + name).mapTo(String.class).one());
    }

    private static boolean tableExists(Database database, String table) {
        return database.jdbi().withHandle(handle -> handle
                .createQuery("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = :name")
                .bind("name", table)
                .mapTo(Integer.class)
                .one()) == 1;
    }
}
