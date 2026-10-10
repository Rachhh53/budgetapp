package app.budget.server.cards;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.cards.CardInterest;
import app.budget.engine.cards.CardPayment;
import app.budget.engine.cards.CardProjector;
import app.budget.engine.cards.PromoPayoff;
import app.budget.engine.cards.StatementCycle;
import app.budget.engine.items.Placement;
import app.budget.engine.schedule.Frequency;
import app.budget.engine.schedule.PayPeriod;
import app.budget.engine.schedule.ScheduleRule;
import app.budget.engine.schedule.WeekendRule;
import app.budget.server.accounts.Account;
import app.budget.server.accounts.AccountType;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.accounts.InterestRepository;
import app.budget.server.accounts.InterestRepository.Rate;
import app.budget.server.accounts.InterestRepository.RateKind;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.cards.CardsRepository.Promo;
import app.budget.server.cards.CardsRepository.PromoFields;
import app.budget.server.cards.CardsRepository.PromoKind;
import app.budget.server.cards.CardsRepository.Settings;
import app.budget.server.cards.CardsRepository.Statement;
import app.budget.server.income.PayCalendar;
import app.budget.server.items.AmountMode;
import app.budget.server.items.ItemCalendar.Occurrence;
import app.budget.server.items.ItemKind;
import app.budget.server.items.ItemsRepository;
import app.budget.server.items.ItemsRepository.Identity;
import app.budget.server.items.ItemsRepository.Item;
import app.budget.server.items.ItemsRepository.Terms;
import app.budget.server.ledger.Ledger;
import app.budget.server.ledger.Ledger.AccountBalance;
import app.budget.server.ledger.Ledger.Transaction;
import app.budget.server.settings.SettingKey;
import app.budget.server.settings.SettingsRepository;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Credit cards: how each one bills and is paid, its statements, its promotional rates and the
 * payoff helper. {@code /api/cards}, {@code /api/cards/{id}} and, under it, {@code /settings},
 * {@code /statements} and {@code /promos}. The card itself is an account ({@code /api/accounts});
 * its regular APR is one of that account's rates.
 */
public final class CardsRoutes {

    private static final int LABEL_MAX = 80;
    private static final int NAME_MAX = 80;

    /** 1,000%: beyond any real rate, and a typo (24.49 entered as 2449) still fits so it can be shown and fixed. */
    private static final int MAX_RATE_BP = 100_000;

    /** Statements listed on a card's page: two years of them. */
    private static final int MAX_STATEMENTS = 24;

    private static final String STATUS_PLANNED = "planned";
    private static final String STATUS_PAID = "paid";
    private static final String STATUS_PART_PAID = "part_paid";
    private static final String STATUS_DUE = "due";

    private final CardsRepository cards;
    private final AccountsRepository accounts;
    private final InterestRepository interest;
    private final ItemsRepository items;
    private final Ledger ledger;
    private final PayCalendar pay;
    private final SettingsRepository settings;

    public CardsRoutes(
            CardsRepository cards,
            AccountsRepository accounts,
            InterestRepository interest,
            ItemsRepository items,
            Ledger ledger,
            PayCalendar pay,
            SettingsRepository settings) {
        this.cards = cards;
        this.accounts = accounts;
        this.interest = interest;
        this.items = items;
        this.ledger = ledger;
        this.pay = pay;
        this.settings = settings;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/cards", ctx -> ApiResponse.ok(ctx, list()));
        routes.get("/api/cards/{id}", ctx -> ApiResponse.ok(ctx, detail(card(ctx))));
        routes.put("/api/cards/{id}/settings", this::saveSettings);
        routes.post("/api/cards/{id}/statements", this::saveStatement);
        routes.delete("/api/cards/{id}/statements/{statementId}", this::deleteStatement);
        routes.post("/api/cards/{id}/promos", this::createPromo);
        routes.put("/api/cards/{id}/promos/{promoId}", this::updatePromo);
        routes.delete("/api/cards/{id}/promos/{promoId}", this::deletePromo);
        routes.post("/api/cards/{id}/promos/{promoId}/payoff", this::addPayoff);
    }

    /**
     * Request body for how a card bills and is paid. Days are days of the month, 1–31 (31 = the last day).
     *
     * @param paymentMode          {@code full_balance}, {@code statement_balance}, {@code minimum} or {@code fixed}
     * @param fixedPaymentCents    the monthly amount, for {@code fixed}
     * @param payFromAccountId     the checking or savings account the payment comes out of; left out
     *                             for no scheduled payment
     * @param monthlyEstimateCents what a statement month's spending is expected to come to, for a card
     *                             whose charges are not entered one by one; left out otherwise
     */
    public record SettingsInput(
            Integer statementCloseDay,
            Integer dueDay,
            String paymentMode,
            Long fixedPaymentCents,
            Boolean autopay,
            Long payFromAccountId,
            Long monthlyEstimateCents) {}

    /** Request body for a statement as it arrived. {@code dueDate} defaults to the card's next due day. */
    public record StatementInput(LocalDate closeDate, Long balanceCents, Long minimumDueCents, LocalDate dueDate) {}

    /** Request body for a promotional rate. Rates are basis points (0% is 0, 24.49% is 2449). */
    public record PromoInput(
            String label,
            String kind,
            Integer rateBp,
            LocalDate startDate,
            LocalDate endDate,
            Long originalBalanceCents,
            Integer rateAfterBp,
            Boolean deferredInterest) {}

    /**
     * A promotion with where it stands today.
     *
     * @param remainingCents             what is left of its balance
     * @param paidPercent                how much of the balance it started with is gone, 0–100
     * @param daysLeft                   days until its last day; negative once it has ended
     * @param warning                    {@code none}, {@code soon}, {@code soon_deferred} or {@code ended}
     * @param warnFrom                   the day warnings start
     * @param paydaysLeft                paydays still to come before it ends
     * @param perPaycheckCents           what clearing it takes from each of those; {@code null} with no payday left
     * @param payoffItemId               the recurring payment the payoff helper added; {@code null} when none
     * @param rateAfterEffectiveBp       the rate on what is left once it ends
     * @param monthlyInterestIfLeftCents what today's remaining balance would cost a month at that rate
     * @param deferredInterestCents      what would be charged at once if it ended with a balance; zero unless deferred
     */
    public record PromoView(
            long id,
            String label,
            PromoKind kind,
            int rateBp,
            LocalDate startDate,
            LocalDate endDate,
            long originalBalanceCents,
            Integer rateAfterBp,
            boolean deferredInterest,
            long remainingCents,
            int paidPercent,
            long daysLeft,
            PromoPayoff.Warning warning,
            LocalDate warnFrom,
            int paydaysLeft,
            Long perPaycheckCents,
            Long payoffItemId,
            int rateAfterEffectiveBp,
            long monthlyInterestIfLeftCents,
            long deferredInterestCents) {}

    /**
     * A statement on a card's page.
     *
     * @param id        {@code null} for the next statement, which is only projected
     * @param entered   true when these are the user's numbers
     * @param paidCents what has been paid toward it since it closed
     * @param status    {@code planned}, {@code paid}, {@code part_paid} or {@code due}
     */
    public record StatementView(
            Long id,
            LocalDate closeDate,
            long balanceCents,
            Long minimumDueCents,
            LocalDate dueDate,
            boolean entered,
            long paidCents,
            String status) {}

    /** The next payment into a card that is still to be made. */
    public record NextPayment(
            long itemId, String name, LocalDate scheduledDate, LocalDate date, long amountCents, boolean estimate, boolean autopay,
            long payFromAccountId) {}

    /**
     * A card on the Credit cards screen.
     *
     * @param balanceCents what is owed today; {@code null} for an archived card nothing is known about
     * @param settings     {@code null} until the user says how the card bills and is paid
     * @param aprBp        the regular APR in force today; {@code null} until one is entered
     * @param promo        the promotion that ends soonest with a balance still on it; {@code null} when none
     */
    public record CardView(
            Account account, Long balanceCents, Settings settings, Integer aprBp, NextPayment nextPayment, PromoView promo) {}

    /**
     * One card with everything about it.
     *
     * @param statements    the next statement (projected) first, then the ones entered, newest first
     * @param rates         the regular APR over time, newest first
     * @param nextCloseDate when the statement being built closes; {@code null} without settings
     */
    public record CardDetail(
            CardView card, List<PromoView> promos, List<StatementView> statements, List<Rate> rates, LocalDate nextCloseDate,
            LocalDate nextDueDate) {}

    private List<CardView> list() {
        LocalDate today = pay.today();
        Map<Long, Long> balances = balances(today);
        Map<Long, Occurrence> nextPayments = ledger.nextPayments();
        Map<Long, Settings> allSettings = cards.allSettings();
        Map<Long, List<Promo>> allPromos = cards.allPromos();
        List<CardView> views = new ArrayList<>();
        for (Account account : accounts.all()) {
            if (isCard(account)) {
                List<PromoView> promos = promoViews(
                        allPromos.getOrDefault(account.id(), List.of()), balances.get(account.id()), aprToday(account.id(), today), today);
                views.add(view(account, balances, nextPayments, allSettings.get(account.id()), promos, today));
            }
        }
        return views;
    }

    private CardDetail detail(Account account) {
        LocalDate today = pay.today();
        Map<Long, Long> balances = balances(today);
        Settings cardSettings = cards.settings(account.id()).orElse(null);
        List<PromoView> promos = promoViews(cards.promos(account.id()), balances.get(account.id()), aprToday(account.id(), today), today);
        CardView view = view(account, balances, ledger.nextPayments(), cardSettings, promos, today);
        LocalDate nextClose = cardSettings == null ? null : StatementCycle.nextCloseAfter(cardSettings.statementCloseDay(), today.minusDays(1));
        return new CardDetail(
                view,
                promos,
                statementViews(account, cardSettings, nextClose, today),
                interest.rates(account.id()).stream().filter(rate -> rate.kind() == RateKind.APR).toList(),
                nextClose,
                nextClose == null ? null : StatementCycle.dueDate(nextClose, cardSettings.dueDay()));
    }

    private CardView view(
            Account account, Map<Long, Long> balances, Map<Long, Occurrence> nextPayments, Settings cardSettings,
            List<PromoView> promos, LocalDate today) {
        Occurrence next = nextPayments.get(account.id());
        return new CardView(
                account,
                balances.get(account.id()),
                cardSettings,
                aprToday(account.id(), today).map(BasisPoints::value).orElse(null),
                next == null ? null : new NextPayment(
                        next.itemId(), next.name(), next.scheduledDate(), next.payBy(), next.amountCents(), next.estimate(),
                        next.autopay(), next.payFromAccountId()),
                promos.stream().filter(promo -> promo.remainingCents() > 0).findFirst().orElse(null));
    }

    private void saveSettings(Context ctx) {
        Account account = card(ctx);
        SettingsInput input = Inputs.body(ctx, SettingsInput.class);
        int closeDay = dayOfMonth(input.statementCloseDay(), "Please choose the day of the month the statement closes.");
        int dueDay = dayOfMonth(input.dueDay(), "Please choose the day of the month the payment is due.");
        CardPayment.Mode mode = Inputs.choice(CardPayment.Mode.class, input.paymentMode(), "how this card is paid");
        Long fixed = null;
        if (mode == CardPayment.Mode.FIXED) {
            fixed = Inputs.amount(input.fixedPaymentCents(), "The amount you pay each month");
            if (fixed == 0) {
                throw ApiException.badRequest("Please enter the amount you pay each month.");
            }
        }
        Long estimate = input.monthlyEstimateCents() == null
                ? null
                : (Long) Inputs.amount(input.monthlyEstimateCents(), "The monthly estimate");
        Long payFrom = input.payFromAccountId();
        if (payFrom != null && accounts.find(payFrom).filter(AccountType::holdsCash).isEmpty()) {
            throw ApiException.badRequest("Please choose a checking or savings account to pay this card from.");
        }
        boolean autopay = Boolean.TRUE.equals(input.autopay());

        Long paymentItemId = cards.settings(account.id()).map(Settings::paymentItemId).orElse(null);
        Settings saved = new Settings(account.id(), closeDay, dueDay, mode, fixed, autopay, payFrom, paymentItemId, estimate);
        cards.saveSettings(new Settings(
                account.id(), closeDay, dueDay, mode, fixed, autopay, payFrom, keepPaymentInStep(account, saved), estimate));
        ApiResponse.ok(ctx, detail(account));
    }

    /**
     * Keeps the card's own recurring payment in step with its settings: monthly on the due day,
     * from the chosen account. It is added the first time an account to pay from is chosen, changed
     * from today when the settings change, and archived when there is no longer an account to pay
     * from. Being an ordinary recurring item, it shows in each pay period with its paid tick and
     * confirmation code; only its amount is special, following the card unless the mode is fixed.
     *
     * @return the id of that item; {@code null} when there is none
     */
    private Long keepPaymentInStep(Account account, Settings saved) {
        LocalDate today = pay.today();
        Item existing = saved.paymentItemId() == null ? null : items.find(saved.paymentItemId()).orElse(null);
        if (saved.payFromAccountId() == null) {
            if (existing != null && !existing.archived()) {
                items.update(
                        existing.id(),
                        new Identity(existing.name(), existing.kind(), existing.note(), true, existing.tagIds()),
                        existing.versionOn(today).terms(),
                        today);
            }
            return existing == null ? null : (Long) existing.id();
        }
        boolean fixed = saved.paymentMode() == CardPayment.Mode.FIXED;
        Terms was = existing == null ? null : existing.versionOn(today).terms();
        Terms terms = new Terms(
                // A payment that follows the card has no amount of its own: the ledger works it out.
                fixed ? saved.fixedPaymentCents() : 0,
                AmountMode.PER_OCCURRENCE,
                !fixed,
                was == null ? null : was.categoryId(),
                saved.payFromAccountId(),
                account.id(),
                saved.autopay(),
                Placement.DUE_PERIOD,
                new ScheduleRule(
                        Frequency.MONTHLY, 1, saved.dueDay(), null, null,
                        was == null ? today : was.schedule().startDate(), null, WeekendRule.NONE, List.of()),
                0,
                null,
                null,
                // The bucket it came out of belongs to the account it was paid from.
                was != null && was.payFromAccountId() == saved.payFromAccountId() ? was.fromBucketId() : null);
        if (existing == null) {
            return items.create(
                    new Identity(shortened(account.name() + " payment"), ItemKind.CARD_PAYMENT, null, false, List.of()), terms, today);
        }
        items.update(
                existing.id(),
                new Identity(existing.name(), ItemKind.CARD_PAYMENT, existing.note(), false, existing.tagIds()),
                terms,
                today);
        return existing.id();
    }

    private void saveStatement(Context ctx) {
        Account account = card(ctx);
        StatementInput input = Inputs.body(ctx, StatementInput.class);
        LocalDate today = pay.today();
        if (input.closeDate() == null) {
            throw ApiException.badRequest("Please choose the date this statement closed.");
        }
        // A statement is the real numbers on a real day, so it cannot be from the future.
        if (input.closeDate().isAfter(today) || !Inputs.inSupportedYears(input.closeDate())) {
            throw ApiException.badRequest("A statement needs a closing date of today or earlier.");
        }
        long balance = Inputs.signedAmount(input.balanceCents(), "The statement balance");
        Long minimum = input.minimumDueCents() == null ? null : (Long) Inputs.amount(input.minimumDueCents(), "The minimum payment");
        LocalDate dueDate = input.dueDate() != null
                ? input.dueDate()
                : cards.settings(account.id())
                        .map(found -> StatementCycle.dueDate(input.closeDate(), found.dueDay()))
                        .orElseThrow(() -> ApiException.badRequest("Please choose the date this statement is due."));
        if (!dueDate.isAfter(input.closeDate()) || !Inputs.inSupportedYears(dueDate)) {
            throw ApiException.badRequest("A statement is due after it closes. Please check the two dates.");
        }
        cards.saveStatement(account.id(), input.closeDate(), balance, minimum, dueDate);
        ApiResponse.send(ctx, 201, detail(account));
    }

    private void deleteStatement(Context ctx) {
        Account account = card(ctx);
        if (!cards.deleteStatement(account.id(), Inputs.id(ctx, "statementId"))) {
            throw ApiException.notFound();
        }
        ApiResponse.ok(ctx, detail(account));
    }

    private void createPromo(Context ctx) {
        Account account = card(ctx);
        cards.createPromo(account.id(), promoFields(Inputs.body(ctx, PromoInput.class)));
        ApiResponse.send(ctx, 201, detail(account));
    }

    private void updatePromo(Context ctx) {
        Account account = card(ctx);
        PromoFields fields = promoFields(Inputs.body(ctx, PromoInput.class));
        if (!cards.updatePromo(account.id(), Inputs.id(ctx, "promoId"), fields)) {
            throw ApiException.notFound();
        }
        ApiResponse.ok(ctx, detail(account));
    }

    private void deletePromo(Context ctx) {
        Account account = card(ctx);
        if (!cards.deletePromo(account.id(), Inputs.id(ctx, "promoId"))) {
            throw ApiException.notFound();
        }
        ApiResponse.ok(ctx, detail(account));
    }

    /**
     * The payoff helper's one click: adds a card payment for every paycheck still to come before
     * the promotion ends, sized so its balance is gone by then (spec §5.6).
     */
    private void addPayoff(Context ctx) {
        Account account = card(ctx);
        Promo promo = cards.findPromo(account.id(), Inputs.id(ctx, "promoId")).orElseThrow(ApiException::notFound);
        LocalDate today = pay.today();
        if (payoffItem(promo).isPresent()) {
            throw ApiException.conflict("This payoff is already one of your recurring payments.");
        }
        PromoView view = promoViews(cards.promos(account.id()), balances(today).get(account.id()), aprToday(account.id(), today), today)
                .stream()
                .filter(found -> found.id() == promo.id())
                .findFirst()
                .orElseThrow(ApiException::notFound);
        if (view.remainingCents() <= 0) {
            throw ApiException.conflict("There is nothing left on this promotion to pay off.");
        }
        if (view.perPaycheckCents() == null) {
            throw ApiException.conflict("There is no payday left before this promotion ends, so there is nothing to spread it over.");
        }
        Settings cardSettings = cards.settings(account.id()).orElse(null);
        Long payFrom = cardSettings != null && cardSettings.payFromAccountId() != null
                ? cardSettings.payFromAccountId()
                : pay.primaryAccountId().orElse(null);
        if (payFrom == null || payFrom == account.id()) {
            throw ApiException.conflict("Please choose the account this card is paid from first, under Payment setting.");
        }
        LocalDate lastPayday = promo.endDate().minusDays(1);
        LocalDate firstPayday = paydays(today.plusDays(1), lastPayday).getFirst();
        Terms terms = new Terms(
                view.perPaycheckCents(),
                AmountMode.PER_OCCURRENCE,
                false,
                null,
                payFrom,
                account.id(),
                cardSettings != null && cardSettings.autopay(),
                Placement.DUE_PERIOD,
                new ScheduleRule(Frequency.EVERY_PAY_PERIOD, 1, null, null, null, firstPayday, lastPayday, WeekendRule.NONE, List.of()),
                0,
                null,
                null,
                null);
        long itemId = items.create(
                new Identity(shortened(account.name() + " · " + promo.label() + " payoff"), ItemKind.CARD_PAYMENT, null, false, List.of()),
                terms,
                firstPayday);
        cards.setPayoffItem(promo.id(), itemId);
        ApiResponse.send(ctx, 201, detail(account));
    }

    private List<PromoView> promoViews(List<Promo> promos, Long balanceCents, Optional<BasisPoints> aprToday, LocalDate today) {
        BasisPoints apr = aprToday.orElse(BasisPoints.of(0));
        Money owed = Money.ofCents(balanceCents == null ? 0 : balanceCents);
        int leadDays = Integer.parseInt(settings.get(SettingKey.PROMO_WARNING_DAYS));
        List<app.budget.engine.cards.Promo> terms = promos.stream().map(Promo::terms).toList();
        List<PromoView> views = new ArrayList<>();
        for (Promo promo : promos) {
            app.budget.engine.cards.Promo own = promo.terms();
            Money remaining = CardInterest.remaining(own, owed, terms, today, apr);
            // Paydays strictly after today and before the end date: the ones still to come.
            int paydaysLeft = paydays(today.plusDays(1), promo.endDate().minusDays(1)).size();
            BasisPoints after = promo.rateAfterBp() != null ? BasisPoints.of(promo.rateAfterBp()) : apr;
            long paid = promo.originalBalanceCents() - remaining.cents();
            views.add(new PromoView(
                    promo.id(), promo.label(), promo.kind(), promo.rateBp(), promo.startDate(), promo.endDate(),
                    promo.originalBalanceCents(), promo.rateAfterBp(), promo.deferredInterest(),
                    remaining.cents(),
                    (int) Math.max(0, Math.min(100, paid * 100 / promo.originalBalanceCents())),
                    PromoPayoff.daysLeft(today, promo.endDate()),
                    PromoPayoff.warning(own, remaining, today, leadDays),
                    promo.endDate().minusDays(leadDays),
                    paydaysLeft,
                    PromoPayoff.perPayday(remaining, paydaysLeft).map(Money::cents).orElse(null),
                    payoffItem(promo).map(Item::id).orElse(null),
                    after.value(),
                    remaining.times(CardInterest.monthlyRate(after)).cents(),
                    // What it would cost if it ran out with today's balance still on it.
                    CardInterest.deferredCharge(own, remaining, apr).cents()));
        }
        return views;
    }

    /** The recurring payment the payoff helper added for a promotion, while it is still in use. */
    private Optional<Item> payoffItem(Promo promo) {
        return Optional.ofNullable(promo.payoffItemId()).flatMap(items::find).filter(item -> !item.archived());
    }

    private List<StatementView> statementViews(Account account, Settings cardSettings, LocalDate nextClose, LocalDate today) {
        List<Statement> entered = cards.statements(account.id()).stream().limit(MAX_STATEMENTS).toList();
        List<StatementView> views = new ArrayList<>();
        if (nextClose != null) {
            Optional<CardProjector.Statement> planned = ledger.plannedStatement(account, nextClose);
            boolean alreadyEntered = entered.stream().anyMatch(statement -> statement.closeDate().equals(nextClose));
            if (planned.isPresent() && !alreadyEntered) {
                CardProjector.Statement next = planned.get();
                views.add(new StatementView(
                        null, next.closeDate(), next.balance().cents(), next.minimumDue() == null ? null : (Long) next.minimumDue().cents(),
                        next.dueDate(), false, 0, STATUS_PLANNED));
            }
        }
        if (entered.isEmpty()) {
            return views;
        }
        // One look at what moved the card covers every statement's "paid since it closed".
        LocalDate from = entered.getLast().closeDate().plusDays(1);
        LocalDate to = entered.stream().map(Statement::dueDate).max(LocalDate::compareTo).orElse(today);
        List<Transaction> lines = ledger.transactions(account, from, to.isBefore(from) ? from : to);
        for (Statement statement : entered) {
            long paid = lines.stream()
                    .filter(line -> line.amountCents() < 0 && !line.unpaid()
                            && line.date().isAfter(statement.closeDate()) && !line.date().isAfter(statement.dueDate())
                            && !line.date().isAfter(today))
                    .mapToLong(line -> -line.amountCents())
                    .sum();
            String status = statement.balanceCents() <= 0 || paid >= statement.balanceCents()
                    ? STATUS_PAID
                    : paid > 0 ? STATUS_PART_PAID : STATUS_DUE;
            views.add(new StatementView(
                    statement.id(), statement.closeDate(), statement.balanceCents(), statement.minimumDueCents(),
                    statement.dueDate(), true, paid, status));
        }
        return views;
    }

    private Map<Long, Long> balances(LocalDate today) {
        Map<Long, Long> balances = new HashMap<>();
        for (AccountBalance balance : ledger.balancesOn(today)) {
            balances.put(balance.accountId(), balance.balanceCents());
        }
        return balances;
    }

    private Optional<BasisPoints> aprToday(long accountId, LocalDate today) {
        // Newest first, so the first one that has started is the one in force.
        return interest.rates(accountId).stream()
                .filter(rate -> rate.kind() == RateKind.APR && !rate.effectiveDate().isAfter(today))
                .findFirst()
                .map(rate -> BasisPoints.of(rate.rateBp()));
    }

    /** The paydays (first days of pay periods) within {@code from}–{@code through}, earliest first. */
    private List<LocalDate> paydays(LocalDate from, LocalDate through) {
        if (through.isBefore(from)) {
            return List.of();
        }
        return pay.payPeriods(from, through).stream()
                .map(PayPeriod::start)
                .filter(payday -> !payday.isBefore(from) && !payday.isAfter(through))
                .toList();
    }

    private PromoFields promoFields(PromoInput input) {
        String label = Inputs.text(input.label(), "a name for this promotion", LABEL_MAX);
        PromoKind kind = Inputs.choice(PromoKind.class, input.kind(), "what kind of promotion this is");
        if (input.rateBp() == null || input.rateBp() < 0 || input.rateBp() > MAX_RATE_BP) {
            throw ApiException.badRequest("Please enter the promotional rate, between 0% and 1,000%.");
        }
        if (input.rateAfterBp() != null && (input.rateAfterBp() < 0 || input.rateAfterBp() > MAX_RATE_BP)) {
            throw ApiException.badRequest("Please enter the rate afterwards between 0% and 1,000%, or leave it empty.");
        }
        if (input.startDate() == null || !Inputs.inSupportedYears(input.startDate())) {
            throw ApiException.badRequest("Please choose the date this promotion started.");
        }
        if (input.endDate() == null || !Inputs.inSupportedYears(input.endDate())) {
            throw ApiException.badRequest("Please choose the date this promotion ends.");
        }
        if (!input.endDate().isAfter(input.startDate())) {
            throw ApiException.badRequest("A promotion ends after it starts. Please check the two dates.");
        }
        long original = Inputs.amount(input.originalBalanceCents(), "The balance it started with");
        if (original == 0) {
            throw ApiException.badRequest("Please enter the balance this promotion started with.");
        }
        return new PromoFields(
                label, kind, input.rateBp(), input.startDate(), input.endDate(), original, input.rateAfterBp(),
                Boolean.TRUE.equals(input.deferredInterest()));
    }

    /** The credit card in the path. Any other kind of account is not a card, so it is "not found". */
    private Account card(Context ctx) {
        return accounts.find(Inputs.id(ctx, "id")).filter(CardsRoutes::isCard).orElseThrow(ApiException::notFound);
    }

    private static boolean isCard(Account account) {
        return Inputs.code(AccountType.CREDIT_CARD).equals(account.type());
    }

    private static int dayOfMonth(Integer day, String message) {
        if (day == null || day < 1 || day > StatementCycle.LAST_DAY) {
            throw ApiException.badRequest(message);
        }
        return day;
    }

    private static String shortened(String name) {
        return name.length() <= NAME_MAX ? name : name.substring(0, NAME_MAX);
    }
}
