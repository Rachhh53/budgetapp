package app.budget.server.assets;

import app.budget.server.api.Inputs;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jdbi.v3.core.Jdbi;

/** Reads and writes {@code assets} (homes, vehicles, anything else owned) and the values entered for them. */
public final class AssetsRepository {

    private static final String SELECT =
            "SELECT id, name, kind, color, appreciation_bp, depreciation_bp, linked_loan_account_id, archived FROM assets ";

    private final Jdbi jdbi;
    private final Clock clock;

    public AssetsRepository(Jdbi jdbi, Clock clock) {
        this.jdbi = jdbi;
        this.clock = clock;
    }

    public enum Kind { HOME, VEHICLE, OTHER }

    /**
     * An asset as stored.
     *
     * @param appreciationBp      the yearly rate it is assumed to change by: above zero it grows, below zero it
     *                            loses value; {@code null} to hold its value. Kept in two columns, a gain in
     *                            {@code appreciation_bp} and a loss in {@code depreciation_bp} (see migration V20)
     * @param linkedLoanAccountId the loan against it, for equity; {@code null} when it is owned outright
     */
    public record Asset(
            long id, String name, Kind kind, String color, Integer appreciationBp, Long linkedLoanAccountId, boolean archived) {}

    /** What the user can set on an asset. */
    public record Fields(String name, Kind kind, String color, Integer appreciationBp, Long linkedLoanAccountId, boolean archived) {}

    /** A value the user entered for one day. Amounts are whole cents. */
    public record Value(long id, LocalDate date, long valueCents) {}

    /** Every asset, in the order the user added them. */
    public List<Asset> all() {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT + "ORDER BY sort_order, id")
                .map((rs, ctx) -> asset(rs))
                .list());
    }

    public Optional<Asset> find(long id) {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT + "WHERE id = :id")
                .bind("id", id)
                .map((rs, ctx) -> asset(rs))
                .findOne());
    }

    /** Adds an asset at the end of the list and returns its id. */
    public long create(Fields fields) {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        INSERT INTO assets (name, kind, color, appreciation_bp, depreciation_bp, linked_loan_account_id,
                                            archived, sort_order, created_at)
                        VALUES (:name, :kind, :color, :appreciationBp, :depreciationBp, :loanId, :archived,
                                (SELECT COALESCE(MAX(sort_order), 0) + 1 FROM assets), :now)
                        RETURNING id
                        """)
                .bind("name", fields.name())
                .bind("kind", Inputs.code(fields.kind()))
                .bind("color", fields.color())
                .bind("appreciationBp", gain(fields.appreciationBp()))
                .bind("depreciationBp", loss(fields.appreciationBp()))
                .bind("loanId", fields.linkedLoanAccountId())
                .bind("archived", fields.archived())
                .bind("now", clock.instant().toString())
                .mapTo(Long.class)
                .one());
    }

    /** @return false when there is no such asset */
    public boolean update(long id, Fields fields) {
        return jdbi.withHandle(handle -> handle
                .createUpdate("""
                        UPDATE assets
                        SET name = :name, kind = :kind, color = :color, appreciation_bp = :appreciationBp,
                            depreciation_bp = :depreciationBp, linked_loan_account_id = :loanId, archived = :archived
                        WHERE id = :id
                        """)
                .bind("id", id)
                .bind("name", fields.name())
                .bind("kind", Inputs.code(fields.kind()))
                .bind("color", fields.color())
                .bind("appreciationBp", gain(fields.appreciationBp()))
                .bind("depreciationBp", loss(fields.appreciationBp()))
                .bind("loanId", fields.linkedLoanAccountId())
                .bind("archived", fields.archived())
                .execute()) > 0;
    }

    /** Deletes the asset and, with it, the values entered for it. */
    public boolean delete(long id) {
        return jdbi.withHandle(handle -> handle
                .createUpdate("DELETE FROM assets WHERE id = :id")
                .bind("id", id)
                .execute()) > 0;
    }

    /** Values entered for an asset, newest first. */
    public List<Value> values(long assetId) {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT id, date, value FROM asset_values WHERE asset_id = :assetId ORDER BY date DESC")
                .bind("assetId", assetId)
                .map((rs, ctx) -> new Value(rs.getLong("id"), LocalDate.parse(rs.getString("date")), rs.getLong("value")))
                .list());
    }

    /** Every value entered, by asset, newest first. */
    public Map<Long, List<Value>> allValues() {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT id, asset_id, date, value FROM asset_values ORDER BY date DESC")
                .map((rs, ctx) -> Map.entry(
                        rs.getLong("asset_id"),
                        new Value(rs.getLong("id"), LocalDate.parse(rs.getString("date")), rs.getLong("value"))))
                .stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList()))));
    }

    /** Records the value for a day. Entering the same day again replaces what was there. */
    public void saveValue(long assetId, LocalDate date, long valueCents) {
        jdbi.useHandle(handle -> handle
                .createUpdate("""
                        INSERT INTO asset_values (asset_id, date, value)
                        VALUES (:assetId, :date, :value)
                        ON CONFLICT (asset_id, date) DO UPDATE SET value = excluded.value
                        """)
                .bind("assetId", assetId)
                .bind("date", date.toString())
                .bind("value", valueCents)
                .execute());
    }

    public boolean deleteValue(long assetId, long valueId) {
        return jdbi.withHandle(handle -> handle
                .createUpdate("DELETE FROM asset_values WHERE id = :valueId AND asset_id = :assetId")
                .bind("valueId", valueId)
                .bind("assetId", assetId)
                .execute()) > 0;
    }

    /** The part of a yearly rate kept in {@code appreciation_bp}: zero or a gain. */
    private static Integer gain(Integer rateBp) {
        return rateBp == null || rateBp < 0 ? null : rateBp;
    }

    /** The part kept in {@code depreciation_bp}: a loss, as a positive number. */
    private static Integer loss(Integer rateBp) {
        return rateBp == null || rateBp >= 0 ? null : -rateBp;
    }

    private static Asset asset(ResultSet rs) throws SQLException {
        int gain = rs.getInt("appreciation_bp");
        Integer appreciationBp = rs.wasNull() ? null : gain;
        int loss = rs.getInt("depreciation_bp");
        if (!rs.wasNull()) {
            appreciationBp = -loss;
        }
        long loan = rs.getLong("linked_loan_account_id");
        Long loanId = rs.wasNull() ? null : loan;
        return new Asset(
                rs.getLong("id"),
                rs.getString("name"),
                Kind.valueOf(rs.getString("kind").toUpperCase(java.util.Locale.ROOT)),
                rs.getString("color"),
                appreciationBp,
                loanId,
                rs.getBoolean("archived"));
    }
}
