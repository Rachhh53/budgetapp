package app.budget.engine.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.planner.DebtOrInvest.Investment;
import app.budget.engine.planner.DebtOrInvest.Outcome;
import app.budget.engine.planner.DebtOrInvest.Plan;
import app.budget.engine.planner.PayoffPlan.Debt;
import app.budget.engine.planner.PayoffPlan.Order;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

class DebtOrInvestTest {

    private static final LocalDate NOV_1 = LocalDate.parse("2026-11-01");
    private static final YearMonth NOV = YearMonth.of(2026, 11);
    private static final Money EXTRA = Money.ofCents(30_000);

    private static final Plan INVEST_ALL = new Plan(BasisPoints.of(0), true);
    private static final Plan PAY_OFF_FIRST = new Plan(BasisPoints.of(10_000), true);
    private static final Plan HALF = new Plan(BasisPoints.of(5_000), true);

    private static Debt debt(long id, long balanceCents, int rateBp, long paymentCents) {
        return new Debt(id, Money.ofCents(balanceCents), BasisPoints.of(rateBp), Money.ofCents(paymentCents), NOV_1);
    }

    private static Investment at(int rateBp) {
        return new Investment(BasisPoints.of(rateBp), Investment.NO_MATCH);
    }

    private static Outcome follow(List<Debt> debts, Investment investment, Plan plan, int months) {
        return DebtOrInvest.follow(debts, EXTRA, Order.HIGHEST_RATE, investment, plan, NOV, months);
    }

    /** With nothing owed, investing the extra is exactly what the investing card works out. */
    @Test
    void withNoDebtsItIsTheContributionGrowth() {
        Outcome outcome = follow(List.of(), at(700), INVEST_ALL, 120);

        ContributionGrowth.Value expected = ContributionGrowth.of(EXTRA, BasisPoints.of(700), 120);
        assertThat(outcome.invested()).isEqualTo(expected.total());
        assertThat(outcome.putIn()).isEqualTo(expected.putIn());
        assertThat(outcome.growth()).isEqualTo(expected.growth());
        assertThat(outcome.debtFreeDate()).isNull();
    }

    /** Paying off first clears the debts on the payoff order's own dates and interest, then invests. */
    @Test
    void payingOffFirstMatchesThePayoffOrderThenInvests() {
        List<Debt> debts = List.of(debt(1, 500_000, 2400, 15_000), debt(2, 1_200_000, 690, 30_000));
        PayoffPlan.Result payoff = PayoffPlan.withExtra(debts, EXTRA, Order.HIGHEST_RATE);

        Outcome outcome = follow(debts, at(700), PAY_OFF_FIRST, 120);

        assertThat(outcome.debtFreeDate()).isEqualTo(payoff.debtFreeDate());
        assertThat(outcome.interest()).isEqualTo(payoff.totalInterest());
        assertThat(outcome.owed()).isEqualTo(Money.ZERO);
        // From then on everything the debts and the extra took is invested: $750 a month.
        int left = (int) java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(payoff.debtFreeDate()), NOV.plusMonths(119));
        assertThat(outcome.putIn().cents()).isGreaterThanOrEqualTo(75_000L * left);
    }

    /** Every plan spends the same: at 0% on both sides, once the debts are gone, each one comes to the same. */
    @Test
    void thePlansSpendTheSameEachMonth() {
        List<Debt> debts = List.of(debt(1, 400_000, 0, 10_000), debt(2, 900_000, 0, 20_000));

        Money all = follow(debts, at(0), INVEST_ALL, 120).net();
        Money first = follow(debts, at(0), PAY_OFF_FIRST, 120).net();
        Money half = follow(debts, at(0), HALF, 120).net();

        // Ten years of $300 extra and $300 of payments, less the $13,000 owed.
        assertThat(all).isEqualTo(Money.ofCents(120L * 60_000 - 1_300_000));
        assertThat(first).isEqualTo(all);
        assertThat(half).isEqualTo(all);
    }

    @Test
    void aDearDebtIsBetterPaidOffAndACheapOneBetterLeft() {
        List<Debt> card = List.of(debt(1, 800_000, 2400, 20_000));
        assertThat(follow(card, at(700), PAY_OFF_FIRST, 120).net()).isGreaterThan(follow(card, at(700), INVEST_ALL, 120).net());

        List<Debt> mortgage = List.of(debt(1, 20_000_000, 300, 120_000));
        assertThat(follow(mortgage, at(800), INVEST_ALL, 120).net()).isGreaterThan(follow(mortgage, at(800), PAY_OFF_FIRST, 120).net());
    }

    /** A split sits between the two, and leaves money owed at the end when the debts outlast it. */
    @Test
    void aSplitPaysBothAndCountsWhatIsStillOwed() {
        List<Debt> debts = List.of(debt(1, 1_500_000, 1200, 20_000));

        Outcome half = follow(debts, at(700), HALF, 36);
        Outcome all = follow(debts, at(700), INVEST_ALL, 36);

        assertThat(half.owed()).isGreaterThan(Money.ZERO).isLessThan(all.owed());
        assertThat(half.invested()).isGreaterThan(Money.ZERO).isLessThan(all.invested());
        assertThat(half.net()).isEqualTo(half.invested().minus(half.owed()));
        assertThat(half.months()).hasSize(36);
        assertThat(half.end().month()).isEqualTo(NOV.plusMonths(35));
    }

    /** When a paid-off debt's payment goes back to the budget, only the extra moves on. */
    @Test
    void aFreedPaymentCanGoBackToTheBudget() {
        List<Debt> debts = List.of(debt(1, 200_000, 1500, 20_000));

        Outcome kept = follow(debts, at(0), PAY_OFF_FIRST, 60);
        Outcome returned = follow(debts, at(0), new Plan(BasisPoints.of(10_000), false), 60);

        assertThat(returned.debtFreeDate()).isEqualTo(kept.debtFreeDate());
        assertThat(kept.putIn()).isGreaterThan(returned.putIn());
    }

    /** An employer's match is added to each month's amount as it goes in. */
    @Test
    void theMatchIsAddedToWhatGoesIn() {
        Investment matched = new Investment(BasisPoints.of(0), amount -> amount.times(new java.math.BigDecimal("0.5")));

        Outcome outcome = follow(List.of(), matched, INVEST_ALL, 12);

        assertThat(outcome.putIn()).isEqualTo(Money.ofCents(360_000));
        assertThat(outcome.match()).isEqualTo(Money.ofCents(180_000));
        assertThat(outcome.invested()).isEqualTo(Money.ofCents(540_000));
    }

    @Test
    void refusesWhatCannotBeAPlan() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Plan(BasisPoints.of(10_001), true));
        assertThatIllegalArgumentException().isThrownBy(() -> at(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> follow(List.of(), at(0), INVEST_ALL, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> follow(List.of(), at(0), INVEST_ALL, 361));
    }
}
