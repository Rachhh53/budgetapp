package app.budget.server.items;

import app.budget.engine.cards.CardPayment;
import app.budget.engine.items.Placement;
import app.budget.engine.items.UsageBill;
import app.budget.engine.schedule.Frequency;
import app.budget.engine.schedule.ScheduleRule;
import app.budget.server.accounts.Account;
import app.budget.server.accounts.AccountType;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Archiving;
import app.budget.server.api.Inputs;
import app.budget.server.buckets.BucketsRepository;
import app.budget.server.buckets.BucketsRepository.Bucket;
import app.budget.server.cards.CardsRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Recurring items and single-occurrence edits: {@code /api/items}, {@code /api/items/{id}} and
 * {@code /api/items/{id}/occurrences/{scheduledDate}}.
 */
public final class ItemsRoutes {

    private static final int NAME_MAX = 80;
    static final int NOTE_MAX = 500;
    /** The most sessions a pack paid ahead can have; the database holds it to the same. */
    private static final int MAX_PACK_SESSIONS = 1000;
    /** How many of an item's next dates its page lists, each to change just that once. */
    private static final int UPCOMING_COUNT = 6;
    /** Billed by use: at most 1,000 uses a week or a month, in hundredths. */
    private static final long MAX_USES_HUNDREDTHS = 100_000;
    static final int CODE_MAX = 60;
    static final int CHECK_NUMBER_MAX = 20;
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
    private final CardsRepository cards;

    public ItemsRoutes(
            ItemsRepository items,
            ItemCalendar calendar,
            PayCalendar pay,
            AccountsRepository accounts,
            CategoriesRepository categories,
            TagsRepository tags,
            BucketsRepository buckets,
            Ledger ledger,
            CardsRepository cards) {
        this.items = items;
        this.calendar = calendar;
        this.pay = pay;
        this.accounts = accounts;
        this.categories = categories;
        this.tags = tags;
        this.buckets = buckets;
        this.ledger = ledger;
        this.cards = cards;
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
        routes.delete("/api/items/{id}/versions/{versionId}", this::removeVersion);
        routes.get("/api/items/{id}/upcoming", ctx -> ApiResponse.ok(ctx, calendar.upcoming(
                items.find(Inputs.id(ctx, "id")).orElseThrow(ApiException::notFound), UPCOMING_COUNT)));
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
     * @param cardPaymentMode a card payment: how much it pays. {@code fixed} is the amount sent; {@code
     *                       statement_balance}, {@code full_balance} and {@code minimum} follow the card,
     *                       so the amount sent means nothing. Left out, it stays as it was (fixed, when new).
     * @param billedLater    a bill: its schedule names visits that build up and are paid when an invoice for them
     *                       arrives (spec §4.6). Only for an amount per time, not a yearly total.
     * @param paidAhead     a bill: its schedule names sessions that use up what was paid ahead for them (spec §4.6).
     *                       Only for an amount per time, and never with {@code billedLater}.
     * @param packSessions  paid ahead as a pack: how many sessions the amount (then the pack's price) buys. The next
     *                       pack is planned as a bill. Left out for an amount per session.
     * @param packSchedule  a pack paid on a schedule of its own (every 10 weeks, say). Left out, the next pack is due
     *                       when the sessions use up the last (Rachel, 2026-10-20: "I want both options")
     * @param usesHundredths billed by use (spec §4.6): about how many uses, in hundredths (2.5 is 250), the amount
     *                       being the cost of each and the schedule monthly on the bill day, in the months it can
     *                       happen in. Left out for any other bill.
     * @param usesPer        billed by use: {@code week} or {@code month}, what the uses are counted per
     * @param archivedOn     with {@code archived}: the day it is archived as of, today or earlier; left out, the day it
     *                       already had, or today
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
            Long fromBucketId,
            String cardPaymentMode,
            Boolean billedLater,
            Boolean paidAhead,
            Integer packSessions,
            ItemScheduleDto packSchedule,
            Long usesHundredths,
            String usesPer,
            LocalDate archivedOn) {}

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
            String checkNumber,
            Long payFromAccountId) {}

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
     * @param paymentOfCardId the credit card whose own payment this is; {@code null} for any other item
     * @param followsCard true when its amount is worked out from that card (its statement balance,
     *                    say), so {@code amountCents} means nothing
     * @param cardPaymentMode how much a card's own payment pays ({@code fixed}, {@code statement_balance},
     *                    {@code full_balance} or {@code minimum}); {@code null} for any other item
     * @param billedLater true when, today, its schedule names visits paid when an invoice arrives (spec §4.6)
     * @param paidAhead   true when, today, its schedule names sessions paid for ahead (spec §4.6)
     * @param packSessions paid ahead as a pack, today: how many sessions it buys; {@code null} otherwise
     * @param packSchedule a pack paid on a schedule of its own, today: when each is paid; {@code null} otherwise
     * @param usesHundredths billed by use, today: about how many uses, in hundredths; {@code null} otherwise. Its
     *                    {@code perOccurrenceCents} is then a whole month of uses
     * @param usesPer     billed by use, today: {@code week} or {@code month}; {@code null} otherwise
     */
    public record ItemView(
            long id,
            String name,
            ItemKind kind,
            String note,
            boolean archived,
            LocalDate archivedOn,
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
            LocalDate pausedUntil,
            Long paymentOfCardId,
            boolean followsCard,
            String cardPaymentMode,
            boolean billedLater,
            boolean paidAhead,
            Integer packSessions,
            ItemScheduleDto packSchedule,
            Long usesHundredths,
            String usesPer) {}

    /** @param until the first day it runs again; left out to pause until it is turned back on */
    public record PauseInput(LocalDate until) {}

    /** @param early true when it is paid from a period before the one it falls due in */
    public record Next(LocalDate scheduledDate, LocalDate dueDate, LocalDate periodStart, boolean early) {}

    /** @param versionId the version it is, to remove it from the history */
    public record PricePoint(long versionId, LocalDate effectiveFrom, long perOccurrenceCents, long perYearCents) {}

    public record Deleted(boolean deleted) {}

    private void create(Context ctx) {
        ItemInput input = Inputs.body(ctx, ItemInput.class);
        Terms sent = terms(input, kind(input));
        OfCard card = ofCard(input, kind(input), sent, null);
        Terms terms = sized(sent, card);
        // The first version covers the item's whole life, so it is dated from when the schedule starts.
        long id = items.create(identity(input, null), terms, terms.schedule().startDate());
        letItsBucketCountFromItsStart(terms);
        keepItsCardInStep(id, card, terms);
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
        Item existing = items.find(id).orElseThrow(ApiException::notFound);
        Identity identity = identity(input, existing.archivedOn());
        Terms sent = terms(input, identity.kind());
        OfCard card = ofCard(input, identity.kind(), sent, id);
        Terms terms = sized(sent, card);
        LocalDate effectiveFrom = input.effectiveFrom() == null ? pay.today() : input.effectiveFrom();
        if (!Inputs.inSupportedYears(effectiveFrom)) {
            throw ApiException.badRequest("Please choose the date this change starts from.");
        }
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
        keepItsCardInStep(id, card, terms);
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
        // Everything is checked before anything is written, so a mistake leaves the old item as it was. The new one
        // takes the old one's place, so it is never archived itself.
        Identity identity = identity(input, null);
        // It stands in for the item it replaces, so it may be the same card's own payment.
        OfCard card = ofCard(input, identity.kind(), terms(input, identity.kind()), id);
        Terms sent = sized(terms(input, identity.kind()), card);
        ScheduleRule rule = sent.schedule();
        Terms terms = sent.withSchedule(new ScheduleRule(
                rule.frequency(), rule.interval(), rule.day1(), rule.day2(), rule.month(),
                pay.today(), rule.endDate(), rule.weekendRule(), rule.manualDates()));
        if (items.find(id).isEmpty()) {
            throw ApiException.notFound();
        }
        long created = items.replace(id, identity, terms);
        letItsBucketCountFromItsStart(terms);
        keepItsCardInStep(created, card, terms);
        ApiResponse.send(ctx, 201, view(created));
    }

    /** A card's own payment: the card's settings as they stand, and how much the payment pays. */
    private record OfCard(CardsRepository.Settings settings, CardPayment.Mode mode) {}

    /**
     * Works out whether a card payment is its card's own payment: the one that can follow the card
     * (its statement balance, say) instead of having an amount. A card has one. It is the payment
     * already tied to the card, or else the first one added while the card has none; a payment that
     * follows the card also takes over from one that has a fixed amount. A promotion's payoff is
     * never it.
     *
     * @param itemId the item being changed; {@code null} for a new one
     * @return {@code null} for anything that is not a card's own payment
     */
    private OfCard ofCard(ItemInput input, ItemKind kind, Terms terms, Long itemId) {
        if (kind != ItemKind.CARD_PAYMENT) {
            return null;
        }
        CardsRepository.Settings settings = cards.settings(terms.payToAccountId()).orElse(null);
        boolean already = settings != null && itemId != null && itemId.equals(settings.paymentItemId());
        CardPayment.Mode mode = input.cardPaymentMode() == null
                ? already ? settings.paymentMode() : CardPayment.Mode.FIXED
                : Inputs.choice(CardPayment.Mode.class, input.cardPaymentMode(), "how much this payment pays");
        boolean follows = mode != CardPayment.Mode.FIXED;
        if (settings == null) {
            if (follows) {
                throw ApiException.badRequest(
                        "Please enter this card's statement and due dates on its page first, so the payment knows which statement to follow.");
            }
            return null;
        }
        if (already) {
            return new OfCard(settings, mode);
        }
        boolean payoff = itemId != null && cards.promos(settings.accountId()).stream()
                .anyMatch(promo -> itemId.equals(promo.payoffItemId()));
        boolean hasOne = settings.paymentItemId() != null
                && items.find(settings.paymentItemId()).filter(item -> !item.archived()).isPresent();
        boolean itsOwn = !payoff && (!hasOne || follows && settings.paymentMode() == CardPayment.Mode.FIXED);
        if (follows && !itsOwn) {
            throw ApiException.badRequest(payoff
                    ? "This payment pays off a promotion, so it keeps its own amount."
                    : "This card already has a payment that follows it. Please enter an amount for this one.");
        }
        return itsOwn ? new OfCard(settings, mode) : null;
    }

    /** A payment that follows its card has no amount of its own: the ledger works it out, so it is an estimate until then. */
    private static Terms sized(Terms terms, OfCard card) {
        if (card == null || card.mode() == CardPayment.Mode.FIXED) {
            return terms;
        }
        return new Terms(
                0, AmountMode.PER_OCCURRENCE, true, terms.categoryId(), terms.payFromAccountId(), terms.payToAccountId(),
                terms.autopay(), terms.placement(), terms.schedule(), terms.daysAfterPayday(), terms.bucketId(),
                terms.saveUpBucketId(), terms.fromBucketId(), terms.billedLater(), terms.paidAhead(), terms.packSessions(),
                terms.packSchedule());
    }

    /**
     * Tells a card about its own payment, so the card's page and its projection know how it is
     * paid. An item that used to be another card's payment (it was pointed at a different card, or
     * turned into something else) is let go of there.
     */
    private void keepItsCardInStep(long itemId, OfCard card, Terms terms) {
        for (CardsRepository.Settings other : cards.allSettings().values()) {
            boolean thisCard = card != null && other.accountId() == card.settings().accountId();
            if (!thisCard && Long.valueOf(itemId).equals(other.paymentItemId())) {
                cards.saveSettings(new CardsRepository.Settings(
                        other.accountId(), other.statementCloseDay(), other.dueDay(), CardsRepository.NO_PAYMENT_YET, null, false,
                        null, null, other.monthlyEstimateCents()));
            }
        }
        if (card == null) {
            return;
        }
        CardsRepository.Settings settings = card.settings();
        cards.saveSettings(new CardsRepository.Settings(
                settings.accountId(), settings.statementCloseDay(), settings.dueDay(), card.mode(),
                card.mode() == CardPayment.Mode.FIXED ? (Long) terms.amountCents() : null,
                terms.autopay(), terms.payFromAccountId(), itemId, settings.monthlyEstimateCents()));
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
        // An invoice was worked out from the visits it covers; skipping one of them afterwards would
        // leave the invoice paying for a visit that is gone.
        if (input.skipped() != null && calendar.onInvoice(item, scheduledDate)) {
            throw ApiException.conflict("That visit is already on an invoice, so it can't be skipped or put back. "
                    + "To change it, remove the invoice first.");
        }

        Long amount = input.amountCents() == null ? null : Inputs.amount(input.amountCents(), "The amount");
        requireSupported(input.dueDate(), "the due date");
        requireSupported(input.paidDate(), "the date it was paid");
        if (input.periodStart() != null && pay.payPeriods(input.periodStart(), input.periodStart()).stream()
                .noneMatch(period -> period.start().equals(input.periodStart()))) {
            throw ApiException.badRequest("Please choose the pay period from the list.");
        }

        // Paid from another account just this once; the item's own account puts it back.
        Long payFrom = input.payFromAccountId() == null ? null : payFromOnce(item, current, scheduledDate, input.payFromAccountId());
        long paidFrom = payFrom != null ? payFrom
                : input.payFromAccountId() != null ? item.versionOn(scheduledDate).terms().payFromAccountId() : current.payFromAccountId();

        // A check can only have been written on a checking account.
        if (input.checkNumber() != null && !input.checkNumber().isBlank() && accounts.find(paidFrom)
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
                // Kept from the first time the amount is typed over, so the original stays on record: what the
                // period showed, which for a payment that follows its card is what the card worked out.
                stored.projectedCents() != null || amount == null
                        ? stored.projectedCents()
                        : ledger.amountOf(id, scheduledDate).orElse(current.amountCents()),
                input.checkNumber() == null
                        ? stored.checkNumber()
                        : Inputs.optionalText(input.checkNumber(), "the check number", CHECK_NUMBER_MAX),
                input.payFromAccountId() == null ? stored.payFromAccountId() : payFrom));
        ApiResponse.ok(ctx, calendar.find(item, scheduledDate).orElseThrow(ApiException::notFound));
    }

    /**
     * The account one occurrence is paid from instead of its item's own (Rachel, 2026-10-20: "sometimes I need to just
     * change the account its pay from as a one off"); {@code null} when it is the item's own, which puts it back.
     */
    private Long payFromOnce(Item item, Occurrence current, LocalDate scheduledDate, long accountId) {
        if (item.kind() == ItemKind.BUCKET_MOVE) {
            throw ApiException.badRequest("A bucket move stays in its bucket's account, so it can't be paid from another.");
        }
        Account from = accounts.find(accountId).filter(account -> !account.archived()).orElse(null);
        if (from == null) {
            throw ApiException.badRequest("Please choose the account this one is paid from.");
        }
        if (current.payToAccountId() != null && current.payToAccountId() == accountId) {
            throw ApiException.badRequest("Please choose an account other than the one this goes to.");
        }
        return accountId == item.versionOn(scheduledDate).terms().payFromAccountId() ? null : accountId;
    }

    /** Removes one amount from an item's history; the one before it carries on. */
    private void removeVersion(Context ctx) {
        long id = Inputs.id(ctx, "id");
        switch (items.removeVersion(id, Inputs.id(ctx, "versionId"))) {
            case NOT_FOUND -> throw ApiException.notFound();
            case LAST -> throw ApiException.conflict(
                    "This is the only amount it has, so it stays. To change it, choose Change the amount.");
            case DONE -> ApiResponse.ok(ctx, view(id));
        }
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
        // The recurring items that are a card's own payment, by item.
        Map<Long, CardsRepository.Settings> cardPayments = new HashMap<>();
        for (CardsRepository.Settings settings : cards.allSettings().values()) {
            if (settings.paymentItemId() != null) {
                cardPayments.put(settings.paymentItemId(), settings);
            }
        }
        return chosen.stream()
                .map(item -> view(
                        item,
                        Optional.ofNullable(next.get(item.id())),
                        saveUps.get(item.id()),
                        pauses.getOrDefault(item.id(), List.of()).stream().filter(pause -> pause.covers(pay.today())).findFirst(),
                        cardPayments.get(item.id())))
                .toList();
    }

    private ItemView view(
            Item item, Optional<Occurrence> next, Long saveUpPerPaycheckCents, Optional<Pause> pause, CardsRepository.Settings card) {
        Terms terms = item.versionOn(pay.today()).terms();
        Amounts amounts = calendar.amounts(terms);
        return new ItemView(
                item.id(),
                item.name(),
                item.kind(),
                item.note(),
                item.archived(),
                item.archivedOn(),
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
                pause.map(Pause::until).orElse(null),
                card == null ? null : (Long) card.accountId(),
                card != null && card.paymentMode() != CardPayment.Mode.FIXED,
                card == null ? null : Inputs.code(card.paymentMode()),
                terms.billedLater(),
                terms.paidAhead(),
                terms.paidAhead() ? terms.packSessions() : null,
                terms.pack().isPresent() && terms.packSchedule() != null ? ItemScheduleDto.of(terms.packSchedule()) : null,
                terms.billedByUse() ? terms.usage().hundredths() : null,
                terms.billedByUse() ? Inputs.code(terms.usage().per()) : null);
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
        return new PricePoint(version.id(), version.effectiveFrom(), amounts.perOccurrenceCents(), amounts.perYearCents());
    }

    private static ItemKind kind(ItemInput input) {
        return Inputs.choice(ItemKind.class, input.kind(), "the type of item");
    }

    /** @param archivedBefore the day the item was archived as of until now; {@code null} when it wasn't */
    private Identity identity(ItemInput input, LocalDate archivedBefore) {
        List<Long> tagIds = Inputs.ids(input.tagIds(), MAX_TAGS, CHOOSE_TAGS);
        if (!tags.allExist(tagIds)) {
            throw ApiException.badRequest(CHOOSE_TAGS);
        }
        return new Identity(
                Inputs.text(input.name(), "a name for this item", NAME_MAX),
                kind(input),
                Inputs.optionalText(input.note(), "the note", NOTE_MAX),
                Archiving.archivedOn(input.archived(), input.archivedOn(), archivedBefore, pay.today(), null),
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
        // The form sends whatever was last ticked; only a bill keeps it.
        boolean billedLater = kind == ItemKind.BILL && Boolean.TRUE.equals(input.billedLater());
        if (billedLater && mode == AmountMode.ANNUAL) {
            throw ApiException.badRequest(
                    "Something billed later is added up visit by visit. Please enter what each visit costs, not a yearly total.");
        }
        boolean paidAhead = kind == ItemKind.BILL && Boolean.TRUE.equals(input.paidAhead());
        if (paidAhead && billedLater) {
            throw ApiException.badRequest("Something can be paid ahead or billed later, not both. Please choose one.");
        }
        if (paidAhead && mode == AmountMode.ANNUAL) {
            throw ApiException.badRequest(
                    "Something paid ahead is used up session by session. Please enter what each session costs, not a yearly total.");
        }
        Integer packSessions = paidAhead ? input.packSessions() : null;
        if (packSessions != null && (packSessions < 1 || packSessions > MAX_PACK_SESSIONS)) {
            throw ApiException.badRequest("Please enter how many sessions the pack has, from 1 to " + MAX_PACK_SESSIONS + ".");
        }
        if (packSessions != null && amount == 0) {
            throw ApiException.badRequest("Please enter what the pack costs, more than $0.00.");
        }
        UsageBill.Usage usage = kind == ItemKind.BILL ? usage(input, mode, amount, schedule, billedLater || paidAhead) : null;
        // Only a pack has packs to schedule; anything else drops a schedule sent for them.
        ScheduleRule packSchedule = packSessions == null || input.packSchedule() == null
                ? null
                : packSchedule(input.packSchedule());
        return new Terms(
                amount,
                mode,
                // Billed by use is always an estimate until the real bill is typed over it.
                usage != null || Boolean.TRUE.equals(input.estimate()),
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
                fromBucketId,
                billedLater,
                paidAhead,
                packSessions,
                packSchedule,
                usage);
    }

    /**
     * Billed by use, checked (spec §4.6): about how many uses a week or a month, at what each costs, billed
     * monthly. {@code null} when it isn't sent, as for any other bill.
     */
    private static UsageBill.Usage usage(ItemInput input, AmountMode mode, long amount, ScheduleRule schedule, boolean otherWay) {
        if (input.usesHundredths() == null) {
            return null;
        }
        if (otherWay) {
            throw ApiException.badRequest(
                    "Something billed by use can't also be billed later or paid ahead. Please choose one way it's charged.");
        }
        if (mode == AmountMode.ANNUAL || amount == 0) {
            throw ApiException.badRequest("Please enter what each time costs, more than $0.00.");
        }
        if (input.usesHundredths() <= 0 || input.usesHundredths() > MAX_USES_HUNDREDTHS) {
            throw ApiException.badRequest("Please enter about how many times, more than 0 and up to 1,000.");
        }
        UsageBill.Per per = Inputs.choice(UsageBill.Per.class, input.usesPer(), "whether that's a week or a month");
        if (schedule.frequency() != Frequency.MONTHLY || schedule.interval() != 1) {
            throw ApiException.badRequest("Something billed by use is billed once a month. Please choose Monthly and the day it's due.");
        }
        return new UsageBill.Usage(input.usesHundredths(), per);
    }

    /** A pack's own schedule, checked: it has to repeat, and on dates of its own rather than paydays. */
    private static ScheduleRule packSchedule(ItemScheduleDto sent) {
        ScheduleRule rule = sent.toRule();
        if (rule.frequency() == Frequency.EVERY_PAY_PERIOD || rule.frequency() == Frequency.ONE_TIME) {
            throw ApiException.badRequest("Please choose how often a pack is paid, such as every 10 weeks or every 3 months.");
        }
        return rule;
    }

    /** One of the user's open buckets, or {@code message} when it is missing, unknown or archived. */
    private Bucket bucket(Long id, String message) {
        return Optional.ofNullable(id)
                .flatMap(buckets::find)
                .filter(found -> !found.archived())
                .orElseThrow(() -> ApiException.badRequest(message));
    }

    static void requireSupported(LocalDate date, String label) {
        if (date != null && !Inputs.inSupportedYears(date)) {
            throw ApiException.badRequest("Please choose " + label + " between 2000 and 2100.");
        }
    }
}
