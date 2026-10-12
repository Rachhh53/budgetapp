package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Download;
import app.budget.server.TestServer.FilePart;
import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Income reports over real HTTP against a temporary database (spec §4.11, §8). "Today" is
 * Thursday 2026-10-01. Brightwater Health pays $3,600.00 gross on the 1st and 15th: $150.00 to
 * the 401(k) and $82.00 for the health plan come out, $2,486.20 comes home, so $881.80 is taxes;
 * the employer adds $50.00 to the 401(k) each time.
 */
class IncomeReportsApiTest extends ApiTestBase {

    private static final long GROSS = 360_000;
    private static final long NET = 248_620;
    private static final long DEDUCTIONS = 23_200;
    private static final long TAXES = 88_180;
    private static final long MATCH = 5_000;

    long checking;
    long retirement;
    long salary;

    @BeforeEach
    void aSalaryARefundAndAPayback() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        retirement = createAccount("Brightwater 401(k)", "investment");
        salary = created("/api/income-sources", """
                {"name":"Brightwater Health","depositAccountId":%d,"grossCents":360000,"netCents":248620,"primary":true,
                 "schedule":{"frequency":"semi_monthly","day1":1,"day2":15,"startDate":"2026-01-01","weekendRule":"none"},
                 "deductions":[{"name":"401(k)","amountCents":15000,"toAccountId":%d},{"name":"Health plan","amountCents":8200}],
                 "match":{"toAccountId":%d,"mode":"fixed","fixedCents":5000}}"""
                .formatted(checking, retirement, retirement));
        created("/api/entries", """
                {"kind":"money_in","date":"2026-03-10","amountCents":50000,"description":"Tax refund","accountId":%d}"""
                .formatted(checking));
        // Money that pays back an expense is the expense coming back: never income.
        long visit = created("/api/entries", """
                {"kind":"money_out","date":"2026-03-02","amountCents":8500,"description":"Visit","payee":"Valley Pediatrics","accountId":%d}"""
                .formatted(checking));
        server.put("/api/entries/" + visit + "/reimbursement", "{\"status\":\"submitted\"}", cookie);
        created("/api/entries", """
                {"kind":"money_in","date":"2026-03-20","amountCents":6000,"description":"HSA payout","accountId":%d,
                 "reimbursesEntryId":%d}""".formatted(checking, visit));
    }

    /** An income report of this year by month, counting {@code include}, with {@code extra} spliced in. */
    private static String income(String include, String extra) {
        return "{\"kind\":\"income\",\"range\":\"this_year\",\"show\":\"chart_and_table\",\"incomeInclude\":[%s]%s}"
                .formatted(include, extra.isEmpty() ? "" : "," + extra);
    }

    private static String everything(String extra) {
        return income("\"paychecks\",\"one_offs\",\"match\",\"interest\"", extra);
    }

    private JsonNode run(String definition) throws Exception {
        Reply reply = server.post("/api/reports/run", "{\"definition\":" + definition + "}", cookie);
        assertThat(reply.status()).as(reply.body().toString()).isEqualTo(200);
        return reply.data();
    }

    private static JsonNode group(JsonNode report, String label) {
        for (JsonNode group : report.get("groups")) {
            if (group.get("label").asText().equals(label)) {
                return group;
            }
        }
        throw new AssertionError("No group called " + label + " in " + report.get("groups"));
    }

    private static List<JsonNode> rows(JsonNode report, String kind) {
        return StreamSupport.stream(report.get("rows").spliterator(), false)
                .filter(row -> row.get("kind").asText().equals(kind))
                .toList();
    }

    @Nested
    class Running {
        @Test
        void anIncomeReportRequiresASession() throws Exception {
            assertThat(server.post("/api/reports/run", "{\"definition\":" + everything("") + "}", null).status()).isEqualTo(401);
            Download csv = server.download("/api/reports/export/csv", "{\"definition\":" + everything("") + "}", null);
            assertThat(csv.status()).isEqualTo(401);
            assertThat(new String(csv.body(), StandardCharsets.UTF_8)).doesNotContain("Brightwater");
            assertThat(server.download("/api/reports/export/pdf", "{\"definition\":" + everything("") + "}", null).status()).isEqualTo(401);
        }

        @Test
        void theYearsIncomeWithGrossWhatCameOutAndWhatCameHome() throws Exception {
            JsonNode report = run(everything(""));

            assertThat(report.get("kind").asText()).isEqualTo("income");
            assertThat(report.get("from").asText()).isEqualTo("2026-01-01");
            assertThat(report.get("to").asText()).isEqualTo("2026-12-31");
            assertThat(report.get("groupBy").asText()).isEqualTo("month");
            assertThat(report.get("grossCents").asLong()).isEqualTo(24 * GROSS);
            assertThat(report.get("taxesCents").asLong()).isEqualTo(24 * TAXES);
            assertThat(report.get("deductionsCents").asLong()).isEqualTo(24 * DEDUCTIONS);
            assertThat(report.get("takeHomeCents").asLong()).isEqualTo(24 * NET);
            assertThat(report.get("oneOffsCents").asLong()).isEqualTo(50_000);
            // The match is the person's money, but it is beside what was received, never in it.
            assertThat(report.get("matchCents").asLong()).isEqualTo(24 * MATCH);
            assertThat(report.get("receivedCents").asLong()).isEqualTo(24 * NET + 50_000);
            assertThat(report.get("paychecksWithoutGross").asInt()).isZero();
            // October 15 on has not come yet: five paychecks.
            assertThat(report.get("estimatedCents").asLong()).isEqualTo(5 * NET);

            assertThat(report.get("groups")).hasSize(12);
            assertThat(report.get("groups").get(0).get("label").asText()).isEqualTo("January 2026");
            JsonNode march = group(report, "March 2026");
            assertThat(march.get("count").asInt()).isEqualTo(5);
            assertThat(march.get("grossCents").asLong()).isEqualTo(2 * GROSS);
            assertThat(march.get("oneOffsCents").asLong()).isEqualTo(50_000);
            assertThat(march.get("matchCents").asLong()).isEqualTo(2 * MATCH);
            assertThat(march.get("receivedCents").asLong()).isEqualTo(2 * NET + 50_000);

            // The payback of the pediatrician's bill is nowhere in it.
            assertThat(rows(report, "one_off")).extracting(row -> row.get("description").asText()).containsExactly("Tax refund");
            JsonNode paycheck = rows(report, "paycheck").get(0);
            assertThat(paycheck.get("date").asText()).isEqualTo("2026-01-01");
            assertThat(paycheck.get("description").asText()).isEqualTo("Brightwater Health paycheck");
            assertThat(paycheck.get("accountId").asLong()).isEqualTo(checking);
            assertThat(paycheck.get("taxesCents").asLong()).isEqualTo(TAXES);
            assertThat(paycheck.get("fromStub").asBoolean()).isFalse();
            assertThat(paycheck.get("estimate").asBoolean()).isFalse();
            assertThat(rows(report, "match").get(0).get("accountId").asLong()).isEqualTo(retirement);
        }

        @Test
        void runningItChangesNothingAndASpendingReportSaysItIsOne() throws Exception {
            // Looking up paydays fills in a new year's bank holidays; that is not a change to offer Undo for.
            Reply reply = server.post("/api/reports/run", "{\"definition\":" + everything("") + "}", cookie);
            assertThat(reply.changed()).isFalse();
            assertThat(server.post("/api/reports/export/csv", "{\"save\":true,\"definition\":" + everything("") + "}", cookie).changed())
                    .isFalse();

            JsonNode spending = run("{\"range\":\"this_year\",\"include\":[\"one_offs\"],\"groupBy\":\"month\",\"show\":\"table\"}");
            assertThat(spending.get("kind").asText()).isEqualTo("spending");
        }

        @Test
        void countsOnlyWhatItIsAskedTo() throws Exception {
            JsonNode paychecks = run(income("\"paychecks\"", ""));
            assertThat(paychecks.get("count").asInt()).isEqualTo(24);
            assertThat(paychecks.get("matchCents").asLong()).isZero();
            assertThat(paychecks.get("receivedCents").asLong()).isEqualTo(24 * NET);

            JsonNode match = run(income("\"match\"", ""));
            assertThat(match.get("count").asInt()).isEqualTo(24);
            assertThat(match.get("receivedCents").asLong()).isZero();
            assertThat(match.get("matchCents").asLong()).isEqualTo(24 * MATCH);
        }

        @Test
        void groupsByYearSourceAccountAndPayPeriod() throws Exception {
            JsonNode year = run(everything("\"incomeGroupBy\":\"year\""));
            assertThat(year.get("groups")).hasSize(1);
            assertThat(group(year, "2026").get("receivedCents").asLong()).isEqualTo(24 * NET + 50_000);

            JsonNode source = run(everything("\"incomeGroupBy\":\"source\""));
            assertThat(source.get("groups")).hasSize(2);
            assertThat(source.get("groups").get(0).get("label").asText()).isEqualTo("Brightwater Health");
            assertThat(group(source, "Brightwater Health").get("matchCents").asLong()).isEqualTo(24 * MATCH);
            assertThat(group(source, "Not from a paycheck").get("receivedCents").asLong()).isEqualTo(50_000);

            JsonNode account = run(everything("\"incomeGroupBy\":\"account\""));
            assertThat(group(account, "Everyday Checking").get("receivedCents").asLong()).isEqualTo(24 * NET + 50_000);
            assertThat(group(account, "Everyday Checking").get("color").asText()).isEqualTo("#0f766e");
            assertThat(group(account, "Brightwater 401(k)").get("matchCents").asLong()).isEqualTo(24 * MATCH);
            assertThat(group(account, "Brightwater 401(k)").get("receivedCents").asLong()).isZero();

            JsonNode period = run(everything("\"incomeGroupBy\":\"pay_period\",\"range\":\"custom\",\"from\":\"2026-10-01\",\"to\":\"2026-10-31\"")
                    .replace("\"range\":\"this_year\",", ""));
            assertThat(period.get("groups")).extracting(group -> group.get("label").asText())
                    .containsExactly("Oct 1 – Oct 14, 2026", "Oct 15 – Oct 31, 2026");
            assertThat(period.get("groups").get(0).get("takeHomeCents").asLong()).isEqualTo(NET);
        }

        @Test
        void filtersByIncomeSourceAndByTheAccountItLandedIn() throws Exception {
            JsonNode ofSource = run(everything("\"sourceIds\":[%d]".formatted(salary)));
            // What has no income source — the refund — is left out.
            assertThat(ofSource.get("oneOffsCents").asLong()).isZero();
            assertThat(ofSource.get("takeHomeCents").asLong()).isEqualTo(24 * NET);

            JsonNode inRetirement = run(everything("\"accountIds\":[%d]".formatted(retirement)));
            assertThat(inRetirement.get("receivedCents").asLong()).isZero();
            assertThat(inRetirement.get("matchCents").asLong()).isEqualTo(24 * MATCH);
            assertThat(rows(inRetirement, "paycheck")).isEmpty();
        }

        @Test
        void interestASavingsAccountEarnsIsCountedAsAnEstimate() throws Exception {
            long savings = createAccount("Rainy Day Savings", "savings");
            enterBalance(savings, "2026-10-01", 500_000);
            server.post("/api/accounts/" + savings + "/rates", "{\"rateBp\":410,\"effectiveDate\":\"2026-01-01\"}", cookie);

            JsonNode report = run(income("\"interest\"", "\"incomeGroupBy\":\"account\""));

            // 5,000.00 × ((1.041)^(1/12) − 1) = 16.77 on October 31, then on a little more each month.
            List<JsonNode> interest = rows(report, "interest");
            assertThat(interest).hasSize(3);
            assertThat(interest.get(0).get("date").asText()).isEqualTo("2026-10-31");
            assertThat(interest.get(0).get("amountCents").asLong()).isEqualTo(1677);
            assertThat(interest.get(0).get("description").asText()).isEqualTo("Interest · Rainy Day Savings");
            assertThat(interest.get(0).get("estimate").asBoolean()).isTrue();
            assertThat(report.get("interestCents").asLong()).isEqualTo(report.get("receivedCents").asLong()).isGreaterThan(3 * 1677L - 1);
            assertThat(group(report, "Rainy Day Savings").get("count").asInt()).isEqualTo(3);
        }

        @Test
        void nothingMatchingIsAnEmptyReportNotAnError() throws Exception {
            JsonNode report = run(income("\"one_offs\"", "\"range\":\"custom\",\"from\":\"2025-01-01\",\"to\":\"2025-12-31\"")
                    .replace("\"range\":\"this_year\",", ""));

            assertThat(report.get("groups")).isEmpty();
            assertThat(report.get("rows")).isEmpty();
            assertThat(report.get("receivedCents").asLong()).isZero();
        }

        @Test
        void explainsWhatIsMissingOrWrong() throws Exception {
            assertError(income("", ""), "Please choose at least one thing to include.");
            assertError(everything("\"sourceIds\":[999]"),
                    "This report filters by an income source that is no longer there. Please choose its filters again.");
            assertError(everything("\"accountIds\":[999]"),
                    "This report filters by an account that is no longer there. Please choose its filters again.");
            assertThat(server.post("/api/reports/run", "{\"definition\":" + everything("\"incomeGroupBy\":\"mood\"") + "}", cookie).status())
                    .isEqualTo(400);
            assertThat(server.post("/api/reports/run", "{\"definition\":" + income("\"lottery\"", "") + "}", cookie).status()).isEqualTo(400);
        }

        private void assertError(String definition, String message) throws Exception {
            Reply reply = server.post("/api/reports/run", "{\"definition\":" + definition + "}", cookie);
            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.error()).isEqualTo(message);
        }
    }

    @Nested
    class FromPayStubs {
        long acme;
        long hsa;

        /** A second job with only a take-home amount entered, and its October 1 stub saved without changing anything. */
        @BeforeEach
        void aSavedStub() throws Exception {
            hsa = createAccount("HSA", "savings");
            acme = created("/api/income-sources", """
                    {"name":"Acme Corp","depositAccountId":%d,"netCents":248620,"primary":false,
                     "schedule":{"frequency":"semi_monthly","day1":1,"day2":15,"startDate":"2026-01-01","weekendRule":"none"}}"""
                    .formatted(checking));
            Reply uploaded = server.upload("/api/income-sources/" + acme + "/pay-stubs", cookie,
                    new FilePart("acme-2026-10-01.pdf", SampleStubs.acmeOctober1()));
            assertThat(uploaded.status()).isEqualTo(201);
            JsonNode stub = uploaded.data();
            List<String> lines = new ArrayList<>();
            for (JsonNode line : stub.get("lines")) {
                String label = line.get("label").asText();
                Long accountId = label.equals("HSA") ? (Long) hsa : label.contains("401(k)") ? (Long) retirement : null;
                lines.add("""
                        {"section":"%s","label":"%s","amountCents":%d,"ytdCents":%s,"tracksAs":"%s","accountId":%s,"sure":true}"""
                        .formatted(line.get("section").asText(), label, line.get("amountCents").asLong(), line.get("ytdCents"),
                                line.get("tracksAs").asText(), accountId));
            }
            long id = stub.get("id").asLong();
            assertThat(server.put("/api/pay-stubs/" + id,
                    "{\"payDate\":\"%s\",\"lines\":[%s]}".formatted(stub.get("payDate").asText(), String.join(",", lines)), cookie).status())
                    .isEqualTo(200);
            assertThat(server.post("/api/pay-stubs/" + id + "/save", "{\"apply\":\"none\"}", cookie).status()).isEqualTo(200);
        }

        @Test
        void aPaycheckWithASavedStubUsesTheStubsOwnTaxesAndTheOthersSayTheirGrossIsNotKnown() throws Exception {
            JsonNode report = run(income("\"paychecks\"",
                    "\"sourceIds\":[%d],\"range\":\"custom\",\"from\":\"2026-10-01\",\"to\":\"2026-10-31\"".formatted(acme))
                    .replace("\"range\":\"this_year\",", ""));

            List<JsonNode> paychecks = rows(report, "paycheck");
            assertThat(paychecks).hasSize(2);
            JsonNode first = paychecks.get(0);
            assertThat(first.get("fromStub").asBoolean()).isTrue();
            assertThat(first.get("grossCents").asLong()).isEqualTo(360_000);
            // Federal 391.78, state 159.30, Social Security 213.98, Medicare 50.04.
            assertThat(first.get("taxesCents").asLong()).isEqualTo(81_510);
            // 401(k) 150.00, HSA 50.00, medical 86.40, dental 9.20, vision 3.10.
            assertThat(first.get("deductionsCents").asLong()).isEqualTo(29_870);
            assertThat(first.get("amountCents").asLong()).isEqualTo(248_620);
            // October 15 has no stub and the source has no gross amount.
            JsonNode second = paychecks.get(1);
            assertThat(second.get("fromStub").asBoolean()).isFalse();
            assertThat(second.get("grossCents").isNull()).isTrue();
            assertThat(second.get("taxesCents").isNull()).isTrue();

            assertThat(report.get("grossCents").asLong()).isEqualTo(360_000);
            assertThat(report.get("takeHomeCents").asLong()).isEqualTo(2 * 248_620L);
            assertThat(report.get("paychecksWithoutGross").asInt()).isEqualTo(1);
        }
    }

    @Nested
    class SavingAndExporting {
        private String bySource() {
            return everything("\"incomeGroupBy\":\"source\",\"sourceIds\":[%d]".formatted(salary));
        }

        @Test
        void savesAnIncomeReportAndGivesItBackAsItWasAsked() throws Exception {
            Reply reply = server.post("/api/reports", "{\"name\":\"Brightwater 2026\",\"definition\":" + bySource() + "}", cookie);

            assertThat(reply.status()).as(reply.body().toString()).isEqualTo(201);
            JsonNode definition = server.get("/api/reports", cookie).data().get(0).get("definition");
            assertThat(definition.get("kind").asText()).isEqualTo("income");
            assertThat(definition.get("incomeGroupBy").asText()).isEqualTo("source");
            assertThat(definition.get("incomeInclude")).extracting(JsonNode::asText)
                    .containsExactly("paychecks", "one_offs", "match", "interest");
            assertThat(definition.get("sourceIds").get(0).asLong()).isEqualTo(salary);
            // What was handed back runs as it is.
            assertThat(run(definition.toString()).get("takeHomeCents").asLong()).isEqualTo(24 * NET);
        }

        @Test
        void anIncomeReportThatCouldNotRunIsNotSaved() throws Exception {
            Reply reply = server.post("/api/reports",
                    "{\"name\":\"Gone\",\"definition\":" + everything("\"sourceIds\":[999]") + "}", cookie);

            assertThat(reply.status()).isEqualTo(400);
            assertThat(server.get("/api/reports", cookie).data()).isEmpty();
        }

        @Test
        void csvHasOneRowPerLineWithWhatCameOutOfEachPaycheck() throws Exception {
            Download csv = server.download("/api/reports/export/csv",
                    "{\"name\":\"Income 2026\",\"definition\":" + everything("") + "}", cookie);

            assertThat(csv.status()).isEqualTo(200);
            assertThat(csv.header("Content-Type")).isEqualTo("text/csv; charset=utf-8");
            assertThat(csv.header("Content-Disposition")).isEqualTo("attachment; filename*=UTF-8''Income%202026.csv");
            String[] lines = new String(csv.body(), StandardCharsets.UTF_8).split("\r\n");
            assertThat(lines[0]).isEqualTo(
                    "﻿Date,Description,Kind,Landed in,Gross,Taxes,Other deductions,Amount,Estimate,From a pay stub");
            // 24 paychecks, 24 matches and the refund.
            assertThat(lines).hasSize(50);
            assertThat(lines).contains(
                    "2026-01-01,Brightwater Health paycheck,Paycheck,Everyday Checking,3600.00,881.80,232.00,2486.20,,",
                    "2026-01-01,Employer match · Brightwater Health,Employer match,Brightwater 401(k),,,,50.00,,",
                    "2026-03-10,Tax refund,One-off,Everyday Checking,,,,500.00,,",
                    "2026-12-15,Brightwater Health paycheck,Paycheck,Everyday Checking,3600.00,881.80,232.00,2486.20,yes,");
        }

        @Test
        void pdfHasTheFiguresTheGroupsAndEveryLine() throws Exception {
            String body = "{\"name\":\"Income 2026\",\"withReceipts\":true,\"definition\":"
                    + everything("\"accountIds\":[%d,%d]".formatted(checking, retirement)) + "}";

            Download pdf = server.download("/api/reports/export/pdf", body, cookie);

            assertThat(pdf.status()).isEqualTo(200);
            assertThat(pdf.header("Content-Type")).isEqualTo("application/pdf");
            try (PDDocument document = Loader.loadPDF(pdf.body())) {
                String text = new PDFTextStripper().getText(document);
                assertThat(text).contains(
                        "Income 2026", "Jan 1, 2026 – Dec 31, 2026",
                        "Income from paychecks, one-off money in, employer match, interest.",
                        "Only what landed in Everyday Checking or Brightwater 401(k).",
                        "Received", "$60,168.80", "Gross pay", "$86,400.00", "Taxes", "$21,163.20", "Other deductions", "$5,568.00",
                        "Employer match", "$1,200.00", "By month", "March 2026", "Every line", "Tax refund", "$500.00");
            }
        }

        @Test
        void askedToSaveFromThisPcItPutsTheFileInDownloads() throws Exception {
            Reply reply = server.post("/api/reports/export/csv",
                    "{\"name\":\"Income 2026\",\"save\":true,\"definition\":" + everything("") + "}", cookie);

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.data().get("saved").get("fileName").asText()).isEqualTo("Income 2026.csv");
            assertThat(server.downloads.resolve("Income 2026.csv")).content(StandardCharsets.UTF_8).contains("Brightwater Health paycheck");
        }
    }
}
