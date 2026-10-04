package app.budget.server.auth;

import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Requests;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.Set;

/**
 * Unlocking, locking, and the gate in front of every other API route.
 *
 * <p>Until the PIN arrives (Phase 6) there is nothing to type: the desktop window, and only
 * the desktop window, may start a session without credentials. The gate itself is already
 * the real one — every route behind it answers 401 without a live session cookie.
 */
public final class SessionRoutes {

    public static final String COOKIE = "budget_session";

    /** Routes that must work while locked: the health check, unlocking, and the shell's shutdown call (own token). */
    private static final Set<String> OPEN_ROUTES =
            Set.of("GET /api/health", "POST /api/session", "POST /api/shutdown");

    private final SessionStore sessions;

    public SessionRoutes(SessionStore sessions) {
        this.sessions = sessions;
    }

    public void register(Javalin app) {
        app.before("/api/*", this::requireSession);
        app.post("/api/session", this::unlock);
        app.get("/api/session", ctx -> ApiResponse.ok(ctx, new SessionStatus(true)));
        app.delete("/api/session", this::lock);
    }

    public record SessionStatus(boolean unlocked) {}

    private void requireSession(Context ctx) {
        if (OPEN_ROUTES.contains(ctx.method() + " " + ctx.path())) {
            return;
        }
        if (!sessions.touch(ctx.cookie(COOKIE))) {
            throw ApiException.unauthorized();
        }
    }

    private void unlock(Context ctx) {
        if (!Requests.isFromThisPc(ctx)) {
            throw ApiException.forbidden("Budget can only be unlocked from the PC it's installed on for now.");
        }
        // HttpOnly keeps the token away from page scripts; SameSite=Strict means no other site
        // can make the browser send it. No Max-Age: the cookie ends when the window closes.
        ctx.header("Set-Cookie", COOKIE + "=" + sessions.issue() + "; Path=/; HttpOnly; SameSite=Strict");
        ApiResponse.send(ctx, 201, new SessionStatus(true));
    }

    private void lock(Context ctx) {
        sessions.revoke(ctx.cookie(COOKIE));
        ctx.header("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
        ApiResponse.ok(ctx, new SessionStatus(false));
    }
}
