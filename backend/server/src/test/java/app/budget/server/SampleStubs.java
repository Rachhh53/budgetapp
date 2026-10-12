package app.budget.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Made-up pay stubs as real PDFs, drawn on the spot in the layouts payroll companies commonly
 * use (decided 2026-10-12: no real stub is used to build or test with). The people and the
 * employer are invented; the figures are the mockup's: $3,600.00 gross, $2,486.20 net.
 */
final class SampleStubs {

    private SampleStubs() {}

    private static final float SIZE = 9;
    private static final float LEFT = 40;
    private static final float CURRENT = 380;
    private static final float YTD = 480;

    /** Draws words where they are put, the way a stub's columns are set. */
    private static final class Sheet implements AutoCloseable {
        private final PDDocument document = new PDDocument();
        private final PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private final PDPageContentStream content;
        private float y = 740;

        Sheet() throws IOException {
            PDPage page = new PDPage();
            document.addPage(page);
            content = new PDPageContentStream(document, page);
        }

        /** Starts at {@code x}. */
        Sheet at(float x, String text) throws IOException {
            content.beginText();
            content.setFont(font, SIZE);
            content.newLineAtOffset(x, y);
            content.showText(text);
            content.endText();
            return this;
        }

        /** Ends at {@code endX}: a column of amounts. */
        Sheet ending(float endX, String text) throws IOException {
            return at(endX - font.getStringWidth(text) / 1000 * SIZE, text);
        }

        Sheet next() {
            y -= 14;
            return this;
        }

        Sheet heads(String title) throws IOException {
            return at(LEFT, title).ending(CURRENT, "Current").ending(YTD, "YTD").next();
        }

        Sheet line(String label, String current, String ytd) throws IOException {
            return at(LEFT, label).ending(CURRENT, current).ending(YTD, ytd).next();
        }

        byte[] bytes() throws IOException {
            content.close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }

        @Override
        public void close() throws IOException {
            document.close();
        }
    }

    /** Acme Corp's stub for October 1, 2026: groups one under another, each with "Current" and "YTD". */
    static byte[] acmeOctober1() {
        return acme("10/01/2026", "3,600.00", "68,400.00", "391.78", "7,443.82", "2,486.20", "47,237.80");
    }

    /** The next one, after a raise: the same layout, with different pay, tax and net. */
    static byte[] acmeOctober15() {
        return acme("10/15/2026", "3,800.00", "72,200.00", "431.78", "7,875.60", "2,646.20", "49,884.00");
    }

    private static byte[] acme(String payDate, String gross, String grossYtd, String federal, String federalYtd, String net, String netYtd) {
        try (Sheet sheet = new Sheet()) {
            sheet.at(LEFT, "Acme Corp").at(300, "Pay Date: " + payDate).next();
            sheet.at(LEFT, "Amara Okafor").next().next();
            sheet.heads("Earnings");
            sheet.line("Regular pay", gross, grossYtd);
            sheet.line("Gross Pay", gross, grossYtd).next();
            sheet.heads("Before-Tax Deductions");
            sheet.line("401(k)", "150.00", "2,850.00");
            sheet.line("HSA", "50.00", "950.00");
            sheet.line("Medical", "86.40", "1,641.60");
            sheet.line("Dental", "9.20", "174.80");
            sheet.line("Vision", "3.10", "58.90").next();
            sheet.heads("Taxes");
            sheet.line("Federal income tax", federal, federalYtd);
            sheet.line("State income tax", "159.30", "3,026.70");
            sheet.line("Social Security", "213.98", "4,065.62");
            sheet.line("Medicare", "50.04", "950.76").next();
            sheet.heads("Employer Paid Benefits");
            sheet.line("Employer 401(k) match", "75.00", "1,425.00").next();
            sheet.line("Net Pay", net, netYtd);
            return sheet.bytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Earnings on the left with hours and a rate, what is taken out on the right, the date under its heading. */
    static byte[] sideBySide() {
        try (Sheet sheet = new Sheet()) {
            sheet.at(LEFT, "Check Date").at(140, "Period Ending").next();
            sheet.at(LEFT, "10/01/2026").at(140, "09/30/2026").next().next();
            sheet.at(LEFT, "Earnings").at(320, "Taxes").next();
            sheet.ending(130, "Rate").ending(170, "Hours").ending(230, "Current").ending(300, "YTD")
                    .ending(500, "Current").ending(570, "YTD").next();
            sheet.at(LEFT, "Regular").ending(130, "45.0000").ending(170, "80.00").ending(230, "3,600.00").ending(300, "68,400.00")
                    .at(320, "Federal Income Tax").ending(500, "391.78").ending(570, "7,443.82").next();
            sheet.at(320, "Social Security").ending(500, "213.98").ending(570, "4,065.62").next();
            sheet.at(320, "Medicare").ending(500, "50.04").ending(570, "950.76").next();
            sheet.at(320, "CA State Income Tax").ending(500, "159.30").ending(570, "3,026.70").next();
            sheet.at(320, "Deductions").next();
            sheet.at(320, "401K").ending(500, "150.00").ending(570, "2,850.00").next();
            sheet.at(320, "Medical Pre-Tax").ending(500, "148.70").ending(570, "2,825.30").next().next();
            sheet.at(LEFT, "Net Pay").ending(230, "$2,486.20");
            return sheet.bytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A statement with an information column beside the pay, what is taken out indented under
     * "Statutory" and "Other" with minus signs and a footnote's asterisk, and, after the net pay,
     * the account it went to, the taxable wages in a sentence, and a tear-off advice.
     */
    static byte[] statement() {
        try (Sheet sheet = new Sheet()) {
            sheet.at(300, "Pay Date: 10/01/2026").next().next();
            sheet.at(LEFT, "Earnings").ending(170, "rate").ending(215, "hours").ending(290, "this period").ending(370, "year to date")
                    .at(390, "Other Benefits and Information").ending(520, "this period").ending(590, "total to date").next();
            sheet.at(LEFT, "Regular").ending(170, "45.0000").ending(215, "80.00").ending(290, "3,600.00").ending(370, "68,400.00")
                    .at(390, "Group Term Life").ending(520, "0.51").ending(590, "9.69").next();
            sheet.at(150, "Gross Pay").ending(290, "$3,600.00").ending(370, "68,400.00").at(390, "Vac Hrs").ending(590, "40.00").next();
            sheet.at(LEFT, "Deductions").at(90, "Statutory").next();
            sheet.at(90, "Federal Income Tax").ending(290, "-391.78").ending(370, "7,443.82").at(390, "Important Notes").next();
            sheet.at(90, "Social Security Tax").ending(290, "-213.98").ending(370, "4,065.62")
                    .at(390, "Loan Amt Paid").ending(590, "840.00").next();
            sheet.at(90, "Medicare Tax").ending(290, "-50.04").ending(370, "950.76").next();
            sheet.at(90, "CA State Income Tax").ending(290, "-159.30").ending(370, "3,026.70").next();
            sheet.at(90, "Other").next();
            sheet.at(90, "401(k)").ending(290, "-150.00*").ending(370, "2,850.00").next();
            sheet.at(90, "Medical").ending(290, "-148.70*").ending(370, "2,825.30").next();
            sheet.at(90, "Net Pay").ending(290, "$2,486.20").next();
            sheet.at(90, "Checking 1").ending(290, "-2,486.20").next();
            sheet.at(90, "Net Check").ending(290, "$0.00").next().next();
            sheet.at(90, "* Excluded from federal taxable wages").next();
            sheet.at(90, "Your federal taxable wages this period are $3,301.30").next().next().next();
            sheet.at(LEFT, "Deposited to the account of").at(300, "account number").at(400, "transit ABA").at(500, "amount").next();
            sheet.at(LEFT, "Amara Okafor").at(300, "xxxxxx1234").at(400, "xxxx xxxx").ending(540, "$2,486.20");
            return sheet.bytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A plain list with no headings, what is taken out shown with minus signs, and one line nothing explains. */
    static byte[] plainList() {
        try (Sheet sheet = new Sheet()) {
            sheet.at(LEFT, "Paid on October 1, 2026").next().next();
            sheet.at(LEFT, "Gross Pay").ending(CURRENT, "3,600.00").next();
            sheet.at(LEFT, "Federal Tax").ending(CURRENT, "-391.78").next();
            sheet.at(LEFT, "State Tax").ending(CURRENT, "(159.30)").next();
            sheet.at(LEFT, "FICA").ending(CURRENT, "264.02-").next();
            sheet.at(LEFT, "401k").ending(CURRENT, "-150.00").next();
            sheet.at(LEFT, "Health Insurance").ending(CURRENT, "-148.70").next();
            sheet.at(LEFT, "Net Pay").ending(CURRENT, "2,486.20");
            return sheet.bytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A page with nothing written on it, as a scanned stub is to anything that reads text. */
    static byte[] scan() {
        try (Sheet sheet = new Sheet()) {
            return sheet.bytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
