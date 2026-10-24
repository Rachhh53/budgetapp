package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A whole paycheck planned for next year (Rachel, 2026-10-22: "plan next year should be all theorical and not change
 * anything real"). "Today" is Thursday 2026-10-01 and the plan is of 2027. Acme Corp pays $2,486.20 take-home on the
 * 1st and 15th.
 */
class PlannedPayApiTest extends ApiTestBase {

    /** $4,000.00 gross, $2,600.00 take-home, $200.00 a paycheck into the 401(k). */
    private static final String RAISE = """
            {"grossCents":400000,"netCents":260000,"netIsEstimate":true,
             "deductions":[{"name":"401(k)","amountCents":20000,"toAccountId":%d}]}""";

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

    private Reply planPay(String body) throws Exception {
        return server.put("/api/plans/" + plan + "/income/" + salary + "/pay", body, cookie);
    }

    private static JsonNode salaryRow(JsonNode plan) {
        return plan.get("steps").get(0).get("rows").get(0);
    }

    private JsonNode source() throws Exception {
        return server.get("/api/income-sources", cookie).data().get(0);
    }

    private long paycheckOn(String day) throws Exception {
        return server.get("/api/paychecks?from=" + day + "&to=" + day, cookie).data().get(0).get("netCents").asLong();
    }

    @Test
    void nothingWorksWithoutASession() throws Exception {
        assertThat(server.put("/api/plans/" + plan + "/income/" + salary + "/pay", RAISE.formatted(retirement), null).status())
                .isEqualTo(401);
        assertThat(salaryRow(server.get("/api/plans/" + plan, cookie).data()).get("pay").get("planned").asBoolean()).isFalse();
    }

    @Test
    void aLineOpensOnThePayAsItStands() throws Exception {
        JsonNode pay = salaryRow(server.get("/api/plans/" + plan, cookie).data()).get("pay");

        assertThat(pay.get("planned").asBoolean()).isFalse();
        assertThat(pay.get("netCents").asLong()).isEqualTo(248_620);
        assertThat(pay.get("deductions")).isEmpty();
    }

    @Test
    void aWholePaycheckIsKeptWithThePlanAndChangesNothingReal() throws Exception {
        Reply planned = planPay(RAISE.formatted(retirement));

        assertThat(planned.status()).as(planned.body().toString()).isEqualTo(200);
        JsonNode row = salaryRow(planned.data());
        assertThat(row.get("chosenCents").asLong()).isEqualTo(260_000);
        assertThat(row.get("yours").asBoolean()).isTrue();
        assertThat(row.get("pay").get("planned").asBoolean()).isTrue();
        assertThat(row.get("pay").get("grossCents").asLong()).isEqualTo(400_000);
        assertThat(row.get("pay").get("deductions").get(0).get("name").asText()).isEqualTo("401(k)");
        assertThat(planned.data().get("incomeCents").asLong()).isEqualTo(260_000L * 24);
        // The income source, and every paycheck this year and next, are exactly as they were.
        assertThat(source().get("netCents").asLong()).isEqualTo(248_620);
        assertThat(source().get("grossCents").isNull()).isTrue();
        assertThat(source().get("deductions")).isEmpty();
        assertThat(source().get("payHistory")).hasSize(1);
        assertThat(paycheckOn("2027-01-01")).isEqualTo(248_620);
    }

    @Test
    void onItsFirstDayThePlannedPaycheckBecomesThePayFromThatDay() throws Exception {
        planPay(RAISE.formatted(retirement));
        server.clock.advance(Duration.ofDays(92));
        cookie = server.unlock();

        assertThat(server.activateDuePlan()).isTrue();

        assertThat(paycheckOn("2026-12-15")).isEqualTo(248_620);
        assertThat(paycheckOn("2027-01-01")).isEqualTo(260_000);
        JsonNode change = source().get("payHistory").get(1);
        assertThat(change.get("effectiveDate").asText()).isEqualTo("2027-01-01");
        assertThat(change.get("grossCents").asLong()).isEqualTo(400_000);
        assertThat(change.get("netIsEstimate").asBoolean()).isTrue();
        assertThat(change.get("deductions").get(0).get("toAccountId").asLong()).isEqualTo(retirement);
    }

    @Test
    void typingTheTakeHomeKeepsThePlannedPaycheckInStep() throws Exception {
        planPay(RAISE.formatted(retirement));

        JsonNode plan = server.put("/api/plans/" + this.plan + "/amounts",
                "{\"kind\":\"income\",\"id\":%d,\"amountCents\":270000}".formatted(salary), cookie).data();

        JsonNode pay = salaryRow(plan).get("pay");
        assertThat(pay.get("planned").asBoolean()).isTrue();
        assertThat(pay.get("netCents").asLong()).isEqualTo(270_000);
        assertThat(pay.get("grossCents").asLong()).isEqualTo(400_000);
    }

    @Test
    void goingBackToThePayAsItStandsForgetsThePlannedPaycheck() throws Exception {
        planPay(RAISE.formatted(retirement));

        JsonNode plan = server.put("/api/plans/" + this.plan + "/amounts",
                "{\"kind\":\"income\",\"id\":%d,\"amountCents\":null}".formatted(salary), cookie).data();

        JsonNode row = salaryRow(plan);
        assertThat(row.get("yours").asBoolean()).isFalse();
        assertThat(row.get("pay").get("planned").asBoolean()).isFalse();
        assertThat(row.get("chosenCents").asLong()).isEqualTo(248_620);
    }

    @Test
    void aPaycheckThatCannotBeRightIsRefused() throws Exception {
        assertThat(planPay("{\"grossCents\":400000}").status()).isEqualTo(400);
        // A match is paid into an investment account only.
        Reply matched = planPay("""
                {"grossCents":400000,"netCents":260000,"match":{"toAccountId":%d,"mode":"fixed","fixedCents":10000}}"""
                .formatted(checking));
        assertThat(matched.status()).isEqualTo(400);
        assertThat(matched.body().get("error").asText()).contains("investment account");
        assertThat(salaryRow(server.get("/api/plans/" + plan, cookie).data()).get("pay").get("planned").asBoolean()).isFalse();
    }

    @Test
    void anIncomeSourceThatIsNotInThePlanIsNotFound() throws Exception {
        assertThat(server.put("/api/plans/" + plan + "/income/9999/pay", RAISE.formatted(retirement), cookie).status())
                .isEqualTo(404);
    }

    @Test
    void anAccountADraftsPaycheckUsesCannotBeDeleted() throws Exception {
        planPay(RAISE.formatted(retirement));

        Reply refused = server.send("DELETE", "/api/accounts/" + retirement, null, Map.of("Cookie", cookie));

        assertThat(refused.status()).isEqualTo(409);
        // Once the draft is deleted, nothing holds on to it.
        server.send("DELETE", "/api/plans/" + plan, "{\"confirm\":\"delete-draft\"}", Map.of("Cookie", cookie));
        assertThat(server.send("DELETE", "/api/accounts/" + retirement, null, Map.of("Cookie", cookie)).status()).isEqualTo(200);
    }

    @Test
    void anActivePlansPaycheckCanNoLongerBeChanged() throws Exception {
        server.clock.advance(Duration.ofDays(92));
        cookie = server.unlock();
        server.activateDuePlan();

        assertThat(planPay(RAISE.formatted(retirement)).status()).isEqualTo(409);
    }
}
