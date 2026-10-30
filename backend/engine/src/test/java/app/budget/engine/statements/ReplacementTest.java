package app.budget.engine.statements;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.schedule.PayPeriod;
import app.budget.engine.statements.Replacement.Change;
import app.budget.engine.statements.Replacement.Charge;
import app.budget.engine.statements.Replacement.Coverage;
import app.budget.engine.statements.Replacement.Group;
import app.budget.engine.statements.Replacement.GroupTotal;
import app.budget.engine.statements.Replacement.GroupType;
import app.budget.engine.statements.Replacement.Mode;
import app.budget.engine.statements.Replacement.Plan;
import app.budget.engine.statements.Replacement.Slot;
import app.budget.engine.statements.Replacement.Span;
import app.budget.engine.statements.Replacement.Target;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Statement totals by pay period and what they make of the items Rachel points them at (2026-10-25). Pay periods are
 * the 1st–14th and 15th–end of October 2026.
 */
class ReplacementTest {

    private static final long GROCERIES = 1;
    private static final long GAS = 2;
    private static final long VACATION_TAG = 9;
    private static final long FOOD = 100;
    private static final long FUEL = 101;
    private static final long TRIP = 102;

    private static final LocalDate OCT1 = LocalDate.of(2026, 10, 1);
    private static final LocalDate OCT15 = LocalDate.of(2026, 10, 15);
    private static final List<PayPeriod> PERIODS = List.of(
            new PayPeriod(OCT1, LocalDate.of(2026, 10, 14), OCT1),
            new PayPeriod(OCT15, LocalDate.of(2026, 10, 31), OCT15));

    private static LocalDate oct(int day) {
        return LocalDate.of(2026, 10, day);
    }

    private static Charge charge(long id, int day, long cents, Long category, Long... tags) {
        return new Charge(id, oct(day), cents, category, List.of(tags), null);
    }

    private static Group category(long id) {
        return new Group(GroupType.CATEGORY, id);
    }

    private static Group tag(long id) {
        return new Group(GroupType.TAG, id);
    }

    /** One date of an item, at $200 before any statement. */
    private static Slot slot(long item, int day, LocalDate period) {
        return new Slot(item, oct(day), period, oct(day), 20000, 20000);
    }

    @Test
    void totalsByPeriodCategoryAndTagWithRefundsComingOff() {
        List<Charge> charges = List.of(
                charge(1, 2, 5420, GROCERIES),
                charge(2, 9, -1210, GROCERIES),
                charge(3, 16, 3800, GAS, VACATION_TAG),
                charge(4, 20, 6000, GROCERIES, VACATION_TAG),
                // In no pay period here, so in no total.
                new Charge(5, LocalDate.of(2026, 11, 2), 999, GROCERIES, List.of(), null));

        List<GroupTotal> totals = Replacement.totals(charges, PERIODS);

        assertThat(totals).containsExactly(
                new GroupTotal(OCT1, category(GROCERIES), 4210, List.of(1L, 2L)),
                new GroupTotal(OCT15, category(GAS), 3800, List.of(3L)),
                new GroupTotal(OCT15, tag(VACATION_TAG), 9800, List.of(3L, 4L)),
                new GroupTotal(OCT15, category(GROCERIES), 6000, List.of(4L)));
    }

    @Test
    void nothingChangesWithoutATarget() {
        Plan plan = Replacement.plan(
                List.of(charge(1, 2, 5420, GROCERIES)), PERIODS, List.of(), List.of(slot(FOOD, 1, OCT1)));
        assertThat(plan.changes()).isEmpty();
        assertThat(plan.unmatched()).containsExactly(1L);
    }

    @Test
    void replaceMakesTheAmountTheTotal() {
        Plan plan = Replacement.plan(
                List.of(charge(1, 2, 5420, GROCERIES), charge(2, 9, -1210, GROCERIES)),
                PERIODS,
                List.of(new Target(OCT1, category(GROCERIES), FOOD, Mode.REPLACE)),
                List.of(slot(FOOD, 1, OCT1)));
        assertThat(plan.changes()).containsExactly(new Change(FOOD, oct(1), OCT1, 20000, 4210, List.of(1L, 2L)));
    }

    @Test
    void addPutsTheTotalOnTopOfWhatItWasBeforeAnyStatement() {
        // Already changed once to $242.10; adding again starts from the $200 it was before, not from that.
        Slot changedBefore = new Slot(FOOD, oct(1), OCT1, oct(1), 24210, 20000);
        Plan plan = Replacement.plan(
                List.of(charge(1, 2, 4210, GROCERIES)),
                PERIODS,
                List.of(new Target(OCT1, category(GROCERIES), FOOD, Mode.ADD)),
                List.of(changedBefore));
        assertThat(plan.changes()).singleElement().extracting(Change::currentCents, Change::newCents).containsExactly(24210L, 24210L);
    }

    @Test
    void aWeeklyBudgetTakesTheChargesFromEachOfItsDays() {
        Plan plan = Replacement.plan(
                List.of(charge(1, 2, 1000, GROCERIES), charge(2, 8, 2500, GROCERIES), charge(3, 13, 400, GROCERIES)),
                PERIODS,
                List.of(new Target(OCT1, category(GROCERIES), FOOD, Mode.REPLACE)),
                // Dated the 3rd and the 10th: the 2nd comes before both and goes to the first.
                List.of(slot(FOOD, 3, OCT1), slot(FOOD, 10, OCT1)));
        assertThat(plan.changes()).containsExactly(
                new Change(FOOD, oct(3), OCT1, 20000, 3500, List.of(1L, 2L)),
                new Change(FOOD, oct(10), OCT1, 20000, 400, List.of(3L)));
    }

    @Test
    void aDateWithNoChargesIsZeroOnReplaceAndUnchangedOnAdd() {
        List<Charge> charges = List.of(charge(1, 12, 1000, GROCERIES));
        List<Slot> slots = List.of(slot(FOOD, 3, OCT1), slot(FOOD, 10, OCT1));
        Plan replace = Replacement.plan(charges, PERIODS, List.of(new Target(OCT1, category(GROCERIES), FOOD, Mode.REPLACE)), slots);
        Plan add = Replacement.plan(charges, PERIODS, List.of(new Target(OCT1, category(GROCERIES), FOOD, Mode.ADD)), slots);
        assertThat(replace.changes()).extracting(Change::newCents).containsExactly(0L, 1000L);
        assertThat(add.changes()).extracting(Change::newCents).containsExactly(20000L, 21000L);
    }

    @Test
    void aChargeBothTargetsClaimWaitsForAChoice() {
        Charge gasOnTrip = charge(1, 16, 3800, GAS, VACATION_TAG);
        List<Target> targets = List.of(
                new Target(OCT15, category(GAS), FUEL, Mode.REPLACE),
                new Target(OCT15, tag(VACATION_TAG), TRIP, Mode.REPLACE));
        List<Slot> slots = List.of(slot(FUEL, 15, OCT15), slot(TRIP, 15, OCT15));

        Plan undecided = Replacement.plan(List.of(gasOnTrip), PERIODS, targets, slots);
        assertThat(undecided.undecided()).containsExactly(1L);
        assertThat(undecided.changes()).extracting(Change::newCents).containsExactly(0L, 0L);

        Charge chosen = new Charge(1, oct(16), 3800, GAS, List.of(VACATION_TAG), tag(VACATION_TAG));
        Plan decided = Replacement.plan(List.of(chosen), PERIODS, targets, slots);
        assertThat(decided.undecided()).isEmpty();
        assertThat(decided.changes()).extracting(Change::itemId, Change::newCents)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(FUEL, 0L), org.assertj.core.groups.Tuple.tuple(TRIP, 3800L));
    }

    @Test
    void twoTargetsOnOneItemCountEachChargeOnce() {
        // Groceries and the Vacation tag both pointed at Food: the charge in both is counted once.
        Plan plan = Replacement.plan(
                List.of(charge(1, 20, 6000, GROCERIES, VACATION_TAG), charge(2, 21, 100, null, VACATION_TAG)),
                PERIODS,
                List.of(new Target(OCT15, category(GROCERIES), FOOD, Mode.REPLACE), new Target(OCT15, tag(VACATION_TAG), FOOD, Mode.ADD)),
                List.of(slot(FOOD, 15, OCT15)));
        // One of the two is Replace, so it starts from nothing.
        assertThat(plan.changes()).containsExactly(new Change(FOOD, oct(15), OCT15, 20000, 6100, List.of(1L, 2L)));
    }

    @Test
    void moreRefundedThanSpentLeavesNothingToPay() {
        Plan plan = Replacement.plan(
                List.of(charge(1, 2, -500, GROCERIES)),
                PERIODS,
                List.of(new Target(OCT1, category(GROCERIES), FOOD, Mode.REPLACE)),
                List.of(slot(FOOD, 1, OCT1)));
        assertThat(plan.changes()).extracting(Change::newCents).containsExactly(0L);
    }

    @Test
    void aTargetOnAnItemWithNoDateInThePeriodChangesNothing() {
        Plan plan = Replacement.plan(
                List.of(charge(1, 2, 500, GROCERIES)),
                PERIODS,
                List.of(new Target(OCT1, category(GROCERIES), FOOD, Mode.REPLACE)),
                List.of(slot(FOOD, 15, OCT15)));
        assertThat(plan.changes()).isEmpty();
        assertThat(plan.unmatched()).containsExactly(1L);
    }

    @Test
    void chargesOutsideEveryPeriodAreSaidSo() {
        Plan plan = Replacement.plan(
                List.of(new Charge(1, LocalDate.of(2026, 9, 30), 500, GROCERIES, List.of(), null)), PERIODS, List.of(), List.of());
        assertThat(plan.outside()).containsExactly(1L);
    }

    @Test
    void statementsOfOneAccountTogetherCoverAPeriod() {
        // One card statement closes on the 9th; the next starts on the 10th.
        List<Coverage> partOnly = Replacement.coverage(PERIODS, List.of(new Span(LocalDate.of(2026, 9, 10), oct(9))));
        assertThat(partOnly).containsExactly(new Coverage(OCT1, oct(1), oct(9), true));

        List<Coverage> both = Replacement.coverage(PERIODS,
                List.of(new Span(LocalDate.of(2026, 9, 10), oct(9)), new Span(oct(10), LocalDate.of(2026, 11, 9))));
        assertThat(both).containsExactly(
                new Coverage(OCT1, oct(1), oct(14), false),
                new Coverage(OCT15, oct(15), oct(31), false));
    }
}
