package app.budget.server.buckets;

import app.budget.engine.Money;
import app.budget.engine.buckets.GoalPace;
import app.budget.server.accounts.Account;
import app.budget.server.accounts.AccountType;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.buckets.BucketsRepository.Bucket;
import app.budget.server.buckets.BucketsRepository.Change;
import app.budget.server.buckets.BucketsRepository.Fields;
import app.budget.server.categories.CategoriesRepository;
import app.budget.server.categories.TagsRepository;
import app.budget.server.entries.EntriesRepository;
import app.budget.server.entries.EntryKind;
import app.budget.server.ledger.Ledger;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
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
    /** What an amount set for a day was kept under before V37, as a difference. */
    private static final String ADJUSTED_NOTE = "Amount adjusted";
    private static final String SET_NOTE = "Amount set";
    private static final int MAX_TAGS = 20;
    private static final String CHOOSE_TAGS = "Please choose tags from the list.";
    private static final String IN_USE =
            "A recurring item still puts money in this bucket. Point that somewhere else first.";

    private final BucketsRepository buckets;
    private final AccountsRepository accounts;
    private final Ledger ledger;
    private final Clock clock;
    private final CategoriesRepository categories;
    private final TagsRepository tags;
    private final EntriesRepository entries;

    public BucketsRoutes(
            BucketsRepository buckets, AccountsRepository accounts, Ledger ledger, Clock clock, CategoriesRepository categories,
            TagsRepository tags, EntriesRepository entries) {
        this.entries = entries;
        this.buckets = buckets;
        this.accounts = accounts;
        this.ledger = ledger;
        this.clock = clock;
        this.categories = categories;
        this.tags = tags;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/buckets", ctx -> ApiResponse.ok(ctx, views(buckets.all())));
        routes.get("/api/goal-pace", this::goalPace);
        routes.post("/api/buckets", this::create);
        routes.get("/api/buckets/{id}", ctx -> ApiResponse.ok(ctx, detail(Inputs.id(ctx, "id"))));
        routes.put("/api/buckets/{id}", this::update);
        routes.delete("/api/buckets/{id}", this::delete);
        routes.post("/api/buckets/{id}/changes", this::addChange);
        routes.put("/api/buckets/{id}/changes/{changeId}", this::updateChange);
        routes.delete("/api/buckets/{id}/changes/{changeId}", this::deleteChange);
        routes.post("/api/buckets/{id}/moves-out", this::addMoveOut);
        routes.put("/api/buckets/{id}/moves-out/{changeId}", this::updateMoveOut);
        routes.get("/api/buckets/{id}/held", this::heldOn);
        routes.put("/api/buckets/{id}/held", this::setHeld);
    }

    /**
     * Request body for adding or changing a bucket. Amounts are whole cents.
     *
     * @param accountId     when adding: the checking or savings account it sits in. It cannot be changed later.
     * @param startingCents when adding: what is already set aside, if anything
     * @param goalDate      the day the goal is to be reached by, if there is one; it means nothing without a goal
     * @param openedOn      the day it counts from; left out, a new bucket counts from today and an existing one keeps its day
     * @param stopAtGoal    true to stop recurring bucket moves into it once it holds its goal; left out, a new
     *                      bucket keeps going and an existing one stays as it is
     */
    public record BucketInput(
            Long accountId,
            String name,
            String color,
            Long goalCents,
            String note,
            Boolean archived,
            Long startingCents,
            LocalDate openedOn,
            LocalDate goalDate,
            Boolean stopAtGoal) {}

    /** Request body for putting money into (+) or taking it out of (−) a bucket by hand. */
    public record ChangeInput(LocalDate date, Long amountCents, String note) {}

    /** Request body for what a bucket held at the end of a day: today, or earlier to build the balance up from then. */
    public record HeldInput(LocalDate date, Long amountCents) {}

    /**
     * Request body for money moved out of a bucket to money not in a bucket (Rachel, 2026-10-20).
     *
     * @param amountCents what is moved out, more than 0 and no more than the bucket holds that day
     * @param note              what it is, as it is listed under Income
     * @param reimbursesEntryId the expense it pays back, when the bucket pays yourself back for one (Rachel,
     *                          2026-10-21); {@code null} for none
     */
    public record MoveOutInput(
            LocalDate date, Long amountCents, String note, Long categoryId, List<Long> tagIds, Long reimbursesEntryId) {}

    /** A move out of a bucket as it was saved. */
    public record MovedOut(long id, BucketDetail bucket) {}

    /** What a bucket held at the end of {@code date}, counting everything scheduled into and out of it up to then. */
    public record Held(LocalDate date, long heldCents) {}

    /**
     * A bucket as the API returns it.
     *
     * @param balanceCents what it holds today; zero once archived, when it no longer holds anything back
     * @param goalProgress how it stands against its goal; {@code null} unless it has both a goal and a date for it
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
            long balanceCents,
            LocalDate goalDate,
            boolean stopAtGoal,
            GoalProgress goalProgress) {}

    /**
     * Whether what is already scheduled reaches the goal by its date, and what it would take if not.
     *
     * @param projectedCents       what the bucket is projected to hold at the end of the goal date
     * @param shortCents           how much of the goal is still missing then; zero when it is on track
     * @param paydaysLeft          paydays from tomorrow to the goal date
     * @param morePerPaycheckCents what to add every paycheck to close the gap; {@code null} when
     *                             nothing is missing or no payday is left
     * @param morePerMonthCents    the same, set aside monthly; {@code null} when nothing is missing
     *                             or the date has passed
     */
    public record GoalProgress(
            long projectedCents, long shortCents, int paydaysLeft, Long morePerPaycheckCents, Long morePerMonthCents) {}

    /** @param changes what the user moved in and out by hand, newest first */
    public record BucketDetail(BucketView bucket, List<Change> changes) {}

    public record Deleted(boolean deleted) {}

    /**
     * What a goal would take, before it is saved: for a form to say "set aside this much every
     * paycheck" while the goal and its date are still being typed. With {@code bucketId} it
     * counts what is already scheduled into that bucket; without, it starts from
     * {@code heldCents} alone (a bucket that does not exist yet has nothing scheduled).
     */
    private void goalPace(Context ctx) {
        long goalCents = Inputs.amount(longParam(ctx, "goalCents"), "The goal");
        LocalDate by = Inputs.dateParam(ctx, "goalDate", null);
        if (by == null || !Inputs.inSupportedYears(by)) {
            throw ApiException.badRequest("Please choose the date for this goal.");
        }
        Long bucketId = longParam(ctx, "bucketId");
        Long heldParam = longParam(ctx, "heldCents");
        Long heldCents = heldParam == null ? null : Inputs.amount(heldParam, "The amount in the bucket");
        LocalDate today = LocalDate.now(clock);
        long projected;
        if (bucketId == null) {
            projected = heldCents == null ? 0 : heldCents;
        } else {
            if (buckets.find(bucketId).isEmpty()) {
                throw ApiException.notFound();
            }
            long heldToday = ledger.bucketBalances(today).getOrDefault(bucketId, 0L);
            long then = by.isAfter(today) ? ledger.bucketBalances(by).getOrDefault(bucketId, 0L) : heldToday;
            // A different amount typed into the form moves the whole projection by the difference.
            projected = then + (heldCents == null ? 0 : heldCents - heldToday);
        }
        ApiResponse.ok(ctx, pace(goalCents, by, today, Money.ofCents(projected)));
    }

    /** An optional whole-number query parameter. */
    private static Long longParam(Context ctx, String name) {
        String value = ctx.queryParam(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Please send " + name + " as a whole number.");
        }
    }

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

    /**
     * Changes money put in or taken out by hand, or an amount set for a day: its date and amount, and for money put in
     * or taken out its note. More set aside than before is checked against the account, as adding it is. 404 for a
     * change that isn't there or was moved out to spend (changed as a move out); 409 for a day another amount is set for.
     */
    private void updateChange(Context ctx) {
        long id = Inputs.id(ctx, "id");
        long changeId = Inputs.id(ctx, "changeId");
        Bucket bucket = buckets.find(id).orElseThrow(ApiException::notFound);
        Change before = buckets.changes(id).stream().filter(change -> change.id() == changeId && !change.movedOut()).findFirst()
                .orElseThrow(ApiException::notFound);
        ChangeInput input = Inputs.body(ctx, ChangeInput.class);
        if (input.date() == null || !Inputs.inSupportedYears(input.date())) {
            throw ApiException.badRequest("Please choose the date.");
        }
        boolean set = before.setToCents() != null;
        if (set && input.date().isAfter(LocalDate.now(clock))) {
            throw ApiException.badRequest("Please choose today or an earlier day for that amount.");
        }
        long amount = set ? Inputs.amount(input.amountCents(), "The amount in the bucket")
                : Inputs.signedAmount(input.amountCents(), "The amount");
        if (!set && amount == 0) {
            throw ApiException.badRequest("Please enter how much to put in or take out.");
        }
        String note = Inputs.optionalText(input.note(), "the note", NOTE_MAX);
        // Only what it sets aside beyond what it did before needs room in the account.
        long more = amount - (set ? before.setToCents() : before.amountCents());
        if (more > 0) {
            LocalDate today = LocalDate.now(clock);
            requireRoomFor(accounts.find(bucket.accountId()).orElseThrow(ApiException::notFound), more,
                    input.date().isAfter(today) ? input.date() : today);
        }
        switch (buckets.updateChange(id, changeId, input.date(), amount, note)) {
            case NOT_FOUND -> throw ApiException.notFound();
            case SAME_DAY -> throw ApiException.conflict(
                    "There's already an amount set for that day. Please change that one, or choose another date.");
            case DONE -> ApiResponse.ok(ctx, detail(id));
        }
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

    /**
     * Moves money out of a bucket to money not in a bucket (Rachel, 2026-10-20: "I can't transfer within
     * an account from a bucket to general fund as income in pay period"). The account's balance stays
     * as it is; what is available goes up, and the pay period lists it under Income. 201 with its id.
     */
    private void addMoveOut(Context ctx) {
        long id = Inputs.id(ctx, "id");
        Bucket bucket = buckets.find(id).orElseThrow(ApiException::notFound);
        MoveOutInput input = Inputs.body(ctx, MoveOutInput.class);
        MoveOut move = moveOut(bucket, input, 0);
        long changeId = buckets.addMoveOut(
                id, move.date(), move.amountCents(), move.note(), move.categoryId(), move.tagIds(), move.reimbursesEntryId());
        entries.settle(move.reimbursesEntryId());
        ApiResponse.send(ctx, 201, new MovedOut(changeId, detail(id)));
    }

    /** Changes a move out of a bucket. 404 when that bucket has no such move. */
    private void updateMoveOut(Context ctx) {
        long id = Inputs.id(ctx, "id");
        long changeId = Inputs.id(ctx, "changeId");
        Bucket bucket = buckets.find(id).orElseThrow(ApiException::notFound);
        Change before = buckets.changes(id).stream().filter(change -> change.id() == changeId).findFirst()
                .orElseThrow(ApiException::notFound);
        MoveOutInput input = Inputs.body(ctx, MoveOutInput.class);
        // What the move already took out is back in the bucket while it is checked again.
        MoveOut move = moveOut(bucket, input, input.date() != null && !input.date().isBefore(before.date()) ? -before.amountCents() : 0);
        if (!buckets.updateMoveOut(
                id, changeId, move.date(), move.amountCents(), move.note(), move.categoryId(), move.tagIds(), move.reimbursesEntryId())) {
            throw ApiException.notFound();
        }
        entries.settle(move.reimbursesEntryId());
        ApiResponse.ok(ctx, new MovedOut(changeId, detail(id)));
    }

    private record MoveOut(LocalDate date, long amountCents, String note, Long categoryId, List<Long> tagIds, Long reimbursesEntryId) {}

    /**
     * Checks a move out of a bucket.
     *
     * @param givenBack what a move being changed took out, counted back into the bucket for the check
     */
    private MoveOut moveOut(Bucket bucket, MoveOutInput input, long givenBack) {
        if (input.date() == null || !Inputs.inSupportedYears(input.date())) {
            throw ApiException.badRequest("Please choose the date.");
        }
        long amount = Inputs.amount(input.amountCents(), "The amount");
        if (amount == 0) {
            throw ApiException.badRequest("Please enter how much to move out of the bucket, more than $0.00.");
        }
        String note = Inputs.text(input.note(), "what this is", NOTE_MAX);
        if (input.categoryId() != null && categories.find(input.categoryId()).isEmpty()) {
            throw ApiException.badRequest("Please choose the category from the list.");
        }
        List<Long> tagIds = Inputs.ids(input.tagIds(), MAX_TAGS, CHOOSE_TAGS);
        if (!tags.allExist(tagIds)) {
            throw ApiException.badRequest(CHOOSE_TAGS);
        }
        if (input.reimbursesEntryId() != null
                && entries.find(input.reimbursesEntryId()).filter(entry -> entry.kind() == EntryKind.MONEY_OUT).isEmpty()) {
            throw ApiException.badRequest("Please choose the expense this pays back from the list.");
        }
        long held = ledger.bucketBalances(input.date()).getOrDefault(bucket.id(), 0L) + givenBack;
        if (amount > held) {
            throw ApiException.conflict(
                    "That is more than " + bucket.name() + " holds on that day. Please enter a smaller amount, or choose a later day.");
        }
        return new MoveOut(input.date(), amount, note, input.categoryId(), tagIds, input.reimbursesEntryId());
    }

    private void heldOn(Context ctx) {
        long id = Inputs.id(ctx, "id");
        if (buckets.find(id).isEmpty()) {
            throw ApiException.notFound();
        }
        LocalDate on = Inputs.dateParam(ctx, "on", LocalDate.now(clock));
        if (!Inputs.inSupportedYears(on)) {
            throw ApiException.badRequest("Please choose the date.");
        }
        ApiResponse.ok(ctx, new Held(on, ledger.bucketBalances(on).getOrDefault(id, 0L)));
    }

    /**
     * Sets what a bucket held at the end of a day. The amount itself is kept, so everything scheduled after it
     * builds on it, and it still holds when something before it is entered or moved later (Rachel, 2026-10-20:
     * "I have been back entering balances and changing dates, but this should adjust"). It was first kept as the
     * difference from what the bucket held when it was set, which froze.
     */
    private void setHeld(Context ctx) {
        long id = Inputs.id(ctx, "id");
        HeldInput input = Inputs.body(ctx, HeldInput.class);
        LocalDate today = LocalDate.now(clock);
        if (input.date() == null || !Inputs.inSupportedYears(input.date()) || input.date().isAfter(today)) {
            throw ApiException.badRequest("Please choose today or an earlier day for that amount.");
        }
        long amount = Inputs.amount(input.amountCents(), "The amount in the bucket");
        Bucket bucket = buckets.find(id).orElseThrow(ApiException::notFound);
        long difference = amount - ledger.bucketBalances(input.date()).getOrDefault(id, 0L);
        if (difference > 0) {
            requireRoomFor(accounts.find(bucket.accountId()).orElseThrow(ApiException::notFound), difference, today);
        }
        buckets.setHeld(id, input.date(), amount, SET_NOTE, ADJUSTED_NOTE);
        ApiResponse.ok(ctx, detail(id));
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
        LocalDate today = LocalDate.now(clock);
        Map<Long, Long> held = chosen.isEmpty() ? Map.of() : ledger.bucketBalances(today);
        // Buckets often share a goal date (the end of the year, say), so each date is projected once.
        Map<LocalDate, Map<Long, Long>> heldOn = new HashMap<>();
        return chosen.stream()
                .map(bucket -> new BucketView(
                        bucket.id(), bucket.accountId(), bucket.name(), bucket.color(), bucket.goalCents(), bucket.note(),
                        bucket.archived(), bucket.openedOn(), held.getOrDefault(bucket.id(), 0L), bucket.goalDate(), bucket.stopAtGoal(),
                        goalProgress(bucket, today, held, heldOn)))
                .toList();
    }

    private GoalProgress goalProgress(Bucket bucket, LocalDate today, Map<Long, Long> heldToday, Map<LocalDate, Map<Long, Long>> heldOn) {
        if (bucket.archived() || bucket.goalCents() == null || bucket.goalDate() == null) {
            return null;
        }
        LocalDate by = bucket.goalDate();
        // A date already gone by has nothing left to project: what it holds now is what it reached.
        Map<Long, Long> then = by.isAfter(today) ? heldOn.computeIfAbsent(by, ledger::bucketBalances) : heldToday;
        return pace(bucket.goalCents(), by, today, Money.ofCents(then.getOrDefault(bucket.id(), 0L)));
    }

    /** How a goal stands on {@code today} given what the bucket is projected to hold on its date. */
    private GoalProgress pace(long goalCents, LocalDate by, LocalDate today, Money projected) {
        Money shortBy = GoalPace.shortfall(Money.ofCents(goalCents), projected);
        int paydays = by.isAfter(today) ? ledger.paydaysBetween(today.plusDays(1), by) : 0;
        boolean missing = shortBy.cents() > 0;
        return new GoalProgress(
                projected.cents(),
                shortBy.cents(),
                paydays,
                missing && paydays > 0 ? (Long) GoalPace.each(shortBy, paydays).cents() : null,
                missing && by.isAfter(today) ? (Long) GoalPace.each(shortBy, monthsLeft(today, by)).cents() : null);
    }

    /** Whole months from {@code today} to {@code by}, counting a part month as one: there is always at least one. */
    private static int monthsLeft(LocalDate today, LocalDate by) {
        int months = (int) ChronoUnit.MONTHS.between(today, by);
        return today.plusMonths(months).isBefore(by) ? months + 1 : Math.max(1, months);
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
        if (input.goalDate() != null && !Inputs.inSupportedYears(input.goalDate())) {
            throw ApiException.badRequest("Please choose the date to reach the goal by.");
        }
        Long goal = input.goalCents() == null ? null : (Long) Inputs.amount(input.goalCents(), "The goal");
        return new Fields(
                Inputs.text(input.name(), "a name for the bucket", NAME_MAX),
                Inputs.color(input.color()),
                // A goal of nothing is no goal.
                goal == null || goal == 0 ? null : goal,
                Inputs.optionalText(input.note(), "the note", NOTE_MAX),
                Boolean.TRUE.equals(input.archived()),
                input.openedOn(),
                // A date for a goal that is not there says nothing.
                goal == null || goal == 0 ? null : input.goalDate(),
                input.stopAtGoal());
    }
}
