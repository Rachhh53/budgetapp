package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A balance entered is what its day ended with (Rachel, 2026-10-30: "balances should always be end of day"; "bc on pay
 * period should be starting + pay check - payments that day + bucket amounts + adjustment in balance on account THAT DAY
 * = real account balance"). The pay period still starts as its first day begins. Checking held $4,224.89 as Oct 1 began,
 * with $933.14 set aside in a bucket; paychecks of $2,486.20 come on the 1st and 15th. "Today" is 2026-10-01, a payday.
 */
class EndOfDayBalanceApiTest extends ApiTestBase {

    long checking;
    long bucket;

    @BeforeEach
    void setUp() throws Exception {
        checking = createAccount("North Country Checking", "checking");
        createSalary(checking);
        enterBalance(checking, "2026-09-30", 422489);
        Reply reply = server.post("/api/buckets",
                "{\"accountId\":%d,\"name\":\"Heat Savings\",\"color\":\"#0f766e\",\"startingCents\":93314}".formatted(checking), cookie);
        bucket = reply.data().get("bucket").get("id").asLong();
    }

    private JsonNode figures() throws Exception {
        JsonNode period = server.get("/api/pay-periods/2026-10-01", cookie).data();
        return StreamSupport.stream(period.get("accounts").spliterator(), false)
                .filter(account -> account.get("accountId").asLong() == checking)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aBalanceEnteredOnPaydayAlreadyHoldsThePaycheck() throws Exception {
        enterBalance(checking, "2026-10-01", 422489 + 248620 - 477);

        JsonNode account = figures();
        // The period starts as the day begins; the balance is set against starting + paycheck.
        assertThat(account.get("startingCents").asLong()).isEqualTo(422489);
        JsonNode adjustment = account.get("adjustments").get(0);
        assertThat(adjustment.get("date").asText()).isEqualTo("2026-10-01");
        assertThat(adjustment.get("differenceCents").asLong()).isEqualTo(-477);
        assertThat(account.get("endingCents").asLong()).isEqualTo(422489 + 248620 - 477);
        // And the account screen's balance today is the one entered.
        JsonNode today = StreamSupport.stream(server.get("/api/account-balances", cookie).data().spliterator(), false)
                .filter(balance -> balance.get("accountId").asLong() == checking)
                .findFirst()
                .orElseThrow();
        assertThat(today.get("balanceCents").asLong()).isEqualTo(422489 + 248620 - 477);
    }

    @Test
    void moneySetAsideIntoTheAccountsOwnBucketThatDayChangesNothing() throws Exception {
        Reply reply = server.post("/api/items", """
                {"name":"Heat Savings","kind":"bucket_move","amountCents":5000,"bucketId":%d,
                 "schedule":{"frequency":"every_pay_period","startDate":"2026-01-01"}}""".formatted(bucket), cookie);
        assertThat(reply.status()).as(reply.body().toString()).isEqualTo(201);

        enterBalance(checking, "2026-10-01", 422489 + 248620);

        JsonNode account = figures();
        assertThat(account.get("adjustments").get(0).get("differenceCents").asLong()).isZero();
        // The set-aside only moves money within the account: less is available, the balance is the same.
        assertThat(account.get("availableCents").asLong()).isEqualTo(account.get("endingCents").asLong() - account.get("bucketsCents").asLong());
        assertThat(account.get("endingCents").asLong()).isEqualTo(422489 + 248620);
    }

    @Test
    void aBillPaidByHandAndNotTickedOffThatDayStillComesAfterIt() throws Exception {
        created("/api/items", """
                {"name":"Personal Training","kind":"bill","amountCents":12750,"payFromAccountId":%d,
                 "schedule":{"frequency":"monthly","day1":1,"startDate":"2026-01-01"}}""".formatted(checking));

        enterBalance(checking, "2026-10-01", 422489 + 248620);

        JsonNode account = figures();
        // Not gone when the day ended, so the balance matches; the bill still leaves after it.
        assertThat(account.get("adjustments").get(0).get("differenceCents").asLong()).isZero();
        assertThat(account.get("endingCents").asLong()).isEqualTo(422489 + 248620 - 12750);
    }
}
