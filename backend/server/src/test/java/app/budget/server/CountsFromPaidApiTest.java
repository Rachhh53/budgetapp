package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An item whose next dates count on from the day it was last paid (Rachel, 2026-10-24: "if it's every 6 weeks, it should
 * adjust upcoming payments to 6 weeks after the latest one"). Today is Oct 1, 2026.
 */
class CountsFromPaidApiTest extends ApiTestBase {

    /** Every six weeks from Thursday Aug 6: Aug 6, Sep 17, Oct 29, Dec 10. */
    private static final String SIX_WEEKS = "{\"frequency\":\"weekly\",\"interval\":6,\"startDate\":\"2026-08-06\"}";

    long checking;

    @BeforeEach
    void setUp() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        createSalary(checking);
    }

    private String haircut(String extra) {
        return "{\"name\":\"Haircut\",\"kind\":\"bill\",\"amountCents\":6500,\"payFromAccountId\":%d,\"schedule\":%s%s}"
                .formatted(checking, SIX_WEEKS, extra.isEmpty() ? "" : "," + extra);
    }

    /** The next three dates still to pay. */
    private List<String> upcoming(long id) throws Exception {
        Reply reply = server.get("/api/items/" + id + "/upcoming", cookie);
        assertThat(reply.status()).as(reply.body().toString()).isEqualTo(200);
        return StreamSupport.stream(reply.data().spliterator(), false)
                .filter(each -> !each.get("paid").asBoolean())
                .map(each -> each.get("dueDate").asText())
                .limit(3)
                .toList();
    }

    private void paid(long id, String scheduled, String on) throws Exception {
        Reply reply = server.put("/api/items/" + id + "/occurrences/" + scheduled, "{\"paid\":true,\"paidDate\":\"" + on + "\"}", cookie);
        assertThat(reply.status()).as(reply.body().toString()).isEqualTo(200);
    }

    @Test
    void paidAWeekLateTheNextDatesCountOnSixWeeksFromTheDayItWasPaid() throws Exception {
        long id = created("/api/items", haircut("\"countsFromPaid\":true"));
        assertThat(server.get("/api/items/" + id, cookie).data().get("countsFromPaid").asBoolean()).isTrue();
        assertThat(upcoming(id)).containsExactly("2026-10-29", "2026-12-10", "2027-01-21");

        paid(id, "2026-09-17", "2026-09-24");

        assertThat(upcoming(id)).containsExactly("2026-11-05", "2026-12-17", "2027-01-28");
        // The next one is paid early: everything after it counts from that day instead.
        paid(id, "2026-11-05", "2026-10-30");
        assertThat(upcoming(id)).containsExactly("2026-12-11", "2027-01-22", "2027-03-05");
    }

    @Test
    void takingThePaymentBackPutsTheDatesBackOnThePaymentBeforeIt() throws Exception {
        long id = created("/api/items", haircut("\"countsFromPaid\":true"));
        paid(id, "2026-08-06", "2026-08-06");
        paid(id, "2026-09-17", "2026-09-24");

        server.put("/api/items/" + id + "/occurrences/2026-09-17", "{\"paid\":false}", cookie);

        assertThat(upcoming(id)).containsExactly("2026-10-29", "2026-12-10", "2027-01-21");
    }

    @Test
    void anItemThatKeepsToItsScheduleIsNotMovedByWhenItWasPaid() throws Exception {
        long id = created("/api/items", haircut(""));
        assertThat(server.get("/api/items/" + id, cookie).data().get("countsFromPaid").asBoolean()).isFalse();

        paid(id, "2026-09-17", "2026-09-24");

        assertThat(upcoming(id)).containsExactly("2026-10-29", "2026-12-10", "2027-01-21");
    }

    @Test
    void theSettingIsKeptWhenAChangeDoesNotMentionItAndCanBeTurnedOff() throws Exception {
        long id = created("/api/items", haircut("\"countsFromPaid\":true"));
        paid(id, "2026-09-17", "2026-09-24");

        // A change of amount from a form that does not send the setting.
        assertThat(server.put("/api/items/" + id, haircut("\"amountCents\":7000").replace("\"amountCents\":6500,", ""), cookie).status())
                .isEqualTo(200);
        assertThat(server.get("/api/items/" + id, cookie).data().get("countsFromPaid").asBoolean()).isTrue();
        assertThat(upcoming(id).getFirst()).isEqualTo("2026-11-05");

        server.put("/api/items/" + id, haircut("\"countsFromPaid\":false"), cookie);

        assertThat(upcoming(id).getFirst()).isEqualTo("2026-10-29");
    }

    @Test
    void theItemsPayPeriodsFollowTheNewDates() throws Exception {
        long id = created("/api/items", haircut("\"countsFromPaid\":true"));
        paid(id, "2026-09-17", "2026-09-24");

        JsonNode october = server.get("/api/pay-periods/2026-10-15", cookie).data().get("occurrences");
        JsonNode november = server.get("/api/pay-periods/2026-11-01", cookie).data().get("occurrences");

        assertThat(october.findValuesAsText("name")).doesNotContain("Haircut");
        assertThat(StreamSupport.stream(november.spliterator(), false).filter(each -> each.get("itemId").asLong() == id))
                .singleElement()
                .satisfies(each -> assertThat(each.get("dueDate").asText()).isEqualTo("2026-11-05"));
    }

    @Test
    void needsASession() throws Exception {
        long id = created("/api/items", haircut("\"countsFromPaid\":true"));

        assertThat(server.put("/api/items/" + id, haircut("\"countsFromPaid\":false"), null).status()).isEqualTo(401);
        assertThat(server.get("/api/items/" + id + "/upcoming", null).status()).isEqualTo(401);
    }
}
