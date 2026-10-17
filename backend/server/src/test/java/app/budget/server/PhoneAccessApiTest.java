package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.budget.server.TestServer.Reply;
import app.budget.server.auth.PinReset;
import app.budget.server.phone.Networks;
import app.budget.server.phone.Networks.Network;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phone access (spec §10, Phase 12), over real HTTP. Requests "from a phone" go to this PC's own
 * network address, so they arrive from the network and not from loopback, exactly as a phone's would.
 * Those tests need the machine to be on a network; they are skipped on one that is not.
 */
class PhoneAccessApiTest {

    private static final String PIN = "2468";

    @TempDir
    Path dataDir;

    /** The networks the server is told this PC is on; a test can change them as it goes. */
    private final AtomicReference<List<Network>> networks = new AtomicReference<>(List.of());

    private TestServer server;
    private String cookie;

    @BeforeEach
    void start() throws Exception {
        server = new TestServer(dataDir, () -> networks.get());
        cookie = server.unlock();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    /** One of this machine's real addresses, so phone access can really listen on it. */
    private static String realAddress() {
        List<Network> real = Networks.ofThisPc().current();
        assumeTrue(!real.isEmpty(), "This machine isn't on a network, so there is nothing to listen on");
        return real.get(0).address();
    }

    private void setPin() throws Exception {
        assertThat(server.put("/api/pin", "{\"newPin\":\"" + PIN + "\"}", cookie).status()).isEqualTo(200);
    }

    private Reply turnOn(String address) throws Exception {
        return server.put("/api/phone-access", "{\"address\":\"" + address + "\"}", cookie);
    }

    /** Sets a PIN and turns phone access on at a real address; returns the address to open on the phone. */
    private String phoneAccessOn() throws Exception {
        String address = realAddress();
        networks.set(List.of(new Network(address, "Wi-Fi", false)));
        setPin();
        Reply on = turnOn(address);
        assertThat(on.status()).isEqualTo(200);
        assertThat(on.data().get("status").get("listening").asBoolean()).isTrue();
        return on.data().get("status").get("url").asText();
    }

    /** Unlocks from the phone with the PIN and returns its session cookie. */
    private String unlockFromPhone(String url) throws Exception {
        Reply unlocked = server.sendTo(url, "POST", "/api/session", "{\"pin\":\"" + PIN + "\"}", null);
        assertThat(unlocked.status()).isEqualTo(201);
        String setCookie = unlocked.setCookie().orElseThrow();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    @Test
    void needsASession() throws Exception {
        assertThat(server.get("/api/phone-access", null).status()).isEqualTo(401);
        assertThat(server.put("/api/phone-access", "{\"address\":\"192.168.1.20\"}", null).status()).isEqualTo(401);
    }

    @Test
    void isOffUntilTurnedOnAndListsThisPcsNetworks() throws Exception {
        networks.set(List.of(new Network("100.101.102.103", "Tailscale", true), new Network("192.168.1.20", "Wi-Fi", false)));

        Reply reply = server.get("/api/phone-access", cookie);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.data().get("status").get("on").asBoolean()).isFalse();
        assertThat(reply.data().get("fromThisPc").asBoolean()).isTrue();
        assertThat(reply.data().get("networks").findValuesAsText("name")).containsExactly("Tailscale", "Wi-Fi");
        assertThat(reply.data().get("networks").get(0).get("tunnel").asBoolean()).isTrue();
        assertThat(reply.data().get("networks").get(1).get("tunnel").asBoolean()).isFalse();
    }

    @Test
    void cantBeTurnedOnWithoutAPin() throws Exception {
        networks.set(List.of(new Network("192.168.1.20", "Wi-Fi", false)));

        Reply reply = turnOn("192.168.1.20");

        assertThat(reply.status()).isEqualTo(409);
        assertThat(reply.error()).contains("set a PIN first");
        assertThat(server.get("/api/phone-access", cookie).data().get("status").get("on").asBoolean()).isFalse();
    }

    @Test
    void onlyListensOnAnAddressThisPcHas() throws Exception {
        networks.set(List.of(new Network("192.168.1.20", "Wi-Fi", false)));
        setPin();

        assertThat(turnOn("0.0.0.0").status()).isEqualTo(400);
        assertThat(turnOn("10.0.0.9").status()).isEqualTo(400);
        assertThat(server.put("/api/phone-access", "not json", cookie).status()).isEqualTo(400);
        assertThat(server.get("/api/phone-access", cookie).data().get("status").get("on").asBoolean()).isFalse();
    }

    @Test
    void isNotASettingThatCanBeSetDirectly() throws Exception {
        assertThat(server.put("/api/settings/phone_access_address", "{\"value\":\"192.168.1.20\"}", cookie).status())
                .isEqualTo(400);
    }

    @Test
    void everyAnswerAsksNotToBeKept() throws Exception {
        assertThat(server.get("/api/health", null).headers().firstValue("Cache-Control")).contains("no-store");
        // A screen's address is answered with the app's page (or a 404 when the app isn't built), never kept either.
        assertThat(server.download("/no-such-screen", null, null).header("Cache-Control")).isEqualTo("no-store");
    }

    @Nested
    class FromAPhone {

        @Test
        void opensWithThePinAndWorksLikeThisPc() throws Exception {
            String url = phoneAccessOn();
            assertThat(server.sendTo(url, "GET", "/api/health", null, null).status()).isEqualTo(200);
            assertThat(server.sendTo(url, "GET", "/api/accounts", null, null).status()).isEqualTo(401);

            String phone = unlockFromPhone(url);

            assertThat(server.sendTo(url, "GET", "/api/accounts", null, phone).status()).isEqualTo(200);
            assertThat(server.sendTo(url, "GET", "/api/health", null, phone).headers().firstValue("Cache-Control"))
                    .contains("no-store");
        }

        @Test
        void aWrongPinIsRefused() throws Exception {
            String url = phoneAccessOn();

            Reply reply = server.sendTo(url, "POST", "/api/session", "{\"pin\":\"0000\"}", null);

            assertThat(reply.status()).isEqualTo(401);
            assertThat(reply.setCookie()).isEmpty();
        }

        @Test
        void aSessionOnlyWorksWhereItWasUnlocked() throws Exception {
            String url = phoneAccessOn();
            String phone = unlockFromPhone(url);

            // The phone's session doesn't open the app on this PC, nor this PC's over the network.
            assertThat(server.get("/api/accounts", phone).status()).isEqualTo(401);
            assertThat(server.sendTo(url, "GET", "/api/accounts", null, cookie).status()).isEqualTo(401);
            // Neither was spoiled for its owner by being tried in the wrong place.
            assertThat(server.get("/api/accounts", cookie).status()).isEqualTo(200);
            assertThat(server.sendTo(url, "GET", "/api/accounts", null, phone).status()).isEqualTo(200);
        }

        @Test
        void cantChangePhoneAccessOrSeeThisPcsNetworks() throws Exception {
            String url = phoneAccessOn();
            String phone = unlockFromPhone(url);

            Reply seen = server.sendTo(url, "GET", "/api/phone-access", null, phone);
            assertThat(seen.data().get("fromThisPc").asBoolean()).isFalse();
            assertThat(seen.data().get("networks")).isEmpty();
            assertThat(seen.data().get("status").get("on").asBoolean()).isTrue();

            assertThat(server.sendTo(url, "PUT", "/api/phone-access", "{\"address\":null}", phone).status()).isEqualTo(403);
            assertThat(server.get("/api/phone-access", cookie).data().get("status").get("listening").asBoolean()).isTrue();
        }

        @Test
        void knowsItIsntThisPc() throws Exception {
            String url = phoneAccessOn();
            String phone = unlockFromPhone(url);

            assertThat(server.sendTo(url, "GET", "/api/session", null, phone).data().get("fromThisPc").asBoolean()).isFalse();
            assertThat(server.get("/api/session", cookie).data().get("fromThisPc").asBoolean()).isTrue();
        }

        @Test
        void cantChangeWhetherThisPcStartsBudgetWithWindows() throws Exception {
            String url = phoneAccessOn();
            String phone = unlockFromPhone(url);

            assertThat(server.sendTo(url, "PUT", "/api/settings/start_with_windows", "{\"value\":\"true\"}", phone).status())
                    .isEqualTo(403);
            // Other settings are the phone's to change like this PC's.
            assertThat(server.sendTo(url, "PUT", "/api/settings/theme", "{\"value\":\"dark\"}", phone).status()).isEqualTo(200);
            Reply here = server.put("/api/settings/start_with_windows", "{\"value\":\"true\"}", cookie);
            assertThat(here.status()).isEqualTo(200);
            assertThat(here.data().get("start_with_windows").asText()).isEqualTo("true");
        }

        @Test
        void thePinCantBeRemovedWhileItIsOn() throws Exception {
            phoneAccessOn();

            Reply reply = server.send("DELETE", "/api/pin", "{\"currentPin\":\"" + PIN + "\"}", java.util.Map.of("Cookie", cookie));

            assertThat(reply.status()).isEqualTo(409);
            assertThat(reply.error()).contains("turn phone access off first");
        }

        @Test
        void turningItOffClosesTheDoorAndSignsThePhoneOut() throws Exception {
            String url = phoneAccessOn();
            String phone = unlockFromPhone(url);

            Reply off = server.put("/api/phone-access", "{\"address\":null}", cookie);

            assertThat(off.status()).isEqualTo(200);
            assertThat(off.data().get("status").get("on").asBoolean()).isFalse();
            assertThatThrownBy(() -> server.sendTo(url, "GET", "/api/health", null, null)).isInstanceOf(IOException.class);
            // This PC stays unlocked.
            assertThat(server.get("/api/accounts", cookie).status()).isEqualTo(200);

            // Turned on again, the phone has to unlock afresh.
            String address = url.replaceAll("^http://|:\\d+/$", "");
            assertThat(turnOn(address).status()).isEqualTo(200);
            String again = server.get("/api/phone-access", cookie).data().get("status").get("url").asText();
            assertThat(server.sendTo(again, "GET", "/api/accounts", null, phone).status()).isEqualTo(401);
        }

        @Test
        void followsTheNetworkAsItGoesAndComesBack() throws Exception {
            String url = phoneAccessOn();
            List<Network> connected = networks.get();

            networks.set(List.of());
            server.keepPhoneAccessUp();

            Reply away = server.get("/api/phone-access", cookie);
            assertThat(away.data().get("status").get("on").asBoolean()).isTrue();
            assertThat(away.data().get("status").get("listening").asBoolean()).isFalse();
            assertThat(away.data().get("status").get("problem").asText()).isEqualTo("network_not_connected");
            assertThatThrownBy(() -> server.sendTo(url, "GET", "/api/health", null, null)).isInstanceOf(IOException.class);

            networks.set(connected);
            server.keepPhoneAccessUp();

            Reply back = server.get("/api/phone-access", cookie);
            assertThat(back.data().get("status").get("listening").asBoolean()).isTrue();
            String newUrl = back.data().get("status").get("url").asText();
            assertThat(server.sendTo(newUrl, "GET", "/api/health", null, null).status()).isEqualTo(200);
        }

        @Test
        void erasingAllDataTurnsItOff() throws Exception {
            String url = phoneAccessOn();

            assertThat(server.send("DELETE", "/api/data", "{\"confirm\":\"erase-all-data\"}", java.util.Map.of("Cookie", cookie))
                    .status()).isEqualTo(200);

            assertThatThrownBy(() -> server.sendTo(url, "GET", "/api/health", null, null)).isInstanceOf(IOException.class);
        }
    }

    @Nested
    class ResettingTheForgottenPin {

        @Test
        void turnsPhoneAccessOffToo() throws Exception {
            String address = realAddress();
            networks.set(List.of(new Network(address, "Wi-Fi", false)));
            setPin();
            assertThat(turnOn(address).status()).isEqualTo(200);
            server.close();

            assertThat(PinReset.run(dataDir)).isEqualTo(PinReset.DONE + " " + PinReset.PHONE_ACCESS_OFF);

            server = new TestServer(dataDir, () -> networks.get());
            cookie = server.unlock();
            assertThat(server.get("/api/phone-access", cookie).data().get("status").get("on").asBoolean()).isFalse();
        }
    }
}
