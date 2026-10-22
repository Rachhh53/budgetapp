package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Investment accounts over real HTTP (spec §5.5): what a paycheck puts into them, the
 * employer's match, assumed growth, and gain or loss between entered balances.
 * "Today" is Thursday 2026-10-01; paydays are the 1st and 15th, gross $3,600.
 */
class InvestmentsApiTest extends ApiTestBase {

    long checking;
    long retirement;
    long brokerage;

    @BeforeEach
    void addAccounts() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        retirement = createAccount("Brightwater 401(k)", "investment");
        brokerage = createAccount("Brokerage", "investment");
    }

    /** The salary, putting {@code employeeCents} of each paycheck into the 401(k), with {@code match} as its match (or "null"). */
    private String salary(long employeeCents, String match) {
        return """
                {"name":"Brightwater Health","depositAccountId":%d,"grossCents":360000,"netCents":248620,"primary":true,
                 "schedule":{"frequency":"semi_monthly","day1":1,"day2":15,"startDate":"2026-01-01","weekendRule":"none"},
                 "deductions":[{"name":"401(k)","amountCents":%d,"toAccountId":%d},{"name":"Health plan","amountCents":8200}],
                 "match":%s}"""
                .formatted(checking, employeeCents, retirement, match);
    }

    /** 50% of what is contributed, up to 6% of gross pay. */
    private String halfUpToSixPercent() {
        return "{\"toAccountId\":%d,\"mode\":\"formula\",\"matchBp\":5000,\"capBpOfGross\":600}".formatted(retirement);
    }

    private Reply addSalary(long employeeCents, String match) throws Exception {
        return server.post("/api/income-sources", salary(employeeCents, match), cookie);
    }

    private JsonNode period(String start) throws Exception {
        Reply reply = server.get("/api/pay-periods/" + start, cookie);
        assertThat(reply.status()).isEqualTo(200);
        return reply.data();
    }

    private static JsonNode account(JsonNode period, long accountId) {
        return StreamSupport.stream(period.get("accounts").spliterator(), false)
                .filter(account -> account.get("accountId").asLong() == accountId)
                .findFirst()
                .orElseThrow();
    }

    private void assertError(Reply reply, String message) {
        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.error()).isEqualTo(message);
    }

    @Nested
    class EmployerMatch {
        @Test
        void aContributionUnderTheCapIsMatchedAndSaysWhatIsLeftOnTheTable() throws Exception {
            Reply reply = addSalary(15000, halfUpToSixPercent());

            assertThat(reply.status()).isEqualTo(201);
            JsonNode match = reply.data().get("match");
            assertThat(match.get("toAccountId").asLong()).isEqualTo(retirement);
            assertThat(match.get("mode").asText()).isEqualTo("formula");
            assertThat(match.get("matchBp").asInt()).isEqualTo(5000);
            assertThat(match.get("capBpOfGross").asInt()).isEqualTo(600);
            // $150 of $3,600 is 4.17%: half of it is matched, and $66 more a paycheck would earn the rest.
            JsonNode figures = reply.data().get("matchFigures");
            assertThat(figures.get("perPaycheckCents").asLong()).isEqualTo(7500);
            assertThat(figures.get("mostPossibleCents").asLong()).isEqualTo(10800);
            assertThat(figures.get("contributeMoreCents").asLong()).isEqualTo(6600);
        }

        @Test
        void atTheCapAndOverItTheMatchIsTheSame() throws Exception {
            JsonNode atCap = addSalary(21600, halfUpToSixPercent()).data().get("matchFigures");
            assertThat(atCap.get("perPaycheckCents").asLong()).isEqualTo(10800);
            assertThat(atCap.get("contributeMoreCents").asLong()).isZero();

            long id = server.get("/api/income-sources", cookie).data().get(0).get("id").asLong();
            JsonNode over = server.put("/api/income-sources/" + id, salary(30000, halfUpToSixPercent()), cookie).data().get("matchFigures");
            assertThat(over.get("perPaycheckCents").asLong()).isEqualTo(10800);
        }

        @Test
        void aFixedMatchIsTheSameEveryPaycheck() throws Exception {
            Reply reply = addSalary(0, "{\"toAccountId\":%d,\"mode\":\"fixed\",\"fixedCents\":5000}".formatted(retirement));

            assertThat(reply.status()).isEqualTo(201);
            assertThat(reply.data().get("match").get("fixedCents").asLong()).isEqualTo(5000);
            assertThat(reply.data().get("match").get("matchBp").isNull()).isTrue();
            assertThat(reply.data().get("matchFigures").get("perPaycheckCents").asLong()).isEqualTo(5000);
        }

        @Test
        void aSourceWithoutAMatchHasNoneAndAMatchCanBeTakenOffAgain() throws Exception {
            long id = addSalary(15000, halfUpToSixPercent()).data().get("id").asLong();

            Reply without = server.put("/api/income-sources/" + id, salary(15000, "null"), cookie);

            assertThat(without.status()).isEqualTo(200);
            assertThat(without.data().get("match").isNull()).isTrue();
            assertThat(without.data().get("matchFigures").isNull()).isTrue();
            assertThat(server.get("/api/income-sources", cookie).data().get(0).get("match").isNull()).isTrue();
        }

        @Test
        void explainsWhatIsWrongWithAMatch() throws Exception {
            assertError(addSalary(15000, "{\"toAccountId\":%d,\"mode\":\"fixed\",\"fixedCents\":5000}".formatted(checking)),
                    "Please choose the investment account the employer match is paid into.");
            assertError(addSalary(15000, "{\"mode\":\"fixed\",\"fixedCents\":5000}"),
                    "Please choose the investment account the employer match is paid into.");
            assertError(addSalary(15000, "{\"toAccountId\":%d,\"mode\":\"sometimes\"}".formatted(retirement)),
                    "Please choose how the match is worked out from the list.");
            assertError(addSalary(15000, "{\"toAccountId\":%d,\"mode\":\"fixed\"}".formatted(retirement)),
                    "The match amount is missing. Please enter an amount.");
            assertError(addSalary(15000, "{\"toAccountId\":%d,\"mode\":\"formula\",\"capBpOfGross\":600}".formatted(retirement)),
                    "Please enter how much of your contribution is matched, between 0% and 1,000%.");
            assertError(addSalary(15000, "{\"toAccountId\":%d,\"mode\":\"formula\",\"matchBp\":5000,\"capBpOfGross\":10001}".formatted(retirement)),
                    "Please enter how much of your pay is matched up to, between 0% and 100%.");
            // Nothing was added along the way.
            assertThat(server.get("/api/income-sources", cookie).data()).isEmpty();
        }

        /** The salary with no gross amount, putting $216 of each paycheck into the 401(k). */
        private String salaryWithoutGross(String match) {
            return """
                    {"name":"Brightwater Health","depositAccountId":%d,"netCents":248620,"primary":true,
                     "schedule":{"frequency":"semi_monthly","day1":1,"day2":15,"startDate":"2026-01-01","weekendRule":"none"},
                     "deductions":[{"name":"401(k)","amountCents":21600,"toAccountId":%d}],
                     "match":%s}""".formatted(checking, retirement, match);
        }

        @Test
        void withoutGrossPayAMatchWorkedOutFromPayAsksForItOrForConfirmation() throws Exception {
            Reply reply = server.post("/api/income-sources", salaryWithoutGross(halfUpToSixPercent()), cookie);

            assertError(reply, "Without the gross amount of a paycheck, there is no telling whether what you put in is within the share of"
                    + " your pay that is matched. Please fill in the gross amount, or confirm that it is.");
            assertThat(server.get("/api/income-sources", cookie).data()).isEmpty();
        }

        @Test
        void withoutGrossPayAConfirmedContributionIsMatchedInFull() throws Exception {
            String dollarForDollar = "{\"toAccountId\":%d,\"mode\":\"formula\",\"matchBp\":10000,\"capBpOfGross\":600,\"contributionWithinCap\":true}"
                    .formatted(retirement);

            Reply reply = server.post("/api/income-sources", salaryWithoutGross(dollarForDollar), cookie);

            assertThat(reply.status()).isEqualTo(201);
            assertThat(reply.data().get("match").get("contributionWithinCap").asBoolean()).isTrue();
            // 100% of the $216 put in; how much more could be earned is not known without gross pay.
            JsonNode figures = reply.data().get("matchFigures");
            assertThat(figures.get("perPaycheckCents").asLong()).isEqualTo(21600);
            assertThat(figures.get("contributeMoreCents").asLong()).isZero();
            // It arrives on payday like any other match.
            JsonNode paycheck = server.get("/api/paychecks?from=2026-10-01&to=2026-10-01", cookie).data().get(0);
            assertThat(paycheck.get("contributions").findValuesAsText("kind")).containsExactly("contribution", "employer_match");
            assertThat(paycheck.get("contributions").get(1).get("amountCents").asLong()).isEqualTo(21600);
        }

        @Test
        void onceGrossPayIsKnownTheConfirmationIsNoLongerNeededOrKept() throws Exception {
            String confirmed = "{\"toAccountId\":%d,\"mode\":\"formula\",\"matchBp\":5000,\"capBpOfGross\":600,\"contributionWithinCap\":true}"
                    .formatted(retirement);

            Reply reply = addSalary(30000, confirmed);

            // $300 is over 6% of $3,600, and the gross amount says so whatever was ticked.
            assertThat(reply.data().get("match").get("contributionWithinCap").asBoolean()).isFalse();
            assertThat(reply.data().get("matchFigures").get("perPaycheckCents").asLong()).isEqualTo(10800);
        }

        @Test
        void aContributionCanBeAShareOfGrossPay() throws Exception {
            Reply reply = server.post("/api/income-sources", """
                    {"name":"Brightwater Health","depositAccountId":%d,"grossCents":360000,"netCents":248620,"primary":true,
                     "schedule":{"frequency":"semi_monthly","day1":1,"day2":15,"startDate":"2026-01-01","weekendRule":"none"},
                     "deductions":[{"name":"401(k)","percentBp":600,"toAccountId":%d}],
                     "match":%s}""".formatted(checking, retirement, halfUpToSixPercent()), cookie);

            assertThat(reply.status()).isEqualTo(201);
            JsonNode deduction = reply.data().get("deductions").get(0);
            assertThat(deduction.get("percentBp").asInt()).isEqualTo(600);
            // 6% of $3,600, and half of it matched.
            assertThat(deduction.get("amountCents").asLong()).isEqualTo(21600);
            assertThat(reply.data().get("matchFigures").get("perPaycheckCents").asLong()).isEqualTo(10800);

            // A raise moves the contribution with it: the share is kept, the dollars are worked out again.
            long id = reply.data().get("id").asLong();
            Reply raised = server.put("/api/income-sources/" + id, """
                    {"name":"Brightwater Health","depositAccountId":%d,"grossCents":400000,"netCents":270000,"primary":true,
                     "schedule":{"frequency":"semi_monthly","day1":1,"day2":15,"startDate":"2026-01-01","weekendRule":"none"},
                     "deductions":[{"name":"401(k)","percentBp":600,"toAccountId":%d}]}""".formatted(checking, retirement), cookie);
            assertThat(raised.data().get("deductions").get(0).get("amountCents").asLong()).isEqualTo(24000);
        }

        @Test
        void aContributionGivenAsAShareNeedsGrossPayAndASensibleShare() throws Exception {
            String body = """
                    {"name":"Brightwater Health","depositAccountId":%d,%s"netCents":248620,"primary":true,
                     "schedule":{"frequency":"semi_monthly","day1":1,"day2":15,"startDate":"2026-01-01","weekendRule":"none"},
                     "deductions":[{"name":"401(k)","percentBp":%d,"toAccountId":%d}]}""";

            assertError(server.post("/api/income-sources", body.formatted(checking, "", 600, retirement), cookie),
                    "A deduction given as a percent of your pay needs the gross amount of a paycheck. Please fill that in as well.");
            assertError(server.post("/api/income-sources", body.formatted(checking, "\"grossCents\":360000,", 10001, retirement), cookie),
                    "Please enter each deduction's share of your pay between 0% and 100%.");
            assertThat(server.get("/api/income-sources", cookie).data()).isEmpty();
        }

        @Test
        void theAccountAMatchIsPaidIntoCannotBeDeletedOrStopBeingAnInvestment() throws Exception {
            // No deduction goes there, so the match is the only thing using the account.
            addSalary(0, "{\"toAccountId\":%d,\"mode\":\"fixed\",\"fixedCents\":5000}".formatted(brokerage));

            assertThat(server.delete("/api/accounts/" + brokerage, cookie).status()).isEqualTo(409);
            Reply changed = server.put("/api/accounts/" + brokerage,
                    "{\"name\":\"Brokerage\",\"type\":\"savings\",\"color\":\"#0f766e\"}", cookie);
            assertThat(changed.status()).isEqualTo(409);
            assertThat(changed.error()).startsWith("An employer match is paid into this account");
        }
    }

    @Nested
    class WhatAPaycheckPutsIn {
        @BeforeEach
        void addTheSalaryAndABalance() throws Exception {
            addSalary(15000, halfUpToSixPercent());
            enterBalance(checking, "2026-10-01", 284512);
            enterBalance(retirement, "2026-10-01", 4000000);
        }

        @Test
        void eachPaycheckListsWhatGoesStraightToOtherAccounts() throws Exception {
            JsonNode contributions = server.get("/api/paychecks?from=2026-10-01&to=2026-10-01", cookie).data().get(0).get("contributions");

            // The health plan names no account, so it is not one of them.
            assertThat(contributions).hasSize(2);
            assertThat(contributions.get(0).get("label").asText()).isEqualTo("401(k)");
            assertThat(contributions.get(0).get("kind").asText()).isEqualTo("contribution");
            assertThat(contributions.get(0).get("toAccountId").asLong()).isEqualTo(retirement);
            assertThat(contributions.get(0).get("amountCents").asLong()).isEqualTo(15000);
            assertThat(contributions.get(1).get("label").asText()).isEqualTo("Employer match");
            assertThat(contributions.get(1).get("kind").asText()).isEqualTo("employer_match");
            assertThat(contributions.get(1).get("amountCents").asLong()).isEqualTo(7500);
        }

        @Test
        void theContributionAndTheMatchArriveInTheInvestmentOnPayday() throws Exception {
            JsonNode period = period("2026-10-01");

            JsonNode invested = account(period, retirement);
            assertThat(invested.get("inCents").asLong()).isEqualTo(22500);
            assertThat(invested.get("endingCents").asLong()).isEqualTo(4022500);
            // They never pass through checking: only take-home pay arrives there.
            assertThat(account(period, checking).get("inCents").asLong()).isEqualTo(248620);
        }

        @Test
        void theAccountsTransactionsNameThem() throws Exception {
            JsonNode lines = server.get("/api/accounts/" + retirement + "/transactions?from=2026-10-01&to=2026-10-01", cookie).data();

            assertThat(StreamSupport.stream(lines.spliterator(), false)
                    .map(line -> line.get("label").asText() + " | " + line.get("kind").asText() + " | " + line.get("amountCents").asLong()))
                    .containsExactlyInAnyOrder(
                            "401(k) · Brightwater Health | contribution | 15000",
                            "Employer match · Brightwater Health | employer_match | 7500",
                            "Balance you entered | balance | 4000000");
        }

        @Test
        void aTransferIntoAnInvestmentCountsToo() throws Exception {
            enterBalance(brokerage, "2026-10-01", 1000000);
            created("/api/items", """
                    {"name":"Invest monthly","kind":"transfer","amountCents":20000,"payFromAccountId":%d,"payToAccountId":%d,"autopay":true,
                     "schedule":{"frequency":"monthly","day1":5,"startDate":"2026-01-01","weekendRule":"none"}}"""
                    .formatted(checking, brokerage));

            assertThat(account(period("2026-10-01"), brokerage).get("endingCents").asLong()).isEqualTo(1020000);
        }
    }

    @Nested
    class AssumedGrowth {
        @BeforeEach
        void addTheSalaryAndABalance() throws Exception {
            addSalary(15000, halfUpToSixPercent());
            enterBalance(retirement, "2026-10-01", 4000000);
        }

        private Reply assume(int rateBp) throws Exception {
            return server.post("/api/accounts/" + retirement + "/rates",
                    "{\"rateBp\":%d,\"effectiveDate\":\"2026-01-01\"}".formatted(rateBp), cookie);
        }

        @Test
        void anInvestmentsRateIsAGrowthRate() throws Exception {
            Reply reply = assume(700);

            assertThat(reply.status()).isEqualTo(201);
            assertThat(reply.data().get("rates").get(0).get("kind").asText()).isEqualTo("growth");
        }

        @Test
        void withoutARateNothingIsAssumed() throws Exception {
            assertThat(period("2026-10-15").get("interest")).isEmpty();
        }

        @Test
        void itGrowsALittleEveryDaySoEveryPayPeriodHasItsShare() throws Exception {
            assume(700);

            JsonNode period = period("2026-10-15");

            // The period's seventeen days are one line, dated the last of them: about 7.50 a day on a little over 40,300.
            assertThat(period.get("interest")).hasSize(1);
            JsonNode growth = period.get("interest").get(0);
            assertThat(growth.get("accountId").asLong()).isEqualTo(retirement);
            assertThat(growth.get("date").asText()).isEqualTo("2026-10-31");
            assertThat(growth.get("amountCents").asLong()).isEqualTo(12794);
            assertThat(growth.get("rateBp").asInt()).isEqualTo(700);
            JsonNode held = account(period, retirement);
            // One payday's 225.00 and the growth are all that came in.
            assertThat(held.get("inCents").asLong()).isEqualTo(22500 + 12794);
            assertThat(held.get("endingCents").asLong()).isEqualTo(held.get("startingCents").asLong() + 22500 + 12794);
            // The period before had its share too, and so does the one after: none is left without.
            assertThat(period("2026-10-01").get("interest")).hasSize(1);
            assertThat(period("2026-11-01").get("interest")).hasSize(1);
        }

        @Test
        void theAccountsListShowsAMonthOfGrowthAsOneLine() throws Exception {
            assume(700);

            JsonNode lines = server.get("/api/accounts/" + retirement + "/transactions?from=2026-10-02&to=2026-11-30", cookie).data();

            List<JsonNode> growth = new ArrayList<>();
            lines.forEach(line -> {
                if (line.get("kind").asText().equals("growth")) {
                    growth.add(line);
                }
            });
            assertThat(growth).extracting(line -> line.get("date").asText()).containsExactlyInAnyOrder("2026-10-31", "2026-11-30");
        }

        @Test
        void theTransactionsCallItAssumedGrowthNotInterest() throws Exception {
            assume(700);

            JsonNode lines = server.get("/api/accounts/" + retirement + "/transactions?from=2026-10-30&to=2026-10-30", cookie).data();

            assertThat(lines).hasSize(1);
            assertThat(lines.get(0).get("label").asText()).isEqualTo("Assumed growth");
            assertThat(lines.get(0).get("kind").asText()).isEqualTo("growth");
        }
    }

    @Nested
    class GainOrLoss {
        @BeforeEach
        void addTheSalary() throws Exception {
            addSalary(15000, halfUpToSixPercent());
        }

        private JsonNode performance(long accountId) throws Exception {
            Reply reply = server.get("/api/accounts/" + accountId + "/performance", cookie);
            assertThat(reply.status()).isEqualTo(200);
            return reply.data();
        }

        @Test
        void requiresASessionAndAnAccountThatExists() throws Exception {
            assertThat(server.get("/api/accounts/" + retirement + "/performance", null).status()).isEqualTo(401);
            assertThat(server.get("/api/accounts/999/performance", cookie).status()).isEqualTo(404);
        }

        @Test
        void theChangeBetweenTwoBalancesIsSplitIntoDepositsAndGrowth() throws Exception {
            enterBalance(retirement, "2026-09-01", 3900000);
            enterBalance(retirement, "2026-10-01", 4000000);

            JsonNode balances = performance(retirement).get("balances");

            // Newest first. Up 1,000.00 over September, of which two paydays put in 450.00.
            JsonNode october = balances.get(0);
            assertThat(october.get("date").asText()).isEqualTo("2026-10-01");
            assertThat(october.get("changeCents").asLong()).isEqualTo(100000);
            assertThat(october.get("depositsCents").asLong()).isEqualTo(45000);
            assertThat(october.get("growthCents").asLong()).isEqualTo(55000);
            // The first one has nothing before it to be set against.
            assertThat(balances.get(1).get("changeCents").isNull()).isTrue();
            assertThat(balances.get(1).get("growthCents").isNull()).isTrue();
        }

        @Test
        void aBalanceThatRoseByLessThanWasPutInIsALoss() throws Exception {
            enterBalance(retirement, "2026-09-01", 3900000);
            enterBalance(retirement, "2026-10-01", 3920000);

            assertThat(performance(retirement).get("balances").get(0).get("growthCents").asLong()).isEqualTo(-25000);
        }

        @Test
        void assumedGrowthIsNeverCountedAsADeposit() throws Exception {
            server.post("/api/accounts/" + retirement + "/rates", "{\"rateBp\":700,\"effectiveDate\":\"2026-01-01\"}", cookie);
            enterBalance(retirement, "2026-09-01", 3900000);
            enterBalance(retirement, "2026-10-01", 4000000);

            // September's assumed growth was projected, but only what was really put in is a deposit.
            assertThat(performance(retirement).get("balances").get(0).get("depositsCents").asLong()).isEqualTo(45000);
        }

        @Test
        void thisYearRunsFromTheFirstBalanceWhenThatCameAfterNewYear() throws Exception {
            enterBalance(retirement, "2026-09-01", 3900000);
            enterBalance(retirement, "2026-10-01", 4000000);

            JsonNode thisYear = performance(retirement).get("thisYear");

            assertThat(thisYear.get("since").asText()).isEqualTo("2026-09-01");
            assertThat(thisYear.get("startCents").asLong()).isEqualTo(3900000);
            // Today's payday has arrived on top of the balance entered this morning.
            assertThat(thisYear.get("nowCents").asLong()).isEqualTo(4022500);
            assertThat(thisYear.get("changeCents").asLong()).isEqualTo(122500);
            assertThat(thisYear.get("depositsCents").asLong()).isEqualTo(67500);
            assertThat(thisYear.get("growthCents").asLong()).isEqualTo(55000);
        }

        @Test
        void thisYearRunsFromNewYearForABalanceEnteredBefore() throws Exception {
            enterBalance(checking, "2025-12-15", 100000);

            JsonNode thisYear = performance(checking).get("thisYear");

            assertThat(thisYear.get("since").asText()).isEqualTo("2026-01-01");
            // Nothing was set up to move it in the last half of December.
            assertThat(thisYear.get("startCents").asLong()).isEqualTo(100000);
            // Nineteen paydays from January 1 to October 1.
            assertThat(thisYear.get("depositsCents").asLong()).isEqualTo(19 * 248620L);
            assertThat(thisYear.get("growthCents").asLong()).isZero();
        }

        @Test
        void actualGrowthRunsFromTheFirstBalanceToTheLatestBesideWhatWasAssumed() throws Exception {
            server.post("/api/accounts/" + retirement + "/rates", "{\"rateBp\":700,\"effectiveDate\":\"2026-01-01\"}", cookie);
            enterBalance(retirement, "2026-04-01", 3700000);
            enterBalance(retirement, "2026-07-01", 3850000);
            enterBalance(retirement, "2026-10-01", 4000000);

            JsonNode performance = performance(retirement);
            JsonNode actual = performance.get("actualGrowth");

            assertThat(actual.get("from").asText()).isEqualTo("2026-04-01");
            assertThat(actual.get("to").asText()).isEqualTo("2026-10-01");
            // The same as the growth between each pair of balances, added up.
            JsonNode balances = performance.get("balances");
            assertThat(actual.get("growthCents").asLong())
                    .isEqualTo(balances.get(0).get("growthCents").asLong() + balances.get(1).get("growthCents").asLong());
            // What was assumed is the assumed growth the account's own list shows over those days.
            long listed = 0;
            for (JsonNode line : server.get("/api/accounts/" + retirement + "/transactions?from=2026-04-01&to=2026-09-30", cookie).data()) {
                if (line.get("kind").asText().equals("growth")) {
                    listed += line.get("amountCents").asLong();
                }
            }
            assertThat(listed).isPositive();
            assertThat(actual.get("assumedCents").asLong()).isEqualTo(listed);
            // Six months apart is long enough to say it as a rate a year.
            assertThat(actual.get("yearlyRateBp").isNull()).isFalse();
        }

        @Test
        void withoutAnAssumedRateNothingWasAssumed() throws Exception {
            enterBalance(retirement, "2026-04-01", 3700000);
            enterBalance(retirement, "2026-10-01", 4000000);

            assertThat(performance(retirement).get("actualGrowth").get("assumedCents").asLong()).isZero();
        }

        @Test
        void balancesUnderThreeMonthsApartGiveGrowthButNoRate() throws Exception {
            enterBalance(retirement, "2026-09-01", 3900000);
            enterBalance(retirement, "2026-10-01", 4000000);

            JsonNode actual = performance(retirement).get("actualGrowth");

            assertThat(actual.get("growthCents").asLong()).isEqualTo(55000);
            assertThat(actual.get("yearlyRateBp").isNull()).isTrue();
        }

        @Test
        void actualGrowthNeedsTwoBalancesAndAnInvestment() throws Exception {
            enterBalance(retirement, "2026-04-01", 3700000);
            enterBalance(checking, "2026-04-01", 100000);
            enterBalance(checking, "2026-09-01", 200000);

            assertThat(performance(retirement).get("actualGrowth").isNull()).isTrue();
            assertThat(performance(checking).get("actualGrowth").isNull()).isTrue();
        }
    }
}
