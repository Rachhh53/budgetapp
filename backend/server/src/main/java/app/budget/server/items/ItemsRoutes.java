package app.budget.server.items;

import app.budget.engine.items.Placement;
import app.budget.engine.schedule.Frequency;
import app.budget.engine.schedule.ScheduleRule;
import app.budget.server.accounts.Account;
import app.budget.server.accounts.AccountType;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.buckets.BucketsRepository;
import app.budget.server.buckets.BucketsRepository.Bucket;
import app.budget.server.categories.CategoriesRepository;
import app.budget.server.categories.TagsRepository;
import app.budget.server.income.PayCalendar;
import app.budget.server.items.ItemCalendar.Amounts;
import app.budget.server.items.ItemCalendar.Occurrence;
import app.budget.server.items.ItemsRepository.Identity;
import app.budget.server.items.ItemsRepository.Item;
import app.budget.server.items.ItemsRepository.Override;
import app.budget.server.items.ItemsRepository.Pause;
import app.budget.server.items.ItemsRepository.Terms;
import app.budget.server.items.ItemsRepository.Version;
import app.budget.server.ledger.Ledger;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Recurring items and single-occurrence edits: {@code /api/items}, {@code /api/items/{id}} and
 * {@code /api/items/{id}/occurrences/{scheduledDate}}.
 */
public final class ItemsRoutes {

    private static final int NAME_MAX = 80;
    private static final int NOTE_MAX = 500;
    private static final int CODE_MAX = 60;
    private static final int CHECK_NUMBER_MAX = 20;
    private static final int MAX_TAGS = 20;
    private static final String CHOOSE_TAGS = "Please choose tags from the list.";
    private static final int MAX_DAYS_AFTER_PAYDAY = 60;

    private final ItemsRepository items;
    private final ItemCalendar calendar;
    private final PayCalendar pay;
    private final AccountsRepository accounts;
    private final CategoriesRepository categories;
    private final TagsRepository tags;
    private final BucketsRepository buckets;
    private final Ledger ledger;

    public ItemsRoutes(
            ItemsRepository items,
            ItemCalendar calendar,
            PayCalendar pay,
            AccountsRepository accounts,
            CategoriesRepository categories,
            TagsRepository tags,
            BucketsRepository buckets,
            Ledger ledger) {
        this.items = items;
        this.calendar = calendar;
        this.pay = pay;
        this.accounts = accounts;
        this.categories = categories;
        this.tags = tags;
        this.buckets = buckets;
        this.ledger = ledger;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/items", ctx -> ApiResponse.ok(ctx, views(items.all())));
        routes.post("/api/items", this::create);
        routes.get("/api/items/{id}", ctx -> ApiResponse.ok(ctx, view(Inputs.id(ctx, "id"))));
        routes.put("/api/items/{id}", this::update);
        routes.delete("/api/items/{id}", this::delete);
        routes.post("/api/items/{id}/replacement", this::replace);
        routes.put("/api/items/{id}/pause", this::pause);
        routes.delete("/api/items/{id}/pause", this::resume);
        routes.put("/api/items/{id}/occurrences/{scheduledDate}", this::editOccurrence);
    }

    /**
     * Request body for creating or changing an item. Amounts are whole cents.
     *
     * @param effectiveFrom  when changing: the change applies from this date on, and earlier
     *                       occurrences keep what they had. Defaults to today.
     * @param bucketId       a bucket move: the bucket the money goes into (its account is the item's
     *                       account). A transfer: optionally, a bucket in the account it goes to.
     * @param saveUpBucketId a bill or budget: optionally, a bucket in the account it is paid from,
     *                       to set a share aside in every paycheck
     */
    public record ItemInput(
            String name,
            String kind,
            String note,
            Boolean archived,
            List<Long> tagIds,
            Long amountCents,
            String amountMode,
            Boolean estimate,
            Long categoryId,
            Long payFromAccountId,
            Long payToAccountId,
            Boolean autopay,
            String placement,
            ItemScheduleDto schedule,
            Integer daysAfterPayday,
            LocalDate effectiveFrom,
            Long bucketId,
            Long saveUpBucketId,
            Long fromBucketId) {}

    /**
     * Request body for a "just this one" edit. Only what is sent changes; an empty
     * confirmation code, check number or note clears it.
     *
     * @param periodStart first day of the pay period to move this occurrence into
     */
    public record OccurrenceInput(
            Long amountCents,
            LocalDate dueDate,
            LocalDate periodStart,
            Boolean paid,
            LocalDate paidDate,
            String confirmationCode,
            String note,
            Boolean skipped,
            String checkNumber) {}

    /**
     * An item as the API returns it: the version in force today, plus what it works out to.
     *
     * @param amountCents the amount as the user entered it (a yearly total when {@code amountMode} is annual)
     * @param saveUpPerPaycheckCents what the next paycheck sets aside for it; {@code null} when it is
     *                    not saved up for, or nothing more needs setting aside
     * @param next        the next occurrence still to pay; {@code null} when there is none in the coming year
     * @param history     every version, oldest first: the item's price history
     * @param paused      true while it is paused today, so nothing it is scheduled for happens
     * @param pausedUntil the first day it runs again; {@code null} when not paused, or paused until turned back on
     */
    public record ItemView(
            long id,
            String name,
            ItemKind kind,
            String note,
            boolean archived,
            LocalDate createdOn,
            List<Long> tagIds,
            long amountCents,
            AmountMode amountMode,
            boolean estimate,
            Long categoryId,
            long payFromAccountId,
            Long payToAccountId,
            boolean autopay,
            Placement placement,
            ItemScheduleDto schedule,
            int daysAfterPayday,
            Long bucketId,
            Long saveUpBucketId,
            Long fromBucketId,
            Long saveUpPerPaycheckCents,
            long perOccurrenceCents,
            long perYearCents,
            Next next,
            List<PricePoint> history,
            boolean paused,
            LocalDate pausedUntil) {}

    /** @param until the first day it runs again; left out to pause until it is turned back on */
    public record PauseInput(LocalDate until) {}

    /** @param early true when it is paid from a period before the one it falls due in */
    public record Next(LocalDate scheduledDate, LocalDate dueDate, LocalDate periodStart, boolean early) {}

    public record PricePoint(LocalDate effectiveFrom, long perOccurrenceCents, long perYearCents) {}

    public record Deleted(boolean deleted) {}

    private void create(Context ctx) {
        ItemInput input = Inputs.body(ctx, ItemInput.class);
        Terms terms = terms(input, kind(input));
        // The first version covers the item's whole life, so it is dated from when the schedule starts.
        long id = items.create(identity(input), terms, terms.schedule().startDate());
        letItsBucketCountFromItsStart(terms);
        ApiResponse.send(ctx, 201, view(id));
    }

    /**
     * A bucket move is an instruction to put money in one bucket, so its own start date decides
     * when that begins: if the bucket was only counting from a later day, it now counts from the
     * payday of the pay period the move starts in. (Transfers and save-ups do not do this: they
     * exist for other reasons, and a new bucket should not be back-filled from them.)
     */
    private void letItsBucketCountFromItsStart(Terms terms) {
        if (terms.bucketId() == null || terms.payToAccountId() != null) {
            return;
        }
        LocalDate start = terms.schedule().startDate();
        // "Every paycheck" is dated from its period's payday, which can fall before the start day itself.
        LocalDate from = pay.payPeriods(start, start).stream()
                .filter(period -> !start.isBefore(period.start()) && !start.isAfter(period.end()))
                .map(period -> period.start())
                .findFirst()
                .orElse(start);
        buckets.find(terms.bucketId())
                .filter(bucket -> from.isBefore(bucket.openedOn()))
                .ifPresent(bucket -> buckets.countFrom(bucket.id(), from));
    }

    private void update(Context ctx) {
        long id = Inputs.id(ctx, "id");
        ItemInput input = Inputs.body(ctx, ItemInput.class);
        Identity identity = identity(input);
        Terms terms = terms(input, identity.kind());
        LocalDate effectiveFrom = input.effectiveFrom() == null ? pay.today() : input.effectiveFrom();
        if (!Inputs.inSupportedYears(effectiveFrom)) {
            throw ApiException.badRequest("Please choose the date this change starts from.");
        }
        Item existing = items.find(id).orElseThrow(ApiException::notFound);
        // Moving the start is a change to the past by its nature. Earlier: it has to reach back
        // to the new start, or everything before today would keep the old, later one. Later:
        // it has to reach back to the old start, or what was scheduled between the two would stay.
        LocalDate start = terms.schedule().startDate();
        LocalDate startWas = existing.versionOn(effectiveFrom).terms().schedule().startDate();
        if (!start.equals(startWas)) {
            LocalDate earlier = start.isBefore(startWas) ? start : startWas;
            effectiveFrom = earlier.isBefore(effectiveFrom) ? earlier : effectiveFrom;
        }
        items.update(id, identity, terms, effectiveFrom);
        letItsBucketCountFromItsStart(terms);
        ApiResponse.ok(ctx, view(id));
    }

    /**
     * Archives an item and adds what was sent as a new one that starts today: the way to turn
     * an item into another kind without touching what it was before. A start date that was
     * sent is ignored, or the new item would repeat the old one's past.
     */
    private void replace(Context ctx) {
        long id = Inputs.id(ctx, "id");
        ItemInput input = Inputs.body(ctx, ItemInput.class);
        // Everything is checked before anything is written, so a mistake leaves the old item as it was.
        Identity identity = identity(input);
        Terms sent = terms(input, identity.kind());
        ScheduleRule rule = sent.schedule();
        Terms terms = sent.withSchedule(new ScheduleRule(
                rule.frequency(), rule.interval(), rule.day1(), rule.day2(), rule.month(),
                pay.today(), rule.endDate(), rule.weekendRule(), rule.manualDates()));
        if (items.find(id).isEmpty()) {
            throw ApiException.notFound();
        }
        long created = items.replace(id, identity, terms);
        letItsBucketCountFromItsStart(terms);
        ApiResponse.send(ctx, 201, view(created));
    }

    /** Pauses an item from today, until a date or until it is resumed. */
    private void pause(Context ctx) {
        long id = Inputs.id(ctx, "id");
        PauseInput input = Inputs.body(ctx, PauseInput.class);
        LocalDate today = pay.today();
        if (input.until() != null && (!input.until().isAfter(today) || !Inputs.inSupportedYears(input.until()))) {
            throw ApiException.badRequest("Please choose a later day for it to start again, or leave the date empty.");
        }
        if (items.find(id).isEmpty()) {
            throw ApiException.notFound();
        }
        items.pause(id, today, input.until());
        ApiResponse.ok(ctx, view(id));
    }

    /** Lets a paused item run again from today. */
    private void resume(Context ctx) {
        long id = Inputs.id(ctx, "id");
        if (items.find(id).isEmpty()) {
            throw ApiException.notFound();
        }
        items.resume(id, pay.today());
        ApiResponse.ok(ctx, view(id));
    }

    private void delete(Context ctx) {
        long id = Inputs.id(ctx, "id");
        if (items.find(id).isEmpty()) {
            throw ApiException.notFound();
        }
        items.delete(id);
        ApiResponse.ok(ctx, new Deleted(true));
    }

    private void editOccurrence(Context ctx) {
        long id = Inputs.id(ctx, "id");
        LocalDate scheduledDate = Inputs.date(ctx.pathParam("scheduledDate"));
        OccurrenceInput input = Inputs.body(ctx, OccurrenceInput.class);
        Item item = items.find(id).orElseThrow(ApiException::notFound);
        if (!Inputs.inSupportedYears(scheduledDate)) {
            throw ApiException.notFound();
        }
        Occurrence current = calendar.find(item, scheduledDate).orElseThrow(ApiException::notFound);

        Long amount = input.amountCents() == null ? null : Inputs.amount(input.amountCents(), "The amount");
        requireSupported(input.dueDate(), "the due date");
        requireSupported(input.paidDate(), "the date it was paid");
        if (input.periodStart() != null && pay.payPeriods(input.periodStart(), input.periodStart()).stream()
                .noneMatch(period -> period.start().equals(input.periodStart()))) {
            throw ApiException.badRequest("Please choose the pay period from the list.");
        }

        // A check can only have been written on a checking account.
        if (input.checkNumber() != null && !input.checkNumber().isBlank() && accounts.find(current.payFromAccountId())
                .filter(account -> account.type().equals(Inputs.code(AccountType.CHECKING)))
                .isEmpty()) {
            throw ApiException.badRequest("A check number can be kept for something paid from a checking account.");
        }

        Override stored = items.overrides(scheduledDate, scheduledDate).stream()
                .filter(edit -> edit.itemId() == id)
                .findFirst()
                .orElse(Override.untouched(id, scheduledDate));
        boolean paid = input.paid() == null ? stored.paid() : input.paid();
        items.saveOverride(new Override(
                id,
                scheduledDate,
                amount == null ? stored.amountCents() : amount,
                input.dueDate() == null ? stored.dueDate() : input.dueDate(),
                input.periodStart() == null ? stored.periodOverride() : input.periodStart(),
                paid,
                // Un-ticking "paid" forgets when; ticking it without a date leaves the date open.
                !paid ? null : input.paidDate() == null ? stored.paidDate() : input.paidDate(),
                input.confirmationCode() == null
                        ? stored.confirmationCode()
                        : Inputs.optionalText(input.confirmationCode(), "the confirmation code", CODE_MAX),
                input.note() == null ? stored.note() : Inputs.optionalText(input.note(), "the note", NOTE_MAX),
                input.skipped() == null ? stored.skipped() : input.skipped(),
                // Kept from the first time the amount is typed over, so the original stays on record.
                stored.projectedCents() != null || amount == null ? stored.projectedCents() : (Long) current.amountCents(),
                input.checkNumber() == null
                        ? stored.checkNumber()
                        : Inputs.optionalText(input.checkNumber(), "the check number", CHECK_NUMBER_MAX)));
        ApiResponse.ok(ctx, calendar.find(item, scheduledDate).orElseThrow(ApiException::notFound));
    }

    private ItemView view(long id) {
        return views(List.of(items.find(id).orElseThrow(ApiException::notFound))).getFirst();
    }

    private List<ItemView> views(List<Item> chosen) {
        // One pass over the calendar for all of them, rather than one per item.
        Map<Long, Occurrence> next = calendar.nextByItem(chosen.stream().filter(item -> !item.archived()).toList());
        boolean anySavedUpFor = chosen.stream()
                .anyMatch(item -> !item.archived() && item.versionOn(pay.today()).terms().saveUpBucketId() != null);
        Map<Long, Long> saveUps = anySavedUpFor ? ledger.nextSaveUps() : Map.of();
        Map<Long, List<Pause>> pauses = items.pauses();
        return chosen.stream()
                .map(item -> view(
                        item,
                        Optional.ofNullable(next.get(item.id())),
                        saveUps.get(item.id()),
                        pauses.getOrDefault(item.id(), List.of()).stream().filter(pause -> pause.covers(pay.today())).findFirst()))
                .toList();
    }

    private ItemView view(Item item, Optional<Occurrence> next, Long saveUpPerPaycheckCents, Optional<Pause> pause) {
        Terms terms = item.versionOn(pay.today()).terms();
        Amounts amounts = calendar.amounts(terms);
        return new ItemView(
                item.id(),
                item.name(),
                item.kind(),
                item.note(),
                item.archived(),
                item.createdOn(),
                item.tagIds(),
                terms.amountCents(),
                terms.amountMode(),
                terms.estimate(),
                terms.categoryId(),
                terms.payFromAccountId(),
                terms.payToAccountId(),
                terms.autopay(),
                terms.placement(),
                ItemScheduleDto.of(terms.schedule()),
                terms.daysAfterPayday(),
                terms.bucketId(),
                terms.saveUpBucketId(),
                terms.fromBucketId(),
                saveUpPerPaycheckCents,
                amounts.perOccurrenceCents(),
                amounts.perYearCents(),
                next.map(occurrence -> new Next(
                                occurrence.scheduledDate(), occurrence.dueDate(), occurrence.periodStart(), occurrence.early()))
                        .orElse(null),
                item.versions().stream().map(this::pricePoint).toList(),
                pause.isPresent(),
                pause.map(Pause::until).orElse(null));
    }

    /**
     * An "every paycheck" item counts for the whole pay period its start falls in: one added
     * today belongs to the paycheck already being spent. A start that is still to come means
     * something else, "not before this day", so it is moved on to the first payday on or after
     * it. It is saved as that payday, which is then what the form shows.
     */
    private ScheduleRule fromThePaydayItStartsOn(ScheduleRule rule) {
        LocalDate start = rule.startDate();
        if (rule.frequency() != Frequency.EVERY_PAY_PERIOD || !start.isAfter(pay.today())) {
            return rule;
        }
        return pay.payPeriods(start, start).stream()
                // Part-way through a period: its payday has already gone by on the start day.
                .filter(period -> period.start().isBefore(start) && !period.end().isBefore(start))
                .findFirst()
                .map(period -> new ScheduleRule(
                        rule.frequency(), rule.interval(), rule.day1(), rule.day2(), rule.month(),
                        period.end().plusDays(1), rule.endDate(), rule.weekendRule(), rule.manualDates()))
                .orElse(rule);
    }

    private PricePoint pricePoint(Version version) {
        Amounts amounts = calendar.amounts(version.terms());
        return new PricePoint(version.effectiveFrom(), amounts.perOccurrenceCents(), amounts.perYearCents());
    }

    private static ItemKind kind(ItemInput input) {
        return Inputs.choice(ItemKind.class, input.kind(), "the type of item");
    }

    private Identity identity(ItemInput input) {
        List<Long> tagIds = Inputs.ids(input.tagIds(), MAX_TAGS, CHOOSE_TAGS);
        if (!tags.allExist(tagIds)) {
            throw ApiException.badRequest(CHOOSE_TAGS);
        }
        return new Identity(
                Inputs.text(input.name(), "a name for this item", NAME_MAX),
                kind(input),
                Inputs.optionalText(input.note(), "the note", NOTE_MAX),
                Boolean.TRUE.equals(input.archived()),
                tagIds);
    }

    private Terms terms(ItemInput input, ItemKind kind) {
        long amount = Inputs.amount(input.amountCents(), "The amount");
        AmountMode mode = input.amountMode() == null
                ? AmountMode.PER_OCCURRENCE
                : Inputs.choice(AmountMode.class, input.amountMode(), "how the amount is entered");
        Placement placement = input.placement() == null
                ? Placement.DUE_PERIOD
                : Inputs.choice(Placement.class, input.placement(), "which paycheck pays it");
        if (input.categoryId() != null && categories.find(input.categoryId()).isEmpty()) {
            throw ApiException.badRequest("Please choose the category from the list.");
        }

        // A bucket move stays inside one account: the bucket says which, so nothing else has to.
        Bucket into = kind == ItemKind.BUCKET_MOVE ? bucket(input.bucketId(), "Please choose the bucket this money goes into.") : null;
        Long payFromId = into != null ? (Long) into.accountId() : input.payFromAccountId();
        Account from = payFromId == null ? null : accounts.find(payFromId).orElse(null);
        if (from == null) {
            throw ApiException.badRequest("Please choose the account this is paid from.");
        }
        boolean movesMoney = kind == ItemKind.TRANSFER || kind == ItemKind.CARD_PAYMENT;
        Long payTo = null;
        if (movesMoney) {
            Account to = input.payToAccountId() == null ? null : accounts.find(input.payToAccountId()).orElse(null);
            if (to == null) {
                throw ApiException.badRequest(kind == ItemKind.CARD_PAYMENT
                        ? "Please choose the card this payment goes to."
                        : "Please choose the account this money goes to.");
            }
            if (to.id() == from.id()) {
                throw ApiException.badRequest("Please choose two different accounts.");
            }
            if (kind == ItemKind.CARD_PAYMENT && !to.type().equals(Inputs.code(AccountType.CREDIT_CARD))) {
                throw ApiException.badRequest("A card payment needs to go to one of your credit cards.");
            }
            payTo = to.id();
        }

        // Only the kinds that use a bucket keep one; the form sends whatever was last chosen.
        Long bucketId = into != null ? (Long) into.id() : null;
        if (kind == ItemKind.TRANSFER && input.bucketId() != null) {
            Bucket inOtherAccount = bucket(input.bucketId(), "Please choose the bucket from the list.");
            if (inOtherAccount.accountId() != payTo) {
                throw ApiException.badRequest("Please choose a bucket in the account this money goes to.");
            }
            bucketId = inOtherAccount.id();
        }
        Long saveUpBucketId = null;
        if ((kind == ItemKind.BILL || kind == ItemKind.BUDGET) && input.saveUpBucketId() != null) {
            Bucket saveUp = bucket(input.saveUpBucketId(), "Please choose the bucket from the list.");
            if (saveUp.accountId() != from.id()) {
                throw ApiException.badRequest("Please choose a save-up bucket in the account this is paid from.");
            }
            saveUpBucketId = saveUp.id();
        }
        // Money leaving an account that has buckets comes out of one of them, or out of what is in none.
        Long fromBucketId = null;
        if (kind != ItemKind.BUCKET_MOVE && input.fromBucketId() != null) {
            Bucket paidFrom = bucket(input.fromBucketId(), "Please choose the bucket from the list.");
            if (paidFrom.accountId() != from.id()) {
                throw ApiException.badRequest("Please choose a bucket in the account this is paid from.");
            }
            if (saveUpBucketId != null) {
                throw ApiException.badRequest(
                        "This is already paid out of the bucket it is saved up in. Please choose one or the other.");
            }
            fromBucketId = paidFrom.id();
        }

        if (input.schedule() == null) {
            throw ApiException.badRequest("Please choose how often it repeats.");
        }
        ScheduleRule schedule = fromThePaydayItStartsOn(input.schedule().toRule());
        int daysAfterPayday = input.daysAfterPayday() == null ? 0 : input.daysAfterPayday();
        if (daysAfterPayday < 0 || daysAfterPayday > MAX_DAYS_AFTER_PAYDAY) {
            throw ApiException.badRequest("Please choose how many days after payday, from 0 to " + MAX_DAYS_AFTER_PAYDAY + ".");
        }
        return new Terms(
                amount,
                mode,
                Boolean.TRUE.equals(input.estimate()),
                input.categoryId(),
                from.id(),
                payTo,
                // Setting money aside needs nobody to pay anything: it simply happens on its day.
                kind == ItemKind.BUCKET_MOVE || Boolean.TRUE.equals(input.autopay()),
                placement,
                schedule,
                // Only "every pay period" is dated from payday; any other schedule names its own day.
                schedule.frequency() == Frequency.EVERY_PAY_PERIOD ? daysAfterPayday : 0,
                bucketId,
                saveUpBucketId,
                fromBucketId);
    }

    /** One of the user's open buckets, or {@code message} when it is missing, unknown or archived. */
    private Bucket bucket(Long id, String message) {
        return Optional.ofNullable(id)
                .flatMap(buckets::find)
                .filter(found -> !found.archived())
                .orElseThrow(() -> ApiException.badRequest(message));
    }

    private static void requireSupported(LocalDate date, String label) {
        if (date != null && !Inputs.inSupportedYears(date)) {
            throw ApiException.badRequest("Please choose " + label + " between 2000 and 2100.");
        }
    }
}
