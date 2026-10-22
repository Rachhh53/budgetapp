package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Paying yourself back for an expense out of a bucket (Rachel, 2026-10-21: "if I am reimbursing myself from a
 * bucket, this doesn't work"). Checking holds $1,214.38 with $300.00 of it in a Medical bucket; a $85.00 copay was
 * paid from checking and is waiting to be paid back. "Today" is 2026-10-01.
 */
class BucketPaybackApiTest extends ApiTestBase {

    long checking;
    long medical;
    long copay;

    @BeforeEach
    void addACopayAndABucket() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        enterBalance(checking, "2026-10-01", 121438);
        Reply bucket = server.post("/api/buckets",
                "{\"accountId\":%d,\"name\":\"Medical\",\"color\":\"#b45309\",\"startingCents\":30000}".formatted(checking), cookie);
        medical = bucket.data().get("bucket").get("id").asLong();
        copay = created("/api/entries", """
                {"kind":"money_out","date":"2026-09-28","amountCents":8500,"description":"Copay","payee":"Valley Pediatrics",
                 "accountId":%d,"reimbursementStatus":"to_submit"}""".formatted(checking));
    }

    private Reply payBack(long cents, Object expenseId) throws Exception {
        return server.post("/api/buckets/" + medical + "/moves-out",
                "{\"date\":\"2026-10-01\",\"amountCents\":%d,\"note\":\"Reimbursement: Valley Pediatrics\",\"reimbursesEntryId\":%s}"
                        .formatted(cents, expenseId),
                cookie);
    }

    private JsonNode copay() throws Exception {
        return server.get("/api/entries/" + copay, cookie).data();
    }

    @Test
    void allOfItMarksTheExpenseReimbursed() throws Exception {
        long id = payBack(8500, copay).data().get("id").asLong();

        JsonNode detail = copay();
        JsonNode stands = detail.get("entry").get("reimbursement");
        assertThat(stands.get("status").asText()).isEqualTo("reimbursed");
        assertThat(stands.get("paidBackCents").asLong()).isEqualTo(8500);
        assertThat(stands.get("owedCents").asLong()).isZero();
        assertThat(detail.get("movesOut")).hasSize(1);
        assertThat(detail.get("movesOut").get(0).get("id").asLong()).isEqualTo(id);
        assertThat(detail.get("movesOut").get(0).get("bucketId").asLong()).isEqualTo(medical);
    }

    @Test
    void partOfItCountsWithMoneyThatCameInForTheRest() throws Exception {
        payBack(5000, copay);
        assertThat(copay().get("entry").get("reimbursement").get("status").asText()).isEqualTo("submitted");

        created("/api/entries", """
                {"kind":"money_in","date":"2026-10-01","amountCents":3500,"description":"HSA payout","accountId":%d,
                 "reimbursesEntryId":%d}""".formatted(checking, copay));

        JsonNode stands = copay().get("entry").get("reimbursement");
        assertThat(stands.get("paidBackCents").asLong()).isEqualTo(8500);
        assertThat(stands.get("status").asText()).isEqualTo("reimbursed");
    }

    @Test
    void changingTheMoveToPayNothingBackTakesItOff() throws Exception {
        long id = payBack(5000, copay).data().get("id").asLong();

        Reply changed = server.put("/api/buckets/" + medical + "/moves-out/" + id,
                "{\"date\":\"2026-10-01\",\"amountCents\":5000,\"note\":\"Back to spending\"}", cookie);

        assertThat(changed.status()).isEqualTo(200);
        assertThat(copay().get("movesOut")).isEmpty();
        assertThat(copay().get("entry").get("reimbursement").get("paidBackCents").asLong()).isZero();
    }

    @Test
    void deletingTheExpenseLeavesTheMovePayingNothingBack() throws Exception {
        createSalary(checking); // for a pay period to list the move in
        long id = payBack(8500, copay).data().get("id").asLong();

        assertThat(server.delete("/api/entries/" + copay, cookie).status()).isEqualTo(200);

        JsonNode move = server.get("/api/pay-periods/2026-10-01", cookie).data().get("movesOut").get(0);
        assertThat(move.get("id").asLong()).isEqualTo(id);
        assertThat(move.get("reimbursesEntryId").isNull()).isTrue();
    }

    @Test
    void onlyAnExpenseCanBePaidBack() throws Exception {
        long moneyIn = created("/api/entries",
                "{\"kind\":\"money_in\",\"date\":\"2026-10-01\",\"amountCents\":100,\"description\":\"Refund\",\"accountId\":%d}"
                        .formatted(checking));

        for (Object notAnExpense : new Object[] {moneyIn, 99999}) {
            Reply reply = payBack(1000, notAnExpense);
            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.error()).isEqualTo("Please choose the expense this pays back from the list.");
        }
    }

    @Test
    void needsTheAppUnlocked() throws Exception {
        assertThat(server.post("/api/buckets/" + medical + "/moves-out", "{\"reimbursesEntryId\":%d}".formatted(copay), null).status())
                .isEqualTo(401);
        assertThat(copay().get("movesOut")).isEmpty();
    }
}
