package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A request refused before its body is read must still get its answer. If the server replies
 * and closes while the body is unread, the connection is reset and the client sees a dropped
 * connection instead of the 401 — which is how a locked app would fail to unlock and retry.
 */
class RefusedRequestsTest extends ApiTestBase {

    /** Large enough that the server can answer before all of it has arrived. */
    private static final String LARGE_BODY = "{\"date\":\"2026-11-27\",\"name\":\"" + "x".repeat(200_000) + "\"}";

    /** The fault showed up a few times per thousand requests, so one request proves nothing. */
    private static final int ROUNDS = 500;

    @Test
    void aLockedRequestWithABodyAlwaysGetsIts401() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            assertThat(server.post("/api/holidays", LARGE_BODY, null).status()).isEqualTo(401);
        }
    }

    @Test
    void aRequestForSomethingThatDoesNotExistAlwaysGetsIts404() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            assertThat(server.put("/api/holidays/not-a-number", LARGE_BODY, cookie).status()).isEqualTo(404);
        }
    }
}
