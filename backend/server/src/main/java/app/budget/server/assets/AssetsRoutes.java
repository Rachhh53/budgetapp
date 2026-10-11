package app.budget.server.assets;

import app.budget.server.accounts.Account;
import app.budget.server.accounts.AccountType;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.assets.AssetsRepository.Kind;
import app.budget.server.assets.NetWorthCalculator.AssetDetail;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.time.Clock;
import java.time.LocalDate;

/**
 * Homes, vehicles and other assets, the values entered for them, and net worth:
 * {@code /api/assets}, {@code /api/assets/{id}}, {@code /api/assets/{id}/values} and {@code /api/net-worth}.
 */
public final class AssetsRoutes {

    private static final int NAME_MAX = 80;

    /** 1,000% a year: beyond any real rate, and a typo (3.0 entered as 300) still fits so it can be shown and fixed. */
    private static final int MAX_RATE_BP = 100_000;

    /** The steepest yearly loss taken: 99%. At 100% there would be nothing left after a month. */
    private static final int MAX_LOSS_BP = 9_900;

    private final AssetsRepository assets;
    private final AccountsRepository accounts;
    private final NetWorthCalculator worth;
    private final Clock clock;

    public AssetsRoutes(AssetsRepository assets, AccountsRepository accounts, NetWorthCalculator worth, Clock clock) {
        this.assets = assets;
        this.accounts = accounts;
        this.worth = worth;
        this.clock = clock;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/assets", ctx -> ApiResponse.ok(ctx, worth.all(today())));
        routes.post("/api/assets", this::create);
        routes.get("/api/assets/{id}", ctx -> ApiResponse.ok(ctx, detail(Inputs.id(ctx, "id"))));
        routes.put("/api/assets/{id}", this::update);
        routes.delete("/api/assets/{id}", this::delete);
        routes.post("/api/assets/{id}/values", this::saveValue);
        routes.delete("/api/assets/{id}/values/{valueId}", this::deleteValue);
        routes.get("/api/net-worth", ctx -> ApiResponse.ok(ctx, worth.summary(today())));
    }

    /**
     * Request body for adding or changing an asset. {@code valueCents} and {@code valueDate} are
     * only read when adding: they record its first value (today when no date is given).
     */
    public record AssetInput(
            String name,
            String kind,
            String color,
            Integer appreciationBp,
            Long linkedLoanAccountId,
            Boolean archived,
            Long valueCents,
            LocalDate valueDate) {}

    /** Request body for entering a value. */
    public record ValueInput(LocalDate date, Long valueCents) {}

    public record Deleted(boolean deleted) {}

    private void create(Context ctx) {
        AssetInput input = Inputs.body(ctx, AssetInput.class);
        AssetsRepository.Fields fields = fields(input);
        Long value = input.valueCents() == null ? null : (Long) Inputs.amount(input.valueCents(), "The value");
        LocalDate valueDate = value == null ? null : valueDate(input.valueDate() == null ? today() : input.valueDate());
        long id = assets.create(fields);
        if (value != null) {
            assets.saveValue(id, valueDate, value);
        }
        ApiResponse.send(ctx, 201, detail(id));
    }

    private void update(Context ctx) {
        long id = Inputs.id(ctx, "id");
        AssetsRepository.Fields fields = fields(Inputs.body(ctx, AssetInput.class));
        if (!assets.update(id, fields)) {
            throw ApiException.notFound();
        }
        ApiResponse.ok(ctx, detail(id));
    }

    private void delete(Context ctx) {
        if (!assets.delete(Inputs.id(ctx, "id"))) {
            throw ApiException.notFound();
        }
        ApiResponse.ok(ctx, new Deleted(true));
    }

    private void saveValue(Context ctx) {
        long id = Inputs.id(ctx, "id");
        ValueInput input = Inputs.body(ctx, ValueInput.class);
        if (input.date() == null) {
            throw ApiException.badRequest("Please choose the date this value is from.");
        }
        LocalDate date = valueDate(input.date());
        long value = Inputs.amount(input.valueCents(), "The value");
        if (assets.find(id).isEmpty()) {
            throw ApiException.notFound();
        }
        assets.saveValue(id, date, value);
        ApiResponse.send(ctx, 201, detail(id));
    }

    private void deleteValue(Context ctx) {
        long id = Inputs.id(ctx, "id");
        if (!assets.deleteValue(id, Inputs.id(ctx, "valueId"))) {
            throw ApiException.notFound();
        }
        ApiResponse.ok(ctx, detail(id));
    }

    private AssetDetail detail(long id) {
        return worth.detail(id, today()).orElseThrow(ApiException::notFound);
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    /** A value is what something was really worth on a real day, so it cannot be from the future. */
    private LocalDate valueDate(LocalDate date) {
        if (date.isAfter(today()) || !Inputs.inSupportedYears(date)) {
            throw ApiException.badRequest("A value needs a date of today or earlier.");
        }
        return date;
    }

    private AssetsRepository.Fields fields(AssetInput input) {
        String name = Inputs.text(input.name(), "a name for this", NAME_MAX);
        Kind kind = Inputs.choice(Kind.class, input.kind(), "what kind of thing this is");
        String color = Inputs.color(input.color());
        Integer rate = input.appreciationBp();
        // Below zero is a yearly loss, for something like a car (decided 2026-10-11).
        if (rate != null && (rate < -MAX_LOSS_BP || rate > MAX_RATE_BP)) {
            throw ApiException.badRequest(
                    "Please enter a yearly rate of up to 1,000% for something that gains value, or up to 99% for something that loses it, or leave it empty.");
        }
        Long loanId = input.linkedLoanAccountId();
        if (loanId != null) {
            Account loan = accounts.find(loanId).orElse(null);
            if (loan == null || !Inputs.code(AccountType.LOAN).equals(loan.type())) {
                throw ApiException.badRequest("Please choose the loan from your loan accounts.");
            }
        }
        return new AssetsRepository.Fields(name, kind, color, rate, loanId, Boolean.TRUE.equals(input.archived()));
    }
}
