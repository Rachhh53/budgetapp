package app.budget.server.income;

import app.budget.engine.schedule.ScheduleRule;
import app.budget.server.api.Inputs;
import app.budget.server.schedule.ScheduleRules;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/**
 * Reads and writes income sources: their schedule, what each paycheck comes to, and
 * single-paycheck edits.
 *
 * <p>What a paycheck comes to — take-home, gross, deductions, the employer's match — can change
 * from a date on (a raise). The amounts a source started with live on its own row, with its
 * deductions and {@code employer_match}; every later change is a row of {@code income_pay_changes}
 * with its own deductions. Both are read back as {@link Terms}, so nothing outside this class
 * needs to know which is which.
 */
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
     * An income source with its schedule and what its paychecks come to over time.
     *
     * @param terms never empty: the amounts it started with first, then each change of pay, oldest first
     */
    public record Source(
            long id,
            String name,
            long depositAccountId,
            boolean primary,
            ScheduleRule schedule,
            List<Terms> terms) {

        /** The amounts in force for a paycheck whose schedule names {@code date}. */
        public Terms termsOn(LocalDate date) {
            Terms inForce = terms.get(0);
            for (Terms later : terms.subList(1, terms.size())) {
                if (!later.effectiveDate().isAfter(date)) {
                    inForce = later;
                }
            }
            return inForce;
        }
    }

    /**
     * What a paycheck comes to from a date on. Amounts are whole cents.
     *
     * @param changeId      {@code null} for the amounts the source started with
     * @param effectiveDate the first day these apply; {@code null} for the amounts the source started with
     */
    public record Terms(Long changeId, LocalDate effectiveDate, Pay pay) {}

    /**
     * What one paycheck comes to.
     *
     * @param grossCents {@code null} when only take-home pay is tracked
     * @param match      {@code null} when the employer adds nothing
     */
    public record Pay(Long grossCents, long netCents, boolean netIsEstimate, List<Deduction> deductions, Match match) {}

    /**
     * @param amountCents what it takes out of one paycheck; for a share of pay, worked out from the gross amount
     * @param toAccountId {@code null} when the money does not go to one of the user's accounts
     * @param percentBp   set when the user gave it as a share of gross pay ("6% of my pay"); {@code null} for a dollar amount
     */
    public record Deduction(String name, long amountCents, Long toAccountId, Integer percentBp) {}

    /**
     * What the employer adds with each paycheck (spec §5.5).
     *
     * @param toAccountId  the account the match is paid into
     * @param fixedCents   the amount every paycheck; set exactly when {@code mode} is {@code FIXED}
     * @param matchBp      the share of the employee's contribution that is matched; for {@code FORMULA}
     * @param capBpOfGross the share of gross pay beyond which contributions are not matched; for {@code FORMULA}
     * @param contributionWithinCap the source has no gross amount, and the user confirmed that what
     *                     they put in is within that share, so all of it is matched
     */
    public record Match(
            long toAccountId, MatchMode mode, Long fixedCents, Integer matchBp, Integer capBpOfGross, boolean contributionWithinCap) {}

    public enum MatchMode { FIXED, FORMULA }

    /** What the user can set on an income source: the source itself, and one set of amounts. */
    public record Fields(String name, long depositAccountId, boolean primary, ScheduleRule schedule, Pay pay) {}

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
            Pay pay = fields.pay();
            long id = handle.createQuery("""
                            INSERT INTO income_sources
                                (name, deposit_account_id, gross_amount, net_amount, net_is_estimate,
                                 schedule_rule_id, is_primary, created_at)
                            VALUES (:name, :depositAccountId, :gross, :net, :netIsEstimate, :ruleId, :primary, :now)
                            RETURNING id
                            """)
                    .bind("name", fields.name())
                    .bind("depositAccountId", fields.depositAccountId())
                    .bind("gross", pay.grossCents())
                    .bind("net", pay.netCents())
                    .bind("netIsEstimate", pay.netIsEstimate())
                    .bind("ruleId", ruleId)
                    .bind("primary", primary)
                    .bind("now", clock.instant().toString())
                    .mapTo(Long.class)
                    .one();
            insertDeductions(handle, id, null, pay.deductions());
            saveMatch(handle, id, pay.match());
            return id;
        });
    }

    /**
     * Changes a source, and replaces one of its sets of amounts in place: a correction, which
     * reaches every paycheck those amounts already cover. The caller has checked that both exist.
     *
     * @param changeId the change of pay to replace; {@code null} for the amounts the source started with
     */
    public void update(long id, Fields fields, Long changeId) {
        jdbi.useTransaction(handle -> {
            updateSource(handle, id, fields);
            if (changeId == null) {
                replaceOriginalPay(handle, id, fields.pay());
            } else {
                replaceChange(handle, id, changeId, fields.pay());
            }
        });
    }

    /**
     * Changes a source, and makes {@code fields.pay()} what its paychecks come to from
     * {@code from} on: a raise. Earlier paychecks keep what they had, and so does a change that
     * starts on a later day. A change already dated {@code from} is replaced.
     */
    public void updateFrom(long id, Fields fields, LocalDate from) {
        jdbi.useTransaction(handle -> {
            updateSource(handle, id, fields);
            Optional<Long> sameDay = handle
                    .createQuery("SELECT id FROM income_pay_changes WHERE income_source_id = :id AND effective_date = :date")
                    .bind("id", id)
                    .bind("date", from.toString())
                    .mapTo(Long.class)
                    .findOne();
            if (sameDay.isPresent()) {
                replaceChange(handle, id, sameDay.get(), fields.pay());
                return;
            }
            Pay pay = fields.pay();
            Match match = pay.match();
            long changeId = handle.createQuery("""
                            INSERT INTO income_pay_changes
                                (income_source_id, effective_date, gross_amount, net_amount, net_is_estimate,
                                 match_to_account_id, match_mode, match_fixed_amount, match_bp, match_cap_bp_of_gross, match_within_cap)
                            VALUES (:id, :date, :gross, :net, :netIsEstimate,
                                    :matchAccountId, :matchMode, :matchFixed, :matchBp, :matchCapBp, :matchWithinCap)
                            RETURNING id
                            """)
                    .bind("id", id)
                    .bind("date", from.toString())
                    .bind("gross", pay.grossCents())
                    .bind("net", pay.netCents())
                    .bind("netIsEstimate", pay.netIsEstimate())
                    .bind("matchAccountId", match == null ? null : match.toAccountId())
                    .bind("matchMode", match == null ? null : Inputs.code(match.mode()))
                    .bind("matchFixed", match == null ? null : match.fixedCents())
                    .bind("matchBp", match == null ? null : match.matchBp())
                    .bind("matchCapBp", match == null ? null : match.capBpOfGross())
                    .bind("matchWithinCap", match != null && match.contributionWithinCap())
                    .mapTo(Long.class)
                    .one();
            insertDeductions(handle, id, changeId, pay.deductions());
        });
    }

    /** Removes one change of pay, so the amounts before it carry on. False when the source has no such change. */
    public boolean deletePayChange(long id, long changeId) {
        // Its deductions go with it (ON DELETE CASCADE).
        return jdbi.withHandle(handle -> handle
                .createUpdate("DELETE FROM income_pay_changes WHERE id = :changeId AND income_source_id = :id")
                .bind("id", id)
                .bind("changeId", changeId)
                .execute()) > 0;
    }

    /** Deletes a source with its changes of pay, deductions, single-paycheck edits and schedule. */
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

    /** The columns of {@code income_sources}, before the schedule, deductions and changes of pay are attached. */
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
        List<Terms> terms = new ArrayList<>();
        terms.add(new Terms(null, null, new Pay(
                row.grossCents(), row.netCents(), row.netIsEstimate(), deductions(handle, row.id(), null), originalMatch(handle, row.id()))));
        terms.addAll(changes(handle, row.id()));
        return new Source(
                row.id(), row.name(), row.depositAccountId(), row.primary(),
                ScheduleRules.load(handle, row.scheduleRuleId()), List.copyOf(terms));
    }

    /** The source's changes of pay, oldest first. */
    private static List<Terms> changes(Handle handle, long sourceId) {
        return handle
                .createQuery("""
                        SELECT id, effective_date, gross_amount, net_amount, net_is_estimate,
                               match_to_account_id, match_mode, match_fixed_amount, match_bp, match_cap_bp_of_gross, match_within_cap
                        FROM income_pay_changes WHERE income_source_id = :id ORDER BY effective_date
                        """)
                .bind("id", sourceId)
                .map((rs, ctx) -> {
                    long changeId = rs.getLong("id");
                    long gross = rs.getLong("gross_amount");
                    Long grossCents = rs.wasNull() ? null : gross;
                    String mode = rs.getString("match_mode");
                    Match match = null;
                    if (mode != null) {
                        long fixed = rs.getLong("match_fixed_amount");
                        Long fixedCents = rs.wasNull() ? null : fixed;
                        int matchBp = rs.getInt("match_bp");
                        Integer matched = rs.wasNull() ? null : matchBp;
                        int capBp = rs.getInt("match_cap_bp_of_gross");
                        Integer cap = rs.wasNull() ? null : capBp;
                        match = new Match(
                                rs.getLong("match_to_account_id"), MatchMode.valueOf(mode.toUpperCase(Locale.ROOT)),
                                fixedCents, matched, cap, rs.getBoolean("match_within_cap"));
                    }
                    return new Terms(changeId, LocalDate.parse(rs.getString("effective_date")), new Pay(
                            grossCents, rs.getLong("net_amount"), rs.getBoolean("net_is_estimate"),
                            deductions(handle, sourceId, changeId), match));
                })
                .list();
    }

    /** The deductions of one change of pay, or of the amounts the source started with ({@code changeId} null). */
    private static List<Deduction> deductions(Handle handle, long sourceId, Long changeId) {
        return handle
                .createQuery("""
                        SELECT name, amount, to_account_id, percent_bp FROM paycheck_deductions
                        WHERE income_source_id = :id AND pay_change_id IS :changeId ORDER BY sort_order, id
                        """)
                .bind("id", sourceId)
                .bind("changeId", changeId)
                .map((rs, ctx) -> {
                    long toAccount = rs.getLong("to_account_id");
                    Long toAccountId = rs.wasNull() ? null : toAccount;
                    int percent = rs.getInt("percent_bp");
                    Integer percentBp = rs.wasNull() ? null : percent;
                    return new Deduction(rs.getString("name"), rs.getLong("amount"), toAccountId, percentBp);
                })
                .list();
    }

    private static Match originalMatch(Handle handle, long sourceId) {
        return handle
                .createQuery("""
                        SELECT to_account_id, mode, fixed_amount, match_bp, cap_bp_of_gross, contribution_within_cap
                        FROM employer_match WHERE income_source_id = :id
                        """)
                .bind("id", sourceId)
                .map((rs, ctx) -> {
                    long fixed = rs.getLong("fixed_amount");
                    Long fixedCents = rs.wasNull() ? null : fixed;
                    int matchBp = rs.getInt("match_bp");
                    Integer match = rs.wasNull() ? null : matchBp;
                    int capBp = rs.getInt("cap_bp_of_gross");
                    Integer cap = rs.wasNull() ? null : capBp;
                    return new Match(
                            rs.getLong("to_account_id"), MatchMode.valueOf(rs.getString("mode").toUpperCase(Locale.ROOT)),
                            fixedCents, match, cap, rs.getBoolean("contribution_within_cap"));
                })
                .findOne()
                .orElse(null);
    }

    /**
     * Makes one source the primary one, so its schedule defines the pay periods, and nothing
     * else about any source changes.
     *
     * @return false when there is no such source
     */
    public boolean makePrimary(long id) {
        return jdbi.inTransaction(handle -> {
            boolean exists = handle.createQuery("SELECT EXISTS (SELECT 1 FROM income_sources WHERE id = :id)")
                    .bind("id", id)
                    .mapTo(Boolean.class)
                    .one();
            if (!exists) {
                return false;
            }
            // The old one first: there is never a moment with two.
            handle.createUpdate("UPDATE income_sources SET is_primary = 0 WHERE is_primary = 1 AND id <> :id")
                    .bind("id", id)
                    .execute();
            handle.createUpdate("UPDATE income_sources SET is_primary = 1 WHERE id = :id").bind("id", id).execute();
            return true;
        });
    }

    /** Everything about a source that is not an amount: its name, account, schedule and whether it is the primary one. */
    private static void updateSource(Handle handle, long id, Fields fields) {
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
                        SET name = :name, deposit_account_id = :depositAccountId, is_primary = :primary
                        WHERE id = :id
                        """)
                .bind("id", id)
                .bind("name", fields.name())
                .bind("depositAccountId", fields.depositAccountId())
                .bind("primary", fields.primary())
                .execute();
    }

    private static void replaceOriginalPay(Handle handle, long id, Pay pay) {
        handle.createUpdate("""
                        UPDATE income_sources
                        SET gross_amount = :gross, net_amount = :net, net_is_estimate = :netIsEstimate
                        WHERE id = :id
                        """)
                .bind("id", id)
                .bind("gross", pay.grossCents())
                .bind("net", pay.netCents())
                .bind("netIsEstimate", pay.netIsEstimate())
                .execute();
        handle.createUpdate("DELETE FROM paycheck_deductions WHERE income_source_id = :id AND pay_change_id IS NULL")
                .bind("id", id)
                .execute();
        insertDeductions(handle, id, null, pay.deductions());
        saveMatch(handle, id, pay.match());
    }

    private static void replaceChange(Handle handle, long id, long changeId, Pay pay) {
        Match match = pay.match();
        handle.createUpdate("""
                        UPDATE income_pay_changes
                        SET gross_amount = :gross, net_amount = :net, net_is_estimate = :netIsEstimate,
                            match_to_account_id = :matchAccountId, match_mode = :matchMode, match_fixed_amount = :matchFixed,
                            match_bp = :matchBp, match_cap_bp_of_gross = :matchCapBp, match_within_cap = :matchWithinCap
                        WHERE id = :changeId AND income_source_id = :id
                        """)
                .bind("id", id)
                .bind("changeId", changeId)
                .bind("gross", pay.grossCents())
                .bind("net", pay.netCents())
                .bind("netIsEstimate", pay.netIsEstimate())
                .bind("matchAccountId", match == null ? null : match.toAccountId())
                .bind("matchMode", match == null ? null : Inputs.code(match.mode()))
                .bind("matchFixed", match == null ? null : match.fixedCents())
                .bind("matchBp", match == null ? null : match.matchBp())
                .bind("matchCapBp", match == null ? null : match.capBpOfGross())
                .bind("matchWithinCap", match != null && match.contributionWithinCap())
                .execute();
        handle.createUpdate("DELETE FROM paycheck_deductions WHERE pay_change_id = :changeId").bind("changeId", changeId).execute();
        insertDeductions(handle, id, changeId, pay.deductions());
    }

    /** Replaces the match of the amounts the source started with; {@code null} removes it. */
    private static void saveMatch(Handle handle, long sourceId, Match match) {
        handle.createUpdate("DELETE FROM employer_match WHERE income_source_id = :id").bind("id", sourceId).execute();
        if (match == null) {
            return;
        }
        handle.createUpdate("""
                        INSERT INTO employer_match
                            (income_source_id, to_account_id, mode, fixed_amount, match_bp, cap_bp_of_gross, contribution_within_cap)
                        VALUES (:sourceId, :toAccountId, :mode, :fixed, :matchBp, :capBp, :withinCap)
                        """)
                .bind("sourceId", sourceId)
                .bind("toAccountId", match.toAccountId())
                .bind("mode", Inputs.code(match.mode()))
                .bind("fixed", match.fixedCents())
                .bind("matchBp", match.matchBp())
                .bind("capBp", match.capBpOfGross())
                .bind("withinCap", match.contributionWithinCap())
                .execute();
    }

    private static void insertDeductions(Handle handle, long sourceId, Long changeId, List<Deduction> deductions) {
        for (int i = 0; i < deductions.size(); i++) {
            Deduction deduction = deductions.get(i);
            handle.createUpdate("""
                            INSERT INTO paycheck_deductions
                                (income_source_id, pay_change_id, name, amount, to_account_id, percent_bp, sort_order)
                            VALUES (:sourceId, :changeId, :name, :amount, :toAccountId, :percentBp, :sortOrder)
                            """)
                    .bind("sourceId", sourceId)
                    .bind("changeId", changeId)
                    .bind("name", deduction.name())
                    .bind("amount", deduction.amountCents())
                    .bind("toAccountId", deduction.toAccountId())
                    .bind("percentBp", deduction.percentBp())
                    .bind("sortOrder", i)
                    .execute();
        }
    }
}
