package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A card's own payment, following its statements, in the months before anything was entered for the card (Rachel,
 * 2026-10-22: "I set that up as recurring and it shows the statement balance for a specific month on every month
 * before that - it should be 0"). The card closes on the 20th and is due on the 13th; its payment, on the 13th since
 * January, was saved with the September statement's $512.30 typed in. "Today" is 2026-10-01.
 */
class CardPaymentBeforeStatementsApiTest extends ApiTestBase {

    long checking;
    long card;

    @BeforeEach
    void aCardWithItsPayment() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        card = createAccount("Travel Rewards Visa", "credit_card");
        createSalary(checking);
        enterBalance(checking, "2026-01-01", 300000);
        server.put("/api/cards/" + card + "/settings", "{\"statementCloseDay\":20,\"dueDay\":13}", cookie);
        created("/api/items", """
                {"name":"Visa payment","kind":"card_payment","amountCents":51230,"cardPaymentMode":"statement_balance",
                 "payFromAccountId":%d,"payToAccountId":%d,"autopay":true,
                 "schedule":{"frequency":"monthly","day1":13,"startDate":"2026-01-01"}}""".formatted(checking, card));
        server.post("/api/cards/" + card + "/statements", "{\"closeDate\":\"2026-09-20\",\"balanceCents\":51230}", cookie);
    }

    private List<JsonNode> payments(String periodStart) throws Exception {
        Reply reply = server.get("/api/pay-periods/" + periodStart, cookie);
        assertThat(reply.status()).isEqualTo(200);
        return StreamSupport.stream(reply.data().get("occurrences").spliterator(), false)
                .filter(occurrence -> occurrence.get("name").asText().equals("Visa payment"))
                .toList();
    }

    @Test
    void theStatementEnteredIsPaidWhenItIsDue() throws Exception {
        assertThat(payments("2026-10-01")).singleElement()
                .satisfies(payment -> assertThat(payment.get("amountCents").asLong()).isEqualTo(51230));
    }

    @Test
    void beforeAnythingWasEnteredForTheCardNothingIsPaid() throws Exception {
        assertThat(payments("2026-06-01")).isEmpty();
        assertThat(payments("2026-09-01")).isEmpty();
    }

    @Test
    void beforeTheFirstBalanceEnteredNothingIsPaidEither() throws Exception {
        enterBalance(card, "2026-09-21", 51230);

        assertThat(payments("2026-06-01")).isEmpty();
        assertThat(payments("2026-10-01")).singleElement()
                .satisfies(payment -> assertThat(payment.get("amountCents").asLong()).isEqualTo(51230));
    }
}
