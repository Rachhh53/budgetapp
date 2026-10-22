package app.budget.server.entries;

import app.budget.engine.reports.Reimbursement.Status;
import app.budget.server.accounts.AccountType;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Archiving;
import app.budget.server.api.Inputs;
import app.budget.server.buckets.BucketsRepository;
import app.budget.server.buckets.BucketsRepository.Bucket;
import app.budget.server.buckets.BucketsRepository.MoveOut;
import app.budget.server.categories.CategoriesRepository;
import app.budget.server.categories.TagsRepository;
import app.budget.server.entries.EntriesRepository.Entry;
import app.budget.server.entries.EntriesRepository.Fields;
import app.budget.server.entries.EntriesRepository.Split;
import app.budget.server.ledger.Ledger;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One-off money in, money out and transfers: {@code /api/entries} and {@code /api/entries/{id}}, with
 * where an expense stands on being paid back ({@code /api/entries/{id}/reimbursement}).
 */
public final class EntriesRoutes {

    private static final int TEXT_MAX = 120;
    private static final int NOTE_MAX = 500;
    private static final int MAX_TAGS = 20;
    private static final String CHOOSE_TAGS = "Please choose tags from the list.";
    private static final int MAX_SPLITS = 20;
    private static final int CHECK_NUMBER_MAX = 20;
    private static final String CHOOSE_BUCKETS = "Please choose each bucket once, from the list.";
    private static final String CHOOSE_EXPENSE = "Please choose the expense this pays back from the list.";

    private final EntriesRepository entries;
    private final AccountsRepository accounts;
    private final CategoriesRepository categories;
    private final TagsRepository tags;
    private final BucketsRepository buckets;
    private final Ledger ledger;
    private final Clock clock;

    public EntriesRoutes(
            EntriesRepository entries,
            AccountsRepository accounts,
            CategoriesRepository categories,
            TagsRepository tags,
            BucketsRepository buckets,
            Ledger ledger,
            Clock clock) {
        this.entries = entries;
        this.accounts = accounts;
        this.categories = categories;
        this.tags = tags;
        this.buckets = buckets;
        this.ledger = ledger;
        this.clock = clock;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/entries", this::list);
        routes.post("/api/entries", this::create);
        routes.get("/api/entries/{id}", this::one);
        routes.put("/api/entries/{id}", this::update);
        routes.delete("/api/entries/{id}", this::delete);
        routes.put("/api/entries/{id}/reimbursement", this::setReimbursement);
        routes.get("/api/expense-years", ctx -> ApiResponse.ok(ctx, new Years(entries.yearsWithExpenses())));
        routes.get("/api/payees", ctx -> ApiResponse.ok(ctx, new Names(entries.payees(), entries.reimbursers())));
    }

    /**
     * Request body for creating or changing an entry. Amounts are whole cents, never negative.
     *
     * @param reimbursementStatus money going out: {@code none}, {@code to_submit}, {@code submitted} or
     *                            {@code reimbursed}; left out, it stays as it was (none, when new)
     * @param reimbursesEntryId   money coming in: the expense it pays back, if any
     */
    public record EntryInput(
            String kind,
            LocalDate date,
            Long amountCents,
            Boolean estimate,
            String description,
            String payee,
            Long categoryId,
            Long accountId,
            Long toAccountId,
            String note,
            List<Long> tagIds,
            List<SplitInput> bucketSplits,
            String checkNumber,
            String reimbursementStatus,
            Long reimbursesEntryId) {}

    /**
     * Request body for where an expense stands on being paid back.
     *
     * @param submittedOn the day it was sent in; kept only once it has been
     * @param to          who pays it back, e.g. "HSA"; optional
     */
    public record ReimbursementInput(String status, LocalDate submittedOn, String to) {}

    /**
     * One entry with what it is linked to.
     *
     * @param paybacks   an expense: the money that came in to pay it back, oldest first
     * @param reimburses money coming in: the expense it pays back; {@code null} when it pays none back
     * @param movesOut   an expense: the money moved out of a bucket to pay it back, oldest first
     */
    public record EntryDetail(Entry entry, List<Entry> paybacks, Entry reimburses, List<MoveOut> movesOut) {}

    /** @param years the years that have one-off spending in them, newest first */
    public record Years(List<Integer> years) {}

    /**
     * Names typed before, for the pickers that offer them again.
     *
     * @param payees      who one-off entries were paid to or came from
     * @param reimbursers who expenses were sent to for paying back
     */
    public record Names(List<String> payees, List<String> reimbursers) {}

    /** Part of money coming in to set aside in one bucket. */
    public record SplitInput(Long bucketId, Long amountCents) {}

    public record Deleted(boolean deleted) {}

    /** Entries between {@code from} and {@code to}; by default the last twelve months and the next twelve. */
    private void list(Context ctx) {
        LocalDate today = LocalDate.now(clock);
        LocalDate from = Inputs.dateParam(ctx, "from", today.minusYears(1));
        LocalDate to = Inputs.dateParam(ctx, "to", today.plusYears(1));
        Inputs.requireSensibleRange(from, to);
        ApiResponse.ok(ctx, entries.between(from, to));
    }

    private void one(Context ctx) {
        Entry entry = view(Inputs.id(ctx, "id"));
        ApiResponse.ok(ctx, new EntryDetail(
                entry,
                entry.kind() == EntryKind.MONEY_OUT ? entries.paybacksOf(entry.id()) : List.of(),
                entry.reimbursesEntryId() == null ? null : entries.find(entry.reimbursesEntryId()).orElse(null),
                entry.kind() == EntryKind.MONEY_OUT ? buckets.movesOutPayingBack(entry.id()) : List.of()));
    }

    private void create(Context ctx) {
        Fields fields = fields(Inputs.body(ctx, EntryInput.class), null);
        requireTheMoneyIsThere(fields, null);
        long id = entries.create(fields);
        ApiResponse.send(ctx, 201, view(id));
    }

    private void update(Context ctx) {
        long id = Inputs.id(ctx, "id");
        Entry existing = entries.find(id).orElseThrow(ApiException::notFound);
        Fields fields = fields(Inputs.body(ctx, EntryInput.class), id);
        requireTheMoneyIsThere(fields, existing);
        if (!entries.update(id, fields)) {
            throw ApiException.notFound();
        }
        ApiResponse.ok(ctx, view(id));
    }

    private void delete(Context ctx) {
        if (!entries.delete(Inputs.id(ctx, "id"))) {
            throw ApiException.notFound();
        }
        ApiResponse.ok(ctx, new Deleted(true));
    }

    private void setReimbursement(Context ctx) {
        long id = Inputs.id(ctx, "id");
        Entry existing = entries.find(id).orElseThrow(ApiException::notFound);
        ReimbursementInput input = Inputs.body(ctx, ReimbursementInput.class);
        if (existing.kind() != EntryKind.MONEY_OUT) {
            throw ApiException.conflict("Only money going out can be paid back.");
        }
        Status status = Inputs.choice(Status.class, input.status(), "where this stands");
        if (input.submittedOn() != null && !Inputs.inSupportedYears(input.submittedOn())) {
            throw ApiException.badRequest("Please choose the day it was sent in.");
        }
        // Something still to send in has no day it was sent, and something nobody pays back has nobody to name.
        boolean sent = status == Status.SUBMITTED || status == Status.REIMBURSED;
        entries.setReimbursement(
                id,
                status,
                sent ? input.submittedOn() : null,
                status == Status.NONE ? null : Inputs.optionalText(input.to(), "who pays this back", TEXT_MAX));
        ApiResponse.ok(ctx, view(id));
    }

    private Entry view(long id) {
        return entries.find(id).orElseThrow(ApiException::notFound);
    }

    /** @param id the entry being changed; {@code null} for a new one */
    private Fields fields(EntryInput input, Long id) {
        EntryKind kind = Inputs.choice(EntryKind.class, input.kind(), "money out, money in or transfer");
        if (input.date() == null || !Inputs.inSupportedYears(input.date())) {
            throw ApiException.badRequest("Please choose the date.");
        }
        long amount = Inputs.amount(input.amountCents(), "The amount");
        String description = Inputs.text(input.description(), "a few words about what this is", TEXT_MAX);
        if (input.accountId() == null || !accounts.exists(input.accountId())) {
            throw ApiException.badRequest(kind == EntryKind.MONEY_IN
                    ? "Please choose the account this money goes into."
                    : "Please choose the account this comes out of.");
        }
        Long toAccountId = null;
        if (kind == EntryKind.TRANSFER) {
            if (input.toAccountId() == null || !accounts.exists(input.toAccountId())) {
                throw ApiException.badRequest("Please choose the account this money goes to.");
            }
            if (input.toAccountId().equals(input.accountId())) {
                throw ApiException.badRequest("Please choose two different accounts.");
            }
            toAccountId = input.toAccountId();
        }
        if (input.categoryId() != null && categories.find(input.categoryId()).isEmpty()) {
            throw ApiException.badRequest("Please choose the category from the list.");
        }
        List<Long> tagIds = Inputs.ids(input.tagIds(), MAX_TAGS, CHOOSE_TAGS);
        if (!tags.allExist(tagIds)) {
            throw ApiException.badRequest(CHOOSE_TAGS);
        }
        return new Fields(
                kind,
                input.date(),
                amount,
                Boolean.TRUE.equals(input.estimate()),
                description,
                Inputs.optionalText(input.payee(), "the payee", TEXT_MAX),
                input.categoryId(),
                input.accountId(),
                toAccountId,
                Inputs.optionalText(input.note(), "the note", NOTE_MAX),
                tagIds,
                splits(input.bucketSplits(), kind, amount, input.date(), input.accountId(), toAccountId == null ? input.accountId() : toAccountId),
                // Only money leaving a checking account can have been paid by check; the form sends whatever was last typed.
                kind != EntryKind.MONEY_IN && isChecking(input.accountId())
                        ? Inputs.optionalText(input.checkNumber(), "the check number", CHECK_NUMBER_MAX)
                        : null,
                kind != EntryKind.MONEY_OUT || input.reimbursementStatus() == null
                        ? null
                        : Inputs.choice(Status.class, input.reimbursementStatus(), "where this stands on being paid back"),
                paysBack(input.reimbursesEntryId(), kind, id));
    }

    /** The expense that money coming in pays back: one that exists, is money going out, and is not itself. */
    private Long paysBack(Long expenseId, EntryKind kind, Long id) {
        if (expenseId == null || kind != EntryKind.MONEY_IN) {
            return null;
        }
        if (expenseId.equals(id) || entries.find(expenseId).filter(expense -> expense.kind() == EntryKind.MONEY_OUT).isEmpty()) {
            throw ApiException.badRequest(CHOOSE_EXPENSE);
        }
        return expenseId;
    }

    private boolean isChecking(long accountId) {
        return accounts.find(accountId)
                .filter(account -> account.type().equals(Inputs.code(AccountType.CHECKING)))
                .isPresent();
    }

    /**
     * Money that comes out of a bucket has to be in it: a bucket never goes below nothing.
     * The account itself is not checked. It can be overdrawn, as a real one can, and the
     * screens warn loudly when it is.
     *
     * @param replacing the entry being changed, whose own money is counted as still there
     */
    private void requireTheMoneyIsThere(Fields fields, Entry replacing) {
        if (fields.kind() == EntryKind.MONEY_IN) {
            return;
        }
        Set<Long> ids = buckets.all().stream()
                .filter(bucket -> !Archiving.awayOn(bucket.archivedOn(), fields.date()) && bucket.accountId() == fields.accountId())
                .map(Bucket::id)
                .collect(Collectors.toSet());
        if (fields.bucketSplits().stream().noneMatch(split -> ids.contains(split.bucketId()))) {
            return;
        }
        // What the entry being changed already took is still there to be taken again.
        boolean sameAccount = replacing != null && replacing.kind() != EntryKind.MONEY_IN
                && replacing.accountId() == fields.accountId() && !replacing.date().isAfter(fields.date());
        Map<Long, Long> held = ledger.bucketBalances(fields.date());
        for (Split split : fields.bucketSplits()) {
            if (!ids.contains(split.bucketId())) {
                continue;
            }
            long alreadyTaken = !sameAccount ? 0 : replacing.bucketSplits().stream()
                    .filter(taken -> taken.bucketId() == split.bucketId())
                    .mapToLong(Split::amountCents)
                    .sum();
            if (split.amountCents() > held.getOrDefault(split.bucketId(), 0L) + alreadyTaken) {
                throw ApiException.conflict(
                        "That is more than that bucket holds on that day. Please enter a smaller amount, or choose somewhere else for it to come out of.");
            }
        }
    }

    /**
     * The buckets an entry touches. A bucket in the account the money leaves is where it comes
     * out of; a bucket in the account it arrives in is where part of it is set aside. A bucket archived as of a later
     * day can still be named, for something from before it was archived.
     */
    private List<Split> splits(List<SplitInput> inputs, EntryKind kind, long amount, LocalDate date, long leaves, long arrivesIn) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        if (inputs.size() > MAX_SPLITS || inputs.stream().anyMatch(split -> split == null || split.bucketId() == null)
                || inputs.stream().map(SplitInput::bucketId).distinct().count() != inputs.size()) {
            throw ApiException.badRequest(CHOOSE_BUCKETS);
        }
        List<Split> splits = new ArrayList<>();
        long setAside = 0;
        long takenOut = 0;
        for (SplitInput input : inputs) {
            Bucket bucket = buckets.find(input.bucketId()).orElse(null);
            boolean comesOut = bucket != null && kind != EntryKind.MONEY_IN && bucket.accountId() == leaves;
            boolean goesIn = bucket != null && kind != EntryKind.MONEY_OUT && bucket.accountId() == arrivesIn;
            if (bucket == null || (date != null && Archiving.awayOn(bucket.archivedOn(), date)) || !(comesOut || goesIn)) {
                throw ApiException.badRequest(kind == EntryKind.MONEY_OUT
                        ? "Please choose a bucket in the account this comes out of."
                        : "Please choose buckets in the account this money goes into.");
            }
            long cents = Inputs.amount(input.amountCents(), comesOut ? "The amount from the bucket" : "The amount to set aside");
            if (cents == 0) {
                throw ApiException.badRequest("Please enter how much to set aside, or remove that bucket.");
            }
            if (comesOut) {
                takenOut += cents;
            } else {
                setAside += cents;
            }
            splits.add(new Split(bucket.id(), cents));
        }
        if (setAside > amount) {
            throw ApiException.badRequest("That sets aside more than the amount itself. Please lower one of the buckets.");
        }
        if (takenOut > amount) {
            throw ApiException.badRequest("That takes more out of buckets than the amount itself. Please lower one of the buckets.");
        }
        return splits;
    }
}
