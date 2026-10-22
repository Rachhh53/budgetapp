package app.budget.server.accounts;

import app.budget.engine.interest.SavingsInterest;
import app.budget.server.api.Inputs;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jdbi.v3.core.Jdbi;

/** Reads and writes {@code interest_settings} and {@code rate_history}. */
public final class InterestRepository {

    private final Jdbi jdbi;

    public InterestRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /** Where an account's interest goes once it is credited. */
    public enum Destination { UNASSIGNED, BUCKET, PROPORTIONAL }

    /** What a rate is the rate of. Each kind of account has one. */
    public enum RateKind { APY, GROWTH, APR, LOAN_RATE }

    /**
     * @param creditDay           day of the month, 1–31; 31 means the last day
     * @param destinationBucketId set exactly when {@code destination} is {@code BUCKET}
     */
    public record Settings(int creditDay, Destination destination, Long destinationBucketId) {

        /** What applies until the user chooses otherwise: the last day of the month, into no bucket. */
        public static final Settings DEFAULT = new Settings(SavingsInterest.LAST_DAY, Destination.UNASSIGNED, null);
    }

    /** A rate in basis points that applies from {@code effectiveDate} until the next one of its kind. */
    public record Rate(long id, RateKind kind, int rateBp, LocalDate effectiveDate) {}

    public Optional<Settings> settings(long accountId) {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT account_id, credit_day, destination, destination_bucket_id FROM interest_settings WHERE account_id = :id")
                .bind("id", accountId)
                .map((rs, ctx) -> settings(rs))
                .findOne());
    }

    /** The interest settings of every account that has them. */
    public Map<Long, Settings> allSettings() {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT account_id, credit_day, destination, destination_bucket_id FROM interest_settings")
                .map((rs, ctx) -> Map.entry(rs.getLong("account_id"), settings(rs)))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    public void saveSettings(long accountId, Settings settings) {
        jdbi.useHandle(handle -> handle
                .createUpdate("""
                        INSERT INTO interest_settings (account_id, credit_day, destination, destination_bucket_id)
                        VALUES (:accountId, :creditDay, :destination, :bucketId)
                        ON CONFLICT (account_id) DO UPDATE SET
                            credit_day = excluded.credit_day, destination = excluded.destination,
                            destination_bucket_id = excluded.destination_bucket_id
                        """)
                .bind("accountId", accountId)
                .bind("creditDay", settings.creditDay())
                .bind("destination", Inputs.code(settings.destination()))
                .bind("bucketId", settings.destinationBucketId())
                .execute());
    }

    /** An account's rates, newest first. */
    public List<Rate> rates(long accountId) {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        SELECT id, account_id, kind, rate_bp, effective_date FROM rate_history
                        WHERE account_id = :id ORDER BY effective_date DESC, id DESC
                        """)
                .bind("id", accountId)
                .map((rs, ctx) -> rate(rs))
                .list());
    }

    /** Every rate of one kind, by account. */
    public Map<Long, List<Rate>> allRates(RateKind kind) {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT id, account_id, kind, rate_bp, effective_date FROM rate_history WHERE kind = :kind ORDER BY effective_date")
                .bind("kind", Inputs.code(kind))
                .map((rs, ctx) -> Map.entry(rs.getLong("account_id"), rate(rs)))
                .stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList()))));
    }

    /** Records a rate from a date. Entering the same date again replaces what was there. */
    public void saveRate(long accountId, RateKind kind, int rateBp, LocalDate effectiveDate) {
        jdbi.useHandle(handle -> handle
                .createUpdate("""
                        INSERT INTO rate_history (account_id, kind, rate_bp, effective_date)
                        VALUES (:accountId, :kind, :rateBp, :effectiveDate)
                        ON CONFLICT (account_id, kind, effective_date) DO UPDATE SET rate_bp = excluded.rate_bp
                        """)
                .bind("accountId", accountId)
                .bind("kind", Inputs.code(kind))
                .bind("rateBp", rateBp)
                .bind("effectiveDate", effectiveDate.toString())
                .execute());
    }

    public boolean deleteRate(long accountId, long rateId) {
        return jdbi.withHandle(handle -> handle
                .createUpdate("DELETE FROM rate_history WHERE id = :rateId AND account_id = :accountId")
                .bind("rateId", rateId)
                .bind("accountId", accountId)
                .execute()) > 0;
    }

    /**
     * Interest typed over the estimate (migration V27).
     *
     * @param date           the day the interest is added; for an investment, the first of the month
     *                       whose growth it is
     * @param estimatedCents what was estimated when it was first typed over
     */
    public record Typed(LocalDate date, long amountCents, long estimatedCents) {}

    /** Every amount typed over, by account and then by date. */
    public Map<Long, Map<LocalDate, Typed>> allTyped() {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT account_id, date, amount, estimated_amount FROM interest_overrides")
                .map((rs, ctx) -> Map.entry(rs.getLong("account_id"), new Typed(
                        LocalDate.parse(rs.getString("date")), rs.getLong("amount"), rs.getLong("estimated_amount"))))
                .stream()
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey, Collectors.toMap(entry -> entry.getValue().date(), Map.Entry::getValue))));
    }

    /**
     * Types over one month's interest. {@code estimatedCents} is only stored the first time, so it
     * keeps what the app showed before anything was typed.
     */
    public void saveTyped(long accountId, LocalDate date, long amountCents, long estimatedCents) {
        jdbi.useHandle(handle -> handle
                .createUpdate("""
                        INSERT INTO interest_overrides (account_id, date, amount, estimated_amount)
                        VALUES (:accountId, :date, :amount, :estimated)
                        ON CONFLICT (account_id, date) DO UPDATE SET amount = excluded.amount
                        """)
                .bind("accountId", accountId)
                .bind("date", date.toString())
                .bind("amount", amountCents)
                .bind("estimated", estimatedCents)
                .execute());
    }

    /** Puts the estimate back. False when nothing was typed over on that date. */
    public boolean deleteTyped(long accountId, LocalDate date) {
        return jdbi.withHandle(handle -> handle
                .createUpdate("DELETE FROM interest_overrides WHERE account_id = :accountId AND date = :date")
                .bind("accountId", accountId)
                .bind("date", date.toString())
                .execute()) > 0;
    }

    private static Settings settings(ResultSet rs) throws SQLException {
        long bucketId = rs.getLong("destination_bucket_id");
        boolean noBucket = rs.wasNull();
        return new Settings(
                rs.getInt("credit_day"),
                Destination.valueOf(rs.getString("destination").toUpperCase(Locale.ROOT)),
                noBucket ? null : bucketId);
    }

    private static Rate rate(ResultSet rs) throws SQLException {
        return new Rate(
                rs.getLong("id"),
                RateKind.valueOf(rs.getString("kind").toUpperCase(Locale.ROOT)),
                rs.getInt("rate_bp"),
                LocalDate.parse(rs.getString("effective_date")));
    }
}
