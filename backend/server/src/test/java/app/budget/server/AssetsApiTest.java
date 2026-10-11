package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Homes, vehicles and other assets, and net worth, over real HTTP (spec §5.7).
 * "Today" is Thursday 2026-10-01.
 */
class AssetsApiTest extends ApiTestBase {

    private static final String HOME = "{\"name\":\"Maple Street house\",\"kind\":\"home\",\"color\":\"#0369a1\"%s}";

    private Reply add(String extra) throws Exception {
        return server.post("/api/assets", HOME.formatted(extra), cookie);
    }

    private long addHomeWorth(long cents, String from) throws Exception {
        Reply reply = add(",\"valueCents\":%d,\"valueDate\":\"%s\"".formatted(cents, from));
        assertThat(reply.status()).isEqualTo(201);
        return reply.data().get("asset").get("id").asLong();
    }

    private void assertError(Reply reply, String message) {
        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.error()).isEqualTo(message);
    }

    @Nested
    class Auth {
        @Test
        void everyRouteRequiresASession() throws Exception {
            long id = addHomeWorth(40000000, "2026-10-01");

            assertThat(server.get("/api/assets", null).status()).isEqualTo(401);
            assertThat(server.post("/api/assets", HOME.formatted(""), null).status()).isEqualTo(401);
            assertThat(server.get("/api/assets/" + id, null).status()).isEqualTo(401);
            assertThat(server.put("/api/assets/" + id, HOME.formatted(""), null).status()).isEqualTo(401);
            assertThat(server.delete("/api/assets/" + id, null).status()).isEqualTo(401);
            assertThat(server.post("/api/assets/" + id + "/values", "{\"date\":\"2026-10-01\",\"valueCents\":1}", null).status())
                    .isEqualTo(401);
            assertThat(server.delete("/api/assets/" + id + "/values/1", null).status()).isEqualTo(401);
            assertThat(server.get("/api/net-worth", null).status()).isEqualTo(401);
            // Nothing was changed by any of those.
            assertThat(server.get("/api/assets", cookie).data()).hasSize(1);
            assertThat(server.get("/api/assets/" + id, cookie).data().get("values")).hasSize(1);
        }
    }

    @Nested
    class AddingAndChanging {
        @Test
        void anAssetStartsWithTheValueGivenForItToday() throws Exception {
            Reply reply = add(",\"valueCents\":40000000");

            assertThat(reply.status()).isEqualTo(201);
            assertThat(reply.changed()).isTrue();
            JsonNode asset = reply.data().get("asset");
            assertThat(asset.get("name").asText()).isEqualTo("Maple Street house");
            assertThat(asset.get("kind").asText()).isEqualTo("home");
            assertThat(asset.get("valueCents").asLong()).isEqualTo(40000000);
            assertThat(asset.get("estimate").asBoolean()).isFalse();
            assertThat(asset.get("latestValue").get("date").asText()).isEqualTo("2026-10-01");
            // Owned outright, all of it is equity.
            assertThat(asset.get("owedCents").isNull()).isTrue();
            assertThat(asset.get("equityCents").asLong()).isEqualTo(40000000);
            assertThat(reply.data().get("values")).hasSize(1);
        }

        @Test
        void withoutAValueItIsListedButWorthNothingYet() throws Exception {
            Reply reply = add("");

            assertThat(reply.status()).isEqualTo(201);
            assertThat(reply.data().get("asset").get("valueCents").isNull()).isTrue();
            assertThat(reply.data().get("asset").get("latestValue").isNull()).isTrue();
            assertThat(reply.data().get("values")).isEmpty();
            assertThat(server.get("/api/assets", cookie).data()).hasSize(1);
        }

        @Test
        void aVehicleAndAnythingElseCanBeAddedToo() throws Exception {
            assertThat(server.post("/api/assets", "{\"name\":\"Outback\",\"kind\":\"vehicle\",\"color\":\"#b45309\"}", cookie).status())
                    .isEqualTo(201);
            assertThat(server.post("/api/assets", "{\"name\":\"Grandmother's ring\",\"kind\":\"other\",\"color\":\"#be185d\"}", cookie).status())
                    .isEqualTo(201);
            assertThat(server.get("/api/assets", cookie).data()).hasSize(2);
        }

        @Test
        void itCanBeRenamedArchivedAndDeleted() throws Exception {
            long id = addHomeWorth(40000000, "2026-10-01");

            Reply changed = server.put("/api/assets/" + id,
                    "{\"name\":\"The house\",\"kind\":\"home\",\"color\":\"#15803d\",\"archived\":true}", cookie);
            assertThat(changed.status()).isEqualTo(200);
            assertThat(changed.data().get("asset").get("name").asText()).isEqualTo("The house");
            assertThat(changed.data().get("asset").get("archived").asBoolean()).isTrue();
            // Changing it never touches the values entered.
            assertThat(changed.data().get("values")).hasSize(1);

            assertThat(server.delete("/api/assets/" + id, cookie).status()).isEqualTo(200);
            assertThat(server.get("/api/assets/" + id, cookie).status()).isEqualTo(404);
            assertThat(server.delete("/api/assets/" + id, cookie).status()).isEqualTo(404);
            assertThat(server.put("/api/assets/" + id, HOME.formatted(""), cookie).status()).isEqualTo(404);
        }

        @Test
        void addingAnAssetCanBeUndone() throws Exception {
            addHomeWorth(40000000, "2026-10-01");

            assertThat(server.post("/api/undo", "", cookie).status()).isEqualTo(200);

            assertThat(server.get("/api/assets", cookie).data()).isEmpty();
        }

        @Test
        void explainsWhatIsWrong() throws Exception {
            assertError(server.post("/api/assets", "{\"kind\":\"home\",\"color\":\"#0369a1\"}", cookie), "Please add a name for this.");
            assertError(server.post("/api/assets", "{\"name\":\"Boat\",\"kind\":\"boat\",\"color\":\"#0369a1\"}", cookie),
                    "Please choose what kind of thing this is from the list.");
            assertError(server.post("/api/assets", "{\"name\":\"Boat\",\"kind\":\"other\",\"color\":\"blue\"}", cookie),
                    "Colors need to be a six-digit hex code, like #0f766e.");
            // A loss of 100% a year or more is not a rate anything can lose by; nor is a gain past 1,000%.
            String rateHelp =
                    "Please enter a yearly rate of up to 1,000% for something that gains value, or up to 99% for something that loses it, or leave it empty.";
            assertError(add(",\"appreciationBp\":-10000"), rateHelp);
            assertError(add(",\"appreciationBp\":100001"), rateHelp);
            assertError(add(",\"valueCents\":-1"), "The value can't be less than zero.");
            assertError(add(",\"valueCents\":40000000,\"valueDate\":\"2026-10-02\""), "A value needs a date of today or earlier.");
            assertThat(server.get("/api/assets", cookie).data()).isEmpty();
        }
    }

    @Nested
    class Values {
        @Test
        void aNewValueTakesOverAndTheSameDayAgainReplacesIt() throws Exception {
            long id = addHomeWorth(38000000, "2025-01-01");

            Reply added = server.post("/api/assets/" + id + "/values", "{\"date\":\"2026-09-01\",\"valueCents\":40000000}", cookie);
            assertThat(added.status()).isEqualTo(201);
            assertThat(added.data().get("asset").get("valueCents").asLong()).isEqualTo(40000000);
            // Newest first.
            assertThat(added.data().get("values").get(0).get("date").asText()).isEqualTo("2026-09-01");
            assertThat(added.data().get("values")).hasSize(2);

            Reply corrected = server.post("/api/assets/" + id + "/values", "{\"date\":\"2026-09-01\",\"valueCents\":40500000}", cookie);
            assertThat(corrected.data().get("values")).hasSize(2);
            assertThat(corrected.data().get("asset").get("valueCents").asLong()).isEqualTo(40500000);
        }

        @Test
        void aValueCanBeRemoved() throws Exception {
            long id = addHomeWorth(38000000, "2025-01-01");
            Reply added = server.post("/api/assets/" + id + "/values", "{\"date\":\"2026-09-01\",\"valueCents\":40000000}", cookie);
            long valueId = added.data().get("values").get(0).get("id").asLong();

            Reply removed = server.delete("/api/assets/" + id + "/values/" + valueId, cookie);

            assertThat(removed.status()).isEqualTo(200);
            assertThat(removed.data().get("asset").get("valueCents").asLong()).isEqualTo(38000000);
            assertThat(server.delete("/api/assets/" + id + "/values/" + valueId, cookie).status()).isEqualTo(404);
        }

        @Test
        void explainsWhatIsWrongWithAValue() throws Exception {
            long id = addHomeWorth(38000000, "2025-01-01");

            assertError(server.post("/api/assets/" + id + "/values", "{\"valueCents\":1}", cookie), "Please choose the date this value is from.");
            assertError(server.post("/api/assets/" + id + "/values", "{\"date\":\"2026-10-02\",\"valueCents\":1}", cookie),
                    "A value needs a date of today or earlier.");
            assertError(server.post("/api/assets/" + id + "/values", "{\"date\":\"2026-10-01\"}", cookie),
                    "The value is missing. Please enter an amount.");
            assertThat(server.post("/api/assets/999/values", "{\"date\":\"2026-10-01\",\"valueCents\":1}", cookie).status()).isEqualTo(404);
        }
    }

    @Nested
    class AppreciationAndEquity {
        @Test
        void aYearlyRateGrowsTheLatestValueByWholeMonths() throws Exception {
            Reply reply = add(",\"appreciationBp\":300,\"valueCents\":40000000,\"valueDate\":\"2026-01-01\"");

            // Nine whole months to October 1: 400,000 × 1.03^(9/12) = 408,966.66
            JsonNode asset = reply.data().get("asset");
            assertThat(asset.get("valueCents").asLong()).isEqualTo(40896666);
            assertThat(asset.get("estimate").asBoolean()).isTrue();
            // What was entered is kept as entered.
            assertThat(asset.get("latestValue").get("valueCents").asLong()).isEqualTo(40000000);
        }

        @Test
        void aYearlyLossShrinksTheLatestValueAndIsKeptAsEntered() throws Exception {
            Reply reply = add(",\"appreciationBp\":-1500,\"valueCents\":2000000,\"valueDate\":\"2026-04-01\"");

            // Six whole months to October 1: 20,000 × 0.85^(6/12) = 18,439.09
            JsonNode asset = reply.data().get("asset");
            assertThat(asset.get("valueCents").asLong()).isEqualTo(1843909);
            assertThat(asset.get("estimate").asBoolean()).isTrue();
            assertThat(asset.get("appreciationBp").asInt()).isEqualTo(-1500);
            // It reads back as a loss, and can be turned into a gain, or into nothing, again.
            long id = asset.get("id").asLong();
            assertThat(server.get("/api/assets/" + id, cookie).data().get("asset").get("appreciationBp").asInt()).isEqualTo(-1500);
        }

        @Test
        void equityIsTheValueLessWhatIsOwedOnTheLoanAgainstIt() throws Exception {
            long mortgage = createAccount("Mortgage", "loan");
            enterBalance(mortgage, "2026-10-01", 28500000);

            Reply reply = add(",\"linkedLoanAccountId\":%d,\"valueCents\":40000000".formatted(mortgage));

            JsonNode asset = reply.data().get("asset");
            assertThat(asset.get("linkedLoanAccountId").asLong()).isEqualTo(mortgage);
            assertThat(asset.get("owedCents").asLong()).isEqualTo(28500000);
            assertThat(asset.get("equityCents").asLong()).isEqualTo(11500000);
        }

        @Test
        void onlyALoanAccountCanBeLinked() throws Exception {
            long checking = createAccount("Everyday Checking", "checking");

            assertError(add(",\"linkedLoanAccountId\":%d".formatted(checking)), "Please choose the loan from your loan accounts.");
            assertError(add(",\"linkedLoanAccountId\":999"), "Please choose the loan from your loan accounts.");
        }

        @Test
        void deletingTheLoanAccountOnlyUnlinksIt() throws Exception {
            long mortgage = createAccount("Mortgage", "loan");
            long id = add(",\"linkedLoanAccountId\":%d,\"valueCents\":40000000".formatted(mortgage)).data().get("asset").get("id").asLong();

            assertThat(server.delete("/api/accounts/" + mortgage, cookie).status()).isEqualTo(200);

            JsonNode asset = server.get("/api/assets/" + id, cookie).data().get("asset");
            assertThat(asset.get("linkedLoanAccountId").isNull()).isTrue();
            assertThat(asset.get("equityCents").asLong()).isEqualTo(40000000);
        }
    }

    @Nested
    class NetWorth {
        @Test
        void withNothingEnteredItIsZero() throws Exception {
            JsonNode worth = server.get("/api/net-worth", cookie).data();

            assertThat(worth.get("date").asText()).isEqualTo("2026-10-01");
            assertThat(worth.get("netWorthCents").asLong()).isZero();
            assertThat(worth.get("assetsCents").asLong()).isZero();
            assertThat(worth.get("liabilitiesCents").asLong()).isZero();
        }

        @Test
        void isEverythingOwnedLessEverythingOwed() throws Exception {
            enterBalance(createAccount("Everyday Checking", "checking"), "2026-10-01", 284512);
            enterBalance(createAccount("Rainy Day Savings", "savings"), "2026-10-01", 1250000);
            enterBalance(createAccount("Brightwater 401(k)", "investment"), "2026-10-01", 8250000);
            enterBalance(createAccount("Travel Rewards Visa", "credit_card"), "2026-10-01", 184300);
            long mortgage = createAccount("Mortgage", "loan");
            enterBalance(mortgage, "2026-10-01", 28500000);
            add(",\"linkedLoanAccountId\":%d,\"valueCents\":40000000".formatted(mortgage));

            JsonNode worth = server.get("/api/net-worth", cookie).data();

            assertThat(worth.get("cashCents").asLong()).isEqualTo(1534512);
            assertThat(worth.get("investmentsCents").asLong()).isEqualTo(8250000);
            assertThat(worth.get("propertyCents").asLong()).isEqualTo(40000000);
            assertThat(worth.get("cardsCents").asLong()).isEqualTo(184300);
            assertThat(worth.get("loansCents").asLong()).isEqualTo(28500000);
            assertThat(worth.get("assetsCents").asLong()).isEqualTo(49784512);
            assertThat(worth.get("liabilitiesCents").asLong()).isEqualTo(28684300);
            assertThat(worth.get("netWorthCents").asLong()).isEqualTo(21100212);
        }

        @Test
        void archivedAccountsAndAssetsAreLeftOut() throws Exception {
            long old = createAccount("Old savings", "savings");
            enterBalance(old, "2026-10-01", 500000);
            server.put("/api/accounts/" + old, "{\"name\":\"Old savings\",\"type\":\"savings\",\"color\":\"#0f766e\",\"archived\":true}", cookie);
            long id = addHomeWorth(40000000, "2026-10-01");
            server.put("/api/assets/" + id, "{\"name\":\"Sold house\",\"kind\":\"home\",\"color\":\"#0369a1\",\"archived\":true}", cookie);

            assertThat(server.get("/api/net-worth", cookie).data().get("netWorthCents").asLong()).isZero();
        }
    }
}
