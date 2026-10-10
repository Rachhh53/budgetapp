package app.budget.server.loans;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jdbi.v3.core.Jdbi;

/** Reads and writes {@code loan_settings}: the terms of each loan. Its rate lives in the rate history. */
public final class LoansRepository {

    private static final String SELECT = """
            SELECT account_id, original_principal, term_months, start_date, payment_amount, payment_day,
                   escrow_insurance, escrow_taxes
            FROM loan_settings
            """;

    private final Jdbi jdbi;

    public LoansRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /**
     * A loan's terms. Amounts are whole cents.
     *
     * @param originalPrincipalCents what was borrowed; {@code null} when not entered
     * @param termMonths             how long it runs; {@code null} when not entered
     * @param startDate              when it began; {@code null} when not entered
     * @param paymentCents           the whole monthly payment, escrow included
     * @param paymentDay             day of the month it is due and interest is added, 1–31 (31 = the last day)
     */
    public record Settings(
            long accountId,
            Long originalPrincipalCents,
            Integer termMonths,
            LocalDate startDate,
            long paymentCents,
            int paymentDay,
            long escrowInsuranceCents,
            long escrowTaxesCents) {

        /** The part of each month's payment that goes to insurance and taxes, not to the loan. */
        public long escrowCents() {
            return escrowInsuranceCents + escrowTaxesCents;
        }
    }

    public Optional<Settings> settings(long accountId) {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT + "WHERE account_id = :id")
                .bind("id", accountId)
                .map((rs, ctx) -> settings(rs))
                .findOne());
    }

    /** The terms of every loan that has them, by account. */
    public Map<Long, Settings> allSettings() {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT)
                .map((rs, ctx) -> settings(rs))
                .stream()
                .collect(Collectors.toMap(Settings::accountId, found -> found)));
    }

    public void save(Settings settings) {
        jdbi.useHandle(handle -> handle
                .createUpdate("""
                        INSERT INTO loan_settings
                            (account_id, original_principal, term_months, start_date, payment_amount, payment_day,
                             escrow_insurance, escrow_taxes)
                        VALUES (:accountId, :principal, :termMonths, :startDate, :payment, :paymentDay, :insurance, :taxes)
                        ON CONFLICT (account_id) DO UPDATE SET
                            original_principal = excluded.original_principal, term_months = excluded.term_months,
                            start_date = excluded.start_date, payment_amount = excluded.payment_amount,
                            payment_day = excluded.payment_day, escrow_insurance = excluded.escrow_insurance,
                            escrow_taxes = excluded.escrow_taxes
                        """)
                .bind("accountId", settings.accountId())
                .bind("principal", settings.originalPrincipalCents())
                .bind("termMonths", settings.termMonths())
                .bind("startDate", settings.startDate() == null ? null : settings.startDate().toString())
                .bind("payment", settings.paymentCents())
                .bind("paymentDay", settings.paymentDay())
                .bind("insurance", settings.escrowInsuranceCents())
                .bind("taxes", settings.escrowTaxesCents())
                .execute());
    }

    private static Settings settings(ResultSet rs) throws SQLException {
        long principal = rs.getLong("original_principal");
        Long principalCents = rs.wasNull() ? null : principal;
        int term = rs.getInt("term_months");
        Integer termMonths = rs.wasNull() ? null : term;
        String start = rs.getString("start_date");
        return new Settings(
                rs.getLong("account_id"),
                principalCents,
                termMonths,
                start == null ? null : LocalDate.parse(start),
                rs.getLong("payment_amount"),
                rs.getInt("payment_day"),
                rs.getLong("escrow_insurance"),
                rs.getLong("escrow_taxes"));
    }
}
