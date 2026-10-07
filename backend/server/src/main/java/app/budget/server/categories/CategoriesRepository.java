package app.budget.server.categories;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;

/** Reads and writes {@code categories}. */
public final class CategoriesRepository {

    private static final String SELECT = "SELECT id, name, color, parent_id FROM categories ";

    private final Jdbi jdbi;
    private final Clock clock;

    public CategoriesRepository(Jdbi jdbi, Clock clock) {
        this.jdbi = jdbi;
        this.clock = clock;
    }

    /** @param parentId {@code null} for a top-level category */
    public record Category(long id, String name, String color, Long parentId) {}

    /** Every category: each top-level one followed by its sub-categories. */
    public List<Category> all() {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        SELECT c.id, c.name, c.color, c.parent_id
                        FROM categories c LEFT JOIN categories p ON p.id = c.parent_id
                        ORDER BY COALESCE(p.sort_order, c.sort_order), COALESCE(p.id, c.id),
                                 c.parent_id IS NOT NULL, c.sort_order, c.id
                        """)
                .map((rs, ctx) -> category(rs))
                .list());
    }

    public Optional<Category> find(long id) {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT + "WHERE id = :id")
                .bind("id", id)
                .map((rs, ctx) -> category(rs))
                .findOne());
    }

    /** True when another category beside {@code exceptId} already has this name under the same parent. */
    public boolean nameTaken(String name, Long parentId, Long exceptId) {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        SELECT EXISTS (
                            SELECT 1 FROM categories
                            WHERE name = :name COLLATE NOCASE
                              AND COALESCE(parent_id, 0) = COALESCE(:parentId, 0)
                              AND id <> COALESCE(:exceptId, 0))
                        """)
                .bind("name", name)
                .bind("parentId", parentId)
                .bind("exceptId", exceptId)
                .mapTo(Boolean.class)
                .one());
    }

    /** Adds a category at the end of its level and returns its id. */
    public long create(String name, String color, Long parentId) {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        INSERT INTO categories (name, color, parent_id, sort_order, created_at)
                        VALUES (:name, :color, :parentId, (SELECT COALESCE(MAX(sort_order), 0) + 1 FROM categories), :now)
                        RETURNING id
                        """)
                .bind("name", name)
                .bind("color", color)
                .bind("parentId", parentId)
                .bind("now", clock.instant().toString())
                .mapTo(Long.class)
                .one());
    }

    public void update(long id, String name, String color, Long parentId) {
        jdbi.useHandle(handle -> handle
                .createUpdate("UPDATE categories SET name = :name, color = :color, parent_id = :parentId WHERE id = :id")
                .bind("id", id)
                .bind("name", name)
                .bind("color", color)
                .bind("parentId", parentId)
                .execute());
    }

    public boolean hasChildren(long id) {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT EXISTS (SELECT 1 FROM categories WHERE parent_id = :id)")
                .bind("id", id)
                .mapTo(Boolean.class)
                .one());
    }

    /** True when a recurring item (in any of its versions) or a one-off entry is filed under it. */
    public boolean isUsed(long id) {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        SELECT EXISTS (SELECT 1 FROM item_versions WHERE category_id = :id)
                            OR EXISTS (SELECT 1 FROM entries WHERE category_id = :id)
                        """)
                .bind("id", id)
                .mapTo(Boolean.class)
                .one());
    }

    public void delete(long id) {
        jdbi.useHandle(handle -> handle.createUpdate("DELETE FROM categories WHERE id = :id").bind("id", id).execute());
    }

    private static Category category(ResultSet rs) throws SQLException {
        long parent = rs.getLong("parent_id");
        Long parentId = rs.wasNull() ? null : parent;
        return new Category(rs.getLong("id"), rs.getString("name"), rs.getString("color"), parentId);
    }
}
