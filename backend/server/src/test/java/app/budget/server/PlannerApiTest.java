package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.engine.BasisPoints;
import app.budget.engine.Money;
import app.budget.engine.loans.Amortization;
import app.budget.engine.loans.Amortization.Schedule;
import app.budget.engine.planner.ContributionGrowth;
import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Debt &amp; decisions over real HTTP against a temporary database (spec §5.9). "Today" is Thursday
 * 2026-10-01; paydays are the 1st and 15th.
 */
class PlannerApiTest extends ApiTestBase {

    private static final String THREE_HUNDRED_FIVE_YEARS = "{\"extraCents\":30000,\"years\":5}";

    long checking;
    long savings;
    long retirement;
    long brokerage;
    long carLoan;
    long mortgage;
    long visa;

    /**
     * Nia's household. A car loan ($14,860.00 at 6.90%, $412.00 on the 15th) and a mortgage
     * ($286,400.00 at 6.25%, $2,100.00 on the 1st of which $300.00 is escrow); a 401(k) assumed to
     * grow 7% a year, where she puts in $150.00 of a $3,600.00 paycheck and her employer matches
     * half of up to 6%; a brokerage account at 6%; savings at 4.10%; and a Visa owing $800.00.
     */
    private void niasHousehold() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        savings = createAccount("Rainy Day Savings", "savings");
        retirement = createAccount("Brightwater 401(k)", "investment");
        brokerage = createAccount("Brokerage", "investment");
        carLoan = createAccount("Car loan", "loan");
        mortgage = createAccount("Mortgage", "loan");
        visa = createAccount("Visa", "credit_card");
        created("/api/income-sources", """
                {"name":"Brightwater Health","depositAccountId":%d,"grossCents":360000,"netCents":248620,"primary":true,
                 "schedule":{"frequency":"semi_monthly","day1":1,"day2":15,"startDate":"2026-01-01","weekendRule":"none"},
                 "deductions":[{"name":"401(k)","amountCents":15000,"toAccountId":%d}],
                 "match":{"toAccountId":%d,"mode":"formula","matchBp":5000,"capBpOfGross":600}}""".formatted(checking, retirement, retirement));
        enterBalance(checking, "2026-10-01", 250_000);
        enterBalance(savings, "2026-10-01", 500_000);
        enterBalance(retirement, "2026-10-01", 4_200_000);
        enterBalance(brokerage, "2026-10-01", 900_000);
        enterBalance(carLoan, "2026-10-01", 1_486_000);
        enterBalance(mortgage, "2026-10-01", 28_640_000);
        enterBalance(visa, "2026-10-01", 80_000);
        rate(savings, 410);
        rate(retirement, 700);
        rate(brokerage, 600);
        rate(carLoan, 690);
        rate(mortgage, 625);
        ok(server.put("/api/accounts/" + carLoan + "/loan", "{\"paymentCents\":41200,\"paymentDay\":15}", cookie));
        ok(server.put("/api/accounts/" + mortgage + "/loan",
                "{\"paymentCents\":210000,\"paymentDay\":1,\"escrowInsuranceCents\":10000,\"escrowTaxesCents\":20000}", cookie));
    }

    private void rate(long accountId, int rateBp) throws Exception {
        Reply reply = server.post("/api/accounts/" + accountId + "/rates",
                "{\"rateBp\":%d,\"effectiveDate\":\"2026-01-01\"}".formatted(rateBp), cookie);
        assertThat(reply.status()).as(reply.body().toString()).isLessThan(300);
    }

    private static void ok(Reply reply) {
        assertThat(reply.status()).as(reply.body().toString()).isLessThan(300);
    }

    private JsonNode compare(String body) throws Exception {
        Reply reply = server.post("/api/planner", body, cookie);
        assertThat(reply.status()).as(reply.body().toString()).isEqualTo(200);
        return reply.data();
    }

    private static JsonNode option(JsonNode comparison, long accountId) {
        for (JsonNode option : comparison.get("options")) {
            if (option.get("accountId").asLong() == accountId) {
                return option;
            }
        }
        return null;
    }

    private static JsonNode onTimeline(JsonNode comparison, long accountId) {
        for (JsonNode debt : comparison.get("payoff").get("debts")) {
            if (debt.get("accountId").asLong() == accountId) {
                return debt;
            }
        }
        return null;
    }

    private static JsonNode flag(JsonNode comparison, String kind) {
        for (JsonNode flag : comparison.get("flags")) {
            if (flag.get("kind").asText().equals(kind)) {
                return flag;
            }
        }
        return null;
    }

    private static LocalDate date(JsonNode node) {
        return LocalDate.parse(node.asText());
    }

    @Nested
    class Auth {
        @Test
        void everyRouteNeedsASession() throws Exception {
            assertThat(server.post("/api/planner", THREE_HUNDRED_FIVE_YEARS, null).status()).isEqualTo(401);
            assertThat(server.put("/api/planner/hidden/1", "{}", null).status()).isEqualTo(401);
            assertThat(server.delete("/api/planner/hidden/1", null).status()).isEqualTo(401);
        }
    }

    @Nested
    class BeforeAnythingIsSetUp {
        @Test
        void aNewAppHasNothingToCompare() throws Exception {
            JsonNode comparison = compare(THREE_HUNDRED_FIVE_YEARS);

            assertThat(comparison.get("today").asText()).isEqualTo("2026-10-01");
            assertThat(comparison.get("extraCents").asLong()).isEqualTo(30_000);
            assertThat(comparison.get("years").asInt()).isEqualTo(5);
            assertThat(comparison.get("flags")).isEmpty();
            assertThat(comparison.get("options")).isEmpty();
            assertThat(comparison.get("hidden")).isEmpty();
            assertThat(comparison.get("payoff").get("order").asText()).isEqualTo("highest_rate");
            assertThat(comparison.get("payoff").get("debts")).isEmpty();
            assertThat(comparison.get("payoff").get("debtFreeDate").isNull()).isTrue();
        }
    }

    @Nested
    class Comparing {
        /** The phase's "done when": $300 a month across the car loan, the mortgage, the 401(k) and the brokerage. */
        @Test
        void threeHundredAMonthIsComparedAcrossDebtsAndInvestments() throws Exception {
            niasHousehold();

            JsonNode comparison = compare(THREE_HUNDRED_FIVE_YEARS);

            // The car loan, against the same schedule its own page shows.
            JsonNode car = option(comparison, carLoan);
            JsonNode carPage = server.get("/api/accounts/" + carLoan + "/loan", cookie).data();
            Schedule carWithExtra = Amortization.schedule(Money.ofCents(1_486_000), BasisPoints.of(690), Money.ofCents(71_200), LocalDate.parse("2026-10-15"));
            assertThat(car.get("kind").asText()).isEqualTo("debt");
            assertThat(car.get("rateBp").asInt()).isEqualTo(690);
            assertThat(car.get("paymentCents").asLong()).isEqualTo(41_200);
            assertThat(car.get("freedCents").asLong()).isEqualTo(71_200);
            assertThat(car.get("payoffDateBefore").asText()).isEqualTo(carPage.get("payoffDate").asText());
            assertThat(date(car.get("payoffDate"))).isEqualTo(carWithExtra.payoffDate());
            assertThat(car.get("interestSavedCents").asLong())
                    .isEqualTo(carPage.get("interestRemainingCents").asLong() - carWithExtra.totalInterest().cents());
            assertThat(car.get("monthsSooner").asInt()).isPositive();

            // The mortgage: escrow never reaches the loan.
            JsonNode home = option(comparison, mortgage);
            JsonNode homePage = server.get("/api/accounts/" + mortgage + "/loan", cookie).data();
            assertThat(home.get("paymentCents").asLong()).isEqualTo(180_000);
            assertThat(home.get("payoffDateBefore").asText()).isEqualTo(homePage.get("payoffDate").asText());
            assertThat(home.get("interestSavedCents").asLong()).isGreaterThan(car.get("interestSavedCents").asLong());

            // The 401(k): $150.00 of each paycheck's extra, of which $66.00 is under the cap and earns $33.00.
            JsonNode k401 = option(comparison, retirement);
            assertThat(k401.get("kind").asText()).isEqualTo("invest");
            assertThat(k401.get("rateBp").asInt()).isEqualTo(700);
            assertThat(k401.get("matchedPerMonthCents").asLong()).isEqualTo(13_200);
            assertThat(k401.get("matchPerMonthCents").asLong()).isEqualTo(6_600);
            assertThat(k401.get("putInCents").asLong()).isEqualTo(1_800_000);
            assertThat(k401.get("matchCents").asLong()).isEqualTo(396_000);
            ContributionGrowth.Value withMatch = ContributionGrowth.of(Money.ofCents(36_600), BasisPoints.of(700), 60);
            assertThat(k401.get("valueCents").asLong()).isEqualTo(withMatch.total().cents());
            assertThat(k401.get("valueCents").asLong())
                    .isEqualTo(k401.get("putInCents").asLong() + k401.get("matchCents").asLong() + k401.get("growthCents").asLong());

            // The brokerage: no match, its own 6%.
            JsonNode invested = option(comparison, brokerage);
            assertThat(invested.get("matchCents").asLong()).isZero();
            assertThat(invested.get("valueCents").asLong())
                    .isEqualTo(ContributionGrowth.of(Money.ofCents(30_000), BasisPoints.of(600), 60).total().cents());
            assertThat(k401.get("valueCents").asLong()).isGreaterThan(invested.get("valueCents").asLong());

            // Savings is offered at its APY; checking is not offered.
            assertThat(option(comparison, savings).get("kind").asText()).isEqualTo("save");
            assertThat(option(comparison, savings).get("rateBp").asInt()).isEqualTo(410);
            assertThat(option(comparison, checking)).isNull();
        }

        @Test
        void theUnclaimedMatchIsFlaggedFirst() throws Exception {
            niasHousehold();

            JsonNode match = flag(compare(THREE_HUNDRED_FIVE_YEARS), "unclaimed_match");

            // $66.00 more a paycheck, twice a month, brings $33.00 each time.
            assertThat(match.get("name").asText()).isEqualTo("Brightwater Health");
            assertThat(match.get("accountName").asText()).isEqualTo("Brightwater 401(k)");
            assertThat(match.get("amountCents").asLong()).isEqualTo(13_200);
            assertThat(match.get("matchCents").asLong()).isEqualTo(6_600);
            assertThat(match.get("capBp").asInt()).isEqualTo(600);
            assertThat(match.get("putInBp").asInt()).isEqualTo(417);
        }

        @Test
        void aCardWithNoPaymentSetUpIsFollowedAtItsEstimatedMinimum() throws Exception {
            niasHousehold();

            JsonNode card = option(compare(THREE_HUNDRED_FIVE_YEARS), visa);

            // No APR entered: 1% of $800.00 is under the $25.00 floor.
            assertThat(card.get("kind").asText()).isEqualTo("debt");
            assertThat(card.get("rateBp").isNull()).isTrue();
            assertThat(card.get("paymentCents").asLong()).isEqualTo(2_500);
            assertThat(card.get("interestSavedCents").asLong()).isZero();
            assertThat(date(card.get("payoffDate"))).isBefore(date(card.get("payoffDateBefore")));
        }

        @Test
        void thePayoffOrderRollsEachPaymentIntoTheNextDebt() throws Exception {
            niasHousehold();

            JsonNode highest = compare("{\"extraCents\":30000,\"years\":5,\"order\":\"highest_rate\"}").get("payoff");
            JsonNode smallest = compare("{\"extraCents\":30000,\"years\":5,\"order\":\"smallest_balance\"}").get("payoff");

            assertThat(highest.get("debts")).hasSize(3);
            assertThat(date(highest.get("debtFreeDate"))).isBefore(date(highest.get("debtFreeDateBefore")));
            assertThat(highest.get("interestSavedCents").asLong()).isPositive();
            // Smallest balance first clears the Visa at once; highest rate first sends the extra to the car loan.
            assertThat(smallest.get("order").asText()).isEqualTo("smallest_balance");
            assertThat(smallest.get("debts").get(0).get("accountId").asLong()).isEqualTo(visa);
            JsonNode visaHighest = null;
            JsonNode visaSmallest = null;
            for (JsonNode debt : highest.get("debts")) {
                visaHighest = debt.get("accountId").asLong() == visa ? debt : visaHighest;
            }
            for (JsonNode debt : smallest.get("debts")) {
                visaSmallest = debt.get("accountId").asLong() == visa ? debt : visaSmallest;
            }
            assertThat(date(visaSmallest.get("payoffDate"))).isBefore(date(visaHighest.get("payoffDate")));
            // Paying the dearest first never costs more interest.
            assertThat(highest.get("interestSavedCents").asLong()).isGreaterThanOrEqualTo(smallest.get("interestSavedCents").asLong());
        }

        @Test
        void withNoExtraNothingIsSaved() throws Exception {
            niasHousehold();

            JsonNode comparison = compare("{\"extraCents\":0,\"years\":5}");

            JsonNode car = option(comparison, carLoan);
            assertThat(car.get("interestSavedCents").asLong()).isZero();
            assertThat(car.get("payoffDate").asText()).isEqualTo(car.get("payoffDateBefore").asText());
            assertThat(option(comparison, brokerage).get("valueCents").asLong()).isZero();
        }

        @Test
        void anAssumedGrowthRateTypedInReplacesTheAccountsOwnForThisComparison() throws Exception {
            niasHousehold();

            JsonNode invested = option(
                    compare("{\"extraCents\":30000,\"years\":5,\"growthBp\":{\"%d\":800}}".formatted(brokerage)), brokerage);

            assertThat(invested.get("rateBp").asInt()).isEqualTo(800);
            assertThat(invested.get("accountRateBp").asInt()).isEqualTo(600);
            assertThat(invested.get("valueCents").asLong())
                    .isEqualTo(ContributionGrowth.of(Money.ofCents(30_000), BasisPoints.of(800), 60).total().cents());
            // Nothing was saved: asked again without it, the account's own rate is back.
            assertThat(option(compare(THREE_HUNDRED_FIVE_YEARS), brokerage).get("rateBp").asInt()).isEqualTo(600);
        }

        @Test
        void aLoanWithNoMonthlyPaymentSaysSoAndStaysOffTheTimeline() throws Exception {
            checking = createAccount("Everyday Checking", "checking");
            long student = createAccount("Student loan", "loan");
            enterBalance(student, "2026-10-01", 1_200_000);

            JsonNode comparison = compare(THREE_HUNDRED_FIVE_YEARS);

            JsonNode option = option(comparison, student);
            assertThat(option.get("problem").asText()).isEqualTo("no_payment");
            assertThat(option.get("payoffDate").isNull()).isTrue();
            assertThat(onTimeline(comparison, student)).isNull();
        }

        @Test
        void comparingIsNotSomethingToUndo() throws Exception {
            niasHousehold();

            Reply reply = server.post("/api/planner", THREE_HUNDRED_FIVE_YEARS, cookie);

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.changed()).isFalse();
        }
    }

    @Nested
    class Promotions {
        long discover;

        private void aZeroPercentBalance() throws Exception {
            checking = createAccount("Everyday Checking", "checking");
            createSalary(checking);
            discover = createAccount("Discover", "credit_card");
            enterBalance(discover, "2026-10-01", 240_000);
            Reply promo = server.post("/api/cards/" + discover + "/promos", """
                    {"label":"0% balance transfer","kind":"balance_transfer","rateBp":0,"startDate":"2026-03-15","endDate":"2027-03-15",
                     "rateAfterBp":2449,"deferredInterest":false}""", cookie);
            assertThat(promo.status()).as(promo.body().toString()).isEqualTo(201);
        }

        @Test
        void aPromotionalRateIsFlaggedAndTheRateAfterItIsUsedFromItsEnd() throws Exception {
            aZeroPercentBalance();

            JsonNode comparison = compare(THREE_HUNDRED_FIVE_YEARS);

            JsonNode flag = flag(comparison, "promo_ending");
            assertThat(flag.get("name").asText()).isEqualTo("0% balance transfer");
            assertThat(flag.get("accountName").asText()).isEqualTo("Discover");
            assertThat(flag.get("date").asText()).isEqualTo("2027-03-15");
            assertThat(flag.get("amountCents").asLong()).isEqualTo(240_000);
            assertThat(flag.get("planned").asBoolean()).isFalse();

            JsonNode card = option(comparison, discover);
            assertThat(card.get("rateBp").asInt()).isZero();
            assertThat(card.get("rateAfterBp").asInt()).isEqualTo(2449);
            assertThat(card.get("rateChangesOn").asText()).isEqualTo("2027-03-16");
            // On its $25.00 minimum alone, the interest from March on is more than the payment: it is never paid off,
            // so there is no "interest saved" to give. With $300.00 more a month it is gone in eight payments.
            assertThat(card.get("paymentCents").asLong()).isEqualTo(2_500);
            assertThat(card.get("paysOffBefore").asBoolean()).isFalse();
            assertThat(card.get("payoffDateBefore").isNull()).isTrue();
            assertThat(card.get("interestSavedCents").isNull()).isTrue();
            assertThat(card.get("paysOff").asBoolean()).isTrue();
            assertThat(card.get("payoffDate").asText()).isEqualTo("2027-06-01");
        }

        @Test
        void aPromotionWithAPayoffPlannedTakesNoneOfTheExtra() throws Exception {
            aZeroPercentBalance();
            long promoId = server.get("/api/cards/" + discover, cookie).data().get("promos").get(0).get("id").asLong();
            Reply payoff = server.post("/api/cards/" + discover + "/promos/" + promoId + "/payoff", "{}", cookie);
            assertThat(payoff.status()).as(payoff.body().toString()).isEqualTo(201);

            JsonNode comparison = compare(THREE_HUNDRED_FIVE_YEARS);

            // Ten paydays before it ends: $240.00 each.
            JsonNode flag = flag(comparison, "promo_ending");
            assertThat(flag.get("planned").asBoolean()).isTrue();
            assertThat(flag.get("perPaycheckCents").asLong()).isEqualTo(24_000);
            assertThat(option(comparison, discover)).isNull();
            JsonNode row = onTimeline(comparison, discover);
            assertThat(row.get("planned").asBoolean()).isTrue();
            assertThat(row.get("payoffDate").asText()).isEqualTo("2027-03-15");
        }
    }

    @Nested
    class Hiding {
        @Test
        void aHiddenAccountIsLeftOutUntilItIsShownAgain() throws Exception {
            niasHousehold();

            Reply hide = server.put("/api/planner/hidden/" + carLoan, "{}", cookie);
            assertThat(hide.status()).isEqualTo(200);
            assertThat(hide.changed()).isTrue();

            JsonNode comparison = compare(THREE_HUNDRED_FIVE_YEARS);
            assertThat(option(comparison, carLoan)).isNull();
            assertThat(onTimeline(comparison, carLoan)).isNull();
            assertThat(comparison.get("hidden")).hasSize(1);
            assertThat(comparison.get("hidden").get(0).get("accountId").asLong()).isEqualTo(carLoan);
            assertThat(comparison.get("hidden").get(0).get("name").asText()).isEqualTo("Car loan");

            // Hiding it twice is the same as once.
            assertThat(server.put("/api/planner/hidden/" + carLoan, "{}", cookie).status()).isEqualTo(200);
            assertThat(compare(THREE_HUNDRED_FIVE_YEARS).get("hidden")).hasSize(1);

            assertThat(server.delete("/api/planner/hidden/" + carLoan, cookie).status()).isEqualTo(200);
            JsonNode again = compare(THREE_HUNDRED_FIVE_YEARS);
            assertThat(option(again, carLoan)).isNotNull();
            assertThat(again.get("hidden")).isEmpty();
        }

        @Test
        void undoBringsAHiddenAccountBack() throws Exception {
            niasHousehold();
            server.put("/api/planner/hidden/" + brokerage, "{}", cookie);

            assertThat(server.post("/api/undo", "{}", cookie).data().get("undone").asBoolean()).isTrue();

            assertThat(option(compare(THREE_HUNDRED_FIVE_YEARS), brokerage)).isNotNull();
        }

        @Test
        void aCheckingAccountOrOneThatDoesNotExistCannotBeHidden() throws Exception {
            checking = createAccount("Everyday Checking", "checking");

            Reply reply = server.put("/api/planner/hidden/" + checking, "{}", cookie);
            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.error()).contains("checking account");
            assertThat(server.put("/api/planner/hidden/9999", "{}", cookie).status()).isEqualTo(404);
            assertThat(server.delete("/api/planner/hidden/not-a-number", cookie).status()).isEqualTo(404);
        }

        @Test
        void deletingAnAccountForgetsThatItWasHidden() throws Exception {
            long old = createAccount("Old brokerage", "investment");
            server.put("/api/planner/hidden/" + old, "{}", cookie);

            assertThat(server.delete("/api/accounts/" + old, cookie).status()).isEqualTo(200);

            assertThat(compare(THREE_HUNDRED_FIVE_YEARS).get("hidden")).isEmpty();
        }
    }

    @Nested
    class Validation {
        @Test
        void whatIsAskedHasToMakeSense() throws Exception {
            assertThat(server.post("/api/planner", "{\"extraCents\":30000,\"years\":2}", cookie).error()).contains("how long to compare over");
            assertThat(server.post("/api/planner", "{\"extraCents\":30000}", cookie).status()).isEqualTo(400);
            assertThat(server.post("/api/planner", "{\"extraCents\":-1,\"years\":5}", cookie).error()).contains("can't be less than zero");
            assertThat(server.post("/api/planner", "{\"years\":5}", cookie).status()).isEqualTo(400);
            assertThat(server.post("/api/planner", "{\"extraCents\":30000,\"years\":5,\"order\":\"alphabetical\"}", cookie).error())
                    .contains("the payoff order");
            assertThat(server.post("/api/planner", "{\"extraCents\":30000,\"years\":5,\"growthBp\":{\"1\":10001}}", cookie).error())
                    .contains("between 0% and 100%");
            assertThat(server.post("/api/planner", "{\"extraCents\":30000,\"years\":5,\"growthBp\":{\"abc\":700}}", cookie).status())
                    .isEqualTo(400);
            assertThat(server.post("/api/planner", "not json", cookie).status()).isEqualTo(400);
        }
    }
}
