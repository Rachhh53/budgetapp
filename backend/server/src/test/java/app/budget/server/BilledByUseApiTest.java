package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Billed by use (spec §4.6; Rachel, 2026-10-20: snowplowing, "how many times per month or week that might happen
 * ... and then it's billed once per month based on those two things"). $45.00 a plow, about twice a week,
 * possible December through March, billed on the 10th. "Today" is 2026-10-01.
 */
class BilledByUseApiTest extends ApiTestBase {

    long checking;
    long plowing;

    @BeforeEach
    void addPlowing() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        createSalary(checking);
        plowing = created("/api/items", plowing(200, "week", "monthly"));
    }

    private String plowing(long usesHundredths, String per, String frequency) {
        return """
                {"name":"Snow plowing","kind":"bill","amountCents":4500,"payFromAccountId":%d,"usesHundredths":%d,"usesPer":"%s",
                 "schedule":{"frequency":"%s","day1":10,"startDate":"2026-10-01","weekendRule":"none","months":[12,1,2,3]}}"""
                .formatted(checking, usesHundredths, per, frequency);
    }

    /** The plowing bills due from {@code from} through {@code to}, by the pay periods that list them. */
    private List<JsonNode> bills(String... periodStarts) throws Exception {
        List<JsonNode> found = new ArrayList<>();
        for (String start : periodStarts) {
            for (JsonNode occurrence : server.get("/api/pay-periods/" + start, cookie).data().get("occurrences")) {
                if (occurrence.get("itemId").asLong() == plowing) {
                    found.add(occurrence);
                }
            }
        }
        return found;
    }

    @Test
    void savesHowOftenAndWorksOutAMonthAndAYear() throws Exception {
        JsonNode item = server.get("/api/items/" + plowing, cookie).data();

        assertThat(item.get("usesHundredths").asLong()).isEqualTo(200);
        assertThat(item.get("usesPer").asText()).isEqualTo("week");
        assertThat(item.get("estimate").asBoolean()).isTrue();
        // Twice a week ≈ 8.67 plows a month, over the 4 months it can happen in.
        assertThat(item.get("perOccurrenceCents").asLong()).isEqualTo(39000);
        assertThat(item.get("perYearCents").asLong()).isEqualTo(156000);
        assertThat(item.get("next").get("dueDate").asText()).isEqualTo("2026-12-10");
    }

    @Test
    void eachBillCoversTheUsesSinceTheLastAndOneComesAfterTheSeason() throws Exception {
        // Dec 10 covers Dec 1–10: 10 days of twice a week. Nothing in October or November.
        assertThat(bills("2026-10-01", "2026-10-15", "2026-11-01", "2026-11-15")).isEmpty();
        JsonNode december = bills("2026-12-01").getFirst();
        assertThat(december.get("dueDate").asText()).isEqualTo("2026-12-10");
        assertThat(december.get("amountCents").asLong()).isEqualTo(12857);
        assertThat(december.get("estimate").asBoolean()).isTrue();
        // Mar 10 covers Feb 11 – Mar 10: 28 days, 8 plows.
        assertThat(bills("2027-03-01").getFirst().get("amountCents").asLong()).isEqualTo(36000);
        // Apr 10 covers Mar 11–31 only: 21 days, 6 plows. Then nothing until next winter.
        assertThat(bills("2027-04-01").getFirst().get("amountCents").asLong()).isEqualTo(27000);
        assertThat(bills("2027-05-01")).isEmpty();
    }

    @Test
    void theRealBillIsTypedOverTheEstimate() throws Exception {
        assertThat(server.put("/api/items/" + plowing + "/occurrences/2026-12-10", "{\"amountCents\":18000}", cookie).status())
                .isEqualTo(200);

        JsonNode december = bills("2026-12-01").getFirst();
        assertThat(december.get("amountCents").asLong()).isEqualTo(18000);
        assertThat(december.get("estimate").asBoolean()).isFalse();
    }

    @Test
    void refusesWhatCannotBeBilledByUse() throws Exception {
        Reply none = server.post("/api/items", plowing(0, "week", "monthly"), cookie);
        assertThat(none.status()).isEqualTo(400);
        assertThat(none.error()).isEqualTo("Please enter about how many times, more than 0 and up to 1,000.");

        Reply day = server.post("/api/items", plowing(200, "day", "monthly"), cookie);
        assertThat(day.error()).isEqualTo("Please choose whether that's a week or a month from the list.");

        Reply weekly = server.post("/api/items", plowing(200, "week", "weekly"), cookie);
        assertThat(weekly.error()).isEqualTo("Something billed by use is billed once a month. Please choose Monthly and the day it's due.");

        Reply both = server.post("/api/items", plowing(200, "week", "monthly").replace("\"usesPer\"", "\"billedLater\":true,\"usesPer\""), cookie);
        assertThat(both.error()).isEqualTo("Something billed by use can't also be billed later or paid ahead. Please choose one way it's charged.");
    }

    @Test
    void listsTheComingBillsToChangeOneAtATime() throws Exception {
        JsonNode upcoming = server.get("/api/items/" + plowing + "/upcoming", cookie).data();

        // Dec 10, Jan 10, Feb 10, Mar 10, Apr 10, then next winter's Dec 10: six at most.
        assertThat(upcoming).hasSize(6);
        assertThat(upcoming.get(0).get("dueDate").asText()).isEqualTo("2026-12-10");
        assertThat(upcoming.get(0).get("amountCents").asLong()).isEqualTo(12857);
        assertThat(upcoming.get(4).get("dueDate").asText()).isEqualTo("2027-04-10");

        // Skipped stays listed, to be put back.
        server.put("/api/items/" + plowing + "/occurrences/2027-01-10", "{\"skipped\":true}", cookie);
        assertThat(server.get("/api/items/" + plowing + "/upcoming", cookie).data().get(1).get("skipped").asBoolean()).isTrue();
        assertThat(server.get("/api/items/999/upcoming", cookie).status()).isEqualTo(404);
    }

    @Test
    void needsASession() throws Exception {
        assertThat(server.get("/api/items/" + plowing + "/upcoming", null).status()).isEqualTo(401);
        assertThat(server.post("/api/items", plowing(200, "week", "monthly"), null).status()).isEqualTo(401);
    }
}
