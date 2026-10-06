package app.budget.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class HostGuardTest {

    private final HostGuard guard = new HostGuard(Set.of("desk.tailnet.ts.net"));

    @Test
    void allowsIpAddressesAndLocalhost() {
        assertThat(guard.isAllowed("127.0.0.1:4873")).isTrue();
        assertThat(guard.isAllowed("127.0.0.1")).isTrue();
        assertThat(guard.isAllowed("192.168.1.20:4873")).isTrue();
        assertThat(guard.isAllowed("[::1]:4873")).isTrue();
        assertThat(guard.isAllowed("LOCALHOST:4873")).isTrue();
    }

    @Test
    void allowsNamesListedInConfig() {
        assertThat(guard.isAllowed("Desk.tailnet.ts.net:4873")).isTrue();
    }

    @Test
    void rejectsOtherDomainNamesWhichIsHowDnsRebindingArrives() {
        assertThat(guard.isAllowed("evil.example:4873")).isFalse();
        assertThat(guard.isAllowed("127.0.0.1.evil.example")).isFalse();
        assertThat(guard.isAllowed("localhost.evil.example")).isFalse();
    }

    @Test
    void rejectsAMissingHost() {
        assertThat(guard.isAllowed(null)).isFalse();
        assertThat(guard.isAllowed(" ")).isFalse();
    }
}
