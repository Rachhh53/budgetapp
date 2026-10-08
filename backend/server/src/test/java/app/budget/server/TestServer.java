package app.budget.server;

import app.budget.server.auth.SessionStore;
import app.budget.server.db.Database;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A real server on a free loopback port, backed by a throwaway SQLite file in a temp folder —
 * never the real data folder. Tests talk to it over HTTP exactly as the app does.
 */
public final class TestServer implements AutoCloseable {

    public static final String SHUTDOWN_TOKEN = "test-shutdown-token";
    public static final Duration SESSION_IDLE = Duration.ofMinutes(15);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A clock the test can move forward, to exercise session expiry without waiting. */
    public static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-01T12:00:00Z");

        public void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /** One HTTP exchange, with the JSON envelope already parsed. */
    public record Reply(int status, JsonNode body, Optional<String> setCookie, HttpHeaders headers) {
        /** True when the server recorded a change that can be undone. */
        public boolean changed() {
            return headers.firstValue("X-Budget-Changed").isPresent();
        }

        public JsonNode data() {
            return body.get("data");
        }

        public String error() {
            return body.get("error").asText();
        }
    }

    public final MutableClock clock = new MutableClock();
    public final AtomicBoolean shutdownRequested = new AtomicBoolean();

    private final BudgetServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;

    public TestServer(Path dataDir) throws IOException {
        Config config = Config.fromEnvironment(Map.of(
                "BUDGET_PORT", "0",
                "BUDGET_DATA_DIR", dataDir.toString(),
                "BUDGET_SHUTDOWN_TOKEN", SHUTDOWN_TOKEN));
        server = new BudgetServer(
                config,
                Database.open(dataDir),
                new SessionStore(clock, SESSION_IDLE),
                clock,
                () -> shutdownRequested.set(true));
        baseUrl = "http://" + config.bindHost() + ":" + server.start();
    }

    public URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    /** Unlocks and returns the session cookie pair ({@code name=value}) to send on later requests. */
    public String unlock() throws IOException, InterruptedException {
        String setCookie = send("POST", "/api/session", null, Map.of()).setCookie().orElseThrow();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    public Reply send(String method, String path, String jsonBody, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .method(method, jsonBody == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(jsonBody));
        if (jsonBody != null) {
            request.header("Content-Type", "application/json");
        }
        headers.forEach(request::header);
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(
                response.statusCode(),
                JSON.readTree(response.body()),
                response.headers().firstValue("Set-Cookie"),
                response.headers());
    }

    public Reply get(String path, String cookie) throws IOException, InterruptedException {
        return send("GET", path, null, cookieHeader(cookie));
    }

    public Reply post(String path, String jsonBody, String cookie) throws IOException, InterruptedException {
        return send("POST", path, jsonBody, cookieHeader(cookie));
    }

    public Reply put(String path, String jsonBody, String cookie) throws IOException, InterruptedException {
        return send("PUT", path, jsonBody, cookieHeader(cookie));
    }

    public Reply delete(String path, String cookie) throws IOException, InterruptedException {
        return send("DELETE", path, null, cookieHeader(cookie));
    }

    private static Map<String, String> cookieHeader(String cookie) {
        return cookie == null ? Map.of() : Map.of("Cookie", cookie);
    }

    @Override
    public void close() {
        server.stop();
    }
}
