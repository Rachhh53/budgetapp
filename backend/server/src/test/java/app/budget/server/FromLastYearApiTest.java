package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A bill estimated from what it came to in the same month a year before (Rachel, 2026-10-24: "can I get the option to use
 * last years amounts as a setting in recurring payments"). Today is Oct 1, 2026.
 */
class FromLastYearApiTest extends ApiTestBase {

    long checking;

    @BeforeEach
    void setUp() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        createSalary(checking);
    }

    /** Electric on the 20th since January 2025, at $90 unless a month says otherwise. */
    private String electric(String extra) {
        return """
                {"name":"Electric","kind":"bill","amountCents":9000,"estimate":true,"payFromAccountId":%d,
                 "schedule":{"frequency":"monthly","day1":20,"startDate":"2025-01-01"}%s}"""
                .formatted(checking, extra.isEmpty() ? "" : "," + extra);
    }

    /** Last November and December's bills, as they really came. */
    private long withLastWinter(String extra) throws Exception {
        long id = created("/api/items", electric(extra));
        typed(id, "2025-11-20", 14000);
        typed(id, "2025-12-20", 18000);
        return id;
    }

    private void typed(long id, String scheduled, long cents) throws Exception {
        Reply reply = server.put("/api/items/" + id + "/occurrences/" + scheduled, "{\"amountCents\":" + cents + "}", cookie);
        assertThat(reply.status()).as(reply.body().toString()).isEqualTo(200);
    }

    private List<JsonNode> upcoming(long id) throws Exception {
        Reply reply = server.get("/api/items/" + id + "/upcoming", cookie);
        assertThat(reply.status()).as(reply.body().toString()).isEqualTo(200);
        return StreamSupport.stream(reply.data().spliterator(), false).toList();
    }

    private static List<Long> amounts(List<JsonNode> dates) {
        return dates.stream().map(each -> each.get("amountCents").asLong()).toList();
    }

    @Test
    void eachDateStillToComeIsWhatTheSameMonthCameToAYearBefore() throws Exception {
        long id = withLastWinter("\"fromLastYear\":true");
        assertThat(server.get("/api/items/" + id, cookie).data().get("fromLastYear").asBoolean()).isTrue();

        List<JsonNode> dates = upcoming(id);

        assertThat(dates).extracting(each -> each.get("dueDate").asText()).startsWith("2026-10-20", "2026-11-20", "2026-12-20", "2027-01-20");
        // October came to the item's own $90 last year; November and December to what was really billed.
        assertThat(amounts(dates)).startsWith(9000L, 14000L, 18000L, 9000L);
        assertThat(dates.get(1).get("estimate").asBoolean()).isTrue();
    }

    @Test
    void anItemThatKeepsItsOwnAmountIsNotEstimatedFromLastYear() throws Exception {
        long id = withLastWinter("");

        assertThat(amounts(upcoming(id))).startsWith(9000L, 9000L, 9000L);
    }

    @Test
    void anAmountTypedForADateStillToComeIsKept() throws Exception {
        long id = withLastWinter("\"fromLastYear\":true");
        typed(id, "2026-11-20", 15500);

        assertThat(amounts(upcoming(id))).startsWith(9000L, 15500L, 18000L);
    }

    @Test
    void aDateWithNothingAYearBeforeUsesTheItemsOwnAmount() throws Exception {
        // Added in March: there was no bill a year before.
        long id = created("/api/items", electric("\"fromLastYear\":true").replace("2025-01-01", "2026-03-01"));

        assertThat(amounts(upcoming(id))).startsWith(9000L, 9000L, 9000L);
    }

    @Test
    void aSkippedMonthIsNotCountedAsNothing() throws Exception {
        long id = withLastWinter("\"fromLastYear\":true");
        server.put("/api/items/" + id + "/occurrences/2025-12-20", "{\"skipped\":true}", cookie);

        // December a year before has nothing that counts, so it is the item's own amount.
        assertThat(amounts(upcoming(id)).get(2)).isEqualTo(9000L);
    }

    @Test
    void theSettingIsKeptWhenAChangeDoesNotMentionIt() throws Exception {
        long id = withLastWinter("\"fromLastYear\":true");

        server.put("/api/items/" + id, electric("").replace("9000", "9500"), cookie);

        assertThat(server.get("/api/items/" + id, cookie).data().get("fromLastYear").asBoolean()).isTrue();
        assertThat(amounts(upcoming(id)).get(1)).isEqualTo(14000L);
    }

    @Test
    void needsASession() throws Exception {
        long id = withLastWinter("\"fromLastYear\":true");

        assertThat(server.put("/api/items/" + id, electric("\"fromLastYear\":false"), null).status()).isEqualTo(401);
    }
}
