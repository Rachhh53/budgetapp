package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Retirement planning (spec §4.12, §5.11). "Today" is 2026-10-01; born Oct 1, 1990, so exactly 36.
 * A 401(k) holds $100,000 at the start of today and gets $500 on the 1st of every month from checking:
 * today's too, so $100,500 by tonight, then $6,000 a year. The scenarios here grow nothing unless a
 * test says so, so the sums are easy to check.
 */
class RetirementApiTest extends ApiTestBase {

    long checking;
    long k401;

    @BeforeEach
    void addAccounts() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        createSalary(checking);
        k401 = createAccount("Fidelity 401(k)", "investment");
        enterBalance(k401, "2026-10-01", 10_000_000);
        created("/api/items", """
                {"name":"Into the 401(k)","kind":"transfer","amountCents":50000,"payFromAccountId":%d,"payToAccountId":%d,"autopay":true,
                 "schedule":{"frequency":"monthly","day1":1,"startDate":"2026-01-01","weekendRule":"none"}}""".formatted(checking, k401));
    }

    private JsonNode view() throws Exception {
        Reply reply = server.get("/api/retirement", cookie);
        assertThat(reply.status()).isEqualTo(200);
        return reply.data();
    }

    private void ready(String taxType) throws Exception {
        assertThat(server.put("/api/retirement/birth-date", "{\"birthDate\":\"1990-10-01\"}", cookie).status()).isEqualTo(200);
        assertThat(server.put("/api/retirement/accounts/" + k401, "{\"taxType\":\"%s\"}".formatted(taxType), cookie).status()).isEqualTo(200);
    }

    /** $40,000 a year at 4%, prices flat, nothing growing, no tax unless said, retiring at 66. */
    private static String scenario(String name, int taxBp, boolean compared) {
        return """
                {"name":"%s","salaryCents":4000000,"inflationBp":0,"withdrawalBp":400,"taxBp":%d,"healthInflationBp":0,
                 "growthBp":0,"growthLowBp":0,"growthHighBp":0,
                 "extraInto":"taxable","retireAge":66,"compared":%s}""".formatted(name, taxBp, compared);
    }

    private JsonNode onlyScenario() throws Exception {
        JsonNode scenarios = view().get("scenarios");
        assertThat(scenarios).hasSize(1);
        return scenarios.get(0);
    }

    @Test
    void asksForTheBirthDateFirstAndListsTheAccountsThatCouldCount() throws Exception {
        JsonNode view = view();

        assertThat(view.get("missing").asText()).isEqualTo("birth_date");
        JsonNode accounts = view.get("accounts");
        // Checking can't be counted, so it isn't offered.
        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0).get("name").asText()).isEqualTo("Fidelity 401(k)");
        assertThat(accounts.get(0).get("taxType").isNull()).isTrue();
        assertThat(accounts.get(0).get("balanceCents").asLong()).isEqualTo(10_050_000);
        assertThat(accounts.get(0).get("yearlyInCents").asLong()).isEqualTo(600_000);
        assertThat(view.get("paychecksAYear").asInt()).isEqualTo(24);
    }

    @Test
    void thenForAnAccountToCount() throws Exception {
        server.put("/api/retirement/birth-date", "{\"birthDate\":\"1990-10-01\"}", cookie);
        created("/api/retirement/scenarios", scenario("Base", 0, true));

        JsonNode view = view();
        assertThat(view.get("birthDate").asText()).isEqualTo("1990-10-01");
        assertThat(view.get("missing").asText()).isEqualTo("accounts");
        assertThat(view.get("scenarios").get(0).get("outcome").isNull()).isTrue();
    }

    @Test
    void worksAScenarioOutAgainstTheAccounts() throws Exception {
        ready("roth");
        created("/api/retirement/scenarios", scenario("Base", 0, true));

        JsonNode scenario = onlyScenario();
        assertThat(scenario.get("name").asText()).isEqualTo("Base");
        assertThat(scenario.get("compared").asBoolean()).isTrue();
        JsonNode outcome = scenario.get("outcome");
        assertThat(outcome.get("ageNow").asInt()).isEqualTo(36);
        // $100,000 + $6,000 a year never reaches $1,000,000 by 100.
        assertThat(outcome.get("earliestAge").isNull()).isTrue();
        assertThat(outcome.get("byAge")).hasSize(65);
        JsonNode target = outcome.get("target");
        assertThat(target.get("age").asInt()).isEqualTo(66);
        assertThat(target.get("neededCents").asLong()).isEqualTo(100_000_000);
        assertThat(target.get("projectedCents").asLong()).isEqualTo(28_050_000);
        assertThat(target.get("shortfallCents").asLong()).isEqualTo(71_950_000);
        assertThat(target.get("enough").asBoolean()).isFalse();
        long monthly = target.get("extraMonthlyCents").asLong();
        assertThat(monthly).isPositive();
        // 24 paychecks a year: half a month's worth each.
        assertThat(target.get("extraPerPaycheckCents").asLong()).isEqualTo(Math.round(monthly / 2.0));
        assertThat(target.get("early").isNull()).isTrue();
    }

    @Test
    void traditionalMoneyCountsAfterTheScenariosTax() throws Exception {
        ready("traditional");
        created("/api/retirement/scenarios", scenario("Base", 1500, false));

        assertThat(onlyScenario().get("outcome").get("target").get("projectedCents").asLong()).isEqualTo(23_842_500);
    }

    @Test
    void anAccountCanStopCounting() throws Exception {
        ready("taxable");
        assertThat(server.put("/api/retirement/accounts/" + k401, "{\"taxType\":null}", cookie).status()).isEqualTo(200);

        JsonNode view = view();
        assertThat(view.get("missing").asText()).isEqualTo("accounts");
        assertThat(view.get("accounts").get(0).get("taxType").isNull()).isTrue();
    }

    @Test
    void changingAScenarioReplacesItsIncomeAndOneOffs() throws Exception {
        ready("roth");
        long id = created("/api/retirement/scenarios", """
                {"name":"Base","salaryCents":4000000,"extraInto":"roth",
                 "incomes":[{"name":"Social Security","yearlyCents":2000000,"startAge":67}],
                 "oneOffs":[{"name":"Sell the house","amountCents":20000000,"age":60}]}""");
        JsonNode first = onlyScenario();
        // Left out, the rates take their defaults.
        assertThat(first.get("inflationBp").asInt()).isEqualTo(300);
        assertThat(first.get("withdrawalBp").asInt()).isEqualTo(400);
        assertThat(first.get("taxBp").asInt()).isEqualTo(1500);
        assertThat(first.get("healthInflationBp").asInt()).isEqualTo(500);
        assertThat(first.get("incomes").get(0).get("name").asText()).isEqualTo("Social Security");
        assertThat(first.get("oneOffs").get(0).get("amountCents").asLong()).isEqualTo(20_000_000);

        Reply changed = server.put("/api/retirement/scenarios/" + id, """
                {"name":"Retire at 60","salaryCents":5000000,"extraInto":"roth","retireAge":60,
                 "oneOffs":[{"name":"New roof","amountCents":-2500000,"age":62}]}""", cookie);

        assertThat(changed.status()).isEqualTo(200);
        JsonNode after = onlyScenario();
        assertThat(after.get("name").asText()).isEqualTo("Retire at 60");
        assertThat(after.get("incomes")).isEmpty();
        assertThat(after.get("oneOffs")).hasSize(1);
        assertThat(after.get("oneOffs").get(0).get("amountCents").asLong()).isEqualTo(-2_500_000);
    }

    @Test
    void growthStartsAtSixPercentWithFourAndEightBesideIt() throws Exception {
        ready("roth");
        created("/api/retirement/scenarios", """
                {"name":"Base","salaryCents":4000000,"inflationBp":0,"extraInto":"roth"}""");

        JsonNode scenario = onlyScenario();
        assertThat(scenario.get("growthBp").asInt()).isEqualTo(600);
        assertThat(scenario.get("growthLowBp").asInt()).isEqualTo(400);
        assertThat(scenario.get("growthHighBp").asInt()).isEqualTo(800);
        assertThat(scenario.get("raiseBp").asInt()).isZero();
        JsonNode outcome = scenario.get("outcome");
        // 4%, 6% and 8%: each faster growth is enough sooner.
        assertThat(outcome.get("earliestLowAge").asInt()).isGreaterThan(outcome.get("earliestAge").asInt());
        assertThat(outcome.get("earliestHighAge").asInt()).isLessThan(outcome.get("earliestAge").asInt());
        JsonNode at46 = outcome.get("byAge").get(10);
        assertThat(at46.get("age").asInt()).isEqualTo(46);
        assertThat(at46.get("projectedLowCents").asLong()).isLessThan(at46.get("projectedCents").asLong());
        assertThat(at46.get("projectedHighCents").asLong()).isGreaterThan(at46.get("projectedCents").asLong());
        // With prices flat, today's dollars and that year's are the same.
        assertThat(at46.get("projectedThenCents").asLong()).isEqualTo(at46.get("projectedCents").asLong());
    }

    @Test
    void aRaiseAddsMoreEachYear() throws Exception {
        ready("roth");
        created("/api/retirement/scenarios", scenario("Raise", 0, false).replace("\"growthBp\":0,", "\"growthBp\":0,\"raiseBp\":1000,"));

        JsonNode scenario = onlyScenario();
        assertThat(scenario.get("raiseBp").asInt()).isEqualTo(1000);
        // $100,500, then $6,000, $6,600 and $7,260 in the three years after.
        JsonNode byAge = scenario.get("outcome").get("byAge");
        assertThat(byAge.get(3).get("age").asInt()).isEqualTo(39);
        assertThat(byAge.get(3).get("projectedCents").asLong()).isEqualTo(10_050_000 + 600_000 + 660_000 + 726_000);
    }

    @Test
    void retiringBeforeSixtySaysHowManyYearsTaxableMoneyCovers() throws Exception {
        ready("traditional");
        created("/api/retirement/scenarios", scenario("Early", 0, false).replace("\"retireAge\":66", "\"retireAge\":55"));

        JsonNode target = onlyScenario().get("outcome").get("target");
        assertThat(target.get("early").get("years").asInt()).isEqualTo(5);
        assertThat(target.get("early").get("covered").asInt()).isZero();
        assertThat(target.get("extraTaxable").asBoolean()).isTrue();
    }

    @Test
    void deletesAScenario() throws Exception {
        long id = created("/api/retirement/scenarios", scenario("Base", 0, false));

        assertThat(server.delete("/api/retirement/scenarios/" + id, cookie).status()).isEqualTo(200);
        assertThat(view().get("scenarios")).isEmpty();
        assertThat(server.delete("/api/retirement/scenarios/" + id, cookie).status()).isEqualTo(404);
        assertThat(server.put("/api/retirement/scenarios/" + id, scenario("Base", 0, false), cookie).status()).isEqualTo(404);
    }

    @Test
    void keepsThreeSideBySide() throws Exception {
        for (int i = 1; i <= 3; i++) {
            created("/api/retirement/scenarios", scenario("Plan " + i, 0, true));
        }

        Reply fourth = server.post("/api/retirement/scenarios", scenario("Plan 4", 0, true), cookie);

        assertThat(fourth.status()).isEqualTo(409);
        assertThat(fourth.error()).isEqualTo("Three scenarios are already side by side. Please take one out of the comparison first.");
        // One already compared can be changed and stay compared.
        long first = view().get("scenarios").get(0).get("id").asLong();
        assertThat(server.put("/api/retirement/scenarios/" + first, scenario("Plan 1b", 0, true), cookie).status()).isEqualTo(200);
    }

    @Test
    void refusesWhatCannotBeRight() throws Exception {
        assertError(server.post("/api/retirement/scenarios", "{\"name\":\"Base\",\"salaryCents\":0}", cookie),
                "Please enter the salary you'd like a year in retirement, more than $0.00.");
        assertError(server.post("/api/retirement/scenarios", "{\"salaryCents\":100}", cookie), "Please add a name for this scenario.");
        assertError(server.post("/api/retirement/scenarios", "{\"name\":\"Base\",\"salaryCents\":100,\"withdrawalBp\":50}", cookie),
                "Please enter a withdrawal rate between 1% and 10% a year.");
        assertError(server.post("/api/retirement/scenarios", "{\"name\":\"Base\",\"salaryCents\":100,\"retireAge\":101}", cookie),
                "Please enter an age to retire between 18 and 100.");
        assertError(server.post("/api/retirement/scenarios", "{\"name\":\"Base\",\"salaryCents\":100,\"extraInto\":\"gold\"}", cookie),
                "Please choose where the extra goes from the list.");
        assertError(server.post("/api/retirement/scenarios",
                        "{\"name\":\"Base\",\"salaryCents\":100,\"incomes\":[{\"name\":\"Pension\",\"yearlyCents\":100,\"startAge\":12}]}", cookie),
                "Please enter the age Pension starts, between 18 and 100.");
        assertError(server.post("/api/retirement/scenarios",
                        "{\"name\":\"Base\",\"salaryCents\":100,\"oneOffs\":[{\"name\":\"Roof\",\"amountCents\":0,\"age\":60}]}", cookie),
                "Please enter an amount for Roof: money in, or money out with a minus sign.");
        assertError(server.post("/api/retirement/scenarios", "{\"name\":\"Base\",\"salaryCents\":100,\"growthBp\":500,\"growthLowBp\":600}", cookie),
                "Please enter a lower growth at or below the expected 5.00%.");
        assertError(server.post("/api/retirement/scenarios", "{\"name\":\"Base\",\"salaryCents\":100,\"growthHighBp\":550}", cookie),
                "Please enter a higher growth at or above the expected 6.00%.");
        assertError(server.post("/api/retirement/scenarios", "{\"name\":\"Base\",\"salaryCents\":100,\"growthBp\":-1}", cookie),
                "Please enter the expected growth between 0% and 100% a year.");
        assertError(server.post("/api/retirement/scenarios", "{\"name\":\"Base\",\"salaryCents\":100,\"raiseBp\":2500}", cookie),
                "Please enter a raise between 0% and 20% a year.");
        assertError(server.put("/api/retirement/birth-date", "{\"birthDate\":\"2020-01-01\"}", cookie),
                "Please enter a birth date between 100 and 18 years ago.");
        assertError(server.put("/api/retirement/accounts/" + checking, "{\"taxType\":\"roth\"}", cookie),
                "Only investment and savings accounts can be counted for retirement.");
        assertError(server.put("/api/retirement/accounts/" + k401, "{\"taxType\":\"hsa\"}", cookie),
                "Please choose how its money is taxed from the list.");
        assertThat(server.put("/api/retirement/accounts/999", "{\"taxType\":\"roth\"}", cookie).status()).isEqualTo(404);
    }

    private static void assertError(Reply reply, String message) {
        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.error()).isEqualTo(message);
    }

    @Test
    void everyPartNeedsASession() throws Exception {
        assertThat(server.get("/api/retirement", null).status()).isEqualTo(401);
        assertThat(server.put("/api/retirement/birth-date", "{\"birthDate\":\"1990-10-01\"}", null).status()).isEqualTo(401);
        assertThat(server.put("/api/retirement/accounts/" + k401, "{\"taxType\":\"roth\"}", null).status()).isEqualTo(401);
        assertThat(server.post("/api/retirement/scenarios", scenario("Base", 0, false), null).status()).isEqualTo(401);
        assertThat(server.put("/api/retirement/scenarios/1", scenario("Base", 0, false), null).status()).isEqualTo(401);
        assertThat(server.delete("/api/retirement/scenarios/1", null).status()).isEqualTo(401);
    }
}
