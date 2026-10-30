package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.FilePart;
import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Statement uploads over real HTTP (Rachel, 2026-10-25): a card statement is read, its charges filed by category and
 * tag, and totals by pay period change only the budgets she points them at, replacing or adding. Every statement is
 * made up ({@link SampleStatements}). "Today" is 2026-10-01; pay periods run on the 1st and 15th.
 */
class StatementUploadsApiTest extends ApiTestBase {

    private static final FilePart CSV = new FilePart("september.csv", SampleStatements.septemberCsv());
    private static final FilePart LATE_CSV = new FilePart("late-september.csv", SampleStatements.lateSeptemberCsv());
    private static final String LAYOUT = """
            {"hasHeader":true,"dateColumn":0,"postedColumn":null,"descriptionColumn":1,"amountColumn":2,"debitColumn":null,
             "creditColumn":null,"dateOrder":"mdy","chargesNegative":false}""";

    long checking;
    long card;
    long groceries;
    long gas;
    long vacation;
    long food;
    long fuel;

    @BeforeEach
    void setUp() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        card = createAccount("Rewards Visa", "credit_card");
        createSalary(checking);
        groceries = created("/api/categories", "{\"name\":\"Groceries\",\"color\":\"#15803d\"}");
        gas = created("/api/categories", "{\"name\":\"Gas\",\"color\":\"#b45309\"}");
        vacation = created("/api/tags", "{\"name\":\"Vacation\",\"color\":\"#475569\"}");
        food = budget("Food for Amara's house", card, groceries);
        fuel = budget("Fuel", card, gas);
    }

    /** A $200 budget every pay period, dated on its payday. */
    private long budget(String name, long account, long category) throws Exception {
        return created("/api/items", """
                {"name":"%s","kind":"budget","amountCents":20000,"payFromAccountId":%d,"categoryId":%d,
                 "schedule":{"frequency":"every_pay_period","startDate":"2026-01-01"}}""".formatted(name, account, category));
    }

    private JsonNode uploaded(long account, FilePart file, String layout) throws Exception {
        Reply reply = server.upload("/api/accounts/" + account + "/statement-uploads", cookie,
                layout == null ? Map.of() : Map.of("layout", layout), file);
        assertThat(reply.status()).as(reply.body().toString()).isEqualTo(201);
        return reply.data();
    }

    private static JsonNode line(JsonNode upload, String description) {
        return StreamSupport.stream(upload.get("lines").spliterator(), false)
                .filter(line -> line.get("description").asText().equals(description))
                .findFirst()
                .orElseThrow();
    }

    private Reply file(long uploadId, JsonNode line, Long categoryId, List<Long> tagIds) throws Exception {
        return server.put("/api/statement-uploads/" + uploadId + "/lines/" + line.get("id").asLong(),
                "{\"categoryId\":%s,\"tagIds\":%s}".formatted(categoryId, tagIds), cookie);
    }

    private static JsonNode period(JsonNode changes, String start) {
        return StreamSupport.stream(changes.get("periods").spliterator(), false)
                .filter(period -> period.get("start").asText().equals(start))
                .findFirst()
                .orElseThrow();
    }

    private static JsonNode group(JsonNode period, String type, long id) {
        return StreamSupport.stream(period.get("groups").spliterator(), false)
                .filter(group -> group.get("type").asText().equals(type) && group.get("id").asLong() == id)
                .findFirst()
                .orElseThrow();
    }

    private Reply target(long uploadId, String period, String type, long groupId, Long itemId, String mode) throws Exception {
        return server.put("/api/statement-uploads/" + uploadId + "/targets", """
                {"periodStart":"%s","groupType":"%s","groupId":%d,"itemId":%s,"mode":"%s"}""".formatted(period, type, groupId, itemId, mode),
                cookie);
    }

    private Reply apply(long uploadId, String periods, String oneOffs) throws Exception {
        return server.post("/api/statement-uploads/" + uploadId + "/apply",
                "{\"periods\":[%s],\"oneOffLineIds\":[%s]}".formatted(periods, oneOffs), cookie);
    }

    /** The item's occurrence in the period starting {@code start}. */
    private JsonNode occurrence(long item, String start) throws Exception {
        for (JsonNode occurrence : server.get("/api/pay-periods/" + start, cookie).data().get("occurrences")) {
            if (occurrence.get("itemId").asLong() == item) {
                return occurrence;
            }
        }
        throw new AssertionError("The item is not in the period starting " + start);
    }

    /** The September CSV, uploaded with its layout and its Kroger and Shell lines filed. */
    private long filedSeptember() throws Exception {
        JsonNode upload = uploaded(card, CSV, LAYOUT);
        long id = upload.get("id").asLong();
        assertThat(file(id, line(upload, "KROGER #123"), groceries, List.of()).status()).isEqualTo(200);
        assertThat(file(id, line(upload, "SHELL OIL 5744"), gas, List.of(vacation)).status()).isEqualTo(200);
        return id;
    }

    @Test
    void aCsvAsksForItsColumnsOnceThenRemembersThem() throws Exception {
        Reply unasked = server.upload("/api/accounts/" + card + "/statement-uploads", cookie, CSV);
        assertThat(unasked.status()).isEqualTo(409);

        Reply columns = server.upload("/api/accounts/" + card + "/statement-uploads/columns", cookie, CSV);
        assertThat(columns.status()).isEqualTo(200);
        assertThat(columns.data().get("headers").toString()).isEqualTo("[\"Date\",\"Description\",\"Amount\"]");
        assertThat(columns.data().get("known").asBoolean()).isFalse();
        assertThat(columns.data().get("guess").get("amountColumn").asInt()).isEqualTo(2);
        assertThat(columns.data().get("guess").get("chargesNegative").asBoolean()).isFalse();
        assertThat(columns.data().get("sample")).hasSize(5);

        JsonNode upload = uploaded(card, CSV, LAYOUT);
        assertThat(upload.get("format").asText()).isEqualTo("csv");
        assertThat(upload.get("from").asText()).isEqualTo("2026-09-02");
        assertThat(upload.get("to").asText()).isEqualTo("2026-09-16");
        assertThat(upload.get("lines")).hasSize(5);
        JsonNode payment = line(upload, "PAYMENT THANK YOU");
        assertThat(payment.get("kind").asText()).isEqualTo("payment");
        assertThat(payment.get("leftOut").asBoolean()).isTrue();
        assertThat(payment.get("amountCents").asLong()).isEqualTo(-50000);
        assertThat(line(upload, "KROGER #123").get("periodStart").asText()).isEqualTo("2026-09-01");
        assertThat(line(upload, "CAFE LUNA").get("periodStart").asText()).isEqualTo("2026-09-15");

        // The next download from the same card is read without asking.
        assertThat(server.upload("/api/accounts/" + card + "/statement-uploads/columns", cookie, LATE_CSV).data().get("known").asBoolean())
                .isTrue();
        assertThat(uploaded(card, LATE_CSV, null).get("lines")).hasSize(3);
    }

    @Test
    void ofxAndPdfStatementsAreReadAsTheyAre() throws Exception {
        JsonNode ofx = uploaded(card, new FilePart("september.qfx", SampleStatements.septemberOfx()), null);
        assertThat(ofx.get("format").asText()).isEqualTo("ofx");
        assertThat(ofx.get("lines")).extracting(line -> line.get("amountCents").asLong()).containsExactly(5420L, -50000L, 3800L);
        assertThat(line(ofx, "KROGER #123").get("txnDate").asText()).isEqualTo("2026-09-02");
        assertThat(line(ofx, "KROGER #123").get("postedDate").asText()).isEqualTo("2026-09-03");

        long otherCard = createAccount("Store Card", "credit_card");
        JsonNode pdf = uploaded(otherCard, new FilePart("september.pdf", SampleStatements.septemberPdf()), null);
        assertThat(pdf.get("format").asText()).isEqualTo("pdf");
        assertThat(pdf.get("from").asText()).isEqualTo("2026-09-01");
        assertThat(pdf.get("to").asText()).isEqualTo("2026-09-30");
        assertThat(pdf.get("lines")).extracting(line -> line.get("description").asText())
                .containsExactly("PAYMENT THANK YOU", "KROGER #123", "SHELL OIL 5744");
        assertThat(pdf.get("lines")).extracting(line -> line.get("amountCents").asLong()).containsExactly(-50000L, 5420L, 3800L);
        long pdfId = pdf.get("id").asLong();
        assertThat(server.download("/api/statement-uploads/" + pdfId + "/file", null, cookie).status()).isEqualTo(200);
        assertThat(server.download("/api/statement-uploads/" + pdfId + "/preview?page=1", null, cookie).status()).isEqualTo(200);
        // A CSV or OFX file is not kept, so there is nothing to download.
        assertThat(server.download("/api/statement-uploads/" + ofx.get("id").asLong() + "/file", null, cookie).status()).isEqualTo(404);
    }

    @Test
    void filesThatAreNotStatementsAreRefusedKindly() throws Exception {
        String path = "/api/accounts/" + card + "/statement-uploads";
        Reply picture = server.upload(path, cookie, new FilePart("photo.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0}));
        assertThat(picture.status()).isEqualTo(415);
        Reply scan = server.upload(path, cookie, new FilePart("scan.pdf", SampleStatements.scannedPdf()));
        assertThat(scan.status()).isEqualTo(422);
        assertThat(scan.body().get("error").asText()).contains("scan");

        long brokerage = createAccount("Brokerage", "investment");
        assertThat(server.upload("/api/accounts/" + brokerage + "/statement-uploads", cookie, Map.of("layout", LAYOUT), CSV).status())
                .isEqualTo(400);
        assertThat(server.upload("/api/accounts/999/statement-uploads", cookie, CSV).status()).isEqualTo(404);
    }

    @Test
    void filingAPayeeFilesItsOtherChargesAndTheNextStatement() throws Exception {
        JsonNode upload = uploaded(card, CSV, LAYOUT);
        long id = upload.get("id").asLong();
        assertThat(line(upload, "KROGER #123").get("newPayee").asBoolean()).isTrue();

        Reply filed = file(id, line(upload, "KROGER #123"), groceries, List.of(vacation));
        assertThat(filed.status()).isEqualTo(200);
        // "KROGER #456" is the same payee, store number aside.
        assertThat(filed.data().get("alsoFiled").asInt()).isEqualTo(1);
        assertThat(line(filed.data().get("upload"), "KROGER #456").get("categoryId").asLong()).isEqualTo(groceries);

        JsonNode next = uploaded(card, LATE_CSV, null);
        JsonNode kroger = line(next, "KROGER #123");
        assertThat(kroger.get("categoryId").asLong()).isEqualTo(groceries);
        assertThat(kroger.get("tagIds").toString()).isEqualTo("[" + vacation + "]");
        assertThat(kroger.get("newPayee").asBoolean()).isFalse();
        // "KROGER RETURN" shares the first word with every Kroger filed, and they agree.
        assertThat(line(next, "KROGER RETURN").get("categoryId").asLong()).isEqualTo(groceries);
    }

    @Test
    void nothingChangesUntilAnItemIsChosen() throws Exception {
        long id = filedSeptember();
        JsonNode changes = server.get("/api/statement-uploads/" + id + "/changes", cookie).data();
        JsonNode first = period(changes, "2026-09-01");

        JsonNode groceryTotal = group(first, "category", groceries);
        assertThat(groceryTotal.get("totalCents").asLong()).isEqualTo(5420 + 2580);
        assertThat(groceryTotal.get("name").asText()).isEqualTo("Groceries");
        assertThat(groceryTotal.get("target").isNull()).isTrue();
        assertThat(groceryTotal.get("hintItemId").isNull()).isTrue();
        assertThat(group(first, "tag", vacation).get("totalCents").asLong()).isEqualTo(3800);
        assertThat(first.get("items")).extracting(item -> item.get("itemId").asLong()).containsExactlyInAnyOrder(food, fuel);
        assertThat(first.get("changes")).isEmpty();
        // The statement covers Sep 2–16 only, so the first period is partly covered.
        assertThat(first.get("coverage").get("partly").asBoolean()).isTrue();
        assertThat(occurrence(food, "2026-09-01").get("amountCents").asLong()).isEqualTo(20000);
    }

    @Test
    void replaceMakesTheBudgetThePeriodsTotalAndUndoPutsItBack() throws Exception {
        long id = filedSeptember();
        Reply chosen = target(id, "2026-09-01", "category", groceries, food, "replace");
        assertThat(chosen.status()).isEqualTo(200);
        JsonNode change = period(chosen.data(), "2026-09-01").get("changes").get(0);
        assertThat(change.get("itemId").asLong()).isEqualTo(food);
        assertThat(change.get("currentCents").asLong()).isEqualTo(20000);
        assertThat(change.get("newCents").asLong()).isEqualTo(8000);

        Reply applied = apply(id, "\"2026-09-01\"", "");
        assertThat(applied.status()).isEqualTo(200);
        assertThat(applied.data().get("amountsChanged").asInt()).isEqualTo(1);
        assertThat(period(applied.data().get("changes"), "2026-09-01").get("upToDate").asBoolean()).isTrue();

        JsonNode occurrence = occurrence(food, "2026-09-01");
        assertThat(occurrence.get("amountCents").asLong()).isEqualTo(8000);
        assertThat(occurrence.get("projectedCents").asLong()).isEqualTo(20000);
        assertThat(occurrence.get("dueDate").asText()).isEqualTo("2026-09-01");
        // Only the chosen period changed.
        assertThat(occurrence(food, "2026-09-15").get("amountCents").asLong()).isEqualTo(20000);

        assertThat(server.post("/api/undo", "", cookie).status()).isEqualTo(200);
        assertThat(occurrence(food, "2026-09-01").get("amountCents").asLong()).isEqualTo(20000);
    }

    @Test
    void addPutsTheTotalOnTopAndApplyingAgainNeverAddsTwice() throws Exception {
        long id = filedSeptember();
        target(id, "2026-09-01", "category", groceries, food, "add");
        apply(id, "\"2026-09-01\"", "");
        assertThat(occurrence(food, "2026-09-01").get("amountCents").asLong()).isEqualTo(28000);

        JsonNode again = period(server.get("/api/statement-uploads/" + id + "/changes", cookie).data(), "2026-09-01");
        assertThat(again.get("upToDate").asBoolean()).isTrue();
        apply(id, "\"2026-09-01\"", "");
        assertThat(occurrence(food, "2026-09-01").get("amountCents").asLong()).isEqualTo(28000);

        // Switched to Replace, it starts from nothing rather than from the amount Add made.
        target(id, "2026-09-01", "category", groceries, food, "replace");
        apply(id, "\"2026-09-01\"", "");
        assertThat(occurrence(food, "2026-09-01").get("amountCents").asLong()).isEqualTo(8000);
    }

    @Test
    void statementsOfOneAccountAddTogetherWithoutCountingARepeatTwice() throws Exception {
        long first = filedSeptember();
        JsonNode second = uploaded(card, LATE_CSV, null);
        // CAFE LUNA on the 16th was on the first statement too.
        assertThat(line(second, "CAFE LUNA").get("duplicateOf").isNull()).isFalse();
        long secondId = second.get("id").asLong();

        target(secondId, "2026-09-15", "category", groceries, food, "replace");
        JsonNode late = period(server.get("/api/statement-uploads/" + secondId + "/changes", cookie).data(), "2026-09-15");
        // $61.00 less the $10.00 return; the Kroger lines were filed from the first statement's payee.
        assertThat(late.get("changes").get(0).get("newCents").asLong()).isEqualTo(5100);
        // Together the two statements cover Sep 2–22 of Sep 15–30: still partly.
        assertThat(late.get("coverage").get("from").asText()).isEqualTo("2026-09-15");
        assertThat(late.get("coverage").get("to").asText()).isEqualTo("2026-09-22");

        // The first upload sees the same choice for the period it shares.
        JsonNode shared = period(server.get("/api/statement-uploads/" + first + "/changes", cookie).data(), "2026-09-15");
        assertThat(group(shared, "category", groceries).get("target").get("itemId").asLong()).isEqualTo(food);
    }

    @Test
    void onlyItemsPaidFromTheStatementsAccountCanBeChosen() throws Exception {
        long id = filedSeptember();
        long fromChecking = budget("Groceries by debit", checking, groceries);
        JsonNode first = period(server.get("/api/statement-uploads/" + id + "/changes", cookie).data(), "2026-09-01");
        assertThat(first.get("items")).extracting(item -> item.get("itemId").asLong()).doesNotContain(fromChecking);

        Reply refused = target(id, "2026-09-01", "category", groceries, fromChecking, "replace");
        assertThat(refused.status()).isEqualTo(400);
        assertThat(target(id, "2026-09-03", "category", groceries, food, "replace").status()).isEqualTo(400);
    }

    @Test
    void aChargeTwoItemsClaimWaitsForAChoice() throws Exception {
        long id = filedSeptember();
        long trip = budget("Trip money", card, groceries);
        target(id, "2026-09-01", "category", gas, fuel, "replace");
        target(id, "2026-09-01", "tag", vacation, trip, "replace");

        JsonNode first = period(server.get("/api/statement-uploads/" + id + "/changes", cookie).data(), "2026-09-01");
        assertThat(first.get("undecided")).hasSize(1);
        assertThat(apply(id, "\"2026-09-01\"", "").status()).isEqualTo(409);

        JsonNode upload = server.get("/api/statement-uploads/" + id, cookie).data();
        JsonNode shell = line(upload, "SHELL OIL 5744");
        Reply chosen = server.put("/api/statement-uploads/" + id + "/lines/" + shell.get("id").asLong(), """
                {"categoryId":%d,"tagIds":[%d],"countsToward":{"type":"tag","id":%d}}""".formatted(gas, vacation, vacation), cookie);
        assertThat(chosen.status()).isEqualTo(200);

        assertThat(apply(id, "\"2026-09-01\"", "").status()).isEqualTo(200);
        assertThat(occurrence(trip, "2026-09-01").get("amountCents").asLong()).isEqualTo(3800);
        assertThat(occurrence(fuel, "2026-09-01").get("amountCents").asLong()).isZero();

        // A choice must be one of the line's own groups.
        Reply wrong = server.put("/api/statement-uploads/" + id + "/lines/" + shell.get("id").asLong(), """
                {"categoryId":%d,"tagIds":[],"countsToward":{"type":"tag","id":%d}}""".formatted(gas, vacation), cookie);
        assertThat(wrong.status()).isEqualTo(400);
    }

    @Test
    void aChoiceIsSuggestedForTheNextPeriodButNeverMade() throws Exception {
        long id = filedSeptember();
        target(id, "2026-09-01", "category", groceries, food, "replace");
        JsonNode upload = server.get("/api/statement-uploads/" + id, cookie).data();
        file(id, line(upload, "CAFE LUNA"), groceries, List.of());

        JsonNode late = period(server.get("/api/statement-uploads/" + id + "/changes", cookie).data(), "2026-09-15");
        JsonNode cafe = group(late, "category", groceries);
        assertThat(cafe.get("hintItemId").asLong()).isEqualTo(food);
        assertThat(cafe.get("target").isNull()).isTrue();
        assertThat(late.get("changes")).isEmpty();
    }

    @Test
    void chargesNoItemTakesCanBeAddedAsOneOffs() throws Exception {
        long id = filedSeptember();
        JsonNode upload = server.get("/api/statement-uploads/" + id, cookie).data();
        long cafe = line(upload, "CAFE LUNA").get("id").asLong();
        JsonNode late = period(server.get("/api/statement-uploads/" + id + "/changes", cookie).data(), "2026-09-15");
        assertThat(late.get("unmatched")).extracting(JsonNode::asLong).containsExactly(cafe);

        Reply applied = apply(id, "", String.valueOf(cafe));
        assertThat(applied.status()).isEqualTo(200);
        assertThat(applied.data().get("oneOffsAdded").asInt()).isEqualTo(1);
        JsonNode entries = server.get("/api/entries?from=2026-09-16&to=2026-09-16", cookie).data();
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.get("kind").asText()).isEqualTo("money_out");
            assertThat(entry.get("amountCents").asLong()).isEqualTo(450);
            assertThat(entry.get("accountId").asLong()).isEqualTo(card);
        });
        // Now a one-off of its own, it counts toward no total and can't be added again.
        assertThat(line(server.get("/api/statement-uploads/" + id, cookie).data(), "CAFE LUNA").get("oneOffEntryId").isNull()).isFalse();
        assertThat(apply(id, "", String.valueOf(cafe)).status()).isEqualTo(400);
    }

    @Test
    void removingAnUploadLeavesWhatItChanged() throws Exception {
        long id = filedSeptember();
        target(id, "2026-09-01", "category", groceries, food, "replace");
        apply(id, "\"2026-09-01\"", "");
        assertThat(server.get("/api/accounts/" + card + "/statement-uploads", cookie).data()).hasSize(1);

        assertThat(server.delete("/api/statement-uploads/" + id, cookie).status()).isEqualTo(200);
        assertThat(server.get("/api/statement-uploads/" + id, cookie).status()).isEqualTo(404);
        assertThat(server.get("/api/accounts/" + card + "/statement-uploads", cookie).data()).isEmpty();
        assertThat(occurrence(food, "2026-09-01").get("amountCents").asLong()).isEqualTo(8000);
    }

    @Test
    void badRequestsAreRefusedKindly() throws Exception {
        long id = filedSeptember();
        assertThat(apply(id, "", "").status()).isEqualTo(400);
        assertThat(apply(id, "\"2026-08-01\"", "").status()).isEqualTo(400);
        assertThat(target(id, "2026-09-01", "category", 999, food, "replace").status()).isEqualTo(400);
        assertThat(target(id, "2026-09-01", "category", groceries, food, "sideways").status()).isEqualTo(400);
        JsonNode upload = server.get("/api/statement-uploads/" + id, cookie).data();
        assertThat(file(id, line(upload, "CAFE LUNA"), 999L, List.of()).status()).isEqualTo(400);
        assertThat(file(id, line(upload, "CAFE LUNA"), null, List.of(999L)).status()).isEqualTo(400);
        assertThat(server.put("/api/statement-uploads/" + id + "/lines/999999", "{}", cookie).status()).isEqualTo(404);
    }

    @Test
    void everyRouteNeedsTheAppUnlocked() throws Exception {
        long id = filedSeptember();
        String upload = "/api/statement-uploads/" + id;
        assertThat(server.get("/api/accounts/" + card + "/statement-uploads", null).status()).isEqualTo(401);
        assertThat(server.upload("/api/accounts/" + card + "/statement-uploads", null, CSV).status()).isEqualTo(401);
        assertThat(server.upload("/api/accounts/" + card + "/statement-uploads/columns", null, CSV).status()).isEqualTo(401);
        assertThat(server.get(upload, null).status()).isEqualTo(401);
        assertThat(server.delete(upload, null).status()).isEqualTo(401);
        assertThat(server.put(upload + "/lines/1", "{}", null).status()).isEqualTo(401);
        assertThat(server.get(upload + "/changes", null).status()).isEqualTo(401);
        assertThat(server.put(upload + "/targets", "{}", null).status()).isEqualTo(401);
        assertThat(server.post(upload + "/apply", "{}", null).status()).isEqualTo(401);
        assertThat(server.download(upload + "/file", null, null).status()).isEqualTo(401);
        assertThat(server.download(upload + "/preview", null, null).status()).isEqualTo(401);
    }
}
