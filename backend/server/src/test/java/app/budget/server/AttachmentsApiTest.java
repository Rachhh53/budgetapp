package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Download;
import app.budget.server.TestServer.FilePart;
import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Receipts and documents over real HTTP against a temporary data folder (spec §4.8).
 * "Today" is Thursday 2026-10-01.
 */
class AttachmentsApiTest extends ApiTestBase {

    private static final FilePart INVOICE = new FilePart("invoice-valley-peds.pdf", SampleFiles.pdf(2, "Invoice"));
    private static final FilePart PHOTO = new FilePart("receipt.jpg", SampleFiles.jpeg(Color.ORANGE));

    long checking;
    long expense;

    @BeforeEach
    void addAnExpense() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        expense = created("/api/entries", """
                {"kind":"money_out","date":"2026-09-28","amountCents":8500,"description":"Copay plus lab work",
                 "payee":"Valley Pediatrics","accountId":%d}""".formatted(checking));
    }

    private String path() {
        return "/api/entries/" + expense + "/attachments";
    }

    /** Every file under the data folder's files/, however deep. */
    private List<Path> stored() throws Exception {
        Path files = dataDir.resolve("files");
        if (!Files.isDirectory(files)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(files)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    @Nested
    class Auth {
        @Test
        void everyAttachmentRouteRequiresASession() throws Exception {
            long id = server.upload(path(), cookie, INVOICE).data().get("attachments").get(0).get("id").asLong();

            assertThat(server.get(path(), null).status()).isEqualTo(401);
            assertThat(server.upload(path(), null, PHOTO).status()).isEqualTo(401);
            assertThat(server.get("/api/items/1/occurrences/2026-10-05/attachments", null).status()).isEqualTo(401);
            assertThat(server.upload("/api/items/1/occurrences/2026-10-05/attachments", null, PHOTO).status()).isEqualTo(401);
            assertThat(server.get("/api/cards/1/statements/1/attachments", null).status()).isEqualTo(401);
            assertThat(server.upload("/api/cards/1/statements/1/attachments", null, PHOTO).status()).isEqualTo(401);
            assertThat(server.download("/api/attachments/" + id + "/file", null, null).status()).isEqualTo(401);
            assertThat(server.download("/api/attachments/" + id + "/preview", null, null).status()).isEqualTo(401);
            assertThat(server.delete("/api/attachments/" + id, null).status()).isEqualTo(401);
            assertThat(server.post("/api/attachments/" + id + "/copy", null, null).status()).isEqualTo(401);
            assertThat(server.downloads).isEmptyDirectory();
            assertThat(server.get("/api/attachment-counts", null).status()).isEqualTo(401);
            // Nothing was changed by any of those, and no file was handed over.
            assertThat(server.get(path(), cookie).data().get("attachments")).hasSize(1);
            assertThat(new String(server.download("/api/attachments/" + id + "/file", null, null).body(), StandardCharsets.UTF_8))
                    .doesNotContain("%PDF");
        }
    }

    @Nested
    class Attaching {
        @Test
        void keepsAPdfAndAPictureAndSaysWhatTheyAre() throws Exception {
            Reply reply = server.upload(path(), cookie, INVOICE, PHOTO);

            assertThat(reply.status()).isEqualTo(201);
            assertThat(reply.changed()).isTrue();
            JsonNode attached = reply.data().get("attachments");
            assertThat(attached).hasSize(2);
            assertThat(attached.get(0).get("name").asText()).isEqualTo("invoice-valley-peds.pdf");
            assertThat(attached.get(0).get("mime").asText()).isEqualTo("application/pdf");
            assertThat(attached.get(0).get("pages").asInt()).isEqualTo(2);
            assertThat(attached.get(0).get("sizeBytes").asLong()).isEqualTo(INVOICE.content().length);
            assertThat(attached.get(1).get("mime").asText()).isEqualTo("image/jpeg");
            assertThat(attached.get(1).get("pages").isNull()).isTrue();
            assertThat(reply.data().get("maxBytes").asLong()).isEqualTo(25L * 1024 * 1024);
            // The entry itself says how many files it has.
            assertThat(server.get("/api/entries/" + expense, cookie).data().get("entry").get("fileCount").asInt()).isEqualTo(2);
        }

        @Test
        void theFileOnDiskIsNamedByItsContentsNeverByWhatTheUserCalledIt() throws Exception {
            server.upload(path(), cookie, INVOICE);

            List<Path> files = stored();
            assertThat(files).hasSize(1);
            assertThat(files.getFirst().getFileName().toString()).matches("[0-9a-f]{64}");
            assertThat(Files.readAllBytes(files.getFirst())).isEqualTo(INVOICE.content());
        }

        @Test
        void theSameFileAttachedTwiceIsStoredOnce() throws Exception {
            long other = created("/api/entries", """
                    {"kind":"money_out","date":"2026-09-19","amountCents":2360,"description":"Prescription","accountId":%d}"""
                    .formatted(checking));

            server.upload(path(), cookie, INVOICE);
            server.upload("/api/entries/" + other + "/attachments", cookie, new FilePart("the same again.pdf", INVOICE.content()));

            assertThat(stored()).hasSize(1);
            assertThat(server.get("/api/entries/" + other + "/attachments", cookie).data().get("attachments").get(0).get("name").asText())
                    .isEqualTo("the same again.pdf");
        }

        @Test
        void aNameIsOnlyEverItsLastPartAndIsNeverEmpty() throws Exception {
            Reply reply = server.upload(path(), cookie,
                    new FilePart("C:\\\\Users\\\\amara\\\\Scans\\\\lab results.png", SampleFiles.png(Color.BLUE)),
                    new FilePart("", SampleFiles.png(Color.RED)));

            assertThat(reply.data().get("attachments").get(0).get("name").asText()).isEqualTo("lab results.png");
            assertThat(reply.data().get("attachments").get(1).get("name").asText()).isEqualTo("Picture.png");
        }

        @Test
        void whatAFileIsComesFromItsContentsNotItsName() throws Exception {
            Reply renamed = server.upload(path(), cookie, new FilePart("looks-like-a-picture.png", INVOICE.content()));
            assertThat(renamed.data().get("attachments").get(0).get("mime").asText()).isEqualTo("application/pdf");

            Reply program = server.upload(path(), cookie, new FilePart("invoice.pdf", "MZ this is not a PDF".getBytes(StandardCharsets.UTF_8)));
            assertThat(program.status()).isEqualTo(415);
            assertThat(program.error()).contains("PDFs and JPEG or PNG pictures");
            assertThat(program.changed()).isFalse();
        }

        @Test
        void explainsKindlyWhatCannotBeKept() throws Exception {
            assertThat(server.upload(path(), cookie, new FilePart("empty.pdf", new byte[0])).error())
                    .isEqualTo("That file is empty. Please choose another.");
            assertThat(server.upload(path(), cookie, new FilePart("cut-short.pdf", "%PDF-1.7 and then nothing".getBytes(StandardCharsets.UTF_8)))
                    .error()).contains("We couldn't read that file");
            assertThat(server.upload(path(), cookie, new FilePart("cut-short.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2}))
                    .error()).contains("We couldn't read that file");
            Reply locked = server.upload(path(), cookie, new FilePart("locked.pdf", SampleFiles.lockedPdf("s3cret")));
            assertThat(locked.status()).isEqualTo(422);
            assertThat(locked.error()).contains("locked with a password");
            assertThat(server.upload(path(), cookie).error()).isEqualTo("Please choose a file to attach.");
            assertThat(server.post(path(), "{}", cookie).error()).isEqualTo("Please choose a file to attach.");
            // None of that attached anything.
            assertThat(server.get(path(), cookie).data().get("attachments")).isEmpty();
        }

        @Test
        void oneRefusedFileLeavesNothingHalfAttached() throws Exception {
            Reply reply = server.upload(path(), cookie, INVOICE, new FilePart("notes.txt", "just text".getBytes(StandardCharsets.UTF_8)));

            assertThat(reply.status()).isEqualTo(415);
            assertThat(server.get(path(), cookie).data().get("attachments")).isEmpty();
        }

        @Test
        void aFileOverTheLimitIsRefusedInWords() throws Exception {
            byte[] big = new byte[25 * 1024 * 1024 + 512];
            System.arraycopy(INVOICE.content(), 0, big, 0, INVOICE.content().length);

            Reply reply = server.upload(path(), cookie, new FilePart("huge.pdf", big));

            assertThat(reply.status()).isEqualTo(413);
            assertThat(reply.error()).contains("larger than 25 MB");
            assertThat(stored()).isEmpty();
        }

        @Test
        void noMoreThanTwentyFilesOnOneThing() throws Exception {
            for (int i = 0; i < 20; i++) {
                assertThat(server.upload(path(), cookie, new FilePart("page-" + i + ".pdf", SampleFiles.pdf(1, "Scan " + i))).status())
                        .isEqualTo(201);
            }

            Reply oneMore = server.upload(path(), cookie, PHOTO);

            assertThat(oneMore.status()).isEqualTo(409);
            assertThat(oneMore.error()).contains("more than 20 files");
        }

        @Test
        void anUnknownEntryIsNotFound() throws Exception {
            assertThat(server.upload("/api/entries/999/attachments", cookie, PHOTO).status()).isEqualTo(404);
            assertThat(server.get("/api/entries/abc/attachments", cookie).status()).isEqualTo(404);
        }
    }

    @Nested
    class ABillAndAStatement {
        @Test
        void aReceiptGoesOnOneTimeARecurringBillCameDue() throws Exception {
            createSalary(checking);
            long electric = created("/api/items", """
                    {"name":"Electric","kind":"bill","amountCents":9400,"payFromAccountId":%d,
                     "schedule":{"frequency":"monthly","day1":5,"startDate":"2026-01-01"}}""".formatted(checking));
            String october = "/api/items/" + electric + "/occurrences/2026-10-05/attachments";

            assertThat(server.upload(october, cookie, INVOICE).status()).isEqualTo(201);

            assertThat(server.get(october, cookie).data().get("attachments")).hasSize(1);
            // September's bill is a different time of the same item.
            assertThat(server.get("/api/items/" + electric + "/occurrences/2026-09-05/attachments", cookie).data().get("attachments"))
                    .isEmpty();
            JsonNode counts = server.get("/api/attachment-counts?from=2026-10-01&to=2026-10-31", cookie).data();
            assertThat(counts.get("occurrences")).hasSize(1);
            assertThat(counts.get("occurrences").get(0).get("itemId").asLong()).isEqualTo(electric);
            assertThat(counts.get("occurrences").get(0).get("scheduledDate").asText()).isEqualTo("2026-10-05");
            assertThat(counts.get("occurrences").get(0).get("count").asInt()).isEqualTo(1);
            // A day the bill does not fall on has nothing to attach to.
            assertThat(server.upload("/api/items/" + electric + "/occurrences/2026-10-06/attachments", cookie, PHOTO).status())
                    .isEqualTo(404);
            assertThat(server.upload("/api/items/" + electric + "/occurrences/not-a-date/attachments", cookie, PHOTO).status())
                    .isEqualTo(404);
        }

        @Test
        void deletingTheBillTakesItsReceiptsWithIt() throws Exception {
            createSalary(checking);
            long electric = created("/api/items", """
                    {"name":"Electric","kind":"bill","amountCents":9400,"payFromAccountId":%d,
                     "schedule":{"frequency":"monthly","day1":5,"startDate":"2026-01-01"}}""".formatted(checking));
            long id = server.upload("/api/items/" + electric + "/occurrences/2026-10-05/attachments", cookie, INVOICE)
                    .data().get("attachments").get(0).get("id").asLong();

            server.delete("/api/items/" + electric, cookie);

            assertThat(server.download("/api/attachments/" + id + "/file", null, cookie).status()).isEqualTo(404);
        }

        @Test
        void aStatementPdfGoesOnACardStatement() throws Exception {
            long card = createAccount("Travel Rewards Visa", "credit_card");
            server.put("/api/cards/" + card + "/settings", "{\"statementCloseDay\":20,\"dueDay\":13}", cookie);
            JsonNode statements = server.post("/api/cards/" + card + "/statements",
                    "{\"closeDate\":\"2026-09-20\",\"balanceCents\":68248,\"minimumDueCents\":4000}", cookie).data().get("statements");
            long statement = -1;
            for (JsonNode candidate : statements) {
                if (candidate.get("entered").asBoolean()) {
                    statement = candidate.get("id").asLong();
                }
            }
            String path = "/api/cards/" + card + "/statements/" + statement + "/attachments";

            assertThat(server.upload(path, cookie, INVOICE).status()).isEqualTo(201);

            assertThat(server.get(path, cookie).data().get("attachments")).hasSize(1);
            JsonNode counts = server.get("/api/attachment-counts", cookie).data().get("statements");
            assertThat(counts.get(0).get("statementId").asLong()).isEqualTo(statement);
            assertThat(counts.get(0).get("count").asInt()).isEqualTo(1);
            // Not another card's statement, not an account that is not a card.
            assertThat(server.upload("/api/cards/" + checking + "/statements/" + statement + "/attachments", cookie, PHOTO).status())
                    .isEqualTo(404);
            assertThat(server.upload("/api/cards/" + card + "/statements/999/attachments", cookie, PHOTO).status()).isEqualTo(404);
            // Correcting the statement keeps what was attached to it.
            server.post("/api/cards/" + card + "/statements",
                    "{\"closeDate\":\"2026-09-20\",\"balanceCents\":70000,\"minimumDueCents\":4000}", cookie);
            assertThat(server.get(path, cookie).data().get("attachments")).hasSize(1);
        }
    }

    @Nested
    class ShowingAndSaving {
        @Test
        void savesACopyIntoDownloadsFromThisPcAndSaysWhatItIsCalled() throws Exception {
            long id = server.upload(path(), cookie, new FilePart("Zoë's invoice.pdf", INVOICE.content()))
                    .data().get("attachments").get(0).get("id").asLong();

            Reply first = server.post("/api/attachments/" + id + "/copy", null, cookie);
            Reply second = server.post("/api/attachments/" + id + "/copy", null, cookie);

            assertThat(first.status()).isEqualTo(200);
            assertThat(first.data().get("saved").get("fileName").asText()).isEqualTo("Zoë's invoice.pdf");
            assertThat(first.data().get("saved").get("folder").asText()).isEqualTo(server.downloads.toString());
            assertThat(server.downloads.resolve("Zoë's invoice.pdf")).hasBinaryContent(INVOICE.content());
            // A second copy sits beside the first; nothing is replaced, and nothing in the budget changed.
            assertThat(second.data().get("saved").get("fileName").asText()).isEqualTo("Zoë's invoice (2).pdf");
            assertThat(first.changed()).isFalse();
            assertThat(server.post("/api/attachments/999999/copy", null, cookie).status()).isEqualTo(404);
        }

        @Test
        void savesACopyExactlyAsItWasAttachedUnderItsOwnName() throws Exception {
            long id = server.upload(path(), cookie, new FilePart("Zoë's invoice.pdf", INVOICE.content()))
                    .data().get("attachments").get(0).get("id").asLong();

            Download file = server.download("/api/attachments/" + id + "/file", null, cookie);

            assertThat(file.status()).isEqualTo(200);
            assertThat(file.body()).isEqualTo(INVOICE.content());
            assertThat(file.header("Content-Type")).isEqualTo("application/pdf");
            assertThat(file.header("Content-Disposition")).isEqualTo("attachment; filename*=UTF-8''Zo%C3%AB%27s%20invoice.pdf");
            assertThat(file.header("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(file.header("Cache-Control")).isEqualTo("no-store");
        }

        @Test
        void aPdfIsShownOnePageAtATimeAsAPicture() throws Exception {
            long id = server.upload(path(), cookie, INVOICE).data().get("attachments").get(0).get("id").asLong();

            Download second = server.download("/api/attachments/" + id + "/preview?page=2", null, cookie);

            assertThat(second.status()).isEqualTo(200);
            assertThat(second.header("Content-Type")).isEqualTo("image/png");
            assertThat(ImageIO.read(new ByteArrayInputStream(second.body())).getWidth()).isGreaterThan(500);
            assertThat(server.download("/api/attachments/" + id + "/preview", null, cookie).status()).isEqualTo(200);
            assertThat(server.download("/api/attachments/" + id + "/preview?page=3", null, cookie).status()).isEqualTo(404);
            assertThat(server.download("/api/attachments/" + id + "/preview?page=0", null, cookie).status()).isEqualTo(404);
            assertThat(server.download("/api/attachments/" + id + "/preview?page=two", null, cookie).status()).isEqualTo(404);
        }

        @Test
        void aPictureIsShownAsItself() throws Exception {
            long id = server.upload(path(), cookie, PHOTO).data().get("attachments").get(0).get("id").asLong();

            Download preview = server.download("/api/attachments/" + id + "/preview", null, cookie);

            assertThat(preview.header("Content-Type")).isEqualTo("image/jpeg");
            assertThat(preview.body()).isEqualTo(PHOTO.content());
        }

        @Test
        void aFileThatHasGoneFromTheFolderIsExplainedNotACrash() throws Exception {
            long id = server.upload(path(), cookie, INVOICE).data().get("attachments").get(0).get("id").asLong();
            Files.delete(stored().getFirst());

            Reply reply = server.get("/api/attachments/" + id + "/file", cookie);

            assertThat(reply.status()).isEqualTo(410);
            assertThat(reply.error()).contains("couldn't find this file on this PC");
        }

        @Test
        void anUnknownAttachmentIsNotFound() throws Exception {
            assertThat(server.get("/api/attachments/999/file", cookie).status()).isEqualTo(404);
            assertThat(server.get("/api/attachments/999/preview", cookie).status()).isEqualTo(404);
            assertThat(server.delete("/api/attachments/999", cookie).status()).isEqualTo(404);
        }
    }

    @Nested
    class RemovingAndUndo {
        @Test
        void removingTakesItOffTheEntryAndUndoPutsItBackWithItsFile() throws Exception {
            long id = server.upload(path(), cookie, INVOICE).data().get("attachments").get(0).get("id").asLong();

            Reply removed = server.delete("/api/attachments/" + id, cookie);

            assertThat(removed.status()).isEqualTo(200);
            assertThat(removed.data().get("attachments")).isEmpty();
            assertThat(server.get("/api/attachments/" + id + "/file", cookie).status()).isEqualTo(404);
            // The file stays on disk for as long as Undo could want it.
            assertThat(stored()).hasSize(1);

            server.post("/api/undo", null, cookie);

            assertThat(server.get(path(), cookie).data().get("attachments")).hasSize(1);
            assertThat(server.download("/api/attachments/" + id + "/file", null, cookie).body()).isEqualTo(INVOICE.content());
        }

        @Test
        void undoingAnUploadTakesTheAttachmentAwayAgain() throws Exception {
            server.upload(path(), cookie, INVOICE);

            server.post("/api/undo", null, cookie);

            assertThat(server.get(path(), cookie).data().get("attachments")).isEmpty();
        }

        @Test
        void deletingTheEntryTakesItsReceiptsAndUndoBringsBothBack() throws Exception {
            long id = server.upload(path(), cookie, INVOICE, PHOTO).data().get("attachments").get(0).get("id").asLong();

            server.delete("/api/entries/" + expense, cookie);
            assertThat(server.get("/api/attachments/" + id + "/file", cookie).status()).isEqualTo(404);

            server.post("/api/undo", null, cookie);

            assertThat(server.get(path(), cookie).data().get("attachments")).hasSize(2);
            assertThat(server.download("/api/attachments/" + id + "/file", null, cookie).body()).isEqualTo(INVOICE.content());
        }
    }

    @Nested
    class TidyingAtStart {
        private void restart() throws Exception {
            server.close();
            server = new TestServer(dataDir);
            cookie = server.unlock();
        }

        @Test
        void aRemovedFileStaysWhileUndoCouldBringItBack() throws Exception {
            long id = server.upload(path(), cookie, INVOICE).data().get("attachments").get(0).get("id").asLong();
            server.delete("/api/attachments/" + id, cookie);

            restart();

            assertThat(stored()).hasSize(1);
            server.post("/api/undo", null, cookie);
            assertThat(server.download("/api/attachments/" + id + "/file", null, cookie).body()).isEqualTo(INVOICE.content());
        }

        @Test
        void aRemovedFileIsDeletedOnceUndoHasForgottenIt() throws Exception {
            long id = server.upload(path(), cookie, INVOICE).data().get("attachments").get(0).get("id").asLong();
            server.upload(path(), cookie, PHOTO);
            server.delete("/api/attachments/" + id, cookie);
            // A hundred later changes push the removal out of what Undo remembers.
            for (int i = 0; i < 100; i++) {
                server.put("/api/settings/theme", "{\"value\":\"%s\"}".formatted(i % 2 == 0 ? "dark" : "light"), cookie);
            }

            restart();

            // The picture is still attached, so it stays; the invoice is gone from disk.
            List<Path> left = stored();
            assertThat(left).hasSize(1);
            assertThat(Files.readAllBytes(left.getFirst())).isEqualTo(PHOTO.content());
            assertThat(server.get(path(), cookie).data().get("attachments")).hasSize(1);
        }

        @Test
        void anUploadThatNeverFinishedIsClearedAwayAndOtherFilesAreLeftAlone() throws Exception {
            server.upload(path(), cookie, INVOICE);
            Path files = dataDir.resolve("files");
            Files.writeString(files.resolve("upload-123.part"), "half a file");
            Files.writeString(files.resolve("something-else.txt"), "not ours to delete");

            restart();

            assertThat(Files.exists(files.resolve("upload-123.part"))).isFalse();
            assertThat(Files.exists(files.resolve("something-else.txt"))).isTrue();
            assertThat(stored()).hasSize(2);
        }
    }

    @Nested
    class Backups {
        @Test
        void aBackupCarriesTheFilesAndRestoreBringsThemBack() throws Exception {
            long id = server.upload(path(), cookie, INVOICE).data().get("attachments").get(0).get("id").asLong();
            String backup = server.post("/api/backups", null, cookie).data().get("backups").get(0).get("name").asText();
            // After the backup: the expense is deleted and its file is lost from the folder.
            server.delete("/api/entries/" + expense, cookie);
            Files.delete(stored().getFirst());

            Reply restored = server.post("/api/backups/restore",
                    "{\"name\":\"" + backup + "\",\"confirm\":\"restore-backup\"}", cookie);

            assertThat(restored.status()).isEqualTo(200);
            cookie = server.unlock();
            assertThat(server.download("/api/attachments/" + id + "/file", null, cookie).body()).isEqualTo(INVOICE.content());
        }
    }
}
