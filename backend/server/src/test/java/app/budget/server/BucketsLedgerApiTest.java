package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Buckets, save-ups, savings interest and the low-balance warning as the pay periods show them,
 * over real HTTP against a temporary database.
 *
 * <p>"Today" is Thursday 2026-10-01; $2,486.20 arrives in checking on the 1st and 15th, so the
 * pay periods are Oct 1–14, Oct 15–31, Nov 1–14 and so on. Checking starts the day with
 * $1,214.38 and savings with $5,000.00.
 */
class BucketsLedgerApiTest extends ApiTestBase {

    long checking;
    long savings;
    long visa;

    @BeforeEach
    void addAccountsBalancesAndAPaycheck() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        savings = createAccount("Rainy Day Savings", "savings");
        visa = createAccount("Visa", "credit_card");
        createSalary(checking);
        enterBalance(checking, "2026-10-01", 121438);
        enterBalance(savings, "2026-10-01", 500000);
    }

    private long bucket(long accountId, String name, long startingCents) throws Exception {
        Reply reply = server.post("/api/buckets",
                "{\"accountId\":%d,\"name\":\"%s\",\"color\":\"#b45309\",\"startingCents\":%d}".formatted(accountId, name, startingCents),
                cookie);
        assertThat(reply.status()).isEqualTo(201);
        return reply.data().get("bucket").get("id").asLong();
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

    private static JsonNode bucketIn(JsonNode period, long bucketId) {
        return StreamSupport.stream(period.get("buckets").spliterator(), false)
                .filter(bucket -> bucket.get("bucketId").asLong() == bucketId)
                .findFirst()
                .orElseThrow();
    }

    /** The moves of one kind in a period, as "date amount" for easy reading. */
    private static List<String> moves(JsonNode period, String kind) {
        return StreamSupport.stream(period.get("bucketMoves").spliterator(), false)
                .filter(move -> move.get("kind").asText().equals(kind))
                .map(move -> move.get("date").asText() + " " + move.get("amountCents").asLong())
                .toList();
    }

    private void assertError(Reply reply, String message) {
        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.error()).isEqualTo(message);
    }

    @Nested
    class RecurringBucketMoves {
        long gifts;

        @BeforeEach
        void fiftyDollarsEveryPaycheck() throws Exception {
            gifts = bucket(checking, "Holiday gifts", 15000);
            created("/api/items", """
                    {"name":"Gift fund","kind":"bucket_move","amountCents":5000,"bucketId":%d,
                     "schedule":{"frequency":"every_pay_period","startDate":"2026-10-01"}}""".formatted(gifts));
        }

        @Test
        void theBalanceStaysPutButLessIsAvailable() throws Exception {
            JsonNode period = period("2026-10-01");

            JsonNode account = account(period, checking);
            assertThat(account.get("outCents").asLong()).isZero();
            assertThat(account.get("endingCents").asLong()).isEqualTo(370058);
            assertThat(account.get("bucketsCents").asLong()).isEqualTo(20000);
            assertThat(account.get("availableCents").asLong()).isEqualTo(350058);
        }

        @Test
        void theBucketGrowsEveryPayPeriod() throws Exception {
            JsonNode first = bucketIn(period("2026-10-01"), gifts);
            assertThat(first.get("accountId").asLong()).isEqualTo(checking);
            assertThat(first.get("startingCents").asLong()).isZero();
            assertThat(first.get("endingCents").asLong()).isEqualTo(20000);

            JsonNode second = bucketIn(period("2026-10-15"), gifts);
            assertThat(second.get("startingCents").asLong()).isEqualTo(20000);
            assertThat(second.get("endingCents").asLong()).isEqualTo(25000);
        }

        @Test
        void thePeriodListsWhatMovedTheBucket() throws Exception {
            JsonNode period = period("2026-10-01");

            assertThat(moves(period, "manual")).containsExactly("2026-10-01 15000");
            assertThat(moves(period, "bucket_move")).containsExactly("2026-10-01 5000");
            JsonNode occurrence = period.get("occurrences").get(0);
            assertThat(occurrence.get("name").asText()).isEqualTo("Gift fund");
            assertThat(occurrence.get("kind").asText()).isEqualTo("bucket_move");
            assertThat(occurrence.get("bucketId").asLong()).isEqualTo(gifts);
            // Setting money aside needs nobody to pay anything.
            assertThat(occurrence.get("autopay").asBoolean()).isTrue();
            assertThat(occurrence.get("payFromAccountId").asLong()).isEqualTo(checking);
        }

        @Test
        void aSkippedMoveSetsNothingAside() throws Exception {
            long item = server.get("/api/items", cookie).data().get(0).get("id").asLong();
            server.put("/api/items/" + item + "/occurrences/2026-10-15", "{\"skipped\":true}", cookie);

            assertThat(bucketIn(period("2026-10-15"), gifts).get("endingCents").asLong()).isEqualTo(20000);
        }

        @Test
        void thePeriodListShowsWhatIsAvailable() throws Exception {
            JsonNode periods = server.get("/api/pay-periods?from=2026-10-01&to=2026-10-31", cookie).data().get("periods");

            assertThat(periods.get(0).get("projectedEndingCents").asLong()).isEqualTo(370058);
            assertThat(periods.get(0).get("availableCents").asLong()).isEqualTo(350058);
            assertThat(periods.get(1).get("availableCents").asLong()).isEqualTo(618678 - 25000);
        }

        @Test
        void aBucketMoveNeedsABucket() throws Exception {
            assertError(server.post("/api/items", """
                    {"name":"Gift fund","kind":"bucket_move","amountCents":5000,"bucketId":999,
                     "schedule":{"frequency":"every_pay_period","startDate":"2026-10-01"}}""", cookie),
                    "Please choose the bucket this money goes into.");
        }
    }

    @Nested
    class BeforeABucketWasOpened {
        long emergency;

        /** A week into the pay period, on Oct 8, a bucket is added along with schedules that began before it. */
        @BeforeEach
        void addABucketPartWayThroughThePeriod() throws Exception {
            server.clock.advance(Duration.ofDays(7));
            cookie = server.unlock();
            long gifts = bucket(checking, "Holiday gifts", 15000);
            emergency = bucket(savings, "Emergency fund", 0);
            created("/api/items", """
                    {"name":"Gift fund","kind":"bucket_move","amountCents":5000,"bucketId":%d,
                     "schedule":{"frequency":"every_pay_period","startDate":"2026-10-15"}}""".formatted(gifts));
            created("/api/items", """
                    {"name":"To savings","kind":"transfer","amountCents":20000,"payFromAccountId":%d,"payToAccountId":%d,
                     "autopay":true,"bucketId":%d,"schedule":{"frequency":"monthly","day1":2,"startDate":"2026-01-01"}}"""
                    .formatted(checking, savings, emergency));
        }

        private long giftsBucket() throws Exception {
            return server.get("/api/buckets", cookie).data().get(0).get("id").asLong();
        }

        private String giftFundStarting(String startDate) throws Exception {
            return """
                    {"name":"Gift fund","kind":"bucket_move","amountCents":5000,"bucketId":%d,
                     "schedule":{"frequency":"every_pay_period","startDate":"%s"}}""".formatted(giftsBucket(), startDate);
        }

        @Test
        void aBucketMoveBackdatedToAnEarlierStartIsCountedFromThen() throws Exception {
            long item = server.get("/api/items", cookie).data().get(0).get("id").asLong();

            // No "effectiveFrom": the change is made today, yet it has to reach back to the new start.
            Reply reply = server.put("/api/items/" + item, giftFundStarting("2026-09-20"), cookie);

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.data().get("schedule").get("startDate").asText()).isEqualTo("2026-09-20");
            // The bucket counts from the payday of the period the move now starts in.
            assertThat(server.get("/api/buckets/" + giftsBucket(), cookie).data().get("bucket").get("openedOn").asText())
                    .isEqualTo("2026-09-15");
            JsonNode period = period("2026-10-01");
            assertThat(moves(period, "bucket_move")).containsExactly("2026-10-01 5000");
            // 150.00 set aside by hand, plus the Sep 15 and Oct 1 paychecks.
            assertThat(account(period, checking).get("bucketsCents").asLong()).isEqualTo(25000);
        }

        @Test
        void aNewBucketMoveThatStartsEarlierBringsItsBucketWithIt() throws Exception {
            long travel = bucket(checking, "Travel", 0);
            created("/api/items", """
                    {"name":"Travel fund","kind":"bucket_move","amountCents":2500,"bucketId":%d,
                     "schedule":{"frequency":"every_pay_period","startDate":"2026-10-08"}}""".formatted(travel));

            // Added on Oct 8, it still belongs to the paycheck in hand, dated Oct 1.
            assertThat(bucketIn(period("2026-10-01"), travel).get("endingCents").asLong()).isEqualTo(2500);
        }

        @Test
        void aStartMovedLaterTakesBackWhatWasScheduledBeforeIt() throws Exception {
            long travel = bucket(checking, "Travel", 0);
            String fund = """
                    {"name":"Travel fund","kind":"bucket_move","amountCents":2500,"bucketId":%d,
                     "schedule":{"frequency":"every_pay_period","startDate":"%s"}}""";
            long item = created("/api/items", fund.formatted(travel, "2026-10-08"));

            Reply reply = server.put("/api/items/" + item, fund.formatted(travel, "2026-11-01"), cookie);

            assertThat(reply.status()).isEqualTo(200);
            assertThat(bucketIn(period("2026-10-01"), travel).get("endingCents").asLong()).isZero();
            assertThat(bucketIn(period("2026-10-15"), travel).get("endingCents").asLong()).isZero();
            assertThat(bucketIn(period("2026-11-01"), travel).get("endingCents").asLong()).isEqualTo(2500);
        }

        @Test
        void aStartStillToComeWaitsForTheFirstPaydayOnOrAfterIt() throws Exception {
            long travel = bucket(checking, "Travel", 0);

            // Oct 21 is part-way through the Oct 15 pay period, whose payday will have gone by.
            Reply reply = server.post("/api/items", """
                    {"name":"Travel fund","kind":"bucket_move","amountCents":2500,"bucketId":%d,
                     "schedule":{"frequency":"every_pay_period","startDate":"2026-10-21"}}""".formatted(travel), cookie);

            assertThat(reply.status()).isEqualTo(201);
            assertThat(reply.data().get("schedule").get("startDate").asText()).isEqualTo("2026-11-01");
            assertThat(reply.data().get("createdOn").asText()).isEqualTo("2026-10-08");
            JsonNode october = period("2026-10-15");
            assertThat(october.get("occurrences").findValuesAsText("name")).doesNotContain("Travel fund");
            assertThat(bucketIn(october, travel).get("endingCents").asLong()).isZero();
            assertThat(bucketIn(period("2026-11-01"), travel).get("endingCents").asLong()).isEqualTo(2500);
        }

        @Test
        void aTransferNeverMovesTheDayItsBucketCountsFrom() throws Exception {
            assertThat(server.get("/api/buckets/" + emergency, cookie).data().get("bucket").get("openedOn").asText())
                    .isEqualTo("2026-10-08");
        }

        @Test
        void aBucketIsNeverBackFilledFromASchedule() throws Exception {
            JsonNode period = period("2026-10-01");

            // Only what was already set aside: the Oct 1 move was before the bucket existed.
            assertThat(account(period, checking).get("bucketsCents").asLong()).isEqualTo(15000);
            assertThat(moves(period, "bucket_move")).isEmpty();
            assertThat(period.get("occurrences").findValuesAsText("name")).containsExactly("To savings");
            assertThat(server.get("/api/buckets", cookie).data().get(0).get("balanceCents").asLong()).isEqualTo(15000);
        }

        @Test
        void aTransferFromBeforeStillArrivesButIntoNoBucket() throws Exception {
            JsonNode period = period("2026-10-01");

            assertThat(account(period, savings).get("endingCents").asLong()).isEqualTo(520000);
            assertThat(account(period, savings).get("bucketsCents").asLong()).isZero();
            assertThat(period.get("occurrences").get(0).get("bucketId").isNull()).isTrue();
        }

        @Test
        void fromTheNextPaydayOnTheSchedulesReachTheirBuckets() throws Exception {
            assertThat(account(period("2026-10-15"), checking).get("bucketsCents").asLong()).isEqualTo(20000);
            JsonNode november = period("2026-11-01");
            assertThat(bucketIn(november, emergency).get("endingCents").asLong()).isEqualTo(20000);
            assertThat(november.get("occurrences").findValuesAsText("name")).contains("Gift fund", "To savings");
        }

        @Test
        void countingFromAnEarlierDayBringsThisPaychecksTransferIn() throws Exception {
            Reply reply = server.put("/api/buckets/" + emergency,
                    "{\"name\":\"Emergency fund\",\"color\":\"#b45309\",\"openedOn\":\"2026-10-01\"}", cookie);
            assertThat(reply.status()).isEqualTo(200);

            JsonNode period = period("2026-10-01");

            assertThat(moves(period, "transfer")).containsExactly("2026-10-02 20000");
            assertThat(account(period, savings).get("bucketsCents").asLong()).isEqualTo(20000);
        }
    }

    @Nested
    class SavingTowardsAGoalByADate {

        @Test
        void aBucketThatStopsAtItsGoalTakesOnlyWhatItStillHasRoomFor() throws Exception {
            // 150.00 there already and 50.00 every paycheck: 200 on Oct 1, 250 on Oct 15, then full at 270.
            JsonNode bucket = giftsWithGoal("\"goalCents\":27000,\"stopAtGoal\":true");
            long id = bucket.get("id").asLong();
            assertThat(bucket.get("stopAtGoal").asBoolean()).isTrue();
            save50EveryPaycheck(id);

            // The move that reaches the goal is cut down to what was missing …
            JsonNode november = server.get("/api/pay-periods/2026-11-01", cookie).data();
            assertThat(november.get("occurrences").findValuesAsText("name")).contains("Gift fund");
            assertThat(november.get("occurrences").get(0).get("amountCents").asLong()).isEqualTo(2000);
            assertThat(november.get("buckets").get(0).get("endingCents").asLong()).isEqualTo(27000);
            // … and after that the move is not listed, and the bucket stays at its goal.
            JsonNode later = server.get("/api/pay-periods/2026-11-15", cookie).data();
            assertThat(later.get("occurrences").findValuesAsText("name")).doesNotContain("Gift fund");
            assertThat(later.get("buckets").get(0).get("endingCents").asLong()).isEqualTo(27000);
        }

        @Test
        void aBucketThatDoesNotStopKeepsTakingItsMoves() throws Exception {
            long id = giftsWithGoal("\"goalCents\":27000").get("id").asLong();
            save50EveryPaycheck(id);

            JsonNode later = server.get("/api/pay-periods/2026-11-15", cookie).data();
            assertThat(later.get("occurrences").findValuesAsText("name")).contains("Gift fund");
            assertThat(later.get("buckets").get(0).get("endingCents").asLong()).isEqualTo(35000);

            // Turning it on later is enough; leaving it out of a change keeps it as it was.
            server.put("/api/buckets/" + id,
                    "{\"name\":\"Holiday gifts\",\"color\":\"#b45309\",\"goalCents\":27000,\"stopAtGoal\":true}", cookie);
            server.put("/api/buckets/" + id, "{\"name\":\"Holiday gifts\",\"color\":\"#b45309\",\"goalCents\":27000}", cookie);
            assertThat(server.get("/api/buckets/" + id, cookie).data().get("bucket").get("stopAtGoal").asBoolean()).isTrue();
            assertThat(server.get("/api/pay-periods/2026-11-15", cookie).data().get("buckets").get(0).get("endingCents").asLong())
                    .isEqualTo(27000);
        }

        @Test
        void saysWhatAGoalWouldTakeBeforeItIsSaved() throws Exception {
            // A bucket that does not exist yet: 150.00 to start with, 600.00 wanted by Dec 1.
            Reply reply = server.get("/api/goal-pace?goalCents=60000&goalDate=2026-12-01&heldCents=15000", cookie);

            assertThat(reply.status()).isEqualTo(200);
            JsonNode pace = reply.data();
            assertThat(pace.get("projectedCents").asLong()).isEqualTo(15000);
            assertThat(pace.get("shortCents").asLong()).isEqualTo(45000);
            // Paydays from tomorrow: Oct 15, Nov 1, Nov 15, Dec 1.
            assertThat(pace.get("paydaysLeft").asInt()).isEqualTo(4);
            assertThat(pace.get("morePerPaycheckCents").asLong()).isEqualTo(11250);
            assertThat(pace.get("morePerMonthCents").asLong()).isEqualTo(22500);
        }

        @Test
        void countsWhatIsAlreadyScheduledForABucketThatExists() throws Exception {
            JsonNode bucket = giftsWithGoal("\"goalCents\":60000,\"goalDate\":\"2026-12-01\"");
            long id = bucket.get("id").asLong();
            long projected = bucket.get("goalProgress").get("projectedCents").asLong();

            // The same goal and date say the same as the bucket itself …
            JsonNode same = server.get("/api/goal-pace?goalCents=60000&goalDate=2026-12-01&bucketId=" + id, cookie).data();
            assertThat(same.get("projectedCents").asLong()).isEqualTo(projected);
            // … and a bigger goal typed into its form is short by that much more.
            JsonNode bigger = server.get("/api/goal-pace?goalCents=80000&goalDate=2026-12-01&bucketId=" + id, cookie).data();
            assertThat(bigger.get("shortCents").asLong()).isEqualTo(80000 - projected);
        }

        @Test
        void aGoalPaceNeedsASessionAGoalAndADate() throws Exception {
            assertThat(server.get("/api/goal-pace?goalCents=60000&goalDate=2026-12-01", null).status()).isEqualTo(401);
            assertThat(server.get("/api/goal-pace?goalDate=2026-12-01", cookie).status()).isEqualTo(400);
            assertThat(server.get("/api/goal-pace?goalCents=60000", cookie).status()).isEqualTo(400);
            assertThat(server.get("/api/goal-pace?goalCents=abc&goalDate=2026-12-01", cookie).status()).isEqualTo(400);
            assertThat(server.get("/api/goal-pace?goalCents=60000&goalDate=2026-12-01&bucketId=9999", cookie).status()).isEqualTo(404);
        }

        private JsonNode giftsWithGoal(String goalAndDate) throws Exception {
            Reply reply = server.post("/api/buckets",
                    "{\"accountId\":%d,\"name\":\"Holiday gifts\",\"color\":\"#b45309\",\"startingCents\":15000,%s}"
                            .formatted(checking, goalAndDate),
                    cookie);
            assertThat(reply.status()).isEqualTo(201);
            return reply.data().get("bucket");
        }

        private void save50EveryPaycheck(long bucketId) throws Exception {
            created("/api/items", """
                    {"name":"Gift fund","kind":"bucket_move","amountCents":5000,"bucketId":%d,
                     "schedule":{"frequency":"every_pay_period","startDate":"2026-10-01"}}""".formatted(bucketId));
        }

        @Test
        void saysHowFarShortItWillBeAndWhatClosingTheGapTakes() throws Exception {
            long gifts = giftsWithGoal("\"goalCents\":60000,\"goalDate\":\"2026-12-01\"").get("id").asLong();
            save50EveryPaycheck(gifts);

            JsonNode bucket = server.get("/api/buckets/" + gifts, cookie).data().get("bucket");

            assertThat(bucket.get("goalDate").asText()).isEqualTo("2026-12-01");
            JsonNode progress = bucket.get("goalProgress");
            // 150.00 there already, plus 50.00 on Oct 1, Oct 15, Nov 1, Nov 15 and Dec 1.
            assertThat(progress.get("projectedCents").asLong()).isEqualTo(40000);
            assertThat(progress.get("shortCents").asLong()).isEqualTo(20000);
            // Four paydays are still to come (today's has been counted), over two months.
            assertThat(progress.get("paydaysLeft").asInt()).isEqualTo(4);
            assertThat(progress.get("morePerPaycheckCents").asLong()).isEqualTo(5000);
            assertThat(progress.get("morePerMonthCents").asLong()).isEqualTo(10000);
        }

        @Test
        void isOnTrackWhenWhatIsScheduledGetsThere() throws Exception {
            long gifts = giftsWithGoal("\"goalCents\":40000,\"goalDate\":\"2026-12-01\"").get("id").asLong();
            save50EveryPaycheck(gifts);

            JsonNode progress = server.get("/api/buckets/" + gifts, cookie).data().get("bucket").get("goalProgress");

            assertThat(progress.get("shortCents").asLong()).isZero();
            assertThat(progress.get("morePerPaycheckCents").isNull()).isTrue();
            assertThat(progress.get("morePerMonthCents").isNull()).isTrue();
        }

        @Test
        void withNothingScheduledTheWholeGapIsSharedOut() throws Exception {
            JsonNode progress = giftsWithGoal("\"goalCents\":25000,\"goalDate\":\"2026-11-10\"").get("goalProgress");

            // 100.00 missing; paydays on Oct 15 and Nov 1; one month and a bit counts as two.
            assertThat(progress.get("shortCents").asLong()).isEqualTo(10000);
            assertThat(progress.get("morePerPaycheckCents").asLong()).isEqualTo(5000);
            assertThat(progress.get("morePerMonthCents").asLong()).isEqualTo(5000);
        }

        @Test
        void aGoalWithoutADateOrADateWithoutAGoalHasNoProgressToReport() throws Exception {
            JsonNode noDate = giftsWithGoal("\"goalCents\":60000");
            assertThat(noDate.get("goalDate").isNull()).isTrue();
            assertThat(noDate.get("goalProgress").isNull()).isTrue();

            Reply noGoal = server.post("/api/buckets",
                    "{\"accountId\":%d,\"name\":\"Travel\",\"color\":\"#b45309\",\"goalDate\":\"2026-12-01\"}".formatted(checking), cookie);
            assertThat(noGoal.data().get("bucket").get("goalDate").isNull()).isTrue();
        }

        @Test
        void aDateAlreadyGoneByReportsWhatItReachedAndAsksForNothingMore() throws Exception {
            JsonNode progress = giftsWithGoal("\"goalCents\":60000,\"goalDate\":\"2026-09-01\"").get("goalProgress");

            assertThat(progress.get("projectedCents").asLong()).isEqualTo(15000);
            assertThat(progress.get("shortCents").asLong()).isEqualTo(45000);
            assertThat(progress.get("paydaysLeft").asInt()).isZero();
            assertThat(progress.get("morePerPaycheckCents").isNull()).isTrue();
            assertThat(progress.get("morePerMonthCents").isNull()).isTrue();
        }
    }

    @Nested
    class BucketsCannotHoldMoreThanTheAccount {
        private static final String NO_ROOM =
                "That is more than this account has outside its buckets. Please enter a smaller amount, or update the account's balance first.";

        private Reply add(long accountId, String name, long startingCents) throws Exception {
            return server.post("/api/buckets",
                    "{\"accountId\":%d,\"name\":\"%s\",\"color\":\"#b45309\",\"startingCents\":%d}".formatted(accountId, name, startingCents),
                    cookie);
        }

        @Test
        void aNewBucketCannotStartWithMoreThanTheAccountHolds() throws Exception {
            Reply reply = add(savings, "Emergency fund", 500001);

            assertThat(reply.status()).isEqualTo(409);
            assertThat(reply.error()).isEqualTo(NO_ROOM);
            assertThat(server.get("/api/buckets", cookie).data()).isEmpty();
        }

        @Test
        void bucketsTogetherCanHoldAllOfItButNoMore() throws Exception {
            long emergency = bucket(savings, "Emergency fund", 300000);
            assertThat(add(savings, "House", 200001).status()).isEqualTo(409);
            assertThat(add(savings, "House", 200000).status()).isEqualTo(201);

            Reply more = server.post("/api/buckets/" + emergency + "/changes", "{\"date\":\"2026-10-01\",\"amountCents\":1}", cookie);

            assertThat(more.status()).isEqualTo(409);
            assertThat(more.error()).isEqualTo(NO_ROOM);
        }

        @Test
        void takingMoneyOutIsAlwaysAllowed() throws Exception {
            long emergency = bucket(savings, "Emergency fund", 500000);

            Reply reply = server.post("/api/buckets/" + emergency + "/changes", "{\"date\":\"2026-10-01\",\"amountCents\":-10000}", cookie);

            assertThat(reply.status()).isEqualTo(201);
        }
    }

    @Nested
    class TransfersIntoABucket {
        long emergency;

        @BeforeEach
        void addABucketInSavings() throws Exception {
            emergency = bucket(savings, "Emergency fund", 0);
        }

        private String transfer(long bucketId) {
            return """
                    {"name":"To savings","kind":"transfer","amountCents":20000,"payFromAccountId":%d,"payToAccountId":%d,
                     "autopay":true,"bucketId":%d,"schedule":{"frequency":"monthly","day1":2,"startDate":"2026-01-01"}}"""
                    .formatted(checking, savings, bucketId);
        }

        @Test
        void theMoneyArrivesInTheOtherAccountAndItsBucket() throws Exception {
            created("/api/items", transfer(emergency));

            JsonNode period = period("2026-10-01");

            assertThat(account(period, checking).get("outCents").asLong()).isEqualTo(20000);
            JsonNode inSavings = account(period, savings);
            assertThat(inSavings.get("endingCents").asLong()).isEqualTo(520000);
            assertThat(inSavings.get("bucketsCents").asLong()).isEqualTo(20000);
            assertThat(inSavings.get("availableCents").asLong()).isEqualTo(500000);
            assertThat(moves(period, "transfer")).containsExactly("2026-10-02 20000");
        }

        @Test
        void aBackdatedTransferFillsTheBucketOnlyFromTheAccountsFirstBalance() throws Exception {
            // The bucket counts from January, and the transfer has run since January too …
            server.put("/api/buckets/" + emergency,
                    "{\"name\":\"Emergency fund\",\"color\":\"#b45309\",\"openedOn\":\"2026-01-01\"}", cookie);
            created("/api/items", transfer(emergency));

            // … but savings only adds up from its first balance, on Oct 1. The bucket takes the
            // transfer from then as well, so it never holds money the account's balance does not show.
            JsonNode october = account(period("2026-10-01"), savings);
            assertThat(october.get("endingCents").asLong()).isEqualTo(520000);
            assertThat(october.get("bucketsCents").asLong()).isEqualTo(20000);

            // An earlier first balance builds both up together from that day.
            enterBalance(savings, "2026-07-31", 440000);
            JsonNode built = account(period("2026-10-01"), savings);
            // Aug 2 and Sep 2 before the Oct 1 balance, then Oct 2 after it.
            assertThat(built.get("bucketsCents").asLong()).isEqualTo(60000);
            assertThat(built.get("endingCents").asLong()).isEqualTo(520000);
        }

        @Test
        void theBucketHasToBeInTheAccountTheMoneyGoesTo() throws Exception {
            long gifts = bucket(checking, "Holiday gifts", 0);

            assertError(server.post("/api/items", transfer(gifts), cookie), "Please choose a bucket in the account this money goes to.");
        }
    }

    @Nested
    class SplittingAOneOff {
        long gifts;
        long emergency;

        @BeforeEach
        void addBuckets() throws Exception {
            gifts = bucket(checking, "Holiday gifts", 0);
            emergency = bucket(savings, "Emergency fund", 0);
        }

        private String bonus(String splits) {
            return "{\"kind\":\"money_in\",\"date\":\"2026-10-09\",\"amountCents\":100000,\"description\":\"Bonus\",\"accountId\":%d,\"bucketSplits\":%s}"
                    .formatted(checking, splits);
        }

        @Test
        void partOfMoneyComingInCanBeSetAside() throws Exception {
            Reply reply = server.post("/api/entries", bonus("[{\"bucketId\":%d,\"amountCents\":40000}]".formatted(gifts)), cookie);
            assertThat(reply.status()).isEqualTo(201);
            assertThat(reply.data().get("bucketSplits").get(0).get("bucketId").asLong()).isEqualTo(gifts);
            assertThat(reply.data().get("bucketSplits").get(0).get("amountCents").asLong()).isEqualTo(40000);

            JsonNode period = period("2026-10-01");
            JsonNode account = account(period, checking);
            assertThat(account.get("endingCents").asLong()).isEqualTo(470058);
            assertThat(account.get("bucketsCents").asLong()).isEqualTo(40000);
            assertThat(account.get("availableCents").asLong()).isEqualTo(430058);
            assertThat(moves(period, "split")).containsExactly("2026-10-09 40000");
        }

        @Test
        void aTransferCanBeSetAsideWhereItArrives() throws Exception {
            created("/api/entries", """
                    {"kind":"transfer","date":"2026-10-09","amountCents":30000,"description":"Tax refund to savings",
                     "accountId":%d,"toAccountId":%d,"bucketSplits":[{"bucketId":%d,"amountCents":30000}]}"""
                    .formatted(checking, savings, emergency));

            assertThat(account(period("2026-10-01"), savings).get("bucketsCents").asLong()).isEqualTo(30000);
        }

        @Test
        void changingTheEntryReplacesItsSplits() throws Exception {
            long id = created("/api/entries", bonus("[{\"bucketId\":%d,\"amountCents\":40000}]".formatted(gifts)));

            Reply reply = server.put("/api/entries/" + id, bonus("[]"), cookie);

            assertThat(reply.data().get("bucketSplits")).isEmpty();
            assertThat(account(period("2026-10-01"), checking).get("bucketsCents").asLong()).isZero();
        }

        @Test
        void explainsWhatIsWrongWithASplit() throws Exception {
            assertError(server.post("/api/entries", bonus("[{\"bucketId\":%d,\"amountCents\":100001}]".formatted(gifts)), cookie),
                    "That sets aside more than the amount itself. Please lower one of the buckets.");
            assertError(server.post("/api/entries", bonus("[{\"bucketId\":%d,\"amountCents\":40000}]".formatted(emergency)), cookie),
                    "Please choose buckets in the account this money goes into.");
            assertError(server.post("/api/entries", bonus("[{\"bucketId\":%d,\"amountCents\":0}]".formatted(gifts)), cookie),
                    "Please enter how much to set aside, or remove that bucket.");
            assertError(server.post("/api/entries",
                            bonus("[{\"bucketId\":%d,\"amountCents\":100},{\"bucketId\":%d,\"amountCents\":100}]".formatted(gifts, gifts)), cookie),
                    "Please choose each bucket once, from the list.");
            assertError(server.post("/api/entries",
                            bonus("[{\"bucketId\":%d,\"amountCents\":100}]".formatted(gifts)).replace("money_in", "money_out"), cookie),
                    "Only money coming in can be set aside in a bucket.");
        }
    }

    @Nested
    class SavingUpForABill {
        long insurance;
        long bill;

        @BeforeEach
        void sixHundredDollarsDueInDecember() throws Exception {
            insurance = bucket(checking, "Car insurance", 0);
            bill = created("/api/items", """
                    {"name":"Car insurance","kind":"bill","amountCents":60000,"payFromAccountId":%d,"saveUpBucketId":%d,
                     "schedule":{"frequency":"yearly","month":12,"day1":10,"startDate":"2026-01-01"}}"""
                    .formatted(checking, insurance));
        }

        @Test
        void aShareIsSetAsideOnEveryPaydayBeforeItIsDue() throws Exception {
            // Five paydays from today to Dec 10: Oct 1, Oct 15, Nov 1, Nov 15 and Dec 1.
            JsonNode october = period("2026-10-01");
            assertThat(moves(october, "save_up")).containsExactly("2026-10-01 12000");
            assertThat(bucketIn(october, insurance).get("endingCents").asLong()).isEqualTo(12000);
            assertThat(account(october, checking).get("availableCents").asLong()).isEqualTo(370058 - 12000);

            assertThat(bucketIn(period("2026-11-15"), insurance).get("endingCents").asLong()).isEqualTo(48000);
        }

        @Test
        void theMoneyLeavesTheBucketWhenTheBillIsPaid() throws Exception {
            JsonNode december = period("2026-12-01");

            assertThat(moves(december, "save_up")).containsExactly("2026-12-01 12000");
            assertThat(moves(december, "save_up_paid")).containsExactly("2026-12-10 -60000");
            JsonNode bucket = bucketIn(december, insurance);
            assertThat(bucket.get("startingCents").asLong()).isEqualTo(48000);
            assertThat(bucket.get("endingCents").asLong()).isZero();
            assertThat(account(december, checking).get("outCents").asLong()).isEqualTo(60000);
        }

        @Test
        void thenSavingStartsAgainForNextYear() throws Exception {
            // 24 paydays from Dec 15, 2026 to Dec 1, 2027.
            JsonNode afterwards = period("2026-12-15");

            assertThat(moves(afterwards, "save_up")).containsExactly("2026-12-15 2500");
            assertThat(bucketIn(afterwards, insurance).get("endingCents").asLong()).isEqualTo(2500);
        }

        @Test
        void theItemSaysWhatEachPaycheckSetsAside() throws Exception {
            JsonNode item = server.get("/api/items/" + bill, cookie).data();

            assertThat(item.get("saveUpBucketId").asLong()).isEqualTo(insurance);
            assertThat(item.get("saveUpPerPaycheckCents").asLong()).isEqualTo(12000);
        }

        @Test
        void aTypedOverAmountIsSharedOutInstead() throws Exception {
            server.put("/api/items/" + bill + "/occurrences/2026-12-10", "{\"amountCents\":65000}", cookie);

            assertThat(moves(period("2026-10-01"), "save_up")).containsExactly("2026-10-01 13000");
        }

        @Test
        void aSkippedBillIsNotSavedUpFor() throws Exception {
            server.put("/api/items/" + bill + "/occurrences/2026-12-10", "{\"skipped\":true}", cookie);

            assertThat(moves(period("2026-12-01"), "save_up_paid")).isEmpty();
            // The paydays go towards next year's instead: 29 of them from today.
            assertThat(moves(period("2026-10-01"), "save_up")).containsExactly("2026-10-01 2069");
        }

        @Test
        void theSaveUpBucketHasToBeInTheAccountTheBillIsPaidFrom() throws Exception {
            long emergency = bucket(savings, "Emergency fund", 0);

            assertError(server.post("/api/items", """
                    {"name":"Renters insurance","kind":"bill","amountCents":18000,"payFromAccountId":%d,"saveUpBucketId":%d,
                     "schedule":{"frequency":"yearly","month":6,"day1":1,"startDate":"2026-01-01"}}"""
                    .formatted(checking, emergency), cookie),
                    "Please choose a save-up bucket in the account this is paid from.");
        }
    }

    @Nested
    class SavingsInterest {
        private Reply addRate(long accountId, int rateBp, String from) throws Exception {
            return server.post("/api/accounts/" + accountId + "/rates",
                    "{\"rateBp\":%d,\"effectiveDate\":\"%s\"}".formatted(rateBp, from), cookie);
        }

        private Reply interest(String body) throws Exception {
            return server.put("/api/accounts/" + savings + "/interest", body, cookie);
        }

        @Test
        void theRoutesRequireASession() throws Exception {
            assertThat(server.put("/api/accounts/" + savings + "/interest", "{\"creditDay\":31,\"destination\":\"unassigned\"}", null).status())
                    .isEqualTo(401);
            assertThat(server.post("/api/accounts/" + savings + "/rates", "{\"rateBp\":410,\"effectiveDate\":\"2026-01-01\"}", null).status())
                    .isEqualTo(401);
            assertThat(server.delete("/api/accounts/" + savings + "/rates/1", null).status()).isEqualTo(401);
        }

        @Test
        void anAccountStartsWithInterestOnTheLastDayGoingNowhereInParticular() throws Exception {
            JsonNode detail = server.get("/api/accounts/" + savings, cookie).data();

            assertThat(detail.get("interest").get("creditDay").asInt()).isEqualTo(31);
            assertThat(detail.get("interest").get("destination").asText()).isEqualTo("unassigned");
            assertThat(detail.get("rates")).isEmpty();
        }

        @Test
        void aRateIsKeptWithTheDateItStartsFrom() throws Exception {
            Reply reply = addRate(savings, 410, "2026-01-01");

            assertThat(reply.status()).isEqualTo(201);
            JsonNode rate = reply.data().get("rates").get(0);
            assertThat(rate.get("kind").asText()).isEqualTo("apy");
            assertThat(rate.get("rateBp").asInt()).isEqualTo(410);
            assertThat(rate.get("effectiveDate").asText()).isEqualTo("2026-01-01");
            // A card's rate is an APR; nobody has to say so.
            assertThat(addRate(visa, 2499, "2026-01-01").data().get("rates").get(0).get("kind").asText()).isEqualTo("apr");
        }

        @Test
        void theSameDateAgainReplacesTheRateAndARateCanBeRemoved() throws Exception {
            addRate(savings, 410, "2026-01-01");
            Reply replaced = addRate(savings, 425, "2026-01-01");
            assertThat(replaced.data().get("rates")).hasSize(1);
            assertThat(replaced.data().get("rates").get(0).get("rateBp").asInt()).isEqualTo(425);

            long rateId = replaced.data().get("rates").get(0).get("id").asLong();
            Reply removed = server.delete("/api/accounts/" + savings + "/rates/" + rateId, cookie);
            assertThat(removed.status()).isEqualTo(200);
            assertThat(removed.data().get("rates")).isEmpty();
            assertThat(server.delete("/api/accounts/" + savings + "/rates/" + rateId, cookie).status()).isEqualTo(404);
        }

        @Test
        void explainsWhatIsWrongWithARate() throws Exception {
            assertError(server.post("/api/accounts/" + savings + "/rates", "{\"effectiveDate\":\"2026-01-01\"}", cookie),
                    "Please enter a rate between 0% and 1,000%.");
            assertError(addRate(savings, -1, "2026-01-01"), "Please enter a rate between 0% and 1,000%.");
            assertError(server.post("/api/accounts/" + savings + "/rates", "{\"rateBp\":410}", cookie),
                    "Please choose the date this rate starts from.");
            assertThat(addRate(999, 410, "2026-01-01").status()).isEqualTo(404);
        }

        @Test
        void interestIsProjectedOnTheCreditDayAsMoneyIn() throws Exception {
            addRate(savings, 410, "2026-01-01");

            assertThat(period("2026-10-01").get("interest")).isEmpty();
            JsonNode period = period("2026-10-15");
            // 5,000.00 × ((1.041)^(1/12) − 1) = 16.77
            JsonNode line = period.get("interest").get(0);
            assertThat(line.get("accountId").asLong()).isEqualTo(savings);
            assertThat(line.get("date").asText()).isEqualTo("2026-10-31");
            assertThat(line.get("amountCents").asLong()).isEqualTo(1677);
            assertThat(line.get("rateBp").asInt()).isEqualTo(410);
            JsonNode account = account(period, savings);
            assertThat(account.get("inCents").asLong()).isEqualTo(1677);
            assertThat(account.get("endingCents").asLong()).isEqualTo(501677);
        }

        @Test
        void theCreditDayCanBeChanged() throws Exception {
            addRate(savings, 410, "2026-01-01");

            assertThat(interest("{\"creditDay\":10,\"destination\":\"unassigned\"}").status()).isEqualTo(200);

            assertThat(period("2026-10-01").get("interest").get(0).get("date").asText()).isEqualTo("2026-10-10");
        }

        @Test
        void interestCanGoIntoOneBucket() throws Exception {
            addRate(savings, 410, "2026-01-01");
            long emergency = bucket(savings, "Emergency fund", 0);

            Reply reply = interest("{\"creditDay\":31,\"destination\":\"bucket\",\"destinationBucketId\":%d}".formatted(emergency));
            assertThat(reply.data().get("interest").get("destinationBucketId").asLong()).isEqualTo(emergency);

            JsonNode period = period("2026-10-15");
            assertThat(moves(period, "interest")).containsExactly("2026-10-31 1677");
            assertThat(bucketIn(period, emergency).get("endingCents").asLong()).isEqualTo(1677);
            // All of it is spoken for, so no more is available than before.
            assertThat(account(period, savings).get("availableCents").asLong()).isEqualTo(500000);
        }

        @Test
        void interestCanFollowWhatEachBucketHolds() throws Exception {
            addRate(savings, 410, "2026-01-01");
            long emergency = bucket(savings, "Emergency fund", 250000);

            interest("{\"creditDay\":31,\"destination\":\"proportional\"}");

            // Half the account is in the bucket, so half the interest is: 8.385 rounds to the even cent.
            assertThat(bucketIn(period("2026-10-15"), emergency).get("endingCents").asLong()).isEqualTo(250838);
        }

        @Test
        void explainsWhatIsWrongWithInterestSettings() throws Exception {
            long gifts = bucket(checking, "Holiday gifts", 0);

            assertError(interest("{\"creditDay\":0,\"destination\":\"unassigned\"}"), "Please choose the day of the month interest is credited.");
            assertError(interest("{\"creditDay\":32,\"destination\":\"unassigned\"}"), "Please choose the day of the month interest is credited.");
            assertError(interest("{\"creditDay\":31,\"destination\":\"somewhere\"}"), "Please choose where the interest goes from the list.");
            assertError(interest("{\"creditDay\":31,\"destination\":\"bucket\"}"), "Please choose one of this account's buckets for the interest.");
            assertError(interest("{\"creditDay\":31,\"destination\":\"bucket\",\"destinationBucketId\":%d}".formatted(gifts)),
                    "Please choose one of this account's buckets for the interest.");
            assertError(server.put("/api/accounts/" + visa + "/interest", "{\"creditDay\":31,\"destination\":\"unassigned\"}", cookie),
                    "Interest settings are for checking and savings accounts.");
            assertThat(server.put("/api/accounts/999/interest", "{\"creditDay\":31,\"destination\":\"unassigned\"}", cookie).status())
                    .isEqualTo(404);
        }
    }

    @Nested
    class LowBalanceWarning {
        private Reply setThreshold(long accountId, String type, String threshold) throws Exception {
            return server.put("/api/accounts/" + accountId,
                    "{\"name\":\"Account\",\"type\":\"%s\",\"color\":\"#0f766e\",\"lowBalanceThresholdCents\":%s}".formatted(type, threshold),
                    cookie);
        }

        @Test
        void nothingIsFlaggedUntilTheUserAsks() throws Exception {
            assertThat(account(period("2026-10-01"), checking).get("lowBalance").asBoolean()).isFalse();
        }

        @Test
        void aPeriodIsFlaggedWhenTheBalanceDipsBelowTheAmount() throws Exception {
            Reply reply = setThreshold(checking, "checking", "150000");
            assertThat(reply.data().get("account").get("lowBalanceThresholdCents").asLong()).isEqualTo(150000);

            // Checking starts Oct 1 with 1,214.38, below 1,500.00; from payday on it is well above.
            assertThat(account(period("2026-10-01"), checking).get("lowBalance").asBoolean()).isTrue();
            assertThat(account(period("2026-10-15"), checking).get("lowBalance").asBoolean()).isFalse();
            JsonNode periods = server.get("/api/pay-periods?from=2026-10-01&to=2026-10-31", cookie).data().get("periods");
            assertThat(periods.get(0).get("lowBalance").asBoolean()).isTrue();
            assertThat(periods.get(1).get("lowBalance").asBoolean()).isFalse();
        }

        @Test
        void theWarningCanBeSwitchedOffAgain() throws Exception {
            setThreshold(checking, "checking", "150000");

            Reply reply = setThreshold(checking, "checking", "null");

            assertThat(reply.data().get("account").get("lowBalanceThresholdCents").isNull()).isTrue();
            assertThat(account(period("2026-10-01"), checking).get("lowBalance").asBoolean()).isFalse();
        }

        @Test
        void onlyCheckingAndSavingsCanHaveOne() throws Exception {
            assertError(setThreshold(visa, "credit_card", "150000"), "A low-balance warning is for checking and savings accounts.");
            assertError(setThreshold(checking, "checking", "-1"), "The low-balance amount can't be less than zero.");
        }
    }

    @Nested
    class AccountProjection {
        @Test
        void requiresASession() throws Exception {
            assertThat(server.get("/api/accounts/" + checking + "/projection", null).status()).isEqualTo(401);
        }

        @Test
        void followsTheAccountThroughThePayPeriodsAhead() throws Exception {
            long gifts = bucket(checking, "Holiday gifts", 15000);

            JsonNode points = server.get("/api/accounts/" + checking + "/projection", cookie).data();

            // The current period through twelve months ahead, twice a month.
            assertThat(points.size()).isGreaterThanOrEqualTo(24);
            JsonNode first = points.get(0);
            assertThat(first.get("start").asText()).isEqualTo("2026-10-01");
            assertThat(first.get("end").asText()).isEqualTo("2026-10-14");
            assertThat(first.get("endingCents").asLong()).isEqualTo(370058);
            assertThat(first.get("lowestCents").asLong()).isEqualTo(121438);
            assertThat(first.get("availableCents").asLong()).isEqualTo(355058);
            assertThat(points.get(1).get("endingCents").asLong()).isEqualTo(618678);
            assertThat(gifts).isPositive();
        }

        @Test
        void isEmptyUntilABalanceIsEntered() throws Exception {
            assertThat(server.get("/api/accounts/" + visa + "/projection", cookie).data()).isEmpty();
        }

        @Test
        void anUnknownAccountIsNotFound() throws Exception {
            assertThat(server.get("/api/accounts/999/projection", cookie).status()).isEqualTo(404);
        }
    }

    @Nested
    class AccountsWithBuckets {
        @Test
        void anAccountHoldingBucketsCannotBecomeACard() throws Exception {
            bucket(checking, "Holiday gifts", 0);

            Reply reply = server.put("/api/accounts/" + checking, "{\"name\":\"Everyday\",\"type\":\"credit_card\",\"color\":\"#0f766e\"}", cookie);

            assertThat(reply.status()).isEqualTo(409);
            assertThat(reply.error()).isEqualTo(
                    "This account has buckets, which only checking and savings accounts can hold. Remove the buckets first.");
        }
    }
}
