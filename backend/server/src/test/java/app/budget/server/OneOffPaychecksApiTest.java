package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * One-off paychecks over real HTTP (spec §4.3): a bonus from an income source, with a paycheck's
 * figures, paid once on its own day. "Today" is Thursday 2026-10-01; Brightwater Health pays
 * $2,486.20 on the 1st and 15th from $3,600.00 gross, puts $150.00 into the 401(k), and matches
 * 50% of that up to 6% of gross pay.
 */
class OneOffPaychecksApiTest extends ApiTestBase {

    long checking;
    long retirement;
    long hsa;
    long salary;

    @BeforeEach
    void addTheSalary() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        retirement = createAccount("Brightwater 401(k)", "investment");
        hsa = createAccount("HSA", "savings");
        salary = created("/api/income-sources", """
                {"name":"Brightwater Health","depositAccountId":%d,"grossCents":360000,"netCents":248620,"primary":true,
                 "schedule":{"frequency":"semi_monthly","day1":1,"day2":15,"startDate":"2026-01-01","weekendRule":"none"},
                 "deductions":[{"name":"401(k)","amountCents":15000,"toAccountId":%d}],
                 "match":{"toAccountId":%d,"mode":"formula","matchBp":5000,"capBpOfGross":600}}"""
                .formatted(checking, retirement, retirement));
    }

    private String path() {
        return "/api/income-sources/" + salary + "/one-off-paychecks";
    }

    /** A $5,000.00 bonus on October 8 with 6% of it into the 401(k) and $3,100.00 take-home; {@code extra} spliced in. */
    private static String bonus(long retirementId, String extra) {
        return """
                {"payDate":"2026-10-08","name":"Q3 bonus","grossCents":500000,"netCents":310000,
                 "deductions":[{"name":"401(k)","percentBp":600,"toAccountId":%d}]%s}"""
                .formatted(retirementId, extra.isEmpty() ? "" : "," + extra);
    }

    private JsonNode added(String body) throws Exception {
        Reply reply = server.post(path(), body, cookie);
        assertThat(reply.status()).as(reply.body().toString()).isEqualTo(201);
        return reply.data();
    }

    private JsonNode paychecks() throws Exception {
        return server.get("/api/paychecks?from=2026-10-01&to=2026-10-31", cookie).data();
    }

    private static JsonNode oneOffIn(JsonNode paychecks) {
        return StreamSupport.stream(paychecks.spliterator(), false)
                .filter(paycheck -> !paycheck.get("oneOffId").isNull())
                .findFirst()
                .orElseThrow();
    }

    @Nested
    class Auth {
        @Test
        void everyRouteRequiresASession() throws Exception {
            long id = added(bonus(retirement, "")).get("id").asLong();

            assertThat(server.get(path(), null).status()).isEqualTo(401);
            assertThat(server.post(path(), bonus(retirement, ""), null).status()).isEqualTo(401);
            assertThat(server.put(path() + "/" + id, bonus(retirement, ""), null).status()).isEqualTo(401);
            assertThat(server.delete(path() + "/" + id, null).status()).isEqualTo(401);
        }
    }

    @Nested
    class Adding {
        @Test
        void aBonusIsAPaycheckOfItsOwnOnItsDay() throws Exception {
            JsonNode bonus = added(bonus(retirement, ""));

            assertThat(bonus.get("name").asText()).isEqualTo("Q3 bonus");
            assertThat(bonus.get("payDate").asText()).isEqualTo("2026-10-08");
            // 6% of the bonus's own gross, not of the regular paycheck's.
            assertThat(bonus.get("deductions").get(0).get("amountCents").asLong()).isEqualTo(30_000);
            // The source's rule on the bonus: 50% of the $300.00 put in, which is within 6% of $5,000.00.
            assertThat(bonus.get("matched").asBoolean()).isTrue();
            assertThat(bonus.get("matchCents").asLong()).isEqualTo(15_000);

            JsonNode paychecks = paychecks();
            // The two regular paychecks are unchanged, with the bonus between them.
            assertThat(paychecks).extracting(paycheck -> paycheck.get("payDate").asText())
                    .containsExactly("2026-10-01", "2026-10-08", "2026-10-15");
            JsonNode paid = oneOffIn(paychecks);
            assertThat(paid.get("name").asText()).isEqualTo("Q3 bonus");
            assertThat(paid.get("netCents").asLong()).isEqualTo(310_000);
            assertThat(paid.get("estimate").asBoolean()).isFalse();
            assertThat(paid.get("depositAccountId").asLong()).isEqualTo(checking);
            assertThat(paid.get("contributions")).extracting(contribution -> contribution.get("amountCents").asLong())
                    .containsExactly(30_000L, 15_000L);
        }

        @Test
        void itLandsInEveryAccountItPaysInto() throws Exception {
            enterBalance(checking, "2026-10-01", 100_000);
            enterBalance(retirement, "2026-10-01", 4_000_000);
            added(bonus(retirement, ""));

            JsonNode lines = server.get("/api/accounts/" + retirement + "/transactions?from=2026-10-08&to=2026-10-08", cookie).data();
            assertThat(lines).extracting(line -> line.get("label").asText())
                    .contains("401(k) · Q3 bonus · Brightwater Health", "Employer match · Q3 bonus · Brightwater Health");
            JsonNode deposit = server.get("/api/accounts/" + checking + "/transactions?from=2026-10-08&to=2026-10-08", cookie).data();
            assertThat(deposit).hasSize(1);
            assertThat(deposit.get(0).get("label").asText()).isEqualTo("Q3 bonus · Brightwater Health");
            assertThat(deposit.get(0).get("amountCents").asLong()).isEqualTo(310_000);
        }

        @Test
        void itCountsInTheSourcesYear() throws Exception {
            JsonNode before = server.get("/api/income-sources/" + salary + "/this-year", cookie).data();
            added(bonus(retirement, ""));

            JsonNode after = server.get("/api/income-sources/" + salary + "/this-year", cookie).data();
            assertThat(after.get("wholeYearCents").asLong()).isEqualTo(before.get("wholeYearCents").asLong() + 310_000);
            assertThat(after.get("grossWholeYearCents").asLong()).isEqualTo(before.get("grossWholeYearCents").asLong() + 500_000);
            assertThat(after.get("paychecksInYear").asInt()).isEqualTo(before.get("paychecksInYear").asInt() + 1);
            // October 8 has not come yet.
            assertThat(after.get("soFarCents").asLong()).isEqualTo(before.get("soFarCents").asLong());
        }

        @Test
        void itIsInItsPayPeriodsIncome() throws Exception {
            added(bonus(retirement, ""));

            JsonNode period = server.get("/api/pay-periods/2026-10-01", cookie).data();
            assertThat(period.get("paychecks")).extracting(paycheck -> paycheck.get("netCents").asLong()).containsExactly(248_620L, 310_000L);
        }

        @Test
        void theMatchCanBeLeftOff() throws Exception {
            JsonNode bonus = added(bonus(retirement, "\"matched\":false"));

            assertThat(bonus.get("matched").asBoolean()).isFalse();
            assertThat(bonus.get("matchCents").isNull()).isTrue();
            assertThat(oneOffIn(paychecks()).get("contributions")).extracting(contribution -> contribution.get("kind").asText())
                    .containsExactly("contribution");
        }

        @Test
        void takeHomeAloneIsEnough() throws Exception {
            JsonNode bonus = added("{\"payDate\":\"2026-10-08\",\"name\":\"Spot award\",\"netCents\":50000}");

            assertThat(bonus.get("grossCents").isNull()).isTrue();
            assertThat(bonus.get("deductions")).isEmpty();
            // A percentage match needs gross pay to be worked out on, so without one nothing is matched.
            assertThat(bonus.get("matchCents").isNull()).isTrue();
        }

        @Test
        void saysKindlyWhatIsMissing() throws Exception {
            Reply noDate = server.post(path(), "{\"name\":\"Bonus\",\"netCents\":50000}", cookie);
            assertThat(noDate.status()).isEqualTo(400);
            assertThat(noDate.error()).isEqualTo("Please choose the day it was paid.");

            Reply noName = server.post(path(), "{\"payDate\":\"2026-10-08\",\"netCents\":50000}", cookie);
            assertThat(noName.status()).isEqualTo(400);
            assertThat(noName.error()).isEqualTo("Please add a name for this paycheck, like Year-end bonus.");

            Reply percentWithoutGross = server.post(path(), """
                    {"payDate":"2026-10-08","name":"Bonus","netCents":50000,
                     "deductions":[{"name":"401(k)","percentBp":600,"toAccountId":%d}]}""".formatted(retirement), cookie);
            assertThat(percentWithoutGross.status()).isEqualTo(400);
            assertThat(percentWithoutGross.error()).contains("needs the gross amount");

            assertThat(server.post("/api/income-sources/999/one-off-paychecks", bonus(retirement, ""), cookie).status()).isEqualTo(404);
            assertThat(server.get(path(), cookie).data()).isEmpty();
        }
    }

    @Nested
    class Changing {
        @Test
        void listsASourcesOneOffsNewestFirst() throws Exception {
            added(bonus(retirement, ""));
            added("{\"payDate\":\"2026-03-01\",\"name\":\"Spot award\",\"netCents\":50000}");

            assertThat(server.get(path(), cookie).data()).extracting(oneOff -> oneOff.get("name").asText())
                    .containsExactly("Q3 bonus", "Spot award");
        }

        @Test
        void changesEverythingAboutIt() throws Exception {
            long id = added(bonus(retirement, "")).get("id").asLong();

            Reply changed = server.put(path() + "/" + id, """
                    {"payDate":"2026-10-09","name":"Annual bonus","grossCents":600000,"netCents":400000,
                     "deductions":[{"name":"HSA","amountCents":10000,"toAccountId":%d}],"matched":false}""".formatted(hsa), cookie);

            assertThat(changed.status()).isEqualTo(200);
            JsonNode paid = oneOffIn(paychecks());
            assertThat(paid.get("payDate").asText()).isEqualTo("2026-10-09");
            assertThat(paid.get("name").asText()).isEqualTo("Annual bonus");
            assertThat(paid.get("netCents").asLong()).isEqualTo(400_000);
            assertThat(paid.get("contributions")).extracting(contribution -> contribution.get("toAccountId").asLong())
                    .containsExactly(hsa);
        }

        @Test
        void removingItTakesItOutOfEverythingAndUndoBringsItBack() throws Exception {
            long id = added(bonus(retirement, "")).get("id").asLong();

            assertThat(server.delete(path() + "/" + id, cookie).status()).isEqualTo(200);
            assertThat(paychecks()).hasSize(2);

            assertThat(server.post("/api/undo", null, cookie).status()).isEqualTo(200);
            assertThat(paychecks()).hasSize(3);
            assertThat(oneOffIn(paychecks()).get("contributions")).hasSize(2);
        }

        @Test
        void onlyItsOwnSourcesOneOffsAreReached() throws Exception {
            long id = added(bonus(retirement, "")).get("id").asLong();
            long other = created("/api/income-sources", """
                    {"name":"Riverside Bakery","depositAccountId":%d,"netCents":31840,
                     "schedule":{"frequency":"biweekly","startDate":"2026-01-02","weekendRule":"none"}}""".formatted(checking));
            String otherPath = "/api/income-sources/" + other + "/one-off-paychecks/" + id;

            assertThat(server.put(otherPath, bonus(retirement, ""), cookie).status()).isEqualTo(404);
            assertThat(server.delete(otherPath, cookie).status()).isEqualTo(404);
            assertThat(server.delete(path() + "/999", cookie).status()).isEqualTo(404);
        }

        @Test
        void goesWithItsIncomeSource() throws Exception {
            added(bonus(retirement, ""));

            assertThat(server.delete("/api/income-sources/" + salary, cookie).status()).isEqualTo(200);

            assertThat(server.get("/api/paychecks?from=2026-10-01&to=2026-10-31", cookie).data()).isEmpty();
        }

        @Test
        void anAccountItPaysIntoCannotBeDeleted() throws Exception {
            added("""
                    {"payDate":"2026-10-08","name":"Bonus","netCents":50000,
                     "deductions":[{"name":"HSA","amountCents":10000,"toAccountId":%d}]}""".formatted(hsa));

            assertThat(server.delete("/api/accounts/" + hsa, cookie).status()).isEqualTo(409);
        }
    }

    @Nested
    class Reports {
        private JsonNode run(String definition) throws Exception {
            Reply reply = server.post("/api/reports/run", "{\"definition\":" + definition + "}", cookie);
            assertThat(reply.status()).as(reply.body().toString()).isEqualTo(200);
            return reply.data();
        }

        @Test
        void anIncomeReportCountsItsGrossTaxesAndDeductions() throws Exception {
            added(bonus(retirement, ""));

            JsonNode report = run("{\"kind\":\"income\",\"range\":\"this_year\",\"show\":\"table\",\"incomeInclude\":[\"paychecks\",\"match\"]}");

            List<JsonNode> bonus = StreamSupport.stream(report.get("rows").spliterator(), false)
                    .filter(row -> row.get("description").asText().contains("Q3 bonus"))
                    .toList();
            assertThat(bonus).extracting(row -> row.get("kind").asText()).containsExactlyInAnyOrder("paycheck", "match");
            JsonNode paycheck = bonus.stream().filter(row -> row.get("kind").asText().equals("paycheck")).findFirst().orElseThrow();
            assertThat(paycheck.get("description").asText()).isEqualTo("Q3 bonus · Brightwater Health");
            assertThat(paycheck.get("grossCents").asLong()).isEqualTo(500_000);
            assertThat(paycheck.get("deductionsCents").asLong()).isEqualTo(30_000);
            // What is neither take-home nor taken out went to taxes.
            assertThat(paycheck.get("taxesCents").asLong()).isEqualTo(500_000 - 30_000 - 310_000);
        }

        @Test
        void projectedAgainstActualLeavesItOutAsNothingWasProjected() throws Exception {
            added("{\"payDate\":\"2026-09-08\",\"name\":\"Q3 bonus\",\"netCents\":310000}");

            JsonNode report = run("{\"kind\":\"projected_vs_actual\",\"range\":\"this_year\",\"show\":\"table\",\"compareInclude\":[\"paychecks\"]}");

            assertThat(report.get("rows")).noneMatch(row -> row.get("description").asText().contains("Q3 bonus"));
        }
    }
}
