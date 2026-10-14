package app.budget.engine.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.investments.EmployerMatch;
import app.budget.engine.planner.MatchOnExtra.Matched;
import org.junit.jupiter.api.Test;

class MatchOnExtraTest {

    /** The spec's §5.5 case: 50% of what is put in, up to 6% of a $3,600 paycheck ($216). */
    private static final EmployerMatch HALF_UP_TO_SIX = EmployerMatch.formula(BasisPoints.of(5000), BasisPoints.of(600));
    private static final Money GROSS = Money.ofCents(360000);

    private static Money cents(long cents) {
        return Money.ofCents(cents);
    }

    @Test
    void extraUnderTheCapIsAllMatched() {
        // Putting in 150.00; another 50.00 is still under the 216.00 that is matched.
        Matched matched = MatchOnExtra.perPaycheck(HALF_UP_TO_SIX, GROSS, cents(15000), cents(5000));

        assertThat(matched).isEqualTo(new Matched(cents(5000), cents(2500)));
    }

    @Test
    void onlyThePartUnderTheCapIsMatched() {
        // 150.00 already in: the next 66.00 reaches the cap and earns 33.00; the rest earns nothing.
        Matched matched = MatchOnExtra.perPaycheck(HALF_UP_TO_SIX, GROSS, cents(15000), cents(15000));

        assertThat(matched).isEqualTo(new Matched(cents(6600), cents(3300)));
    }

    @Test
    void atOrOverTheCapNothingMoreIsMatched() {
        assertThat(MatchOnExtra.perPaycheck(HALF_UP_TO_SIX, GROSS, cents(21600), cents(15000))).isEqualTo(Matched.NONE);
        assertThat(MatchOnExtra.perPaycheck(HALF_UP_TO_SIX, GROSS, cents(30000), cents(15000))).isEqualTo(Matched.NONE);
    }

    @Test
    void aFixedMatchDoesNotGrowWithWhatIsPutIn() {
        assertThat(MatchOnExtra.perPaycheck(EmployerMatch.fixed(cents(10000)), GROSS, cents(15000), cents(15000))).isEqualTo(Matched.NONE);
    }

    @Test
    void aMonthsExtraIsSharedOverThePaychecksAndComesBackAsAMonth() {
        // Paid twice a month: 300.00 a month is 150.00 a paycheck, of which 66.00 is matched at 33.00.
        Matched matched = MatchOnExtra.perMonth(HALF_UP_TO_SIX, GROSS, cents(15000), cents(30000), 24);

        assertThat(matched).isEqualTo(new Matched(cents(13200), cents(6600)));
    }

    @Test
    void sharingOverAnOddNumberOfPaychecksNeverMatchesMoreThanWasPutIn() {
        // 26 paychecks: 20.00 a month is 9.23 a paycheck, which comes back as 20.00 (19.998…), not more.
        Matched matched = MatchOnExtra.perMonth(HALF_UP_TO_SIX, GROSS, cents(15000), cents(2000), 26);

        assertThat(matched.matchedPart()).isLessThanOrEqualTo(cents(2000));
        assertThat(matched.match()).isEqualTo(cents(1001));
    }

    @Test
    void withNoPaychecksNothingIsMatched() {
        assertThat(MatchOnExtra.perMonth(HALF_UP_TO_SIX, GROSS, cents(15000), cents(30000), 0)).isEqualTo(Matched.NONE);
    }

    @Test
    void aNegativeExtraIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> MatchOnExtra.perPaycheck(HALF_UP_TO_SIX, GROSS, cents(15000), cents(-1)));
    }
}
