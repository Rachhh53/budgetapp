package app.budget.engine.statements;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.schedule.PayPeriod;
import app.budget.engine.statements.BudgetActuals.BudgetDate;
import app.budget.engine.statements.BudgetActuals.Charge;
import app.budget.engine.statements.BudgetActuals.Covered;
import app.budget.engine.statements.BudgetActuals.Result;
import app.budget.engine.statements.BudgetActuals.Span;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A budget becoming what was really spent, from statement charges put on it (Rachel, 2026-10-30), with what is still to
 * come for days no statement covers yet. Her example: a pay period of Jan 1-15, a weekly Food budget from Jan 1, and a
 * statement through Jan 7.
 */
class BudgetActualsTest {

    private static final long FOOD = 21;
    private static final long FUEL = 22;
    private static final PayPeriod JANUARY = new PayPeriod(day(1), day(15), day(1));
    private static final PayPeriod LATE_JANUARY = new PayPeriod(day(16), day(31), day(16));
    private static final List<PayPeriod> PERIODS = List.of(JANUARY, LATE_JANUARY);

    private static LocalDate day(int day) {
        return LocalDate.of(2027, 1, day);
    }

    private static BudgetDate food(int day, long budgeted) {
        return new BudgetDate(FOOD, day(day), day <= 15 ? day(1) : day(16), day(day), budgeted, budgeted, null, false);
    }

    private static final List<BudgetDate> WEEKLY_FOOD = List.of(food(1, 15000), food(8, 15000), food(15, 15000), food(22, 15000), food(29, 15000));

    private static final long DISCOVER = 4;
    private static final long AMAZON = 5;

    /** Household, $200 a pay period, paid from Discover. */
    private static final BudgetDate HOUSEHOLD = new BudgetDate(FUEL, day(1), day(1), day(1), 20000, 20000, null, false, DISCOVER);

    @Test
    void chargesFromAnotherCardTakeOffStillToComeAndAreKeptApart() {
        // Rachel, 2026-10-30: Amazon card charges put on a Discover budget lower it, so the total stays what was budgeted.
        List<Charge> charges = List.of(new Charge(1, day(3), 3000, FUEL, AMAZON), new Charge(2, day(9), 2000, FUEL, AMAZON),
                new Charge(3, day(4), 4000, FUEL, DISCOVER));
        Result result = BudgetActuals.work(charges, PERIODS, List.of(HOUSEHOLD),
                java.util.Map.of(DISCOVER, List.of(new Span(day(1), day(7))), AMAZON, List.of(new Span(day(1), day(15))))).getFirst();

        // Discover's statement covers only the first week, so the rest is still to come: $200 less $40 and $50.
        assertThat(result.covered()).isEqualTo(Covered.PARTLY);
        assertThat(result.actualCents()).isEqualTo(4000);
        assertThat(result.elsewhereCents()).isEqualTo(5000);
        assertThat(result.stillToComeCents()).isEqualTo(11000);
        assertThat(result.newCents()).isEqualTo(15000);
        assertThat(result.newCents() + result.elsewhereCents()).isEqualTo(20000);
        assertThat(result.elsewhere()).singleElement().satisfies(amazon -> {
            assertThat(amazon.accountId()).isEqualTo(AMAZON);
            assertThat(amazon.cents()).isEqualTo(5000);
            assertThat(amazon.lastDay()).isEqualTo(day(9));
            assertThat(amazon.lineIds()).containsExactly(1L, 2L);
        });
        assertThat(result.lineIds()).containsExactly(1L, 2L, 3L);
    }

    @Test
    void onceTheBudgetsOwnStatementsAreInItIsWhatWasSpentThereAndCanStillTakeMore() {
        List<Charge> charges = new java.util.ArrayList<>(List.of(new Charge(1, day(3), 3000, FUEL, AMAZON), new Charge(3, day(4), 4000, FUEL, DISCOVER)));
        java.util.Map<Long, List<Span>> spans = java.util.Map.of(DISCOVER, List.of(new Span(day(1), day(15))));
        Result covered = BudgetActuals.work(charges, PERIODS, List.of(HOUSEHOLD), spans).getFirst();
        assertThat(covered.covered()).isEqualTo(Covered.FULLY);
        assertThat(covered.stillToComeCents()).isZero();
        assertThat(covered.newCents()).isEqualTo(4000);
        assertThat(covered.elsewhereCents()).isEqualTo(3000);

        // Rachel: "even after those statements are in, I can still add actuals to the budget".
        charges.add(new Charge(4, day(12), 1500, FUEL, AMAZON));
        Result more = BudgetActuals.work(charges, PERIODS, List.of(HOUSEHOLD), spans).getFirst();
        assertThat(more.newCents()).isEqualTo(4000);
        assertThat(more.elsewhereCents()).isEqualTo(4500);
    }

    @Test
    void aBudgetWithOnlyAnotherCardsChargesStillCountsThem() {
        Result result = BudgetActuals.work(List.of(new Charge(1, day(3), 6000, FUEL, AMAZON)), PERIODS, List.of(HOUSEHOLD),
                java.util.Map.of()).getFirst();
        assertThat(result.actualCents()).isZero();
        assertThat(result.stillToComeCents()).isEqualTo(14000);
        assertThat(result.newCents()).isEqualTo(14000);
        assertThat(result.elsewhereCents()).isEqualTo(6000);
    }

    @Test
    void aDateTheStatementCoversBecomesWhatWasSpentAndOneItDoesNotKeepsItsBudget() {
        List<Charge> charges = List.of(new Charge(1, day(2), 5420, FOOD), new Charge(2, day(6), 2580, FOOD));
        List<Result> results = BudgetActuals.work(charges, PERIODS, WEEKLY_FOOD, List.of(new Span(day(1), day(7))));

        assertThat(results).extracting(Result::scheduledDate).containsExactly(day(1), day(8), day(15));
        Result first = results.get(0);
        assertThat(first.covered()).isEqualTo(Covered.FULLY);
        assertThat(first.actualCents()).isEqualTo(8000);
        assertThat(first.stillToComeCents()).isZero();
        assertThat(first.newCents()).isEqualTo(8000);
        assertThat(first.lineIds()).containsExactly(1L, 2L);
        // Jan 8: no statement yet, so the budget stays as still to come on top of nothing spent.
        Result second = results.get(1);
        assertThat(second.covered()).isEqualTo(Covered.NONE);
        assertThat(second.actualCents()).isZero();
        assertThat(second.stillToComeCents()).isEqualTo(15000);
        assertThat(second.newCents()).isEqualTo(15000);
        // The period's last date takes the days up to the period's end.
        assertThat(results.get(2).from()).isEqualTo(day(15));
        assertThat(results.get(2).to()).isEqualTo(day(15));
    }

    @Test
    void aDatePartlyCoveredIsWhatWasSpentPlusTheBudgetLeftOrWhatWasTyped() {
        List<Charge> charges = List.of(new Charge(1, day(9), 6000, FOOD));
        List<Span> spans = List.of(new Span(day(1), day(10)));
        Result partly = BudgetActuals.work(charges, PERIODS, WEEKLY_FOOD, spans).get(1);
        assertThat(partly.covered()).isEqualTo(Covered.PARTLY);
        assertThat(partly.coveredThrough()).isEqualTo(day(10));
        assertThat(partly.stillToComeCents()).isEqualTo(9000);
        assertThat(partly.newCents()).isEqualTo(15000);
        assertThat(partly.stillTyped()).isFalse();

        // Typed, still to come is what was typed; once spending passes the budget it adds nothing.
        List<BudgetDate> typed = List.of(food(1, 15000), new BudgetDate(FOOD, day(8), day(1), day(8), 15000, 15000, 2000L, false));
        Result withTyped = BudgetActuals.work(charges, PERIODS, typed, spans).get(1);
        assertThat(withTyped.stillToComeCents()).isEqualTo(2000);
        assertThat(withTyped.newCents()).isEqualTo(8000);
        assertThat(withTyped.stillTyped()).isTrue();
        Result over = BudgetActuals.work(List.of(new Charge(1, day(9), 20000, FOOD)), PERIODS, WEEKLY_FOOD, spans).get(1);
        assertThat(over.stillToComeCents()).isZero();
        assertThat(over.newCents()).isEqualTo(20000);
    }

    @Test
    void aLaterStatementCoveringTheRestTakesStillToComeToNothingEvenWhenTyped() {
        List<BudgetDate> typed = List.of(food(1, 15000), new BudgetDate(FOOD, day(8), day(1), day(8), 15000, 15000, 2000L, false));
        List<Charge> charges = List.of(new Charge(1, day(9), 6000, FOOD), new Charge(2, day(12), 3000, FOOD));
        // Two statements of the account add together: through the 10th, then from the 11th.
        Result result = BudgetActuals.work(charges, PERIODS, typed, List.of(new Span(day(1), day(10)), new Span(day(11), day(31)))).get(1);
        assertThat(result.covered()).isEqualTo(Covered.FULLY);
        assertThat(result.actualCents()).isEqualTo(9000);
        assertThat(result.stillToComeCents()).isZero();
        assertThat(result.newCents()).isEqualTo(9000);
    }

    @Test
    void aBudgetWithNoChargesIsLeftAloneAndOneWhoseChargesWereTakenOffGoesBackToItsBudget() {
        BudgetDate fuel = new BudgetDate(FUEL, day(1), day(1), day(1), 20000, 20000, null, false);
        List<Result> nothing = BudgetActuals.work(List.of(), PERIODS, List.of(fuel), List.of(new Span(day(1), day(15))));
        assertThat(nothing).isEmpty();

        // A statement set it to $38 before; with its charge taken off, it goes back to the $200 budgeted.
        BudgetDate applied = new BudgetDate(FUEL, day(1), day(1), day(1), 3800, 20000, null, true);
        assertThat(BudgetActuals.work(List.of(), PERIODS, List.of(applied), List.of(new Span(day(1), day(15))))).singleElement()
                .satisfies(result -> {
                    assertThat(result.currentCents()).isEqualTo(3800);
                    assertThat(result.newCents()).isEqualTo(20000);
                    assertThat(result.lineIds()).isEmpty();
                });
    }

    @Test
    void chargesGoToTheDateTheirDayFallsUnderInTheirOwnPayPeriod() {
        assertThat(BudgetActuals.dateFor(day(9), FOOD, PERIODS, WEEKLY_FOOD)).hasValueSatisfying(date -> assertThat(date.scheduledDate()).isEqualTo(day(8)));
        assertThat(BudgetActuals.dateFor(day(16), FOOD, PERIODS, WEEKLY_FOOD)).hasValueSatisfying(date -> assertThat(date.scheduledDate()).isEqualTo(day(22)));
        assertThat(BudgetActuals.dateFor(day(9), FUEL, PERIODS, WEEKLY_FOOD)).isEmpty();
        assertThat(BudgetActuals.dateFor(LocalDate.of(2027, 2, 1), FOOD, PERIODS, WEEKLY_FOOD)).isEmpty();
    }

    @Test
    void moneyBackComesOffWhatWasSpentButNeverBelowNothing() {
        List<Charge> charges = List.of(new Charge(1, day(2), 5000, FOOD), new Charge(2, day(3), -7000, FOOD));
        Result result = BudgetActuals.work(charges, PERIODS, WEEKLY_FOOD, List.of(new Span(day(1), day(31)))).getFirst();
        assertThat(result.actualCents()).isEqualTo(-2000);
        assertThat(result.newCents()).isZero();
    }

    @Test
    void theStatementsCoverAPeriodTogether() {
        assertThat(BudgetActuals.coverage(PERIODS, List.of(new Span(day(1), day(7)), new Span(day(8), day(20)))))
                .extracting(BudgetActuals.Coverage::partly)
                .containsExactly(false, true);
    }

    @Test
    void theRowsOfAStatementAddUpToItsBalancesOrSayByHowMuchNot() {
        // A card: owed $1,000, then $54.20 spent, $12.10 back and a $500 payment leaves $542.10 owed.
        List<Long> rows = List.of(5420L, -1210L, -50000L);
        StatementCheck.Result card = StatementCheck.of(true, 100000L, 54210L, rows);
        assertThat(card.spentCents()).isEqualTo(5420);
        assertThat(card.backCents()).isEqualTo(51210);
        assertThat(card.expectedNewCents()).isEqualTo(54210);
        assertThat(card.differenceCents()).isZero();
        // A bank account: what it holds goes down with spending; a row the reader missed shows as the difference.
        StatementCheck.Result bank = StatementCheck.of(false, 100000L, 93340L, List.of(5420L));
        assertThat(bank.expectedNewCents()).isEqualTo(94580);
        assertThat(bank.differenceCents()).isEqualTo(-1240);
        // Without a balance there is nothing to check against.
        assertThat(StatementCheck.of(true, null, 54210L, rows).differenceCents()).isNull();
    }
}
