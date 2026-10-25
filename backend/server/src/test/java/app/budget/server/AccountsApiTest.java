package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Accounts and the balances entered for them, over real HTTP against a temporary database. */
class AccountsApiTest extends ApiTestBase {

    private static final String CHECKING = """
            {"name":"Everyday Checking","institution":"Coastal Credit Union","type":"checking","color":"#0F766E"}""";

    @Nested
    class Auth {
        @Test
        void everyAccountRouteRequiresASession() throws Exception {
            long id = createAccount("Everyday Checking", "checking");

            assertThat(server.get("/api/accounts", null).status()).isEqualTo(401);
            assertThat(server.post("/api/accounts", CHECKING, null).status()).isEqualTo(401);
            assertThat(server.get("/api/accounts/" + id, null).status()).isEqualTo(401);
            assertThat(server.put("/api/accounts/" + id, CHECKING, null).status()).isEqualTo(401);
            assertThat(server.delete("/api/accounts/" + id, null).status()).isEqualTo(401);
            assertThat(server.post("/api/accounts/" + id + "/balances",
                    "{\"date\":\"2026-10-01\",\"amountCents\":100}", null).status()).isEqualTo(401);
            assertThat(server.delete("/api/accounts/" + id + "/balances/1", null).status()).isEqualTo(401);
            // Nothing was changed by any of those.
            assertThat(server.get("/api/accounts", cookie).data()).hasSize(1);
        }
    }

    @Nested
    class Creating {
        @Test
        void savesAnAccountAndListsIt() throws Exception {
            Reply created = server.post("/api/accounts", CHECKING, cookie);

            assertThat(created.status()).isEqualTo(201);
            assertThat(created.data().get("account").get("name").asText()).isEqualTo("Everyday Checking");
            assertThat(created.data().get("account").get("type").asText()).isEqualTo("checking");
            assertThat(created.data().get("account").get("color").asText()).isEqualTo("#0f766e");
            assertThat(created.data().get("account").get("latestBalance").isNull()).isTrue();
            assertThat(created.data().get("balances")).isEmpty();

            Reply list = server.get("/api/accounts", cookie);
            assertThat(list.data()).hasSize(1);
            assertThat(list.data().get(0).get("institution").asText()).isEqualTo("Coastal Credit Union");
        }

        @Test
        void acceptsEveryKindOfAccountInTheOrderAdded() throws Exception {
            for (String type : new String[] {"checking", "savings", "credit_card", "investment", "loan"}) {
                createAccount(type, type);
            }

            assertThat(server.get("/api/accounts", cookie).data().findValuesAsText("type"))
                    .containsExactly("checking", "savings", "credit_card", "investment", "loan");
        }

        @Test
        void asksForANameKindly() throws Exception {
            Reply reply = server.post("/api/accounts", "{\"name\":\"  \",\"type\":\"checking\",\"color\":\"#0f766e\"}", cookie);

            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.error()).isEqualTo("Please add a name for the account.");
        }

        @Test
        void rejectsAKindThatIsNotOnTheList() throws Exception {
            Reply reply = server.post("/api/accounts", "{\"name\":\"Stash\",\"type\":\"mattress\",\"color\":\"#0f766e\"}", cookie);

            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.error()).contains("kind of account");
        }

        @Test
        void rejectsAColorThatIsNotHex() throws Exception {
            Reply reply = server.post("/api/accounts", "{\"name\":\"Stash\",\"type\":\"savings\",\"color\":\"teal\"}", cookie);

            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.error()).contains("hex code");
        }

        @Test
        void rejectsABodyItCannotReadWithoutLeakingInternals() throws Exception {
            Reply notJson = server.post("/api/accounts", "checking", cookie);
            Reply unknownField = server.post("/api/accounts", "{\"name\":\"A\",\"type\":\"savings\",\"color\":\"#0f766e\",\"x\":1}", cookie);

            assertThat(notJson.status()).isEqualTo(400);
            assertThat(notJson.error()).doesNotContain("Exception").doesNotContain("jackson");
            assertThat(unknownField.status()).isEqualTo(400);
            assertThat(server.get("/api/accounts", cookie).data()).isEmpty();
        }
    }

    @Nested
    class Changing {
        @Test
        void renamesAndArchives() throws Exception {
            long id = createAccount("Old name", "savings");

            Reply reply = server.put("/api/accounts/" + id, """
                    {"name":"Rainy Day","type":"savings","subtypeLabel":"High-yield","color":"#7c3aed","archived":true}""", cookie);

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.data().get("account").get("name").asText()).isEqualTo("Rainy Day");
            assertThat(reply.data().get("account").get("subtypeLabel").asText()).isEqualTo("High-yield");
            assertThat(reply.data().get("account").get("archived").asBoolean()).isTrue();
        }

        @Test
        void anAccountThatDoesNotExistIsNotFound() throws Exception {
            assertThat(server.put("/api/accounts/999", CHECKING, cookie).status()).isEqualTo(404);
            assertThat(server.get("/api/accounts/999", cookie).status()).isEqualTo(404);
            assertThat(server.get("/api/accounts/abc", cookie).status()).isEqualTo(404);
            assertThat(server.delete("/api/accounts/999", cookie).status()).isEqualTo(404);
        }

        @Test
        void validatesAChangeLikeANewAccount() throws Exception {
            long id = createAccount("Keep me", "savings");

            Reply reply = server.put("/api/accounts/" + id, "{\"name\":\"\",\"type\":\"savings\",\"color\":\"#0f766e\"}", cookie);

            assertThat(reply.status()).isEqualTo(400);
            assertThat(server.get("/api/accounts/" + id, cookie).data().get("account").get("name").asText()).isEqualTo("Keep me");
        }
    }

    @Nested
    class Balances {
        @Test
        void theNewestEntryIsTheAccountsBalance() throws Exception {
            long id = createAccount("Everyday Checking", "checking");

            server.post("/api/accounts/" + id + "/balances", "{\"date\":\"2026-10-01\",\"amountCents\":170510,\"note\":\"From statement\"}", cookie);
            Reply reply = server.post("/api/accounts/" + id + "/balances", "{\"date\":\"2026-09-01\",\"amountCents\":150000}", cookie);

            assertThat(reply.status()).isEqualTo(201);
            assertThat(reply.data().get("account").get("latestBalance").get("amountCents").asLong()).isEqualTo(170510);
            assertThat(reply.data().get("balances").findValuesAsText("date")).containsExactly("2026-10-01", "2026-09-01");
            assertThat(reply.data().get("balances").get(0).get("note").asText()).isEqualTo("From statement");
            assertThat(server.get("/api/accounts", cookie).data().get(0).get("latestBalance").get("date").asText())
                    .isEqualTo("2026-10-01");
        }

        @Test
        void enteringTheSameDayAgainReplacesIt() throws Exception {
            long id = createAccount("Everyday Checking", "checking");

            server.post("/api/accounts/" + id + "/balances", "{\"date\":\"2026-10-01\",\"amountCents\":100}", cookie);
            Reply reply = server.post("/api/accounts/" + id + "/balances", "{\"date\":\"2026-10-01\",\"amountCents\":250}", cookie);

            assertThat(reply.data().get("balances")).hasSize(1);
            assertThat(reply.data().get("balances").get(0).get("amountCents").asLong()).isEqualTo(250);
        }

        @Test
        void anOverdrawnBalanceCanBeNegative() throws Exception {
            long id = createAccount("Everyday Checking", "checking");

            Reply reply = server.post("/api/accounts/" + id + "/balances", "{\"date\":\"2026-09-30\",\"amountCents\":-4200}", cookie);

            assertThat(reply.status()).isEqualTo(201);
            assertThat(reply.data().get("account").get("latestBalance").get("amountCents").asLong()).isEqualTo(-4200);
        }

        @Test
        void aBalanceForADayToComeIsPlannedNotTheBalanceYet() throws Exception {
            long id = createAccount("Health FSA", "savings");
            server.post("/api/accounts/" + id + "/balances", "{\"date\":\"2026-09-30\",\"amountCents\":10000}", cookie);

            Reply planned = server.post("/api/accounts/" + id + "/balances", "{\"date\":\"2027-01-01\",\"amountCents\":300000}", cookie);

            // Saved and listed, but until Jan 1 the account still holds what was last entered.
            assertThat(planned.status()).isEqualTo(201);
            assertThat(planned.data().get("account").get("latestBalance").get("amountCents").asLong()).isEqualTo(10000);
            assertThat(planned.data().get("balances").size()).isEqualTo(2);
            // The projection takes it as the balance on its day.
            var points = server.get("/api/accounts/" + id + "/projection?from=2026-12-01&to=2027-02-28", cookie).data();
            for (var point : points) {
                boolean after = !point.get("end").asText().startsWith("2026");
                assertThat(point.get("endingCents").asLong()).as(point.toString()).isEqualTo(after ? 300000 : 10000);
            }
        }

        @Test
        void aPlannedBalanceStillNeedsASupportedYear() throws Exception {
            long id = createAccount("Health FSA", "savings");

            Reply reply = server.post("/api/accounts/" + id + "/balances", "{\"date\":\"2101-01-01\",\"amountCents\":100}", cookie);

            assertThat(reply.status()).isEqualTo(400);
        }

        @Test
        void needsADateAndAnAmount() throws Exception {
            long id = createAccount("Everyday Checking", "checking");

            assertThat(server.post("/api/accounts/" + id + "/balances", "{\"amountCents\":100}", cookie).status()).isEqualTo(400);
            assertThat(server.post("/api/accounts/" + id + "/balances", "{\"date\":\"2026-10-01\"}", cookie).status()).isEqualTo(400);
            assertThat(server.post("/api/accounts/" + id + "/balances", "{\"date\":\"soon\",\"amountCents\":1}", cookie).status())
                    .isEqualTo(400);
            assertThat(server.post("/api/accounts/" + id + "/balances",
                    "{\"date\":\"2026-10-01\",\"amountCents\":99999999999999999}", cookie).status()).isEqualTo(400);
        }

        @Test
        void anAccountThatDoesNotExistIsNotFound() throws Exception {
            Reply reply = server.post("/api/accounts/999/balances", "{\"date\":\"2026-10-01\",\"amountCents\":100}", cookie);

            assertThat(reply.status()).isEqualTo(404);
        }

        @Test
        void anEntryCanBeRemovedButOnlyThroughItsOwnAccount() throws Exception {
            long checking = createAccount("Everyday Checking", "checking");
            long savings = createAccount("Rainy Day", "savings");
            Reply saved = server.post("/api/accounts/" + checking + "/balances", "{\"date\":\"2026-10-01\",\"amountCents\":100}", cookie);
            long entryId = saved.data().get("balances").get(0).get("id").asLong();

            assertThat(server.delete("/api/accounts/" + savings + "/balances/" + entryId, cookie).status()).isEqualTo(404);
            Reply removed = server.delete("/api/accounts/" + checking + "/balances/" + entryId, cookie);

            assertThat(removed.status()).isEqualTo(200);
            assertThat(removed.data().get("balances")).isEmpty();
            assertThat(removed.data().get("account").get("latestBalance").isNull()).isTrue();
        }
    }

    @Nested
    class Deleting {
        @Test
        void removesTheAccountAndItsBalances() throws Exception {
            long id = createAccount("Everyday Checking", "checking");
            server.post("/api/accounts/" + id + "/balances", "{\"date\":\"2026-10-01\",\"amountCents\":100}", cookie);

            Reply reply = server.delete("/api/accounts/" + id, cookie);

            assertThat(reply.status()).isEqualTo(200);
            assertThat(server.get("/api/accounts", cookie).data()).isEmpty();
        }

        @Test
        void isRefusedWhileAPaycheckIsDepositedThere() throws Exception {
            long id = createAccount("Everyday Checking", "checking");
            server.post("/api/income-sources", """
                    {"name":"Acme","depositAccountId":%d,"netCents":100000,
                     "schedule":{"frequency":"weekly","startDate":"2026-10-02","weekendRule":"none"}}""".formatted(id), cookie);

            Reply reply = server.delete("/api/accounts/" + id, cookie);

            assertThat(reply.status()).isEqualTo(409);
            assertThat(reply.error()).contains("income source").contains("archive");
            assertThat(server.get("/api/accounts", cookie).data()).hasSize(1);
        }
    }
}
