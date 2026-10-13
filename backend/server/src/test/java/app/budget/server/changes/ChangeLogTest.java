package app.budget.server.changes;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.db.Database;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The change log against a temporary database, without the HTTP layer. */
class ChangeLogTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dataDir;

    Jdbi jdbi;
    ChangeLog changes;

    Database database;

    @BeforeEach
    void open() {
        database = Database.open(dataDir);
        jdbi = database.jdbi();
        changes = new ChangeLog(jdbi, CLOCK);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private void addTag(String name) {
        jdbi.useHandle(handle -> handle
                .createUpdate("INSERT INTO tags (name, color, created_at) VALUES (:name, '#475569', '2026-10-01T12:00:00Z')")
                .bind("name", name)
                .execute());
    }

    private List<String> tagNames() {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT name FROM tags ORDER BY id").mapTo(String.class).list());
    }

    @Test
    void everyTableOfTheUsersDataIsCovered() {
        List<String> all = jdbi.withHandle(handle -> handle
                .createQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite%' ORDER BY name")
                .mapTo(String.class)
                .list());

        List<String> tracked = ChangeLog.trackedTables(jdbi);

        assertThat(tracked).contains("accounts", "balance_entries", "items", "item_versions", "occurrence_overrides",
                "entries", "entry_tags", "categories", "tags", "settings", "income_sources", "paycheck_overrides");
        // Whatever is not tracked is the log itself, the migration history, or the app lock
        // (Undo must never bring back an old PIN): nothing of the user's money.
        assertThat(all).filteredOn(table -> !tracked.contains(table))
                .containsExactlyInAnyOrder("change_groups", "change_log", "change_recording", "flyway_schema_history", "auth");
    }

    @Test
    void everyTrackedTableHasItsThreeTriggers() {
        long triggers = jdbi.withHandle(handle -> handle
                .createQuery("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'track\\_%' ESCAPE '\\'")
                .mapTo(Long.class)
                .one());

        assertThat(triggers).isEqualTo(ChangeLog.trackedTables(jdbi).size() * 3L);
    }

    @Test
    void installingAgainReplacesTheTriggersInsteadOfDoublingThem() {
        ChangeLog.installTracking(jdbi);
        changes.begin();
        addTag("Reimbursable");
        changes.finish();

        long logged = jdbi.withHandle(handle -> handle.createQuery("SELECT COUNT(*) FROM change_log").mapTo(Long.class).one());

        assertThat(logged).isEqualTo(1);
    }

    @Test
    void aTableThatGainsAColumnHasItsTriggersRebuiltToCoverIt() {
        // What a migration does: the triggers written before it know nothing of the new column.
        jdbi.useHandle(handle -> handle.execute("ALTER TABLE tags ADD COLUMN emoji TEXT"));
        ChangeLog.installTracking(jdbi);

        changes.begin();
        jdbi.useHandle(handle -> handle.execute(
                "INSERT INTO tags (name, color, created_at, emoji) VALUES ('Trips', '#0f766e', 'now', 'sun')"));
        changes.finish();

        String after = jdbi.withHandle(handle -> handle.createQuery("SELECT after FROM change_log").mapTo(String.class).one());
        assertThat(after).contains("\"emoji\":\"sun\"");
    }

    @Test
    void aTriggerLeftOverFromATableThatIsGoneIsRemoved() {
        jdbi.useHandle(handle -> {
            handle.execute("CREATE TABLE scratch (id INTEGER PRIMARY KEY)");
        });
        ChangeLog.installTracking(jdbi);
        // Dropping a table takes its triggers with it; one named for it on another table would linger.
        jdbi.useHandle(handle -> {
            handle.execute("DROP TABLE scratch");
            handle.execute("CREATE TRIGGER \"track_scratch_insert\" AFTER INSERT ON tags BEGIN SELECT 1; END");
        });

        ChangeLog.installTracking(jdbi);

        long triggers = jdbi.withHandle(handle -> handle
                .createQuery("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'track\\_%' ESCAPE '\\'")
                .mapTo(Long.class)
                .one());
        assertThat(triggers).isEqualTo(ChangeLog.trackedTables(jdbi).size() * 3L);
    }

    @Test
    void nothingIsLoggedOutsideAChange() {
        addTag("Reimbursable");

        assertThat(changes.canUndo()).isFalse();
        assertThat(changes.undoLatest()).isFalse();
        assertThat(tagNames()).containsExactly("Reimbursable");
    }

    @Test
    void aChangeThatTouchedNothingIsForgotten() {
        changes.begin();

        assertThat(changes.finish()).isFalse();
        assertThat(changes.canUndo()).isFalse();
    }

    @Test
    void onlyTheNewestChangesAreKept() {
        for (int i = 0; i <= ChangeLog.CHANGES_KEPT; i++) {
            changes.begin();
            addTag("Tag " + i);
            changes.finish();
        }

        int undone = 0;
        while (changes.undoLatest()) {
            undone++;
        }

        assertThat(undone).isEqualTo(ChangeLog.CHANGES_KEPT);
        // The oldest change fell off the end of the log, so it stays.
        assertThat(tagNames()).containsExactly("Tag 0");
    }

    @Test
    void anUndoneValueThatWasEmptyGoesBackToEmpty() {
        jdbi.useHandle(handle -> handle.execute(
                "INSERT INTO accounts (name, type, color, archived, sort_order, created_at) "
                        + "VALUES ('Everyday Checking', 'checking', '#0f766e', 0, 1, '2026-10-01T12:00:00Z')"));
        changes.begin();
        jdbi.useHandle(handle -> handle.execute("UPDATE accounts SET institution = 'Maple Credit Union'"));
        changes.finish();

        changes.undoLatest();

        String institution = jdbi.withHandle(handle ->
                handle.createQuery("SELECT institution FROM accounts").mapTo(String.class).findOne().orElse(null));
        assertThat(institution).isNull();
    }
}
