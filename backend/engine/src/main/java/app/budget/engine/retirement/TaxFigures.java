package app.budget.engine.retirement;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import java.util.List;
import java.util.Objects;

/**
 * The published figures that federal tax and Medicare premiums in retirement are worked out from (spec §5.12; Rachel,
 * 2026-10-23: "is there an algorithm we can use - make it transparent and editable?"). The built-in ones are those for
 * {@link #year}; every one of them can be changed, as they change every year and Budget never looks them up.
 *
 * <p>Amounts are in the dollars of {@link #year}. Brackets, deductions, the capital gains bands and the Medicare income
 * brackets rise with prices every year, so in today's dollars they stay as they are; the Social Security and net
 * investment income thresholds are fixed in law, so in today's dollars they shrink by inflation.
 *
 * @param seniorDeductionLastYear the last year of the extra deduction for those 65 and over (2025–2028)
 * @param seniorPhaseOut          how much of each dollar of income over its start the senior deduction loses (6%)
 * @param niitRate                the net investment income tax on gains over its threshold (3.8%)
 * @param partB                   the monthly Part B premium in each Medicare income bracket, the standard one first
 * @param partD                   the monthly Part D surcharge in each bracket, nothing in the first
 */
public record TaxFigures(
        int year,
        Filing single,
        Filing joint,
        int seniorDeductionLastYear,
        BasisPoints seniorPhaseOut,
        BasisPoints niitRate,
        List<Money> partB,
        List<Money> partD) {

    public TaxFigures {
        Objects.requireNonNull(single);
        Objects.requireNonNull(joint);
        partB = List.copyOf(partB);
        partD = List.copyOf(partD);
        if (partB.size() != partD.size() || partB.size() != single.medicareFrom().size() || partB.size() != joint.medicareFrom().size()) {
            throw new IllegalArgumentException("Each Medicare income bracket needs its Part B premium and its Part D surcharge.");
        }
    }

    /** Filing alone, or married filing jointly. */
    public enum Status { SINGLE, JOINT }

    public Filing filing(Status status) {
        return status == Status.JOINT ? joint : single;
    }

    /**
     * One tax bracket: income up to {@code upTo} is taxed at {@code rate}.
     *
     * @param upTo {@code null} for the top bracket, which has no end
     */
    public record Bracket(Money upTo, BasisPoints rate) {}

    /**
     * The figures for one way of filing.
     *
     * @param brackets          ordinary income brackets, lowest first; the last has no end
     * @param standardDeduction the standard deduction
     * @param extraAt65         the extra standard deduction for each person 65 or over
     * @param seniorDeduction   the further deduction for each person 65 or over, until {@code seniorDeductionLastYear}
     * @param seniorPhaseOutFrom the income above which the senior deduction shrinks
     * @param zeroGainsUpTo     taxable income up to which long-term gains are taxed at 0%
     * @param fifteenGainsUpTo  taxable income up to which they are taxed at 15%; 20% above
     * @param socialSecurityBase the provisional income over which half of Social Security can be taxed (fixed in law)
     * @param socialSecurityUpper the provisional income over which up to 85% can be (fixed in law)
     * @param niitFrom          the income over which gains carry the net investment income tax (fixed in law)
     * @param medicareFrom      where each Medicare income bracket starts: 0, then each income it is more than
     */
    public record Filing(
            List<Bracket> brackets,
            Money standardDeduction,
            Money extraAt65,
            Money seniorDeduction,
            Money seniorPhaseOutFrom,
            Money zeroGainsUpTo,
            Money fifteenGainsUpTo,
            Money socialSecurityBase,
            Money socialSecurityUpper,
            Money niitFrom,
            List<Money> medicareFrom) {

        public Filing {
            brackets = List.copyOf(brackets);
            medicareFrom = List.copyOf(medicareFrom);
            if (brackets.isEmpty() || brackets.getLast().upTo() != null) {
                throw new IllegalArgumentException("The last tax bracket has no end.");
            }
            for (int i = 0; i < brackets.size() - 1; i++) {
                if (brackets.get(i).upTo() == null) {
                    throw new IllegalArgumentException("Only the last tax bracket can be without an end.");
                }
                if (i > 0 && brackets.get(i).upTo().compareTo(brackets.get(i - 1).upTo()) <= 0) {
                    throw new IllegalArgumentException("Each tax bracket has to end above the one before it.");
                }
            }
            if (medicareFrom.isEmpty() || !medicareFrom.getFirst().isZero()) {
                throw new IllegalArgumentException("The first Medicare income bracket starts at $0.");
            }
            for (int i = 1; i < medicareFrom.size(); i++) {
                if (medicareFrom.get(i).compareTo(medicareFrom.get(i - 1)) <= 0) {
                    throw new IllegalArgumentException("Each Medicare income bracket has to start above the one before it.");
                }
            }
        }
    }

    private static Money dollars(long dollars) {
        return Money.ofCents(dollars * 100);
    }

    private static Money cents(long cents) {
        return Money.ofCents(cents);
    }

    private static Bracket bracket(Long upToDollars, int rateBp) {
        return new Bracket(upToDollars == null ? null : dollars(upToDollars), BasisPoints.of(rateBp));
    }

    /**
     * The 2026 figures: IRS Rev. Proc. 2025-32 (brackets, standard deduction, capital gains), the One Big Beautiful Bill
     * Act's $6,000 senior deduction (2025–2028), and CMS's 2026 Part B and Part D income-related amounts. To be checked
     * against irs.gov and medicare.gov; every one can be changed in the app.
     */
    public static final TaxFigures Y2026 = new TaxFigures(
            2026,
            new Filing(
                    List.of(bracket(12_400L, 1000), bracket(50_400L, 1200), bracket(105_700L, 2200), bracket(201_775L, 2400),
                            bracket(256_225L, 3200), bracket(640_600L, 3500), bracket(null, 3700)),
                    dollars(16_100), dollars(2_050), dollars(6_000), dollars(75_000),
                    dollars(49_450), dollars(545_500),
                    dollars(25_000), dollars(34_000), dollars(200_000),
                    List.of(dollars(0), dollars(109_000), dollars(137_000), dollars(171_000), dollars(205_000), dollars(500_000))),
            new Filing(
                    List.of(bracket(24_800L, 1000), bracket(100_800L, 1200), bracket(211_400L, 2200), bracket(403_550L, 2400),
                            bracket(512_450L, 3200), bracket(768_700L, 3500), bracket(null, 3700)),
                    dollars(32_200), dollars(1_650), dollars(6_000), dollars(150_000),
                    dollars(98_900), dollars(613_700),
                    dollars(32_000), dollars(44_000), dollars(250_000),
                    List.of(dollars(0), dollars(218_000), dollars(274_000), dollars(342_000), dollars(410_000), dollars(750_000))),
            2028,
            BasisPoints.of(600),
            BasisPoints.of(380),
            List.of(cents(20_290), cents(28_410), cents(40_580), cents(52_750), cents(64_920), cents(68_990)),
            List.of(cents(0), cents(1_450), cents(3_750), cents(6_040), cents(8_330), cents(9_100)));
}
