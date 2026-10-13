package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Plan next year over real HTTP against a temporary database (spec §4.10, §5.8). "Today" is
 * Thursday 2026-10-01, so suggestions look at Oct 2025 – Sep 2026 and the plan is of 2027.
 *
 * <p>Rent has been $1,500.00 since January 2025. Electric has run since January 2024: $133.00
 * through 2025, $140.00 from January 2026, with July 2026 typed over at $212.00. Phone, $85.00,
 * only began in June 2026. Groceries is $300.00 every paycheck, and paychecks began in January
 * 2026. $200.00 goes to savings every month.
 */
class PlansApiTest extends ApiTestBase {

    private static final String ELECTRIC = """
            {"name":"Electric","kind":"bill","amountCents":%d,"payFromAccountId":%d,"autopay":true,
             "schedule":{"frequency":"monthly","day1":16,"startDate":"2024-01-01"}%s}""";

    long checking;
    long savings;
    long salary;
    long rent;
    long electric;
    long phone;
    long groceries;
    long transfer;

    @BeforeEach
    void aHouseholdWithSomeHistory() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        savings = createAccount("Rainy Day Savings", "savings");
        salary = createSalary(checking);
        rent = created("/api/items", """
                {"name":"Rent","kind":"bill","amountCents":150000,"payFromAccountId":%d,"autopay":true,
                 "schedule":{"frequency":"monthly","day1":1,"startDate":"2025-01-01"}}""".formatted(checking));
        electric = created("/api/items", ELECTRIC.formatted(13_300, checking, ""));
        Reply raised = server.put("/api/items/" + electric, ELECTRIC.formatted(14_000, checking, ",\"effectiveFrom\":\"2026-01-01\""), cookie);
        assertThat(raised.status()).as(raised.body().toString()).isEqualTo(200);
        server.put("/api/items/" + electric + "/occurrences/2026-07-16", "{\"amountCents\":21200}", cookie);
        phone = created("/api/items", """
                {"name":"Phone","kind":"bill","amountCents":8500,"payFromAccountId":%d,"autopay":true,
                 "schedule":{"frequency":"monthly","day1":20,"startDate":"2026-06-01"}}""".formatted(checking));
        groceries = created("/api/items", """
                {"name":"Groceries","kind":"budget","amountCents":30000,"payFromAccountId":%d,
                 "schedule":{"frequency":"every_pay_period","startDate":"2024-01-01"}}""".formatted(checking));
        transfer = created("/api/items", """
                {"name":"To savings","kind":"transfer","amountCents":20000,"payFromAccountId":%d,"payToAccountId":%d,
                 "schedule":{"frequency":"monthly","day1":2,"startDate":"2025-01-01"}}""".formatted(checking, savings));
    }

    private long startPlan() throws Exception {
        Reply reply = server.post("/api/plans", "{}", cookie);
        assertThat(reply.status()).as(reply.body().toString()).isEqualTo(201);
        return reply.data().get("id").asLong();
    }

    private JsonNode plan(long id) throws Exception {
        Reply reply = server.get("/api/plans/" + id, cookie);
        assertThat(reply.status()).as(reply.body().toString()).isEqualTo(200);
        return reply.data();
    }

    private static JsonNode step(JsonNode plan, String name) {
        for (JsonNode step : plan.get("steps")) {
            if (step.get("step").asText().equals(name)) {
                return step;
            }
        }
        throw new AssertionError("No step called " + name);
    }

    private static JsonNode row(JsonNode plan, String name) {
        for (JsonNode step : plan.get("steps")) {
            for (JsonNode row : step.get("rows")) {
                if (row.get("name").asText().equals(name)) {
                    return row;
                }
            }
        }
        throw new AssertionError("No line called " + name + " in " + plan.get("steps"));
    }

    private Reply type(long planId, String kind, long id, String amount) throws Exception {
        return server.put("/api/plans/" + planId + "/amounts",
                "{\"kind\":\"%s\",\"id\":%d,\"amountCents\":%s}".formatted(kind, id, amount), cookie);
    }

    private Reply delete(long planId, String body) throws Exception {
        return server.send("DELETE", "/api/plans/" + planId, body, Map.of("Cookie", cookie));
    }

    /** Moves the server's clock on. The app has locked itself long before, so it is unlocked again. */
    private void later(int days) throws Exception {
        server.clock.advance(Duration.ofDays(days));
        cookie = server.unlock();
    }

    /** On to 2027-01-01, the day the 2027 plan starts. */
    private void goToNewYear() throws Exception {
        later(92);
    }

    @Nested
    class Auth {
        @Test
        void nothingWorksWithoutASession() throws Exception {
            long id = startPlan();

            assertThat(server.get("/api/plans", null).status()).isEqualTo(401);
            assertThat(server.post("/api/plans", "{}", null).status()).isEqualTo(401);
            assertThat(server.get("/api/plans/" + id, null).status()).isEqualTo(401);
            assertThat(server.put("/api/plans/" + id, "{\"basis\":\"last_12\"}", null).status()).isEqualTo(401);
            assertThat(server.put("/api/plans/" + id + "/amounts", "{\"kind\":\"item\",\"id\":1,\"amountCents\":1}", null).status())
                    .isEqualTo(401);
            assertThat(server.post("/api/plans/" + id + "/accept-suggestions", "{}", null).status()).isEqualTo(401);
            assertThat(server.post("/api/plans/" + id + "/finish", "{}", null).status()).isEqualTo(401);
            assertThat(server.send("DELETE", "/api/plans/" + id, "{\"confirm\":\"delete-draft\"}", Map.of()).status()).isEqualTo(401);
            assertThat(server.get("/api/items/" + electric + "/plan-suggestion", null).status()).isEqualTo(401);
            // Nothing above touched the draft.
            assertThat(plan(id).get("plan").get("status").asText()).isEqualTo("draft");
        }
    }

    @Nested
    class Starting {
        @Test
        void nextYearCanBePlannedFromTheReminderDayOn() throws Exception {
            JsonNode overview = server.get("/api/plans", cookie).data();

            assertThat(overview.get("plans")).isEmpty();
            assertThat(overview.get("nextYear").asInt()).isEqualTo(2027);
            assertThat(overview.get("nextStartsOn").asText()).isEqualTo("2027-01-01");
            assertThat(overview.get("canPlan").asBoolean()).isTrue();
            // The app already has things in it, so first-time setup is not offered.
            assertThat(overview.get("canSetUp").asBoolean()).isFalse();
            // October 1 is the day the reminder starts by default.
            assertThat(overview.get("reminderDue").asBoolean()).isTrue();
        }

        @Test
        void aDraftStartsOnTheBudgetYearStartAndSuggestsFromTheLastTwelveMonthsAndTheirTrend() throws Exception {
            JsonNode plan = plan(startPlan());

            assertThat(plan.get("plan").get("year").asInt()).isEqualTo(2027);
            assertThat(plan.get("plan").get("status").asText()).isEqualTo("draft");
            assertThat(plan.get("plan").get("basis").asText()).isEqualTo("last_12_trend");
            assertThat(plan.get("plan").get("startsOn").asText()).isEqualTo("2027-01-01");
            assertThat(plan.get("plan").get("endsOn").asText()).isEqualTo("2027-12-31");
            assertThat(plan.get("historyFrom").asText()).isEqualTo("2025-10-01");
            assertThat(plan.get("historyTo").asText()).isEqualTo("2026-09-30");
            assertThat(plan.get("paydays").asInt()).isEqualTo(24);
            assertThat(plan.get("stale").asBoolean()).isFalse();
        }

        @Test
        void onlyOneDraftAtATime() throws Exception {
            startPlan();

            Reply again = server.post("/api/plans", "{}", cookie);

            assertThat(again.status()).isEqualTo(409);
            assertThat(again.error()).contains("2027 plan is still a draft");
            assertThat(server.get("/api/plans", cookie).data().get("canPlan").asBoolean()).isFalse();
            assertThat(server.get("/api/plans", cookie).data().get("reminderDue").asBoolean()).isFalse();
        }

        @Test
        void anUnknownKindOfPlanIsRefused() throws Exception {
            assertThat(server.post("/api/plans", "{\"kind\":\"decade\"}", cookie).status()).isEqualTo(400);
            assertThat(server.post("/api/plans", "not json", cookie).status()).isEqualTo(400);
        }

        @Test
        void aPlanThatDoesNotExistIsNotFound() throws Exception {
            assertThat(server.get("/api/plans/999", cookie).status()).isEqualTo(404);
            assertThat(server.get("/api/plans/abc", cookie).status()).isEqualTo(404);
        }

        @Test
        void theBudgetYearCanStartOnAnotherDay() throws Exception {
            assertThat(server.put("/api/settings/budget_year_start", "{\"value\":\"07-01\"}", cookie).status()).isEqualTo(200);

            assertThat(server.get("/api/plans", cookie).data().get("nextStartsOn").asText()).isEqualTo("2027-07-01");
            JsonNode plan = plan(startPlan());
            assertThat(plan.get("plan").get("endsOn").asText()).isEqualTo("2028-06-30");
        }

        @Test
        void aDayThatNotEveryYearHasCannotStartTheBudgetYear() throws Exception {
            assertThat(server.put("/api/settings/budget_year_start", "{\"value\":\"02-29\"}", cookie).status()).isEqualTo(400);
            assertThat(server.put("/api/settings/budget_year_start", "{\"value\":\"13-01\"}", cookie).status()).isEqualTo(400);
            assertThat(server.put("/api/settings/plan_reminder", "{\"value\":\"October\"}", cookie).status()).isEqualTo(400);
        }

        @Test
        void theReminderWaitsForItsDay() throws Exception {
            server.put("/api/settings/plan_reminder", "{\"value\":\"11-15\"}", cookie);

            assertThat(server.get("/api/plans", cookie).data().get("reminderDue").asBoolean()).isFalse();
        }
    }

    @Nested
    class Suggestions {
        @Test
        void aBillWithTwoYearsBehindItIsSuggestedAtItsLastTwelveMonthsMovedOnByTheirTrend() throws Exception {
            JsonNode electricRow = row(plan(startPlan()), "Electric");

            // Oct–Dec 2025 at $133.00, eight months of 2026 at $140.00 and July at $212.00.
            assertThat(electricRow.get("last12Cents").asLong()).isEqualTo(173_100);
            // Against $1,596.00 the twelve months before: up 8.46%.
            assertThat(electricRow.get("trendBp").asInt()).isEqualTo(846);
            // $1,731.00 × 1.0846 = $1,877.44, to the dollar.
            assertThat(electricRow.get("suggestedCents").asLong()).isEqualTo(187_700);
            assertThat(electricRow.get("reason").asText()).isEqualTo("history");
            assertThat(electricRow.get("chosenCents").asLong()).isEqualTo(187_700);
            assertThat(electricRow.get("yours").asBoolean()).isFalse();
            assertThat(electricRow.get("currentCents").asLong()).isEqualTo(168_000);
            assertThat(electricRow.get("perOccurrenceCents").asLong()).isEqualTo(15_642);
            assertThat(electricRow.get("occurrences").asInt()).isEqualTo(12);
            assertThat(electricRow.get("step").asText()).isEqualTo("bills");
        }

        @Test
        void aBillWithOneYearBehindItHasNoTrendToApply() throws Exception {
            JsonNode rentRow = row(plan(startPlan()), "Rent");

            assertThat(rentRow.get("last12Cents").asLong()).isEqualTo(1_800_000);
            assertThat(rentRow.get("trendBp").isNull()).isTrue();
            assertThat(rentRow.get("suggestedCents").asLong()).isEqualTo(1_800_000);
            assertThat(rentRow.get("reason").asText()).isEqualTo("history");
        }

        @Test
        void aBillThatBeganThisSummerIsSuggestedAtWhatItIsNowOverAYear() throws Exception {
            JsonNode phoneRow = row(plan(startPlan()), "Phone");

            assertThat(phoneRow.get("last12Cents").isNull()).isTrue();
            assertThat(phoneRow.get("reason").asText()).isEqualTo("not_enough_history");
            // Not its four months stretched to twelve: $85.00 × 12.
            assertThat(phoneRow.get("suggestedCents").asLong()).isEqualTo(102_000);
        }

        @Test
        void aBudgetHasNoHistoryFromBeforeThePaydaysItFollows() throws Exception {
            JsonNode groceriesRow = row(plan(startPlan()), "Groceries");

            // The budget is dated 2024, but paychecks only began in January 2026.
            assertThat(groceriesRow.get("reason").asText()).isEqualTo("not_enough_history");
            assertThat(groceriesRow.get("suggestedCents").asLong()).isEqualTo(720_000);
            assertThat(groceriesRow.get("step").asText()).isEqualTo("budgets");
            assertThat(groceriesRow.get("occurrences").asInt()).isEqualTo(24);
            assertThat(groceriesRow.get("perOccurrenceCents").asLong()).isEqualTo(30_000);
        }

        @Test
        void movingMoneyAndPayAreSuggestedUnchanged() throws Exception {
            JsonNode plan = plan(startPlan());

            JsonNode toSavings = row(plan, "To savings");
            assertThat(toSavings.get("step").asText()).isEqualTo("saving");
            assertThat(toSavings.get("reason").asText()).isEqualTo("fixed");
            assertThat(toSavings.get("suggestedCents").asLong()).isEqualTo(240_000);
            JsonNode pay = row(plan, "Acme Corp");
            assertThat(pay.get("kind").asText()).isEqualTo("income");
            assertThat(pay.get("step").asText()).isEqualTo("income");
            assertThat(pay.get("chosenCents").asLong()).isEqualTo(248_620);
            assertThat(pay.get("occurrences").asInt()).isEqualTo(24);
            assertThat(pay.get("yearCents").asLong()).isEqualTo(248_620L * 24);
        }

        @Test
        void eachStepAddsUpAndThePlanSaysWhatIsLeft() throws Exception {
            JsonNode plan = plan(startPlan());

            assertThat(step(plan, "bills").get("rows").findValuesAsText("name")).containsExactly("Electric", "Phone", "Rent");
            // 1,877.00 + 1,020.00 + 18,000.00
            assertThat(step(plan, "bills").get("chosenCents").asLong()).isEqualTo(2_089_700);
            assertThat(step(plan, "bills").get("currentCents").asLong()).isEqualTo(168_000 + 102_000 + 1_800_000);
            assertThat(step(plan, "bills").get("changes").asInt()).isEqualTo(1);
            assertThat(step(plan, "annual").get("rows")).isEmpty();
            assertThat(plan.get("incomeCents").asLong()).isEqualTo(248_620L * 24);
            assertThat(plan.get("outCents").asLong()).isEqualTo(2_089_700 + 720_000 + 240_000);
        }

        @Test
        void theBasisCanBeTheLastTwelveMonthsAsTheyWereOrLastCalendarYear() throws Exception {
            long id = startPlan();

            Reply last12 = server.put("/api/plans/" + id, "{\"basis\":\"last_12\"}", cookie);
            assertThat(last12.status()).as(last12.body().toString()).isEqualTo(200);
            assertThat(row(last12.data(), "Electric").get("suggestedCents").asLong()).isEqualTo(173_100);

            JsonNode priorYear = server.put("/api/plans/" + id, "{\"basis\":\"prior_year\"}", cookie).data();
            assertThat(priorYear.get("plan").get("basis").asText()).isEqualTo("prior_year");
            // All of 2025 at $133.00.
            assertThat(row(priorYear, "Electric").get("suggestedCents").asLong()).isEqualTo(159_600);
            assertThat(row(priorYear, "Rent").get("suggestedCents").asLong()).isEqualTo(1_800_000);
        }

        @Test
        void aBasisThatIsNotOneOfTheThreeIsRefused() throws Exception {
            long id = startPlan();

            assertThat(server.put("/api/plans/" + id, "{\"basis\":\"vibes\"}", cookie).status()).isEqualTo(400);
            assertThat(server.put("/api/plans/" + id, "{}", cookie).status()).isEqualTo(400);
            assertThat(server.put("/api/plans/999", "{\"basis\":\"last_12\"}", cookie).status()).isEqualTo(404);
        }

        @Test
        void suggestionsStayAsTheyWereUntilAskedToCatchUp() throws Exception {
            long id = startPlan();
            later(31);

            JsonNode later = plan(id);
            assertThat(later.get("stale").asBoolean()).isTrue();
            assertThat(later.get("historyTo").asText()).isEqualTo("2026-09-30");

            JsonNode refreshed = server.put("/api/plans/" + id, "{\"basis\":\"last_12_trend\",\"refresh\":true}", cookie).data();
            assertThat(refreshed.get("stale").asBoolean()).isFalse();
            assertThat(refreshed.get("historyTo").asText()).isEqualTo("2026-10-31");
        }

        @Test
        void aBillPageCanAskWhatNextYearsPlanWouldSuggest() throws Exception {
            JsonNode suggestion = server.get("/api/items/" + electric + "/plan-suggestion", cookie).data().get("suggestion");

            assertThat(suggestion.get("year").asInt()).isEqualTo(2027);
            assertThat(suggestion.get("perYearCents").asLong()).isEqualTo(187_700);
            assertThat(suggestion.get("perMonthCents").asLong()).isEqualTo(15_642);
            // Moving money has no price to suggest.
            assertThat(server.get("/api/items/" + transfer + "/plan-suggestion", cookie).data().get("suggestion").isNull()).isTrue();
            assertThat(server.get("/api/items/999/plan-suggestion", cookie).status()).isEqualTo(404);
        }
    }

    @Nested
    class YourAmounts {
        @Test
        void aTypedAmountIsYoursAndCanBeTakenOutAgain() throws Exception {
            long id = startPlan();

            Reply typed = type(id, "item", rent, "1920000");

            assertThat(typed.status()).as(typed.body().toString()).isEqualTo(200);
            assertThat(typed.changed()).isTrue();
            JsonNode rentRow = row(typed.data(), "Rent");
            assertThat(rentRow.get("chosenCents").asLong()).isEqualTo(1_920_000);
            assertThat(rentRow.get("yours").asBoolean()).isTrue();
            assertThat(rentRow.get("suggestedCents").asLong()).isEqualTo(1_800_000);
            assertThat(rentRow.get("perOccurrenceCents").asLong()).isEqualTo(160_000);
            assertThat(step(typed.data(), "bills").get("yours").asInt()).isEqualTo(1);

            JsonNode cleared = type(id, "item", rent, "null").data();
            assertThat(row(cleared, "Rent").get("chosenCents").asLong()).isEqualTo(1_800_000);
            assertThat(row(cleared, "Rent").get("yours").asBoolean()).isFalse();
        }

        @Test
        void takeHomePayIsTypedForOnePaycheck() throws Exception {
            long id = startPlan();

            JsonNode plan = type(id, "income", salary, "260000").data();

            assertThat(row(plan, "Acme Corp").get("yearCents").asLong()).isEqualTo(260_000L * 24);
            assertThat(plan.get("incomeCents").asLong()).isEqualTo(260_000L * 24);
        }

        @Test
        void aDraftChangesNothingYet() throws Exception {
            long id = startPlan();
            type(id, "item", rent, "1920000");

            JsonNode costs = server.get("/api/annual-costs", cookie).data();

            for (JsonNode row : costs.get("rows")) {
                if (row.get("name").asText().equals("Rent")) {
                    assertThat(row.get("totalsCents").get(2).asLong()).isEqualTo(1_800_000);
                }
            }
        }

        @Test
        void acceptingAStepsSuggestionsTakesItsTypedAmountsOut() throws Exception {
            long id = startPlan();
            type(id, "item", rent, "1920000");
            type(id, "item", groceries, "800000");

            JsonNode plan = server.post("/api/plans/" + id + "/accept-suggestions", "{\"step\":\"bills\"}", cookie).data();

            assertThat(row(plan, "Rent").get("yours").asBoolean()).isFalse();
            // Another step's amounts are left as they were typed.
            assertThat(row(plan, "Groceries").get("chosenCents").asLong()).isEqualTo(800_000);

            JsonNode all = server.post("/api/plans/" + id + "/accept-suggestions", "{}", cookie).data();
            assertThat(row(all, "Groceries").get("yours").asBoolean()).isFalse();
        }

        @Test
        void anAmountThatCannotBeRightIsRefused() throws Exception {
            long id = startPlan();

            assertThat(type(id, "item", rent, "-1").status()).isEqualTo(400);
            assertThat(type(id, "item", rent, "99999999999999999").status()).isEqualTo(400);
            assertThat(type(id, "tax", rent, "100").status()).isEqualTo(400);
            assertThat(server.put("/api/plans/" + id + "/amounts", "{\"kind\":\"item\",\"amountCents\":100}", cookie).status())
                    .isEqualTo(400);
            assertThat(server.post("/api/plans/" + id + "/accept-suggestions", "{\"step\":\"taxes\"}", cookie).status()).isEqualTo(400);
            // A line the plan does not hold.
            assertThat(type(id, "item", 999, "100").status()).isEqualTo(404);
            assertThat(type(999, "item", rent, "100").status()).isEqualTo(404);
            assertThat(row(plan(id), "Rent").get("yours").asBoolean()).isFalse();
        }
    }

    @Nested
    class Deleting {
        @Test
        void aDraftCanBeDeletedAndStartedOver() throws Exception {
            long id = startPlan();
            type(id, "item", rent, "1920000");

            Reply deleted = delete(id, "{\"confirm\":\"delete-draft\"}");

            assertThat(deleted.status()).as(deleted.body().toString()).isEqualTo(200);
            assertThat(server.get("/api/plans/" + id, cookie).status()).isEqualTo(404);
            long again = startPlan();
            assertThat(row(plan(again), "Rent").get("yours").asBoolean()).isFalse();
        }

        @Test
        void deletingNeedsToBeMeant() throws Exception {
            long id = startPlan();

            assertThat(delete(id, "{}").status()).isEqualTo(400);
            assertThat(delete(id, "{\"confirm\":\"yes\"}").status()).isEqualTo(400);
            assertThat(delete(999, "{\"confirm\":\"delete-draft\"}").status()).isEqualTo(404);
            assertThat(plan(id).get("plan").get("status").asText()).isEqualTo("draft");
        }

        @Test
        void undoBringsADeletedDraftBack() throws Exception {
            long id = startPlan();
            type(id, "item", rent, "1920000");
            delete(id, "{\"confirm\":\"delete-draft\"}");

            server.post("/api/undo", null, cookie);

            assertThat(row(plan(id), "Rent").get("chosenCents").asLong()).isEqualTo(1_920_000);
        }
    }

    @Nested
    class TakingEffect {
        private JsonNode costsRow(String name) throws Exception {
            for (JsonNode row : server.get("/api/annual-costs", cookie).data().get("rows")) {
                if (row.get("name").asText().equals(name)) {
                    return row.get("totalsCents");
                }
            }
            throw new AssertionError("No annual costs row called " + name);
        }

        @Test
        void nothingHappensBeforeTheYearStarts() throws Exception {
            long id = startPlan();

            assertThat(server.activateDuePlan()).isFalse();

            assertThat(plan(id).get("plan").get("status").asText()).isEqualTo("draft");
        }

        @Test
        void onItsFirstDayThePlanBecomesActiveAndItsAmountsTakeOverFromThatDay() throws Exception {
            long id = startPlan();
            type(id, "item", rent, "1920000");
            type(id, "income", salary, "260000");
            long electric2026 = costsRow("Electric").get(2).asLong();
            goToNewYear();

            assertThat(server.activateDuePlan()).isTrue();

            JsonNode plan = plan(id);
            assertThat(plan.get("plan").get("status").asText()).isEqualTo("active");
            assertThat(plan.get("plan").get("activatedOn").asText()).isEqualTo("2027-01-01");
            // The windows are now 2025, 2026 and 2027: last year is exactly what it was.
            assertThat(costsRow("Rent").get(1).asLong()).isEqualTo(1_800_000);
            assertThat(costsRow("Rent").get(2).asLong()).isEqualTo(1_920_000);
            assertThat(costsRow("Electric").get(1).asLong()).isEqualTo(electric2026);
            // $1,877.00 over twelve bills is $156.42 each.
            assertThat(costsRow("Electric").get(2).asLong()).isEqualTo(15_642 * 12);
            // Phone was suggested at what it already was, so nothing about it changed.
            assertThat(costsRow("Phone").get(2).asLong()).isEqualTo(102_000);

            JsonNode december = server.get("/api/paychecks?from=2026-12-15&to=2026-12-15", cookie).data().get(0);
            JsonNode january = server.get("/api/paychecks?from=2027-01-01&to=2027-01-01", cookie).data().get(0);
            assertThat(december.get("netCents").asLong()).isEqualTo(248_620);
            assertThat(january.get("netCents").asLong()).isEqualTo(260_000);
        }

        @Test
        void thePlanKeepsARecordOfWhatItSet() throws Exception {
            long id = startPlan();
            type(id, "item", rent, "1920000");
            goToNewYear();
            server.activateDuePlan();
            // Rent is changed again later in the year; the plan still says what it set.
            server.put("/api/items/" + rent, """
                    {"name":"Rent","kind":"bill","amountCents":170000,"payFromAccountId":%d,"autopay":true,
                     "schedule":{"frequency":"monthly","day1":1,"startDate":"2025-01-01"},"effectiveFrom":"2027-06-01"}"""
                    .formatted(checking), cookie);

            JsonNode rentRow = row(plan(id), "Rent");

            assertThat(rentRow.get("chosenCents").asLong()).isEqualTo(1_920_000);
            assertThat(rentRow.get("yours").asBoolean()).isTrue();
            assertThat(row(plan(id), "Electric").get("chosenCents").asLong()).isEqualTo(187_700);
            assertThat(row(plan(id), "Electric").get("yours").asBoolean()).isFalse();
        }

        @Test
        void aChangeAlreadySetForLaterInTheYearStillStands() throws Exception {
            long id = startPlan();
            server.put("/api/items/" + electric, ELECTRIC.formatted(15_000, checking, ",\"effectiveFrom\":\"2027-03-01\""), cookie);
            goToNewYear();

            server.activateDuePlan();

            // January and February at the plan's amount, then ten months at the $150.00 already set.
            long perBill = row(plan(id), "Electric").get("perOccurrenceCents").asLong();
            assertThat(costsRow("Electric").get(2).asLong()).isEqualTo(perBill * 2 + 15_000 * 10);
        }

        @Test
        void itHappensOnce() throws Exception {
            startPlan();
            goToNewYear();
            server.activateDuePlan();

            assertThat(server.activateDuePlan()).isFalse();
        }

        @Test
        void anActivePlanCanNoLongerBeChangedOrDeleted() throws Exception {
            long id = startPlan();
            goToNewYear();
            server.activateDuePlan();

            assertThat(type(id, "item", rent, "100").status()).isEqualTo(409);
            assertThat(server.put("/api/plans/" + id, "{\"basis\":\"last_12\"}", cookie).status()).isEqualTo(409);
            assertThat(server.post("/api/plans/" + id + "/accept-suggestions", "{}", cookie).status()).isEqualTo(409);
            assertThat(delete(id, "{\"confirm\":\"delete-draft\"}").status()).isEqualTo(409);
            assertThat(server.post("/api/plans/" + id + "/finish", "{}", cookie).status()).isEqualTo(409);
        }

        @Test
        void thePlanAfterItClosesItWhenItsOwnYearStarts() throws Exception {
            long first = startPlan();
            goToNewYear();
            server.activateDuePlan();
            long second = startPlan();
            assertThat(plan(second).get("plan").get("year").asInt()).isEqualTo(2028);
            later(365);

            assertThat(server.activateDuePlan()).isTrue();

            assertThat(plan(first).get("plan").get("status").asText()).isEqualTo("closed");
            assertThat(plan(second).get("plan").get("status").asText()).isEqualTo("active");
        }

        @Test
        void theDashboardSaysWhenAPlanHasJustTakenEffect() throws Exception {
            assertThat(server.get("/api/dashboard", cookie).data().get("attention").findValuesAsText("kind")).contains("plan_reminder");
            startPlan();
            assertThat(server.get("/api/dashboard", cookie).data().get("attention").findValuesAsText("kind"))
                    .doesNotContain("plan_reminder", "plan_active");
            goToNewYear();
            server.activateDuePlan();

            JsonNode attention = server.get("/api/dashboard", cookie).data().get("attention");

            assertThat(attention.findValuesAsText("kind")).contains("plan_active");
            later(15);
            assertThat(server.get("/api/dashboard", cookie).data().get("attention").findValuesAsText("kind")).doesNotContain("plan_active");
        }
    }
}
