package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A raise over real HTTP: an income source's amounts changing from a date on, with earlier
 * paychecks keeping what they had. "Today" is Thursday 2026-10-01; paydays are the 1st and 15th.
 */
class PayChangesApiTest extends ApiTestBase {

    long checking;
    long retirement;
    long salary;

    @BeforeEach
    void addTheSalary() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        retirement = createAccount("Brightwater 401(k)", "investment");
        salary = server.post("/api/income-sources", pay(360000, 248620, ""), cookie).data().get("id").asLong();
    }

    /** The salary putting 6% of gross into the 401(k), matched in full; {@code extra} is spliced in as more fields. */
    private String pay(long grossCents, long netCents, String extra) {
        return """
                {"name":"Brightwater Health","depositAccountId":%d,"grossCents":%d,"netCents":%d,"primary":true,
                 "schedule":{"frequency":"semi_monthly","day1":1,"day2":15,"startDate":"2026-01-01","weekendRule":"none"},
                 "deductions":[{"name":"401(k)","percentBp":600,"toAccountId":%d}],
                 "match":{"toAccountId":%d,"mode":"formula","matchBp":10000,"capBpOfGross":600}%s}"""
                .formatted(checking, grossCents, netCents, retirement, retirement, extra);
    }

    private Reply change(String body) throws Exception {
        return server.put("/api/income-sources/" + salary, body, cookie);
    }

    private JsonNode paycheckOn(String date) throws Exception {
        JsonNode paychecks = server.get("/api/paychecks?from=" + date + "&to=" + date, cookie).data();
        assertThat(paychecks).hasSize(1);
        return paychecks.get(0);
    }

    @Test
    void removingAChangeOfPayRequiresASession() throws Exception {
        long changeId = change(pay(400000, 270000, ",\"effectiveFrom\":\"2027-06-01\"")).data().get("payHistory").get(1).get("id").asLong();

        assertThat(server.delete("/api/income-sources/" + salary + "/pay-changes/" + changeId, null).status()).isEqualTo(401);
        assertThat(server.get("/api/income-sources", cookie).data().get(0).get("payHistory")).hasSize(2);
    }

    @Test
    void aNewSourceHasOnlyTheAmountsItStartedWith() throws Exception {
        JsonNode history = server.get("/api/income-sources", cookie).data().get(0).get("payHistory");

        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("id").isNull()).isTrue();
        assertThat(history.get(0).get("effectiveDate").isNull()).isTrue();
        assertThat(history.get(0).get("netCents").asLong()).isEqualTo(248620);
        assertThat(history.get(0).get("current").asBoolean()).isTrue();
    }

    @Test
    void aRaiseStillToComeLeavesEveryEarlierPaycheckAlone() throws Exception {
        Reply reply = change(pay(400000, 270000, ",\"effectiveFrom\":\"2027-06-01\""));

        assertThat(reply.status()).isEqualTo(200);
        // What is in force today is still what shows on the source itself.
        assertThat(reply.data().get("netCents").asLong()).isEqualTo(248620);
        assertThat(reply.data().get("grossCents").asLong()).isEqualTo(360000);
        JsonNode history = reply.data().get("payHistory");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("current").asBoolean()).isTrue();
        assertThat(history.get(1).get("effectiveDate").asText()).isEqualTo("2027-06-01");
        assertThat(history.get(1).get("netCents").asLong()).isEqualTo(270000);
        assertThat(history.get(1).get("current").asBoolean()).isFalse();
        // 6% of the new gross, and the match that goes with it.
        assertThat(history.get(1).get("deductions").get(0).get("amountCents").asLong()).isEqualTo(24000);
        assertThat(history.get(1).get("matchFigures").get("perPaycheckCents").asLong()).isEqualTo(24000);

        assertThat(paycheckOn("2026-10-01").get("netCents").asLong()).isEqualTo(248620);
        assertThat(paycheckOn("2027-05-15").get("netCents").asLong()).isEqualTo(248620);
    }

    @Test
    void fromItsDateOnPaychecksAndWhatTheyPutAwayFollowTheRaise() throws Exception {
        change(pay(400000, 270000, ",\"effectiveFrom\":\"2027-06-01\""));

        JsonNode before = paycheckOn("2027-05-15");
        assertThat(before.get("netCents").asLong()).isEqualTo(248620);
        assertThat(before.get("contributions").get(0).get("amountCents").asLong()).isEqualTo(21600);

        JsonNode after = paycheckOn("2027-06-01");
        assertThat(after.get("netCents").asLong()).isEqualTo(270000);
        assertThat(after.get("contributions").findValuesAsText("kind")).containsExactly("contribution", "employer_match");
        assertThat(after.get("contributions").get(0).get("amountCents").asLong()).isEqualTo(24000);
        assertThat(after.get("contributions").get(1).get("amountCents").asLong()).isEqualTo(24000);
        assertThat(paycheckOn("2027-06-15").get("netCents").asLong()).isEqualTo(270000);
    }

    @Test
    void aRaiseDatedInThePastIsInForceTodayAndKeepsWhatCameBefore() throws Exception {
        Reply reply = change(pay(400000, 270000, ",\"effectiveFrom\":\"2026-07-01\""));

        assertThat(reply.data().get("netCents").asLong()).isEqualTo(270000);
        assertThat(reply.data().get("payHistory").get(1).get("current").asBoolean()).isTrue();
        assertThat(paycheckOn("2026-06-15").get("netCents").asLong()).isEqualTo(248620);
        assertThat(paycheckOn("2026-07-01").get("netCents").asLong()).isEqualTo(270000);
    }

    @Test
    void theSameDayAgainReplacesThatChange() throws Exception {
        change(pay(400000, 270000, ",\"effectiveFrom\":\"2027-06-01\""));

        Reply again = change(pay(410000, 275000, ",\"effectiveFrom\":\"2027-06-01\""));

        assertThat(again.data().get("payHistory")).hasSize(2);
        assertThat(again.data().get("payHistory").get(1).get("netCents").asLong()).isEqualTo(275000);
    }

    @Test
    void amountsThatDoNotDifferFromThatDaysAreNotAChangeOfPay() throws Exception {
        Reply reply = change(pay(360000, 248620, ",\"effectiveFrom\":\"2027-06-01\"").replace("Brightwater Health", "Brightwater Clinic"));

        // The rest of the edit still took, without a raise that changes nothing.
        assertThat(reply.data().get("name").asText()).isEqualTo("Brightwater Clinic");
        assertThat(reply.data().get("payHistory")).hasSize(1);
    }

    @Test
    void withoutADateAnEditCorrectsTheAmountsInForceToday() throws Exception {
        change(pay(400000, 270000, ",\"effectiveFrom\":\"2026-07-01\""));

        Reply corrected = change(pay(400000, 271500, ""));

        JsonNode history = corrected.data().get("payHistory");
        assertThat(history).hasSize(2);
        // The raise is what is in force, so the raise is what was corrected.
        assertThat(history.get(0).get("netCents").asLong()).isEqualTo(248620);
        assertThat(history.get(1).get("netCents").asLong()).isEqualTo(271500);
        assertThat(history.get(1).get("effectiveDate").asText()).isEqualTo("2026-07-01");
    }

    @Test
    void theAmountsItStartedWithCanStillBeCorrectedAfterARaise() throws Exception {
        change(pay(400000, 270000, ",\"effectiveFrom\":\"2026-07-01\""));

        Reply corrected = change(pay(360000, 250000, ",\"originalAmounts\":true"));

        JsonNode history = corrected.data().get("payHistory");
        assertThat(history.get(0).get("netCents").asLong()).isEqualTo(250000);
        assertThat(history.get(1).get("netCents").asLong()).isEqualTo(270000);
        assertThat(paycheckOn("2026-06-15").get("netCents").asLong()).isEqualTo(250000);
    }

    @Test
    void removingAChangeOfPayLetsTheAmountsBeforeItCarryOn() throws Exception {
        long changeId = change(pay(400000, 270000, ",\"effectiveFrom\":\"2026-07-01\"")).data().get("payHistory").get(1).get("id").asLong();

        Reply removed = server.delete("/api/income-sources/" + salary + "/pay-changes/" + changeId, cookie);

        assertThat(removed.status()).isEqualTo(200);
        assertThat(removed.data().get("payHistory")).hasSize(1);
        assertThat(removed.data().get("netCents").asLong()).isEqualTo(248620);
        assertThat(paycheckOn("2026-07-01").get("netCents").asLong()).isEqualTo(248620);
        // Its deductions went with it: the 6% is back to 6% of the first gross amount.
        assertThat(paycheckOn("2026-07-01").get("contributions").get(0).get("amountCents").asLong()).isEqualTo(21600);
        // Gone is gone, and another source's change is not this one's to remove.
        assertThat(server.delete("/api/income-sources/" + salary + "/pay-changes/" + changeId, cookie).status()).isEqualTo(404);
        assertThat(server.delete("/api/income-sources/999/pay-changes/1", cookie).status()).isEqualTo(404);
    }

    @Test
    void aRaiseCanBeUndone() throws Exception {
        change(pay(400000, 270000, ",\"effectiveFrom\":\"2026-07-01\""));

        assertThat(server.post("/api/undo", "", cookie).status()).isEqualTo(200);

        JsonNode source = server.get("/api/income-sources", cookie).data().get(0);
        assertThat(source.get("payHistory")).hasSize(1);
        assertThat(source.get("netCents").asLong()).isEqualTo(248620);
    }

    @Test
    void aSingleTypedOverPaycheckKeepsItsAmountThroughARaise() throws Exception {
        server.put("/api/income-sources/" + salary + "/paychecks/2027-06-15", "{\"netCents\":300000}", cookie);

        change(pay(400000, 270000, ",\"effectiveFrom\":\"2027-06-01\""));

        assertThat(paycheckOn("2027-06-15").get("netCents").asLong()).isEqualTo(300000);
        assertThat(paycheckOn("2027-07-01").get("netCents").asLong()).isEqualTo(270000);
    }

    @Test
    void anAccountAFutureMatchIsPaidIntoIsStillInUse() throws Exception {
        long later = createAccount("Harbor IRA", "investment");
        String toTheOtherAccount = """
                {"name":"Brightwater Health","depositAccountId":%d,"grossCents":400000,"netCents":270000,"primary":true,
                 "schedule":{"frequency":"semi_monthly","day1":1,"day2":15,"startDate":"2026-01-01","weekendRule":"none"},
                 "match":{"toAccountId":%d,"mode":"fixed","fixedCents":5000},"effectiveFrom":"2027-06-01"}"""
                .formatted(checking, later);
        assertThat(change(toTheOtherAccount).status()).isEqualTo(200);

        assertThat(server.delete("/api/accounts/" + later, cookie).status()).isEqualTo(409);
        assertThat(server.put("/api/accounts/" + later, "{\"name\":\"Harbor IRA\",\"type\":\"savings\",\"color\":\"#0f766e\"}", cookie).status())
                .isEqualTo(409);
    }

    @Test
    void thisYearRequiresASessionAndASourceThatExists() throws Exception {
        assertThat(server.get("/api/income-sources/" + salary + "/this-year", null).status()).isEqualTo(401);
        assertThat(server.get("/api/income-sources/999/this-year", cookie).status()).isEqualTo(404);
    }

    @Test
    void thisYearAddsUpWhatHasArrivedAndWhatTheWholeYearBrings() throws Exception {
        JsonNode year = server.get("/api/income-sources/" + salary + "/this-year", cookie).data();

        // Paid on the 1st and 15th since January: 19 paychecks by October 1, today's included, of 24.
        assertThat(year.get("year").asInt()).isEqualTo(2026);
        assertThat(year.get("paychecksSoFar").asInt()).isEqualTo(19);
        assertThat(year.get("soFarCents").asLong()).isEqualTo(19 * 248620L);
        assertThat(year.get("paychecksInYear").asInt()).isEqualTo(24);
        assertThat(year.get("wholeYearCents").asLong()).isEqualTo(24 * 248620L);
        assertThat(year.get("estimate").asBoolean()).isFalse();
    }

    @Test
    void thisYearFollowsARaiseAndAPaycheckTypedOver() throws Exception {
        // A raise from July: 12 paychecks at the old amount, 12 at the new one.
        change(pay(400000, 270000, ",\"effectiveFrom\":\"2026-07-01\""));
        // And one paycheck still to come typed over.
        server.put("/api/income-sources/" + salary + "/paychecks/2026-12-15", "{\"netCents\":300000}", cookie);

        JsonNode year = server.get("/api/income-sources/" + salary + "/this-year", cookie).data();

        assertThat(year.get("soFarCents").asLong()).isEqualTo(12 * 248620L + 7 * 270000L);
        assertThat(year.get("wholeYearCents").asLong()).isEqualTo(12 * 248620L + 11 * 270000L + 300000L);
    }

    @Test
    void refusesADateOutsideTheYearsTheAppCovers() throws Exception {
        Reply reply = change(pay(400000, 270000, ",\"effectiveFrom\":\"1800-01-01\""));

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.error()).isEqualTo("Please choose the date your pay changes from.");
        assertThat(server.get("/api/income-sources", cookie).data().get(0).get("payHistory")).hasSize(1);
    }
}
