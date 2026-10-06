package app.budget.server.schedule;

import app.budget.engine.schedule.Holiday;
import app.budget.engine.schedule.UsBankHolidays;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/**
 * Reads and writes {@code holidays}.
 *
 * <p>US bank holidays are worked out locally and added one year at a time, the first time
 * that year is needed. Each year is added once only, so a holiday the user removed or
 * moved never comes back.
 */
public final class HolidaysRepository {

    private static final String SOURCE_US_BANK = "us_bank";
    private static final String SOURCE_USER = "user";

    private final Jdbi jdbi;

    public HolidaysRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /** A holiday as the API returns it. {@code source} is {@code us_bank} or {@code user}. */
    public record StoredHoliday(long id, LocalDate date, String name, String source, boolean enabled) {}

    /** Every holiday from {@code from} to {@code to} (inclusive), including ones switched off. */
    public List<StoredHoliday> between(LocalDate from, LocalDate to) {
        return jdbi.inTransaction(handle -> {
            addBankHolidays(handle, from.getYear(), to.getYear());
            return handle.createQuery("""
                            SELECT id, date, name, source, enabled FROM holidays
                            WHERE date BETWEEN :from AND :to ORDER BY date
                            """)
                    .bind("from", from.toString())
                    .bind("to", to.toString())
                    .map((rs, ctx) -> new StoredHoliday(
                            rs.getLong("id"),
                            LocalDate.parse(rs.getString("date")),
                            rs.getString("name"),
                            rs.getString("source"),
                            rs.getBoolean("enabled")))
                    .list();
        });
    }

    /** The dates that count as closed days from {@code from} to {@code to}: holidays left switched on. */
    public List<LocalDate> closedDates(LocalDate from, LocalDate to) {
        return between(from, to).stream().filter(StoredHoliday::enabled).map(StoredHoliday::date).toList();
    }

    public boolean dateTaken(LocalDate date, Long exceptId) {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT EXISTS (SELECT 1 FROM holidays WHERE date = :date AND id IS NOT :exceptId)")
                .bind("date", date.toString())
                .bind("exceptId", exceptId)
                .mapTo(Boolean.class)
                .one());
    }

    /** Adds a holiday of the user's own. */
    public void create(LocalDate date, String name) {
        jdbi.useHandle(handle -> handle
                .createUpdate("INSERT INTO holidays (date, name, source, enabled) VALUES (:date, :name, :source, 1)")
                .bind("date", date.toString())
                .bind("name", name)
                .bind("source", SOURCE_USER)
                .execute());
    }

    public boolean update(long id, LocalDate date, String name, boolean enabled) {
        return jdbi.withHandle(handle -> handle
                .createUpdate("UPDATE holidays SET date = :date, name = :name, enabled = :enabled WHERE id = :id")
                .bind("id", id)
                .bind("date", date.toString())
                .bind("name", name)
                .bind("enabled", enabled)
                .execute()) > 0;
    }

    public Optional<LocalDate> dateOf(long id) {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT date FROM holidays WHERE id = :id")
                .bind("id", id)
                .mapTo(String.class)
                .findOne()
                .map(LocalDate::parse));
    }

    public boolean delete(long id) {
        return jdbi.withHandle(handle -> handle
                .createUpdate("DELETE FROM holidays WHERE id = :id")
                .bind("id", id)
                .execute()) > 0;
    }

    private static void addBankHolidays(Handle handle, int fromYear, int toYear) {
        List<Integer> alreadyAdded = handle
                .createQuery("SELECT year FROM holiday_years_seeded WHERE year BETWEEN :from AND :to")
                .bind("from", fromYear)
                .bind("to", toYear)
                .mapTo(Integer.class)
                .list();
        for (int year = fromYear; year <= toYear; year++) {
            if (alreadyAdded.contains(year)) {
                continue;
            }
            for (Holiday holiday : UsBankHolidays.in(year)) {
                // A date the user already added a holiday of their own on is left as theirs.
                handle.createUpdate("""
                                INSERT INTO holidays (date, name, source, enabled) VALUES (:date, :name, :source, 1)
                                ON CONFLICT (date) DO NOTHING
                                """)
                        .bind("date", holiday.date().toString())
                        .bind("name", holiday.name())
                        .bind("source", SOURCE_US_BANK)
                        .execute();
            }
            handle.createUpdate("INSERT INTO holiday_years_seeded (year) VALUES (:year)")
                    .bind("year", year)
                    .execute();
        }
    }
}
