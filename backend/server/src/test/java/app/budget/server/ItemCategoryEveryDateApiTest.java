package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Changing a recurring item's category for its whole history, not only from a date on, and packs bought ahead following
 * the item's category and tags (Rachel, 2026-10-25: "can I have a way to retroactively update tags on a recurring item?
 * ... and categories"). The server's clock says it is 2026-10-01; paydays are the 1st and 15th.
 */
class ItemCategoryEveryDateApiTest extends ApiTestBase {

    long checking;
    long food;
    long groceries;
    long family;
    long item;

    @BeforeEach
    void aBudgetWithAHistory() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        createSalary(checking);
        food = created("/api/categories", "{\"name\":\"Food\",\"color\":\"#b45309\"}");
        groceries = created("/api/categories", "{\"name\":\"Groceries\",\"color\":\"#15803d\"}");
        family = created("/api/tags", "{\"name\":\"Family\",\"color\":\"#be185d\"}");
        item = created("/api/items", budget(food, ""));
    }

    private String budget(long categoryId, String extra) {
        return """
                {"name":"Groceries for the Nguyens","kind":"budget","amountCents":30000,"payFromAccountId":%d,"categoryId":%d,
                 "schedule":{"frequency":"monthly","day1":5,"startDate":"2026-01-01"}%s}""".formatted(checking, categoryId, extra);
    }

    /** The category of the item's occurrence in the pay period starting {@code start}. */
    private long categoryIn(String start) throws Exception {
        for (JsonNode occurrence : server.get("/api/pay-periods/" + start, cookie).data().get("occurrences")) {
            if (occurrence.get("itemId").asLong() == item) {
                return occurrence.get("categoryId").asLong();
            }
        }
        throw new AssertionError("The item is not in the period starting " + start);
    }

    @Test
    void aChangeFromADateLeavesEarlierDatesInTheirOwnCategory() throws Exception {
        Reply changed = server.put("/api/items/" + item, budget(groceries, ",\"effectiveFrom\":\"2026-10-01\""), cookie);

        assertThat(changed.status()).isEqualTo(200);
        assertThat(categoryIn("2026-03-01")).isEqualTo(food);
        assertThat(categoryIn("2026-10-01")).isEqualTo(groceries);
    }

    @Test
    void everyDateTakesTheNewCategoryWhenAsked() throws Exception {
        Reply changed = server.put("/api/items/" + item,
                budget(groceries, ",\"effectiveFrom\":\"2026-10-01\",\"categoryEveryDate\":true"), cookie);

        assertThat(changed.status()).isEqualTo(200);
        assertThat(categoryIn("2026-03-01")).isEqualTo(groceries);
        assertThat(categoryIn("2026-10-01")).isEqualTo(groceries);
        // The amounts history is left as it was: still one price.
        assertThat(changed.data().get("history")).hasSize(1);
    }

    @Test
    void aCategoryAlreadyChangedFromADateCanBeMadeEveryDatesAfterwards() throws Exception {
        server.put("/api/items/" + item, budget(groceries, ",\"effectiveFrom\":\"2026-10-01\""), cookie);
        assertThat(server.get("/api/items/" + item, cookie).data().get("history").findValues("categoryId").stream().map(JsonNode::asLong))
                .containsExactly(food, groceries);

        Reply again = server.put("/api/items/" + item,
                budget(groceries, ",\"effectiveFrom\":\"2026-10-01\",\"categoryEveryDate\":true"), cookie);

        assertThat(again.status()).isEqualTo(200);
        assertThat(categoryIn("2026-03-01")).isEqualTo(groceries);
        assertThat(again.data().get("history").findValues("categoryId").stream().map(JsonNode::asLong)).containsOnly(groceries);
    }

    @Test
    void packsBoughtAheadFollowTheItemsCategoryAndTags() throws Exception {
        long training = created("/api/items", """
                {"name":"Personal training with Priya","kind":"bill","amountCents":127500,"payFromAccountId":%d,"categoryId":%d,
                 "paidAhead":true,"packSessions":20,
                 "schedule":{"frequency":"weekly","startDate":"2026-08-03","weekendRule":"none","weekdays":[1,4]}}"""
                .formatted(checking, food));
        Reply bought = server.post("/api/items/" + training + "/paid-ahead/payments", "{\"date\":\"2026-08-03\",\"amountCents\":127500}", cookie);
        long entryId = bought.data().get("payments").get(0).get("entryId").asLong();
        String body = """
                {"name":"Personal training with Priya","kind":"bill","amountCents":127500,"payFromAccountId":%d,"categoryId":%d,
                 "tagIds":[%d],"paidAhead":true,"packSessions":20,"effectiveFrom":"2026-10-01","categoryEveryDate":true,
                 "schedule":{"frequency":"weekly","startDate":"2026-08-03","weekendRule":"none","weekdays":[1,4]}}"""
                .formatted(checking, groceries, family);

        assertThat(server.put("/api/items/" + training, body, cookie).status()).isEqualTo(200);

        JsonNode pack = server.get("/api/entries/" + entryId, cookie).data().get("entry");
        assertThat(pack.get("categoryId").asLong()).isEqualTo(groceries);
        assertThat(pack.get("tagIds").get(0).asLong()).isEqualTo(family);
    }

    @Test
    void aPackBoughtBeforeTheChangeKeepsItsCategoryUnlessEveryDateIsAsked() throws Exception {
        long training = created("/api/items", """
                {"name":"Personal training with Priya","kind":"bill","amountCents":127500,"payFromAccountId":%d,"categoryId":%d,
                 "paidAhead":true,"packSessions":20,
                 "schedule":{"frequency":"weekly","startDate":"2026-08-03","weekendRule":"none","weekdays":[1,4]}}"""
                .formatted(checking, food));
        long entryId = server.post("/api/items/" + training + "/paid-ahead/payments", "{\"date\":\"2026-08-03\",\"amountCents\":127500}", cookie)
                .data().get("payments").get(0).get("entryId").asLong();
        String body = """
                {"name":"Personal training with Priya","kind":"bill","amountCents":127500,"payFromAccountId":%d,"categoryId":%d,
                 "paidAhead":true,"packSessions":20,"effectiveFrom":"2026-10-01",
                 "schedule":{"frequency":"weekly","startDate":"2026-08-03","weekendRule":"none","weekdays":[1,4]}}"""
                .formatted(checking, groceries);

        server.put("/api/items/" + training, body, cookie);

        assertThat(server.get("/api/entries/" + entryId, cookie).data().get("entry").get("categoryId").asLong()).isEqualTo(food);
    }

    @Test
    void changingAnItemNeedsASession() throws Exception {
        Reply refused = server.put("/api/items/" + item, budget(groceries, ",\"categoryEveryDate\":true"), null);

        assertThat(refused.status()).isEqualTo(401);
        assertThat(categoryIn("2026-03-01")).isEqualTo(food);
    }
}
