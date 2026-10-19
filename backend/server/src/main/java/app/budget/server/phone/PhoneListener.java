package app.budget.server.phone;

import java.util.Optional;
import java.util.function.Supplier;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

/**
 * The second place the server listens while phone access is on: one network address of this PC,
 * never every address at once ({@code 0.0.0.0}). The desktop window's own listener on 127.0.0.1
 * is left alone; this one is opened and closed beside it while the app runs.
 *
 * <p>The listener shares the first one's HTTP settings, so a request that arrives here goes through
 * exactly the same checks: the host guard, the session gate, the security headers.
 */
public final class PhoneListener {

    private final Supplier<Server> server;
    private ServerConnector connector;

    /** @param server the running Jetty server; asked for only once it has started */
    public PhoneListener(Supplier<Server> server) {
        this.server = server;
    }

    /**
     * Starts listening on {@code address}:{@code port}, closing any earlier listener first.
     *
     * @return the port actually bound ({@code port} itself, unless it was 0)
     * @throws Exception when that address and port can't be listened on (taken, or gone)
     */
    public synchronized int open(String address, int port) throws Exception {
        close();
        Server jetty = server.get();
        ServerConnector opened = new ServerConnector(jetty, new HttpConnectionFactory(httpSettingsOf(jetty)));
        opened.setHost(address);
        opened.setPort(port);
        jetty.addConnector(opened);
        try {
            opened.start();
        } catch (Exception e) {
            jetty.removeConnector(opened);
            throw e;
        }
        connector = opened;
        return opened.getLocalPort();
    }

    /** Stops listening on the network; the desktop window is unaffected. Safe to call when closed. */
    public synchronized void close() {
        if (connector == null) {
            return;
        }
        try {
            connector.stop();
        } catch (Exception e) {
            // Stopping can only fail because it is already stopping; it is removed either way.
        }
        server.get().removeConnector(connector);
        connector = null;
    }

    /** The address being listened on, when phone access is open. */
    public synchronized Optional<String> address() {
        return Optional.ofNullable(connector).map(ServerConnector::getHost);
    }

    public synchronized int port() {
        return connector == null ? -1 : connector.getLocalPort();
    }

    /** The first listener's HTTP settings (request size limits and the like), so both behave the same. */
    private static HttpConfiguration httpSettingsOf(Server jetty) {
        for (Connector existing : jetty.getConnectors()) {
            HttpConnectionFactory http = existing.getConnectionFactory(HttpConnectionFactory.class);
            if (http != null) {
                return http.getHttpConfiguration();
            }
        }
        return new HttpConfiguration();
    }
}
