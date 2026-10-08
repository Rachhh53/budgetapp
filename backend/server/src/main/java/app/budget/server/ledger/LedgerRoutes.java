package app.budget.server.ledger;

import app.budget.server.accounts.Account;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.income.PayCalendar;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.time.LocalDate;

/**
 * Pay periods with their balances: {@code /api/pay-periods} lists them with each one's
 * projected ending, and {@code /api/pay-periods/{start}} is everything in one of them.
 * {@code /api/accounts/{id}/projection} follows one account through them.
 */
public final class LedgerRoutes {

    private final Ledger ledger;
    private final PayCalendar calendar;
    private final AccountsRepository accounts;

    public LedgerRoutes(Ledger ledger, PayCalendar calendar, AccountsRepository accounts) {
        this.ledger = ledger;
        this.calendar = calendar;
        this.accounts = accounts;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/pay-periods", this::list);
        routes.get("/api/pay-periods/{start}", this::one);
        routes.get("/api/accounts/{id}/projection", this::accountProjection);
    }

    /** One account at the end of each pay period, from the current one through the horizon: its chart. */
    private void accountProjection(Context ctx) {
        Account account = accounts.find(Inputs.id(ctx, "id")).orElseThrow(ApiException::notFound);
        ApiResponse.ok(ctx, ledger.accountSeries(account, calendar.today(), calendar.horizonEnd()));
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
