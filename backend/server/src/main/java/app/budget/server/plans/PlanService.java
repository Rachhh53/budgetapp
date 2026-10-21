package app.budget.server.plans;

import app.budget.engine.Money;
import app.budget.engine.items.AnnualAmount;
import app.budget.engine.plans.PlanSuggestions;
import app.budget.engine.plans.PlanSuggestions.Basis;
import app.budget.engine.plans.PlanSuggestions.History;
import app.budget.engine.plans.PlanSuggestions.Reason;
import app.budget.engine.plans.PlanSuggestions.Suggestion;
import app.budget.engine.reports.AnnualCosts.Window;
import app.budget.engine.schedule.Frequency;
import app.budget.engine.schedule.PayPeriod;
import app.budget.engine.schedule.ScheduleRule;
import app.budget.server.accounts.AccountType;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.api.Inputs;
import app.budget.server.income.IncomeRepository;
import app.budget.server.income.IncomeRepository.Pay;
import app.budget.server.income.IncomeRepository.Source;
import app.budget.server.income.PayCalendar;
import app.budget.server.income.PayCalendar.Paycheck;
import app.budget.server.items.AmountMode;
import app.budget.server.items.ItemCalendar;
import app.budget.server.items.ItemCalendar.Occurrence;
import app.budget.server.items.ItemKind;
import app.budget.server.items.ItemsRepository;
import app.budget.server.items.ItemsRepository.Item;
import app.budget.server.items.ItemsRepository.Terms;
import app.budget.server.plans.PlansRepository.Amount;
import app.budget.server.plans.PlansRepository.Plan;
import app.budget.server.plans.PlansRepository.Status;
import app.budget.server.reports.AnnualCostsRunner;
import app.budget.server.settings.SettingKey;
import app.budget.server.settings.SettingsRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Yearly plans (spec §4.10, §5.8): what each bill, budget, transfer and paycheck is to come to
 * next year, drawn up ahead of time and put into force on the day the budget year starts.
 *
 * <p>A draft changes nothing. Each amount in it is the suggestion until one is typed in; only
 * typed amounts are kept, so a draft always shows the items as they are now. The suggestion
 * rules are the engine's ({@link PlanSuggestions}); this gathers what goes into them.
 */
public final class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    /** How long after a plan takes effect the Dashboard still says so. */
    public static final int ACTIVE_NOTICE_DAYS = 14;

    private final Jdbi jdbi;
    private final PlansRepository plans;
    private final ItemsRepository items;
    private final ItemCalendar calendar;
    private final PayCalendar pay;
    private final IncomeRepository income;
    private final AccountsRepository accounts;
    private final SettingsRepository settings;

    public PlanService(
            Jdbi jdbi,
            PlansRepository plans,
            ItemsRepository items,
            ItemCalendar calendar,
            PayCalendar pay,
            IncomeRepository income,
            AccountsRepository accounts,
            SettingsRepository settings) {
        this.jdbi = jdbi;
        this.plans = plans;
        this.items = items;
        this.calendar = calendar;
        this.pay = pay;
        this.income = income;
        this.accounts = accounts;
        this.settings = settings;
    }

    /** The steps of the wizard that hold amounts, in order. Review, the sixth, only adds them up. */
    public enum Step { INCOME, BILLS, ANNUAL, BUDGETS, SAVING }

    /** @param endsOn the last day of the plan's year */
    public record PlanView(
            long id,
            int year,
            Status status,
            Basis basis,
            LocalDate startsOn,
            LocalDate endsOn,
            boolean setup,
            LocalDate suggestedAsOf,
            LocalDate activatedOn) {

        static PlanView of(Plan plan) {
            return new PlanView(
                    plan.id(), plan.year(), plan.status(), plan.basis(), plan.startsOn(), plan.endsOn(), plan.setup(),
                    plan.suggestedAsOf(), plan.activatedOn());
        }
    }

    /**
     * Every plan, and what can be started.
     *
     * @param nextYear     the year a new plan would be of
     * @param nextStartsOn the day it would start: the next budget year start after today
     * @param canPlan      true when a plan of that year can be started: none exists, nothing else is in draft,
     *                     and there is an income source to give it pay periods
     * @param canSetUp     true in an app with nothing in it yet, where first-time setup is offered
     * @param reminderDue  true from the plan reminder day until next year has a plan
     */
    public record Overview(
            List<PlanView> plans, int nextYear, LocalDate nextStartsOn, boolean canPlan, boolean canSetUp, boolean reminderDue) {}

    /**
     * One line of a step. Amounts of an item are totals for the plan's year; of an income source,
     * take-home pay for one paycheck, with {@code yearCents} beside them for adding up.
     *
     * @param kind              {@code item} or {@code income}
     * @param step              the step it is listed in
     * @param itemKind         the recurring item's kind; {@code null} for income
     * @param frequency         how often it comes round
     * @param last12Cents       what it came to in the 12 months the suggestions look at; {@code null} without that much history
     * @param trendBp           those 12 months against the 12 before, in basis points; {@code null} with nothing to compare
     * @param reason            why the suggestion is what it is
     * @param currentCents      what it comes to at the amount already set for the plan's first day
     * @param chosenCents       the plan's amount: what was typed, or else the suggestion
     * @param yours             true when the amount was typed in
     * @param perOccurrenceCents the plan's amount for one bill, one budget line, one transfer or one paycheck
     * @param occurrences       how many of those the plan's year holds, by its schedule
     * @param perPaycheckCents  an item's amount shared over the year's paydays: what saving up for it takes
     * @param savedUpFor        true for a bill that is saved up for in a bucket
     * @param yearCents         the plan's amount over the whole year
     * @param currentYearCents  {@code currentCents} over the whole year
     */
    public record Row(
            String kind,
            Step step,
            long id,
            String name,
            ItemKind itemKind,
            Frequency frequency,
            Long last12Cents,
            Integer trendBp,
            long suggestedCents,
            Reason reason,
            long currentCents,
            long chosenCents,
            boolean yours,
            long perOccurrenceCents,
            int occurrences,
            long perPaycheckCents,
            boolean savedUpFor,
            long yearCents,
            long currentYearCents) {

        boolean isItem() {
            return ITEM.equals(kind);
        }

        boolean changes() {
            return chosenCents != currentCents;
        }
    }

    /**
     * One step with its lines and what they add up to over the plan's year.
     *
     * @param last12Cents {@code null} when no line has that much history
     * @param trendBp     the lines that have a trend, taken together; {@code null} when none has
     * @param yours       how many amounts were typed in
     * @param changes     how many differ from what is set now
     */
    public record StepView(
            Step step, List<Row> rows, Long last12Cents, Integer trendBp, long suggestedCents, long chosenCents, long currentCents,
            int yours, int changes) {}

    /**
     * A plan in full.
     *
     * @param historyFrom the first day of the 12 months the suggestions look at
     * @param historyTo   their last day
     * @param stale       true when a month has ended since the suggestions were worked out, so newer ones can be had
     * @param paydays     how many paydays the plan's year holds
     * @param incomeCents take-home pay over the plan's year
     * @param outCents    bills, annual costs, budgets and saving over the plan's year
     */
    public record PlanDetail(
            PlanView plan, LocalDate historyFrom, LocalDate historyTo, boolean stale, int paydays, List<StepView> steps,
            long incomeCents, long outCents) {}

    /** What Plan next year would suggest for one bill or budget, for the screens that mention it. */
    public record ItemSuggestion(int year, Basis basis, long perYearCents, long perMonthCents, Reason reason, Integer trendBp) {}

    static final String ITEM = "item";
    static final String INCOME = "income";

    public Overview overview() {
        LocalDate today = pay.today();
        LocalDate next = nextStart(today);
        List<Plan> all = plans.all();
        boolean nextPlanned = all.stream().anyMatch(plan -> plan.startsOn().equals(next));
        boolean drafting = all.stream().anyMatch(plan -> plan.status() == Status.DRAFT);
        boolean hasIncome = income.count() > 0;
        return new Overview(
                all.stream().map(PlanView::of).toList(),
                next.getYear(),
                next,
                !nextPlanned && !drafting && hasIncome,
                all.isEmpty() && !hasIncome && items.all().isEmpty(),
                reminderDue(today, next, nextPlanned) && hasIncome);
    }

    /** True from the reminder day of the budget year in hand until next year has a plan. */
    private boolean reminderDue(LocalDate today, LocalDate next, boolean nextPlanned) {
        if (nextPlanned) {
            return false;
        }
        MonthDay reminder = monthDay(SettingKey.PLAN_REMINDER);
        // The reminder day that falls inside the budget year now under way.
        LocalDate yearStart = next.minusYears(1);
        LocalDate day = reminder.atYear(yearStart.getYear());
        if (day.isBefore(yearStart)) {
            day = reminder.atYear(yearStart.getYear() + 1);
        }
        return !today.isBefore(day);
    }

    /** The plan made active in the last {@link #ACTIVE_NOTICE_DAYS} days, for the Dashboard to mention. */
    public Optional<PlanView> newlyActive() {
        LocalDate today = pay.today();
        return plans.all().stream()
                .filter(plan -> plan.status() == Status.ACTIVE && !plan.setup() && plan.activatedOn() != null
                        && !plan.activatedOn().isBefore(today.minusDays(ACTIVE_NOTICE_DAYS)))
                .findFirst()
                .map(PlanView::of);
    }

    /** Starts next year's plan as a draft and returns its id. */
    public long startNextYear() {
        requireNoDraft();
        if (income.count() == 0) {
            throw ApiException.conflict("A plan follows your paydays. Please add your income first.");
        }
        LocalDate today = pay.today();
        LocalDate startsOn = nextStart(today);
        if (plans.all().stream().anyMatch(plan -> plan.startsOn().equals(startsOn))) {
            throw ApiException.conflict("There is already a plan for " + startsOn.getYear() + ".");
        }
        return plans.create(Basis.LAST_12_TREND, startsOn, today, false);
    }

    /** Starts first-time setup: a draft of the budget year already under way. Returns its id. */
    public long startSetup() {
        requireNoDraft();
        if (!plans.all().isEmpty()) {
            throw ApiException.conflict("Budget is already set up. You can plan next year instead.");
        }
        LocalDate today = pay.today();
        return plans.create(Basis.LAST_12_TREND, nextStart(today).minusYears(1), today, true);
    }

    private void requireNoDraft() {
        plans.draft().ifPresent(draft -> {
            throw ApiException.conflict(draft.setup()
                    ? "Please finish setting up first."
                    : "The " + draft.year() + " plan is still a draft. Finish it, or delete it and start over.");
        });
    }

    public Optional<PlanDetail> find(long id) {
        return plans.find(id).map(this::detail);
    }

    /** Changes what a draft's suggestions are worked out from; {@code refresh} also brings them up to today. */
    public void setBasis(long id, Basis basis, boolean refresh) {
        Plan plan = draft(id);
        plans.setBasis(id, basis, refresh ? pay.today() : plan.suggestedAsOf());
    }

    /**
     * Types an amount into a draft, or with {@code null} takes it out again so the line follows its
     * suggestion.
     *
     * @param kind {@code item} or {@code income}
     */
    public void setAmount(long id, String kind, long rowId, Long amountCents) {
        Plan plan = planning(id);
        Row row = rows(plan).stream()
                .filter(candidate -> candidate.kind().equals(kind) && candidate.id() == rowId)
                .findFirst()
                .orElseThrow(ApiException::notFound);
        Long itemId = row.isItem() ? (Long) rowId : null;
        Long sourceId = row.isItem() ? null : (Long) rowId;
        if (amountCents == null) {
            plans.clear(id, itemId, sourceId);
        } else {
            plans.put(id, new Amount(itemId, sourceId, row.suggestedCents(), Inputs.amount(amountCents, "The amount"), true));
        }
    }

    /** Takes every typed amount out of one step, or of all of them when {@code step} is {@code null}. */
    public void acceptSuggestions(long id, Step step) {
        Plan plan = planning(id);
        for (Row row : rows(plan)) {
            if (row.yours() && (step == null || row.step() == step)) {
                plans.clear(id, row.isItem() ? (Long) row.id() : null, row.isItem() ? null : (Long) row.id());
            }
        }
    }

    /** Deletes a draft. Nothing it held had taken effect. */
    public void deleteDraft(long id) {
        draft(id);
        plans.delete(id);
    }

    /** Ends first-time setup: its plan becomes the active one. Nothing else changes, as setup adds things directly. */
    public void finishSetup(long id) {
        Plan plan = draft(id);
        if (!plan.setup()) {
            throw ApiException.conflict("This plan becomes active by itself on the day its year starts.");
        }
        plans.activate(id, pay.today());
    }

    /**
     * Puts a draft into force once its first day has come: every amount in it becomes what its
     * item or paycheck comes to from that day on, and the plan before it is closed. Earlier
     * dates keep what they had. Does nothing, and returns false, when no draft is due.
     */
    public boolean activateDue() {
        LocalDate today = pay.today();
        Optional<Plan> due = plans.draft().filter(plan -> !plan.setup() && !plan.startsOn().isAfter(today));
        if (due.isEmpty()) {
            return false;
        }
        Plan plan = due.get();
        // One transaction: a plan is in force whole or not at all.
        jdbi.useTransaction(handle -> {
            Map<Long, Item> itemById = new HashMap<>();
            items.all().forEach(item -> itemById.put(item.id(), item));
            for (Row row : rows(plan)) {
                Long itemId = row.isItem() ? (Long) row.id() : null;
                Long sourceId = row.isItem() ? null : (Long) row.id();
                plans.put(plan.id(), new Amount(itemId, sourceId, row.suggestedCents(), row.chosenCents(), row.yours()));
                if (!row.changes()) {
                    continue;
                }
                if (row.isItem()) {
                    Terms terms = itemById.get(row.id()).versionOn(plan.startsOn()).terms();
                    long amount = terms.amountMode() == AmountMode.ANNUAL ? row.chosenCents() : row.perOccurrenceCents();
                    items.changeAmountFrom(row.id(), plan.startsOn(), amount);
                } else {
                    Source source = income.find(row.id()).orElseThrow();
                    Pay now = source.termsOn(plan.startsOn()).pay();
                    // A planned take-home amount is an estimate until a pay stub says what it really is.
                    Pay planned = new Pay(now.grossCents(), row.chosenCents(), true, now.deductions(), now.match());
                    income.updateFrom(
                            source.id(),
                            new IncomeRepository.Fields(source.name(), source.depositAccountId(), source.primary(), source.schedule(), planned),
                            plan.startsOn());
                }
            }
            plans.activate(plan.id(), today);
        });
        log.info("The {} plan is now active", plan.year());
        return true;
    }

    /** What a plan would suggest for one bill or budget next year; empty for anything else. */
    public Optional<ItemSuggestion> suggestionFor(Item item) {
        if (item.archived() || (item.kind() != ItemKind.BILL && item.kind() != ItemKind.BUDGET)) {
            return Optional.empty();
        }
        LocalDate today = pay.today();
        // The draft in hand, if there is one, so this says the same as the plan does.
        Plan plan = plans.draft()
                .filter(draft -> !draft.setup())
                .orElseGet(() -> new Plan(0, nextStart(today).getYear(), Status.DRAFT, Basis.LAST_12_TREND, nextStart(today), today, false, null));
        return itemRows(plan, List.of(item)).stream()
                .filter(row -> row.reason() != Reason.FIXED)
                .findFirst()
                .map(row -> new ItemSuggestion(
                        plan.year(), plan.basis(), row.suggestedCents(), Math.round(row.suggestedCents() / 12.0), row.reason(), row.trendBp()));
    }

    private Plan draft(long id) {
        Plan plan = plans.find(id).orElseThrow(ApiException::notFound);
        if (plan.status() != Status.DRAFT) {
            throw ApiException.conflict("The " + plan.year() + " plan is already in use, so it can no longer be changed here.");
        }
        return plan;
    }

    /** A draft of a coming year: the kind amounts can be typed into. */
    private Plan planning(long id) {
        Plan plan = draft(id);
        if (plan.setup()) {
            throw ApiException.conflict("While setting up, amounts are changed on the item itself.");
        }
        return plan;
    }

    private PlanDetail detail(Plan plan) {
        List<Row> rows = plan.status() == Status.DRAFT ? rows(plan) : recorded(plan);
        List<StepView> steps = new ArrayList<>();
        for (Step step : Step.values()) {
            steps.add(stepView(step, rows.stream().filter(row -> row.step() == step).toList()));
        }
        Window last12 = PlanSuggestions.rollingWindows(plan.suggestedAsOf()).getLast();
        long incomeCents = steps.getFirst().chosenCents();
        long outCents = steps.stream().skip(1).mapToLong(StepView::chosenCents).sum();
        boolean stale = plan.status() == Status.DRAFT && !plan.setup()
                && YearMonth.from(plan.suggestedAsOf()).isBefore(YearMonth.from(pay.today()));
        return new PlanDetail(PlanView.of(plan), last12.from(), last12.to(), stale, paydays(plan), steps, incomeCents, outCents);
    }

    private static StepView stepView(Step step, List<Row> rows) {
        long last12 = 0;
        long before = 0;
        boolean anyHistory = false;
        for (Row row : rows) {
            if (row.last12Cents() != null) {
                anyHistory = true;
                last12 += row.last12Cents();
            }
            if (row.last12Cents() != null && row.trendBp() != null) {
                // What the 12 months before came to, back from the trend: last12 ÷ (1 + trend).
                before += Math.round(row.last12Cents() * 10_000.0 / (10_000 + row.trendBp()));
            }
        }
        long trended = rows.stream().filter(row -> row.last12Cents() != null && row.trendBp() != null).mapToLong(Row::last12Cents).sum();
        Integer trendBp = before > 0 ? (Integer) (int) Math.round((trended - before) * 10_000.0 / before) : null;
        return new StepView(
                step,
                rows,
                anyHistory ? (Long) last12 : null,
                trendBp,
                rows.stream().mapToLong(row -> row.isItem() ? row.suggestedCents() : row.suggestedCents() * row.occurrences()).sum(),
                rows.stream().mapToLong(Row::yearCents).sum(),
                rows.stream().mapToLong(Row::currentYearCents).sum(),
                (int) rows.stream().filter(Row::yours).count(),
                (int) rows.stream().filter(Row::changes).count());
    }

    /** Every line of a draft as things stand today: income first, then items by name. */
    private List<Row> rows(Plan plan) {
        List<Row> rows = new ArrayList<>(incomeRows(plan));
        List<Item> planned = items.all().stream().filter(item -> isPlanned(item, plan)).toList();
        rows.addAll(itemRows(plan, planned));
        return rows;
    }

    /** A plan that is no longer a draft shows what it set, not what the items have become since. */
    private List<Row> recorded(Plan plan) {
        Map<Long, Amount> byItem = new HashMap<>();
        Map<Long, Amount> bySource = new HashMap<>();
        for (Amount amount : plans.amounts(plan.id())) {
            if (amount.itemId() != null) {
                byItem.put(amount.itemId(), amount);
            } else {
                bySource.put(amount.incomeSourceId(), amount);
            }
        }
        List<Row> rows = new ArrayList<>();
        for (Row row : rows(plan)) {
            Amount amount = row.isItem() ? byItem.get(row.id()) : bySource.get(row.id());
            if (amount == null) {
                // Added since the plan took effect, or by first-time setup: it is what it is.
                rows.add(withChosen(row, row.currentCents(), row.currentCents(), false));
            } else {
                rows.add(withChosen(row, amount.suggestedCents(), amount.chosenCents(), amount.byUser()));
            }
        }
        return rows;
    }

    /** Recurring items a plan covers: still running in its year, and an amount someone sets. */
    private boolean isPlanned(Item item, Plan plan) {
        if (item.archived() || item.kind() == ItemKind.CARD_PAYMENT) {
            // A card payment follows its card; there is no yearly amount to plan.
            return false;
        }
        ScheduleRule schedule = item.versionOn(plan.startsOn()).terms().schedule();
        boolean over = schedule.endDate() != null && schedule.endDate().isBefore(plan.startsOn());
        boolean notYet = schedule.startDate().isAfter(plan.endsOn());
        return schedule.frequency() != Frequency.ONE_TIME && !over && !notYet;
    }

    /** Which step a recurring item is planned in. */
    private static Step stepOf(Item item, Terms terms, Set<Long> loans) {
        Frequency frequency = terms.schedule().frequency();
        return switch (item.kind()) {
            case BUDGET -> Step.BUDGETS;
            case BILL -> terms.amountMode() == AmountMode.ANNUAL || terms.saveUpBucketId() != null
                            || frequency == Frequency.YEARLY || frequency == Frequency.EVERY_N_MONTHS
                    ? Step.ANNUAL
                    : Step.BILLS;
            // A loan's payment is a bill in all but name: it is owed, at an amount someone else set.
            case TRANSFER -> terms.payToAccountId() != null && loans.contains(terms.payToAccountId()) ? Step.BILLS : Step.SAVING;
            case BUCKET_MOVE, CARD_PAYMENT -> Step.SAVING;
        };
    }

    private List<Row> incomeRows(Plan plan) {
        Map<Long, Amount> typed = new HashMap<>();
        plans.amounts(plan.id()).stream()
                .filter(amount -> amount.incomeSourceId() != null && amount.byUser())
                .forEach(amount -> typed.put(amount.incomeSourceId(), amount));
        Window last12 = PlanSuggestions.rollingWindows(plan.suggestedAsOf()).getLast();
        Map<Long, Long> received = new HashMap<>();
        for (Paycheck paycheck : pay.paychecks(last12.from(), last12.to())) {
            received.merge(paycheck.incomeSourceId(), paycheck.netCents(), Long::sum);
        }
        Map<Long, Integer> paychecksInYear = new HashMap<>();
        for (Paycheck paycheck : pay.paychecks(plan.startsOn(), plan.endsOn())) {
            // A plan's amount is take-home per regular paycheck; a one-off (a bonus) is not one of them.
            if (paycheck.scheduled()) {
                paychecksInYear.merge(paycheck.incomeSourceId(), 1, Integer::sum);
            }
        }

        List<Row> rows = new ArrayList<>();
        for (Source source : income.all()) {
            ScheduleRule schedule = source.schedule();
            if (schedule.endDate() != null && schedule.endDate().isBefore(plan.startsOn())) {
                continue;
            }
            long current = source.termsOn(plan.startsOn()).pay().netCents();
            Amount amount = typed.get(source.id());
            long chosen = amount == null ? current : amount.chosenCents();
            int paychecks = paychecksInYear.getOrDefault(source.id(), 0);
            boolean fullYear = !YearMonth.from(schedule.startDate()).isAfter(YearMonth.from(last12.from()));
            rows.add(new Row(
                    INCOME, Step.INCOME, source.id(), source.name(), null, schedule.frequency(),
                    fullYear ? received.getOrDefault(source.id(), 0L) : null,
                    null, current, Reason.FIXED, current, chosen, amount != null,
                    chosen, paychecks, chosen, false, chosen * paychecks, current * paychecks));
        }
        return rows;
    }

    private List<Row> itemRows(Plan plan, List<Item> planned) {
        Map<Long, Amount> typed = new HashMap<>();
        plans.amounts(plan.id()).stream()
                .filter(amount -> amount.itemId() != null && amount.byUser())
                .forEach(amount -> typed.put(amount.itemId(), amount));

        List<Window> rolling = PlanSuggestions.rollingWindows(plan.suggestedAsOf());
        Window before12 = rolling.getFirst();
        Window last12 = rolling.getLast();
        Window priorYear = PlanSuggestions.priorYear(plan.suggestedAsOf());
        LocalDate from = before12.from().isBefore(priorYear.from()) ? before12.from() : priorYear.from();
        Map<Long, long[]> sums = new HashMap<>();
        for (Occurrence occurrence : calendar.expand(planned, from, last12.to()).occurrences()) {
            if (!AnnualCostsRunner.counts(occurrence)) {
                continue;
            }
            LocalDate date = AnnualCostsRunner.date(occurrence);
            long[] sum = sums.computeIfAbsent(occurrence.itemId(), unused -> new long[3]);
            sum[0] += holds(last12, date) ? occurrence.amountCents() : 0;
            sum[1] += holds(before12, date) ? occurrence.amountCents() : 0;
            sum[2] += holds(priorYear, date) ? occurrence.amountCents() : 0;
        }

        ScheduleRule payRule = pay.payRule().orElse(null);
        int paydays = paydays(plan);
        Set<Long> loans = new HashSet<>();
        accounts.all().stream()
                .filter(account -> account.type().equals(Inputs.code(AccountType.LOAN)))
                .forEach(account -> loans.add(account.id()));

        List<Row> rows = new ArrayList<>();
        for (Item item : planned) {
            Terms terms = item.versionOn(plan.startsOn()).terms();
            AnnualAmount.PerYear perYear = AnnualAmount.perYear(terms.schedule(), payRule, plan.startsOn().getYear());
            Money set = Money.ofCents(terms.amountCents());
            // Billed by use is a cost each time at about so many times, which the calendar turns into a year.
            long current = terms.billedByUse()
                    ? calendar.amounts(terms).perYearCents()
                    : (terms.amountMode() == AmountMode.ANNUAL ? set : AnnualAmount.yearly(set, perYear)).cents();

            boolean cost = item.kind() == ItemKind.BILL || item.kind() == ItemKind.BUDGET;
            ScheduleRule first = item.versions().getFirst().terms().schedule();
            YearMonth began = YearMonth.from(first.startDate());
            if (first.frequency() == Frequency.EVERY_PAY_PERIOD && payRule != null && YearMonth.from(payRule.startDate()).isAfter(began)) {
                // It comes round with each payday, so there is none of it before the paydays themselves begin.
                began = YearMonth.from(payRule.startDate());
            }
            long[] sum = sums.getOrDefault(item.id(), new long[3]);
            // History counts from the month an item began: a bill added in March has no January to compare.
            Long last = cost && !began.isAfter(YearMonth.from(last12.from())) ? (Long) sum[0] : null;
            Long before = cost && !began.isAfter(YearMonth.from(before12.from())) ? (Long) sum[1] : null;
            Long prior = cost && !began.isAfter(YearMonth.from(priorYear.from())) ? (Long) sum[2] : null;
            Suggestion suggestion = PlanSuggestions.suggest(plan.basis(), new History(
                    money(last), money(before), money(prior), Money.ofCents(current), !cost));

            Amount amount = typed.get(item.id());
            // First-time setup plans nothing: every amount is the one the item already has.
            long suggested = plan.setup() ? current : suggestion.amount().cents();
            long chosen = plan.setup() ? current : amount == null ? suggested : amount.chosenCents();
            int occurrences = Math.max(1, Math.round((float) perYear.occurrences() / perYear.years()));
            rows.add(new Row(
                    ITEM, stepOf(item, terms, loans), item.id(), item.name(), item.kind(), terms.schedule().frequency(),
                    last, suggestion.trendBp(), suggested, suggestion.reason(), current, chosen, amount != null && !plan.setup(),
                    // Billed by use, the plan changes what each time costs, in step with the year.
                    terms.billedByUse()
                            ? (current == 0 ? set.cents() : set.times(BigDecimal.valueOf(chosen).divide(BigDecimal.valueOf(current), 20, Money.ROUNDING)).cents())
                            : AnnualAmount.perOccurrence(Money.ofCents(chosen), perYear).cents(),
                    occurrences,
                    paydays == 0 ? 0 : Math.round((double) chosen / paydays),
                    terms.saveUpBucketId() != null,
                    chosen, current));
        }
        rows.sort(Comparator.comparing(Row::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Row::id));
        return rows;
    }

    private static Row withChosen(Row row, long suggested, long chosen, boolean yours) {
        long year = row.isItem() ? chosen : chosen * row.occurrences();
        long perOccurrence = row.isItem() && row.yearCents() > 0
                ? Math.round((double) row.perOccurrenceCents() * chosen / row.yearCents())
                : chosen;
        return new Row(
                row.kind(), row.step(), row.id(), row.name(), row.itemKind(), row.frequency(), row.last12Cents(), row.trendBp(), suggested,
                row.reason(), row.currentCents(), chosen, yours, perOccurrence, row.occurrences(), row.perPaycheckCents(),
                row.savedUpFor(), year, row.currentYearCents());
    }

    /** How many paydays of the primary income fall in the plan's year. */
    private int paydays(Plan plan) {
        int count = 0;
        for (PayPeriod period : pay.payPeriods(plan.startsOn(), plan.endsOn())) {
            if (!period.start().isBefore(plan.startsOn()) && !period.start().isAfter(plan.endsOn())) {
                count++;
            }
        }
        return count;
    }

    /** The first budget year start after {@code today}. */
    private LocalDate nextStart(LocalDate today) {
        MonthDay start = monthDay(SettingKey.BUDGET_YEAR_START);
        LocalDate thisYear = start.atYear(today.getYear());
        return thisYear.isAfter(today) ? thisYear : start.atYear(today.getYear() + 1);
    }

    private MonthDay monthDay(SettingKey setting) {
        return MonthDay.parse("--" + settings.get(setting));
    }

    private static boolean holds(Window window, LocalDate date) {
        return !date.isBefore(window.from()) && !date.isAfter(window.to());
    }

    private static Money money(Long cents) {
        return cents == null ? null : Money.ofCents(cents);
    }
}
