package app.budget.server;

import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.HostGuard;
import app.budget.server.auth.SessionRoutes;
import app.budget.server.auth.SessionStore;
import app.budget.server.lifecycle.PortFinder;
import app.budget.server.lifecycle.ShutdownRoutes;
import app.budget.server.settings.SettingsRepository;
import app.budget.server.settings.SettingsRoutes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The HTTP server: the JSON API under {@code /api} and the built React app everywhere else,
 * on one origin (so there is no CORS to configure, and none is enabled).
 */
public final class BudgetServer {

    private static final Logger log = LoggerFactory.getLogger(BudgetServer.class);

    /** Classpath folder the frontend build is packaged into (see server/pom.xml). */
    private static final String STATIC_ROOT = "/public";
    private static final String INDEX = STATIC_ROOT + "/index.html";

    /**
     * Blocks every external origin: the page may only load and talk to this server itself.
     * Inline style attributes are allowed because user-chosen colors are applied that way.
     */
    private static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "style-src 'self'",
            "style-src-attr 'unsafe-inline'",
            "img-src 'self' data: blob:",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    private static final String GENERIC_ERROR =
            "Something went wrong on our side. Your data is safe — please try again.";

    private final Config config;
    private final Javalin app;

    public BudgetServer(Config config, Jdbi jdbi, SessionStore sessions, Clock clock, Runnable onShutdown) {
        this.config = config;
        byte[] indexHtml = readIndexHtml();

        app = Javalin.create(javalin -> {
            javalin.showJavalinBanner = false;
            javalin.jsonMapper(new JavalinJackson(jsonMapper(), false));
            if (indexHtml != null) {
                javalin.staticFiles.add(files -> {
                    files.hostedPath = "/";
                    files.directory = STATIC_ROOT;
                    files.location = Location.CLASSPATH;
                });
            }
        });

        app.before(new HostGuard(config.allowedHosts()));
        app.before(BudgetServer::addSecurityHeaders);

        // Registers the session gate first, so it runs ahead of every route added below.
        new SessionRoutes(sessions).register(app);
        app.get("/api/health", ctx -> ApiResponse.ok(ctx, Map.of("ok", true)));
        new SettingsRoutes(new SettingsRepository(jdbi, clock)).register(app);
        new ShutdownRoutes(config.shutdownToken(), onShutdown).register(app);

        app.exception(ApiException.class, (e, ctx) -> ApiResponse.fail(ctx, e.status(), e.getMessage()));
        app.exception(HttpResponseException.class, (e, ctx) -> ApiResponse.fail(ctx, e.getStatus(),
                e.getStatus() >= 500 ? GENERIC_ERROR : "We couldn't understand that request."));
        app.exception(Exception.class, (e, ctx) -> {
            // Full detail stays in the local log; the client only ever sees the generic message.
            log.error("Unhandled error on {} {}", ctx.method(), ctx.matchedPath(), e);
            ApiResponse.fail(ctx, 500, GENERIC_ERROR);
        });
        app.error(404, ctx -> notFound(ctx, indexHtml));
    }

    /** Starts listening and returns the port actually bound. */
    public int start() throws IOException {
        int port = PortFinder.firstFree(config.bindHost(), config.port());
        app.start(config.bindHost(), port);
        return app.port();
    }

    public void stop() {
        app.stop();
    }

    private static ObjectMapper jsonMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private static void addSecurityHeaders(Context ctx) {
        ctx.header("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        ctx.header("X-Content-Type-Options", "nosniff");
        ctx.header("Referrer-Policy", "no-referrer");
        if (ctx.path().startsWith("/api/")) {
            // API answers hold financial data; keep them out of any cache.
            ctx.header("Cache-Control", "no-store");
        }
    }

    /**
     * Unknown API paths get the JSON envelope. Any other unknown path is a screen inside the
     * React app (e.g. /accounts after a refresh), so it gets the app's index page.
     */
    private static void notFound(Context ctx, byte[] indexHtml) {
        if (ctx.path().startsWith("/api/") || indexHtml == null || !ctx.method().name().equals("GET")) {
            ApiResponse.fail(ctx, 404, "We couldn't find that.");
        } else {
            ctx.status(200).contentType("text/html; charset=utf-8").result(indexHtml);
        }
    }

    private static byte[] readIndexHtml() {
        try (InputStream in = BudgetServer.class.getResourceAsStream(INDEX)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the bundled app page", e);
        }
    }
}
