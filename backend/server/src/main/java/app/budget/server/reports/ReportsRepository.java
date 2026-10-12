package app.budget.server.reports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;

/**
 * Reads and writes {@code saved_reports}. What a report asks for is kept as JSON in the
 * shape the API sends it, and is checked again every time it is read back: a definition
 * written by an older version is held to today's rules like any other.
 */
public final class ReportsRepository {

    private static final String SELECT = "SELECT id, name, definition FROM saved_reports ";

    private final Jdbi jdbi;
    private final Clock clock;
    private final ObjectMapper json;

    /** @param json the API's own mapper, so a stored definition reads exactly as a request body does */
    public ReportsRepository(Jdbi jdbi, Clock clock, ObjectMapper json) {
        this.jdbi = jdbi;
        this.clock = clock;
        this.json = json;
    }

    public record SavedReport(long id, String name, ReportDefinition definition) {}

    /** Every saved report, by name. */
    public List<SavedReport> all() {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT + "ORDER BY name COLLATE NOCASE")
                .map((rs, ctx) -> saved(rs))
                .list());
    }

    public Optional<SavedReport> find(long id) {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT + "WHERE id = :id")
                .bind("id", id)
                .map((rs, ctx) -> saved(rs))
                .findOne());
    }

    /** True when another report beside {@code exceptId} already has this name. */
    public boolean nameTaken(String name, Long exceptId) {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        SELECT EXISTS (
                            SELECT 1 FROM saved_reports WHERE name = :name COLLATE NOCASE AND id <> COALESCE(:exceptId, 0))
                        """)
                .bind("name", name)
                .bind("exceptId", exceptId)
                .mapTo(Boolean.class)
                .one());
    }

    public long create(String name, ReportDefinition definition) {
        return jdbi.withHandle(handle -> handle
                .createQuery("INSERT INTO saved_reports (name, definition, created_at) VALUES (:name, :definition, :now) RETURNING id")
                .bind("name", name)
                .bind("definition", write(definition))
                .bind("now", clock.instant().toString())
                .mapTo(Long.class)
                .one());
    }

    /** @return false when there is no such report */
    public boolean update(long id, String name, ReportDefinition definition) {
        return jdbi.withHandle(handle -> handle
                .createUpdate("UPDATE saved_reports SET name = :name, definition = :definition WHERE id = :id")
                .bind("name", name)
                .bind("definition", write(definition))
                .bind("id", id)
                .execute()) > 0;
    }

    public boolean delete(long id) {
        return jdbi.withHandle(handle -> handle.createUpdate("DELETE FROM saved_reports WHERE id = :id").bind("id", id).execute()) > 0;
    }

    private String write(ReportDefinition definition) {
        try {
            return json.writeValueAsString(definition);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A report definition could not be written", e);
        }
    }

    private SavedReport saved(ResultSet rs) throws SQLException {
        try {
            ReportDefinition definition = ReportDefinition.of(json.readValue(rs.getString("definition"), ReportDefinition.Input.class));
            return new SavedReport(rs.getLong("id"), rs.getString("name"), definition);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A saved report could not be read", e);
        }
    }
}
