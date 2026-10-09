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
import app.budget.server.items.ItemsRepository.Item;
import app.budget.server.items.ItemsRepository.Override;
import app.budget.server.items.ItemsRepository.Terms;
import app.budget.server.items.ItemsRepository.Version;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    public ItemCalendar(ItemsRepository items, PayCalendar pay) {
        this.items = items;
        this.pay = pay;
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
            Long saveUpBucketId) {

        /** This occurrence at another amount: for a bucket move that only has room for part of itself. */
        public Occurrence withAmount(long cents) {
            return new Occurrence(
                    itemId, name, kind, scheduledDate, dueDate, periodStart, early, payBy, cents, estimate, projectedCents,
                    autopay, placement, categoryId, payFromAccountId, payToAccountId, paid, paidDate, confirmationCode, note,
                    skipped, bucketId, saveUpBucketId);
        }

        /** This occurrence with its money going into no bucket: for one dated before its bucket was opened. */
        public Occurrence withoutBucket() {
            return new Occurrence(
                    itemId, name, kind, scheduledDate, dueDate, periodStart, early, payBy, amountCents, estimate, projectedCents,
                    autopay, placement, categoryId, payFromAccountId, payToAccountId, paid, paidDate, confirmationCode, note,
                    skipped, null, saveUpBucketId);
        }
    }

    /** Occurrences together with the pay periods they were placed in. */
    public record Expansion(List<PayPeriod> periods, List<Occurrence> occurrences) {}

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

    /** The occurrence of {@code item} that its schedule names on {@code scheduledDate}, if there is one. */
    public Optional<Occurrence> find(Item item, LocalDate scheduledDate) {
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

        List<Occurrence> occurrences = new ArrayList<>();
        for (Item item : chosen) {
            List<OccurrencePlanner.Version<Terms>> versions = item.versions().stream()
                    .map(ItemCalendar::planned)
                    .toList();
            Map<LocalDate, Override> edited = edits.getOrDefault(item.id(), Map.of());
            for (Planned<Terms> planned : planner.occurrences(versions, lo, hi)) {
                Override edit = edited.getOrDefault(planned.scheduled(), Override.untouched(item.id(), planned.scheduled()));
                occurrences.add(occurrence(item, planned, edit, assigner, payRule));
            }
        }
        occurrences.sort(Comparator.comparing(Occurrence::payBy).thenComparing(Occurrence::name));
        return new Expansion(periods, occurrences);
    }

    private Occurrence occurrence(Item item, Planned<Terms> planned, Override edit, PeriodAssigner assigner, ScheduleRule payRule) {
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
                terms.saveUpBucketId());
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
