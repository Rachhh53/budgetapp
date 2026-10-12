package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Download;
import app.budget.server.TestServer.FilePart;
import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pay stubs over real HTTP against a temporary data folder (spec Phase 8). Every stub is made
 * up ({@link SampleStubs}). "Today" is Thursday 2026-10-01, and Acme Corp pays $2,486.20 on
 * the 1st and 15th.
 */
class PayStubsApiTest extends ApiTestBase {

    private static final FilePart OCTOBER_1 = new FilePart("acme-2026-10-01.pdf", SampleStubs.acmeOctober1());
    private static final FilePart OCTOBER_15 = new FilePart("acme-2026-10-15.pdf", SampleStubs.acmeOctober15());

    long checking;
    long retirement;
    long hsa;
    long salary;

    @BeforeEach
    void addASalary() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        retirement = createAccount("Fidelity 401(k)", "investment");
        hsa = createAccount("HSA", "savings");
        salary = createSalary(checking);
    }

    private String stubsPath() {
        return "/api/income-sources/" + salary + "/pay-stubs";
    }

    private JsonNode uploaded(FilePart stub) throws Exception {
        Reply reply = server.upload(stubsPath(), cookie, stub);
        assertThat(reply.status()).isEqualTo(201);
        return reply.data();
    }

    private static JsonNode line(JsonNode stub, String label) {
        return StreamSupport.stream(stub.get("lines").spliterator(), false)
                .filter(line -> line.get("label").asText().equals(label))
                .findFirst()
                .orElseThrow();
    }

    /** The stub's lines as a request body, with the 401(k), the HSA and the match each sent to its account. */
    private String linesWithAccounts(JsonNode stub) {
        List<String> lines = new ArrayList<>();
        for (JsonNode line : stub.get("lines")) {
            String label = line.get("label").asText();
            Long accountId = label.equals("HSA") ? (Long) hsa : label.contains("401(k)") ? (Long) retirement : null;
            lines.add("""
                    {"section":"%s","label":"%s","amountCents":%d,"ytdCents":%s,"tracksAs":"%s","accountId":%s,"sure":true}"""
                    .formatted(line.get("section").asText(), label, line.get("amountCents").asLong(), line.get("ytdCents"),
                            line.get("tracksAs").asText(), accountId));
        }
        return "{\"payDate\":\"%s\",\"lines\":[%s]}".formatted(stub.get("payDate").asText(), String.join(",", lines));
    }

    /** Adds the October 1 stub and files its 401(k), HSA and match lines, ready to save. */
    private long reviewed() throws Exception {
        JsonNode stub = uploaded(OCTOBER_1);
        long id = stub.get("id").asLong();
        assertThat(server.put("/api/pay-stubs/" + id, linesWithAccounts(stub), cookie).status()).isEqualTo(200);
        return id;
    }

    private Reply save(long id, String apply) throws Exception {
        return server.post("/api/pay-stubs/" + id + "/save", "{\"apply\":\"" + apply + "\"}", cookie);
    }

    private JsonNode source() throws Exception {
        return server.get("/api/income-sources", cookie).data().get(0);
    }

    @Nested
    class Auth {
        @Test
        void everyPayStubRouteRequiresASession() throws Exception {
            long id = uploaded(OCTOBER_1).get("id").asLong();

            assertThat(server.get(stubsPath(), null).status()).isEqualTo(401);
            assertThat(server.upload(stubsPath(), null, OCTOBER_15).status()).isEqualTo(401);
            assertThat(server.upload("/api/pay-stubs/read", null, OCTOBER_15).status()).isEqualTo(401);
            assertThat(server.get("/api/pay-stubs/" + id, null).status()).isEqualTo(401);
            assertThat(server.put("/api/pay-stubs/" + id, "{\"lines\":[]}", null).status()).isEqualTo(401);
            assertThat(server.post("/api/pay-stubs/" + id + "/save", "{\"apply\":\"none\"}", null).status()).isEqualTo(401);
            assertThat(server.delete("/api/pay-stubs/" + id, null).status()).isEqualTo(401);
            assertThat(server.download("/api/pay-stubs/" + id + "/file", null, null).status()).isEqualTo(401);
            assertThat(server.download("/api/pay-stubs/" + id + "/preview", null, null).status()).isEqualTo(401);
            assertThat(server.post("/api/pay-stubs/" + id + "/copy", null, null).status()).isEqualTo(401);
        }
    }

    @Nested
    class Reading {
        @Test
        void readsEveryLineOfAStubAndChecksItAddsUp() throws Exception {
            JsonNode stub = uploaded(OCTOBER_1);

            assertThat(stub.get("status").asText()).isEqualTo("review");
            assertThat(stub.get("applied").isNull()).isTrue();
            assertThat(stub.get("sourceName").asText()).isEqualTo("Acme Corp");
            assertThat(stub.get("name").asText()).isEqualTo("acme-2026-10-01.pdf");
            assertThat(stub.get("payDate").asText()).isEqualTo("2026-10-01");
            assertThat(stub.get("pages").asInt()).isEqualTo(1);
            assertThat(stub.get("lines")).extracting(line -> line.get("label").asText()).containsExactly(
                    "Regular pay", "401(k)", "HSA", "Medical", "Dental", "Vision",
                    "Federal income tax", "State income tax", "Social Security", "Medicare", "Net pay", "Employer 401(k) match");
            assertThat(stub.get("toCheck").asInt()).isZero();

            JsonNode retirementLine = line(stub, "401(k)");
            assertThat(retirementLine.get("section").asText()).isEqualTo("pre_tax");
            assertThat(retirementLine.get("tracksAs").asText()).isEqualTo("contribution");
            assertThat(retirementLine.get("amountCents").asLong()).isEqualTo(15_000);
            assertThat(retirementLine.get("ytdCents").asLong()).isEqualTo(285_000);
            assertThat(retirementLine.get("accountId").isNull()).isTrue();
            assertThat(line(stub, "Employer 401(k) match").get("tracksAs").asText()).isEqualTo("match");

            JsonNode totals = stub.get("totals");
            assertThat(totals.get("grossCents").asLong()).isEqualTo(360_000);
            assertThat(totals.get("deductionsCents").asLong()).isEqualTo(111_380);
            assertThat(totals.get("netCents").asLong()).isEqualTo(248_620);
            assertThat(totals.get("employerCents").asLong()).isEqualTo(7_500);
            assertThat(totals.get("differenceCents").asLong()).isZero();
            assertThat(totals.get("balanced").asBoolean()).isTrue();

            // The paycheck it is for, and what saving it onto the source would set.
            assertThat(stub.get("paycheck").get("scheduledDate").asText()).isEqualTo("2026-10-01");
            assertThat(stub.get("wouldSet").get("grossCents").asLong()).isEqualTo(360_000);
            assertThat(stub.get("wouldSet").get("netCents").asLong()).isEqualTo(248_620);
            assertThat(stub.get("wouldSet").get("deductions")).hasSize(5);
        }

        @Test
        void readsOtherCommonLayouts() throws Exception {
            JsonNode sideBySide = uploaded(new FilePart("side.pdf", SampleStubs.sideBySide()));
            assertThat(sideBySide.get("payDate").asText()).isEqualTo("2026-10-01");
            assertThat(sideBySide.get("totals").get("grossCents").asLong()).isEqualTo(360_000);
            assertThat(sideBySide.get("totals").get("balanced").asBoolean()).isTrue();
            assertThat(line(sideBySide, "401K").get("tracksAs").asText()).isEqualTo("contribution");
            assertThat(line(sideBySide, "Federal Income Tax").get("ytdCents").asLong()).isEqualTo(744_382);

            JsonNode plain = uploaded(new FilePart("plain.pdf", SampleStubs.plainList()));
            assertThat(plain.get("payDate").asText()).isEqualTo("2026-10-01");
            assertThat(plain.get("totals").get("balanced").asBoolean()).isTrue();
            assertThat(line(plain, "FICA").get("amountCents").asLong()).isEqualTo(26_402);
        }

        @Test
        void readsOnlyThePayFromAStatementWithMoreOnItThanPay() throws Exception {
            JsonNode statement = uploaded(new FilePart("statement.pdf", SampleStubs.statement()));

            // Not the information column, not the account the net pay went to, not the taxable wages.
            assertThat(statement.get("lines")).extracting(each -> each.get("label").asText()).containsExactly(
                    "Regular", "401(k)", "Medical",
                    "Federal Income Tax", "Social Security Tax", "Medicare Tax", "CA State Income Tax", "Net pay");
            assertThat(statement.get("payDate").asText()).isEqualTo("2026-10-01");
            assertThat(statement.get("totals").get("grossCents").asLong()).isEqualTo(360_000);
            assertThat(statement.get("totals").get("netCents").asLong()).isEqualTo(248_620);
            assertThat(statement.get("totals").get("balanced").asBoolean()).isTrue();
            assertThat(line(statement, "401(k)").get("amountCents").asLong()).isEqualTo(15_000);
            assertThat(line(statement, "401(k)").get("section").asText()).isEqualTo("pre_tax");
        }

        @Test
        void listsASourcesStubsNewestFirst() throws Exception {
            uploaded(OCTOBER_1);
            uploaded(OCTOBER_15);

            JsonNode list = server.get(stubsPath(), cookie).data();

            assertThat(list).extracting(stub -> stub.get("payDate").asText()).containsExactly("2026-10-15", "2026-10-01");
            assertThat(list.get(0).get("grossCents").asLong()).isEqualTo(380_000);
            assertThat(list.get(0).get("netCents").asLong()).isEqualTo(264_620);
            assertThat(list.get(0).get("status").asText()).isEqualTo("review");
        }

        @Test
        void readsAStubWithoutKeepingItForAnIncomeNotAddedYet() throws Exception {
            Reply reply = server.upload("/api/pay-stubs/read", cookie, OCTOBER_1);

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.changed()).isFalse();
            assertThat(reply.data().get("payDate").asText()).isEqualTo("2026-10-01");
            assertThat(reply.data().get("totals").get("netCents").asLong()).isEqualTo(248_620);
            assertThat(reply.data().get("lines")).hasSize(12);
            assertThat(server.get(stubsPath(), cookie).data()).isEmpty();
        }

        @Test
        void refusesWhatIsNotAReadableStubInKindWords() throws Exception {
            Reply photo = server.upload(stubsPath(), cookie, new FilePart("stub.png", SampleFiles.png(Color.WHITE)));
            assertThat(photo.status()).isEqualTo(415);
            assertThat(photo.error()).contains("needs to be a PDF");

            Reply scan = server.upload(stubsPath(), cookie, new FilePart("scan.pdf", SampleStubs.scan()));
            assertThat(scan.status()).isEqualTo(422);
            assertThat(scan.error()).contains("looks like a scan or a photo");

            Reply locked = server.upload(stubsPath(), cookie, new FilePart("locked.pdf", SampleFiles.lockedPdf("secret")));
            assertThat(locked.status()).isEqualTo(422);
            assertThat(locked.error()).contains("locked with a password");

            assertThat(server.upload(stubsPath(), cookie, OCTOBER_1, OCTOBER_15).status()).isEqualTo(400);
            assertThat(server.upload("/api/income-sources/999/pay-stubs", cookie, OCTOBER_1).status()).isEqualTo(404);
            assertThat(server.get("/api/pay-stubs/999", cookie).status()).isEqualTo(404);
            assertThat(server.get(stubsPath(), cookie).data()).isEmpty();
        }

        @Test
        void keepsAPdfThatIsNoStubWithNoLinesToFillInByHand() throws Exception {
            JsonNode stub = uploaded(new FilePart("letter.pdf", SampleFiles.pdf(1, "Welcome aboard")));

            assertThat(stub.get("lines")).isEmpty();
            assertThat(stub.get("payDate").isNull()).isTrue();
            assertThat(stub.get("wouldSet").isNull()).isTrue();
        }
    }

    @Nested
    class Reviewing {
        @Test
        void changesLinesAndTheirAccountsWhileInReview() throws Exception {
            JsonNode stub = uploaded(OCTOBER_1);
            long id = stub.get("id").asLong();

            Reply changed = server.put("/api/pay-stubs/" + id, linesWithAccounts(stub), cookie);

            assertThat(changed.status()).isEqualTo(200);
            assertThat(line(changed.data(), "401(k)").get("accountId").asLong()).isEqualTo(retirement);
            assertThat(line(changed.data(), "HSA").get("accountId").asLong()).isEqualTo(hsa);
            assertThat(changed.data().get("wouldSet").get("match").get("toAccountId").asLong()).isEqualTo(retirement);
            assertThat(changed.data().get("wouldSet").get("match").get("fixedCents").asLong()).isEqualTo(7_500);
        }

        @Test
        void aLineRemovedLeavesTheStubNotAddingUpAndSaysByHowMuch() throws Exception {
            long id = uploaded(OCTOBER_1).get("id").asLong();

            Reply changed = server.put("/api/pay-stubs/" + id, """
                    {"payDate":"2026-10-01","lines":[
                      {"section":"earnings","label":"Regular pay","amountCents":360000,"tracksAs":"gross"},
                      {"section":"tax","label":"Federal income tax","amountCents":39178,"tracksAs":"tax","sure":false},
                      {"section":"net","label":"Net pay","amountCents":248620,"tracksAs":"net"}]}""", cookie);

            assertThat(changed.data().get("totals").get("balanced").asBoolean()).isFalse();
            assertThat(changed.data().get("totals").get("differenceCents").asLong()).isEqualTo(72_202);
            assertThat(changed.data().get("toCheck").asInt()).isEqualTo(1);
        }

        @Test
        void refusesLinesThatDoNotMakeSense() throws Exception {
            long id = uploaded(OCTOBER_1).get("id").asLong();
            String path = "/api/pay-stubs/" + id;

            // A tax cannot be tracked as a contribution.
            assertThat(server.put(path, """
                    {"lines":[{"section":"tax","label":"Federal","amountCents":100,"tracksAs":"contribution"}]}""", cookie)
                    .status()).isEqualTo(400);
            assertThat(server.put(path, """
                    {"lines":[{"section":"sideways","label":"Federal","amountCents":100,"tracksAs":"tax"}]}""", cookie)
                    .status()).isEqualTo(400);
            assertThat(server.put(path, """
                    {"lines":[{"section":"tax","label":"","amountCents":100,"tracksAs":"tax"}]}""", cookie).status()).isEqualTo(400);
            assertThat(server.put(path, """
                    {"lines":[{"section":"tax","label":"Federal","amountCents":-100,"tracksAs":"tax"}]}""", cookie)
                    .status()).isEqualTo(400);
            assertThat(server.put(path, """
                    {"lines":[{"section":"tax","label":"Federal","tracksAs":"tax"}]}""", cookie).status()).isEqualTo(400);
            // An account that does not exist, and a match into an account that is not an investment.
            assertThat(server.put(path, """
                    {"lines":[{"section":"pre_tax","label":"401(k)","amountCents":100,"tracksAs":"contribution","accountId":999}]}""",
                    cookie).status()).isEqualTo(400);
            Reply match = server.put(path, """
                    {"lines":[{"section":"employer","label":"Match","amountCents":100,"tracksAs":"match","accountId":%d}]}"""
                    .formatted(checking), cookie);
            assertThat(match.status()).isEqualTo(400);
            assertThat(match.error()).contains("investment account");
            assertThat(server.put(path, "{\"payDate\":\"1850-01-01\",\"lines\":[]}", cookie).status()).isEqualTo(400);
            assertThat(server.put(path, "not json", cookie).status()).isEqualTo(400);
            assertThat(server.put("/api/pay-stubs/999", "{\"lines\":[]}", cookie).status()).isEqualTo(404);
            // Nothing refused changed the stub.
            assertThat(server.get(path, cookie).data().get("lines")).hasSize(12);
        }
    }

    @Nested
    class Saving {
        @Test
        void correctingPutsTheStubsFiguresOnTheSourceAsItStandsToday() throws Exception {
            long id = reviewed();

            Reply saved = save(id, "correct");

            assertThat(saved.status()).isEqualTo(200);
            assertThat(saved.changed()).isTrue();
            assertThat(saved.data().get("status").asText()).isEqualTo("saved");
            assertThat(saved.data().get("applied").asText()).isEqualTo("correct");
            JsonNode source = source();
            assertThat(source.get("grossCents").asLong()).isEqualTo(360_000);
            assertThat(source.get("netCents").asLong()).isEqualTo(248_620);
            assertThat(source.get("netIsEstimate").asBoolean()).isFalse();
            // What is taken out before tax becomes the deductions, the savings with their accounts; taxes stay on the stub.
            assertThat(source.get("deductions")).extracting(deduction -> deduction.get("name").asText())
                    .containsExactly("401(k)", "HSA", "Medical", "Dental", "Vision");
            assertThat(source.get("deductions").get(0).get("toAccountId").asLong()).isEqualTo(retirement);
            assertThat(source.get("deductions").get(1).get("toAccountId").asLong()).isEqualTo(hsa);
            assertThat(source.get("deductions").get(2).get("toAccountId").isNull()).isTrue();
            assertThat(source.get("match").get("mode").asText()).isEqualTo("fixed");
            assertThat(source.get("match").get("fixedCents").asLong()).isEqualTo(7_500);
            // A correction, so there is no change of pay to list.
            assertThat(source.get("payHistory")).hasSize(1);
        }

        @Test
        void aPayChangeStartsWithThePaycheckTheStubIsForAndLeavesEarlierOnesAlone() throws Exception {
            uploaded(OCTOBER_1);
            JsonNode raise = uploaded(OCTOBER_15);
            long id = raise.get("id").asLong();

            assertThat(save(id, "pay_change").status()).isEqualTo(200);

            JsonNode history = source().get("payHistory");
            assertThat(history).hasSize(2);
            assertThat(history.get(0).get("netCents").asLong()).isEqualTo(248_620);
            assertThat(history.get(1).get("effectiveDate").asText()).isEqualTo("2026-10-15");
            assertThat(history.get(1).get("grossCents").asLong()).isEqualTo(380_000);
            assertThat(history.get(1).get("netCents").asLong()).isEqualTo(264_620);
            JsonNode paychecks = server.get("/api/paychecks?from=2026-10-01&to=2026-11-01", cookie).data();
            assertThat(paychecks).extracting(paycheck -> paycheck.get("netCents").asLong()).containsExactly(248_620L, 264_620L, 264_620L);
        }

        @Test
        void justThatPaycheckTypesOverTheOneTheStubIsFor() throws Exception {
            long id = uploaded(OCTOBER_15).get("id").asLong();

            assertThat(save(id, "paycheck").status()).isEqualTo(200);

            assertThat(source().get("netCents").asLong()).isEqualTo(248_620);
            assertThat(source().get("grossCents").isNull()).isTrue();
            JsonNode paychecks = server.get("/api/paychecks?from=2026-10-01&to=2026-11-01", cookie).data();
            assertThat(paychecks).extracting(paycheck -> paycheck.get("netCents").asLong()).containsExactly(248_620L, 264_620L, 248_620L);
            assertThat(paychecks.get(1).get("projectedCents").asLong()).isEqualTo(248_620);
        }

        @Test
        void aStubWithNoPaycheckNearItsDateCannotChangeOne() throws Exception {
            JsonNode stub = uploaded(OCTOBER_1);
            long id = stub.get("id").asLong();
            // Dated the 8th: a week from either payday.
            server.put("/api/pay-stubs/" + id, linesWithAccounts(stub).replace("2026-10-01", "2026-10-08"), cookie);

            Reply refused = save(id, "paycheck");

            assertThat(refused.status()).isEqualTo(409);
            assertThat(refused.error()).contains("couldn't find a paycheck");
            assertThat(server.get("/api/pay-stubs/" + id, cookie).data().get("status").asText()).isEqualTo("review");
            assertThat(server.get("/api/pay-stubs/" + id, cookie).data().get("paycheck").isNull()).isTrue();
        }

        @Test
        void keepingAStubWithoutChangingAnythingLeavesTheSourceAsItWas() throws Exception {
            long id = reviewed();

            assertThat(save(id, "none").data().get("applied").asText()).isEqualTo("none");

            assertThat(source().get("grossCents").isNull()).isTrue();
            assertThat(source().get("deductions")).isEmpty();
        }

        @Test
        void aMatchWorkedOutByARuleIsNotReplacedByOneStubsAmount() throws Exception {
            server.put("/api/income-sources/" + salary, """
                    {"name":"Acme Corp","depositAccountId":%d,"grossCents":360000,"netCents":248620,"primary":true,
                     "schedule":{"frequency":"semi_monthly","day1":1,"day2":15,"startDate":"2026-01-01","weekendRule":"none"},
                     "deductions":[{"name":"401 (K)","percentBp":417,"toAccountId":%d}],
                     "match":{"toAccountId":%d,"mode":"formula","matchBp":5000,"capBpOfGross":600}}"""
                    .formatted(checking, retirement, retirement), cookie);
            // The 401(k) line takes the account the source's own deduction of that name goes to.
            JsonNode stub = uploaded(OCTOBER_1);
            assertThat(line(stub, "401(k)").get("accountId").asLong()).isEqualTo(retirement);
            assertThat(line(stub, "Employer 401(k) match").get("accountId").asLong()).isEqualTo(retirement);

            save(stub.get("id").asLong(), "correct");

            assertThat(source().get("match").get("mode").asText()).isEqualTo("formula");
            // $150.00 is not 4.17% of $3,600.00 to the cent, so the deduction is now the stub's dollars.
            assertThat(source().get("deductions").get(0).get("amountCents").asLong()).isEqualTo(15_000);
            assertThat(source().get("deductions").get(0).get("percentBp").isNull()).isTrue();
        }

        @Test
        void refusesToSaveWhatCannotBeSaved() throws Exception {
            long id = uploaded(OCTOBER_1).get("id").asLong();
            assertThat(save(id, "sideways").status()).isEqualTo(400);
            assertThat(server.post("/api/pay-stubs/" + id + "/save", "{}", cookie).status()).isEqualTo(400);
            assertThat(save(999, "none").status()).isEqualTo(404);

            // No date, and no net pay line.
            server.put("/api/pay-stubs/" + id, """
                    {"lines":[{"section":"earnings","label":"Regular pay","amountCents":360000,"tracksAs":"gross"}]}""", cookie);
            Reply noDate = save(id, "none");
            assertThat(noDate.status()).isEqualTo(400);
            assertThat(noDate.error()).contains("day this stub was paid");
            server.put("/api/pay-stubs/" + id, """
                    {"payDate":"2026-10-01","lines":[{"section":"earnings","label":"Regular pay","amountCents":360000,"tracksAs":"gross"}]}""",
                    cookie);
            Reply noNet = save(id, "correct");
            assertThat(noNet.status()).isEqualTo(400);
            assertThat(noNet.error()).contains("no net pay line");
            assertThat(source().get("grossCents").isNull()).isTrue();
        }

        @Test
        void aSavedStubIsNotChangedOrSavedAgain() throws Exception {
            long id = reviewed();
            save(id, "none");

            assertThat(server.put("/api/pay-stubs/" + id, "{\"lines\":[]}", cookie).status()).isEqualTo(409);
            assertThat(save(id, "correct").status()).isEqualTo(409);
            assertThat(source().get("grossCents").isNull()).isTrue();
        }

        @Test
        void undoTakesBackBothTheStubBeingSavedAndWhatItChanged() throws Exception {
            long id = reviewed();
            save(id, "correct");

            assertThat(server.post("/api/undo", null, cookie).status()).isEqualTo(200);

            assertThat(source().get("grossCents").isNull()).isTrue();
            assertThat(source().get("deductions")).isEmpty();
            assertThat(server.get("/api/pay-stubs/" + id, cookie).data().get("status").asText()).isEqualTo("review");
        }
    }

    @Nested
    class Remembering {
        @Test
        void aSecondStubFromTheSameEmployerFillsInEveryLineWithNoMapping() throws Exception {
            // The first stub's "Vision" is filed by hand as an after-tax benefit, and its savings lines get their accounts.
            JsonNode first = uploaded(OCTOBER_1);
            long firstId = first.get("id").asLong();
            server.put("/api/pay-stubs/" + firstId,
                    linesWithAccounts(first).replace("\"section\":\"pre_tax\",\"label\":\"Vision\"", "\"section\":\"after_tax\",\"label\":\"Vision\""),
                    cookie);
            save(firstId, "correct");

            JsonNode second = uploaded(OCTOBER_15);

            assertThat(second.get("toCheck").asInt()).isZero();
            assertThat(second.get("totals").get("balanced").asBoolean()).isTrue();
            assertThat(line(second, "401(k)").get("accountId").asLong()).isEqualTo(retirement);
            assertThat(line(second, "HSA").get("accountId").asLong()).isEqualTo(hsa);
            assertThat(line(second, "Employer 401(k) match").get("accountId").asLong()).isEqualTo(retirement);
            assertThat(line(second, "Vision").get("section").asText()).isEqualTo("after_tax");
            // Ready to save as it is: the raise, from its own payday on.
            assertThat(save(second.get("id").asLong(), "pay_change").status()).isEqualTo(200);
            assertThat(source().get("payHistory").get(1).get("deductions").get(0).get("toAccountId").asLong()).isEqualTo(retirement);
        }
    }

    @Nested
    class TheFile {
        private List<Path> stored() throws Exception {
            Path files = dataDir.resolve("files");
            if (!Files.isDirectory(files)) {
                return List.of();
            }
            try (Stream<Path> walk = Files.walk(files)) {
                return walk.filter(Files::isRegularFile).toList();
            }
        }

        @Test
        void isShownAsAPictureAndHandedBackAsItWasAdded() throws Exception {
            long id = uploaded(OCTOBER_1).get("id").asLong();

            Download preview = server.download("/api/pay-stubs/" + id + "/preview?page=1", null, cookie);
            assertThat(preview.status()).isEqualTo(200);
            assertThat(preview.header("Content-Type")).startsWith("image/png");
            assertThat(ImageIO.read(new ByteArrayInputStream(preview.body()))).isNotNull();
            assertThat(server.download("/api/pay-stubs/" + id + "/preview?page=2", null, cookie).status()).isEqualTo(404);

            Download file = server.download("/api/pay-stubs/" + id + "/file", null, cookie);
            assertThat(file.status()).isEqualTo(200);
            assertThat(file.body()).isEqualTo(OCTOBER_1.content());
            assertThat(file.header("Content-Disposition")).startsWith("attachment").contains("acme-2026-10-01.pdf");

            Reply copy = server.post("/api/pay-stubs/" + id + "/copy", null, cookie);
            assertThat(copy.data().get("saved").get("fileName").asText()).isEqualTo("acme-2026-10-01.pdf");
            assertThat(Files.readAllBytes(server.downloads.resolve("acme-2026-10-01.pdf"))).isEqualTo(OCTOBER_1.content());
        }

        @Test
        void staysWhileItsStubDoesAndGoesOnceNothingCanBringTheStubBack() throws Exception {
            long id = uploaded(OCTOBER_1).get("id").asLong();
            server.close();
            server = new TestServer(dataDir);
            cookie = server.unlock();
            assertThat(stored()).hasSize(1);

            assertThat(server.delete("/api/pay-stubs/" + id, cookie).status()).isEqualTo(200);
            assertThat(server.get("/api/pay-stubs/" + id, cookie).status()).isEqualTo(404);
            // Undo can still bring the stub back, so its file is kept through a restart.
            server.close();
            server = new TestServer(dataDir);
            cookie = server.unlock();
            assertThat(stored()).hasSize(1);
            assertThat(server.post("/api/undo", null, cookie).status()).isEqualTo(200);
            assertThat(server.get("/api/pay-stubs/" + id, cookie).data().get("lines")).hasSize(12);
        }

        @Test
        void goesWithItsIncomeSource() throws Exception {
            long id = uploaded(OCTOBER_1).get("id").asLong();

            assertThat(server.delete("/api/income-sources/" + salary, cookie).status()).isEqualTo(200);

            assertThat(server.get("/api/pay-stubs/" + id, cookie).status()).isEqualTo(404);
        }
    }
}
