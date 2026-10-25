package app.budget.server.items;

import app.budget.engine.reports.Reimbursement.Status;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.buckets.BucketsRepository;
import app.budget.server.buckets.BucketsRepository.MoveOut;
import app.budget.server.entries.EntriesRepository;
import app.budget.server.entries.EntriesRepository.Entry;
import app.budget.server.income.PayCalendar;
import app.budget.server.items.ItemCalendar.Occurrence;
import app.budget.server.items.ItemsRepository.Item;
import app.budget.server.items.ReimbursementViews.PackView;
import app.budget.server.items.ReimbursementViews.PaymentView;
import app.budget.server.items.ReimbursementsRepository.PackFlag;
import app.budget.server.items.ReimbursementsRepository.Settings;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.time.LocalDate;
import java.util.List;

/**
 * Paying back recurring payments (Rachel, 2026-10-24): how an item is paid back ({@code /api/items/{id}/reimbursement}),
 * flagging one payment ({@code /api/items/{id}/occurrences/{date}/reimbursement}) and where it stands, and every flagged
 * payment in a year for Expenses &amp; receipts ({@code /api/payment-reimbursements}). A pack paid ahead that is still to
 * be bought is flagged the same way ({@code /api/items/{id}/paid-ahead/packs/{plannedDate}/reimbursement}; Rachel,
 * 2026-10-25: "Flag each pack"), and the flag goes onto the payment when the pack is bought.
 */
public final class ReimbursementsRoutes {

    private static final int TEXT_MAX = 120;
    private static final int MOST_DAYS_AFTER = 365;
    private static final String PAYMENT = "/api/items/{id}/occurrences/{scheduledDate}/reimbursement";
    private static final String OTHER_ACCOUNT = "Please choose an account it comes out of other than the one it comes back into.";
    private static final String PACK = "/api/items/{id}/paid-ahead/packs/{plannedDate}/reimbursement";

    private final ReimbursementsRepository reimbursements;
    private final ReimbursementViews views;
    private final ItemsRepository items;
    private final ItemCalendar calendar;
    private final AccountsRepository accounts;
    private final EntriesRepository entries;
    private final BucketsRepository buckets;
    private final PayCalendar pay;

    public ReimbursementsRoutes(
            ReimbursementsRepository reimbursements,
            ReimbursementViews views,
            ItemsRepository items,
            ItemCalendar calendar,
            AccountsRepository accounts,
            EntriesRepository entries,
            BucketsRepository buckets,
            PayCalendar pay) {
        this.pay = pay;
        this.reimbursements = reimbursements;
        this.views = views;
        this.items = items;
        this.calendar = calendar;
        this.accounts = accounts;
        this.entries = entries;
        this.buckets = buckets;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/items/{id}/reimbursement", ctx -> ApiResponse.ok(ctx, itemView(item(ctx))));
        routes.put("/api/items/{id}/reimbursement", this::saveSettings);
        routes.get(PAYMENT, ctx -> ApiResponse.ok(ctx, detail(flagged(ctx))));
        routes.put(PAYMENT, this::flag);
        routes.delete(PAYMENT, this::unflag);
        routes.put(PAYMENT + "/status", this::setStatus);
        routes.get("/api/payment-reimbursements", this::inYear);
        routes.put(PACK, this::flagPack);
        routes.delete(PACK, this::unflagPack);
    }

    /**
     * How an item is paid back.
     *
     * @param reimbursedBy  who pays it back; optional
     * @param daysAfter     about how many days after each payment the money comes back, 0 to 365
     * @param intoAccountId the account it comes back into
     * @param fromAccountId the account it comes out of, like an FSA; left out, from outside
     */
    public record SettingsInput(String reimbursedBy, Integer daysAfter, Long intoAccountId, Long fromAccountId) {}

    /**
     * Flags a payment, or changes how much of it comes back. The item's way back can be set in the same step, and has to be
     * when it has none yet.
     *
     * @param amountCents   what comes back; left out, the payment's own amount
     * @param fromAccountId the account this one comes out of, like an FSA; left out, from outside. The screens start it at
     *                      the item's
     */
    public record FlagInput(Long amountCents, String reimbursedBy, Integer daysAfter, Long intoAccountId, Long fromAccountId) {}

    /**
     * Where a flagged payment stands, as for a one-off expense. Nobody paying it back after all is a {@code DELETE}.
     *
     * @param submittedOn the day it was sent in; kept only once it has been
     * @param to          who it was sent to; optional
     */
    public record StatusInput(String status, LocalDate submittedOn, String to) {}

    /**
     * An item's way back ({@code null} when none is set), its payments flagged, oldest first, and its planned packs
     * flagged, next first.
     */
    public record ItemReimbursement(Settings settings, List<PaymentView> payments, List<PackView> packs) {}

    /**
     * One flagged payment with the money that came back for it.
     *
     * @param paybacks money that came in to pay it back, oldest first
     * @param movesOut money moved out of a bucket to pay it back, oldest first
     */
    public record PaymentDetail(PaymentView payment, List<Entry> paybacks, List<MoveOut> movesOut) {}

    public record Deleted(boolean deleted) {}

    private void saveSettings(Context ctx) {
        Item item = item(ctx);
        requirePaidOut(item);
        reimbursements.saveSettings(settings(item, Inputs.body(ctx, SettingsInput.class)));
        ApiResponse.ok(ctx, itemView(item));
    }

    private void flag(Context ctx) {
        Item item = item(ctx);
        requirePaidOut(item);
        LocalDate scheduledDate = scheduledDate(ctx);
        Occurrence payment = calendar.find(item, scheduledDate).orElseThrow(ApiException::notFound);
        if (payment.skipped()) {
            throw ApiException.conflict("This payment is skipped, so there is nothing to be paid back. Put it back first.");
        }
        FlagInput input = Inputs.body(ctx, FlagInput.class);
        long amount = input.amountCents() == null ? payment.amountCents() : Inputs.amount(input.amountCents(), "What comes back");
        if (amount == 0) {
            throw ApiException.badRequest("Please enter how much comes back, more than $0.00.");
        }
        saveWayBack(item, input);
        long id = reimbursements.flag(item.id(), scheduledDate, amount, fromAccount(input.fromAccountId(), item));
        ApiResponse.ok(ctx, detail(views.find(id).orElseThrow(ApiException::notFound)));
    }

    /** Flags a planned pack, or changes how much of it comes back: part of it or all, no more than its price. */
    private void flagPack(Context ctx) {
        Item item = item(ctx);
        requirePaidOut(item);
        Occurrence pack = plannedPack(ctx, item);
        FlagInput input = Inputs.body(ctx, FlagInput.class);
        long amount = input.amountCents() == null ? pack.amountCents() : Inputs.amount(input.amountCents(), "What comes back");
        if (amount == 0 || amount > pack.amountCents()) {
            throw ApiException.badRequest("Please enter how much comes back, more than $0.00 and no more than the pack.");
        }
        saveWayBack(item, input);
        reimbursements.flagPack(item.id(), packNumber(item, pack), new PackFlag(amount, fromAccount(input.fromAccountId(), item)));
        ApiResponse.ok(ctx, itemView(item));
    }

    private void unflagPack(Context ctx) {
        Item item = item(ctx);
        if (!reimbursements.unflagPack(item.id(), packNumber(item, plannedPack(ctx, item)))) {
            throw ApiException.notFound();
        }
        ApiResponse.ok(ctx, itemView(item));
    }

    /** The planned pack named in the path; 404 when the item has no such pack still to buy. */
    private Occurrence plannedPack(Context ctx, Item item) {
        LocalDate planned = Inputs.date(ctx.pathParam("plannedDate"));
        return views.packsInLine(item).stream()
                .filter(pack -> pack.scheduledDate().equals(planned))
                .findFirst()
                .orElseThrow(ApiException::notFound);
    }

    private int packNumber(Item item, Occurrence pack) {
        return views.packsInLine(item).indexOf(pack) + 1;
    }

    /** Sets the item's way back when the flag brings one, and refuses a flag without one when the item has none yet. */
    private void saveWayBack(Item item, FlagInput input) {
        boolean settingWayBack = input.intoAccountId() != null || input.daysAfter() != null || input.reimbursedBy() != null;
        if (settingWayBack) {
            reimbursements.saveSettings(settings(item,
                    new SettingsInput(input.reimbursedBy(), input.daysAfter(), input.intoAccountId(), input.fromAccountId())));
        } else if (reimbursements.settings(item.id()).isEmpty()) {
            // Without a way back the money could not be planned: it is asked for with the first payment flagged.
            throw ApiException.badRequest("Please choose the account it comes back into.");
        }
    }

    private void unflag(Context ctx) {
        if (!reimbursements.unflag(flagged(ctx).id())) {
            throw ApiException.notFound();
        }
        ApiResponse.ok(ctx, new Deleted(true));
    }

    private void setStatus(Context ctx) {
        PaymentView payment = flagged(ctx);
        StatusInput input = Inputs.body(ctx, StatusInput.class);
        Status status = Inputs.choice(Status.class, input.status(), "where this stands");
        if (status == Status.NONE) {
            throw ApiException.badRequest("To say nobody pays this back, take the flag off instead.");
        }
        if (input.submittedOn() != null && !Inputs.inSupportedYears(input.submittedOn())) {
            throw ApiException.badRequest("Please choose the day it was sent in.");
        }
        // Something still to send in has no day it was sent.
        boolean sent = status == Status.SUBMITTED || status == Status.REIMBURSED;
        reimbursements.setStatus(payment.id(), status, sent ? input.submittedOn() : null,
                Inputs.optionalText(input.to(), "who pays this back", TEXT_MAX));
        ApiResponse.ok(ctx, detail(views.find(payment.id()).orElseThrow(ApiException::notFound)));
    }

    /** Every payment flagged in {@code year} (by default this year), oldest first. */
    private void inYear(Context ctx) {
        String asked = ctx.queryParam("year");
        int year = asked == null ? pay.today().getYear() : year(asked);
        ApiResponse.ok(ctx, views.inYear(year));
    }

    private static int year(String asked) {
        try {
            int year = Integer.parseInt(asked);
            if (Inputs.inSupportedYears(LocalDate.of(year, 1, 1))) {
                return year;
            }
        } catch (NumberFormatException ignored) {
            // Refused below, in words.
        }
        throw ApiException.badRequest("Please choose a year from the list.");
    }

    private ItemReimbursement itemView(Item item) {
        return new ItemReimbursement(reimbursements.settings(item.id()).orElse(null), views.ofItem(item.id()), views.packsOf(item));
    }

    private PaymentDetail detail(PaymentView payment) {
        return new PaymentDetail(payment, entries.paybacksOfPayment(payment.id()), buckets.movesOutPayingBackPayment(payment.id()));
    }

    private Settings settings(Item item, SettingsInput input) {
        if (input.intoAccountId() == null || accounts.find(input.intoAccountId()).filter(account -> !account.archived()).isEmpty()) {
            throw ApiException.badRequest("Please choose the account it comes back into.");
        }
        if (input.daysAfter() == null || input.daysAfter() < 0 || input.daysAfter() > MOST_DAYS_AFTER) {
            throw ApiException.badRequest("Please enter about how many days after it comes back, from 0 to " + MOST_DAYS_AFTER + ".");
        }
        Long from = input.fromAccountId();
        if (from != null && (from.equals(input.intoAccountId()) || accounts.find(from).filter(account -> !account.archived()).isEmpty())) {
            throw ApiException.badRequest(OTHER_ACCOUNT);
        }
        return new Settings(item.id(), Inputs.optionalText(input.reimbursedBy(), "who pays it back", TEXT_MAX), input.daysAfter(),
                input.intoAccountId(), from);
    }

    /** An account money comes out of: an open one, other than the one it comes back into. {@code null} is from outside. */
    private Long fromAccount(Long accountId, Item item) {
        if (accountId == null) {
            return null;
        }
        Long into = reimbursements.settings(item.id()).map(Settings::intoAccountId).orElse(null);
        if (accountId.equals(into) || accounts.find(accountId).filter(account -> !account.archived()).isEmpty()) {
            throw ApiException.badRequest(OTHER_ACCOUNT);
        }
        return accountId;
    }

    private Item item(Context ctx) {
        return items.find(Inputs.id(ctx, "id")).orElseThrow(ApiException::notFound);
    }

    private static LocalDate scheduledDate(Context ctx) {
        LocalDate date = Inputs.date(ctx.pathParam("scheduledDate"));
        if (!Inputs.inSupportedYears(date)) {
            throw ApiException.notFound();
        }
        return date;
    }

    /** The payment named in the path, flagged; 404 when it is not. */
    private PaymentView flagged(Context ctx) {
        return views.find(Inputs.id(ctx, "id"), scheduledDate(ctx)).orElseThrow(ApiException::notFound);
    }

    /** Only money going out to someone, a bill or a budget, can be paid back. */
    private static void requirePaidOut(Item item) {
        if (item.kind() != ItemKind.BILL && item.kind() != ItemKind.BUDGET) {
            throw ApiException.conflict("Only a bill or a budget can be paid back. Transfers and card payments stay with you.");
        }
    }
}
