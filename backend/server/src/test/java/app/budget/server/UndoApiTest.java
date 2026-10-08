package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import app.budget.server.data.DataRoutes;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Undo over real HTTP against a temporary database. "Today" is Thursday 2026-10-01. */
class UndoApiTest extends ApiTestBase {

    private static final String MONTHLY_5TH = "{\"frequency\":\"monthly\",\"day1\":5,\"startDate\":\"2026-01-01\"}";

    long checking;

    @BeforeEach
    void addAnAccount() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
    }

    private String vet(long cents) {
        return "{\"kind\":\"money_out\",\"date\":\"2026-10-03\",\"amountCents\":%d,\"description\":\"Vet emergency\",\"accountId\":%d}"
                .formatted(cents, checking);
    }

    private Reply undo() throws Exception {
        return server.post("/api/undo", null, cookie);
    }

    @Test
    void undoRequiresASession() throws Exception {
        created("/api/entries", vet(64000));

        assertThat(server.get("/api/undo", null).status()).isEqualTo(401);
        assertThat(server.post("/api/undo", null, null).status()).isEqualTo(401);
        // Nothing was undone by that.
        assertThat(server.get("/api/entries", cookie).data()).hasSize(1);
    }

    @Test
    void aChangeSaysItCanBeUndoneAndAReadDoesNot() throws Exception {
        assertThat(server.post("/api/entries", vet(64000), cookie).changed()).isTrue();
        assertThat(server.get("/api/entries", cookie).changed()).isFalse();
        assertThat(server.get("/api/undo", cookie).data().get("available").asBoolean()).isTrue();
    }

    @Test
    void aRefusedRequestLeavesNothingToUndo() throws Exception {
        Reply refused = server.post("/api/entries", vet(-5), cookie);

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.changed()).isFalse();
        // The only change on record is the account from setup: one undo and there is nothing left.
        undo();
        assertThat(server.get("/api/undo", cookie).data().get("available").asBoolean()).isFalse();
        assertThat(server.get("/api/accounts", cookie).data()).isEmpty();
    }

    @Test
    void undoTakesBackAnAddedEntry() throws Exception {
        created("/api/entries", vet(64000));

        Reply reply = undo();

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.data().get("undone").asBoolean()).isTrue();
        assertThat(server.get("/api/entries", cookie).data()).isEmpty();
    }

    @Test
    void undoPutsBackWhatAnEditReplaced() throws Exception {
        long id = created("/api/entries", vet(64000));
        server.put("/api/entries/" + id, vet(70000), cookie);

        undo();

        assertThat(server.get("/api/entries", cookie).data().get(0).get("amountCents").asLong()).isEqualTo(64000);
    }

    @Test
    void undoBringsBackADeletedEntryWithItsTags() throws Exception {
        long tag = created("/api/tags", "{\"name\":\"Reimbursable\",\"color\":\"#475569\"}");
        long id = created("/api/entries", vet(64000).replace("}", ",\"tagIds\":[" + tag + "]}"));
        server.delete("/api/entries/" + id, cookie);
        assertThat(server.get("/api/entries", cookie).data()).isEmpty();

        undo();

        JsonNode entry = server.get("/api/entries", cookie).data().get(0);
        assertThat(entry.get("id").asLong()).isEqualTo(id);
        assertThat(entry.get("description").asText()).isEqualTo("Vet emergency");
        assertThat(entry.get("tagIds").get(0).asLong()).isEqualTo(tag);
    }

    @Test
    void undoBringsBackADeletedItemWithItsHistoryAndWhatWasMarkedPaid() throws Exception {
        createSalary(checking);
        long id = created("/api/items",
                "{\"name\":\"Car loan\",\"kind\":\"bill\",\"amountCents\":41200,\"payFromAccountId\":%d,\"schedule\":%s}"
                        .formatted(checking, MONTHLY_5TH));
        server.put("/api/items/" + id, """
                {"name":"Car loan","kind":"bill","amountCents":43000,"payFromAccountId":%d,"schedule":%s,"effectiveFrom":"2026-11-01"}"""
                .formatted(checking, MONTHLY_5TH), cookie);
        server.put("/api/items/" + id + "/occurrences/2026-10-05", "{\"paid\":true,\"confirmationCode\":\"VX93-2210\"}", cookie);
        server.delete("/api/items/" + id, cookie);
        assertThat(server.get("/api/items", cookie).data()).isEmpty();

        undo();

        JsonNode item = server.get("/api/items/" + id, cookie).data();
        assertThat(item.get("name").asText()).isEqualTo("Car loan");
        assertThat(item.get("history")).hasSize(2);
        JsonNode october = server.get("/api/pay-periods/2026-10-01", cookie).data().get("occurrences").get(0);
        assertThat(october.get("paid").asBoolean()).isTrue();
        assertThat(october.get("confirmationCode").asText()).isEqualTo("VX93-2210");
    }

    @Test
    void undoUnticksABillThatWasMarkedPaid() throws Exception {
        createSalary(checking);
        long id = created("/api/items",
                "{\"name\":\"Electric\",\"kind\":\"bill\",\"amountCents\":14000,\"payFromAccountId\":%d,\"schedule\":%s}"
                        .formatted(checking, MONTHLY_5TH));
        server.put("/api/items/" + id + "/occurrences/2026-10-05", "{\"paid\":true}", cookie);

        undo();

        JsonNode october = server.get("/api/pay-periods/2026-10-01", cookie).data().get("occurrences").get(0);
        assertThat(october.get("paid").asBoolean()).isFalse();
    }

    @Test
    void eachUndoStepsBackOneMoreChange() throws Exception {
        long id = created("/api/entries", vet(64000));
        server.put("/api/entries/" + id, vet(70000), cookie);
        server.put("/api/entries/" + id, vet(80000), cookie);

        assertThat(undo().data().get("available").asBoolean()).isTrue();
        assertThat(server.get("/api/entries", cookie).data().get(0).get("amountCents").asLong()).isEqualTo(70000);
        undo();
        assertThat(server.get("/api/entries", cookie).data().get(0).get("amountCents").asLong()).isEqualTo(64000);
        undo();
        assertThat(server.get("/api/entries", cookie).data()).isEmpty();
    }

    @Test
    void anUndoIsNotItselfSomethingToUndo() throws Exception {
        // Setup added the account: one change. Undoing it leaves nothing.
        Reply reply = undo();

        assertThat(reply.changed()).isFalse();
        assertThat(reply.data().get("available").asBoolean()).isFalse();
        assertThat(server.get("/api/accounts", cookie).data()).isEmpty();
    }

    @Test
    void withNothingToUndoItSaysSoKindly() throws Exception {
        undo();

        Reply reply = undo();

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.data().get("undone").asBoolean()).isFalse();
    }

    @Test
    void undoCoversSettingsAccountsAndBalances() throws Exception {
        enterBalance(checking, "2026-10-01", 121438);
        server.put("/api/accounts/" + checking,
                "{\"name\":\"Bills Checking\",\"type\":\"checking\",\"color\":\"#0f766e\"}", cookie);

        undo();
        assertThat(server.get("/api/accounts/" + checking, cookie).data().get("account").get("name").asText())
                .isEqualTo("Everyday Checking");
        undo();
        assertThat(server.get("/api/accounts/" + checking, cookie).data().get("balances")).isEmpty();
    }

    @Test
    void erasingAllDataCannotBeUndoneAndUndoStillWorksAfterwards() throws Exception {
        created("/api/entries", vet(64000));
        server.send("DELETE", "/api/data", "{\"confirm\":\"" + DataRoutes.CONFIRMATION + "\"}", Map.of("Cookie", cookie));
        cookie = server.unlock();

        assertThat(undo().data().get("undone").asBoolean()).isFalse();
        assertThat(server.get("/api/accounts", cookie).data()).isEmpty();

        // The rebuilt database records changes again.
        long account = createAccount("Fresh Start Checking", "checking");
        assertThat(account).isPositive();
        undo();
        assertThat(server.get("/api/accounts", cookie).data()).isEmpty();
    }
}
