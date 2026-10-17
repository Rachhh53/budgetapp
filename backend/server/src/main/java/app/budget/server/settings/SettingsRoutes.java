package app.budget.server.settings;

import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Requests;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.time.Clock;
import java.time.LocalDate;

/** {@code GET /api/settings} and {@code PUT /api/settings/{key}}. */
public final class SettingsRoutes {

    private final SettingsRepository settings;

    private final Clock clock;

    public SettingsRoutes(SettingsRepository settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/settings", ctx -> ApiResponse.ok(ctx, settings.all()));
        routes.put("/api/settings/{key}", this::update);
    }

    /** Request body for an update. */
    public record SettingUpdate(String value) {}

    private void update(Context ctx) {
        SettingKey setting = SettingKey.find(ctx.pathParam("key")).orElseThrow(ApiException::notFound);
        if (setting.thisPcOnly() && !Requests.isFromThisPc(ctx)) {
            throw ApiException.forbidden("For safety, this can only be changed on the PC Budget is installed on.");
        }
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
        // Forms open on this day, and several of them (a balance, a bucket's first day) take no day after today.
        if (setting == SettingKey.DEFAULT_DATE && !update.value().isEmpty()
                && LocalDate.parse(update.value()).isAfter(LocalDate.now(clock))) {
            throw ApiException.badRequest(setting.hint());
        }
        settings.put(setting, update.value());
        ApiResponse.ok(ctx, settings.all());
    }
}
