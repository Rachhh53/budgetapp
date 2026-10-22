package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Download;
import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Annual costs over real HTTP against a temporary database (spec §5.8, §8). "Today" is Thursday
 * 2026-10-01. Rent has been $1,500.00 on the 1st since January 2025. Electric was $133.00 on
 * the 16th through 2025 and $140.00 from January 2026, with July 2026 typed over at $212.00.
 */
class AnnualCostsApiTest extends ApiTestBase {

    private static final String ELECTRIC = """
            {"name":"Electric","kind":"bill","amountCents":%d,"payFromAccountId":%d,"categoryId":%d,"autopay":true,
             "schedule":{"frequency":"monthly","day1":16,"startDate":"2025-01-01"}%s}""";

    long checking;
    long savings;
    long utilities;
    long rent;
    long electric;
    long transfer;

    @BeforeEach
    void twoYearsOfRentAndElectric() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        savings = createAccount("Rainy Day Savings", "savings");
        createSalary(checking);
        utilities = created("/api/categories", "{\"name\":\"Utilities\",\"color\":\"#b45309\"}");
        rent = created("/api/items", """
                {"name":"Rent","kind":"bill","amountCents":150000,"payFromAccountId":%d,"autopay":true,
                 "schedule":{"frequency":"monthly","day1":1,"startDate":"2025-01-01"}}""".formatted(checking));
        electric = created("/api/items", ELECTRIC.formatted(13_300, checking, utilities, ""));
        Reply raised = server.put("/api/items/" + electric,
                ELECTRIC.formatted(14_000, checking, utilities, ",\"effectiveFrom\":\"2026-01-01\""), cookie);
        assertThat(raised.status()).as(raised.body().toString()).isEqualTo(200);
        server.put("/api/items/" + electric + "/occurrences/2026-07-16", "{\"amountCents\":21200}", cookie);
        // Moving money is not a cost: it must never show up here.
        transfer = created("/api/items", """
                {"name":"To savings","kind":"transfer","amountCents":20000,"payFromAccountId":%d,"payToAccountId":%d,
                 "schedule":{"frequency":"monthly","day1":2,"startDate":"2025-01-01"}}""".formatted(checking, savings));
    }

    private JsonNode costs(String query) throws Exception {
        Reply reply = server.get("/api/annual-costs" + query, cookie);
        assertThat(reply.status()).as(reply.body().toString()).isEqualTo(200);
        return reply.data();
    }

    private static JsonNode row(JsonNode costs, String name) {
        for (JsonNode row : costs.get("rows")) {
            if (row.get("name").asText().equals(name)) {
                return row;
            }
        }
        throw new AssertionError("No row called " + name + " in " + costs.get("rows"));
    }

    private static long[] longs(JsonNode array) {
        long[] values = new long[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).asLong();
        }
        return values;
    }

    @Nested
    class Auth {
        @Test
        void nothingWorksWithoutASession() throws Exception {
            assertThat(server.get("/api/annual-costs", null).status()).isEqualTo(401);
            assertThat(server.get("/api/items/" + electric + "/by-month", null).status()).isEqualTo(401);
            assertThat(server.post("/api/annual-costs/export/csv", "{\"save\":true}", null).status()).isEqualTo(401);
            assertThat(server.download("/api/annual-costs/export/csv", "{}", null).status()).isEqualTo(401);
            assertThat(Files.list(server.downloads)).isEmpty();
        }
    }

    @Nested
    class CalendarYears {
        @Test
        void eachBillHasAColumnPerYearLargestFirst() throws Exception {
            JsonNode costs = costs("");

            assertThat(costs.get("basis").asText()).isEqualTo("calendar");
            assertThat(costs.get("windows").findValuesAsText("label")).containsExactly("2024", "2025", "2026");
            assertThat(costs.get("rows").findValuesAsText("name")).containsExactly("Rent", "Electric");
            assertThat(longs(row(costs, "Rent").get("totalsCents"))).containsExactly(0, 1_800_000, 1_800_000);
            // 2026 is eleven months at $140.00 and July at $212.00.
            assertThat(longs(row(costs, "Electric").get("totalsCents"))).containsExactly(0, 159_600, 175_200);
        }

        @Test
        void theChangeIsThisYearAgainstLast() throws Exception {
            JsonNode costs = costs("?basis=calendar");

            // (1,752.00 − 1,596.00) ÷ 1,596.00 = 9.77%
            assertThat(row(costs, "Electric").get("changeBp").asInt()).isEqualTo(977);
            assertThat(row(costs, "Electric").get("perMonthCents").asLong()).isEqualTo(14_600);
            assertThat(row(costs, "Rent").get("changeBp").asInt()).isZero();
            assertThat(longs(costs.get("totalsCents"))).containsExactly(0, 1_959_600, 1_975_200);
            assertThat(costs.get("changeBp").asInt()).isEqualTo(80);
            assertThat(costs.get("perMonthCents").asLong()).isEqualTo(164_600);
        }

        @Test
        void theBiggestIncreaseIsNamedWithHowMuchMoreItCame() throws Exception {
            JsonNode biggest = costs("").get("biggestIncrease");

            assertThat(biggest.get("itemId").asLong()).isEqualTo(electric);
            assertThat(biggest.get("name").asText()).isEqualTo("Electric");
            assertThat(biggest.get("changeBp").asInt()).isEqualTo(977);
            assertThat(biggest.get("increaseCents").asLong()).isEqualTo(15_600);
        }

        @Test
        void itSaysFromWhenThisYearIsBudgetedRatherThanSpent() throws Exception {
            assertThat(costs("").get("budgetedFrom").asText()).isEqualTo("2026-11-01");
        }

        @Test
        void aRowCarriesItsCategoryAndKind() throws Exception {
            JsonNode row = row(costs(""), "Electric");

            assertThat(row.get("itemId").asLong()).isEqualTo(electric);
            assertThat(row.get("kind").asText()).isEqualTo("bill");
            assertThat(row.get("categoryName").asText()).isEqualTo("Utilities");
            assertThat(row.get("color").asText()).isEqualTo("#b45309");
            assertThat(row(costs(""), "Rent").get("categoryName").isNull()).isTrue();
        }

        @Test
        void anArchivedItemCountsUntilTheDayItWasArchivedAsOf() throws Exception {
            // Rachel, 2026-10-22: "the previous activity needs to remain".
            server.put("/api/items/" + rent, """
                    {"name":"Rent","kind":"bill","amountCents":150000,"payFromAccountId":%d,"autopay":true,"archived":true,
                     "archivedOn":"2026-06-01","schedule":{"frequency":"monthly","day1":1,"startDate":"2025-01-01"}}"""
                    .formatted(checking), cookie);

            // All of 2025, and January to May 2026: the June 1 rent is on the day it was archived as of.
            assertThat(longs(row(costs(""), "Rent").get("totalsCents"))).containsExactly(0, 1_800_000, 750_000);
        }
    }

    @Nested
    class RollingMonths {
        @Test
        void theNewestTwelveMonthsEndWithLastMonth() throws Exception {
            JsonNode costs = costs("?basis=rolling");

            assertThat(costs.get("basis").asText()).isEqualTo("rolling");
            assertThat(costs.get("windows").findValuesAsText("label"))
                    .containsExactly("Oct 2023 – Sep 2024", "Oct 2024 – Sep 2025", "Oct 2025 – Sep 2026");
            // Oct–Dec 2025 at $133.00, then eight months at $140.00 and July at $212.00.
            assertThat(longs(row(costs, "Electric").get("totalsCents"))).containsExactly(0, 9 * 13_300, 3 * 13_300 + 8 * 14_000 + 21_200);
            assertThat(costs.get("budgetedFrom").isNull()).isTrue();
        }

        @Test
        void aBasisThatIsNotOnTheListIsRefusedKindly() throws Exception {
            Reply reply = server.get("/api/annual-costs?basis=fiscal", cookie);

            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.error()).contains("calendar years or rolling months");
        }
    }

    @Nested
    class ByMonth {
        @Test
        void anItemsYearIsSetBesideTheYearBefore() throws Exception {
            Reply reply = server.get("/api/items/" + electric + "/by-month", cookie);

            assertThat(reply.status()).as(reply.body().toString()).isEqualTo(200);
            JsonNode year = reply.data();
            assertThat(year.get("year").asInt()).isEqualTo(2026);
            assertThat(year.get("months")).hasSize(12);
            JsonNode july = year.get("months").get(6);
            assertThat(july.get("month").asText()).isEqualTo("2026-07");
            assertThat(july.get("amountCents").asLong()).isEqualTo(21_200);
            assertThat(july.get("beforeCents").asLong()).isEqualTo(13_300);
            assertThat(year.get("months").get(9).get("toCome").asBoolean()).isFalse();
            assertThat(year.get("months").get(10).get("toCome").asBoolean()).isTrue();
        }

        @Test
        void itSaysTheHighestTheLowestTheAverageAndWhetherTheBudgetIsTooLow() throws Exception {
            JsonNode year = server.get("/api/items/" + electric + "/by-month", cookie).data();

            assertThat(year.get("highest").get("month").asText()).isEqualTo("2026-07");
            assertThat(year.get("highest").get("amountCents").asLong()).isEqualTo(21_200);
            assertThat(year.get("lowest").get("amountCents").asLong()).isEqualTo(14_000);
            assertThat(year.get("totalCents").asLong()).isEqualTo(175_200);
            assertThat(year.get("averageCents").asLong()).isEqualTo(14_600);
            assertThat(year.get("budgetCents").asLong()).isEqualTo(14_000);
            assertThat(year.get("budgetTooLow").asBoolean()).isTrue();
        }

        @Test
        void aBillThatComesToItsAmountIsNotTooLow() throws Exception {
            JsonNode year = server.get("/api/items/" + rent + "/by-month", cookie).data();

            assertThat(year.get("averageCents").asLong()).isEqualTo(150_000);
            assertThat(year.get("budgetTooLow").asBoolean()).isFalse();
        }

        @Test
        void aTransferHasNoCostsAndSaysSo() throws Exception {
            Reply reply = server.get("/api/items/" + transfer + "/by-month", cookie);

            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.error()).contains("bills and budgets");
        }

        @Test
        void anItemThatIsNotThereIsNotFound() throws Exception {
            assertThat(server.get("/api/items/9999/by-month", cookie).status()).isEqualTo(404);
        }
    }

    @Nested
    class Exporting {
        @Test
        void theCsvIsTheTableWithAColumnPerYear() throws Exception {
            Download csv = server.download("/api/annual-costs/export/csv", "{}", cookie);

            assertThat(csv.status()).isEqualTo(200);
            assertThat(csv.header("Content-Disposition")).contains("Annual%20costs.csv");
            String text = new String(csv.body(), StandardCharsets.UTF_8);
            assertThat(text).contains("Item,Kind,Category,2024,2025,2026,Change %,Per month");
            assertThat(text).contains("Electric,Bill or expense,Utilities,0.00,1596.00,1752.00,9.77,146.00");
            assertThat(text).contains("Rent,Bill or expense,,0.00,18000.00,18000.00,0.00,1500.00");
        }

        @Test
        void askedToSaveFromThisPcItPutsTheFileInDownloadsAndLeavesNothingToUndo() throws Exception {
            Reply reply = server.post("/api/annual-costs/export/csv", "{\"basis\":\"rolling\",\"save\":true}", cookie);

            assertThat(reply.status()).as(reply.body().toString()).isEqualTo(200);
            assertThat(reply.data().get("saved").get("fileName").asText()).isEqualTo("Annual costs.csv");
            assertThat(reply.changed()).isFalse();
            assertThat(Files.readString(server.downloads.resolve("Annual costs.csv"))).contains("Oct 2025 – Sep 2026");
        }

        @Test
        void aBasisThatIsNotOnTheListIsRefusedKindly() throws Exception {
            assertThat(server.post("/api/annual-costs/export/csv", "{\"basis\":\"fiscal\"}", cookie).status()).isEqualTo(400);
        }
    }
}
