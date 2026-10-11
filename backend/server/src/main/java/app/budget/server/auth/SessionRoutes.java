package app.budget.server.auth;

import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.api.Requests;
import app.budget.server.auth.PinService.Outcome;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.util.Set;

/**
 * Unlocking, locking, the PIN, and the gate in front of every other API route.
 *
 * <p>The PIN is optional (decided 2026-10-11). Without one, the desktop window, and only the
 * desktop window, may start a session without credentials. With one, a session starts only
 * with the PIN, wherever the request comes from. Either way every route behind the gate
 * answers 401 without a live session cookie.
 */
public final class SessionRoutes {

    public static final String COOKIE = "budget_session";

    /**
     * Routes that must work while locked: the health check, asking whether the app is locked,
     * unlocking, resetting a forgotten PIN, and the shell's shutdown call (own token).
     */
    private static final Set<String> OPEN_ROUTES = Set.of(
            "GET /api/health", "GET /api/session", "POST /api/session", "POST /api/pin/recovery", "POST /api/shutdown");

    private static final String WRONG_PIN = "That PIN didn't match. Please try again.";
    private static final String WRONG_CODE = "That recovery code didn't match. Please check it and try again.";

    private final SessionStore sessions;
    private final PinService pins;

    public SessionRoutes(SessionStore sessions, PinService pins) {
        this.sessions = sessions;
        this.pins = pins;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.before("/api/*", this::requireSession);
        routes.get("/api/session", ctx -> ApiResponse.ok(ctx, status(sessions.touch(ctx.cookie(COOKIE)))));
        routes.post("/api/session", this::unlock);
        routes.delete("/api/session", this::lock);
        routes.put("/api/pin", this::setPin);
        routes.delete("/api/pin", this::removePin);
        routes.delete("/api/pin/offer", this::dismissOffer);
        routes.post("/api/pin/recovery", this::resetWithRecoveryCode);
        routes.post("/api/pin/recovery-code", this::newRecoveryCode);
    }

    /**
     * @param unlocked          true when this request carries a live session
     * @param pinSet            true when a PIN is needed to unlock
     * @param retryAfterSeconds after too many wrong tries, how long until the next one is looked at
     * @param offerPin          true when the first-launch offer to set a PIN has not been answered yet
     */
    public record SessionStatus(boolean unlocked, boolean pinSet, long retryAfterSeconds, boolean offerPin) {}

    /** Request bodies. A PIN is text, never a number: it may start with zeros. */
    public record UnlockRequest(String pin) {}

    public record PinRequest(String currentPin, String newPin) {}

    public record CurrentPinRequest(String currentPin) {}

    public record RecoveryRequest(String recoveryCode, String newPin) {}

    /** @param recoveryCode the code to write down, sent this once; {@code null} when it has not changed */
    public record PinSaved(String recoveryCode, SessionStatus session) {}

    private void requireSession(Context ctx) {
        if (OPEN_ROUTES.contains(ctx.method() + " " + ctx.path())) {
            return;
        }
        if (!sessions.touch(ctx.cookie(COOKIE))) {
            throw ApiException.unauthorized();
        }
    }

    private SessionStatus status(boolean unlocked) {
        PinService.Status pin = pins.status();
        return new SessionStatus(unlocked, pin.pinSet(), pin.retryAfterSeconds(), unlocked && pin.offerPending());
    }

    private void unlock(Context ctx) {
        if (pins.status().pinSet()) {
            // The desktop window sends no body before it knows a PIN is needed.
            String pin = ctx.body().isBlank() ? null : Inputs.body(ctx, UnlockRequest.class).pin();
            require(pins.checkPin(pin), 401, WRONG_PIN);
        } else if (!Requests.isFromThisPc(ctx)) {
            throw ApiException.forbidden("Please set a PIN on your PC before opening Budget from another device.");
        }
        startSession(ctx);
        ApiResponse.send(ctx, 201, status(true));
    }

    private void lock(Context ctx) {
        sessions.revoke(ctx.cookie(COOKIE));
        ctx.header("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
        ApiResponse.ok(ctx, status(false));
    }

    /** Sets the first PIN, or changes it (which needs the one in use). */
    private void setPin(Context ctx) {
        PinRequest request = Inputs.body(ctx, PinRequest.class);
        if (!PinService.isValidPin(request.newPin())) {
            throw ApiException.badRequest(PinService.PIN_HINT);
        }
        String recoveryCode = null;
        if (pins.status().pinSet()) {
            requireCurrentPin(request.currentPin());
            pins.changePin(request.newPin());
        } else {
            recoveryCode = pins.setPin(request.newPin());
        }
        // Anything else that was unlocked now has to give the new PIN.
        sessions.revokeAllExcept(ctx.cookie(COOKIE));
        ApiResponse.ok(ctx, new PinSaved(recoveryCode, status(true)));
    }

    private void removePin(Context ctx) {
        if (pins.status().pinSet()) {
            requireCurrentPin(Inputs.body(ctx, CurrentPinRequest.class).currentPin());
            pins.removePin();
        }
        ApiResponse.ok(ctx, status(true));
    }

    private void dismissOffer(Context ctx) {
        pins.dismissOffer();
        ApiResponse.ok(ctx, status(true));
    }

    /** A forgotten PIN: the recovery code sets a new one, and is itself replaced, since it has now been used. */
    private void resetWithRecoveryCode(Context ctx) {
        RecoveryRequest request = Inputs.body(ctx, RecoveryRequest.class);
        if (!pins.status().pinSet()) {
            throw ApiException.conflict("There is no PIN to reset. Budget opens without one.");
        }
        if (!PinService.isValidPin(request.newPin())) {
            throw ApiException.badRequest(PinService.PIN_HINT);
        }
        require(pins.checkRecoveryCode(request.recoveryCode()), 401, WRONG_CODE);
        pins.changePin(request.newPin());
        String recoveryCode = pins.newRecoveryCode();
        sessions.revokeAll();
        startSession(ctx);
        ApiResponse.send(ctx, 201, new PinSaved(recoveryCode, status(true)));
    }

    private void newRecoveryCode(Context ctx) {
        if (!pins.status().pinSet()) {
            throw ApiException.conflict("A recovery code goes with a PIN. Please set a PIN first.");
        }
        requireCurrentPin(Inputs.body(ctx, CurrentPinRequest.class).currentPin());
        ApiResponse.ok(ctx, new PinSaved(pins.newRecoveryCode(), status(true)));
    }

    /**
     * Inside a session a wrong PIN is 403, not 401: the app is still unlocked, and 401 is what
     * tells the screen to show the lock.
     */
    private void requireCurrentPin(String pin) {
        require(pins.checkPin(pin), 403, "That isn't the PIN in use now. Please try again.");
    }

    private void require(Outcome outcome, int wrongStatus, String wrongMessage) {
        switch (outcome) {
            case OK -> { }
            case WRONG -> throw new ApiException(wrongStatus, wrongMessage);
            case WAIT -> throw new ApiException(429, "That's a few tries that didn't match. Please wait "
                    + PinService.inWords(Math.max(1, pins.status().retryAfterSeconds())) + ", then try again.");
        }
    }

    private void startSession(Context ctx) {
        // HttpOnly keeps the token away from page scripts; SameSite=Strict means no other site
        // can make the browser send it. No Max-Age: the cookie ends when the window closes.
        ctx.header("Set-Cookie", COOKIE + "=" + sessions.issue() + "; Path=/; HttpOnly; SameSite=Strict");
    }
}
