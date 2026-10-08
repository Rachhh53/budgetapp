package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Categories and tags over real HTTP against a temporary database. */
class CategoriesApiTest extends ApiTestBase {

    private static final String FOOD = "{\"name\":\"Food\",\"color\":\"#15803d\"}";
    private static final String MEDICAL = "{\"name\":\"Medical\",\"color\":\"#be185d\"}";

    private long category(String name, Long parentId) throws Exception {
        return created("/api/categories",
                "{\"name\":\"%s\",\"color\":\"#0f766e\",\"parentId\":%s}".formatted(name, parentId));
    }

    private long tag(String name) throws Exception {
        return created("/api/tags", "{\"name\":\"%s\",\"color\":\"#be185d\"}".formatted(name));
    }

    /** A monthly bill of $100 from a fresh checking account, filed and tagged as given. */
    private long bill(Long categoryId, String tagIds) throws Exception {
        long checking = createAccount("Everyday Checking", "checking");
        return created("/api/items", """
                {"name":"Pharmacy","kind":"bill","amountCents":10000,"categoryId":%s,"payFromAccountId":%d,"tagIds":%s,
                 "schedule":{"frequency":"monthly","day1":5,"startDate":"2026-01-01"}}"""
                .formatted(categoryId, checking, tagIds));
    }

    @Nested
    class Auth {
        @Test
        void everyCategoryAndTagRouteRequiresASession() throws Exception {
            long food = category("Food", null);
            long medical = tag("Medical");

            assertThat(server.get("/api/categories", null).status()).isEqualTo(401);
            assertThat(server.post("/api/categories", MEDICAL, null).status()).isEqualTo(401);
            assertThat(server.put("/api/categories/" + food, MEDICAL, null).status()).isEqualTo(401);
            assertThat(server.delete("/api/categories/" + food, null).status()).isEqualTo(401);
            assertThat(server.get("/api/tags", null).status()).isEqualTo(401);
            assertThat(server.post("/api/tags", FOOD, null).status()).isEqualTo(401);
            assertThat(server.put("/api/tags/" + medical, FOOD, null).status()).isEqualTo(401);
            assertThat(server.delete("/api/tags/" + medical, null).status()).isEqualTo(401);
            // Nothing was changed by any of those.
            assertThat(server.get("/api/categories", cookie).data().findValuesAsText("name")).containsExactly("Food");
            assertThat(server.get("/api/tags", cookie).data().findValuesAsText("name")).containsExactly("Medical");
        }
    }

    @Nested
    class Categories {
        @Test
        void startsEmptyAndAddsACategoryWithItsColor() throws Exception {
            assertThat(server.get("/api/categories", cookie).data()).isEmpty();

            Reply reply = server.post("/api/categories", "{\"name\":\"  Food \",\"color\":\"#15803D\"}", cookie);

            assertThat(reply.status()).isEqualTo(201);
            assertThat(reply.data().get("name").asText()).isEqualTo("Food");
            assertThat(reply.data().get("color").asText()).isEqualTo("#15803d");
            assertThat(reply.data().get("parentId").isNull()).isTrue();
            assertThat(reply.data().get("itemCount").asInt()).isZero();
        }

        @Test
        void listsEachCategoryFollowedByItsSubCategories() throws Exception {
            long housing = category("Housing", null);
            long food = category("Food", null);
            category("Rent", housing);
            category("Groceries", food);
            category("Insurance", housing);

            assertThat(server.get("/api/categories", cookie).data().findValuesAsText("name"))
                    .containsExactly("Housing", "Rent", "Insurance", "Food", "Groceries");
        }

        @Test
        void aNameCanOnlyBeUsedOncePerLevelWhateverItsCase() throws Exception {
            long housing = category("Housing", null);
            category("Other", housing);

            Reply again = server.post("/api/categories", "{\"name\":\"housing\",\"color\":\"#0f766e\"}", cookie);
            // The same name under a different parent is fine.
            Reply elsewhere = server.post("/api/categories", "{\"name\":\"Other\",\"color\":\"#0f766e\"}", cookie);

            assertThat(again.status()).isEqualTo(409);
            assertThat(again.error()).isEqualTo("You already have a category called housing.");
            assertThat(elsewhere.status()).isEqualTo(201);
        }

        @Test
        void explainsWhatIsMissingOrWrong() throws Exception {
            long housing = category("Housing", null);
            long rent = category("Rent", housing);

            assertThat(server.post("/api/categories", "{\"color\":\"#0f766e\"}", cookie).error())
                    .isEqualTo("Please add a name for the category.");
            assertThat(server.post("/api/categories", "{\"name\":\"Food\",\"color\":\"green\"}", cookie).error())
                    .isEqualTo("Colors need to be a six-digit hex code, like #0f766e.");
            assertThat(server.post("/api/categories", "{\"name\":\"Food\",\"color\":\"#0f766e\",\"parentId\":999}", cookie).error())
                    .isEqualTo("Please choose the parent category from the list.");
            assertThat(server.post("/api/categories", "{\"name\":\"Deposit\",\"color\":\"#0f766e\",\"parentId\":%d}".formatted(rent), cookie).error())
                    .isEqualTo("A sub-category can't have sub-categories of its own.");
            assertThat(server.post("/api/categories", "not json", cookie).status()).isEqualTo(400);
        }

        @Test
        void changesNameColorAndParent() throws Exception {
            long housing = category("Housing", null);
            long utilities = category("Utilities", null);

            Reply reply = server.put("/api/categories/" + utilities,
                    "{\"name\":\"Bills\",\"color\":\"#b45309\",\"parentId\":%d}".formatted(housing), cookie);

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.data().get("name").asText()).isEqualTo("Bills");
            assertThat(reply.data().get("color").asText()).isEqualTo("#b45309");
            assertThat(reply.data().get("parentId").asLong()).isEqualTo(housing);
        }

        @Test
        void aCategoryWithSubCategoriesCannotBecomeOne() throws Exception {
            long housing = category("Housing", null);
            category("Rent", housing);
            long food = category("Food", null);

            Reply reply = server.put("/api/categories/" + housing,
                    "{\"name\":\"Housing\",\"color\":\"#0f766e\",\"parentId\":%d}".formatted(food), cookie);

            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.error()).isEqualTo("A category that has sub-categories can't be moved inside another one.");
        }

        @Test
        void countsItsItemsAndWhatTheyCostAYearIncludingSubCategories() throws Exception {
            long medical = category("Medical", null);
            long prescriptions = category("Prescriptions", medical);
            bill(prescriptions, "[]");

            JsonNode categories = server.get("/api/categories", cookie).data();

            assertThat(categories.get(0).get("itemCount").asInt()).isEqualTo(1);
            assertThat(categories.get(0).get("perYearCents").asLong()).isEqualTo(120000);
            assertThat(categories.get(1).get("itemCount").asInt()).isEqualTo(1);
            assertThat(categories.get(1).get("perYearCents").asLong()).isEqualTo(120000);
        }

        @Test
        void cannotBeDeletedWhileInUseOrWhileItHasSubCategories() throws Exception {
            long medical = category("Medical", null);
            long prescriptions = category("Prescriptions", medical);
            long item = bill(prescriptions, "[]");

            Reply parent = server.delete("/api/categories/" + medical, cookie);
            Reply used = server.delete("/api/categories/" + prescriptions, cookie);

            assertThat(parent.status()).isEqualTo(409);
            assertThat(parent.error()).isEqualTo("This category still has sub-categories. Move or delete those first.");
            assertThat(used.status()).isEqualTo(409);
            assertThat(used.error()).isEqualTo("This category is still used by an item. Choose another category for those first.");

            // Once nothing uses them, both can go.
            server.delete("/api/items/" + item, cookie);
            assertThat(server.delete("/api/categories/" + prescriptions, cookie).status()).isEqualTo(200);
            assertThat(server.delete("/api/categories/" + medical, cookie).status()).isEqualTo(200);
            assertThat(server.get("/api/categories", cookie).data()).isEmpty();
        }

        @Test
        void anUnknownCategoryIsNotFound() throws Exception {
            assertThat(server.put("/api/categories/999", FOOD, cookie).status()).isEqualTo(404);
            assertThat(server.delete("/api/categories/999", cookie).status()).isEqualTo(404);
            assertThat(server.delete("/api/categories/abc", cookie).status()).isEqualTo(404);
        }
    }

    @Nested
    class Tags {
        @Test
        void addsListsByNameAndChangesATag() throws Exception {
            tag("Tax deductible");
            long medical = tag("Medical – out of pocket");

            Reply changed = server.put("/api/tags/" + medical, "{\"name\":\"Medical\",\"color\":\"#9f1239\"}", cookie);

            assertThat(changed.status()).isEqualTo(200);
            assertThat(changed.data().get("color").asText()).isEqualTo("#9f1239");
            assertThat(server.get("/api/tags", cookie).data().findValuesAsText("name")).containsExactly("Medical", "Tax deductible");
        }

        @Test
        void aNameCanOnlyBeUsedOnce() throws Exception {
            tag("Reimbursable");
            long other = tag("Subscriptions");

            Reply again = server.post("/api/tags", "{\"name\":\"REIMBURSABLE\",\"color\":\"#0f766e\"}", cookie);
            Reply renamed = server.put("/api/tags/" + other, "{\"name\":\"Reimbursable\",\"color\":\"#0f766e\"}", cookie);

            assertThat(again.status()).isEqualTo(409);
            assertThat(again.error()).isEqualTo("You already have a tag called REIMBURSABLE.");
            assertThat(renamed.status()).isEqualTo(409);
        }

        @Test
        void explainsWhatIsMissingOrWrong() throws Exception {
            assertThat(server.post("/api/tags", "{\"name\":\" \",\"color\":\"#0f766e\"}", cookie).error())
                    .isEqualTo("Please add a name for the tag.");
            assertThat(server.post("/api/tags", "{\"name\":\"Medical\"}", cookie).error())
                    .isEqualTo("Colors need to be a six-digit hex code, like #0f766e.");
        }

        @Test
        void countsItsItemsAndCannotBeDeletedWhileOnOne() throws Exception {
            long medical = tag("Medical");
            long item = bill(null, "[%d]".formatted(medical));

            JsonNode tags = server.get("/api/tags", cookie).data();
            Reply refused = server.delete("/api/tags/" + medical, cookie);

            assertThat(tags.get(0).get("itemCount").asInt()).isEqualTo(1);
            assertThat(tags.get(0).get("perYearCents").asLong()).isEqualTo(120000);
            assertThat(refused.status()).isEqualTo(409);
            assertThat(refused.error()).isEqualTo("This tag is still on an item. Take it off those first.");

            server.delete("/api/items/" + item, cookie);
            assertThat(server.delete("/api/tags/" + medical, cookie).status()).isEqualTo(200);
        }

        @Test
        void anUnknownTagIsNotFound() throws Exception {
            assertThat(server.put("/api/tags/999", FOOD, cookie).status()).isEqualTo(404);
            assertThat(server.delete("/api/tags/999", cookie).status()).isEqualTo(404);
        }
    }
}
