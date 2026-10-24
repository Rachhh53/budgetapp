package app.budget.server.retirement;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.retirement.TaxFigures;
import app.budget.engine.retirement.TaxFigures.Bracket;
import app.budget.engine.retirement.TaxFigures.Filing;
import app.budget.server.api.ApiException;
import java.util.ArrayList;
import java.util.List;

/**
 * The tax and Medicare figures as the page shows and edits them, and as they are kept when changed (spec §5.12): whole
 * cents and basis points, the brackets as their tops and rates. Sent whole and checked whole, as one figure only makes
 * sense beside the others.
 *
 * @param partBCents a month, in each Medicare income bracket, the standard premium first
 * @param partDCents the Part D surcharge a month in each bracket, nothing in the first
 */
public record FiguresView(
        Integer year,
        FilingView single,
        FilingView joint,
        Integer seniorDeductionLastYear,
        Integer seniorPhaseOutBp,
        Integer niitBp,
        List<Long> partBCents,
        List<Long> partDCents) {

    /**
     * @param bracketTopsCents where each bracket but the top one ends, as taxable income
     * @param bracketRatesBp   each bracket's rate, one more than the tops
     * @param medicareFromCents where each Medicare income bracket starts, 0 first
     */
    public record FilingView(
            List<Long> bracketTopsCents,
            List<Integer> bracketRatesBp,
            Long standardDeductionCents,
            Long extraAt65Cents,
            Long seniorDeductionCents,
            Long seniorPhaseOutFromCents,
            Long zeroGainsUpToCents,
            Long fifteenGainsUpToCents,
            Long socialSecurityBaseCents,
            Long socialSecurityUpperCents,
            Long niitFromCents,
            List<Long> medicareFromCents) {

        static FilingView of(Filing filing) {
            List<Bracket> brackets = filing.brackets();
            return new FilingView(
                    brackets.stream().filter(bracket -> bracket.upTo() != null).map(bracket -> bracket.upTo().cents()).toList(),
                    brackets.stream().map(bracket -> bracket.rate().value()).toList(),
                    filing.standardDeduction().cents(),
                    filing.extraAt65().cents(),
                    filing.seniorDeduction().cents(),
                    filing.seniorPhaseOutFrom().cents(),
                    filing.zeroGainsUpTo().cents(),
                    filing.fifteenGainsUpTo().cents(),
                    filing.socialSecurityBase().cents(),
                    filing.socialSecurityUpper().cents(),
                    filing.niitFrom().cents(),
                    filing.medicareFrom().stream().map(Money::cents).toList());
        }

        Filing toFiling(String who) {
            if (bracketTopsCents == null || bracketRatesBp == null || bracketRatesBp.size() < 2 || bracketRatesBp.size() > 12
                    || bracketTopsCents.size() != bracketRatesBp.size() - 1) {
                throw ApiException.badRequest("Please give each " + who + " tax bracket its rate, and each but the top one where it ends.");
            }
            List<Bracket> brackets = new ArrayList<>();
            for (int i = 0; i < bracketRatesBp.size(); i++) {
                Money upTo = i < bracketTopsCents.size() ? amount(bracketTopsCents.get(i), "Where each " + who + " tax bracket ends") : null;
                brackets.add(new Bracket(upTo, rate(bracketRatesBp.get(i), "Each " + who + " tax bracket's rate")));
            }
            if (medicareFromCents == null || medicareFromCents.size() < 2 || medicareFromCents.size() > 10) {
                throw ApiException.badRequest("Please give where each " + who + " Medicare income bracket starts.");
            }
            try {
                return new Filing(
                        brackets,
                        amount(standardDeductionCents, "The " + who + " standard deduction"),
                        amount(extraAt65Cents, "The " + who + " extra deduction at 65"),
                        amount(seniorDeductionCents, "The " + who + " senior deduction"),
                        amount(seniorPhaseOutFromCents, "Where the " + who + " senior deduction starts to shrink"),
                        amount(zeroGainsUpToCents, "The " + who + " top of 0% gains"),
                        amount(fifteenGainsUpToCents, "The " + who + " top of 15% gains"),
                        amount(socialSecurityBaseCents, "The " + who + " first Social Security threshold"),
                        amount(socialSecurityUpperCents, "The " + who + " second Social Security threshold"),
                        amount(niitFromCents, "The " + who + " investment income tax threshold"),
                        medicareFromCents.stream().map(cents -> amount(cents, "Where each " + who + " Medicare bracket starts")).toList());
            } catch (IllegalArgumentException wrong) {
                throw ApiException.badRequest(wrong.getMessage());
            }
        }
    }

    public static FiguresView of(TaxFigures figures) {
        return new FiguresView(
                figures.year(),
                FilingView.of(figures.single()),
                FilingView.of(figures.joint()),
                figures.seniorDeductionLastYear(),
                figures.seniorPhaseOut().value(),
                figures.niitRate().value(),
                figures.partB().stream().map(Money::cents).toList(),
                figures.partD().stream().map(Money::cents).toList());
    }

    /** The figures, each checked; a 400 says what is wrong. */
    public TaxFigures toFigures() {
        if (year == null || year < 2020 || year > 2100) {
            throw ApiException.badRequest("Please say which year these figures are for, between 2020 and 2100.");
        }
        if (single == null || joint == null) {
            throw ApiException.badRequest("Please give the figures for filing alone and for married filing jointly.");
        }
        if (seniorDeductionLastYear == null || seniorDeductionLastYear < 2020 || seniorDeductionLastYear > 2100) {
            throw ApiException.badRequest("Please give the last year of the senior deduction, between 2020 and 2100.");
        }
        if (partBCents == null || partDCents == null) {
            throw ApiException.badRequest("Please give the Part B premium and Part D surcharge for each Medicare income bracket.");
        }
        Filing alone = single.toFiling("single");
        Filing married = joint.toFiling("joint");
        try {
            return new TaxFigures(year, alone, married, seniorDeductionLastYear,
                    rate(seniorPhaseOutBp, "How fast the senior deduction shrinks"), rate(niitBp, "The investment income tax rate"),
                    partBCents.stream().map(cents -> amount(cents, "Each Part B premium")).toList(),
                    partDCents.stream().map(cents -> amount(cents, "Each Part D surcharge")).toList());
        } catch (IllegalArgumentException wrong) {
            throw ApiException.badRequest(wrong.getMessage());
        }
    }

    private static Money amount(Long cents, String what) {
        if (cents == null || cents < 0 || cents > 100_000_000_000L) {
            throw ApiException.badRequest(what + " needs an amount of $0.00 or more.");
        }
        return Money.ofCents(cents);
    }

    private static BasisPoints rate(Integer bp, String what) {
        if (bp == null || bp < 0 || bp > 10_000) {
            throw ApiException.badRequest(what + " needs to be between 0% and 100%.");
        }
        return BasisPoints.of(bp);
    }
}
