package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * "Pay off debt or invest?" on Debt &amp; decisions (spec §5.9; Rachel, 2026-10-23): every plan spends the same each
 * month and is measured on the same day. "Today" is 2026-10-01. A car loan of $14,860.00 at 6.90% ($412.00 on the
 * 15th), a brokerage account assumed to grow 6% a year and savings at 4.10%.
 */
class DebtOrInvestApiTest extends ApiTestBase {

    long carLoan;
    long brokerage;
    long savings;

    private void household() throws Exception {
        long checking = createAccount("Everyday Checking", "checking");
        createSalary(checking);
        carLoan = createAccount("Car loan", "loan");
        brokerage = createAccount("Brokerage", "investment");
        savings = createAccount("Rainy Day Savings", "savings");
        enterBalance(carLoan, "2026-10-01", 1_486_000);
        enterBalance(brokerage, "2026-10-01", 900_000);
        enterBalance(savings, "2026-10-01", 500_000);
        rate(carLoan, 690);
        rate(brokerage, 600);
        rate(savings, 410);
        ok(server.put("/api/accounts/" + carLoan + "/loan", "{\"paymentCents\":41200,\"paymentDay\":15}", cookie));
    }

    private void rate(long accountId, int rateBp) throws Exception {
        ok(server.post("/api/accounts/" + accountId + "/rates", "{\"rateBp\":%d,\"effectiveDate\":\"2026-01-01\"}".formatted(rateBp), cookie));
    }

    private static void ok(Reply reply) {
        assertThat(reply.status()).as(reply.body().toString()).isLessThan(300);
    }

    private JsonNode plans(String settings) throws Exception {
        Reply reply = server.post("/api/planner", "{\"extraCents\":30000,\"years\":10,\"plans\":%s}".formatted(settings), cookie);
        assertThat(reply.status()).as(reply.body().toString()).isEqualTo(200);
        return reply.data().get("debtOrInvest");
    }

    private static JsonNode plan(JsonNode view, String kind) {
        for (JsonNode plan : view.get("plans")) {
            if (plan.get("kind").asText().equals(kind)) {
                return plan;
            }
        }
        throw new AssertionError("no " + kind);
    }

    @Test
    void itNeedsASession() throws Exception {
        assertThat(server.post("/api/planner", "{\"extraCents\":30000,\"years\":10,\"plans\":{}}", null).status()).isEqualTo(401);
    }

    @Test
    void withNoDebtThereIsNothingToWeigh() throws Exception {
        long checking = createAccount("Everyday Checking", "checking");
        createSalary(checking);
        createAccount("Brokerage", "investment");

        assertThat(plans("null").get("reason").asText()).isEqualTo("no_debts");
    }

    @Test
    void threePlansAreMeasuredOnTheSameDayAndSpendTheSame() throws Exception {
        household();

        JsonNode view = plans("{}");

        assertThat(view.get("reason").isNull()).isTrue();
        // The account the extra does most in: the brokerage at 6%, ahead of savings at 4.10%.
        assertThat(view.get("investAccountId").asLong()).isEqualTo(brokerage);
        assertThat(view.get("growthBp").asInt()).isEqualTo(600);
        assertThat(view.get("years").asInt()).isEqualTo(10);
        assertThat(view.get("splitBp").asInt()).isEqualTo(5000);
        assertThat(view.get("freedMoves").asBoolean()).isTrue();
        assertThat(view.get("monthlyCents").asLong()).isEqualTo(41_200 + 30_000);
        assertThat(view.get("choices")).hasSize(2);
        assertThat(view.get("points")).hasSize(120);

        JsonNode all = plan(view, "invest_all");
        JsonNode first = plan(view, "pay_off_first");
        JsonNode split = plan(view, "split");
        // Paid off first, the loan is gone well inside ten years and everything after is invested.
        assertThat(first.get("owedCents").asLong()).isZero();
        assertThat(first.get("debtFreeDate").asText()).isEqualTo("2028-08-15");
        assertThat(all.get("owedCents").asLong()).isZero();
        assertThat(first.get("interestCents").asLong()).isLessThan(split.get("interestCents").asLong());
        assertThat(split.get("interestCents").asLong()).isLessThan(all.get("interestCents").asLong());
        // Better off than today: what is invested plus the debt paid down, so every line starts near $0, not at minus the debt.
        assertThat(view.get("owedTodayCents").asLong()).isEqualTo(1_486_000);
        for (JsonNode plan : view.get("plans")) {
            assertThat(plan.get("paidDownCents").asLong()).isEqualTo(1_486_000 - plan.get("owedCents").asLong());
            assertThat(plan.get("betterOffCents").asLong()).isEqualTo(plan.get("investedCents").asLong() + plan.get("paidDownCents").asLong());
        }
        JsonNode month1 = view.get("points").get(0);
        assertThat(month1.get("investAllCents").asLong())
                .isEqualTo(month1.get("investAllInvestedCents").asLong() + 1_486_000 - month1.get("investAllOwedCents").asLong());
        // At 6.90% against 6% growth, paying off first comes out ahead, and so does the best split.
        assertThat(view.get("best").asText()).isEqualTo("pay_off_first");
        assertThat(view.get("bestSplitBp").asInt()).isEqualTo(10_000);
        JsonNode last = view.get("points").get(119);
        assertThat(last.get("payOffFirstCents").asLong()).isEqualTo(first.get("betterOffCents").asLong());
    }

    @Test
    void everyChoiceCanBeChanged() throws Exception {
        household();

        JsonNode view = plans("{\"investAccountId\":%d,\"splitBp\":2500,\"growthBp\":1000,\"years\":7,\"freedMoves\":false}".formatted(savings));

        assertThat(view.get("investAccountId").asLong()).isEqualTo(savings);
        assertThat(view.get("growthBp").asInt()).isEqualTo(1000);
        assertThat(view.get("accountGrowthBp").asInt()).isEqualTo(410);
        assertThat(view.get("years").asInt()).isEqualTo(7);
        assertThat(view.get("points")).hasSize(84);
        assertThat(plan(view, "split").get("debtShareBp").asInt()).isEqualTo(2500);
        // At 10% growth against 6.90%, investing comes out ahead.
        assertThat(view.get("best").asText()).isEqualTo("invest_all");
        assertThat(view.get("freedMoves").asBoolean()).isFalse();
    }

    @Test
    void aFreedPaymentGoingBackToTheBudgetInvestsLess() throws Exception {
        household();

        long kept = plan(plans("{}"), "pay_off_first").get("putInCents").asLong();
        long returned = plan(plans("{\"freedMoves\":false}"), "pay_off_first").get("putInCents").asLong();

        assertThat(returned).isLessThan(kept);
    }

    @Test
    void settingsThatCannotBeRightAreRefused() throws Exception {
        household();

        assertThat(server.post("/api/planner", "{\"extraCents\":30000,\"years\":10,\"plans\":{\"splitBp\":10001}}", cookie).error())
                .contains("from 0% to 100%");
        assertThat(server.post("/api/planner", "{\"extraCents\":30000,\"years\":10,\"plans\":{\"years\":31}}", cookie).error())
                .contains("1 to 30 years");
        assertThat(server.post("/api/planner", "{\"extraCents\":30000,\"years\":10,\"plans\":{\"growthBp\":-1}}", cookie).status())
                .isEqualTo(400);
    }
}
