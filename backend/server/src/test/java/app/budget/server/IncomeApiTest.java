package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Income sources, paychecks and pay periods over real HTTP against a temporary database.
 * "Today" is Thursday 2026-10-01.
 */
class IncomeApiTest extends ApiTestBase {

    long checking;

    @BeforeEach
    void addAnAccount() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
    }

    /** Paid on the 1st and 15th, moved to the previous business day. */
    private String salary(String name, boolean primary) {
        return """
                {"name":"%s","depositAccountId":%d,"grossCents":360000,"netCents":248620,"primary":%s,
                 "schedule":{"frequency":"semi_monthly","day1":1,"day2":15,"startDate":"2026-01-01",
                             "weekendRule":"previous_business_day"},
                 "deductions":[{"name":"401(k)","amountCents":15000},{"name":"HSA","amountCents":5000}]}"""
                .formatted(name, checking, primary);
    }

    /** A second job paid every other Friday from Oct 9, with hours that vary. */
    private String secondJob() {
        return """
                {"name":"Riverside Bakery","depositAccountId":%d,"netCents":31840,"netIsEstimate":true,
                 "schedule":{"frequency":"biweekly","startDate":"2026-10-09","weekendRule":"none"}}"""
                .formatted(checking);
    }

    private long create(String body) throws Exception {
        Reply reply = server.post("/api/income-sources", body, cookie);
        assertThat(reply.status()).isEqualTo(201);
        return reply.data().get("id").asLong();
    }

    @Nested
    class Auth {
        @Test
        void everyIncomeRouteRequiresASession() throws Exception {
            long id = create(salary("Acme Corp", true));

            assertThat(server.get("/api/income-sources", null).status()).isEqualTo(401);
            assertThat(server.post("/api/income-sources", secondJob(), null).status()).isEqualTo(401);
            assertThat(server.put("/api/income-sources/" + id, salary("Changed", true), null).status()).isEqualTo(401);
            assertThat(server.delete("/api/income-sources/" + id, null).status()).isEqualTo(401);
            assertThat(server.put("/api/income-sources/" + id + "/primary", "{}", null).status()).isEqualTo(401);
            assertThat(server.put("/api/income-sources/" + id + "/paychecks/2026-10-15", "{\"netCents\":1}", null).status())
                    .isEqualTo(401);
            assertThat(server.get("/api/paychecks", null).status()).isEqualTo(401);
            assertThat(server.get("/api/pay-periods", null).status()).isEqualTo(401);
            // Nothing was changed by any of those.
            JsonNode sources = server.get("/api/income-sources", cookie).data();
            assertThat(sources).hasSize(1);
            assertThat(sources.get(0).get("name").asText()).isEqualTo("Acme Corp");
        }
    }

    @Nested
    class Sources {
        @Test
        void theFirstSourceBecomesPrimaryEvenWhenNotAsked() throws Exception {
            Reply reply = server.post("/api/income-sources", salary("Acme Corp", false), cookie);

            assertThat(reply.status()).isEqualTo(201);
            assertThat(reply.data().get("primary").asBoolean()).isTrue();
            assertThat(reply.data().get("grossCents").asLong()).isEqualTo(360000);
            assertThat(reply.data().get("netCents").asLong()).isEqualTo(248620);
            assertThat(reply.data().get("schedule").get("frequency").asText()).isEqualTo("semi_monthly");
            assertThat(reply.data().get("schedule").get("day2").asInt()).isEqualTo(15);
            assertThat(reply.data().get("schedule").get("weekendRule").asText()).isEqualTo("previous_business_day");
            assertThat(reply.data().get("deductions").findValuesAsText("name")).containsExactly("401(k)", "HSA");
        }

        @Test
        void aLaterSourceIsNotPrimaryUnlessAskedAndThenTakesOver() throws Exception {
            long acme = create(salary("Acme Corp", true));
            long bakery = create(secondJob());
            // Top-level names only: deductions have names of their own.
            assertThat(server.get("/api/income-sources", cookie).data())
                    .extracting(source -> source.get("name").asText())
                    .containsExactly("Acme Corp", "Riverside Bakery");

            long newJob = create(salary("New Job", true));

            JsonNode sources = server.get("/api/income-sources", cookie).data();
            assertThat(sources.get(0).get("id").asLong()).isEqualTo(newJob);
            assertThat(sources.findValues("primary").stream().map(JsonNode::asBoolean)).containsExactly(true, false, false);
            assertThat(sources.findValues("id").stream().map(JsonNode::asLong)).contains(acme, bakery);
        }

        @Test
        void aSourceWithOnlyTakeHomePayHasNoGross() throws Exception {
            Reply reply = server.post("/api/income-sources", secondJob(), cookie);

            assertThat(reply.data().get("grossCents").isNull()).isTrue();
            assertThat(reply.data().get("netIsEstimate").asBoolean()).isTrue();
            assertThat(reply.data().get("deductions")).isEmpty();
        }

        @Test
        void explainsWhatIsMissingOrWrong() throws Exception {
            String base = "{\"name\":\"Acme\",\"depositAccountId\":%d,\"netCents\":100,\"schedule\":%s}";
            String weekly = "{\"frequency\":\"weekly\",\"startDate\":\"2026-10-02\",\"weekendRule\":\"none\"}";

            assertError("{\"name\":\"\",\"depositAccountId\":%d,\"netCents\":100,\"schedule\":%s}".formatted(checking, weekly),
                    "Please add a name for this income.");
            assertError(base.formatted(999, weekly), "Please choose the account this pay is deposited to.");
            assertError("{\"name\":\"Acme\",\"depositAccountId\":%d,\"netCents\":-1,\"schedule\":%s}".formatted(checking, weekly),
                    "The take-home amount can't be less than zero.");
            assertError("{\"name\":\"Acme\",\"depositAccountId\":%d,\"schedule\":%s}".formatted(checking, weekly),
                    "The take-home amount is missing. Please enter an amount.");
            assertError("{\"name\":\"Acme\",\"depositAccountId\":%d,\"netCents\":100}".formatted(checking),
                    "Please choose how often you're paid.");
            assertError(base.formatted(checking, "{\"frequency\":\"yearly\",\"startDate\":\"2026-10-02\",\"weekendRule\":\"none\"}"),
                    "Please choose how often you're paid from the list.");
            assertError(base.formatted(checking, "{\"frequency\":\"weekly\",\"weekendRule\":\"none\"}"),
                    "Please choose the date of a payday to start from.");
            assertError(base.formatted(checking, "{\"frequency\":\"weekly\",\"startDate\":\"2026-10-02\",\"weekendRule\":\"skip\"}"),
                    "Please choose what happens on a weekend or holiday from the list.");
            assertError(base.formatted(checking,
                            "{\"frequency\":\"semi_monthly\",\"day1\":15,\"day2\":15,\"startDate\":\"2026-10-02\",\"weekendRule\":\"none\"}"),
                    "Please choose two different days of the month.");
            assertError(base.formatted(checking,
                            "{\"frequency\":\"monthly\",\"day1\":32,\"startDate\":\"2026-10-02\",\"weekendRule\":\"none\"}"),
                    "Please choose the day of the month from the list.");
            assertError(base.formatted(checking,
                            "{\"frequency\":\"manual_dates\",\"manualDates\":[\"2026-10-02\"],\"weekendRule\":\"none\"}"),
                    "Please add at least two paydays, so there's a pay period between them.");
            assertError(base.formatted(checking, weekly).replace("}}", "},\"deductions\":[{\"name\":\"401(k)\",\"amountCents\":1,\"toAccountId\":999}]}"),
                    "Please choose the account each deduction goes to from the list.");

            assertThat(server.get("/api/income-sources", cookie).data()).isEmpty();
        }

        private void assertError(String body, String message) throws Exception {
            Reply reply = server.post("/api/income-sources", body, cookie);
            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.error()).isEqualTo(message);
        }

        @Test
        void changingASourceReplacesItsScheduleAndDeductions() throws Exception {
            long savings = createAccount("Retirement", "investment");
            long id = create(salary("Acme Corp", true));

            Reply reply = server.put("/api/income-sources/" + id, """
                    {"name":"Acme Corporation","depositAccountId":%d,"netCents":250000,"primary":true,
                     "schedule":{"frequency":"manual_dates","weekendRule":"none",
                                 "manualDates":["2026-11-20","2026-10-05","2026-10-20"]},
                     "deductions":[{"name":"401(k)","amountCents":20000,"toAccountId":%d}]}""".formatted(checking, savings), cookie);

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.data().get("name").asText()).isEqualTo("Acme Corporation");
            assertThat(reply.data().get("grossCents").isNull()).isTrue();
            assertThat(reply.data().get("schedule").get("frequency").asText()).isEqualTo("manual_dates");
            assertThat(reply.data().get("schedule").get("manualDates").toString())
                    .isEqualTo("[\"2026-10-05\",\"2026-10-20\",\"2026-11-20\"]");
            assertThat(reply.data().get("deductions")).hasSize(1);
            assertThat(reply.data().get("deductions").get(0).get("toAccountId").asLong()).isEqualTo(savings);
        }

        @Test
        void thePrimarySourceCannotSimplyStopBeingPrimary() throws Exception {
            long id = create(salary("Acme Corp", true));

            Reply reply = server.put("/api/income-sources/" + id, salary("Acme Corp", false), cookie);

            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.error()).contains("pay periods");
            assertThat(server.get("/api/income-sources", cookie).data().get(0).get("primary").asBoolean()).isTrue();
        }

        @Test
        void payPeriodsCanBeHandedToAnotherSourceWithoutSendingItAllAgain() throws Exception {
            long acme = create(salary("Acme Corp", true));
            long bakery = create(secondJob());

            Reply reply = server.put("/api/income-sources/" + bakery + "/primary", "{}", cookie);

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.changed()).isTrue();
            assertThat(reply.data().get("primary").asBoolean()).isTrue();
            // Nothing else about either source moved.
            assertThat(reply.data().get("netCents").asLong()).isEqualTo(31840);
            JsonNode sources = server.get("/api/income-sources", cookie).data();
            assertThat(sources.get(0).get("id").asLong()).isEqualTo(bakery);
            assertThat(sources.get(1).get("id").asLong()).isEqualTo(acme);
            assertThat(sources.get(1).get("primary").asBoolean()).isFalse();
            assertThat(server.get("/api/pay-periods", cookie).data().get("primarySourceId").asLong()).isEqualTo(bakery);

            server.post("/api/undo", null, cookie);

            assertThat(server.get("/api/pay-periods", cookie).data().get("primarySourceId").asLong()).isEqualTo(acme);
        }

        @Test
        void handingPayPeriodsToASourceThatIsNotThereIsNotFound() throws Exception {
            create(salary("Acme Corp", true));

            assertThat(server.put("/api/income-sources/999/primary", "{}", cookie).status()).isEqualTo(404);
            assertThat(server.put("/api/income-sources/abc/primary", "{}", cookie).status()).isEqualTo(404);
            assertThat(server.get("/api/income-sources", cookie).data().get(0).get("primary").asBoolean()).isTrue();
        }

        @Test
        void makingAnotherSourcePrimaryMovesTheRole() throws Exception {
            long acme = create(salary("Acme Corp", true));
            long bakery = create(secondJob());

            Reply reply = server.put("/api/income-sources/" + bakery, secondJob().replace("}}", "},\"primary\":true}"), cookie);

            assertThat(reply.status()).isEqualTo(200);
            JsonNode sources = server.get("/api/income-sources", cookie).data();
            assertThat(sources.get(0).get("id").asLong()).isEqualTo(bakery);
            assertThat(sources.get(1).get("id").asLong()).isEqualTo(acme);
            assertThat(sources.get(1).get("primary").asBoolean()).isFalse();
        }

        @Test
        void thePrimarySourceCannotBeRemovedWhileOthersDependOnIt() throws Exception {
            long acme = create(salary("Acme Corp", true));
            long bakery = create(secondJob());

            Reply refused = server.delete("/api/income-sources/" + acme, cookie);

            assertThat(refused.status()).isEqualTo(409);
            assertThat(refused.error()).contains("primary");
            assertThat(server.delete("/api/income-sources/" + bakery, cookie).status()).isEqualTo(200);
            assertThat(server.delete("/api/income-sources/" + acme, cookie).status()).isEqualTo(200);
            assertThat(server.get("/api/income-sources", cookie).data()).isEmpty();
            // With the source gone, its deposit account is free to be deleted too.
            assertThat(server.delete("/api/accounts/" + checking, cookie).status()).isEqualTo(200);
        }

        @Test
        void aSourceThatDoesNotExistIsNotFound() throws Exception {
            assertThat(server.put("/api/income-sources/999", salary("Acme", true), cookie).status()).isEqualTo(404);
            assertThat(server.delete("/api/income-sources/999", cookie).status()).isEqualTo(404);
            assertThat(server.delete("/api/income-sources/abc", cookie).status()).isEqualTo(404);
        }
    }

    @Nested
    class PayPeriods {
        @Test
        void thereAreNoneUntilThereIsAnIncomeSource() throws Exception {
            Reply reply = server.get("/api/pay-periods", cookie);

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.data().get("primarySourceId").isNull()).isTrue();
            assertThat(reply.data().get("periods")).isEmpty();
        }

        @Test
        void startWithTheCurrentPeriodAndFollowThePrimarySchedule() throws Exception {
            long id = create(salary("Acme Corp", true));

            JsonNode data = server.get("/api/pay-periods", cookie).data();
            JsonNode periods = data.get("periods");

            assertThat(data.get("primarySourceId").asLong()).isEqualTo(id);
            assertPeriod(periods.get(0), "2026-10-01", "2026-10-14", true);
            assertPeriod(periods.get(1), "2026-10-15", "2026-10-29", false);
            // Nov 1, 2026 is a Sunday, so that payday is Fri Oct 30.
            assertPeriod(periods.get(2), "2026-10-30", "2026-11-12", false);
            assertThat(periods.get(2).get("scheduledPayday").asText()).isEqualTo("2026-11-01");
            // The default horizon is 12 months: the last period is the one holding Oct 1, 2027.
            assertPeriod(periods.get(periods.size() - 1), "2027-10-01", "2027-10-14", false);
        }

        @Test
        void aHolidayOfTheUsersOwnMovesThePayday() throws Exception {
            create(salary("Acme Corp", true));
            server.post("/api/holidays", "{\"date\":\"2026-10-30\",\"name\":\"Office closed\"}", cookie);

            JsonNode periods = server.get("/api/pay-periods", cookie).data().get("periods");

            assertPeriod(periods.get(1), "2026-10-15", "2026-10-28", false);
            assertPeriod(periods.get(2), "2026-10-29", "2026-11-12", false);
        }

        @Test
        void aBankHolidayMovesThePaydayUntilItIsSwitchedOff() throws Exception {
            // Paid on the 26th; Nov 26, 2026 is Thanksgiving.
            create("""
                    {"name":"Acme Corp","depositAccountId":%d,"netCents":500000,
                     "schedule":{"frequency":"monthly","day1":26,"startDate":"2026-01-01",
                                 "weekendRule":"previous_business_day"}}""".formatted(checking));
            assertThat(server.get("/api/pay-periods?from=2026-11-25&to=2026-11-25", cookie).data()
                    .get("periods").get(0).get("start").asText()).isEqualTo("2026-11-25");

            JsonNode thanksgiving = server.get("/api/holidays?from=2026-11-26&to=2026-11-26", cookie).data().get(0);
            server.put("/api/holidays/" + thanksgiving.get("id").asLong(),
                    "{\"date\":\"2026-11-26\",\"name\":\"Thanksgiving Day\",\"enabled\":false}", cookie);

            assertThat(server.get("/api/pay-periods?from=2026-11-26&to=2026-11-26", cookie).data()
                    .get("periods").get(0).get("start").asText()).isEqualTo("2026-11-26");
        }

        @Test
        void aSecondJobsPaychecksLandInThePrimaryPeriodTheyFallIn() throws Exception {
            create(salary("Acme Corp", true));
            create(secondJob());

            JsonNode periods = server.get("/api/pay-periods", cookie).data().get("periods");

            // Oct 1–14: the Oct 1 salary and the Oct 9 bakery check.
            JsonNode first = periods.get(0);
            assertThat(first.get("paychecks").findValuesAsText("sourceName")).containsExactly("Acme Corp", "Riverside Bakery");
            assertThat(first.get("paychecks").findValuesAsText("payDate")).containsExactly("2026-10-01", "2026-10-09");
            assertThat(first.get("paychecks").get(1).get("estimate").asBoolean()).isTrue();
            assertThat(first.get("incomeCents").asLong()).isEqualTo(248620 + 31840);
            // Oct 15–29: the Oct 15 salary and the Oct 23 bakery check.
            assertThat(periods.get(1).get("paychecks").findValuesAsText("payDate")).containsExactly("2026-10-15", "2026-10-23");
        }

        @Test
        void aShorterHorizonMeansFewerPeriods() throws Exception {
            create(salary("Acme Corp", true));
            int twelveMonths = server.get("/api/pay-periods", cookie).data().get("periods").size();

            Reply saved = server.put("/api/settings/projection_horizon_months", "{\"value\":\"6\"}", cookie);
            JsonNode periods = server.get("/api/pay-periods", cookie).data().get("periods");

            assertThat(saved.status()).isEqualTo(200);
            assertThat(periods.size()).isLessThan(twelveMonths);
            assertPeriod(periods.get(periods.size() - 1), "2027-04-01", "2027-04-14", false);
        }

        @Test
        void theHorizonMustBeOneOfTheChoices() throws Exception {
            assertThat(server.get("/api/settings", cookie).data().get("projection_horizon_months").asText()).isEqualTo("12");
            assertThat(server.put("/api/settings/projection_horizon_months", "{\"value\":\"7\"}", cookie).status()).isEqualTo(400);
        }

        @Test
        void refusesARangeThatMakesNoSense() throws Exception {
            assertThat(server.get("/api/pay-periods?from=2027-01-01&to=2026-01-01", cookie).status()).isEqualTo(400);
            assertThat(server.get("/api/pay-periods?from=someday", cookie).status()).isEqualTo(400);
            assertThat(server.get("/api/paychecks?from=2026-01-01&to=2099-01-01", cookie).status()).isEqualTo(400);
        }

        private void assertPeriod(JsonNode period, String start, String end, boolean current) {
            assertThat(period.get("start").asText()).isEqualTo(start);
            assertThat(period.get("end").asText()).isEqualTo(end);
            assertThat(period.get("current").asBoolean()).isEqualTo(current);
        }
    }

    @Nested
    class Paychecks {
        @Test
        void listsUpcomingPaychecksFromEverySourceInDateOrder() throws Exception {
            create(salary("Acme Corp", true));
            create(secondJob());

            JsonNode paychecks = server.get("/api/paychecks?from=2026-10-01&to=2026-11-01", cookie).data();

            assertThat(paychecks.findValuesAsText("payDate")).containsExactly(
                    "2026-10-01", "2026-10-09", "2026-10-15", "2026-10-23", "2026-10-30");
            JsonNode moved = paychecks.get(4);
            assertThat(moved.get("scheduledDate").asText()).isEqualTo("2026-11-01");
            assertThat(moved.get("netCents").asLong()).isEqualTo(248620);
            assertThat(moved.get("estimate").asBoolean()).isFalse();
            assertThat(moved.get("projectedCents").isNull()).isTrue();
        }

        @Test
        void oneAmountCanBeTypedOverAndKeepsWhatWasProjected() throws Exception {
            create(salary("Acme Corp", true));
            long bakery = create(secondJob());

            Reply first = server.put("/api/income-sources/" + bakery + "/paychecks/2026-10-09", "{\"netCents\":29015}", cookie);
            Reply second = server.put("/api/income-sources/" + bakery + "/paychecks/2026-10-09", "{\"netCents\":30000}", cookie);

            assertThat(first.status()).isEqualTo(200);
            assertThat(second.status()).isEqualTo(200);
            JsonNode paychecks = server.get("/api/paychecks?from=2026-10-09&to=2026-10-23", cookie).data();
            JsonNode edited = paychecks.get(0);
            assertThat(edited.get("netCents").asLong()).isEqualTo(30000);
            // A typed amount is no longer an estimate; the original projection is kept for reference.
            assertThat(edited.get("estimate").asBoolean()).isFalse();
            assertThat(edited.get("projectedCents").asLong()).isEqualTo(31840);
            // Only that one paycheck changed.
            JsonNode next = paychecks.get(2);
            assertThat(next.get("payDate").asText()).isEqualTo("2026-10-23");
            assertThat(next.get("netCents").asLong()).isEqualTo(31840);
            assertThat(next.get("estimate").asBoolean()).isTrue();
        }

        @Test
        void anEditFollowsAPaydayThatAWeekendMoved() throws Exception {
            long acme = create(salary("Acme Corp", true));

            // The Nov 1 paycheck arrives Fri Oct 30; it is still edited by its scheduled date.
            server.put("/api/income-sources/" + acme + "/paychecks/2026-11-01", "{\"netCents\":260000}", cookie);

            JsonNode paycheck = server.get("/api/paychecks?from=2026-10-30&to=2026-10-30", cookie).data().get(0);
            assertThat(paycheck.get("netCents").asLong()).isEqualTo(260000);
            assertThat(server.get("/api/pay-periods?from=2026-10-30&to=2026-10-30", cookie).data()
                    .get("periods").get(0).get("incomeCents").asLong()).isEqualTo(260000);
        }

        @Test
        void onlyRealPaydaysCanBeEdited() throws Exception {
            long acme = create(salary("Acme Corp", true));

            assertThat(server.put("/api/income-sources/" + acme + "/paychecks/2026-10-07", "{\"netCents\":1}", cookie).status())
                    .isEqualTo(404);
            assertThat(server.put("/api/income-sources/999/paychecks/2026-10-15", "{\"netCents\":1}", cookie).status())
                    .isEqualTo(404);
            assertThat(server.put("/api/income-sources/" + acme + "/paychecks/soon", "{\"netCents\":1}", cookie).status())
                    .isEqualTo(400);
            assertThat(server.put("/api/income-sources/" + acme + "/paychecks/2026-10-15", "{\"netCents\":-5}", cookie).status())
                    .isEqualTo(400);
            assertThat(server.put("/api/income-sources/" + acme + "/paychecks/2026-10-15", "{}", cookie).status())
                    .isEqualTo(400);
        }

        @Test
        void removingASourceRemovesItsEditsToo() throws Exception {
            long acme = create(salary("Acme Corp", true));
            server.put("/api/income-sources/" + acme + "/paychecks/2026-10-15", "{\"netCents\":1}", cookie);

            assertThat(server.delete("/api/income-sources/" + acme, cookie).status()).isEqualTo(200);

            assertThat(server.get("/api/paychecks", cookie).data()).isEmpty();
        }
    }
}
