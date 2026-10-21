package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Typing over an interest line in an account's transactions (spec §5.4; Rachel, 2026-10-19:
 * "edit an interest amount in an account if it's wrong"), over real HTTP against a temporary
 * database. "Today" is Thursday 2026-10-01. Savings held $5,000.00 on Aug 1 at 4.10% APY,
 * credited on the last day of the month, so Aug 31 and Sep 30 have been paid and Oct 31 is to come.
 */
class TypedInterestApiTest extends ApiTestBase {

    long savings;

    @BeforeEach
    void savingsEarningInterest() throws Exception {
        savings = createAccount("Rainy Day Savings", "savings");
        enterBalance(savings, "2026-08-01", 500000);
        rate(savings, 410);
    }

    private void rate(long accountId, int rateBp) throws Exception {
        assertThat(server.post("/api/accounts/" + accountId + "/rates",
                "{\"rateBp\":%d,\"effectiveDate\":\"2026-01-01\"}".formatted(rateBp), cookie).status()).isEqualTo(201);
    }

    private Reply typeOver(long accountId, String date, String body) throws Exception {
        return server.put("/api/accounts/" + accountId + "/transactions/interest/" + date, body, cookie);
    }

    private Reply putBack(long accountId, String date) throws Exception {
        return server.delete("/api/accounts/" + accountId + "/transactions/interest/" + date, cookie);
    }

    /** The account's line of {@code kind} dated {@code date}; {@code null} when there is none. */
    private JsonNode line(long accountId, String kind, String date) throws Exception {
        JsonNode lines = server.get("/api/accounts/" + accountId + "/transactions?from=2026-08-01&to=2026-12-31", cookie).data();
        for (JsonNode line : lines) {
            if (line.get("kind").asText().equals(kind) && line.get("date").asText().equals(date)) {
                return line;
            }
        }
        return null;
    }

    private void assertError(Reply reply, String message) {
        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.error()).isEqualTo(message);
    }

    @Nested
    class Auth {
        @Test
        void typingOverAndPuttingBackNeedASession() throws Exception {
            assertThat(server.put("/api/accounts/" + savings + "/transactions/interest/2026-09-30", "{\"amountCents\":1500}", null).status())
                    .isEqualTo(401);
            assertThat(server.delete("/api/accounts/" + savings + "/transactions/interest/2026-09-30", null).status()).isEqualTo(401);
        }
    }

    @Nested
    class Savings {
        @Test
        void theTypedAmountBecomesTheMonthsInterestAndSaysWhatWasEstimated() throws Exception {
            long estimated = line(savings, "interest", "2026-09-30").get("amountCents").asLong();
            assertThat(line(savings, "interest", "2026-09-30").get("estimatedCents").isNull()).isTrue();
            long octoberBefore = line(savings, "interest", "2026-10-31").get("amountCents").asLong();

            assertThat(typeOver(savings, "2026-09-30", "{\"amountCents\":51500}").status()).isEqualTo(200);

            JsonNode september = line(savings, "interest", "2026-09-30");
            assertThat(september.get("amountCents").asLong()).isEqualTo(51500);
            assertThat(september.get("estimatedCents").asLong()).isEqualTo(estimated);
            // October, still to come, is worked out on the balance September left, so it moves (by a few cents for every
            // dollar September changed: interest on interest is small).
            JsonNode october = line(savings, "interest", "2026-10-31");
            assertThat(october.get("estimatedCents").isNull()).isTrue();
            assertThat(october.get("amountCents").asLong()).isNotEqualTo(octoberBefore);
        }

        @Test
        void interestStillToComeCannotBeTypedOverAndKeepsFollowingTheBalance() throws Exception {
            Reply reply = typeOver(savings, "2026-10-31", "{\"amountCents\":1500}");

            assertThat(reply.status()).isEqualTo(409);
            assertThat(reply.error()).isEqualTo(
                    "This interest hasn't been added yet, so Budget keeps working it out. You can type the real amount once the day has come.");
            long before = line(savings, "interest", "2026-10-31").get("amountCents").asLong();
            enterBalance(savings, "2026-10-01", 600000);
            assertThat(line(savings, "interest", "2026-10-31").get("amountCents").asLong()).isGreaterThan(before);
        }

        @Test
        void thePayPeriodNoLongerCallsItAnEstimate() throws Exception {
            // Paid on the 1st and 15th, so Sep 15–30 is a pay period.
            createSalary(createAccount("Everyday Checking", "checking"));
            typeOver(savings, "2026-09-30", "{\"amountCents\":1500}");

            JsonNode period = server.get("/api/pay-periods/2026-09-15", cookie).data();
            JsonNode interest = period.get("interest").get(0);
            assertThat(interest.get("amountCents").asLong()).isEqualTo(1500);
            assertThat(interest.get("estimate").asBoolean()).isFalse();
        }

        @Test
        void typingOverAgainKeepsWhatWasFirstEstimated() throws Exception {
            long estimated = line(savings, "interest", "2026-09-30").get("amountCents").asLong();
            typeOver(savings, "2026-09-30", "{\"amountCents\":1500}");
            typeOver(savings, "2026-09-30", "{\"amountCents\":1600}");

            JsonNode september = line(savings, "interest", "2026-09-30");
            assertThat(september.get("amountCents").asLong()).isEqualTo(1600);
            assertThat(september.get("estimatedCents").asLong()).isEqualTo(estimated);
        }

        @Test
        void zeroIsAllowedAndTheLineStaysToBePutBack() throws Exception {
            assertThat(typeOver(savings, "2026-09-30", "{\"amountCents\":0}").status()).isEqualTo(200);

            assertThat(line(savings, "interest", "2026-09-30").get("amountCents").asLong()).isZero();
        }

        @Test
        void puttingTheEstimateBack() throws Exception {
            long estimated = line(savings, "interest", "2026-09-30").get("amountCents").asLong();
            typeOver(savings, "2026-09-30", "{\"amountCents\":1500}");

            assertThat(putBack(savings, "2026-09-30").status()).isEqualTo(200);

            JsonNode september = line(savings, "interest", "2026-09-30");
            assertThat(september.get("amountCents").asLong()).isEqualTo(estimated);
            assertThat(september.get("estimatedCents").isNull()).isTrue();
            // Nothing is typed over any more.
            assertThat(putBack(savings, "2026-09-30").status()).isEqualTo(404);
        }

        @Test
        void undoTakesTheTypedAmountBack() throws Exception {
            long estimated = line(savings, "interest", "2026-09-30").get("amountCents").asLong();
            typeOver(savings, "2026-09-30", "{\"amountCents\":1500}");

            assertThat(server.post("/api/undo", null, cookie).status()).isEqualTo(200);

            assertThat(line(savings, "interest", "2026-09-30").get("amountCents").asLong()).isEqualTo(estimated);
        }

        @Test
        void aDayWithNoInterestCannotBeTypedOver() throws Exception {
            assertThat(typeOver(savings, "2026-09-15", "{\"amountCents\":1500}").status()).isEqualTo(404);
            assertThat(typeOver(999, "2026-09-30", "{\"amountCents\":1500}").status()).isEqualTo(404);
            long checking = createAccount("Everyday Checking", "checking");
            enterBalance(checking, "2026-08-01", 100000);
            // No rate, so nothing is estimated to type over.
            assertThat(typeOver(checking, "2026-09-30", "{\"amountCents\":1500}").status()).isEqualTo(404);
        }

        @Test
        void theAmountMustBeThereAndNotLessThanZero() throws Exception {
            assertError(typeOver(savings, "2026-09-30", "{}"), "The interest is missing. Please enter an amount.");
            assertThat(typeOver(savings, "2026-09-30", "{\"amountCents\":-1}").status()).isEqualTo(400);
            assertThat(typeOver(savings, "not-a-date", "{\"amountCents\":1500}").status()).isEqualTo(400);
        }
    }

    @Nested
    class OtherKinds {
        @Test
        void anInvestmentsMonthOfGrowthIsTypedOverWholeOnceTheMonthIsOver() throws Exception {
            long retirement = createAccount("Brightwater 401(k)", "investment");
            enterBalance(retirement, "2026-08-01", 4000000);
            rate(retirement, 700);
            JsonNode before = line(retirement, "growth", "2026-08-31");

            // Any day of the month names the month.
            assertThat(typeOver(retirement, "2026-08-12", "{\"amountCents\":20000}").status()).isEqualTo(200);

            JsonNode august = line(retirement, "growth", "2026-08-31");
            assertThat(august.get("amountCents").asLong()).isEqualTo(20000);
            assertThat(august.get("estimatedCents").asLong()).isEqualTo(before.get("amountCents").asLong());
            assertThat(putBack(retirement, "2026-08-31").status()).isEqualTo(200);
            assertThat(line(retirement, "growth", "2026-08-31").get("amountCents").asLong()).isEqualTo(before.get("amountCents").asLong());
            // October has only begun.
            Reply october = typeOver(retirement, "2026-10-01", "{\"amountCents\":20000}");
            assertThat(october.status()).isEqualTo(409);
            assertThat(october.error()).startsWith("This month isn't over yet");
        }

        @Test
        void aCardsInterestAtTheStatementClose() throws Exception {
            long card = createAccount("Travel Rewards Visa", "credit_card");
            enterBalance(card, "2026-08-01", 100000);
            rate(card, 2449);
            assertThat(server.put("/api/cards/" + card + "/settings", "{\"statementCloseDay\":20,\"dueDay\":13}", cookie).status())
                    .isEqualTo(200);
            // 1,000.00 × 24.49% ÷ 12.
            assertThat(line(card, "card_interest", "2026-08-20").get("amountCents").asLong()).isEqualTo(2041);

            assertThat(typeOver(card, "2026-08-20", "{\"amountCents\":1999}").status()).isEqualTo(200);

            JsonNode august = line(card, "card_interest", "2026-08-20");
            assertThat(august.get("amountCents").asLong()).isEqualTo(1999);
            assertThat(august.get("estimatedCents").asLong()).isEqualTo(2041);
        }

        @Test
        void aLoansMonthOfInterest() throws Exception {
            long mortgage = createAccount("Maple Street mortgage", "loan");
            enterBalance(mortgage, "2026-08-15", 10000000);
            rate(mortgage, 600);
            assertThat(server.put("/api/accounts/" + mortgage + "/loan", """
                    {"originalPrincipalCents":30000000,"termMonths":360,"startDate":"2024-06-01","paymentCents":240000,
                     "paymentDay":1,"escrowInsuranceCents":35000,"escrowTaxesCents":25000}""", cookie).status()).isEqualTo(200);
            // 100,000.00 × 6% ÷ 12.
            assertThat(line(mortgage, "loan_interest", "2026-09-01").get("amountCents").asLong()).isEqualTo(50000);

            assertThat(typeOver(mortgage, "2026-09-01", "{\"amountCents\":49000}").status()).isEqualTo(200);

            JsonNode september = line(mortgage, "loan_interest", "2026-09-01");
            assertThat(september.get("amountCents").asLong()).isEqualTo(49000);
            assertThat(september.get("estimatedCents").asLong()).isEqualTo(50000);
        }

        @Test
        void deletingTheAccountTakesWhatWasTypedWithIt() throws Exception {
            typeOver(savings, "2026-09-30", "{\"amountCents\":1500}");

            assertThat(server.delete("/api/accounts/" + savings, cookie).status()).isEqualTo(200);
            assertThat(putBack(savings, "2026-09-30").status()).isEqualTo(404);
        }
    }
}
