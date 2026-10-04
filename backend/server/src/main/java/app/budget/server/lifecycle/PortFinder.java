package app.budget.server.lifecycle;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

/** Finds the port to listen on: the configured one, or the next free one after it. */
public final class PortFinder {

    static final int MAX_ATTEMPTS = 50;

    private PortFinder() {}

    /**
     * @param preferred the port to try first; {@code 0} asks the OS for any free port
     * @throws IOException when nothing in the range is free, or the host cannot be bound at all
     */
    public static int firstFree(String host, int preferred) throws IOException {
        InetAddress address = InetAddress.getByName(host);
        IOException lastFailure = null;
        for (int offset = 0; offset < MAX_ATTEMPTS && preferred + offset <= 65_535; offset++) {
            try (ServerSocket probe = new ServerSocket(preferred + offset, 1, address)) {
                return probe.getLocalPort();
            } catch (IOException e) {
                lastFailure = e;
            }
        }
        throw new IOException("No free port found starting at " + preferred, lastFailure);
    }
}
