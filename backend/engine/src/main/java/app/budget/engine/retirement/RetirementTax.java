package app.budget.engine.retirement;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.retirement.TaxFigures.Bracket;
import app.budget.engine.retirement.TaxFigures.Filing;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

/**
 * Federal income tax on one year in retirement, the way the return works it out (spec §5.12), in today's cents:
 * how much of Social Security is taxed, adjusted gross income, the standard deduction with what is added at 65, the
 * ordinary brackets, long-term gains stacked on top at 0%, 15% and 20%, and the net investment income tax. Its income is
 * also Medicare's: what picks the premium two years later (MAGI) is adjusted gross income, as no tax-exempt interest is
 * modelled. An estimate, not tax advice.
 */
public final class RetirementTax {

    private static final MathContext MATH = MathContext.DECIMAL64;
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal EIGHTY_FIVE = new BigDecimal("0.85");
    private static final BasisPoints FIFTEEN = BasisPoints.of(1500);
    private static final BasisPoints TWENTY = BasisPoints.of(2000);

    private RetirementTax() {}

    /**
     * Who files, and when.
     *
     * @param people65   how many on the return are 65 or over that year: each adds to the deduction
     * @param fixedShare what a dollar fixed in law is worth in today's dollars that year (prices now ÷ prices then): the
     *                   Social Security and net investment income thresholds don't rise with prices
     */
    public record Year(TaxFigures figures, TaxFigures.Status status, int calendarYear, int people65, BigDecimal fixedShare) {

        Filing filing() {
            return figures.filing(status);
        }
    }

    /**
     * One year's income, in today's cents.
     *
     * @param ordinary       taxed as ordinary income: traditional withdrawals, pensions, other income as far as it is taxed
     * @param socialSecurity Social Security benefits, before working out how much of them is taxed
     * @param gains          long-term capital gains: the part of taxable-account withdrawals that is growth
     */
    public record Income(BigDecimal ordinary, BigDecimal socialSecurity, BigDecimal gains) {}

    /**
     * @param taxedSocialSecurity how much of Social Security counts as income
     * @param agi                 adjusted gross income, which is also what Medicare's premium goes by (MAGI)
     * @param deductions          the standard deduction with what is added at 65
     * @param taxable             taxable income
     * @param ordinaryTax         on taxable income other than gains
     * @param gainsTax            on long-term gains
     * @param niit                net investment income tax
     * @param marginal            the ordinary bracket the last dollar of ordinary income falls in
     */
    public record Tax(
            BigDecimal taxedSocialSecurity,
            BigDecimal agi,
            BigDecimal deductions,
            BigDecimal taxable,
            BigDecimal ordinaryTax,
            BigDecimal gainsTax,
            BigDecimal niit,
            BasisPoints marginal) {

        public BigDecimal federal() {
            return ordinaryTax.add(gainsTax).add(niit);
        }
    }

    public static Tax of(Year year, Income income) {
        Filing filing = year.filing();
        BigDecimal ordinary = income.ordinary().max(BigDecimal.ZERO);
        BigDecimal gains = income.gains().max(BigDecimal.ZERO);
        BigDecimal taxedSocialSecurity = taxedSocialSecurity(filing, year.fixedShare(), ordinary.add(gains), income.socialSecurity().max(BigDecimal.ZERO));
        BigDecimal agi = ordinary.add(gains).add(taxedSocialSecurity);

        BigDecimal deductions = cents(filing.standardDeduction()).add(cents(filing.extraAt65()).multiply(BigDecimal.valueOf(year.people65())));
        if (year.people65() > 0 && year.calendarYear() <= year.figures().seniorDeductionLastYear()) {
            BigDecimal senior = cents(filing.seniorDeduction()).multiply(BigDecimal.valueOf(year.people65()));
            BigDecimal over = agi.subtract(cents(filing.seniorPhaseOutFrom())).max(BigDecimal.ZERO);
            deductions = deductions.add(senior.subtract(over.multiply(year.figures().seniorPhaseOut().toFraction(), MATH)).max(BigDecimal.ZERO));
        }
        BigDecimal taxable = agi.subtract(deductions).max(BigDecimal.ZERO);
        // Gains are the last of taxable income: what is left after them is ordinary.
        BigDecimal taxableGains = gains.min(taxable);
        BigDecimal taxableOrdinary = taxable.subtract(taxableGains);

        BigDecimal ordinaryTax = BigDecimal.ZERO;
        BigDecimal from = BigDecimal.ZERO;
        BasisPoints marginal = filing.brackets().getFirst().rate();
        for (Bracket bracket : filing.brackets()) {
            BigDecimal upTo = bracket.upTo() == null ? null : cents(bracket.upTo());
            BigDecimal inBracket = (upTo == null ? taxableOrdinary : taxableOrdinary.min(upTo)).subtract(from).max(BigDecimal.ZERO);
            ordinaryTax = ordinaryTax.add(inBracket.multiply(bracket.rate().toFraction(), MATH));
            if (taxableOrdinary.compareTo(from) > 0) {
                marginal = bracket.rate();
            }
            if (upTo == null || taxableOrdinary.compareTo(upTo) <= 0) {
                break;
            }
            from = upTo;
        }

        // Gains stack on top of ordinary income: the part below the 0% top is free, then 15%, then 20%.
        BigDecimal zeroTop = cents(filing.zeroGainsUpTo());
        BigDecimal fifteenTop = cents(filing.fifteenGainsUpTo());
        BigDecimal top = taxableOrdinary.add(taxableGains);
        BigDecimal atZero = zeroTop.min(top).subtract(taxableOrdinary).max(BigDecimal.ZERO);
        BigDecimal atFifteen = fifteenTop.min(top).subtract(taxableOrdinary.max(zeroTop)).max(BigDecimal.ZERO);
        BigDecimal atTwenty = taxableGains.subtract(atZero).subtract(atFifteen).max(BigDecimal.ZERO);
        BigDecimal gainsTax = atFifteen.multiply(FIFTEEN.toFraction(), MATH).add(atTwenty.multiply(TWENTY.toFraction(), MATH));

        BigDecimal niitOver = agi.subtract(cents(filing.niitFrom()).multiply(year.fixedShare(), MATH)).max(BigDecimal.ZERO);
        BigDecimal niit = gains.min(niitOver).multiply(year.figures().niitRate().toFraction(), MATH);
        return new Tax(taxedSocialSecurity, agi, deductions, taxable, ordinaryTax, gainsTax, niit, marginal);
    }

    /**
     * How much of Social Security is taxed (IRS Publication 915): none while provisional income (other income + half of
     * Social Security) is under the first threshold, up to half between the two, and up to 85% above.
     */
    static BigDecimal taxedSocialSecurity(Filing filing, BigDecimal fixedShare, BigDecimal otherIncome, BigDecimal socialSecurity) {
        if (socialSecurity.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal base = cents(filing.socialSecurityBase()).multiply(fixedShare, MATH);
        BigDecimal upper = cents(filing.socialSecurityUpper()).multiply(fixedShare, MATH);
        BigDecimal provisional = otherIncome.add(socialSecurity.multiply(HALF, MATH));
        if (provisional.compareTo(base) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal halfOfIt = socialSecurity.multiply(HALF, MATH);
        if (provisional.compareTo(upper) <= 0) {
            return provisional.subtract(base).multiply(HALF, MATH).min(halfOfIt);
        }
        BigDecimal firstBand = upper.subtract(base).multiply(HALF, MATH).min(halfOfIt);
        return provisional.subtract(upper).multiply(EIGHTY_FIVE, MATH).add(firstBand).min(socialSecurity.multiply(EIGHTY_FIVE, MATH));
    }

    /** The top of each ordinary bracket as taxable income, lowest first; the top bracket has none. */
    public static List<Money> bracketTops(Filing filing) {
        return filing.brackets().stream().filter(bracket -> bracket.upTo() != null).map(Bracket::upTo).toList();
    }

    static BigDecimal cents(Money money) {
        return new BigDecimal(money.cents());
    }
}
