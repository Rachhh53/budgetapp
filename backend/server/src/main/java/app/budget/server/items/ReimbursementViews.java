package app.budget.server.items;

import app.budget.engine.reports.Reimbursement.Share;
import app.budget.server.api.Inputs;
import app.budget.server.entries.EntriesRepository.Repayment;
import app.budget.server.files.AttachmentsRepository;
import app.budget.server.files.AttachmentsRepository.Owner;
import app.budget.server.items.ItemCalendar.Occurrence;
import app.budget.server.items.ItemsRepository.Item;
import app.budget.server.items.ReimbursementsRepository.Flagged;
import app.budget.server.items.ReimbursementsRepository.PackFlag;
import app.budget.server.items.ReimbursementsRepository.Settings;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Flagged recurring payments as the screens show them: each with its payment (what was paid, when, from where) and where
 * paying it back stands, worked out the same way as for a one-off expense (Rachel, 2026-10-24: "same as one-off expenses").
 */
public final class ReimbursementViews {

    private final ReimbursementsRepository reimbursements;
    private final ItemsRepository items;
    private final ItemCalendar calendar;
    private final AttachmentsRepository attachments;

    public ReimbursementViews(
            ReimbursementsRepository reimbursements, ItemsRepository items, ItemCalendar calendar, AttachmentsRepository attachments) {
        this.reimbursements = reimbursements;
        this.items = items;
        this.calendar = calendar;
        this.attachments = attachments;
    }

    /**
     * One flagged payment.
     *
     * @param scheduledDate the date the item's schedule names, which identifies the payment
     * @param kind          the item's kind: {@code bill} or {@code budget}
     * @param date          the day it was paid, once it is; until then the day it is due
     * @param paymentCents  what the payment itself is
     * @param estimate      true while the payment's amount is an estimate
     * @param accountId     the account it is paid from
     * @param amountCents   what is to come back
     * @param reimbursement where paying it back stands, with what has come back and what is still owed
     * @param expectedOn    the day the rest is planned to come back: {@code date} plus the item's days after; {@code null}
     *                      when the item has no way back set
     * @param intoAccountId the account it comes back into; {@code null} when the item has no way back set
     * @param fromAccountId the account it comes out of, like an FSA; {@code null} from outside
     * @param skipped       true when the payment was skipped since it was flagged, so nothing will be paid to come back
     * @param fileCount     receipts and documents attached to the payment
     */
    public record PaymentView(
            long id,
            long itemId,
            LocalDate scheduledDate,
            String name,
            String kind,
            LocalDate date,
            boolean paid,
            long paymentCents,
            boolean estimate,
            long accountId,
            Long categoryId,
            List<Long> tagIds,
            long amountCents,
            Repayment reimbursement,
            LocalDate expectedOn,
            Long intoAccountId,
            Long fromAccountId,
            boolean skipped,
            int fileCount) {}

    /**
     * A planned pack flagged to be paid back (migration V47; Rachel, 2026-10-25: "Flag each pack").
     *
     * @param packNumber    its place in line: 1 is the next pack to buy
     * @param plannedDate   the day Budget planned it on, which names it
     * @param dueDate       the day it is due, moved or not
     * @param priceCents    what the pack costs
     * @param amountCents   what comes back, no more than the price
     * @param expectedOn    the day it is planned to come back: the due day plus the item's days after
     * @param intoAccountId the account it comes back into
     * @param fromAccountId the account it comes out of; {@code null} from outside
     */
    public record PackView(
            long itemId,
            String name,
            int packNumber,
            LocalDate plannedDate,
            LocalDate dueDate,
            long priceCents,
            long amountCents,
            LocalDate expectedOn,
            long intoAccountId,
            Long fromAccountId) {}

    /** Every planned pack flagged to be paid back, by its due day. */
    public List<PackView> packs() {
        Map<Long, Settings> settings = reimbursements.allSettings();
        List<PackView> all = new ArrayList<>();
        reimbursements.packFlags().forEach((itemId, flags) -> items.find(itemId)
                .ifPresent(item -> all.addAll(packs(item, flags, settings.get(itemId)))));
        all.sort(Comparator.comparing(PackView::dueDate).thenComparing(PackView::name, String.CASE_INSENSITIVE_ORDER));
        return all;
    }

    /** An item's planned packs flagged to be paid back, by its due day. */
    public List<PackView> packsOf(Item item) {
        Map<Integer, PackFlag> flags = reimbursements.packFlags().getOrDefault(item.id(), Map.of());
        return packs(item, flags, reimbursements.settings(item.id()).orElse(null));
    }

    /**
     * The planned packs in line, the next one first: a pack's place is what its flag is kept under, so it is counted in
     * the order Budget planned them, whatever day any was moved to.
     */
    public List<Occurrence> packsInLine(Item item) {
        return calendar.plannedPacks(item).stream().sorted(Comparator.comparing(Occurrence::scheduledDate)).toList();
    }

    private List<PackView> packs(Item item, Map<Integer, PackFlag> flags, Settings settings) {
        // Without a way back there is nowhere to plan the money; flags only beyond the look-ahead wait until they come in view.
        if (flags.isEmpty() || settings == null) {
            return List.of();
        }
        List<Occurrence> inLine = packsInLine(item);
        List<PackView> views = new ArrayList<>();
        for (int i = 0; i < inLine.size(); i++) {
            PackFlag flag = flags.get(i + 1);
            if (flag != null) {
                Occurrence pack = inLine.get(i);
                views.add(new PackView(item.id(), item.name(), i + 1, pack.scheduledDate(), pack.dueDate(), pack.amountCents(),
                        Math.min(flag.amountCents(), pack.amountCents()), pack.dueDate().plusDays(settings.daysAfter()),
                        settings.intoAccountId(), flag.fromAccountId()));
            }
        }
        views.sort(Comparator.comparing(PackView::dueDate));
        return views;
    }

    /** Every flagged payment, by its date, oldest first. */
    public List<PaymentView> all() {
        return views(reimbursements.all());
    }

    /** The flagged payments dated within {@code year}, oldest first. */
    public List<PaymentView> inYear(int year) {
        return all().stream().filter(view -> view.date().getYear() == year).toList();
    }

    /** An item's flagged payments, oldest first. */
    public List<PaymentView> ofItem(long itemId) {
        return views(reimbursements.ofItem(itemId));
    }

    public Optional<PaymentView> find(long id) {
        return reimbursements.find(id).flatMap(flagged -> views(List.of(flagged)).stream().findFirst());
    }

    public Optional<PaymentView> find(long itemId, LocalDate scheduledDate) {
        return reimbursements.find(itemId, scheduledDate).flatMap(flagged -> views(List.of(flagged)).stream().findFirst());
    }

    private List<PaymentView> views(List<Flagged> flagged) {
        if (flagged.isEmpty()) {
            return List.of();
        }
        Map<Long, Settings> settings = reimbursements.allSettings();
        LocalDate first = flagged.stream().map(Flagged::scheduledDate).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate last = flagged.stream().map(Flagged::scheduledDate).max(Comparator.naturalOrder()).orElseThrow();
        // Paid or moved, a payment's own day can be some way from the day its schedule names.
        Map<Owner.Occurrence, Integer> files = attachments.countsByOccurrence(first.minusYears(1), last.plusYears(1));
        List<PaymentView> views = new ArrayList<>();
        for (Flagged one : flagged) {
            Optional<Item> item = items.find(one.itemId());
            if (item.isEmpty()) {
                continue;
            }
            // Asked for by its date, a payment the schedule no longer names is found too.
            Optional<Occurrence> found = calendar.find(item.get(), one.scheduledDate());
            views.add(view(one, item.get(), found.orElse(null), settings.get(one.itemId()),
                    files.getOrDefault(new Owner.Occurrence(one.itemId(), one.scheduledDate()), 0)));
        }
        views.sort(Comparator.comparing(PaymentView::date).thenComparing(PaymentView::name, String.CASE_INSENSITIVE_ORDER));
        return views;
    }

    /** @param occurrence the payment; {@code null} when the item no longer has one on that date */
    private static PaymentView view(Flagged flagged, Item item, Occurrence occurrence, Settings settings, int fileCount) {
        LocalDate date = occurrence == null ? flagged.scheduledDate()
                : occurrence.paid() && occurrence.paidDate() != null ? occurrence.paidDate()
                : occurrence.dueDate();
        Share share = flagged.share();
        LocalDate expectedOn = settings == null ? null : date.plusDays(settings.daysAfter());
        Repayment repayment = new Repayment(flagged.status(), flagged.submittedOn(), flagged.to(), flagged.paidBackCents(),
                share.reimbursed().cents(), share.owed().cents(), flagged.amountCents(),
                settings == null ? null : settings.intoAccountId(), expectedOn, flagged.fromAccountId());
        return new PaymentView(
                flagged.id(),
                item.id(),
                flagged.scheduledDate(),
                item.name(),
                Inputs.code(item.kind()),
                date,
                occurrence != null && occurrence.paid(),
                occurrence == null ? flagged.amountCents() : occurrence.amountCents(),
                occurrence != null && occurrence.estimate(),
                occurrence == null ? item.versionOn(flagged.scheduledDate()).terms().payFromAccountId() : occurrence.payFromAccountId(),
                occurrence == null ? item.versionOn(flagged.scheduledDate()).terms().categoryId() : occurrence.categoryId(),
                item.tagIds(),
                flagged.amountCents(),
                repayment,
                expectedOn,
                settings == null ? null : settings.intoAccountId(),
                flagged.fromAccountId(),
                occurrence == null || occurrence.skipped(),
                fileCount);
    }
}
