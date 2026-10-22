package app.budget.server.items;

import app.budget.engine.Money;
import app.budget.engine.items.AnnualAmount;
import app.budget.engine.items.OccurrencePlanner;
import app.budget.engine.items.PaidAhead;
import app.budget.engine.items.OccurrencePlanner.Planned;
import app.budget.engine.items.PeriodAssigner;
import app.budget.engine.items.Placement;
import app.budget.engine.items.UsageBill;
import app.budget.engine.schedule.PayPeriod;
import app.budget.engine.schedule.ScheduleRule;
import app.budget.server.api.Archiving;
import app.budget.server.income.PayCalendar;
import app.budget.server.items.InvoicesRepository.Invoice;
import app.budget.server.items.ItemsRepository.Item;
import app.budget.server.items.ItemsRepository.Override;
import app.budget.server.items.ItemsRepository.Pause;
import app.budget.server.items.ItemsRepository.Terms;
import app.budget.server.items.ItemsRepository.Version;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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
    private final PrepaymentsRepository prepayments;

    public ItemCalendar(ItemsRepository items, PayCalendar pay, InvoicesRepository invoices, PrepaymentsRepository prepayments) {
        this.items = items;
        this.pay = pay;
        this.invoices = invoices;
        this.prepayments = prepayments;
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
     * @param coversThrough  an invoice: the last visit it pays for; a planned pack: the last session it would cover;
     *                       {@code null} otherwise
     * @param plannedPack    the next pack of something paid ahead as a pack, planned by Budget on the first session
     *                       nothing paid covers (spec §4.6). It is paid by recording the payment, never edited.
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
            LocalDate coversThrough,
            boolean plannedPack) {

        /** Anything but a planned pack. */
        public Occurrence(
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
            this(itemId, name, kind, scheduledDate, dueDate, periodStart, early, payBy, amountCents, estimate, projectedCents,
                    autopay, placement, categoryId, payFromAccountId, payToAccountId, paid, paidDate, confirmationCode, note,
                    skipped, bucketId, saveUpBucketId, fromBucketId, checkNumber, invoiceId, coversThrough, false);
        }

        /**
         * A session as the next pack, due on it: the pack's price, untouched by anything typed over the
         * session, through the last session it would cover.
         */
        Occurrence asPack(long priceCents, LocalDate lastCovered) {
            return new Occurrence(
                    itemId, name, ItemKind.BILL, scheduledDate, dueDate, periodStart, early, payBy, priceCents, false, null,
                    false, placement, categoryId, payFromAccountId, null, false, null, null, null, false, null, null, fromBucketId,
                    null, null, lastCovered, true);
        }

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
     * @param visits   what billed-later items' schedules name (spec §4.6): they move no money, and are
     *                 not among {@code occurrences}, where their invoices are instead
     * @param sessions what paid-ahead items' schedules name (spec §4.6): they move no money either, but
     *                 use up what was paid ahead, which was a one-off entry
     */
    public record Expansion(
            List<PayPeriod> periods, List<Occurrence> occurrences, List<Occurrence> visits, List<Occurrence> sessions) {}

    /**
     * What one occurrence of this version costs and what that adds up to over a year.
     * For an annual amount the yearly figure is exactly what the user typed.
     */
    public record Amounts(long perOccurrenceCents, long perYearCents) {}

    /**
     * Every occurrence of every item that falls due, or is paid from a period, within {@code from}–{@code to}. A margin
     * either side is included; callers keep what they need. An archived item has what came before the day it was
     * archived as of, and nothing from that day on.
     */
    public Expansion expand(LocalDate from, LocalDate to) {
        return expand(items.all(), from, to);
    }

    /**
     * The occurrence of {@code item} that its schedule names on {@code scheduledDate}, if there is one:
     * a visit, for something billed later, or a session, for something paid ahead. An invoice is not
     * among them; it is changed as an invoice.
     */
    public Optional<Occurrence> find(Item item, LocalDate scheduledDate) {
        Expansion expansion = expand(List.of(item), scheduledDate, scheduledDate);
        return Stream.of(expansion.occurrences(), expansion.visits(), expansion.sessions())
                .flatMap(List::stream)
                // A planned pack shares its first session's date, but is paid by recording the payment, never edited.
                .filter(occurrence -> occurrence.invoiceId() == null && !occurrence.plannedPack() && occurrence.scheduledDate().equals(scheduledDate))
                .findFirst();
    }

    /**
     * What receipts can be kept with on {@code scheduledDate}: an occurrence, or an invoice by its due
     * date. A visit is not something paid, so it keeps none.
     */
    public Optional<Occurrence> findPaid(Item item, LocalDate scheduledDate) {
        return expand(List.of(item), scheduledDate, scheduledDate).occurrences().stream()
                .filter(occurrence -> !occurrence.plannedPack() && occurrence.scheduledDate().equals(scheduledDate))
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

    /**
     * {@code item}'s next {@code count} dates from today on, within the coming year, skipped ones included so
     * they can be put back (Rachel, 2026-10-20: "allow me to change upcoming amounts in the recurring screen").
     * What its schedule names that moves no money (a visit billed later, a session paid ahead) isn't among them.
     */
    public List<Occurrence> upcoming(Item item, int count) {
        LocalDate today = pay.today();
        return expand(List.of(item), today, today.plusMonths(NEXT_WITHIN_MONTHS)).occurrences().stream()
                .filter(occurrence -> occurrence.itemId() == item.id() && !occurrence.dueDate().isBefore(today))
                .sorted(Comparator.comparing(Occurrence::dueDate))
                .limit(count)
                .toList();
    }

    public Amounts amounts(Terms terms) {
        return amounts(terms, pay.payRule().orElse(null));
    }

    private Amounts amounts(Terms terms, ScheduleRule payRule) {
        AnnualAmount.PerYear perYear = AnnualAmount.perYear(terms.schedule(), payRule, pay.today().getYear());
        Money amount = Money.ofCents(terms.amountCents());
        if (terms.billedByUse()) {
            // Billed by use: a whole month of uses, and that for each month it can happen in.
            int year = pay.today().getYear();
            int months = (int) Arrays.stream(Month.values()).filter(month -> terms.schedule().runsIn(LocalDate.of(year, month, 1))).count();
            return new Amounts(
                    UsageBill.typicalMonth(amount, terms.usage()).cents(),
                    UsageBill.yearly(amount, terms.usage(), months).cents());
        }
        Optional<PaidAhead.Pack> pack = terms.pack();
        if (pack.isPresent()) {
            // A session of a pack costs its share of the price: $1,275 for 20 is $63.75 a session.
            Money session = AnnualAmount.perOccurrence(amount, new AnnualAmount.PerYear(pack.get().sessions(), 1));
            return new Amounts(session.cents(), AnnualAmount.yearly(session, perYear).cents());
        }
        return terms.amountMode() == AmountMode.ANNUAL
                ? new Amounts(AnnualAmount.perOccurrence(amount, perYear).cents(), amount.cents())
                : new Amounts(amount.cents(), AnnualAmount.yearly(amount, perYear).cents());
    }

    /** As {@link #expand(LocalDate, LocalDate)}, for just these items. */
    public Expansion expand(List<Item> chosen, LocalDate from, LocalDate to) {
        Expansion plain = expandSchedules(chosen, from, to);
        List<Item> packs = chosen.stream().filter(item -> item.versions().stream().anyMatch(version -> version.terms().pack().isPresent())).toList();
        if (packs.isEmpty()) {
            return plain;
        }
        // The next packs of anything paid ahead as a pack are bills like any other, in the range asked for.
        LocalDate lo = from.minusMonths(MARGIN_MONTHS);
        LocalDate hi = to.plusMonths(MARGIN_MONTHS);
        List<Occurrence> occurrences = new ArrayList<>(plain.occurrences());
        for (Item item : packs) {
            plannedPacks(item).stream()
                    .filter(pack -> !pack.dueDate().isBefore(lo) && !pack.dueDate().isAfter(hi))
                    .filter(pack -> !Archiving.awayOn(item.archivedOn(), pack.dueDate()))
                    .forEach(occurrences::add);
        }
        occurrences.sort(Comparator.comparing(Occurrence::payBy).thenComparing(Occurrence::name));
        return new Expansion(plain.periods(), occurrences, plain.visits(), plain.sessions());
    }

    /**
     * A pack still to be bought.
     *
     * @param planned     the day Budget plans it on, which is also what a move of it is kept under
     * @param lastCovered the last session it would cover; {@code null} on a pack schedule, where it isn't worked out
     */
    private record PackDue(LocalDate planned, LocalDate lastCovered, long priceCents) {}

    /**
     * The packs still to be bought for {@code item}, paid ahead as a pack (spec §4.6), as bills placed in
     * their pay periods, each on the day it was moved to if it was (Rachel, 2026-10-20: "the option to just
     * change the date"). As far ahead as the look-ahead goes.
     */
    public List<Occurrence> plannedPacks(Item item) {
        List<PackDue> due = packsDue(item);
        if (due.isEmpty()) {
            return List.of();
        }
        Map<LocalDate, LocalDate> moves = items.packMoves(item.id());
        List<LocalDate> days = due.stream().map(pack -> moves.getOrDefault(pack.planned(), pack.planned())).toList();
        LocalDate lo = Collections.min(days).minusMonths(MARGIN_MONTHS);
        LocalDate hi = Collections.max(days).plusMonths(MARGIN_MONTHS);
        PeriodAssigner assigner = new PeriodAssigner(pay.payPeriods(lo, hi));
        ScheduleRule payRule = pay.payRule().orElse(null);
        List<Occurrence> planned = new ArrayList<>();
        for (int i = 0; i < due.size(); i++) {
            PackDue pack = due.get(i);
            Planned<Terms> on = new Planned<>(pack.planned(), days.get(i), null, planned(item.versionOn(pack.planned())));
            planned.add(occurrence(item, on, Override.untouched(item.id(), pack.planned()), assigner, payRule, null, null)
                    .asPack(pack.priceCents(), pack.lastCovered()));
        }
        planned.sort(Comparator.comparing(Occurrence::dueDate));
        return planned;
    }

    /** When the packs are due: on the pack's own schedule when it has one today, otherwise as the sessions use them up. */
    private List<PackDue> packsDue(Item item) {
        LocalDate today = pay.today();
        Terms terms = item.versionOn(today).terms();
        return terms.pack().isPresent() && terms.packSchedule() != null ? scheduledPacks(item, terms) : packsAsUsed(item);
    }

    /**
     * Each pack due on the first session that what was paid so far, and the packs before it, do not cover,
     * at the price in force that day (Rachel, 2026-10-19: "I pay 1275 for a 20 session pack… Yes, plan it").
     * Skipped sessions use nothing, so they push the next pack later.
     */
    private List<PackDue> packsAsUsed(Item item) {
        List<PackDue> planned = new ArrayList<>();
        Money paid = paidFor(item);
        List<Occurrence> remaining = activeSessions(item);
        // The price and size can change from a version on, so each pack is worked out from the one in force on its day.
        while (!remaining.isEmpty()) {
            Optional<PaidAhead.Pack> pack = item.versionOn(remaining.getFirst().scheduledDate()).terms().pack();
            if (pack.isEmpty()) {
                break;
            }
            List<LocalDate> days = remaining.stream().map(Occurrence::dueDate).toList();
            List<LocalDate> due = PaidAhead.nextPacks(paid, days, pack.get());
            if (due.isEmpty()) {
                break;
            }
            int at = days.indexOf(due.getFirst());
            int last = Math.min(at + pack.get().sessions(), remaining.size()) - 1;
            planned.add(new PackDue(remaining.get(at).dueDate(), remaining.get(last).dueDate(), pack.get().price().cents()));
            remaining = remaining.subList(last + 1, remaining.size());
            paid = Money.ZERO;
        }
        return planned;
    }

    /**
     * Each pack due on the pack's own schedule (Rachel, 2026-10-20: every 10 weeks, say). The schedule
     * counts from when it was set: each payment recorded since then pays the next date on it, early or late,
     * so what is left planned are the dates after those.
     */
    private List<PackDue> scheduledPacks(Item item, Terms terms) {
        LocalDate setOn = packScheduleSetOn(item, terms.packSchedule());
        ScheduleRule rule = terms.packSchedule();
        LocalDate from = rule.startDate().isAfter(setOn) ? rule.startDate() : setOn;
        LocalDate to = pay.horizonEnd();
        if (from.isAfter(to)) {
            return List.of();
        }
        long paidSince = prepayments.forItem(item.id()).stream().filter(payment -> !payment.date().isBefore(setOn)).count();
        long price = terms.pack().orElseThrow().price().cents();
        return pay.scheduleFor(from, to).occurrences(rule, from, to).stream()
                .skip(paidSince)
                .map(date -> new PackDue(date.date(), null, price))
                .toList();
    }

    /** The day the pack schedule in force today was set: where the versions carrying it unchanged begin. */
    private static LocalDate packScheduleSetOn(Item item, ScheduleRule rule) {
        LocalDate setOn = null;
        for (Version version : item.versions()) {
            if (rule.equals(version.terms().packSchedule())) {
                setOn = setOn == null ? version.effectiveFrom() : setOn;
            } else {
                setOn = null;
            }
        }
        return setOn == null ? item.versions().getLast().effectiveFrom() : setOn;
    }

    /**
     * On a pack schedule, the first session the payments so far and the packs planned by its day don't cover:
     * a pack due too late for it (spec §4.6). Empty when every session is covered in time, and for anything
     * else, which is warned about from its own standing or has its next pack planned when the last one runs out.
     */
    public Optional<LocalDate> shortBeforeNextPack(Item item) {
        Terms terms = item.versionOn(pay.today()).terms();
        if (terms.pack().isEmpty() || terms.packSchedule() == null) {
            return Optional.empty();
        }
        PaidAhead.Pack pack = terms.pack().get();
        List<Map.Entry<LocalDate, Long>> money = new ArrayList<>();
        prepayments.forItem(item.id()).forEach(payment -> money.add(Map.entry(payment.date(), payment.amountCents())));
        plannedPacks(item).forEach(next -> money.add(Map.entry(next.dueDate(), next.amountCents())));
        int used = 0;
        LocalDate horizon = pay.horizonEnd();
        for (Occurrence session : activeSessions(item)) {
            // Packs are planned only as far as the look-ahead; past it, a session would look unpaid when it isn't.
            if (session.dueDate().isAfter(horizon)) {
                break;
            }
            used++;
            long paidBy = money.stream().filter(paid -> !paid.getKey().isAfter(session.dueDate())).mapToLong(Map.Entry::getValue).sum();
            // Whole sessions only, as everywhere: what was paid by then covers ⌊paid × sessions ÷ price⌋ of them.
            long covered = BigInteger.valueOf(paidBy).multiply(BigInteger.valueOf(pack.sessions()))
                    .divide(BigInteger.valueOf(pack.price().cents())).longValueExact();
            if (used > covered) {
                return Optional.of(session.dueDate());
            }
        }
        return Optional.empty();
    }

    /** Everything paid ahead for {@code item}. */
    private Money paidFor(Item item) {
        return Money.ofCents(prepayments.forItem(item.id()).stream().mapToLong(PrepaymentsRepository.Prepayment::amountCents).sum());
    }

    /** {@code item}'s sessions over its whole life to the look-ahead's end, skipped ones left out, oldest first. */
    private List<Occurrence> activeSessions(Item item) {
        return expandSchedules(List.of(item), item.versions().getFirst().effectiveFrom(), pay.horizonEnd()).sessions().stream()
                .filter(session -> !session.skipped())
                .toList();
    }

    /** {@link #expand(List, LocalDate, LocalDate)} without the planned packs: what the schedules themselves name. */
    private Expansion expandSchedules(List<Item> chosen, LocalDate from, LocalDate to) {
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
        List<Occurrence> sessions = new ArrayList<>();
        for (Item item : chosen) {
            List<Pause> paused = pauses.getOrDefault(item.id(), List.of());
            List<OccurrencePlanner.Version<Terms>> versions = item.versions().stream()
                    .map(ItemCalendar::planned)
                    .toList();
            Map<LocalDate, Override> edited = edits.getOrDefault(item.id(), Map.of());
            for (Planned<Terms> planned : planner.occurrences(versions, lo, hi)) {
                // While an item is paused, what its schedule names simply does not happen; nor once it is archived.
                if (paused.stream().anyMatch(pause -> pause.covers(planned.scheduled()))
                        || Archiving.awayOn(item.archivedOn(), planned.scheduled())) {
                    continue;
                }
                Override edit = edited.getOrDefault(planned.scheduled(), Override.untouched(item.id(), planned.scheduled()));
                Occurrence occurrence = occurrence(item, planned, edit, assigner, payRule, null, null);
                // Billed by use: no bill comes when the days since the last one had no months it can happen in.
                if (planned.version().details().billedByUse() && edit.amountCents() == null && occurrence.amountCents() == 0) {
                    continue;
                }
                // A visit of something billed later moves no money: its invoice does, when it comes. Nor
                // does a session of something paid ahead: the payment made ahead, a one-off, already did.
                Terms terms = planned.version().details();
                (terms.billedLater() ? visits : terms.paidAhead() ? sessions : occurrences).add(occurrence);
            }
            for (Invoice invoice : invoiced.getOrDefault(item.id(), List.of())) {
                if (!invoice.dueDate().isBefore(lo) && !invoice.dueDate().isAfter(hi)) {
                    occurrences.add(invoiceOccurrence(item, invoice, assigner, payRule));
                }
            }
        }
        occurrences.sort(Comparator.comparing(Occurrence::payBy).thenComparing(Occurrence::name));
        visits.sort(Comparator.comparing(Occurrence::dueDate).thenComparing(Occurrence::name));
        sessions.sort(Comparator.comparing(Occurrence::dueDate).thenComparing(Occurrence::name));
        return new Expansion(periods, occurrences, visits, sessions);
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
                invoice.projectedCents() == invoice.amountCents() ? null : invoice.projectedCents(), invoice.checkNumber(), null);
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
     * What the paid-ahead items among {@code chosen} cost within {@code from}–{@code to} (spec §4.6),
     * for annual costs: each payment made ahead, paid on its day, and each session that nothing paid
     * covers, as still to come. A session paid for is not counted again; its payment already was.
     * Each is filed and named as the item's first session is.
     */
    public List<Occurrence> paidAheadCosts(LocalDate from, LocalDate to) {
        return paidAheadCosts(items.all(), from, to);
    }

    /** {@link #paidAheadCosts(LocalDate, LocalDate)} for {@code chosen} only. */
    public List<Occurrence> paidAheadCosts(List<Item> chosen, LocalDate from, LocalDate to) {
        List<Occurrence> costs = new ArrayList<>();
        // Bought as a pack, what is still to come is its planned packs, which are bills like any other.
        Set<Long> inPacks = chosen.stream()
                .filter(item -> item.versionOn(pay.today()).terms().pack().isPresent())
                .map(Item::id)
                .collect(Collectors.toSet());
        standings(chosen, pay.today()).values().forEach(standing -> {
            if (standing.sessions().isEmpty()) {
                return;
            }
            Occurrence first = standing.sessions().getFirst();
            standing.payments().stream()
                    .filter(payment -> !payment.date().isBefore(from) && !payment.date().isAfter(to))
                    .map(payment -> new Occurrence(
                            first.itemId(), first.name(), first.kind(), payment.date(), payment.date(), null, false, null,
                            payment.amountCents(), false, null, false, first.placement(), first.categoryId(),
                            payment.accountId(), null, true, payment.date(), null, null, false, null, null, null, null, null, null))
                    .forEach(costs::add);
            if (inPacks.contains(first.itemId())) {
                return;
            }
            LocalDate coversThrough = standing.result().coversThrough();
            standing.sessions().stream()
                    .filter(session -> !session.skipped())
                    .filter(session -> coversThrough == null || session.dueDate().isAfter(coversThrough))
                    .filter(session -> !session.dueDate().isBefore(from) && !session.dueDate().isAfter(to))
                    .forEach(costs::add);
        });
        return costs;
    }

    /**
     * The visits of a billed-later item that no invoice covers yet, up to {@code through}, oldest
     * first; skipped ones are left out (spec §4.6).
     */
    public List<Occurrence> unbilled(Item item, LocalDate through) {
        return uninvoiced(item, through).stream().filter(visit -> !visit.skipped()).toList();
    }

    /**
     * The visits of a billed-later item that no invoice covers yet, up to {@code through}, oldest
     * first, skipped ones included: the item's page lists those too, so a skipped visit can be put back.
     */
    public List<Occurrence> uninvoiced(Item item, LocalDate through) {
        LocalDate after = coveredThrough(item).orElse(null);
        LocalDate from = after != null ? after.plusDays(1) : item.versions().getFirst().effectiveFrom();
        if (from.isAfter(through)) {
            return List.of();
        }
        return expand(List.of(item), from, through).visits().stream()
                .filter(visit -> !visit.dueDate().isBefore(from) && !visit.dueDate().isAfter(through))
                .toList();
    }

    /** Whether {@code scheduledDate} is a visit of {@code item} that an invoice already covers. */
    public boolean onInvoice(Item item, LocalDate scheduledDate) {
        Optional<LocalDate> covered = coveredThrough(item);
        return covered.isPresent() && expand(List.of(item), scheduledDate, scheduledDate).visits().stream()
                .anyMatch(visit -> visit.scheduledDate().equals(scheduledDate) && !visit.dueDate().isAfter(covered.get()));
    }

    /**
     * Where a paid-ahead item stands (spec §4.6): everything paid ahead for it, against its sessions
     * from the first to the look-ahead's end, skipped ones using nothing.
     *
     * @param sessions every session up to the look-ahead's end, skipped ones included, oldest first
     */
    public record Standing(PaidAhead.Result result, List<Occurrence> sessions, List<PrepaymentsRepository.Prepayment> payments) {}

    /** {@link Standing} for one item, as of {@code today}. */
    public Standing standing(Item item, LocalDate today) {
        return standing(item, today, prepayments.forItem(item.id()), expandSchedules(List.of(item), item.versions().getFirst().effectiveFrom(),
                pay.horizonEnd()).sessions());
    }

    /**
     * {@link Standing} for every item among {@code chosen} that is, or was, paid ahead. Each is worked
     * out over its whole life, since its first session uses the first money paid ahead.
     */
    public Map<Long, Standing> standings(List<Item> chosen, LocalDate today) {
        Map<Long, List<PrepaymentsRepository.Prepayment>> paid = prepayments.byItem();
        Map<Long, Standing> standings = new HashMap<>();
        for (Item item : chosen) {
            if (item.versions().stream().anyMatch(version -> version.terms().paidAhead())) {
                List<Occurrence> sessions =
                        expandSchedules(List.of(item), item.versions().getFirst().effectiveFrom(), pay.horizonEnd()).sessions();
                standings.put(item.id(), standing(item, today, paid.getOrDefault(item.id(), List.of()), sessions));
            }
        }
        return standings;
    }

    private static Standing standing(
            Item item, LocalDate today, List<PrepaymentsRepository.Prepayment> payments, List<Occurrence> sessions) {
        Money paid = Money.ofCents(payments.stream().mapToLong(PrepaymentsRepository.Prepayment::amountCents).sum());
        List<Occurrence> used = sessions.stream().filter(session -> !session.skipped()).toList();
        // A pack is counted in whole sessions against its price (today's), never in rounded cents.
        Optional<PaidAhead.Pack> pack = item.versionOn(today).terms().pack();
        PaidAhead.Result result = pack.isPresent()
                ? PaidAhead.drawDown(paid, used.stream().map(Occurrence::dueDate).toList(), today, pack.get())
                : PaidAhead.drawDown(paid, used.stream()
                        .map(session -> new PaidAhead.Session(session.dueDate(), Money.ofCents(session.amountCents())))
                        .toList(), today);
        return new Standing(result, sessions, payments);
    }

    /** The last visit day the item's invoices cover, if it has any. */
    private Optional<LocalDate> coveredThrough(Item item) {
        return invoices.forItem(item.id()).stream().map(Invoice::coversThrough).max(Comparator.naturalOrder());
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

        // Paid, it belongs to the day it was paid, early or late (Rachel, 2026-10-22: "it says paid march 15 though ...
        // it gets paid when it's paid not when it's due"; "no you can pay late"). Ticking one paid dates it inside the
        // pay period it is ticked from unless another day is chosen, so ticking off old bills keeps them where they
        // were. Moved to a period by hand, it stays there.
        boolean paidOn = edit.paid() && edit.paidDate() != null && edit.periodOverride() == null;
        Optional<PayPeriod> period;
        if (edit.periodOverride() != null) {
            period = assigner.startingOn(edit.periodOverride());
        } else if (paidOn) {
            period = assigner.containing(edit.paidDate());
        } else if (planned.ownPeriod() != null) {
            // "Every pay period" belongs to the period it was made for, wherever in it it is dated.
            period = Optional.of(planned.ownPeriod());
        } else {
            period = assigner.assign(due, terms.placement());
        }

        // The period's figures have to add up to what it lists, so an occurrence always counts
        // on a day inside the period that pays for it: "due Oct 16, pay by Oct 14".
        LocalDate counted = paidOn ? edit.paidDate() : due;
        LocalDate payBy = period.map(found -> clamp(counted, found)).orElse(counted);
        LocalDate periodStart = period.map(PayPeriod::start).orElse(null);
        boolean early = periodStart != null
                && assigner.containing(due).filter(dueIn -> dueIn.start().isAfter(periodStart)).isPresent();

        boolean typedOver = edit.amountCents() != null;
        long amount = typedOver ? edit.amountCents() : terms.billedByUse() ? usageBill(terms, planned.scheduled()) : amounts(terms, payRule).perOccurrenceCents();
        // Paid from another account just this once (Rachel, 2026-10-20): a bucket belongs to the item's own account,
        // so this one comes out of money not in a bucket there.
        boolean otherAccount = edit.payFromAccountId() != null && edit.payFromAccountId() != terms.payFromAccountId();
        long payFrom = otherAccount ? edit.payFromAccountId() : terms.payFromAccountId();
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
                payFrom,
                terms.payToAccountId(),
                edit.paid(),
                edit.paidDate(),
                edit.confirmationCode(),
                edit.note(),
                edit.skipped(),
                otherAccount ? null : terms.bucketId(),
                otherAccount ? null : terms.saveUpBucketId(),
                otherAccount ? null : terms.fromBucketId(),
                edit.checkNumber(),
                invoiceId,
                coversThrough);
    }

    private static OccurrencePlanner.Version<Terms> planned(Version version) {
        Terms terms = version.terms();
        // Billed by use: its months are when it can be used, and a bill for the last of them still comes after.
        ScheduleRule rule = terms.billedByUse() ? terms.schedule().inMonths(null) : terms.schedule();
        return new OccurrencePlanner.Version<>(version.effectiveFrom(), rule, terms.daysAfterPayday(), terms);
    }

    /** The estimated bill on {@code scheduled}, billed by use: every use since the bill before it (spec §4.6). */
    private static long usageBill(Terms terms, LocalDate scheduled) {
        ScheduleRule rule = terms.schedule();
        return UsageBill.between(Money.ofCents(terms.amountCents()), terms.usage(), rule, UsageBill.coversAfter(rule, scheduled), scheduled).cents();
    }

    private static LocalDate clamp(LocalDate date, PayPeriod period) {
        if (date.isBefore(period.start())) {
            return period.start();
        }
        return date.isAfter(period.end()) ? period.end() : date;
    }
}
