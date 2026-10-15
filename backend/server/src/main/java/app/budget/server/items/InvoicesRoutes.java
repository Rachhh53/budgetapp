package app.budget.server.items;

import app.budget.server.accounts.AccountType;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.income.PayCalendar;
import app.budget.server.items.InvoicesRepository.Fields;
import app.budget.server.items.InvoicesRepository.Invoice;
import app.budget.server.items.ItemCalendar.Occurrence;
import app.budget.server.items.ItemsRepository.Item;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Billed later (spec §4.6): the visits of an item that no invoice covers yet, and its invoices.
 * {@code /api/items/{id}/invoices} lists both and adds an invoice for the visits up to a day;
 * {@code /api/items/{id}/invoices/{invoiceId}} changes one (its amount, dates, whether it is paid)
 * or removes it, which leaves its visits not billed again.
 */
public final class InvoicesRoutes {

    private final InvoicesRepository invoices;
    private final ItemsRepository items;
    private final ItemCalendar calendar;
    private final AccountsRepository accounts;
    private final PayCalendar pay;

    public InvoicesRoutes(
            InvoicesRepository invoices, ItemsRepository items, ItemCalendar calendar, AccountsRepository accounts, PayCalendar pay) {
        this.invoices = invoices;
        this.items = items;
        this.calendar = calendar;
        this.accounts = accounts;
        this.pay = pay;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/items/{id}/invoices", this::list);
        routes.post("/api/items/{id}/invoices", this::create);
        routes.put("/api/items/{id}/invoices/{invoiceId}", this::update);
        routes.delete("/api/items/{id}/invoices/{invoiceId}", this::delete);
    }

    /**
     * A new invoice.
     *
     * @param coversThrough the last visit it is for; left out, every visit not billed up to the invoice date
     * @param amountCents   what it bills; left out, what those visits come to
     */
    public record InvoiceInput(LocalDate invoiceDate, LocalDate dueDate, LocalDate coversThrough, Long amountCents, String note) {}

    /** A change to an invoice. Only what is sent changes; an empty confirmation code, check number or note clears it. */
    public record InvoiceChange(
            Long amountCents,
            LocalDate invoiceDate,
            LocalDate dueDate,
            Boolean paid,
            LocalDate paidDate,
            String confirmationCode,
            String checkNumber,
            String note) {}

    /**
     * One visit no invoice covers yet.
     *
     * @param skipped the visit did not (or will not) happen: it is in no total and on no invoice
     */
    public record Visit(LocalDate scheduledDate, LocalDate date, long amountCents, boolean skipped) {}

    /** How many visits still to come are listed, so the next one or two can be skipped ahead of time. */
    private static final int UPCOMING_SHOWN = 3;

    /** How far ahead to look for them: three monthly visits fit in this. */
    private static final int UPCOMING_DAYS = 100;

    /**
     * An invoice with the pay period that pays it.
     *
     * @param periodStart {@code null} when no pay period holds its due date
     */
    public record InvoiceView(Invoice invoice, LocalDate periodStart) {}

    /**
     * @param notBilled      the visits up to today that no invoice covers, oldest first
     * @param notBilledCents what they come to
     * @param skipped        the visits up to today that no invoice covers and that were skipped, oldest first
     * @param upcoming       the next few visits after today, skipped ones included
     * @param invoices       newest first
     */
    public record Billing(
            List<Visit> notBilled, long notBilledCents, List<Visit> skipped, List<Visit> upcoming, List<InvoiceView> invoices) {}

    public record Deleted(boolean deleted) {}

    private void list(Context ctx) {
        Item item = billedLater(Inputs.id(ctx, "id"));
        ApiResponse.ok(ctx, billing(item));
    }

    private Billing billing(Item item) {
        LocalDate today = pay.today();
        List<Visit> waiting = calendar.uninvoiced(item, today.plusDays(UPCOMING_DAYS)).stream()
                .map(visit -> new Visit(visit.scheduledDate(), visit.dueDate(), visit.amountCents(), visit.skipped()))
                .toList();
        List<Visit> visits = waiting.stream().filter(visit -> !visit.date().isAfter(today) && !visit.skipped()).toList();
        List<Visit> skipped = waiting.stream().filter(visit -> !visit.date().isAfter(today) && visit.skipped()).toList();
        List<Visit> upcoming = waiting.stream().filter(visit -> visit.date().isAfter(today)).limit(UPCOMING_SHOWN).toList();
        List<InvoiceView> views = invoices.forItem(item.id()).stream()
                .sorted(Comparator.comparing(Invoice::coversThrough).reversed())
                .map(invoice -> view(item, invoice))
                .toList();
        return new Billing(visits, visits.stream().mapToLong(Visit::amountCents).sum(), skipped, upcoming, views);
    }

    private void create(Context ctx) {
        Item item = billedLater(Inputs.id(ctx, "id"));
        InvoiceInput input = Inputs.body(ctx, InvoiceInput.class);
        if (input.invoiceDate() == null) {
            throw ApiException.badRequest("Please choose the day the invoice is dated.");
        }
        if (input.dueDate() == null) {
            throw ApiException.badRequest("Please choose the day the invoice is due.");
        }
        ItemsRoutes.requireSupported(input.invoiceDate(), "the invoice date");
        ItemsRoutes.requireSupported(input.dueDate(), "the due date");
        if (input.dueDate().isBefore(input.invoiceDate())) {
            throw ApiException.badRequest("Please choose a due date on or after the invoice date.");
        }
        LocalDate coversThrough = input.coversThrough() == null ? input.invoiceDate() : input.coversThrough();
        ItemsRoutes.requireSupported(coversThrough, "the last visit it covers");

        List<Occurrence> covered = calendar.unbilled(item, coversThrough);
        if (covered.isEmpty()) {
            throw ApiException.badRequest("There are no visits waiting to be billed up to that day. Please check the dates.");
        }
        long projected = covered.stream().mapToLong(Occurrence::amountCents).sum();
        long amount = input.amountCents() == null ? projected : Inputs.amount(input.amountCents(), "The amount billed");
        requireFreeDueDate(item, input.dueDate(), null);

        long id = invoices.create(item.id(), new Fields(
                input.invoiceDate(), input.dueDate(), coversThrough, amount, projected, false, null, null, null,
                Inputs.optionalText(input.note(), "the note", ItemsRoutes.NOTE_MAX)));
        ApiResponse.send(ctx, 201, view(item, invoices.find(id).orElseThrow()));
    }

    private void update(Context ctx) {
        Item item = billedLater(Inputs.id(ctx, "id"));
        Invoice stored = invoice(item, ctx);
        InvoiceChange input = Inputs.body(ctx, InvoiceChange.class);
        ItemsRoutes.requireSupported(input.invoiceDate(), "the invoice date");
        ItemsRoutes.requireSupported(input.dueDate(), "the due date");
        ItemsRoutes.requireSupported(input.paidDate(), "the date it was paid");
        LocalDate invoiceDate = input.invoiceDate() == null ? stored.invoiceDate() : input.invoiceDate();
        LocalDate dueDate = input.dueDate() == null ? stored.dueDate() : input.dueDate();
        if (dueDate.isBefore(invoiceDate)) {
            throw ApiException.badRequest("Please choose a due date on or after the invoice date.");
        }
        if (!dueDate.equals(stored.dueDate())) {
            requireFreeDueDate(item, dueDate, stored.id());
        }
        // A check can only have been written on a checking account.
        long payFrom = item.versionOn(dueDate).terms().payFromAccountId();
        if (input.checkNumber() != null && !input.checkNumber().isBlank() && accounts.find(payFrom)
                .filter(account -> account.type().equals(Inputs.code(AccountType.CHECKING)))
                .isEmpty()) {
            throw ApiException.badRequest("A check number can be kept for something paid from a checking account.");
        }
        boolean paid = input.paid() == null ? stored.paid() : input.paid();
        invoices.update(stored.id(), new Fields(
                invoiceDate,
                dueDate,
                stored.coversThrough(),
                input.amountCents() == null ? stored.amountCents() : Inputs.amount(input.amountCents(), "The amount billed"),
                stored.projectedCents(),
                paid,
                // Un-ticking "paid" forgets when; ticking it without a date leaves the date open.
                !paid ? null : input.paidDate() == null ? stored.paidDate() : input.paidDate(),
                input.confirmationCode() == null
                        ? stored.confirmationCode()
                        : Inputs.optionalText(input.confirmationCode(), "the confirmation code", ItemsRoutes.CODE_MAX),
                input.checkNumber() == null
                        ? stored.checkNumber()
                        : Inputs.optionalText(input.checkNumber(), "the check number", ItemsRoutes.CHECK_NUMBER_MAX),
                input.note() == null ? stored.note() : Inputs.optionalText(input.note(), "the note", ItemsRoutes.NOTE_MAX)));
        ApiResponse.ok(ctx, view(item, invoices.find(stored.id()).orElseThrow()));
    }

    /** Only the newest invoice can be removed, so the visits it leaves not billed are the latest ones. */
    private void delete(Context ctx) {
        Item item = billedLater(Inputs.id(ctx, "id"));
        Invoice invoice = invoice(item, ctx);
        boolean newest = invoices.forItem(item.id()).stream().noneMatch(other -> other.coversThrough().isAfter(invoice.coversThrough()));
        if (!newest) {
            throw ApiException.conflict("Only the newest invoice can be removed. Please remove the later ones first.");
        }
        invoices.delete(invoice.id());
        ApiResponse.ok(ctx, new Deleted(true));
    }

    /** The item, which has to be (or have been) billed later to have visits and invoices. */
    private Item billedLater(long id) {
        Item item = items.find(id).orElseThrow(ApiException::notFound);
        if (item.versions().stream().noneMatch(version -> version.terms().billedLater())) {
            throw ApiException.conflict("This isn't billed later, so it has no invoices. Turn on “Billed later” for it first.");
        }
        return item;
    }

    private Invoice invoice(Item item, Context ctx) {
        return invoices.find(Inputs.id(ctx, "invoiceId"))
                .filter(invoice -> invoice.itemId() == item.id())
                .orElseThrow(ApiException::notFound);
    }

    /** Receipts are kept by the item and the due date, so two invoices of one item cannot share a due date. */
    private void requireFreeDueDate(Item item, LocalDate dueDate, Long except) {
        boolean taken = invoices.forItem(item.id()).stream()
                .anyMatch(other -> other.dueDate().equals(dueDate) && (except == null || other.id() != except));
        if (taken) {
            throw ApiException.conflict("Another invoice for this is due that day. Please choose a different due date.");
        }
    }

    private InvoiceView view(Item item, Invoice invoice) {
        LocalDate periodStart = calendar.expand(List.of(item), invoice.dueDate(), invoice.dueDate()).occurrences().stream()
                .filter(occurrence -> Long.valueOf(invoice.id()).equals(occurrence.invoiceId()))
                .findFirst()
                .map(Occurrence::periodStart)
                .orElse(null);
        return new InvoiceView(invoice, periodStart);
    }
}
