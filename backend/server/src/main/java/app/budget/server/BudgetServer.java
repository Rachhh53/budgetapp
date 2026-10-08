package app.budget.server;

import app.budget.server.accounts.AccountsRepository;
import app.budget.server.accounts.AccountsRoutes;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.HostGuard;
import app.budget.server.api.LowerCaseEnumSerializer;
import app.budget.server.auth.SessionRoutes;
import app.budget.server.auth.SessionStore;
import app.budget.server.categories.CategoriesRepository;
import app.budget.server.categories.CategoriesRoutes;
import app.budget.server.categories.TagsRepository;
import app.budget.server.changes.ChangeLog;
import app.budget.server.changes.ChangeRoutes;
import app.budget.server.data.DataRoutes;
import app.budget.server.db.Database;
import app.budget.server.entries.EntriesRepository;
import app.budget.server.entries.EntriesRoutes;
import app.budget.server.income.IncomeRepository;
import app.budget.server.income.IncomeRoutes;
import app.budget.server.income.PayCalendar;
import app.budget.server.items.ItemCalendar;
import app.budget.server.items.ItemsRepository;
import app.budget.server.items.ItemsRoutes;
import app.budget.server.ledger.Ledger;
import app.budget.server.ledger.LedgerRoutes;
import app.budget.server.lifecycle.PortFinder;
import app.budget.server.lifecycle.ShutdownRoutes;
import app.budget.server.schedule.HolidaysRepository;
import app.budget.server.schedule.HolidaysRoutes;
import app.budget.server.settings.SettingsRepository;
import app.budget.server.settings.SettingsRoutes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;
import io.javalin.router.Endpoint;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.Map;
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

    public BudgetServer(Config config, Database database, SessionStore sessions, Clock clock, Runnable onShutdown) {
        this.config = config;
        byte[] indexHtml = readIndexHtml();

        SettingsRepository settings = new SettingsRepository(database.jdbi(), clock);
        AccountsRepository accounts = new AccountsRepository(database.jdbi(), clock);
        HolidaysRepository holidays = new HolidaysRepository(database.jdbi());
        IncomeRepository income = new IncomeRepository(database.jdbi(), clock);
        PayCalendar payCalendar = new PayCalendar(income, holidays, settings, clock);
        CategoriesRepository categories = new CategoriesRepository(database.jdbi(), clock);
        TagsRepository tags = new TagsRepository(database.jdbi(), clock);
        ItemsRepository items = new ItemsRepository(database.jdbi(), clock);
        EntriesRepository entries = new EntriesRepository(database.jdbi(), clock);
        ItemCalendar itemCalendar = new ItemCalendar(items, payCalendar);
        Ledger ledger = new Ledger(accounts, entries, itemCalendar, payCalendar);

        app = Javalin.create(javalin -> {
            javalin.startup.showJavalinBanner = false;
            javalin.jsonMapper(new JavalinJackson(jsonMapper(), false));
            if (indexHtml != null) {
                javalin.staticFiles.add(files -> {
                    files.hostedPath = "/";
                    files.directory = STATIC_ROOT;
                    files.location = Location.CLASSPATH;
                });
            }

            JavalinDefaultRoutingApi routes = javalin.routes;
            routes.before(new HostGuard(config.allowedHosts()));
            routes.before(BudgetServer::addSecurityHeaders);

            // Registers the session gate first, so it runs ahead of every route added below.
            new SessionRoutes(sessions).register(routes);
            // Straight after the gate: everything a signed-in request changes from here on can be undone.
            new ChangeRoutes(new ChangeLog(database.jdbi(), clock)).register(routes);
            routes.get("/api/health", ctx -> ApiResponse.ok(ctx, Map.of("ok", true)));
            new SettingsRoutes(settings).register(routes);
            new AccountsRoutes(accounts, clock).register(routes);
            new HolidaysRoutes(holidays, clock).register(routes);
            new IncomeRoutes(income, accounts, payCalendar).register(routes);
            new CategoriesRoutes(categories, tags, items, itemCalendar, payCalendar).register(routes);
            new ItemsRoutes(items, itemCalendar, payCalendar, accounts, categories, tags).register(routes);
            new EntriesRoutes(entries, accounts, categories, tags, clock).register(routes);
            new LedgerRoutes(ledger, payCalendar).register(routes);
            new DataRoutes(database, sessions).register(routes);
            new ShutdownRoutes(config.shutdownToken(), onShutdown).register(routes);

            routes.exception(ApiException.class, (e, ctx) -> ApiResponse.fail(ctx, e.status(), e.getMessage()));
            routes.exception(HttpResponseException.class, (e, ctx) -> ApiResponse.fail(ctx, e.getStatus(),
                    e.getStatus() >= 500 ? GENERIC_ERROR : "We couldn't understand that request."));
            routes.exception(Exception.class, (e, ctx) -> {
                // Full detail stays in the local log; the client only ever sees the generic message.
                // The route's pattern is logged, never the address itself, which can hold ids.
                Endpoint endpoint = ctx.endpoints().lastHttpEndpoint();
                log.error("Unhandled error on {} {}", ctx.method(), endpoint == null ? "(no route)" : endpoint.path, e);
                ApiResponse.fail(ctx, 500, GENERIC_ERROR);
            });
            routes.error(404, ctx -> notFound(ctx, indexHtml));
        });
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
                .registerModule(new SimpleModule().addSerializer(new LowerCaseEnumSerializer()))
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
