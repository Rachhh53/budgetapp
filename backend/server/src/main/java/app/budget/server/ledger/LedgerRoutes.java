package app.budget.server.ledger;

import app.budget.server.accounts.Account;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.accounts.InterestRepository;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.cards.CardsRepository;
import app.budget.server.income.PayCalendar;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Pay periods with their balances: {@code /api/pay-periods} lists them with each one's
 * projected ending, and {@code /api/pay-periods/{start}} is everything in one of them.
 * {@code /api/accounts/{id}/projection} follows one account through them, and
 * {@code /api/account-balances} is what every account holds today, and
 * {@code /api/accounts/{id}/transactions} lists what moved one of them, and
 * {@code /api/accounts/{id}/performance} says how it has changed and how much of that was deposits.
 * {@code /api/accounts/{id}/transactions/interest/{date}} types over one of its interest lines, once it has been added,
 * and {@code /api/accounts/{id}/transactions/rewards/{date}} a card's rewards at one statement close.
 */
public final class LedgerRoutes {

    private final Ledger ledger;
    private final PayCalendar calendar;
    private final AccountsRepository accounts;
    private final InterestRepository interest;
    private final CardsRepository cards;

    public LedgerRoutes(
            Ledger ledger, PayCalendar calendar, AccountsRepository accounts, InterestRepository interest, CardsRepository cards) {
        this.ledger = ledger;
        this.calendar = calendar;
        this.accounts = accounts;
        this.interest = interest;
        this.cards = cards;
    }

    /** Request body for typing over one interest line: the amount of interest, never negative. */
    public record InterestInput(Long amountCents) {}

    public record Done(boolean done) {}

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/pay-periods", this::list);
        routes.get("/api/pay-periods/{start}", this::one);
        routes.get("/api/accounts/{id}/projection", this::accountProjection);
        routes.get("/api/account-balances", ctx -> ApiResponse.ok(ctx, ledger.balancesOn(calendar.today())));
        routes.get("/api/accounts/{id}/transactions", this::accountTransactions);
        routes.get("/api/accounts/{id}/performance", this::accountPerformance);
        routes.put("/api/accounts/{id}/transactions/interest/{date}", this::typeOverInterest);
        routes.delete("/api/accounts/{id}/transactions/interest/{date}", this::useEstimatedInterest);
        routes.put("/api/accounts/{id}/transactions/rewards/{date}", this::typeOverRewards);
        routes.delete("/api/accounts/{id}/transactions/rewards/{date}", this::useEstimatedRewards);
    }

    /**
     * Types over a card's rewards at one statement close (spec §5.6: "you can type over a month that
     * came out different"), in the request's {@code amountCents}, never negative: what came off. 404
     * when the card has no rewards line that day; 409 before the close has come, as for interest.
     */
    private void typeOverRewards(Context ctx) {
        Account account = accounts.find(Inputs.id(ctx, "id")).orElseThrow(ApiException::notFound);
        LocalDate date = Inputs.date(ctx.pathParam("date"));
        long amount = Inputs.amount(Inputs.body(ctx, InterestInput.class).amountCents(), "The rewards");
        if (!Inputs.inSupportedYears(date)) {
            throw ApiException.notFound();
        }
        Ledger.Transaction line = ledger.rewardsLine(account, date).orElseThrow(ApiException::notFound);
        if (line.date().isAfter(calendar.today())) {
            throw ApiException.conflict(
                    "This statement hasn't closed yet, so Budget keeps working its rewards out. You can type the real amount once it has.");
        }
        // Kept as what came off, a positive amount; what was estimated is kept from the first time only.
        long estimated = line.estimatedCents() != null ? -line.estimatedCents() : -line.amountCents();
        cards.saveTypedRewards(account.id(), line.date(), amount, estimated);
        ApiResponse.ok(ctx, new Done(true));
    }

    /** Puts Budget's estimate back on one statement's rewards that were typed over. 404 when none were. */
    private void useEstimatedRewards(Context ctx) {
        Account account = accounts.find(Inputs.id(ctx, "id")).orElseThrow(ApiException::notFound);
        LocalDate date = Inputs.date(ctx.pathParam("date"));
        if (!cards.deleteTypedRewards(account.id(), date)) {
            throw ApiException.notFound();
        }
        ApiResponse.ok(ctx, new Done(true));
    }

    /**
     * Types over one interest line (Rachel, 2026-10-19: "edit an interest amount in an account if
     * it's wrong"): a month's interest on savings, a card or a loan, or a month's growth on an
     * investment, given by any day within it. The amount simply becomes the amount; nothing else
     * changes, and later months are worked out from the balance it leaves. 404 when there is no
     * interest line on that day.
     */
    private void typeOverInterest(Context ctx) {
        Account account = accounts.find(Inputs.id(ctx, "id")).orElseThrow(ApiException::notFound);
        LocalDate date = Inputs.date(ctx.pathParam("date"));
        long amount = Inputs.amount(Inputs.body(ctx, InterestInput.class).amountCents(), "The interest");
        if (!Inputs.inSupportedYears(date)) {
            throw ApiException.notFound();
        }
        Ledger.Transaction line = ledger.interestLine(account, date).orElseThrow(ApiException::notFound);
        // Interest still to come has no real figure yet, so it stays worked out and follows every change
        // before it (Rachel, 2026-10-19: "it's upcoming it shouldn't be set in stone"). A month's growth
        // is only known once the month is over.
        LocalDate settles = isGrowth(line) ? YearMonth.from(line.date()).atEndOfMonth() : line.date();
        if (settles.isAfter(calendar.today())) {
            throw ApiException.conflict(isGrowth(line)
                    ? "This month isn't over yet, so Budget keeps working its growth out. You can type the real amount once the month has ended."
                    : "This interest hasn't been added yet, so Budget keeps working it out. You can type the real amount once the day has come.");
        }
        // What was estimated is kept from the first time only (the repository leaves it alone after that).
        long estimated = line.estimatedCents() != null ? line.estimatedCents() : Math.abs(line.amountCents());
        interest.saveTyped(account.id(), Ledger.typedOverKey(account, line.date()), amount, estimated);
        ApiResponse.ok(ctx, new Done(true));
    }

    private static boolean isGrowth(Ledger.Transaction line) {
        return "growth".equals(line.kind());
    }

    /**
     * Puts Budget's estimate back on one interest line that was typed over. 404 when nothing was.
     * Allowed for interest still to come, so an amount typed there before that was refused can be cleared.
     */
    private void useEstimatedInterest(Context ctx) {
        Account account = accounts.find(Inputs.id(ctx, "id")).orElseThrow(ApiException::notFound);
        LocalDate date = Inputs.date(ctx.pathParam("date"));
        if (!interest.deleteTyped(account.id(), Ledger.typedOverKey(account, date))) {
            throw ApiException.notFound();
        }
        ApiResponse.ok(ctx, new Done(true));
    }

    /** This year so far, and each balance entered set against the one before it. */
    private void accountPerformance(Context ctx) {
        Account account = accounts.find(Inputs.id(ctx, "id")).orElseThrow(ApiException::notFound);
        ApiResponse.ok(ctx, ledger.performance(account));
    }

    /** What moved one account within {@code from}–{@code to}; by default this year so far and the month ahead. */
    private void accountTransactions(Context ctx) {
        Account account = accounts.find(Inputs.id(ctx, "id")).orElseThrow(ApiException::notFound);
        LocalDate from = Inputs.dateParam(ctx, "from", calendar.today().withDayOfYear(1));
        LocalDate to = Inputs.dateParam(ctx, "to", calendar.today().plusMonths(1));
        Inputs.requireSensibleRange(from, to);
        ApiResponse.ok(ctx, ledger.transactions(account, from, to));
    }

    /** One account at the end of each pay period, from the current one through the horizon: its chart. */
    /**
     * One account at the end of each pay period overlapping {@code from}–{@code to}; by default the
     * current one through the horizon. Earlier dates give what the account held then, as worked out
     * from the balances entered and everything that moved; later ones run on past the horizon.
     */
    private void accountProjection(Context ctx) {
        Account account = accounts.find(Inputs.id(ctx, "id")).orElseThrow(ApiException::notFound);
        LocalDate from = Inputs.dateParam(ctx, "from", calendar.today());
        LocalDate to = Inputs.dateParam(ctx, "to", calendar.horizonEnd());
        Inputs.requireSensibleRange(from, to);
        ApiResponse.ok(ctx, ledger.accountSeries(account, from, to));
    }

    /** Pay periods overlapping {@code from}–{@code to}; by default the current one through the horizon. */
    private void list(Context ctx) {
        LocalDate from = Inputs.dateParam(ctx, "from", calendar.today());
        LocalDate to = Inputs.dateParam(ctx, "to", calendar.horizonEnd());
        Inputs.requireSensibleRange(from, to);
        ApiResponse.ok(ctx, ledger.periods(from, to));
    }

    /** The period that opens on {@code start}. Any other date is not a period, so it is "not found". */
    private void one(Context ctx) {
        LocalDate start = Inputs.date(ctx.pathParam("start"));
        if (!Inputs.inSupportedYears(start)) {
            throw ApiException.notFound();
        }
        ApiResponse.ok(ctx, ledger.period(start).orElseThrow(ApiException::notFound));
    }
}
