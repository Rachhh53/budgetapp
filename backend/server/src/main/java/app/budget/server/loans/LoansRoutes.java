package app.budget.server.loans;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.interest.SavingsInterest;
import app.budget.engine.loans.Amortization;
import app.budget.engine.loans.Amortization.Schedule;
import app.budget.engine.items.Placement;
import app.budget.engine.schedule.Frequency;
import app.budget.engine.schedule.ScheduleRule;
import app.budget.engine.schedule.WeekendRule;
import app.budget.server.accounts.Account;
import app.budget.server.accounts.AccountType;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.accounts.InterestRepository;
import app.budget.server.accounts.InterestRepository.RateKind;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.income.PayCalendar;
import app.budget.server.items.AmountMode;
import app.budget.server.items.ItemKind;
import app.budget.server.items.ItemsRepository;
import app.budget.server.items.ItemsRepository.Identity;
import app.budget.server.items.ItemsRepository.Item;
import app.budget.server.items.ItemsRepository.Terms;
import app.budget.server.ledger.Ledger;
import app.budget.server.loans.LoansRepository.Settings;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A loan's terms and what they work out to: {@code GET} and {@code PUT /api/accounts/{id}/loan}.
 * The loan itself is an account, and its rate is one of that account's rates.
 */
public final class LoansRoutes {

    private static final int MAX_TERM_MONTHS = 600;
    /** The longest name an item takes. */
    private static final int MAX_NAME = 80;

    private final LoansRepository loans;
    private final AccountsRepository accounts;
    private final InterestRepository interest;
    private final Ledger ledger;
    private final PayCalendar pay;
    private final ItemsRepository items;

    public LoansRoutes(
            LoansRepository loans, AccountsRepository accounts, InterestRepository interest, Ledger ledger, PayCalendar pay,
            ItemsRepository items) {
        this.loans = loans;
        this.accounts = accounts;
        this.interest = interest;
        this.ledger = ledger;
        this.pay = pay;
        this.items = items;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/accounts/{id}/loan", ctx -> ApiResponse.ok(ctx, view(loan(ctx))));
        routes.put("/api/accounts/{id}/loan", this::save);
        routes.post("/api/accounts/{id}/loan/payment", this::addPayment);
    }

    /**
     * Request body for a loan's terms. Amounts are whole cents.
     *
     * @param paymentCents the whole monthly payment, escrow included
     * @param paymentDay   day of the month it is due, 1–31 (31 = the last day); defaults to the
     *                     start date's day, or the 1st
     */
    public record LoanInput(
            Long originalPrincipalCents,
            Integer termMonths,
            LocalDate startDate,
            Long paymentCents,
            Integer paymentDay,
            Long escrowInsuranceCents,
            Long escrowTaxesCents,
            Long paymentItemId) {}

    /** Request body for adding the loan's payment as a recurring item, from the loan's page. */
    public record PaymentInput(Long payFromAccountId, Boolean autopay) {}

    /** A recurring item that pays the loan. */
    public record PaidBy(long itemId, String name, boolean linked) {}

    /** How one monthly payment is shared out. {@code paymentCents} is the whole payment, escrow included. */
    public record PaymentSplit(LocalDate date, long paymentCents, long interestCents, long principalCents, long escrowCents) {}

    /** A calendar year of the payments still to come. {@code endingCents} is what is owed at its end. */
    public record YearRow(int year, long interestCents, long principalCents, long endingCents) {}

    /**
     * A loan with what its terms work out to from today's balance.
     *
     * @param settings                {@code null} until the terms are entered; nothing below is worked out then
     * @param rateBp                  the rate in force today; {@code null} until one is entered
     * @param balanceCents            what is owed today
     * @param standardPaymentCents    the level payment (before escrow) that pays off what was borrowed over
     *                                its term; {@code null} unless the amount, term and rate are all known
     * @param nextPayment             the next monthly payment and how it is shared out
     * @param payoffDate              when the last payment falls; {@code null} when the payment never pays it off
     * @param interestRemainingCents  the interest still to be paid until then
     * @param paysOff                 false when the payment does not cover the interest
     * @param paidBy                  the recurring items that pay it: the one linked here, and any
     *                                transfer into the loan
     * @param years                   the payments still to come, a year at a time
     */
    public record LoanView(
            Settings settings,
            Integer rateBp,
            Long balanceCents,
            Long standardPaymentCents,
            PaymentSplit nextPayment,
            LocalDate payoffDate,
            Long interestRemainingCents,
            boolean paysOff,
            List<PaidBy> paidBy,
            List<YearRow> years) {}

    private void save(Context ctx) {
        Account account = loan(ctx);
        LoanInput input = Inputs.body(ctx, LoanInput.class);
        long payment = Inputs.amount(input.paymentCents(), "The monthly payment");
        long insurance = input.escrowInsuranceCents() == null ? 0 : Inputs.amount(input.escrowInsuranceCents(), "The insurance amount");
        long taxes = input.escrowTaxesCents() == null ? 0 : Inputs.amount(input.escrowTaxesCents(), "The taxes amount");
        if (insurance + taxes > payment) {
            throw ApiException.badRequest("The insurance and taxes can't add up to more than the whole payment.");
        }
        Long principal = input.originalPrincipalCents() == null
                ? null
                : (Long) Inputs.amount(input.originalPrincipalCents(), "The amount borrowed");
        if (principal != null && principal == 0) {
            throw ApiException.badRequest("Please enter the amount borrowed, or leave it empty.");
        }
        if (input.termMonths() != null && (input.termMonths() < 1 || input.termMonths() > MAX_TERM_MONTHS)) {
            throw ApiException.badRequest("Please enter how long the loan runs, from 1 month to 50 years.");
        }
        if (input.startDate() != null && !Inputs.inSupportedYears(input.startDate())) {
            throw ApiException.badRequest("Please choose the date the loan started, or leave it empty.");
        }
        Integer day = input.paymentDay() != null
                ? input.paymentDay()
                : input.startDate() != null ? (Integer) input.startDate().getDayOfMonth() : (Integer) 1;
        if (day < 1 || day > SavingsInterest.LAST_DAY) {
            throw ApiException.badRequest("Please choose the day of the month the payment is due.");
        }
        Long paymentItemId = input.paymentItemId() == null ? null : (Long) payingItem(account, input.paymentItemId()).id();
        loans.save(new Settings(
                account.id(), principal, input.termMonths(), input.startDate(), payment, day, insurance, taxes, paymentItemId));
        ApiResponse.ok(ctx, view(account));
    }

    private LoanView view(Account account) {
        LocalDate today = pay.today();
        Settings settings = loans.settings(account.id()).orElse(null);
        // Newest first, so the first one that has started is the one in force.
        Optional<BasisPoints> rate = interest.rates(account.id()).stream()
                .filter(found -> found.kind() == RateKind.LOAN_RATE && !found.effectiveDate().isAfter(today))
                .findFirst()
                .map(found -> BasisPoints.of(found.rateBp()));
        Long balance = ledger.balancesOn(today).stream()
                .filter(found -> found.accountId() == account.id())
                .map(found -> (Long) found.balanceCents())
                .findFirst()
                .orElse(null);
        Integer rateBp = rate.map(BasisPoints::value).orElse(null);
        if (settings == null) {
            return new LoanView(null, rateBp, balance, null, null, null, null, true, paidBy(account, null), List.of());
        }
        Long standard = settings.originalPrincipalCents() != null && settings.termMonths() != null && rate.isPresent()
                ? (Long) Amortization.standardPayment(Money.ofCents(settings.originalPrincipalCents()), rate.get(), settings.termMonths()).cents()
                : null;

        // The next payment day from today on; the schedule runs monthly from there.
        LocalDate next = SavingsInterest.creditDates(settings.paymentDay(), today, today.plusMonths(1)).getFirst();
        Money owed = Money.ofCents(balance == null ? 0 : balance);
        Money toTheLoan = Money.ofCents(settings.paymentCents() - settings.escrowCents());
        Schedule schedule = Amortization.schedule(owed, rate.orElse(BasisPoints.of(0)), toTheLoan, next);

        PaymentSplit split = null;
        if (!schedule.payments().isEmpty()) {
            Amortization.Payment first = schedule.payments().getFirst();
            split = new PaymentSplit(
                    first.date(), first.payment().cents() + settings.escrowCents(), first.interest().cents(), first.principal().cents(),
                    settings.escrowCents());
        }
        return new LoanView(
                settings, rateBp, balance, standard, split, schedule.payoffDate(),
                schedule.paysOff() ? (Long) schedule.totalInterest().cents() : null, schedule.paysOff(),
                paidBy(account, settings.paymentItemId()), years(schedule));
    }

    /**
     * The recurring item being linked as the loan's payment. It has to be one that takes money
     * out of an account and sends it nowhere else: a bill, or a transfer into this loan.
     */
    private Item payingItem(Account loan, long itemId) {
        Item item = items.find(itemId)
                .orElseThrow(() -> ApiException.badRequest("Please choose the recurring item that pays this loan."));
        Terms terms = item.versionOn(pay.today()).terms();
        boolean paysElsewhere = terms.payToAccountId() != null && terms.payToAccountId() != loan.id();
        if (item.kind() == ItemKind.BUDGET || item.kind() == ItemKind.BUCKET_MOVE || item.kind() == ItemKind.CARD_PAYMENT || paysElsewhere) {
            throw ApiException.badRequest("Please choose a bill, or a transfer into this loan, as the item that pays it.");
        }
        if (terms.payFromAccountId() == loan.id()) {
            throw ApiException.badRequest("A loan can't be paid from itself. Please choose another recurring item.");
        }
        return item;
    }

    /** The recurring items that pay the loan today: the linked one first, then any transfer into it. */
    private List<PaidBy> paidBy(Account loan, Long linkedItemId) {
        LocalDate today = pay.today();
        List<PaidBy> paying = new ArrayList<>();
        for (Item item : items.all()) {
            if (item.archived()) {
                continue;
            }
            boolean linked = linkedItemId != null && linkedItemId == item.id();
            Long payTo = item.versionOn(today).terms().payToAccountId();
            if (linked || (payTo != null && payTo == loan.id())) {
                paying.add(new PaidBy(item.id(), item.name(), linked));
            }
        }
        paying.sort((a, b) -> Boolean.compare(b.linked(), a.linked()));
        return paying;
    }

    /**
     * Adds the loan's monthly payment as a recurring transfer into it, from the loan's page: the
     * whole payment, on the payment day, from today on. It is an ordinary recurring item afterwards.
     */
    private void addPayment(Context ctx) {
        Account account = loan(ctx);
        PaymentInput input = Inputs.body(ctx, PaymentInput.class);
        Settings settings = loans.settings(account.id())
                .orElseThrow(() -> ApiException.conflict("Please enter the monthly payment first."));
        if (!paidBy(account, settings.paymentItemId()).isEmpty()) {
            throw ApiException.conflict("This loan is already paid by one of your recurring items.");
        }
        if (settings.paymentCents() == 0) {
            throw ApiException.conflict("Please enter the monthly payment first.");
        }
        Account from = input.payFromAccountId() == null ? null : accounts.find(input.payFromAccountId()).orElse(null);
        if (from == null || !AccountType.holdsCash(from)) {
            throw ApiException.badRequest("Please choose a checking or savings account to pay this loan from.");
        }
        LocalDate today = pay.today();
        Terms terms = new Terms(
                settings.paymentCents(),
                AmountMode.PER_OCCURRENCE,
                false,
                null,
                from.id(),
                account.id(),
                Boolean.TRUE.equals(input.autopay()),
                Placement.DUE_PERIOD,
                new ScheduleRule(Frequency.MONTHLY, 1, settings.paymentDay(), null, null, today, null, WeekendRule.NONE, List.of()),
                0,
                null,
                null,
                null,
                false,
                false);
        String name = account.name() + " payment";
        long itemId = items.create(
                new Identity(name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name, ItemKind.TRANSFER, null, false, List.of()),
                terms,
                today);
        loans.save(new Settings(
                settings.accountId(), settings.originalPrincipalCents(), settings.termMonths(), settings.startDate(),
                settings.paymentCents(), settings.paymentDay(), settings.escrowInsuranceCents(), settings.escrowTaxesCents(), itemId));
        ApiResponse.send(ctx, 201, view(account));
    }

    /** The schedule a calendar year at a time: a mortgage's 360 payments are too many to read. */
    private static List<YearRow> years(Schedule schedule) {
        List<YearRow> rows = new ArrayList<>();
        int year = 0;
        long interestCents = 0;
        long principalCents = 0;
        long ending = 0;
        for (Amortization.Payment payment : schedule.payments()) {
            if (payment.date().getYear() != year && year != 0) {
                rows.add(new YearRow(year, interestCents, principalCents, ending));
                interestCents = 0;
                principalCents = 0;
            }
            year = payment.date().getYear();
            interestCents += payment.interest().cents();
            principalCents += payment.principal().cents();
            ending = payment.balanceAfter().cents();
        }
        if (year != 0) {
            rows.add(new YearRow(year, interestCents, principalCents, ending));
        }
        return rows;
    }

    /** The loan in the path. Any other kind of account is not a loan, so it is "not found". */
    private Account loan(Context ctx) {
        return accounts.find(Inputs.id(ctx, "id"))
                .filter(account -> Inputs.code(AccountType.LOAN).equals(account.type()))
                .orElseThrow(ApiException::notFound);
    }
}
