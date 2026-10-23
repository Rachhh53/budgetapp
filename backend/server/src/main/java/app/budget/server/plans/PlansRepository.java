package app.budget.server.plans;

import app.budget.engine.plans.PlanSuggestions.Basis;
import app.budget.server.api.Inputs;
import app.budget.server.income.IncomeRepository.Deduction;
import app.budget.server.income.IncomeRepository.Match;
import app.budget.server.income.IncomeRepository.MatchMode;
import app.budget.server.income.IncomeRepository.Pay;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jdbi.v3.core.Handle;
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

    /**
     * A raise partway through a plan's year: from {@code from} on, the planned paycheck goes up by {@code bp}.
     *
     * @param bp the raise in basis points, 300 for 3%
     */
    public record Raise(LocalDate from, int bp) {}

    /**
     * A paycheck planned in full for a plan's year.
     *
     * @param pay   what it comes to from the plan's first day
     * @param raise {@code null} when it stays the same all year
     */
    public record PlannedPay(Pay pay, Raise raise) {}

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

    /**
     * Forgets the amount typed for one item or income source, so it follows its suggestion again. For
     * an income source the whole paycheck planned for it goes too.
     */
    public void clear(long planId, Long itemId, Long incomeSourceId) {
        jdbi.useTransaction(handle -> {
            handle.createUpdate("""
                            DELETE FROM plan_year_amounts
                            WHERE plan_year_id = :planId AND item_id IS :itemId AND income_source_id IS :sourceId
                            """)
                    .bind("planId", planId)
                    .bind("itemId", itemId)
                    .bind("sourceId", incomeSourceId)
                    .execute();
            if (incomeSourceId != null) {
                // Its deductions go with it (ON DELETE CASCADE).
                handle.createUpdate("DELETE FROM plan_year_pay WHERE plan_year_id = :planId AND income_source_id = :sourceId")
                        .bind("planId", planId)
                        .bind("sourceId", incomeSourceId)
                        .execute();
            }
        });
    }

    /** The paychecks planned in full for a plan's year, by income source. Only these are kept; the others follow today's pay. */
    public Map<Long, PlannedPay> pays(long planId) {
        return jdbi.withHandle(handle -> {
            Map<Long, PlannedPay> pays = new HashMap<>();
            handle.createQuery("""
                            SELECT id, income_source_id, gross_amount, net_amount, net_is_estimate, match_to_account_id,
                                   match_mode, match_fixed_amount, match_bp, match_cap_bp_of_gross, match_within_cap,
                                   raise_from, raise_bp
                            FROM plan_year_pay WHERE plan_year_id = :planId
                            """)
                    .bind("planId", planId)
                    .map((rs, ctx) -> {
                        long payId = rs.getLong("id");
                        Long grossCents = nullableLong(rs, "gross_amount");
                        String mode = rs.getString("match_mode");
                        Match match = mode == null ? null : new Match(
                                rs.getLong("match_to_account_id"),
                                MatchMode.valueOf(mode.toUpperCase(Locale.ROOT)),
                                nullableLong(rs, "match_fixed_amount"),
                                nullableInt(rs, "match_bp"),
                                nullableInt(rs, "match_cap_bp_of_gross"),
                                rs.getBoolean("match_within_cap"));
                        String raiseFrom = rs.getString("raise_from");
                        Integer raiseBp = nullableInt(rs, "raise_bp");
                        Raise raise = raiseFrom == null || raiseBp == null ? null : new Raise(LocalDate.parse(raiseFrom), raiseBp);
                        Pay pay = new Pay(
                                grossCents, rs.getLong("net_amount"), rs.getBoolean("net_is_estimate"), deductions(handle, payId), match);
                        return Map.entry(rs.getLong("income_source_id"), new PlannedPay(pay, raise));
                    })
                    .list()
                    .forEach(entry -> pays.put(entry.getKey(), entry.getValue()));
            return pays;
        });
    }

    /** Keeps a whole paycheck with a plan for one income source, in place of any kept before. */
    public void putPay(long planId, long incomeSourceId, PlannedPay planned) {
        Pay pay = planned.pay();
        Raise raise = planned.raise();
        jdbi.useTransaction(handle -> {
            handle.createUpdate("DELETE FROM plan_year_pay WHERE plan_year_id = :planId AND income_source_id = :sourceId")
                    .bind("planId", planId)
                    .bind("sourceId", incomeSourceId)
                    .execute();
            Match match = pay.match();
            long payId = handle.createQuery("""
                            INSERT INTO plan_year_pay
                                (plan_year_id, income_source_id, gross_amount, net_amount, net_is_estimate,
                                 match_to_account_id, match_mode, match_fixed_amount, match_bp, match_cap_bp_of_gross, match_within_cap,
                                 raise_from, raise_bp)
                            VALUES (:planId, :sourceId, :gross, :net, :netIsEstimate,
                                    :matchAccountId, :matchMode, :matchFixed, :matchBp, :matchCapBp, :matchWithinCap,
                                    :raiseFrom, :raiseBp)
                            RETURNING id
                            """)
                    .bind("planId", planId)
                    .bind("sourceId", incomeSourceId)
                    .bind("gross", pay.grossCents())
                    .bind("net", pay.netCents())
                    .bind("netIsEstimate", pay.netIsEstimate())
                    .bind("matchAccountId", match == null ? null : match.toAccountId())
                    .bind("matchMode", match == null ? null : Inputs.code(match.mode()))
                    .bind("matchFixed", match == null ? null : match.fixedCents())
                    .bind("matchBp", match == null ? null : match.matchBp())
                    .bind("matchCapBp", match == null ? null : match.capBpOfGross())
                    .bind("matchWithinCap", match != null && match.contributionWithinCap())
                    .bind("raiseFrom", raise == null ? null : raise.from().toString())
                    .bind("raiseBp", raise == null ? null : raise.bp())
                    .mapTo(Long.class)
                    .one();
            int order = 0;
            for (Deduction deduction : pay.deductions()) {
                handle.createUpdate("""
                                INSERT INTO plan_year_pay_deductions (plan_year_pay_id, name, amount, to_account_id, percent_bp, sort_order)
                                VALUES (:payId, :name, :amount, :toAccountId, :percentBp, :order)
                                """)
                        .bind("payId", payId)
                        .bind("name", deduction.name())
                        .bind("amount", deduction.amountCents())
                        .bind("toAccountId", deduction.toAccountId())
                        .bind("percentBp", deduction.percentBp())
                        .bind("order", order++)
                        .execute();
            }
        });
    }

    private static List<Deduction> deductions(Handle handle, long payId) {
        return handle
                .createQuery("""
                        SELECT name, amount, to_account_id, percent_bp FROM plan_year_pay_deductions
                        WHERE plan_year_pay_id = :payId ORDER BY sort_order, id
                        """)
                .bind("payId", payId)
                .map((rs, ctx) -> new Deduction(
                        rs.getString("name"), rs.getLong("amount"), nullableLong(rs, "to_account_id"), nullableInt(rs, "percent_bp")))
                .list();
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

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
