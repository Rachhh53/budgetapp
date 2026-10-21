package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Typing the real amount over an estimated payment moves what is available after buckets (Rachel, 2026-10-21: "I
 * just updated an estimated recurring payment in a pay period to a real amount and the available after buckets
 * amount didn't update"). Checking holds $5,538.34 on Oct 1 with $3,310.10 set in a bucket; electric is an estimated
 * $150.00 on the 15th, the first day of a pay period, the day its paycheck comes in. "Today" is 2026-10-01.
 */
class AvailableRecalcApiTest extends ApiTestBase {

    long checking;
    long savingsBucket;
    long electric;

    @BeforeEach
    void setUp() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        createSalary(checking);
        enterBalance(checking, "2026-10-01", 553834);
        savingsBucket = server.post("/api/buckets",
                "{\"accountId\":%d,\"name\":\"Emergency\",\"color\":\"#b45309\",\"startingCents\":331010}".formatted(checking), cookie)
                .data().get("bucket").get("id").asLong();
        electric = created("/api/items", """
                {"name":"Electric","kind":"bill","amountCents":15000,"estimate":true,"payFromAccountId":%d,
                 "schedule":{"frequency":"monthly","day1":15,"startDate":"2026-01-15","weekendRule":"none"}}""".formatted(checking));
    }

    private JsonNode figures() throws Exception {
        JsonNode period = server.get("/api/pay-periods/2026-10-15", cookie).data();
        return StreamSupport.stream(period.get("accounts").spliterator(), false)
                .filter(account -> account.get("accountId").asLong() == checking)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void theRealAmountMovesTheEndingAndWhatIsAvailable() throws Exception {
        JsonNode before = figures();

        assertThat(server.put("/api/items/" + electric + "/occurrences/2026-10-15", "{\"amountCents\":17312}", cookie).status())
                .isEqualTo(200);

        JsonNode after = figures();
        assertThat(after.get("endingCents").asLong()).isEqualTo(before.get("endingCents").asLong() - 2312);
        assertThat(after.get("availableCents").asLong()).isEqualTo(before.get("availableCents").asLong() - 2312);
    }

    @Test
    void soItDoesWhenTheBucketAmountWasSetForADay() throws Exception {
        server.put("/api/buckets/" + savingsBucket + "/held", "{\"date\":\"2026-10-01\",\"amountCents\":331010}", cookie);
        JsonNode before = figures();

        server.put("/api/items/" + electric + "/occurrences/2026-10-15", "{\"amountCents\":17312}", cookie);

        assertThat(figures().get("availableCents").asLong()).isEqualTo(before.get("availableCents").asLong() - 2312);
    }

    @Test
    void soItDoesWhenTheRealAmountIsMarkedPaid() throws Exception {
        JsonNode before = figures();

        server.put("/api/items/" + electric + "/occurrences/2026-10-15", "{\"amountCents\":17312,\"paid\":true}", cookie);

        assertThat(figures().get("availableCents").asLong()).isEqualTo(before.get("availableCents").asLong() - 2312);
    }
}
