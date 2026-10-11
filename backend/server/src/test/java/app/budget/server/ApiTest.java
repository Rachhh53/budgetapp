package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import app.budget.server.lifecycle.ShutdownRoutes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end tests of every endpoint over real HTTP against a temporary database. */
class ApiTest {

    @TempDir
    Path dataDir;

    TestServer server;

    @BeforeEach
    void start() throws Exception {
        server = new TestServer(dataDir);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Nested
    class Health {
        @Test
        void answersWithoutASessionSoTheShellCanCheckOnIt() throws Exception {
            Reply reply = server.get("/api/health", null);

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.data().get("ok").asBoolean()).isTrue();
        }

        @Test
        void usesTheStandardEnvelope() throws Exception {
            Reply reply = server.get("/api/health", null);

            assertThat(reply.body().fieldNames()).toIterable().containsExactlyInAnyOrder("data", "error", "status");
            assertThat(reply.body().get("status").asInt()).isEqualTo(200);
            assertThat(reply.body().get("error").isNull()).isTrue();
        }
    }

    @Nested
    class Session {
        @Test
        void statusSaysLockedWithoutASession() throws Exception {
            // Open on purpose: the lock screen has to ask whether a PIN is needed.
            Reply reply = server.get("/api/session", null);

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.data().get("unlocked").asBoolean()).isFalse();
            assertThat(reply.data().get("pinSet").asBoolean()).isFalse();
        }

        @Test
        void aLockedRequestIsRefusedKindly() throws Exception {
            Reply reply = server.get("/api/settings", null);

            assertThat(reply.status()).isEqualTo(401);
            assertThat(reply.error()).contains("locked");
            assertThat(reply.data().isNull()).isTrue();
        }

        @Test
        void lockRequiresASession() throws Exception {
            assertThat(server.delete("/api/session", null).status()).isEqualTo(401);
        }

        @Test
        void unlockingFromThisPcSetsAHardenedCookie() throws Exception {
            Reply reply = server.send("POST", "/api/session", null, Map.of());

            assertThat(reply.status()).isEqualTo(201);
            assertThat(reply.data().get("unlocked").asBoolean()).isTrue();
            assertThat(reply.setCookie()).hasValueSatisfying(cookie -> assertThat(cookie)
                    .startsWith("budget_session=")
                    .contains("HttpOnly")
                    .contains("SameSite=Strict"));
        }

        @Test
        void aSessionCookieUnlocksProtectedRoutes() throws Exception {
            String cookie = server.unlock();

            assertThat(server.get("/api/settings", cookie).status()).isEqualTo(200);
        }

        @Test
        void aMadeUpCookieIsRejected() throws Exception {
            assertThat(server.get("/api/settings", "budget_session=not-a-real-token").status()).isEqualTo(401);
        }

        @Test
        void lockingEndsTheSession() throws Exception {
            String cookie = server.unlock();

            assertThat(server.delete("/api/session", cookie).status()).isEqualTo(200);
            assertThat(server.get("/api/settings", cookie).status()).isEqualTo(401);
        }

        @Test
        void anIdleSessionExpires() throws Exception {
            String cookie = server.unlock();

            server.clock.advance(TestServer.SESSION_IDLE.plusSeconds(1));

            assertThat(server.get("/api/settings", cookie).status()).isEqualTo(401);
        }

        @Test
        void activityKeepsASessionAlive() throws Exception {
            String cookie = server.unlock();

            server.clock.advance(Duration.ofMinutes(10));
            assertThat(server.get("/api/settings", cookie).status()).isEqualTo(200);
            server.clock.advance(Duration.ofMinutes(10));

            assertThat(server.get("/api/settings", cookie).status()).isEqualTo(200);
        }
    }

    @Nested
    class Settings {
        @Test
        void readingRequiresASession() throws Exception {
            assertThat(server.get("/api/settings", null).status()).isEqualTo(401);
        }

        @Test
        void changingRequiresASession() throws Exception {
            assertThat(server.put("/api/settings/theme", "{\"value\":\"dark\"}", null).status()).isEqualTo(401);
        }

        @Test
        void startsWithDefaults() throws Exception {
            Reply reply = server.get("/api/settings", server.unlock());

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.data().get("theme").asText()).isEqualTo("system");
            assertThat(reply.data().get("accent_color").asText()).isEqualTo("#0f766e");
            assertThat(reply.data().get("auto_lock_minutes").asText()).isEqualTo("15");
            assertThat(reply.data().get("start_amounts_hidden").asText()).isEqualTo("false");
            assertThat(reply.data().get("unpaid_highlight_days").asText()).isEqualTo("3");
        }

        @Test
        void theNewerSettingsTakeOnlyTheirOwnChoices() throws Exception {
            String cookie = server.unlock();

            assertThat(server.put("/api/settings/auto_lock_minutes", "{\"value\":\"5\"}", cookie).status()).isEqualTo(200);
            assertThat(server.put("/api/settings/auto_lock_minutes", "{\"value\":\"7\"}", cookie).status()).isEqualTo(400);
            assertThat(server.put("/api/settings/start_amounts_hidden", "{\"value\":\"true\"}", cookie).status()).isEqualTo(200);
            assertThat(server.put("/api/settings/start_amounts_hidden", "{\"value\":\"yes\"}", cookie).status()).isEqualTo(400);
            assertThat(server.put("/api/settings/unpaid_highlight_days", "{\"value\":\"0\"}", cookie).status()).isEqualTo(200);
            assertThat(server.put("/api/settings/unpaid_highlight_days", "{\"value\":\"4\"}", cookie).status()).isEqualTo(400);

            Reply saved = server.get("/api/settings", cookie);
            assertThat(saved.data().get("auto_lock_minutes").asText()).isEqualTo("5");
            assertThat(saved.data().get("start_amounts_hidden").asText()).isEqualTo("true");
            assertThat(saved.data().get("unpaid_highlight_days").asText()).isEqualTo("0");
        }

        @Test
        void savesAChangeAndKeepsIt() throws Exception {
            String cookie = server.unlock();

            Reply saved = server.put("/api/settings/theme", "{\"value\":\"dark\"}", cookie);
            server.put("/api/settings/theme", "{\"value\":\"light\"}", cookie);

            assertThat(saved.status()).isEqualTo(200);
            assertThat(saved.data().get("theme").asText()).isEqualTo("dark");
            assertThat(server.get("/api/settings", cookie).data().get("theme").asText()).isEqualTo("light");
        }

        @Test
        void rejectsAValueThatIsNotOneOfTheChoices() throws Exception {
            String cookie = server.unlock();

            Reply reply = server.put("/api/settings/theme", "{\"value\":\"neon\"}", cookie);

            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.error()).contains("light, dark");
            assertThat(server.get("/api/settings", cookie).data().get("theme").asText()).isEqualTo("system");
        }

        @Test
        void rejectsAnAccentColorThatIsNotHex() throws Exception {
            Reply reply = server.put("/api/settings/accent_color", "{\"value\":\"teal\"}", server.unlock());

            assertThat(reply.status()).isEqualTo(400);
        }

        @Test
        void rejectsABodyThatIsNotJson() throws Exception {
            Reply reply = server.put("/api/settings/theme", "dark", server.unlock());

            assertThat(reply.status()).isEqualTo(400);
            // The parser's internals must never reach the client.
            assertThat(reply.error()).doesNotContain("Exception").doesNotContain("jackson");
        }

        @Test
        void anUnknownSettingIsNotFound() throws Exception {
            Reply reply = server.put("/api/settings/nope", "{\"value\":\"x\"}", server.unlock());

            assertThat(reply.status()).isEqualTo(404);
        }
    }

    @Nested
    class EraseAllData {
        private static final String CONFIRMED = "{\"confirm\":\"erase-all-data\"}";

        @Test
        void requiresASession() throws Exception {
            Reply reply = server.send("DELETE", "/api/data", CONFIRMED, Map.of());

            assertThat(reply.status()).isEqualTo(401);
        }

        @Test
        void erasesNothingWithoutTheConfirmation() throws Exception {
            String cookie = server.unlock();
            server.put("/api/settings/theme", "{\"value\":\"dark\"}", cookie);

            Reply missing = server.send("DELETE", "/api/data", null, Map.of("Cookie", cookie));
            Reply wrong = server.send("DELETE", "/api/data", "{\"confirm\":\"yes\"}", Map.of("Cookie", cookie));

            assertThat(missing.status()).isEqualTo(400);
            assertThat(wrong.status()).isEqualTo(400);
            assertThat(wrong.error()).contains("Nothing was erased");
            assertThat(server.get("/api/settings", cookie).data().get("theme").asText()).isEqualTo("dark");
        }

        @Test
        void leavesAFreshAppBehind() throws Exception {
            String cookie = server.unlock();
            server.put("/api/settings/theme", "{\"value\":\"dark\"}", cookie);
            Path receipt = Files.createDirectories(dataDir.resolve("files")).resolve("receipt.pdf");
            Path backup = Files.createDirectories(dataDir.resolve("backups")).resolve("pre-migration-x.db");
            Files.writeString(receipt, "receipt");
            Files.writeString(backup, "backup");

            Reply reply = server.send("DELETE", "/api/data", CONFIRMED, Map.of("Cookie", cookie));

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.data().get("erased").asBoolean()).isTrue();
            assertThat(receipt).doesNotExist();
            assertThat(backup).doesNotExist();
            // Everyone is signed out, and after unlocking again the settings are back to defaults.
            assertThat(server.get("/api/settings", cookie).status()).isEqualTo(401);
            assertThat(server.get("/api/settings", server.unlock()).data().get("theme").asText()).isEqualTo("system");
        }

        @Test
        void theAppKeepsWorkingAfterwards() throws Exception {
            server.send("DELETE", "/api/data", CONFIRMED, Map.of("Cookie", server.unlock()));
            String cookie = server.unlock();

            Reply saved = server.put("/api/settings/theme", "{\"value\":\"light\"}", cookie);

            assertThat(saved.status()).isEqualTo(200);
            assertThat(saved.data().get("theme").asText()).isEqualTo("light");
        }
    }

    @Nested
    class Shutdown {
        @Test
        void isRefusedWithoutTheShellsToken() throws Exception {
            Reply reply = server.send("POST", "/api/shutdown", null, Map.of());

            assertThat(reply.status()).isEqualTo(401);
            assertThat(server.shutdownRequested).isFalse();
        }

        @Test
        void isRefusedWithTheWrongTokenEvenWhenUnlocked() throws Exception {
            Reply reply = server.send("POST", "/api/shutdown", null, Map.of(
                    "Cookie", server.unlock(), ShutdownRoutes.TOKEN_HEADER, "guess"));

            assertThat(reply.status()).isEqualTo(401);
            assertThat(server.shutdownRequested).isFalse();
        }

        @Test
        void stopsTheServerWhenTheShellAsks() throws Exception {
            Reply reply = server.send("POST", "/api/shutdown", null, Map.of(
                    ShutdownRoutes.TOKEN_HEADER, TestServer.SHUTDOWN_TOKEN));

            assertThat(reply.status()).isEqualTo(202);
            // The callback runs on its own thread just after the response is written.
            for (int i = 0; i < 100 && !server.shutdownRequested.get(); i++) {
                Thread.sleep(20);
            }
            assertThat(server.shutdownRequested).isTrue();
        }
    }

    @Nested
    class UnknownRoutes {
        @Test
        void areHiddenBehindTheSessionGate() throws Exception {
            assertThat(server.get("/api/does-not-exist", null).status()).isEqualTo(401);
        }

        @Test
        void getTheEnvelopeWhenUnlocked() throws Exception {
            Reply reply = server.get("/api/does-not-exist", server.unlock());

            assertThat(reply.status()).isEqualTo(404);
            assertThat(reply.body().get("status").asInt()).isEqualTo(404);
            assertThat(reply.error()).isNotBlank();
        }
    }

    @Nested
    class Headers {
        @Test
        void everyResponseCarriesAStrictContentSecurityPolicy() throws Exception {
            var response = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(server.uri("/api/health")).build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());

            assertThat(response.headers().firstValue("Content-Security-Policy"))
                    .hasValueSatisfying(csp -> assertThat(csp).contains("default-src 'self'").doesNotContain("http"));
            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
            assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        }
    }
}
