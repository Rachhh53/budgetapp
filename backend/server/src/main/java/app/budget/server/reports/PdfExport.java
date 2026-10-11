package app.budget.server.reports;

import app.budget.server.files.FileStore.Kind;
import app.budget.server.reports.ReportRunner.GroupView;
import app.budget.server.reports.ReportRunner.Named;
import app.budget.server.reports.ReportRunner.Report;
import app.budget.server.reports.ReportRunner.Row;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A report as a PDF to keep or hand to someone (spec §8): its totals, its groups, every line,
 * and then, when asked, the receipts and documents attached to those lines, each on its own
 * pages under a caption saying what it belongs to.
 *
 * <p>Everything is drawn here on this PC. Text uses a font from Windows' own Fonts folder
 * when there is one, so names in any alphabet are printed; without one it falls back to the
 * font every PDF reader has, and letters that font lacks become "?".
 */
final class PdfExport {

    private static final Logger log = LoggerFactory.getLogger(PdfExport.class);

    /** US Letter, as the app is for US households. Sizes are in points (1/72 inch). */
    private static final PDRectangle PAPER = PDRectangle.LETTER;
    private static final float MARGIN = 48;
    private static final float WIDTH = PAPER.getWidth() - 2 * MARGIN;
    private static final float TOP = PAPER.getHeight() - MARGIN;

    private static final float TITLE_SIZE = 18;
    private static final float HEADING_SIZE = 12;
    private static final float BODY_SIZE = 9.5f;
    private static final float SMALL_SIZE = 8;
    private static final float ROW_HEIGHT = 16;

    private static final float[] INK = {0.10f, 0.12f, 0.16f};
    private static final float[] MUTED = {0.39f, 0.43f, 0.49f};
    private static final float[] RULE = {0.86f, 0.88f, 0.91f};
    /** The light blue box an estimate is shown in everywhere in the app (spec §1). */
    private static final float[] ESTIMATE_FILL = {0.86f, 0.93f, 1.0f};

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

    /** Regular and bold of the first family found, tried in this order. */
    private static final List<String[]> FONT_FILES = List.of(
            new String[] {"segoeui.ttf", "segoeuib.ttf"},
            new String[] {"arial.ttf", "arialbd.ttf"});

    /**
     * A file to add after the report.
     *
     * @param caption what it belongs to, e.g. "Sep 28, 2026 · Valley Pediatrics · invoice.pdf"
     */
    record Receipt(String caption, Path file, Kind kind) {}

    /**
     * @param title    the report's name
     * @param about    lines under the title saying what the report covers
     * @param names    the names for each of the report's rows, in the same order
     * @param receipts the files to add after the report, in order; empty for none
     * @param fontsDir where to look for a font that can print any alphabet; empty to use the built-in one
     */
    static byte[] of(String title, List<String> about, Report report, List<Named> names, List<Receipt> receipts,
            Optional<Path> fontsDir) {
        // Receipts that are PDFs lend their pages to the report, and must stay open until it is written.
        List<PDDocument> lent = new ArrayList<>();
        try (PDDocument document = new PDDocument()) {
            Sheet sheet = new Sheet(document, fontsDir);
            sheet.title(title);
            for (String line : about) {
                sheet.note(line);
            }
            sheet.gap(10);
            totals(sheet, report);
            groups(sheet, report);
            lines(sheet, report, names);
            sheet.finish();

            List<String> leftOut = new ArrayList<>();
            for (Receipt receipt : receipts) {
                try {
                    if (receipt.kind() == Kind.PDF) {
                        appendPdf(document, sheet, receipt, lent);
                    } else {
                        sheet.picture(receipt);
                    }
                } catch (IOException | RuntimeException e) {
                    // One receipt that cannot be added must not cost the user the whole report.
                    log.warn("A receipt could not be added to a report PDF", e);
                    leftOut.add(receipt.caption());
                }
            }
            if (!leftOut.isEmpty()) {
                sheet.newPage();
                sheet.heading("Files that could not be added");
                sheet.note("These are still safe in Budget. You can open each one from the expense or bill it belongs to.");
                sheet.gap(6);
                for (String caption : leftOut) {
                    sheet.note(caption);
                }
                sheet.finish();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the report PDF", e);
        } finally {
            for (PDDocument document : lent) {
                try {
                    document.close();
                } catch (IOException e) {
                    log.warn("A receipt PDF could not be closed after the export", e);
                }
            }
        }
    }

    private static void totals(Sheet sheet, Report report) throws IOException {
        List<String[]> figures = new ArrayList<>();
        figures.add(new String[] {"Total", money(report.totalCents())});
        if (report.hasReimbursements()) {
            figures.add(new String[] {"Reimbursed", money(report.reimbursedCents())});
            figures.add(new String[] {"Submitted, waiting", money(report.waitingCents())});
            figures.add(new String[] {"Not submitted yet", money(report.notSubmittedCents())});
        }
        sheet.figures(figures);
        if (report.estimatedCents() > 0) {
            sheet.note("Includes " + money(report.estimatedCents())
                    + " that is estimated or still to come. Estimates are shaded light blue below.");
        }
        sheet.gap(12);
    }

    private static void groups(Sheet sheet, Report report) throws IOException {
        sheet.heading(switch (report.groupBy()) {
            case MONTH -> "By month";
            case YEAR -> "By year";
            case CATEGORY -> "By category";
            case TAG -> "By tag";
            case PAYEE -> "By payee";
            case ACCOUNT -> "By account";
        });
        float[] widths = {WIDTH - 160, 60, 100};
        boolean[] right = {false, true, true};
        sheet.tableHeader(widths, right, "", "Items", "Total");
        if (report.groups().isEmpty()) {
            sheet.note("Nothing was spent that matches this report.");
        }
        for (GroupView group : report.groups()) {
            sheet.tableRow(widths, right, -1, group.label(), String.valueOf(group.count()), money(group.totalCents()));
        }
        sheet.totalRow(widths, right, "Total", String.valueOf(report.count()), money(report.totalCents()));
        sheet.gap(14);
    }

    private static void lines(Sheet sheet, Report report, List<Named> names) throws IOException {
        if (report.rows().isEmpty()) {
            return;
        }
        sheet.heading("Every line");
        float[] widths = {78, 160, 108, 94, WIDTH - 78 - 160 - 108 - 94};
        boolean[] right = {false, false, false, false, true};
        sheet.tableHeader(widths, right, "Date", "What", "Category", "Paid from", "Amount");
        for (int i = 0; i < report.rows().size(); i++) {
            Row row = report.rows().get(i);
            String what = row.payee() == null || row.payee().equalsIgnoreCase(row.description())
                    ? row.description()
                    : row.payee() + " · " + row.description();
            sheet.tableRow(widths, right, row.estimate() ? 4 : -1,
                    row.date().format(DAY), what, names.get(i).category(), names.get(i).account(), money(row.amountCents()));
        }
    }

    /** Adds every page of a PDF receipt, with its caption written small across the top of its first page. */
    private static void appendPdf(PDDocument document, Sheet sheet, Receipt receipt, List<PDDocument> lent) throws IOException {
        if (!Files.isRegularFile(receipt.file())) {
            throw new IOException("The file is not on disk");
        }
        PDDocument source = Loader.loadPDF(receipt.file().toFile());
        lent.add(source);
        boolean first = true;
        for (PDPage page : source.getPages()) {
            PDPage added = document.importPage(page);
            if (first) {
                sheet.stamp(added, receipt.caption());
                first = false;
            }
        }
    }

    static String money(long cents) {
        long whole = Math.abs(cents) / 100;
        return (cents < 0 ? "-" : "") + String.format(Locale.US, "$%,d.%02d", whole, Math.abs(cents) % 100);
    }

    static String day(LocalDate date) {
        return date.format(DAY);
    }

    /** The pages being written: where the pen is, and when to start a new page. */
    private static final class Sheet {
        private final PDDocument document;
        private final PDFont regular;
        private final PDFont bold;
        /** Characters already tried in {@link #regular}: true when it can draw them. */
        private final Map<Integer, Boolean> drawable = new HashMap<>();
        private PDPageContentStream stream;
        private float y;

        Sheet(PDDocument document, Optional<Path> fontsDir) throws IOException {
            this.document = document;
            PDFont[] fonts = fonts(document, fontsDir);
            this.regular = fonts[0];
            this.bold = fonts[1];
            newPage();
        }

        private static PDFont[] fonts(PDDocument document, Optional<Path> fontsDir) {
            if (fontsDir.isPresent()) {
                for (String[] family : FONT_FILES) {
                    Path regular = fontsDir.get().resolve(family[0]);
                    Path bold = fontsDir.get().resolve(family[1]);
                    if (Files.isRegularFile(regular) && Files.isRegularFile(bold)) {
                        try {
                            return new PDFont[] {
                                PDType0Font.load(document, regular.toFile()), PDType0Font.load(document, bold.toFile())
                            };
                        } catch (IOException | RuntimeException e) {
                            log.warn("A system font could not be used for the report PDF; trying the next", e);
                        }
                    }
                }
            }
            return new PDFont[] {
                new PDType1Font(Standard14Fonts.FontName.HELVETICA), new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
            };
        }

        void newPage() throws IOException {
            finish();
            PDPage page = new PDPage(PAPER);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = TOP;
        }

        void finish() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }

        /** Starts a new page when fewer than {@code height} points are left on this one. */
        private void room(float height) throws IOException {
            if (stream == null || y - height < MARGIN) {
                newPage();
            }
        }

        void gap(float points) {
            y -= points;
        }

        void title(String text) throws IOException {
            room(TITLE_SIZE + 8);
            y -= TITLE_SIZE;
            write(fit(text, bold, TITLE_SIZE, WIDTH), bold, TITLE_SIZE, MARGIN, y, INK);
            y -= 8;
        }

        void heading(String text) throws IOException {
            // A heading is never left alone at the foot of a page: it needs its table's first rows with it.
            room(HEADING_SIZE + 6 + 3 * ROW_HEIGHT);
            y -= HEADING_SIZE;
            write(text, bold, HEADING_SIZE, MARGIN, y, INK);
            y -= 6;
        }

        /** A line of small grey text, wrapped to the page. */
        void note(String text) throws IOException {
            for (String line : wrap(clean(text), regular, BODY_SIZE, WIDTH)) {
                room(BODY_SIZE + 4);
                y -= BODY_SIZE + 2;
                write(line, regular, BODY_SIZE, MARGIN, y, MUTED);
                y -= 2;
            }
        }

        /** The big figures across the top: a label over an amount, side by side. */
        void figures(List<String[]> figures) throws IOException {
            room(40);
            float cell = WIDTH / figures.size();
            for (int i = 0; i < figures.size(); i++) {
                float x = MARGIN + i * cell;
                write(figures.get(i)[0], regular, SMALL_SIZE, x, y - SMALL_SIZE - 2, MUTED);
                write(figures.get(i)[1], bold, 14, x, y - SMALL_SIZE - 20, INK);
            }
            y -= 40;
        }

        void tableHeader(float[] widths, boolean[] right, String... cells) throws IOException {
            room(2 * ROW_HEIGHT);
            cells(widths, right, bold, SMALL_SIZE, MUTED, -1, cells);
            rule();
        }

        /** @param shaded the column to draw the estimate box behind; -1 for none */
        void tableRow(float[] widths, boolean[] right, int shaded, String... cells) throws IOException {
            room(ROW_HEIGHT);
            cells(widths, right, regular, BODY_SIZE, INK, shaded, cells);
        }

        void totalRow(float[] widths, boolean[] right, String... cells) throws IOException {
            room(ROW_HEIGHT + 2);
            rule();
            cells(widths, right, bold, BODY_SIZE, INK, -1, cells);
        }

        private void cells(float[] widths, boolean[] right, PDFont font, float size, float[] color, int shaded, String... cells)
                throws IOException {
            float baseline = y - ROW_HEIGHT + 5;
            float x = MARGIN;
            for (int i = 0; i < cells.length; i++) {
                String text = fit(cells[i], font, size, widths[i] - 8);
                float textWidth = width(text, font, size);
                float at = right[i] ? x + widths[i] - textWidth : x;
                if (i == shaded) {
                    stream.setNonStrokingColor(ESTIMATE_FILL[0], ESTIMATE_FILL[1], ESTIMATE_FILL[2]);
                    stream.addRect(at - 3, baseline - 3, textWidth + 6, size + 4);
                    stream.fill();
                }
                write(text, font, size, at, baseline, color);
                x += widths[i];
            }
            y -= ROW_HEIGHT;
        }

        private void rule() throws IOException {
            stream.setStrokingColor(RULE[0], RULE[1], RULE[2]);
            stream.setLineWidth(0.75f);
            stream.moveTo(MARGIN, y);
            stream.lineTo(MARGIN + WIDTH, y);
            stream.stroke();
        }

        /** A picture receipt on a page of its own, under its caption, as large as fits. */
        void picture(Receipt receipt) throws IOException {
            if (!Files.isRegularFile(receipt.file())) {
                throw new IOException("The file is not on disk");
            }
            PDImageXObject image = PDImageXObject.createFromFileByContent(receipt.file().toFile(), document);
            newPage();
            note(receipt.caption());
            gap(8);
            float scale = Math.min(1, Math.min(WIDTH / image.getWidth(), (y - MARGIN) / image.getHeight()));
            float width = image.getWidth() * scale;
            float height = image.getHeight() * scale;
            stream.drawImage(image, MARGIN, y - height, width, height);
            finish();
        }

        /** Writes {@code caption} small at the top of a page that came from somewhere else. */
        void stamp(PDPage page, String caption) throws IOException {
            PDRectangle box = page.getCropBox();
            float size = SMALL_SIZE;
            String text = fit(caption, regular, size, box.getWidth() - 24);
            try (PDPageContentStream over = new PDPageContentStream(document, page, AppendMode.APPEND, true, true)) {
                float baseline = box.getUpperRightY() - size - 6;
                // A white strip under the words, so they can be read over whatever the page has there.
                over.setNonStrokingColor(1f, 1f, 1f);
                over.addRect(box.getLowerLeftX() + 8, baseline - 3, width(text, regular, size) + 8, size + 5);
                over.fill();
                over.beginText();
                over.setNonStrokingColor(MUTED[0], MUTED[1], MUTED[2]);
                over.setFont(regular, size);
                over.newLineAtOffset(box.getLowerLeftX() + 12, baseline);
                over.showText(text);
                over.endText();
            }
        }

        private void write(String text, PDFont font, float size, float x, float baseline, float[] color) throws IOException {
            String clean = clean(text);
            if (clean.isEmpty()) {
                return;
            }
            stream.beginText();
            stream.setNonStrokingColor(color[0], color[1], color[2]);
            stream.setFont(font, size);
            stream.newLineAtOffset(x, baseline);
            stream.showText(clean);
            stream.endText();
        }

        private float width(String text, PDFont font, float size) throws IOException {
            return font.getStringWidth(clean(text)) / 1000 * size;
        }

        /** {@code text}, cut short with "…" when it is wider than {@code max}. */
        private String fit(String text, PDFont font, float size, float max) throws IOException {
            String clean = clean(text);
            if (width(clean, font, size) <= max) {
                return clean;
            }
            String ellipsis = clean("…").equals("?") ? "..." : "…";
            int end = clean.length();
            while (end > 0 && width(clean.substring(0, end).stripTrailing() + ellipsis, font, size) > max) {
                end = clean.offsetByCodePoints(end, -1);
            }
            return clean.substring(0, end).stripTrailing() + ellipsis;
        }

        private List<String> wrap(String text, PDFont font, float size, float max) throws IOException {
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split(" ")) {
                String tried = line.isEmpty() ? word : line + " " + word;
                if (!line.isEmpty() && width(tried, font, size) > max) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(tried);
                }
            }
            if (!line.isEmpty()) {
                lines.add(fit(line.toString(), font, size, max));
            }
            return lines;
        }

        /** Text the font can draw: line breaks become spaces, and anything it has no shape for becomes "?". */
        private String clean(String text) {
            if (text == null) {
                return "";
            }
            StringBuilder clean = new StringBuilder(text.length());
            text.codePoints().forEach(codePoint -> {
                if (Character.isISOControl(codePoint)) {
                    clean.append(' ');
                } else if (drawable.computeIfAbsent(codePoint, this::canDraw)) {
                    clean.appendCodePoint(codePoint);
                } else {
                    clean.append('?');
                }
            });
            return clean.toString();
        }

        /** Both weights are tried: a letter is only kept if the heading font has it too. */
        private boolean canDraw(int codePoint) {
            String character = new String(Character.toChars(codePoint));
            try {
                regular.encode(character);
                bold.encode(character);
                return true;
            } catch (IOException | RuntimeException e) {
                return false;
            }
        }
    }
}
