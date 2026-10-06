package app.budget.server.income;

import app.budget.engine.schedule.ScheduleRule;
import app.budget.server.schedule.ScheduleRules;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/** Reads and writes income sources, their deductions and single-paycheck edits. */
public final class IncomeRepository {

    private static final String SELECT_SOURCE = """
            SELECT id, name, deposit_account_id, gross_amount, net_amount, net_is_estimate, schedule_rule_id, is_primary
            FROM income_sources
            """;

    private final Jdbi jdbi;
    private final Clock clock;

    public IncomeRepository(Jdbi jdbi, Clock clock) {
        this.jdbi = jdbi;
        this.clock = clock;
    }

    /**
     * An income source with its schedule and deductions. Amounts are whole cents.
     *
     * @param grossCents {@code null} when only take-home pay is tracked
     */
    public record Source(
            long id,
            String name,
            long depositAccountId,
            Long grossCents,
            long netCents,
            boolean netIsEstimate,
            boolean primary,
            ScheduleRule schedule,
            List<Deduction> deductions) {}

    /** @param toAccountId {@code null} when the money does not go to one of the user's accounts */
    public record Deduction(String name, long amountCents, Long toAccountId) {}

    /** What the user can set on an income source. */
    public record Fields(
            String name,
            long depositAccountId,
            Long grossCents,
            long netCents,
            boolean netIsEstimate,
            boolean primary,
            ScheduleRule schedule,
            List<Deduction> deductions) {}

    /** One paycheck whose amount was typed over. {@code projectedCents} is what it showed before. */
    public record PaycheckOverride(long incomeSourceId, LocalDate scheduledDate, long netCents, long projectedCents) {}

    /** Every source, the primary one first. */
    public List<Source> all() {
        return jdbi.withHandle(handle -> {
            List<Source> sources = new ArrayList<>();
            for (SourceRow row : rows(handle, SELECT_SOURCE + "ORDER BY is_primary DESC, id", null)) {
                sources.add(complete(handle, row));
            }
            return sources;
        });
    }

    public Optional<Source> find(long id) {
        return jdbi.withHandle(handle -> rows(handle, SELECT_SOURCE + "WHERE id = :id", id).stream()
                .findFirst()
                .map(row -> complete(handle, row)));
    }

    public int count() {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT COUNT(*) FROM income_sources")
                .mapTo(Integer.class)
                .one());
    }

    /**
     * Adds a source and returns its id. The very first source is always the primary one,
     * because something has to define the pay periods; making a later one primary takes
     * that role from whichever source had it.
     */
    public long create(Fields fields) {
        return jdbi.inTransaction(handle -> {
            boolean first = handle.createQuery("SELECT COUNT(*) FROM income_sources").mapTo(Integer.class).one() == 0;
            boolean primary = fields.primary() || first;
            if (primary) {
                handle.createUpdate("UPDATE income_sources SET is_primary = 0 WHERE is_primary = 1").execute();
            }
            long ruleId = ScheduleRules.insert(handle, fields.schedule());
            long id = handle.createQuery("""
                            INSERT INTO income_sources
                                (name, deposit_account_id, gross_amount, net_amount, net_is_estimate,
                                 schedule_rule_id, is_primary, created_at)
                            VALUES (:name, :depositAccountId, :gross, :net, :netIsEstimate, :ruleId, :primary, :now)
                            RETURNING id
                            """)
                    .bind("name", fields.name())
                    .bind("depositAccountId", fields.depositAccountId())
                    .bind("gross", fields.grossCents())
                    .bind("net", fields.netCents())
                    .bind("netIsEstimate", fields.netIsEstimate())
                    .bind("ruleId", ruleId)
                    .bind("primary", primary)
                    .bind("now", clock.instant().toString())
                    .mapTo(Long.class)
                    .one();
            insertDeductions(handle, id, fields.deductions());
            return id;
        });
    }

    /** Replaces everything about an existing source. The caller has checked that it exists. */
    public void update(long id, Fields fields) {
        jdbi.useTransaction(handle -> {
            if (fields.primary()) {
                handle.createUpdate("UPDATE income_sources SET is_primary = 0 WHERE is_primary = 1 AND id <> :id")
                        .bind("id", id)
                        .execute();
            }
            long ruleId = handle.createQuery("SELECT schedule_rule_id FROM income_sources WHERE id = :id")
                    .bind("id", id)
                    .mapTo(Long.class)
                    .one();
            ScheduleRules.update(handle, ruleId, fields.schedule());
            handle.createUpdate("""
                            UPDATE income_sources
                            SET name = :name, deposit_account_id = :depositAccountId, gross_amount = :gross,
                                net_amount = :net, net_is_estimate = :netIsEstimate, is_primary = :primary
                            WHERE id = :id
                            """)
                    .bind("id", id)
                    .bind("name", fields.name())
                    .bind("depositAccountId", fields.depositAccountId())
                    .bind("gross", fields.grossCents())
                    .bind("net", fields.netCents())
                    .bind("netIsEstimate", fields.netIsEstimate())
                    .bind("primary", fields.primary())
                    .execute();
            handle.createUpdate("DELETE FROM paycheck_deductions WHERE income_source_id = :id").bind("id", id).execute();
            insertDeductions(handle, id, fields.deductions());
        });
    }

    /** Deletes a source with its deductions, single-paycheck edits and schedule. */
    public void delete(long id) {
        jdbi.useTransaction(handle -> {
            Optional<Long> ruleId = handle.createQuery("SELECT schedule_rule_id FROM income_sources WHERE id = :id")
                    .bind("id", id)
                    .mapTo(Long.class)
                    .findOne();
            handle.createUpdate("DELETE FROM income_sources WHERE id = :id").bind("id", id).execute();
            // The source points at its schedule, so the schedule can only go once the source has.
            ruleId.ifPresent(rule -> ScheduleRules.delete(handle, rule));
        });
    }

    /** Single-paycheck edits whose scheduled date falls within {@code from}–{@code to}. */
    public List<PaycheckOverride> overrides(LocalDate from, LocalDate to) {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        SELECT income_source_id, scheduled_date, net_amount, projected_amount
                        FROM paycheck_overrides WHERE scheduled_date BETWEEN :from AND :to
                        """)
                .bind("from", from.toString())
                .bind("to", to.toString())
                .map((rs, ctx) -> new PaycheckOverride(
                        rs.getLong("income_source_id"),
                        LocalDate.parse(rs.getString("scheduled_date")),
                        rs.getLong("net_amount"),
                        rs.getLong("projected_amount")))
                .list());
    }

    /**
     * Sets the amount of one paycheck. {@code projectedCents} is only stored the first time
     * a paycheck is edited, so it keeps what the app originally showed.
     */
    public void saveOverride(long incomeSourceId, LocalDate scheduledDate, long netCents, long projectedCents) {
        jdbi.useHandle(handle -> handle
                .createUpdate("""
                        INSERT INTO paycheck_overrides (income_source_id, scheduled_date, net_amount, projected_amount)
                        VALUES (:sourceId, :scheduledDate, :net, :projected)
                        ON CONFLICT (income_source_id, scheduled_date) DO UPDATE SET net_amount = excluded.net_amount
                        """)
                .bind("sourceId", incomeSourceId)
                .bind("scheduledDate", scheduledDate.toString())
                .bind("net", netCents)
                .bind("projected", projectedCents)
                .execute());
    }

    /** The columns of {@code income_sources}, before the schedule and deductions are attached. */
    private record SourceRow(
            long id, String name, long depositAccountId, Long grossCents, long netCents,
            boolean netIsEstimate, long scheduleRuleId, boolean primary) {}

    private static List<SourceRow> rows(Handle handle, String sql, Long id) {
        var query = handle.createQuery(sql);
        if (id != null) {
            query.bind("id", id);
        }
        return query.map((rs, ctx) -> {
            long gross = rs.getLong("gross_amount");
            Long grossCents = rs.wasNull() ? null : gross;
            return new SourceRow(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getLong("deposit_account_id"),
                    grossCents,
                    rs.getLong("net_amount"),
                    rs.getBoolean("net_is_estimate"),
                    rs.getLong("schedule_rule_id"),
                    rs.getBoolean("is_primary"));
        }).list();
    }

    private static Source complete(Handle handle, SourceRow row) {
        List<Deduction> deductions = handle
                .createQuery("""
                        SELECT name, amount, to_account_id FROM paycheck_deductions
                        WHERE income_source_id = :id ORDER BY sort_order, id
                        """)
                .bind("id", row.id())
                .map((rs, ctx) -> {
                    long toAccount = rs.getLong("to_account_id");
                    Long toAccountId = rs.wasNull() ? null : toAccount;
                    return new Deduction(rs.getString("name"), rs.getLong("amount"), toAccountId);
                })
                .list();
        return new Source(
                row.id(), row.name(), row.depositAccountId(), row.grossCents(), row.netCents(),
                row.netIsEstimate(), row.primary(), ScheduleRules.load(handle, row.scheduleRuleId()), deductions);
    }

    private static void insertDeductions(Handle handle, long sourceId, List<Deduction> deductions) {
        for (int i = 0; i < deductions.size(); i++) {
            Deduction deduction = deductions.get(i);
            handle.createUpdate("""
                            INSERT INTO paycheck_deductions (income_source_id, name, amount, to_account_id, sort_order)
                            VALUES (:sourceId, :name, :amount, :toAccountId, :sortOrder)
                            """)
                    .bind("sourceId", sourceId)
                    .bind("name", deduction.name())
                    .bind("amount", deduction.amountCents())
                    .bind("toAccountId", deduction.toAccountId())
                    .bind("sortOrder", i)
                    .execute();
        }
    }
}
