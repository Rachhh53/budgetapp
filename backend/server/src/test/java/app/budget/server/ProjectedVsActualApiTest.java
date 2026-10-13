package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Download;
import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Projected vs. actual reports over real HTTP against a temporary database (spec §4.11, §5.10).
 * "Today" is Thursday 2026-10-01; paydays are the 1st and 15th, $2,486.20 each. Electric is
 * $140.00 on the 16th and Groceries a $300.00 budget every paycheck. Three amounts were typed
 * over: July's electric bill ($212.00), the second September grocery budget ($275.00) and the
 * August 15 paycheck ($2,600.00).
 */
class ProjectedVsActualApiTest extends ApiTestBase {

    private static final long NET = 248_620;

    long checking;
    long salary;
    long utilities;
    long electric;
    long groceries;

    @BeforeEach
    void aYearWithThreeAmountsTypedOver() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        salary = createSalary(checking);
        utilities = created("/api/categories", "{\"name\":\"Utilities\",\"color\":\"#b45309\"}");
        electric = created("/api/items", """
                {"name":"Electric","kind":"bill","amountCents":14000,"payFromAccountId":%d,"categoryId":%d,"autopay":true,
                 "schedule":{"frequency":"monthly","day1":16,"startDate":"2026-01-01"}}""".formatted(checking, utilities));
        groceries = created("/api/items", """
                {"name":"Groceries","kind":"budget","amountCents":30000,"payFromAccountId":%d,
                 "schedule":{"frequency":"every_pay_period","startDate":"2026-01-01"}}""".formatted(checking));
        typeOver("/api/items/" + electric + "/occurrences/2026-07-16", "{\"amountCents\":21200}");
        typeOver("/api/items/" + groceries + "/occurrences/2026-09-15", "{\"amountCents\":27500}");
        typeOver("/api/income-sources/" + salary + "/paychecks/2026-08-15", "{\"netCents\":260000}");
    }

    private void typeOver(String path, String body) throws Exception {
        Reply reply = server.put(path, body, cookie);
        assertThat(reply.status()).as(reply.body().toString()).isEqualTo(200);
    }

    /** This year by month, comparing {@code include}, with {@code extra} spliced in. */
    private static String compare(String include, String extra) {
        return "{\"kind\":\"projected_vs_actual\",\"range\":\"this_year\",\"show\":\"table\",\"compareInclude\":[%s]%s}"
                .formatted(include, extra.isEmpty() ? "" : "," + extra);
    }

    private static String everything(String extra) {
        return compare("\"bills\",\"budgets\",\"paychecks\"", extra);
    }

    private JsonNode run(String definition) throws Exception {
        Reply reply = server.post("/api/reports/run", "{\"definition\":" + definition + "}", cookie);
        assertThat(reply.status()).as(reply.body().toString()).isEqualTo(200);
        return reply.data();
    }

    private static JsonNode group(JsonNode side, String label) {
        for (JsonNode group : side.get("groups")) {
            if (group.get("label").asText().equals(label)) {
                return group;
            }
        }
        throw new AssertionError("No group called " + label + " in " + side.get("groups"));
    }

    @Nested
    class Auth {
        @Test
        void nothingWorksWithoutASession() throws Exception {
            String body = "{\"definition\":" + everything("") + "}";

            assertThat(server.post("/api/reports/run", body, null).status()).isEqualTo(401);
            assertThat(server.download("/api/reports/export/csv", body, null).status()).isEqualTo(401);
            assertThat(server.download("/api/reports/export/pdf", body, null).status()).isEqualTo(401);
        }
    }

    @Nested
    class Running {
        @Test
        void spendingIsSetBesideWhatWasProjectedAndSaysHowManyAmountsWereTypedOver() throws Exception {
            JsonNode report = run(everything(""));

            assertThat(report.get("kind").asText()).isEqualTo("projected_vs_actual");
            assertThat(report.get("from").asText()).isEqualTo("2026-01-01");
            assertThat(report.get("to").asText()).isEqualTo("2026-12-31");
            JsonNode spending = report.get("spending");
            // Twelve electric bills and twenty-four grocery budgets.
            assertThat(spending.get("count").asInt()).isEqualTo(36);
            assertThat(spending.get("changed").asInt()).isEqualTo(2);
            assertThat(spending.get("projectedCents").asLong()).isEqualTo(12 * 14_000 + 24 * 30_000);
            assertThat(spending.get("actualCents").asLong()).isEqualTo(12 * 14_000 + 24 * 30_000 + 7_200 - 2_500);
            assertThat(spending.get("differenceCents").asLong()).isEqualTo(4_700);
        }

        @Test
        void paychecksAreAddedUpApartFromSpending() throws Exception {
            JsonNode paychecks = run(everything("")).get("paychecks");

            assertThat(paychecks.get("count").asInt()).isEqualTo(24);
            assertThat(paychecks.get("changed").asInt()).isEqualTo(1);
            assertThat(paychecks.get("projectedCents").asLong()).isEqualTo(24 * NET);
            assertThat(paychecks.get("differenceCents").asLong()).isEqualTo(260_000 - NET);
        }

        @Test
        void byMonthListsEveryMonthWithItsOwnFigures() throws Exception {
            JsonNode report = run(everything(""));

            assertThat(report.get("spending").get("groups")).hasSize(12);
            JsonNode july = group(report.get("spending"), "July 2026");
            assertThat(july.get("count").asInt()).isEqualTo(3);
            assertThat(july.get("changed").asInt()).isEqualTo(1);
            assertThat(july.get("projectedCents").asLong()).isEqualTo(74_000);
            assertThat(july.get("actualCents").asLong()).isEqualTo(81_200);
            assertThat(group(report.get("paychecks"), "August 2026").get("differenceCents").asLong()).isEqualTo(260_000 - NET);
        }

        @Test
        void aLineSaysWhatItWasProjectedAtAndWhetherItIsStillToCome() throws Exception {
            JsonNode rows = run(compare("\"bills\"", "")).get("rows");

            assertThat(rows).hasSize(12);
            JsonNode july = rows.get(6);
            assertThat(july.get("kind").asText()).isEqualTo("bill");
            assertThat(july.get("itemId").asLong()).isEqualTo(electric);
            assertThat(july.get("date").asText()).isEqualTo("2026-07-16");
            assertThat(july.get("projectedCents").asLong()).isEqualTo(14_000);
            assertThat(july.get("actualCents").asLong()).isEqualTo(21_200);
            assertThat(july.get("changed").asBoolean()).isTrue();
            assertThat(july.get("toCome").asBoolean()).isFalse();
            // December has not come, and was never typed over: it is what was projected.
            assertThat(rows.get(11).get("toCome").asBoolean()).isTrue();
            assertThat(rows.get(11).get("differenceCents").asLong()).isZero();
        }

        @Test
        void byItemNamesEachItemAndPutsWhatMovedFurthestFirst() throws Exception {
            JsonNode spending = run(everything("\"compareGroupBy\":\"item\"")).get("spending");

            assertThat(spending.get("groups").findValuesAsText("label")).containsExactly("Electric", "Groceries");
            assertThat(run(everything("\"compareGroupBy\":\"item\"")).get("paychecks").get("groups").get(0).get("label").asText())
                    .isEqualTo("Acme Corp paycheck");
        }

        @Test
        void byCategoryUsesTheCategoryNameAndColor() throws Exception {
            JsonNode report = run(everything("\"compareGroupBy\":\"category\""));

            JsonNode named = group(report.get("spending"), "Utilities");
            assertThat(named.get("color").asText()).isEqualTo("#b45309");
            assertThat(named.get("count").asInt()).isEqualTo(12);
            assertThat(group(report.get("spending"), "No category").get("count").asInt()).isEqualTo(24);
            assertThat(group(report.get("paychecks"), "Paychecks").get("count").asInt()).isEqualTo(24);
        }

        @Test
        void onlyWhatChangedLeavesOutEverythingThatCameToWhatWasProjected() throws Exception {
            JsonNode report = run(everything("\"changedOnly\":true"));

            assertThat(report.get("rows")).hasSize(3);
            assertThat(report.get("spending").get("count").asInt()).isEqualTo(2);
            assertThat(report.get("paychecks").get("count").asInt()).isEqualTo(1);
        }

        @Test
        void aCategoryFilterLeavesPaychecksOutBecauseTheyHaveNone() throws Exception {
            JsonNode report = run(everything("\"categoryIds\":[%d]".formatted(utilities)));

            assertThat(report.get("spending").get("count").asInt()).isEqualTo(12);
            assertThat(report.get("paychecks").get("count").asInt()).isZero();
        }

        @Test
        void runningItLeavesNothingToUndo() throws Exception {
            Reply reply = server.post("/api/reports/run", "{\"definition\":" + everything("") + "}", cookie);

            assertThat(reply.changed()).isFalse();
        }
    }

    @Nested
    class Refusing {
        @Test
        void nothingToCompareIsAskedFor() throws Exception {
            Reply reply = server.post("/api/reports/run", "{\"definition\":" + compare("", "") + "}", cookie);

            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.error()).contains("at least one thing");
        }

        @Test
        void aGroupingThatIsNotOnTheListIsRefusedKindly() throws Exception {
            Reply reply = server.post("/api/reports/run", "{\"definition\":" + everything("\"compareGroupBy\":\"payee\"") + "}", cookie);

            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.error()).contains("how to group it");
        }

        @Test
        void aCategoryThatIsGoneIsSaid() throws Exception {
            Reply reply = server.post("/api/reports/run", "{\"definition\":" + everything("\"categoryIds\":[9999]") + "}", cookie);

            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.error()).contains("no longer there");
        }
    }

    @Nested
    class SavingAndExporting {
        @Test
        void aSavedReportKeepsItsKindAndChoices() throws Exception {
            long id = created("/api/reports",
                    "{\"name\":\"What changed\",\"definition\":" + everything("\"changedOnly\":true,\"compareGroupBy\":\"item\"") + "}");

            JsonNode saved = null;
            for (JsonNode report : server.get("/api/reports", cookie).data()) {
                if (report.get("id").asLong() == id) {
                    saved = report.get("definition");
                }
            }
            assertThat(saved).isNotNull();
            assertThat(saved.get("kind").asText()).isEqualTo("projected_vs_actual");
            assertThat(saved.get("compareGroupBy").asText()).isEqualTo("item");
            assertThat(saved.get("changedOnly").asBoolean()).isTrue();
            assertThat(saved.get("compareInclude")).hasSize(3);
        }

        @Test
        void theCsvHasALinePerAmountWithBothFigures() throws Exception {
            Download csv = server.download("/api/reports/export/csv", "{\"name\":\"What changed\",\"definition\":" + everything("") + "}", cookie);

            assertThat(csv.status()).isEqualTo(200);
            String text = new String(csv.body(), StandardCharsets.UTF_8);
            assertThat(text).contains("Date,Description,Kind,Category,Account,Projected,Actual,Difference,Typed over,Still to come");
            assertThat(text).contains("2026-07-16,Electric,Bill or expense,Utilities,Everyday Checking,140.00,212.00,72.00,yes,");
            assertThat(text).contains("2026-08-15,Acme Corp paycheck,Paycheck,,Everyday Checking,2486.20,2600.00,113.80,yes,");
        }

        @Test
        void thePdfSetsSpendingAndPaychecksOutApart() throws Exception {
            Download pdf = server.download("/api/reports/export/pdf", "{\"name\":\"What changed\",\"definition\":" + everything("") + "}", cookie);

            assertThat(pdf.status()).isEqualTo(200);
            try (PDDocument document = Loader.loadPDF(pdf.body())) {
                String text = new PDFTextStripper().getText(document);
                assertThat(text).contains("What changed", "Spending, by month", "Paychecks, by month", "Projected", "+$47.00");
            }
        }
    }
}
