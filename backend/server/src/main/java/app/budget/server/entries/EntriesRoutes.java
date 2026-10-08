package app.budget.server.entries;

import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.buckets.BucketsRepository;
import app.budget.server.buckets.BucketsRepository.Bucket;
import app.budget.server.categories.CategoriesRepository;
import app.budget.server.categories.TagsRepository;
import app.budget.server.entries.EntriesRepository.Entry;
import app.budget.server.entries.EntriesRepository.Fields;
import app.budget.server.entries.EntriesRepository.Split;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** One-off money in, money out and transfers: {@code /api/entries} and {@code /api/entries/{id}}. */
public final class EntriesRoutes {

    private static final int TEXT_MAX = 120;
    private static final int NOTE_MAX = 500;
    private static final int MAX_TAGS = 20;
    private static final String CHOOSE_TAGS = "Please choose tags from the list.";
    private static final int MAX_SPLITS = 20;
    private static final String CHOOSE_BUCKETS = "Please choose each bucket once, from the list.";

    private final EntriesRepository entries;
    private final AccountsRepository accounts;
    private final CategoriesRepository categories;
    private final TagsRepository tags;
    private final BucketsRepository buckets;
    private final Clock clock;

    public EntriesRoutes(
            EntriesRepository entries,
            AccountsRepository accounts,
            CategoriesRepository categories,
            TagsRepository tags,
            BucketsRepository buckets,
            Clock clock) {
        this.entries = entries;
        this.accounts = accounts;
        this.categories = categories;
        this.tags = tags;
        this.buckets = buckets;
        this.clock = clock;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/entries", this::list);
        routes.post("/api/entries", this::create);
        routes.put("/api/entries/{id}", this::update);
        routes.delete("/api/entries/{id}", this::delete);
    }

    /** Request body for creating or changing an entry. Amounts are whole cents, never negative. */
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
            List<SplitInput> bucketSplits) {}

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

    private void create(Context ctx) {
        long id = entries.create(fields(Inputs.body(ctx, EntryInput.class)));
        ApiResponse.send(ctx, 201, view(id));
    }

    private void update(Context ctx) {
        long id = Inputs.id(ctx, "id");
        if (!entries.update(id, fields(Inputs.body(ctx, EntryInput.class)))) {
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

    private Entry view(long id) {
        return entries.find(id).orElseThrow(ApiException::notFound);
    }

    private Fields fields(EntryInput input) {
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
                splits(input.bucketSplits(), kind, amount, toAccountId == null ? input.accountId() : toAccountId));
    }

    /**
     * What is set aside in buckets out of money arriving in {@code arrivesIn}. Money going out
     * has nothing to set aside.
     */
    private List<Split> splits(List<SplitInput> inputs, EntryKind kind, long amount, long arrivesIn) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        if (kind == EntryKind.MONEY_OUT) {
            throw ApiException.badRequest("Only money coming in can be set aside in a bucket.");
        }
        if (inputs.size() > MAX_SPLITS || inputs.stream().anyMatch(split -> split == null || split.bucketId() == null)
                || inputs.stream().map(SplitInput::bucketId).distinct().count() != inputs.size()) {
            throw ApiException.badRequest(CHOOSE_BUCKETS);
        }
        List<Split> splits = new ArrayList<>();
        long total = 0;
        for (SplitInput input : inputs) {
            Bucket bucket = buckets.find(input.bucketId()).orElse(null);
            if (bucket == null || bucket.archived() || bucket.accountId() != arrivesIn) {
                throw ApiException.badRequest("Please choose buckets in the account this money goes into.");
            }
            long cents = Inputs.amount(input.amountCents(), "The amount to set aside");
            if (cents == 0) {
                throw ApiException.badRequest("Please enter how much to set aside, or remove that bucket.");
            }
            total += cents;
            splits.add(new Split(bucket.id(), cents));
        }
        if (total > amount) {
            throw ApiException.badRequest("That sets aside more than the amount itself. Please lower one of the buckets.");
        }
        return splits;
    }
}
