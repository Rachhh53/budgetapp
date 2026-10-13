package app.budget.server.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** Connections kept open between uses, against a temporary database file. */
class KeptOpenDataSourceTest {

    @TempDir
    Path dir;

    Path file;
    KeptOpenDataSource pool;

    @BeforeEach
    void open() {
        file = dir.resolve("kept-open.db");
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        pool = new KeptOpenDataSource(source);
    }

    @AfterEach
    void close() {
        pool.close();
    }

    /** SQLite numbers its connections' temporary tables apart, so a temp table tells two connections from one. */
    private static void mark(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE IF NOT EXISTS mark (id INTEGER)");
        }
    }

    private static boolean marked(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet found = statement.executeQuery("SELECT count(*) FROM sqlite_temp_master WHERE name = 'mark'")) {
            return found.next() && found.getInt(1) == 1;
        }
    }

    @Test
    void aConnectionGivenBackIsTheNextOneHandedOut() throws SQLException {
        try (Connection first = pool.getConnection()) {
            mark(first);
        }

        try (Connection second = pool.getConnection()) {
            assertThat(marked(second)).isTrue();
        }
    }

    @Test
    void twoCallersAtOnceGetAConnectionEach() throws SQLException {
        try (Connection first = pool.getConnection();
                Connection second = pool.getConnection()) {
            mark(first);

            assertThat(marked(second)).isFalse();
        }
    }

    @Test
    void aConnectionCannotBeUsedOnceItIsGivenBack() throws SQLException {
        Connection connection = pool.getConnection();
        connection.close();

        assertThat(connection.isClosed()).isTrue();
        assertThatThrownBy(connection::createStatement).isInstanceOf(SQLException.class);
        // Closing twice is allowed, and must not hand the same connection to two callers.
        connection.close();
        try (Connection first = pool.getConnection();
                Connection second = pool.getConnection()) {
            mark(first);
            assertThat(marked(second)).isFalse();
        }
    }

    @Test
    void workLeftHalfDoneIsNotPassedToTheNextCaller() throws SQLException {
        try (Connection setUp = pool.getConnection(); Statement statement = setUp.createStatement()) {
            statement.execute("CREATE TABLE notes (body TEXT)");
        }
        try (Connection unfinished = pool.getConnection(); Statement statement = unfinished.createStatement()) {
            unfinished.setAutoCommit(false);
            statement.execute("INSERT INTO notes (body) VALUES ('never committed')");
        }

        try (Connection next = pool.getConnection();
                Statement statement = next.createStatement();
                ResultSet count = statement.executeQuery("SELECT count(*) FROM notes")) {
            assertThat(next.getAutoCommit()).isTrue();
            assertThat(count.next()).isTrue();
            assertThat(count.getInt(1)).isZero();
        }
    }

    @Test
    void closingTheIdleOnesLetsGoOfTheFile() throws Exception {
        try (Connection connection = pool.getConnection()) {
            mark(connection);
        }

        pool.closeIdle();

        // Windows refuses to delete a file that is still open, which is what erasing all data has to do.
        Files.delete(file);
        try (Connection fresh = pool.getConnection()) {
            assertThat(marked(fresh)).isFalse();
        }
    }
}
