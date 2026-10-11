package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Download;
import app.budget.server.TestServer.FilePart;
import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Reports over real HTTP against a temporary database (spec §4.11, §8): the Phase 7 example,
 * "Medical out-of-pocket 2026". "Today" is Thursday 2026-10-01; paydays are the 1st and 15th.
 */
class ReportsApiTest extends ApiTestBase {

    long checking;
    long visa;
    long medical;
    long dental;
    long pets;
    long outOfPocket;
    long taxDeductible;
    long peds;
    long cvs;

    @BeforeEach
    void aYearOfSpending() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        visa = createAccount("Visa", "credit_card");
        createSalary(checking);
        medical = created("/api/categories", "{\"name\":\"Medical\",\"color\":\"#be185d\"}");
        dental = created("/api/categories", "{\"name\":\"Dental\",\"color\":\"#9d174d\",\"parentId\":%d}".formatted(medical));
        pets = created("/api/categories", "{\"name\":\"Pets\",\"color\":\"#a16207\"}");
        outOfPocket = created("/api/tags", "{\"name\":\"Medical – out of pocket\",\"color\":\"#be185d\"}");
        taxDeductible = created("/api/tags", "{\"name\":\"Tax deductible\",\"color\":\"#15803d\"}");

        // Reimbursed in full, by hand.
        long urgentCare = spend("2026-01-20", 12000, "Urgent care", checking, medical, outOfPocket);
        stands(urgentCare, "reimbursed");
        // Sent in, $60.00 of $85.00 back so far.
        peds = spend("2026-09-28", 8500, "Valley Pediatrics", visa, medical, outOfPocket);
        stands(peds, "submitted");
        created("/api/entries", """
                {"kind":"money_in","date":"2026-09-30","amountCents":6000,"description":"HSA payout","accountId":%d,
                 "reimbursesEntryId":%d}""".formatted(checking, peds));
        // Still to send in; two tags, in the sub-category.
        cvs = spend("2026-09-19", 2360, "CVS Pharmacy", checking, dental, outOfPocket, taxDeductible);
        stands(cvs, "to_submit");
        // Not medical at all, and last year's.
        spend("2026-03-29", 6450, "Maple Street Vets", checking, pets);
        spend("2025-11-02", 9900, "Urgent care", checking, medical, outOfPocket);
    }

    private long spend(String date, long cents, String payee, long account, long category, long... tags) throws Exception {
        List<String> ids = new ArrayList<>();
        for (long tag : tags) {
            ids.add(String.valueOf(tag));
        }
        return created("/api/entries", """
                {"kind":"money_out","date":"%s","amountCents":%d,"description":"Visit","payee":"%s","accountId":%d,
                 "categoryId":%d,"tagIds":[%s]}""".formatted(date, cents, payee, account, category, String.join(",", ids)));
    }

    private void stands(long entry, String status) throws Exception {
        server.put("/api/entries/" + entry + "/reimbursement", "{\"status\":\"%s\"}".formatted(status), cookie);
    }

    /** A definition: this year's one-off spending by month, with {@code extra} spliced over the defaults. */
    private static String definition(String extra) {
        String defaults = "\"range\":\"this_year\",\"include\":[\"one_offs\"],\"groupBy\":\"month\",\"show\":\"chart_and_table\"";
        return "{" + (extra.isEmpty() ? defaults : extra + "," + defaults) + "}";
    }

    /** As {@link #definition}, replacing one of the defaults. */
    private static String definition(String key, String value, String extra) {
        return definition(extra).replaceFirst("\"" + key + "\":(\\[[^]]*]|\"[^\"]*\")", "\"" + key + "\":" + value);
    }

    private String medicalOutOfPocket() {
        return definition("\"tagIds\":[%d]".formatted(outOfPocket));
    }

    private JsonNode run(String definition) throws Exception {
        Reply reply = server.post("/api/reports/run", "{\"definition\":" + definition + "}", cookie);
        assertThat(reply.status()).as(reply.body().toString()).isEqualTo(200);
        return reply.data();
    }

    private static JsonNode group(JsonNode report, String label) {
        for (JsonNode group : report.get("groups")) {
            if (group.get("label").asText().equals(label)) {
                return group;
            }
        }
        throw new AssertionError("No group called " + label + " in " + report.get("groups"));
    }

    @Nested
    class Auth {
        @Test
        void everyReportRouteRequiresASession() throws Exception {
            String save = "{\"name\":\"Medical\",\"definition\":" + medicalOutOfPocket() + "}";
            long id = created("/api/reports", save);

            assertThat(server.get("/api/reports", null).status()).isEqualTo(401);
            assertThat(server.post("/api/reports", save.replace("Medical", "Other"), null).status()).isEqualTo(401);
            assertThat(server.put("/api/reports/" + id, save.replace("Medical", "Renamed"), null).status()).isEqualTo(401);
            assertThat(server.delete("/api/reports/" + id, null).status()).isEqualTo(401);
            assertThat(server.post("/api/reports/run", "{\"definition\":" + medicalOutOfPocket() + "}", null).status()).isEqualTo(401);
            Download csv = server.download("/api/reports/export/csv", "{\"definition\":" + medicalOutOfPocket() + "}", null);
            Download pdf = server.download("/api/reports/export/pdf", "{\"definition\":" + medicalOutOfPocket() + "}", null);
            assertThat(csv.status()).isEqualTo(401);
            assertThat(pdf.status()).isEqualTo(401);
            assertThat(new String(csv.body(), StandardCharsets.UTF_8)).doesNotContain("Valley");
            // Nothing was changed by any of those.
            JsonNode saved = server.get("/api/reports", cookie).data();
            assertThat(saved).hasSize(1);
            assertThat(saved.get(0).get("name").asText()).isEqualTo("Medical");
        }
    }

    @Nested
    class Running {
        @Test
        void medicalOutOfPocketThisYearByMonthWithWhatHasComeBack() throws Exception {
            JsonNode report = run(medicalOutOfPocket());

            assertThat(report.get("from").asText()).isEqualTo("2026-01-01");
            assertThat(report.get("to").asText()).isEqualTo("2026-12-31");
            assertThat(report.get("count").asInt()).isEqualTo(3);
            assertThat(report.get("totalCents").asLong()).isEqualTo(12000 + 8500 + 2360);
            assertThat(report.get("reimbursedCents").asLong()).isEqualTo(12000 + 6000);
            assertThat(report.get("waitingCents").asLong()).isEqualTo(2500);
            assertThat(report.get("notSubmittedCents").asLong()).isEqualTo(2360);
            assertThat(report.get("hasReimbursements").asBoolean()).isTrue();
            // Twelve months, the empty ones too, in order.
            assertThat(report.get("groups")).hasSize(12);
            assertThat(report.get("groups").get(0).get("label").asText()).isEqualTo("January 2026");
            assertThat(report.get("groups").get(0).get("key").asText()).isEqualTo("2026-01");
            assertThat(group(report, "January 2026").get("totalCents").asLong()).isEqualTo(12000);
            assertThat(group(report, "February 2026").get("count").asInt()).isZero();
            assertThat(group(report, "September 2026").get("count").asInt()).isEqualTo(2);
            assertThat(group(report, "September 2026").get("totalCents").asLong()).isEqualTo(10860);
            // The lines, oldest first, each saying where it stands.
            JsonNode rows = report.get("rows");
            assertThat(rows).hasSize(3);
            assertThat(rows.get(0).get("payee").asText()).isEqualTo("Urgent care");
            assertThat(rows.get(2).get("payee").asText()).isEqualTo("Valley Pediatrics");
            assertThat(rows.get(2).get("kind").asText()).isEqualTo("one_off");
            assertThat(rows.get(2).get("entryId").asLong()).isEqualTo(peds);
            assertThat(rows.get(2).get("reimbursement").asText()).isEqualTo("submitted");
            assertThat(rows.get(2).get("owedCents").asLong()).isEqualTo(2500);
        }

        @Test
        void runningAReportChangesNothingAndLeavesNothingToUndo() throws Exception {
            Reply reply = server.post("/api/reports/run", "{\"definition\":" + medicalOutOfPocket() + "}", cookie);

            assertThat(reply.changed()).isFalse();
        }

        @Test
        void groupsByCategoryTagPayeeAccountAndYear() throws Exception {
            JsonNode byCategory = run(definition("groupBy", "\"category\"", ""));
            assertThat(byCategory.get("groups")).hasSize(3);
            assertThat(byCategory.get("groups").get(0).get("label").asText()).isEqualTo("Medical");
            assertThat(byCategory.get("groups").get(0).get("color").asText()).isEqualTo("#be185d");
            assertThat(byCategory.get("groups").get(0).get("totalCents").asLong()).isEqualTo(20500);
            assertThat(group(byCategory, "Medical › Dental").get("totalCents").asLong()).isEqualTo(2360);
            assertThat(group(byCategory, "Pets").get("totalCents").asLong()).isEqualTo(6450);

            JsonNode byTag = run(definition("groupBy", "\"tag\"", ""));
            assertThat(group(byTag, "Medical – out of pocket").get("count").asInt()).isEqualTo(3);
            assertThat(group(byTag, "Tax deductible").get("totalCents").asLong()).isEqualTo(2360);
            assertThat(group(byTag, "No tags").get("totalCents").asLong()).isEqualTo(6450);
            // CVS has two tags and is in both groups, but once in the total.
            assertThat(byTag.get("count").asInt()).isEqualTo(4);
            assertThat(byTag.get("totalCents").asLong()).isEqualTo(12000 + 8500 + 2360 + 6450);

            JsonNode byPayee = run(definition("groupBy", "\"payee\"", ""));
            assertThat(byPayee.get("groups").get(0).get("label").asText()).isEqualTo("Urgent care");

            JsonNode byAccount = run(definition("groupBy", "\"account\"", ""));
            assertThat(group(byAccount, "Visa").get("totalCents").asLong()).isEqualTo(8500);

            JsonNode byYear = run(definition("groupBy", "\"year\"",
                    "\"from\":\"2025-01-01\",\"to\":\"2026-12-31\"").replace("this_year", "custom"));
            assertThat(byYear.get("groups")).hasSize(2);
            assertThat(group(byYear, "2025").get("totalCents").asLong()).isEqualTo(9900);
        }

        @Test
        void filtersNarrowTogetherAndAParentCategoryCoversItsSubCategories() throws Exception {
            assertThat(run(definition("\"categoryIds\":[%d]".formatted(medical))).get("count").asInt()).isEqualTo(3);
            assertThat(run(definition("\"categoryIds\":[%d]".formatted(dental))).get("count").asInt()).isEqualTo(1);
            assertThat(run(definition("\"accountIds\":[%d]".formatted(visa))).get("totalCents").asLong()).isEqualTo(8500);
            assertThat(run(definition("\"payees\":[\"cvs pharmacy\",\"Maple Street Vets\"]")).get("count").asInt()).isEqualTo(2);
            assertThat(run(definition("\"reimbursement\":[\"submitted\",\"to_submit\"]")).get("totalCents").asLong()).isEqualTo(10860);
            // Tagged out of pocket AND paid from checking AND still to send in: only CVS.
            JsonNode narrow = run(definition("\"tagIds\":[%d],\"accountIds\":[%d],\"reimbursement\":[\"to_submit\"]"
                    .formatted(outOfPocket, checking)));
            assertThat(narrow.get("rows")).hasSize(1);
            assertThat(narrow.get("rows").get(0).get("entryId").asLong()).isEqualTo(cvs);
        }

        @Test
        void theDatesMoveWithToday() throws Exception {
            assertThat(run(definition("range", "\"last_year\"", "")).get("totalCents").asLong()).isEqualTo(9900);
            JsonNode last90 = run(definition("range", "\"last_90_days\"", ""));
            assertThat(last90.get("from").asText()).isEqualTo("2026-07-04");
            assertThat(last90.get("to").asText()).isEqualTo("2026-10-01");
            assertThat(last90.get("count").asInt()).isEqualTo(2);
            JsonNode last12 = run(definition("range", "\"last_12_months\"", ""));
            assertThat(last12.get("from").asText()).isEqualTo("2025-10-02");
            assertThat(last12.get("count").asInt()).isEqualTo(5);
        }

        @Test
        void billsAndBudgetsAreCountedOnTheDayPaidOrDueAndWhatIsStillToComeIsAnEstimate() throws Exception {
            long electric = created("/api/items", """
                    {"name":"Electric","kind":"bill","amountCents":9400,"payFromAccountId":%d,"categoryId":%d,"tagIds":[%d],
                     "schedule":{"frequency":"monthly","day1":5,"startDate":"2026-08-01"}}""".formatted(checking, pets, taxDeductible));
            created("/api/items", """
                    {"name":"Groceries","kind":"budget","amountCents":30000,"payFromAccountId":%d,
                     "schedule":{"frequency":"monthly","day1":1,"startDate":"2026-09-01"}}""".formatted(checking));
            created("/api/items", """
                    {"name":"To savings","kind":"transfer","amountCents":5000,"payFromAccountId":%d,"payToAccountId":%d,
                     "schedule":{"frequency":"monthly","day1":2,"startDate":"2026-09-01"}}""".formatted(checking, visa));
            // September's bill was paid a day late, at its real amount; October's is skipped.
            server.put("/api/items/" + electric + "/occurrences/2026-09-05",
                    "{\"amountCents\":10150,\"paid\":true,\"paidDate\":\"2026-09-06\"}", cookie);
            server.put("/api/items/" + electric + "/occurrences/2026-10-05", "{\"skipped\":true}", cookie);
            server.upload("/api/items/" + electric + "/occurrences/2026-09-05/attachments", cookie,
                    new FilePart("electric-sept.pdf", SampleFiles.pdf(1, "Electric")));

            JsonNode bills = run(definition("include", "[\"bills\"]", ""));

            // Aug 5, Sep 6 (paid), Nov 5, Dec 5: October was skipped.
            assertThat(bills.get("count").asInt()).isEqualTo(4);
            assertThat(bills.get("totalCents").asLong()).isEqualTo(9400 + 10150 + 9400 + 9400);
            JsonNode september = bills.get("rows").get(1);
            assertThat(september.get("kind").asText()).isEqualTo("bill");
            assertThat(september.get("date").asText()).isEqualTo("2026-09-06");
            assertThat(september.get("scheduledDate").asText()).isEqualTo("2026-09-05");
            assertThat(september.get("itemId").asLong()).isEqualTo(electric);
            assertThat(september.get("amountCents").asLong()).isEqualTo(10150);
            assertThat(september.get("estimate").asBoolean()).isFalse();
            assertThat(september.get("fileCount").asInt()).isEqualTo(1);
            assertThat(september.get("reimbursement").isNull()).isTrue();
            assertThat(bills.get("rows").get(3).get("estimate").asBoolean()).isTrue();
            assertThat(bills.get("estimatedCents").asLong()).isEqualTo(9400 + 9400);
            assertThat(bills.get("hasReimbursements").asBoolean()).isFalse();
            // A bill's tags and category are its item's.
            assertThat(run(definition("include", "[\"bills\"]", "\"tagIds\":[%d]".formatted(taxDeductible))).get("count").asInt())
                    .isEqualTo(4);
            assertThat(run(definition("include", "[\"bills\"]", "\"reimbursement\":[\"submitted\"]")).get("count").asInt()).isZero();

            // Budgets are their own choice, and transfers are never spending.
            JsonNode budgets = run(definition("include", "[\"budgets\"]", ""));
            assertThat(budgets.get("count").asInt()).isEqualTo(4);
            assertThat(budgets.get("rows").get(0).get("kind").asText()).isEqualTo("budget");
            JsonNode everything = run(definition("include", "[\"one_offs\",\"bills\",\"budgets\"]", ""));
            assertThat(everything.get("count").asInt()).isEqualTo(4 + 4 + 4);
            assertThat(everything.get("rows").toString()).doesNotContain("To savings");
        }

        @Test
        void nothingMatchingIsAnEmptyReportNotAnError() throws Exception {
            JsonNode report = run(definition("\"payees\":[\"Nobody at all\"]"));

            assertThat(report.get("count").asInt()).isZero();
            assertThat(report.get("totalCents").asLong()).isZero();
            assertThat(report.get("rows")).isEmpty();
            assertThat(report.get("groups")).hasSize(12);
        }

        @Test
        void explainsWhatIsMissingOrWrong() throws Exception {
            assertError(definition("range", "\"forever\"", ""), "Please choose the dates from the list.");
            assertError(definition("range", "\"custom\"", ""), "Please choose both the first and the last day.");
            assertError(definition("range", "\"custom\"", "\"from\":\"2026-05-01\",\"to\":\"2026-04-01\""),
                    "The last day is before the first. Please check the two dates.");
            assertError(definition("range", "\"custom\"", "\"from\":\"2001-01-01\",\"to\":\"2026-04-01\""),
                    "Please choose a range of dates up to ten years long, between 2000 and 2100.");
            assertError(definition("include", "[]", ""), "Please choose at least one thing to include.");
            assertError(definition("include", "[\"transfers\"]", ""), "Please choose what to include from the list.");
            assertError(definition("groupBy", "\"weekday\"", ""), "Please choose how to group it from the list.");
            assertError(definition("show", "\"pie\"", ""), "Please choose how to show it from the list.");
            assertError(definition("\"tagIds\":[999]"),
                    "This report filters by a tag that is no longer there. Please choose its filters again.");
            assertError(definition("\"categoryIds\":[999]"),
                    "This report filters by a category that is no longer there. Please choose its filters again.");
            assertError(definition("\"accountIds\":[999]"),
                    "This report filters by an account that is no longer there. Please choose its filters again.");
            assertError(definition("\"reimbursement\":[\"lost\"]"), "Please choose where the reimbursement stands from the list.");
            assertThat(server.post("/api/reports/run", "{}", cookie).error()).isEqualTo("Please choose what this report shows.");
            assertThat(server.post("/api/reports/run", "not json", cookie).status()).isEqualTo(400);
        }

        private void assertError(String definition, String message) throws Exception {
            Reply reply = server.post("/api/reports/run", "{\"definition\":" + definition + "}", cookie);
            assertThat(reply.status()).as(definition).isEqualTo(400);
            assertThat(reply.error()).isEqualTo(message);
        }
    }

    @Nested
    class Saving {
        private String named(String name) {
            return "{\"name\":\"%s\",\"definition\":%s}".formatted(name, medicalOutOfPocket());
        }

        @Test
        void savesAReportAndGivesItBackAsItWasAsked() throws Exception {
            Reply reply = server.post("/api/reports", named(" Medical out-of-pocket 2026 "), cookie);

            assertThat(reply.status()).isEqualTo(201);
            assertThat(reply.changed()).isTrue();
            assertThat(reply.data().get("name").asText()).isEqualTo("Medical out-of-pocket 2026");
            JsonNode definition = reply.data().get("definition");
            assertThat(definition.get("range").asText()).isEqualTo("this_year");
            assertThat(definition.get("include").get(0).asText()).isEqualTo("one_offs");
            assertThat(definition.get("tagIds").get(0).asLong()).isEqualTo(outOfPocket);
            assertThat(definition.get("groupBy").asText()).isEqualTo("month");
            assertThat(definition.get("show").asText()).isEqualTo("chart_and_table");
            // What comes back can be sent straight back in: it runs as it stands.
            assertThat(run(definition.toString()).get("count").asInt()).isEqualTo(3);
        }

        @Test
        void listsSavedReportsByNameAndChangesAndDeletesThem() throws Exception {
            long medicalReport = created("/api/reports", named("Medical out-of-pocket 2026"));
            created("/api/reports", named("home repair"));

            JsonNode saved = server.get("/api/reports", cookie).data();
            assertThat(saved).extracting(report -> report.get("name").asText())
                    .containsExactly("home repair", "Medical out-of-pocket 2026");

            String changed = "{\"name\":\"Medical by payee\",\"definition\":%s}"
                    .formatted(definition("groupBy", "\"payee\"", "\"tagIds\":[%d]".formatted(outOfPocket)));
            Reply updated = server.put("/api/reports/" + medicalReport, changed, cookie);
            assertThat(updated.status()).isEqualTo(200);
            assertThat(updated.data().get("name").asText()).isEqualTo("Medical by payee");
            assertThat(updated.data().get("definition").get("groupBy").asText()).isEqualTo("payee");

            assertThat(server.delete("/api/reports/" + medicalReport, cookie).status()).isEqualTo(200);
            assertThat(server.get("/api/reports", cookie).data()).hasSize(1);
            // Undo brings a deleted report back.
            server.post("/api/undo", null, cookie);
            assertThat(server.get("/api/reports", cookie).data()).hasSize(2);
        }

        @Test
        void aNameIsNeededAndCanOnlyBeUsedOnce() throws Exception {
            long first = created("/api/reports", named("Medical"));
            long second = created("/api/reports", named("Home repair"));

            assertThat(server.post("/api/reports", named(""), cookie).error()).isEqualTo("Please add a name for this report.");
            Reply taken = server.post("/api/reports", named("MEDICAL"), cookie);
            assertThat(taken.status()).isEqualTo(409);
            assertThat(taken.error()).isEqualTo("There is already a report called that. Please choose another name.");
            assertThat(server.put("/api/reports/" + second, named("medical"), cookie).status()).isEqualTo(409);
            // Saving a report again under its own name is fine.
            assertThat(server.put("/api/reports/" + first, named("Medical"), cookie).status()).isEqualTo(200);
            assertThat(server.post("/api/reports", named("x".repeat(81)), cookie).status()).isEqualTo(400);
        }

        @Test
        void aReportThatCouldNotRunIsNotSaved() throws Exception {
            String broken = "{\"name\":\"Broken\",\"definition\":%s}".formatted(definition("\"tagIds\":[999]"));

            assertThat(server.post("/api/reports", broken, cookie).status()).isEqualTo(400);
            assertThat(server.post("/api/reports", "{\"name\":\"No definition\"}", cookie).status()).isEqualTo(400);
            assertThat(server.get("/api/reports", cookie).data()).isEmpty();
        }

        @Test
        void anUnknownReportIsNotFound() throws Exception {
            assertThat(server.put("/api/reports/999", named("Medical"), cookie).status()).isEqualTo(404);
            assertThat(server.delete("/api/reports/999", cookie).status()).isEqualTo(404);
            assertThat(server.delete("/api/reports/abc", cookie).status()).isEqualTo(404);
        }
    }

    @Nested
    class Exporting {
        private String export(String extra) {
            return "{\"name\":\"Medical out-of-pocket 2026\",\"definition\":%s%s}".formatted(medicalOutOfPocket(), extra);
        }

        @Test
        void csvHasOneRowPerLineWithAmountsAsPlainNumbers() throws Exception {
            Download csv = server.download("/api/reports/export/csv", export(""), cookie);

            assertThat(csv.status()).isEqualTo(200);
            assertThat(csv.header("Content-Type")).isEqualTo("text/csv; charset=utf-8");
            assertThat(csv.header("Content-Disposition"))
                    .isEqualTo("attachment; filename*=UTF-8''Medical%20out-of-pocket%202026.csv");
            String text = new String(csv.body(), StandardCharsets.UTF_8);
            assertThat(text).startsWith("﻿Date,Description,Paid to,Kind,Category,Tags,Paid from,Amount,Estimate,"
                    + "Reimbursement,Reimbursed,Still owed,Files\r\n");
            String[] lines = text.split("\r\n");
            assertThat(lines).hasSize(4);
            assertThat(lines[1]).isEqualTo(
                    "2026-01-20,Visit,Urgent care,One-off,Medical,Medical – out of pocket,Everyday Checking,120.00,,Reimbursed,120.00,0.00,0");
            assertThat(lines[2]).isEqualTo(
                    "2026-09-19,Visit,CVS Pharmacy,One-off,Medical › Dental,\"Medical – out of pocket, Tax deductible\","
                            + "Everyday Checking,23.60,,To submit,0.00,23.60,0");
            assertThat(lines[3]).contains("Valley Pediatrics", "85.00", "Submitted,60.00,25.00");
        }

        @Test
        void csvNeverLetsTypedTextRunAsAFormulaAndKeepsQuotesAndCommas() throws Exception {
            created("/api/entries", """
                    {"kind":"money_out","date":"2026-06-01","amountCents":100,"description":"=HYPERLINK(\\"http://example.invalid\\")",
                     "payee":"Smith, Jones & \\"Co\\"","accountId":%d,"tagIds":[%d]}""".formatted(checking, outOfPocket));

            String text = new String(server.download("/api/reports/export/csv", export(""), cookie).body(), StandardCharsets.UTF_8);

            assertThat(text).contains("\"'=HYPERLINK(\"\"http://example.invalid\"\")\",\"Smith, Jones & \"\"Co\"\"\"");
        }

        @Test
        void pdfHasTheTotalsTheGroupsAndEveryLine() throws Exception {
            Download pdf = server.download("/api/reports/export/pdf", export(""), cookie);

            assertThat(pdf.status()).isEqualTo(200);
            assertThat(pdf.header("Content-Type")).isEqualTo("application/pdf");
            assertThat(pdf.header("Content-Disposition"))
                    .isEqualTo("attachment; filename*=UTF-8''Medical%20out-of-pocket%202026.pdf");
            try (PDDocument document = Loader.loadPDF(pdf.body())) {
                String text = new PDFTextStripper().getText(document);
                assertThat(text).contains(
                        "Medical out-of-pocket 2026", "Jan 1, 2026 – Dec 31, 2026", "Includes one-off spending.",
                        "Only what is tagged Medical – out of pocket.",
                        "Total", "$228.60", "Reimbursed", "$180.00", "Submitted, waiting", "$25.00", "Not submitted yet", "$23.60",
                        "By month", "September 2026", "$108.60", "Every line", "Sep 28, 2026", "Valley Pediatrics", "$85.00");
                assertThat(document.getNumberOfPages()).isEqualTo(1);
            }
        }

        @Test
        void pdfWithReceiptsAddsEveryPageOfEachFileAfterTheReport() throws Exception {
            server.upload("/api/entries/" + peds + "/attachments", cookie,
                    new FilePart("invoice-valley-peds.pdf", SampleFiles.pdf(2, "Invoice")),
                    new FilePart("receipt.png", SampleFiles.png(Color.ORANGE)));

            Download with = server.download("/api/reports/export/pdf", export(",\"withReceipts\":true"), cookie);
            Download without = server.download("/api/reports/export/pdf", export(",\"withReceipts\":false"), cookie);

            try (PDDocument document = Loader.loadPDF(with.body())) {
                // The report, the invoice's two pages, the picture's page.
                assertThat(document.getNumberOfPages()).isEqualTo(4);
                String text = new PDFTextStripper().getText(document);
                assertThat(text).contains(
                        "Invoice page 1", "Invoice page 2",
                        "Sep 28, 2026 · Valley Pediatrics · $85.00 · invoice-valley-peds.pdf",
                        "Sep 28, 2026 · Valley Pediatrics · $85.00 · receipt.png");
                assertThat(text).doesNotContain("could not be added");
            }
            try (PDDocument document = Loader.loadPDF(without.body())) {
                assertThat(document.getNumberOfPages()).isEqualTo(1);
            }
        }

        @Test
        void aReceiptMissingFromDiskIsListedAndTheRestOfThePdfStillComes() throws Exception {
            server.upload("/api/entries/" + peds + "/attachments", cookie, new FilePart("invoice.pdf", SampleFiles.pdf(1, "Invoice")));
            try (var files = java.nio.file.Files.walk(dataDir.resolve("files"))) {
                for (var file : files.filter(java.nio.file.Files::isRegularFile).toList()) {
                    java.nio.file.Files.delete(file);
                }
            }

            Download pdf = server.download("/api/reports/export/pdf", export(",\"withReceipts\":true"), cookie);

            assertThat(pdf.status()).isEqualTo(200);
            try (PDDocument document = Loader.loadPDF(pdf.body())) {
                assertThat(new PDFTextStripper().getText(document))
                        .contains("Files that could not be added", "Valley Pediatrics · $85.00 · invoice.pdf");
            }
        }

        @Test
        void namesInOtherAlphabetsNeverBreakThePdf() throws Exception {
            created("/api/entries", """
                    {"kind":"money_out","date":"2026-06-01","amountCents":100,"description":"診察 😀","payee":"Nguyễn Thị Hoa",
                     "accountId":%d,"tagIds":[%d]}""".formatted(checking, outOfPocket));

            Download pdf = server.download("/api/reports/export/pdf", export(""), cookie);

            assertThat(pdf.status()).isEqualTo(200);
            try (PDDocument document = Loader.loadPDF(pdf.body())) {
                assertThat(new PDFTextStripper().getText(document)).contains("Jun 1, 2026");
            }
        }

        @Test
        void anExportWithNoNameIsCalledReportAndABadDefinitionIsRefused() throws Exception {
            Download unnamed = server.download("/api/reports/export/csv", "{\"definition\":" + medicalOutOfPocket() + "}", cookie);
            assertThat(unnamed.header("Content-Disposition")).isEqualTo("attachment; filename*=UTF-8''Report.csv");
            Download slashes = server.download("/api/reports/export/csv",
                    "{\"name\":\"Medical 2026/27: \\\"final\\\"\",\"definition\":" + medicalOutOfPocket() + "}", cookie);
            assertThat(slashes.header("Content-Disposition")).isEqualTo("attachment; filename*=UTF-8''Medical%202026%2027%20%20%20final%20.csv");

            assertThat(server.post("/api/reports/export/csv", "{}", cookie).status()).isEqualTo(400);
            assertThat(server.post("/api/reports/export/pdf", "{\"definition\":" + definition("\"tagIds\":[999]") + "}", cookie).status())
                    .isEqualTo(400);
        }
    }
}
