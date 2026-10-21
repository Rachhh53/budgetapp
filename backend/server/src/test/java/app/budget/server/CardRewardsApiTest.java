package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Card rewards (spec §5.6; Rachel, 2026-10-19: "option to apply rewards balance automatically each
 * month to a card balance"), over real HTTP against a temporary database. "Today" is Thursday
 * 2026-10-01. The card owed nothing on Aug 1, closes on the 20th and is due on the 13th.
 */
class CardRewardsApiTest extends ApiTestBase {

    long card;

    @BeforeEach
    void aCardWithItsDates() throws Exception {
        card = createAccount("Travel Rewards Visa", "credit_card");
        enterBalance(card, "2026-08-01", 0);
        assertThat(server.put("/api/cards/" + card + "/settings", "{\"statementCloseDay\":20,\"dueDay\":13}", cookie).status())
                .isEqualTo(200);
        charge("2026-08-05", 40000);
        charge("2026-09-10", 20000);
        charge("2026-10-05", 30000);
    }

    private void charge(String date, long cents) throws Exception {
        assertThat(server.post("/api/entries", """
                {"kind":"money_out","date":"%s","amountCents":%d,"description":"Groceries","accountId":%d}"""
                .formatted(date, cents, card), cookie).status()).isEqualTo(201);
    }

    private Reply setRate(String body) throws Exception {
        return server.put("/api/cards/" + card + "/rewards", body, cookie);
    }

    private Reply typeOver(String date, String body) throws Exception {
        return server.put("/api/accounts/" + card + "/transactions/rewards/" + date, body, cookie);
    }

    /** The card's rewards line dated {@code date}; {@code null} when there is none. */
    private JsonNode rewards(String date) throws Exception {
        JsonNode lines = server.get("/api/accounts/" + card + "/transactions?from=2026-08-01&to=2026-12-31", cookie).data();
        for (JsonNode line : lines) {
            if (line.get("kind").asText().equals("card_rewards") && line.get("date").asText().equals(date)) {
                return line;
            }
        }
        return null;
    }

    @Nested
    class Auth {
        @Test
        void everyRewardsRouteNeedsASession() throws Exception {
            assertThat(server.put("/api/cards/" + card + "/rewards", "{\"rateBp\":150}", null).status()).isEqualTo(401);
            assertThat(server.put("/api/accounts/" + card + "/transactions/rewards/2026-08-20", "{\"amountCents\":600}", null).status())
                    .isEqualTo(401);
            assertThat(server.delete("/api/accounts/" + card + "/transactions/rewards/2026-08-20", null).status()).isEqualTo(401);
        }
    }

    @Nested
    class Rate {
        @Test
        void comeOffAtEachCloseOnWhatWasChargedSinceTheLast() throws Exception {
            assertThat(rewards("2026-08-20")).isNull();

            Reply saved = setRate("{\"rateBp\":150}");

            assertThat(saved.status()).isEqualTo(200);
            assertThat(saved.data().get("rewardsBp").asInt()).isEqualTo(150);
            // 400.00 × 1.5%, 200.00 × 1.5%, 300.00 × 1.5%; nothing charged after Oct 20.
            assertThat(rewards("2026-08-20").get("amountCents").asLong()).isEqualTo(-600);
            assertThat(rewards("2026-09-20").get("amountCents").asLong()).isEqualTo(-300);
            assertThat(rewards("2026-10-20").get("amountCents").asLong()).isEqualTo(-450);
            assertThat(rewards("2026-11-20")).isNull();
            assertThat(rewards("2026-08-20").get("label").asText()).isEqualTo("Rewards");
        }

        @Test
        void canBeTakenAway() throws Exception {
            setRate("{\"rateBp\":150}");

            Reply none = setRate("{\"rateBp\":null}");

            assertThat(none.status()).isEqualTo(200);
            assertThat(none.data().get("rewardsBp").isNull()).isTrue();
            assertThat(rewards("2026-08-20")).isNull();
        }

        @Test
        void refusesARateOutsideWhatACardGives() throws Exception {
            for (String body : new String[] {"{\"rateBp\":0}", "{\"rateBp\":2001}", "{\"rateBp\":-5}"}) {
                Reply refused = setRate(body);
                assertThat(refused.status()).isEqualTo(400);
                assertThat(refused.error()).isEqualTo("Please enter the cash back as a percentage from 0.01 to 20, like 1.50.");
            }
        }

        @Test
        void needsTheCardsDatesFirst() throws Exception {
            long fresh = createAccount("Store card", "credit_card");

            Reply refused = server.put("/api/cards/" + fresh + "/rewards", "{\"rateBp\":150}", cookie);

            assertThat(refused.status()).isEqualTo(409);
            assertThat(refused.error()).isEqualTo("Please enter this card's statement and due dates first.");
        }

        @Test
        void isOnlyForACard() throws Exception {
            long checking = createAccount("Everyday Checking", "checking");

            assertThat(server.put("/api/cards/" + checking + "/rewards", "{\"rateBp\":150}", cookie).status()).isEqualTo(404);
        }
    }

    @Nested
    class TypedOver {
        @BeforeEach
        void rewarded() throws Exception {
            setRate("{\"rateBp\":150}");
        }

        @Test
        void theTypedAmountComesOffAndSaysWhatWasEstimated() throws Exception {
            assertThat(typeOver("2026-08-20", "{\"amountCents\":725}").status()).isEqualTo(200);

            JsonNode august = rewards("2026-08-20");
            assertThat(august.get("amountCents").asLong()).isEqualTo(-725);
            assertThat(august.get("estimatedCents").asLong()).isEqualTo(-600);
            // Typed again, it still says what was first estimated.
            typeOver("2026-08-20", "{\"amountCents\":700}");
            assertThat(rewards("2026-08-20").get("estimatedCents").asLong()).isEqualTo(-600);
        }

        @Test
        void putBackIsTheEstimateAgain() throws Exception {
            typeOver("2026-08-20", "{\"amountCents\":725}");

            assertThat(server.delete("/api/accounts/" + card + "/transactions/rewards/2026-08-20", cookie).status()).isEqualTo(200);

            assertThat(rewards("2026-08-20").get("amountCents").asLong()).isEqualTo(-600);
            assertThat(rewards("2026-08-20").get("estimatedCents").isNull()).isTrue();
            assertThat(server.delete("/api/accounts/" + card + "/transactions/rewards/2026-08-20", cookie).status()).isEqualTo(404);
        }

        @Test
        void waitsForTheStatementToClose() throws Exception {
            Reply refused = typeOver("2026-10-20", "{\"amountCents\":500}");

            assertThat(refused.status()).isEqualTo(409);
            assertThat(refused.error()).startsWith("This statement hasn't closed yet");
        }

        @Test
        void onlyADayWithRewardsCanBeTypedOver() throws Exception {
            assertThat(typeOver("2026-08-21", "{\"amountCents\":500}").status()).isEqualTo(404);
            Reply negative = typeOver("2026-08-20", "{\"amountCents\":-500}");
            assertThat(negative.status()).isEqualTo(400);
        }
    }

    /** The card's rewards as its page shows them. */
    private JsonNode rewardsView() throws Exception {
        return server.get("/api/cards/" + card, cookie).data().get("rewards");
    }

    private JsonNode line(String kind, String date) throws Exception {
        JsonNode lines = server.get("/api/accounts/" + card + "/transactions?from=2026-08-01&to=2026-12-31", cookie).data();
        for (JsonNode line : lines) {
            if (line.get("kind").asText().equals(kind) && line.get("date").asText().equals(date)) {
                return line;
            }
        }
        return null;
    }

    @Nested
    class BuildUp {
        @Test
        void areTrackedInsteadOfComingOffTheStatement() throws Exception {
            assertThat(setRate("{\"rateBp\":150,\"buildUp\":true}").status()).isEqualTo(200);

            assertThat(rewards("2026-08-20")).isNull();
            JsonNode view = rewardsView();
            assertThat(view.get("buildUp").asBoolean()).isTrue();
            // 6.00 in August and 3.00 in September, by today.
            assertThat(view.get("builtUpCents").asLong()).isEqualTo(900);
            assertThat(view.get("nextCreditDate").isNull()).isTrue();
        }

        @Test
        void startFromWhatTheCardSaysOnADay() throws Exception {
            setRate("{\"rateBp\":150,\"buildUp\":true,\"startCents\":2000,\"startDate\":\"2026-09-25\"}");

            // Its 20.00 holds everything before Sep 25.
            assertThat(rewardsView().get("builtUpCents").asLong()).isEqualTo(2000);
            assertThat(rewardsView().get("startDate").asText()).isEqualTo("2026-09-25");
        }

        @Test
        void aRedemptionGoesOnAsACreditAndCanBeChangedOrDeleted() throws Exception {
            setRate("{\"rateBp\":150,\"buildUp\":true}");

            Reply made = server.post("/api/cards/" + card + "/rewards/redemptions", "{\"date\":\"2026-10-02\",\"note\":\"Holiday\"}", cookie);

            assertThat(made.status()).isEqualTo(201);
            long id = made.data().get("id").asLong();
            assertThat(line("card_rewards_redeemed", "2026-10-02").get("amountCents").asLong()).isEqualTo(-900);
            assertThat(line("card_rewards_redeemed", "2026-10-02").get("label").asText()).isEqualTo("Rewards redeemed");
            JsonNode redemption = rewardsView().get("redemptions").get(0);
            assertThat(redemption.get("creditedCents").asLong()).isEqualTo(900);
            assertThat(redemption.get("note").asText()).isEqualTo("Holiday");
            assertThat(rewardsView().get("nextCreditDate").asText()).isEqualTo("2026-10-02");

            // A set amount, never more than has built up.
            assertThat(server.put("/api/cards/" + card + "/rewards/redemptions/" + id, "{\"date\":\"2026-10-02\",\"amountCents\":500}", cookie)
                    .status()).isEqualTo(200);
            assertThat(line("card_rewards_redeemed", "2026-10-02").get("amountCents").asLong()).isEqualTo(-500);

            assertThat(server.delete("/api/cards/" + card + "/rewards/redemptions/" + id, cookie).status()).isEqualTo(200);
            assertThat(line("card_rewards_redeemed", "2026-10-02")).isNull();
            assertThat(server.delete("/api/cards/" + card + "/rewards/redemptions/" + id, cookie).status()).isEqualTo(404);
        }

        @Test
        void aRuleUsesThemAllInTheMonthChosen() throws Exception {
            setRate("{\"rateBp\":150,\"buildUp\":true,\"yearlyMonth\":10}");

            // 9.00 built up, + 4.50 at the Oct 20 close: all of it comes off that statement.
            assertThat(line("card_rewards_redeemed", "2026-10-20").get("amountCents").asLong()).isEqualTo(-1350);
            assertThat(rewardsView().get("nextCreditCents").asLong()).isEqualTo(1350);
            assertThat(rewardsView().get("yearlyMonth").asInt()).isEqualTo(10);
        }

        @Test
        void goingBackToEachStatementTakesThemOffAgain() throws Exception {
            setRate("{\"rateBp\":150,\"buildUp\":true}");
            setRate("{\"rateBp\":150}");

            assertThat(rewardsView().get("buildUp").asBoolean()).isFalse();
            assertThat(rewards("2026-08-20").get("amountCents").asLong()).isEqualTo(-600);
        }

        @Test
        void refusesWhatCannotBeRight() throws Exception {
            Reply notBuilding = server.post("/api/cards/" + card + "/rewards/redemptions", "{\"date\":\"2026-10-02\"}", cookie);
            assertThat(notBuilding.status()).isEqualTo(409);
            assertThat(notBuilding.error()).isEqualTo(
                    "This card's rewards come off each statement on their own, so nothing builds up to redeem. Choose Let them build up first.");

            assertThat(setRate("{\"rateBp\":150,\"buildUp\":true,\"yearlyMonth\":13}").error()).isEqualTo("Please choose the month they go on from the list.");
            assertThat(setRate("{\"rateBp\":150,\"buildUp\":true,\"startCents\":500}").error()).isEqualTo("Please choose the day that amount is from.");
            assertThat(setRate("{\"rateBp\":150,\"buildUp\":true,\"thresholdCents\":0}").error())
                    .isEqualTo("Please enter the amount they go on at, more than $0.00, or leave it empty.");
            setRate("{\"rateBp\":150,\"buildUp\":true}");
            assertThat(server.post("/api/cards/" + card + "/rewards/redemptions", "{\"date\":\"2026-10-02\",\"amountCents\":0}", cookie).error())
                    .isEqualTo("Please enter how much to redeem, more than $0.00, or choose everything built up.");
            assertThat(server.post("/api/cards/" + card + "/rewards/redemptions", "{}", cookie).error()).isEqualTo("Please choose the day the credit goes on.");
            assertThat(server.put("/api/cards/" + card + "/rewards/redemptions/999", "{\"date\":\"2026-10-02\"}", cookie).status()).isEqualTo(404);
        }

        @Test
        void everyRedemptionRouteNeedsASession() throws Exception {
            assertThat(server.post("/api/cards/" + card + "/rewards/redemptions", "{\"date\":\"2026-10-02\"}", null).status()).isEqualTo(401);
            assertThat(server.put("/api/cards/" + card + "/rewards/redemptions/1", "{\"date\":\"2026-10-02\"}", null).status()).isEqualTo(401);
            assertThat(server.delete("/api/cards/" + card + "/rewards/redemptions/1", null).status()).isEqualTo(401);
        }
    }
}
