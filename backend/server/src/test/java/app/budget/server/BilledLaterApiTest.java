package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Billed later (spec §4.6): weekly therapy at $120 a visit, billed now and then. "Today" is
 * 2026-10-01; pay periods are the 1st–14th and the 15th–end. Visits every Tuesday from Sep 1:
 * Sep 1, 8, 15, 22 and 29 have happened, $600 not billed yet.
 */
class BilledLaterApiTest extends ApiTestBase {

    long checking;
    long therapy;

    @BeforeEach
    void addTherapy() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        createSalary(checking);
        therapy = created("/api/items", therapyItem(""));
    }

    private String therapyItem(String extra) {
        return """
                {"name":"Therapy","kind":"bill","amountCents":12000,"payFromAccountId":%d,"billedLater":true,
                 "schedule":{"frequency":"weekly","startDate":"2026-09-01","weekendRule":"none"}%s}"""
                .formatted(checking, extra);
    }

    private JsonNode period(String start) throws Exception {
        return server.get("/api/pay-periods/" + start, cookie).data();
    }

    private Reply invoice(String body) throws Exception {
        return server.post("/api/items/" + therapy + "/invoices", body, cookie);
    }

    private static final String SEPTEMBER = """
            {"invoiceDate":"2026-10-01","dueDate":"2026-10-20","coversThrough":"2026-09-30","amountCents":61000}""";

    private long outOf(JsonNode period) {
        for (JsonNode account : period.get("accounts")) {
            if (account.get("accountId").asLong() == checking) {
                return account.get("outCents").asLong();
            }
        }
        throw new AssertionError("no checking account in the period");
    }

    @Nested
    class Auth {
        @Test
        void everyInvoiceRouteRequiresASession() throws Exception {
            long id = invoice(SEPTEMBER).data().get("invoice").get("id").asLong();
            String path = "/api/items/" + therapy + "/invoices";

            assertThat(server.get(path, null).status()).isEqualTo(401);
            assertThat(server.post(path, SEPTEMBER, null).status()).isEqualTo(401);
            assertThat(server.put(path + "/" + id, "{\"paid\":true}", null).status()).isEqualTo(401);
            assertThat(server.delete(path + "/" + id, null).status()).isEqualTo(401);
            assertThat(server.get(path, cookie).data().get("invoices")).hasSize(1);
        }
    }

    @Test
    void visitsBuildUpOnTheCurrentPeriodWithoutMovingMoney() throws Exception {
        JsonNode october = period("2026-10-01");

        assertThat(october.get("occurrences")).isEmpty();
        JsonNode unbilled = october.get("unbilled").get(0);
        assertThat(unbilled.get("name").asText()).isEqualTo("Therapy");
        assertThat(unbilled.get("visits").asInt()).isEqualTo(5);
        assertThat(unbilled.get("amountCents").asLong()).isEqualTo(60000);
        assertThat(unbilled.get("since").asText()).isEqualTo("2026-09-01");
        assertThat(unbilled.get("through").asText()).isEqualTo("2026-09-29");
        assertThat(outOf(october)).isZero();
        // Only the current period carries it.
        assertThat(period("2026-10-15").get("unbilled")).isEmpty();
        assertThat(server.get("/api/items/" + therapy, cookie).data().get("billedLater").asBoolean()).isTrue();
    }

    @Test
    void anInvoiceIsPaidLikeABillAndClearsWhatItCovers() throws Exception {
        Reply added = invoice(SEPTEMBER);

        assertThat(added.status()).isEqualTo(201);
        assertThat(added.changed()).isTrue();
        JsonNode invoice = added.data().get("invoice");
        assertThat(invoice.get("amountCents").asLong()).isEqualTo(61000);
        assertThat(invoice.get("projectedCents").asLong()).isEqualTo(60000);
        assertThat(added.data().get("periodStart").asText()).isEqualTo("2026-10-15");

        JsonNode october = period("2026-10-01");
        assertThat(october.get("unbilled")).isEmpty();
        JsonNode later = period("2026-10-15");
        JsonNode bill = later.get("occurrences").get(0);
        assertThat(bill.get("invoiceId").asLong()).isEqualTo(invoice.get("id").asLong());
        assertThat(bill.get("coversThrough").asText()).isEqualTo("2026-09-30");
        assertThat(bill.get("amountCents").asLong()).isEqualTo(61000);
        assertThat(bill.get("projectedCents").asLong()).isEqualTo(60000);
        assertThat(bill.get("estimate").asBoolean()).isFalse();
        assertThat(bill.get("paid").asBoolean()).isFalse();
        assertThat(outOf(later)).isEqualTo(61000);

        long id = invoice.get("id").asLong();
        Reply paid = server.put("/api/items/" + therapy + "/invoices/" + id,
                "{\"paid\":true,\"paidDate\":\"2026-10-18\",\"confirmationCode\":\"TH-88\"}", cookie);
        assertThat(paid.status()).isEqualTo(200);
        JsonNode afterPaying = period("2026-10-15").get("occurrences").get(0);
        assertThat(afterPaying.get("paid").asBoolean()).isTrue();
        assertThat(afterPaying.get("paidDate").asText()).isEqualTo("2026-10-18");
        assertThat(afterPaying.get("confirmationCode").asText()).isEqualTo("TH-88");
    }

    @Test
    void anInvoiceLeftWithoutAnAmountBillsWhatTheVisitsCameTo() throws Exception {
        // A visit that did not happen is skipped like any occurrence, and is not billed.
        assertThat(server.put("/api/items/" + therapy + "/occurrences/2026-09-15", "{\"skipped\":true}", cookie).status())
                .isEqualTo(200);
        assertThat(period("2026-10-01").get("unbilled").get(0).get("amountCents").asLong()).isEqualTo(48000);

        JsonNode invoice = invoice("{\"invoiceDate\":\"2026-09-30\",\"dueDate\":\"2026-10-10\"}").data().get("invoice");

        assertThat(invoice.get("amountCents").asLong()).isEqualTo(48000);
        assertThat(invoice.get("coversThrough").asText()).isEqualTo("2026-09-30");
    }

    @Test
    void refusesAnInvoiceThatCannotBeRight() throws Exception {
        Reply nothingToBill = invoice("{\"invoiceDate\":\"2026-08-15\",\"dueDate\":\"2026-08-30\"}");
        assertThat(nothingToBill.status()).isEqualTo(400);
        assertThat(nothingToBill.error()).isEqualTo("There are no visits waiting to be billed up to that day. Please check the dates.");

        Reply backwards = invoice("{\"invoiceDate\":\"2026-10-01\",\"dueDate\":\"2026-09-20\"}");
        assertThat(backwards.status()).isEqualTo(400);
        assertThat(backwards.error()).isEqualTo("Please choose a due date on or after the invoice date.");

        assertThat(invoice("{\"dueDate\":\"2026-10-20\"}").status()).isEqualTo(400);
        assertThat(invoice("{\"invoiceDate\":\"2026-09-10\",\"dueDate\":\"2026-10-20\"}").status()).isEqualTo(201);
        Reply sameDueDate = invoice("{\"invoiceDate\":\"2026-10-01\",\"dueDate\":\"2026-10-20\"}");
        assertThat(sameDueDate.status()).isEqualTo(409);

        long rent = created("/api/items", """
                {"name":"Rent","kind":"bill","amountCents":140000,"payFromAccountId":%d,
                 "schedule":{"frequency":"monthly","day1":1,"startDate":"2026-01-01"}}""".formatted(checking));
        assertThat(server.post("/api/items/" + rent + "/invoices", SEPTEMBER, cookie).status()).isEqualTo(409);
    }

    @Test
    void onlyABillPerVisitCanBeBilledLater() throws Exception {
        Reply yearly = server.post("/api/items", therapyItem(",\"amountMode\":\"annual\""), cookie);
        assertThat(yearly.status()).isEqualTo(400);
        assertThat(yearly.error()).contains("enter what each visit costs");

        // A budget keeps none of it: the form sends whatever was last ticked.
        long groceries = created("/api/items", therapyItem("").replace("\"bill\"", "\"budget\"").replace("Therapy", "Groceries"));
        assertThat(server.get("/api/items/" + groceries, cookie).data().get("billedLater").asBoolean()).isFalse();
    }

    @Test
    void removingTheNewestInvoiceLeavesItsVisitsNotBilledAgain() throws Exception {
        long first = invoice("{\"invoiceDate\":\"2026-09-12\",\"dueDate\":\"2026-09-25\"}").data().get("invoice").get("id").asLong();
        long second = invoice(SEPTEMBER).data().get("invoice").get("id").asLong();

        Reply older = server.delete("/api/items/" + therapy + "/invoices/" + first, cookie);
        assertThat(older.status()).isEqualTo(409);

        assertThat(server.delete("/api/items/" + therapy + "/invoices/" + second, cookie).status()).isEqualTo(200);
        JsonNode unbilled = period("2026-10-01").get("unbilled").get(0);
        assertThat(unbilled.get("visits").asInt()).isEqualTo(3);
        assertThat(unbilled.get("since").asText()).isEqualTo("2026-09-15");

        // Undo puts it back.
        assertThat(server.post("/api/undo", "{}", cookie).data().get("undone").asBoolean()).isTrue();
        assertThat(period("2026-10-01").get("unbilled")).isEmpty();
    }

    @Test
    void savedUpForVisitByVisitAndPaidOutOfTheBucket() throws Exception {
        enterBalance(checking, "2026-08-01", 500000);
        long bucket = server.post("/api/buckets", """
                {"accountId":%d,"name":"Therapy","color":"#0f766e","openedOn":"2026-08-01"}""".formatted(checking), cookie)
                .data().get("bucket").get("id").asLong();
        assertThat(server.put("/api/items/" + therapy, therapyItem(",\"effectiveFrom\":\"2026-09-01\",\"saveUpBucketId\":" + bucket), cookie).status())
                .isEqualTo(200);

        // Five visits set $600 aside, and nothing has left the account.
        JsonNode beforeInvoice = server.get("/api/buckets/" + bucket, cookie).data().get("bucket");
        assertThat(beforeInvoice.get("balanceCents").asLong()).isEqualTo(60000);

        invoice(SEPTEMBER);

        // The invoice takes out what its visits set aside ($600), never its own $610; the rest is money not in a bucket.
        JsonNode afterDue = period("2026-10-15");
        long bucketEnd = 0;
        for (JsonNode one : afterDue.get("buckets")) {
            if (one.get("bucketId").asLong() == bucket) {
                bucketEnd = one.get("endingCents").asLong();
            }
        }
        // Two more visits (Oct 6 and 13) are set aside before the invoice is paid; Oct 20 and 27 after.
        assertThat(bucketEnd).isEqualTo(4 * 12000);
    }

    @Test
    void reportsCountVisitsNotBilledYetAsStillToCome() throws Exception {
        invoice("{\"invoiceDate\":\"2026-09-12\",\"dueDate\":\"2026-09-25\",\"amountCents\":24000}");

        JsonNode report = server.post("/api/reports/run", """
                {"definition":{"range":"custom","from":"2026-09-01","to":"2026-09-30","include":["bills"],"groupBy":"month",
                 "show":"table","tagIds":[],"categoryIds":[],"accountIds":[],"payees":[]}}""", cookie).data();

        // The invoice ($240, due Sep 25) and the three visits after it that nobody has billed yet ($360).
        assertThat(report.get("totalCents").asLong()).isEqualTo(60000);
        assertThat(report.get("estimatedCents").asLong()).isEqualTo(36000);
    }
}
