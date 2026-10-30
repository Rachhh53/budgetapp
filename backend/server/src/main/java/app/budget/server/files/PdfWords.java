package app.budget.server.files;

import app.budget.engine.stubs.PayStub.Row;
import app.budget.engine.stubs.PayStub.Word;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Reads the words of a PDF and where each sits on its page, on this PC (spec §1: nothing is
 * sent anywhere). Making sense of them is the engine's job: {@code StubReader} for a pay stub,
 * {@code PdfStatement} for a card or bank statement.
 *
 * <p>Only text that is in the PDF as text is read. A scan or a photo has none, and comes back
 * as no rows at all; reading those needs text recognition, which is not built.
 */
public final class PdfWords {

    private PdfWords() {}

    /** Words whose baselines are within this many points share a row. Stub and statement type is 7 to 10 points tall. */
    private static final float SAME_ROW = 3;

    private record Placed(int page, float y, Word word) {}

    /**
     * The PDF's words, row by row from the top of its first page.
     *
     * @param mostPages how far into the document to read: a stub is a page or two, a statement a dozen at most, and
     *                  anything longer is some other document not worth reading through
     * @throws IOException when the PDF cannot be read
     */
    public static List<Row> rows(Path pdf, int mostPages) throws IOException {
        List<Placed> placed = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    collect(getCurrentPageNo(), positions, placed);
                }
            };
            stripper.setSortByPosition(true);
            stripper.setEndPage(mostPages);
            // The text itself is thrown away: the words are gathered, with their places, as it is written.
            stripper.getText(document);
        }
        return rows(placed);
    }

    /** Cuts a run of characters into words at its spaces, each with where it starts and ends. */
    private static void collect(int page, List<TextPosition> positions, List<Placed> placed) {
        StringBuilder text = new StringBuilder();
        float start = 0;
        float end = 0;
        float y = 0;
        for (TextPosition position : positions) {
            String characters = position.getUnicode();
            if (characters == null || characters.isBlank()) {
                if (!text.isEmpty()) {
                    placed.add(new Placed(page, y, new Word(start, end, text.toString())));
                    text.setLength(0);
                }
                continue;
            }
            if (text.isEmpty()) {
                start = position.getXDirAdj();
                y = position.getYDirAdj();
            }
            end = position.getXDirAdj() + position.getWidthDirAdj();
            text.append(characters);
        }
        if (!text.isEmpty()) {
            placed.add(new Placed(page, y, new Word(start, end, text.toString())));
        }
    }

    /**
     * Gathers words into rows by how far down the page they sit, whatever order the PDF drew
     * them in: a stub's left and right halves are often drawn one after the other.
     */
    private static List<Row> rows(List<Placed> placed) {
        placed.sort(Comparator.comparingInt(Placed::page).thenComparingDouble(Placed::y));
        List<Row> rows = new ArrayList<>();
        List<Word> current = new ArrayList<>();
        int page = 0;
        float y = 0;
        for (Placed word : placed) {
            if (!current.isEmpty() && (word.page() != page || word.y() - y > SAME_ROW)) {
                rows.add(row(page, current));
                current = new ArrayList<>();
            }
            if (current.isEmpty()) {
                page = word.page();
                y = word.y();
            }
            current.add(word.word());
        }
        if (!current.isEmpty()) {
            rows.add(row(page, current));
        }
        return rows;
    }

    private static Row row(int page, List<Word> words) {
        words.sort(Comparator.comparingDouble(Word::x));
        return new Row(page, List.copyOf(words));
    }
}
