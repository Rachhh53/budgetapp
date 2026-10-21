package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Removing an amount from a recurring item's Amount history (Rachel, 2026-10-20: "amount history record in recurring
 * items should be able to be deleted"). Rent of $1,550.00 from Jan 1, 2026, then $1,600.00 from Nov 1. "Today" is
 * 2026-10-01.
 */
class AmountHistoryApiTest extends ApiTestBase {

    long checking;
    long rent;

    @BeforeEach
    void addRent() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        rent = created("/api/items", rent(155000, null));
        assertThat(server.put("/api/items/" + rent, rent(160000, "2026-11-01"), cookie).status()).isEqualTo(200);
    }

    private String rent(long cents, String from) {
        return """
                {"name":"Rent","kind":"bill","amountCents":%d,"payFromAccountId":%d,%s
                 "schedule":{"frequency":"monthly","day1":1,"startDate":"2026-01-01","weekendRule":"none"}}"""
                .formatted(cents, checking, from == null ? "" : "\"effectiveFrom\":\"" + from + "\",");
    }

    private JsonNode history() throws Exception {
        return server.get("/api/items/" + rent, cookie).data().get("history");
    }

    @Test
    void removingTheNewerAmountLeavesTheOneBeforeItInForce() throws Exception {
        long newer = history().get(1).get("versionId").asLong();

        Reply reply = server.delete("/api/items/" + rent + "/versions/" + newer, cookie);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(history()).hasSize(1);
        assertThat(history().get(0).get("perOccurrenceCents").asLong()).isEqualTo(155000);
    }

    @Test
    void removingTheFirstLeavesTheNextCoveringEverything() throws Exception {
        long first = history().get(0).get("versionId").asLong();

        server.delete("/api/items/" + rent + "/versions/" + first, cookie);

        assertThat(history()).hasSize(1);
        assertThat(server.get("/api/items/" + rent, cookie).data().get("amountCents").asLong()).isEqualTo(160000);
    }

    @Test
    void theOnlyAmountStays() throws Exception {
        server.delete("/api/items/" + rent + "/versions/" + history().get(1).get("versionId").asLong(), cookie);

        Reply last = server.delete("/api/items/" + rent + "/versions/" + history().get(0).get("versionId").asLong(), cookie);

        assertThat(last.status()).isEqualTo(409);
        assertThat(last.error()).isEqualTo("This is the only amount it has, so it stays. To change it, choose Change the amount.");
    }

    @Test
    void anotherItemsAmountIsNotFound() throws Exception {
        assertThat(server.delete("/api/items/" + rent + "/versions/99999", cookie).status()).isEqualTo(404);
    }

    @Test
    void needsASession() throws Exception {
        assertThat(server.delete("/api/items/" + rent + "/versions/1", null).status()).isEqualTo(401);
    }
}
