package app.budget.server.changes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Update;

/**
 * The change log behind Undo (spec §4.1, §6): the before and after of every row a request
 * touched, and the way back.
 *
 * <p>Rows are captured by database triggers, not by each repository, so nothing that
 * changes data can forget to be undoable. The triggers are rebuilt from the live schema
 * every time the database is opened, so they always list every column of every table.
 *
 * <p>Table and column names cannot be bound as parameters. Wherever one appears in SQL
 * here it was read from the database's own schema, never from a request; every value is
 * bound.
 */
public final class ChangeLog {

    /** How many changes can be undone, newest first. Older ones are forgotten. */
    static final int CHANGES_KEPT = 100;

    /**
     * Tables Undo leaves alone: the log itself, the migration history, and the app lock.
     * Undo must never bring back an old PIN, and its hashes are kept out of the log.
     */
    private static final Set<String> NOT_TRACKED =
            Set.of("change_groups", "change_log", "change_recording", "flyway_schema_history", "auth");

    private static final String TRIGGER_PREFIX = "track_";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Jdbi jdbi;
    private final Clock clock;

    public ChangeLog(Jdbi jdbi, Clock clock) {
        this.jdbi = jdbi;
        this.clock = clock;
    }

    /** One table as the triggers and Undo need to know it. */
    private record Table(String name, List<String> columns, List<String> keyColumns) {}

    /**
     * (Re)creates the triggers that feed the log, one set per table, from the schema as it is
     * now. Called whenever the database has just been migrated or rebuilt.
     */
    public static void installTracking(Jdbi jdbi) {
        jdbi.useTransaction(handle -> {
            List<String> stale = handle
                    .createQuery("SELECT name FROM sqlite_master WHERE type = 'trigger' AND name LIKE :prefix ESCAPE '\\'")
                    .bind("prefix", TRIGGER_PREFIX.replace("_", "\\_") + "%")
                    .mapTo(String.class)
                    .list();
            for (String trigger : stale) {
                handle.execute("DROP TRIGGER " + quoted(trigger));
            }
            for (Table table : tables(handle)) {
                String oldRow = rowAsJson(table, "OLD");
                String newRow = rowAsJson(table, "NEW");
                createTrigger(handle, table, "insert", "INSERT", "NULL", newRow);
                createTrigger(handle, table, "update", "UPDATE", oldRow, newRow);
                createTrigger(handle, table, "delete", "DELETE", oldRow, "NULL");
            }
        });
    }

    /** The tables Undo covers. Package-visible so a test can check none is left out. */
    static List<String> trackedTables(Jdbi jdbi) {
        return jdbi.withHandle(handle -> tables(handle).stream().map(Table::name).toList());
    }

    /** Starts recording a change. Everything written until {@link #finish} belongs to it. */
    public void begin() {
        jdbi.useTransaction(handle -> {
            long group = handle.createQuery("INSERT INTO change_groups (created_at) VALUES (:now) RETURNING id")
                    .bind("now", clock.instant().toString())
                    .mapTo(Long.class)
                    .one();
            handle.createUpdate("UPDATE change_recording SET group_id = :group WHERE id = 1").bind("group", group).execute();
        });
    }

    /**
     * Stops recording.
     *
     * @return true when the change touched anything, so there is something to undo
     */
    public boolean finish() {
        return jdbi.inTransaction(handle -> {
            Long group = handle.createQuery("SELECT group_id FROM change_recording WHERE id = 1")
                    .mapTo(Long.class)
                    .findOne()
                    .orElse(null);
            handle.execute("UPDATE change_recording SET group_id = NULL WHERE id = 1");
            if (group == null) {
                return false;
            }
            boolean changed = handle.createQuery("SELECT EXISTS (SELECT 1 FROM change_log WHERE group_id = :group)")
                    .bind("group", group)
                    .mapTo(Boolean.class)
                    .one();
            if (!changed) {
                // A request that was refused, or changed nothing, leaves nothing to undo.
                handle.createUpdate("DELETE FROM change_groups WHERE id = :group").bind("group", group).execute();
                return false;
            }
            handle.createUpdate("""
                            DELETE FROM change_groups
                            WHERE id NOT IN (SELECT id FROM change_groups ORDER BY id DESC LIMIT :kept)
                            """)
                    .bind("kept", CHANGES_KEPT)
                    .execute();
            return true;
        });
    }

    /** True when there is a change to undo. */
    public boolean canUndo() {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT EXISTS (SELECT 1 FROM change_groups)")
                .mapTo(Boolean.class)
                .one());
    }

    /**
     * Puts back everything the most recent change touched, then forgets that change, so the
     * next call undoes the one before it.
     *
     * @return false when there was nothing to undo
     */
    public boolean undoLatest() {
        return jdbi.inTransaction(handle -> {
            Long group = handle.createQuery("SELECT MAX(id) FROM change_groups").mapTo(Long.class).findOne().orElse(null);
            if (group == null) {
                return false;
            }
            // Rows are put back one at a time, so a child can briefly exist without its parent;
            // references are checked once, when the transaction commits.
            handle.execute("PRAGMA defer_foreign_keys = ON");
            List<Table> tables = tables(handle);
            List<LoggedRow> rows = handle
                    .createQuery("SELECT table_name, op, before, after FROM change_log WHERE group_id = :group ORDER BY id DESC")
                    .bind("group", group)
                    .map((rs, ctx) -> new LoggedRow(
                            rs.getString("table_name"), rs.getString("op"), rs.getString("before"), rs.getString("after")))
                    .list();
            for (LoggedRow row : rows) {
                // A table that no longer exists (dropped by a later migration) has nothing to put back.
                tables.stream()
                        .filter(table -> table.name().equals(row.table()))
                        .findFirst()
                        .ifPresent(table -> reverse(handle, table, row));
            }
            handle.createUpdate("DELETE FROM change_groups WHERE id = :group").bind("group", group).execute();
            return true;
        });
    }

    private record LoggedRow(String table, String op, String before, String after) {}

    private static void reverse(Handle handle, Table table, LoggedRow row) {
        switch (row.op()) {
            case "insert" -> delete(handle, table, parse(row.after()));
            case "delete" -> insert(handle, table, parse(row.before()));
            case "update" -> update(handle, table, parse(row.before()), parse(row.after()));
            default -> throw new IllegalStateException("Unknown change kind in the log: " + row.op());
        }
    }

    private static void insert(Handle handle, Table table, JsonNode row) {
        List<String> columns = present(table.columns(), row);
        String sql = "INSERT INTO " + quoted(table.name())
                + " (" + columns.stream().map(ChangeLog::quoted).collect(Collectors.joining(", ")) + ")"
                + " VALUES (" + columns.stream().map(column -> ":v_" + columns.indexOf(column)).collect(Collectors.joining(", ")) + ")";
        Update insert = handle.createUpdate(sql);
        for (int i = 0; i < columns.size(); i++) {
            insert.bind("v_" + i, value(row.get(columns.get(i))));
        }
        insert.execute();
    }

    private static void delete(Handle handle, Table table, JsonNode row) {
        List<String> key = present(table.keyColumns(), row);
        Update delete = handle.createUpdate("DELETE FROM " + quoted(table.name()) + " WHERE " + matching(key, "k_"));
        bindAll(delete, key, "k_", row);
        delete.execute();
    }

    /** Puts {@code before} back over the row that {@code after} identifies. */
    private static void update(Handle handle, Table table, JsonNode before, JsonNode after) {
        List<String> columns = present(table.columns(), before);
        List<String> key = present(table.keyColumns(), after);
        String assignments = columns.stream()
                .map(column -> quoted(column) + " = :v_" + columns.indexOf(column))
                .collect(Collectors.joining(", "));
        Update update = handle.createUpdate(
                "UPDATE " + quoted(table.name()) + " SET " + assignments + " WHERE " + matching(key, "k_"));
        bindAll(update, columns, "v_", before);
        bindAll(update, key, "k_", after);
        update.execute();
    }

    /** {@code "a" IS :k_0 AND "b" IS :k_1}: IS, not =, so a NULL key column still matches. */
    private static String matching(List<String> columns, String prefix) {
        return columns.stream()
                .map(column -> quoted(column) + " IS :" + prefix + columns.indexOf(column))
                .collect(Collectors.joining(" AND "));
    }

    private static void bindAll(Update statement, List<String> columns, String prefix, JsonNode row) {
        for (int i = 0; i < columns.size(); i++) {
            statement.bind(prefix + i, value(row.get(columns.get(i))));
        }
    }

    /** The columns that still exist and were logged: a migration may have added or removed some since. */
    private static List<String> present(List<String> columns, JsonNode row) {
        return columns.stream().filter(row::has).toList();
    }

    /** Every column here is INTEGER or TEXT (money is whole cents), so a logged value is a long, text or NULL. */
    private static Object value(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isIntegralNumber() ? (Object) node.longValue() : node.asText();
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A change log row could not be read", e);
        }
    }

    private static List<Table> tables(Handle handle) {
        List<String> names = handle
                .createQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' ORDER BY name")
                .mapTo(String.class)
                .list();
        List<Table> tables = new ArrayList<>();
        for (String name : names) {
            if (NOT_TRACKED.contains(name)) {
                continue;
            }
            List<String> columns = new ArrayList<>();
            List<String> key = new ArrayList<>();
            handle.createQuery("SELECT name, pk FROM pragma_table_info(:table) ORDER BY cid")
                    .bind("table", name)
                    .map((rs, ctx) -> {
                        columns.add(rs.getString("name"));
                        if (rs.getInt("pk") > 0) {
                            key.add(rs.getString("name"));
                        }
                        return null;
                    })
                    .list();
            // A table with no primary key is identified by the whole row.
            tables.add(new Table(name, List.copyOf(columns), key.isEmpty() ? List.copyOf(columns) : List.copyOf(key)));
        }
        return tables;
    }

    private static void createTrigger(Handle handle, Table table, String op, String event, String before, String after) {
        handle.execute("CREATE TRIGGER " + quoted(TRIGGER_PREFIX + table.name() + "_" + op)
                + " AFTER " + event + " ON " + quoted(table.name())
                + " WHEN (SELECT group_id FROM change_recording WHERE id = 1) IS NOT NULL"
                + " BEGIN INSERT INTO change_log (group_id, table_name, op, before, after)"
                + " VALUES ((SELECT group_id FROM change_recording WHERE id = 1), " + literal(table.name()) + ", "
                + literal(op) + ", " + before + ", " + after + "); END");
    }

    /** {@code json_object('id', NEW."id", 'name', NEW."name", …)} */
    private static String rowAsJson(Table table, String row) {
        return table.columns().stream()
                .map(column -> literal(column) + ", " + row + "." + quoted(column))
                .collect(Collectors.joining(", ", "json_object(", ")"));
    }

    private static String quoted(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String literal(String text) {
        return "'" + text.replace("'", "''") + "'";
    }
}
