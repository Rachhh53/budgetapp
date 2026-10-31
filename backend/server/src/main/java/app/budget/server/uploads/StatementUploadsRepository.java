package app.budget.server.uploads;

import app.budget.engine.statements.BudgetActuals.Span;
import app.budget.engine.statements.Duplicates;
import app.budget.engine.statements.StatementRead.Kind;
import app.budget.server.api.Inputs;
import app.budget.server.files.AttachmentsRepository;
import app.budget.server.files.FileStore.Stored;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/**
 * Reads and writes the statement uploads of each account (migrations {@code V49}, {@code V51}, {@code V52}): their lines
 * and what each is chosen to be (on a budget, matched to something already in Budget, or a one-off), the category and tags
 * and the budget each payee was last given, still to come typed for a budget date, and which item dates a statement set.
 * Every query is bound; nothing in a line is logged, since it says where someone spends.
 */
public final class StatementUploadsRepository {

    private final Jdbi jdbi;
    private final Clock clock;

    public StatementUploadsRepository(Jdbi jdbi, Clock clock) {
        this.jdbi = jdbi;
        this.clock = clock;
    }

    /** The kind of file it was read from. */
    public enum Format { CSV, OFX, PDF }

    /**
     * An upload.
     *
     * @param hash      the kept PDF; null for a CSV or OFX file, which is not kept
     * @param appliedAt     when it was last applied; null until then
     * @param previousCents the balance it starts from (on a card, what was owed); null when neither read nor typed
     * @param newCents      the balance it ends with; null when neither read nor typed
     * @param chargesCents  the statement's own total of charges (withdrawals on a bank account), as typed; null when not
     * @param creditsCents  the statement's own total of payments and credits (deposits on a bank account); null when not
     */
    public record Upload(
            long id, long accountId, String name, Format format, LocalDate from, LocalDate to, String createdAt, String appliedAt,
            String hash, Long sizeBytes, Integer pages, Long previousCents, Long newCents, Long chargesCents, Long creditsCents) {}

    /**
     * One line, as kept.
     *
     * @param amountCents  spent as positive, money back negative
     * @param duplicateOf   the line of an earlier upload it repeats; null when it is new
     * @param oneOffEntryId the one-off it was added as; null until Apply adds it
     * @param addOneOff     chosen to be added as a one-off on the next Apply
     * @param match         what Budget already has that it is, chosen by the user; null when it isn't matched
     * @param budgetItemId  the budget it is put on; null for none
     * @param budgetDate    the date of that budget it counted toward when last applied; null before then
     */
    public record Line(
            long id,
            long uploadId,
            LocalDate txnDate,
            LocalDate postedDate,
            String description,
            String payeeKey,
            long amountCents,
            Kind kind,
            boolean leftOut,
            boolean sure,
            String externalId,
            Long duplicateOf,
            Long categoryId,
            List<Long> tagIds,
            Long oneOffEntryId,
            boolean addOneOff,
            Match match,
            Long budgetItemId,
            LocalDate budgetDate) {

        /** Still open: not left out, not a repeat, not a one-off (added or chosen) and not matched to something already there. */
        public boolean open() {
            return !leftOut && duplicateOf == null && oneOffEntryId == null && !addOneOff && match == null;
        }

        /** Put on a budget, and counting toward it. */
        public boolean onBudget() {
            return open() && budgetItemId != null;
        }
    }

    /**
     * What a line was matched to: a date of a recurring item ({@code itemId} and {@code scheduledDate}) or a one-off
     * entry ({@code entryId}). Once {@code applied}, a date was written and {@code before} is what it was, to put back.
     */
    public record Match(Long itemId, LocalDate scheduledDate, Long entryId, boolean applied, Before before) {

        public static Match item(long itemId, LocalDate scheduledDate) {
            return new Match(itemId, scheduledDate, null, false, Before.NOTHING);
        }

        public static Match entry(long entryId) {
            return new Match(null, null, entryId, false, Before.NOTHING);
        }

        /** The bill's date, or null for a one-off. */
        public ItemDate itemDate() {
            return itemId == null ? null : new ItemDate(itemId, scheduledDate);
        }
    }

    /**
     * An item's date before a line was matched to it.
     *
     * @param amountCents    the amount typed over it then; null for none
     * @param projectedCents the amount it showed before its first typed amount; null for none
     */
    public record Before(Long amountCents, boolean paid, LocalDate paidDate, Long projectedCents) {
        public static final Before NOTHING = new Before(null, false, null, null);
    }

    /** A line to add with a new upload. */
    public record NewLine(
            LocalDate txnDate, LocalDate postedDate, String description, String payeeKey, long amountCents, Kind kind, boolean leftOut,
            boolean sure, String externalId, Long duplicateOf, Long categoryId, List<Long> tagIds) {}

    /** What a payee was last given. */
    public record PayeeRule(Long categoryId, List<Long> tagIds) {}

    /**
     * What a statement set a budget's date to, and what it was before any statement did.
     *
     * @param actualCents what the charges on it came to then; null for a date set before V52
     * @param stillCents  what was still to come then; null for a date set before V52
     */
    public record Replaced(long baseCents, long appliedCents, Long actualCents, Long stillCents) {}

    /** A bill's date whose applied match was let go of, put back as {@code before} on its pay period's next Apply. */
    public record Restore(ItemDate date, LocalDate periodStart, Before before) {}

    /** An item's date, as occurrence_overrides keys it. */
    public record ItemDate(long itemId, LocalDate scheduledDate) {}

    private static final String UPLOAD_SELECT = """
            SELECT u.id, u.account_id, u.name, u.format, u.from_date, u.to_date, u.created_at, u.applied_at, f.hash, f.size, f.pages,
                   u.previous_balance, u.new_balance, u.statement_charges, u.statement_credits
            FROM statement_uploads u LEFT JOIN files f ON f.id = u.file_id
            """;

    private static final String LINE_SELECT = """
            SELECT l.id, l.upload_id, l.txn_date, l.posted_date, l.description, l.payee_key, l.amount, l.kind, l.left_out, l.sure,
                   l.external_id, l.duplicate_of, l.category_id, l.one_off_entry_id, l.add_one_off, l.matched_item_id, l.matched_date,
                   l.matched_entry_id, l.match_applied, l.before_amount, l.before_paid, l.before_paid_date, l.before_projected,
                   l.budget_item_id, l.budget_date
            FROM statement_upload_lines l
            """;

    /**
     * Adds an upload with its lines. {@code file} is the kept PDF, or null for a file that is not kept; the balances and
     * totals are those the file says, or null.
     */
    public long create(
            long accountId, Stored file, String name, Format format, LocalDate from, LocalDate to, Long previousCents, Long newCents,
            Long chargesCents, Long creditsCents, List<NewLine> lines) {
        return jdbi.inTransaction(handle -> {
            String now = clock.instant().toString();
            long id = handle.createQuery("""
                            INSERT INTO statement_uploads
                                (account_id, file_id, name, format, from_date, to_date, created_at, previous_balance, new_balance,
                                 statement_charges, statement_credits)
                            VALUES (:accountId, :fileId, :name, :format, :from, :to, :now, :previous, :new, :charges, :credits)
                            RETURNING id
                            """)
                    .bind("accountId", accountId)
                    .bind("fileId", file == null ? null : AttachmentsRepository.fileId(handle, file, now))
                    .bind("name", name)
                    .bind("format", Inputs.code(format))
                    .bind("from", from.toString())
                    .bind("to", to.toString())
                    .bind("now", now)
                    .bind("previous", previousCents)
                    .bind("new", newCents)
                    .bind("charges", chargesCents)
                    .bind("credits", creditsCents)
                    .mapTo(Long.class)
                    .one();
            for (int position = 0; position < lines.size(); position++) {
                NewLine line = lines.get(position);
                long lineId = handle.createQuery("""
                                INSERT INTO statement_upload_lines
                                    (upload_id, position, txn_date, posted_date, description, payee_key, amount, kind, left_out, sure,
                                     external_id, duplicate_of, category_id)
                                VALUES (:uploadId, :position, :txnDate, :postedDate, :description, :payeeKey, :amount, :kind, :leftOut,
                                        :sure, :externalId, :duplicateOf, :categoryId)
                                RETURNING id
                                """)
                        .bind("uploadId", id)
                        .bind("position", position)
                        .bind("txnDate", line.txnDate().toString())
                        .bind("postedDate", line.postedDate() == null ? null : line.postedDate().toString())
                        .bind("description", line.description())
                        .bind("payeeKey", line.payeeKey())
                        .bind("amount", line.amountCents())
                        .bind("kind", Inputs.code(line.kind()))
                        .bind("leftOut", line.leftOut())
                        .bind("sure", line.sure())
                        .bind("externalId", line.externalId())
                        .bind("duplicateOf", line.duplicateOf())
                        .bind("categoryId", line.categoryId())
                        .mapTo(Long.class)
                        .one();
                insertTags(handle, lineId, line.tagIds());
            }
            return id;
        });
    }

    public Optional<Upload> find(long id) {
        return jdbi.withHandle(handle -> handle.createQuery(UPLOAD_SELECT + "WHERE u.id = :id")
                .bind("id", id)
                .map((rs, ctx) -> upload(rs))
                .findOne());
    }

    /** An account's uploads, the latest statement first. */
    public List<Upload> ofAccount(long accountId) {
        return jdbi.withHandle(handle -> handle.createQuery(UPLOAD_SELECT + "WHERE u.account_id = :id ORDER BY u.to_date DESC, u.id DESC")
                .bind("id", accountId)
                .map((rs, ctx) -> upload(rs))
                .list());
    }

    /** One upload's lines, in the order the statement had them. */
    public List<Line> lines(long uploadId) {
        return jdbi.withHandle(handle -> withTags(handle, handle
                .createQuery(LINE_SELECT + "WHERE l.upload_id = :id ORDER BY l.position, l.id")
                .bind("id", uploadId)
                .map((rs, ctx) -> line(rs))
                .list()));
    }

    /** Every line of an account's uploads: its statements add together within a pay period. */
    public List<Line> linesOfAccount(long accountId) {
        return jdbi.withHandle(handle -> withTags(handle, handle
                .createQuery(LINE_SELECT + """
                        JOIN statement_uploads u ON u.id = l.upload_id
                        WHERE u.account_id = :id ORDER BY l.txn_date, l.upload_id, l.position
                        """)
                .bind("id", accountId)
                .map((rs, ctx) -> line(rs))
                .list()));
    }

    public Optional<Line> line(long lineId) {
        return jdbi.withHandle(handle -> withTags(handle, handle
                .createQuery(LINE_SELECT + "WHERE l.id = :id")
                .bind("id", lineId)
                .map((rs, ctx) -> line(rs))
                .list()).stream().findFirst());
    }

    /** The lines already kept for an account that are not repeats themselves, for noticing them on a new statement. */
    public List<Duplicates.Known> known(long accountId) {
        return linesOfAccount(accountId).stream()
                .filter(line -> line.duplicateOf() == null)
                .map(line -> new Duplicates.Known(line.id(), line.txnDate(), line.amountCents(), line.description(), line.externalId()))
                .toList();
    }

    /** The days each of an account's uploads covers. */
    public List<Span> spans(long accountId) {
        return ofAccount(accountId).stream().map(upload -> new Span(upload.from(), upload.to())).toList();
    }

    /** The days every account's statements cover, by account: a budget date is covered by its own account's alone. */
    public Map<Long, List<Span>> spansByAccount() {
        Map<Long, List<Span>> spans = new HashMap<>();
        jdbi.withHandle(handle -> handle.createQuery(UPLOAD_SELECT).map((rs, ctx) -> upload(rs)).list())
                .forEach(upload -> spans.computeIfAbsent(upload.accountId(), key -> new ArrayList<>()).add(new Span(upload.from(), upload.to())));
        return spans;
    }

    /** A line with the account whose statement it is on. */
    public record AccountLine(Line line, long accountId) {}

    /**
     * Every line of any account's statements put on a budget: a budget can take charges from a card other than the one it
     * is paid from (Rachel, 2026-10-30).
     */
    public List<AccountLine> onBudgets() {
        return jdbi.withHandle(handle -> {
            Map<Long, Long> accountOf = new HashMap<>();
            handle.createQuery("SELECT id, account_id FROM statement_uploads")
                    .map((rs, ctx) -> Map.entry(rs.getLong("id"), rs.getLong("account_id")))
                    .forEach(entry -> accountOf.put(entry.getKey(), entry.getValue()));
            List<Line> lines = withTags(handle, handle
                    .createQuery(LINE_SELECT + "WHERE l.budget_item_id IS NOT NULL ORDER BY l.txn_date, l.upload_id, l.position")
                    .map((rs, ctx) -> line(rs))
                    .list());
            return lines.stream().map(line -> new AccountLine(line, accountOf.get(line.uploadId()))).toList();
        });
    }

    /** The one-off on each other account holding what was spent on a budget date there, by account. */
    public Map<Long, Long> elsewhere(ItemDate date) {
        Map<Long, Long> entries = new HashMap<>();
        jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT account_id, entry_id FROM statement_budget_elsewhere WHERE item_id = :itemId AND original_due_date = :date
                        """)
                .bind("itemId", date.itemId())
                .bind("date", date.scheduledDate().toString())
                .map((rs, ctx) -> Map.entry(rs.getLong("account_id"), rs.getLong("entry_id")))
                .list())
                .forEach(entry -> entries.put(entry.getKey(), entry.getValue()));
        return entries;
    }

    /** The budget dates with a one-off on this account for what was spent on them here. */
    public List<ItemDate> elsewhereOn(long accountId) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT item_id, original_due_date FROM statement_budget_elsewhere WHERE account_id = :accountId
                        """)
                .bind("accountId", accountId)
                .map((rs, ctx) -> new ItemDate(rs.getLong("item_id"), LocalDate.parse(rs.getString("original_due_date"))))
                .list());
    }

    public void saveElsewhere(ItemDate date, long accountId, long entryId) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        INSERT INTO statement_budget_elsewhere (item_id, original_due_date, account_id, entry_id)
                        VALUES (:itemId, :date, :accountId, :entryId)
                        ON CONFLICT (item_id, original_due_date, account_id) DO UPDATE SET entry_id = excluded.entry_id
                        """)
                .bind("itemId", date.itemId())
                .bind("date", date.scheduledDate().toString())
                .bind("accountId", accountId)
                .bind("entryId", entryId)
                .execute());
    }

    public void deleteElsewhere(ItemDate date, long accountId) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        DELETE FROM statement_budget_elsewhere WHERE item_id = :itemId AND original_due_date = :date AND account_id = :accountId
                        """)
                .bind("itemId", date.itemId())
                .bind("date", date.scheduledDate().toString())
                .bind("accountId", accountId)
                .execute());
    }

    /**
     * Gives several lines the same category and tags at once (Rachel, 2026-10-30: "I can't just mass categorize and tag
     * them"); what a line had is replaced.
     */
    public void file(Collection<Long> lineIds, Long categoryId, List<Long> tagIds) {
        jdbi.useTransaction(handle -> {
            for (long lineId : lineIds) {
                handle.createUpdate("UPDATE statement_upload_lines SET category_id = :categoryId WHERE id = :id")
                        .bind("categoryId", categoryId)
                        .bind("id", lineId)
                        .execute();
                handle.createUpdate("DELETE FROM statement_upload_line_tags WHERE line_id = :id").bind("id", lineId).execute();
                insertTags(handle, lineId, tagIds);
            }
        });
    }

    /** A line as it is changed on the review page. */
    public record LineFields(
            LocalDate txnDate, String description, String payeeKey, long amountCents, Kind kind, boolean leftOut, Long categoryId,
            List<Long> tagIds) {}

    /** Changes a line; looked at, it is no longer one to check. */
    public void updateLine(long lineId, LineFields fields) {
        jdbi.useTransaction(handle -> {
            handle.createUpdate("""
                            UPDATE statement_upload_lines
                            SET txn_date = :txnDate, description = :description, payee_key = :payeeKey, amount = :amount, kind = :kind,
                                left_out = :leftOut, category_id = :categoryId, sure = 1
                            WHERE id = :id
                            """)
                    .bind("txnDate", fields.txnDate().toString())
                    .bind("description", fields.description())
                    .bind("payeeKey", fields.payeeKey())
                    .bind("amount", fields.amountCents())
                    .bind("kind", Inputs.code(fields.kind()))
                    .bind("leftOut", fields.leftOut())
                    .bind("categoryId", fields.categoryId())
                    .bind("id", lineId)
                    .execute();
            handle.createUpdate("DELETE FROM statement_upload_line_tags WHERE line_id = :id").bind("id", lineId).execute();
            insertTags(handle, lineId, fields.tagIds());
        });
    }

    /**
     * Gives the same category and tags to the account's other lines from the payee that have neither yet, so filing one
     * charge files the rest of them.
     *
     * @return how many other lines were filled in
     */
    public int fillFromPayee(long accountId, long exceptLineId, String payeeKey, Long categoryId, List<Long> tagIds) {
        if (payeeKey.isEmpty() || categoryId == null && tagIds.isEmpty()) {
            return 0;
        }
        return jdbi.inTransaction(handle -> {
            List<Long> lineIds = handle.createQuery("""
                            SELECT l.id FROM statement_upload_lines l JOIN statement_uploads u ON u.id = l.upload_id
                            WHERE u.account_id = :accountId AND l.payee_key = :key AND l.id <> :except AND l.category_id IS NULL
                              AND NOT EXISTS (SELECT 1 FROM statement_upload_line_tags t WHERE t.line_id = l.id)
                            """)
                    .bind("accountId", accountId)
                    .bind("key", payeeKey)
                    .bind("except", exceptLineId)
                    .mapTo(Long.class)
                    .list();
            for (long lineId : lineIds) {
                handle.createUpdate("UPDATE statement_upload_lines SET category_id = :categoryId WHERE id = :id")
                        .bind("categoryId", categoryId)
                        .bind("id", lineId)
                        .execute();
                insertTags(handle, lineId, tagIds);
            }
            return lineIds.size();
        });
    }

    /** Marks a line as added as a one-off of its own, the entry it became, so it is no longer chosen to be. */
    public void setOneOff(long lineId, long entryId) {
        jdbi.useHandle(handle -> handle.createUpdate("UPDATE statement_upload_lines SET one_off_entry_id = :entryId, add_one_off = 0 WHERE id = :id")
                .bind("entryId", entryId)
                .bind("id", lineId)
                .execute());
    }

    /** Chooses a line to be added as a one-off on the next Apply, or not. */
    public void setAddOneOff(long lineId, boolean add) {
        jdbi.useHandle(handle -> handle.createUpdate("UPDATE statement_upload_lines SET add_one_off = :add WHERE id = :id")
                .bind("add", add)
                .bind("id", lineId)
                .execute());
    }

    /** Puts lines on a budget, or with a null {@code itemId} on none. */
    public void setBudget(Collection<Long> lineIds, Long itemId) {
        if (lineIds.isEmpty()) {
            return;
        }
        jdbi.useHandle(handle -> handle.createUpdate("UPDATE statement_upload_lines SET budget_item_id = :itemId WHERE id IN (<ids>)")
                .bind("itemId", itemId)
                .bindList("ids", List.copyOf(lineIds))
                .execute());
    }

    /** Records the budget date lines counted toward when applied. */
    public void setBudgetDate(Collection<Long> lineIds, LocalDate date) {
        if (lineIds.isEmpty()) {
            return;
        }
        jdbi.useHandle(handle -> handle.createUpdate("UPDATE statement_upload_lines SET budget_date = :date WHERE id IN (<ids>)")
                .bind("date", date.toString())
                .bindList("ids", List.copyOf(lineIds))
                .execute());
    }

    /** The lines of every statement that counted toward a budget's date when it was last applied, by day. */
    public List<Line> onBudgetDate(long itemId, LocalDate date) {
        return jdbi.withHandle(handle -> withTags(handle, handle
                .createQuery(LINE_SELECT + "WHERE l.budget_item_id = :itemId AND l.budget_date = :date ORDER BY l.txn_date, l.id")
                .bind("itemId", itemId)
                .bind("date", date.toString())
                .map((rs, ctx) -> line(rs))
                .list()));
    }

    /** Chooses what a line is: not yet written to the bill's date, which Apply does. */
    public void setMatch(long lineId, Match match) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        UPDATE statement_upload_lines
                        SET matched_item_id = :itemId, matched_date = :date, matched_entry_id = :entryId, match_applied = 0,
                            before_amount = NULL, before_paid = 0, before_paid_date = NULL, before_projected = NULL
                        WHERE id = :id
                        """)
                .bind("itemId", match.itemId())
                .bind("date", match.scheduledDate() == null ? null : match.scheduledDate().toString())
                .bind("entryId", match.entryId())
                .bind("id", lineId)
                .execute());
    }

    /** Records that a line's match was written to its bill's date, and what that date was before. */
    public void setMatchApplied(long lineId, Before before) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        UPDATE statement_upload_lines
                        SET match_applied = 1, before_amount = :amount, before_paid = :paid, before_paid_date = :paidDate,
                            before_projected = :projected
                        WHERE id = :id
                        """)
                .bind("amount", before.amountCents())
                .bind("paid", before.paid())
                .bind("paidDate", before.paidDate() == null ? null : before.paidDate().toString())
                .bind("projected", before.projectedCents())
                .bind("id", lineId)
                .execute());
    }

    /** Lets go of what a line was matched to, so it is open again. */
    public void clearMatch(long lineId) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        UPDATE statement_upload_lines
                        SET matched_item_id = NULL, matched_date = NULL, matched_entry_id = NULL, match_applied = 0, before_amount = NULL,
                            before_paid = 0, before_paid_date = NULL, before_projected = NULL
                        WHERE id = :id
                        """)
                .bind("id", lineId)
                .execute());
    }

    /** Remembers a bill's date to put back on its pay period's next Apply. */
    public void saveRestore(long accountId, Restore restore) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        INSERT INTO statement_match_restores
                            (item_id, original_due_date, account_id, period_start, amount, paid, paid_date, projected)
                        VALUES (:itemId, :date, :accountId, :period, :amount, :paid, :paidDate, :projected)
                        ON CONFLICT (item_id, original_due_date) DO UPDATE SET
                            account_id = excluded.account_id, period_start = excluded.period_start, amount = excluded.amount,
                            paid = excluded.paid, paid_date = excluded.paid_date, projected = excluded.projected
                        """)
                .bind("itemId", restore.date().itemId())
                .bind("date", restore.date().scheduledDate().toString())
                .bind("accountId", accountId)
                .bind("period", restore.periodStart().toString())
                .bind("amount", restore.before().amountCents())
                .bind("paid", restore.before().paid())
                .bind("paidDate", restore.before().paidDate() == null ? null : restore.before().paidDate().toString())
                .bind("projected", restore.before().projectedCents())
                .execute());
    }

    /** An account's bill dates waiting to be put back. */
    public List<Restore> restores(long accountId) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT item_id, original_due_date, period_start, amount, paid, paid_date, projected
                        FROM statement_match_restores WHERE account_id = :id
                        """)
                .bind("id", accountId)
                .map((rs, ctx) -> {
                    String paidDate = rs.getString("paid_date");
                    return new Restore(
                            new ItemDate(rs.getLong("item_id"), LocalDate.parse(rs.getString("original_due_date"))),
                            LocalDate.parse(rs.getString("period_start")),
                            new Before(nullableLong(rs, "amount"), rs.getBoolean("paid"), paidDate == null ? null : LocalDate.parse(paidDate),
                                    nullableLong(rs, "projected")));
                })
                .list());
    }

    public void deleteRestore(ItemDate date) {
        jdbi.useHandle(handle -> handle.createUpdate("DELETE FROM statement_match_restores WHERE item_id = :itemId AND original_due_date = :date")
                .bind("itemId", date.itemId())
                .bind("date", date.scheduledDate().toString())
                .execute());
    }

    /** Records the statement's balances and totals as typed, or null to leave one unsaid. */
    public void setBalances(long uploadId, Long previousCents, Long newCents, Long chargesCents, Long creditsCents) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        UPDATE statement_uploads
                        SET previous_balance = :previous, new_balance = :new, statement_charges = :charges, statement_credits = :credits
                        WHERE id = :id
                        """)
                .bind("previous", previousCents)
                .bind("new", newCents)
                .bind("charges", chargesCents)
                .bind("credits", creditsCents)
                .bind("id", uploadId)
                .execute());
    }

    /** The budget each payee's charges were last put on, on an account, by payee key. */
    public Map<String, Long> budgetHints(long accountId) {
        Map<String, Long> hints = new HashMap<>();
        jdbi.useHandle(handle -> handle.createQuery("SELECT payee_key, item_id FROM statement_budget_hints WHERE account_id = :id")
                .bind("id", accountId)
                .map((rs, ctx) -> hints.put(rs.getString("payee_key"), rs.getLong("item_id")))
                .list());
        return hints;
    }

    /** Remembers the budget a payee's charge was put on, as a suggestion for the next one. */
    public void rememberBudget(long accountId, String payeeKey, long itemId) {
        if (payeeKey.isEmpty()) {
            return;
        }
        jdbi.useHandle(handle -> handle.createUpdate("""
                        INSERT INTO statement_budget_hints (account_id, payee_key, item_id) VALUES (:accountId, :key, :itemId)
                        ON CONFLICT (account_id, payee_key) DO UPDATE SET item_id = excluded.item_id
                        """)
                .bind("accountId", accountId)
                .bind("key", payeeKey)
                .bind("itemId", itemId)
                .execute());
    }

    /** Still to come as typed, for these items' dates. */
    public Map<ItemDate, Long> typedStill(Collection<Long> itemIds) {
        Map<ItemDate, Long> typed = new HashMap<>();
        if (itemIds.isEmpty()) {
            return typed;
        }
        jdbi.useHandle(handle -> handle
                .createQuery("SELECT item_id, original_due_date, amount FROM statement_still_to_come WHERE item_id IN (<ids>)")
                .bindList("ids", List.copyOf(itemIds))
                .map((rs, ctx) -> typed.put(new ItemDate(rs.getLong("item_id"), LocalDate.parse(rs.getString("original_due_date"))),
                        rs.getLong("amount")))
                .list());
        return typed;
    }

    /** Types still to come for a budget's date, or with null goes back to working it out. */
    public void setTypedStill(ItemDate date, Long cents) {
        jdbi.useHandle(handle -> {
            if (cents == null) {
                handle.createUpdate("DELETE FROM statement_still_to_come WHERE item_id = :itemId AND original_due_date = :date")
                        .bind("itemId", date.itemId())
                        .bind("date", date.scheduledDate().toString())
                        .execute();
                return;
            }
            handle.createUpdate("""
                            INSERT INTO statement_still_to_come (item_id, original_due_date, amount) VALUES (:itemId, :date, :amount)
                            ON CONFLICT (item_id, original_due_date) DO UPDATE SET amount = excluded.amount
                            """)
                    .bind("itemId", date.itemId())
                    .bind("date", date.scheduledDate().toString())
                    .bind("amount", cents)
                    .execute();
        });
    }

    /** Removes an upload and its lines. What it changed stays, as for a pay stub; Undo brings it back. */
    public boolean delete(long id) {
        return jdbi.withHandle(handle -> handle.createUpdate("DELETE FROM statement_uploads WHERE id = :id").bind("id", id).execute()) > 0;
    }

    public void markApplied(long uploadId) {
        jdbi.useHandle(handle -> handle.createUpdate("UPDATE statement_uploads SET applied_at = :now WHERE id = :id")
                .bind("now", clock.instant().toString())
                .bind("id", uploadId)
                .execute());
    }

    /** Every payee's remembered category and tags, by key. */
    public Map<String, PayeeRule> payeeRules() {
        return jdbi.withHandle(handle -> {
            Map<String, List<Long>> tags = new HashMap<>();
            handle.createQuery("SELECT payee_key, tag_id FROM payee_rule_tags ORDER BY tag_id")
                    .map((rs, ctx) -> tags.computeIfAbsent(rs.getString("payee_key"), key -> new ArrayList<>()).add(rs.getLong("tag_id")))
                    .list();
            Map<String, PayeeRule> rules = new HashMap<>();
            handle.createQuery("SELECT payee_key, category_id FROM payee_rules")
                    .map((rs, ctx) -> rules.put(rs.getString("payee_key"),
                            new PayeeRule(nullableLong(rs, "category_id"), List.copyOf(tags.getOrDefault(rs.getString("payee_key"), List.of())))))
                    .list();
            return rules;
        });
    }

    /** Remembers what a payee was given; given nothing, it is forgotten. */
    public void rememberPayee(String payeeKey, Long categoryId, List<Long> tagIds) {
        if (payeeKey.isEmpty()) {
            return;
        }
        jdbi.useTransaction(handle -> {
            handle.createUpdate("DELETE FROM payee_rules WHERE payee_key = :key").bind("key", payeeKey).execute();
            if (categoryId == null && tagIds.isEmpty()) {
                return;
            }
            handle.createUpdate("INSERT INTO payee_rules (payee_key, category_id) VALUES (:key, :categoryId)")
                    .bind("key", payeeKey)
                    .bind("categoryId", categoryId)
                    .execute();
            for (long tagId : tagIds) {
                handle.createUpdate("INSERT INTO payee_rule_tags (payee_key, tag_id) VALUES (:key, :tagId)")
                        .bind("key", payeeKey)
                        .bind("tagId", tagId)
                        .execute();
            }
        });
    }

    /** Which of these item dates a statement set, and to what. */
    public Map<ItemDate, Replaced> replaced(Collection<Long> itemIds) {
        Map<ItemDate, Replaced> replaced = new HashMap<>();
        if (itemIds.isEmpty()) {
            return replaced;
        }
        jdbi.useHandle(handle -> handle
                .createQuery("""
                        SELECT item_id, original_due_date, base_amount, applied_amount, actual_amount, still_to_come
                        FROM statement_replacements WHERE item_id IN (<ids>)
                        """)
                .bindList("ids", List.copyOf(itemIds))
                .map((rs, ctx) -> replaced.put(
                        new ItemDate(rs.getLong("item_id"), LocalDate.parse(rs.getString("original_due_date"))), replaced(rs)))
                .list());
        return replaced;
    }

    /** The item dates a statement set from {@code from} through {@code to}, by their scheduled date. */
    public Map<ItemDate, Replaced> replacedBetween(LocalDate from, LocalDate to) {
        Map<ItemDate, Replaced> replaced = new HashMap<>();
        jdbi.useHandle(handle -> handle
                .createQuery("""
                        SELECT item_id, original_due_date, base_amount, applied_amount, actual_amount, still_to_come
                        FROM statement_replacements WHERE original_due_date BETWEEN :from AND :to
                        """)
                .bind("from", from.toString())
                .bind("to", to.toString())
                .map((rs, ctx) -> replaced.put(
                        new ItemDate(rs.getLong("item_id"), LocalDate.parse(rs.getString("original_due_date"))), replaced(rs)))
                .list());
        return replaced;
    }

    private static Replaced replaced(ResultSet rs) throws SQLException {
        return new Replaced(rs.getLong("base_amount"), rs.getLong("applied_amount"), nullableLong(rs, "actual_amount"),
                nullableLong(rs, "still_to_come"));
    }

    public void saveReplaced(ItemDate date, Replaced replaced) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        INSERT INTO statement_replacements
                            (item_id, original_due_date, base_amount, applied_amount, applied_at, actual_amount, still_to_come)
                        VALUES (:itemId, :date, :base, :applied, :now, :actual, :still)
                        ON CONFLICT (item_id, original_due_date) DO UPDATE SET
                            base_amount = excluded.base_amount, applied_amount = excluded.applied_amount, applied_at = excluded.applied_at,
                            actual_amount = excluded.actual_amount, still_to_come = excluded.still_to_come
                        """)
                .bind("itemId", date.itemId())
                .bind("date", date.scheduledDate().toString())
                .bind("base", replaced.baseCents())
                .bind("applied", replaced.appliedCents())
                .bind("actual", replaced.actualCents())
                .bind("still", replaced.stillCents())
                .bind("now", clock.instant().toString())
                .execute());
    }

    /** Forgets that a statement set an item's date, which is back to what was budgeted. */
    public void deleteReplaced(ItemDate date) {
        jdbi.useHandle(handle -> handle.createUpdate("DELETE FROM statement_replacements WHERE item_id = :itemId AND original_due_date = :date")
                .bind("itemId", date.itemId())
                .bind("date", date.scheduledDate().toString())
                .execute());
    }

    private static void insertTags(Handle handle, long lineId, List<Long> tagIds) {
        for (long tagId : tagIds) {
            handle.createUpdate("INSERT OR IGNORE INTO statement_upload_line_tags (line_id, tag_id) VALUES (:lineId, :tagId)")
                    .bind("lineId", lineId)
                    .bind("tagId", tagId)
                    .execute();
        }
    }

    /** Fills in each line's tags, in one query for all of them. */
    private static List<Line> withTags(Handle handle, List<Line> lines) {
        if (lines.isEmpty()) {
            return lines;
        }
        Map<Long, List<Long>> tags = new HashMap<>();
        handle.createQuery("SELECT line_id, tag_id FROM statement_upload_line_tags WHERE line_id IN (<ids>) ORDER BY tag_id")
                .bindList("ids", lines.stream().map(Line::id).toList())
                .map((rs, ctx) -> tags.computeIfAbsent(rs.getLong("line_id"), id -> new ArrayList<>()).add(rs.getLong("tag_id")))
                .list();
        return lines.stream()
                .map(line -> new Line(line.id(), line.uploadId(), line.txnDate(), line.postedDate(), line.description(), line.payeeKey(),
                        line.amountCents(), line.kind(), line.leftOut(), line.sure(), line.externalId(), line.duplicateOf(),
                        line.categoryId(), List.copyOf(tags.getOrDefault(line.id(), List.of())), line.oneOffEntryId(), line.addOneOff(),
                        line.match(), line.budgetItemId(), line.budgetDate()))
                .toList();
    }

    private static Upload upload(ResultSet rs) throws SQLException {
        String hash = rs.getString("hash");
        return new Upload(
                rs.getLong("id"),
                rs.getLong("account_id"),
                rs.getString("name"),
                choice(Format.class, rs.getString("format")),
                LocalDate.parse(rs.getString("from_date")),
                LocalDate.parse(rs.getString("to_date")),
                rs.getString("created_at"),
                rs.getString("applied_at"),
                hash,
                hash == null ? null : rs.getLong("size"),
                hash == null ? null : nullableInt(rs, "pages"),
                nullableLong(rs, "previous_balance"),
                nullableLong(rs, "new_balance"),
                nullableLong(rs, "statement_charges"),
                nullableLong(rs, "statement_credits"));
    }

    private static Line line(ResultSet rs) throws SQLException {
        String posted = rs.getString("posted_date");
        String budgetDate = rs.getString("budget_date");
        return new Line(
                rs.getLong("id"),
                rs.getLong("upload_id"),
                LocalDate.parse(rs.getString("txn_date")),
                posted == null ? null : LocalDate.parse(posted),
                rs.getString("description"),
                rs.getString("payee_key"),
                rs.getLong("amount"),
                choice(Kind.class, rs.getString("kind")),
                rs.getBoolean("left_out"),
                rs.getBoolean("sure"),
                rs.getString("external_id"),
                nullableLong(rs, "duplicate_of"),
                nullableLong(rs, "category_id"),
                List.of(),
                nullableLong(rs, "one_off_entry_id"),
                rs.getBoolean("add_one_off"),
                match(rs),
                nullableLong(rs, "budget_item_id"),
                budgetDate == null ? null : LocalDate.parse(budgetDate));
    }

    /** What the line was matched to; null when nothing is, or what it was matched to has since been deleted. */
    private static Match match(ResultSet rs) throws SQLException {
        Long itemId = nullableLong(rs, "matched_item_id");
        String date = rs.getString("matched_date");
        Long entryId = nullableLong(rs, "matched_entry_id");
        boolean applied = rs.getBoolean("match_applied");
        if (entryId != null) {
            return new Match(null, null, entryId, applied, Before.NOTHING);
        }
        if (itemId == null || date == null) {
            return null;
        }
        String paidDate = rs.getString("before_paid_date");
        return new Match(itemId, LocalDate.parse(date), null, applied, new Before(
                nullableLong(rs, "before_amount"), rs.getBoolean("before_paid"), paidDate == null ? null : LocalDate.parse(paidDate),
                nullableLong(rs, "before_projected")));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    /** A stored code back as its enum; the table's own checks keep anything else out. */
    private static <E extends Enum<E>> E choice(Class<E> type, String code) {
        for (E constant : type.getEnumConstants()) {
            if (Inputs.code(constant).equals(code)) {
                return constant;
            }
        }
        throw new IllegalStateException("A stored statement upload has a value this version does not know");
    }
}
