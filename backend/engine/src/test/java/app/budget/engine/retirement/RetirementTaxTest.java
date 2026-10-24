package app.budget.engine.retirement;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.retirement.RetirementTax.Income;
import app.budget.engine.retirement.RetirementTax.Tax;
import app.budget.engine.retirement.TaxFigures.Status;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Federal tax on a year in retirement, worked by hand from the 2026 figures (spec §5.12). */
class RetirementTaxTest {

    private static BigDecimal dollars(long dollars) {
        return BigDecimal.valueOf(dollars * 100);
    }

    private static RetirementTax.Year year(Status status, int calendarYear, int people65) {
        return new RetirementTax.Year(TaxFigures.Y2026, status, calendarYear, people65, BigDecimal.ONE);
    }

    private static Tax tax(RetirementTax.Year year, long ordinary, long socialSecurity, long gains) {
        return RetirementTax.of(year, new Income(dollars(ordinary), dollars(socialSecurity), dollars(gains)));
    }

    @Test
    void ordinaryIncomeGoesThroughTheBracketsAfterTheStandardDeduction() {
        Tax tax = tax(year(Status.SINGLE, 2026, 0), 50_000, 0, 0);

        // $50,000 − $16,100 = $33,900: $12,400 at 10% and $21,500 at 12%.
        assertThat(tax.taxable()).isEqualByComparingTo(dollars(33_900));
        assertThat(tax.federal()).isEqualByComparingTo(dollars(3_820));
        assertThat(tax.marginal().value()).isEqualTo(1200);
    }

    @Test
    void marriedFilingJointlyHasWiderBrackets() {
        Tax tax = tax(year(Status.JOINT, 2026, 0), 100_000, 0, 0);

        // $100,000 − $32,200 = $67,800: $24,800 at 10% and $43,000 at 12%.
        assertThat(tax.federal()).isEqualByComparingTo(dollars(2_480 + 5_160));
    }

    @Test
    void socialSecurityIsTaxedOnlyPastItsThresholds() {
        RetirementTax.Year single = year(Status.SINGLE, 2026, 0);
        // Provisional $20,000 + $12,000 = $32,000: half of what is over $25,000.
        assertThat(tax(single, 20_000, 24_000, 0).taxedSocialSecurity()).isEqualByComparingTo(dollars(3_500));
        // Provisional $55,000: 85% of what is over $34,000 and $4,500 below, at most 85% of the benefit.
        assertThat(tax(single, 40_000, 30_000, 0).taxedSocialSecurity()).isEqualByComparingTo(dollars(22_350));
        assertThat(tax(single, 200_000, 30_000, 0).taxedSocialSecurity()).isEqualByComparingTo(dollars(25_500));
        // Under $25,000 none of it.
        assertThat(tax(single, 5_000, 20_000, 0).taxedSocialSecurity()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void thresholdsFixedInLawShrinkInTodaysDollars() {
        // Prices doubled: the $25,000 threshold is worth $12,500 of today's.
        RetirementTax.Year later = new RetirementTax.Year(TaxFigures.Y2026, Status.SINGLE, 2050, 0, new BigDecimal("0.5"));

        assertThat(RetirementTax.of(later, new Income(dollars(10_000), dollars(10_000), BigDecimal.ZERO)).taxedSocialSecurity())
                .isEqualByComparingTo(dollars(1_250));
    }

    @Test
    void at65TheDeductionGrowsAndTheSeniorDeductionShrinksWithIncome() {
        assertThat(tax(year(Status.SINGLE, 2026, 1), 50_000, 0, 0).deductions()).isEqualByComparingTo(dollars(16_100 + 2_050 + 6_000));
        // $25,000 over $75,000 takes 6% of it, $1,500, off the $6,000.
        assertThat(tax(year(Status.SINGLE, 2026, 1), 100_000, 0, 0).deductions()).isEqualByComparingTo(dollars(16_100 + 2_050 + 4_500));
        // After 2028 there is no senior deduction.
        assertThat(tax(year(Status.SINGLE, 2029, 1), 50_000, 0, 0).deductions()).isEqualByComparingTo(dollars(16_100 + 2_050));
        // Two on a joint return.
        assertThat(tax(year(Status.JOINT, 2026, 2), 60_000, 0, 0).deductions()).isEqualByComparingTo(dollars(32_200 + 3_300 + 12_000));
    }

    @Test
    void gainsStackOnTopOfOrdinaryIncome() {
        Tax tax = tax(year(Status.SINGLE, 2026, 0), 30_000, 0, 40_000);

        // Taxable $53,900, of it $40,000 gains: $13,900 ordinary, then $35,550 of gains at 0% and $4,450 at 15%.
        assertThat(tax.ordinaryTax()).isEqualByComparingTo(dollars(1_240 + 180));
        assertThat(tax.gainsTax()).isEqualByComparingTo(new BigDecimal("66750"));
        assertThat(tax.niit()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void gainsOverTheThresholdCarryTheNetInvestmentIncomeTax() {
        Tax tax = tax(year(Status.SINGLE, 2026, 0), 150_000, 0, 100_000);

        // $250,000 is $50,000 over $200,000: 3.8% of it.
        assertThat(tax.niit()).isEqualByComparingTo(dollars(1_900));
    }
}
