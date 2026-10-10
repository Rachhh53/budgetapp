package app.budget.server.loans;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.interest.SavingsInterest;
import app.budget.engine.loans.Amortization;
import app.budget.engine.loans.Amortization.Schedule;
import app.budget.server.accounts.Account;
import app.budget.server.accounts.AccountType;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.accounts.InterestRepository;
import app.budget.server.accounts.InterestRepository.RateKind;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.income.PayCalendar;
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

    private final LoansRepository loans;
    private final AccountsRepository accounts;
    private final InterestRepository interest;
    private final Ledger ledger;
    private final PayCalendar pay;

    public LoansRoutes(
            LoansRepository loans, AccountsRepository accounts, InterestRepository interest, Ledger ledger, PayCalendar pay) {
        this.loans = loans;
        this.accounts = accounts;
        this.interest = interest;
        this.ledger = ledger;
        this.pay = pay;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/accounts/{id}/loan", ctx -> ApiResponse.ok(ctx, view(loan(ctx))));
        routes.put("/api/accounts/{id}/loan", this::save);
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
            Long escrowTaxesCents) {}

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
        loans.save(new Settings(account.id(), principal, input.termMonths(), input.startDate(), payment, day, insurance, taxes));
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
            return new LoanView(null, rateBp, balance, null, null, null, null, true, List.of());
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
                schedule.paysOff() ? (Long) schedule.totalInterest().cents() : null, schedule.paysOff(), years(schedule));
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
