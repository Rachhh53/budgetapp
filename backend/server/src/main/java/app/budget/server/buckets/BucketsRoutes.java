package app.budget.server.buckets;

import app.budget.server.accounts.Account;
import app.budget.server.accounts.AccountType;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.buckets.BucketsRepository.Bucket;
import app.budget.server.buckets.BucketsRepository.Change;
import app.budget.server.buckets.BucketsRepository.Fields;
import app.budget.server.ledger.Ledger;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Buckets and what the user moves in and out of them by hand: {@code /api/buckets},
 * {@code /api/buckets/{id}} and {@code /api/buckets/{id}/changes}.
 */
public final class BucketsRoutes {

    private static final int NAME_MAX = 60;
    private static final int NOTE_MAX = 200;
    private static final String STARTING_NOTE = "Starting amount";
    private static final String IN_USE =
            "A recurring item still puts money in this bucket. Point that somewhere else first.";

    private final BucketsRepository buckets;
    private final AccountsRepository accounts;
    private final Ledger ledger;
    private final Clock clock;

    public BucketsRoutes(BucketsRepository buckets, AccountsRepository accounts, Ledger ledger, Clock clock) {
        this.buckets = buckets;
        this.accounts = accounts;
        this.ledger = ledger;
        this.clock = clock;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/buckets", ctx -> ApiResponse.ok(ctx, views(buckets.all())));
        routes.post("/api/buckets", this::create);
        routes.get("/api/buckets/{id}", ctx -> ApiResponse.ok(ctx, detail(Inputs.id(ctx, "id"))));
        routes.put("/api/buckets/{id}", this::update);
        routes.delete("/api/buckets/{id}", this::delete);
        routes.post("/api/buckets/{id}/changes", this::addChange);
        routes.delete("/api/buckets/{id}/changes/{changeId}", this::deleteChange);
    }

    /**
     * Request body for adding or changing a bucket. Amounts are whole cents.
     *
     * @param accountId     when adding: the checking or savings account it sits in. It cannot be changed later.
     * @param startingCents when adding: what is already set aside, if anything
     * @param openedOn      the day it counts from; left out, a new bucket counts from today and an existing one keeps its day
     */
    public record BucketInput(
            Long accountId,
            String name,
            String color,
            Long goalCents,
            String note,
            Boolean archived,
            Long startingCents,
            LocalDate openedOn) {}

    /** Request body for putting money into (+) or taking it out of (−) a bucket by hand. */
    public record ChangeInput(LocalDate date, Long amountCents, String note) {}

    /**
     * A bucket as the API returns it.
     *
     * @param balanceCents what it holds today; zero once archived, when it no longer holds anything back
     */
    public record BucketView(
            long id,
            long accountId,
            String name,
            String color,
            Long goalCents,
            String note,
            boolean archived,
            LocalDate openedOn,
            long balanceCents) {}

    /** @param changes what the user moved in and out by hand, newest first */
    public record BucketDetail(BucketView bucket, List<Change> changes) {}

    public record Deleted(boolean deleted) {}

    private void create(Context ctx) {
        BucketInput input = Inputs.body(ctx, BucketInput.class);
        Fields fields = fields(input);
        Account account = input.accountId() == null ? null : accounts.find(input.accountId()).orElse(null);
        if (account == null || !AccountType.holdsCash(account)) {
            throw ApiException.badRequest("Please choose a checking or savings account for this bucket.");
        }
        long starting = input.startingCents() == null ? 0 : Inputs.amount(input.startingCents(), "The starting amount");
        requireFreeName(account.id(), fields.name(), null);
        requireRoomFor(account, starting, LocalDate.now(clock));
        long id = buckets.create(account.id(), fields);
        if (starting > 0) {
            buckets.addChange(id, LocalDate.now(clock), starting, STARTING_NOTE);
        }
        ApiResponse.send(ctx, 201, detail(id));
    }

    private void update(Context ctx) {
        long id = Inputs.id(ctx, "id");
        Fields fields = fields(Inputs.body(ctx, BucketInput.class));
        Bucket existing = buckets.find(id).orElseThrow(ApiException::notFound);
        requireFreeName(existing.accountId(), fields.name(), id);
        if (fields.archived() && !existing.archived() && buckets.isUsedByItems(id)) {
            throw ApiException.conflict(IN_USE);
        }
        buckets.update(id, fields);
        ApiResponse.ok(ctx, detail(id));
    }

    private void delete(Context ctx) {
        long id = Inputs.id(ctx, "id");
        if (buckets.find(id).isEmpty()) {
            throw ApiException.notFound();
        }
        if (buckets.isUsedByItems(id)) {
            throw ApiException.conflict(IN_USE);
        }
        buckets.delete(id);
        ApiResponse.ok(ctx, new Deleted(true));
    }

    private void addChange(Context ctx) {
        long id = Inputs.id(ctx, "id");
        ChangeInput input = Inputs.body(ctx, ChangeInput.class);
        if (input.date() == null || !Inputs.inSupportedYears(input.date())) {
            throw ApiException.badRequest("Please choose the date.");
        }
        long amount = Inputs.signedAmount(input.amountCents(), "The amount");
        if (amount == 0) {
            throw ApiException.badRequest("Please enter how much to put in or take out.");
        }
        String note = Inputs.optionalText(input.note(), "the note", NOTE_MAX);
        Bucket bucket = buckets.find(id).orElseThrow(ApiException::notFound);
        // Checked as things stand now, or on the day itself for a move dated ahead.
        LocalDate today = LocalDate.now(clock);
        requireRoomFor(
                accounts.find(bucket.accountId()).orElseThrow(ApiException::notFound),
                amount,
                input.date().isAfter(today) ? input.date() : today);
        buckets.addChange(id, input.date(), amount, note);
        ApiResponse.send(ctx, 201, detail(id));
    }

    private void deleteChange(Context ctx) {
        long id = Inputs.id(ctx, "id");
        if (!buckets.deleteChange(id, Inputs.id(ctx, "changeId"))) {
            throw ApiException.notFound();
        }
        ApiResponse.ok(ctx, detail(id));
    }

    private BucketDetail detail(long id) {
        Bucket bucket = buckets.find(id).orElseThrow(ApiException::notFound);
        return new BucketDetail(views(List.of(bucket)).getFirst(), buckets.changes(id));
    }

    private List<BucketView> views(List<Bucket> chosen) {
        Map<Long, Long> held = chosen.isEmpty() ? Map.of() : ledger.bucketBalances(LocalDate.now(clock));
        return chosen.stream()
                .map(bucket -> new BucketView(
                        bucket.id(), bucket.accountId(), bucket.name(), bucket.color(), bucket.goalCents(), bucket.note(),
                        bucket.archived(), bucket.openedOn(), held.getOrDefault(bucket.id(), 0L)))
                .toList();
    }

    /**
     * Buckets set aside money the account holds, so together they cannot hold more than it
     * does. Only what is put in by hand is refused here; a schedule that would overrun later
     * shows up as a negative "available" instead, since balances and schedules both change.
     * An account with no balance entered yet has nothing to check against.
     */
    private void requireRoomFor(Account account, long addingCents, LocalDate day) {
        if (addingCents <= 0) {
            return;
        }
        ledger.availableOn(account, day).filter(available -> addingCents > available).ifPresent(available -> {
            throw ApiException.conflict(
                    "That is more than this account has outside its buckets. Please enter a smaller amount, or update the account's balance first.");
        });
    }

    private void requireFreeName(long accountId, String name, Long exceptId) {
        if (buckets.nameTaken(accountId, name, exceptId)) {
            throw ApiException.conflict("This account already has a bucket with that name. Please choose another.");
        }
    }

    private Fields fields(BucketInput input) {
        // A bucket cannot start counting on a day that has not come: nothing could be shown in it until then.
        if (input.openedOn() != null
                && (!Inputs.inSupportedYears(input.openedOn()) || input.openedOn().isAfter(LocalDate.now(clock)))) {
            throw ApiException.badRequest("Please choose today or an earlier day for this bucket to count from.");
        }
        Long goal = input.goalCents() == null ? null : (Long) Inputs.amount(input.goalCents(), "The goal");
        return new Fields(
                Inputs.text(input.name(), "a name for the bucket", NAME_MAX),
                Inputs.color(input.color()),
                // A goal of nothing is no goal.
                goal == null || goal == 0 ? null : goal,
                Inputs.optionalText(input.note(), "the note", NOTE_MAX),
                Boolean.TRUE.equals(input.archived()),
                input.openedOn());
    }
}
