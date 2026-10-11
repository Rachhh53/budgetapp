package app.budget.server.changes;

import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Records every change so it can be undone, and undoes them: {@code POST /api/undo}.
 *
 * <p>Each request that can change data is recorded as one change, start to finish. Such
 * requests are handled one at a time: this is one person's app, and it keeps two changes
 * from being mixed into one Undo step.
 */
public final class ChangeRoutes {

    /** Set on the answer to a request that changed something: the UI offers Undo when it sees it. */
    public static final String CHANGED_HEADER = "X-Budget-Changed";

    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");

    /** Unlocking, locking and the shell's shutdown call touch no data. */
    private static final Set<String> NOT_DATA = Set.of("/api/session", "/api/shutdown");

    /**
     * Changes that cannot be taken back: Undo itself (there is no redo), erasing all data,
     * which must leave nothing behind (spec §7), and restoring a backup, which replaces the
     * change log along with everything else. They still wait their turn.
     */
    private static final Set<String> NOT_RECORDED = Set.of("/api/undo", "/api/data", "/api/backups/restore");

    /** Requests that write files on this PC but none of the budget's data: nothing to record, nothing to wait for. */
    private static final Set<String> NOT_BUDGET_DATA = Set.of("/api/backups", "/api/folders");

    private static final String TURN_HELD = "changes.turnHeld";
    private static final String RECORDING = "changes.recording";
    private static final int WAIT_SECONDS = 10;

    private final ChangeLog changes;
    /** A semaphore, not a lock: nothing guarantees the same thread finishes a request that started it. */
    private final Semaphore turn = new Semaphore(1, true);

    public ChangeRoutes(ChangeLog changes) {
        this.changes = changes;
    }

    /** Register after the session gate, so a locked request is refused before anything is recorded. */
    public void register(JavalinDefaultRoutingApi routes) {
        routes.before("/api/*", this::start);
        routes.after("/api/*", this::finish);
        routes.get("/api/undo", ctx -> ApiResponse.ok(ctx, new UndoStatus(changes.canUndo())));
        routes.post("/api/undo", this::undo);
    }

    /** @param available true when there is a change to undo */
    public record UndoStatus(boolean available) {}

    /**
     * @param undone    false when there was nothing to undo
     * @param available true when there is an earlier change that can be undone next
     */
    public record Undone(boolean undone, boolean available) {}

    private void start(Context ctx) throws InterruptedException {
        if (READS.contains(ctx.method().name()) || NOT_DATA.contains(ctx.path()) || NOT_BUDGET_DATA.contains(ctx.path())) {
            return;
        }
        if (!turn.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new ApiException(503, "Budget is still finishing your last change. Please try again in a moment.");
        }
        ctx.attribute(TURN_HELD, true);
        if (!NOT_RECORDED.contains(ctx.path())) {
            changes.begin();
            ctx.attribute(RECORDING, true);
        }
    }

    /** Runs however the request ended, so the turn is always handed on. */
    private void finish(Context ctx) {
        if (ctx.attribute(TURN_HELD) == null) {
            return;
        }
        try {
            if (ctx.attribute(RECORDING) != null && changes.finish()) {
                ctx.header(CHANGED_HEADER, "true");
            }
        } finally {
            turn.release();
        }
    }

    private void undo(Context ctx) {
        boolean undone = changes.undoLatest();
        ApiResponse.ok(ctx, new Undone(undone, changes.canUndo()));
    }
}
