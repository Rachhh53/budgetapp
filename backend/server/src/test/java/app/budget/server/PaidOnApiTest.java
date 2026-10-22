package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A bill ticked paid belongs to the day it was paid, not the day it was due (Rachel, 2026-10-22: "it says paid march
 * 15 though - why didn't that move it to the correct pay period? ... it gets paid when it's paid not when it's due").
 * Paychecks on the 1st and 15th; Electric is $140.00 due on the 20th. "Today" is 2026-10-01.
 */
class PaidOnApiTest extends ApiTestBase {

    long checking;
    long electric;

    @BeforeEach
    void aBill() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        createSalary(checking);
        enterBalance(checking, "2026-09-01", 300000);
        electric = created("/api/items", """
                {"name":"Electric","kind":"bill","amountCents":14000,"payFromAccountId":%d,
                 "schedule":{"frequency":"monthly","day1":20,"startDate":"2026-01-01"}}""".formatted(checking));
    }

    private List<JsonNode> electricIn(String periodStart) throws Exception {
        Reply reply = server.get("/api/pay-periods/" + periodStart, cookie);
        assertThat(reply.status()).isEqualTo(200);
        return StreamSupport.stream(reply.data().get("occurrences").spliterator(), false)
                .filter(occurrence -> occurrence.get("itemId").asLong() == electric)
                .toList();
    }

    @Test
    void unpaidItIsInThePeriodItIsDueIn() throws Exception {
        assertThat(electricIn("2026-09-15")).hasSize(1);
        assertThat(electricIn("2026-09-01")).isEmpty();
    }

    @Test
    void paidEarlyItMovesToThePeriodItWasPaidInAndCountsThatDay() throws Exception {
        Reply paid = server.put("/api/items/" + electric + "/occurrences/2026-09-20", "{\"paid\":true,\"paidDate\":\"2026-09-12\"}", cookie);
        assertThat(paid.status()).isEqualTo(200);

        assertThat(electricIn("2026-09-15")).isEmpty();
        JsonNode moved = electricIn("2026-09-01").getFirst();
        assertThat(moved.get("dueDate").asText()).isEqualTo("2026-09-20");
        assertThat(moved.get("payBy").asText()).isEqualTo("2026-09-12");
        assertThat(moved.get("paidDate").asText()).isEqualTo("2026-09-12");
    }

    @Test
    void paidLateItMovesToThePeriodItWasPaidIn() throws Exception {
        // Rachel, 2026-10-22: "no you can pay late".
        server.put("/api/items/" + electric + "/occurrences/2026-08-20", "{\"paid\":true,\"paidDate\":\"2026-09-02\"}", cookie);

        assertThat(electricIn("2026-08-15")).isEmpty();
        assertThat(electricIn("2026-09-01")).extracting(occurrence -> occurrence.get("payBy").asText()).contains("2026-09-02");
    }
}
