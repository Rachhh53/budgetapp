package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pay periods with their balances, over real HTTP against a temporary database.
 * "Today" is Thursday 2026-10-01; $2,486.20 arrives on the 1st and 15th, so the pay periods
 * are Oct 1–14, Oct 15–31 and Nov 1–14.
 */
class LedgerApiTest extends ApiTestBase {

    long checking;
    long savings;
    long visa;

    @BeforeEach
    void addAccountsAndAPaycheck() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        savings = createAccount("Rainy Day Savings", "savings");
        visa = createAccount("Visa", "credit_card");
        createSalary(checking);
    }

    private long item(String name, String kind, long cents, String schedule, String extra) throws Exception {
        return created("/api/items",
                "{\"name\":\"%s\",\"kind\":\"%s\",\"amountCents\":%d,\"payFromAccountId\":%d,\"schedule\":%s%s}"
                        .formatted(name, kind, cents, checking, schedule, extra.isEmpty() ? "" : "," + extra));
    }

    private static String monthly(int day) {
        return "{\"frequency\":\"monthly\",\"day1\":%d,\"startDate\":\"2026-01-01\"}".formatted(day);
    }

    private JsonNode period(String start) throws Exception {
        Reply reply = server.get("/api/pay-periods/" + start, cookie);
        assertThat(reply.status()).isEqualTo(200);
        return reply.data();
    }

    /** One account's figures within a period's detail. */
    private static JsonNode account(JsonNode period, long accountId) {
        return StreamSupport.stream(period.get("accounts").spliterator(), false)
                .filter(account -> account.get("accountId").asLong() == accountId)
                .findFirst()
                .orElseThrow();
    }

    @Nested
    class ATransferDueOnPayday {
        @Test
        void arrivesInTheOtherAccountWhoseBalanceWasEnteredTheDayBefore() throws Exception {
            enterBalance(checking, "2026-09-30", 500000);
            enterBalance(savings, "2026-09-30", 1000000);
            item("To savings", "transfer", 45000, monthly(1), "\"autopay\":true,\"payToAccountId\":" + savings);

            JsonNode period = period("2026-10-01");
            assertThat(period.get("occurrences").findValuesAsText("name")).contains("To savings");
            assertThat(account(period, checking).get("outCents").asLong()).isEqualTo(45000);
            assertThat(account(period, savings).get("inCents").asLong()).isEqualTo(45000);
            assertThat(account(period("2026-10-15"), savings).get("startingCents").asLong()).isEqualTo(1045000);
        }
    }

    @Nested
    class Auth {
        @Test
        void bothRoutesRequireASession() throws Exception {
            assertThat(server.get("/api/pay-periods", null).status()).isEqualTo(401);
            assertThat(server.get("/api/pay-periods/2026-10-01", null).status()).isEqualTo(401);
        }
    }

    @Nested
    class BeforeABalanceIsEntered {
        @Test
        void theAccountIsBuiltUpFromNothingByItsPaychecks() throws Exception {
            JsonNode data = server.get("/api/pay-periods?from=2026-10-01&to=2026-10-31", cookie).data();

            assertThat(data.get("primaryAccountId").asLong()).isEqualTo(checking);
            assertThat(data.get("periods")).hasSize(2);
            assertThat(data.get("periods").get(0).get("incomeCents").asLong()).isEqualTo(248620);
            assertThat(data.get("periods").get(0).get("paychecks").get(0).get("depositAccountId").asLong()).isEqualTo(checking);
            // Nineteen paychecks since the pay schedule began on Jan 1, the one on Oct 1 included.
            assertThat(data.get("periods").get(0).get("projectedEndingCents").asLong()).isEqualTo(19 * 248620L);
        }

        @Test
        void aPeriodAddsUpEverythingSinceTheFirstMoneyMoved() throws Exception {
            item("Car loan", "bill", 41200, monthly(5), "\"autopay\":true");

            JsonNode period = period("2026-10-01");

            assertThat(period.get("end").asText()).isEqualTo("2026-10-14");
            assertThat(period.get("current").asBoolean()).isTrue();
            assertThat(period.get("occurrences").findValuesAsText("name")).containsExactly("Car loan");
            // Eighteen paychecks and nine loan payments before Oct 1; one more of each in the period.
            JsonNode built = account(period, checking);
            assertThat(built.get("builtFromZero").asBoolean()).isTrue();
            assertThat(built.get("startingCents").asLong()).isEqualTo(18 * 248620L - 9 * 41200L);
            assertThat(built.get("inCents").asLong()).isEqualTo(248620);
            assertThat(built.get("outCents").asLong()).isEqualTo(41200);
            assertThat(built.get("endingCents").asLong()).isEqualTo(19 * 248620L - 10 * 41200L);
            // Nothing has moved in or out of savings, so it stands at nothing.
            assertThat(account(period, savings).get("endingCents").asLong()).isZero();
            assertThat(account(period, savings).get("builtFromZero").asBoolean()).isTrue();
        }

        @Test
        void enteringABalanceTakesOverFromThatDay() throws Exception {
            enterBalance(checking, "2026-10-01", 121438);

            JsonNode entered = account(period("2026-10-01"), checking);
            assertThat(entered.get("builtFromZero").asBoolean()).isFalse();
            assertThat(entered.get("startingCents").asLong()).isEqualTo(121438);
        }

        @Test
        void aTransferBuildsTheAccountItArrivesInAndItsBucketTogether() throws Exception {
            long fund = server.post("/api/buckets",
                    "{\"accountId\":%d,\"name\":\"Emergency fund\",\"color\":\"#b45309\"}".formatted(savings), cookie)
                    .data().get("bucket").get("id").asLong();
            server.put("/api/buckets/" + fund,
                    "{\"name\":\"Emergency fund\",\"color\":\"#b45309\",\"openedOn\":\"2026-01-01\"}", cookie);
            item("To savings", "transfer", 45000, monthly(1), "\"autopay\":true,\"payToAccountId\":%d,\"bucketId\":%d".formatted(savings, fund));

            // Ten transfers, Jan 1 to Oct 1: all in the account and all in its bucket.
            JsonNode built = account(period("2026-10-01"), savings);
            assertThat(built.get("endingCents").asLong()).isEqualTo(450000);
            assertThat(built.get("bucketsCents").asLong()).isEqualTo(450000);
            assertThat(built.get("availableCents").asLong()).isZero();
        }

        @Test
        void anArchivedAccountIsNotListedFromTheDayItWasArchivedAsOf() throws Exception {
            assertThat(server.put("/api/accounts/" + visa,
                    "{\"name\":\"Visa\",\"type\":\"credit_card\",\"color\":\"#0f766e\",\"archived\":true}", cookie).status()).isEqualTo(200);

            // Archived today, Oct 1, so not in the period starting today. ArchivingApiTest covers the periods before.
            assertThat(period("2026-10-01").get("accounts").findValues("accountId")).extracting(JsonNode::asLong)
                    .containsExactlyInAnyOrder(checking, savings);
        }
    }

    @Nested
    class Projection {
        long electric;
        long rent;
        long carLoan;

        @BeforeEach
        void setUpAMonth() throws Exception {
            enterBalance(checking, "2026-10-01", 121438);
            enterBalance(savings, "2026-10-01", 500000);
            // Rent is set aside a paycheck ahead ("Pay a period early"): due on payday, it comes out of the one before.
            rent = item("Rent", "bill", 155000, monthly(1), "\"placement\":\"one_early\"");
            electric = item("Electric", "bill", 14000, monthly(16), "\"placement\":\"one_early\",\"estimate\":true");
            carLoan = item("Car loan", "bill", 41200, monthly(5), "\"autopay\":true");
            item("Groceries", "budget", 30000, "{\"frequency\":\"every_pay_period\",\"startDate\":\"2026-10-01\"}", "");
            item("To savings", "transfer", 20000, monthly(2), "\"autopay\":true,\"payToAccountId\":" + savings);
            created("/api/entries",
                    "{\"kind\":\"money_out\",\"date\":\"2026-10-09\",\"amountCents\":8500,\"description\":\"Birthday gift for Mom\",\"accountId\":%d}"
                            .formatted(checking));
            // October's rent belonged to the paycheck before, and was paid from it.
            server.put("/api/items/" + rent + "/occurrences/2026-10-01", "{\"paid\":true,\"paidDate\":\"2026-09-29\"}", cookie);
        }

        @Test
        void aPeriodListsWhatItPaysForAndAddsItUp() throws Exception {
            JsonNode period = period("2026-10-01");

            // Electric is due on the 16th but placed one early; rent due Oct 1 is not here at all.
            assertThat(period.get("occurrences").findValuesAsText("name"))
                    .containsExactlyInAnyOrder("Groceries", "To savings", "Car loan", "Electric");
            assertThat(period.get("entries").findValuesAsText("description")).containsExactly("Birthday gift for Mom");
            assertThat(period.get("paychecks")).hasSize(1);
            assertThat(period.get("carriedOver")).isEmpty();

            JsonNode account = account(period, checking);
            assertThat(account.get("startingCents").asLong()).isEqualTo(121438);
            assertThat(account.get("startingOn").asText()).isEqualTo("2026-10-01");
            assertThat(account.get("entered").asBoolean()).isTrue();
            assertThat(account.get("inCents").asLong()).isEqualTo(248620);
            // 300.00 groceries + 200.00 transfer + 412.00 car loan + 85.00 gift + 140.00 electric
            assertThat(account.get("outCents").asLong()).isEqualTo(113700);
            assertThat(account.get("endingCents").asLong()).isEqualTo(256358);
            assertThat(account.get("lowestCents").asLong()).isEqualTo(121438);
        }

        @Test
        void anOccurrenceCountsOnADayInsideThePeriodThatPaysForIt() throws Exception {
            JsonNode electricOccurrence = StreamSupport.stream(period("2026-10-01").get("occurrences").spliterator(), false)
                    .filter(occurrence -> occurrence.get("itemId").asLong() == electric)
                    .findFirst()
                    .orElseThrow();

            assertThat(electricOccurrence.get("dueDate").asText()).isEqualTo("2026-10-16");
            assertThat(electricOccurrence.get("payBy").asText()).isEqualTo("2026-10-14");
            assertThat(electricOccurrence.get("early").asBoolean()).isTrue();
            assertThat(electricOccurrence.get("estimate").asBoolean()).isTrue();
        }

        @Test
        void theTransferArrivesInTheOtherAccount() throws Exception {
            JsonNode account = account(period("2026-10-01"), savings);

            assertThat(account.get("inCents").asLong()).isEqualTo(20000);
            assertThat(account.get("outCents").asLong()).isZero();
            assertThat(account.get("endingCents").asLong()).isEqualTo(520000);
        }

        @Test
        void eachPeriodStartsWhereTheOneBeforeEnded() throws Exception {
            JsonNode second = period("2026-10-15");

            // November's rent is due on payday, so it comes out of this paycheck.
            assertThat(second.get("occurrences").findValuesAsText("name")).containsExactlyInAnyOrder("Groceries", "Rent");
            JsonNode account = account(second, checking);
            assertThat(account.get("startingCents").asLong()).isEqualTo(256358);
            assertThat(account.get("entered").asBoolean()).isFalse();
            assertThat(account.get("outCents").asLong()).isEqualTo(185000);
            assertThat(account.get("endingCents").asLong()).isEqualTo(319978);
        }

        @Test
        void theListCarriesEachPeriodsProjectedEnding() throws Exception {
            JsonNode periods = server.get("/api/pay-periods?from=2026-10-01&to=2026-10-31", cookie).data().get("periods");

            assertThat(periods.findValues("projectedEndingCents").stream().map(JsonNode::asLong)).containsExactly(256358L, 319978L);
            assertThat(periods.get(0).get("lowestCents").asLong()).isEqualTo(121438);
        }

        @Test
        void aBillNotYetMarkedPaidStillComesOutOfTheBalanceEnteredAfterIt() throws Exception {
            server.put("/api/items/" + rent + "/occurrences/2026-10-01", "{\"paid\":false}", cookie);

            JsonNode period = period("2026-10-01");
            JsonNode account = account(period, checking);

            // Rent belongs to the period before, so it is named separately from this period's own list.
            assertThat(period.get("occurrences").findValuesAsText("name")).doesNotContain("Rent");
            assertThat(period.get("carriedOver").findValuesAsText("name")).containsExactly("Rent");

            // The balance entered on Oct 1 does not include rent that has not been paid yet.
            assertThat(account.get("outCents").asLong()).isEqualTo(113700 + 155000);
            assertThat(account.get("endingCents").asLong()).isEqualTo(256358 - 155000);
        }

        @Test
        void aBudgetIsTakenAsSpentOnItsDayAndNeverCarriedAsUnpaid() throws Exception {
            // A balance entered after this period's groceries: a budget is never ticked, so it is already in that balance.
            enterBalance(checking, "2026-10-05", 100000);

            JsonNode period = period("2026-10-01");

            assertThat(period.get("carriedOver").findValuesAsText("name")).doesNotContain("Groceries");
            assertThat(period.get("occurrences").findValuesAsText("name")).contains("Groceries");
        }

        @Test
        void oneOccurrenceCanBePaidFromAnotherAccountJustThisOnce() throws Exception {
            long checkingOut = account(period("2026-10-01"), checking).get("outCents").asLong();
            long savingsOut = account(period("2026-10-01"), savings).get("outCents").asLong();

            Reply moved = server.put("/api/items/" + electric + "/occurrences/2026-10-16", "{\"payFromAccountId\":%d}".formatted(savings), cookie);

            assertThat(moved.status()).isEqualTo(200);
            assertThat(moved.data().get("payFromAccountId").asLong()).isEqualTo(savings);
            assertThat(account(period("2026-10-01"), checking).get("outCents").asLong()).isEqualTo(checkingOut - 14000);
            assertThat(account(period("2026-10-01"), savings).get("outCents").asLong()).isEqualTo(savingsOut + 14000);
            // November's is still paid from checking.
            assertThat(account(period("2026-11-01"), checking).get("outCents").asLong()).isEqualTo(30000 + 20000 + 41200 + 14000);

            // Choosing the item's own account again puts it back.
            server.put("/api/items/" + electric + "/occurrences/2026-10-16", "{\"payFromAccountId\":%d}".formatted(checking), cookie);
            assertThat(account(period("2026-10-01"), checking).get("outCents").asLong()).isEqualTo(checkingOut);
        }

        @Test
        void aOneTimeAccountHasToBeOneThatCanPayIt() throws Exception {
            long transfer = item("Top up", "transfer", 5000, monthly(20), "\"payToAccountId\":" + savings);

            Reply toItself = server.put("/api/items/" + transfer + "/occurrences/2026-10-20", "{\"payFromAccountId\":%d}".formatted(savings), cookie);
            assertThat(toItself.status()).isEqualTo(400);
            assertThat(toItself.error()).isEqualTo("Please choose an account other than the one this goes to.");
            Reply missing = server.put("/api/items/" + electric + "/occurrences/2026-10-16", "{\"payFromAccountId\":999}", cookie);
            assertThat(missing.error()).isEqualTo("Please choose the account this one is paid from.");
        }

        @Test
        void typingOverOneOccurrenceChangesOnlyThatPeriod() throws Exception {
            server.put("/api/items/" + electric + "/occurrences/2026-10-16", "{\"amountCents\":15250}", cookie);

            assertThat(account(period("2026-10-01"), checking).get("endingCents").asLong()).isEqualTo(256358 - 1250);
            // November's electric bill, paid from the Nov 1 period, is still the estimate.
            assertThat(account(period("2026-11-01"), checking).get("outCents").asLong())
                    .isEqualTo(30000 + 20000 + 41200 + 14000);
        }

        @Test
        void movingAnOccurrenceToAnotherPeriodMovesItsMoneyToo() throws Exception {
            server.put("/api/items/" + electric + "/occurrences/2026-10-16", "{\"periodStart\":\"2026-10-15\"}", cookie);

            assertThat(period("2026-10-01").get("occurrences").findValuesAsText("name")).doesNotContain("Electric");
            assertThat(account(period("2026-10-01"), checking).get("outCents").asLong()).isEqualTo(113700 - 14000);
            assertThat(period("2026-10-15").get("occurrences").findValuesAsText("name")).contains("Electric");
            assertThat(account(period("2026-10-15"), checking).get("endingCents").asLong()).isEqualTo(319978);
        }

        @Test
        void aSkippedOccurrenceIsListedButNotCounted() throws Exception {
            server.put("/api/items/" + carLoan + "/occurrences/2026-10-05", "{\"skipped\":true}", cookie);

            JsonNode period = period("2026-10-01");

            assertThat(period.get("occurrences").findValuesAsText("name")).contains("Car loan");
            assertThat(account(period, checking).get("outCents").asLong()).isEqualTo(113700 - 41200);
        }

        @Test
        void aChangeFromADateLeavesEarlierPeriodsAsTheyWere() throws Exception {
            server.put("/api/items/" + carLoan,
                    "{\"name\":\"Car loan\",\"kind\":\"bill\",\"amountCents\":43000,\"payFromAccountId\":%d,\"autopay\":true,\"schedule\":%s,\"effectiveFrom\":\"2026-11-01\"}"
                            .formatted(checking, monthly(5)), cookie);

            assertThat(account(period("2026-10-01"), checking).get("outCents").asLong()).isEqualTo(113700);
            assertThat(account(period("2026-11-01"), checking).get("outCents").asLong())
                    .isEqualTo(30000 + 20000 + 43000 + 14000);
        }

        @Test
        void enteringTheBalanceAgainForTheSameDayReplacesIt() throws Exception {
            enterBalance(checking, "2026-10-01", 20000);

            JsonNode account = account(period("2026-10-01"), checking);

            assertThat(account.get("startingCents").asLong()).isEqualTo(20000);
            assertThat(account.get("endingCents").asLong()).isEqualTo(20000 + 248620 - 113700);
        }

        @Test
        void billsFromOlderPeriodsThatWereNeverTickedAreTakenAsPaid() throws Exception {
            // Electric has been due on the 16th of every month since January and none were ticked;
            // only October's, in the period the balance was entered in, still comes out.
            JsonNode account = account(period("2026-10-01"), checking);

            assertThat(account.get("outCents").asLong()).isEqualTo(113700);
        }
    }

    @Nested
    class CreditCards {
        @Test
        void aBillOnACardRaisesWhatIsOwedAndAPaymentLowersIt() throws Exception {
            enterBalance(checking, "2026-10-01", 121438);
            enterBalance(visa, "2026-10-01", 61248);
            created("/api/items", """
                    {"name":"Internet","kind":"bill","amountCents":7000,"payFromAccountId":%d,"autopay":true,
                     "schedule":{"frequency":"monthly","day1":10,"startDate":"2026-01-01"}}""".formatted(visa));
            item("Visa payment", "card_payment", 61248, monthly(13), "\"payToAccountId\":" + visa);

            JsonNode period = period("2026-10-01");

            JsonNode card = account(period, visa);
            assertThat(card.get("startingCents").asLong()).isEqualTo(61248);
            // For a card, "in" is what was charged to it and "out" is what was paid off.
            assertThat(card.get("inCents").asLong()).isEqualTo(7000);
            assertThat(card.get("outCents").asLong()).isEqualTo(61248);
            assertThat(card.get("endingCents").asLong()).isEqualTo(7000);
            // The internet bill never touches checking; the card payment does.
            assertThat(account(period, checking).get("outCents").asLong()).isEqualTo(61248);
        }
    }

    @Nested
    class NotAPeriod {
        @Test
        void aDateThatDoesNotOpenAPeriodIsNotFound() throws Exception {
            assertThat(server.get("/api/pay-periods/2026-10-02", cookie).status()).isEqualTo(404);
            assertThat(server.get("/api/pay-periods/1999-10-01", cookie).status()).isEqualTo(404);
            assertThat(server.get("/api/pay-periods/soon", cookie).status()).isEqualTo(400);
        }
    }
}
