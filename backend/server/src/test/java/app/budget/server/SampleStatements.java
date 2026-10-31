package app.budget.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Made-up card statements, as a bank's CSV, OFX and PDF downloads would have them (no real statement is used to build or
 * test with). September 2026, when pay periods run Sep 1–14 and Sep 15–30.
 */
final class SampleStatements {

    private SampleStatements() {}

    /** Charges written positive and the payment negative, the way many cards export. */
    static byte[] septemberCsv() {
        return """
                Date,Description,Amount
                09/02/2026,KROGER #123,54.20
                09/05/2026,SHELL OIL 5744,38.00
                09/09/2026,KROGER #456,25.80
                09/10/2026,PAYMENT THANK YOU,-500.00
                09/16/2026,CAFE LUNA,4.50
                """.getBytes(StandardCharsets.UTF_8);
    }

    /** The next download from the same card: the same columns, overlapping the last by one line. */
    static byte[] lateSeptemberCsv() {
        return """
                Date,Description,Amount
                09/16/2026,CAFE LUNA,4.50
                09/18/2026,KROGER #123,61.00
                09/22/2026,KROGER RETURN,-10.00
                """.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] septemberOfx() {
        return """
                OFXHEADER:100
                DATA:OFXSGML

                <OFX><CREDITCARDMSGSRSV1><CCSTMTTRNRS><CCSTMTRS>
                <BANKTRANLIST><DTSTART>20260901<DTEND>20260930
                <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20260903<DTUSER>20260902<TRNAMT>-54.20<FITID>S1<NAME>KROGER #123</STMTTRN>
                <STMTTRN><TRNTYPE>PAYMENT<DTPOSTED>20260910<TRNAMT>500.00<FITID>S2<NAME>PAYMENT THANK YOU</STMTTRN>
                <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20260917<TRNAMT>-38.00<FITID>S3<NAME>SHELL OIL 5744</STMTTRN>
                </BANKTRANLIST></CCSTMTRS></CCSTMTTRNRS></CREDITCARDMSGSRSV1></OFX>
                """.getBytes(StandardCharsets.UTF_8);
    }

    /** A one-page card statement drawn as a PDF, with its period at the top and a line of spending per row. */
    static byte[] septemberPdf() {
        String[][] rows = {
            {"Statement period 09/01/26 - 09/30/26"},
            {"Previous Balance", "$500.00"},
            // The card's account summary, as Discover prints it.
            {"Payments and Credits", "-$500.00"},
            {"Purchases", "+$92.20"},
            {"Fees Charged", "+$0.00"},
            {"PAYMENTS AND OTHER CREDITS"},
            {"09/10", "PAYMENT THANK YOU", "500.00"},
            {"PURCHASES"},
            {"09/02", "KROGER #123", "54.20"},
            {"09/05", "SHELL OIL 5744", "38.00"},
            {"New Balance", "$92.20"},
        };
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                float y = 740;
                for (String[] row : rows) {
                    float x = 40;
                    for (int i = 0; i < row.length; i++) {
                        // The last of several is an amount, set against the right-hand column.
                        boolean amount = row.length > 1 && i == row.length - 1;
                        float at = amount ? 500 - font.getStringWidth(row[i]) / 1000 * 9 : x;
                        content.beginText();
                        content.setFont(font, 9);
                        content.newLineAtOffset(at, y);
                        content.showText(row[i]);
                        content.endText();
                        x = i == 0 ? 100 : x + 200;
                    }
                    y -= 14;
                }
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A PDF with no text in it: what a scan looks like to Budget. */
    static byte[] scannedPdf() {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
