package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** One-off entries over real HTTP against a temporary database. "Today" is 2026-10-01. */
class EntriesApiTest extends ApiTestBase {

    long checking;
    long savings;

    @BeforeEach
    void addAccounts() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        savings = createAccount("Rainy Day Savings", "savings");
    }

    private String vet() {
        return "{\"kind\":\"money_out\",\"date\":\"2026-10-03\",\"amountCents\":64000,\"description\":\"Vet emergency\",\"accountId\":%d}"
                .formatted(checking);
    }

    @Nested
    class Auth {
        @Test
        void everyEntryRouteRequiresASession() throws Exception {
            long id = created("/api/entries", vet());

            assertThat(server.get("/api/entries", null).status()).isEqualTo(401);
            assertThat(server.post("/api/entries", vet(), null).status()).isEqualTo(401);
            assertThat(server.put("/api/entries/" + id, vet().replace("64000", "1"), null).status()).isEqualTo(401);
            assertThat(server.delete("/api/entries/" + id, null).status()).isEqualTo(401);
            // Nothing was changed by any of those.
            JsonNode entries = server.get("/api/entries", cookie).data();
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).get("amountCents").asLong()).isEqualTo(64000);
        }
    }

    @Test
    void keepsTheCheckNumberOfSomethingPaidFromChecking() throws Exception {
        String byCheck = vet().replace("}", ",\"checkNumber\":\" 1042 \"}");

        Reply added = server.post("/api/entries", byCheck, cookie);
        assertThat(added.status()).isEqualTo(201);
        assertThat(added.data().get("checkNumber").asText()).isEqualTo("1042");
        long id = added.data().get("id").asLong();

        // Sent empty, it is cleared; left out, it is cleared too, as the whole entry is what was sent.
        assertThat(server.put("/api/entries/" + id, vet().replace("}", ",\"checkNumber\":\"\"}"), cookie).data().get("checkNumber").isNull())
                .isTrue();
        // A savings account has no checks, and money coming in was not paid by one.
        assertThat(server.post("/api/entries", byCheck.replace("\"accountId\":" + checking, "\"accountId\":" + savings), cookie)
                .data().get("checkNumber").isNull()).isTrue();
        assertThat(server.post("/api/entries", byCheck.replace("money_out", "money_in"), cookie).data().get("checkNumber").isNull()).isTrue();

        Reply tooLong = server.post("/api/entries", vet().replace("}", ",\"checkNumber\":\"" + "1".repeat(21) + "\"}"), cookie);
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.error()).isEqualTo("Please keep the check number to 20 characters or fewer.");
    }

    @Test
    void addsMoneyOutWithEverythingKeptForIt() throws Exception {
        long pets = created("/api/categories", "{\"name\":\"Pets\",\"color\":\"#a16207\"}");
        long tag = created("/api/tags", "{\"name\":\"Reimbursable\",\"color\":\"#475569\"}");

        Reply reply = server.post("/api/entries", """
                {"kind":"money_out","date":"2026-10-03","amountCents":64000,"estimate":true,"description":" Vet emergency ",
                 "payee":"Maple Street Vets","categoryId":%d,"accountId":%d,"note":"Luna's paw","tagIds":[%d]}"""
                .formatted(pets, checking, tag), cookie);

        assertThat(reply.status()).isEqualTo(201);
        JsonNode entry = reply.data();
        assertThat(entry.get("kind").asText()).isEqualTo("money_out");
        assertThat(entry.get("date").asText()).isEqualTo("2026-10-03");
        assertThat(entry.get("amountCents").asLong()).isEqualTo(64000);
        assertThat(entry.get("estimate").asBoolean()).isTrue();
        assertThat(entry.get("description").asText()).isEqualTo("Vet emergency");
        assertThat(entry.get("payee").asText()).isEqualTo("Maple Street Vets");
        assertThat(entry.get("categoryId").asLong()).isEqualTo(pets);
        assertThat(entry.get("accountId").asLong()).isEqualTo(checking);
        assertThat(entry.get("toAccountId").isNull()).isTrue();
        assertThat(entry.get("tagIds").get(0).asLong()).isEqualTo(tag);
    }

    @Test
    void addsMoneyInAndATransfer() throws Exception {
        Reply bonus = server.post("/api/entries",
                "{\"kind\":\"money_in\",\"date\":\"2026-10-09\",\"amountCents\":150000,\"description\":\"Q3 bonus\",\"accountId\":%d}"
                        .formatted(checking), cookie);
        Reply transfer = server.post("/api/entries", """
                {"kind":"transfer","date":"2026-10-10","amountCents":50000,"description":"Top up savings",
                 "accountId":%d,"toAccountId":%d}""".formatted(checking, savings), cookie);

        assertThat(bonus.status()).isEqualTo(201);
        assertThat(bonus.data().get("kind").asText()).isEqualTo("money_in");
        assertThat(transfer.status()).isEqualTo(201);
        assertThat(transfer.data().get("toAccountId").asLong()).isEqualTo(savings);
    }

    @Test
    void listsEntriesInDateOrderWithinTheRangeAsked() throws Exception {
        created("/api/entries", vet().replace("2026-10-03", "2026-11-20").replace("Vet emergency", "Later"));
        created("/api/entries", vet());
        created("/api/entries", vet().replace("2026-10-03", "2024-01-05").replace("Vet emergency", "Long ago"));

        JsonNode byDefault = server.get("/api/entries", cookie).data();
        JsonNode october = server.get("/api/entries?from=2026-10-01&to=2026-10-31", cookie).data();

        // By default: the last twelve months and the next twelve.
        assertThat(byDefault.findValuesAsText("description")).containsExactly("Vet emergency", "Later");
        assertThat(october.findValuesAsText("description")).containsExactly("Vet emergency");
        assertThat(server.get("/api/entries?from=2026-10-31&to=2026-10-01", cookie).status()).isEqualTo(400);
    }

    @Test
    void changesAnEntryAndReplacesItsTags() throws Exception {
        long first = created("/api/tags", "{\"name\":\"Medical\",\"color\":\"#be185d\"}");
        long second = created("/api/tags", "{\"name\":\"Tax deductible\",\"color\":\"#15803d\"}");
        long id = created("/api/entries", vet().replace("}", ",\"tagIds\":[%d]}".formatted(first)));

        Reply reply = server.put("/api/entries/" + id,
                vet().replace("64000", "58000").replace("}", ",\"tagIds\":[%d]}".formatted(second)), cookie);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.data().get("amountCents").asLong()).isEqualTo(58000);
        assertThat(reply.data().get("tagIds")).hasSize(1);
        assertThat(reply.data().get("tagIds").get(0).asLong()).isEqualTo(second);
    }

    @Test
    void deletesAnEntry() throws Exception {
        long id = created("/api/entries", vet());

        assertThat(server.delete("/api/entries/" + id, cookie).data().get("deleted").asBoolean()).isTrue();
        assertThat(server.get("/api/entries", cookie).data()).isEmpty();
    }

    @Test
    void anUnknownEntryIsNotFound() throws Exception {
        assertThat(server.put("/api/entries/999", vet(), cookie).status()).isEqualTo(404);
        assertThat(server.delete("/api/entries/999", cookie).status()).isEqualTo(404);
        assertThat(server.delete("/api/entries/abc", cookie).status()).isEqualTo(404);
    }

    @Test
    void explainsWhatIsMissingOrWrong() throws Exception {
        String base = "{\"kind\":\"%s\",\"date\":\"2026-10-03\",\"amountCents\":100,\"description\":\"Gift\",\"accountId\":%d%s}";

        assertError(base.formatted("gift", checking, ""), "Please choose money out, money in or transfer from the list.");
        assertError(vet().replace("\"date\":\"2026-10-03\",", ""), "Please choose the date.");
        assertError(vet().replace("64000", "-1"), "The amount can't be less than zero.");
        assertError(vet().replace("Vet emergency", " "), "Please add a few words about what this is.");
        assertError(base.formatted("money_out", 999, ""), "Please choose the account this comes out of.");
        assertError(base.formatted("money_in", 999, ""), "Please choose the account this money goes into.");
        assertError(base.formatted("transfer", checking, ""), "Please choose the account this money goes to.");
        assertError(base.formatted("transfer", checking, ",\"toAccountId\":" + checking), "Please choose two different accounts.");
        assertError(base.formatted("money_out", checking, ",\"categoryId\":999"), "Please choose the category from the list.");
        assertError(base.formatted("money_out", checking, ",\"tagIds\":[999]"), "Please choose tags from the list.");
        assertThat(server.post("/api/entries", "not json", cookie).status()).isEqualTo(400);
    }

    @Test
    void anAccountInUseByAnEntryCannotBeDeleted() throws Exception {
        created("/api/entries", vet());

        Reply reply = server.delete("/api/accounts/" + checking, cookie);

        assertThat(reply.status()).isEqualTo(409);
        assertThat(reply.error()).startsWith("This account is still used by a recurring item or a one-off entry.");
    }

    private void assertError(String body, String message) throws Exception {
        Reply reply = server.post("/api/entries", body, cookie);
        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.error()).isEqualTo(message);
    }
}
