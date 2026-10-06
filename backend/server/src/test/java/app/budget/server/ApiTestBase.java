package app.budget.server;

import app.budget.server.TestServer.Reply;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/**
 * Shared setup for endpoint tests: a real server over HTTP against a temporary database,
 * already unlocked. The server's clock says it is 2026-10-01.
 */
abstract class ApiTestBase {

    @TempDir
    Path dataDir;

    TestServer server;

    /** A live session cookie. Pass {@code null} instead to act as someone who has not unlocked. */
    String cookie;

    @BeforeEach
    void start() throws Exception {
        server = new TestServer(dataDir);
        cookie = server.unlock();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    /** Creates an account and returns its id. */
    long createAccount(String name, String type) throws Exception {
        Reply reply = server.post("/api/accounts",
                "{\"name\":\"%s\",\"type\":\"%s\",\"color\":\"#0f766e\"}".formatted(name, type), cookie);
        return reply.data().get("account").get("id").asLong();
    }
}
