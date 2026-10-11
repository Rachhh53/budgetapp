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

    private static final String DATES = "{\"statementCloseDay\":20,\"dueDay\":13%s}";

    /** The card's own dates: its statement closes on the 20th and is due on the 13th. */
    private Reply saveDates(String extra) throws Exception {
        return server.put("/api/cards/" + card + "/settings", DATES.formatted(extra), cookie);
    }

    private String payment(String mode, long amountCents, String extra) {
        return """
                {"name":"Travel Rewards Visa payment","kind":"card_payment","amountCents":%d,"cardPaymentMode":"%s",
                 "payFromAccountId":%d,"payToAccountId":%d,
                 "schedule":{"frequency":"monthly","day1":13,"startDate":"2026-10-01"}%s}"""
                .formatted(amountCents, mode, checking, card, extra);
    }

    /** Sets the card's dates, then adds its recurring payment (or changes the one it has), on autopay. */
    private Reply payBy(String mode, long amountCents, String extra) throws Exception {
        saveDates("");
        JsonNode items = server.get("/api/items", cookie).data();
        return items.isEmpty()
                ? server.post("/api/items", payment(mode, amountCents, extra), cookie)
                : server.put("/api/items/" + items.get(0).get("id").asLong(), payment(mode, amountCents, extra), cookie);
    }

    private Reply payBy(String mode) throws Exception {
        return payBy(mode, 0, ",\"autopay\":true");
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
            assertThat(server.put(base + "/settings", DATES.formatted(""), null).status()).isEqualTo(401);
            assertThat(server.put(base + "/minimum", "{\"shareBp\":200,\"floorCents\":3500,\"plusInterest\":false}", null).status())
                    .isEqualTo(401);
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
            assertThat(server.put("/api/cards/" + checking + "/settings", DATES.formatted(""), cookie).status()).isEqualTo(404);
            assertThat(server.get("/api/cards/999", cookie).status()).isEqualTo(404);
        }
    }

    @Nested
    class SettingsAndThePayment {
        @Test
        void datesAreSavedAndShownWithTheRegularApr() throws Exception {
            setApr(2449);

            Reply saved = saveDates(",\"monthlyEstimateCents\":45000");

            assertThat(saved.status()).isEqualTo(200);
            assertThat(saved.changed()).isTrue();
            JsonNode found = saved.data().get("card");
            assertThat(found.get("settings").get("statementCloseDay").asInt()).isEqualTo(20);
            assertThat(found.get("settings").get("dueDay").asInt()).isEqualTo(13);
            assertThat(found.get("settings").get("monthlyEstimateCents").asLong()).isEqualTo(45000);
            assertThat(found.get("aprBp").asInt()).isEqualTo(2449);
            assertThat(saved.data().get("nextCloseDate").asText()).isEqualTo("2026-10-20");
            assertThat(saved.data().get("nextDueDate").asText()).isEqualTo("2026-11-13");
            // How it is paid is its recurring payment's business: saving the dates adds none.
            assertThat(found.get("settings").get("paymentItemId").isNull()).isTrue();
            assertThat(server.get("/api/items", cookie).data()).isEmpty();
        }

        @Test
        void aRecurringPaymentIntoTheCardIsItsOwnAndCanFollowIt() throws Exception {
            Reply added = payBy("statement_balance");

            assertThat(added.status()).isEqualTo(201);
            JsonNode payment = added.data();
            assertThat(payment.get("paymentOfCardId").asLong()).isEqualTo(card);
            assertThat(payment.get("cardPaymentMode").asText()).isEqualTo("statement_balance");
            assertThat(payment.get("followsCard").asBoolean()).isTrue();
            // It has no amount of its own: the card works it out.
            assertThat(payment.get("amountCents").asLong()).isZero();
            assertThat(payment.get("estimate").asBoolean()).isTrue();
            JsonNode settings = detail().get("card").get("settings");
            assertThat(settings.get("paymentItemId").asLong()).isEqualTo(payment.get("id").asLong());
            assertThat(settings.get("paymentMode").asText()).isEqualTo("statement_balance");
            assertThat(settings.get("payFromAccountId").asLong()).isEqualTo(checking);
            assertThat(settings.get("autopay").asBoolean()).isTrue();
        }

        @Test
        void changingThePaymentIsDoneOnTheItemAndTheCardFollows() throws Exception {
            payBy("statement_balance");

            Reply changed = payBy("fixed", 24000, ",\"placement\":\"one_early\"");

            assertThat(changed.status()).isEqualTo(200);
            JsonNode items = server.get("/api/items", cookie).data();
            assertThat(items).hasSize(1);
            assertThat(items.get(0).get("amountCents").asLong()).isEqualTo(24000);
            assertThat(items.get(0).get("estimate").asBoolean()).isFalse();
            assertThat(items.get(0).get("autopay").asBoolean()).isFalse();
            assertThat(items.get(0).get("placement").asText()).isEqualTo("one_early");
            assertThat(items.get(0).get("followsCard").asBoolean()).isFalse();
            assertThat(items.get(0).get("cardPaymentMode").asText()).isEqualTo("fixed");
            assertThat(items.get(0).get("paymentOfCardId").asLong()).isEqualTo(card);
            JsonNode settings = detail().get("card").get("settings");
            assertThat(settings.get("paymentMode").asText()).isEqualTo("fixed");
            assertThat(settings.get("fixedPaymentCents").asLong()).isEqualTo(24000);
            assertThat(settings.get("autopay").asBoolean()).isFalse();
        }

        @Test
        void savingTheItemWithoutSayingHowMuchItPaysLeavesThatAsItWas() throws Exception {
            long itemId = payBy("full_balance").data().get("id").asLong();

            Reply saved = server.put("/api/items/" + itemId, """
                    {"name":"Visa","kind":"card_payment","amountCents":0,"payFromAccountId":%d,"payToAccountId":%d,
                     "schedule":{"frequency":"monthly","day1":13,"startDate":"2026-10-01"}}""".formatted(checking, card), cookie);

            assertThat(saved.data().get("cardPaymentMode").asText()).isEqualTo("full_balance");
            assertThat(saved.data().get("followsCard").asBoolean()).isTrue();
        }

        @Test
        void aCardPaymentThatWasAlreadyThereBecomesTheCardsOwn() throws Exception {
            // Set up before the card had its dates, paid a period early.
            long already = created("/api/items", """
                    {"name":"Visa bill","kind":"card_payment","amountCents":25000,"payFromAccountId":%d,"payToAccountId":%d,
                     "placement":"one_early","schedule":{"frequency":"monthly","day1":13,"startDate":"2026-01-13"}}"""
                    .formatted(checking, card));

            Reply saved = saveDates("");

            // No second payment is added beside it, and it stays exactly as it was entered.
            JsonNode settings = saved.data().get("card").get("settings");
            assertThat(settings.get("paymentItemId").asLong()).isEqualTo(already);
            assertThat(settings.get("paymentMode").asText()).isEqualTo("fixed");
            assertThat(settings.get("fixedPaymentCents").asLong()).isEqualTo(25000);
            JsonNode all = server.get("/api/items", cookie).data();
            assertThat(all).hasSize(1);
            assertThat(all.get(0).get("name").asText()).isEqualTo("Visa bill");
            assertThat(all.get(0).get("amountCents").asLong()).isEqualTo(25000);
            assertThat(all.get(0).get("placement").asText()).isEqualTo("one_early");
            assertThat(all.get(0).get("paymentOfCardId").asLong()).isEqualTo(card);
        }

        @Test
        void movingTheDueDayMovesAMonthlyPaymentWithIt() throws Exception {
            payBy("statement_balance");

            server.put("/api/cards/" + card + "/settings", "{\"statementCloseDay\":20,\"dueDay\":17}", cookie);

            JsonNode payment = server.get("/api/items", cookie).data().get(0);
            assertThat(payment.get("schedule").get("day1").asInt()).isEqualTo(17);
            assertThat(payment.get("followsCard").asBoolean()).isTrue();
        }

        @Test
        void onlyOnePaymentFollowsACardAndItNeedsTheCardsDates() throws Exception {
            // Without the card's dates there is no statement to follow.
            assertError(server.post("/api/items", payment("statement_balance", 0, ""), cookie),
                    "Please enter this card's statement and due dates on its page first, so the payment knows which statement to follow.");
            assertError(server.post("/api/items", payment("whenever", 0, ""), cookie),
                    "Please choose how much this payment pays from the list.");
            payBy("statement_balance");

            assertError(server.post("/api/items", payment("minimum", 0, ""), cookie),
                    "This card already has a payment that follows it. Please enter an amount for this one.");
            // An extra payment of a fixed amount is fine, and the card's own payment stays the one that follows it.
            long extra = created("/api/items", payment("fixed", 5000, ""));
            assertThat(server.get("/api/items/" + extra, cookie).data().get("paymentOfCardId").isNull()).isTrue();
            assertThat(detail().get("card").get("settings").get("paymentMode").asText()).isEqualTo("statement_balance");
        }

        @Test
        void aPaymentPointedAtAnotherCardLetsGoOfTheFirst() throws Exception {
            long itemId = payBy("fixed", 24000, "").data().get("id").asLong();
            long other = createAccount("Everyday Mastercard", "credit_card");

            server.put("/api/items/" + itemId, """
                    {"name":"Card payment","kind":"card_payment","amountCents":24000,"payFromAccountId":%d,"payToAccountId":%d,
                     "schedule":{"frequency":"monthly","day1":13,"startDate":"2026-10-01"}}""".formatted(checking, other), cookie);

            JsonNode settings = detail().get("card").get("settings");
            assertThat(settings.get("paymentItemId").isNull()).isTrue();
            assertThat(settings.get("payFromAccountId").isNull()).isTrue();
        }

        @Test
        void deletingThePaymentLeavesTheCardWithNone() throws Exception {
            long itemId = payBy("statement_balance").data().get("id").asLong();

            server.delete("/api/items/" + itemId, cookie);

            assertThat(detail().get("card").get("settings").get("paymentItemId").isNull()).isTrue();
            assertThat(detail().get("card").get("nextPayment").isNull()).isTrue();
        }

        @Test
        void savingTheDatesCanBeUndone() throws Exception {
            saveDates("");

            assertThat(server.post("/api/undo", "", cookie).status()).isEqualTo(200);

            assertThat(detail().get("card").get("settings").isNull()).isTrue();
        }

        @Test
        void explainsWhatIsWrong() throws Exception {
            String base = "/api/cards/" + card + "/settings";

            assertError(server.put(base, "{\"dueDay\":13}", cookie), "Please choose the day of the month the statement closes.");
            assertError(server.put(base, "{\"statementCloseDay\":20,\"dueDay\":32}", cookie),
                    "Please choose the day of the month the payment is due.");
            assertError(saveDates(",\"monthlyEstimateCents\":-1"), "The monthly estimate can't be less than zero.");
            assertThat(detail().get("card").get("settings").isNull()).isTrue();
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
            payBy("statement_balance");

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
        void aPaymentSaysWhichStatementItPaysAndWhetherThatOneIsReal() throws Exception {
            payBy("minimum");
            server.post("/api/cards/" + card + "/statements",
                    "{\"closeDate\":\"2026-09-20\",\"balanceCents\":68248,\"minimumDueCents\":4000}", cookie);

            // October's payment is the minimum on the statement that was entered.
            JsonNode october = period("2026-10-01");
            assertThat(october.get("occurrences").get(0).get("amountCents").asLong()).isEqualTo(4000);
            assertThat(october.get("occurrences").get(0).get("estimate").asBoolean()).isFalse();
            JsonNode paid = october.get("statementsPaid");
            assertThat(paid).hasSize(1);
            assertThat(paid.get(0).get("itemId").asLong()).isEqualTo(october.get("occurrences").get(0).get("itemId").asLong());
            assertThat(paid.get(0).get("scheduledDate").asText()).isEqualTo("2026-10-13");
            assertThat(paid.get(0).get("closeDate").asText()).isEqualTo("2026-09-20");
            assertThat(paid.get(0).get("entered").asBoolean()).isTrue();

            // November's pays the statement still to close in October, which nobody has entered: an estimate.
            JsonNode november = period("2026-11-01");
            assertThat(november.get("occurrences").get(0).get("estimate").asBoolean()).isTrue();
            assertThat(november.get("statementsPaid").get(0).get("closeDate").asText()).isEqualTo("2026-10-20");
            assertThat(november.get("statementsPaid").get(0).get("entered").asBoolean()).isFalse();
        }

        @Test
        void aFullBalancePaymentPaysNoParticularStatement() throws Exception {
            payBy("full_balance");

            assertThat(period("2026-10-01").get("statementsPaid")).isEmpty();
        }

        @Test
        void chargesMadeSinceThenWaitForTheNextStatement() throws Exception {
            payBy("statement_balance");
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
            payBy("full_balance");
            server.post("/api/entries", """
                    {"kind":"money_out","date":"2026-10-05","amountCents":10000,"description":"Vet visit","accountId":%d}"""
                    .formatted(card), cookie);

            assertThat(period("2026-10-01").get("occurrences").get(0).get("amountCents").asLong()).isEqualTo(78248);
        }

        @Test
        void theNextPaymentShowsOnTheCard() throws Exception {
            payBy("statement_balance");

            JsonNode next = server.get("/api/cards", cookie).data().get(0).get("nextPayment");

            assertThat(next.get("date").asText()).isEqualTo("2026-10-13");
            assertThat(next.get("amountCents").asLong()).isEqualTo(68248);
            assertThat(next.get("estimate").asBoolean()).isTrue();
            assertThat(next.get("autopay").asBoolean()).isTrue();
            assertThat(next.get("payFromAccountId").asLong()).isEqualTo(checking);
        }

        @Test
        void anAmountTypedOverThePaymentStands() throws Exception {
            payBy("statement_balance");
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
            payBy("fixed", 24000, "");

            JsonNode period = period("2026-10-01");

            assertThat(period.get("occurrences").get(0).get("amountCents").asLong()).isEqualTo(24000);
            assertThat(period.get("occurrences").get(0).get("estimate").asBoolean()).isFalse();
            assertThat(ending(period, card)).isEqualTo(44248);
        }
    }

    @Nested
    class TheMinimumEstimate {
        private Reply saveMinimum(String body) throws Exception {
            return server.put("/api/cards/" + card + "/minimum", body, cookie);
        }

        @BeforeEach
        void payAndBalances() throws Exception {
            createSalary(checking);
            enterBalance(checking, "2026-10-01", 300000);
            enterBalance(card, "2026-10-01", 500000);
        }

        @Test
        void startsAsOnePercentPlusInterestAndAtLeastTwentyFiveDollars() throws Exception {
            saveDates("");

            JsonNode minimum = detail().get("minimum");

            assertThat(minimum.get("shareBp").asInt()).isEqualTo(100);
            assertThat(minimum.get("floorCents").asLong()).isEqualTo(2500);
            assertThat(minimum.get("plusInterest").asBoolean()).isTrue();
        }

        @Test
        void theCardsOwnRuleSizesAMinimumPaymentUntilTheRealOneIsEntered() throws Exception {
            payBy("minimum");
            // The usual rule: 1% of the 5,000.00 it starts with.
            assertThat(period("2026-10-01").get("occurrences").get(0).get("amountCents").asLong()).isEqualTo(5000);

            Reply saved = saveMinimum("{\"shareBp\":200,\"floorCents\":3500,\"plusInterest\":false}");

            assertThat(saved.status()).isEqualTo(200);
            assertThat(saved.changed()).isTrue();
            assertThat(saved.data().get("minimum").get("shareBp").asInt()).isEqualTo(200);
            assertThat(saved.data().get("minimum").get("floorCents").asLong()).isEqualTo(3500);
            assertThat(saved.data().get("minimum").get("plusInterest").asBoolean()).isFalse();
            // 2% of 5,000.00.
            assertThat(period("2026-10-01").get("occurrences").get(0).get("amountCents").asLong()).isEqualTo(10000);

            // A statement entered with its minimum is the real figure, whatever the rule says.
            server.post("/api/cards/" + card + "/statements",
                    "{\"closeDate\":\"2026-09-20\",\"balanceCents\":500000,\"minimumDueCents\":4000}", cookie);
            assertThat(period("2026-10-01").get("occurrences").get(0).get("amountCents").asLong()).isEqualTo(4000);
        }

        @Test
        void savingTheCardsDatesAgainLeavesTheRuleAlone() throws Exception {
            saveDates("");
            saveMinimum("{\"shareBp\":200,\"floorCents\":3500,\"plusInterest\":false}");

            saveDates(",\"monthlyEstimateCents\":45000");

            assertThat(detail().get("minimum").get("shareBp").asInt()).isEqualTo(200);
            assertThat(detail().get("minimum").get("floorCents").asLong()).isEqualTo(3500);
        }

        @Test
        void explainsWhatIsWrong() throws Exception {
            // The rule is kept with the card's dates.
            assertThat(saveMinimum("{\"shareBp\":200,\"floorCents\":3500,\"plusInterest\":false}").status()).isEqualTo(409);
            saveDates("");
            assertError(saveMinimum("{\"floorCents\":3500}"), "Please enter the share of the balance as a percentage from 0 to 100.");
            assertError(saveMinimum("{\"shareBp\":10001,\"floorCents\":3500}"),
                    "Please enter the share of the balance as a percentage from 0 to 100.");
            assertError(saveMinimum("{\"shareBp\":100,\"floorCents\":-1}"), "The least the minimum comes to can't be less than zero.");
            assertThat(server.put("/api/cards/" + checking + "/minimum", "{\"shareBp\":100,\"floorCents\":0}", cookie).status())
                    .isEqualTo(404);
            assertThat(detail().get("minimum").get("shareBp").asInt()).isEqualTo(100);
        }
    }

    @Nested
    class InterestAndEstimates {
        @Test
        void aBalanceCarriedPastTheStatementIsChargedEstimatedInterest() throws Exception {
            enterBalance(card, "2026-10-01", 100000);
            setApr(2449);
            saveDates("");

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
            saveDates(",\"monthlyEstimateCents\":45000");

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
            saveDates("");
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
            payBy("statement_balance");
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
        void theRateAfterAPromotionBecomesTheRegularAprOfACardThatHasNone() throws Exception {
            long store = createAccount("Harbor Home store card", "credit_card");
            String promo = """
                    {"label":"New sofa","kind":"purchase","rateBp":0,"startDate":"2026-06-01","endDate":"2027-06-01",
                     "originalBalanceCents":200000,"rateAfterBp":%d,"deferredInterest":false}""";

            Reply added = server.post("/api/cards/" + store + "/promos", promo.formatted(2999), cookie);

            assertThat(added.status()).isEqualTo(201);
            assertThat(added.data().get("card").get("aprBp").asInt()).isEqualTo(2999);
            JsonNode rates = added.data().get("rates");
            assertThat(rates).hasSize(1);
            // In force since the promotion started, so it is the card's rate today.
            assertThat(rates.get(0).get("effectiveDate").asText()).isEqualTo("2026-06-01");

            // A card that already has a regular APR keeps it, whatever a later promotion goes to.
            Reply second = server.post("/api/cards/" + store + "/promos", promo.formatted(1999), cookie);
            assertThat(second.data().get("card").get("aprBp").asInt()).isEqualTo(2999);
            assertThat(second.data().get("rates")).hasSize(1);
        }

        @Test
        void aPromotionOnEverythingCoversTheWholeBalanceHoweverMuchIsOwed() throws Exception {
            enterBalance(card, "2026-10-01", 400200);
            setApr(2367);
            saveDates("");

            Reply added = server.post("/api/cards/" + card + "/promos", """
                    {"label":"Intro rate","kind":"purchase","rateBp":0,"startDate":"2026-10-01","endDate":"2026-10-31"}""", cookie);

            assertThat(added.status()).isEqualTo(201);
            JsonNode promo = added.data().get("promos").get(0);
            assertThat(promo.get("originalBalanceCents").isNull()).isTrue();
            // All that is owed is on the rate, so there is no starting amount to be a share of.
            assertThat(promo.get("remainingCents").asLong()).isEqualTo(400200);
            assertThat(promo.get("paidPercent").isNull()).isTrue();
            // Nothing is charged while it runs: October's statement closes inside it.
            assertThat(ofKind(transactions("2026-10-01", "2026-10-31"), "card_interest")).isNull();
            // November's closes after it ended, at the regular APR on everything: 4,002.00 Ã— 23.67% Ã· 12.
            assertThat(ofKind(transactions("2026-11-01", "2026-11-30"), "card_interest").get("amountCents").asLong()).isEqualTo(7894);
        }

        @Test
        void aPromotionOnASetAmountLeavesTheRestAtTheRegularApr() throws Exception {
            enterBalance(card, "2026-10-01", 400200);
            setApr(2367);
            saveDates("");

            server.post("/api/cards/" + card + "/promos", """
                    {"label":"Balance transfer","kind":"balance_transfer","rateBp":0,"startDate":"2026-10-01","endDate":"2026-10-31",
                     "originalBalanceCents":100000}""", cookie);

            // Only 1,000.00 is on the 0% rate: the other 3,002.00 is charged 23.67% Ã· 12.
            assertThat(ofKind(transactions("2026-10-01", "2026-10-31"), "card_interest").get("amountCents").asLong()).isEqualTo(5921);
        }

        @Test
        void aPromotionCostsNothingWhileItRuns() throws Exception {
            saveDates("");
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
            saveDates("");
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
                    cookie), "Please enter the amount this rate is on, or choose everything on the card.");
            // Deferred interest is charged on an amount, so a promotion on the whole balance cannot be one.
            assertError(server.post("/api/cards/" + card + "/promos", """
                    {"label":"Store card","kind":"purchase","rateBp":0,"startDate":"2026-03-15","endDate":"2027-03-15","deferredInterest":true}""",
                    cookie), "Deferred interest is charged on the amount a promotion started with. Please enter the amount this rate is on.");
            assertError(server.post(base, "{\"label\":\"New sofa\",\"rateAfterBp\":-1," + ok + "}", cookie),
                    "Please enter the rate afterwards between 0% and 1,000%, or leave it empty.");
            assertThat(detail().get("promos")).isEmpty();
        }
    }
}
