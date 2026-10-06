package app.budget.server.data;

import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Requests;
import app.budget.server.auth.SessionStore;
import app.budget.server.db.Database;
import io.javalin.Javalin;
import io.javalin.http.Context;

/**
 * {@code DELETE /api/data} — "Erase all data": wipes everything and leaves a fresh app.
 *
 * <p>Deliberately awkward to trigger by accident: it needs a live session, must come from
 * this PC (never a phone), and the request has to carry an explicit confirmation phrase,
 * which the UI only sends after the person has confirmed.
 */
public final class DataRoutes {

    /** What the request body must say. Not a secret — a guard against stray or replayed calls. */
    public static final String CONFIRMATION = "erase-all-data";

    private final Database database;
    private final SessionStore sessions;

    public DataRoutes(Database database, SessionStore sessions) {
        this.database = database;
        this.sessions = sessions;
    }

    public void register(Javalin app) {
        app.delete("/api/data", this::eraseEverything);
    }

    /** Request body. */
    public record EraseRequest(String confirm) {}

    public record EraseResult(boolean erased) {}

    private void eraseEverything(Context ctx) {
        if (!Requests.isFromThisPc(ctx)) {
            throw ApiException.forbidden("For safety, data can only be erased from the PC Budget is installed on.");
        }
        EraseRequest request;
        try {
            request = ctx.bodyAsClass(EraseRequest.class);
        } catch (Exception e) {
            request = null;
        }
        if (request == null || !CONFIRMATION.equals(request.confirm())) {
            throw ApiException.badRequest("Nothing was erased — the request wasn't confirmed.");
        }
        database.eraseEverything();
        // A fresh app has no one signed in; from Phase 6 this also means no PIN is set.
        sessions.revokeAll();
        ApiResponse.ok(ctx, new EraseResult(true));
    }
}
