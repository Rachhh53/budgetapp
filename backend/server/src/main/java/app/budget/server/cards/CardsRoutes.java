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
import app.budget.server.accounts.Account.BalanceEntry;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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

    /** The whole balance: 100%, in basis points. */
    private static final int MAX_SHARE_BP = 10_000;
    /** The most cash back a card is taken to give: 20%, as migration V33 allows. */
    private static final int MAX_REWARDS_BP = 2_000;
    /** The longest note on a redemption. */
    private static final int NOTE_MAX = 80;

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
        routes.put("/api/cards/{id}/minimum", this::saveMinimum);
        routes.put("/api/cards/{id}/rewards", this::saveRewards);
        routes.post("/api/cards/{id}/rewards/redemptions", this::addRedemption);
        routes.put("/api/cards/{id}/rewards/redemptions/{redemptionId}", this::updateRedemption);
        routes.delete("/api/cards/{id}/rewards/redemptions/{redemptionId}", this::deleteRedemption);
        routes.post("/api/cards/{id}/statements", this::saveStatement);
        routes.put("/api/cards/{id}/statements/{statementId}", this::updateStatement);
        routes.delete("/api/cards/{id}/statements/{statementId}", this::deleteStatement);
        routes.post("/api/cards/{id}/promos", this::createPromo);
        routes.put("/api/cards/{id}/promos/{promoId}", this::updatePromo);
        routes.delete("/api/cards/{id}/promos/{promoId}", this::deletePromo);
        routes.post("/api/cards/{id}/promos/{promoId}/payoff", this::addPayoff);
    }

    /**
     * Request body for how a card bills. Days are days of the month, 1–31 (31 = the last day). How the
     * card is paid is not here: its payment is a recurring item, and is set there (see {@code ItemsRoutes}).
     *
     * @param monthlyEstimateCents what a statement month's spending is expected to come to, for a card
     *                             whose charges are not entered one by one; left out otherwise
     */
    public record SettingsInput(Integer statementCloseDay, Integer dueDay, Long monthlyEstimateCents) {}

    /**
     * Request body for how a card's minimum payment is estimated until a statement with the real one is entered.
     *
     * @param shareBp      the share of the statement balance, in basis points (1% is 100); 0 to 10000
     * @param floorCents   the least it comes to, when the balance is at least that much
     * @param plusInterest true when that month's interest is added to the share
     */
    public record MinimumInput(Integer shareBp, Long floorCents, Boolean plusInterest) {}

    /**
     * Request body for a card's rewards (spec §5.6).
     *
     * @param rateBp         the cash back on what is charged, in basis points (1.5% is 150), 1 to 2000; {@code null} for none
     * @param buildUp        true to let them build up instead of coming off each statement
     * @param yearlyMonth    built up: every year, all of it goes on at the statement closing in this month (1–12); {@code null} for no such rule
     * @param thresholdCents built up: all of it goes on once at least this has built up; {@code null} for no such rule
     * @param startCents     built up: what the card says has built up on {@code startDate}; {@code null} to start from nothing
     */
    public record RewardsInput(
            Integer rateBp, Boolean buildUp, Integer yearlyMonth, Long thresholdCents, Long startCents, LocalDate startDate) {}

    /**
     * Request body for using built-up rewards as a statement credit.
     *
     * @param amountCents {@code null} for everything built up by then
     */
    public record RedemptionInput(LocalDate date, Long amountCents, String note) {}

    /**
     * A redemption as the API returns it.
     *
     * @param creditedCents what it comes to: never more than had built up by then; 0 when nothing had
     */
    public record RedemptionView(long id, LocalDate date, Long amountCents, String note, long creditedCents) {}

    /**
     * A card's rewards, as the API returns them.
     *
     * @param buildUp         false when they come off each statement
     * @param builtUpCents    built up: what has built up as of today
     * @param nextCreditDate  built up: when the next statement credit is, by a rule or a redemption; {@code null} for none in the year ahead
     * @param nextCreditCents built up: what it comes to
     */
    public record RewardsView(
            int rateBp, boolean buildUp, Integer yearlyMonth, Long thresholdCents, Long startCents, LocalDate startDate, long builtUpCents,
            LocalDate nextCreditDate, Long nextCreditCents, List<RedemptionView> redemptions) {}

    /** How a card's minimum payment is estimated, as the API returns it. */
    public record MinimumView(int shareBp, long floorCents, boolean plusInterest) {}

    /** Request body for a statement as it arrived. {@code dueDate} defaults to the card's next due day. */
    public record StatementInput(LocalDate closeDate, Long balanceCents, Long minimumDueCents, LocalDate dueDate) {}

    /**
     * Request body for a promotional rate. Rates are basis points (0% is 0, 24.49% is 2449).
     *
     * @param originalBalanceCents the amount the rate is on (a balance transfer, one purchase); left
     *                             out when it is on everything the card owes, new charges included
     */
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
     * @param originalBalanceCents       the amount it is on; {@code null} when it is on the whole balance
     * @param remainingCents             what is left of its balance; for one on the whole balance, all that is owed
     * @param paidPercent                how much of the amount it started with is gone, 0–100; {@code null}
     *                                   for one on the whole balance, which has no amount to measure against
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
            Long originalBalanceCents,
            Integer rateAfterBp,
            boolean deferredInterest,
            long remainingCents,
            Integer paidPercent,
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
    /**
     * @param recordsCents  an entered statement: what the card's own records came to at the end of its closing day
     *                      (Rachel, 2026-10-21: "when I enter a statement balance … and there is discrepancy … just
     *                      make that visible"); {@code null} for the projected one
     * @param reconcileFrom when they differ, the first day of what to look through: the day after the last entered
     *                      statement that matched, or the last balance entered, whichever is later; {@code null} when
     *                      they match
     */
    public record StatementView(
            Long id,
            LocalDate closeDate,
            long balanceCents,
            Long minimumDueCents,
            LocalDate dueDate,
            boolean entered,
            long paidCents,
            String status,
            Long recordsCents,
            LocalDate reconcileFrom) {}

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
     * @param minimum       how its minimum payment is estimated; the usual rule until it is changed
     * @param rewardsBp     its cash back on what is charged, in basis points; {@code null} for none
     * @param rewards       how its rewards are used; {@code null} for none
     */
    public record CardDetail(
            CardView card, List<PromoView> promos, List<StatementView> statements, List<Rate> rates, LocalDate nextCloseDate,
            LocalDate nextDueDate, MinimumView minimum, Integer rewardsBp, RewardsView rewards) {}

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
                nextClose == null ? null : StatementCycle.dueDate(nextClose, cardSettings.dueDay()),
                minimumView(cards.minimumRules().getOrDefault(account.id(), CardPayment.MinimumRule.USUAL)),
                cards.rewardsRates().get(account.id()),
                rewardsView(account, today));
    }

    /** How a card's rewards are used, with what has built up today and the next credit; {@code null} without rewards. */
    private RewardsView rewardsView(Account account, LocalDate today) {
        Integer rate = cards.rewardsRates().get(account.id());
        if (rate == null) {
            return null;
        }
        CardsRepository.BuildUp buildUp = cards.rewardsBuildUps().get(account.id());
        if (buildUp == null) {
            return new RewardsView(rate, false, null, null, null, null, 0, null, null, List.of());
        }
        CardProjector.Result followed = ledger.cardProjection(account, today, today.plusYears(1));
        // What had built up by today: the last change on or before it, else the card's own starting figure if it has begun.
        long builtUp = buildUp.startCents() != null && (buildUp.startDate() == null || !buildUp.startDate().isAfter(today))
                ? buildUp.startCents() : 0;
        for (CardProjector.RewardsPoint point : followed.rewards()) {
            if (!point.date().isAfter(today)) {
                builtUp = point.builtUp().cents();
            }
        }
        Map<Integer, Long> credited = new HashMap<>();
        CardProjector.Derived next = null;
        for (CardProjector.Derived line : followed.derived()) {
            if (line.kind() == CardProjector.Kind.REWARDS_REDEEMED) {
                if (line.key() >= 0) {
                    credited.put(line.key(), -line.amount().cents());
                }
                if (next == null && line.date().isAfter(today)) {
                    next = line;
                }
            }
        }
        List<RedemptionView> redemptions = cards.allRedemptions().getOrDefault(account.id(), List.of()).stream()
                .map(redemption -> new RedemptionView(redemption.id(), redemption.date(), redemption.amountCents(), redemption.note(),
                        credited.getOrDefault(Math.toIntExact(redemption.id()), 0L)))
                .toList();
        return new RewardsView(rate, true, buildUp.yearlyMonth(), buildUp.thresholdCents(), buildUp.startCents(), buildUp.startDate(),
                builtUp, next == null ? null : next.date(), next == null ? null : -next.amount().cents(), redemptions);
    }

    /**
     * Sets or takes away a card's rewards, and how they are used: off each statement close, or left to build up and
     * put on by a rule or a redemption (Rachel, 2026-10-20).
     */
    private void saveRewards(Context ctx) {
        Account account = card(ctx);
        RewardsInput input = Inputs.body(ctx, RewardsInput.class);
        Integer rate = input.rateBp();
        if (rate != null && (rate < 1 || rate > MAX_REWARDS_BP)) {
            throw ApiException.badRequest("Please enter the cash back as a percentage from 0.01 to 20, like 1.50.");
        }
        CardsRepository.BuildUp buildUp = rate != null && Boolean.TRUE.equals(input.buildUp()) ? buildUp(input) : null;
        // Rewards are earned at a statement close, so they mean nothing until the card's dates are in.
        if (!cards.saveRewardsRate(account.id(), rate)) {
            throw ApiException.conflict("Please enter this card's statement and due dates first.");
        }
        cards.saveRewardsBuildUp(account.id(), buildUp);
        ApiResponse.ok(ctx, detail(account));
    }

    private CardsRepository.BuildUp buildUp(RewardsInput input) {
        Integer month = input.yearlyMonth();
        if (month != null && (month < 1 || month > 12)) {
            throw ApiException.badRequest("Please choose the month they go on from the list.");
        }
        Long threshold = input.thresholdCents();
        if (threshold != null && Inputs.amount(threshold, "The amount they go on at") == 0) {
            throw ApiException.badRequest("Please enter the amount they go on at, more than $0.00, or leave it empty.");
        }
        Long start = input.startCents() == null ? null : Inputs.amount(input.startCents(), "What has built up");
        if (start != null && input.startDate() == null) {
            throw ApiException.badRequest("Please choose the day that amount is from.");
        }
        if (input.startDate() != null && input.startDate().isAfter(pay.today())) {
            throw ApiException.badRequest("Please choose a day up to today for what has built up.");
        }
        return new CardsRepository.BuildUp(month, threshold, start, start == null ? null : input.startDate());
    }

    private void addRedemption(Context ctx) {
        Account account = card(ctx);
        RedemptionInput input = redemptionInput(ctx, account);
        long id = cards.addRedemption(account.id(), input.date(), input.amountCents(), input.note());
        ApiResponse.send(ctx, 201, new RedemptionCreated(id, detail(account)));
    }

    private void updateRedemption(Context ctx) {
        Account account = card(ctx);
        long id = Inputs.id(ctx, "redemptionId");
        cards.findRedemption(account.id(), id).orElseThrow(ApiException::notFound);
        RedemptionInput input = redemptionInput(ctx, account);
        cards.updateRedemption(account.id(), id, input.date(), input.amountCents(), input.note());
        ApiResponse.ok(ctx, detail(account));
    }

    private void deleteRedemption(Context ctx) {
        Account account = card(ctx);
        if (!cards.deleteRedemption(account.id(), Inputs.id(ctx, "redemptionId"))) {
            throw ApiException.notFound();
        }
        ApiResponse.ok(ctx, detail(account));
    }

    public record RedemptionCreated(long id, CardDetail card) {}

    /** A redemption as sent, checked; only a card whose rewards build up has anything to redeem. */
    private RedemptionInput redemptionInput(Context ctx, Account account) {
        if (!cards.rewardsBuildUps().containsKey(account.id())) {
            throw ApiException.conflict(
                    "This card's rewards come off each statement on their own, so nothing builds up to redeem. Choose Let them build up first.");
        }
        RedemptionInput input = Inputs.body(ctx, RedemptionInput.class);
        if (input.date() == null) {
            throw ApiException.badRequest("Please choose the day the credit goes on.");
        }
        Long amount = input.amountCents() == null ? null : Inputs.amount(input.amountCents(), "The amount to redeem");
        if (amount != null && amount == 0) {
            throw ApiException.badRequest("Please enter how much to redeem, more than $0.00, or choose everything built up.");
        }
        String note = Inputs.optionalText(input.note(), "the note", NOTE_MAX);
        return new RedemptionInput(input.date(), amount, note);
    }

    private static MinimumView minimumView(CardPayment.MinimumRule rule) {
        return new MinimumView(rule.shareBp(), rule.floor().cents(), rule.plusInterest());
    }

    private void saveMinimum(Context ctx) {
        Account account = card(ctx);
        MinimumInput input = Inputs.body(ctx, MinimumInput.class);
        if (input.shareBp() == null || input.shareBp() < 0 || input.shareBp() > MAX_SHARE_BP) {
            throw ApiException.badRequest("Please enter the share of the balance as a percentage from 0 to 100.");
        }
        long floor = Inputs.amount(input.floorCents(), "The least the minimum comes to");
        CardPayment.MinimumRule rule = new CardPayment.MinimumRule(input.shareBp(), Money.ofCents(floor), Boolean.TRUE.equals(input.plusInterest()));
        // The rule is kept with the card's dates, and means nothing without them: no statement is projected until they are in.
        if (!cards.saveMinimumRule(account.id(), rule)) {
            throw ApiException.conflict("Please enter this card's statement and due dates first.");
        }
        ApiResponse.ok(ctx, detail(account));
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
        Long estimate = input.monthlyEstimateCents() == null
                ? null
                : (Long) Inputs.amount(input.monthlyEstimateCents(), "The monthly estimate");

        // How it is paid belongs to its recurring payment, so that part is kept exactly as it stands.
        Settings was = cards.settings(account.id()).orElse(null);
        Settings saved = was == null
                ? new Settings(account.id(), closeDay, dueDay, CardsRepository.NO_PAYMENT_YET, null, false, null, null, estimate)
                : new Settings(
                        account.id(), closeDay, dueDay, was.paymentMode(), was.fixedPaymentCents(), was.autopay(),
                        was.payFromAccountId(), was.paymentItemId(), estimate);
        cards.saveSettings(withItsPayment(account, saved, was != null && was.dueDay() != dueDay));
        ApiResponse.ok(ctx, detail(account));
    }

    /**
     * Ties a card's dates to its recurring payment. A payment into this card that was already among
     * the recurring items becomes the card's own, as the fixed amount it was entered as. And when the
     * due day moves, a monthly payment moves with it from today: it is due when the card says it is.
     */
    private Settings withItsPayment(Account account, Settings saved, boolean dueDayMoved) {
        LocalDate today = pay.today();
        Item linked = saved.paymentItemId() == null
                ? null
                : items.find(saved.paymentItemId()).filter(item -> !item.archived()).orElse(null);
        if (linked == null) {
            return paymentAlreadyThere(account, today)
                    .map(item -> {
                        Terms terms = item.versionOn(today).terms();
                        return new Settings(
                                saved.accountId(), saved.statementCloseDay(), saved.dueDay(), CardPayment.Mode.FIXED,
                                terms.amountCents(), terms.autopay(), terms.payFromAccountId(), item.id(),
                                saved.monthlyEstimateCents());
                    })
                    .orElse(saved);
        }
        Terms terms = linked.versionOn(today).terms();
        ScheduleRule rule = terms.schedule();
        if (dueDayMoved && rule.frequency() == Frequency.MONTHLY) {
            items.update(
                    linked.id(),
                    new Identity(linked.name(), linked.kind(), linked.note(), linked.archivedOn(), linked.tagIds(), linked.countsFromPaid(), linked.fromLastYear()),
                    new Terms(
                            terms.amountCents(), terms.amountMode(), terms.estimate(), terms.categoryId(), terms.payFromAccountId(),
                            terms.payToAccountId(), terms.autopay(), terms.placement(),
                            new ScheduleRule(
                                    rule.frequency(), rule.interval(), saved.dueDay(), rule.day2(), rule.month(), rule.startDate(),
                                    rule.endDate(), rule.weekendRule(), rule.manualDates()),
                            terms.daysAfterPayday(), terms.bucketId(), terms.saveUpBucketId(), terms.fromBucketId(), terms.billedLater(),
                            terms.paidAhead()),
                    today);
        }
        return saved;
    }

    /**
     * The one recurring card payment into this card that the user set up before the card's dates
     * were entered. With none, or more than one, nothing is taken over. A promotion's payoff is
     * a different payment and is left alone.
     */
    private Optional<Item> paymentAlreadyThere(Account account, LocalDate today) {
        Set<Long> payoffs = cards.promos(account.id()).stream()
                .map(Promo::payoffItemId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<Item> found = items.all().stream()
                .filter(item -> !item.archived() && item.kind() == ItemKind.CARD_PAYMENT && !payoffs.contains(item.id()))
                .filter(item -> {
                    Long payTo = item.versionOn(today).terms().payToAccountId();
                    return payTo != null && payTo == account.id();
                })
                .toList();
        return found.size() == 1 ? Optional.of(found.getFirst()) : Optional.empty();
    }

    private void saveStatement(Context ctx) {
        Account account = card(ctx);
        StatementFields fields = statementFields(account, Inputs.body(ctx, StatementInput.class));
        cards.saveStatement(account.id(), fields.closeDate(), fields.balanceCents(), fields.minimumDueCents(), fields.dueDate());
        ApiResponse.send(ctx, 201, detail(account));
    }

    /** Changes a statement in place: its numbers and dates. 404 for one the card doesn't have; 409 for a day taken. */
    private void updateStatement(Context ctx) {
        Account account = card(ctx);
        long statementId = Inputs.id(ctx, "statementId");
        StatementFields fields = statementFields(account, Inputs.body(ctx, StatementInput.class));
        switch (cards.updateStatement(
                account.id(), statementId, fields.closeDate(), fields.balanceCents(), fields.minimumDueCents(), fields.dueDate())) {
            case NOT_FOUND -> throw ApiException.notFound();
            case SAME_DAY -> throw ApiException.conflict(
                    "There's already a statement that closed that day. Please change that one, or choose another date.");
            case DONE -> ApiResponse.ok(ctx, detail(account));
        }
    }

    private record StatementFields(LocalDate closeDate, long balanceCents, Long minimumDueCents, LocalDate dueDate) {}

    /** Checks a statement's numbers and dates, filling in the due date from the card's usual day when left out. */
    private StatementFields statementFields(Account account, StatementInput input) {
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
        return new StatementFields(input.closeDate(), balance, minimum, dueDate);
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
        PromoFields fields = promoFields(Inputs.body(ctx, PromoInput.class));
        cards.createPromo(account.id(), fields);
        regularAprFrom(account, fields);
        ApiResponse.send(ctx, 201, detail(account));
    }

    private void updatePromo(Context ctx) {
        Account account = card(ctx);
        PromoFields fields = promoFields(Inputs.body(ctx, PromoInput.class));
        if (!cards.updatePromo(account.id(), Inputs.id(ctx, "promoId"), fields)) {
            throw ApiException.notFound();
        }
        regularAprFrom(account, fields);
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
            throw ApiException.conflict("Please add this card's recurring payment first, so the payoff knows which account to come out of.");
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
                null,
                false,
                false);
        long itemId = items.create(
                new Identity(shortened(account.name() + " · " + promo.label() + " payoff"), ItemKind.CARD_PAYMENT, null, null, List.of()),
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
            views.add(new PromoView(
                    promo.id(), promo.label(), promo.kind(), promo.rateBp(), promo.startDate(), promo.endDate(),
                    promo.originalBalanceCents(), promo.rateAfterBp(), promo.deferredInterest(),
                    remaining.cents(),
                    promo.originalBalanceCents() == null
                            ? null
                            : (Integer) (int) Math.max(0, Math.min(100,
                                    (promo.originalBalanceCents() - remaining.cents()) * 100 / promo.originalBalanceCents())),
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
                        next.dueDate(), false, 0, STATUS_PLANNED, null, null));
            }
        }
        if (entered.isEmpty()) {
            return views;
        }
        // One look at what moved the card covers every statement's "paid since it closed".
        LocalDate from = entered.getLast().closeDate().plusDays(1);
        LocalDate to = entered.stream().map(Statement::dueDate).max(LocalDate::compareTo).orElse(today);
        List<Transaction> lines = ledger.transactions(account, from, to.isBefore(from) ? from : to);
        Map<LocalDate, Long> records = records(account, entered);
        Map<LocalDate, LocalDate> reconcileFrom = reconcileFrom(account, entered, records, today);
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
                    statement.dueDate(), true, paid, status, records.get(statement.closeDate()),
                    reconcileFrom.get(statement.closeDate())));
        }
        return views;
    }

    /** What the card's records came to at the end of each entered statement's closing day, worked out in one pass. */
    private Map<LocalDate, Long> records(Account account, List<Statement> entered) {
        List<LocalDate> closes = entered.stream().map(Statement::closeDate).distinct().sorted().toList();
        Map<LocalDate, Long> records = new HashMap<>();
        for (Ledger.DayBalances day : ledger.balancesOn(closes)) {
            day.balances().stream()
                    .filter(balance -> balance.accountId() == account.id())
                    .findFirst()
                    .ifPresent(balance -> records.put(day.date(), balance.balanceCents()));
        }
        return records;
    }

    /**
     * For each entered statement the records don't match: where to start looking. A balance entered for the card
     * puts the records right from its day, and a statement that matched says everything up to its close was right.
     */
    private Map<LocalDate, LocalDate> reconcileFrom(
            Account account, List<Statement> entered, Map<LocalDate, Long> records, LocalDate today) {
        // A balance planned for a day still to come puts nothing right yet.
        List<LocalDate> balancesEntered = accounts.balances(account.id()).stream()
                .map(BalanceEntry::date)
                .filter(date -> !date.isAfter(today))
                .sorted()
                .toList();
        List<Statement> oldestFirst = entered.stream().sorted(Comparator.comparing(Statement::closeDate)).toList();
        Map<LocalDate, LocalDate> from = new HashMap<>();
        LocalDate matched = null;
        for (Statement statement : oldestFirst) {
            Long counted = records.get(statement.closeDate());
            if (counted == null || counted == statement.balanceCents()) {
                matched = statement.closeDate().plusDays(1);
                continue;
            }
            LocalDate balance = balancesEntered.stream().filter(date -> !date.isAfter(statement.closeDate()))
                    .reduce((earlier, later) -> later).orElse(null);
            // Never matched and no balance: the month the statement covers.
            LocalDate start = matched != null ? matched : statement.closeDate().minusMonths(1).plusDays(1);
            if (balance != null && balance.isAfter(start)) {
                start = balance;
            }
            from.put(statement.closeDate(), start);
        }
        return from;
    }

    private Map<Long, Long> balances(LocalDate today) {
        Map<Long, Long> balances = new HashMap<>();
        for (AccountBalance balance : ledger.balancesOn(today)) {
            balances.put(balance.accountId(), balance.balanceCents());
        }
        return balances;
    }

    /**
     * The rate a promotion goes to afterwards is the card's regular APR (Rachel, 2026-10-10), so on a
     * card that has none yet, entering it on a promotion enters it for the card as well. A card
     * that already has a regular APR keeps it: a promotion may end at a different rate.
     */
    private void regularAprFrom(Account account, PromoFields fields) {
        boolean hasApr = interest.rates(account.id()).stream().anyMatch(rate -> rate.kind() == RateKind.APR);
        if (fields.rateAfterBp() == null || hasApr) {
            return;
        }
        // In force from the promotion's start, or today if it starts later, so it is the card's rate now.
        LocalDate today = pay.today();
        interest.saveRate(account.id(), RateKind.APR, fields.rateAfterBp(), fields.startDate().isAfter(today) ? today : fields.startDate());
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
        // Left out, the rate is on everything the card owes.
        Long original = input.originalBalanceCents() == null ? null : (Long) Inputs.amount(input.originalBalanceCents(), "The amount on this rate");
        if (original != null && original == 0) {
            throw ApiException.badRequest("Please enter the amount this rate is on, or choose everything on the card.");
        }
        boolean deferred = Boolean.TRUE.equals(input.deferredInterest());
        if (deferred && original == null) {
            throw ApiException.badRequest(
                    "Deferred interest is charged on the amount a promotion started with. Please enter the amount this rate is on.");
        }
        return new PromoFields(
                label, kind, input.rateBp(), input.startDate(), input.endDate(), original, input.rateAfterBp(), deferred);
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
