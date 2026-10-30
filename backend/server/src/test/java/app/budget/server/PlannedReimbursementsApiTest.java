package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Planning money to come back (migration V47; Rachel, 2026-10-25: "there is no way to plan for the reimbursement in that
 * account as well so I look short for the year"; she chose to flag each pack, to plan one-off expenses too, and "partials
 * are possible"). The server's clock says it is 2026-10-01.
 */
class PlannedReimbursementsApiTest extends ApiTestBase {

    long checking;
    long fsa;

    @BeforeEach
    void accounts() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        fsa = createAccount("Flexible Spending", "savings");
        enterBalance(checking, "2026-09-01", 500000);
        enterBalance(fsa, "2026-09-01", 0);
        createSalary(checking);
    }

    /** The planned money back into {@code account} from September through {@code to}, as its Transactions card lists it. */
    private List<JsonNode> paidBackLines(long account, LocalDate to) throws Exception {
        JsonNode lines = server.get("/api/accounts/" + account + "/transactions?from=2026-09-01&to=" + to, cookie).data();
        List<JsonNode> paidBack = new ArrayList<>();
        lines.forEach(line -> {
            if (line.get("kind").asText().equals("paid_back")) {
                paidBack.add(line);
            }
        });
        return paidBack;
    }

    @Nested
    class APackPaidAhead {

        long training;
        String nextPlanned;
        LocalDate nextDue;

        @BeforeEach
        void aPackBought() throws Exception {
            training = created("/api/items", """
                    {"name":"Personal training with Priya","kind":"bill","amountCents":127500,"payFromAccountId":%d,"paidAhead":true,
                     "packSessions":20,"schedule":{"frequency":"weekly","startDate":"2026-08-03","weekendRule":"none","weekdays":[1,4]}}"""
                    .formatted(checking));
            assertThat(server.post("/api/items/" + training + "/paid-ahead/payments", "{\"date\":\"2026-08-03\",\"amountCents\":127500}",
                    cookie).status()).isEqualTo(201);
            JsonNode standing = server.get("/api/items/" + training + "/paid-ahead", cookie).data();
            nextPlanned = standing.get("nextPackPlanned").asText();
            nextDue = LocalDate.parse(standing.get("nextPack").asText());
        }

        private Reply flag(String planned, String body) throws Exception {
            return server.put("/api/items/" + training + "/paid-ahead/packs/" + planned + "/reimbursement", body, cookie);
        }

        private Reply flagNextWithTheWayBack(long cents) throws Exception {
            Reply reply = flag(nextPlanned, "{\"amountCents\":%d,\"intoAccountId\":%d,\"daysAfter\":14,\"reimbursedBy\":\"FSA\"}"
                    .formatted(cents, fsa));
            assertThat(reply.status()).as(reply.body().toString()).isEqualTo(200);
            return reply;
        }

        @Test
        void partOfAPackStillToBuyIsPlannedBackTheItemsDaysAfterItsDue() throws Exception {
            JsonNode packs = flagNextWithTheWayBack(100000).data().get("packs");

            assertThat(packs).hasSize(1);
            assertThat(packs.get(0).get("packNumber").asInt()).isEqualTo(1);
            assertThat(packs.get(0).get("priceCents").asLong()).isEqualTo(127500);
            assertThat(packs.get(0).get("amountCents").asLong()).isEqualTo(100000);
            assertThat(packs.get(0).get("expectedOn").asText()).isEqualTo(nextDue.plusDays(14).toString());
            List<JsonNode> lines = paidBackLines(fsa, nextDue.plusDays(30));
            assertThat(lines).hasSize(1);
            assertThat(lines.getFirst().get("amountCents").asLong()).isEqualTo(100000);
            assertThat(lines.getFirst().get("date").asText()).isEqualTo(nextDue.plusDays(14).toString());
            assertThat(lines.getFirst().get("label").asText()).isEqualTo("Paid back: Personal training with Priya");
        }

        @Test
        void thePayPeriodItComesBackInListsIt() throws Exception {
            flagNextWithTheWayBack(127500);
            LocalDate back = nextDue.plusDays(14);
            // Paydays are the 1st and 15th.
            LocalDate start = back.getDayOfMonth() >= 15 ? back.withDayOfMonth(15) : back.withDayOfMonth(1);

            JsonNode moneyBack = server.get("/api/pay-periods/" + start, cookie).data().get("moneyBack");

            assertThat(moneyBack).hasSize(1);
            assertThat(moneyBack.get(0).get("owedCents").asLong()).isEqualTo(127500);
            assertThat(moneyBack.get(0).get("source").get("itemId").asLong()).isEqualTo(training);
            assertThat(moneyBack.get(0).get("source").get("dueDate").asText()).isEqualTo(nextDue.toString());
        }

        @Test
        void buyingThePackPutsItsFlagOnThePaymentPlannedFromTheDayPaid() throws Exception {
            flagNextWithTheWayBack(100000);

            Reply paid = server.post("/api/items/" + training + "/paid-ahead/payments",
                    "{\"date\":\"2026-10-01\",\"amountCents\":127500}", cookie);

            assertThat(paid.status()).isEqualTo(201);
            assertThat(server.get("/api/items/" + training + "/reimbursement", cookie).data().get("packs")).isEmpty();
            long entryId = paid.data().get("payments").get(0).get("entryId").asLong();
            JsonNode stands = server.get("/api/entries/" + entryId, cookie).data().get("entry").get("reimbursement");
            assertThat(stands.get("status").asText()).isEqualTo("to_submit");
            assertThat(stands.get("to").asText()).isEqualTo("FSA");
            assertThat(stands.get("amountCents").asLong()).isEqualTo(100000);
            assertThat(stands.get("owedCents").asLong()).isEqualTo(100000);
            assertThat(stands.get("intoAccountId").asLong()).isEqualTo(fsa);
            assertThat(stands.get("expectedOn").asText()).isEqualTo("2026-10-15");
            // Still planned once, now from the payment.
            List<JsonNode> lines = paidBackLines(fsa, LocalDate.of(2026, 12, 31));
            assertThat(lines).hasSize(1);
            assertThat(lines.getFirst().get("source").get("entryId").asLong()).isEqualTo(entryId);
        }

        @Test
        void aFlagStaysWithItsOwnPackAsTheOnesBeforeItAreBought() throws Exception {
            JsonNode packs = server.get("/api/items/" + training + "/upcoming", cookie).data();
            String second = null;
            for (JsonNode occurrence : packs) {
                if (occurrence.get("plannedPack").asBoolean() && !occurrence.get("scheduledDate").asText().equals(nextPlanned)) {
                    second = occurrence.get("scheduledDate").asText();
                    break;
                }
            }
            assertThat(second).isNotNull();
            flagNextWithTheWayBack(127500);
            assertThat(flag(second, "{\"amountCents\":50000}").status()).isEqualTo(200);

            server.post("/api/items/" + training + "/paid-ahead/payments", "{\"date\":\"2026-10-01\",\"amountCents\":127500}", cookie);

            JsonNode left = server.get("/api/items/" + training + "/reimbursement", cookie).data().get("packs");
            assertThat(left).hasSize(1);
            assertThat(left.get(0).get("packNumber").asInt()).isEqualTo(1);
            assertThat(left.get(0).get("amountCents").asLong()).isEqualTo(50000);
        }

        @Test
        void aFlagCanBeTakenOff() throws Exception {
            flagNextWithTheWayBack(127500);

            Reply off = server.delete("/api/items/" + training + "/paid-ahead/packs/" + nextPlanned + "/reimbursement", cookie);

            assertThat(off.status()).isEqualTo(200);
            assertThat(off.data().get("packs")).isEmpty();
            assertThat(paidBackLines(fsa, nextDue.plusDays(30))).isEmpty();
            assertThat(server.delete("/api/items/" + training + "/paid-ahead/packs/" + nextPlanned + "/reimbursement", cookie)
                    .status()).isEqualTo(404);
        }

        @Test
        void refusesMoreThanThePackAWayBackLeftOutAndAPackNotPlanned() throws Exception {
            Reply noWayBack = flag(nextPlanned, "{\"amountCents\":100000}");
            assertThat(noWayBack.status()).isEqualTo(400);
            assertThat(noWayBack.error()).isEqualTo("Please choose the account it comes back into.");
            flagNextWithTheWayBack(127500);

            Reply tooMuch = flag(nextPlanned, "{\"amountCents\":127501}");
            assertThat(tooMuch.status()).isEqualTo(400);
            assertThat(tooMuch.error()).isEqualTo("Please enter how much comes back, more than $0.00 and no more than the pack.");
            assertThat(flag("2026-08-04", "{\"amountCents\":100}").status()).isEqualTo(404);
        }

        @Test
        void thePackRoutesRequireASession() throws Exception {
            String path = "/api/items/" + training + "/paid-ahead/packs/" + nextPlanned + "/reimbursement";
            assertThat(server.put(path, "{\"amountCents\":100}", null).status()).isEqualTo(401);
            assertThat(server.delete(path, null).status()).isEqualTo(401);
        }
    }

    /** Rachel, 2026-10-25: "there is not option to take it out of the fsa. some will come out of there, some might just be reimbursed from no account". */
    @Nested
    class OutOfOneOfHerAccounts {

        long training;
        String nextPlanned;
        LocalDate nextDue;

        @BeforeEach
        void aPackPaidBackOutOfTheFsa() throws Exception {
            training = created("/api/items", """
                    {"name":"Personal training with Priya","kind":"bill","amountCents":127500,"payFromAccountId":%d,"paidAhead":true,
                     "packSessions":20,"schedule":{"frequency":"weekly","startDate":"2026-08-03","weekendRule":"none","weekdays":[1,4]}}"""
                    .formatted(checking));
            server.post("/api/items/" + training + "/paid-ahead/payments", "{\"date\":\"2026-08-03\",\"amountCents\":127500}", cookie);
            JsonNode standing = server.get("/api/items/" + training + "/paid-ahead", cookie).data();
            nextPlanned = standing.get("nextPackPlanned").asText();
            nextDue = LocalDate.parse(standing.get("nextPack").asText());
        }

        private Reply flagNext(String from) throws Exception {
            return server.put("/api/items/" + training + "/paid-ahead/packs/" + nextPlanned + "/reimbursement",
                    "{\"amountCents\":127500,\"intoAccountId\":%d,\"daysAfter\":14,\"fromAccountId\":%s}".formatted(checking, from), cookie);
        }

        @Test
        void itIsPlannedAsATransferOutOfThatAccountIntoTheOneItComesBackInto() throws Exception {
            Reply flagged = flagNext(String.valueOf(fsa));

            assertThat(flagged.status()).as(flagged.body().toString()).isEqualTo(200);
            assertThat(flagged.data().get("settings").get("fromAccountId").asLong()).isEqualTo(fsa);
            assertThat(flagged.data().get("packs").get(0).get("fromAccountId").asLong()).isEqualTo(fsa);
            List<JsonNode> into = paidBackLines(checking, nextDue.plusDays(30));
            List<JsonNode> outOf = paidBackLines(fsa, nextDue.plusDays(30));
            assertThat(into).hasSize(1);
            assertThat(into.getFirst().get("amountCents").asLong()).isEqualTo(127500);
            assertThat(outOf).hasSize(1);
            assertThat(outOf.getFirst().get("amountCents").asLong()).isEqualTo(-127500);
            assertThat(outOf.getFirst().get("label").asText()).isEqualTo("Pays back: Personal training with Priya");
        }

        @Test
        void fromOutsideNothingLeavesAnyOfHerAccounts() throws Exception {
            flagNext("null");

            assertThat(paidBackLines(checking, nextDue.plusDays(30))).hasSize(1);
            assertThat(paidBackLines(fsa, nextDue.plusDays(30))).isEmpty();
        }

        @Test
        void theBoughtPackKeepsWhereItComesOutOf() throws Exception {
            flagNext(String.valueOf(fsa));

            long entryId = server.post("/api/items/" + training + "/paid-ahead/payments", "{\"date\":\"2026-10-01\",\"amountCents\":127500}", cookie)
                    .data().get("payments").get(0).get("entryId").asLong();

            assertThat(server.get("/api/entries/" + entryId, cookie).data().get("entry").get("reimbursement").get("fromAccountId").asLong())
                    .isEqualTo(fsa);
        }

        @Test
        void aTransferOutOfTheFsaPaysAnExpenseBack() throws Exception {
            long copay = created("/api/entries", """
                    {"kind":"money_out","date":"2026-09-28","amountCents":8500,"description":"Copay","accountId":%d,"reimbursementStatus":"to_submit"}"""
                    .formatted(checking));
            Reply planned = server.put("/api/entries/" + copay + "/reimbursement", """
                    {"status":"submitted","intoAccountId":%d,"expectedOn":"2026-10-20","fromAccountId":%d}""".formatted(checking, fsa), cookie);
            assertThat(planned.data().get("reimbursement").get("fromAccountId").asLong()).isEqualTo(fsa);
            assertThat(paidBackLines(fsa, LocalDate.of(2026, 10, 31))).hasSize(1);

            created("/api/entries", """
                    {"kind":"transfer","date":"2026-10-05","amountCents":8500,"description":"FSA claim","accountId":%d,"toAccountId":%d,
                     "reimbursesEntryId":%d}""".formatted(fsa, checking, copay));

            JsonNode stands = server.get("/api/entries/" + copay, cookie).data().get("entry").get("reimbursement");
            assertThat(stands.get("status").asText()).isEqualTo("reimbursed");
            assertThat(paidBackLines(fsa, LocalDate.of(2026, 10, 31))).isEmpty();
            assertThat(paidBackLines(checking, LocalDate.of(2026, 10, 31))).isEmpty();
        }

        @Test
        void itCannotComeOutOfTheAccountItComesBackInto() throws Exception {
            Reply same = flagNext(String.valueOf(checking));

            assertThat(same.status()).isEqualTo(400);
            assertThat(same.error()).isEqualTo("Please choose an account it comes out of other than the one it comes back into.");
        }
    }

    @Nested
    class AOneOffExpense {

        long copay;

        @BeforeEach
        void anExpenseToSubmit() throws Exception {
            copay = created("/api/entries", """
                    {"kind":"money_out","date":"2026-09-28","amountCents":8500,"description":"Copay","payee":"Valley Pediatrics",
                     "accountId":%d,"reimbursementStatus":"to_submit"}""".formatted(checking));
        }

        private JsonNode stands() throws Exception {
            return server.get("/api/entries/" + copay, cookie).data().get("entry").get("reimbursement");
        }

        private Reply plan(String body) throws Exception {
            return server.put("/api/entries/" + copay + "/reimbursement", body, cookie);
        }

        @Test
        void markedAsItIsAddedAllOfItIsPlannedBackAboutAMonthLaterIntoItsOwnAccount() throws Exception {
            JsonNode stands = stands();

            assertThat(stands.get("amountCents").asLong()).isEqualTo(8500);
            assertThat(stands.get("intoAccountId").asLong()).isEqualTo(checking);
            assertThat(stands.get("expectedOn").asText()).isEqualTo("2026-10-28");
            List<JsonNode> lines = paidBackLines(checking, LocalDate.of(2026, 10, 31));
            assertThat(lines).hasSize(1);
            assertThat(lines.getFirst().get("amountCents").asLong()).isEqualTo(8500);
            assertThat(lines.getFirst().get("label").asText()).isEqualTo("Paid back: Copay");
        }

        @Test
        void partOfItCanComeBackIntoAnotherAccountOnAnotherDay() throws Exception {
            Reply planned = plan("{\"status\":\"submitted\",\"submittedOn\":\"2026-09-30\",\"amountCents\":6000,\"intoAccountId\":%d,\"expectedOn\":\"2026-10-20\"}"
                    .formatted(fsa));

            assertThat(planned.status()).as(planned.body().toString()).isEqualTo(200);
            JsonNode stands = planned.data().get("reimbursement");
            assertThat(stands.get("amountCents").asLong()).isEqualTo(6000);
            assertThat(stands.get("owedCents").asLong()).isEqualTo(6000);
            assertThat(paidBackLines(checking, LocalDate.of(2026, 10, 31))).isEmpty();
            List<JsonNode> lines = paidBackLines(fsa, LocalDate.of(2026, 10, 31));
            assertThat(lines).hasSize(1);
            assertThat(lines.getFirst().get("date").asText()).isEqualTo("2026-10-20");
            assertThat(lines.getFirst().get("amountCents").asLong()).isEqualTo(6000);
        }

        @Test
        void sayingOnlyWhereItStandsKeepsThePlan() throws Exception {
            plan("{\"status\":\"to_submit\",\"amountCents\":6000,\"intoAccountId\":%d,\"expectedOn\":\"2026-10-20\"}".formatted(fsa));

            JsonNode stands = plan("{\"status\":\"submitted\",\"submittedOn\":\"2026-10-01\"}").data().get("reimbursement");

            assertThat(stands.get("amountCents").asLong()).isEqualTo(6000);
            assertThat(stands.get("intoAccountId").asLong()).isEqualTo(fsa);
            assertThat(stands.get("expectedOn").asText()).isEqualTo("2026-10-20");
            // All of it again is said with the expense's own amount.
            assertThat(plan("{\"status\":\"submitted\",\"amountCents\":8500}").data().get("reimbursement").get("amountCents").asLong())
                    .isEqualTo(8500);
        }

        @Test
        void markedFromItsOwnFormItIsPlannedTooAndMadeSmallerAllOfItComesBack() throws Exception {
            long gift = created("/api/entries", """
                    {"kind":"money_out","date":"2026-09-20","amountCents":4000,"description":"Team lunch","accountId":%d}"""
                    .formatted(checking));
            Reply marked = server.put("/api/entries/" + gift, """
                    {"kind":"money_out","date":"2026-09-20","amountCents":4000,"description":"Team lunch","accountId":%d,
                     "reimbursementStatus":"to_submit"}""".formatted(checking), cookie);
            assertThat(marked.status()).isEqualTo(200);
            assertThat(marked.data().get("reimbursement").get("expectedOn").asText()).isEqualTo("2026-10-20");

            plan("{\"status\":\"to_submit\",\"amountCents\":6000}");
            server.put("/api/entries/" + copay, """
                    {"kind":"money_out","date":"2026-09-28","amountCents":5000,"description":"Copay","payee":"Valley Pediatrics",
                     "accountId":%d}""".formatted(checking), cookie);

            assertThat(stands().get("amountCents").asLong()).isEqualTo(5000);
            assertThat(stands().get("owedCents").asLong()).isEqualTo(5000);
        }

        @Test
        void whenThePartComesBackItIsPaidBackAndNoLongerPlanned() throws Exception {
            plan("{\"status\":\"submitted\",\"amountCents\":6000,\"intoAccountId\":%d,\"expectedOn\":\"2026-10-20\"}".formatted(fsa));

            created("/api/entries", """
                    {"kind":"money_in","date":"2026-10-02","amountCents":6000,"description":"FSA claim","accountId":%d,"reimbursesEntryId":%d}"""
                    .formatted(fsa, copay));

            assertThat(stands().get("status").asText()).isEqualTo("reimbursed");
            assertThat(stands().get("owedCents").asLong()).isZero();
            assertThat(paidBackLines(fsa, LocalDate.of(2026, 10, 31))).isEmpty();
        }

        @Test
        void nobodyPayingItBackTakesThePlanAway() throws Exception {
            plan("{\"status\":\"none\"}");

            assertThat(stands().get("intoAccountId").isNull()).isTrue();
            assertThat(stands().get("expectedOn").isNull()).isTrue();
            assertThat(paidBackLines(checking, LocalDate.of(2026, 10, 31))).isEmpty();
        }

        @Test
        void refusesMoreThanTheExpenseAndAnAccountWithoutADay() throws Exception {
            Reply tooMuch = plan("{\"status\":\"to_submit\",\"amountCents\":8501}");
            assertThat(tooMuch.status()).isEqualTo(400);
            assertThat(tooMuch.error()).isEqualTo("Please enter how much comes back, more than $0.00 and no more than the expense.");

            Reply noDay = plan("{\"status\":\"to_submit\",\"intoAccountId\":%d}".formatted(fsa));
            assertThat(noDay.status()).isEqualTo(400);
            assertThat(noDay.error()).isEqualTo("Please choose about when it comes back.");
        }
    }
}
