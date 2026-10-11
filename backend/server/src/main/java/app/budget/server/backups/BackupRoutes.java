package app.budget.server.backups;

import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.api.Requests;
import app.budget.server.auth.SessionStore;
import app.budget.server.backups.BackupService.Backup;
import app.budget.server.backups.BackupService.RestoreException;
import app.budget.server.settings.SettingKey;
import app.budget.server.settings.SettingsRepository;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code /api/backups} (list, back up now, choose the folder, restore) and
 * {@code /api/folders}, the folder browser behind "Choose folder".
 *
 * <p>Looking at the list and backing up now are open to any session. Everything that reads
 * this PC's folders, or replaces the data, answers this PC only, like Erase all data.
 */
public final class BackupRoutes {

    /** What a restore request must say. Not a secret: a guard against stray or replayed calls. */
    public static final String RESTORE_CONFIRMATION = "restore-backup";

    private static final String THIS_PC_ONLY = "For safety, this can only be done from the PC Budget is installed on.";
    private static final String FOLDER_UNAVAILABLE =
            "We couldn't save a backup to that folder. Please check it's still there, or choose another one.";

    private final BackupService backups;
    private final FolderBrowser folders;
    private final SettingsRepository settings;
    private final SessionStore sessions;

    public BackupRoutes(BackupService backups, FolderBrowser folders, SettingsRepository settings, SessionStore sessions) {
        this.backups = backups;
        this.folders = folders;
        this.settings = settings;
        this.sessions = sessions;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/backups", ctx -> ApiResponse.ok(ctx, status()));
        routes.post("/api/backups", this::backUpNow);
        routes.put("/api/backups/folder", this::chooseFolder);
        routes.post("/api/backups/restore", this::restore);
        routes.get("/api/folders", this::listFolders);
        routes.post("/api/folders", this::createFolder);
    }

    /**
     * @param folder              where backups are saved
     * @param usingDefaultFolder  true until a folder has been chosen: they are then inside the app's own data
     * @param keepCount           how many are kept
     * @param backups             newest first
     * @param lastBackupAt        when the newest was made; {@code null} when there are none
     * @param lastDailyBackupFailed true when today's automatic backup could not be saved
     */
    public record Status(
            String folder,
            boolean usingDefaultFolder,
            int keepCount,
            List<Backup> backups,
            LocalDateTime lastBackupAt,
            boolean lastDailyBackupFailed) {}

    /** Request bodies. */
    public record FolderChoice(String path) {}

    public record RestoreRequest(String name, String confirm) {}

    public record NewFolder(String parent, String name) {}

    public record Restored(boolean restored) {}

    private Status status() {
        List<Backup> list = backups.list();
        return new Status(
                backups.folder().toString(),
                backups.usingDefaultFolder(),
                Integer.parseInt(settings.get(SettingKey.BACKUP_KEEP_COUNT)),
                list,
                list.isEmpty() ? null : list.get(0).createdAt(),
                backups.lastDailyBackupFailed());
    }

    private void backUpNow(Context ctx) {
        try {
            backups.backUpNow();
        } catch (BackupService.FolderUnavailableException e) {
            throw ApiException.conflict(FOLDER_UNAVAILABLE);
        }
        ApiResponse.send(ctx, 201, status());
    }

    private void chooseFolder(Context ctx) {
        requireThisPc(ctx);
        if (!backups.chooseFolder(Inputs.body(ctx, FolderChoice.class).path())) {
            throw ApiException.badRequest(
                    "Backups can't be saved to that folder. Please choose one that exists and that you can save files in.");
        }
        ApiResponse.ok(ctx, status());
    }

    private void restore(Context ctx) {
        requireThisPc(ctx);
        RestoreRequest request = Inputs.body(ctx, RestoreRequest.class);
        if (!RESTORE_CONFIRMATION.equals(request.confirm())) {
            throw ApiException.badRequest("Nothing was restored — the request wasn't confirmed.");
        }
        try {
            backups.restore(request.name());
        } catch (RestoreException e) {
            throw switch (e.problem()) {
                case NOT_FOUND -> ApiException.notFound();
                case UNREADABLE -> ApiException.badRequest(
                        "We couldn't read that backup, so nothing was changed. It may be damaged; please try another one.");
                case TOO_NEW -> ApiException.conflict(
                        "That backup was made by a newer version of Budget, so nothing was changed. "
                                + "Please install the newer version, then restore it.");
            };
        } catch (BackupService.FolderUnavailableException e) {
            throw ApiException.conflict(
                    "We couldn't save a copy of what you have now first, so nothing was changed. Please try again.");
        }
        // What was on screen belongs to the data that was replaced: everyone unlocks afresh.
        sessions.revokeAll();
        ApiResponse.ok(ctx, new Restored(true));
    }

    private void listFolders(Context ctx) {
        requireThisPc(ctx);
        String path = ctx.queryParam("path");
        if (path == null || path.isBlank()) {
            ApiResponse.ok(ctx, folders.top());
            return;
        }
        ApiResponse.ok(ctx, folders.list(path).orElseThrow(() ->
                ApiException.badRequest("We couldn't open that folder. Please choose another one.")));
    }

    private void createFolder(Context ctx) {
        requireThisPc(ctx);
        NewFolder request = Inputs.body(ctx, NewFolder.class);
        String created = folders.create(request.parent(), request.name()).orElseThrow(() -> ApiException.badRequest(
                "We couldn't make that folder. Please try a different name, without any of \\ / : * ? \" < > |"));
        ctx.status(201);
        ApiResponse.send(ctx, 201, folders.list(created).orElseThrow(ApiException::notFound));
    }

    private static void requireThisPc(Context ctx) {
        if (!Requests.isFromThisPc(ctx)) {
            throw ApiException.forbidden(THIS_PC_ONLY);
        }
    }
}
