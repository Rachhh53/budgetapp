package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Backups, restore and the folder browser over HTTP, against temporary folders only. */
class BackupsApiTest extends ApiTestBase {

    private static final String CONFIRMED = "\"confirm\":\"restore-backup\"";

    /** Stands in for a folder the user chose, away from the app's own data. */
    @TempDir
    Path chosen;

    private Path inAppBackups() {
        return dataDir.resolve("backups");
    }

    private static String json(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private Reply chooseFolder(Path folder) throws Exception {
        return server.put("/api/backups/folder", "{\"path\":\"%s\"}".formatted(json(folder)), cookie);
    }

    /** Backs up now and returns the name of the backup just made. */
    private String backUp() throws Exception {
        Reply reply = server.post("/api/backups", null, cookie);
        assertThat(reply.status()).isEqualTo(201);
        return reply.data().get("backups").get(0).get("name").asText();
    }

    private Reply restore(String name) throws Exception {
        return server.post("/api/backups/restore", "{\"name\":\"%s\",%s}".formatted(name, CONFIRMED), cookie);
    }

    private List<String> accountNames() throws Exception {
        List<String> names = new ArrayList<>();
        server.get("/api/accounts", cookie).data().forEach(account -> names.add(account.get("name").asText()));
        return names;
    }

    private static List<String> zipsIn(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(file -> file.getFileName().toString()).filter(name -> name.endsWith(".zip")).sorted().toList();
        }
    }

    private static Map<String, byte[]> entriesOf(Path zip) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        return entries;
    }

    private static void writeZip(Path zip, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    @Nested
    class Auth {
        @Test
        void everyBackupAndFolderRouteRequiresASession() throws Exception {
            String name = backUp();

            assertThat(server.get("/api/backups", null).status()).isEqualTo(401);
            assertThat(server.post("/api/backups", null, null).status()).isEqualTo(401);
            assertThat(server.put("/api/backups/folder", "{\"path\":\"%s\"}".formatted(json(chosen)), null).status()).isEqualTo(401);
            assertThat(server.post("/api/backups/restore", "{\"name\":\"%s\",%s}".formatted(name, CONFIRMED), null).status())
                    .isEqualTo(401);
            assertThat(server.get("/api/folders", null).status()).isEqualTo(401);
            assertThat(server.post("/api/folders", "{\"parent\":\"%s\",\"name\":\"New\"}".formatted(json(chosen)), null).status())
                    .isEqualTo(401);
            // Nothing was made or moved by any of those.
            assertThat(zipsIn(inAppBackups())).containsExactly(name);
            assertThat(Files.exists(chosen.resolve("New"))).isFalse();
        }
    }

    @Nested
    class BackingUp {
        @Test
        void aFreshAppKeepsBackupsInItsOwnFolderAndHasNoneYet() throws Exception {
            Reply reply = server.get("/api/backups", cookie);

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.data().get("usingDefaultFolder").asBoolean()).isTrue();
            assertThat(reply.data().get("folder").asText()).isEqualTo(inAppBackups().toString());
            assertThat(reply.data().get("keepCount").asInt()).isEqualTo(30);
            assertThat(reply.data().get("backups")).isEmpty();
            assertThat(reply.data().get("lastBackupAt").isNull()).isTrue();
            assertThat(reply.data().get("lastDailyBackupFailed").asBoolean()).isFalse();
        }

        @Test
        void backUpNowWritesOneZipWithTheDatabaseItsFilesAndANote() throws Exception {
            createAccount("Everyday checking", "checking");
            Files.createDirectories(dataDir.resolve("files"));
            Files.writeString(dataDir.resolve("files").resolve("receipt-abc.pdf"), "a receipt");

            Reply reply = server.post("/api/backups", null, cookie);

            assertThat(reply.status()).isEqualTo(201);
            // Not something Undo could take back, so it does not offer to.
            assertThat(reply.changed()).isFalse();
            JsonNode backup = reply.data().get("backups").get(0);
            assertThat(backup.get("name").asText()).isEqualTo("budget-backup-20261001-120000.zip");
            assertThat(backup.get("createdAt").asText()).isEqualTo("2026-10-01T12:00:00");
            assertThat(backup.get("sizeBytes").asLong()).isPositive();
            assertThat(reply.data().get("lastBackupAt").asText()).isEqualTo("2026-10-01T12:00:00");

            Map<String, byte[]> entries = entriesOf(inAppBackups().resolve(backup.get("name").asText()));
            assertThat(entries.keySet()).containsExactly("backup.json", "budget.db", "files/receipt-abc.pdf");
            assertThat(new String(entries.get("files/receipt-abc.pdf"), StandardCharsets.UTF_8)).isEqualTo("a receipt");
            assertThat(new String(entries.get("backup.json"), StandardCharsets.UTF_8)).contains("\"app\":\"Budget\"");
            // A real SQLite file, and nothing half-written is left beside it.
            assertThat(new String(entries.get("budget.db"), 0, 15, StandardCharsets.US_ASCII)).isEqualTo("SQLite format 3");
            try (Stream<Path> files = Files.list(inAppBackups())) {
                assertThat(files.map(file -> file.getFileName().toString())).noneMatch(name -> name.endsWith(".part"));
            }
            try (Stream<Path> files = Files.list(dataDir)) {
                assertThat(files.map(file -> file.getFileName().toString())).noneMatch(name -> name.startsWith("backup-work"));
            }
        }

        @Test
        void backupsGoToTheFolderChosen() throws Exception {
            Reply choice = chooseFolder(chosen);

            assertThat(choice.status()).isEqualTo(200);
            assertThat(choice.data().get("usingDefaultFolder").asBoolean()).isFalse();
            assertThat(choice.data().get("folder").asText()).isEqualTo(chosen.toString());

            String name = backUp();

            assertThat(zipsIn(chosen)).containsExactly(name);
            assertThat(zipsIn(inAppBackups())).isEmpty();
            // The settings route shows it but never takes it: a folder is checked before it is saved.
            assertThat(server.get("/api/settings", cookie).data().get("backup_folder").asText()).isEqualTo(chosen.toString());
            assertThat(server.put("/api/settings/backup_folder", "{\"value\":\"C:\\\\\"}", cookie).status()).isEqualTo(400);
        }

        @Test
        void aFolderThatIsNotThereOrIsAFileIsRefusedAndTheOldOneStays() throws Exception {
            Path file = Files.writeString(chosen.resolve("notes.txt"), "not a folder");

            assertThat(chooseFolder(chosen.resolve("missing")).status()).isEqualTo(400);
            assertThat(chooseFolder(file).status()).isEqualTo(400);
            assertThat(server.put("/api/backups/folder", "not json", cookie).status()).isEqualTo(400);

            assertThat(server.get("/api/backups", cookie).data().get("usingDefaultFolder").asBoolean()).isTrue();
        }

        @Test
        void choosingNoFolderGoesBackToTheAppsOwn() throws Exception {
            chooseFolder(chosen);

            Reply reply = server.put("/api/backups/folder", "{\"path\":null}", cookie);

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.data().get("usingDefaultFolder").asBoolean()).isTrue();
        }

        @Test
        void aChosenFolderThatHasGoneAwayIsExplainedKindly() throws Exception {
            Path usbStick = Files.createDirectory(chosen.resolve("usb"));
            chooseFolder(usbStick);
            Files.delete(usbStick);
            // Something in the way of the folder's name, as when a drive letter now points elsewhere.
            Files.writeString(usbStick, "not a folder any more");

            Reply reply = server.post("/api/backups", null, cookie);

            assertThat(reply.status()).isEqualTo(409);
            assertThat(reply.error()).contains("check it's still there");
            assertThat(reply.error()).doesNotContain(usbStick.toString());
        }

        @Test
        void onlyTheNewestAreKept() throws Exception {
            server.put("/api/settings/backup_keep_count", "{\"value\":\"7\"}", cookie);
            List<String> made = new ArrayList<>();
            for (int day = 0; day < 9; day++) {
                made.add(backUp());
                server.clock.advance(Duration.ofDays(1));
                // A day's idle time ends the session; unlocking again is not what is being tested.
                cookie = server.unlock();
            }

            assertThat(zipsIn(inAppBackups())).containsExactlyElementsOf(made.subList(2, 9));
            JsonNode listed = server.get("/api/backups", cookie).data().get("backups");
            assertThat(listed).hasSize(7);
            assertThat(listed.get(0).get("name").asText()).isEqualTo(made.get(8));
        }

        @Test
        void theKeepCountIsOneOfItsChoices() throws Exception {
            assertThat(server.put("/api/settings/backup_keep_count", "{\"value\":\"8\"}", cookie).status()).isEqualTo(400);
            assertThat(server.put("/api/settings/backup_keep_count", "{\"value\":\"90\"}", cookie).status()).isEqualTo(200);
            assertThat(server.get("/api/backups", cookie).data().get("keepCount").asInt()).isEqualTo(90);
        }

        @Test
        void theDailyBackupIsMadeOncePerDay() throws Exception {
            assertThat(server.backups().backUpIfDue()).isTrue();
            assertThat(server.backups().backUpIfDue()).isFalse();
            server.clock.advance(Duration.ofHours(11));
            assertThat(server.backups().backUpIfDue()).isFalse();

            server.clock.advance(Duration.ofHours(2));
            assertThat(server.backups().backUpIfDue()).isTrue();

            assertThat(zipsIn(inAppBackups())).hasSize(2);
        }

        @Test
        void aDailyBackupThatCannotBeSavedSaysSoAndNeverThrows() throws Exception {
            Path usbStick = Files.createDirectory(chosen.resolve("usb"));
            chooseFolder(usbStick);
            Files.delete(usbStick);
            Files.writeString(usbStick, "not a folder any more");

            assertThat(server.backups().backUpIfDue()).isFalse();
            assertThat(server.get("/api/backups", cookie).data().get("lastDailyBackupFailed").asBoolean()).isTrue();

            Files.delete(usbStick);
            Files.createDirectory(usbStick);
            assertThat(server.backups().backUpIfDue()).isTrue();
            assertThat(server.get("/api/backups", cookie).data().get("lastDailyBackupFailed").asBoolean()).isFalse();
        }
    }

    @Nested
    class Restoring {
        @Test
        void putsBackWhatWasThereAndKeepsACopyOfWhatItReplaced() throws Exception {
            createAccount("Everyday checking", "checking");
            Files.createDirectories(dataDir.resolve("files"));
            Files.writeString(dataDir.resolve("files").resolve("receipt-abc.pdf"), "a receipt");
            String name = backUp();
            createAccount("Rainy day", "savings");
            Files.delete(dataDir.resolve("files").resolve("receipt-abc.pdf"));
            Files.writeString(dataDir.resolve("files").resolve("later.pdf"), "added after the backup");

            Reply reply = restore(name);

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.data().get("restored").asBoolean()).isTrue();
            // Everyone unlocks afresh: what was on screen belonged to the data that was replaced.
            assertThat(server.get("/api/accounts", cookie).status()).isEqualTo(401);
            cookie = server.unlock();
            assertThat(accountNames()).containsExactly("Everyday checking");
            assertThat(Files.readString(dataDir.resolve("files").resolve("receipt-abc.pdf"))).isEqualTo("a receipt");
            assertThat(Files.exists(dataDir.resolve("files").resolve("later.pdf"))).isFalse();
            // What was replaced is kept, in the app's own folder, apart from the daily backups.
            assertThat(zipsIn(inAppBackups())).containsExactly(name, "budget-before-restore-20261001-120000.zip");
            assertThat(server.get("/api/backups", cookie).data().get("backups")).hasSize(1);
            try (Stream<Path> files = Files.list(dataDir)) {
                assertThat(files.map(file -> file.getFileName().toString())).noneMatch(file -> file.startsWith("restore-work"));
            }
        }

        @Test
        void bringsDataBackAfterEverythingWasErased() throws Exception {
            chooseFolder(chosen);
            createAccount("Everyday checking", "checking");
            String name = backUp();
            server.send("DELETE", "/api/data", "{\"confirm\":\"erase-all-data\"}", Map.of("Cookie", cookie));
            cookie = server.unlock();
            assertThat(accountNames()).isEmpty();
            // Erasing forgot the folder too; backups saved elsewhere were not touched.
            chooseFolder(chosen);

            assertThat(restore(name).status()).isEqualTo(200);

            cookie = server.unlock();
            assertThat(accountNames()).containsExactly("Everyday checking");
            assertThat(server.get("/api/backups", cookie).data().get("folder").asText()).isEqualTo(chosen.toString());
        }

        @Test
        void keepsThePinThatIsSetNowNotTheOneInTheBackup() throws Exception {
            String name = backUp();
            server.put("/api/pin", "{\"newPin\":\"4829\"}", cookie);

            assertThat(restore(name).status()).isEqualTo(200);

            Reply status = server.get("/api/session", null);
            assertThat(status.data().get("pinSet").asBoolean()).isTrue();
            assertThat(server.send("POST", "/api/session", "{\"pin\":\"4829\"}", Map.of()).status()).isEqualTo(201);
        }

        @Test
        void leavesNothingForUndoThatWasNeverAChange() throws Exception {
            createAccount("Everyday checking", "checking");
            String name = backUp();

            restore(name);
            cookie = server.unlock();

            // The account's own change is still there to undo; nothing else was added by the restore.
            assertThat(server.post("/api/undo", null, cookie).data().get("undone").asBoolean()).isTrue();
            assertThat(accountNames()).isEmpty();
            assertThat(server.get("/api/undo", cookie).data().get("available").asBoolean()).isFalse();
        }

        @Test
        void needsTheConfirmationAndANameFromTheList() throws Exception {
            createAccount("Everyday checking", "checking");
            String name = backUp();
            createAccount("Rainy day", "savings");

            assertThat(server.post("/api/backups/restore", "{\"name\":\"%s\"}".formatted(name), cookie).status()).isEqualTo(400);
            assertThat(server.post("/api/backups/restore", "not json", cookie).status()).isEqualTo(400);
            assertThat(restore("budget-backup-20200101-000000.zip").status()).isEqualTo(404);
            // A name is only ever one from the list: nothing else on the disk can be reached with it.
            assertThat(restore("../" + name).status()).isEqualTo(404);
            assertThat(restore("budget-before-restore-20261001-120000.zip").status()).isEqualTo(404);

            assertThat(accountNames()).containsExactly("Everyday checking", "Rainy day");
            assertThat(server.get("/api/accounts", cookie).status()).isEqualTo(200);
        }

        @Test
        void aDamagedBackupIsRefusedAndNothingChanges() throws Exception {
            createAccount("Everyday checking", "checking");
            Files.createDirectories(inAppBackups());
            Files.writeString(inAppBackups().resolve("budget-backup-20260901-080000.zip"), "this is not a zip file");
            String noDatabase = "budget-backup-20260902-080000.zip";
            writeZip(inAppBackups().resolve(noDatabase), Map.of("backup.json", "{\"app\":\"Budget\"}".getBytes(StandardCharsets.UTF_8)));
            String notOurs = "budget-backup-20260903-080000.zip";
            writeZip(inAppBackups().resolve(notOurs), Map.of(
                    "backup.json", "{\"app\":\"Budget\",\"createdAt\":\"x\",\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8),
                    "budget.db", "not a database".getBytes(StandardCharsets.UTF_8)));

            for (String name : List.of("budget-backup-20260901-080000.zip", noDatabase, notOurs)) {
                Reply reply = restore(name);

                assertThat(reply.status()).as(name).isEqualTo(400);
                assertThat(reply.error()).contains("nothing was changed");
            }
            assertThat(accountNames()).containsExactly("Everyday checking");
        }

        @Test
        void aBackupFromANewerVersionIsRefusedAndNothingChanges() throws Exception {
            createAccount("Everyday checking", "checking");
            String name = backUp();
            // Make the backup look as if a later version of the app had written it.
            Path zip = inAppBackups().resolve(name);
            Map<String, byte[]> entries = entriesOf(zip);
            Path db = chosen.resolve("newer.db");
            Files.write(db, entries.get("budget.db"));
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
                    Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO flyway_schema_history
                            (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
                        VALUES (999, '999', 'from the future', 'SQL', 'V999__future.sql', 1, 'test', 1, 1)
                        """);
            }
            entries.put("budget.db", Files.readAllBytes(db));
            writeZip(zip, entries);
            createAccount("Rainy day", "savings");

            Reply reply = restore(name);

            assertThat(reply.status()).isEqualTo(409);
            assertThat(reply.error()).contains("newer version");
            assertThat(accountNames()).containsExactly("Everyday checking", "Rainy day");
        }

        @Test
        void aBackupCannotWriteOutsideTheFilesFolder() throws Exception {
            String name = backUp();
            Path zip = inAppBackups().resolve(name);
            Map<String, byte[]> entries = entriesOf(zip);
            entries.put("files/../../escaped.txt", "should never land".getBytes(StandardCharsets.UTF_8));
            entries.put("files/kept.txt", "an ordinary attachment".getBytes(StandardCharsets.UTF_8));
            writeZip(zip, entries);

            assertThat(restore(name).status()).isEqualTo(200);

            assertThat(Files.readString(dataDir.resolve("files").resolve("kept.txt"))).isEqualTo("an ordinary attachment");
            assertThat(Files.exists(dataDir.resolve("escaped.txt"))).isFalse();
            assertThat(Files.exists(dataDir.getParent().resolve("escaped.txt"))).isFalse();
        }
    }

    @Nested
    class Folders {
        @Test
        void theTopListsTheDrives() throws Exception {
            Reply reply = server.get("/api/folders", cookie);

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.data().get("path").isNull()).isTrue();
            assertThat(reply.data().get("folders")).isNotEmpty();
            assertThat(reply.data().get("writable").asBoolean()).isFalse();
        }

        @Test
        void aFolderListsTheFoldersInsideItByNameAndNeverItsFiles() throws Exception {
            Files.createDirectory(chosen.resolve("zebra"));
            Files.createDirectory(chosen.resolve("Apple"));
            Files.writeString(chosen.resolve("statement.pdf"), "a file");

            Reply reply = server.get("/api/folders?path=" + java.net.URLEncoder.encode(chosen.toString(), StandardCharsets.UTF_8), cookie);

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.data().get("path").asText()).isEqualTo(chosen.toString());
            assertThat(reply.data().get("parent").asText()).isEqualTo(chosen.getParent().toString());
            assertThat(reply.data().get("writable").asBoolean()).isTrue();
            assertThat(reply.data().get("folders").findValuesAsText("name")).containsExactly("Apple", "zebra");
            assertThat(reply.data().get("folders").get(0).get("path").asText()).isEqualTo(chosen.resolve("Apple").toString());
        }

        @Test
        void aPathThatIsNotAFolderIsExplainedKindly() throws Exception {
            String missing = java.net.URLEncoder.encode(chosen.resolve("missing").toString(), StandardCharsets.UTF_8);

            Reply reply = server.get("/api/folders?path=" + missing, cookie);

            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.error()).contains("couldn't open that folder");
        }

        @Test
        void aNewFolderCanBeMadeAndIsThenOpened() throws Exception {
            Reply reply = server.post("/api/folders", "{\"parent\":\"%s\",\"name\":\" Budget backups \"}".formatted(json(chosen)), cookie);

            assertThat(reply.status()).isEqualTo(201);
            assertThat(reply.data().get("path").asText()).isEqualTo(chosen.resolve("Budget backups").toString());
            assertThat(Files.isDirectory(chosen.resolve("Budget backups"))).isTrue();
        }

        @Test
        void aNameWindowsWouldRefuseOrThatClimbsOutIsNotMade() throws Exception {
            Files.createDirectory(chosen.resolve("Taken"));

            for (String bad : new String[] {"", "   ", "a/b", "a\\\\b", "..", "what?", "ends.", "Taken", "x".repeat(101)}) {
                Reply reply = server.post("/api/folders", "{\"parent\":\"%s\",\"name\":\"%s\"}".formatted(json(chosen), bad), cookie);

                assertThat(reply.status()).as("name '%s'", bad).isEqualTo(400);
            }
            assertThat(server.post("/api/folders", "{\"name\":\"x\"}", cookie).status()).isEqualTo(400);
            try (Stream<Path> inside = Files.list(chosen)) {
                assertThat(inside.map(path -> path.getFileName().toString())).containsExactly("Taken");
            }
            assertThat(Files.exists(chosen.getParent().resolve("b"))).isFalse();
        }
    }
}
