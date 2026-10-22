package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Archiving as of a day (Rachel, 2026-10-22: "I archived a bucket once it was empty and it basically removed all the
 * transactions and bucket balance from previous pay periods ... the previous activity needs to remain"; "this should
 * be true for all things that can be archived"). Before the day it was archived as of, a bucket, recurring item or
 * account counts as it did; from that day on it is put away. Paychecks on the 1st and 15th; "today" is 2026-10-01.
 */
class ArchivingApiTest extends ApiTestBase {

    long checking;

    @BeforeEach
    void aCheckingAccountAndAPaycheck() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        createSalary(checking);
        enterBalance(checking, "2026-09-01", 300000);
    }

    private JsonNode period(String start) throws Exception {
        Reply reply = server.get("/api/pay-periods/" + start, cookie);
        assertThat(reply.status()).isEqualTo(200);
        return reply.data();
    }

    private static List<JsonNode> list(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).toList();
    }

    private static List<Long> ids(JsonNode array, String field) {
        return list(array).stream().map(node -> node.get(field).asLong()).toList();
    }

    private long bucketHoldingFiftyDollarsUntilSeptember20() throws Exception {
        long gifts = server.post("/api/buckets",
                "{\"accountId\":%d,\"name\":\"Holiday gifts\",\"color\":\"#b45309\",\"openedOn\":\"2026-09-01\"}".formatted(checking),
                cookie).data().get("bucket").get("id").asLong();
        server.post("/api/buckets/" + gifts + "/changes", "{\"date\":\"2026-09-02\",\"amountCents\":5000}", cookie);
        server.post("/api/buckets/" + gifts + "/changes", "{\"date\":\"2026-09-20\",\"amountCents\":-5000}", cookie);
        return gifts;
    }

    private Reply archiveBucket(long id, String archivedOn) throws Exception {
        String day = archivedOn == null ? "" : ",\"archivedOn\":\"" + archivedOn + "\"";
        return server.put("/api/buckets/" + id, "{\"name\":\"Holiday gifts\",\"color\":\"#b45309\",\"archived\":true%s}".formatted(day), cookie);
    }

    @Test
    void anArchivedBucketKeepsItsHistoryInThePeriodsBefore() throws Exception {
        long gifts = bucketHoldingFiftyDollarsUntilSeptember20();

        Reply archived = archiveBucket(gifts, "2026-09-25");

        assertThat(archived.status()).isEqualTo(200);
        assertThat(archived.data().get("bucket").get("archivedOn").asText()).isEqualTo("2026-09-25");
        JsonNode september = list(period("2026-09-01").get("buckets")).stream()
                .filter(bucket -> bucket.get("bucketId").asLong() == gifts).findFirst().orElseThrow();
        assertThat(september.get("endingCents").asLong()).isEqualTo(5000);
        assertThat(ids(period("2026-09-15").get("bucketMoves"), "bucketId")).contains(gifts);
        // Gone from the period after it was archived.
        assertThat(ids(period("2026-10-01").get("buckets"), "bucketId")).doesNotContain(gifts);
    }

    @Test
    void anArchivedBucketLetsGoOfWhatIsLeftOnTheDayItWasArchivedAsOf() throws Exception {
        long gifts = server.post("/api/buckets",
                "{\"accountId\":%d,\"name\":\"Holiday gifts\",\"color\":\"#b45309\",\"openedOn\":\"2026-09-01\"}".formatted(checking),
                cookie).data().get("bucket").get("id").asLong();
        server.post("/api/buckets/" + gifts + "/changes", "{\"date\":\"2026-09-02\",\"amountCents\":5000}", cookie);

        archiveBucket(gifts, "2026-09-20");

        JsonNode closing = list(period("2026-09-15").get("buckets")).stream()
                .filter(bucket -> bucket.get("bucketId").asLong() == gifts).findFirst().orElseThrow();
        assertThat(closing.get("startingCents").asLong()).isEqualTo(5000);
        assertThat(closing.get("endingCents").asLong()).isZero();
    }

    @Test
    void archivingIsAsOfTodayUnlessADayIsChosenAndBringingItBackClearsIt() throws Exception {
        long gifts = bucketHoldingFiftyDollarsUntilSeptember20();

        assertThat(archiveBucket(gifts, null).data().get("bucket").get("archivedOn").asText()).isEqualTo("2026-10-01");
        // Saved again without a day, it keeps the one it has.
        archiveBucket(gifts, "2026-09-25");
        assertThat(archiveBucket(gifts, null).data().get("bucket").get("archivedOn").asText()).isEqualTo("2026-09-25");

        Reply back = server.put("/api/buckets/" + gifts, "{\"name\":\"Holiday gifts\",\"color\":\"#b45309\",\"archived\":false}", cookie);
        assertThat(back.data().get("bucket").get("archived").asBoolean()).isFalse();
        assertThat(back.data().get("bucket").get("archivedOn").isNull()).isTrue();
    }

    @Test
    void theDayIsTodayOrEarlierAndNotBeforeTheBucketCounts() throws Exception {
        long gifts = bucketHoldingFiftyDollarsUntilSeptember20();

        Reply ahead = archiveBucket(gifts, "2026-10-02");
        assertThat(ahead.status()).isEqualTo(400);
        assertThat(ahead.error()).isEqualTo("Please choose today or an earlier day to archive it as of.");
        Reply early = archiveBucket(gifts, "2026-08-31");
        assertThat(early.status()).isEqualTo(400);
        assertThat(early.error()).isEqualTo("Please choose a day on or after the day it counts from to archive it as of.");
    }

    @Test
    void anEntryFromBeforeABucketWasArchivedCanStillNameIt() throws Exception {
        long gifts = bucketHoldingFiftyDollarsUntilSeptember20();
        archiveBucket(gifts, "2026-09-25");
        String entry = """
                {"kind":"money_in","date":"%s","amountCents":2000,"description":"Birthday money","accountId":%d,
                 "bucketSplits":[{"bucketId":%d,"amountCents":2000}]}""";

        assertThat(server.post("/api/entries", entry.formatted("2026-09-22", checking, gifts), cookie).status()).isEqualTo(201);
        assertThat(server.post("/api/entries", entry.formatted("2026-09-26", checking, gifts), cookie).status()).isEqualTo(400);
    }

    @Test
    void anArchivedItemKeepsWhatCameBeforeTheDayItWasArchivedAsOf() throws Exception {
        String item = """
                {"name":"Rent","kind":"bill","amountCents":150000,"payFromAccountId":%d,"autopay":true,%s
                 "schedule":{"frequency":"monthly","day1":5,"startDate":"2026-01-01"}}""";
        long rent = created("/api/items", item.formatted(checking, ""));

        Reply archived = server.put("/api/items/" + rent, item.formatted(checking, "\"archived\":true,\"archivedOn\":\"2026-09-20\","), cookie);

        assertThat(archived.status()).isEqualTo(200);
        assertThat(archived.data().get("archivedOn").asText()).isEqualTo("2026-09-20");
        assertThat(ids(period("2026-09-01").get("occurrences"), "itemId")).contains(rent);
        assertThat(ids(period("2026-10-01").get("occurrences"), "itemId")).doesNotContain(rent);
    }

    @Test
    void anArchivedAccountIsListedInThePeriodsBefore() throws Exception {
        long savings = createAccount("Rainy Day Savings", "savings");
        enterBalance(savings, "2026-09-01", 500000);

        Reply archived = server.put("/api/accounts/" + savings,
                "{\"name\":\"Rainy Day Savings\",\"type\":\"savings\",\"color\":\"#0f766e\",\"archived\":true,\"archivedOn\":\"2026-09-20\"}",
                cookie);

        assertThat(archived.status()).isEqualTo(200);
        assertThat(archived.data().get("account").get("archivedOn").asText()).isEqualTo("2026-09-20");
        assertThat(ids(period("2026-09-15").get("accounts"), "accountId")).contains(savings);
        assertThat(ids(period("2026-10-01").get("accounts"), "accountId")).doesNotContain(savings);
    }

    @Test
    void anAssetIsArchivedAsOfADayToo() throws Exception {
        long home = server.post("/api/assets",
                "{\"name\":\"Home\",\"kind\":\"home\",\"color\":\"#0f766e\",\"valueCents\":30000000,\"valueDate\":\"2026-01-01\"}", cookie)
                .data().get("asset").get("id").asLong();

        Reply archived = server.put("/api/assets/" + home,
                "{\"name\":\"Home\",\"kind\":\"home\",\"color\":\"#0f766e\",\"archived\":true,\"archivedOn\":\"2026-09-20\"}", cookie);

        assertThat(archived.status()).isEqualTo(200);
        assertThat(archived.data().get("asset").get("archivedOn").asText()).isEqualTo("2026-09-20");
        assertThat(server.put("/api/assets/" + home, "{\"name\":\"Home\",\"kind\":\"home\",\"color\":\"#0f766e\",\"archived\":true}", null)
                .status()).isEqualTo(401);
    }
}
