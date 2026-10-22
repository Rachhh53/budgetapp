package app.budget.server.cards;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.cards.CardPayment;
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

/** Reads and writes {@code card_settings}, {@code card_statements} and {@code card_promos}. */
public final class CardsRepository {

    private static final String SELECT_SETTINGS = """
            SELECT account_id, statement_close_day, due_day, payment_mode, fixed_payment_amount, autopay,
                   pay_from_account_id, payment_item_id, monthly_estimate
            FROM card_settings
            """;
    private static final String SELECT_STATEMENTS =
            "SELECT id, account_id, close_date, statement_balance, minimum_due, due_date FROM card_statements ";
    private static final String SELECT_PROMOS = """
            SELECT id, account_id, label, kind, rate_bp, start_date, end_date, original_balance, rate_after_bp,
                   deferred_interest, payoff_item_id
            FROM card_promos
            """;

    /**
     * What a card's payment mode is stored as while no recurring payment is tied to it. It sizes
     * nothing until there is one; a fixed amount is not used because that needs an amount to store.
     */
    public static final CardPayment.Mode NO_PAYMENT_YET = CardPayment.Mode.STATEMENT_BALANCE;

    private final Jdbi jdbi;

    public CardsRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /**
     * How a card bills and is paid. Days are days of the month, 1–31, where 31 means the last day.
     * The dates and the estimate are the card's own. How it is paid is a copy of its recurring
     * payment, written here whenever that item is saved, so the card's projection can read it.
     *
     * @param paymentMode          how much the card's own payment pays; {@link #NO_PAYMENT_YET} while it has none
     * @param fixedPaymentCents    that payment's amount when {@code paymentMode} is {@code FIXED}
     * @param payFromAccountId     the account that payment comes out of; {@code null} while there is none
     * @param paymentItemId        the recurring item that is this card's own payment; {@code null}
     *                             until one is added, or once that item is deleted
     * @param monthlyEstimateCents what a statement month's spending is expected to come to, for a card
     *                             whose charges are not entered one by one; {@code null} otherwise
     */
    public record Settings(
            long accountId,
            int statementCloseDay,
            int dueDay,
            CardPayment.Mode paymentMode,
            Long fixedPaymentCents,
            boolean autopay,
            Long payFromAccountId,
            Long paymentItemId,
            Long monthlyEstimateCents) {}

    /** A statement as it arrived. {@code minimumDueCents} is {@code null} when it was not entered. */
    public record Statement(long id, long accountId, LocalDate closeDate, long balanceCents, Long minimumDueCents, LocalDate dueDate) {}

    public enum PromoKind { BALANCE_TRANSFER, PURCHASE, OTHER }

    /**
     * A promotional rate, on a set amount or on everything the card owes.
     *
     * @param originalBalanceCents the amount it is on; {@code null} for the whole balance
     * @param rateAfterBp  the rate on what is left afterwards; {@code null} for the card's regular APR
     * @param payoffItemId the recurring payment the payoff helper added for it; {@code null} when none
     */
    public record Promo(
            long id,
            long accountId,
            String label,
            PromoKind kind,
            int rateBp,
            LocalDate startDate,
            LocalDate endDate,
            Long originalBalanceCents,
            Integer rateAfterBp,
            boolean deferredInterest,
            Long payoffItemId) {

        /** This promotion as the engine works with it. */
        public app.budget.engine.cards.Promo terms() {
            return new app.budget.engine.cards.Promo(
                    id,
                    BasisPoints.of(rateBp),
                    startDate,
                    endDate,
                    originalBalanceCents == null ? null : Money.ofCents(originalBalanceCents),
                    rateAfterBp == null ? null : BasisPoints.of(rateAfterBp),
                    deferredInterest);
        }
    }

    /** What the user can set on a promotion. */
    public record PromoFields(
            String label,
            PromoKind kind,
            int rateBp,
            LocalDate startDate,
            LocalDate endDate,
            Long originalBalanceCents,
            Integer rateAfterBp,
            boolean deferredInterest) {}

    public Optional<Settings> settings(long accountId) {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT_SETTINGS + "WHERE account_id = :id")
                .bind("id", accountId)
                .map((rs, ctx) -> settings(rs))
                .findOne());
    }

    /** The settings of every card that has them, by account. */
    public Map<Long, Settings> allSettings() {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT_SETTINGS)
                .map((rs, ctx) -> settings(rs))
                .stream()
                .collect(Collectors.toMap(Settings::accountId, found -> found)));
    }

    /**
     * How each card's minimum payment is estimated, by account, for every card that has settings.
     * Kept beside the settings rather than in them: it is changed on its own, and saving the
     * settings leaves it as it is.
     */
    public Map<Long, CardPayment.MinimumRule> minimumRules() {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT account_id, minimum_share_bp, minimum_floor, minimum_plus_interest FROM card_settings")
                .map((rs, ctx) -> Map.entry(
                        rs.getLong("account_id"),
                        new CardPayment.MinimumRule(
                                rs.getInt("minimum_share_bp"),
                                Money.ofCents(rs.getLong("minimum_floor")),
                                rs.getBoolean("minimum_plus_interest"))))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    /** @return false when the card has no settings yet, so there was nothing to change */
    public boolean saveMinimumRule(long accountId, CardPayment.MinimumRule rule) {
        return jdbi.withHandle(handle -> handle
                .createUpdate("""
                        UPDATE card_settings
                        SET minimum_share_bp = :shareBp, minimum_floor = :floor, minimum_plus_interest = :plusInterest
                        WHERE account_id = :accountId
                        """)
                .bind("accountId", accountId)
                .bind("shareBp", rule.shareBp())
                .bind("floor", rule.floor().cents())
                .bind("plusInterest", rule.plusInterest())
                .execute()) > 0;
    }

    /**
     * Each card's cash-back rate in basis points (spec §5.6, migration V33), for every card that has one.
     * Kept beside the settings like the minimum rule: saving the card's dates leaves it as it is.
     */
    public Map<Long, Integer> rewardsRates() {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT account_id, rewards_bp FROM card_settings WHERE rewards_bp IS NOT NULL")
                .map((rs, ctx) -> Map.entry(rs.getLong("account_id"), rs.getInt("rewards_bp")))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    /**
     * Sets a card's cash-back rate, or with {@code null} takes its rewards away.
     *
     * @return false when the card has no settings yet, so there was nothing to change
     */
    public boolean saveRewardsRate(long accountId, Integer rateBp) {
        return jdbi.withHandle(handle -> handle
                .createUpdate("UPDATE card_settings SET rewards_bp = :rateBp WHERE account_id = :accountId")
                .bind("accountId", accountId)
                .bind("rateBp", rateBp)
                .execute()) > 0;
    }

    /**
     * How a card's rewards are used once they build up (migration V35). Only cards whose rewards build up have one.
     *
     * @param yearlyMonth    all built up goes on at the statement closing in this month every year; {@code null} for no such rule
     * @param thresholdCents all built up goes on once at least this has built up; {@code null} for no such rule
     * @param startCents     what had built up on {@code startDate}; {@code null} with it, to start from nothing
     */
    public record BuildUp(Integer yearlyMonth, Long thresholdCents, Long startCents, LocalDate startDate) {}

    /** Every card whose rewards build up instead of coming off each statement, by card. */
    public Map<Long, BuildUp> rewardsBuildUps() {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        SELECT account_id, rewards_yearly_month, rewards_threshold_cents, rewards_start_cents, rewards_start_date
                        FROM card_settings WHERE rewards_build_up = 1
                        """)
                .map((rs, ctx) -> {
                    int month = rs.getInt("rewards_yearly_month");
                    Integer yearlyMonth = rs.wasNull() ? null : month;
                    long threshold = rs.getLong("rewards_threshold_cents");
                    Long thresholdCents = rs.wasNull() ? null : threshold;
                    long start = rs.getLong("rewards_start_cents");
                    Long startCents = rs.wasNull() ? null : start;
                    String startDate = rs.getString("rewards_start_date");
                    return Map.entry(rs.getLong("account_id"),
                            new BuildUp(yearlyMonth, thresholdCents, startCents, startDate == null ? null : LocalDate.parse(startDate)));
                })
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    /**
     * Sets how a card's rewards are used: with {@code buildUp}, they build up; with {@code null}, they come off each statement.
     *
     * @return false when the card has no settings yet
     */
    public boolean saveRewardsBuildUp(long accountId, BuildUp buildUp) {
        return jdbi.withHandle(handle -> handle
                .createUpdate("""
                        UPDATE card_settings SET rewards_build_up = :on, rewards_yearly_month = :month,
                            rewards_threshold_cents = :threshold, rewards_start_cents = :startCents, rewards_start_date = :startDate
                        WHERE account_id = :accountId
                        """)
                .bind("accountId", accountId)
                .bind("on", buildUp == null ? 0 : 1)
                .bind("month", buildUp == null ? null : buildUp.yearlyMonth())
                .bind("threshold", buildUp == null ? null : buildUp.thresholdCents())
                .bind("startCents", buildUp == null ? null : buildUp.startCents())
                .bind("startDate", buildUp == null || buildUp.startDate() == null ? null : buildUp.startDate().toString())
                .execute()) > 0;
    }

    /**
     * A statement credit asked for from built-up rewards.
     *
     * @param amountCents {@code null} for everything built up by then
     */
    public record Redemption(long id, long accountId, LocalDate date, Long amountCents, String note) {}

    /** Every card's redemptions, by card, earliest first. */
    public Map<Long, List<Redemption>> allRedemptions() {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT id, account_id, date, amount_cents, note FROM card_reward_redemptions ORDER BY date, id")
                .map((rs, ctx) -> {
                    long amount = rs.getLong("amount_cents");
                    Long amountCents = rs.wasNull() ? null : amount;
                    return new Redemption(rs.getLong("id"), rs.getLong("account_id"), LocalDate.parse(rs.getString("date")), amountCents,
                            rs.getString("note"));
                })
                .stream()
                .collect(Collectors.groupingBy(Redemption::accountId)));
    }

    public Optional<Redemption> findRedemption(long accountId, long id) {
        return Optional.ofNullable(allRedemptions().getOrDefault(accountId, List.of()).stream()
                .filter(redemption -> redemption.id() == id)
                .findFirst()
                .orElse(null));
    }

    public long addRedemption(long accountId, LocalDate date, Long amountCents, String note) {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        INSERT INTO card_reward_redemptions (account_id, date, amount_cents, note) VALUES (:accountId, :date, :amount, :note)
                        RETURNING id
                        """)
                .bind("accountId", accountId)
                .bind("date", date.toString())
                .bind("amount", amountCents)
                .bind("note", note)
                .mapTo(Long.class)
                .one());
    }

    public void updateRedemption(long accountId, long id, LocalDate date, Long amountCents, String note) {
        jdbi.useHandle(handle -> handle
                .createUpdate("""
                        UPDATE card_reward_redemptions SET date = :date, amount_cents = :amount, note = :note
                        WHERE id = :id AND account_id = :accountId
                        """)
                .bind("accountId", accountId)
                .bind("id", id)
                .bind("date", date.toString())
                .bind("amount", amountCents)
                .bind("note", note)
                .execute());
    }

    /** False when there was no such redemption on the card. */
    public boolean deleteRedemption(long accountId, long id) {
        return jdbi.withHandle(handle -> handle
                .createUpdate("DELETE FROM card_reward_redemptions WHERE id = :id AND account_id = :accountId")
                .bind("accountId", accountId)
                .bind("id", id)
                .execute()) > 0;
    }

    /**
     * A statement's rewards typed over the estimate.
     *
     * @param closeDate      the projected close they are added on
     * @param estimatedCents what was estimated when it was first typed over
     */
    public record TypedRewards(LocalDate closeDate, long amountCents, long estimatedCents) {}

    /** Every month of rewards typed over, by card and then by close date. */
    public Map<Long, Map<LocalDate, TypedRewards>> allTypedRewards() {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT account_id, close_date, amount, estimated_amount FROM card_reward_overrides")
                .map((rs, ctx) -> Map.entry(rs.getLong("account_id"), new TypedRewards(
                        LocalDate.parse(rs.getString("close_date")), rs.getLong("amount"), rs.getLong("estimated_amount"))))
                .stream()
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey, Collectors.toMap(entry -> entry.getValue().closeDate(), Map.Entry::getValue))));
    }

    /** Types over one statement's rewards. What was estimated is only stored the first time. */
    public void saveTypedRewards(long accountId, LocalDate closeDate, long amountCents, long estimatedCents) {
        jdbi.useHandle(handle -> handle
                .createUpdate("""
                        INSERT INTO card_reward_overrides (account_id, close_date, amount, estimated_amount)
                        VALUES (:accountId, :closeDate, :amount, :estimated)
                        ON CONFLICT (account_id, close_date) DO UPDATE SET amount = excluded.amount
                        """)
                .bind("accountId", accountId)
                .bind("closeDate", closeDate.toString())
                .bind("amount", amountCents)
                .bind("estimated", estimatedCents)
                .execute());
    }

    /** Puts the estimate back. False when nothing was typed over on that close. */
    public boolean deleteTypedRewards(long accountId, LocalDate closeDate) {
        return jdbi.withHandle(handle -> handle
                .createUpdate("DELETE FROM card_reward_overrides WHERE account_id = :accountId AND close_date = :closeDate")
                .bind("accountId", accountId)
                .bind("closeDate", closeDate.toString())
                .execute()) > 0;
    }

    public void saveSettings(Settings settings) {
        jdbi.useHandle(handle -> handle
                .createUpdate("""
                        INSERT INTO card_settings
                            (account_id, statement_close_day, due_day, payment_mode, fixed_payment_amount, autopay,
                             pay_from_account_id, payment_item_id, monthly_estimate)
                        VALUES (:accountId, :closeDay, :dueDay, :mode, :fixed, :autopay, :payFrom, :itemId, :estimate)
                        ON CONFLICT (account_id) DO UPDATE SET
                            statement_close_day = excluded.statement_close_day, due_day = excluded.due_day,
                            payment_mode = excluded.payment_mode, fixed_payment_amount = excluded.fixed_payment_amount,
                            autopay = excluded.autopay, pay_from_account_id = excluded.pay_from_account_id,
                            payment_item_id = excluded.payment_item_id, monthly_estimate = excluded.monthly_estimate
                        """)
                .bind("accountId", settings.accountId())
                .bind("closeDay", settings.statementCloseDay())
                .bind("dueDay", settings.dueDay())
                .bind("mode", Inputs.code(settings.paymentMode()))
                .bind("fixed", settings.fixedPaymentCents())
                .bind("autopay", settings.autopay())
                .bind("payFrom", settings.payFromAccountId())
                .bind("itemId", settings.paymentItemId())
                .bind("estimate", settings.monthlyEstimateCents())
                .execute());
    }

    /** A card's statements, newest first. */
    public List<Statement> statements(long accountId) {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT_STATEMENTS + "WHERE account_id = :id ORDER BY close_date DESC")
                .bind("id", accountId)
                .map((rs, ctx) -> statement(rs))
                .list());
    }

    /** Every statement entered, by card, oldest first. */
    public Map<Long, List<Statement>> allStatements() {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT_STATEMENTS + "ORDER BY close_date")
                .map((rs, ctx) -> statement(rs))
                .stream()
                .collect(Collectors.groupingBy(Statement::accountId)));
    }

    /** Records a statement. Entering the same closing date again replaces what was there. */
    public void saveStatement(long accountId, LocalDate closeDate, long balanceCents, Long minimumDueCents, LocalDate dueDate) {
        jdbi.useHandle(handle -> handle
                .createUpdate("""
                        INSERT INTO card_statements (account_id, close_date, statement_balance, minimum_due, due_date)
                        VALUES (:accountId, :closeDate, :balance, :minimum, :dueDate)
                        ON CONFLICT (account_id, close_date) DO UPDATE SET
                            statement_balance = excluded.statement_balance, minimum_due = excluded.minimum_due,
                            due_date = excluded.due_date
                        """)
                .bind("accountId", accountId)
                .bind("closeDate", closeDate.toString())
                .bind("balance", balanceCents)
                .bind("minimum", minimumDueCents)
                .bind("dueDate", dueDate.toString())
                .execute());
    }

    /** What changing a statement came to. */
    public enum StatementChange {
        DONE,
        /** No such statement on that card. */
        NOT_FOUND,
        /** Another statement of the card already closed that day. */
        SAME_DAY
    }

    /**
     * Changes a statement in place, closing date included, so what is attached to it stays with it (Rachel,
     * 2026-10-21: "I want the ability to edit basically everything I enter. namely statements").
     */
    public StatementChange updateStatement(
            long accountId, long statementId, LocalDate closeDate, long balanceCents, Long minimumDueCents, LocalDate dueDate) {
        return jdbi.inTransaction(handle -> {
            boolean found = handle.createQuery("SELECT count(*) FROM card_statements WHERE id = :id AND account_id = :accountId")
                    .bind("id", statementId)
                    .bind("accountId", accountId)
                    .mapTo(Integer.class)
                    .one() > 0;
            if (!found) {
                return StatementChange.NOT_FOUND;
            }
            boolean taken = handle.createQuery("""
                            SELECT count(*) FROM card_statements WHERE account_id = :accountId AND close_date = :closeDate AND id <> :id
                            """)
                    .bind("accountId", accountId)
                    .bind("closeDate", closeDate.toString())
                    .bind("id", statementId)
                    .mapTo(Integer.class)
                    .one() > 0;
            if (taken) {
                return StatementChange.SAME_DAY;
            }
            handle.createUpdate("""
                            UPDATE card_statements
                            SET close_date = :closeDate, statement_balance = :balance, minimum_due = :minimum, due_date = :dueDate
                            WHERE id = :id AND account_id = :accountId
                            """)
                    .bind("closeDate", closeDate.toString())
                    .bind("balance", balanceCents)
                    .bind("minimum", minimumDueCents)
                    .bind("dueDate", dueDate.toString())
                    .bind("id", statementId)
                    .bind("accountId", accountId)
                    .execute();
            return StatementChange.DONE;
        });
    }

    public boolean deleteStatement(long accountId, long statementId) {
        return jdbi.withHandle(handle -> handle
                .createUpdate("DELETE FROM card_statements WHERE id = :id AND account_id = :accountId")
                .bind("id", statementId)
                .bind("accountId", accountId)
                .execute()) > 0;
    }

    /** A card's promotions, the one that ends first at the top. */
    public List<Promo> promos(long accountId) {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT_PROMOS + "WHERE account_id = :id ORDER BY end_date, id")
                .bind("id", accountId)
                .map((rs, ctx) -> promo(rs))
                .list());
    }

    /** Every promotion, by card. */
    public Map<Long, List<Promo>> allPromos() {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT_PROMOS + "ORDER BY end_date, id")
                .map((rs, ctx) -> promo(rs))
                .stream()
                .collect(Collectors.groupingBy(Promo::accountId)));
    }

    public Optional<Promo> findPromo(long accountId, long promoId) {
        return jdbi.withHandle(handle -> handle
                .createQuery(SELECT_PROMOS + "WHERE id = :id AND account_id = :accountId")
                .bind("id", promoId)
                .bind("accountId", accountId)
                .map((rs, ctx) -> promo(rs))
                .findOne());
    }

    /** Adds a promotion and returns its id. */
    public long createPromo(long accountId, PromoFields fields) {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        INSERT INTO card_promos
                            (account_id, label, kind, rate_bp, start_date, end_date, original_balance, rate_after_bp, deferred_interest)
                        VALUES (:accountId, :label, :kind, :rateBp, :startDate, :endDate, :original, :rateAfterBp, :deferred)
                        RETURNING id
                        """)
                .bind("accountId", accountId)
                .bind("label", fields.label())
                .bind("kind", Inputs.code(fields.kind()))
                .bind("rateBp", fields.rateBp())
                .bind("startDate", fields.startDate().toString())
                .bind("endDate", fields.endDate().toString())
                .bind("original", fields.originalBalanceCents())
                .bind("rateAfterBp", fields.rateAfterBp())
                .bind("deferred", fields.deferredInterest())
                .mapTo(Long.class)
                .one());
    }

    /** @return false when the card has no such promotion */
    public boolean updatePromo(long accountId, long promoId, PromoFields fields) {
        return jdbi.withHandle(handle -> handle
                .createUpdate("""
                        UPDATE card_promos
                        SET label = :label, kind = :kind, rate_bp = :rateBp, start_date = :startDate, end_date = :endDate,
                            original_balance = :original, rate_after_bp = :rateAfterBp, deferred_interest = :deferred
                        WHERE id = :id AND account_id = :accountId
                        """)
                .bind("id", promoId)
                .bind("accountId", accountId)
                .bind("label", fields.label())
                .bind("kind", Inputs.code(fields.kind()))
                .bind("rateBp", fields.rateBp())
                .bind("startDate", fields.startDate().toString())
                .bind("endDate", fields.endDate().toString())
                .bind("original", fields.originalBalanceCents())
                .bind("rateAfterBp", fields.rateAfterBp())
                .bind("deferred", fields.deferredInterest())
                .execute()) > 0;
    }

    public boolean deletePromo(long accountId, long promoId) {
        return jdbi.withHandle(handle -> handle
                .createUpdate("DELETE FROM card_promos WHERE id = :id AND account_id = :accountId")
                .bind("id", promoId)
                .bind("accountId", accountId)
                .execute()) > 0;
    }

    /** Remembers the recurring payment the payoff helper added for a promotion. */
    public void setPayoffItem(long promoId, long itemId) {
        jdbi.useHandle(handle -> handle
                .createUpdate("UPDATE card_promos SET payoff_item_id = :itemId WHERE id = :id")
                .bind("id", promoId)
                .bind("itemId", itemId)
                .execute());
    }

    private static Settings settings(ResultSet rs) throws SQLException {
        return new Settings(
                rs.getLong("account_id"),
                rs.getInt("statement_close_day"),
                rs.getInt("due_day"),
                CardPayment.Mode.valueOf(rs.getString("payment_mode").toUpperCase(Locale.ROOT)),
                nullableLong(rs, "fixed_payment_amount"),
                rs.getBoolean("autopay"),
                nullableLong(rs, "pay_from_account_id"),
                nullableLong(rs, "payment_item_id"),
                nullableLong(rs, "monthly_estimate"));
    }

    private static Statement statement(ResultSet rs) throws SQLException {
        return new Statement(
                rs.getLong("id"),
                rs.getLong("account_id"),
                LocalDate.parse(rs.getString("close_date")),
                rs.getLong("statement_balance"),
                nullableLong(rs, "minimum_due"),
                LocalDate.parse(rs.getString("due_date")));
    }

    private static Promo promo(ResultSet rs) throws SQLException {
        int rateAfter = rs.getInt("rate_after_bp");
        Integer rateAfterBp = rs.wasNull() ? null : rateAfter;
        return new Promo(
                rs.getLong("id"),
                rs.getLong("account_id"),
                rs.getString("label"),
                PromoKind.valueOf(rs.getString("kind").toUpperCase(Locale.ROOT)),
                rs.getInt("rate_bp"),
                LocalDate.parse(rs.getString("start_date")),
                LocalDate.parse(rs.getString("end_date")),
                nullableLong(rs, "original_balance"),
                rateAfterBp,
                rs.getBoolean("deferred_interest"),
                nullableLong(rs, "payoff_item_id"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
