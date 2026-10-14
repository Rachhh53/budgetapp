package app.budget.server.items;

import app.budget.engine.Money;
import app.budget.engine.items.AnnualAmount;
import app.budget.engine.items.OccurrencePlanner;
import app.budget.engine.items.OccurrencePlanner.Planned;
import app.budget.engine.items.PeriodAssigner;
import app.budget.engine.items.Placement;
import app.budget.engine.schedule.PayPeriod;
import app.budget.engine.schedule.ScheduleRule;
import app.budget.server.income.PayCalendar;
import app.budget.server.items.InvoicesRepository.Invoice;
import app.budget.server.items.ItemsRepository.Item;
import app.budget.server.items.ItemsRepository.Override;
import app.budget.server.items.ItemsRepository.Pause;
import app.budget.server.items.ItemsRepository.Terms;
import app.budget.server.items.ItemsRepository.Version;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Works out the occurrences of recurring items from what is stored: their versions, the pay
 * periods, and the single-occurrence edits. Nothing here is saved — like {@link PayCalendar},
 * it is computed on every request, so it always reflects the current schedules.
 */
public final class ItemCalendar {

    /**
     * How far beyond the asked-for dates to look. An occurrence due just outside them can be
     * paid from a period inside them (a bill due on the 1st, paid from the paycheck before),
     * and with monthly pay that reaches a full period back.
     */
    private static final int MARGIN_MONTHS = 2;

    /** How far ahead to look for an item's next occurrence: far enough for a yearly bill. */
    private static final int NEXT_WITHIN_MONTHS = 13;

    private final ItemsRepository items;
    private final PayCalendar pay;
    private final InvoicesRepository invoices;

    public ItemCalendar(ItemsRepository items, PayCalendar pay, InvoicesRepository invoices) {
        this.items = items;
        this.pay = pay;
        this.invoices = invoices;
    }

    /**
     * One occurrence of a recurring item, with any "just this one" edit applied.
     *
     * @param scheduledDate  the date the schedule names; identifies this occurrence for edits
     * @param dueDate        when it falls due, after any weekend or holiday move or an edit
     * @param periodStart    first day of the pay period that pays for it; {@code null} when no period is known
     * @param early          true when it is paid from a period before the one it falls due in
     * @param payBy          the day it counts on: the due date, brought inside the period that pays for it
     * @param estimate       true while the amount is the item's estimate, not yet typed over or paid
     * @param projectedCents what the app showed before the amount was typed over; {@code null} if untouched
     * @param bucketId       the bucket a bucket move, or a transfer, puts this money in
     * @param saveUpBucketId the bucket this bill is saved up for in
     * @param fromBucketId   the bucket of the paying account it comes out of; {@code null} for money not in a bucket
     * @param checkNumber    the check this one was paid with; {@code null} when none was written
     * @param invoiceId      an invoice for a billed-later item's visits, standing in for them as one bill to pay (spec §4.6);
     *                       {@code null} for anything its schedule names
     * @param coversThrough  an invoice: the last visit it pays for; {@code null} otherwise
     */
    public record Occurrence(
            long itemId,
            String name,
            ItemKind kind,
            LocalDate scheduledDate,
            LocalDate dueDate,
            LocalDate periodStart,
            boolean early,
            LocalDate payBy,
            long amountCents,
            boolean estimate,
            Long projectedCents,
            boolean autopay,
            Placement placement,
            Long categoryId,
            long payFromAccountId,
            Long payToAccountId,
            boolean paid,
            LocalDate paidDate,
            String confirmationCode,
            String note,
            boolean skipped,
            Long bucketId,
            Long saveUpBucketId,
            Long fromBucketId,
            String checkNumber,
            Long invoiceId,
            LocalDate coversThrough) {

        /** This occurrence at another amount: for a bucket move that only has room for part of itself. */
        public Occurrence withAmount(long cents) {
            return new Occurrence(
                    itemId, name, kind, scheduledDate, dueDate, periodStart, early, payBy, cents, estimate, projectedCents,
                    autopay, placement, categoryId, payFromAccountId, payToAccountId, paid, paidDate, confirmationCode, note,
                    skipped, bucketId, saveUpBucketId, fromBucketId, checkNumber, invoiceId, coversThrough);
        }

        /**
         * This occurrence at the amount worked out for it: for a card payment that follows its
         * card, which is only an estimate while the statement it pays is one.
         */
        public Occurrence withAmount(long cents, boolean isEstimate) {
            return new Occurrence(
                    itemId, name, kind, scheduledDate, dueDate, periodStart, early, payBy, cents, isEstimate, projectedCents,
                    autopay, placement, categoryId, payFromAccountId, payToAccountId, paid, paidDate, confirmationCode, note,
                    skipped, bucketId, saveUpBucketId, fromBucketId, checkNumber, invoiceId, coversThrough);
        }

        /** This occurrence with its money going into no bucket: for one dated before its bucket was opened. */
        public Occurrence withoutBucket() {
            return new Occurrence(
                    itemId, name, kind, scheduledDate, dueDate, periodStart, early, payBy, amountCents, estimate, projectedCents,
                    autopay, placement, categoryId, payFromAccountId, payToAccountId, paid, paidDate, confirmationCode, note,
                    skipped, null, saveUpBucketId, fromBucketId, checkNumber, invoiceId, coversThrough);
        }
    }

    /**
     * Occurrences together with the pay periods they were placed in.
     *
     * @param visits what billed-later items' schedules name (spec §4.6): they move no money, and are
     *               not among {@code occurrences}, where their invoices are instead
     */
    public record Expansion(List<PayPeriod> periods, List<Occurrence> occurrences, List<Occurrence> visits) {}

    /**
     * What one occurrence of this version costs and what that adds up to over a year.
     * For an annual amount the yearly figure is exactly what the user typed.
     */
    public record Amounts(long perOccurrenceCents, long perYearCents) {}

    /**
     * Every occurrence of every active item that falls due, or is paid from a period, within
     * {@code from}–{@code to}. A margin either side is included; callers keep what they need.
     */
    public Expansion expand(LocalDate from, LocalDate to) {
        List<Item> active = items.all().stream().filter(item -> !item.archived()).toList();
        return expand(active, from, to);
    }

    /**
     * The occurrence of {@code item} that its schedule names on {@code scheduledDate}, if there is one:
     * a visit, for something billed later. An invoice is not among them; it is changed as an invoice.
     */
    public Optional<Occurrence> find(Item item, LocalDate scheduledDate) {
        Expansion expansion = expand(List.of(item), scheduledDate, scheduledDate);
        return Stream.concat(expansion.occurrences().stream(), expansion.visits().stream())
                .filter(occurrence -> occurrence.invoiceId() == null && occurrence.scheduledDate().equals(scheduledDate))
                .findFirst();
    }

    /**
     * What receipts can be kept with on {@code scheduledDate}: an occurrence, or an invoice by its due
     * date. A visit is not something paid, so it keeps none.
     */
    public Optional<Occurrence> findPaid(Item item, LocalDate scheduledDate) {
        return expand(List.of(item), scheduledDate, scheduledDate).occurrences().stream()
                .filter(occurrence -> occurrence.scheduledDate().equals(scheduledDate))
                .findFirst();
    }

    /** For each of {@code chosen} that has one: its next occurrence still to pay, from today on. */
    public Map<Long, Occurrence> nextByItem(List<Item> chosen) {
        LocalDate today = pay.today();
        Map<Long, Occurrence> next = new HashMap<>();
        // Occurrences come back in date order, so the first one seen for an item is its next.
        for (Occurrence occurrence : expand(chosen, today, today.plusMonths(NEXT_WITHIN_MONTHS)).occurrences()) {
            if (!occurrence.skipped() && !occurrence.paid() && !occurrence.dueDate().isBefore(today)) {
                next.putIfAbsent(occurrence.itemId(), occurrence);
            }
        }
        return next;
    }

    public Amounts amounts(Terms terms) {
        return amounts(terms, pay.payRule().orElse(null));
    }

    private Amounts amounts(Terms terms, ScheduleRule payRule) {
        AnnualAmount.PerYear perYear = AnnualAmount.perYear(terms.schedule(), payRule, pay.today().getYear());
        Money amount = Money.ofCents(terms.amountCents());
        return terms.amountMode() == AmountMode.ANNUAL
                ? new Amounts(AnnualAmount.perOccurrence(amount, perYear).cents(), amount.cents())
                : new Amounts(amount.cents(), AnnualAmount.yearly(amount, perYear).cents());
    }

    /** As {@link #expand(LocalDate, LocalDate)}, for just these items (archived ones included, if given). */
    public Expansion expand(List<Item> chosen, LocalDate from, LocalDate to) {
        LocalDate lo = from.minusMonths(MARGIN_MONTHS);
        LocalDate hi = to.plusMonths(MARGIN_MONTHS);
        List<PayPeriod> periods = pay.payPeriods(lo, hi);
        OccurrencePlanner planner = new OccurrencePlanner(pay.scheduleFor(lo, hi), periods);
        PeriodAssigner assigner = new PeriodAssigner(periods);
        ScheduleRule payRule = pay.payRule().orElse(null);

        // Edits are keyed by scheduled date, which can sit a little outside the dates its due date moved into.
        Map<Long, Map<LocalDate, Override>> edits = new HashMap<>();
        for (Override edit : items.overrides(lo.minusMonths(1), hi.plusMonths(1))) {
            edits.computeIfAbsent(edit.itemId(), id -> new HashMap<>()).put(edit.originalDueDate(), edit);
        }

        Map<Long, List<Pause>> pauses = items.pauses();
        Map<Long, List<Invoice>> invoiced = invoices.byItem();
        List<Occurrence> occurrences = new ArrayList<>();
        List<Occurrence> visits = new ArrayList<>();
        for (Item item : chosen) {
            List<Pause> paused = pauses.getOrDefault(item.id(), List.of());
            List<OccurrencePlanner.Version<Terms>> versions = item.versions().stream()
                    .map(ItemCalendar::planned)
                    .toList();
            Map<LocalDate, Override> edited = edits.getOrDefault(item.id(), Map.of());
            for (Planned<Terms> planned : planner.occurrences(versions, lo, hi)) {
                // While an item is paused, what its schedule names simply does not happen.
                if (paused.stream().anyMatch(pause -> pause.covers(planned.scheduled()))) {
                    continue;
                }
                Override edit = edited.getOrDefault(planned.scheduled(), Override.untouched(item.id(), planned.scheduled()));
                Occurrence occurrence = occurrence(item, planned, edit, assigner, payRule, null, null);
                // A visit of something billed later moves no money: its invoice does, when it comes.
                (planned.version().details().billedLater() ? visits : occurrences).add(occurrence);
            }
            for (Invoice invoice : invoiced.getOrDefault(item.id(), List.of())) {
                if (!invoice.dueDate().isBefore(lo) && !invoice.dueDate().isAfter(hi)) {
                    occurrences.add(invoiceOccurrence(item, invoice, assigner, payRule));
                }
            }
        }
        occurrences.sort(Comparator.comparing(Occurrence::payBy).thenComparing(Occurrence::name));
        visits.sort(Comparator.comparing(Occurrence::dueDate).thenComparing(Occurrence::name));
        return new Expansion(periods, occurrences, visits);
    }

    /**
     * An invoice as one bill to pay: due on its own due date, from the account its item is paid from
     * then, at the amount billed. What its visits came to is kept as the projected amount, so the
     * Projected vs. actual report can set the two side by side.
     */
    private Occurrence invoiceOccurrence(Item item, Invoice invoice, PeriodAssigner assigner, ScheduleRule payRule) {
        Version version = item.versionOn(invoice.dueDate());
        Planned<Terms> planned = new Planned<>(invoice.dueDate(), invoice.dueDate(), null, planned(version));
        Override edit = new Override(
                item.id(), invoice.dueDate(), invoice.amountCents(), null, null, invoice.paid(), invoice.paidDate(),
                invoice.confirmationCode(), invoice.note(), false,
                invoice.projectedCents() == invoice.amountCents() ? null : invoice.projectedCents(), invoice.checkNumber());
        return occurrence(item, planned, edit, assigner, payRule, invoice.id(), invoice.coversThrough());
    }

    /**
     * The visits in {@code expansion} that no invoice covers yet, skipped ones left out: a cost still
     * to be billed, which reports and annual costs count as still to come, at what each visit costs.
     */
    public List<Occurrence> notYetBilled(Expansion expansion) {
        Map<Long, LocalDate> coveredUpTo = new HashMap<>();
        invoices.byItem().forEach((itemId, list) -> list.stream()
                .map(Invoice::coversThrough)
                .max(Comparator.naturalOrder())
                .ifPresent(day -> coveredUpTo.put(itemId, day)));
        return expansion.visits().stream()
                .filter(visit -> !visit.skipped())
                .filter(visit -> {
                    LocalDate covered = coveredUpTo.get(visit.itemId());
                    return covered == null || visit.dueDate().isAfter(covered);
                })
                .toList();
    }

    /**
     * The visits of a billed-later item that no invoice covers yet, up to {@code through}, oldest
     * first; skipped ones are left out (spec §4.6).
     */
    public List<Occurrence> unbilled(Item item, LocalDate through) {
        LocalDate after = invoices.forItem(item.id()).stream()
                .map(Invoice::coversThrough)
                .max(Comparator.naturalOrder())
                .orElse(null);
        LocalDate from = after != null ? after.plusDays(1) : item.versions().getFirst().effectiveFrom();
        if (from.isAfter(through)) {
            return List.of();
        }
        return expand(List.of(item), from, through).visits().stream()
                .filter(visit -> !visit.skipped() && !visit.dueDate().isBefore(from) && !visit.dueDate().isAfter(through))
                .toList();
    }

    private Occurrence occurrence(
            Item item,
            Planned<Terms> planned,
            Override edit,
            PeriodAssigner assigner,
            ScheduleRule payRule,
            Long invoiceId,
            LocalDate coversThrough) {
        Terms terms = planned.version().details();
        LocalDate due = edit.dueDate() != null ? edit.dueDate() : planned.due();

        Optional<PayPeriod> period;
        if (edit.periodOverride() != null) {
            period = assigner.startingOn(edit.periodOverride());
        } else if (planned.ownPeriod() != null) {
            // "Every pay period" belongs to the period it was made for, wherever in it it is dated.
            period = Optional.of(planned.ownPeriod());
        } else {
            period = assigner.assign(due, terms.placement(), terms.autopay());
        }

        // The period's figures have to add up to what it lists, so an occurrence always counts
        // on a day inside the period that pays for it: "due Oct 16, pay by Oct 14".
        LocalDate payBy = period.map(found -> clamp(due, found)).orElse(due);
        LocalDate periodStart = period.map(PayPeriod::start).orElse(null);
        boolean early = periodStart != null
                && assigner.containing(due).filter(dueIn -> dueIn.start().isAfter(periodStart)).isPresent();

        boolean typedOver = edit.amountCents() != null;
        long amount = typedOver ? edit.amountCents() : amounts(terms, payRule).perOccurrenceCents();
        return new Occurrence(
                item.id(),
                item.name(),
                item.kind(),
                planned.scheduled(),
                due,
                periodStart,
                early,
                payBy,
                amount,
                // An amount stops being an estimate once it is typed over or marked paid (spec §1).
                terms.estimate() && !typedOver && !edit.paid(),
                edit.projectedCents(),
                terms.autopay(),
                terms.placement(),
                terms.categoryId(),
                terms.payFromAccountId(),
                terms.payToAccountId(),
                edit.paid(),
                edit.paidDate(),
                edit.confirmationCode(),
                edit.note(),
                edit.skipped(),
                terms.bucketId(),
                terms.saveUpBucketId(),
                terms.fromBucketId(),
                edit.checkNumber(),
                invoiceId,
                coversThrough);
    }

    private static OccurrencePlanner.Version<Terms> planned(Version version) {
        Terms terms = version.terms();
        return new OccurrencePlanner.Version<>(version.effectiveFrom(), terms.schedule(), terms.daysAfterPayday(), terms);
    }

    private static LocalDate clamp(LocalDate date, PayPeriod period) {
        if (date.isBefore(period.start())) {
            return period.start();
        }
        return date.isAfter(period.end()) ? period.end() : date;
    }
}
