package app.budget.engine.cards;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.cards.CardPayment.Mode;
import app.budget.engine.cards.CardProjector.Derived;
import app.budget.engine.cards.CardProjector.Kind;
import app.budget.engine.cards.CardProjector.Payment;
import app.budget.engine.cards.CardProjector.Result;
import app.budget.engine.cards.CardProjector.Statement;
import app.budget.engine.cards.CardProjector.Terms;
import app.budget.engine.interest.InterestTerms;
import app.budget.engine.interest.InterestTerms.RateChange;
import app.budget.engine.projection.BalanceAnchor;
import app.budget.engine.projection.Movement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A card that closes on the 20th and is due on the 15th, at 24.49%. */
class CardProjectorTest {

    private static final InterestTerms APR =
            new InterestTerms(31, List.of(new RateChange(LocalDate.parse("2026-01-01"), BasisPoints.of(2449))));

    private static LocalDate date(String text) {
        return LocalDate.parse(text);
    }

    private static Money cents(long cents) {
        return Money.ofCents(cents);
    }

    private static Terms terms(Mode mode, Long monthlyEstimateCents, List<Promo> promos, Map<LocalDate, Statement> entered) {
        return new Terms(20, 15, mode, monthlyEstimateCents == null ? null : cents(monthlyEstimateCents), APR, promos, entered);
    }

    private static Terms terms(Mode mode) {
        return terms(mode, null, List.of(), Map.of());
    }

    private static BalanceAnchor owed(String on, long amountCents) {
        return new BalanceAnchor(date(on), cents(amountCents));
    }

    private static Movement charge(String on, long amountCents) {
        return new Movement(date(on), cents(amountCents), false);
    }

    @Test
    void aCardPaidInFullEveryMonthIsNeverChargedInterest() {
        Result result = CardProjector.project(
                List.of(owed("2026-10-01", 68248)),
                List.of(charge("2026-10-05", 10000)),
                List.of(new Payment(date("2026-10-13"), 7, false)),
                terms(Mode.STATEMENT_BALANCE),
                date("2026-10-31"));

        // The payment is what was owed when the card was entered, not the charge made since.
        assertThat(result.derived()).containsExactly(new Derived(date("2026-10-13"), Kind.PAYMENT, cents(-68248), 7));
        assertThat(result.statements()).containsExactly(
                new Statement(date("2026-10-20"), cents(10000), cents(2500), date("2026-11-15"), Money.ZERO, false));
    }

    @Test
    void aBalanceCarriedFromOneStatementToTheNextIsChargedInterest() {
        Result result = CardProjector.project(
                List.of(owed("2026-10-01", 100000)), List.of(), List.of(), terms(Mode.STATEMENT_BALANCE), date("2026-11-30"));

        // 1,000.00 × 24.49% ÷ 12 = 20.41, then 1,020.41 × 24.49% ÷ 12 = 20.82
        assertThat(result.derived()).containsExactly(
                new Derived(date("2026-10-20"), Kind.INTEREST, cents(2041), -1),
                new Derived(date("2026-11-20"), Kind.INTEREST, cents(2082), -1));
        assertThat(result.statements()).extracting(Statement::balance).containsExactly(cents(102041), cents(104123));
    }

    @Test
    void interestTypedOverIsWhatTheStatementCarriesAndLaterMonthsFollowIt() {
        Terms typed = new Terms(20, 15, Mode.STATEMENT_BALANCE, null, APR.withTyped(Map.of(date("2026-10-20"), cents(1500))), List.of(), Map.of());
        Result result = CardProjector.project(List.of(owed("2026-10-01", 100000)), List.of(), List.of(), typed, date("2026-11-30"));

        // October is 15.00 as typed; November is 1,015.00 × 24.49% ÷ 12 = 20.71.
        assertThat(result.derived()).containsExactly(
                new Derived(date("2026-10-20"), Kind.INTEREST, cents(1500), -1),
                new Derived(date("2026-11-20"), Kind.INTEREST, cents(2071), -1));
        assertThat(result.statements()).extracting(Statement::balance).containsExactly(cents(101500), cents(103571));
        assertThat(result.statements().getFirst().interest()).isEqualTo(cents(1500));
    }

    @Test
    void rewardsOnWhatWasChargedComeOffAtTheCloseSoThePaymentIsSmaller() {
        Terms rewarded = terms(Mode.STATEMENT_BALANCE).withRewards(BasisPoints.of(150), Map.of());
        Result result = CardProjector.project(
                List.of(owed("2026-10-01", 0)),
                // The payment and the charge after the close are not earned on in October.
                List.of(charge("2026-10-05", 40000), charge("2026-10-12", 12345), charge("2026-10-14", -5000), charge("2026-10-25", 20000)),
                List.of(new Payment(date("2026-11-15"), 3, false)),
                rewarded,
                date("2026-11-30"));

        // 523.45 charged × 1.5% = 7.85 (7.85175, to the cent). November: 200.00 × 1.5% = 3.00.
        assertThat(result.derived()).containsExactly(
                new Derived(date("2026-10-20"), Kind.REWARDS, cents(-785), -1),
                new Derived(date("2026-11-15"), Kind.PAYMENT, cents(-46560), 3),
                new Derived(date("2026-11-20"), Kind.REWARDS, cents(-300), -1));
        assertThat(result.statements().getFirst().balance()).isEqualTo(cents(46560));
    }

    @Test
    void rewardsLeftToBuildUpAreTrackedAndNeverComeOffOnTheirOwn() {
        Terms building = terms(Mode.STATEMENT_BALANCE).withRewards(BasisPoints.of(200), Map.of())
                .withRewardsBuildUp(new CardProjector.BuildUp(null, null, cents(1500), date("2026-09-30"), List.of()));
        Result result = CardProjector.project(
                List.of(owed("2026-10-01", 0)), List.of(charge("2026-10-05", 50000), charge("2026-11-05", 25000)), List.of(), building,
                date("2026-11-30"));

        // 15.00 built up already, + 10.00 in October, + 5.00 in November; the balance never drops for it.
        assertThat(result.derived()).noneMatch(line -> line.kind() == Kind.REWARDS || line.kind() == Kind.REWARDS_REDEEMED);
        assertThat(result.rewards()).containsExactly(
                new CardProjector.RewardsPoint(date("2026-10-20"), cents(1000), Money.ZERO, cents(2500)),
                new CardProjector.RewardsPoint(date("2026-11-20"), cents(500), Money.ZERO, cents(3000)));
        assertThat(result.statements().getFirst().balance()).isEqualTo(cents(50000));
    }

    @Test
    void aRedemptionGoesOnAsACreditNeverMoreThanHasBuiltUp() {
        Terms building = terms(Mode.STATEMENT_BALANCE).withRewards(BasisPoints.of(200), Map.of())
                .withRewardsBuildUp(new CardProjector.BuildUp(null, null, cents(1500), null, List.of(
                        new CardProjector.Redemption(date("2026-10-10"), cents(1000), 7),
                        new CardProjector.Redemption(date("2026-11-01"), null, 8),
                        new CardProjector.Redemption(date("2026-11-02"), cents(500), 9))));
        Result result = CardProjector.project(
                List.of(owed("2026-10-01", 0)), List.of(charge("2026-10-05", 50000)), List.of(), building, date("2026-11-30"));

        // 10.00 of the 15.00 on Oct 10; 5.00 left + 10.00 earned at the close, all 15.00 on Nov 1; nothing left for Nov 2.
        assertThat(result.derived()).filteredOn(line -> line.kind() == Kind.REWARDS_REDEEMED).containsExactly(
                new Derived(date("2026-10-10"), Kind.REWARDS_REDEEMED, cents(-1000), 7),
                new Derived(date("2026-11-01"), Kind.REWARDS_REDEEMED, cents(-1500), 8));
        // The October statement is the charge less the credit.
        assertThat(result.statements().getFirst().balance()).isEqualTo(cents(49000));
        assertThat(result.rewards().getLast().builtUp()).isEqualTo(Money.ZERO);
    }

    @Test
    void aRuleUsesEverythingBuiltUpInTheMonthChosenOrOnceItReachesTheAmount() {
        Terms yearly = terms(Mode.STATEMENT_BALANCE).withRewards(BasisPoints.of(200), Map.of())
                .withRewardsBuildUp(new CardProjector.BuildUp(11, null, Money.ZERO, null, List.of()));
        Result inNovember = CardProjector.project(
                List.of(owed("2026-10-01", 0)), List.of(charge("2026-10-05", 50000), charge("2026-11-05", 25000)), List.of(), yearly,
                date("2026-12-31"));
        assertThat(inNovember.derived()).filteredOn(line -> line.kind() == Kind.REWARDS_REDEEMED).containsExactly(
                new Derived(date("2026-11-20"), Kind.REWARDS_REDEEMED, cents(-1500), -1));

        Terms reaching = terms(Mode.STATEMENT_BALANCE).withRewards(BasisPoints.of(200), Map.of())
                .withRewardsBuildUp(new CardProjector.BuildUp(null, cents(1200), Money.ZERO, null, List.of()));
        Result once = CardProjector.project(
                List.of(owed("2026-10-01", 0)), List.of(charge("2026-10-05", 50000), charge("2026-11-05", 25000)), List.of(), reaching,
                date("2026-12-31"));
        // 10.00 in October is short of 12.00; 15.00 in November is past it, so all of it goes on, off that statement.
        assertThat(once.derived()).filteredOn(line -> line.kind() == Kind.REWARDS_REDEEMED).containsExactly(
                new Derived(date("2026-11-20"), Kind.REWARDS_REDEEMED, cents(-1500), -1));
        // November's statement is 15.00 less than the same card's with no rule (both carry October's interest).
        Terms noRule = terms(Mode.STATEMENT_BALANCE).withRewards(BasisPoints.of(200), Map.of())
                .withRewardsBuildUp(new CardProjector.BuildUp(null, null, Money.ZERO, null, List.of()));
        Result kept = CardProjector.project(
                List.of(owed("2026-10-01", 0)), List.of(charge("2026-10-05", 50000), charge("2026-11-05", 25000)), List.of(), noRule,
                date("2026-12-31"));
        assertThat(once.statements().get(1).balance()).isEqualTo(kept.statements().get(1).balance().minus(cents(1500)));
    }

    @Test
    void rewardsAreEarnedOnTheMonthlyEstimateAndComeOffAfterInterest() {
        Terms rewarded = terms(Mode.STATEMENT_BALANCE, 45000L, List.of(), Map.of()).withRewards(BasisPoints.of(200), Map.of());
        Result result = CardProjector.project(List.of(owed("2026-10-01", 100000)), List.of(), List.of(), rewarded, date("2026-10-31"));

        // 1,000.00 carried: 20.41 interest. 450.00 estimated × 2% = 9.00 back.
        assertThat(result.derived()).containsExactly(
                new Derived(date("2026-10-20"), Kind.ESTIMATED_SPENDING, cents(45000), -1),
                new Derived(date("2026-10-20"), Kind.INTEREST, cents(2041), -1),
                new Derived(date("2026-10-20"), Kind.REWARDS, cents(-900), -1));
        assertThat(result.statements().getFirst().balance()).isEqualTo(cents(146141));
    }

    @Test
    void aMonthsRewardsTypedOverAreWhatComesOffZeroIncluded() {
        Terms rewarded = terms(Mode.STATEMENT_BALANCE)
                .withRewards(BasisPoints.of(150), Map.of(date("2026-10-20"), cents(1234), date("2026-11-20"), Money.ZERO));
        Result result = CardProjector.project(
                List.of(owed("2026-10-01", 0)), List.of(charge("2026-10-05", 10000), charge("2026-11-05", 10000)), List.of(), rewarded,
                date("2026-11-30"));

        assertThat(result.derived()).filteredOn(line -> line.kind() == Kind.REWARDS).containsExactly(
                new Derived(date("2026-10-20"), Kind.REWARDS, cents(-1234), -1),
                new Derived(date("2026-11-20"), Kind.REWARDS, Money.ZERO, -1));
    }

    @Test
    void aCardWithoutRewardsOrWithNothingChargedGetsNone() {
        Result none = CardProjector.project(
                List.of(owed("2026-10-01", 0)), List.of(charge("2026-10-05", 10000)), List.of(), terms(Mode.STATEMENT_BALANCE), date("2026-10-31"));
        Result nothingCharged = CardProjector.project(
                List.of(owed("2026-10-01", 0)), List.of(), List.of(), terms(Mode.STATEMENT_BALANCE).withRewards(BasisPoints.of(150), Map.of()),
                date("2026-10-31"));

        assertThat(none.derived()).isEmpty();
        assertThat(nothingCharged.derived()).isEmpty();
    }

    @Test
    void aStatementTheUserEnteredAlreadyHoldsItsRewards() {
        Statement real = new Statement(date("2026-10-20"), cents(9850), null, date("2026-11-15"), Money.ZERO, true);
        Result result = CardProjector.project(
                List.of(owed("2026-10-01", 0), owed("2026-10-21", 9850)),
                List.of(charge("2026-10-05", 10000), charge("2026-11-05", 10000)),
                List.of(),
                new Terms(20, 15, Mode.STATEMENT_BALANCE, null, APR, List.of(), Map.of(date("2026-10-20"), real))
                        .withRewards(BasisPoints.of(150), Map.of()),
                date("2026-11-30"));

        // Only November, on the charge made after the entered statement closed.
        assertThat(result.derived()).filteredOn(line -> line.kind() == Kind.REWARDS).containsExactly(new Derived(date("2026-11-20"), Kind.REWARDS, cents(-150), -1));
    }

    @Test
    void onlyWhatWasLeftUnpaidIsChargedInterest() {
        Result result = CardProjector.project(
                List.of(owed("2026-10-01", 100000)),
                List.of(charge("2026-10-10", -60000), charge("2026-10-12", 30000)),
                List.of(),
                terms(Mode.STATEMENT_BALANCE),
                date("2026-10-31"));

        // 400.00 of the 1,000.00 was left: 400 × 24.49% ÷ 12 = 8.16. The new 300.00 charge is still in its grace period.
        assertThat(result.derived()).containsExactly(new Derived(date("2026-10-20"), Kind.INTEREST, cents(816), -1));
        assertThat(result.statements().getFirst().balance()).isEqualTo(cents(70816));
    }

    @Test
    void aCardThatIsNotItemizedTakesOneEstimateEachStatementMonth() {
        Result result = CardProjector.project(
                List.of(owed("2026-10-01", 0)),
                List.of(),
                List.of(new Payment(date("2026-11-15"), 1, false), new Payment(date("2026-12-15"), 2, false)),
                terms(Mode.FULL_BALANCE, 45000L, List.of(), Map.of()),
                date("2026-12-31"));

        assertThat(result.derived()).containsExactly(
                new Derived(date("2026-10-20"), Kind.ESTIMATED_SPENDING, cents(45000), -1),
                new Derived(date("2026-11-15"), Kind.PAYMENT, cents(-45000), 1),
                new Derived(date("2026-11-20"), Kind.ESTIMATED_SPENDING, cents(45000), -1),
                new Derived(date("2026-12-15"), Kind.PAYMENT, cents(-45000), 2),
                new Derived(date("2026-12-20"), Kind.ESTIMATED_SPENDING, cents(45000), -1));
    }

    @Test
    void chargesEnteredCountTowardTheEstimateSoTheyNeverStack() {
        Result result = CardProjector.project(
                List.of(owed("2026-10-01", 0)),
                // 300.00 of bills charged to the card in October; 500.00 in November, more than the estimate.
                List.of(charge("2026-10-05", 30000), charge("2026-11-05", 50000)),
                List.of(),
                terms(Mode.FULL_BALANCE, 45000L, List.of(), Map.of()),
                date("2026-12-31"));

        // October adds only the 150.00 the charges left; November nothing; December, with nothing entered, all of it.
        assertThat(result.derived()).filteredOn(line -> line.kind() == Kind.ESTIMATED_SPENDING).containsExactly(
                new Derived(date("2026-10-20"), Kind.ESTIMATED_SPENDING, cents(15000), -1),
                new Derived(date("2026-12-20"), Kind.ESTIMATED_SPENDING, cents(45000), -1));
        assertThat(result.statements().getFirst().balance()).isEqualTo(cents(45000));
    }

    @Test
    void aStatementTheUserEnteredTakesOverFromTheEstimate() {
        Statement real = new Statement(date("2026-10-20"), cents(51230), cents(3500), date("2026-11-15"), Money.ZERO, true);

        Result result = CardProjector.project(
                // The caller also gives the statement's balance as what is owed the day after it closed.
                List.of(owed("2026-10-01", 0), owed("2026-10-21", 51230)),
                List.of(),
                List.of(new Payment(date("2026-11-15"), 1, false)),
                terms(Mode.STATEMENT_BALANCE, 45000L, List.of(), Map.of(date("2026-10-20"), real)),
                date("2026-11-19"));

        // No estimate is added for October, and the payment is the real statement balance.
        assertThat(result.derived()).containsExactly(new Derived(date("2026-11-15"), Kind.PAYMENT, cents(-51230), 1));
        assertThat(result.statements()).containsExactly(real);
    }

    @Test
    void aMinimumPaymentIsTheStatementsMinimum() {
        Statement real = new Statement(date("2026-10-20"), cents(240000), cents(3500), date("2026-11-15"), Money.ZERO, true);

        Result result = CardProjector.project(
                List.of(owed("2026-10-01", 240000), owed("2026-10-21", 240000)),
                List.of(),
                List.of(new Payment(date("2026-11-15"), 1, false)),
                terms(Mode.MINIMUM, null, List.of(), Map.of(date("2026-10-20"), real)),
                date("2026-11-19"));

        assertThat(result.derived()).containsExactly(new Derived(date("2026-11-15"), Kind.PAYMENT, cents(-3500), 1));
    }

    @Test
    void untilAStatementClosesTheMinimumIsEstimatedFromTheBalanceTheCardStartsWith() {
        Result result = CardProjector.project(
                List.of(owed("2026-10-01", 240000)),
                List.of(),
                List.of(new Payment(date("2026-10-15"), 1, false)),
                terms(Mode.MINIMUM),
                date("2026-10-19"));

        // 1% of 2,400.00 is under the usual $25.00 floor.
        assertThat(result.derived()).containsExactly(new Derived(date("2026-10-15"), Kind.PAYMENT, cents(-2500), 1));
    }

    @Test
    void aZeroPercentPromotionCostsNothingUntilItEnds() {
        Promo promo = new Promo(1, BasisPoints.of(0), date("2026-03-15"), date("2027-03-15"), cents(300000), null, false);

        Result result = CardProjector.project(
                List.of(owed("2027-02-01", 240000)), List.of(), List.of(),
                terms(Mode.FIXED, null, List.of(promo), Map.of()), date("2027-03-31"));

        // February's statement is free; March's closes after the promotion ended: 2,400 × 24.49% ÷ 12 = 48.98
        assertThat(result.derived()).containsExactly(new Derived(date("2027-03-20"), Kind.INTEREST, cents(4898), -1));
    }

    @Test
    void deferredInterestIsChargedOnTheLastDayWhenABalanceIsStillThere() {
        Promo promo = new Promo(1, BasisPoints.of(0), date("2026-03-15"), date("2027-03-15"), cents(300000), null, true);

        Result unpaid = CardProjector.project(
                List.of(owed("2027-03-01", 240000)), List.of(), List.of(),
                terms(Mode.FIXED, null, List.of(promo), Map.of()), date("2027-03-19"));
        Result cleared = CardProjector.project(
                List.of(owed("2027-03-01", 240000)), List.of(charge("2027-03-10", -240000)), List.of(),
                terms(Mode.FIXED, null, List.of(promo), Map.of()), date("2027-03-19"));

        assertThat(unpaid.derived()).containsExactly(new Derived(date("2027-03-15"), Kind.DEFERRED_INTEREST, cents(73470), -1));
        assertThat(cleared.derived()).isEmpty();
    }

    @Test
    void aBalanceEnteredLaterReplacesWhatWasWorkedOut() {
        Result result = CardProjector.project(
                List.of(owed("2026-10-01", 100000), owed("2026-11-01", 20000)),
                List.of(),
                List.of(new Payment(date("2026-11-15"), 1, false)),
                terms(Mode.FULL_BALANCE),
                date("2026-11-19"));

        assertThat(result.derived()).containsExactly(
                new Derived(date("2026-10-20"), Kind.INTEREST, cents(2041), -1),
                new Derived(date("2026-11-15"), Kind.PAYMENT, cents(-20000), 1));
    }

    @Test
    void anUnpaidPaymentFromBeforeTheNewestBalanceIsMadeOnThatDay() {
        Result result = CardProjector.project(
                List.of(owed("2026-10-01", 100000), owed("2026-11-01", 20000)),
                List.of(),
                List.of(new Payment(date("2026-10-28"), 1, true)),
                terms(Mode.FULL_BALANCE),
                date("2026-11-19"));

        // It has not been paid yet, so it pays what the newer balance says is owed.
        assertThat(result.derived()).contains(new Derived(date("2026-11-01"), Kind.PAYMENT, cents(-20000), 1));
    }

    @Test
    void aStatementEnteredBeforeTheBalanceItStartsFromIsStillTheOneToPay() {
        // September's statement said 725.00; by October 1 more had been charged and 3,125.00 was owed.
        Statement september = new Statement(date("2026-09-20"), cents(72500), cents(2500), date("2026-10-15"), Money.ZERO, true);
        Result result = CardProjector.project(
                List.of(owed("2026-10-01", 312500)),
                List.of(),
                List.of(new Payment(date("2026-10-15"), 1, false), new Payment(date("2026-11-15"), 2, false)),
                terms(Mode.STATEMENT_BALANCE, null, List.of(), Map.of(date("2026-09-20"), september)),
                date("2026-11-19"));

        // Only the statement is paid; the rest was charged after it closed, so nothing is carried and no interest is charged.
        assertThat(result.derived()).containsExactly(
                new Derived(date("2026-10-15"), Kind.PAYMENT, cents(-72500), 1),
                new Derived(date("2026-11-15"), Kind.PAYMENT, cents(-240000), 2));
        assertThat(result.statements()).extracting(Statement::balance).containsExactly(cents(240000));
        // The first is the amount on a real statement; the second is sized from a projected one.
        assertThat(result.exact()).containsExactly(1);
        // Each says which statement it pays: the one entered, then the one projected to close in October.
        assertThat(result.pays()).containsExactlyInAnyOrderEntriesOf(Map.of(1, date("2026-09-20"), 2, date("2026-10-20")));
    }

    @Test
    void anOlderStatementSaysNothingAboutWhatIsDueNow() {
        Statement august = new Statement(date("2026-08-20"), cents(72500), cents(2500), date("2026-09-15"), Money.ZERO, true);
        Result result = CardProjector.project(
                List.of(owed("2026-10-01", 312500)),
                List.of(),
                List.of(new Payment(date("2026-10-15"), 1, false)),
                terms(Mode.STATEMENT_BALANCE, null, List.of(), Map.of(date("2026-08-20"), august)),
                date("2026-10-19"));

        assertThat(result.derived()).containsExactly(new Derived(date("2026-10-15"), Kind.PAYMENT, cents(-312500), 1));
        assertThat(result.exact()).isEmpty();
        // It is sized from the balance the card starts with, so there is no statement to name.
        assertThat(result.pays()).isEmpty();
    }

    @Test
    void nothingIsWorkedOutBeforeABalanceIsKnown() {
        assertThat(CardProjector.project(List.of(), List.of(charge("2026-10-05", 10000)), List.of(), terms(Mode.FULL_BALANCE), date("2026-10-31")))
                .isEqualTo(Result.NONE);
        assertThat(CardProjector.project(List.of(owed("2026-11-01", 100)), List.of(), List.of(), terms(Mode.FULL_BALANCE), date("2026-10-31")))
                .isEqualTo(Result.NONE);
    }
}
