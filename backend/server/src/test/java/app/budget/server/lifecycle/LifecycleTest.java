package app.budget.server.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
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
        // Windows reserves blocks of ports (Hyper-V and others), and a port the OS hands out can sit right below one:
        // then none of the next ones can be bound and the finder rightly gives up (CI, 2026-10-21: "No free port found
        // starting at 49686"). So the busy port is one whose next port was just seen free.
        for (int tries = 0; tries < 20; tries++) {
            try (ServerSocket busy = new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))) {
                int next = busy.getLocalPort() + 1;
                if (!canBind(next)) {
                    continue;
                }
                int port = PortFinder.firstFree(LOOPBACK, busy.getLocalPort());

                assertThat(port).isGreaterThan(busy.getLocalPort());
                return;
            }
        }
        throw new AssertionError("No busy port with a free one after it was found to test with");
    }

    private static boolean canBind(int port) {
        try (ServerSocket probe = new ServerSocket(port, 1, InetAddress.getByName(LOOPBACK))) {
            return true;
        } catch (IOException e) {
            return false;
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
