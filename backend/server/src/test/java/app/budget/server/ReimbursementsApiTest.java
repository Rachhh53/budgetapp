package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Expenses that are paid back (spec §4.7) over real HTTP: the status, the day sent in, who to,
 * and the money that comes back. "Today" is Thursday 2026-10-01.
 */
class ReimbursementsApiTest extends ApiTestBase {

    long checking;
    long expense;

    @BeforeEach
    void addAnExpense() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        expense = created("/api/entries", spent("Valley Pediatrics", 8500, ""));
    }

    private String spent(String payee, long cents, String extra) {
        return "{\"kind\":\"money_out\",\"date\":\"2026-09-28\",\"amountCents\":%d,\"description\":\"Copay\",\"payee\":\"%s\",\"accountId\":%d%s}"
                .formatted(cents, payee, checking, extra);
    }

    private String paidBack(long cents, Long expenseId) {
        return "{\"kind\":\"money_in\",\"date\":\"2026-10-01\",\"amountCents\":%d,\"description\":\"HSA payout\",\"accountId\":%d%s}"
                .formatted(cents, checking, expenseId == null ? "" : ",\"reimbursesEntryId\":" + expenseId);
    }

    private Reply stands(String body) throws Exception {
        return server.put("/api/entries/" + expense + "/reimbursement", body, cookie);
    }

    private JsonNode reimbursement() throws Exception {
        return server.get("/api/entries/" + expense, cookie).data().get("entry").get("reimbursement");
    }

    @Test
    void theReimbursementRoutesRequireASession() throws Exception {
        assertThat(server.get("/api/entries/" + expense, null).status()).isEqualTo(401);
        assertThat(server.put("/api/entries/" + expense + "/reimbursement", "{\"status\":\"reimbursed\"}", null).status()).isEqualTo(401);
        assertThat(server.get("/api/expense-years", null).status()).isEqualTo(401);
        assertThat(server.get("/api/payees", null).status()).isEqualTo(401);
        // Nothing was changed by that.
        assertThat(reimbursement().get("status").asText()).isEqualTo("none");
    }

    @Test
    void anExpenseStartsAsNotPaidBackByAnyone() throws Exception {
        JsonNode stands = reimbursement();

        assertThat(stands.get("status").asText()).isEqualTo("none");
        assertThat(stands.get("submittedOn").isNull()).isTrue();
        assertThat(stands.get("to").isNull()).isTrue();
        assertThat(stands.get("paidBackCents").asLong()).isZero();
        assertThat(stands.get("owedCents").asLong()).isZero();
    }

    @Test
    void anExpenseCanBeAddedAlreadyMarkedToSubmit() throws Exception {
        long id = created("/api/entries", spent("CVS Pharmacy", 9520, ",\"reimbursementStatus\":\"to_submit\""));

        JsonNode stands = server.get("/api/entries/" + id, cookie).data().get("entry").get("reimbursement");

        assertThat(stands.get("status").asText()).isEqualTo("to_submit");
        assertThat(stands.get("owedCents").asLong()).isEqualTo(9520);
    }

    @Test
    void keepsTheDayItWasSentInAndWhoTo() throws Exception {
        Reply reply = stands("{\"status\":\"submitted\",\"submittedOn\":\"2026-09-29\",\"to\":\" HSA \"}");

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.changed()).isTrue();
        JsonNode stands = reply.data().get("reimbursement");
        assertThat(stands.get("status").asText()).isEqualTo("submitted");
        assertThat(stands.get("submittedOn").asText()).isEqualTo("2026-09-29");
        assertThat(stands.get("to").asText()).isEqualTo("HSA");
        assertThat(stands.get("owedCents").asLong()).isEqualTo(8500);
        assertThat(server.get("/api/payees", cookie).data().get("reimbursers").get(0).asText()).isEqualTo("HSA");
    }

    @Test
    void somethingStillToSendInHasNoDaySentAndNothingPaidBackHasNoOneNamed() throws Exception {
        assertThat(stands("{\"status\":\"to_submit\",\"submittedOn\":\"2026-09-29\",\"to\":\"HSA\"}")
                .data().get("reimbursement").get("submittedOn").isNull()).isTrue();

        JsonNode none = stands("{\"status\":\"none\",\"submittedOn\":\"2026-09-29\",\"to\":\"HSA\"}").data().get("reimbursement");

        assertThat(none.get("submittedOn").isNull()).isTrue();
        assertThat(none.get("to").isNull()).isTrue();
    }

    @Test
    void aPartPaymentIsCountedAndWhatIsStillOwedIsShown() throws Exception {
        stands("{\"status\":\"submitted\",\"to\":\"HSA\"}");

        long payback = created("/api/entries", paidBack(6000, expense));

        JsonNode detail = server.get("/api/entries/" + expense, cookie).data();
        assertThat(detail.get("entry").get("reimbursement").get("status").asText()).isEqualTo("submitted");
        assertThat(detail.get("entry").get("reimbursement").get("paidBackCents").asLong()).isEqualTo(6000);
        assertThat(detail.get("entry").get("reimbursement").get("reimbursedCents").asLong()).isEqualTo(6000);
        assertThat(detail.get("entry").get("reimbursement").get("owedCents").asLong()).isEqualTo(2500);
        assertThat(detail.get("paybacks")).hasSize(1);
        assertThat(detail.get("paybacks").get(0).get("id").asLong()).isEqualTo(payback);
        // The money that came in says what it pays back.
        JsonNode moneyIn = server.get("/api/entries/" + payback, cookie).data();
        assertThat(moneyIn.get("entry").get("reimbursesEntryId").asLong()).isEqualTo(expense);
        assertThat(moneyIn.get("reimburses").get("payee").asText()).isEqualTo("Valley Pediatrics");
        assertThat(moneyIn.get("entry").get("reimbursement").isNull()).isTrue();
    }

    @Test
    void moneyCoveringAllOfItMarksItReimbursedInTheSameStepAndUndoTakesBothBack() throws Exception {
        stands("{\"status\":\"submitted\"}");
        created("/api/entries", paidBack(6000, expense));

        created("/api/entries", paidBack(2500, expense));

        assertThat(reimbursement().get("status").asText()).isEqualTo("reimbursed");
        assertThat(reimbursement().get("owedCents").asLong()).isZero();

        server.post("/api/undo", null, cookie);

        assertThat(reimbursement().get("status").asText()).isEqualTo("submitted");
        assertThat(reimbursement().get("paidBackCents").asLong()).isEqualTo(6000);
    }

    @Test
    void moneyComingBackForSomethingNeverSentInMakesItSubmittedAtLeast() throws Exception {
        created("/api/entries", paidBack(1000, expense));

        assertThat(reimbursement().get("status").asText()).isEqualTo("submitted");
    }

    @Test
    void markedReimbursedByHandCountsInFullWithNoMoneyLinked() throws Exception {
        JsonNode stands = stands("{\"status\":\"reimbursed\",\"submittedOn\":\"2026-09-29\"}").data().get("reimbursement");

        assertThat(stands.get("reimbursedCents").asLong()).isEqualTo(8500);
        assertThat(stands.get("owedCents").asLong()).isZero();
    }

    @Test
    void changingWhatMoneyPaysBackSettlesBothExpenses() throws Exception {
        long other = created("/api/entries", spent("Brightside Dental", 6000, ",\"reimbursementStatus\":\"submitted\""));
        long payback = created("/api/entries", paidBack(6000, expense));

        server.put("/api/entries/" + payback, paidBack(6000, other), cookie);

        assertThat(reimbursement().get("paidBackCents").asLong()).isZero();
        JsonNode otherStands = server.get("/api/entries/" + other, cookie).data().get("entry").get("reimbursement");
        assertThat(otherStands.get("status").asText()).isEqualTo("reimbursed");
        // Unlinking it altogether is just leaving the expense out.
        server.put("/api/entries/" + payback, paidBack(6000, null), cookie);
        assertThat(server.get("/api/entries/" + other, cookie).data().get("paybacks")).isEmpty();
    }

    @Test
    void deletingAnExpenseLeavesTheMoneyThatPaidItBackAsPlainMoneyIn() throws Exception {
        long payback = created("/api/entries", paidBack(8500, expense));

        assertThat(server.delete("/api/entries/" + expense, cookie).status()).isEqualTo(200);

        JsonNode moneyIn = server.get("/api/entries/" + payback, cookie).data();
        assertThat(moneyIn.get("entry").get("reimbursesEntryId").isNull()).isTrue();
        assertThat(moneyIn.get("reimburses").isNull()).isTrue();
        // And Undo brings the expense back with its money linked again.
        server.post("/api/undo", null, cookie);
        assertThat(reimbursement().get("paidBackCents").asLong()).isEqualTo(8500);
    }

    @Test
    void anExpenseTurnedIntoATransferStopsBeingOwed() throws Exception {
        long savings = createAccount("Rainy Day Savings", "savings");
        stands("{\"status\":\"submitted\",\"submittedOn\":\"2026-09-29\",\"to\":\"HSA\"}");
        long payback = created("/api/entries", paidBack(1000, expense));

        server.put("/api/entries/" + expense, """
                {"kind":"transfer","date":"2026-09-28","amountCents":8500,"description":"Moved to savings",
                 "accountId":%d,"toAccountId":%d}""".formatted(checking, savings), cookie);

        assertThat(reimbursement().isNull()).isTrue();
        assertThat(server.get("/api/entries/" + payback, cookie).data().get("entry").get("reimbursesEntryId").isNull()).isTrue();
        assertThat(server.get("/api/payees", cookie).data().get("reimbursers")).isEmpty();
    }

    @Test
    void editingAnExpenseLeavesWhereItStandsAlone() throws Exception {
        stands("{\"status\":\"submitted\",\"submittedOn\":\"2026-09-29\",\"to\":\"HSA\"}");

        server.put("/api/entries/" + expense, spent("Valley Pediatrics", 9000, ""), cookie);

        assertThat(reimbursement().get("status").asText()).isEqualTo("submitted");
        assertThat(reimbursement().get("submittedOn").asText()).isEqualTo("2026-09-29");
        assertThat(reimbursement().get("to").asText()).isEqualTo("HSA");
        assertThat(reimbursement().get("owedCents").asLong()).isEqualTo(9000);
    }

    @Test
    void explainsWhatIsMissingOrWrong() throws Exception {
        long payback = created("/api/entries", paidBack(1000, null));

        assertThat(stands("{\"status\":\"paid\"}").error()).isEqualTo("Please choose where this stands from the list.");
        assertThat(stands("{}").status()).isEqualTo(400);
        assertThat(stands("{\"status\":\"submitted\",\"submittedOn\":\"1850-01-01\"}").error())
                .isEqualTo("Please choose the day it was sent in.");
        assertThat(stands("{\"status\":\"submitted\",\"to\":\"" + "x".repeat(121) + "\"}").status()).isEqualTo(400);
        // Money coming in cannot itself be paid back.
        Reply moneyIn = server.put("/api/entries/" + payback + "/reimbursement", "{\"status\":\"submitted\"}", cookie);
        assertThat(moneyIn.status()).isEqualTo(409);
        assertThat(moneyIn.error()).isEqualTo("Only money going out can be paid back.");
        assertThat(server.put("/api/entries/999/reimbursement", "{\"status\":\"submitted\"}", cookie).status()).isEqualTo(404);
        // What money pays back has to be an expense, and not itself.
        assertThat(server.post("/api/entries", paidBack(1000, 999L), cookie).error())
                .isEqualTo("Please choose the expense this pays back from the list.");
        assertThat(server.post("/api/entries", paidBack(1000, payback), cookie).status()).isEqualTo(400);
        assertThat(server.put("/api/entries/" + payback, paidBack(1000, payback), cookie).status()).isEqualTo(400);
        assertThat(server.post("/api/entries", spent("CVS", 100, ",\"reimbursementStatus\":\"maybe\""), cookie).status()).isEqualTo(400);
        assertThat(server.get("/api/entries/999", cookie).status()).isEqualTo(404);
    }

    @Test
    void listsTheYearsWithSpendingAndThePayeesTypedBefore() throws Exception {
        created("/api/entries", spent("CVS Pharmacy", 2360, "").replace("2026-09-28", "2025-03-02"));
        created("/api/entries", spent("cvs pharmacy", 100, ""));
        created("/api/entries", paidBack(100, null).replace("2026-10-01", "2024-01-01"));

        JsonNode years = server.get("/api/expense-years", cookie).data().get("years");
        JsonNode payees = server.get("/api/payees", cookie).data().get("payees");

        // 2024 only has money coming in.
        assertThat(years).hasSize(2);
        assertThat(years.get(0).asInt()).isEqualTo(2026);
        assertThat(years.get(1).asInt()).isEqualTo(2025);
        assertThat(payees).extracting(JsonNode::asText).containsExactly("CVS Pharmacy", "Valley Pediatrics");
    }
}
