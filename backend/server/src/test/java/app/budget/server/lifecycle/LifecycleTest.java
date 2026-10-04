package app.budget.server.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LifecycleTest {

    private static final String LOOPBACK = "127.0.0.1";

    @TempDir
    Path dataDir;

    @Test
    void onlyOneBackendMayHoldADataFolder() throws Exception {
        try (InstanceLock first = InstanceLock.acquire(dataDir)) {
            assertThatThrownBy(() -> InstanceLock.acquire(dataDir))
                    .isInstanceOf(InstanceLock.AlreadyRunningException.class);
        }
    }

    @Test
    void theDataFolderIsFreeAgainOnceTheBackendStops() throws Exception {
        InstanceLock.acquire(dataDir).close();

        try (InstanceLock second = InstanceLock.acquire(dataDir)) {
            assertThat(second).isNotNull();
        }
    }

    @Test
    void usesThePreferredPortWhenItIsFree() throws Exception {
        int free;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))) {
            free = probe.getLocalPort();
        }

        assertThat(PortFinder.firstFree(LOOPBACK, free)).isEqualTo(free);
    }

    @Test
    void movesToTheNextPortWhenThePreferredOneIsBusy() throws Exception {
        try (ServerSocket busy = new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))) {
            int port = PortFinder.firstFree(LOOPBACK, busy.getLocalPort());

            assertThat(port).isGreaterThan(busy.getLocalPort());
        }
    }

    @Test
    void stopsWhenTheShellExits() throws Exception {
        // A short-lived child process stands in for the desktop shell.
        Process shell = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version")
                .redirectErrorStream(true)
                .start();
        CountDownLatch stopped = new CountDownLatch(1);

        ParentWatch.watch(shell.pid(), stopped::countDown);
        shell.getInputStream().readAllBytes();

        assertThat(stopped.await(30, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void stopsRightAwayWhenTheShellIsAlreadyGone() throws Exception {
        Process shell = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version")
                .redirectErrorStream(true)
                .start();
        shell.getInputStream().readAllBytes();
        shell.waitFor();
        CountDownLatch stopped = new CountDownLatch(1);

        ParentWatch.watch(shell.pid(), stopped::countDown);

        assertThat(stopped.await(30, TimeUnit.SECONDS)).isTrue();
    }
}
