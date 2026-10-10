package app.budget.server.accounts;

import app.budget.engine.interest.SavingsInterest;
import app.budget.server.accounts.InterestRepository.Destination;
import app.budget.server.accounts.InterestRepository.RateKind;
import app.budget.server.accounts.InterestRepository.Settings;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.buckets.BucketsRepository;
import app.budget.server.buckets.BucketsRepository.Bucket;
import app.budget.server.ledger.Ledger;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Accounts and what the user enters for them: {@code /api/accounts}, {@code /api/accounts/{id}},
 * and under it {@code /balances}, {@code /interest} and {@code /rates}; and
 * {@code PUT /api/account-balances}, which enters several accounts' balances in one go.
 */
public final class AccountsRoutes {

    private static final int NAME_MAX = 80;
    private static final int NOTE_MAX = 200;

    /** 1,000%: beyond any real rate, and a typo (4.10 entered as 410) still fits so it can be shown and fixed. */
    private static final int MAX_RATE_BP = 100_000;

    /** More accounts than anyone keeps; only there to stop a runaway request. */
    private static final int MAX_BALANCES = 200;

    private final AccountsRepository accounts;
    private final InterestRepository interest;
    private final BucketsRepository buckets;
    private final Ledger ledger;
    private final Clock clock;

    public AccountsRoutes(
            AccountsRepository accounts, InterestRepository interest, BucketsRepository buckets, Ledger ledger, Clock clock) {
        this.accounts = accounts;
        this.interest = interest;
        this.buckets = buckets;
        this.ledger = ledger;
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
        routes.put("/api/accounts/{id}/interest", this::saveInterest);
        routes.post("/api/accounts/{id}/rates", this::saveRate);
        routes.delete("/api/accounts/{id}/rates/{rateId}", this::deleteRate);
        routes.put("/api/account-balances", this::saveBalances);
    }

    /**
     * Request body for creating or changing an account. {@code balanceCents} and
     * {@code balanceDate} are only read when adding: they record its first balance (today when no
     * date is given), so an account and what it holds or owes go in as one change.
     */
    public record AccountInput(
            String name,
            String institution,
            String type,
            String subtypeLabel,
            String color,
            Boolean archived,
            Long lowBalanceThresholdCents,
            Long balanceCents,
            LocalDate balanceDate) {}

    /** Request body for entering a balance. */
    public record BalanceInput(LocalDate date, Long amountCents, String note) {}

    /** Request body for when interest is credited and where it goes. */
    public record InterestInput(Integer creditDay, String destination, Long destinationBucketId) {}

    /** Request body for a rate, in basis points (4.10% is 410), from a date on. */
    public record RateInput(Integer rateBp, LocalDate effectiveDate) {}

    /** Request body for entering several accounts' balances for one day. */
    public record BalancesInput(LocalDate date, List<AccountBalanceInput> balances) {}

    public record AccountBalanceInput(Long accountId, Long amountCents) {}

    public record Saved(int saved) {}

    public record Deleted(boolean deleted) {}

    private void create(Context ctx) {
        AccountInput input = Inputs.body(ctx, AccountInput.class);
        AccountsRepository.Fields fields = fields(input);
        // Checked before anything is saved, so a refused balance never leaves an account behind.
        LocalDate today = LocalDate.now(clock);
        LocalDate balanceDate = input.balanceDate() == null ? today : input.balanceDate();
        Long balance = input.balanceCents() == null ? null : Inputs.signedAmount(input.balanceCents(), "The balance");
        if (balance != null && (balanceDate.isAfter(today) || !Inputs.inSupportedYears(balanceDate))) {
            throw ApiException.badRequest("A balance needs a date of today or earlier.");
        }
        long id = accounts.create(fields);
        if (balance != null) {
            accounts.saveBalance(id, balanceDate, balance, null);
        }
        ApiResponse.send(ctx, 201, detail(id));
    }

    private void update(Context ctx) {
        long id = Inputs.id(ctx, "id");
        AccountsRepository.Fields fields = fields(Inputs.body(ctx, AccountInput.class));
        if (!accounts.exists(id)) {
            throw ApiException.notFound();
        }
        if (!fields.type().holdsCash() && buckets.all().stream().anyMatch(bucket -> bucket.accountId() == id)) {
            throw ApiException.conflict(
                    "This account has buckets, which only checking and savings accounts can hold. Remove the buckets first.");
        }
        if (fields.type() != AccountType.INVESTMENT && accounts.receivesEmployerMatch(id)) {
            throw ApiException.conflict(
                    "An employer match is paid into this account, which only an investment account can take. Change the match on the Income screen first.");
        }
        accounts.update(id, fields);
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

    /**
     * "Update balances": the real numbers for several accounts on one day, saved together so one
     * Undo takes them all back. Nothing is saved unless every line is in order.
     */
    private void saveBalances(Context ctx) {
        BalancesInput input = Inputs.body(ctx, BalancesInput.class);
        if (input.date() == null) {
            throw ApiException.badRequest("Please choose the date these balances are from.");
        }
        if (input.date().isAfter(LocalDate.now(clock)) || !Inputs.inSupportedYears(input.date())) {
            throw ApiException.badRequest("A balance needs a date of today or earlier.");
        }
        if (input.balances() == null || input.balances().isEmpty() || input.balances().contains(null)) {
            throw ApiException.badRequest("Please enter a balance for at least one account.");
        }
        if (input.balances().size() > MAX_BALANCES) {
            throw ApiException.badRequest("That is more balances than can be saved at once. Please enter them in smaller groups.");
        }
        List<AccountsRepository.NewBalance> balances = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (AccountBalanceInput balance : input.balances()) {
            if (balance.accountId() == null || !accounts.exists(balance.accountId())) {
                throw ApiException.badRequest("One of those accounts could not be found. Please reload and try again.");
            }
            if (!seen.add(balance.accountId())) {
                throw ApiException.badRequest("Each account can have one balance for a day. Please enter each account once.");
            }
            balances.add(new AccountsRepository.NewBalance(balance.accountId(), Inputs.signedAmount(balance.amountCents(), "A balance")));
        }
        accounts.saveBalances(input.date(), balances);
        ApiResponse.ok(ctx, new Saved(balances.size()));
    }

    private void deleteBalance(Context ctx) {
        long id = Inputs.id(ctx, "id");
        long entryId = Inputs.id(ctx, "entryId");
        Account account = accounts.find(id).orElseThrow(ApiException::notFound);
        // Removing a balance must not leave the buckets holding more than the account does without it.
        LocalDate today = LocalDate.now(clock);
        boolean coveredNow = ledger.availableOn(account, today).orElse(0L) >= 0;
        if (coveredNow && ledger.availableOn(account, today, entryId).orElse(0L) < 0) {
            throw ApiException.conflict(
                    "Without this balance, this account's buckets would hold more than the account does. Please lower a bucket first.");
        }
        if (!accounts.deleteBalance(id, entryId)) {
            throw ApiException.notFound();
        }
        ApiResponse.ok(ctx, detail(id));
    }

    private void saveInterest(Context ctx) {
        long id = Inputs.id(ctx, "id");
        InterestInput input = Inputs.body(ctx, InterestInput.class);
        Account account = accounts.find(id).orElseThrow(ApiException::notFound);
        if (!AccountType.holdsCash(account)) {
            throw ApiException.badRequest("Interest settings are for checking and savings accounts.");
        }
        if (input.creditDay() == null || input.creditDay() < 1 || input.creditDay() > SavingsInterest.LAST_DAY) {
            throw ApiException.badRequest("Please choose the day of the month interest is credited.");
        }
        Destination destination = Inputs.choice(Destination.class, input.destination(), "where the interest goes");
        Long bucketId = null;
        if (destination == Destination.BUCKET) {
            Bucket bucket = input.destinationBucketId() == null ? null : buckets.find(input.destinationBucketId()).orElse(null);
            if (bucket == null || bucket.accountId() != id || bucket.archived()) {
                throw ApiException.badRequest("Please choose one of this account's buckets for the interest.");
            }
            bucketId = bucket.id();
        }
        interest.saveSettings(id, new Settings(input.creditDay(), destination, bucketId));
        ApiResponse.ok(ctx, detail(id));
    }

    private void saveRate(Context ctx) {
        long id = Inputs.id(ctx, "id");
        RateInput input = Inputs.body(ctx, RateInput.class);
        if (input.rateBp() == null || input.rateBp() < 0 || input.rateBp() > MAX_RATE_BP) {
            throw ApiException.badRequest("Please enter a rate between 0% and 1,000%.");
        }
        if (input.effectiveDate() == null || !Inputs.inSupportedYears(input.effectiveDate())) {
            throw ApiException.badRequest("Please choose the date this rate starts from.");
        }
        Account account = accounts.find(id).orElseThrow(ApiException::notFound);
        interest.saveRate(id, rateKind(account), input.rateBp(), input.effectiveDate());
        ApiResponse.send(ctx, 201, detail(id));
    }

    private void deleteRate(Context ctx) {
        long id = Inputs.id(ctx, "id");
        if (!interest.deleteRate(id, Inputs.id(ctx, "rateId"))) {
            throw ApiException.notFound();
        }
        ApiResponse.ok(ctx, detail(id));
    }

    private Account.Detail detail(long id) {
        Account account = accounts.find(id).orElseThrow(ApiException::notFound);
        return new Account.Detail(
                account, accounts.balances(id), interest.settings(id).orElse(Settings.DEFAULT), interest.rates(id));
    }

    /** Each kind of account has one kind of rate, so the request never has to say which. */
    private static RateKind rateKind(Account account) {
        return switch (Inputs.choice(AccountType.class, account.type(), "the kind of account")) {
            case CHECKING, SAVINGS -> RateKind.APY;
            case INVESTMENT -> RateKind.GROWTH;
            case CREDIT_CARD -> RateKind.APR;
            case LOAN -> RateKind.LOAN_RATE;
        };
    }

    private static AccountsRepository.Fields fields(AccountInput input) {
        AccountType type = Inputs.choice(AccountType.class, input.type(), "the kind of account");
        Long threshold = input.lowBalanceThresholdCents() == null
                ? null
                : (Long) Inputs.amount(input.lowBalanceThresholdCents(), "The low-balance amount");
        if (threshold != null && !type.holdsCash()) {
            throw ApiException.badRequest("A low-balance warning is for checking and savings accounts.");
        }
        return new AccountsRepository.Fields(
                Inputs.text(input.name(), "a name for the account", NAME_MAX),
                Inputs.optionalText(input.institution(), "the bank or institution", NAME_MAX),
                type,
                Inputs.optionalText(input.subtypeLabel(), "the account label", NAME_MAX),
                Inputs.color(input.color()),
                Boolean.TRUE.equals(input.archived()),
                threshold);
    }
}
