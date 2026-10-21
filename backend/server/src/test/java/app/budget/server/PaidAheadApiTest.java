package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Paid ahead (spec §4.6): a $25 weekly course on Mondays from Sep 7, $100 paid for it on Sep 1.
 * "Today" is 2026-10-01; pay periods are the 1st–14th and the 15th–end. Sep 7, 14, 21 and 28 have
 * happened and used the $100, so Oct 5 is the first session it doesn't cover.
 */
class PaidAheadApiTest extends ApiTestBase {

    long checking;
    long course;

    @BeforeEach
    void addCourse() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        createSalary(checking);
        course = created("/api/items", courseItem(""));
    }

    private String courseItem(String extra) {
        return """
                {"name":"Pottery course","kind":"bill","amountCents":2500,"payFromAccountId":%d,"paidAhead":true,
                 "schedule":{"frequency":"weekly","startDate":"2026-09-07","weekendRule":"none"}%s}"""
                .formatted(checking, extra);
    }

    private String path() {
        return "/api/items/" + course + "/paid-ahead";
    }

    private Reply payment(String body) throws Exception {
        return server.post(path() + "/payments", body, cookie);
    }

    private JsonNode standing() throws Exception {
        return server.get(path(), cookie).data();
    }

    private JsonNode period(String start) throws Exception {
        return server.get("/api/pay-periods/" + start, cookie).data();
    }

    private static final String HUNDRED = "{\"date\":\"2026-09-01\",\"amountCents\":10000}";

    @Nested
    class Auth {
        @Test
        void everyPaidAheadRouteRequiresASession() throws Exception {
            assertThat(server.get(path(), null).status()).isEqualTo(401);
            assertThat(server.post(path() + "/payments", HUNDRED, null).status()).isEqualTo(401);
            assertThat(standing().get("payments")).isEmpty();
        }
    }

    @Test
    void sessionsUseUpWhatWasPaidWithoutMovingMoney() throws Exception {
        Reply paid = payment(HUNDRED);

        assertThat(paid.status()).isEqualTo(201);
        assertThat(paid.changed()).isTrue();
        JsonNode standing = paid.data();
        assertThat(standing.get("paidCents").asLong()).isEqualTo(10000);
        assertThat(standing.get("usedCents").asLong()).isEqualTo(10000);
        assertThat(standing.get("leftCents").asLong()).isZero();
        assertThat(standing.get("coversThrough").asText()).isEqualTo("2026-09-28");
        assertThat(standing.get("firstUncovered").asText()).isEqualTo("2026-10-05");
        assertThat(standing.get("used")).hasSize(4);
        assertThat(standing.get("used").get(0).get("date").asText()).isEqualTo("2026-09-28");
        assertThat(standing.get("used").get(0).get("covered").asBoolean()).isTrue();
        JsonNode next = standing.get("upcoming").get(0);
        assertThat(next.get("date").asText()).isEqualTo("2026-10-05");
        assertThat(next.get("covered").asBoolean()).isFalse();
        assertThat(standing.get("upcoming")).hasSize(3);
        assertThat(server.get("/api/items/" + course, cookie).data().get("paidAhead").asBoolean()).isTrue();

        // Sessions are not bills: nothing is due in a period for them.
        assertThat(period("2026-10-01").get("occurrences")).isEmpty();
    }

    @Test
    void thePaymentIsAOneOffFiledAsTheItem() throws Exception {
        JsonNode payment = payment("{\"date\":\"2026-09-01\",\"amountCents\":10000,\"note\":\"Fall term\"}")
                .data().get("payments").get(0);

        JsonNode entry = server.get("/api/entries/" + payment.get("entryId").asLong(), cookie).data().get("entry");
        assertThat(entry.get("kind").asText()).isEqualTo("money_out");
        assertThat(entry.get("description").asText()).isEqualTo("Pottery course");
        assertThat(entry.get("amountCents").asLong()).isEqualTo(10000);
        assertThat(entry.get("accountId").asLong()).isEqualTo(checking);
        assertThat(payment.get("accountId").asLong()).isEqualTo(checking);

        // Removing the one-off takes the payment with it.
        assertThat(server.delete("/api/entries/" + payment.get("entryId").asLong(), cookie).status()).isEqualTo(200);
        assertThat(standing().get("payments")).isEmpty();
        assertThat(standing().get("paidCents").asLong()).isZero();
    }

    @Test
    void theCurrentPeriodWarnsWhenItRunsOutAndAnotherPaymentClearsIt() throws Exception {
        payment(HUNDRED);

        JsonNode warning = period("2026-10-01").get("paidAheadRunningOut").get(0);
        assertThat(warning.get("itemId").asLong()).isEqualTo(course);
        assertThat(warning.get("name").asText()).isEqualTo("Pottery course");
        assertThat(warning.get("leftCents").asLong()).isZero();
        assertThat(warning.get("firstUncovered").asText()).isEqualTo("2026-10-05");
        assertThat(warning.get("payFromAccountId").asLong()).isEqualTo(checking);
        assertThat(period("2026-10-15").get("paidAheadRunningOut")).isEmpty();

        payment("{\"date\":\"2026-10-01\",\"amountCents\":5000}");

        assertThat(period("2026-10-01").get("paidAheadRunningOut")).isEmpty();
        assertThat(period("2026-10-15").get("paidAheadRunningOut").get(0).get("firstUncovered").asText())
                .isEqualTo("2026-10-19");
    }

    @Test
    void aSkippedSessionUsesNothing() throws Exception {
        payment(HUNDRED);

        assertThat(server.put("/api/items/" + course + "/occurrences/2026-09-14", "{\"skipped\":true}", cookie).status())
                .isEqualTo(200);

        JsonNode standing = standing();
        assertThat(standing.get("usedCents").asLong()).isEqualTo(7500);
        assertThat(standing.get("leftCents").asLong()).isEqualTo(2500);
        assertThat(standing.get("coversThrough").asText()).isEqualTo("2026-10-05");
        JsonNode skipped = standing.get("used").get(2);
        assertThat(skipped.get("date").asText()).isEqualTo("2026-09-14");
        assertThat(skipped.get("skipped").asBoolean()).isTrue();
        assertThat(skipped.get("covered").asBoolean()).isFalse();
    }

    @Test
    void sessionsPastWhatWasPaidLeaveItShort() throws Exception {
        payment("{\"date\":\"2026-09-01\",\"amountCents\":5000}");

        JsonNode standing = standing();
        assertThat(standing.get("leftCents").asLong()).isEqualTo(-5000);
        assertThat(standing.get("firstUncovered").asText()).isEqualTo("2026-09-21");
        // Already out before this period began, so the current period still says so.
        assertThat(period("2026-10-01").get("paidAheadRunningOut")).hasSize(1);
    }

    @Test
    void annualCostsCountWhatWasPaidThenTheSessionsNothingCovers() throws Exception {
        payment(HUNDRED);

        JsonNode months = server.get("/api/items/" + course + "/by-month", cookie).data().get("months");

        // Sep: the $100 paid, not its four sessions again. Oct: Oct 5, 12, 19 and 26, still to be paid for.
        assertThat(months.get(7).get("amountCents").asLong()).isZero();
        assertThat(months.get(8).get("amountCents").asLong()).isEqualTo(10000);
        assertThat(months.get(9).get("amountCents").asLong()).isEqualTo(10000);
        JsonNode costs = server.get("/api/annual-costs", cookie).data();
        assertThat(costs.get("rows").get(0).get("name").asText()).isEqualTo("Pottery course");
    }

    @Test
    void refusesAPaymentThatCannotBeRight() throws Exception {
        Reply noDate = payment("{\"amountCents\":10000}");
        assertThat(noDate.status()).isEqualTo(400);
        assertThat(noDate.error()).isEqualTo("Please choose the day it was paid.");

        Reply nothing = payment("{\"date\":\"2026-09-01\",\"amountCents\":0}");
        assertThat(nothing.status()).isEqualTo(400);
        assertThat(nothing.error()).isEqualTo("Please enter what was paid, more than $0.00.");

        assertThat(payment("{\"date\":\"2026-09-01\",\"amountCents\":-100}").status()).isEqualTo(400);

        Reply noAccount = payment("{\"date\":\"2026-09-01\",\"amountCents\":10000,\"accountId\":999999}");
        assertThat(noAccount.status()).isEqualTo(400);
        assertThat(noAccount.error()).isEqualTo("Please choose the account it was paid from.");

        assertThat(server.get("/api/items/999999/paid-ahead", cookie).status()).isEqualTo(404);
        assertThat(standing().get("payments")).isEmpty();
    }

    @Test
    void onlySomethingPaidAheadTakesPaymentsMadeAhead() throws Exception {
        long rent = created("/api/items", """
                {"name":"Rent","kind":"bill","amountCents":140000,"payFromAccountId":%d,
                 "schedule":{"frequency":"monthly","day1":1,"startDate":"2026-01-01"}}""".formatted(checking));

        Reply refused = server.post("/api/items/" + rent + "/paid-ahead/payments", HUNDRED, cookie);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(server.get("/api/items/" + rent + "/paid-ahead", cookie).status()).isEqualTo(409);
    }

    @Test
    void anItemIsPaidAheadOrBilledLaterButNotBoth() throws Exception {
        Reply both = server.post("/api/items", courseItem(",\"billedLater\":true"), cookie);
        assertThat(both.status()).isEqualTo(400);
        assertThat(both.error()).isEqualTo("Something can be paid ahead or billed later, not both. Please choose one.");

        Reply yearly = server.post("/api/items", courseItem(",\"amountMode\":\"annual\""), cookie);
        assertThat(yearly.status()).isEqualTo(400);
        assertThat(yearly.error())
                .isEqualTo("Something paid ahead is used up session by session. Please enter what each session costs, not a yearly total.");
    }
}
