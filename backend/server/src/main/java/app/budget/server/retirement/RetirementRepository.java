package app.budget.server.retirement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/**
 * Reads and writes retirement planning (spec §4.12): the birth date, which accounts count and how
 * their money is taxed, and the scenarios with their other income and one-offs. Nothing worked out
 * is kept: every figure comes from the accounts as they stand (§5.11).
 */
public final class RetirementRepository {

    private final Jdbi jdbi;
    private final Clock clock;

    public RetirementRepository(Jdbi jdbi, Clock clock) {
        this.jdbi = jdbi;
        this.clock = clock;
    }

    /**
     * Other money coming in, a year in today's dollars, from an age on.
     *
     * @param socialSecurity true for Social Security, which is taxed by its own formula
     * @param countedBp      for anything else, how much of it is taxed income: a pension 10000, a disability benefit 0
     */
    public record Income(String name, long yearlyCents, int startAge, boolean socialSecurity, int countedBp) {}

    /** Money in (+) or out (−) once, at an age, in today's dollars. */
    public record OneOff(String name, long amountCents, int age) {}

    /**
     * Everything a scenario is made of, as stored.
     *
     * @param growthBp     the expected growth a year, for every account
     * @param growthLowBp  a slower growth shown beside it
     * @param growthHighBp a faster growth shown beside it
     * @param raiseBp      how much more goes in each year than the year before; 0 for none
     * @param extraInto {@code traditional}, {@code roth} or {@code taxable}
     * @param retireAge {@code null} when no age is chosen
     */
    public record Fields(
            String name,
            long salaryCents,
            int inflationBp,
            int withdrawalBp,
            int taxBp,
            long healthBeforeCents,
            long healthAfterCents,
            int healthInflationBp,
            int growthBp,
            int growthLowBp,
            int growthHighBp,
            int raiseBp,
            long extraMonthlyCents,
            String extraInto,
            Integer retireAge,
            boolean compared,
            Taxes taxes,
            List<Income> incomes,
            List<OneOff> oneOffs) {

        public Fields {
            Objects.requireNonNull(taxes);
            incomes = List.copyOf(incomes);
            oneOffs = List.copyOf(oneOffs);
        }
    }

    public record Scenario(long id, Fields fields) {}

    /**
     * How a scenario taxes retirement (§5.12).
     *
     * @param mode            {@code flat} for the one rate on traditional money, {@code worked_out} for the rules
     * @param filing          {@code single} or {@code joint}
     * @param medicarePeople  1, or 2 with a spouse
     * @param partDPlanCents  a Part D plan, a month a person
     * @param supplementCents Medigap or Medicare Advantage, a month a person
     * @param workingMagiCents income a year while working; {@code null} for the pay Budget knows
     * @param magiOverrideCents income typed for every year in retirement; {@code null} to work it out
     * @param strategy        {@code none}, {@code bracket:N} or {@code medicare:N}; {@code null} for the cheapest
     */
    public record Taxes(
            String mode,
            String filing,
            int medicarePeople,
            int stateTaxBp,
            int gainsShareBp,
            long partDPlanCents,
            long supplementCents,
            Long workingMagiCents,
            Long magiOverrideCents,
            String strategy) {

        /** As a scenario has them before any is set: the flat rate, filing alone. */
        public static final Taxes FLAT = new Taxes("flat", "single", 1, 0, 5000, 0, 0, null, null, null);
    }

    public Optional<LocalDate> birthDate() {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT birth_date FROM retirement_profile WHERE id = 1")
                .mapTo(String.class)
                .findOne()
                .map(LocalDate::parse));
    }

    public void setBirthDate(LocalDate birthDate) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        INSERT INTO retirement_profile (id, birth_date) VALUES (1, :birthDate)
                        ON CONFLICT (id) DO UPDATE SET birth_date = excluded.birth_date
                        """)
                .bind("birthDate", birthDate.toString())
                .execute());
    }

    public Optional<LocalDate> spouseBirthDate() {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT spouse_birth_date FROM retirement_profile WHERE id = 1")
                .mapTo(String.class)
                .findOne()
                .filter(Objects::nonNull)
                .map(LocalDate::parse));
    }

    /**
     * @param spouseBirthDate {@code null} for no spouse
     * @return false when there is no birth date yet to go with it
     */
    public boolean setSpouseBirthDate(LocalDate spouseBirthDate) {
        return jdbi.withHandle(handle -> handle.createUpdate("UPDATE retirement_profile SET spouse_birth_date = :date WHERE id = 1")
                .bind("date", spouseBirthDate == null ? null : spouseBirthDate.toString())
                .execute()) > 0;
    }

    /** The tax and Medicare figures as changed, as their JSON; empty for the built-in ones. */
    public Optional<String> taxFigures() {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT figures FROM retirement_tax_figures WHERE id = 1")
                .mapTo(String.class)
                .findOne());
    }

    /** @param json {@code null} to go back to the built-in figures */
    public void setTaxFigures(String json) {
        jdbi.useHandle(handle -> {
            if (json == null) {
                handle.createUpdate("DELETE FROM retirement_tax_figures WHERE id = 1").execute();
                return;
            }
            handle.createUpdate("""
                            INSERT INTO retirement_tax_figures (id, figures, updated_at) VALUES (1, :figures, :now)
                            ON CONFLICT (id) DO UPDATE SET figures = excluded.figures, updated_at = excluded.updated_at
                            """)
                    .bind("figures", json)
                    .bind("now", clock.instant().toString())
                    .execute();
        });
    }

    /** How each counted account's money is taxed, by account id; an account not counted isn't listed. */
    public Map<Long, String> taxTypes() {
        Map<Long, String> types = new HashMap<>();
        jdbi.useHandle(handle -> handle
                .createQuery("SELECT id, retirement_tax_type FROM accounts WHERE retirement_tax_type IS NOT NULL")
                .map((rs, ctx) -> Map.entry(rs.getLong("id"), rs.getString("retirement_tax_type")))
                .forEach(entry -> types.put(entry.getKey(), entry.getValue())));
        return types;
    }

    /** @param taxType {@code null} to stop counting the account */
    public void setTaxType(long accountId, String taxType) {
        jdbi.useHandle(handle -> handle.createUpdate("UPDATE accounts SET retirement_tax_type = :type WHERE id = :id")
                .bind("type", taxType)
                .bind("id", accountId)
                .execute());
    }

    /** Oldest first. */
    public List<Scenario> scenarios() {
        return jdbi.withHandle(handle -> {
            List<Long> ids = handle.createQuery("SELECT id FROM retirement_scenarios ORDER BY id").mapTo(Long.class).list();
            List<Scenario> scenarios = new ArrayList<>();
            for (long id : ids) {
                read(handle, id).ifPresent(scenarios::add);
            }
            return scenarios;
        });
    }

    public Optional<Scenario> find(long id) {
        return jdbi.withHandle(handle -> read(handle, id));
    }

    public int count() {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT count(*) FROM retirement_scenarios").mapTo(Integer.class).one());
    }

    /** How many are in the comparison, leaving out {@code exceptId} (the one being changed); {@code null} counts them all. */
    public int comparedCount(Long exceptId) {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT count(*) FROM retirement_scenarios WHERE compared = 1 AND id IS NOT :except")
                .bind("except", exceptId)
                .mapTo(Integer.class)
                .one());
    }

    public long create(Fields fields) {
        return jdbi.inTransaction(handle -> {
            long id = handle.createQuery("""
                            INSERT INTO retirement_scenarios (name, salary_cents, inflation_bp, withdrawal_bp, tax_bp,
                                health_before_cents, health_after_cents, health_inflation_bp, growth_bp, growth_low_bp, growth_high_bp, raise_bp, extra_monthly_cents,
                                extra_into, retire_age, compared, tax_mode, filing, medicare_people, state_tax_bp, gains_share_bp,
                                part_d_plan_cents, supplement_cents, working_magi_cents, magi_override_cents, strategy, created_at)
                            VALUES (:name, :salaryCents, :inflationBp, :withdrawalBp, :taxBp, :healthBeforeCents,
                                :healthAfterCents, :healthInflationBp, :growthBp, :growthLowBp, :growthHighBp, :raiseBp, :extraMonthlyCents, :extraInto, :retireAge,
                                :compared, :mode, :filing, :medicarePeople, :stateTaxBp, :gainsShareBp, :partDPlanCents, :supplementCents,
                                :workingMagiCents, :magiOverrideCents, :strategy, :now)
                            RETURNING id
                            """)
                    .bindMethods(fields)
                    .bindMethods(fields.taxes())
                    .bind("now", clock.instant().toString())
                    .mapTo(Long.class)
                    .one();
            writeParts(handle, id, fields);
            return id;
        });
    }

    /** @return false when there is no such scenario */
    public boolean update(long id, Fields fields) {
        return jdbi.inTransaction(handle -> {
            int changed = handle.createUpdate("""
                            UPDATE retirement_scenarios SET name = :name, salary_cents = :salaryCents,
                                inflation_bp = :inflationBp, withdrawal_bp = :withdrawalBp, tax_bp = :taxBp,
                                health_before_cents = :healthBeforeCents, health_after_cents = :healthAfterCents,
                                health_inflation_bp = :healthInflationBp, growth_bp = :growthBp, growth_low_bp = :growthLowBp,
                                growth_high_bp = :growthHighBp, raise_bp = :raiseBp,
                                extra_monthly_cents = :extraMonthlyCents, extra_into = :extraInto, retire_age = :retireAge,
                                compared = :compared, tax_mode = :mode, filing = :filing, medicare_people = :medicarePeople,
                                state_tax_bp = :stateTaxBp, gains_share_bp = :gainsShareBp, part_d_plan_cents = :partDPlanCents,
                                supplement_cents = :supplementCents, working_magi_cents = :workingMagiCents,
                                magi_override_cents = :magiOverrideCents, strategy = :strategy
                            WHERE id = :id
                            """)
                    .bindMethods(fields)
                    .bindMethods(fields.taxes())
                    .bind("id", id)
                    .execute();
            if (changed == 0) {
                return false;
            }
            // Its other income and one-offs are sent whole, so they replace what was there.
            handle.createUpdate("DELETE FROM retirement_income WHERE scenario_id = :id").bind("id", id).execute();
            handle.createUpdate("DELETE FROM retirement_one_offs WHERE scenario_id = :id").bind("id", id).execute();
            writeParts(handle, id, fields);
            return true;
        });
    }

    /** Its other income and one-offs go with it. @return false when there is no such scenario */
    public boolean delete(long id) {
        return jdbi.withHandle(handle -> handle.createUpdate("DELETE FROM retirement_scenarios WHERE id = :id")
                .bind("id", id)
                .execute()) > 0;
    }

    private static void writeParts(Handle handle, long id, Fields fields) {
        for (Income income : fields.incomes()) {
            handle.createUpdate("""
                            INSERT INTO retirement_income (scenario_id, name, yearly_cents, start_age, social_security, counted_bp)
                            VALUES (:id, :name, :yearlyCents, :startAge, :socialSecurity, :countedBp)
                            """)
                    .bindMethods(income)
                    .bind("id", id)
                    .execute();
        }
        for (OneOff oneOff : fields.oneOffs()) {
            handle.createUpdate("INSERT INTO retirement_one_offs (scenario_id, name, amount_cents, age) VALUES (:id, :name, :amountCents, :age)")
                    .bindMethods(oneOff)
                    .bind("id", id)
                    .execute();
        }
    }

    private static Optional<Scenario> read(Handle handle, long id) {
        List<Income> incomes = handle.createQuery("""
                        SELECT name, yearly_cents, start_age, social_security, counted_bp FROM retirement_income
                        WHERE scenario_id = :id ORDER BY start_age, id
                        """)
                .bind("id", id)
                .map((rs, ctx) -> new Income(rs.getString("name"), rs.getLong("yearly_cents"), rs.getInt("start_age"),
                        rs.getInt("social_security") == 1, rs.getInt("counted_bp")))
                .list();
        List<OneOff> oneOffs = handle.createQuery("""
                        SELECT name, amount_cents, age FROM retirement_one_offs WHERE scenario_id = :id ORDER BY age, id
                        """)
                .bind("id", id)
                .map((rs, ctx) -> new OneOff(rs.getString("name"), rs.getLong("amount_cents"), rs.getInt("age")))
                .list();
        return handle.createQuery("SELECT * FROM retirement_scenarios WHERE id = :id")
                .bind("id", id)
                .map((rs, ctx) -> new Scenario(rs.getLong("id"), new Fields(
                        rs.getString("name"),
                        rs.getLong("salary_cents"),
                        rs.getInt("inflation_bp"),
                        rs.getInt("withdrawal_bp"),
                        rs.getInt("tax_bp"),
                        rs.getLong("health_before_cents"),
                        rs.getLong("health_after_cents"),
                        rs.getInt("health_inflation_bp"),
                        rs.getInt("growth_bp"),
                        rs.getInt("growth_low_bp"),
                        rs.getInt("growth_high_bp"),
                        rs.getInt("raise_bp"),
                        rs.getLong("extra_monthly_cents"),
                        rs.getString("extra_into"),
                        nullableInt(rs, "retire_age"),
                        rs.getInt("compared") == 1,
                        new Taxes(
                                rs.getString("tax_mode"),
                                rs.getString("filing"),
                                rs.getInt("medicare_people"),
                                rs.getInt("state_tax_bp"),
                                rs.getInt("gains_share_bp"),
                                rs.getLong("part_d_plan_cents"),
                                rs.getLong("supplement_cents"),
                                nullableLong(rs, "working_magi_cents"),
                                nullableLong(rs, "magi_override_cents"),
                                rs.getString("strategy")),
                        incomes,
                        oneOffs)))
                .findOne();
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
