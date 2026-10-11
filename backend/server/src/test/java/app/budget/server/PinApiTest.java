package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.budget.server.TestServer.Reply;
import app.budget.server.auth.PinReset;
import app.budget.server.lifecycle.InstanceLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The app lock over HTTP: setting, changing and removing the PIN, unlocking, the wait, and recovery. */
class PinApiTest extends ApiTestBase {

    private static final String PIN = "48291537";
    private static final String OTHER_PIN = "1357";
    private static final String CODE_SHAPE = "[A-HJ-NP-Z2-9]{4}(-[A-HJ-NP-Z2-9]{4}){3}";
    private static final String CONFIRMED_ERASE = "{\"confirm\":\"erase-all-data\"}";

    /** Sets {@link #PIN} from the unlocked session and returns the recovery code. */
    private String setPin() throws Exception {
        Reply reply = server.put("/api/pin", newPin(PIN), cookie);
        assertThat(reply.status()).isEqualTo(200);
        return reply.data().get("recoveryCode").asText();
    }

    private static String newPin(String pin) {
        return "{\"newPin\":\"%s\"}".formatted(pin);
    }

    private static String change(String currentPin, String newPin) {
        return "{\"currentPin\":\"%s\",\"newPin\":\"%s\"}".formatted(currentPin, newPin);
    }

    private static String current(String pin) {
        return "{\"currentPin\":\"%s\"}".formatted(pin);
    }

    private Reply unlockWith(String pin) throws Exception {
        return server.send("POST", "/api/session", pin == null ? null : "{\"pin\":\"%s\"}".formatted(pin), Map.of());
    }

    private Reply recover(String code, String pin) throws Exception {
        return server.send("POST", "/api/pin/recovery",
                "{\"recoveryCode\":\"%s\",\"newPin\":\"%s\"}".formatted(code, pin), Map.of());
    }

    private Reply deleteWithBody(String path, String body) throws Exception {
        return server.send("DELETE", path, body, Map.of("Cookie", cookie));
    }

    private static String cookieOf(Reply reply) {
        String setCookie = reply.setCookie().orElseThrow();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    private boolean isUnlocked(String sessionCookie) throws Exception {
        return server.get("/api/settings", sessionCookie).status() == 200;
    }

    @Nested
    class Auth {
        @Test
        void everyPinRouteButRecoveryNeedsASession() throws Exception {
            assertThat(server.put("/api/pin", newPin(PIN), null).status()).isEqualTo(401);
            assertThat(server.send("DELETE", "/api/pin", current(PIN), Map.of()).status()).isEqualTo(401);
            assertThat(server.delete("/api/pin/offer", null).status()).isEqualTo(401);
            assertThat(server.post("/api/pin/recovery-code", current(PIN), null).status()).isEqualTo(401);
            assertThat(server.get("/api/session", cookie).data().get("pinSet").asBoolean()).isFalse();
        }
    }

    @Nested
    class SettingAPin {
        @Test
        void aFreshAppHasNoPinAndOffersOneOnce() throws Exception {
            Reply fresh = server.get("/api/session", cookie);

            assertThat(fresh.data().get("pinSet").asBoolean()).isFalse();
            assertThat(fresh.data().get("offerPin").asBoolean()).isTrue();

            assertThat(server.delete("/api/pin/offer", cookie).status()).isEqualTo(200);

            assertThat(server.get("/api/session", cookie).data().get("offerPin").asBoolean()).isFalse();
        }

        @Test
        void theOfferIsNeverMadeToSomeoneWhoIsLockedOut() throws Exception {
            assertThat(server.get("/api/session", null).data().get("offerPin").asBoolean()).isFalse();
        }

        @Test
        void settingOneGivesARecoveryCodeOnce() throws Exception {
            Reply reply = server.put("/api/pin", newPin(PIN), cookie);

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.data().get("recoveryCode").asText()).matches(CODE_SHAPE);
            assertThat(reply.data().get("session").get("pinSet").asBoolean()).isTrue();
            assertThat(reply.data().get("session").get("offerPin").asBoolean()).isFalse();
            // Not user data: there is nothing for Undo to take back.
            assertThat(reply.changed()).isFalse();
        }

        @Test
        void aPinIsFourToEightDigits() throws Exception {
            for (String bad : new String[] {"123", "123456789", "12a4", "", "    "}) {
                Reply reply = server.put("/api/pin", newPin(bad), cookie);

                assertThat(reply.status()).as("PIN '%s'", bad).isEqualTo(400);
                assertThat(reply.error()).contains("4 to 8 digits");
            }
            assertThat(server.put("/api/pin", "{}", cookie).status()).isEqualTo(400);
            assertThat(server.put("/api/pin", "not json", cookie).status()).isEqualTo(400);
            assertThat(server.get("/api/session", cookie).data().get("pinSet").asBoolean()).isFalse();
        }

        @Test
        void aPinWithLeadingZerosIsKeptAsTyped() throws Exception {
            server.put("/api/pin", newPin("0042"), cookie);

            assertThat(unlockWith("42").status()).isEqualTo(401);
            assertThat(unlockWith("0042").status()).isEqualTo(201);
        }

        @Test
        void neitherSecretIsWrittenToDiskAsTyped() throws Exception {
            String code = setPin();

            try (Stream<Path> files = Files.list(dataDir)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String raw = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                    assertThat(raw).as(file.getFileName().toString())
                            .doesNotContain(PIN)
                            .doesNotContain(code)
                            .doesNotContain(code.replace("-", ""));
                }
            }
        }

        @Test
        void settingOneEndsEveryOtherSessionButKeepsThisOne() throws Exception {
            String other = server.unlock();

            setPin();

            assertThat(isUnlocked(cookie)).isTrue();
            assertThat(isUnlocked(other)).isFalse();
        }

        @Test
        void undoNeverTakesAPinBack() throws Exception {
            setPin();

            server.post("/api/undo", null, cookie);

            assertThat(server.get("/api/session", cookie).data().get("pinSet").asBoolean()).isTrue();
        }

        @Test
        void thePinIsStillThereAfterARestart() throws Exception {
            setPin();
            server.close();
            server = new TestServer(dataDir);

            assertThat(server.get("/api/session", null).data().get("pinSet").asBoolean()).isTrue();
            assertThat(unlockWith(PIN).status()).isEqualTo(201);
        }
    }

    @Nested
    class Unlocking {
        @Test
        void withAPinSetOnlyThePinUnlocks() throws Exception {
            setPin();

            Reply without = unlockWith(null);
            Reply wrong = unlockWith(OTHER_PIN);
            Reply right = unlockWith(PIN);

            assertThat(without.status()).isEqualTo(401);
            assertThat(without.setCookie()).isEmpty();
            assertThat(wrong.status()).isEqualTo(401);
            assertThat(wrong.error()).contains("didn't match");
            assertThat(wrong.setCookie()).isEmpty();
            assertThat(right.status()).isEqualTo(201);
            assertThat(isUnlocked(cookieOf(right))).isTrue();
        }

        @Test
        void askingWithoutAPinIsNotCountedAsAGuess() throws Exception {
            setPin();

            for (int i = 0; i < 6; i++) {
                assertThat(unlockWith(null).status()).isEqualTo(401);
            }

            assertThat(unlockWith(PIN).status()).isEqualTo(201);
        }

        @Test
        void theThirdWrongPinStartsAWaitThatEvenTheRightPinMustSitOut() throws Exception {
            setPin();

            assertThat(unlockWith("0000").status()).isEqualTo(401);
            assertThat(unlockWith("0001").status()).isEqualTo(401);
            assertThat(server.get("/api/session", null).data().get("retryAfterSeconds").asLong()).isZero();
            assertThat(unlockWith("0002").status()).isEqualTo(401);

            Reply waiting = unlockWith(PIN);
            assertThat(waiting.status()).isEqualTo(429);
            assertThat(waiting.error()).contains("30 seconds");
            assertThat(server.get("/api/session", null).data().get("retryAfterSeconds").asLong()).isEqualTo(30);

            server.clock.advance(Duration.ofSeconds(30));

            assertThat(unlockWith(PIN).status()).isEqualTo(201);
            assertThat(server.get("/api/session", null).data().get("retryAfterSeconds").asLong()).isZero();
        }

        @Test
        void theWaitGrowsWithEachFurtherWrongPinAndStopsAtAnHour() throws Exception {
            setPin();
            long[] waits = {30, 60, 300, 900, 3600, 3600};

            unlockWith("0000");
            unlockWith("0001");
            for (long wait : waits) {
                assertThat(unlockWith("0002").status()).isEqualTo(401);
                assertThat(server.get("/api/session", null).data().get("retryAfterSeconds").asLong()).isEqualTo(wait);
                server.clock.advance(Duration.ofSeconds(wait));
            }
        }

        @Test
        void theRightPinStartsTheCountAgain() throws Exception {
            setPin();

            unlockWith("0000");
            unlockWith("0001");
            assertThat(unlockWith(PIN).status()).isEqualTo(201);
            unlockWith("0000");
            unlockWith("0001");

            assertThat(unlockWith(PIN).status()).isEqualTo(201);
        }

        @Test
        void theWaitOutlastsARestart() throws Exception {
            setPin();
            unlockWith("0000");
            unlockWith("0001");
            unlockWith("0002");
            server.close();
            server = new TestServer(dataDir);

            // A fresh server's clock starts where the first one's did, so the wait is still ahead.
            assertThat(unlockWith(PIN).status()).isEqualTo(429);
        }

        @Test
        void autoLockFollowsTheSetting() throws Exception {
            server.put("/api/settings/auto_lock_minutes", "{\"value\":\"1\"}", cookie);

            server.clock.advance(Duration.ofSeconds(61));

            assertThat(isUnlocked(cookie)).isFalse();
        }
    }

    @Nested
    class ChangingAndRemoving {
        @Test
        void changingNeedsThePinInUse() throws Exception {
            setPin();

            Reply missing = server.put("/api/pin", newPin(OTHER_PIN), cookie);
            Reply wrong = server.put("/api/pin", change("9999", OTHER_PIN), cookie);

            // 403, not 401: the app is still unlocked, and 401 would send the screen to the lock.
            assertThat(missing.status()).isEqualTo(403);
            assertThat(wrong.status()).isEqualTo(403);
            assertThat(isUnlocked(cookie)).isTrue();
            assertThat(unlockWith(PIN).status()).isEqualTo(201);
        }

        @Test
        void changingKeepsTheRecoveryCodeAlreadyWrittenDown() throws Exception {
            String code = setPin();

            Reply changed = server.put("/api/pin", change(PIN, OTHER_PIN), cookie);

            assertThat(changed.status()).isEqualTo(200);
            assertThat(changed.data().get("recoveryCode").isNull()).isTrue();
            assertThat(unlockWith(PIN).status()).isEqualTo(401);
            assertThat(unlockWith(OTHER_PIN).status()).isEqualTo(201);
            assertThat(recover(code, PIN).status()).isEqualTo(201);
        }

        @Test
        void removingNeedsThePinAndThenTheAppOpensWithoutOne() throws Exception {
            setPin();

            assertThat(deleteWithBody("/api/pin", current("9999")).status()).isEqualTo(403);
            assertThat(deleteWithBody("/api/pin", null).status()).isEqualTo(400);
            assertThat(server.get("/api/session", cookie).data().get("pinSet").asBoolean()).isTrue();

            Reply removed = deleteWithBody("/api/pin", current(PIN));

            assertThat(removed.status()).isEqualTo(200);
            assertThat(removed.data().get("pinSet").asBoolean()).isFalse();
            // Someone who removed their PIN has decided: the offer is not made again.
            assertThat(removed.data().get("offerPin").asBoolean()).isFalse();
            assertThat(unlockWith(null).status()).isEqualTo(201);
        }

        @Test
        void wrongPinsInsideASessionCountTowardsTheWaitToo() throws Exception {
            setPin();

            for (int i = 0; i < 3; i++) {
                assertThat(server.put("/api/pin", change("9999", OTHER_PIN), cookie).status()).isEqualTo(403);
            }

            assertThat(server.put("/api/pin", change(PIN, OTHER_PIN), cookie).status()).isEqualTo(429);
        }

        @Test
        void erasingAllDataRemovesThePin() throws Exception {
            setPin();

            deleteWithBody("/api/data", CONFIRMED_ERASE);

            Reply fresh = unlockWith(null);
            assertThat(fresh.status()).isEqualTo(201);
            assertThat(fresh.data().get("pinSet").asBoolean()).isFalse();
            assertThat(fresh.data().get("offerPin").asBoolean()).isTrue();
        }
    }

    @Nested
    class Recovery {
        @Test
        void theCodeSetsANewPinUnlocksAndIsReplaced() throws Exception {
            String code = setPin();
            String other = unlockWithPin();

            // Typed the way people type: lower case, without the dashes.
            Reply reply = recover(code.replace("-", "").toLowerCase(), OTHER_PIN);

            assertThat(reply.status()).isEqualTo(201);
            String newCode = reply.data().get("recoveryCode").asText();
            assertThat(newCode).matches(CODE_SHAPE).isNotEqualTo(code);
            assertThat(isUnlocked(cookieOf(reply))).isTrue();
            // Whoever was unlocked with the forgotten PIN is locked again.
            assertThat(isUnlocked(other)).isFalse();
            assertThat(unlockWith(PIN).status()).isEqualTo(401);
            assertThat(unlockWith(OTHER_PIN).status()).isEqualTo(201);
            // A code works once.
            assertThat(recover(code, PIN).status()).isEqualTo(401);
            assertThat(recover(newCode, PIN).status()).isEqualTo(201);
        }

        private String unlockWithPin() throws Exception {
            return cookieOf(unlockWith(PIN));
        }

        @Test
        void aWrongCodeIsRefusedAndCounted() throws Exception {
            setPin();

            for (int i = 0; i < 3; i++) {
                Reply wrong = recover("AAAA-AAAA-AAAA-AAAA", OTHER_PIN);
                assertThat(wrong.status()).isEqualTo(401);
                assertThat(wrong.error()).contains("recovery code");
                assertThat(wrong.setCookie()).isEmpty();
            }

            // The PIN and the code share one count, so guessing at one cannot dodge the other's wait.
            assertThat(unlockWith(PIN).status()).isEqualTo(429);
        }

        @Test
        void theNewPinIsCheckedBeforeTheCodeIsUsed() throws Exception {
            String code = setPin();

            assertThat(recover(code, "12").status()).isEqualTo(400);

            assertThat(recover(code, OTHER_PIN).status()).isEqualTo(201);
        }

        @Test
        void thereIsNothingToResetWithoutAPin() throws Exception {
            assertThat(recover("AAAA-AAAA-AAAA-AAAA", OTHER_PIN).status()).isEqualTo(409);
        }

        @Test
        void aNewCodeNeedsThePinAndRetiresTheOldOne() throws Exception {
            String code = setPin();

            assertThat(server.post("/api/pin/recovery-code", current("9999"), cookie).status()).isEqualTo(403);
            Reply reply = server.post("/api/pin/recovery-code", current(PIN), cookie);

            assertThat(reply.status()).isEqualTo(200);
            String newCode = reply.data().get("recoveryCode").asText();
            assertThat(newCode).matches(CODE_SHAPE).isNotEqualTo(code);
            assertThat(recover(code, OTHER_PIN).status()).isEqualTo(401);
            assertThat(recover(newCode, OTHER_PIN).status()).isEqualTo(201);
        }

        @Test
        void aCodeGoesWithAPin() throws Exception {
            assertThat(server.post("/api/pin/recovery-code", current(PIN), cookie).status()).isEqualTo(409);
        }
    }

    @Nested
    class ResetFromTheCommandLine {
        @Test
        void removesThePinAndNothingElse() throws Exception {
            setPin();
            long accountId = createAccount("Everyday checking", "checking");
            server.close();

            assertThat(PinReset.run(dataDir)).isEqualTo(PinReset.DONE);

            server = new TestServer(dataDir);
            String session = cookieOf(unlockWith(null));
            assertThat(server.get("/api/session", session).data().get("pinSet").asBoolean()).isFalse();
            assertThat(server.get("/api/accounts/" + accountId, session).status()).isEqualTo(200);
        }

        @Test
        void saysSoWhenThereIsNoPin() throws Exception {
            server.close();

            assertThat(PinReset.run(dataDir)).isEqualTo(PinReset.NOTHING_TO_DO);
        }

        @Test
        void isRefusedWhileTheAppIsOpen() throws Exception {
            setPin();

            try (InstanceLock running = InstanceLock.acquire(dataDir)) {
                assertThatThrownBy(() -> PinReset.run(dataDir)).isInstanceOf(InstanceLock.AlreadyRunningException.class);
            }

            assertThat(server.get("/api/session", cookie).data().get("pinSet").asBoolean()).isTrue();
        }
    }
}
