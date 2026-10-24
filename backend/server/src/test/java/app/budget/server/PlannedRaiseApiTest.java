package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A raise partway through a planned year (Rachel, 2026-10-23: "plan my income for next year for jan-may and then I
 * expect to get a cost of living raise in june ... let me just do the raise as a percent"). "Today" is Thursday
 * 2026-10-01 and the plan is of 2027. Acme Corp pays on the 1st and 15th: 24 paychecks, 14 of them from June 1.
 */
class PlannedRaiseApiTest extends ApiTestBase {

    /** $4,000.00 gross, $2,600.00 take-home, 5% of gross into the 401(k); 3% more from June 1. */
    private static final String PAY_WITH_RAISE = """
            {"grossCents":400000,"netCents":260000,"netIsEstimate":false,
             "deductions":[{"name":"401(k)","percentBp":500,"toAccountId":%d}],
             "raise":{"from":"%s","bp":%d}}""";

    long checking;
    long retirement;
    long salary;
    long plan;

    @BeforeEach
    void aPlanOfNextYear() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        retirement = createAccount("Retirement 401(k)", "investment");
        salary = createSalary(checking);
        Reply started = server.post("/api/plans", "{}", cookie);
        assertThat(started.status()).as(started.body().toString()).isEqualTo(201);
        plan = started.data().get("id").asLong();
    }

    private Reply planPay(String from, int bp) throws Exception {
        return server.put("/api/plans/" + plan + "/income/" + salary + "/pay", PAY_WITH_RAISE.formatted(retirement, from, bp), cookie);
    }

    private static JsonNode salaryRow(JsonNode plan) {
        return plan.get("steps").get(0).get("rows").get(0);
    }

    private JsonNode paycheckOn(String day) throws Exception {
        return server.get("/api/paychecks?from=" + day + "&to=" + day, cookie).data().get(0);
    }

    @Test
    void nothingWorksWithoutASession() throws Exception {
        Reply refused = server.put("/api/plans/" + plan + "/income/" + salary + "/pay",
                PAY_WITH_RAISE.formatted(retirement, "2027-06-01", 300), null);

        assertThat(refused.status()).isEqualTo(401);
    }

    @Test
    void theYearCountsThePaychecksBeforeTheRaiseAtThePayAndTheRestRaised() throws Exception {
        Reply planned = planPay("2027-06-01", 300);

        assertThat(planned.status()).as(planned.body().toString()).isEqualTo(200);
        JsonNode row = salaryRow(planned.data());
        JsonNode pay = row.get("pay");
        assertThat(row.get("chosenCents").asLong()).isEqualTo(260_000);
        assertThat(pay.get("raiseFrom").asText()).isEqualTo("2027-06-01");
        assertThat(pay.get("raiseBp").asInt()).isEqualTo(300);
        assertThat(pay.get("raisedGrossCents").asLong()).isEqualTo(412_000);
        assertThat(pay.get("raisedNetCents").asLong()).isEqualTo(267_800);
        assertThat(pay.get("raisedPaychecks").asInt()).isEqualTo(14);
        // Ten paychecks January to May at the pay, fourteen from June raised.
        assertThat(row.get("yearCents").asLong()).isEqualTo(260_000L * 10 + 267_800L * 14);
        assertThat(planned.data().get("incomeCents").asLong()).isEqualTo(260_000L * 10 + 267_800L * 14);
        // Nothing real changes while it is a plan.
        assertThat(server.get("/api/income-sources", cookie).data().get(0).get("payHistory")).hasSize(1);
    }

    @Test
    void onItsFirstDayThePlanAddsAPayChangeForTheRaiseFromItsOwnDay() throws Exception {
        planPay("2027-06-01", 300);
        server.clock.advance(Duration.ofDays(92));
        cookie = server.unlock();

        assertThat(server.activateDuePlan()).isTrue();

        assertThat(paycheckOn("2027-05-15").get("netCents").asLong()).isEqualTo(260_000);
        JsonNode june = paycheckOn("2027-06-01");
        assertThat(june.get("netCents").asLong()).isEqualTo(267_800);
        JsonNode history = server.get("/api/income-sources", cookie).data().get(0).get("payHistory");
        assertThat(history).hasSize(3);
        JsonNode raise = history.get(2);
        assertThat(raise.get("effectiveDate").asText()).isEqualTo("2027-06-01");
        assertThat(raise.get("grossCents").asLong()).isEqualTo(412_000);
        // A deduction set as a share of pay goes up with it; take-home after a raise is an estimate.
        assertThat(raise.get("deductions").get(0).get("amountCents").asLong()).isEqualTo(20_600);
        assertThat(raise.get("netIsEstimate").asBoolean()).isTrue();
        assertThat(history.get(1).get("netIsEstimate").asBoolean()).isFalse();
    }

    @Test
    void typingTheTakeHomeKeepsTheRaise() throws Exception {
        planPay("2027-06-01", 300);

        JsonNode plan = server.put("/api/plans/" + this.plan + "/amounts",
                "{\"kind\":\"income\",\"id\":%d,\"amountCents\":270000}".formatted(salary), cookie).data();

        JsonNode pay = salaryRow(plan).get("pay");
        assertThat(pay.get("raiseBp").asInt()).isEqualTo(300);
        assertThat(pay.get("raisedNetCents").asLong()).isEqualTo(278_100);
    }

    @Test
    void aRaiseThatCannotBeRightIsRefused() throws Exception {
        // From the plan's first day it is the pay, not a raise; after the year it belongs to the next plan.
        assertThat(planPay("2027-01-01", 300).status()).isEqualTo(400);
        assertThat(planPay("2028-01-01", 300).status()).isEqualTo(400);
        assertThat(planPay("2027-06-01", 0).status()).isEqualTo(400);
        assertThat(planPay("2027-06-01", 10_001).status()).isEqualTo(400);
        assertThat(server.put("/api/plans/" + plan + "/income/" + salary + "/pay",
                "{\"netCents\":260000,\"raise\":{\"bp\":300}}", cookie).status()).isEqualTo(400);
        assertThat(salaryRow(server.get("/api/plans/" + plan, cookie).data()).get("pay").get("planned").asBoolean()).isFalse();
    }

    @Test
    void savingThePayWithoutARaiseTakesTheRaiseAway() throws Exception {
        planPay("2027-06-01", 300);

        Reply plain = server.put("/api/plans/" + plan + "/income/" + salary + "/pay", "{\"netCents\":260000}", cookie);

        JsonNode row = salaryRow(plain.data());
        assertThat(row.get("pay").get("raiseFrom").isNull()).isTrue();
        assertThat(row.get("yearCents").asLong()).isEqualTo(260_000L * 24);
    }
}
