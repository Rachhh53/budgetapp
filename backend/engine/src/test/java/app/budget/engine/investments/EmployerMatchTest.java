package app.budget.engine.investments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import org.junit.jupiter.api.Test;

/** Employer match (spec §5.5): gross $3,600 a paycheck, matched 50% up to 6% of gross. */
class EmployerMatchTest {

    private static final Money GROSS = Money.ofCents(360_000);
    private static final EmployerMatch HALF_UP_TO_SIX_PERCENT = EmployerMatch.formula(BasisPoints.of(5000), BasisPoints.of(600));

    @Test
    void aContributionUnderTheCapIsMatchedInFull() {
        // $150 is 4.17% of gross: half of it is matched.
        assertThat(HALF_UP_TO_SIX_PERCENT.forPaycheck(GROSS, Money.ofCents(15_000))).isEqualTo(Money.ofCents(7_500));
    }

    @Test
    void aContributionExactlyAtTheCapEarnsTheWholeMatch() {
        // $216 is 6% of gross.
        assertThat(HALF_UP_TO_SIX_PERCENT.forPaycheck(GROSS, Money.ofCents(21_600))).isEqualTo(Money.ofCents(10_800));
    }

    @Test
    void aContributionOverTheCapIsMatchedOnlyUpToIt() {
        // $300 is over the cap: 50% × 6% × $3,600 = $108.
        assertThat(HALF_UP_TO_SIX_PERCENT.forPaycheck(GROSS, Money.ofCents(30_000))).isEqualTo(Money.ofCents(10_800));
    }

    @Test
    void nothingContributedEarnsNothing() {
        assertThat(HALF_UP_TO_SIX_PERCENT.forPaycheck(GROSS, Money.ZERO)).isEqualTo(Money.ZERO);
    }

    @Test
    void saysWhatTheWholeMatchIsAndHowFarShortAContributionFalls() {
        assertThat(HALF_UP_TO_SIX_PERCENT.mostPossible(GROSS)).isEqualTo(Money.ofCents(10_800));
        assertThat(HALF_UP_TO_SIX_PERCENT.contributionShortOfCap(GROSS, Money.ofCents(15_000))).isEqualTo(Money.ofCents(6_600));
        assertThat(HALF_UP_TO_SIX_PERCENT.contributionShortOfCap(GROSS, Money.ofCents(30_000))).isEqualTo(Money.ZERO);
    }

    @Test
    void fractionsOfACentRoundToTheEvenCent() {
        // 50% of $0.25 is 12.5 cents, and of $0.35 is 17.5 cents.
        EmployerMatch half = EmployerMatch.formula(BasisPoints.of(5000), BasisPoints.of(10_000));
        assertThat(half.forPaycheck(GROSS, Money.ofCents(25))).isEqualTo(Money.ofCents(12));
        assertThat(half.forPaycheck(GROSS, Money.ofCents(35))).isEqualTo(Money.ofCents(18));
    }

    @Test
    void aFixedMatchIsTheSameWhateverIsContributed() {
        EmployerMatch fixed = EmployerMatch.fixed(Money.ofCents(5_000));

        assertThat(fixed.forPaycheck(GROSS, Money.ZERO)).isEqualTo(Money.ofCents(5_000));
        assertThat(fixed.forPaycheck(GROSS, Money.ofCents(30_000))).isEqualTo(Money.ofCents(5_000));
        assertThat(fixed.mostPossible(GROSS)).isEqualTo(Money.ofCents(5_000));
        assertThat(fixed.contributionShortOfCap(GROSS, Money.ZERO)).isEqualTo(Money.ZERO);
    }

    @Test
    void withoutGrossPayAContributionConfirmedWithinTheCapIsMatchedInFull() {
        EmployerMatch dollarForDollar = EmployerMatch.formula(BasisPoints.of(10_000), BasisPoints.of(600));
        EmployerMatch half = EmployerMatch.formula(BasisPoints.of(5_000), BasisPoints.of(600));

        assertThat(dollarForDollar.forContributionWithinCap(Money.ofCents(21_600))).isEqualTo(Money.ofCents(21_600));
        assertThat(half.forContributionWithinCap(Money.ofCents(15_000))).isEqualTo(Money.ofCents(7_500));
        assertThat(half.forContributionWithinCap(Money.ZERO)).isEqualTo(Money.ZERO);
        assertThat(EmployerMatch.fixed(Money.ofCents(5_000)).forContributionWithinCap(Money.ofCents(1))).isEqualTo(Money.ofCents(5_000));
    }

    @Test
    void negativeTermsAreRefused() {
        assertThatThrownBy(() -> EmployerMatch.fixed(Money.ofCents(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmployerMatch.formula(BasisPoints.of(-1), BasisPoints.of(600))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmployerMatch.formula(BasisPoints.of(5000), BasisPoints.of(-1))).isInstanceOf(IllegalArgumentException.class);
    }
}
