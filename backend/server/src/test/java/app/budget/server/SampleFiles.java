package app.budget.server;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Small real files made on the spot, for tests that attach receipts. Nothing here is read from disk. */
final class SampleFiles {

    private SampleFiles() {}

    /** A PDF of {@code pages} pages, each saying {@code words}, so two calls with different words differ. */
    static byte[] pdf(int pages, String words) {
        return pdf(pages, words, null);
    }

    /** A PDF that cannot be opened without {@code password}. */
    static byte[] lockedPdf(String password) {
        return pdf(1, "Locked", password);
    }

    private static byte[] pdf(int pages, String words, String password) {
        try (PDDocument document = new PDDocument()) {
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    content.newLineAtOffset(72, 700);
                    content.showText(words + " page " + i);
                    content.endText();
                }
            }
            if (password != null) {
                document.protect(new StandardProtectionPolicy(password, password, new AccessPermission()));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] png(Color color) {
        return picture("png", color);
    }

    static byte[] jpeg(Color color) {
        return picture("jpg", color);
    }

    private static byte[] picture(String format, Color color) {
        BufferedImage image = new BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, 60, 40);
        graphics.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
