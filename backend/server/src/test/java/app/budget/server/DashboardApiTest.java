package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The dashboard over real HTTP against a temporary database (spec §8). "Today" is Thursday
 * 2026-10-01; paydays are the 1st and 15th.
 */
class DashboardApiTest extends ApiTestBase {

    private static final long NET = 248_620;

    long checking;
    long savings;
    long retirement;
    long visa;
    long housing;
    long food;
    long rent;
    long water;

    /**
     * Amara's budget. Checking held $100.00 on Sep 15 and warns under $1,000.00; savings held
     * $5,000.00 on Aug 15; the Visa owed $800.00 on Sep 15. Each paycheck brings $2,486.20 home,
     * puts $150.00 in the 401(k) with $50.00 from her employer, and $200.00 moves to savings.
     * Rent ($1,400.00, the 1st) and water ($50.00, the 20th) are paid by hand and not ticked.
     */
    private void amarasBudget() throws Exception {
        checking = server.post("/api/accounts",
                "{\"name\":\"Everyday Checking\",\"type\":\"checking\",\"color\":\"#0f766e\",\"lowBalanceThresholdCents\":100000}", cookie)
                .data().get("account").get("id").asLong();
        savings = createAccount("Rainy Day Savings", "savings");
        retirement = createAccount("Brightwater 401(k)", "investment");
        visa = createAccount("Visa", "credit_card");
        created("/api/income-sources", """
                {"name":"Brightwater Health","depositAccountId":%d,"grossCents":360000,"netCents":248620,"primary":true,
                 "schedule":{"frequency":"semi_monthly","day1":1,"day2":15,"startDate":"2026-01-01","weekendRule":"none"},
                 "deductions":[{"name":"401(k)","amountCents":15000,"toAccountId":%d}],
                 "match":{"toAccountId":%d,"mode":"fixed","fixedCents":5000}}""".formatted(checking, retirement, retirement));
        enterBalance(checking, "2026-09-15", 10_000);
        enterBalance(savings, "2026-08-15", 500_000);
        enterBalance(visa, "2026-09-15", 80_000);

        housing = created("/api/categories", "{\"name\":\"Housing\",\"color\":\"#0f766e\"}");
        food = created("/api/categories", "{\"name\":\"Food\",\"color\":\"#15803d\"}");
        long groceries = created("/api/categories", "{\"name\":\"Groceries\",\"color\":\"#166534\",\"parentId\":%d}".formatted(food));
        rent = created("/api/items", """
                {"name":"Rent","kind":"bill","amountCents":140000,"payFromAccountId":%d,"categoryId":%d,
                 "schedule":{"frequency":"monthly","day1":1,"startDate":"2026-01-01"}}""".formatted(checking, housing));
        water = created("/api/items", """
                {"name":"Water","kind":"bill","amountCents":5000,"payFromAccountId":%d,
                 "schedule":{"frequency":"monthly","day1":20,"startDate":"2026-01-01"}}""".formatted(checking));
        created("/api/items", """
                {"name":"To savings","kind":"transfer","amountCents":20000,"payFromAccountId":%d,"payToAccountId":%d,"autopay":true,
                 "schedule":{"frequency":"every_pay_period","startDate":"2026-01-01"}}""".formatted(checking, savings));
        created("/api/entries", """
                {"kind":"money_out","date":"2026-09-20","amountCents":6450,"description":"Farmers market","accountId":%d,"categoryId":%d}"""
                .formatted(checking, groceries));
        Reply bucket = server.post("/api/buckets", """
                {"accountId":%d,"name":"Holiday gifts","color":"#b45309","goalCents":100000,"goalDate":"2026-12-01"}"""
                .formatted(savings), cookie);
        assertThat(bucket.status()).as(bucket.body().toString()).isEqualTo(201);
    }

    private JsonNode dashboard(String query) throws Exception {
        Reply reply = server.get("/api/dashboard" + query, cookie);
        assertThat(reply.status()).as(reply.body().toString()).isEqualTo(200);
        return reply.data();
    }

    private static JsonNode attention(JsonNode dashboard, String kind) {
        for (JsonNode item : dashboard.get("attention")) {
            if (item.get("kind").asText().equals(kind)) {
                return item;
            }
        }
        throw new AssertionError("Nothing of kind " + kind + " in " + dashboard.get("attention"));
    }

    @Nested
    class Auth {
        @Test
        void itNeedsASession() throws Exception {
            assertThat(server.get("/api/dashboard", null).status()).isEqualTo(401);
        }
    }

    @Nested
    class BeforeAnythingIsSetUp {
        @Test
        void aNewAppAnswersWithZerosAndNothingToDo() throws Exception {
            JsonNode dashboard = dashboard("");

            assertThat(dashboard.get("today").asText()).isEqualTo("2026-10-01");
            assertThat(dashboard.get("range").asText()).isEqualTo("three_months");
            assertThat(dashboard.get("netWorth").get("cents").asLong()).isZero();
            assertThat(dashboard.get("netWorth").get("changeCents").isNull()).isTrue();
            assertThat(dashboard.get("lowestAhead").isNull()).isTrue();
            assertThat(dashboard.get("saved").get("totalCents").asLong()).isZero();
            assertThat(dashboard.get("debt").get("totalCents").asLong()).isZero();
            assertThat(dashboard.get("spending").get("totalCents").asLong()).isZero();
            assertThat(dashboard.get("spending").get("categories")).isEmpty();
            assertThat(dashboard.get("netWorthOverTime")).isEmpty();
            assertThat(dashboard.get("attention")).isEmpty();
        }

        @Test
        void aRangeThatIsNotOnTheListIsRefusedKindly() throws Exception {
            Reply reply = server.get("/api/dashboard?range=decade", cookie);

            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.error()).contains("the dates");
        }
    }

    @Nested
    class Figures {
        @Test
        void netWorthIsTheSameFigureTheAccountsScreenShows() throws Exception {
            amarasBudget();

            JsonNode dashboard = dashboard("");

            long shown = server.get("/api/net-worth", cookie).data().get("netWorthCents").asLong();
            assertThat(dashboard.get("netWorth").get("cents").asLong()).isEqualTo(shown);
            assertThat(dashboard.get("netWorth").get("since").asText()).isEqualTo("2026-10-01");
            assertThat(dashboard.get("netWorth").get("changeCents").isNull()).isFalse();
        }

        @Test
        void lowestBalanceAheadNamesTheAccountAndThePayPeriod() throws Exception {
            amarasBudget();

            JsonNode lowest = dashboard("").get("lowestAhead");

            // $100.00, the Sep 15 paycheck, then $200.00 to savings, water, the market and rent: $871.70 as October opens.
            assertThat(lowest.get("accountId").asLong()).isEqualTo(checking);
            assertThat(lowest.get("accountName").asText()).isEqualTo("Everyday Checking");
            assertThat(lowest.get("periodStart").asText()).isEqualTo("2026-10-01");
            assertThat(lowest.get("cents").asLong()).isEqualTo(87_170);
            assertThat(lowest.get("low").asBoolean()).isTrue();
        }

        @Test
        void savedAndInvestedIsTransfersContributionsAndMatchThisYear() throws Exception {
            amarasBudget();

            JsonNode saved = dashboard("").get("saved");

            // Nineteen paydays from January 1 to today, October 1 among them.
            assertThat(saved.get("transfersCents").asLong()).isEqualTo(19 * 20_000);
            assertThat(saved.get("contributionsCents").asLong()).isEqualTo(19 * 15_000);
            assertThat(saved.get("matchCents").asLong()).isEqualTo(19 * 5_000);
            assertThat(saved.get("totalCents").asLong()).isEqualTo(19 * 40_000);
        }

        @Test
        void totalDebtIsWhatIsOwedOnCardsAndLoans() throws Exception {
            amarasBudget();

            JsonNode debt = dashboard("").get("debt");

            assertThat(debt.get("cardsCents").asLong()).isEqualTo(80_000);
            assertThat(debt.get("loansCents").asLong()).isZero();
            assertThat(debt.get("totalCents").asLong()).isEqualTo(80_000);
        }
    }

    @Nested
    class SpendingByCategory {
        @Test
        void theLastThreeMonthsAreSharedOutByTopLevelCategoryLargestFirst() throws Exception {
            amarasBudget();

            JsonNode spending = dashboard("?range=three_months").get("spending");

            assertThat(spending.get("from").asText()).isEqualTo("2026-07-02");
            assertThat(spending.get("to").asText()).isEqualTo("2026-10-01");
            // Rent on Aug 1, Sep 1 and Oct 1; water on Jul 20, Aug 20 and Sep 20; the market under Food › Groceries.
            assertThat(spending.get("categories").findValuesAsText("name")).containsExactly("Housing", "No category", "Food");
            assertThat(spending.get("categories").get(0).get("totalCents").asLong()).isEqualTo(420_000);
            assertThat(spending.get("categories").get(0).get("color").asText()).isEqualTo("#0f766e");
            assertThat(spending.get("categories").get(1).get("categoryId").isNull()).isTrue();
            assertThat(spending.get("categories").get(2).get("categoryId").asLong()).isEqualTo(food);
            assertThat(spending.get("categories").get(2).get("totalCents").asLong()).isEqualTo(6_450);
            assertThat(spending.get("totalCents").asLong()).isEqualTo(420_000 + 15_000 + 6_450);
        }

        @Test
        void thisPeriodIsThePayPeriodInHandWithWhatIsStillToCome() throws Exception {
            amarasBudget();

            JsonNode spending = dashboard("?range=period").get("spending");

            assertThat(spending.get("from").asText()).isEqualTo("2026-10-01");
            assertThat(spending.get("to").asText()).isEqualTo("2026-10-14");
            assertThat(spending.get("totalCents").asLong()).isEqualTo(140_000);
        }

        @Test
        void thisYearRunsFromJanuaryFirstToToday() throws Exception {
            amarasBudget();

            JsonNode spending = dashboard("?range=year").get("spending");

            assertThat(spending.get("from").asText()).isEqualTo("2026-01-01");
            assertThat(spending.get("to").asText()).isEqualTo("2026-10-01");
            assertThat(spending.get("totalCents").asLong()).isEqualTo(10 * 140_000 + 9 * 5_000 + 6_450);
        }
    }

    @Nested
    class NetWorthOverTime {
        @Test
        void itRunsFromTheFirstBalanceEnteredThroughTodayToTheLookAhead() throws Exception {
            amarasBudget();

            JsonNode dashboard = dashboard("");
            List<String> dates = new ArrayList<>(dashboard.get("netWorthOverTime").findValuesAsText("date"));

            // Month ends, with today among them; nothing was known before August.
            assertThat(dates).startsWith("2026-08-31", "2026-09-30", "2026-10-01", "2026-10-31");
            assertThat(dates.getLast()).isEqualTo("2027-10-31");
            JsonNode today = dashboard.get("netWorthOverTime").get(dates.indexOf("2026-10-01"));
            assertThat(today.get("projected").asBoolean()).isFalse();
            assertThat(today.get("netWorthCents").asLong()).isEqualTo(dashboard.get("netWorth").get("cents").asLong());
            assertThat(dashboard.get("netWorthOverTime").get(dates.indexOf("2026-10-31")).get("projected").asBoolean()).isTrue();
        }

        @Test
        void theChangeThisMonthIsMeasuredFromTheEndOfLastMonth() throws Exception {
            amarasBudget();

            JsonNode dashboard = dashboard("");
            List<String> dates = dashboard.get("netWorthOverTime").findValuesAsText("date");
            long endOfSeptember = dashboard.get("netWorthOverTime").get(dates.indexOf("2026-09-30")).get("netWorthCents").asLong();

            assertThat(dashboard.get("netWorth").get("changeCents").asLong())
                    .isEqualTo(dashboard.get("netWorth").get("cents").asLong() - endOfSeptember);
        }
    }

    @Nested
    class NeedsAttention {
        @Test
        void whatIsLateComesFirstThenWhatIsAboutToGoWrongThenWhatIsDue() throws Exception {
            amarasBudget();

            JsonNode dashboard = dashboard("");

            // "Today" is the day the plan reminder starts by default, so it closes the list.
            assertThat(dashboard.get("attention").findValuesAsText("kind"))
                    .containsExactly("past_due", "low_balance", "due_soon", "goal_short", "balance_stale", "plan_reminder");
        }

        @Test
        void aBillPaidByHandAndNotTickedIsPastDueOnceItsDayHasGone() throws Exception {
            amarasBudget();

            JsonNode late = attention(dashboard(""), "past_due");

            assertThat(late.get("name").asText()).isEqualTo("Water");
            assertThat(late.get("date").asText()).isEqualTo("2026-09-20");
            assertThat(late.get("amountCents").asLong()).isEqualTo(5_000);
            assertThat(late.get("itemId").asLong()).isEqualTo(water);
            assertThat(late.get("periodStart").asText()).isEqualTo("2026-09-15");
        }

        @Test
        void tickingItPaidTakesItOffTheList() throws Exception {
            amarasBudget();
            server.put("/api/items/" + water + "/occurrences/2026-09-20", "{\"paid\":true}", cookie);

            assertThat(dashboard("").get("attention").findValuesAsText("kind")).doesNotContain("past_due");
        }

        @Test
        void aBillDueWithinTheHighlightDaysIsDueSoonAndTheSettingCanTurnThatOff() throws Exception {
            amarasBudget();

            JsonNode soon = attention(dashboard(""), "due_soon");
            assertThat(soon.get("name").asText()).isEqualTo("Rent");
            assertThat(soon.get("date").asText()).isEqualTo("2026-10-01");
            assertThat(soon.get("itemId").asLong()).isEqualTo(rent);

            server.put("/api/settings/unpaid_highlight_days", "{\"value\":\"0\"}", cookie);
            assertThat(dashboard("").get("attention").findValuesAsText("kind")).doesNotContain("due_soon");
        }

        @Test
        void anAccountThatDipsUnderItsWarningSaysInWhichPayPeriod() throws Exception {
            amarasBudget();

            JsonNode low = attention(dashboard(""), "low_balance");

            assertThat(low.get("name").asText()).isEqualTo("Everyday Checking");
            assertThat(low.get("accountId").asLong()).isEqualTo(checking);
            assertThat(low.get("periodStart").asText()).isEqualTo("2026-10-01");
            assertThat(low.get("amountCents").asLong()).isEqualTo(87_170);
        }

        @Test
        void aGoalThatWillNotBeReachedSaysHowFarShort() throws Exception {
            amarasBudget();

            JsonNode goal = attention(dashboard(""), "goal_short");

            assertThat(goal.get("name").asText()).isEqualTo("Holiday gifts");
            assertThat(goal.get("date").asText()).isEqualTo("2026-12-01");
            assertThat(goal.get("amountCents").asLong()).isEqualTo(100_000);
            assertThat(goal.get("goalCents").asLong()).isEqualTo(100_000);
            assertThat(goal.get("accountId").asLong()).isEqualTo(savings);
        }

        @Test
        void aBalanceNotEnteredForOverAMonthIsMentionedOnceForAllSuchAccounts() throws Exception {
            amarasBudget();

            JsonNode stale = attention(dashboard(""), "balance_stale");

            // Savings, Aug 15. Checking and the Visa were entered two weeks ago; the 401(k) never was, which is fine.
            assertThat(stale.get("name").asText()).isEqualTo("Rainy Day Savings");
            assertThat(stale.get("date").asText()).isEqualTo("2026-08-15");
            assertThat(stale.get("count").asInt()).isEqualTo(1);
            assertThat(stale.get("accountId").asLong()).isEqualTo(savings);
        }

        @Test
        void aPromotionEndingWithinTheWarningDaysWithABalanceLeftIsListed() throws Exception {
            amarasBudget();
            server.put("/api/cards/" + visa + "/settings", "{\"statementCloseDay\":20,\"dueDay\":13}", cookie);
            Reply promo = server.post("/api/cards/" + visa + "/promos", """
                    {"label":"Balance transfer","kind":"balance_transfer","rateBp":0,"startDate":"2026-03-15",
                     "endDate":"2026-11-10","originalBalanceCents":60000,"deferredInterest":true}""", cookie);
            assertThat(promo.status()).as(promo.body().toString()).isEqualTo(201);

            JsonNode ending = attention(dashboard(""), "promo_ending");

            assertThat(ending.get("name").asText()).isEqualTo("Balance transfer");
            assertThat(ending.get("date").asText()).isEqualTo("2026-11-10");
            assertThat(ending.get("accountId").asLong()).isEqualTo(visa);
            assertThat(ending.get("amountCents").asLong()).isPositive();
            assertThat(ending.get("urgent").asBoolean()).isTrue();
        }

        @Test
        void aBillThatCostsMoreThanLastYearIsPointedOut() throws Exception {
            amarasBudget();
            long internet = created("/api/items", """
                    {"name":"Internet","kind":"bill","amountCents":7000,"payFromAccountId":%d,"autopay":true,
                     "schedule":{"frequency":"monthly","day1":5,"startDate":"2025-01-01"}}""".formatted(checking));
            server.put("/api/items/" + internet, """
                    {"name":"Internet","kind":"bill","amountCents":8400,"payFromAccountId":%d,"autopay":true,"effectiveFrom":"2026-01-01",
                     "schedule":{"frequency":"monthly","day1":5,"startDate":"2025-01-01"}}""".formatted(checking), cookie);

            JsonNode price = attention(dashboard(""), "price_up");

            assertThat(price.get("name").asText()).isEqualTo("Internet");
            // $70.00 to $84.00 a month: 20% and $168.00 more over the year.
            assertThat(price.get("count").asInt()).isEqualTo(2_000);
            assertThat(price.get("amountCents").asLong()).isEqualTo(16_800);
        }
    }
}
