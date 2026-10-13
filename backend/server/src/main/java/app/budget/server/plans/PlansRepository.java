package app.budget.server.plans;

import app.budget.engine.plans.PlanSuggestions.Basis;
import app.budget.server.api.Inputs;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;

/** Reads and writes yearly plans and the amounts typed into them (spec §4.10). */
public final class PlansRepository {

    private final Jdbi jdbi;
    private final Clock clock;

    public PlansRepository(Jdbi jdbi, Clock clock) {
        this.jdbi = jdbi;
        this.clock = clock;
    }

    public enum Status { DRAFT, ACTIVE, CLOSED }

    /**
     * One plan.
     *
     * @param year           the year its first day is in, which is what it is called
     * @param suggestedAsOf  the day its suggestions are worked out as of
     * @param setup          true for the plan made by first-time setup, of the year already under way
     * @param activatedOn    the day it became active; {@code null} while it is a draft
     */
    public record Plan(
            long id,
            int year,
            Status status,
            Basis basis,
            LocalDate startsOn,
            LocalDate suggestedAsOf,
            boolean setup,
            LocalDate activatedOn) {

        /** The last day of its year. */
        public LocalDate endsOn() {
            return startsOn.plusYears(1).minusDays(1);
        }
    }

    /**
     * An amount kept with a plan: for a recurring item its total for the year, for an income
     * source its take-home pay for one paycheck. Exactly one of the two ids is set.
     *
     * @param byUser true when it was typed in; false when it is the suggestion as the plan set it
     */
    public record Amount(Long itemId, Long incomeSourceId, long suggestedCents, long chosenCents, boolean byUser) {}

    /** Every plan, the latest year first. */
    public List<Plan> all() {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT * FROM plan_years ORDER BY starts_on DESC")
                .map((rs, ctx) -> plan(rs))
                .list());
    }

    public Optional<Plan> find(long id) {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT * FROM plan_years WHERE id = :id")
                .bind("id", id)
                .map((rs, ctx) -> plan(rs))
                .findOne());
    }

    /** The one plan still being drawn up, if there is one. */
    public Optional<Plan> draft() {
        return all().stream().filter(plan -> plan.status() == Status.DRAFT).findFirst();
    }

    /** Adds a draft and returns its id. The caller has checked that no draft, and no plan of that year, exists. */
    public long create(Basis basis, LocalDate startsOn, LocalDate suggestedAsOf, boolean setup) {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        INSERT INTO plan_years (year, status, basis, starts_on, suggested_as_of, setup, created_at)
                        VALUES (:year, 'draft', :basis, :startsOn, :asOf, :setup, :now)
                        RETURNING id
                        """)
                .bind("year", startsOn.getYear())
                .bind("basis", Inputs.code(basis))
                .bind("startsOn", startsOn.toString())
                .bind("asOf", suggestedAsOf.toString())
                .bind("setup", setup)
                .bind("now", clock.instant().toString())
                .mapTo(Long.class)
                .one());
    }

    /** Changes what a draft's suggestions are worked out from, and as of which day. */
    public void setBasis(long id, Basis basis, LocalDate suggestedAsOf) {
        jdbi.useHandle(handle -> handle
                .createUpdate("UPDATE plan_years SET basis = :basis, suggested_as_of = :asOf WHERE id = :id")
                .bind("id", id)
                .bind("basis", Inputs.code(basis))
                .bind("asOf", suggestedAsOf.toString())
                .execute());
    }

    /** Deletes a plan and every amount kept with it. */
    public void delete(long id) {
        jdbi.useHandle(handle -> handle.createUpdate("DELETE FROM plan_years WHERE id = :id").bind("id", id).execute());
    }

    /** Makes a draft the active plan from {@code today}; the plan that was active is closed. */
    public void activate(long id, LocalDate today) {
        jdbi.useTransaction(handle -> {
            handle.createUpdate("UPDATE plan_years SET status = 'closed' WHERE status = 'active'").execute();
            handle.createUpdate("UPDATE plan_years SET status = 'active', activated_on = :today WHERE id = :id")
                    .bind("id", id)
                    .bind("today", today.toString())
                    .execute();
        });
    }

    /** The amounts kept with a plan: only the typed ones while it is a draft, all of them once it is active. */
    public List<Amount> amounts(long planId) {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT * FROM plan_year_amounts WHERE plan_year_id = :planId ORDER BY id")
                .bind("planId", planId)
                .map((rs, ctx) -> new Amount(
                        nullableLong(rs, "item_id"),
                        nullableLong(rs, "income_source_id"),
                        rs.getLong("suggested_amount"),
                        rs.getLong("chosen_amount"),
                        "user".equals(rs.getString("chosen_by"))))
                .list());
    }

    /** Keeps one amount with a plan, in place of any kept for the same item or income source. */
    public void put(long planId, Amount amount) {
        jdbi.useTransaction(handle -> {
            handle.createUpdate("""
                            DELETE FROM plan_year_amounts
                            WHERE plan_year_id = :planId AND item_id IS :itemId AND income_source_id IS :sourceId
                            """)
                    .bind("planId", planId)
                    .bind("itemId", amount.itemId())
                    .bind("sourceId", amount.incomeSourceId())
                    .execute();
            handle.createUpdate("""
                            INSERT INTO plan_year_amounts
                                (plan_year_id, item_id, income_source_id, suggested_amount, chosen_amount, chosen_by)
                            VALUES (:planId, :itemId, :sourceId, :suggested, :chosen, :chosenBy)
                            """)
                    .bind("planId", planId)
                    .bind("itemId", amount.itemId())
                    .bind("sourceId", amount.incomeSourceId())
                    .bind("suggested", amount.suggestedCents())
                    .bind("chosen", amount.chosenCents())
                    .bind("chosenBy", amount.byUser() ? "user" : "suggestion")
                    .execute();
        });
    }

    /** Forgets the amount typed for one item or income source, so it follows its suggestion again. */
    public void clear(long planId, Long itemId, Long incomeSourceId) {
        jdbi.useHandle(handle -> handle
                .createUpdate("""
                        DELETE FROM plan_year_amounts
                        WHERE plan_year_id = :planId AND item_id IS :itemId AND income_source_id IS :sourceId
                        """)
                .bind("planId", planId)
                .bind("itemId", itemId)
                .bind("sourceId", incomeSourceId)
                .execute());
    }

    private static Plan plan(ResultSet rs) throws SQLException {
        String activatedOn = rs.getString("activated_on");
        return new Plan(
                rs.getLong("id"),
                rs.getInt("year"),
                Status.valueOf(rs.getString("status").toUpperCase(Locale.ROOT)),
                Basis.valueOf(rs.getString("basis").toUpperCase(Locale.ROOT)),
                LocalDate.parse(rs.getString("starts_on")),
                LocalDate.parse(rs.getString("suggested_as_of")),
                rs.getBoolean("setup"),
                activatedOn == null ? null : LocalDate.parse(activatedOn));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
