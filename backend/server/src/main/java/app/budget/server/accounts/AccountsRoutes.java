package app.budget.server.accounts;

import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.time.Clock;
import java.time.LocalDate;

/**
 * Accounts and the balances the user enters for them:
 * {@code /api/accounts}, {@code /api/accounts/{id}} and {@code /api/accounts/{id}/balances}.
 */
public final class AccountsRoutes {

    private static final int NAME_MAX = 80;
    private static final int NOTE_MAX = 200;

    private final AccountsRepository accounts;
    private final Clock clock;

    public AccountsRoutes(AccountsRepository accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/accounts", ctx -> ApiResponse.ok(ctx, accounts.all()));
        routes.post("/api/accounts", this::create);
        routes.get("/api/accounts/{id}", ctx -> ApiResponse.ok(ctx, detail(Inputs.id(ctx, "id"))));
        routes.put("/api/accounts/{id}", this::update);
        routes.delete("/api/accounts/{id}", this::delete);
        routes.post("/api/accounts/{id}/balances", this::saveBalance);
        routes.delete("/api/accounts/{id}/balances/{entryId}", this::deleteBalance);
    }

    /** Request body for creating or changing an account. */
    public record AccountInput(
            String name, String institution, String type, String subtypeLabel, String color, Boolean archived) {}

    /** Request body for entering a balance. */
    public record BalanceInput(LocalDate date, Long amountCents, String note) {}

    public record Deleted(boolean deleted) {}

    private void create(Context ctx) {
        long id = accounts.create(fields(Inputs.body(ctx, AccountInput.class)));
        ApiResponse.send(ctx, 201, detail(id));
    }

    private void update(Context ctx) {
        long id = Inputs.id(ctx, "id");
        if (!accounts.update(id, fields(Inputs.body(ctx, AccountInput.class)))) {
            throw ApiException.notFound();
        }
        ApiResponse.ok(ctx, detail(id));
    }

    private void delete(Context ctx) {
        long id = Inputs.id(ctx, "id");
        if (accounts.isUsedByIncome(id)) {
            throw ApiException.conflict(
                    "This account is still used by an income source. Point that somewhere else first, or archive the account instead.");
        }
        if (accounts.isUsedByItemsOrEntries(id)) {
            throw ApiException.conflict(
                    "This account is still used by a recurring item or a one-off entry. Point those somewhere else first, or archive the account instead.");
        }
        if (!accounts.delete(id)) {
            throw ApiException.notFound();
        }
        ApiResponse.ok(ctx, new Deleted(true));
    }

    private void saveBalance(Context ctx) {
        long id = Inputs.id(ctx, "id");
        BalanceInput input = Inputs.body(ctx, BalanceInput.class);
        if (input.date() == null) {
            throw ApiException.badRequest("Please choose the date this balance is from.");
        }
        // A balance entry is the real number on a real day, so it cannot be from the future.
        if (input.date().isAfter(LocalDate.now(clock))) {
            throw ApiException.badRequest("A balance needs a date of today or earlier.");
        }
        long amount = Inputs.signedAmount(input.amountCents(), "The balance");
        String note = Inputs.optionalText(input.note(), "the note", NOTE_MAX);
        if (!accounts.exists(id)) {
            throw ApiException.notFound();
        }
        accounts.saveBalance(id, input.date(), amount, note);
        ApiResponse.send(ctx, 201, detail(id));
    }

    private void deleteBalance(Context ctx) {
        long id = Inputs.id(ctx, "id");
        if (!accounts.deleteBalance(id, Inputs.id(ctx, "entryId"))) {
            throw ApiException.notFound();
        }
        ApiResponse.ok(ctx, detail(id));
    }

    private Account.Detail detail(long id) {
        Account account = accounts.find(id).orElseThrow(ApiException::notFound);
        return new Account.Detail(account, accounts.balances(id));
    }

    private static AccountsRepository.Fields fields(AccountInput input) {
        return new AccountsRepository.Fields(
                Inputs.text(input.name(), "a name for the account", NAME_MAX),
                Inputs.optionalText(input.institution(), "the bank or institution", NAME_MAX),
                Inputs.choice(AccountType.class, input.type(), "the kind of account"),
                Inputs.optionalText(input.subtypeLabel(), "the account label", NAME_MAX),
                Inputs.color(input.color()),
                Boolean.TRUE.equals(input.archived()));
    }
}
