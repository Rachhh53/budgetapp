package app.budget.server.search;

import app.budget.engine.items.PeriodAssigner;
import app.budget.engine.schedule.PayPeriod;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.entries.EntriesRepository;
import app.budget.server.entries.EntriesRepository.Entry;
import app.budget.server.entries.EntryKind;
import app.budget.server.income.PayCalendar;
import app.budget.server.items.ItemCalendar;
import app.budget.server.items.ItemCalendar.Occurrence;
import app.budget.server.items.ItemKind;
import app.budget.server.items.ItemsRepository;
import app.budget.server.items.ItemsRepository.Item;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finding something from the pay period screen (spec §8): {@code POST /api/search} looks up one-off
 * entries by description or payee and recurring items by name, and says for each when it last
 * happened and when it is next planned, with the pay period that lists it. It is a POST so what is
 * typed never sits in an address, and it records nothing (see {@code ChangeRoutes}).
 */
public final class SearchRoutes {

    public static final String SEARCH = "/api/search";

    static final int MIN_LENGTH = 2;
    static final int MAX_LENGTH = 100;
    /** More than fit on screen; one more is asked for to know whether there are others. */
    static final int MOST_SHOWN = 30;
    /** How far back to look for the last time a recurring item happened: a yearly bill and a little more. */
    private static final int LAST_WITHIN_MONTHS = 13;

    private final EntriesRepository entries;
    private final ItemsRepository items;
    private final ItemCalendar calendar;
    private final PayCalendar pay;

    public SearchRoutes(EntriesRepository entries, ItemsRepository items, ItemCalendar calendar, PayCalendar pay) {
        this.entries = entries;
        this.items = items;
        this.calendar = calendar;
        this.pay = pay;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.post(SEARCH, this::search);
    }

    /** @param text what was typed */
    public record SearchInput(String text) {}

    /**
     * One time a recurring item happens.
     *
     * @param periodStart the pay period that lists it; {@code null} when no pay period holds it
     * @param paid        the last time only: ticked as paid, or on autopay (taken as paid on its day)
     */
    public record When(LocalDate date, long amountCents, boolean estimate, boolean paid, LocalDate periodStart) {}

    /**
     * A recurring item that matched.
     *
     * @param last the latest time it happened, up to today; {@code null} when it has not yet
     * @param next the next time still to pay; {@code null} when nothing more is planned
     */
    public record ItemMatch(long id, String name, ItemKind kind, long payFromAccountId, When last, When next) {}

    /**
     * A one-off entry that matched.
     *
     * @param upcoming    dated after today: planned, not yet happened
     * @param periodStart the pay period that lists it; {@code null} when no pay period holds its date
     */
    public record EntryMatch(
            long id,
            EntryKind kind,
            String description,
            String payee,
            LocalDate date,
            long amountCents,
            boolean estimate,
            long accountId,
            boolean upcoming,
            LocalDate periodStart) {}

    /**
     * @param more true when more one-offs matched than are listed (the newest are)
     */
    public record Results(List<ItemMatch> recurring, List<EntryMatch> oneOffs, boolean more) {}

    private void search(Context ctx) {
        SearchInput input = Inputs.body(ctx, SearchInput.class);
        String text = input.text() == null ? "" : input.text().strip();
        if (text.length() < MIN_LENGTH) {
            throw ApiException.badRequest("Please type at least " + MIN_LENGTH + " letters to search.");
        }
        if (text.length() > MAX_LENGTH) {
            throw ApiException.badRequest("Please keep the search to " + MAX_LENGTH + " characters or fewer.");
        }
        ApiResponse.ok(ctx, search(text));
    }

    Results search(String text) {
        LocalDate today = pay.today();
        List<Entry> found = entries.matching(text, MOST_SHOWN + 1);
        boolean more = found.size() > MOST_SHOWN;
        List<Entry> shown = more ? found.subList(0, MOST_SHOWN) : found;

        PeriodAssigner periods = periodsFor(shown, today);
        List<EntryMatch> oneOffs = shown.stream()
                .map(entry -> new EntryMatch(
                        entry.id(), entry.kind(), entry.description(), entry.payee(), entry.date(), entry.amountCents(),
                        entry.estimate(), entry.accountId(), entry.date().isAfter(today),
                        periods.containing(entry.date()).map(PayPeriod::start).orElse(null)))
                .toList();
        return new Results(recurring(text, today), oneOffs, more);
    }

    /** Recurring items whose name holds the text, that are still in use, by name. */
    private List<ItemMatch> recurring(String text, LocalDate today) {
        String lower = text.toLowerCase(Locale.ROOT);
        List<Item> matched = items.all().stream()
                .filter(item -> !item.archived() && item.name().toLowerCase(Locale.ROOT).contains(lower))
                .sorted(Comparator.comparing(item -> item.name().toLowerCase(Locale.ROOT)))
                .limit(MOST_SHOWN)
                .toList();
        if (matched.isEmpty()) {
            return List.of();
        }
        Map<Long, Occurrence> next = calendar.nextByItem(matched);
        Map<Long, Occurrence> last = new HashMap<>();
        // In date order, so the last one seen for an item up to today is its latest.
        for (Occurrence occurrence : calendar.expand(matched, today.minusMonths(LAST_WITHIN_MONTHS), today).occurrences()) {
            if (!occurrence.skipped() && !occurrence.dueDate().isAfter(today)) {
                last.put(occurrence.itemId(), occurrence);
            }
        }
        return matched.stream()
                .map(item -> new ItemMatch(
                        item.id(), item.name(), item.kind(), item.versionOn(today).terms().payFromAccountId(),
                        when(last.get(item.id())), when(next.get(item.id()))))
                .toList();
    }

    private static When when(Occurrence occurrence) {
        if (occurrence == null) {
            return null;
        }
        return new When(
                occurrence.dueDate(), occurrence.amountCents(), occurrence.estimate(),
                occurrence.paid() || occurrence.autopay(), occurrence.periodStart());
    }

    /** The pay periods across the entries' dates, to say which one lists each. */
    private PeriodAssigner periodsFor(List<Entry> shown, LocalDate today) {
        LocalDate from = shown.stream().map(Entry::date).min(Comparator.naturalOrder()).orElse(today);
        LocalDate to = shown.stream().map(Entry::date).max(Comparator.naturalOrder()).orElse(today);
        return new PeriodAssigner(pay.payPeriods(from, to));
    }
}
