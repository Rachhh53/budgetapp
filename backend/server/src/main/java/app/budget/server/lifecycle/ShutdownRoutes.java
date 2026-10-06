package app.budget.server.lifecycle;

import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Requests;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * {@code POST /api/shutdown} — how the desktop shell asks the backend to stop when its
 * window closes. Only the shell knows the per-launch token, and only requests from this PC
 * are considered, so neither a web page nor a phone can switch the app off.
 */
public final class ShutdownRoutes {

    public static final String TOKEN_HEADER = "X-Budget-Shutdown-Token";

    private final Optional<String> expectedToken;
    private final Runnable shutdown;

    /**
     * @param expectedToken empty when the server was started without a shell (plain dev run);
     *                      the endpoint then refuses everyone
     * @param shutdown      what to run once the response has been sent
     */
    public ShutdownRoutes(Optional<String> expectedToken, Runnable shutdown) {
        this.expectedToken = expectedToken;
        this.shutdown = shutdown;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.post("/api/shutdown", this::handle);
    }

    public record ShutdownStatus(boolean stopping) {}

    private void handle(Context ctx) {
        if (!Requests.isFromThisPc(ctx) || !tokenMatches(ctx.header(TOKEN_HEADER))) {
            throw ApiException.unauthorized();
        }
        ApiResponse.send(ctx, 202, new ShutdownStatus(true));
        // Off the request thread, so this response is delivered before the server goes away.
        Thread.ofPlatform().name("budget-shutdown").start(shutdown);
    }

    private boolean tokenMatches(String presented) {
        // Constant-time comparison: response timing must not leak how much of the token matched.
        return presented != null && expectedToken
                .map(expected -> MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8)))
                .orElse(false);
    }
}
