package app.budget.server.api;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rejects requests whose Host header is a domain name we were not told about.
 *
 * <p>Why: with DNS rebinding, a malicious web page can point its own domain at 127.0.0.1
 * and have the browser treat this server as "same origin". Such requests always carry the
 * attacker's domain as Host, while genuine ones use an IP address or {@code localhost} —
 * so only those (plus names explicitly allowed in config) get through.
 */
public final class HostGuard implements Handler {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern BRACKETED_IPV6 = Pattern.compile("\\[[0-9a-fA-F:.]+]");
    private static final Pattern PORT_SUFFIX = Pattern.compile(":\\d{1,5}$");

    private final Set<String> extraHosts;

    public HostGuard(Set<String> extraHosts) {
        this.extraHosts = extraHosts;
    }

    @Override
    public void handle(Context ctx) {
        if (!isAllowed(ctx.header("Host"))) {
            throw ApiException.forbidden("This address isn't one Budget recognises. Please open Budget from the app itself.");
        }
    }

    boolean isAllowed(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        String host = PORT_SUFFIX.matcher(hostHeader.trim().toLowerCase()).replaceFirst("");
        return host.equals("localhost")
                || IPV4.matcher(host).matches()
                || BRACKETED_IPV6.matcher(host).matches()
                || extraHosts.contains(host);
    }
}
