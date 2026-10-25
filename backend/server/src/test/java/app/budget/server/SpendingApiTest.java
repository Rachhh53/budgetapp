package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What went out of an account each pay period, by category: the bars under its chart (Rachel, 2026-10-24). */
class SpendingApiTest extends ApiTestBase {

    long checking;
    long savings;
    long housing;
    long food;

    @BeforeEach
    void setUp() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        savings = createAccount("Rainy Day Savings", "savings");
        enterBalance(checking, "2026-09-01", 500000);
        enterBalance(savings, "2026-09-01", 100000);
        createSalary(checking);
        housing = created("/api/categories", "{\"name\":\"Housing\",\"color\":\"#b45309\"}");
        food = created("/api/categories", "{\"name\":\"Food\",\"color\":\"#0f766e\"}");
        created("/api/items", """
                {"name":"Rent","kind":"bill","amountCents":160000,"payFromAccountId":%d,"categoryId":%d,"autopay":true,
                 "schedule":{"frequency":"monthly","day1":8,"startDate":"2026-01-01"}}""".formatted(checking, housing));
        created("/api/entries", """
                {"kind":"money_out","date":"2026-09-21","amountCents":6400,"description":"Market","accountId":%d,"categoryId":%d}"""
                .formatted(checking, food));
        created("/api/entries", """
                {"kind":"money_out","date":"2026-09-22","amountCents":2500,"description":"Parking","accountId":%d}""".formatted(checking));
        created("/api/entries", """
                {"kind":"transfer","date":"2026-09-23","amountCents":10000,"description":"To savings","accountId":%d,"toAccountId":%d}"""
                .formatted(checking, savings));
    }

    private JsonNode september(long account) throws Exception {
        Reply reply = server.get("/api/accounts/" + account + "/spending?from=2026-09-01&to=2026-09-30", cookie);
        assertThat(reply.status()).as(reply.body().toString()).isEqualTo(200);
        return reply.data();
    }

    @Test
    void everythingThatWentOutIsAddedUpByCategoryEachPayPeriodBiggestFirst() throws Exception {
        JsonNode periods = september(checking);

        assertThat(periods).hasSize(2);
        JsonNode first = periods.get(0);
        assertThat(first.get("start").asText()).isEqualTo("2026-09-01");
        assertThat(first.get("end").asText()).isEqualTo("2026-09-14");
        assertThat(first.get("outCents").asLong()).isEqualTo(160000);
        assertThat(first.get("categories")).hasSize(1);
        assertThat(first.get("categories").get(0).get("categoryId").asLong()).isEqualTo(housing);
        // The paycheck came in, so it is not counted; a transfer out is, with what has no category.
        JsonNode second = periods.get(1);
        assertThat(second.get("outCents").asLong()).isEqualTo(18900);
        assertThat(second.get("categories").get(0).get("categoryId").isNull()).isTrue();
        assertThat(second.get("categories").get(0).get("cents").asLong()).isEqualTo(12500);
        assertThat(second.get("categories").get(1).get("categoryId").asLong()).isEqualTo(food);
        assertThat(second.get("categories").get(1).get("cents").asLong()).isEqualTo(6400);
    }

    @Test
    void moneyArrivingIsNotSpending() throws Exception {
        JsonNode periods = september(savings);

        assertThat(periods).hasSize(2);
        assertThat(periods.findValues("outCents").stream().map(JsonNode::asLong)).containsOnly(0L);
    }

    @Test
    void onACardItIsWhatWasChargedNotWhatPaidItDown() throws Exception {
        long visa = createAccount("Visa", "credit_card");
        enterBalance(visa, "2026-09-01", 0);
        created("/api/entries", """
                {"kind":"money_out","date":"2026-09-20","amountCents":4200,"description":"Groceries","accountId":%d,"categoryId":%d}"""
                .formatted(visa, food));
        created("/api/entries", """
                {"kind":"transfer","date":"2026-09-24","amountCents":4200,"description":"Pay the Visa","accountId":%d,"toAccountId":%d}"""
                .formatted(checking, visa));

        JsonNode periods = september(visa);

        // Rachel, 2026-10-24: "it needs to be on THOSE too". The charge counts; the payment toward the card does not.
        assertThat(periods).hasSize(2);
        assertThat(periods.get(0).get("outCents").asLong()).isZero();
        assertThat(periods.get(1).get("outCents").asLong()).isEqualTo(4200);
        assertThat(periods.get(1).get("categories").get(0).get("categoryId").asLong()).isEqualTo(food);
    }

    @Test
    void aLoanHasNoneSinceGoingOutMeansSomethingElseThere() throws Exception {
        long loan = createAccount("Car loan", "loan");

        assertThat(september(loan)).isEmpty();
    }

    @Test
    void anAccountThatDoesNotExistOrARangeThatMakesNoSenseIsRefused() throws Exception {
        assertThat(server.get("/api/accounts/9999/spending", cookie).status()).isEqualTo(404);
        assertThat(server.get("/api/accounts/" + checking + "/spending?from=2027-01-01&to=2026-01-01", cookie).status()).isEqualTo(400);
    }

    @Test
    void needsASession() throws Exception {
        assertThat(server.get("/api/accounts/" + checking + "/spending", null).status()).isEqualTo(401);
    }
}
