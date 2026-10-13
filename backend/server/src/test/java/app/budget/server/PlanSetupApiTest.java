package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * First-time setup over real HTTP (spec §8): an app with nothing in it walks through the same
 * steps as Plan next year, for the year already under way. "Today" is Thursday 2026-10-01.
 */
class PlanSetupApiTest extends ApiTestBase {

    private long startSetup() throws Exception {
        Reply reply = server.post("/api/plans", "{\"kind\":\"setup\"}", cookie);
        assertThat(reply.status()).as(reply.body().toString()).isEqualTo(201);
        return reply.data().get("id").asLong();
    }

    @Test
    void anEmptyAppOffersSetupAndNothingToPlanYet() throws Exception {
        JsonNode overview = server.get("/api/plans", cookie).data();

        assertThat(overview.get("canSetUp").asBoolean()).isTrue();
        assertThat(overview.get("canPlan").asBoolean()).isFalse();
        // Nobody is reminded to plan a year they have not set up.
        assertThat(overview.get("reminderDue").asBoolean()).isFalse();
        assertThat(server.get("/api/dashboard", cookie).data().get("attention").findValuesAsText("kind")).doesNotContain("plan_reminder");
    }

    @Test
    void setupIsOfTheYearAlreadyUnderWay() throws Exception {
        long id = startSetup();

        JsonNode plan = server.get("/api/plans/" + id, cookie).data();

        assertThat(plan.get("plan").get("setup").asBoolean()).isTrue();
        assertThat(plan.get("plan").get("status").asText()).isEqualTo("draft");
        assertThat(plan.get("plan").get("startsOn").asText()).isEqualTo("2026-01-01");
        assertThat(plan.get("steps")).hasSize(5);
        assertThat(server.get("/api/plans", cookie).data().get("canSetUp").asBoolean()).isFalse();
    }

    @Test
    void whatIsAddedDuringSetupShowsInItsStepAtTheAmountItHas() throws Exception {
        long id = startSetup();
        long checking = createAccount("Everyday Checking", "checking");
        createSalary(checking);
        long rent = created("/api/items", """
                {"name":"Rent","kind":"bill","amountCents":150000,"payFromAccountId":%d,"autopay":true,
                 "schedule":{"frequency":"monthly","day1":1,"startDate":"2026-01-01"}}""".formatted(checking));

        JsonNode plan = server.get("/api/plans/" + id, cookie).data();

        JsonNode bills = plan.get("steps").get(1);
        assertThat(bills.get("step").asText()).isEqualTo("bills");
        assertThat(bills.get("rows").get(0).get("name").asText()).isEqualTo("Rent");
        assertThat(bills.get("rows").get(0).get("chosenCents").asLong()).isEqualTo(1_800_000);
        assertThat(bills.get("rows").get(0).get("yours").asBoolean()).isFalse();
        assertThat(plan.get("incomeCents").asLong()).isEqualTo(248_620L * 24);
        // Setup plans nothing of its own: an amount is changed on the item itself.
        Reply typed = server.put("/api/plans/" + id + "/amounts", "{\"kind\":\"item\",\"id\":%d,\"amountCents\":100}".formatted(rent), cookie);
        assertThat(typed.status()).isEqualTo(409);
    }

    @Test
    void finishingSetupMakesItsPlanTheActiveOneAndChangesNothingElse() throws Exception {
        long id = startSetup();
        long checking = createAccount("Everyday Checking", "checking");
        createSalary(checking);

        Reply finished = server.post("/api/plans/" + id + "/finish", "{}", cookie);

        assertThat(finished.status()).as(finished.body().toString()).isEqualTo(200);
        assertThat(finished.data().get("plan").get("status").asText()).isEqualTo("active");
        JsonNode overview = server.get("/api/plans", cookie).data();
        assertThat(overview.get("canPlan").asBoolean()).isTrue();
        assertThat(overview.get("nextYear").asInt()).isEqualTo(2027);
        // Having just set up is not a plan "taking effect": the Dashboard does not announce it.
        assertThat(server.get("/api/dashboard", cookie).data().get("attention").findValuesAsText("kind")).doesNotContain("plan_active");
        assertThat(server.get("/api/paychecks?from=2026-10-01&to=2026-10-01", cookie).data().get(0).get("netCents").asLong())
                .isEqualTo(248_620);
    }

    @Test
    void nextYearWaitsUntilSetupIsFinished() throws Exception {
        startSetup();
        createSalary(createAccount("Everyday Checking", "checking"));

        Reply nextYear = server.post("/api/plans", "{}", cookie);

        assertThat(nextYear.status()).isEqualTo(409);
        assertThat(nextYear.error()).isEqualTo("Please finish setting up first.");
    }

    @Test
    void setupIsOnlyForTheFirstTime() throws Exception {
        long id = startSetup();
        server.post("/api/plans/" + id + "/finish", "{}", cookie);

        Reply again = server.post("/api/plans", "{\"kind\":\"setup\"}", cookie);

        assertThat(again.status()).isEqualTo(409);
    }

    @Test
    void aPlanForNextYearNeedsPaydaysToFollow() throws Exception {
        Reply reply = server.post("/api/plans", "{}", cookie);

        assertThat(reply.status()).isEqualTo(409);
        assertThat(reply.error()).contains("add your income first");
    }
}
