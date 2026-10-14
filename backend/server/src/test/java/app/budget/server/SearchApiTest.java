package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Searching one-offs and recurring items from the pay period screen ({@code POST /api/search}).
 * "Today" is 2026-10-01; pay periods are the 1st–14th and the 15th–end of each month.
 */
class SearchApiTest extends ApiTestBase {

    long checking;

    @BeforeEach
    void addAccountAndPaycheck() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        createSalary(checking);
    }

    private void oneOff(String date, String description, String payee) throws Exception {
        created("/api/entries", """
                {"kind":"money_out","date":"%s","amountCents":4500,"description":"%s","payee":%s,"accountId":%d}"""
                .formatted(date, description, payee == null ? "null" : "\"" + payee + "\"", checking));
    }

    private Reply search(String text, String cookie) throws Exception {
        return server.post("/api/search", "{\"text\":\"" + text + "\"}", cookie);
    }

    @Test
    void needsASession() throws Exception {
        assertThat(search("vet", null).status()).isEqualTo(401);
    }

    @Test
    void findsOneOffsByDescriptionOrPayeeWithTheirPayPeriod() throws Exception {
        oneOff("2026-08-20", "Vet checkup", null);
        oneOff("2026-11-03", "Flea meds", "Maple Street VETS");
        oneOff("2026-09-02", "Groceries", null);

        Reply reply = search("  vet ", cookie);

        assertThat(reply.status()).isEqualTo(200);
        JsonNode oneOffs = reply.data().get("oneOffs");
        assertThat(oneOffs).hasSize(2);
        // Newest first: the planned one, then the one that already happened.
        assertThat(oneOffs.get(0).get("description").asText()).isEqualTo("Flea meds");
        assertThat(oneOffs.get(0).get("upcoming").asBoolean()).isTrue();
        assertThat(oneOffs.get(0).get("periodStart").asText()).isEqualTo("2026-11-01");
        assertThat(oneOffs.get(1).get("description").asText()).isEqualTo("Vet checkup");
        assertThat(oneOffs.get(1).get("upcoming").asBoolean()).isFalse();
        assertThat(oneOffs.get(1).get("periodStart").asText()).isEqualTo("2026-08-15");
        assertThat(reply.data().get("more").asBoolean()).isFalse();
    }

    @Test
    void takesWildcardsAsTheCharactersTyped() throws Exception {
        oneOff("2026-09-02", "Gift for Ana", null);
        oneOff("2026-09-03", "50% off sale", null);

        JsonNode oneOffs = search("0%", cookie).data().get("oneOffs");

        assertThat(oneOffs).hasSize(1);
        assertThat(oneOffs.get(0).get("description").asText()).isEqualTo("50% off sale");
        assertThat(search("__", cookie).data().get("oneOffs")).isEmpty();
    }

    @Test
    void saysWhenARecurringItemLastHappenedAndIsNextPlanned() throws Exception {
        created("/api/items", """
                {"name":"Therapy","kind":"bill","amountCents":12000,"payFromAccountId":%d,"autopay":true,
                 "schedule":{"frequency":"monthly","day1":20,"startDate":"2026-01-01"}}""".formatted(checking));
        created("/api/items", """
                {"name":"Rent","kind":"bill","amountCents":140000,"payFromAccountId":%d,
                 "schedule":{"frequency":"monthly","day1":5,"startDate":"2026-01-01"}}""".formatted(checking));

        JsonNode recurring = search("THERA", cookie).data().get("recurring");

        assertThat(recurring).hasSize(1);
        JsonNode therapy = recurring.get(0);
        assertThat(therapy.get("name").asText()).isEqualTo("Therapy");
        assertThat(therapy.get("last").get("date").asText()).isEqualTo("2026-09-20");
        assertThat(therapy.get("last").get("paid").asBoolean()).isTrue();
        assertThat(therapy.get("last").get("periodStart").asText()).isEqualTo("2026-09-15");
        assertThat(therapy.get("next").get("date").asText()).isEqualTo("2026-10-20");
        assertThat(therapy.get("next").get("amountCents").asLong()).isEqualTo(12000);
        assertThat(therapy.get("next").get("periodStart").asText()).isEqualTo("2026-10-15");
    }

    @Test
    void anItemNotStartedYetHasNoLastTime() throws Exception {
        created("/api/items", """
                {"name":"Gym","kind":"bill","amountCents":3000,"payFromAccountId":%d,
                 "schedule":{"frequency":"monthly","day1":10,"startDate":"2026-12-01"}}""".formatted(checking));

        JsonNode gym = search("gym", cookie).data().get("recurring").get(0);

        assertThat(gym.get("last").isNull()).isTrue();
        assertThat(gym.get("next").get("date").asText()).isEqualTo("2026-12-10");
    }

    @Test
    void asksForAtLeastTwoLettersAndAtMostAHundred() throws Exception {
        Reply tooShort = search(" a ", cookie);
        assertThat(tooShort.status()).isEqualTo(400);
        assertThat(tooShort.error()).isEqualTo("Please type at least 2 letters to search.");

        assertThat(search("x".repeat(101), cookie).status()).isEqualTo(400);
        assertThat(server.post("/api/search", "{}", cookie).status()).isEqualTo(400);
    }

    @Test
    void searchingIsNothingToUndo() throws Exception {
        assertThat(search("vet", cookie).changed()).isFalse();
    }
}
