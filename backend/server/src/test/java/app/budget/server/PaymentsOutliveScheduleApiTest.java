package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A payment is never hidden by a change to the schedule that named its date (Rachel, 2026-10-22: "I deleted an estimate
 * from a recurring bill and it deleted all my real payments"). Payments are kept by the date the schedule named; once
 * that date no longer comes up, the payment is still listed. Paychecks on the 1st and 15th. Water is $60.00 on the 5th
 * from January; from June it is an estimate of $75.00 on the 20th. "Today" is 2026-10-01.
 */
class PaymentsOutliveScheduleApiTest extends ApiTestBase {

    long checking;
    long water;

    @BeforeEach
    void aBillWhoseScheduleChanged() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        createSalary(checking);
        water = created("/api/items", water(6000, 5, null));
        Reply changed = server.put("/api/items/" + water, water(7500, 20, "2026-06-01"), cookie);
        assertThat(changed.status()).as(changed.body().toString()).isEqualTo(200);
    }

    private String water(long cents, int day, String from) {
        return """
                {"name":"Water","kind":"bill","amountCents":%d,"payFromAccountId":%d,%s
                 "schedule":{"frequency":"monthly","day1":%d,"startDate":"2026-01-01","weekendRule":"none"}}"""
                .formatted(cents, checking, from == null ? "" : "\"effectiveFrom\":\"" + from + "\",", day);
    }

    private List<JsonNode> waterIn(String periodStart) throws Exception {
        Reply reply = server.get("/api/pay-periods/" + periodStart, cookie);
        assertThat(reply.status()).isEqualTo(200);
        return StreamSupport.stream(reply.data().get("occurrences").spliterator(), false)
                .filter(occurrence -> occurrence.get("itemId").asLong() == water)
                .toList();
    }

    private void pay(String scheduled, String paidOn, long cents) throws Exception {
        Reply paid = server.put("/api/items/" + water + "/occurrences/" + scheduled,
                "{\"paid\":true,\"paidDate\":\"%s\",\"amountCents\":%d}".formatted(paidOn, cents), cookie);
        assertThat(paid.status()).as(paid.body().toString()).isEqualTo(200);
    }

    private long newerVersion() throws Exception {
        return server.get("/api/items/" + water, cookie).data().get("history").get(1).get("versionId").asLong();
    }

    @Test
    void removingTheAmountWhoseScheduleNamedThePaymentsLeavesThePaymentsWhereTheyWere() throws Exception {
        pay("2026-07-20", "2026-07-20", 8123);
        pay("2026-08-20", "2026-08-21", 7790);

        assertThat(server.delete("/api/items/" + water + "/versions/" + newerVersion(), cookie).status()).isEqualTo(200);

        JsonNode july = waterIn("2026-07-15").stream().filter(occurrence -> occurrence.get("paid").asBoolean()).findFirst().orElseThrow();
        assertThat(july.get("scheduledDate").asText()).isEqualTo("2026-07-20");
        assertThat(july.get("amountCents").asLong()).isEqualTo(8123);
        assertThat(july.get("paidDate").asText()).isEqualTo("2026-07-20");
        assertThat(waterIn("2026-08-15")).anyMatch(occurrence -> occurrence.get("paid").asBoolean()
                && occurrence.get("amountCents").asLong() == 7790);
        // The schedule in force again names the 5th, which comes up unpaid as before.
        assertThat(waterIn("2026-07-01")).anyMatch(occurrence -> occurrence.get("dueDate").asText().equals("2026-07-05"));
    }

    @Test
    void correctingTheDayItIsDueLeavesThePaymentsWhereTheyWere() throws Exception {
        pay("2026-09-20", "2026-09-18", 7500);

        // The amount from June on is corrected in place to the 25th: the 20th no longer comes up.
        Reply corrected = server.put("/api/items/" + water, water(7500, 25, null), cookie);
        assertThat(corrected.status()).as(corrected.body().toString()).isEqualTo(200);

        assertThat(waterIn("2026-09-15")).anyMatch(occurrence -> occurrence.get("paid").asBoolean()
                && occurrence.get("scheduledDate").asText().equals("2026-09-20"));
    }

    @Test
    void aPaymentWhoseDateNoLongerComesUpCanStillBeChanged() throws Exception {
        pay("2026-07-20", "2026-07-20", 8123);
        server.delete("/api/items/" + water + "/versions/" + newerVersion(), cookie);

        Reply unticked = server.put("/api/items/" + water + "/occurrences/2026-07-20", "{\"paid\":false}", cookie);

        assertThat(unticked.status()).as(unticked.body().toString()).isEqualTo(200);
        // Not paid, and not a date the schedule names, it is nothing to list.
        assertThat(waterIn("2026-07-15")).noneMatch(occurrence -> occurrence.get("scheduledDate").asText().equals("2026-07-20"));
    }

    @Test
    void anUnpaidAmountTypedForADateThatNoLongerComesUpIsNotCountedTwice() throws Exception {
        server.put("/api/items/" + water + "/occurrences/2026-11-20", "{\"amountCents\":9000}", cookie);

        server.delete("/api/items/" + water + "/versions/" + newerVersion(), cookie);

        assertThat(waterIn("2026-11-15")).isEmpty();
        assertThat(waterIn("2026-11-01")).singleElement()
                .satisfies(occurrence -> assertThat(occurrence.get("amountCents").asLong()).isEqualTo(6000));
    }
}
