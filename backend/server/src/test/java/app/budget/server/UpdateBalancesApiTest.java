package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * "Update balances": several accounts' balances entered in one go ({@code PUT /api/account-balances}).
 * "Today" is Thursday 2026-10-01.
 */
class UpdateBalancesApiTest extends ApiTestBase {

    long checking;
    long savings;

    @BeforeEach
    void addAccounts() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        savings = createAccount("Rainy Day Savings", "savings");
    }

    private Reply update(String date, String balances) throws Exception {
        return server.put("/api/account-balances", "{\"date\":%s,\"balances\":%s}".formatted(date, balances), cookie);
    }

    private String both(long checkingCents, long savingsCents) {
        return "[{\"accountId\":%d,\"amountCents\":%d},{\"accountId\":%d,\"amountCents\":%d}]"
                .formatted(checking, checkingCents, savings, savingsCents);
    }

    private long latest(long accountId) throws Exception {
        return server.get("/api/accounts/" + accountId, cookie).data().get("account").get("latestBalance").get("amountCents").asLong();
    }

    private boolean hasBalance(long accountId) throws Exception {
        return !server.get("/api/accounts/" + accountId, cookie).data().get("balances").isEmpty();
    }

    private void assertError(Reply reply, String message) {
        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.error()).isEqualTo(message);
    }

    @Test
    void requiresASession() throws Exception {
        Reply reply = server.put("/api/account-balances",
                "{\"date\":\"2026-10-01\",\"balances\":%s}".formatted(both(284512, 1250000)), null);

        assertThat(reply.status()).isEqualTo(401);
        assertThat(hasBalance(checking)).isFalse();
    }

    @Test
    void savesEveryBalanceForTheDay() throws Exception {
        Reply reply = update("\"2026-10-01\"", both(284512, 1250000));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.changed()).isTrue();
        assertThat(reply.data().get("saved").asInt()).isEqualTo(2);
        assertThat(latest(checking)).isEqualTo(284512);
        assertThat(latest(savings)).isEqualTo(1250000);
    }

    @Test
    void theSameDayAgainReplacesWhatWasThereAndABalanceMayBeOverdrawn() throws Exception {
        update("\"2026-10-01\"", both(284512, 1250000));

        update("\"2026-10-01\"", "[{\"accountId\":%d,\"amountCents\":-1500}]".formatted(checking));

        assertThat(server.get("/api/accounts/" + checking, cookie).data().get("balances")).hasSize(1);
        assertThat(latest(checking)).isEqualTo(-1500);
        // An account left out keeps what it had.
        assertThat(latest(savings)).isEqualTo(1250000);
    }

    @Test
    void oneUndoTakesThemAllBack() throws Exception {
        update("\"2026-10-01\"", both(284512, 1250000));

        assertThat(server.post("/api/undo", "", cookie).status()).isEqualTo(200);

        assertThat(hasBalance(checking)).isFalse();
        assertThat(hasBalance(savings)).isFalse();
    }

    @Test
    void explainsWhatIsWrongAndSavesNothing() throws Exception {
        assertError(update("null", both(1, 2)), "Please choose the date these balances are from.");
        assertError(update("\"2026-10-02\"", both(1, 2)), "A balance needs a date of today or earlier.");
        assertError(update("\"2026-10-01\"", "[]"), "Please enter a balance for at least one account.");
        assertError(update("\"2026-10-01\"", "[{\"accountId\":%d,\"amountCents\":1},{\"accountId\":999,\"amountCents\":2}]".formatted(checking)),
                "One of those accounts could not be found. Please reload and try again.");
        assertError(update("\"2026-10-01\"", "[{\"accountId\":%d,\"amountCents\":1},{\"accountId\":%d,\"amountCents\":2}]".formatted(checking, checking)),
                "Each account can have one balance for a day. Please enter each account once.");
        assertError(update("\"2026-10-01\"", "[{\"accountId\":%d,\"amountCents\":1},{\"accountId\":%d}]".formatted(checking, savings)),
                "A balance is missing. Please enter an amount.");
        assertThat(hasBalance(checking)).isFalse();
        assertThat(hasBalance(savings)).isFalse();
    }
}
