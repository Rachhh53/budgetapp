package app.budget.server.settings;

import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

/** {@code GET /api/settings} and {@code PUT /api/settings/{key}}. */
public final class SettingsRoutes {

    private final SettingsRepository settings;

    public SettingsRoutes(SettingsRepository settings) {
        this.settings = settings;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/settings", ctx -> ApiResponse.ok(ctx, settings.all()));
        routes.put("/api/settings/{key}", this::update);
    }

    /** Request body for an update. */
    public record SettingUpdate(String value) {}

    private void update(Context ctx) {
        SettingKey setting = SettingKey.find(ctx.pathParam("key")).orElseThrow(ApiException::notFound);
        SettingUpdate update;
        try {
            update = ctx.bodyAsClass(SettingUpdate.class);
        } catch (Exception e) {
            // Malformed or missing JSON: the parser's own message is not something to show anyone.
            throw ApiException.badRequest(setting.hint());
        }
        if (update == null || !setting.accepts(update.value())) {
            throw ApiException.badRequest(setting.hint());
        }
        settings.put(setting, update.value());
        ApiResponse.ok(ctx, settings.all());
    }
}
