package app.budget.engine.stubs;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.Money;
import app.budget.engine.stubs.PayStub.Line;
import app.budget.engine.stubs.PayStub.Read;
import app.budget.engine.stubs.PayStub.Remembered;
import app.budget.engine.stubs.PayStub.Row;
import app.budget.engine.stubs.PayStub.Section;
import app.budget.engine.stubs.PayStub.Totals;
import app.budget.engine.stubs.PayStub.Tracking;
import app.budget.engine.stubs.PayStub.Word;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Reading a pay stub from where its words sit (spec Phase 8). Every stub here is made up, in
 * the layouts payroll companies commonly use; the figures are the mockup's (design/pay-stub.png):
 * gross $3,600.00 − $1,113.80 taken out = $2,486.20 net.
 */
class StubReaderTest {

    /** About the width of a character in a stub's small type, in points. */
    private static final double CHAR = 5;

    // Where the columns of the made-up stubs end.
    private static final double CURRENT = 380;
    private static final double YTD = 480;

    /** Words starting at {@code x}, one after another. */
    private static List<Word> at(double x, String text) {
        List<Word> words = new ArrayList<>();
        for (String part : text.split(" ")) {
            words.add(new Word(x, x + part.length() * CHAR, part));
            x += (part.length() + 1) * CHAR;
        }
        return words;
    }

    /** One word ending at {@code endX}, the way a column of amounts is set. */
    private static List<Word> ending(double endX, String text) {
        return List.of(new Word(endX - text.length() * CHAR, endX, text));
    }

    @SafeVarargs
    private static Row row(List<Word>... parts) {
        List<Word> words = new ArrayList<>();
        for (List<Word> part : parts) {
            words.addAll(part);
        }
        return new Row(1, words);
    }

    private static Row heads(String title) {
        return row(at(40, title), ending(CURRENT, "Current"), ending(YTD, "YTD"));
    }

    private static Row line(String label, String current, String ytd) {
        return row(at(40, label), ending(CURRENT, current), ending(YTD, ytd));
    }

    private static Money cents(long cents) {
        return Money.ofCents(cents);
    }

    private static Line find(Read read, String label) {
        return read.lines().stream().filter(line -> line.label().equals(label)).findFirst().orElseThrow();
    }

    /** Groups one under another, each with its own "Current" and "YTD". */
    private static List<Row> stacked() {
        return List.of(
                row(at(40, "Acme Corp"), at(300, "Pay Date: 10/01/2026")),
                row(at(40, "Pay Period 09/16/2026 - 09/30/2026")),
                heads("Earnings"),
                line("Regular pay", "3,600.00", "68,400.00"),
                line("Gross Pay", "3,600.00", "68,400.00"),
                heads("Before-Tax Deductions"),
                line("401(k)", "150.00", "2,850.00"),
                line("HSA", "50.00", "950.00"),
                line("Medical", "86.40", "1,641.60"),
                line("Dental", "9.20", "174.80"),
                line("Vision", "3.10", "58.90"),
                heads("Taxes"),
                line("Federal income tax", "391.78", "7,443.82"),
                line("State income tax", "159.30", "3,026.70"),
                line("Social Security", "213.98", "4,065.62"),
                line("Medicare", "50.04", "950.76"),
                line("Total Deductions", "1,113.80", "21,162.20"),
                heads("Employer Paid Benefits"),
                line("Employer 401(k) match", "75.00", "1,425.00"),
                line("Net Pay", "2,486.20", "47,237.80"));
    }

    @Test
    void readsAStubWhoseGroupsAreStackedAndAddsUp() {
        Read read = StubReader.read(stacked(), Map.of());

        assertThat(read.payDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(read.lines()).extracting(Line::label).containsExactly(
                "Regular pay", "401(k)", "HSA", "Medical", "Dental", "Vision",
                "Federal income tax", "State income tax", "Social Security", "Medicare", "Net pay", "Employer 401(k) match");
        assertThat(read.lines()).allMatch(Line::sure);
        assertThat(find(read, "Regular pay")).isEqualTo(
                new Line(Section.EARNINGS, "Regular pay", cents(360_000), cents(6_840_000), Tracking.GROSS, true));
        assertThat(find(read, "401(k)").tracking()).isEqualTo(Tracking.CONTRIBUTION);
        assertThat(find(read, "HSA").tracking()).isEqualTo(Tracking.CONTRIBUTION);
        assertThat(find(read, "Vision")).isEqualTo(
                new Line(Section.PRE_TAX, "Vision", cents(310), cents(5_890), Tracking.BENEFIT, true));
        assertThat(find(read, "Medicare").section()).isEqualTo(Section.TAX);
        assertThat(find(read, "Employer 401(k) match")).isEqualTo(
                new Line(Section.EMPLOYER, "Employer 401(k) match", cents(7_500), cents(142_500), Tracking.MATCH, true));
        assertThat(read.statedGross()).isEqualTo(cents(360_000));
        assertThat(read.statedNet()).isEqualTo(cents(248_620));

        Totals totals = Totals.of(read.lines());
        assertThat(totals.gross()).isEqualTo(cents(360_000));
        assertThat(totals.deductions()).isEqualTo(cents(111_380));
        assertThat(totals.net()).isEqualTo(cents(248_620));
        assertThat(totals.employer()).isEqualTo(cents(7_500));
        assertThat(totals.balanced()).isTrue();
    }

    @Test
    void readsEarningsAndDeductionsSetSideBySide() {
        // Earnings on the left with hours and a rate; what is taken out on the right, each with its own columns.
        List<Row> rows = List.of(
                row(at(40, "Check Date"), at(140, "Period Ending")),
                row(at(40, "10/01/2026"), at(140, "09/30/2026")),
                row(at(40, "Earnings"), at(320, "Taxes")),
                row(ending(130, "Rate"), ending(170, "Hours"), ending(230, "Current"), ending(300, "YTD"),
                        ending(500, "Current"), ending(570, "YTD")),
                row(at(40, "Regular"), ending(130, "45.0000"), ending(170, "80.00"), ending(230, "3,600.00"), ending(300, "68,400.00"),
                        at(320, "Federal Income Tax"), ending(500, "391.78"), ending(570, "7,443.82")),
                row(at(320, "Social Security"), ending(500, "213.98"), ending(570, "4,065.62")),
                row(at(320, "Medicare"), ending(500, "50.04"), ending(570, "950.76")),
                row(at(320, "CA State Income Tax"), ending(500, "159.30"), ending(570, "3,026.70")),
                row(at(320, "Deductions")),
                row(at(320, "401K"), ending(500, "150.00"), ending(570, "2,850.00")),
                row(at(320, "Medical Pre-Tax"), ending(500, "148.70"), ending(570, "2,825.30")),
                row(at(40, "Net Pay"), ending(230, "$2,486.20")));

        Read read = StubReader.read(rows, Map.of());

        assertThat(read.payDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        // The 80.00 hours are not mistaken for pay: they sit under "Hours".
        assertThat(find(read, "Regular")).isEqualTo(
                new Line(Section.EARNINGS, "Regular", cents(360_000), cents(6_840_000), Tracking.GROSS, true));
        assertThat(find(read, "Federal Income Tax")).isEqualTo(
                new Line(Section.TAX, "Federal Income Tax", cents(39_178), cents(744_382), Tracking.TAX, true));
        assertThat(find(read, "401K")).isEqualTo(
                new Line(Section.PRE_TAX, "401K", cents(15_000), cents(285_000), Tracking.CONTRIBUTION, true));
        assertThat(find(read, "Medical Pre-Tax").tracking()).isEqualTo(Tracking.BENEFIT);
        assertThat(find(read, "Net pay").amount()).isEqualTo(cents(248_620));
        assertThat(Totals.of(read.lines()).balanced()).isTrue();
    }

    @Test
    void readsAPlainListWithNoHeadingsAndMinusSigns() {
        List<Row> rows = List.of(
                row(at(40, "Paid on October 1, 2026")),
                row(at(40, "Gross Pay"), ending(CURRENT, "3,600.00")),
                row(at(40, "Federal Tax"), ending(CURRENT, "-391.78")),
                row(at(40, "State Tax"), ending(CURRENT, "(159.30)")),
                row(at(40, "FICA"), ending(CURRENT, "264.02-")),
                row(at(40, "401k"), ending(CURRENT, "-150.00")),
                row(at(40, "Health Insurance"), ending(CURRENT, "-148.70")),
                row(at(40, "Check Number"), ending(CURRENT, "100234.00")),
                row(at(40, "Net Pay"), ending(CURRENT, "2,486.20")));

        Read read = StubReader.read(rows, Map.of());

        assertThat(read.payDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        // With no earnings lines, the stub's own gross pay is the gross; the check number is no part of the pay.
        assertThat(read.lines()).extracting(Line::label)
                .containsExactly("Gross pay", "401k", "Health Insurance", "Federal Tax", "State Tax", "FICA", "Net pay");
        assertThat(find(read, "State Tax").amount()).isEqualTo(cents(15_930));
        assertThat(find(read, "FICA").amount()).isEqualTo(cents(26_402));
        assertThat(find(read, "401k").ytd()).isNull();
        assertThat(Totals.of(read.lines()).balanced()).isTrue();
    }

    @Test
    void pointsOutALineItCouldOnlyGuessAt() {
        List<Row> rows = List.of(
                heads("Deductions"),
                line("Misc 12", "20.00", "380.00"),
                line("Roth 401(k)", "100.00", "1,900.00"));

        Read read = StubReader.read(rows, Map.of());

        assertThat(find(read, "Misc 12")).isEqualTo(
                new Line(Section.PRE_TAX, "Misc 12", cents(2_000), cents(38_000), Tracking.BENEFIT, false));
        // "Roth" says after tax even under a plain "Deductions".
        assertThat(find(read, "Roth 401(k)")).isEqualTo(
                new Line(Section.AFTER_TAX, "Roth 401(k)", cents(10_000), cents(190_000), Tracking.CONTRIBUTION, true));
    }

    @Test
    void doubtsPayPickedFromAmongHoursAndRateWithNoHeadings() {
        List<Row> rows = List.of(
                row(at(40, "Earnings")),
                row(at(40, "Regular"), ending(200, "80.00"), ending(260, "45.00"), ending(CURRENT, "3,600.00"), ending(YTD, "68,400.00")));

        Line regular = find(StubReader.read(rows, Map.of()), "Regular");

        assertThat(regular.amount()).isEqualTo(cents(360_000));
        assertThat(regular.ytd()).isEqualTo(cents(6_840_000));
        assertThat(regular.sure()).isFalse();
    }

    @Test
    void filesALineTheWayItWasFiledOnAnEarlierStub() {
        Map<String, Remembered> remembered = Map.of(
                PayStub.key("Misc 12"), new Remembered(Section.AFTER_TAX, Tracking.CONTRIBUTION),
                // Even a line that reads like a total is kept once someone has filed it.
                PayStub.key("Total Rewards"), new Remembered(Section.EARNINGS, Tracking.GROSS));
        List<Row> rows = List.of(
                heads("Deductions"),
                line("MISC 12", "20.00", "380.00"),
                line("Total Rewards", "5.00", "95.00"));

        Read read = StubReader.read(rows, remembered);

        assertThat(find(read, "MISC 12")).isEqualTo(
                new Line(Section.AFTER_TAX, "MISC 12", cents(2_000), cents(38_000), Tracking.CONTRIBUTION, true));
        assertThat(find(read, "Total Rewards").section()).isEqualTo(Section.EARNINGS);
    }

    @Test
    void keepsALineWithNothingThisPaycheckButAYearToDate() {
        List<Row> rows = List.of(heads("Earnings"), row(at(40, "Bonus"), ending(YTD, "2,000.00")));

        assertThat(find(StubReader.read(rows, Map.of()), "Bonus")).isEqualTo(
                new Line(Section.EARNINGS, "Bonus", Money.ZERO, cents(200_000), Tracking.GROSS, true));
    }

    @Test
    void leavesOutWhereTheNetPayWasDeposited() {
        List<Row> rows = List.of(
                heads("Earnings"),
                line("Salary", "3,600.00", "68,400.00"),
                row(at(40, "Net Pay Distribution")),
                row(at(40, "Checking ****1234"), ending(CURRENT, "2,486.20")));

        // Not a line of its own; and with no net pay printed, what was deposited is the net pay.
        Read read = StubReader.read(rows, Map.of());
        assertThat(read.lines()).extracting(Line::label).containsExactly("Salary", "Net pay");
        assertThat(find(read, "Net pay").amount()).isEqualTo(cents(248_620));
    }

    /**
     * A statement in the layout a large payroll company uses (made up, like the rest): earnings
     * beside an information column, deductions indented under "Statutory" and "Other", amounts
     * taken out shown with a minus sign and a footnote's asterisk, and after the net pay the
     * account it went to, "Net Check", the taxable wages in a sentence, and a tear-off advice.
     */
    private static List<Row> statement() {
        return List.of(
                row(at(300, "Pay Date: 10/01/2026")),
                row(at(40, "Earnings"), ending(200, "rate"), ending(250, "hours"), at(290, "this period"), at(370, "year to date"),
                        at(450, "Other Benefits and Information"), at(600, "this period"), at(680, "total to date")),
                row(at(40, "Regular"), ending(200, "45.0000"), ending(250, "80.00"), ending(345, "3,600.00"), ending(430, "68,400.00"),
                        at(450, "Group Term Life"), ending(655, "0.51"), ending(745, "9.69")),
                row(at(180, "Gross Pay"), ending(345, "$3,600.00"), ending(430, "68,400.00"), at(450, "Vac Hrs"), ending(745, "40.00")),
                row(at(40, "Deductions"), at(100, "Statutory")),
                row(at(100, "Federal Income Tax"), ending(345, "-391.78"), ending(430, "7,443.82"), at(450, "Important Notes")),
                row(at(100, "Social Security Tax"), ending(345, "-213.98"), ending(430, "4,065.62"),
                        at(450, "Loan Amt Paid"), ending(745, "840.00")),
                row(at(100, "Medicare Tax"), ending(345, "-50.04"), ending(430, "950.76")),
                row(at(100, "CA State Income Tax"), ending(345, "-159.30"), ending(430, "3,026.70")),
                row(at(100, "Other")),
                row(at(100, "401(k)"), ending(345, "-150.00*"), ending(430, "2,850.00")),
                row(at(100, "Medical"), ending(345, "-148.70*"), ending(430, "2,825.30")),
                row(at(100, "Net Pay"), ending(345, "$2,486.20")),
                row(at(100, "Checking 1"), ending(345, "-2,486.20")),
                row(at(100, "Net Check"), ending(345, "$0.00")),
                row(at(100, "* Excluded from federal taxable wages")),
                row(at(100, "Your federal taxable wages this period are $3,301.30")),
                row(at(40, "Deposited to the account of"), at(300, "account number"), at(400, "transit ABA"), at(500, "amount")),
                row(at(40, "Amara Okafor"), at(300, "xxxxxx1234"), at(400, "xxxx xxxx"), ending(540, "$2,486.20")));
    }

    @Test
    void readsAStatementWithAnInformationColumnAndLinesAfterTheNetPay() {
        Read read = StubReader.read(statement(), Map.of());

        // Nothing from the information column, nothing printed after the net pay, and the net pay once.
        assertThat(read.lines()).extracting(Line::label).containsExactly(
                "Regular", "401(k)", "Medical",
                "Federal Income Tax", "Social Security Tax", "Medicare Tax", "CA State Income Tax", "Net pay");
        assertThat(read.payDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(find(read, "Regular")).isEqualTo(
                new Line(Section.EARNINGS, "Regular", cents(360_000), cents(6_840_000), Tracking.GROSS, true));
        // What is taken out is held as a plain amount, whatever sign and footnote mark the stub prints.
        assertThat(find(read, "401(k)")).isEqualTo(
                new Line(Section.PRE_TAX, "401(k)", cents(15_000), cents(285_000), Tracking.CONTRIBUTION, true));
        assertThat(find(read, "Medical")).isEqualTo(
                new Line(Section.PRE_TAX, "Medical", cents(14_870), cents(282_530), Tracking.BENEFIT, true));
        assertThat(find(read, "Medicare Tax")).isEqualTo(
                new Line(Section.TAX, "Medicare Tax", cents(5_004), cents(95_076), Tracking.TAX, true));
        assertThat(find(read, "Net pay").amount()).isEqualTo(cents(248_620));

        Totals totals = Totals.of(read.lines());
        assertThat(totals.gross()).isEqualTo(cents(360_000));
        assertThat(totals.deductions()).isEqualTo(cents(111_380));
        assertThat(totals.balanced()).isTrue();
    }

    @Test
    void theAccountThePayWentIntoIsTheNetPayNotSomethingTakenOut() {
        // Listed among the deductions, above a net pay of nothing: all of it went to the bank.
        List<Row> rows = List.of(
                heads("Earnings"),
                line("Regular", "3,600.00", "68,400.00"),
                heads("Deductions"),
                line("Federal Income Tax", "-964.80", "18,331.20"),
                line("401(k)", "-149.00", "2,831.00"),
                line("Checking 1", "-2,486.20", "47,237.80"),
                line("Net Pay", "$0.00", "0.00"));

        Read read = StubReader.read(rows, Map.of());

        assertThat(read.lines()).extracting(Line::label).containsExactly("Regular", "401(k)", "Federal Income Tax", "Net pay");
        assertThat(find(read, "Net pay").amount()).isEqualTo(cents(248_620));
        assertThat(Totals.of(read.lines()).balanced()).isTrue();
    }

    @Test
    void aLineThatIsTheNetPayOverAgainIsDroppedWhateverItIsCalled() {
        // Nothing in its name says where the money went, and it was even filed by hand once; its amount gives it away.
        List<Row> rows = List.of(
                heads("Earnings"),
                line("Regular", "3,600.00", "68,400.00"),
                heads("Taxes"),
                line("Federal Income Tax", "-1,113.80", "21,162.20"),
                line("Acct 4417 Amara O", "-2,486.20", "47,237.80"),
                line("Net Pay", "2,486.20", "47,237.80"));
        Map<String, Remembered> remembered = Map.of(PayStub.key("Acct 4417 Amara O"), new Remembered(Section.TAX, Tracking.TAX));

        Read read = StubReader.read(rows, remembered);

        assertThat(read.lines()).extracting(Line::label).containsExactly("Regular", "Federal Income Tax", "Net pay");
        assertThat(Totals.of(read.lines()).balanced()).isTrue();
    }

    @Test
    void aDeductionThatHappensToEqualTheNetPayIsKeptWhenTheStubAddsUpWithIt() {
        List<Row> rows = List.of(
                heads("Earnings"),
                line("Regular", "3,000.00", "57,000.00"),
                heads("Taxes"),
                line("Federal Income Tax", "-1,000.00", "19,000.00"),
                heads("After-Tax Deductions"),
                line("Garnishment", "-1,000.00", "19,000.00"),
                line("Net Pay", "1,000.00", "19,000.00"));

        assertThat(StubReader.read(rows, Map.of()).lines()).extracting(Line::label)
                .containsExactly("Regular", "Federal Income Tax", "Garnishment", "Net pay");
    }

    @Test
    void aGroupTitledAfterTheNetPayIsStillRead() {
        List<Row> rows = List.of(
                heads("Earnings"),
                line("Salary", "3,600.00", "68,400.00"),
                line("Net Pay", "3,600.00", "68,400.00"),
                line("Checking 1", "3,600.00", "68,400.00"),
                heads("Employer Paid Benefits"),
                line("Employer 401(k) match", "75.00", "1,425.00"));

        assertThat(StubReader.read(rows, Map.of()).lines()).extracting(Line::label)
                .containsExactly("Salary", "Net pay", "Employer 401(k) match");
    }

    @Test
    void aStubWithNoDateOrNothingToReadSaysSo() {
        Read read = StubReader.read(List.of(row(at(40, "Thank you for your hard work"))), Map.of());

        assertThat(read.payDate()).isNull();
        assertThat(read.lines()).isEmpty();
        assertThat(Totals.of(read.lines()).balanced()).isTrue();
    }

    @Test
    void aStubThatDoesNotAddUpSaysByHowMuch() {
        List<Row> rows = List.of(
                heads("Earnings"),
                line("Salary", "3,600.00", "68,400.00"),
                heads("Taxes"),
                line("Federal income tax", "391.78", "7,443.82"),
                line("Net Pay", "3,200.00", "60,800.00"));

        Totals totals = Totals.of(StubReader.read(rows, Map.of()).lines());

        assertThat(totals.balanced()).isFalse();
        assertThat(totals.difference()).isEqualTo(cents(822));
    }

    @Test
    void labelsAreRememberedByTheirLettersAndDigitsOnly() {
        assertThat(PayStub.key("401(k) ")).isEqualTo(PayStub.key("401 (K)")).isEqualTo("401k");
    }
}
