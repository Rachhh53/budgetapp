package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Something paid ahead as a pack, in next year's plan (Rachel, 2026-10-23: personal training "is saying it's 0 on the 2027
 * plan when I go twice per week"). Personal training is $1,275.00 for 20 sessions, Mondays and Thursdays from Monday
 * 2026-03-02: $63.75 a session, 104 sessions a year. "Today" is 2026-10-01, so its whole months are April to September.
 */
class PlannedPackApiTest extends ApiTestBase {

    long checking;
    long training;
    long plan;

    @BeforeEach
    void aPackAndAPlan() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        createSalary(checking);
        training = created("/api/items", """
                {"name":"Personal training","kind":"bill","amountCents":127500,"payFromAccountId":%d,"paidAhead":true,"packSessions":20,
                 "schedule":{"frequency":"weekly","startDate":"2026-03-02","weekendRule":"none","weekdays":[1,4]}}"""
                .formatted(checking));
        assertThat(server.post("/api/items/" + training + "/paid-ahead/payments", "{\"date\":\"2026-03-02\",\"amountCents\":127500}", cookie)
                .status()).isEqualTo(201);
        Reply started = server.post("/api/plans", "{}", cookie);
        assertThat(started.status()).as(started.body().toString()).isEqualTo(201);
        plan = started.data().get("id").asLong();
    }

    private JsonNode trainingRow() throws Exception {
        for (JsonNode step : server.get("/api/plans/" + plan, cookie).data().get("steps")) {
            for (JsonNode row : step.get("rows")) {
                if (row.get("name").asText().equals("Personal training")) {
                    return row;
                }
            }
        }
        throw new AssertionError("Personal training is not in the plan");
    }

    @Test
    void theSessionsUsedAreItsHistoryAtTheirShareOfThePack() throws Exception {
        JsonNode row = trainingRow();

        // Not nothing, and not $1,275.00 a session: $63.75 a session, twice a week.
        assertThat(row.get("currentCents").asLong()).isEqualTo(663_000);
        assertThat(row.get("reason").asText()).isEqualTo("short_history");
        assertThat(row.get("historyMonths").asInt()).isEqualTo(6);
        assertThat(row.get("last12Cents").asLong()).isPositive();
        assertThat(row.get("trendBp").asInt()).isZero();
        assertThat(row.get("suggestedCents").asLong()).isEqualTo(663_000);
        assertThat(row.get("perOccurrenceCents").asLong()).isEqualTo(6_375);
    }

    @Test
    void onItsFirstDayThePlanPricesThePackNotASession() throws Exception {
        // $7,650.00 a year is $73.56 a session, $1,471.20 a pack of 20.
        Reply typed = server.put("/api/plans/" + plan + "/amounts",
                "{\"kind\":\"item\",\"id\":%d,\"amountCents\":765000}".formatted(training), cookie);
        assertThat(typed.status()).as(typed.body().toString()).isEqualTo(200);
        server.clock.advance(Duration.ofDays(92));
        cookie = server.unlock();

        assertThat(server.activateDuePlan()).isTrue();

        JsonNode item = server.get("/api/items/" + training, cookie).data();
        assertThat(item.get("history").get(1).get("effectiveFrom").asText()).isEqualTo("2027-01-01");
        assertThat(item.get("amountCents").asLong()).isEqualTo(147_120);
        assertThat(item.get("packSessions").asInt()).isEqualTo(20);
    }

    @Test
    void nothingWorksWithoutASession() throws Exception {
        assertThat(server.get("/api/plans/" + plan, null).status()).isEqualTo(401);
    }
}
