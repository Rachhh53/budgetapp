package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Each pay period starts with what the one before ended on, whatever balances are entered (Rachel, 2026-10-21:
 * "previous pay period available after buckets always equals next period starting available"). A balance entered
 * later shows in its period as an adjustment, which can be changed or deleted. Checking holds $5,538.34 on Sep 1 with
 * $3,310.10 in a bucket; paychecks come on the 1st and 15th. "Today" is 2026-10-01.
 */
class PeriodChainApiTest extends ApiTestBase {

    long checking;

    @BeforeEach
    void setUp() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        createSalary(checking);
        enterBalance(checking, "2026-09-01", 553834);
        server.post("/api/buckets",
                "{\"accountId\":%d,\"name\":\"Emergency\",\"color\":\"#b45309\",\"startingCents\":331010}".formatted(checking), cookie);
    }

    private JsonNode figures(String start) throws Exception {
        JsonNode period = server.get("/api/pay-periods/" + start, cookie).data();
        return StreamSupport.stream(period.get("accounts").spliterator(), false)
                .filter(account -> account.get("accountId").asLong() == checking)
                .findFirst()
                .orElseThrow();
    }

    private void assertChained(String before, String after) throws Exception {
        JsonNode first = figures(before);
        JsonNode next = figures(after);
        assertThat(next.get("startingCents").asLong()).isEqualTo(first.get("endingCents").asLong());
        assertThat(next.get("startingAvailableCents").asLong()).isEqualTo(first.get("availableCents").asLong());
    }

    @Test
    void aBalanceEnteredOnAPeriodsFirstDayIsAnAdjustmentInIt() throws Exception {
        enterBalance(checking, "2026-09-15", 500000);

        assertChained("2026-09-01", "2026-09-15");
        JsonNode september15 = figures("2026-09-15");
        assertThat(september15.get("entered").asBoolean()).isFalse();
        JsonNode adjustment = september15.get("adjustments").get(0);
        assertThat(adjustment.get("date").asText()).isEqualTo("2026-09-15");
        assertThat(adjustment.get("balanceCents").asLong()).isEqualTo(500000);
        // Expected that morning: Sep 1's balance and paycheck.
        assertThat(adjustment.get("differenceCents").asLong()).isEqualTo(500000 - (553834 + 248620));
        assertThat(september15.get("endingCents").asLong()).isEqualTo(
                september15.get("startingCents").asLong() + september15.get("inCents").asLong() - september15.get("outCents").asLong()
                        + september15.get("adjustedCents").asLong());
    }

    @Test
    void aBalanceEnteredPartWayThroughKeepsTheChainToo() throws Exception {
        enterBalance(checking, "2026-09-20", 700000);

        assertChained("2026-09-01", "2026-09-15");
        assertChained("2026-09-15", "2026-10-01");
        assertThat(figures("2026-09-15").get("adjustments")).hasSize(1);
    }

    @Test
    void deletingTheAdjustmentRemovesTheBalance() throws Exception {
        enterBalance(checking, "2026-09-20", 700000);
        long entryId = figures("2026-09-15").get("adjustments").get(0).get("entryId").asLong();

        assertThat(server.delete("/api/accounts/" + checking + "/balances/" + entryId, cookie).status()).isEqualTo(200);

        assertThat(figures("2026-09-15").get("adjustments")).isEmpty();
        assertThat(figures("2026-09-15").get("adjustedCents").asLong()).isZero();
    }

    @Test
    void scheduledBucketMovesBeforeALaterBalanceStillCount() throws Exception {
        // A bucket filled on the 5th of every month, and a balance entered part way through September: the next
        // period must still start with everything the bucket held, however far back it was put in.
        long savings = server.post("/api/buckets", """
                {"accountId":%d,"name":"Car repairs","color":"#be185d","startingCents":10000,"openedOn":"2026-07-01"}"""
                .formatted(checking), cookie).data().get("bucket").get("id").asLong();
        created("/api/items", """
                {"name":"Fill emergency","kind":"bucket_move","amountCents":50000,"bucketId":%d,
                 "schedule":{"frequency":"monthly","day1":5,"startDate":"2026-07-05","weekendRule":"none"}}""".formatted(savings));
        enterBalance(checking, "2026-07-01", 553834);
        enterBalance(checking, "2026-09-20", 700000);

        assertChained("2026-09-01", "2026-09-15");
        assertChained("2026-09-15", "2026-10-01");
    }

    @Test
    void aCardPaymentComesToTheSameWhicheverPeriodIsShown() throws Exception {
        // Checking's balance is from Sep 1; the card's from Aug 1 and Sep 10. A payment that follows the card leaves
        // checking on Sep 13. Shown on its own, the next period used to work the payment out from the card's Sep 10
        // balance instead, and started somewhere else (Rachel, 2026-10-21: Mar 31 started $4,500.88 above Mar 13's end).
        long card = createAccount("Travel Rewards Visa", "credit_card");
        server.put("/api/cards/" + card + "/settings", "{\"statementCloseDay\":20,\"dueDay\":13}", cookie);
        enterBalance(card, "2026-08-01", 50000);
        enterBalance(card, "2026-09-10", 80000);
        created("/api/items", """
                {"name":"Visa payment","kind":"card_payment","amountCents":0,"cardPaymentMode":"statement_balance",
                 "payFromAccountId":%d,"payToAccountId":%d,"autopay":true,
                 "schedule":{"frequency":"monthly","day1":13,"startDate":"2026-07-01"}}""".formatted(checking, card));

        assertChained("2026-09-01", "2026-09-15");
        assertChained("2026-09-15", "2026-10-01");
    }

    @Test
    void needsTheAppUnlocked() throws Exception {
        assertThat(server.get("/api/pay-periods/2026-10-15", null).status()).isEqualTo(401);
    }
}
