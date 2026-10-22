package app.budget.server.schedule;

import app.budget.engine.schedule.Frequency;
import app.budget.engine.schedule.ScheduleRule;
import app.budget.engine.schedule.WeekendRule;
import app.budget.server.api.Inputs;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.statement.SqlStatement;

/**
 * Stores {@code schedule_rules} and their {@code manual_dates}. Every method takes the
 * caller's {@link Handle}, so a rule is always saved in the same transaction as whatever owns it.
 */
public final class ScheduleRules {

    private ScheduleRules() {}

    public static long insert(Handle handle, ScheduleRule rule) {
        long id = bind(handle.createQuery("""
                        INSERT INTO schedule_rules
                            (frequency, interval, day_1, day_2, month, start_date, end_date, weekend_rule, active_months, weekdays)
                        VALUES (:frequency, :interval, :day1, :day2, :month, :startDate, :endDate, :weekendRule, :activeMonths, :weekdays)
                        RETURNING id
                        """), rule)
                .mapTo(Long.class)
                .one();
        insertManualDates(handle, id, rule);
        return id;
    }

    public static void update(Handle handle, long id, ScheduleRule rule) {
        bind(handle.createUpdate("""
                        UPDATE schedule_rules
                        SET frequency = :frequency, interval = :interval, day_1 = :day1, day_2 = :day2, month = :month,
                            start_date = :startDate, end_date = :endDate, weekend_rule = :weekendRule, active_months = :activeMonths, weekdays = :weekdays
                        WHERE id = :id
                        """), rule)
                .bind("id", id)
                .execute();
        handle.createUpdate("DELETE FROM manual_dates WHERE schedule_rule_id = :id").bind("id", id).execute();
        insertManualDates(handle, id, rule);
    }

    public static void delete(Handle handle, long id) {
        handle.createUpdate("DELETE FROM schedule_rules WHERE id = :id").bind("id", id).execute();
    }

    public static ScheduleRule load(Handle handle, long id) {
        List<LocalDate> manualDates = handle
                .createQuery("SELECT date FROM manual_dates WHERE schedule_rule_id = :id ORDER BY date")
                .bind("id", id)
                .mapTo(String.class)
                .map(LocalDate::parse)
                .list();
        return handle
                .createQuery("""
                        SELECT frequency, interval, day_1, day_2, month, start_date, end_date, weekend_rule, active_months, weekdays
                        FROM schedule_rules WHERE id = :id
                        """)
                .bind("id", id)
                .map((rs, ctx) -> new ScheduleRule(
                        Frequency.valueOf(rs.getString("frequency").toUpperCase(Locale.ROOT)),
                        rs.getInt("interval"),
                        nullableInt(rs, "day_1"),
                        nullableInt(rs, "day_2"),
                        nullableInt(rs, "month"),
                        LocalDate.parse(rs.getString("start_date")),
                        rs.getString("end_date") == null ? null : LocalDate.parse(rs.getString("end_date")),
                        WeekendRule.valueOf(rs.getString("weekend_rule").toUpperCase(Locale.ROOT)),
                        manualDates,
                        months(rs.getInt("active_months")),
                        weekdays(rs.getInt("weekdays"))))
                .one();
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static <S extends SqlStatement<S>> S bind(S statement, ScheduleRule rule) {
        return statement
                .bind("frequency", Inputs.code(rule.frequency()))
                .bind("interval", rule.interval())
                .bind("day1", rule.day1())
                .bind("day2", rule.day2())
                .bind("month", rule.month())
                .bind("startDate", rule.startDate().toString())
                .bind("endDate", rule.endDate() == null ? null : rule.endDate().toString())
                .bind("weekendRule", Inputs.code(rule.weekendRule()))
                .bind("activeMonths", bits(rule.months()))
                .bind("weekdays", weekdayBits(rule.weekdays()));
    }

    /** One bit a month, January the lowest (migration V29). */
    private static int bits(Set<Month> months) {
        int bits = 0;
        for (Month month : months) {
            bits |= 1 << (month.getValue() - 1);
        }
        return bits;
    }

    /** One bit a day of the week, Monday the lowest (migration V29); none is the start date's own day. */
    private static int weekdayBits(Set<DayOfWeek> weekdays) {
        int bits = 0;
        for (DayOfWeek day : weekdays) {
            bits |= 1 << (day.getValue() - 1);
        }
        return bits;
    }

    private static Set<DayOfWeek> weekdays(int bits) {
        Set<DayOfWeek> weekdays = EnumSet.noneOf(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            if ((bits & (1 << (day.getValue() - 1))) != 0) {
                weekdays.add(day);
            }
        }
        return weekdays;
    }

    private static Set<Month> months(int bits) {
        Set<Month> months = EnumSet.noneOf(Month.class);
        for (Month month : Month.values()) {
            if ((bits & (1 << (month.getValue() - 1))) != 0) {
                months.add(month);
            }
        }
        return months;
    }

    private static void insertManualDates(Handle handle, long id, ScheduleRule rule) {
        for (LocalDate date : rule.manualDates()) {
            handle.createUpdate("INSERT INTO manual_dates (schedule_rule_id, date) VALUES (:id, :date)")
                    .bind("id", id)
                    .bind("date", date.toString())
                    .execute();
        }
    }
}
