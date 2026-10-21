package app.budget.server.income;

import app.budget.server.income.IncomeRepository.Deduction;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Query;

/**
 * Reads and writes one-off paychecks: a bonus, or any extra pay from an income source that
 * arrives once on its own day, with a paycheck's figures but no place in the schedule (spec §4.3).
 */
public final class OneOffPaychecksRepository {

    private static final String SELECT = """
            SELECT id, income_source_id, pay_date, name, gross_amount, net_amount, matched, pay_stub_id
            FROM one_off_paychecks
            """;

    private final Jdbi jdbi;
    private final Clock clock;

    public OneOffPaychecksRepository(Jdbi jdbi, Clock clock) {
        this.jdbi = jdbi;
        this.clock = clock;
    }

    /**
     * One extra paycheck as stored.
     *
     * @param grossCents {@code null} when only take-home pay was given
     * @param matched    the employer's match is added by the source's own rule on this paycheck
     * @param payStubId  the stub it was saved from; {@code null} when typed in, or once that stub is removed
     */
    public record OneOff(
            long id,
            long incomeSourceId,
            LocalDate payDate,
            String name,
            Long grossCents,
            long netCents,
            List<Deduction> deductions,
            boolean matched,
            Long payStubId) {}

    /** What the user sets on a one-off paycheck. */
    public record Fields(LocalDate payDate, String name, Long grossCents, long netCents, List<Deduction> deductions, boolean matched) {}

    /** Every one-off paycheck paid within {@code from}–{@code to}, from every source, oldest first. */
    public List<OneOff> between(LocalDate from, LocalDate to) {
        return jdbi.withHandle(handle -> complete(handle, handle.createQuery(SELECT + "WHERE pay_date BETWEEN :from AND :to ORDER BY pay_date, id")
                .bind("from", from.toString())
                .bind("to", to.toString())));
    }

    /** One source's one-off paychecks, newest first. */
    public List<OneOff> forSource(long sourceId) {
        return jdbi.withHandle(handle -> complete(handle, handle.createQuery(SELECT + "WHERE income_source_id = :id ORDER BY pay_date DESC, id DESC")
                .bind("id", sourceId)));
    }

    public Optional<OneOff> find(long id) {
        return jdbi.withHandle(handle -> complete(handle, handle.createQuery(SELECT + "WHERE id = :id").bind("id", id)).stream().findFirst());
    }

    /** The one-off paycheck a stub was saved as, if it still exists. */
    public Optional<OneOff> forStub(long payStubId) {
        return jdbi.withHandle(handle -> complete(handle, handle.createQuery(SELECT + "WHERE pay_stub_id = :stub").bind("stub", payStubId))
                .stream()
                .findFirst());
    }

    /** Adds one and returns its id. {@code payStubId} is the stub it comes from, or {@code null}. */
    public long create(long sourceId, Fields fields, Long payStubId) {
        return jdbi.inTransaction(handle -> {
            long id = handle.createQuery("""
                            INSERT INTO one_off_paychecks
                                (income_source_id, pay_date, name, gross_amount, net_amount, matched, pay_stub_id, created_at)
                            VALUES (:sourceId, :payDate, :name, :gross, :net, :matched, :stubId, :now)
                            RETURNING id
                            """)
                    .bind("sourceId", sourceId)
                    .bind("payDate", fields.payDate().toString())
                    .bind("name", fields.name())
                    .bind("gross", fields.grossCents())
                    .bind("net", fields.netCents())
                    .bind("matched", fields.matched())
                    .bind("stubId", payStubId)
                    .bind("now", clock.instant().toString())
                    .mapTo(Long.class)
                    .one();
            insertDeductions(handle, id, fields.deductions());
            return id;
        });
    }

    /** Replaces everything the user sets on it. The stub it came from, if any, stays linked. */
    public void update(long id, Fields fields) {
        jdbi.useTransaction(handle -> {
            handle.createUpdate("""
                            UPDATE one_off_paychecks
                            SET pay_date = :payDate, name = :name, gross_amount = :gross, net_amount = :net, matched = :matched
                            WHERE id = :id
                            """)
                    .bind("id", id)
                    .bind("payDate", fields.payDate().toString())
                    .bind("name", fields.name())
                    .bind("gross", fields.grossCents())
                    .bind("net", fields.netCents())
                    .bind("matched", fields.matched())
                    .execute();
            handle.createUpdate("DELETE FROM one_off_paycheck_deductions WHERE one_off_paycheck_id = :id").bind("id", id).execute();
            insertDeductions(handle, id, fields.deductions());
        });
    }

    /** Removes one, with its deductions (ON DELETE CASCADE). */
    public void delete(long id) {
        jdbi.useHandle(handle -> handle.createUpdate("DELETE FROM one_off_paychecks WHERE id = :id").bind("id", id).execute());
    }

    /** The rows a query found, each with its deductions. */
    private static List<OneOff> complete(Handle handle, Query query) {
        record Row(long id, long sourceId, LocalDate payDate, String name, Long grossCents, long netCents, boolean matched, Long stubId) {}
        List<Row> rows = query.map((rs, ctx) -> {
            long gross = rs.getLong("gross_amount");
            Long grossCents = rs.wasNull() ? null : gross;
            long stub = rs.getLong("pay_stub_id");
            Long stubId = rs.wasNull() ? null : stub;
            return new Row(
                    rs.getLong("id"), rs.getLong("income_source_id"), LocalDate.parse(rs.getString("pay_date")), rs.getString("name"),
                    grossCents, rs.getLong("net_amount"), rs.getBoolean("matched"), stubId);
        }).list();
        List<OneOff> found = new ArrayList<>();
        for (Row row : rows) {
            found.add(new OneOff(
                    row.id(), row.sourceId(), row.payDate(), row.name(), row.grossCents(), row.netCents(),
                    deductions(handle, row.id()), row.matched(), row.stubId()));
        }
        return found;
    }

    private static List<Deduction> deductions(Handle handle, long id) {
        return handle
                .createQuery("""
                        SELECT name, amount, to_account_id, percent_bp FROM one_off_paycheck_deductions
                        WHERE one_off_paycheck_id = :id ORDER BY sort_order, id
                        """)
                .bind("id", id)
                .map((rs, ctx) -> {
                    long toAccount = rs.getLong("to_account_id");
                    Long toAccountId = rs.wasNull() ? null : toAccount;
                    int percent = rs.getInt("percent_bp");
                    Integer percentBp = rs.wasNull() ? null : percent;
                    return new Deduction(rs.getString("name"), rs.getLong("amount"), toAccountId, percentBp);
                })
                .list();
    }

    private static void insertDeductions(Handle handle, long id, List<Deduction> deductions) {
        for (int i = 0; i < deductions.size(); i++) {
            Deduction deduction = deductions.get(i);
            handle.createUpdate("""
                            INSERT INTO one_off_paycheck_deductions
                                (one_off_paycheck_id, name, amount, to_account_id, percent_bp, sort_order)
                            VALUES (:id, :name, :amount, :toAccountId, :percentBp, :sortOrder)
                            """)
                    .bind("id", id)
                    .bind("name", deduction.name())
                    .bind("amount", deduction.amountCents())
                    .bind("toAccountId", deduction.toAccountId())
                    .bind("percentBp", deduction.percentBp())
                    .bind("sortOrder", i)
                    .execute();
        }
    }
}
