package app.budget.engine.retirement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.retirement.RetirementPlanner.AgePoint;
import app.budget.engine.retirement.RetirementPlanner.Income;
import app.budget.engine.retirement.RetirementPlanner.OneOff;
import app.budget.engine.retirement.RetirementPlanner.Pot;
import app.budget.engine.retirement.RetirementPlanner.Result;
import app.budget.engine.retirement.RetirementPlanner.Scenario;
import app.budget.engine.retirement.RetirementPlanner.Target;
import app.budget.engine.retirement.RetirementPlanner.TaxType;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Retirement (spec §5.11). Born Jan 1, 1990 and planned on a birthday, so today is exactly 36. */
class RetirementPlannerTest {

    private static final LocalDate BORN = LocalDate.of(1990, 1, 1);
    private static final LocalDate TODAY = LocalDate.of(2026, 1, 1);

    private static Money dollars(long dollars) {
        return Money.ofCents(dollars * 100);
    }

    /**
     * $40,000 a year at a 4% withdrawal, with no health cost; the lower and higher growth are 2 points either side
     * (never below 0%).
     */
    private static Scenario scenario(int inflationBp, int taxBp, Money healthBefore, int growthBp, int raiseBp, Integer retireAge,
            List<Income> incomes, List<OneOff> oneOffs) {
        return new Scenario(dollars(40_000), BasisPoints.of(inflationBp), BasisPoints.of(400), BasisPoints.of(taxBp), healthBefore,
                Money.ZERO, BasisPoints.of(0), BasisPoints.of(growthBp), BasisPoints.of(Math.max(0, growthBp - 200)),
                BasisPoints.of(growthBp + 200), BasisPoints.of(raiseBp), Money.ZERO, TaxType.TAXABLE, retireAge, incomes, oneOffs);
    }

    /** Nothing grows, prices don't rise, no tax. */
    private static Scenario plain(Integer retireAge, List<Income> incomes, List<OneOff> oneOffs) {
        return scenario(0, 0, Money.ZERO, 0, 0, retireAge, incomes, oneOffs);
    }

    /** $100,000 today, $30,000 in a year. */
    private static final Pot FLAT = new Pot(TaxType.TAXABLE, dollars(100_000), dollars(30_000));

    private static AgePoint at(Result result, int age) {
        return result.byAge().stream().filter(point -> point.age() == age).findFirst().orElseThrow();
    }

    @Test
    void withNothingGrowingEnoughIsTwentyFiveYearsOfSalary() {
        Result result = RetirementPlanner.plan(BORN, TODAY, List.of(FLAT), plain(null, List.of(), List.of()));

        assertThat(result.ageNow()).isEqualTo(36);
        // $100,000 + $30,000 a year reaches $1,000,000 after 30 years.
        assertThat(result.earliestAge()).isEqualTo(66);
        assertThat(at(result, 66).projected()).isEqualTo(dollars(1_000_000));
        assertThat(at(result, 66).needed()).isEqualTo(dollars(1_000_000));
        assertThat(at(result, 65).enough()).isFalse();
        assertThat(result.byAge().getFirst().age()).isEqualTo(36);
        assertThat(result.byAge().getLast().age()).isEqualTo(100);
    }

    @Test
    void growthCompoundsToTheYearlyRateExactly() {
        Pot growing = new Pot(TaxType.TAXABLE, dollars(100_000), Money.ZERO);

        Result result = RetirementPlanner.plan(BORN, TODAY, List.of(growing), scenario(0, 0, Money.ZERO, 600, 0, null, List.of(), List.of()));

        // $100,000 at 6% for 30 years: 100,000 × 1.06^30 = $574,349.12.
        assertThat(at(result, 66).projected().cents()).isCloseTo(57_434_912L, within(5L));
        assertThat(at(result, 37).projected().cents()).isCloseTo(10_600_000L, within(1L));
    }

    @Test
    void growthIsCountedAfterPricesRise() {
        Pot growing = new Pot(TaxType.TAXABLE, dollars(100_000), Money.ZERO);

        Result result = RetirementPlanner.plan(BORN, TODAY, List.of(growing), scenario(300, 0, Money.ZERO, 700, 0, null, List.of(), List.of()));

        // Ten years at 7% in today's dollars at 3%: 100,000 × (1.07 / 1.03)^10.
        BigDecimal expected = new BigDecimal("1.07").divide(new BigDecimal("1.03"), MathContext.DECIMAL128)
                .pow(10, MathContext.DECIMAL128).multiply(new BigDecimal("10000000"));
        assertThat(at(result, 46).projected().cents()).isCloseTo(expected.longValue(), within(2L));
        // The same before inflation is taken off: 100,000 × 1.07^10 = $196,715.14.
        assertThat(at(result, 46).projectedThen().cents()).isCloseTo(19_671_514L, within(5L));
    }

    @Test
    void theLowerAndHigherGrowthAreWorkedOutBesideTheExpected() {
        Pot growing = new Pot(TaxType.TAXABLE, dollars(100_000), dollars(30_000));

        Result result = RetirementPlanner.plan(BORN, TODAY, List.of(growing), scenario(0, 0, Money.ZERO, 600, 0, null, List.of(), List.of()));

        AgePoint at46 = at(result, 46);
        assertThat(at46.projectedLow()).isLessThan(at46.projected());
        assertThat(at46.projectedHigh()).isGreaterThan(at46.projected());
        // 4%, 6% and 8%: each faster growth is enough sooner.
        assertThat(result.earliestLow()).isGreaterThan(result.earliestAge());
        assertThat(result.earliestHigh()).isLessThan(result.earliestAge());
        Pot still = new Pot(TaxType.TAXABLE, dollars(100_000), Money.ZERO);
        Result atFour = RetirementPlanner.plan(BORN, TODAY, List.of(still), scenario(0, 0, Money.ZERO, 400, 0, null, List.of(), List.of()));
        Result range = RetirementPlanner.plan(BORN, TODAY, List.of(still), scenario(0, 0, Money.ZERO, 600, 0, null, List.of(), List.of()));
        assertThat(at(range, 50).projectedLow()).isEqualTo(at(atFour, 50).projected());
    }

    @Test
    void theLowerGrowthCantBeAboveTheExpected() {
        assertThatThrownBy(() -> new Scenario(dollars(40_000), BasisPoints.of(0), BasisPoints.of(400), BasisPoints.of(0), Money.ZERO,
                Money.ZERO, BasisPoints.of(0), BasisPoints.of(600), BasisPoints.of(700), BasisPoints.of(800), BasisPoints.of(0), Money.ZERO,
                TaxType.TAXABLE, null, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRaiseAddsMoreEachYear() {
        Result result = RetirementPlanner.plan(BORN, TODAY, List.of(FLAT), scenario(0, 0, Money.ZERO, 0, 1000, null, List.of(), List.of()));

        // $30,000 the first year, $33,000 the second, $36,300 the third.
        assertThat(at(result, 37).projected()).isEqualTo(dollars(130_000));
        assertThat(at(result, 38).projected()).isEqualTo(dollars(163_000));
        assertThat(at(result, 39).projected()).isEqualTo(dollars(199_300));
    }

    @Test
    void traditionalMoneyCountsAfterTax() {
        Pot ira = new Pot(TaxType.TRADITIONAL, dollars(100_000), Money.ZERO);

        Result result = RetirementPlanner.plan(BORN, TODAY, List.of(ira), scenario(0, 1500, Money.ZERO, 0, 0, null, List.of(), List.of()));

        assertThat(at(result, 40).projected()).isEqualTo(dollars(85_000));
    }

    @Test
    void theYearsBeforeOtherIncomeStartsCountInFull() {
        Result result = RetirementPlanner.plan(BORN, TODAY, List.of(FLAT),
                plain(62, List.of(new Income(dollars(20_000), 67)), List.of()));

        // 62 to 66 in full ($40,000 each), then $20,000 a year ÷ 4%.
        assertThat(result.target().needed()).isEqualTo(dollars(5 * 40_000 + 500_000));
    }

    @Test
    void healthInsuranceBeforeMedicareAddsToTheEarlyYears() {
        Result result = RetirementPlanner.plan(BORN, TODAY, List.of(FLAT),
                scenario(0, 0, dollars(10_000), 0, 0, 62, List.of(), List.of()));

        // 62, 63, 64 at $50,000, then $40,000 ÷ 4%.
        assertThat(result.target().needed()).isEqualTo(dollars(3 * 50_000 + 1_000_000));
    }

    @Test
    void retiringBeforeSixtyNeedsTaxableMoneyToBridgeTheYears() {
        Pot ira = new Pot(TaxType.TRADITIONAL, dollars(10_000_000), Money.ZERO);

        Result result = RetirementPlanner.plan(BORN, TODAY, List.of(ira), scenario(0, 0, Money.ZERO, 500, 0, 58, List.of(), List.of()));

        Target target = result.target();
        assertThat(target.enough()).isFalse();
        assertThat(target.early().years()).isEqualTo(2);
        assertThat(target.early().covered()).isZero();
        // So the extra goes where it can bridge: taxable money for $80,000 by 58. It grows at 5%, so less than
        // $80,000 ÷ 264 months.
        assertThat(target.extraTaxable()).isTrue();
        assertThat(target.extraMonthly().cents()).isBetween(10_000L, 30_303L);
        // Nothing before 60 is enough on IRA money alone; 60 is.
        assertThat(result.earliestAge()).isEqualTo(60);
    }

    @Test
    void theExtraAMonthIsJustEnough() {
        Result result = RetirementPlanner.plan(BORN, TODAY, List.of(FLAT), plain(61, List.of(), List.of()));

        Target target = result.target();
        // $850,000 by 61 of $1,000,000: $150,000 more over 300 months.
        assertThat(target.projected()).isEqualTo(dollars(850_000));
        assertThat(target.shortfall()).isEqualTo(dollars(150_000));
        assertThat(target.extraMonthly()).isEqualTo(dollars(500));
        assertThat(target.extraTaxable()).isFalse();
    }

    @Test
    void anAgeThatIsAlreadyEnoughNeedsNoExtra() {
        Target target = RetirementPlanner.plan(BORN, TODAY, List.of(FLAT), plain(70, List.of(), List.of())).target();

        assertThat(target.enough()).isTrue();
        assertThat(target.extraMonthly()).isNull();
        assertThat(target.early()).isNull();
    }

    @Test
    void aOneOffAtAnAgeCountsFromThen() {
        Result result = RetirementPlanner.plan(BORN, TODAY, List.of(FLAT),
                plain(null, List.of(), List.of(new OneOff(dollars(100_000), 50))));

        // $200,000 + $30,000 a year reaches $1,000,000 after 27 years.
        assertThat(result.earliestAge()).isEqualTo(63);
        assertThat(at(result, 49).projected()).isEqualTo(dollars(100_000 + 13 * 30_000));
        assertThat(at(result, 50).projected()).isEqualTo(dollars(200_000 + 14 * 30_000));
    }

    @Test
    void aOneOffStillToComeLowersWhatIsNeeded() {
        Result result = RetirementPlanner.plan(BORN, TODAY, List.of(FLAT),
                plain(null, List.of(), List.of(new OneOff(dollars(100_000), 80))));

        assertThat(at(result, 60).needed()).isEqualTo(dollars(900_000));
        assertThat(at(result, 80).needed()).isEqualTo(dollars(1_000_000));
    }

    @Test
    void anAgeNoAmountCanReachSaysSo() {
        Scenario huge = new Scenario(dollars(100_000_000), BasisPoints.of(0), BasisPoints.of(100), BasisPoints.of(0), Money.ZERO,
                Money.ZERO, BasisPoints.of(0), BasisPoints.of(0), BasisPoints.of(0), BasisPoints.of(0), BasisPoints.of(0), Money.ZERO,
                TaxType.TAXABLE, 37, List.of(), List.of());

        Target target = RetirementPlanner.plan(BORN, TODAY, List.of(FLAT), huge).target();

        assertThat(target.reachable()).isFalse();
        assertThat(target.extraMonthly()).isNull();
    }

    @Test
    void halfwayThroughAYearTheNextBirthdayComesFirst() {
        Result result = RetirementPlanner.plan(BORN, LocalDate.of(2026, 7, 1), List.of(FLAT), plain(null, List.of(), List.of()));

        assertThat(result.ageNow()).isEqualTo(36);
        assertThat(result.byAge().getFirst().age()).isEqualTo(37);
        // Six months in: $100,000 + $15,000.
        assertThat(at(result, 37).projected()).isEqualTo(dollars(115_000));
    }
}
