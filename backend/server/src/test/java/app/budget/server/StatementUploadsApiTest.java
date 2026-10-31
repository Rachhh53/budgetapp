package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.FilePart;
import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Statement uploads over real HTTP (Rachel, 2026-10-25, made simpler 2026-10-30): a card statement is read, each charge
 * is put on a budget, matched to what is already in Budget, or chosen to be a one-off, and nothing changes in the pay
 * periods until Apply. A budget becomes what was spent plus what is still to come. Every statement is made up
 * ({@link SampleStatements}). "Today" is 2026-10-01; pay periods run on the 1st and 15th.
 */
class StatementUploadsApiTest extends ApiTestBase {

    private static final FilePart CSV = new FilePart("september.csv", SampleStatements.septemberCsv());
    private static final FilePart LATE_CSV = new FilePart("late-september.csv", SampleStatements.lateSeptemberCsv());
    /** Says it covers all of September, so a budget date in it is fully covered. */
    private static final FilePart OFX = new FilePart("september.qfx", SampleStatements.septemberOfx());
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

    private long bill(String name, long cents, int day) throws Exception {
        return created("/api/items", """
                {"name":"%s","kind":"bill","amountCents":%d,"payFromAccountId":%d,
                 "schedule":{"frequency":"monthly","day1":%d,"startDate":"2026-01-01"}}""".formatted(name, cents, card, day));
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

    private static long id(JsonNode upload, String description) {
        return line(upload, description).get("id").asLong();
    }

    private Reply file(long uploadId, JsonNode line, Long categoryId, List<Long> tagIds) throws Exception {
        return server.put("/api/statement-uploads/" + uploadId + "/lines/" + line.get("id").asLong(),
                "{\"categoryId\":%s,\"tagIds\":%s}".formatted(categoryId, tagIds), cookie);
    }

    private Reply putOn(long uploadId, Long itemId, long... lineIds) throws Exception {
        String ids = java.util.Arrays.stream(lineIds).mapToObj(Long::toString).collect(Collectors.joining(","));
        return server.put("/api/statement-uploads/" + uploadId + "/budget", "{\"lineIds\":[%s],\"itemId\":%s}".formatted(ids, itemId), cookie);
    }

    private JsonNode changes(long uploadId) throws Exception {
        return server.get("/api/statement-uploads/" + uploadId + "/changes", cookie).data();
    }

    private static JsonNode period(JsonNode changes, String start) {
        return StreamSupport.stream(changes.get("periods").spliterator(), false)
                .filter(period -> period.get("start").asText().equals(start))
                .findFirst()
                .orElseThrow();
    }

    private static JsonNode change(JsonNode period, long itemId, String date) {
        return StreamSupport.stream(period.get("changes").spliterator(), false)
                .filter(change -> change.get("itemId").asLong() == itemId && change.get("scheduledDate").asText().equals(date))
                .findFirst()
                .orElseThrow();
    }

    private Reply apply(long uploadId, String periods) throws Exception {
        return server.post("/api/statement-uploads/" + uploadId + "/apply", "{\"periods\":[%s]}".formatted(periods), cookie);
    }

    private Reply matchToDate(long uploadId, long lineId, long itemId, String date) throws Exception {
        return server.put("/api/statement-uploads/" + uploadId + "/lines/" + lineId + "/match",
                "{\"itemId\":%d,\"scheduledDate\":\"%s\"}".formatted(itemId, date), cookie);
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
        JsonNode ofx = uploaded(card, OFX, null);
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
    void theRowsAreCheckedAgainstTheStatementsOwnBalances() throws Exception {
        // The PDF says its balances: $500 owed, a $500 payment, $92.20 spent, $92.20 owed.
        long otherCard = createAccount("Store Card", "credit_card");
        JsonNode pdf = uploaded(otherCard, new FilePart("september.pdf", SampleStatements.septemberPdf()), null);
        JsonNode check = pdf.get("check");
        assertThat(check.get("previousCents").asLong()).isEqualTo(50000);
        assertThat(check.get("newCents").asLong()).isEqualTo(9220);
        assertThat(check.get("spentCents").asLong()).isEqualTo(9220);
        assertThat(check.get("backCents").asLong()).isEqualTo(50000);
        assertThat(check.get("differenceCents").asLong()).isZero();
        // Its own totals are read from the summary too, to set against the rows (Rachel, 2026-10-30).
        assertThat(check.get("statementChargesCents").asLong()).isEqualTo(9220);
        assertThat(check.get("statementCreditsCents").asLong()).isEqualTo(50000);

        // A CSV says none, so they are typed off the paper statement; every row counts, the payment among them.
        JsonNode csv = uploaded(card, CSV, LAYOUT);
        long id = csv.get("id").asLong();
        assertThat(csv.get("check").get("differenceCents").isNull()).isTrue();
        Reply adds = server.put("/api/statement-uploads/" + id + "/balances", "{\"previousCents\":50000,\"newCents\":12250}", cookie);
        assertThat(adds.status()).isEqualTo(200);
        assertThat(adds.data().get("check").get("differenceCents").asLong()).isZero();
        Reply off = server.put("/api/statement-uploads/" + id + "/balances", "{\"previousCents\":50000,\"newCents\":12000}", cookie);
        assertThat(off.data().get("check").get("expectedNewCents").asLong()).isEqualTo(12250);
        assertThat(off.data().get("check").get("differenceCents").asLong()).isEqualTo(-250);

        // The statement's own totals of charges and of payments and credits are kept beside them (Rachel, 2026-10-30),
        // and cannot be less than zero.
        Reply totals = server.put("/api/statement-uploads/" + id + "/balances",
                "{\"previousCents\":50000,\"newCents\":12250,\"chargesCents\":12250,\"creditsCents\":50000}", cookie);
        assertThat(totals.data().get("check").get("statementChargesCents").asLong()).isEqualTo(12250);
        assertThat(totals.data().get("check").get("statementCreditsCents").asLong()).isEqualTo(50000);
        assertThat(server.get("/api/statement-uploads/" + id, cookie).data().get("check").get("statementChargesCents").asLong()).isEqualTo(12250);
        assertThat(server.put("/api/statement-uploads/" + id + "/balances", "{\"chargesCents\":-1}", cookie).status()).isEqualTo(400);
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

        Reply filed = file(id, line(upload, "KROGER #123"), groceries, List.of(vacation));
        assertThat(filed.status()).isEqualTo(200);
        // "KROGER #456" is the same payee, store number aside.
        assertThat(filed.data().get("alsoFiled").asInt()).isEqualTo(1);
        assertThat(line(filed.data().get("upload"), "KROGER #456").get("categoryId").asLong()).isEqualTo(groceries);

        JsonNode next = uploaded(card, LATE_CSV, null);
        JsonNode kroger = line(next, "KROGER #123");
        assertThat(kroger.get("categoryId").asLong()).isEqualTo(groceries);
        assertThat(kroger.get("tagIds").toString()).isEqualTo("[" + vacation + "]");
        // "KROGER RETURN" shares the first word with every Kroger filed, and they agree.
        assertThat(line(next, "KROGER RETURN").get("categoryId").asLong()).isEqualTo(groceries);
    }

    @Test
    void aChargePutOnABudgetChangesNothingUntilApplyThenTheBudgetIsWhatWasSpent() throws Exception {
        JsonNode upload = uploaded(card, OFX, null);
        long id = upload.get("id").asLong();
        JsonNode first = period(changes(id), "2026-09-01");
        assertThat(first.get("budgets")).extracting(budget -> budget.get("itemId").asLong()).containsExactlyInAnyOrder(food, fuel);
        assertThat(first.get("changes")).isEmpty();
        assertThat(first.get("open")).extracting(JsonNode::asLong).contains(id(upload, "KROGER #123"));

        Reply put = putOn(id, food, id(upload, "KROGER #123"));
        assertThat(put.status()).as(put.body().toString()).isEqualTo(200);
        assertThat(line(put.data(), "KROGER #123").get("budgetItemId").asLong()).isEqualTo(food);
        JsonNode chosen = change(period(changes(id), "2026-09-01"), food, "2026-09-01");
        // The statement covers every day of Food's Sep 1 date, so it is what was spent and nothing more.
        assertThat(chosen.get("covered").asText()).isEqualTo("fully");
        assertThat(chosen.get("budgetedCents").asLong()).isEqualTo(20000);
        assertThat(chosen.get("actualCents").asLong()).isEqualTo(5420);
        assertThat(chosen.get("stillToComeCents").asLong()).isZero();
        assertThat(chosen.get("newCents").asLong()).isEqualTo(5420);
        // Nothing changed yet.
        assertThat(occurrence(food, "2026-09-01").get("amountCents").asLong()).isEqualTo(20000);

        Reply applied = apply(id, "\"2026-09-01\"");
        assertThat(applied.status()).isEqualTo(200);
        assertThat(period(applied.data().get("changes"), "2026-09-01").get("upToDate").asBoolean()).isTrue();
        JsonNode occurrence = occurrence(food, "2026-09-01");
        assertThat(occurrence.get("amountCents").asLong()).isEqualTo(5420);
        assertThat(occurrence.get("projectedCents").asLong()).isEqualTo(20000);
        // Food's other dates, with no charges on them, are left alone.
        assertThat(occurrence(food, "2026-09-15").get("amountCents").asLong()).isEqualTo(20000);

        // The pay period can show what it was budgeted, what was spent and the charges behind it.
        JsonNode shown = server.get("/api/statement-budgets?from=2026-09-01&to=2026-09-14", cookie).data();
        assertThat(shown).singleElement().satisfies(budget -> {
            assertThat(budget.get("itemId").asLong()).isEqualTo(food);
            assertThat(budget.get("budgetedCents").asLong()).isEqualTo(20000);
            assertThat(budget.get("actualCents").asLong()).isEqualTo(5420);
            assertThat(budget.get("appliedCents").asLong()).isEqualTo(5420);
            assertThat(budget.get("charges")).singleElement()
                    .satisfies(charge -> assertThat(charge.get("description").asText()).isEqualTo("KROGER #123"));
        });

        assertThat(server.post("/api/undo", "", cookie).status()).isEqualTo(200);
        assertThat(occurrence(food, "2026-09-01").get("amountCents").asLong()).isEqualTo(20000);
    }

    @Test
    void aDateOnlyPartlyCoveredKeepsTheRestAsStillToComeUntilALaterStatementCoversIt() throws Exception {
        // The CSV runs Sep 2-16, so Food's Sep 1-14 date is missing Sep 1.
        JsonNode upload = uploaded(card, CSV, LAYOUT);
        long id = upload.get("id").asLong();
        assertThat(putOn(id, food, id(upload, "KROGER #123"), id(upload, "KROGER #456")).status()).isEqualTo(200);
        JsonNode partly = change(period(changes(id), "2026-09-01"), food, "2026-09-01");
        assertThat(partly.get("covered").asText()).isEqualTo("partly");
        assertThat(partly.get("actualCents").asLong()).isEqualTo(8000);
        assertThat(partly.get("stillToComeCents").asLong()).isEqualTo(12000);
        assertThat(partly.get("newCents").asLong()).isEqualTo(20000);

        // Typed, still to come is what was typed.
        Reply typed = server.put("/api/statement-uploads/" + id + "/still-to-come",
                "{\"itemId\":%d,\"scheduledDate\":\"2026-09-01\",\"cents\":3000}".formatted(food), cookie);
        assertThat(typed.status()).isEqualTo(200);
        JsonNode withTyped = change(period(typed.data(), "2026-09-01"), food, "2026-09-01");
        assertThat(withTyped.get("stillTyped").asBoolean()).isTrue();
        assertThat(withTyped.get("newCents").asLong()).isEqualTo(11000);
        apply(id, "\"2026-09-01\"");
        assertThat(occurrence(food, "2026-09-01").get("amountCents").asLong()).isEqualTo(11000);

        // Changed on the pay period, it takes effect straight away.
        Reply onPeriod = server.put("/api/statement-budgets/" + food + "/2026-09-01/still-to-come", "{\"cents\":5000}", cookie);
        assertThat(onPeriod.status()).as(onPeriod.body().toString()).isEqualTo(200);
        assertThat(occurrence(food, "2026-09-01").get("amountCents").asLong()).isEqualTo(13000);

        // A statement covering all of September fills in the rest: nothing is still to come, typed or not.
        long ofx = uploaded(card, OFX, null).get("id").asLong();
        JsonNode covered = change(period(changes(ofx), "2026-09-01"), food, "2026-09-01");
        assertThat(covered.get("covered").asText()).isEqualTo("fully");
        assertThat(covered.get("stillToComeCents").asLong()).isZero();
        // The OFX's Kroger is the CSV's again, so it is counted once.
        assertThat(covered.get("newCents").asLong()).isEqualTo(8000);
        apply(ofx, "\"2026-09-01\"");
        assertThat(occurrence(food, "2026-09-01").get("amountCents").asLong()).isEqualTo(8000);
    }

    @Test
    void aBudgetWhoseChargesAreAllTakenOffGoesBackToItsBudgetOnApply() throws Exception {
        JsonNode upload = uploaded(card, OFX, null);
        long id = upload.get("id").asLong();
        long kroger = id(upload, "KROGER #123");
        putOn(id, food, kroger);
        apply(id, "\"2026-09-01\"");
        assertThat(occurrence(food, "2026-09-01").get("amountCents").asLong()).isEqualTo(5420);

        assertThat(putOn(id, null, kroger).status()).isEqualTo(200);
        JsonNode back = change(period(changes(id), "2026-09-01"), food, "2026-09-01");
        assertThat(back.get("lineIds")).isEmpty();
        assertThat(back.get("newCents").asLong()).isEqualTo(20000);
        apply(id, "\"2026-09-01\"");
        assertThat(occurrence(food, "2026-09-01").get("amountCents").asLong()).isEqualTo(20000);
        assertThat(server.get("/api/statement-budgets?from=2026-09-01&to=2026-09-14", cookie).data()).isEmpty();
    }

    @Test
    void theBudgetAPayeeWentOnIsSuggestedNextTimeButNeverChosen() throws Exception {
        JsonNode upload = uploaded(card, CSV, LAYOUT);
        long id = upload.get("id").asLong();
        putOn(id, food, id(upload, "KROGER #123"));

        JsonNode next = uploaded(card, LATE_CSV, null);
        JsonNode kroger = line(next, "KROGER #123");
        assertThat(kroger.get("suggestedItemId").asLong()).isEqualTo(food);
        assertThat(kroger.get("budgetItemId").isNull()).isTrue();
        assertThat(line(next, "KROGER RETURN").get("suggestedItemId").isNull()).isTrue();
    }

    @Test
    void onlyOpenChargesGoOnABudgetWithADateInTheirPayPeriod() throws Exception {
        JsonNode upload = uploaded(card, CSV, LAYOUT);
        long id = upload.get("id").asLong();
        // A budget paid from another account is offered too (Rachel, 2026-10-30); one with no date then is not.
        long fromChecking = budget("Groceries by debit", checking, groceries);
        assertThat(putOn(id, fromChecking, id(upload, "KROGER #123")).status()).isEqualTo(200);
        long later = created("/api/items", """
                {"name":"Holiday gifts","kind":"budget","amountCents":20000,"payFromAccountId":%d,"categoryId":%d,
                 "schedule":{"frequency":"every_pay_period","startDate":"2027-01-01"}}""".formatted(card, groceries));
        assertThat(putOn(id, later, id(upload, "KROGER #456")).status()).isEqualTo(400);
        // The payment is left out; a charge can't go on a budget and be a one-off.
        assertThat(putOn(id, food, id(upload, "PAYMENT THANK YOU")).status()).isEqualTo(409);
        server.post("/api/statement-uploads/" + id + "/lines/" + id(upload, "CAFE LUNA") + "/one-off", "", cookie);
        assertThat(putOn(id, food, id(upload, "CAFE LUNA")).status()).isEqualTo(409);
        assertThat(putOn(id, food).status()).isEqualTo(400);
        assertThat(putOn(id, food, 999999).status()).isEqualTo(404);
    }

    @Test
    void aOneOffIsAddedOnApplyAndCanBeTakenBackBefore() throws Exception {
        JsonNode upload = uploaded(card, CSV, LAYOUT);
        long id = upload.get("id").asLong();
        long cafe = id(upload, "CAFE LUNA");
        String path = "/api/statement-uploads/" + id + "/lines/" + cafe + "/one-off";

        Reply chosen = server.post(path, "", cookie);
        assertThat(chosen.status()).isEqualTo(200);
        assertThat(line(chosen.data(), "CAFE LUNA").get("addOneOff").asBoolean()).isTrue();
        assertThat(server.get("/api/entries?from=2026-09-16&to=2026-09-16", cookie).data()).isEmpty();
        assertThat(period(changes(id), "2026-09-15").get("oneOffs")).extracting(JsonNode::asLong).containsExactly(cafe);
        assertThat(server.delete(path, cookie).status()).isEqualTo(200);
        assertThat(period(changes(id), "2026-09-15").get("oneOffs")).isEmpty();

        server.post(path, "", cookie);
        apply(id, "\"2026-09-15\"");
        JsonNode entries = server.get("/api/entries?from=2026-09-16&to=2026-09-16", cookie).data();
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.get("kind").asText()).isEqualTo("money_out");
            assertThat(entry.get("amountCents").asLong()).isEqualTo(450);
            assertThat(entry.get("accountId").asLong()).isEqualTo(card);
        });
        // Added, it is a one-off of its own now: not added twice, and not taken back here.
        assertThat(server.post(path, "", cookie).status()).isEqualTo(409);
        assertThat(server.delete(path, cookie).status()).isEqualTo(409);
        apply(id, "\"2026-09-15\"");
        assertThat(server.get("/api/entries?from=2026-09-16&to=2026-09-16", cookie).data()).hasSize(1);
    }

    @Test
    void aChargeMatchedToABillTakesItsAmountAndIsTickedPaidOnApplyAndUnmatchingPutsItBackOnApply() throws Exception {
        long fuelCard = bill("Fuel card", 4000, 5);
        JsonNode upload = uploaded(card, CSV, LAYOUT);
        long id = upload.get("id").asLong();
        long shell = id(upload, "SHELL OIL 5744");
        JsonNode before = period(changes(id), "2026-09-01");
        // Bills can be matched; budgets are what charges are put on.
        assertThat(before.get("matchable").findValuesAsText("name")).contains("Fuel card").doesNotContain("Food for Amara's house");

        Reply matched = matchToDate(id, shell, fuelCard, "2026-09-05");
        assertThat(matched.status()).as(matched.body().toString()).isEqualTo(200);
        assertThat(line(matched.data(), "SHELL OIL 5744").get("matched").get("applied").asBoolean()).isFalse();
        assertThat(occurrence(fuelCard, "2026-09-01").get("amountCents").asLong()).isEqualTo(4000);
        // Matched, it is no longer open, so it can't go on a budget too.
        assertThat(putOn(id, fuel, shell).status()).isEqualTo(409);

        apply(id, "\"2026-09-01\"");
        JsonNode paid = occurrence(fuelCard, "2026-09-01");
        assertThat(paid.get("amountCents").asLong()).isEqualTo(3800);
        assertThat(paid.get("paid").asBoolean()).isTrue();
        assertThat(paid.get("paidDate").asText()).isEqualTo("2026-09-05");
        assertThat(period(changes(id), "2026-09-01").get("matches")).singleElement()
                .satisfies(match -> assertThat(match.get("applied").asBoolean()).isTrue());

        assertThat(server.delete("/api/statement-uploads/" + id + "/lines/" + shell + "/match", cookie).status()).isEqualTo(200);
        assertThat(occurrence(fuelCard, "2026-09-01").get("amountCents").asLong()).isEqualTo(3800);
        assertThat(period(changes(id), "2026-09-01").get("restores").asInt()).isEqualTo(1);
        apply(id, "\"2026-09-01\"");
        JsonNode back = occurrence(fuelCard, "2026-09-01");
        assertThat(back.get("amountCents").asLong()).isEqualTo(4000);
        assertThat(back.get("paid").asBoolean()).isFalse();
    }

    @Test
    void aChargeMatchedToAOneOffAlreadyEnteredIsOnlyLinked() throws Exception {
        long entry = created("/api/entries",
                "{\"kind\":\"money_out\",\"date\":\"2026-09-09\",\"amountCents\":2580,\"description\":\"Groceries run\",\"accountId\":%d}"
                        .formatted(card));
        JsonNode upload = uploaded(card, CSV, LAYOUT);
        long id = upload.get("id").asLong();
        long kroger = id(upload, "KROGER #456");

        Reply matched = server.put("/api/statement-uploads/" + id + "/lines/" + kroger + "/match", "{\"entryId\":%d}".formatted(entry), cookie);
        assertThat(matched.status()).as(matched.body().toString()).isEqualTo(200);
        apply(id, "\"2026-09-01\"");
        assertThat(server.get("/api/entries?from=2026-09-09&to=2026-09-09", cookie).data()).singleElement()
                .satisfies(found -> assertThat(found.get("amountCents").asLong()).isEqualTo(2580));
        assertThat(line(server.get("/api/statement-uploads/" + id, cookie).data(), "KROGER #456").get("matched").get("applied").asBoolean())
                .isTrue();
    }

    @Test
    void aMatchMustBeSomethingNearTheChargesPayPeriodThatNoOtherChargeHas() throws Exception {
        long fuelCard = bill("Fuel card", 4000, 5);
        JsonNode upload = uploaded(card, CSV, LAYOUT);
        long id = upload.get("id").asLong();
        // A budget, a date two pay periods away, and money back can't be matched.
        assertThat(matchToDate(id, id(upload, "SHELL OIL 5744"), food, "2026-09-01").status()).isEqualTo(400);
        assertThat(matchToDate(id, id(upload, "SHELL OIL 5744"), fuelCard, "2026-10-05").status()).isEqualTo(400);
        assertThat(matchToDate(id, id(upload, "PAYMENT THANK YOU"), fuelCard, "2026-09-05").status()).isEqualTo(400);
        assertThat(server.put("/api/statement-uploads/" + id + "/lines/" + id(upload, "SHELL OIL 5744") + "/match", "{}", cookie).status())
                .isEqualTo(400);
        // One charge to a date: a second has to wait until the first is unmatched.
        assertThat(matchToDate(id, id(upload, "SHELL OIL 5744"), fuelCard, "2026-09-05").status()).isEqualTo(200);
        assertThat(matchToDate(id, id(upload, "KROGER #123"), fuelCard, "2026-09-05").status()).isEqualTo(409);
        assertThat(server.put("/api/statement-uploads/" + id + "/lines/999999/match", "{}", cookie).status()).isEqualTo(404);
    }

    @Test
    void aChargeCanBeMatchedToABillInThePayPeriodNextToItsOwnAndMovesItThere() throws Exception {
        // Rachel, 2026-10-30: a card charge on 9/14 for a bill dated 9/15 couldn't be matched across the edge.
        long phone = bill("Phone", 500, 14);
        JsonNode upload = uploaded(card, CSV, LAYOUT);
        long id = upload.get("id").asLong();
        assertThat(period(changes(id), "2026-09-15").get("matchable").findValuesAsText("name")).contains("Phone");

        Reply matched = matchToDate(id, id(upload, "CAFE LUNA"), phone, "2026-09-14");
        assertThat(matched.status()).as(matched.body().toString()).isEqualTo(200);
        apply(id, "\"2026-09-15\"");
        // Paid on the charge's date, it is in the charge's pay period now, with the charge's amount.
        JsonNode paid = occurrence(phone, "2026-09-15");
        assertThat(paid.get("amountCents").asLong()).isEqualTo(450);
        assertThat(paid.get("paidDate").asText()).isEqualTo("2026-09-16");
    }

    @Test
    void removingAnUploadLeavesWhatItChanged() throws Exception {
        JsonNode upload = uploaded(card, OFX, null);
        long id = upload.get("id").asLong();
        putOn(id, food, id(upload, "KROGER #123"));
        apply(id, "\"2026-09-01\"");
        assertThat(server.get("/api/accounts/" + card + "/statement-uploads", cookie).data()).hasSize(1);

        assertThat(server.delete("/api/statement-uploads/" + id, cookie).status()).isEqualTo(200);
        assertThat(server.get("/api/statement-uploads/" + id, cookie).status()).isEqualTo(404);
        assertThat(server.get("/api/accounts/" + card + "/statement-uploads", cookie).data()).isEmpty();
        assertThat(occurrence(food, "2026-09-01").get("amountCents").asLong()).isEqualTo(5420);
    }

    @Test
    void badRequestsAreRefusedKindly() throws Exception {
        JsonNode upload = uploaded(card, CSV, LAYOUT);
        long id = upload.get("id").asLong();
        assertThat(apply(id, "").status()).isEqualTo(400);
        assertThat(apply(id, "\"2026-08-01\"").status()).isEqualTo(400);
        assertThat(file(id, line(upload, "CAFE LUNA"), 999L, List.of()).status()).isEqualTo(400);
        assertThat(file(id, line(upload, "CAFE LUNA"), null, List.of(999L)).status()).isEqualTo(400);
        assertThat(server.put("/api/statement-uploads/" + id + "/lines/999999", "{}", cookie).status()).isEqualTo(404);
        String still = "/api/statement-uploads/" + id + "/still-to-come";
        assertThat(server.put(still, "{\"itemId\":%d,\"scheduledDate\":\"2026-09-02\",\"cents\":100}".formatted(food), cookie).status())
                .isEqualTo(400);
        assertThat(server.put(still, "{\"itemId\":%d,\"scheduledDate\":\"2026-09-01\",\"cents\":-100}".formatted(food), cookie).status())
                .isEqualTo(400);
        // Still to come on the pay period is only for a date a statement set.
        assertThat(server.put("/api/statement-budgets/" + food + "/2026-09-01/still-to-come", "{\"cents\":100}", cookie).status())
                .isEqualTo(404);
        assertThat(server.get("/api/statement-budgets?from=2026-09-01&to=2028-01-01", cookie).status()).isEqualTo(400);
    }

    /** The one-offs on an account dated {@code day}. */
    private List<JsonNode> entriesOn(long account, String day) throws Exception {
        return StreamSupport.stream(server.get("/api/entries?from=%s&to=%s".formatted(day, day), cookie).data().spliterator(), false)
                .filter(entry -> entry.get("accountId").asLong() == account)
                .toList();
    }

    @Test
    void chargesFromAnotherCardLowerTheBudgetAndBecomeOneOneOffThere() throws Exception {
        // Rachel, 2026-10-30: Amazon card charges put on a budget paid from another card lower that budget, and go on the
        // Amazon card as one one-off with the budget's category and tags, so the total stays what was budgeted.
        long amazon = createAccount("Amazon Store Card", "credit_card");
        JsonNode upload = uploaded(amazon, CSV, LAYOUT);
        long id = upload.get("id").asLong();
        JsonNode choices = period(changes(id), "2026-09-01").get("budgets");
        assertThat(choices).anySatisfy(choice -> {
            assertThat(choice.get("itemId").asLong()).isEqualTo(food);
            assertThat(choice.get("accountName").asText()).isEqualTo("Rewards Visa");
        });
        assertThat(putOn(id, food, id(upload, "KROGER #123"), id(upload, "KROGER #456")).status()).isEqualTo(200);

        JsonNode date = change(period(changes(id), "2026-09-01"), food, "2026-09-01");
        assertThat(date.get("accountId").asLong()).isEqualTo(card);
        assertThat(date.get("actualCents").asLong()).isZero();
        assertThat(date.get("stillToComeCents").asLong()).isEqualTo(12000);
        assertThat(date.get("newCents").asLong()).isEqualTo(12000);
        assertThat(date.get("elsewhere")).singleElement().satisfies(each -> {
            assertThat(each.get("accountName").asText()).isEqualTo("Amazon Store Card");
            assertThat(each.get("cents").asLong()).isEqualTo(8000);
        });
        // Nothing changes until Apply.
        assertThat(entriesOn(amazon, "2026-09-09")).isEmpty();

        assertThat(apply(id, "\"2026-09-01\"").status()).isEqualTo(200);
        assertThat(occurrence(food, "2026-09-01").get("amountCents").asLong()).isEqualTo(12000);
        assertThat(entriesOn(amazon, "2026-09-09")).singleElement().satisfies(entry -> {
            assertThat(entry.get("amountCents").asLong()).isEqualTo(8000);
            assertThat(entry.get("description").asText()).isEqualTo("Food for Amara's house");
            assertThat(entry.get("categoryId").asLong()).isEqualTo(groceries);
        });
        assertThat(period(changes(id), "2026-09-01").get("upToDate").asBoolean()).isTrue();

        // Applying again after taking one off changes that one-off, never adding a second.
        putOn(id, null, id(upload, "KROGER #456"));
        apply(id, "\"2026-09-01\"");
        assertThat(entriesOn(amazon, "2026-09-09")).isEmpty();
        assertThat(entriesOn(amazon, "2026-09-02")).singleElement()
                .satisfies(entry -> assertThat(entry.get("amountCents").asLong()).isEqualTo(5420));
        assertThat(occurrence(food, "2026-09-01").get("amountCents").asLong()).isEqualTo(14580);

        // With every charge taken off, the one-off goes and the budget is back to what was budgeted.
        putOn(id, null, id(upload, "KROGER #123"));
        apply(id, "\"2026-09-01\"");
        assertThat(entriesOn(amazon, "2026-09-02")).isEmpty();
        assertThat(occurrence(food, "2026-09-01").get("amountCents").asLong()).isEqualTo(20000);
    }

    @Test
    void onceTheBudgetsOwnStatementIsInItIsWhatWasSpentThereAndTheOtherCardKeepsItsShare() throws Exception {
        long amazon = createAccount("Amazon Store Card", "credit_card");
        JsonNode amazonUpload = uploaded(amazon, CSV, LAYOUT);
        long amazonId = amazonUpload.get("id").asLong();
        putOn(amazonId, food, id(amazonUpload, "KROGER #123"), id(amazonUpload, "KROGER #456"));
        apply(amazonId, "\"2026-09-01\"");

        // The card's own statement covers all of September: Food there is just what was spent there.
        JsonNode cardUpload = uploaded(card, OFX, null);
        long cardId = cardUpload.get("id").asLong();
        putOn(cardId, food, id(cardUpload, "KROGER #123"));
        JsonNode date = change(period(changes(cardId), "2026-09-01"), food, "2026-09-01");
        assertThat(date.get("covered").asText()).isEqualTo("fully");
        assertThat(date.get("stillToComeCents").asLong()).isZero();
        assertThat(date.get("newCents").asLong()).isEqualTo(5420);
        assertThat(date.get("elsewhere").get(0).get("cents").asLong()).isEqualTo(8000);

        apply(cardId, "\"2026-09-01\"");
        assertThat(occurrence(food, "2026-09-01").get("amountCents").asLong()).isEqualTo(5420);
        assertThat(entriesOn(amazon, "2026-09-09")).singleElement()
                .satisfies(entry -> assertThat(entry.get("amountCents").asLong()).isEqualTo(8000));
        // The pay period shows the other card's share beside what was spent from the card.
        JsonNode shown = server.get("/api/statement-budgets?from=2026-09-01&to=2026-09-14", cookie).data().get(0);
        assertThat(shown.get("actualCents").asLong()).isEqualTo(5420);
        assertThat(shown.get("elsewhereCents").asLong()).isEqualTo(8000);
    }

    @Test
    void severalChargesTakeTheSameCategoryAndTagsAtOnce() throws Exception {
        JsonNode upload = uploaded(card, CSV, LAYOUT);
        long id = upload.get("id").asLong();
        Reply filed = server.put("/api/statement-uploads/" + id + "/filing", "{\"lineIds\":[%d,%d],\"categoryId\":%d,\"tagIds\":[%d]}"
                .formatted(id(upload, "KROGER #123"), id(upload, "SHELL OIL 5744"), groceries, vacation), cookie);
        assertThat(filed.status()).isEqualTo(200);
        assertThat(line(filed.data(), "KROGER #123").get("categoryId").asLong()).isEqualTo(groceries);
        assertThat(line(filed.data(), "SHELL OIL 5744").get("tagIds")).extracting(JsonNode::asLong).containsExactly(vacation);
        assertThat(line(filed.data(), "CAFE LUNA").get("categoryId").isNull()).isTrue();

        assertThat(server.put("/api/statement-uploads/" + id + "/filing", "{\"lineIds\":[%d],\"categoryId\":99999}"
                .formatted(id(upload, "KROGER #123")), cookie).status()).isEqualTo(400);
        assertThat(server.put("/api/statement-uploads/" + id + "/filing", "{\"lineIds\":[]}", cookie).status()).isEqualTo(400);
        assertThat(server.put("/api/statement-uploads/" + id + "/filing", "{\"lineIds\":[999999]}", cookie).status()).isEqualTo(404);
    }

    @Test
    void severalChargesTakeACategoryAndTagsAndBecomeOneOffsTogether() throws Exception {
        JsonNode upload = uploaded(card, CSV, LAYOUT);
        long id = upload.get("id").asLong();
        long kroger = id(upload, "KROGER #123");
        long shell = id(upload, "SHELL OIL 5744");
        putOn(id, food, kroger);
        String body = "{\"lineIds\":[%d,%d],\"categoryId\":%d,\"tagIds\":[%d],\"oneOffs\":true}".formatted(kroger, shell, groceries, vacation);

        Reply filed = server.put("/api/statement-uploads/" + id + "/filing", body, cookie);
        assertThat(filed.status()).isEqualTo(200);
        assertThat(line(filed.data(), "KROGER #123").get("addOneOff").asBoolean()).isTrue();
        // Off the budget it was on, as Add as a one-off does for one.
        assertThat(line(filed.data(), "KROGER #123").get("budgetItemId").isNull()).isTrue();
        assertThat(line(filed.data(), "SHELL OIL 5744").get("addOneOff").asBoolean()).isTrue();
        assertThat(server.get("/api/entries?from=2026-09-01&to=2026-09-14", cookie).data()).isEmpty();

        apply(id, "\"2026-09-01\"");
        JsonNode entries = server.get("/api/entries?from=2026-09-01&to=2026-09-14", cookie).data();
        assertThat(entries).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.get("accountId").asLong()).isEqualTo(card);
            assertThat(entry.get("categoryId").asLong()).isEqualTo(groceries);
            assertThat(entry.get("tagIds")).extracting(JsonNode::asLong).containsExactly(vacation);
        });
        // Added already: refused for all of them, and nothing about them changes.
        long cafe = id(upload, "CAFE LUNA");
        Reply again = server.put("/api/statement-uploads/" + id + "/filing",
                "{\"lineIds\":[%d,%d],\"oneOffs\":true}".formatted(cafe, kroger), cookie);
        assertThat(again.status()).isEqualTo(409);
        assertThat(line(server.get("/api/statement-uploads/" + id, cookie).data(), "CAFE LUNA").get("addOneOff").asBoolean()).isFalse();
    }

    @Test
    void everyRouteNeedsTheAppUnlocked() throws Exception {
        long id = uploaded(card, CSV, LAYOUT).get("id").asLong();
        String upload = "/api/statement-uploads/" + id;
        assertThat(server.get("/api/accounts/" + card + "/statement-uploads", null).status()).isEqualTo(401);
        assertThat(server.upload("/api/accounts/" + card + "/statement-uploads", null, CSV).status()).isEqualTo(401);
        assertThat(server.upload("/api/accounts/" + card + "/statement-uploads/columns", null, CSV).status()).isEqualTo(401);
        assertThat(server.get(upload, null).status()).isEqualTo(401);
        assertThat(server.delete(upload, null).status()).isEqualTo(401);
        assertThat(server.put(upload + "/lines/1", "{}", null).status()).isEqualTo(401);
        assertThat(server.put(upload + "/budget", "{}", null).status()).isEqualTo(401);
        assertThat(server.put(upload + "/filing", "{}", null).status()).isEqualTo(401);
        assertThat(server.get(upload + "/changes", null).status()).isEqualTo(401);
        assertThat(server.put(upload + "/balances", "{}", null).status()).isEqualTo(401);
        assertThat(server.put(upload + "/still-to-come", "{}", null).status()).isEqualTo(401);
        assertThat(server.post(upload + "/apply", "{}", null).status()).isEqualTo(401);
        assertThat(server.put(upload + "/lines/1/match", "{}", null).status()).isEqualTo(401);
        assertThat(server.delete(upload + "/lines/1/match", null).status()).isEqualTo(401);
        assertThat(server.post(upload + "/lines/1/one-off", "", null).status()).isEqualTo(401);
        assertThat(server.delete(upload + "/lines/1/one-off", null).status()).isEqualTo(401);
        assertThat(server.download(upload + "/file", null, null).status()).isEqualTo(401);
        assertThat(server.download(upload + "/preview", null, null).status()).isEqualTo(401);
        assertThat(server.get("/api/statement-budgets?from=2026-09-01&to=2026-09-14", null).status()).isEqualTo(401);
        assertThat(server.put("/api/statement-budgets/1/2026-09-01/still-to-come", "{}", null).status()).isEqualTo(401);
    }
}
