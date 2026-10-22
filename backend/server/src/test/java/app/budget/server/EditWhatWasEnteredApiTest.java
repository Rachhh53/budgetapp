package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Changing what was entered in place (Rachel, 2026-10-21: "I want the ability to edit basically everything I enter.
 * namely statements in accounts. also where's my recon and difference in accounts?"): card statements, money put into
 * or taken out of a bucket by hand and amounts set for a day, and what the records came to beside each balance
 * entered. "Today" is 2026-10-01.
 */
class EditWhatWasEnteredApiTest extends ApiTestBase {

    private static List<JsonNode> list(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).toList();
    }

    private static void assertError(Reply reply, int status, String message) {
        assertThat(reply.status()).isEqualTo(status);
        assertThat(reply.error()).isEqualTo(message);
    }

    @Nested
    class Statements {
        long card;
        long statement;

        @BeforeEach
        void aCardWithAStatement() throws Exception {
            card = createAccount("Visa", "credit_card");
            server.put("/api/cards/" + card + "/settings", "{\"statementCloseDay\":20,\"dueDay\":13}", cookie);
            JsonNode statements = server.post("/api/cards/" + card + "/statements",
                    "{\"closeDate\":\"2026-09-20\",\"balanceCents\":51230,\"minimumDueCents\":3500}", cookie).data().get("statements");
            statement = closedOn(statements, "2026-09-20").get("id").asLong();
        }

        private JsonNode closedOn(JsonNode statements, String date) {
            return list(statements).stream().filter(one -> one.get("closeDate").asText().equals(date)).findFirst().orElseThrow();
        }

        private Reply change(long id, String body) throws Exception {
            return server.put("/api/cards/" + card + "/statements/" + id, body, cookie);
        }

        @Test
        void itsNumbersAndDatesChangeInPlace() throws Exception {
            Reply changed = change(statement,
                    "{\"closeDate\":\"2026-09-19\",\"balanceCents\":51320,\"minimumDueCents\":3600,\"dueDate\":\"2026-10-15\"}");

            assertThat(changed.status()).isEqualTo(200);
            JsonNode moved = closedOn(changed.data().get("statements"), "2026-09-19");
            // The same statement, so what is attached to it stays with it.
            assertThat(moved.get("id").asLong()).isEqualTo(statement);
            assertThat(moved.get("balanceCents").asLong()).isEqualTo(51320);
            assertThat(moved.get("minimumDueCents").asLong()).isEqualTo(3600);
            assertThat(moved.get("dueDate").asText()).isEqualTo("2026-10-15");
            assertThat(list(changed.data().get("statements"))).noneMatch(one -> one.get("closeDate").asText().equals("2026-09-20"));
        }

        @Test
        void aDayAnotherStatementClosedIsRefused() throws Exception {
            server.post("/api/cards/" + card + "/statements", "{\"closeDate\":\"2026-08-20\",\"balanceCents\":20000}", cookie);

            assertError(change(statement, "{\"closeDate\":\"2026-08-20\",\"balanceCents\":51230}"), 409,
                    "There's already a statement that closed that day. Please change that one, or choose another date.");
        }

        @Test
        void isCheckedAsWhenItWasAdded() throws Exception {
            assertError(change(statement, "{\"closeDate\":\"2026-09-20\",\"balanceCents\":51230,\"dueDate\":\"2026-09-10\"}"), 400,
                    "A statement is due after it closes. Please check the two dates.");
            assertThat(change(999, "{\"closeDate\":\"2026-09-20\",\"balanceCents\":1}").status()).isEqualTo(404);
        }

        @Test
        void needsTheAppUnlocked() throws Exception {
            assertThat(server.put("/api/cards/" + card + "/statements/" + statement,
                    "{\"closeDate\":\"2026-09-20\",\"balanceCents\":1}", null).status()).isEqualTo(401);
        }
    }

    @Nested
    class BucketChanges {
        long checking;
        long gifts;

        @BeforeEach
        void aBucketHoldingSomething() throws Exception {
            checking = createAccount("Everyday Checking", "checking");
            enterBalance(checking, "2026-09-01", 121438);
            gifts = server.post("/api/buckets",
                    "{\"accountId\":%d,\"name\":\"Holiday gifts\",\"color\":\"#b45309\",\"startingCents\":15000}".formatted(checking),
                    cookie).data().get("bucket").get("id").asLong();
        }

        private JsonNode changes() throws Exception {
            return server.get("/api/buckets/" + gifts, cookie).data().get("changes");
        }

        private long newest() throws Exception {
            return changes().get(0).get("id").asLong();
        }

        private Reply change(long id, String body) throws Exception {
            return server.put("/api/buckets/" + gifts + "/changes/" + id, body, cookie);
        }

        @Test
        void moneyPutInByHandChangesItsAmountDateAndNote() throws Exception {
            server.post("/api/buckets/" + gifts + "/changes", "{\"date\":\"2026-10-01\",\"amountCents\":5000,\"note\":\"Birthday\"}", cookie);

            Reply changed = change(newest(), "{\"date\":\"2026-09-28\",\"amountCents\":7500,\"note\":\"Birthday money\"}");

            assertThat(changed.status()).isEqualTo(200);
            assertThat(changed.data().get("bucket").get("balanceCents").asLong()).isEqualTo(22500);
            JsonNode edited = list(changed.data().get("changes")).stream()
                    .filter(one -> one.get("amountCents").asLong() == 7500).findFirst().orElseThrow();
            assertThat(edited.get("date").asText()).isEqualTo("2026-09-28");
            assertThat(edited.get("note").asText()).isEqualTo("Birthday money");
            assertThat(edited.get("movedOut").asBoolean()).isFalse();
        }

        @Test
        void anAmountSetChangesWhatItIsSetTo() throws Exception {
            server.put("/api/buckets/" + gifts + "/held", "{\"date\":\"2026-10-01\",\"amountCents\":30000}", cookie);

            Reply changed = change(newest(), "{\"date\":\"2026-10-01\",\"amountCents\":25000}");

            assertThat(changed.status()).isEqualTo(200);
            assertThat(changed.data().get("changes").get(0).get("setToCents").asLong()).isEqualTo(25000);
            assertThat(changed.data().get("bucket").get("balanceCents").asLong()).isEqualTo(25000);
        }

        @Test
        void aDayAnotherAmountIsSetForIsRefused() throws Exception {
            server.put("/api/buckets/" + gifts + "/held", "{\"date\":\"2026-09-30\",\"amountCents\":20000}", cookie);
            server.put("/api/buckets/" + gifts + "/held", "{\"date\":\"2026-10-01\",\"amountCents\":30000}", cookie);

            assertError(change(newest(), "{\"date\":\"2026-09-30\",\"amountCents\":30000}"), 409,
                    "There's already an amount set for that day. Please change that one, or choose another date.");
        }

        @Test
        void moneyMovedOutToSpendIsChangedAsAMoveOut() throws Exception {
            server.post("/api/buckets/" + gifts + "/moves-out", "{\"date\":\"2026-10-01\",\"amountCents\":2000,\"note\":\"Gift wrap\"}", cookie);
            JsonNode moved = list(changes()).stream().filter(one -> one.get("movedOut").asBoolean()).findFirst().orElseThrow();

            assertThat(change(moved.get("id").asLong(), "{\"date\":\"2026-10-01\",\"amountCents\":-1000}").status()).isEqualTo(404);
            assertThat(change(999, "{\"date\":\"2026-10-01\",\"amountCents\":1000}").status()).isEqualTo(404);
        }

        @Test
        void isCheckedAsWhenItWasAdded() throws Exception {
            long starting = newest();

            assertError(change(starting, "{\"amountCents\":500}"), 400, "Please choose the date.");
            assertError(change(starting, "{\"date\":\"2026-10-01\",\"amountCents\":0}"), 400,
                    "Please enter how much to put in or take out.");
        }

        @Test
        void needsTheAppUnlocked() throws Exception {
            assertThat(server.put("/api/buckets/" + gifts + "/changes/" + newest(), "{\"date\":\"2026-10-01\",\"amountCents\":1}", null)
                    .status()).isEqualTo(401);
        }
    }

    @Test
    void eachBalanceEnteredSaysWhatTheRecordsCameTo() throws Exception {
        long checking = createAccount("Everyday Checking", "checking");
        enterBalance(checking, "2026-09-01", 100000);
        created("/api/entries",
                "{\"kind\":\"money_out\",\"date\":\"2026-09-05\",\"amountCents\":10000,\"description\":\"Groceries\",\"accountId\":%d}"
                        .formatted(checking));
        enterBalance(checking, "2026-09-10", 89000);

        List<JsonNode> balances = list(server.get("/api/accounts/" + checking + "/performance", cookie).data().get("balances"));

        JsonNode later = balances.stream().filter(one -> one.get("date").asText().equals("2026-09-10")).findFirst().orElseThrow();
        // $1,000.00 less the $100.00 of groceries: $10.00 more than the $890.00 entered.
        assertThat(later.get("recordsCents").asLong()).isEqualTo(90000);
    }
}
