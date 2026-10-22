package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Paid ahead as a pack, twice a week (spec §4.6; Rachel, 2026-10-19: "I pay 1275 for a 20 session pack.
 * I go twice per week. That means I pay about every 10 weeks"). Mondays and Thursdays from Sep 7, 2026;
 * $1,275 paid on Sep 1 covers the 20 sessions through Nov 12, so the next pack is due Mon Nov 16.
 * "Today" is 2026-10-01; pay periods are the 1st–14th and the 15th–end.
 */
class PaidAheadPackApiTest extends ApiTestBase {

    long checking;
    long classes;

    @BeforeEach
    void addClasses() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        createSalary(checking);
        classes = created("/api/items", pack(20));
        assertThat(server.post("/api/items/" + classes + "/paid-ahead/payments", "{\"date\":\"2026-09-01\",\"amountCents\":127500}", cookie)
                .status()).isEqualTo(201);
    }

    private String pack(int sessions) {
        return """
                {"name":"Climbing classes","kind":"bill","amountCents":127500,"payFromAccountId":%d,"paidAhead":true,"packSessions":%d,
                 "schedule":{"frequency":"weekly","startDate":"2026-09-07","weekendRule":"none","weekdays":[1,4]}}"""
                .formatted(checking, sessions);
    }

    private List<JsonNode> packsIn(String periodStart) throws Exception {
        List<JsonNode> packs = new ArrayList<>();
        for (JsonNode occurrence : server.get("/api/pay-periods/" + periodStart, cookie).data().get("occurrences")) {
            if (occurrence.get("plannedPack").asBoolean()) {
                packs.add(occurrence);
            }
        }
        return packs;
    }

    @Test
    void eachSessionCostsItsShareAndTwiceAWeekIsCountedTwice() throws Exception {
        JsonNode item = server.get("/api/items/" + classes, cookie).data();

        assertThat(item.get("packSessions").asInt()).isEqualTo(20);
        assertThat(item.get("schedule").get("weekdays").toString()).isEqualTo("[1,4]");
        assertThat(item.get("perOccurrenceCents").asLong()).isEqualTo(6375);
        assertThat(item.get("perYearCents").asLong()).isEqualTo(663000);
        assertThat(item.get("next").get("dueDate").asText()).isEqualTo("2026-11-16");
    }

    @Test
    void theNextPackIsABillOnTheFirstSessionNothingCovers() throws Exception {
        List<JsonNode> packs = packsIn("2026-11-15");

        assertThat(packs).hasSize(1);
        JsonNode next = packs.getFirst();
        assertThat(next.get("name").asText()).isEqualTo("Climbing classes");
        assertThat(next.get("dueDate").asText()).isEqualTo("2026-11-16");
        assertThat(next.get("amountCents").asLong()).isEqualTo(127500);
        assertThat(next.get("autopay").asBoolean()).isFalse();
        // Twenty sessions from Nov 16, twice a week.
        assertThat(next.get("coversThrough").asText()).isEqualTo("2027-01-21");
        assertThat(packsIn("2026-11-01")).isEmpty();
        // Planned, not warned about.
        assertThat(server.get("/api/pay-periods/2026-11-15", cookie).data().get("paidAheadRunningOut")).isEmpty();
    }

    @Test
    void whatIsLeftAndWhenTheNextPackIsDue() throws Exception {
        JsonNode standing = server.get("/api/items/" + classes + "/paid-ahead", cookie).data();

        // Sep 7, 10, 14, 17, 21, 24, 28 and Oct 1 so far: eight of twenty.
        assertThat(standing.get("usedCents").asLong()).isEqualTo(51000);
        assertThat(standing.get("leftCents").asLong()).isEqualTo(76500);
        assertThat(standing.get("coversThrough").asText()).isEqualTo("2026-11-12");
        assertThat(standing.get("packSessions").asInt()).isEqualTo(20);
        assertThat(standing.get("packPriceCents").asLong()).isEqualTo(127500);
        assertThat(standing.get("nextPack").asText()).isEqualTo("2026-11-16");
    }

    @Test
    void aSkippedSessionMovesTheNextPackLater() throws Exception {
        assertThat(server.put("/api/items/" + classes + "/occurrences/2026-10-05", "{\"skipped\":true}", cookie).status()).isEqualTo(200);

        assertThat(server.get("/api/items/" + classes + "/paid-ahead", cookie).data().get("nextPack").asText()).isEqualTo("2026-11-19");
        assertThat(packsIn("2026-11-15").getFirst().get("dueDate").asText()).isEqualTo("2026-11-19");
    }

    @Test
    void payingForTheNextPackPlansTheOneAfter() throws Exception {
        server.post("/api/items/" + classes + "/paid-ahead/payments", "{\"date\":\"2026-11-14\",\"amountCents\":127500}", cookie);

        assertThat(packsIn("2026-11-15")).isEmpty();
        assertThat(server.get("/api/items/" + classes + "/paid-ahead", cookie).data().get("nextPack").asText()).isEqualTo("2027-01-25");
    }

    @Test
    void annualCostsCountThePaymentsAndThePlannedPacks() throws Exception {
        JsonNode months = server.get("/api/items/" + classes + "/by-month", cookie).data().get("months");

        assertThat(months.get(8).get("amountCents").asLong()).isEqualTo(127500);
        assertThat(months.get(9).get("amountCents").asLong()).isZero();
        assertThat(months.get(10).get("amountCents").asLong()).isEqualTo(127500);
    }

    @Test
    void refusesAPackThatCannotBeRight() throws Exception {
        Reply none = server.post("/api/items", pack(0), cookie);
        assertThat(none.status()).isEqualTo(400);
        assertThat(none.error()).isEqualTo("Please enter how many sessions the pack has, from 1 to 1000.");

        Reply monthlyOnDays = server.post("/api/items", """
                {"name":"Gym","kind":"bill","amountCents":5000,"payFromAccountId":%d,
                 "schedule":{"frequency":"monthly","day1":1,"startDate":"2026-01-01","weekdays":[1,4]}}""".formatted(checking), cookie);
        assertThat(monthlyOnDays.status()).isEqualTo(400);
        assertThat(monthlyOnDays.error())
                .isEqualTo("Days of the week are for something weekly or every two weeks. Please choose how often again.");
    }

    /** The classes, with each pack paid on a schedule of its own (Rachel, 2026-10-20: "I want both options"). */
    private String packEvery(String schedule) {
        return pack(20).replaceFirst("\"schedule\"", "\"packSchedule\":" + schedule + ",\"schedule\"");
    }

    @Test
    void aPackOnItsOwnScheduleIsDueOnItsDates() throws Exception {
        // Every 10 weeks from Sep 1, set today (Oct 1): the next date after today is Nov 10.
        Reply reply = server.put("/api/items/" + classes,
                packEvery("{\"frequency\":\"weekly\",\"interval\":10,\"startDate\":\"2026-09-01\"}"), cookie);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.data().get("packSchedule").get("interval").asInt()).isEqualTo(10);
        JsonNode standing = server.get("/api/items/" + classes + "/paid-ahead", cookie).data();
        assertThat(standing.get("nextPack").asText()).isEqualTo("2026-11-10");
        // Twenty sessions every ten weeks, twice a week: they never run out.
        assertThat(standing.get("shortFrom").isNull()).isTrue();
        assertThat(packsIn("2026-11-01")).extracting(pack -> pack.get("dueDate").asText()).containsExactly("2026-11-10");
        assertThat(packsIn("2026-11-15")).isEmpty();
    }

    @Test
    void packsTooFarApartWarnWhenTheSessionsRunOut() throws Exception {
        // Every 12 weeks from Sep 1: the next is Nov 24, but the first pack runs out after Nov 12.
        server.put("/api/items/" + classes, packEvery("{\"frequency\":\"weekly\",\"interval\":12,\"startDate\":\"2026-09-01\"}"), cookie);

        JsonNode standing = server.get("/api/items/" + classes + "/paid-ahead", cookie).data();
        assertThat(standing.get("nextPack").asText()).isEqualTo("2026-11-24");
        assertThat(standing.get("shortFrom").asText()).isEqualTo("2026-11-16");
        assertThat(server.get("/api/pay-periods/2026-11-15", cookie).data().get("paidAheadRunningOut")).isNotEmpty();
    }

    @Test
    void aPackCanBePaidEveryAnyNumberOfMonths() throws Exception {
        Reply reply = server.put("/api/items/" + classes,
                packEvery("{\"frequency\":\"every_n_months\",\"interval\":7,\"day1\":1,\"startDate\":\"2026-09-01\"}"), cookie);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(server.get("/api/items/" + classes + "/paid-ahead", cookie).data().get("nextPack").asText()).isEqualTo("2027-04-01");
    }

    @Test
    void refusesAPackScheduleThatCannotBeRight() throws Exception {
        Reply paydays = server.put("/api/items/" + classes,
                packEvery("{\"frequency\":\"every_pay_period\",\"startDate\":\"2026-09-01\"}"), cookie);
        assertThat(paydays.status()).isEqualTo(400);
        assertThat(paydays.error()).isEqualTo("Please choose how often a pack is paid, such as every 10 weeks or every 3 months.");

        Reply noWeeks = server.put("/api/items/" + classes,
                packEvery("{\"frequency\":\"weekly\",\"interval\":0,\"startDate\":\"2026-09-01\"}"), cookie);
        assertThat(noWeeks.status()).isEqualTo(400);
        assertThat(noWeeks.error()).isEqualTo("Please choose how many weeks apart, from 1 to 52.");
    }

    @Test
    void theNextPackCanBeMovedAndPutBack() throws Exception {
        Reply moved = server.put("/api/items/" + classes + "/paid-ahead/packs/2026-11-16", "{\"date\":\"2026-11-20\"}", cookie);

        assertThat(moved.status()).isEqualTo(200);
        assertThat(moved.data().get("nextPack").asText()).isEqualTo("2026-11-20");
        assertThat(moved.data().get("nextPackPlanned").asText()).isEqualTo("2026-11-16");
        assertThat(packsIn("2026-11-15").getFirst().get("dueDate").asText()).isEqualTo("2026-11-20");

        Reply back = server.delete("/api/items/" + classes + "/paid-ahead/packs/2026-11-16", cookie);
        assertThat(back.status()).isEqualTo(200);
        assertThat(back.data().get("nextPack").asText()).isEqualTo("2026-11-16");
    }

    @Test
    void onlyAPlannedPackCanBeMoved() throws Exception {
        assertThat(server.put("/api/items/" + classes + "/paid-ahead/packs/2026-11-17", "{\"date\":\"2026-11-20\"}", cookie).status())
                .isEqualTo(404);
        Reply noDate = server.put("/api/items/" + classes + "/paid-ahead/packs/2026-11-16", "{}", cookie);
        assertThat(noDate.status()).isEqualTo(400);
        assertThat(noDate.error()).isEqualTo("Please choose the day the pack is due.");
    }

    @Test
    void readingItRequiresASession() throws Exception {
        assertThat(server.get("/api/items/" + classes + "/paid-ahead", null).status()).isEqualTo(401);
        assertThat(server.put("/api/items/" + classes + "/paid-ahead/packs/2026-11-16", "{\"date\":\"2026-11-20\"}", null).status())
                .isEqualTo(401);
        assertThat(server.delete("/api/items/" + classes + "/paid-ahead/packs/2026-11-16", null).status()).isEqualTo(401);
    }
}
