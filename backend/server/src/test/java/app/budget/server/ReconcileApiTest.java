package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

/**
 * Reconciling what was entered against the records behind it (Rachel, 2026-10-21: "when I enter a statement balance for
 * an account and there is discrepancy … just make that visible … so I could adjust or add/delete things"). Each line
 * of an account's transactions says what it came from; a balance entered says since when to look; an entered card
 * statement says what the records came to. "Today" is 2026-10-01.
 */
class ReconcileApiTest extends ApiTestBase {

    private static List<JsonNode> list(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).toList();
    }

    @Test
    void eachLineSaysWhatItCameFromAndWhichPayPeriodListsIt() throws Exception {
        long checking = createAccount("Everyday Checking", "checking");
        createSalary(checking);
        enterBalance(checking, "2026-09-01", 553834);
        long groceries = created("/api/entries",
                "{\"kind\":\"money_out\",\"date\":\"2026-09-05\",\"amountCents\":10000,\"description\":\"Groceries\",\"accountId\":%d}"
                        .formatted(checking));

        List<JsonNode> lines = list(server.get(
                "/api/accounts/" + checking + "/transactions?from=2026-09-01&to=2026-09-09", cookie).data());

        JsonNode spent = lines.stream().filter(line -> line.get("label").asText().equals("Groceries")).findFirst().orElseThrow();
        assertThat(spent.get("source").get("type").asText()).isEqualTo("entry");
        assertThat(spent.get("source").get("entryId").asLong()).isEqualTo(groceries);
        assertThat(spent.get("source").get("periodStart").asText()).isEqualTo("2026-09-01");
        JsonNode paid = lines.stream().filter(line -> line.get("kind").asText().equals("paycheck")).findFirst().orElseThrow();
        assertThat(paid.get("source").get("type").asText()).isEqualTo("paycheck");
        assertThat(paid.get("source").get("scheduledDate").asText()).isEqualTo("2026-09-01");
        JsonNode balance = lines.stream().filter(line -> line.get("kind").asText().equals("balance")).findFirst().orElseThrow();
        assertThat(balance.get("source").isNull()).isTrue();
    }

    @Test
    void aBalanceEnteredSaysSinceWhenTheRecordsMatched() throws Exception {
        long checking = createAccount("Everyday Checking", "checking");
        createSalary(checking);
        enterBalance(checking, "2026-09-01", 553834);
        enterBalance(checking, "2026-09-10", 790000);

        JsonNode adjustment = StreamSupport.stream(server.get("/api/pay-periods/2026-09-01", cookie).data().get("accounts").spliterator(), false)
                .filter(account -> account.get("accountId").asLong() == checking)
                .findFirst().orElseThrow()
                .get("adjustments").get(0);

        assertThat(adjustment.get("since").asText()).isEqualTo("2026-09-01");
        // Expected Sep 10: Sep 1's balance and paycheck.
        assertThat(adjustment.get("differenceCents").asLong()).isEqualTo(790000 - (553834 + 248620));
    }

    @Test
    void anEnteredStatementSaysWhatTheRecordsCameToAndSinceWhen() throws Exception {
        long card = createAccount("Visa", "credit_card");
        server.put("/api/cards/" + card + "/settings", "{\"statementCloseDay\":20,\"dueDay\":13}", cookie);
        enterBalance(card, "2026-08-21", 0);
        created("/api/entries",
                "{\"kind\":\"money_out\",\"date\":\"2026-08-25\",\"amountCents\":6000,\"description\":\"Gas\",\"accountId\":%d}".formatted(card));
        // August matched; September is $8.00 more than the records.
        server.post("/api/cards/" + card + "/statements", "{\"closeDate\":\"2026-08-20\",\"balanceCents\":0}", cookie);
        created("/api/entries",
                "{\"kind\":\"money_out\",\"date\":\"2026-09-02\",\"amountCents\":4000,\"description\":\"Lunch\",\"accountId\":%d}".formatted(card));
        JsonNode statements = server.post("/api/cards/" + card + "/statements",
                "{\"closeDate\":\"2026-09-20\",\"balanceCents\":10800}", cookie).data().get("statements");

        JsonNode september = list(statements).stream()
                .filter(statement -> statement.get("closeDate").asText().equals("2026-09-20")).findFirst().orElseThrow();
        assertThat(september.get("recordsCents").asLong()).isEqualTo(10000);
        assertThat(september.get("reconcileFrom").asText()).isEqualTo("2026-08-21");
        JsonNode august = list(statements).stream()
                .filter(statement -> statement.get("closeDate").asText().equals("2026-08-20")).findFirst().orElseThrow();
        assertThat(august.get("reconcileFrom").isNull()).isTrue();
    }

    @Test
    void needsTheAppUnlocked() throws Exception {
        long checking = createAccount("Everyday Checking", "checking");
        assertThat(server.get("/api/accounts/" + checking + "/transactions?from=2026-09-01&to=2026-09-09", null).status()).isEqualTo(401);
    }
}
