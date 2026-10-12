package app.budget.server;

import app.budget.server.accounts.AccountsRepository;
import app.budget.server.accounts.AccountsRoutes;
import app.budget.server.accounts.InterestRepository;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.HostGuard;
import app.budget.server.api.LowerCaseEnumSerializer;
import app.budget.server.assets.AssetsRepository;
import app.budget.server.assets.AssetsRoutes;
import app.budget.server.assets.NetWorthCalculator;
import app.budget.server.auth.AuthRepository;
import app.budget.server.auth.PinService;
import app.budget.server.auth.SessionRoutes;
import app.budget.server.auth.SessionStore;
import app.budget.server.backups.BackupRoutes;
import app.budget.server.backups.BackupService;
import app.budget.server.backups.FolderBrowser;
import app.budget.server.buckets.BucketsRepository;
import app.budget.server.buckets.BucketsRoutes;
import app.budget.server.cards.CardsRepository;
import app.budget.server.cards.CardsRoutes;
import app.budget.server.categories.CategoriesRepository;
import app.budget.server.categories.CategoriesRoutes;
import app.budget.server.categories.TagsRepository;
import app.budget.server.changes.ChangeLog;
import app.budget.server.changes.ChangeRoutes;
import app.budget.server.data.DataRoutes;
import app.budget.server.db.Database;
import app.budget.server.entries.EntriesRepository;
import app.budget.server.entries.EntriesRoutes;
import app.budget.server.files.AttachmentsRepository;
import app.budget.server.files.AttachmentsRoutes;
import app.budget.server.files.FileStore;
import app.budget.server.files.SavedCopies;
import app.budget.server.income.IncomeRepository;
import app.budget.server.income.IncomeRoutes;
import app.budget.server.income.PayCalendar;
import app.budget.server.items.ItemCalendar;
import app.budget.server.items.ItemsRepository;
import app.budget.server.items.ItemsRoutes;
import app.budget.server.ledger.Ledger;
import app.budget.server.ledger.LedgerRoutes;
import app.budget.server.loans.LoansRepository;
import app.budget.server.loans.LoansRoutes;
import app.budget.server.lifecycle.PortFinder;
import app.budget.server.lifecycle.ShutdownRoutes;
import app.budget.server.reports.ReportRunner;
import app.budget.server.reports.ReportsRepository;
import app.budget.server.reports.ReportsRoutes;
import app.budget.server.schedule.HolidaysRepository;
import app.budget.server.schedule.HolidaysRoutes;
import app.budget.server.settings.SettingKey;
import app.budget.server.settings.SettingsRepository;
import app.budget.server.settings.SettingsRoutes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.config.SizeUnit;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;
import io.javalin.router.Endpoint;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
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

    /** How many megabytes of an upload are held in memory before it is written to a temporary file. */
    private static final int UPLOAD_IN_MEMORY_MB = 1;

    private static final String GENERIC_ERROR =
            "Something went wrong on our side. Your data is safe — please try again.";

    private final Config config;
    private final Javalin app;
    private final BackupService backups;

    public BudgetServer(Config config, Database database, Clock clock, Runnable onShutdown) {
        this.config = config;
        byte[] indexHtml = readIndexHtml();

        SettingsRepository settings = new SettingsRepository(database.jdbi(), clock);
        // Auto-lock follows the setting as it is now, not as it was when the app started.
        SessionStore sessions = new SessionStore(clock,
                () -> Duration.ofMinutes(Long.parseLong(settings.get(SettingKey.AUTO_LOCK_MINUTES))));
        AuthRepository auth = new AuthRepository(database.jdbi());
        PinService pins = new PinService(auth, clock);
        backups = new BackupService(database, settings, auth, clock);
        FolderBrowser folders = new FolderBrowser(Path.of(System.getProperty("user.home")));
        AccountsRepository accounts = new AccountsRepository(database.jdbi(), clock);
        HolidaysRepository holidays = new HolidaysRepository(database.jdbi());
        IncomeRepository income = new IncomeRepository(database.jdbi(), clock);
        PayCalendar payCalendar = new PayCalendar(income, holidays, settings, clock);
        CategoriesRepository categories = new CategoriesRepository(database.jdbi(), clock);
        TagsRepository tags = new TagsRepository(database.jdbi(), clock);
        ItemsRepository items = new ItemsRepository(database.jdbi(), clock);
        EntriesRepository entries = new EntriesRepository(database.jdbi(), clock);
        BucketsRepository buckets = new BucketsRepository(database.jdbi(), clock);
        InterestRepository interest = new InterestRepository(database.jdbi());
        ItemCalendar itemCalendar = new ItemCalendar(items, payCalendar);
        CardsRepository cards = new CardsRepository(database.jdbi());
        LoansRepository loans = new LoansRepository(database.jdbi());
        Ledger ledger = new Ledger(accounts, entries, items, itemCalendar, payCalendar, buckets, interest, cards, loans);
        AssetsRepository assets = new AssetsRepository(database.jdbi(), clock);
        NetWorthCalculator netWorth = new NetWorthCalculator(assets, accounts, ledger);
        FileStore fileStore = new FileStore(config.dataDir(), config.maxUploadMb() * 1024L * 1024L);
        AttachmentsRepository attachments = new AttachmentsRepository(database.jdbi(), clock);
        // Nothing is being recorded yet, so this is the moment to delete files nothing can want any more:
        // ones taken off whatever they were attached to, once Undo can no longer put them back.
        fileStore.sweep(attachments.forgetUnused());
        ReportsRepository reports = new ReportsRepository(database.jdbi(), clock, jsonMapper());
        ReportRunner reportRunner = new ReportRunner(entries, items, itemCalendar, payCalendar, accounts, categories, tags, attachments);

        app = Javalin.create(javalin -> {
            javalin.startup.showJavalinBanner = false;
            javalin.jsonMapper(new JavalinJackson(jsonMapper(), false));
            // Uploads: a little over what one request may carry, so a file that is too large is
            // refused by AttachmentsRoutes in words, not by the server with a bare error.
            javalin.jetty.multipartConfig.cacheDirectory(config.dataDir().resolve("upload-cache").toString());
            javalin.jetty.multipartConfig.maxInMemoryFileSize(UPLOAD_IN_MEMORY_MB, SizeUnit.MB);
            javalin.jetty.multipartConfig.maxFileSize(config.maxUploadMb() + 1L, SizeUnit.MB);
            javalin.jetty.multipartConfig.maxTotalRequestSize((config.maxUploadMb() + 1L) * AttachmentsRoutes.MAX_PER_OWNER, SizeUnit.MB);
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
            new SessionRoutes(sessions, pins).register(routes);
            // Straight after the gate: everything a signed-in request changes from here on can be undone.
            new ChangeRoutes(new ChangeLog(database.jdbi(), clock)).register(routes);
            routes.get("/api/health", ctx -> ApiResponse.ok(ctx, Map.of("ok", true)));
            new SettingsRoutes(settings).register(routes);
            new AccountsRoutes(accounts, interest, buckets, ledger, clock).register(routes);
            new BucketsRoutes(buckets, accounts, ledger, clock).register(routes);
            new HolidaysRoutes(holidays, clock).register(routes);
            new IncomeRoutes(income, accounts, payCalendar, clock).register(routes);
            new CategoriesRoutes(categories, tags, items, itemCalendar, payCalendar).register(routes);
            new ItemsRoutes(items, itemCalendar, payCalendar, accounts, categories, tags, buckets, ledger, cards).register(routes);
            new EntriesRoutes(entries, accounts, categories, tags, buckets, ledger, clock).register(routes);
            new LedgerRoutes(ledger, payCalendar, accounts).register(routes);
            new AssetsRoutes(assets, accounts, netWorth, clock).register(routes);
            new CardsRoutes(cards, accounts, interest, items, ledger, payCalendar, settings).register(routes);
            new LoansRoutes(loans, accounts, interest, ledger, payCalendar, items).register(routes);
            SavedCopies savedCopies = new SavedCopies(config.downloadsDir());
            new AttachmentsRoutes(attachments, fileStore, entries, items, itemCalendar, cards, accounts, clock, savedCopies)
                    .register(routes);
            new ReportsRoutes(reports, reportRunner, attachments, fileStore, accounts, categories, tags, config.fontsDir(), savedCopies)
                    .register(routes);
            new DataRoutes(database, sessions).register(routes);
            new BackupRoutes(backups, folders, settings, sessions).register(routes);
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

    /** The backup service, for the daily timer (started by {@code Main}, not by tests). */
    public BackupService backups() {
        return backups;
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
