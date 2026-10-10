package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Loans over real HTTP (spec §5.7): terms, amortization, and escrow that is paid but never
 * lowers the loan. "Today" is Thursday 2026-10-01.
 */
class LoansApiTest extends ApiTestBase {

    /** $2,400.00 a month on the 1st, of which $350.00 is insurance and $250.00 taxes. */
    private static final String TERMS = """
            {"originalPrincipalCents":30000000,"termMonths":360,"startDate":"2024-06-01","paymentCents":240000,
             "paymentDay":1,"escrowInsuranceCents":35000,"escrowTaxesCents":25000}""";

    private long mortgage;

    @BeforeEach
    void aMortgage() throws Exception {
        mortgage = createAccount("Maple Street mortgage", "loan");
    }

    private Reply saveTerms(String body) throws Exception {
        return server.put("/api/accounts/" + mortgage + "/loan", body, cookie);
    }

    private void setRate(int rateBp) throws Exception {
        assertThat(server.post("/api/accounts/" + mortgage + "/rates",
                "{\"rateBp\":%d,\"effectiveDate\":\"2024-06-01\"}".formatted(rateBp), cookie).status()).isEqualTo(201);
    }

    private void assertError(Reply reply, String message) {
        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.error()).isEqualTo(message);
    }

    @Nested
    class Auth {
        @Test
        void everyRouteRequiresASession() throws Exception {
            assertThat(server.get("/api/accounts/" + mortgage + "/loan", null).status()).isEqualTo(401);
            assertThat(server.put("/api/accounts/" + mortgage + "/loan", TERMS, null).status()).isEqualTo(401);
            // Nothing was saved.
            assertThat(server.get("/api/accounts/" + mortgage + "/loan", cookie).data().get("settings").isNull()).isTrue();
        }
    }

    @Nested
    class Terms {
        @Test
        void untilTermsAreEnteredALoanIsOnlyItsBalance() throws Exception {
            enterBalance(mortgage, "2026-10-01", 28640000);

            JsonNode loan = server.get("/api/accounts/" + mortgage + "/loan", cookie).data();

            assertThat(loan.get("settings").isNull()).isTrue();
            assertThat(loan.get("rateBp").isNull()).isTrue();
            assertThat(loan.get("balanceCents").asLong()).isEqualTo(28640000);
            assertThat(loan.get("nextPayment").isNull()).isTrue();
            assertThat(loan.get("years")).isEmpty();
        }

        @Test
        void theNextPaymentIsSplitIntoInterestPrincipalAndEscrow() throws Exception {
            enterBalance(mortgage, "2026-10-01", 28640000);
            setRate(625);

            Reply saved = saveTerms(TERMS);

            assertThat(saved.status()).isEqualTo(200);
            assertThat(saved.changed()).isTrue();
            JsonNode loan = saved.data();
            assertThat(loan.get("settings").get("paymentCents").asLong()).isEqualTo(240000);
            assertThat(loan.get("settings").get("paymentDay").asInt()).isEqualTo(1);
            assertThat(loan.get("settings").get("escrowInsuranceCents").asLong()).isEqualTo(35000);
            assertThat(loan.get("settings").get("escrowTaxesCents").asLong()).isEqualTo(25000);
            assertThat(loan.get("rateBp").asInt()).isEqualTo(625);
            JsonNode next = loan.get("nextPayment");
            assertThat(next.get("date").asText()).isEqualTo("2026-10-01");
            assertThat(next.get("paymentCents").asLong()).isEqualTo(240000);
            assertThat(next.get("escrowCents").asLong()).isEqualTo(60000);
            // 286,400.00 × 6.25% ÷ 12 = 1,491.67; the other 308.33 of the 1,800.00 lowers the loan.
            assertThat(next.get("interestCents").asLong()).isEqualTo(149167);
            assertThat(next.get("principalCents").asLong()).isEqualTo(30833);
            assertThat(loan.get("paysOff").asBoolean()).isTrue();
            assertThat(loan.get("payoffDate").isNull()).isFalse();
            assertThat(loan.get("interestRemainingCents").asLong()).isPositive();
            // The first year listed is this one, ending with what is owed after December's payment.
            assertThat(loan.get("years").get(0).get("year").asInt()).isEqualTo(2026);
            assertThat(loan.get("years").get(0).get("endingCents").asLong()).isLessThan(28640000);
        }

        @Test
        void theStandardPaymentIsWorkedOutFromWhatWasBorrowed() throws Exception {
            setRate(600);

            // The textbook case: $300,000 at 6% over 30 years is $1,798.65 a month before escrow.
            assertThat(saveTerms(TERMS).data().get("standardPaymentCents").asLong()).isEqualTo(179865);
        }

        @Test
        void aPaymentThatDoesNotCoverTheInterestIsSaidNotToPayItOff() throws Exception {
            enterBalance(mortgage, "2026-10-01", 28640000);
            setRate(625);

            JsonNode loan = saveTerms("{\"paymentCents\":100000}").data();

            assertThat(loan.get("paysOff").asBoolean()).isFalse();
            assertThat(loan.get("payoffDate").isNull()).isTrue();
            assertThat(loan.get("interestRemainingCents").isNull()).isTrue();
        }

        @Test
        void thePaymentDayDefaultsToTheDayTheLoanStarted() throws Exception {
            JsonNode loan = saveTerms("{\"paymentCents\":240000,\"startDate\":\"2024-06-15\"}").data();

            assertThat(loan.get("settings").get("paymentDay").asInt()).isEqualTo(15);
            assertThat(loan.get("settings").get("escrowInsuranceCents").asLong()).isZero();
        }

        @Test
        void savingTermsCanBeUndone() throws Exception {
            saveTerms(TERMS);

            assertThat(server.post("/api/undo", "", cookie).status()).isEqualTo(200);

            assertThat(server.get("/api/accounts/" + mortgage + "/loan", cookie).data().get("settings").isNull()).isTrue();
        }

        @Test
        void explainsWhatIsWrong() throws Exception {
            assertError(saveTerms("{}"), "The monthly payment is missing. Please enter an amount.");
            assertError(saveTerms("{\"paymentCents\":50000,\"escrowInsuranceCents\":35000,\"escrowTaxesCents\":25000}"),
                    "The insurance and taxes can't add up to more than the whole payment.");
            assertError(saveTerms("{\"paymentCents\":240000,\"originalPrincipalCents\":0}"),
                    "Please enter the amount borrowed, or leave it empty.");
            assertError(saveTerms("{\"paymentCents\":240000,\"termMonths\":601}"),
                    "Please enter how long the loan runs, from 1 month to 50 years.");
            assertError(saveTerms("{\"paymentCents\":240000,\"paymentDay\":0}"),
                    "Please choose the day of the month the payment is due.");
            assertThat(server.get("/api/accounts/" + mortgage + "/loan", cookie).data().get("settings").isNull()).isTrue();
        }

        @Test
        void anAccountThatIsNotALoanIsNotFound() throws Exception {
            long checking = createAccount("Everyday Checking", "checking");

            assertThat(server.get("/api/accounts/" + checking + "/loan", cookie).status()).isEqualTo(404);
            assertThat(server.put("/api/accounts/" + checking + "/loan", TERMS, cookie).status()).isEqualTo(404);
            assertThat(server.get("/api/accounts/999/loan", cookie).status()).isEqualTo(404);
        }
    }

    @Nested
    class InTheLedger {
        private long checking;

        @BeforeEach
        void aMortgagePaidFromChecking() throws Exception {
            checking = createAccount("Everyday Checking", "checking");
            createSalary(checking);
            enterBalance(checking, "2026-10-01", 500000);
            enterBalance(mortgage, "2026-10-01", 28640000);
            setRate(625);
            saveTerms(TERMS);
            created("/api/items", """
                    {"name":"Mortgage payment","kind":"transfer","amountCents":240000,"payFromAccountId":%d,"payToAccountId":%d,
                     "autopay":true,"schedule":{"frequency":"monthly","day1":1,"startDate":"2026-11-01"}}"""
                    .formatted(checking, mortgage));
        }

        private JsonNode lines(String from, String to) throws Exception {
            return server.get("/api/accounts/" + mortgage + "/transactions?from=" + from + "&to=" + to, cookie).data();
        }

        private long amountOf(JsonNode lines, String kind) {
            for (JsonNode line : lines) {
                if (line.get("kind").asText().equals(kind)) {
                    return line.get("amountCents").asLong();
                }
            }
            throw new AssertionError("No " + kind + " line");
        }

        private long owedAtTheEndOf(String periodStart) throws Exception {
            for (JsonNode account : server.get("/api/pay-periods/" + periodStart, cookie).data().get("accounts")) {
                if (account.get("accountId").asLong() == mortgage) {
                    return account.get("endingCents").asLong();
                }
            }
            throw new AssertionError("No figures for the mortgage");
        }

        @Test
        void aPaymentAddsAMonthsInterestAndOnlyTheRestLowersTheLoan() throws Exception {
            JsonNode november = lines("2026-11-01", "2026-11-30");

            // 286,400.00 × 6.25% ÷ 12 = 1,491.67 of interest; 600.00 of the 2,400.00 is escrow.
            assertThat(amountOf(november, "loan_interest")).isEqualTo(149167);
            assertThat(amountOf(november, "transfer")).isEqualTo(-240000);
            assertThat(amountOf(november, "escrow")).isEqualTo(60000);
            // 286,400.00 + 1,491.67 − 2,400.00 + 600.00
            assertThat(owedAtTheEndOf("2026-11-01")).isEqualTo(28609167);
        }

        @Test
        void checkingStillPaysTheWholePayment() throws Exception {
            JsonNode period = server.get("/api/pay-periods/2026-11-01", cookie).data();

            for (JsonNode account : period.get("accounts")) {
                if (account.get("accountId").asLong() == checking) {
                    // 5,000.00 + three paychecks (Oct 1, Oct 15, Nov 1) − the whole 2,400.00
                    assertThat(account.get("endingCents").asLong()).isEqualTo(500000 + 3 * 248620 - 240000);
                }
            }
        }

        @Test
        void anExtraPaymentGoesWhollyToTheLoan() throws Exception {
            server.post("/api/entries", """
                    {"kind":"transfer","date":"2026-10-20","amountCents":50000,"description":"Extra principal","accountId":%d,"toAccountId":%d}"""
                    .formatted(checking, mortgage), cookie);

            assertThat(owedAtTheEndOf("2026-10-15")).isEqualTo(28590000);
        }

        @Test
        void aHomesEquityFollowsTheLoanAsItIsPaidDown() throws Exception {
            Reply home = server.post("/api/assets", """
                    {"name":"Maple Street house","kind":"home","color":"#0369a1","linkedLoanAccountId":%d,"valueCents":40000000}"""
                    .formatted(mortgage), cookie);

            // Today, before the first payment: what was entered.
            assertThat(home.data().get("asset").get("owedCents").asLong()).isEqualTo(28640000);
            assertThat(home.data().get("asset").get("equityCents").asLong()).isEqualTo(11360000);
        }

        @Test
        void aLoanWithoutTermsIsOnlyLoweredByWhatIsPaid() throws Exception {
            long carLoan = createAccount("Car loan", "loan");
            enterBalance(carLoan, "2026-10-01", 1486000);
            created("/api/items", """
                    {"name":"Car payment","kind":"transfer","amountCents":41200,"payFromAccountId":%d,"payToAccountId":%d,
                     "autopay":true,"schedule":{"frequency":"monthly","day1":5,"startDate":"2026-11-05"}}"""
                    .formatted(checking, carLoan));

            for (JsonNode account : server.get("/api/pay-periods/2026-11-01", cookie).data().get("accounts")) {
                if (account.get("accountId").asLong() == carLoan) {
                    assertThat(account.get("endingCents").asLong()).isEqualTo(1486000 - 41200);
                }
            }
        }
    }
}
