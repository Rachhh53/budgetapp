package app.budget.server.items;

import app.budget.engine.items.PaidAhead;
import app.budget.server.accounts.Account;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.entries.EntriesRepository;
import app.budget.server.entries.EntryKind;
import app.budget.server.income.PayCalendar;
import app.budget.server.items.ItemCalendar.Occurrence;
import app.budget.server.items.ItemCalendar.Standing;
import app.budget.server.items.ItemsRepository.Item;
import app.budget.server.items.ItemsRepository.Terms;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Paid ahead (spec §4.6; Rachel, 2026-10-19: "if I pay 100 today and I attend a course that costs
 * 25/week and I want to draw down on that each week"). {@code /api/items/{id}/paid-ahead} says
 * where the money paid ahead stands against the item's sessions; {@code .../payments} records
 * another payment, as a one-off entry of money going out that is linked to the item. The payment is
 * then changed or removed as that one-off, like any other.
 */
public final class PaidAheadRoutes {

    /** How many sessions still to come are listed, so the next ones can be skipped ahead of time. */
    private static final int UPCOMING_SHOWN = 3;

    private final ItemsRepository items;
    private final ItemCalendar calendar;
    private final EntriesRepository entries;
    private final PrepaymentsRepository prepayments;
    private final AccountsRepository accounts;
    private final PayCalendar pay;

    public PaidAheadRoutes(
            ItemsRepository items,
            ItemCalendar calendar,
            EntriesRepository entries,
            PrepaymentsRepository prepayments,
            AccountsRepository accounts,
            PayCalendar pay) {
        this.items = items;
        this.calendar = calendar;
        this.entries = entries;
        this.prepayments = prepayments;
        this.accounts = accounts;
        this.pay = pay;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/items/{id}/paid-ahead", this::show);
        routes.post("/api/items/{id}/paid-ahead/payments", this::pay);
        routes.put("/api/items/{id}/paid-ahead/packs/{plannedDate}", this::movePack);
        routes.delete("/api/items/{id}/paid-ahead/packs/{plannedDate}", this::unmovePack);
    }

    /** Request body for moving a planned pack to another day. */
    public record MoveInput(LocalDate date) {}

    /**
     * A payment made ahead.
     *
     * @param accountId the account it was paid from; left out, the one the item is paid from that day
     */
    public record PaymentInput(LocalDate date, Long amountCents, Long accountId, String note) {}

    /**
     * One session.
     *
     * @param scheduledDate identifies it for skipping it
     * @param covered       what was paid ahead covers it in full
     */
    public record SessionView(LocalDate scheduledDate, LocalDate date, long amountCents, boolean skipped, boolean covered) {}

    /** One payment made ahead: the one-off entry that is it. */
    public record PaymentView(long entryId, LocalDate date, long amountCents, long accountId) {}

    /**
     * @param paidCents      everything paid ahead
     * @param usedCents      what the sessions up to today came to
     * @param leftCents      what is still there; negative once sessions went past what was paid
     * @param coversThrough  the last session it covers; {@code null} when none
     * @param firstUncovered the first session it does not cover; {@code null} when it covers every one ahead
     * @param used           the sessions up to today, newest first, skipped ones included so they can be put back
     * @param upcoming       the next few sessions after today, skipped ones included
     * @param payments       newest first
     * @param packSessions   bought as a pack (today): how many sessions it has; {@code null} for a price per session
     * @param packPriceCents bought as a pack: its price today; {@code null} otherwise
     * @param nextPack       bought as a pack: the day the next one is due, planned as a bill; {@code null} otherwise, or
     *                       when every session ahead is covered
     * @param nextPackPlanned the day Budget planned that pack for, which names it for moving it; the same as
     *                       {@code nextPack} unless it was moved
     * @param shortFrom      a pack on its own schedule: the first session the packs paid by then don't cover, so
     *                       the sessions run out before the next pack; {@code null} when they never do
     */
    public record PaidAheadView(
            long paidCents,
            long usedCents,
            long leftCents,
            LocalDate coversThrough,
            LocalDate firstUncovered,
            List<SessionView> used,
            List<SessionView> upcoming,
            List<PaymentView> payments,
            Integer packSessions,
            Long packPriceCents,
            LocalDate nextPack,
            LocalDate nextPackPlanned,
            LocalDate shortFrom) {}

    private void show(Context ctx) {
        ApiResponse.ok(ctx, view(paidAhead(Inputs.id(ctx, "id"))));
    }

    private void pay(Context ctx) {
        Item item = paidAhead(Inputs.id(ctx, "id"));
        PaymentInput input = Inputs.body(ctx, PaymentInput.class);
        if (input.date() == null) {
            throw ApiException.badRequest("Please choose the day it was paid.");
        }
        ItemsRoutes.requireSupported(input.date(), "the day it was paid");
        long amount = Inputs.amount(input.amountCents(), "The amount paid");
        if (amount == 0) {
            throw ApiException.badRequest("Please enter what was paid, more than $0.00.");
        }
        Terms terms = item.versionOn(input.date()).terms();
        long accountId = input.accountId() == null ? terms.payFromAccountId() : input.accountId();
        Account account = accounts.find(accountId).filter(found -> !found.archived())
                .orElseThrow(() -> ApiException.badRequest("Please choose the account it was paid from."));
        // A one-off like any other, named and filed as its item, so reports and receipts treat it as spending.
        long entryId = entries.create(new EntriesRepository.Fields(
                EntryKind.MONEY_OUT, input.date(), amount, false, item.name(), null, terms.categoryId(), account.id(), null,
                Inputs.optionalText(input.note(), "the note", ItemsRoutes.NOTE_MAX), item.tagIds(), List.of(), null, null, null));
        prepayments.link(entryId, item.id());
        ApiResponse.send(ctx, 201, view(item));
    }

    private PaidAheadView view(Item item) {
        LocalDate today = pay.today();
        Standing standing = calendar.standing(item, today);
        LocalDate coversThrough = standing.result().coversThrough();
        Terms terms = item.versionOn(today).terms();
        Optional<PaidAhead.Pack> pack = terms.pack();
        Optional<Occurrence> nextPack = pack.isEmpty() ? Optional.empty() : calendar.plannedPacks(item).stream().findFirst();
        List<SessionView> sessions = standing.sessions().stream()
                .map(session -> session(session, coversThrough))
                .toList();
        return new PaidAheadView(
                standing.result().paid().cents(),
                standing.result().used().cents(),
                standing.result().left().cents(),
                coversThrough,
                standing.result().firstUncovered(),
                sessions.stream().filter(session -> !session.date().isAfter(today))
                        .sorted(Comparator.comparing(SessionView::date).reversed()).toList(),
                sessions.stream().filter(session -> session.date().isAfter(today)).limit(UPCOMING_SHOWN).toList(),
                standing.payments().stream()
                        .sorted(Comparator.comparing(PrepaymentsRepository.Prepayment::date).reversed())
                        .map(payment -> new PaymentView(payment.entryId(), payment.date(), payment.amountCents(), payment.accountId()))
                        .toList(),
                pack.map(PaidAhead.Pack::sessions).orElse(null),
                pack.map(found -> found.price().cents()).orElse(null),
                nextPack.map(Occurrence::dueDate).orElse(null),
                nextPack.map(Occurrence::scheduledDate).orElse(null),
                pack.isPresent() && terms.packSchedule() != null ? calendar.shortBeforeNextPack(item).orElse(null) : null);
    }

    /**
     * Moves a planned pack to another day (Rachel, 2026-10-20: "I also want the option to just change the
     * date"). The pack is named by the day Budget planned it for; moving it back there puts it back.
     */
    private void movePack(Context ctx) {
        Item item = paidAhead(Inputs.id(ctx, "id"));
        LocalDate planned = plannedPack(item, ctx);
        MoveInput input = Inputs.body(ctx, MoveInput.class);
        if (input.date() == null) {
            throw ApiException.badRequest("Please choose the day the pack is due.");
        }
        ItemsRoutes.requireSupported(input.date(), "the day the pack is due");
        if (input.date().equals(planned)) {
            items.unmovePack(item.id(), planned);
        } else {
            items.movePack(item.id(), planned, input.date());
        }
        ApiResponse.ok(ctx, view(item));
    }

    /** Puts a moved pack back on the day Budget planned it for. */
    private void unmovePack(Context ctx) {
        Item item = paidAhead(Inputs.id(ctx, "id"));
        items.unmovePack(item.id(), plannedPack(item, ctx));
        ApiResponse.ok(ctx, view(item));
    }

    /** The planned day named in the path, which has to be one of the item's planned packs. */
    private LocalDate plannedPack(Item item, Context ctx) {
        LocalDate planned = Inputs.date(ctx.pathParam("plannedDate"));
        if (calendar.plannedPacks(item).stream().noneMatch(pack -> pack.scheduledDate().equals(planned))) {
            throw ApiException.notFound();
        }
        return planned;
    }

    /** A skipped session uses nothing, so it is never "covered"; one on or before the last covered session is. */
    private static SessionView session(Occurrence session, LocalDate coversThrough) {
        boolean covered = !session.skipped() && coversThrough != null && !session.dueDate().isAfter(coversThrough);
        return new SessionView(session.scheduledDate(), session.dueDate(), session.amountCents(), session.skipped(), covered);
    }

    /** The item, which has to be (or have been) paid ahead to have sessions and payments made ahead. */
    private Item paidAhead(long id) {
        Item item = items.find(id).orElseThrow(ApiException::notFound);
        if (item.versions().stream().noneMatch(version -> version.terms().paidAhead())) {
            throw ApiException.conflict("This isn't paid ahead, so it has no payments made ahead. Turn on “Paid ahead” for it first.");
        }
        return item;
    }
}
