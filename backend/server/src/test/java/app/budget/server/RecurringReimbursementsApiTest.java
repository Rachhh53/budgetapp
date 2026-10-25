package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Paying back recurring payments (Rachel, 2026-10-24: "only payments I flag and a set amount defaulted to the payment being
 * flagged"; the money comes back a set number of days after, into an account chosen on the item, and is tracked like a
 * one-off expense). The server's clock says it is 2026-10-01.
 */
class RecurringReimbursementsApiTest extends ApiTestBase {

    long checking;
    long hsa;
    long therapy;
    String september;

    @BeforeEach
    void setUp() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        hsa = createAccount("Health Savings", "savings");
        enterBalance(checking, "2026-09-01", 500000);
        enterBalance(hsa, "2026-09-01", 0);
        createSalary(checking);
        therapy = created("/api/items", """
                {"name":"Therapy with Dr. Okafor","kind":"bill","amountCents":15000,"payFromAccountId":%d,"autopay":false,
                 "schedule":{"frequency":"monthly","day1":15,"startDate":"2026-01-01"}}""".formatted(checking));
        september = "/api/items/" + therapy + "/occurrences/2026-09-15";
        server.put(september, "{\"paid\":true,\"paidDate\":\"2026-09-16\"}", cookie);
    }

    private Reply flagSeptember(String body) throws Exception {
        return server.put(september + "/reimbursement", body, cookie);
    }

    private JsonNode flaggedWithTheWayBack() throws Exception {
        Reply reply = flagSeptember("{\"intoAccountId\":%d,\"daysAfter\":14,\"reimbursedBy\":\"HSA\"}".formatted(hsa));
        assertThat(reply.status()).as(reply.body().toString()).isEqualTo(200);
        return reply.data().get("payment");
    }

    /** What moved the HSA from September through October, as its Transactions card lists it. */
    private List<JsonNode> paidBackLines() throws Exception {
        JsonNode lines = server.get("/api/accounts/" + hsa + "/transactions?from=2026-09-01&to=2026-10-31", cookie).data();
        List<JsonNode> paidBack = new ArrayList<>();
        lines.forEach(line -> {
            if (line.get("kind").asText().equals("paid_back")) {
                paidBack.add(line);
            }
        });
        return paidBack;
    }

    private long paidBack(long cents, String date) throws Exception {
        return created("/api/entries", """
                {"kind":"money_in","date":"%s","amountCents":%d,"description":"HSA claim","accountId":%d,"reimbursesPaymentId":%d}"""
                .formatted(date, cents, hsa, flaggedWithTheWayBack().get("id").asLong()));
    }

    @Test
    void flaggingAPaymentTakesItsAmountAndPlansTheMoneyBackTheItemsDaysAfterIt() throws Exception {
        JsonNode payment = flaggedWithTheWayBack();

        assertThat(payment.get("name").asText()).isEqualTo("Therapy with Dr. Okafor");
        assertThat(payment.get("date").asText()).isEqualTo("2026-09-16");
        assertThat(payment.get("amountCents").asLong()).isEqualTo(15000);
        assertThat(payment.get("reimbursement").get("status").asText()).isEqualTo("to_submit");
        assertThat(payment.get("reimbursement").get("owedCents").asLong()).isEqualTo(15000);
        // Paid Sep 16, back about 14 days later, into the HSA.
        assertThat(payment.get("expectedOn").asText()).isEqualTo("2026-09-30");
        assertThat(payment.get("intoAccountId").asLong()).isEqualTo(hsa);
        List<JsonNode> lines = paidBackLines();
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().get("amountCents").asLong()).isEqualTo(15000);
        assertThat(lines.getFirst().get("label").asText()).isEqualTo("Paid back: Therapy with Dr. Okafor");
        assertThat(lines.getFirst().get("unpaid").asBoolean()).isTrue();
        // Only the flagged payment: October's is not paid back.
        JsonNode item = server.get("/api/items/" + therapy + "/reimbursement", cookie).data();
        assertThat(item.get("settings").get("daysAfter").asInt()).isEqualTo(14);
        assertThat(item.get("payments")).hasSize(1);
    }

    @Test
    void thePayPeriodListsMoneyStillToComeBackEvenWhenItIsLate() throws Exception {
        flaggedWithTheWayBack();

        // Due back Sep 30, before the current period (Oct 1–14): still owed, so it is listed there.
        JsonNode current = server.get("/api/pay-periods/2026-10-01", cookie).data();
        JsonNode later = server.get("/api/pay-periods/2026-10-15", cookie).data();

        assertThat(current.get("moneyBack")).hasSize(1);
        assertThat(current.get("moneyBack").get(0).get("owedCents").asLong()).isEqualTo(15000);
        assertThat(later.get("moneyBack")).isEmpty();
    }

    @Test
    void aSetAmountCanBeLessThanThePaymentAndChangedLater() throws Exception {
        flaggedWithTheWayBack();

        Reply changed = flagSeptember("{\"amountCents\":12000}");

        assertThat(changed.status()).isEqualTo(200);
        assertThat(changed.data().get("payment").get("amountCents").asLong()).isEqualTo(12000);
        assertThat(changed.data().get("payment").get("paymentCents").asLong()).isEqualTo(15000);
        assertThat(paidBackLines().getFirst().get("amountCents").asLong()).isEqualTo(12000);
        assertThat(flagSeptember("{\"amountCents\":0}").status()).isEqualTo(400);
    }

    @Test
    void theFirstPaymentFlaggedAsksWhereTheMoneyComesBackInto() throws Exception {
        Reply reply = flagSeptember("{}");

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.error()).isEqualTo("Please choose the account it comes back into.");
        assertThat(flagSeptember("{\"intoAccountId\":%d,\"daysAfter\":400}".formatted(hsa)).status()).isEqualTo(400);
    }

    @Test
    void moneyThatComesBackShrinksWhatIsPlannedAndMovesItAlong() throws Exception {
        paidBack(5000, "2026-10-01");

        JsonNode part = server.get(september + "/reimbursement", cookie).data();
        assertThat(part.get("payment").get("reimbursement").get("paidBackCents").asLong()).isEqualTo(5000);
        assertThat(part.get("payment").get("reimbursement").get("owedCents").asLong()).isEqualTo(10000);
        assertThat(part.get("payment").get("reimbursement").get("status").asText()).isEqualTo("submitted");
        assertThat(part.get("paybacks")).hasSize(1);
        assertThat(paidBackLines().getFirst().get("amountCents").asLong()).isEqualTo(10000);

        paidBack(10000, "2026-10-01");

        JsonNode all = server.get(september + "/reimbursement", cookie).data().get("payment").get("reimbursement");
        assertThat(all.get("status").asText()).isEqualTo("reimbursed");
        assertThat(paidBackLines()).isEmpty();
    }

    @Test
    void moneyComingInSaysWhichPaymentItPaysBack() throws Exception {
        long entry = paidBack(5000, "2026-10-01");

        JsonNode detail = server.get("/api/entries/" + entry, cookie).data();

        assertThat(detail.get("entry").get("reimbursesPaymentId").asLong()).isEqualTo(flaggedWithTheWayBack().get("id").asLong());
        assertThat(detail.get("reimbursesPayment").get("name").asText()).isEqualTo("Therapy with Dr. Okafor");
    }

    @Test
    void itMovesThroughTheSameStepsAsAOneOffExpense() throws Exception {
        flaggedWithTheWayBack();

        Reply sent = server.put(september + "/reimbursement/status",
                "{\"status\":\"submitted\",\"submittedOn\":\"2026-09-20\",\"to\":\"HSA\"}", cookie);

        assertThat(sent.status()).isEqualTo(200);
        JsonNode standing = sent.data().get("payment").get("reimbursement");
        assertThat(standing.get("status").asText()).isEqualTo("submitted");
        assertThat(standing.get("submittedOn").asText()).isEqualTo("2026-09-20");
        // Marked reimbursed, nothing more is planned to come.
        server.put(september + "/reimbursement/status", "{\"status\":\"reimbursed\"}", cookie);
        assertThat(paidBackLines()).isEmpty();
        assertThat(server.put(september + "/reimbursement/status", "{\"status\":\"none\"}", cookie).status()).isEqualTo(400);
    }

    @Test
    void takingTheFlagOffLeavesTheMoneyThatCameBackPayingNothingBack() throws Exception {
        long entry = paidBack(5000, "2026-10-01");

        assertThat(server.delete(september + "/reimbursement", cookie).status()).isEqualTo(200);

        assertThat(server.get(september + "/reimbursement", cookie).status()).isEqualTo(404);
        JsonNode money = server.get("/api/entries/" + entry, cookie).data().get("entry");
        assertThat(money.get("amountCents").asLong()).isEqualTo(5000);
        assertThat(money.get("reimbursesPaymentId").isNull()).isTrue();
        assertThat(paidBackLines()).isEmpty();
    }

    @Test
    void aBucketCanPayItBack() throws Exception {
        long medical = server.post("/api/buckets", "{\"accountId\":%d,\"name\":\"Medical\",\"color\":\"#be185d\",\"startingCents\":20000}"
                .formatted(checking), cookie).data().get("bucket").get("id").asLong();
        long id = flaggedWithTheWayBack().get("id").asLong();

        Reply moved = server.post("/api/buckets/" + medical + "/moves-out", """
                {"date":"2026-10-01","amountCents":15000,"note":"Therapy back","reimbursesPaymentId":%d}""".formatted(id), cookie);

        assertThat(moved.status()).as(moved.body().toString()).isEqualTo(201);
        JsonNode detail = server.get(september + "/reimbursement", cookie).data();
        assertThat(detail.get("movesOut")).hasSize(1);
        assertThat(detail.get("payment").get("reimbursement").get("status").asText()).isEqualTo("reimbursed");
    }

    @Test
    void flaggedPaymentsAreListedByYearWithExpenses() throws Exception {
        flaggedWithTheWayBack();

        JsonNode listed = server.get("/api/payment-reimbursements?year=2026", cookie).data();

        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("itemId").asLong()).isEqualTo(therapy);
        assertThat(server.get("/api/payment-reimbursements?year=2025", cookie).data()).isEmpty();
        assertThat(server.get("/api/expense-years", cookie).data().get("years").get(0).asInt()).isEqualTo(2026);
        assertThat(server.get("/api/payment-reimbursements?year=soon", cookie).status()).isEqualTo(400);
    }

    @Test
    void reportsSayWhereAFlaggedBillStands() throws Exception {
        flaggedWithTheWayBack();

        JsonNode report = server.post("/api/reports/run", """
                {"definition":{"range":"custom","from":"2026-09-01","to":"2026-09-30","include":["bills"],"groupBy":"month",
                 "show":"table","tagIds":[],"categoryIds":[],"accountIds":[],"payees":[],"reimbursement":["to_submit"]}}""", cookie).data();

        assertThat(report.get("rows")).hasSize(1);
        assertThat(report.get("rows").get(0).get("reimbursement").asText()).isEqualTo("to_submit");
    }

    @Test
    void onlyABillOrBudgetThatIsThereCanBeFlagged() throws Exception {
        long savings = created("/api/items", """
                {"name":"To savings","kind":"transfer","amountCents":5000,"payFromAccountId":%d,"payToAccountId":%d,
                 "schedule":{"frequency":"monthly","day1":15,"startDate":"2026-01-01"}}""".formatted(checking, hsa));
        String body = "{\"intoAccountId\":%d,\"daysAfter\":14}".formatted(hsa);

        assertThat(server.put("/api/items/" + savings + "/occurrences/2026-09-15/reimbursement", body, cookie).status()).isEqualTo(409);
        assertThat(server.put("/api/items/9999/occurrences/2026-09-15/reimbursement", body, cookie).status()).isEqualTo(404);
        server.put("/api/items/" + therapy + "/occurrences/2026-11-15", "{\"skipped\":true}", cookie);
        assertThat(server.put("/api/items/" + therapy + "/occurrences/2026-11-15/reimbursement", body, cookie).status()).isEqualTo(409);
    }

    @Test
    void anAccountMoneyComesBackIntoCannotBeDeleted() throws Exception {
        flaggedWithTheWayBack();

        assertThat(server.delete("/api/accounts/" + hsa, cookie).status()).isEqualTo(409);
    }

    @Test
    void needsASession() throws Exception {
        assertThat(server.get("/api/items/" + therapy + "/reimbursement", null).status()).isEqualTo(401);
        assertThat(server.put(september + "/reimbursement", "{}", null).status()).isEqualTo(401);
        assertThat(server.get("/api/payment-reimbursements", null).status()).isEqualTo(401);
    }
}
