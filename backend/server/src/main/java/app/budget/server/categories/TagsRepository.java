package app.budget.server.categories;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;

/** Reads and writes {@code tags}. */
public final class TagsRepository {

    private final Jdbi jdbi;
    private final Clock clock;

    public TagsRepository(Jdbi jdbi, Clock clock) {
        this.jdbi = jdbi;
        this.clock = clock;
    }

    public record Tag(long id, String name, String color) {}

    /** Every tag, by name. */
    public List<Tag> all() {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT id, name, color FROM tags ORDER BY name COLLATE NOCASE")
                .map((rs, ctx) -> new Tag(rs.getLong("id"), rs.getString("name"), rs.getString("color")))
                .list());
    }

    public Optional<Tag> find(long id) {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT id, name, color FROM tags WHERE id = :id")
                .bind("id", id)
                .map((rs, ctx) -> new Tag(rs.getLong("id"), rs.getString("name"), rs.getString("color")))
                .findOne());
    }

    /** True when every one of {@code ids} is a tag that exists. */
    public boolean allExist(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return true;
        }
        Set<Long> wanted = Set.copyOf(ids);
        int found = jdbi.withHandle(handle -> handle
                .createQuery("SELECT COUNT(*) FROM tags WHERE id IN (<ids>)")
                .bindList("ids", wanted)
                .mapTo(Integer.class)
                .one());
        return found == wanted.size();
    }

    /** True when another tag beside {@code exceptId} already has this name. */
    public boolean nameTaken(String name, Long exceptId) {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        SELECT EXISTS (
                            SELECT 1 FROM tags WHERE name = :name COLLATE NOCASE AND id <> COALESCE(:exceptId, 0))
                        """)
                .bind("name", name)
                .bind("exceptId", exceptId)
                .mapTo(Boolean.class)
                .one());
    }

    public long create(String name, String color) {
        return jdbi.withHandle(handle -> handle
                .createQuery("INSERT INTO tags (name, color, created_at) VALUES (:name, :color, :now) RETURNING id")
                .bind("name", name)
                .bind("color", color)
                .bind("now", clock.instant().toString())
                .mapTo(Long.class)
                .one());
    }

    public void update(long id, String name, String color) {
        jdbi.useHandle(handle -> handle
                .createUpdate("UPDATE tags SET name = :name, color = :color WHERE id = :id")
                .bind("id", id)
                .bind("name", name)
                .bind("color", color)
                .execute());
    }

    /** True when a recurring item or a one-off entry carries this tag. */
    public boolean isUsed(long id) {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        SELECT EXISTS (SELECT 1 FROM item_tags WHERE tag_id = :id)
                            OR EXISTS (SELECT 1 FROM entry_tags WHERE tag_id = :id)
                        """)
                .bind("id", id)
                .mapTo(Boolean.class)
                .one());
    }

    public void delete(long id) {
        jdbi.useHandle(handle -> handle.createUpdate("DELETE FROM tags WHERE id = :id").bind("id", id).execute());
    }
}
