package app.budget.engine.statements;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.Money;
import app.budget.engine.statements.StatementRead.Kind;
import app.budget.engine.statements.StatementRead.Line;
import app.budget.engine.statements.StatementText.DateOrder;
import app.budget.engine.stubs.PayStub.Row;
import app.budget.engine.stubs.PayStub.Word;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reading statements (Rachel, 2026-10-25: CSV, OFX and PDF). Every statement here is made up, in layouts banks commonly
 * use; no real statement is read or kept.
 */
class StatementReadersTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);

    @Test
    void amountsAsBanksWriteThem() {
        assertThat(StatementText.amount("$1,234.56")).contains(Money.ofCents(123456));
        assertThat(StatementText.amount("-12.00")).contains(Money.ofCents(-1200));
        assertThat(StatementText.amount("$-12.00")).contains(Money.ofCents(-1200));
        assertThat(StatementText.amount("(12.00)")).contains(Money.ofCents(-1200));
        assertThat(StatementText.amount("12.00 CR")).contains(Money.ofCents(-1200));
        assertThat(StatementText.amount("12.00-")).contains(Money.ofCents(-1200));
        assertThat(StatementText.amount("12.5")).contains(Money.ofCents(1250));
        assertThat(StatementText.amount("12.00 DR")).contains(Money.ofCents(1200));
        assertThat(StatementText.amount("(12.00")).isEmpty();
        assertThat(StatementText.amount("Kroger")).isEmpty();
        assertThat(StatementText.amount("")).isEmpty();
        assertThat(StatementText.looksLikeMoney("1200")).isFalse();
        assertThat(StatementText.looksLikeMoney("12.00")).isTrue();
    }

    @Test
    void datesAsBanksWriteThem() {
        LocalDate oct5 = LocalDate.of(2026, 10, 5);
        assertThat(StatementText.date("2026-10-05", DateOrder.MDY)).contains(oct5);
        assertThat(StatementText.date("10/05/2026", DateOrder.MDY)).contains(oct5);
        assertThat(StatementText.date("10/05/26", DateOrder.MDY)).contains(oct5);
        assertThat(StatementText.date("05/10/2026", DateOrder.DMY)).contains(oct5);
        assertThat(StatementText.date("20261005", DateOrder.YMD)).contains(oct5);
        assertThat(StatementText.date("20261005120000.000[-5:EST]", DateOrder.YMD)).contains(oct5);
        assertThat(StatementText.date("Oct 5, 2026", DateOrder.MDY)).contains(oct5);
        assertThat(StatementText.date("05 Oct 2026", DateOrder.MDY)).contains(oct5);
        assertThat(StatementText.date("02/30/2026", DateOrder.MDY)).isEmpty();
        // A first number over 12 can only be a day.
        assertThat(StatementText.date("25/10/2026", DateOrder.MDY)).contains(LocalDate.of(2026, 10, 25));
        assertThat(StatementText.monthDay("10/05", DateOrder.MDY)).contains(MonthDay.of(10, 5));
        assertThat(StatementText.monthDay("Oct 5", DateOrder.MDY)).contains(MonthDay.of(10, 5));
    }

    @Test
    void payeeKeysIgnoreStoreNumbersAndProcessors() {
        assertThat(PayeeKey.of("SQ *BLUE BOTTLE 0123")).isEqualTo("blue bottle");
        assertThat(PayeeKey.of("BLUE BOTTLE #77")).isEqualTo("blue bottle");
        assertThat(PayeeKey.of("POS DEBIT KROGER #0456 DAYTON OH")).isEqualTo("kroger dayton");
        assertThat(PayeeKey.firstWord("kroger dayton")).isEqualTo("kroger");
        assertThat(PayeeKey.of("1234 5678")).isEmpty();
    }

    @Test
    void aCardCsvWithOneSignedColumn() {
        // Charges negative, payments positive: one common card layout.
        String csv = """
                Transaction Date,Post Date,Description,Category,Type,Amount,Memo
                10/03/2026,10/04/2026,KROGER #123,Groceries,Sale,-54.20,
                10/05/2026,10/06/2026,"SHELL OIL, 5744",Gas,Sale,-38.00,
                10/07/2026,10/07/2026,AUTOMATIC PAYMENT - THANK YOU,,Payment,500.00,
                10/09/2026,10/10/2026,KROGER #123,Groceries,Return,12.10,
                """;
        List<List<String>> rows = CsvStatement.rows(csv);
        CsvStatement.Layout layout = CsvStatement.guess(rows);

        assertThat(layout.hasHeader()).isTrue();
        assertThat(layout.dateColumn()).isZero();
        assertThat(layout.postedColumn()).isEqualTo(1);
        assertThat(layout.descriptionColumn()).isEqualTo(2);
        assertThat(layout.amountColumn()).isEqualTo(5);
        assertThat(layout.chargesNegative()).isTrue();

        List<Line> lines = CsvStatement.read(rows, layout, true).statement().lines();
        assertThat(lines).extracting(Line::description)
                .containsExactly("KROGER #123", "SHELL OIL, 5744", "AUTOMATIC PAYMENT - THANK YOU", "KROGER #123");
        assertThat(lines).extracting(line -> line.amount().cents()).containsExactly(5420L, 3800L, -50000L, -1210L);
        assertThat(lines).extracting(Line::kind).containsExactly(Kind.CHARGE, Kind.CHARGE, Kind.PAYMENT, Kind.REFUND);
        assertThat(lines.get(0).txnDate()).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(lines.get(0).postedDate()).isEqualTo(LocalDate.of(2026, 10, 4));
    }

    @Test
    void aBankCsvWithDebitAndCreditColumns() {
        String csv = """
                Date;Description;Debit;Credit;Balance
                2026-10-01;PAYROLL DIRECT DEP;;2486.20;3486.20
                2026-10-02;CITY WATER;64.10;;3422.10
                2026-10-03;ONLINE TRANSFER TO SAVINGS;200.00;;3222.10
                ;Total;264.10;2486.20;
                """;
        List<List<String>> rows = CsvStatement.rows(csv);
        CsvStatement.Layout layout = CsvStatement.guess(rows);
        assertThat(layout.amountColumn()).isNull();
        assertThat(layout.debitColumn()).isEqualTo(2);
        assertThat(layout.creditColumn()).isEqualTo(3);

        CsvStatement.Read read = CsvStatement.read(rows, layout, false);
        assertThat(read.statement().lines()).extracting(line -> line.amount().cents()).containsExactly(-248620L, 6410L, 20000L);
        assertThat(read.statement().lines()).extracting(Line::kind).containsExactly(Kind.TRANSFER, Kind.CHARGE, Kind.TRANSFER);
        // The footer of totals is not a line gone missing.
        assertThat(read.skipped()).isEmpty();
        assertThat(read.statement().from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(read.statement().to()).isEqualTo(LocalDate.of(2026, 10, 3));
    }

    @Test
    void aCsvWithNoHeaderAndDayFirstDates() {
        String csv = "25/09/2026,CAFE LUNA,4.50\n02/10/2026,CAFE LUNA,4.50\nnot a date,???,1.00\n";
        List<List<String>> rows = CsvStatement.rows(csv);
        CsvStatement.Layout layout = CsvStatement.guess(rows);
        assertThat(layout.hasHeader()).isFalse();
        assertThat(layout.dateOrder()).isEqualTo(DateOrder.DMY);

        CsvStatement.Read read = CsvStatement.read(rows, layout, true);
        assertThat(read.statement().lines()).extracting(Line::txnDate)
                .containsExactly(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 2));
        assertThat(read.skipped()).extracting(CsvStatement.Skipped::row).containsExactly(3);
    }

    @Test
    void ofxInItsOlderSgmlForm() {
        String ofx = """
                OFXHEADER:100
                DATA:OFXSGML

                <OFX><CREDITCARDMSGSRSV1><CCSTMTTRNRS><CCSTMTRS>
                <BANKTRANLIST><DTSTART>20260914<DTEND>20261013
                <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20261004120000<DTUSER>20261003<TRNAMT>-54.20<FITID>A1<NAME>KROGER #123</STMTTRN>
                <STMTTRN><TRNTYPE>PAYMENT<DTPOSTED>20261007<TRNAMT>500.00<FITID>A2<NAME>PAYMENT THANK YOU</STMTTRN>
                <STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20261010<TRNAMT>12.10<FITID>A3<NAME>KROGER #123<MEMO>RETURN</STMTTRN>
                </BANKTRANLIST></CCSTMTRS></CCSTMTTRNRS></CREDITCARDMSGSRSV1></OFX>
                """;
        assertThat(OfxStatement.looksLikeOfx(ofx)).isTrue();
        StatementRead read = OfxStatement.read(ofx, true);

        assertThat(read.from()).isEqualTo(LocalDate.of(2026, 9, 14));
        assertThat(read.to()).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(read.lines()).extracting(line -> line.amount().cents()).containsExactly(5420L, -50000L, -1210L);
        assertThat(read.lines()).extracting(Line::kind).containsExactly(Kind.CHARGE, Kind.PAYMENT, Kind.REFUND);
        assertThat(read.lines()).extracting(Line::externalId).containsExactly("A1", "A2", "A3");
        // The day it was spent, with the day it posted beside it.
        assertThat(read.lines().get(0).txnDate()).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(read.lines().get(0).postedDate()).isEqualTo(LocalDate.of(2026, 10, 4));
        assertThat(read.lines().get(1).postedDate()).isNull();
        assertThat(read.lines().get(2).description()).isEqualTo("KROGER #123 RETURN");
    }

    @Test
    void ofxInItsXmlForm() {
        String ofx = """
                <?xml version="1.0"?><?OFX OFXHEADER="200"?>
                <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><BANKTRANLIST>
                <STMTTRN><TRNTYPE>DEBIT</TRNTYPE><DTPOSTED>20261002</DTPOSTED><TRNAMT>-64.10</TRNAMT><FITID>B1</FITID>
                <NAME>CITY WATER &amp; SEWER</NAME></STMTTRN>
                <STMTTRN><TRNTYPE>XFER</TRNTYPE><DTPOSTED>20261003</DTPOSTED><TRNAMT>-200.00</TRNAMT><FITID>B2</FITID>
                <NAME>TO SAVINGS</NAME></STMTTRN>
                </BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>
                """;
        StatementRead read = OfxStatement.read(ofx, false);
        assertThat(read.lines()).extracting(Line::description).containsExactly("CITY WATER & SEWER", "TO SAVINGS");
        assertThat(read.lines()).extracting(Line::kind).containsExactly(Kind.CHARGE, Kind.TRANSFER);
    }

    /** A row of words, set one after another. */
    private static Row row(int page, String... words) {
        List<Word> placed = new ArrayList<>();
        double x = 40;
        for (String word : words) {
            placed.add(new Word(x, x + word.length() * 5, word));
            x += word.length() * 5 + 8;
        }
        return new Row(page, placed);
    }

    @Test
    void aCardPdfAcrossTheNewYear() {
        List<Row> rows = List.of(
                row(1, "Opening/Closing", "Date", "12/14/26", "-", "01/13/27"),
                row(1, "Previous", "Balance", "$1,000.00"),
                row(1, "PAYMENTS", "AND", "OTHER", "CREDITS"),
                row(1, "12/20", "PAYMENT", "THANK", "YOU", "500.00"),
                row(1, "12/22", "KROGER", "RETURN", "12.10"),
                row(1, "PURCHASES"),
                row(1, "12/18", "12/19", "KROGER", "#123", "54.20"),
                row(2, "Jan", "3", "SHELL", "OIL", "5744", "$", "38.00"),
                row(2, "01/05", "CAFE", "LUNA", "4.50", "CR"),
                row(2, "New", "Balance", "$580.10"));
        StatementRead read = PdfStatement.read(rows, true, TODAY);

        assertThat(read.from()).isEqualTo(LocalDate.of(2026, 12, 14));
        assertThat(read.to()).isEqualTo(LocalDate.of(2027, 1, 13));
        assertThat(read.lines()).extracting(Line::description)
                .containsExactly("PAYMENT THANK YOU", "KROGER RETURN", "KROGER #123", "SHELL OIL 5744", "CAFE LUNA");
        assertThat(read.lines()).extracting(line -> line.amount().cents()).containsExactly(-50000L, -1210L, 5420L, 3800L, -450L);
        assertThat(read.lines()).extracting(Line::kind)
                .containsExactly(Kind.PAYMENT, Kind.REFUND, Kind.CHARGE, Kind.CHARGE, Kind.REFUND);
        // December's lines are of 2026, January's of 2027.
        assertThat(read.lines()).extracting(Line::txnDate).containsExactly(
                LocalDate.of(2026, 12, 20), LocalDate.of(2026, 12, 22), LocalDate.of(2026, 12, 18),
                LocalDate.of(2027, 1, 3), LocalDate.of(2027, 1, 5));
        assertThat(read.lines().get(2).postedDate()).isEqualTo(LocalDate.of(2026, 12, 19));
        assertThat(read.lines()).allMatch(Line::sure);
    }

    @Test
    void aPdfWithNoWholeDateIsPointedOut() {
        StatementRead read = PdfStatement.read(List.of(row(1, "10/05", "KROGER", "54.20")), true, TODAY);
        assertThat(read.lines()).singleElement().satisfies(line -> {
            assertThat(line.txnDate()).isEqualTo(LocalDate.of(2026, 10, 5));
            assertThat(line.sure()).isFalse();
        });
    }

    @Test
    void duplicatesOfAnEarlierStatementAreFoundOnceEach() {
        Line coffee = new Line(LocalDate.of(2026, 10, 5), null, "CAFE LUNA", Money.ofCents(450), Kind.CHARGE, true, null);
        Line withId = new Line(LocalDate.of(2026, 10, 6), null, "KROGER", Money.ofCents(100), Kind.CHARGE, true, "X9");
        List<Duplicates.Known> known = List.of(
                new Duplicates.Known(7, LocalDate.of(2026, 10, 5), 450, "cafe  luna", null),
                new Duplicates.Known(8, LocalDate.of(2026, 10, 1), 999, "other", "X9"));
        assertThat(Duplicates.of(List.of(coffee, coffee, withId), known)).containsExactly(7L, null, 8L);
    }
}
