package app.budget.server.entries;

import app.budget.engine.Money;
import app.budget.engine.reports.Reimbursement;
import app.budget.engine.reports.Reimbursement.Share;
import app.budget.engine.reports.Reimbursement.Status;
import app.budget.server.api.Inputs;
import app.budget.server.items.ReimbursementsRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.SqlStatement;

/** Reads and writes one-off entries (money in, money out, transfers), their tags and their bucket splits. */
public final class EntriesRepository {

    private static final String SELECT = """
            SELECT id, kind, date, amount, is_estimate, description, payee, category_id, account_id, to_account_id, note,
                   check_number, reimbursement_status, reimbursement_submitted_on, reimbursement_to, reimburses_entry_id,
                   reimburses_payment_id, reimbursement_amount, reimbursement_into_account_id, reimbursement_expected_on,
                   reimbursement_from_account_id
            FROM entries
            """;

    private final Jdbi jdbi;
    private final Clock clock;

    public EntriesRepository(Jdbi jdbi, Clock clock) {
        this.jdbi = jdbi;
        this.clock = clock;
    }

    /** Part of an entry set aside in a bucket. Amounts are whole cents, always positive. */
    public record Split(long bucketId, long amountCents) {}

    /**
     * A one-off entry as stored and as the API returns it. Amounts are whole cents, always positive:
     * the kind says which way the money goes.
     *
     * @param accountId    the account it leaves (money out, transfer) or arrives in (money in)
     * @param toAccountId  where a transfer goes; {@code null} otherwise
     * @param bucketSplits how much of the money arriving goes into which buckets; the rest stays unassigned
     * @param checkNumber  the check it was paid with, when it left a checking account that way; {@code null} otherwise
     * @param reimbursement where money going out stands on being paid back; {@code null} for money in and transfers
     * @param reimbursesEntryId the expense this money coming in pays back; {@code null} otherwise
     * @param reimbursesPaymentId the payment of a recurring item this money coming in pays back (migration V46); {@code null}
     *                     otherwise. It pays back one or the other, never both.
     * @param fileCount    how many receipts and documents are attached
     */
    public record Entry(
            long id,
            EntryKind kind,
            LocalDate date,
            long amountCents,
            boolean estimate,
            String description,
            String payee,
            Long categoryId,
            long accountId,
            Long toAccountId,
            String note,
            List<Long> tagIds,
            List<Split> bucketSplits,
            String checkNumber,
            Repayment reimbursement,
            Long reimbursesEntryId,
            Long reimbursesPaymentId,
            int fileCount) {}

    /**
     * Where an expense stands on being paid back (spec §4.7), and when what is still owed is planned to come back.
     *
     * @param submittedOn     the day it was sent in; {@code null} when not said
     * @param to              who pays it back ("HSA", "Work"); {@code null} when not said
     * @param paidBackCents   what the money-in entries that pay it back add up to
     * @param reimbursedCents how much of what comes back counts as having come back
     * @param owedCents       how much is still to come, sent in or not
     * @param amountCents     what comes back: all of the expense unless less was set (Rachel, 2026-10-24: "partials are
     *                        possible")
     * @param intoAccountId   the account it is planned to come back into; {@code null} when it is not planned
     * @param expectedOn      about when it is planned to come back; {@code null} when it is not planned
     */
    public record Repayment(
            Status status,
            LocalDate submittedOn,
            String to,
            long paidBackCents,
            long reimbursedCents,
            long owedCents,
            long amountCents,
            Long intoAccountId,
            LocalDate expectedOn,
            Long fromAccountId) {

        /** The same, as the shares a report adds up. */
        public Share share() {
            return Reimbursement.of(status, Money.ofCents(amountCents), Money.ofCents(paidBackCents));
        }

        /** True when what is still owed is planned to come in somewhere on some day. */
        public boolean planned() {
            return intoAccountId != null && expectedOn != null;
        }
    }

    /**
     * How an expense is planned to be paid back.
     *
     * @param amountCents   what comes back; {@code null} for all of it
     * @param intoAccountId the account it comes back into; with {@code expectedOn}, {@code null} for not planned
     * @param expectedOn    about when
     * @param fromAccountId the account it comes out of, like an FSA; {@code null} from outside (migration V48)
     */
    public record Plan(Long amountCents, Long intoAccountId, LocalDate expectedOn, Long fromAccountId) {

        public static final Plan NONE = new Plan(null, null, null, null);
    }

    /**
     * What the user can set on an entry.
     *
     * @param reimbursementStatus for money going out: where it stands on being paid back; {@code null}
     *                            leaves that as it is (not paid back by anyone, when new)
     * @param reimbursesEntryId   for money coming in: the expense it pays back
     * @param reimbursesPaymentId for money coming in: the flagged recurring payment it pays back, instead
     */
    public record Fields(
            EntryKind kind,
            LocalDate date,
            long amountCents,
            boolean estimate,
            String description,
            String payee,
            Long categoryId,
            long accountId,
            Long toAccountId,
            String note,
            List<Long> tagIds,
            List<Split> bucketSplits,
            String checkNumber,
            Status reimbursementStatus,
            Long reimbursesEntryId,
            Long reimbursesPaymentId) {}

    /** Entries dated within {@code from}–{@code to}, oldest first. */
    public List<Entry> between(LocalDate from, LocalDate to) {
        return jdbi.withHandle(handle -> complete(handle, handle
                .createQuery(SELECT + "WHERE date BETWEEN :from AND :to ORDER BY date, id")
                .bind("from", from.toString())
                .bind("to", to.toString())
                .map((rs, ctx) -> entry(rs))
                .list()));
    }

    /**
     * Entries whose description or payee holds {@code text} (any capitals), newest first, at most {@code limit}.
     * The text is matched as it is typed: {@code %} and {@code _} in it are not wildcards.
     */
    public List<Entry> matching(String text, int limit) {
        // "!" as the escape character: JDBI's statement parser misreads a quoted backslash.
        String escaped = text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return jdbi.withHandle(handle -> complete(handle, handle
                .createQuery(SELECT + """
                        WHERE description LIKE :pattern ESCAPE '!' OR payee LIKE :pattern ESCAPE '!'
                        ORDER BY date DESC, id DESC LIMIT :limit
                        """)
                .bind("pattern", "%" + escaped + "%")
                .bind("limit", limit)
                .map((rs, ctx) -> entry(rs))
                .list()));
    }

    /** The date of the oldest entry; empty when there are none. */
    public Optional<LocalDate> earliestDate() {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT min(date) FROM entries")
                .mapTo(String.class)
                .findOne()
                .map(LocalDate::parse));
    }

    /** The years that have money going out in them, newest first. */
    public List<Integer> yearsWithExpenses() {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        SELECT DISTINCT CAST(substr(date, 1, 4) AS INTEGER) AS year FROM entries
                        WHERE kind = 'money_out' ORDER BY year DESC
                        """)
                .mapTo(Integer.class)
                .list());
    }

    /** Everyone a one-off entry was paid to or came from, by name, each once. */
    public List<String> payees() {
        return distinct("payee");
    }

    /** Everyone an expense or a recurring payment was sent to for paying back, by name, each once. */
    public List<String> reimbursers() {
        return jdbi.withHandle(handle -> handle
                .createQuery("""
                        SELECT min(name) FROM (
                            SELECT reimbursement_to AS name FROM entries WHERE reimbursement_to IS NOT NULL
                            UNION ALL SELECT reimbursement_to FROM payment_reimbursements WHERE reimbursement_to IS NOT NULL
                            UNION ALL SELECT reimbursed_by FROM item_reimbursement_settings WHERE reimbursed_by IS NOT NULL)
                        GROUP BY name COLLATE NOCASE ORDER BY min(name) COLLATE NOCASE
                        """)
                .mapTo(String.class)
                .list());
    }

    /** {@code column} is a name written above, never anything a request sent. */
    private List<String> distinct(String column) {
        return jdbi.withHandle(handle -> handle
                // "CVS pharmacy" and "CVS Pharmacy" are one name: the picker offers it once.
                .createQuery("SELECT min(" + column + ") FROM entries WHERE " + column + " IS NOT NULL GROUP BY "
                        + column + " COLLATE NOCASE ORDER BY " + column + " COLLATE NOCASE")
                .mapTo(String.class)
                .list());
    }

    /** Every entry, whenever it is dated, that set something aside in a bucket; oldest first. */
    public List<Entry> withBucketSplits() {
        return jdbi.withHandle(handle -> complete(handle, handle
                .createQuery(SELECT + "WHERE id IN (SELECT entry_id FROM entry_bucket_splits) ORDER BY date, id")
                .map((rs, ctx) -> entry(rs))
                .list()));
    }

    /** Expenses still being paid back with a plan for the rest: what is planned to come back, and when. */
    public List<Entry> plannedBack() {
        return jdbi.withHandle(handle -> complete(handle, handle
                .createQuery(SELECT + """
                        WHERE kind = 'money_out' AND reimbursement_status IN ('to_submit', 'submitted')
                          AND reimbursement_into_account_id IS NOT NULL AND reimbursement_expected_on IS NOT NULL
                        ORDER BY reimbursement_expected_on, id
                        """)
                .map((rs, ctx) -> entry(rs))
                .list()));
    }

    /** The money that came in to pay back the flagged recurring payment {@code paymentId}, oldest first. */
    public List<Entry> paybacksOfPayment(long paymentId) {
        return jdbi.withHandle(handle -> complete(handle, handle
                .createQuery(SELECT + "WHERE reimburses_payment_id = :id ORDER BY date, id")
                .bind("id", paymentId)
                .map((rs, ctx) -> entry(rs))
                .list()));
    }

    /** The money that came in to pay {@code expenseId} back, oldest first. */
    public List<Entry> paybacksOf(long expenseId) {
        return jdbi.withHandle(handle -> complete(handle, handle
                .createQuery(SELECT + "WHERE reimburses_entry_id = :id ORDER BY date, id")
                .bind("id", expenseId)
                .map((rs, ctx) -> entry(rs))
                .list()));
    }

    public Optional<Entry> find(long id) {
        return jdbi.withHandle(handle -> handle.createQuery(SELECT + "WHERE id = :id")
                .bind("id", id)
                .map((rs, ctx) -> entry(rs))
                .findOne()
                .map(row -> complete(handle, List.of(row)).getFirst()));
    }

    public long create(Fields fields) {
        return jdbi.inTransaction(handle -> {
            long id = bind(handle.createQuery("""
                            INSERT INTO entries
                                (kind, date, amount, is_estimate, description, payee, category_id, account_id,
                                 to_account_id, note, check_number, reimbursement_status, reimburses_entry_id,
                                 reimburses_payment_id, created_at)
                            VALUES (:kind, :date, :amount, :estimate, :description, :payee, :categoryId, :accountId,
                                    :toAccountId, :note, :checkNumber, coalesce(:status, 'none'), :reimbursesEntryId,
                                    :reimbursesPaymentId, :now)
                            RETURNING id
                            """), fields)
                    .bind("now", clock.instant().toString())
                    .mapTo(Long.class)
                    .one();
            replaceTags(handle, id, fields.tagIds());
            replaceSplits(handle, id, fields.bucketSplits());
            settle(handle, fields.reimbursesEntryId());
            ReimbursementsRepository.settle(handle, fields.reimbursesPaymentId());
            return id;
        });
    }

    /** @return false when there is no such entry */
    public boolean update(long id, Fields fields) {
        return jdbi.inTransaction(handle -> {
            Long paidBackBefore = handle.createQuery("SELECT reimburses_entry_id FROM entries WHERE id = :id")
                    .bind("id", id)
                    .mapTo(Long.class)
                    .findOne()
                    .orElse(null);
            Long paymentBefore = handle.createQuery("SELECT reimburses_payment_id FROM entries WHERE id = :id")
                    .bind("id", id)
                    .mapTo(Long.class)
                    .findOne()
                    .orElse(null);
            // Only money going out can be paid back: an entry changed into anything else stops being owed.
            int changed = bind(handle.createUpdate("""
                            UPDATE entries
                            SET kind = :kind, date = :date, amount = :amount, is_estimate = :estimate,
                                description = :description, payee = :payee, category_id = :categoryId,
                                account_id = :accountId, to_account_id = :toAccountId, note = :note,
                                check_number = :checkNumber, reimburses_entry_id = :reimbursesEntryId,
                                reimburses_payment_id = :reimbursesPaymentId,
                                reimbursement_status = CASE WHEN :kind <> 'money_out' THEN 'none'
                                                            ELSE coalesce(:status, reimbursement_status) END
                            WHERE id = :id
                            """), fields)
                    .bind("id", id)
                    .execute();
            if (changed > 0) {
                // Without a status there is no day it was sent in and nobody it was sent to.
                handle.createUpdate("""
                                UPDATE entries SET reimbursement_submitted_on = NULL, reimbursement_to = NULL,
                                    reimbursement_amount = NULL, reimbursement_into_account_id = NULL,
                                    reimbursement_expected_on = NULL, reimbursement_from_account_id = NULL
                                WHERE id = :id AND reimbursement_status = 'none'
                                  AND (reimbursement_submitted_on IS NOT NULL OR reimbursement_to IS NOT NULL
                                       OR reimbursement_amount IS NOT NULL OR reimbursement_into_account_id IS NOT NULL
                                       OR reimbursement_expected_on IS NOT NULL OR reimbursement_from_account_id IS NOT NULL)
                                """)
                        .bind("id", id)
                        .execute();
                if (fields.kind() != EntryKind.MONEY_OUT) {
                    releasePaybacks(handle, id);
                }
                replaceTags(handle, id, fields.tagIds());
                replaceSplits(handle, id, fields.bucketSplits());
                // Its own amount may have changed, and so may what it pays back.
                settle(handle, id);
                settle(handle, fields.reimbursesEntryId());
                if (paidBackBefore != null && !paidBackBefore.equals(fields.reimbursesEntryId())) {
                    settle(handle, paidBackBefore);
                }
                ReimbursementsRepository.settle(handle, fields.reimbursesPaymentId());
                if (paymentBefore != null && !paymentBefore.equals(fields.reimbursesPaymentId())) {
                    ReimbursementsRepository.settle(handle, paymentBefore);
                }
            }
            return changed > 0;
        });
    }

    /**
     * Sets where an expense stands on being paid back, and how it is planned to come back. A smaller amount may now be all
     * paid back, so the status is moved along to match. @return false when there is no such expense
     */
    public boolean setReimbursement(long id, Status status, LocalDate submittedOn, String to, Plan plan) {
        return jdbi.inTransaction(handle -> {
            boolean found = handle
                    .createUpdate("""
                            UPDATE entries
                            SET reimbursement_status = :status, reimbursement_submitted_on = :submittedOn, reimbursement_to = :to,
                                reimbursement_amount = :amount, reimbursement_into_account_id = :into,
                                reimbursement_expected_on = :expected, reimbursement_from_account_id = :from
                            WHERE id = :id AND kind = 'money_out'
                            """)
                    .bind("status", Inputs.code(status))
                    .bind("submittedOn", submittedOn == null ? null : submittedOn.toString())
                    .bind("to", to)
                    .bind("amount", plan.amountCents())
                    .bind("into", plan.intoAccountId())
                    .bind("expected", plan.expectedOn() == null ? null : plan.expectedOn().toString())
                    .bind("from", plan.fromAccountId())
                    .bind("id", id)
                    .execute() > 0;
            settle(handle, id);
            return found;
        });
    }

    public boolean delete(long id) {
        return jdbi.inTransaction(handle -> {
            // Money that paid this back stays as money that came in; it just no longer pays anything back.
            releasePaybacks(handle, id);
            return handle.createUpdate("DELETE FROM entries WHERE id = :id").bind("id", id).execute() > 0;
        });
    }

    private static void releasePaybacks(Handle handle, long expenseId) {
        handle.createUpdate("UPDATE entries SET reimburses_entry_id = NULL WHERE reimburses_entry_id = :id")
                .bind("id", expenseId)
                .execute();
        handle.createUpdate("UPDATE bucket_changes SET reimburses_entry_id = NULL WHERE reimburses_entry_id = :id")
                .bind("id", expenseId)
                .execute();
    }

    /** An expense's amount and status, for {@link #settle}. */
    private record Owed(long amountCents, Status status) {}

    /**
     * Moves an expense's status forward to match the money that has come back for it: reimbursed once
     * all of it is covered, at least submitted once any of it is. Never moves it back.
     */
    /** {@link #settle} for money that came back from elsewhere: a move out of a bucket that pays it back. */
    public void settle(Long expenseId) {
        jdbi.useTransaction(handle -> settle(handle, expenseId));
    }

    private static void settle(Handle handle, Long expenseId) {
        if (expenseId == null) {
            return;
        }
        // What comes back, which may be less than the expense.
        handle.createQuery("""
                        SELECT coalesce(reimbursement_amount, amount) AS amount, reimbursement_status FROM entries
                        WHERE id = :id AND kind = 'money_out'
                        """)
                .bind("id", expenseId)
                .map((rs, ctx) -> new Owed(rs.getLong("amount"), status(rs.getString("reimbursement_status"))))
                .findOne()
                .ifPresent(owed -> {
                    Status next = Reimbursement.afterPayback(
                            owed.status(), Money.ofCents(owed.amountCents()), Money.ofCents(paidBack(handle, expenseId)));
                    if (next != owed.status()) {
                        handle.createUpdate("UPDATE entries SET reimbursement_status = :status WHERE id = :id")
                                .bind("status", Inputs.code(next))
                                .bind("id", expenseId)
                                .execute();
                    }
                });
    }

    /**
     * What came back for an expense: money coming in that pays it back, and money moved out of a bucket to pay
     * yourself back for it (stored as a negative change, so taken away to count it up).
     */
    private static long paidBack(Handle handle, long expenseId) {
        return handle.createQuery("""
                        SELECT coalesce((SELECT sum(amount) FROM entries WHERE reimburses_entry_id = :id), 0)
                             - coalesce((SELECT sum(amount) FROM bucket_changes WHERE reimburses_entry_id = :id), 0)
                        """)
                .bind("id", expenseId)
                .mapTo(Long.class)
                .one();
    }

    private static <S extends SqlStatement<S>> S bind(S statement, Fields fields) {
        return statement
                .bind("kind", Inputs.code(fields.kind()))
                .bind("date", fields.date().toString())
                .bind("amount", fields.amountCents())
                .bind("estimate", fields.estimate())
                .bind("description", fields.description())
                .bind("payee", fields.payee())
                .bind("categoryId", fields.categoryId())
                .bind("accountId", fields.accountId())
                .bind("toAccountId", fields.toAccountId())
                .bind("note", fields.note())
                .bind("checkNumber", fields.checkNumber())
                .bind("status", fields.reimbursementStatus() == null ? null : Inputs.code(fields.reimbursementStatus()))
                .bind("reimbursesEntryId", fields.reimbursesEntryId())
                .bind("reimbursesPaymentId", fields.reimbursesPaymentId());
    }

    private static void replaceTags(Handle handle, long entryId, List<Long> tagIds) {
        handle.createUpdate("DELETE FROM entry_tags WHERE entry_id = :id").bind("id", entryId).execute();
        for (Long tagId : tagIds) {
            handle.createUpdate("INSERT INTO entry_tags (entry_id, tag_id) VALUES (:entryId, :tagId)")
                    .bind("entryId", entryId)
                    .bind("tagId", tagId)
                    .execute();
        }
    }

    private static void replaceSplits(Handle handle, long entryId, List<Split> splits) {
        handle.createUpdate("DELETE FROM entry_bucket_splits WHERE entry_id = :id").bind("id", entryId).execute();
        for (Split split : splits) {
            handle.createUpdate("INSERT INTO entry_bucket_splits (entry_id, bucket_id, amount) VALUES (:entryId, :bucketId, :amount)")
                    .bind("entryId", entryId)
                    .bind("bucketId", split.bucketId())
                    .bind("amount", split.amountCents())
                    .execute();
        }
    }

    /** Attaches each row's tags and bucket splits, what has been paid back, and how many files it has. */
    private static List<Entry> complete(Handle handle, List<Entry> rows) {
        List<Entry> entries = new ArrayList<>();
        for (Entry row : rows) {
            List<Long> tagIds = handle.createQuery("SELECT tag_id FROM entry_tags WHERE entry_id = :id ORDER BY tag_id")
                    .bind("id", row.id())
                    .mapTo(Long.class)
                    .list();
            List<Split> splits = handle
                    .createQuery("SELECT bucket_id, amount FROM entry_bucket_splits WHERE entry_id = :id ORDER BY bucket_id")
                    .bind("id", row.id())
                    .map((rs, ctx) -> new Split(rs.getLong("bucket_id"), rs.getLong("amount")))
                    .list();
            int fileCount = handle.createQuery("SELECT count(*) FROM attachments WHERE entry_id = :id")
                    .bind("id", row.id())
                    .mapTo(Integer.class)
                    .one();
            Repayment repayment = row.reimbursement() == null
                    ? null
                    : repayment(row.reimbursement(), paidBack(handle, row.id()));
            entries.add(new Entry(
                    row.id(), row.kind(), row.date(), row.amountCents(), row.estimate(), row.description(), row.payee(),
                    row.categoryId(), row.accountId(), row.toAccountId(), row.note(), tagIds, splits, row.checkNumber(),
                    repayment, row.reimbursesEntryId(), row.reimbursesPaymentId(), fileCount));
        }
        return entries;
    }

    /** {@code stored} with the figures that follow from the money that has come back. */
    private static Repayment repayment(Repayment stored, long paidBackCents) {
        Share share = Reimbursement.of(stored.status(), Money.ofCents(stored.amountCents()), Money.ofCents(paidBackCents));
        return new Repayment(stored.status(), stored.submittedOn(), stored.to(), paidBackCents, share.reimbursed().cents(),
                share.owed().cents(), stored.amountCents(), stored.intoAccountId(), stored.expectedOn(), stored.fromAccountId());
    }

    /** A row of {@code entries}, before its tags, splits and figures are attached. */
    private static Entry entry(ResultSet rs) throws SQLException {
        EntryKind kind = EntryKind.valueOf(rs.getString("kind").toUpperCase(Locale.ROOT));
        String submittedOn = rs.getString("reimbursement_submitted_on");
        String expectedOn = rs.getString("reimbursement_expected_on");
        Long partOf = nullableLong(rs, "reimbursement_amount");
        Repayment stored = kind != EntryKind.MONEY_OUT ? null : new Repayment(
                status(rs.getString("reimbursement_status")),
                submittedOn == null ? null : LocalDate.parse(submittedOn),
                rs.getString("reimbursement_to"),
                0, 0, 0,
                partOf == null ? rs.getLong("amount") : partOf,
                nullableLong(rs, "reimbursement_into_account_id"),
                expectedOn == null ? null : LocalDate.parse(expectedOn),
                nullableLong(rs, "reimbursement_from_account_id"));
        return new Entry(
                rs.getLong("id"),
                kind,
                LocalDate.parse(rs.getString("date")),
                rs.getLong("amount"),
                rs.getBoolean("is_estimate"),
                rs.getString("description"),
                rs.getString("payee"),
                nullableLong(rs, "category_id"),
                rs.getLong("account_id"),
                nullableLong(rs, "to_account_id"),
                rs.getString("note"),
                List.of(),
                List.of(),
                rs.getString("check_number"),
                stored,
                nullableLong(rs, "reimburses_entry_id"),
                nullableLong(rs, "reimburses_payment_id"),
                0);
    }

    private static Status status(String code) {
        return Status.valueOf(code.toUpperCase(Locale.ROOT));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
