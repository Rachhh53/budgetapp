package app.budget.engine.cards;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.Money;
import app.budget.engine.cards.CardPayment.Mode;
import org.junit.jupiter.api.Test;

class CardPaymentTest {

    private static Money cents(long cents) {
        return Money.ofCents(cents);
    }

    private static Money pay(Mode mode, long owed, long statement, long minimum, long paid) {
        return CardPayment.amount(mode, cents(owed), cents(statement), cents(minimum), cents(paid), cents(24000));
    }

    @Test
    void theFullBalanceIsEverythingOwedThatDay() {
        assertThat(pay(Mode.FULL_BALANCE, 122500, 68248, 3500, 0)).isEqualTo(cents(122500));
    }

    @Test
    void theStatementBalanceIsWhatTheLastStatementSaid() {
        assertThat(pay(Mode.STATEMENT_BALANCE, 122500, 68248, 3500, 0)).isEqualTo(cents(68248));
    }

    @Test
    void whatWasAlreadyPaidSinceTheStatementComesOffIt() {
        assertThat(pay(Mode.STATEMENT_BALANCE, 102500, 68248, 3500, 20000)).isEqualTo(cents(48248));
        assertThat(pay(Mode.STATEMENT_BALANCE, 54252, 68248, 3500, 68248)).isEqualTo(Money.ZERO);
        assertThat(pay(Mode.MINIMUM, 240000, 240000, 3500, 5000)).isEqualTo(Money.ZERO);
    }

    @Test
    void theMinimumIsTheStatementsMinimum() {
        assertThat(pay(Mode.MINIMUM, 240000, 240000, 3500, 0)).isEqualTo(cents(3500));
    }

    @Test
    void aFixedPaymentIsTheSameEveryTime() {
        assertThat(pay(Mode.FIXED, 240000, 240000, 3500, 0)).isEqualTo(cents(24000));
    }

    @Test
    void aPaymentIsNeverMoreThanIsOwed() {
        assertThat(pay(Mode.FIXED, 10000, 240000, 3500, 0)).isEqualTo(cents(10000));
        assertThat(pay(Mode.STATEMENT_BALANCE, 5000, 68248, 3500, 0)).isEqualTo(cents(5000));
        // A card in credit is owed nothing.
        assertThat(pay(Mode.FULL_BALANCE, -2000, 0, 0, 0)).isEqualTo(Money.ZERO);
    }

    @Test
    void anEstimatedMinimumIsOnePercentPlusInterestAndAtLeastTwentyFiveDollars() {
        // 1% of 5,000.00 is 50.00, plus 100.00 of interest.
        assertThat(CardPayment.estimatedMinimum(cents(500000), cents(10000))).isEqualTo(cents(15000));
        // 1% of 2,400.00 is 24.00: under the usual floor.
        assertThat(CardPayment.estimatedMinimum(cents(240000), Money.ZERO)).isEqualTo(cents(2500));
        // Never more than the balance itself.
        assertThat(CardPayment.estimatedMinimum(cents(1000), Money.ZERO)).isEqualTo(cents(1000));
        assertThat(CardPayment.estimatedMinimum(Money.ZERO, Money.ZERO)).isEqualTo(Money.ZERO);
    }
}
