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

    /** Records what an account held (or owed) at the start of {@code date}. */
    void enterBalance(long accountId, String date, long cents) throws Exception {
        server.post("/api/accounts/" + accountId + "/balances",
                "{\"date\":\"%s\",\"amountCents\":%d}".formatted(date, cents), cookie);
    }

    /**
     * Adds the primary paycheck: $2,486.20 into {@code accountId} on the 1st and 15th, never moved
     * for weekends, so the pay periods are Oct 1–14, Oct 15–31, Nov 1–14 and so on.
     */
    long createSalary(long accountId) throws Exception {
        Reply reply = server.post("/api/income-sources", """
                {"name":"Acme Corp","depositAccountId":%d,"netCents":248620,"primary":true,
                 "schedule":{"frequency":"semi_monthly","day1":1,"day2":15,"startDate":"2026-01-01","weekendRule":"none"}}"""
                .formatted(accountId), cookie);
        return reply.data().get("id").asLong();
    }

    /** Posts {@code body} to {@code path}, expects it to be created, and returns the new record's id. */
    long created(String path, String body) throws Exception {
        Reply reply = server.post(path, body, cookie);
        if (reply.status() != 201) {
            throw new AssertionError("Expected 201 from POST " + path + " but got " + reply.status() + ": " + reply.body());
        }
        return reply.data().get("id").asLong();
    }
}
