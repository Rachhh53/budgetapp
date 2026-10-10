package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Credit cards over real HTTP (spec §5.6): settings, the payment that follows the card,
 * statements, estimated interest, promotions and the payoff helper.
 * "Today" is Thursday 2026-10-01; paydays are the 1st and 15th.
 */
class CardsApiTest extends ApiTestBase {

    private static final String PROMO = """
            {"label":"Balance transfer","kind":"balance_transfer","rateBp":0,"startDate":"2026-03-15",
             "endDate":"%s","originalBalanceCents":300000%s}""";

    private long checking;
    private long card;

    @BeforeEach
    void accounts() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        card = createAccount("Travel Rewards Visa", "credit_card");
    }

    private String settings(String mode, String extra) {
        return "{\"statementCloseDay\":20,\"dueDay\":13,\"paymentMode\":\"%s\"%s}".formatted(mode, extra);
    }

    private Reply saveSettings(String mode, String extra) throws Exception {
        return server.put("/api/cards/" + card + "/settings", settings(mode, extra), cookie);
    }

    private String paidFromChecking() {
        return ",\"payFromAccountId\":%d,\"autopay\":true".formatted(checking);
    }

    private void setApr(int rateBp) throws Exception {
        assertThat(server.post("/api/accounts/" + card + "/rates",
                "{\"rateBp\":%d,\"effectiveDate\":\"2026-01-01\"}".formatted(rateBp), cookie).status()).isEqualTo(201);
    }

    private Reply addPromo(String endDate, String extra) throws Exception {
        return server.post("/api/cards/" + card + "/promos", PROMO.formatted(endDate, extra), cookie);
    }

    private JsonNode detail() throws Exception {
        return server.get("/api/cards/" + card, cookie).data();
    }

    private JsonNode period(String start) throws Exception {
        return server.get("/api/pay-periods/" + start, cookie).data();
    }

    private static long ending(JsonNode period, long accountId) {
        for (JsonNode account : period.get("accounts")) {
            if (account.get("accountId").asLong() == accountId) {
                return account.get("endingCents").asLong();
            }
        }
        throw new AssertionError("No figures for account " + accountId);
    }

    private JsonNode transactions(String from, String to) throws Exception {
        return server.get("/api/accounts/" + card + "/transactions?from=" + from + "&to=" + to, cookie).data();
    }

    private static JsonNode ofKind(JsonNode lines, String kind) {
        for (JsonNode line : lines) {
            if (line.get("kind").asText().equals(kind)) {
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
        void everyRouteRequiresASession() throws Exception {
            String base = "/api/cards/" + card;

            assertThat(server.get("/api/cards", null).status()).isEqualTo(401);
            assertThat(server.get(base, null).status()).isEqualTo(401);
            assertThat(server.put(base + "/settings", settings("minimum", ""), null).status()).isEqualTo(401);
            assertThat(server.post(base + "/statements", "{\"closeDate\":\"2026-09-20\",\"balanceCents\":1}", null).status()).isEqualTo(401);
            assertThat(server.delete(base + "/statements/1", null).status()).isEqualTo(401);
            assertThat(server.post(base + "/promos", PROMO.formatted("2027-03-15", ""), null).status()).isEqualTo(401);
            assertThat(server.put(base + "/promos/1", PROMO.formatted("2027-03-15", ""), null).status()).isEqualTo(401);
            assertThat(server.delete(base + "/promos/1", null).status()).isEqualTo(401);
            assertThat(server.post(base + "/promos/1/payoff", "", null).status()).isEqualTo(401);
            // Nothing was changed by any of those.
            assertThat(detail().get("card").get("settings").isNull()).isTrue();
            assertThat(detail().get("promos")).isEmpty();
        }
    }

    @Nested
    class Listing {
        @Test
        void onlyCreditCardsAreListedWithWhatIsOwedToday() throws Exception {
            enterBalance(card, "2026-10-01", 68248);
            createAccount("Mortgage", "loan");

            JsonNode cards = server.get("/api/cards", cookie).data();

            assertThat(cards).hasSize(1);
            assertThat(cards.get(0).get("account").get("name").asText()).isEqualTo("Travel Rewards Visa");
            assertThat(cards.get(0).get("balanceCents").asLong()).isEqualTo(68248);
            assertThat(cards.get(0).get("settings").isNull()).isTrue();
            assertThat(cards.get(0).get("aprBp").isNull()).isTrue();
            assertThat(cards.get(0).get("nextPayment").isNull()).isTrue();
        }

        @Test
        void anAccountThatIsNotACardIsNotFound() throws Exception {
            assertThat(server.get("/api/cards/" + checking, cookie).status()).isEqualTo(404);
            assertThat(server.put("/api/cards/" + checking + "/settings", settings("minimum", ""), cookie).status()).isEqualTo(404);
            assertThat(server.get("/api/cards/999", cookie).status()).isEqualTo(404);
        }
    }

    @Nested
    class SettingsAndThePayment {
        @Test
        void settingsAreSavedAndShownWithTheRegularApr() throws Exception {
            setApr(2449);

            Reply saved = saveSettings("minimum", ",\"monthlyEstimateCents\":45000");

            assertThat(saved.status()).isEqualTo(200);
            assertThat(saved.changed()).isTrue();
            JsonNode found = saved.data().get("card");
            assertThat(found.get("settings").get("statementCloseDay").asInt()).isEqualTo(20);
            assertThat(found.get("settings").get("dueDay").asInt()).isEqualTo(13);
            assertThat(found.get("settings").get("paymentMode").asText()).isEqualTo("minimum");
            assertThat(found.get("settings").get("monthlyEstimateCents").asLong()).isEqualTo(45000);
            assertThat(found.get("aprBp").asInt()).isEqualTo(2449);
            assertThat(saved.data().get("nextCloseDate").asText()).isEqualTo("2026-10-20");
            assertThat(saved.data().get("nextDueDate").asText()).isEqualTo("2026-11-13");
            // With no account to pay from, no payment is scheduled.
            assertThat(server.get("/api/items", cookie).data()).isEmpty();
        }

        @Test
        void choosingAnAccountToPayFromAddsTheCardsOwnRecurringPayment() throws Exception {
            Reply saved = saveSettings("statement_balance", paidFromChecking());

            JsonNode items = server.get("/api/items", cookie).data();
            assertThat(items).hasSize(1);
            JsonNode payment = items.get(0);
            assertThat(payment.get("name").asText()).isEqualTo("Travel Rewards Visa payment");
            assertThat(payment.get("kind").asText()).isEqualTo("card_payment");
            assertThat(payment.get("payFromAccountId").asLong()).isEqualTo(checking);
            assertThat(payment.get("payToAccountId").asLong()).isEqualTo(card);
            assertThat(payment.get("autopay").asBoolean()).isTrue();
            assertThat(payment.get("schedule").get("frequency").asText()).isEqualTo("monthly");
            assertThat(payment.get("schedule").get("day1").asInt()).isEqualTo(13);
            assertThat(payment.get("paymentOfCardId").asLong()).isEqualTo(card);
            assertThat(payment.get("followsCard").asBoolean()).isTrue();
            assertThat(saved.data().get("card").get("settings").get("paymentItemId").asLong()).isEqualTo(payment.get("id").asLong());
        }

        @Test
        void changingTheSettingsChangesThatSamePayment() throws Exception {
            saveSettings("statement_balance", paidFromChecking());

            saveSettings("fixed", ",\"fixedPaymentCents\":24000,\"payFromAccountId\":" + checking);

            JsonNode items = server.get("/api/items", cookie).data();
            assertThat(items).hasSize(1);
            assertThat(items.get(0).get("amountCents").asLong()).isEqualTo(24000);
            assertThat(items.get(0).get("estimate").asBoolean()).isFalse();
            assertThat(items.get(0).get("autopay").asBoolean()).isFalse();
            assertThat(items.get(0).get("followsCard").asBoolean()).isFalse();
            assertThat(items.get(0).get("paymentOfCardId").asLong()).isEqualTo(card);
        }

        @Test
        void takingTheAccountAwayArchivesThePaymentAndChoosingOneAgainBringsItBack() throws Exception {
            saveSettings("statement_balance", paidFromChecking());

            saveSettings("statement_balance", "");
            assertThat(server.get("/api/items", cookie).data().get(0).get("archived").asBoolean()).isTrue();

            saveSettings("statement_balance", paidFromChecking());
            JsonNode items = server.get("/api/items", cookie).data();
            assertThat(items).hasSize(1);
            assertThat(items.get(0).get("archived").asBoolean()).isFalse();
        }

        @Test
        void savingSettingsCanBeUndoneTogetherWithThePaymentItAdded() throws Exception {
            saveSettings("statement_balance", paidFromChecking());

            assertThat(server.post("/api/undo", "", cookie).status()).isEqualTo(200);

            assertThat(detail().get("card").get("settings").isNull()).isTrue();
            assertThat(server.get("/api/items", cookie).data()).isEmpty();
        }

        @Test
        void explainsWhatIsWrong() throws Exception {
            String base = "/api/cards/" + card + "/settings";
            long savings = createAccount("Rainy Day", "savings");
            long loan = createAccount("Car loan", "loan");

            assertError(server.put(base, "{\"dueDay\":13,\"paymentMode\":\"minimum\"}", cookie),
                    "Please choose the day of the month the statement closes.");
            assertError(server.put(base, "{\"statementCloseDay\":20,\"dueDay\":32,\"paymentMode\":\"minimum\"}", cookie),
                    "Please choose the day of the month the payment is due.");
            assertError(saveSettings("whenever", ""), "Please choose how this card is paid from the list.");
            assertError(saveSettings("fixed", ""), "The amount you pay each month is missing. Please enter an amount.");
            assertError(saveSettings("fixed", ",\"fixedPaymentCents\":0"), "Please enter the amount you pay each month.");
            assertError(saveSettings("minimum", ",\"monthlyEstimateCents\":-1"), "The monthly estimate can't be less than zero.");
            assertError(saveSettings("minimum", ",\"payFromAccountId\":" + loan),
                    "Please choose a checking or savings account to pay this card from.");
            assertError(saveSettings("minimum", ",\"payFromAccountId\":999"),
                    "Please choose a checking or savings account to pay this card from.");
            assertThat(detail().get("card").get("settings").isNull()).isTrue();
            // A savings account is fine.
            assertThat(saveSettings("minimum", ",\"payFromAccountId\":" + savings).status()).isEqualTo(200);
        }
    }

    @Nested
    class ThePaymentFollowsTheCard {
        @BeforeEach
        void payAndBalances() throws Exception {
            createSalary(checking);
            enterBalance(checking, "2026-10-01", 300000);
            enterBalance(card, "2026-10-01", 68248);
        }

        @Test
        void aStatementBalancePaymentIsWhatTheCardOwed() throws Exception {
            saveSettings("statement_balance", paidFromChecking());

            JsonNode period = period("2026-10-01");

            JsonNode payment = period.get("occurrences").get(0);
            assertThat(payment.get("name").asText()).isEqualTo("Travel Rewards Visa payment");
            assertThat(payment.get("dueDate").asText()).isEqualTo("2026-10-13");
            assertThat(payment.get("amountCents").asLong()).isEqualTo(68248);
            assertThat(payment.get("estimate").asBoolean()).isTrue();
            // 3,000.00 + 2,486.20 − 682.48
            assertThat(ending(period, checking)).isEqualTo(480372);
            assertThat(ending(period, card)).isZero();
        }

        @Test
        void chargesMadeSinceThenWaitForTheNextStatement() throws Exception {
            saveSettings("statement_balance", paidFromChecking());
            server.post("/api/entries", """
                    {"kind":"money_out","date":"2026-10-05","amountCents":10000,"description":"Vet visit","accountId":%d}"""
                    .formatted(card), cookie);

            assertThat(period("2026-10-01").get("occurrences").get(0).get("amountCents").asLong()).isEqualTo(68248);
            assertThat(ending(period("2026-10-01"), card)).isEqualTo(10000);
            // November's payment is October's statement: the 100.00 charge.
            JsonNode november = period("2026-11-01");
            assertThat(november.get("occurrences").get(0).get("dueDate").asText()).isEqualTo("2026-11-13");
            assertThat(november.get("occurrences").get(0).get("amountCents").asLong()).isEqualTo(10000);
            assertThat(ending(november, card)).isZero();
        }

        @Test
        void aFullBalancePaymentIsEverythingOwedThatDay() throws Exception {
            saveSettings("full_balance", paidFromChecking());
            server.post("/api/entries", """
                    {"kind":"money_out","date":"2026-10-05","amountCents":10000,"description":"Vet visit","accountId":%d}"""
                    .formatted(card), cookie);

            assertThat(period("2026-10-01").get("occurrences").get(0).get("amountCents").asLong()).isEqualTo(78248);
        }

        @Test
        void theNextPaymentShowsOnTheCard() throws Exception {
            saveSettings("statement_balance", paidFromChecking());

            JsonNode next = server.get("/api/cards", cookie).data().get(0).get("nextPayment");

            assertThat(next.get("date").asText()).isEqualTo("2026-10-13");
            assertThat(next.get("amountCents").asLong()).isEqualTo(68248);
            assertThat(next.get("estimate").asBoolean()).isTrue();
            assertThat(next.get("autopay").asBoolean()).isTrue();
            assertThat(next.get("payFromAccountId").asLong()).isEqualTo(checking);
        }

        @Test
        void anAmountTypedOverThePaymentStands() throws Exception {
            saveSettings("statement_balance", paidFromChecking());
            long itemId = server.get("/api/items", cookie).data().get(0).get("id").asLong();

            Reply typed = server.put("/api/items/" + itemId + "/occurrences/2026-10-13", "{\"amountCents\":50000}", cookie);

            assertThat(typed.status()).isEqualTo(200);
            JsonNode period = period("2026-10-01");
            assertThat(period.get("occurrences").get(0).get("amountCents").asLong()).isEqualTo(50000);
            assertThat(period.get("occurrences").get(0).get("estimate").asBoolean()).isFalse();
            // What the card had worked out is kept as the projected amount.
            assertThat(period.get("occurrences").get(0).get("projectedCents").asLong()).isEqualTo(68248);
            assertThat(ending(period, card)).isEqualTo(18248);
        }

        @Test
        void aFixedPaymentIsItsOwnAmount() throws Exception {
            saveSettings("fixed", ",\"fixedPaymentCents\":24000,\"payFromAccountId\":" + checking);

            JsonNode period = period("2026-10-01");

            assertThat(period.get("occurrences").get(0).get("amountCents").asLong()).isEqualTo(24000);
            assertThat(period.get("occurrences").get(0).get("estimate").asBoolean()).isFalse();
            assertThat(ending(period, card)).isEqualTo(44248);
        }
    }

    @Nested
    class InterestAndEstimates {
        @Test
        void aBalanceCarriedPastTheStatementIsChargedEstimatedInterest() throws Exception {
            enterBalance(card, "2026-10-01", 100000);
            setApr(2449);
            saveSettings("minimum", "");

            JsonNode interest = ofKind(transactions("2026-10-01", "2026-10-31"), "card_interest");

            // 1,000.00 × 24.49% ÷ 12 = 20.41, on the day the statement closes.
            assertThat(interest.get("date").asText()).isEqualTo("2026-10-20");
            assertThat(interest.get("amountCents").asLong()).isEqualTo(2041);
            assertThat(interest.get("label").asText()).isEqualTo("Estimated interest");
        }

        @Test
        void withoutSettingsNothingIsEstimated() throws Exception {
            enterBalance(card, "2026-10-01", 100000);
            setApr(2449);

            assertThat(ofKind(transactions("2026-10-01", "2026-12-31"), "card_interest")).isNull();
        }

        @Test
        void aCardThatIsNotItemizedTakesItsMonthlyEstimateOnTheClosingDate() throws Exception {
            enterBalance(card, "2026-10-01", 0);
            saveSettings("full_balance", ",\"monthlyEstimateCents\":45000");

            JsonNode lines = transactions("2026-10-01", "2026-10-31");

            JsonNode estimate = ofKind(lines, "card_estimate");
            assertThat(estimate.get("date").asText()).isEqualTo("2026-10-20");
            assertThat(estimate.get("amountCents").asLong()).isEqualTo(45000);
            assertThat(estimate.get("label").asText()).isEqualTo("Estimated spending");
        }
    }

    @Nested
    class Statements {
        @BeforeEach
        void cardSettings() throws Exception {
            saveSettings("statement_balance", "");
        }

        private Reply addStatement(String body) throws Exception {
            return server.post("/api/cards/" + card + "/statements", body, cookie);
        }

        @Test
        void aStatementIsTheRealBalanceFromTheDayAfterItCloses() throws Exception {
            Reply added = addStatement("{\"closeDate\":\"2026-09-20\",\"balanceCents\":51230,\"minimumDueCents\":3500}");

            assertThat(added.status()).isEqualTo(201);
            assertThat(added.changed()).isTrue();
            // Nobody entered a balance for the card, yet it now owes what the statement said.
            assertThat(added.data().get("card").get("balanceCents").asLong()).isEqualTo(51230);
            JsonNode statements = added.data().get("statements");
            assertThat(statements).hasSize(2);
            // The next statement, still being built, comes first.
            assertThat(statements.get(0).get("status").asText()).isEqualTo("planned");
            assertThat(statements.get(0).get("closeDate").asText()).isEqualTo("2026-10-20");
            assertThat(statements.get(0).get("id").isNull()).isTrue();
            assertThat(statements.get(1).get("entered").asBoolean()).isTrue();
            assertThat(statements.get(1).get("balanceCents").asLong()).isEqualTo(51230);
            assertThat(statements.get(1).get("minimumDueCents").asLong()).isEqualTo(3500);
            // Due on the card's due day, the first one after it closed.
            assertThat(statements.get(1).get("dueDate").asText()).isEqualTo("2026-10-13");
            assertThat(statements.get(1).get("status").asText()).isEqualTo("due");
        }

        @Test
        void theNextPaymentIsTheStatementEvenWhenMoreIsOwedByNow() throws Exception {
            saveSettings("statement_balance", paidFromChecking());
            addStatement("{\"closeDate\":\"2026-09-20\",\"balanceCents\":51230,\"minimumDueCents\":3500}");
            // Charges since it closed: the balance entered today is higher than the statement.
            enterBalance(card, "2026-10-01", 90000);

            JsonNode shown = detail();

            JsonNode next = shown.get("card").get("nextPayment");
            assertThat(next.get("date").asText()).isEqualTo("2026-10-13");
            assertThat(next.get("amountCents").asLong()).isEqualTo(51230);
            // It is the figure on a real statement, so it is not an estimate.
            assertThat(next.get("estimate").asBoolean()).isFalse();
            // What is left closes with the next statement.
            assertThat(shown.get("statements").get(0).get("balanceCents").asLong()).isEqualTo(90000 - 51230);
            assertThat(ofKind(transactions("2026-10-01", "2026-10-31"), "card_payment").get("amountCents").asLong()).isEqualTo(-51230);
        }

        @Test
        void theSameClosingDateAgainReplacesIt() throws Exception {
            addStatement("{\"closeDate\":\"2026-09-20\",\"balanceCents\":51230}");

            Reply corrected = addStatement("{\"closeDate\":\"2026-09-20\",\"balanceCents\":51320,\"dueDate\":\"2026-10-15\"}");

            assertThat(corrected.data().get("statements")).hasSize(2);
            assertThat(corrected.data().get("statements").get(1).get("balanceCents").asLong()).isEqualTo(51320);
            assertThat(corrected.data().get("statements").get(1).get("dueDate").asText()).isEqualTo("2026-10-15");
        }

        @Test
        void whatWasPaidSinceItClosedSaysWhetherItIsPaid() throws Exception {
            addStatement("{\"closeDate\":\"2026-09-20\",\"balanceCents\":51230}");
            String payment = """
                    {"kind":"transfer","date":"2026-09-28","amountCents":%d,"description":"Card payment","accountId":%d,"toAccountId":%d}""";

            server.post("/api/entries", payment.formatted(20000, checking, card), cookie);
            JsonNode partly = detail().get("statements").get(1);
            assertThat(partly.get("status").asText()).isEqualTo("part_paid");
            assertThat(partly.get("paidCents").asLong()).isEqualTo(20000);

            server.post("/api/entries", payment.formatted(31230, checking, card), cookie);
            assertThat(detail().get("statements").get(1).get("status").asText()).isEqualTo("paid");
        }

        @Test
        void aStatementCanBeRemoved() throws Exception {
            Reply added = addStatement("{\"closeDate\":\"2026-09-20\",\"balanceCents\":51230}");
            long id = added.data().get("statements").get(1).get("id").asLong();

            Reply removed = server.delete("/api/cards/" + card + "/statements/" + id, cookie);

            assertThat(removed.status()).isEqualTo(200);
            assertThat(removed.data().get("statements")).hasSize(1);
            assertThat(server.delete("/api/cards/" + card + "/statements/" + id, cookie).status()).isEqualTo(404);
        }

        @Test
        void explainsWhatIsWrongWithAStatement() throws Exception {
            assertError(addStatement("{\"balanceCents\":51230}"), "Please choose the date this statement closed.");
            assertError(addStatement("{\"closeDate\":\"2026-10-02\",\"balanceCents\":51230}"),
                    "A statement needs a closing date of today or earlier.");
            assertError(addStatement("{\"closeDate\":\"2026-09-20\"}"), "The statement balance is missing. Please enter an amount.");
            assertError(addStatement("{\"closeDate\":\"2026-09-20\",\"balanceCents\":51230,\"minimumDueCents\":-1}"),
                    "The minimum payment can't be less than zero.");
            assertError(addStatement("{\"closeDate\":\"2026-09-20\",\"balanceCents\":51230,\"dueDate\":\"2026-09-20\"}"),
                    "A statement is due after it closes. Please check the two dates.");
            // Only the statement still being built is listed.
            assertThat(detail().get("statements")).allSatisfy(statement -> assertThat(statement.get("entered").asBoolean()).isFalse());
        }

        @Test
        void aCardWithoutSettingsNeedsTheDueDate() throws Exception {
            long other = createAccount("Store card", "credit_card");

            assertError(server.post("/api/cards/" + other + "/statements", "{\"closeDate\":\"2026-09-20\",\"balanceCents\":51230}", cookie),
                    "Please choose the date this statement is due.");
            assertThat(server.post("/api/cards/" + other + "/statements",
                    "{\"closeDate\":\"2026-09-20\",\"balanceCents\":51230,\"dueDate\":\"2026-10-15\"}", cookie).status()).isEqualTo(201);
        }
    }

    @Nested
    class Promos {
        @BeforeEach
        void aCardWithABalance() throws Exception {
            createSalary(checking);
            enterBalance(card, "2026-10-01", 240000);
            setApr(2449);
        }

        @Test
        void aPromotionShowsWhatIsLeftAndWhatClearingItTakes() throws Exception {
            Reply added = addPromo("2027-03-15", "");

            assertThat(added.status()).isEqualTo(201);
            assertThat(added.changed()).isTrue();
            JsonNode promo = added.data().get("promos").get(0);
            assertThat(promo.get("label").asText()).isEqualTo("Balance transfer");
            assertThat(promo.get("kind").asText()).isEqualTo("balance_transfer");
            assertThat(promo.get("remainingCents").asLong()).isEqualTo(240000);
            assertThat(promo.get("paidPercent").asInt()).isEqualTo(20);
            assertThat(promo.get("daysLeft").asLong()).isEqualTo(165);
            assertThat(promo.get("warning").asText()).isEqualTo("none");
            // Sixty days before March 15.
            assertThat(promo.get("warnFrom").asText()).isEqualTo("2027-01-14");
            // Spec §5.6: $2,400 before Mar 15, 2027 with 10 paydays left → $240.00.
            assertThat(promo.get("paydaysLeft").asInt()).isEqualTo(10);
            assertThat(promo.get("perPaycheckCents").asLong()).isEqualTo(24000);
            assertThat(promo.get("payoffItemId").isNull()).isTrue();
            // What is left would cost 2,400.00 × 24.49% ÷ 12 = 48.98 a month afterwards.
            assertThat(promo.get("rateAfterEffectiveBp").asInt()).isEqualTo(2449);
            assertThat(promo.get("monthlyInterestIfLeftCents").asLong()).isEqualTo(4898);
            assertThat(promo.get("deferredInterestCents").asLong()).isZero();
            // It is also the card's headline promotion.
            assertThat(added.data().get("card").get("promo").get("id").asLong()).isEqualTo(promo.get("id").asLong());
        }

        @Test
        void aPromotionCostsNothingWhileItRuns() throws Exception {
            saveSettings("minimum", "");
            addPromo("2027-03-15", "");

            assertThat(ofKind(transactions("2026-10-01", "2027-03-19"), "card_interest")).isNull();
            // March's statement closes after it ended.
            assertThat(ofKind(transactions("2027-03-20", "2027-03-31"), "card_interest").get("amountCents").asLong()).isEqualTo(4898);
        }

        @Test
        void theWarningStartsTheLeadTimeBeforeItEndsAndIsStrongerForDeferredInterest() throws Exception {
            // Enough is owed for both promotions to still be whole.
            enterBalance(card, "2026-10-01", 600000);
            addPromo("2026-11-15", "");
            addPromo("2026-11-20", ",\"deferredInterest\":true");

            JsonNode promos = detail().get("promos");

            assertThat(promos.get(0).get("daysLeft").asLong()).isEqualTo(45);
            assertThat(promos.get(0).get("warning").asText()).isEqualTo("soon");
            assertThat(promos.get(1).get("warning").asText()).isEqualTo("soon_deferred");
            // 3,000.00 for eight months at 24.49% would be charged at once: 3,000 × 24.49% × 8 ÷ 12 = 489.80
            assertThat(promos.get(1).get("deferredInterestCents").asLong()).isEqualTo(48980);
        }

        @Test
        void thePayoffHelperAddsAPaymentForEveryPaycheckBeforeItEnds() throws Exception {
            enterBalance(checking, "2026-10-01", 300000);
            saveSettings("minimum", "");
            long promoId = addPromo("2027-03-15", "").data().get("promos").get(0).get("id").asLong();

            Reply added = server.post("/api/cards/" + card + "/promos/" + promoId + "/payoff", "", cookie);

            assertThat(added.status()).isEqualTo(201);
            assertThat(added.changed()).isTrue();
            JsonNode items = server.get("/api/items", cookie).data();
            assertThat(items).hasSize(1);
            JsonNode payoff = items.get(0);
            assertThat(payoff.get("name").asText()).isEqualTo("Travel Rewards Visa · Balance transfer payoff");
            assertThat(payoff.get("kind").asText()).isEqualTo("card_payment");
            assertThat(payoff.get("amountCents").asLong()).isEqualTo(24000);
            // With nothing chosen to pay the card from, it comes out of the account the paycheck lands in.
            assertThat(payoff.get("payFromAccountId").asLong()).isEqualTo(checking);
            assertThat(payoff.get("payToAccountId").asLong()).isEqualTo(card);
            assertThat(payoff.get("schedule").get("frequency").asText()).isEqualTo("every_pay_period");
            assertThat(payoff.get("schedule").get("startDate").asText()).isEqualTo("2026-10-15");
            assertThat(payoff.get("schedule").get("endDate").asText()).isEqualTo("2027-03-14");
            assertThat(added.data().get("promos").get(0).get("payoffItemId").asLong()).isEqualTo(payoff.get("id").asLong());

            // Ten payments of 240.00 later, nothing is left when it ends.
            assertThat(ending(period("2027-03-01"), card)).isZero();
            // And it cannot be added twice.
            Reply again = server.post("/api/cards/" + card + "/promos/" + promoId + "/payoff", "", cookie);
            assertThat(again.status()).isEqualTo(409);
            assertThat(again.error()).isEqualTo("This payoff is already one of your recurring payments.");
        }

        @Test
        void thePayoffCanBeAddedAgainOnceItsPaymentIsDeleted() throws Exception {
            long promoId = addPromo("2027-03-15", "").data().get("promos").get(0).get("id").asLong();
            server.post("/api/cards/" + card + "/promos/" + promoId + "/payoff", "", cookie);
            long itemId = server.get("/api/items", cookie).data().get(0).get("id").asLong();

            assertThat(server.delete("/api/items/" + itemId, cookie).status()).isEqualTo(200);

            assertThat(detail().get("promos").get(0).get("payoffItemId").isNull()).isTrue();
            assertThat(server.post("/api/cards/" + card + "/promos/" + promoId + "/payoff", "", cookie).status()).isEqualTo(201);
        }

        @Test
        void thePayoffNeedsAPaydayAndABalance() throws Exception {
            long ending = addPromo("2026-10-10", "").data().get("promos").get(0).get("id").asLong();
            Reply noPayday = server.post("/api/cards/" + card + "/promos/" + ending + "/payoff", "", cookie);
            assertThat(noPayday.status()).isEqualTo(409);
            assertThat(noPayday.error())
                    .isEqualTo("There is no payday left before this promotion ends, so there is nothing to spread it over.");

            enterBalance(card, "2026-10-01", 0);
            Reply nothingLeft = server.post("/api/cards/" + card + "/promos/" + ending + "/payoff", "", cookie);
            assertThat(nothingLeft.status()).isEqualTo(409);
            assertThat(nothingLeft.error()).isEqualTo("There is nothing left on this promotion to pay off.");
            assertThat(server.post("/api/cards/" + card + "/promos/999/payoff", "", cookie).status()).isEqualTo(404);
        }

        @Test
        void aPayPeriodIsFlaggedWhenAPromotionEndsInItWithABalanceLeft() throws Exception {
            long promoId = addPromo("2027-03-15", "").data().get("promos").get(0).get("id").asLong();

            JsonNode flagged = period("2027-03-15").get("promosEnding");
            assertThat(flagged).hasSize(1);
            assertThat(flagged.get(0).get("accountId").asLong()).isEqualTo(card);
            assertThat(flagged.get(0).get("label").asText()).isEqualTo("Balance transfer");
            assertThat(flagged.get(0).get("endDate").asText()).isEqualTo("2027-03-15");
            assertThat(flagged.get(0).get("remainingCents").asLong()).isEqualTo(240000);
            assertThat(period("2027-03-01").get("promosEnding")).isEmpty();
            JsonNode listed = server.get("/api/pay-periods?from=2027-03-15&to=2027-03-15", cookie).data().get("periods").get(0);
            assertThat(listed.get("promosEnding")).hasSize(1);

            // Once it is on course to be cleared in time, there is nothing to flag.
            server.post("/api/cards/" + card + "/promos/" + promoId + "/payoff", "", cookie);
            assertThat(period("2027-03-15").get("promosEnding")).isEmpty();
        }

        @Test
        void aPromotionCanBeChangedAndRemoved() throws Exception {
            long promoId = addPromo("2027-03-15", "").data().get("promos").get(0).get("id").asLong();
            String path = "/api/cards/" + card + "/promos/" + promoId;

            Reply changed = server.put(path, """
                    {"label":"Summer transfer","kind":"other","rateBp":299,"startDate":"2026-06-01","endDate":"2027-06-01",
                     "originalBalanceCents":200000,"rateAfterBp":1999,"deferredInterest":true}""", cookie);

            assertThat(changed.status()).isEqualTo(200);
            JsonNode promo = changed.data().get("promos").get(0);
            assertThat(promo.get("label").asText()).isEqualTo("Summer transfer");
            assertThat(promo.get("kind").asText()).isEqualTo("other");
            assertThat(promo.get("rateBp").asInt()).isEqualTo(299);
            assertThat(promo.get("rateAfterBp").asInt()).isEqualTo(1999);
            assertThat(promo.get("rateAfterEffectiveBp").asInt()).isEqualTo(1999);
            assertThat(promo.get("deferredInterest").asBoolean()).isTrue();
            // It never covers more than it started with.
            assertThat(promo.get("remainingCents").asLong()).isEqualTo(200000);

            assertThat(server.delete(path, cookie).status()).isEqualTo(200);
            assertThat(detail().get("promos")).isEmpty();
            assertThat(server.delete(path, cookie).status()).isEqualTo(404);
            assertThat(server.put(path, PROMO.formatted("2027-03-15", ""), cookie).status()).isEqualTo(404);
        }

        @Test
        void explainsWhatIsWrongWithAPromotion() throws Exception {
            String base = "/api/cards/" + card + "/promos";
            String ok = "\"kind\":\"purchase\",\"rateBp\":0,\"startDate\":\"2026-03-15\",\"endDate\":\"2027-03-15\",\"originalBalanceCents\":1000";

            assertError(server.post(base, "{" + ok + "}", cookie), "Please add a name for this promotion.");
            assertError(server.post(base, "{\"label\":\"New sofa\",\"kind\":\"gift\",\"rateBp\":0}", cookie),
                    "Please choose what kind of promotion this is from the list.");
            assertError(server.post(base, "{\"label\":\"New sofa\",\"kind\":\"purchase\"}", cookie),
                    "Please enter the promotional rate, between 0% and 1,000%.");
            assertError(server.post(base, "{\"label\":\"New sofa\",\"kind\":\"purchase\",\"rateBp\":0}", cookie),
                    "Please choose the date this promotion started.");
            assertError(server.post(base, "{\"label\":\"New sofa\",\"kind\":\"purchase\",\"rateBp\":0,\"startDate\":\"2026-03-15\"}", cookie),
                    "Please choose the date this promotion ends.");
            assertError(server.post(base, """
                    {"label":"New sofa","kind":"purchase","rateBp":0,"startDate":"2026-03-15","endDate":"2026-03-15","originalBalanceCents":1000}""",
                    cookie), "A promotion ends after it starts. Please check the two dates.");
            assertError(server.post(base, """
                    {"label":"New sofa","kind":"purchase","rateBp":0,"startDate":"2026-03-15","endDate":"2027-03-15","originalBalanceCents":0}""",
                    cookie), "Please enter the balance this promotion started with.");
            assertError(server.post(base, "{\"label\":\"New sofa\",\"rateAfterBp\":-1," + ok + "}", cookie),
                    "Please enter the rate afterwards between 0% and 1,000%, or leave it empty.");
            assertThat(detail().get("promos")).isEmpty();
        }
    }
}
