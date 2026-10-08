package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Buckets and what is moved in and out of them by hand, over real HTTP against a temporary database. */
class BucketsApiTest extends ApiTestBase {

    long checking;
    long savings;
    long visa;

    @BeforeEach
    void addAccounts() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        savings = createAccount("Rainy Day Savings", "savings");
        visa = createAccount("Visa", "credit_card");
    }

    private static String bucketJson(long accountId, String name, String extra) {
        return "{\"accountId\":%d,\"name\":\"%s\",\"color\":\"#b45309\"%s}".formatted(accountId, name, extra.isEmpty() ? "" : "," + extra);
    }

    private long bucket(long accountId, String name, String extra) throws Exception {
        Reply reply = server.post("/api/buckets", bucketJson(accountId, name, extra), cookie);
        assertThat(reply.status()).isEqualTo(201);
        return reply.data().get("bucket").get("id").asLong();
    }

    private void assertError(Reply reply, int status, String message) {
        assertThat(reply.status()).isEqualTo(status);
        assertThat(reply.error()).isEqualTo(message);
    }

    @Nested
    class Auth {
        @Test
        void everyRouteRequiresASession() throws Exception {
            long id = bucket(checking, "Holiday gifts", "");

            assertThat(server.get("/api/buckets", null).status()).isEqualTo(401);
            assertThat(server.post("/api/buckets", bucketJson(checking, "Car repairs", ""), null).status()).isEqualTo(401);
            assertThat(server.get("/api/buckets/" + id, null).status()).isEqualTo(401);
            assertThat(server.put("/api/buckets/" + id, bucketJson(checking, "Gifts", ""), null).status()).isEqualTo(401);
            assertThat(server.delete("/api/buckets/" + id, null).status()).isEqualTo(401);
            assertThat(server.post("/api/buckets/" + id + "/changes", "{\"date\":\"2026-10-01\",\"amountCents\":500}", null).status())
                    .isEqualTo(401);
            assertThat(server.delete("/api/buckets/" + id + "/changes/1", null).status()).isEqualTo(401);
        }
    }

    @Nested
    class Creating {
        @Test
        void addsAnEmptyBucketOpenedToday() throws Exception {
            Reply reply = server.post("/api/buckets", bucketJson(checking, "Holiday gifts", "\"goalCents\":60000,\"note\":\"For December\""), cookie);

            assertThat(reply.status()).isEqualTo(201);
            JsonNode bucket = reply.data().get("bucket");
            assertThat(bucket.get("accountId").asLong()).isEqualTo(checking);
            assertThat(bucket.get("name").asText()).isEqualTo("Holiday gifts");
            assertThat(bucket.get("color").asText()).isEqualTo("#b45309");
            assertThat(bucket.get("goalCents").asLong()).isEqualTo(60000);
            assertThat(bucket.get("note").asText()).isEqualTo("For December");
            assertThat(bucket.get("archived").asBoolean()).isFalse();
            assertThat(bucket.get("openedOn").asText()).isEqualTo("2026-10-01");
            assertThat(bucket.get("balanceCents").asLong()).isZero();
            assertThat(reply.data().get("changes")).isEmpty();
        }

        @Test
        void whatIsAlreadySetAsideBecomesItsFirstChange() throws Exception {
            Reply reply = server.post("/api/buckets", bucketJson(savings, "Emergency fund", "\"startingCents\":250000"), cookie);

            assertThat(reply.data().get("bucket").get("balanceCents").asLong()).isEqualTo(250000);
            JsonNode change = reply.data().get("changes").get(0);
            assertThat(change.get("date").asText()).isEqualTo("2026-10-01");
            assertThat(change.get("amountCents").asLong()).isEqualTo(250000);
            assertThat(change.get("note").asText()).isEqualTo("Starting amount");
        }

        @Test
        void aGoalOfNothingIsNoGoal() throws Exception {
            Reply reply = server.post("/api/buckets", bucketJson(checking, "Holiday gifts", "\"goalCents\":0"), cookie);

            assertThat(reply.data().get("bucket").get("goalCents").isNull()).isTrue();
        }

        @Test
        void onlyCheckingAndSavingsCanHoldBuckets() throws Exception {
            String message = "Please choose a checking or savings account for this bucket.";

            assertError(server.post("/api/buckets", bucketJson(visa, "Holiday gifts", ""), cookie), 400, message);
            assertError(server.post("/api/buckets", bucketJson(999, "Holiday gifts", ""), cookie), 400, message);
            assertError(server.post("/api/buckets", "{\"name\":\"Holiday gifts\",\"color\":\"#b45309\"}", cookie), 400, message);
        }

        @Test
        void explainsWhatIsMissingOrWrong() throws Exception {
            assertError(server.post("/api/buckets", bucketJson(checking, "", ""), cookie), 400, "Please add a name for the bucket.");
            assertError(server.post("/api/buckets", bucketJson(checking, "Gifts", "").replace("#b45309", "orange"), cookie),
                    400, "Colors need to be a six-digit hex code, like #0f766e.");
            assertError(server.post("/api/buckets", bucketJson(checking, "Gifts", "\"goalCents\":-1"), cookie),
                    400, "The goal can't be less than zero.");
            assertError(server.post("/api/buckets", bucketJson(checking, "Gifts", "\"startingCents\":-1"), cookie),
                    400, "The starting amount can't be less than zero.");
            assertError(server.post("/api/buckets", "not json", cookie), 400, "We couldn't read that. Please check the form and try again.");
        }

        @Test
        void aNameCanOnlyBeUsedOnceInAnAccount() throws Exception {
            bucket(checking, "Holiday gifts", "");

            assertError(server.post("/api/buckets", bucketJson(checking, "holiday GIFTS", ""), cookie),
                    409, "This account already has a bucket with that name. Please choose another.");
            // Another account may have its own.
            assertThat(server.post("/api/buckets", bucketJson(savings, "Holiday gifts", ""), cookie).status()).isEqualTo(201);
        }

        @Test
        void addingABucketCanBeUndone() throws Exception {
            Reply added = server.post("/api/buckets", bucketJson(checking, "Holiday gifts", "\"startingCents\":15000"), cookie);
            assertThat(added.changed()).isTrue();

            server.post("/api/undo", null, cookie);

            assertThat(server.get("/api/buckets", cookie).data()).isEmpty();
        }
    }

    @Nested
    class Listing {
        @Test
        void listsEveryBucketWithWhatItHoldsToday() throws Exception {
            bucket(checking, "Holiday gifts", "\"startingCents\":15000");
            bucket(savings, "Emergency fund", "");

            JsonNode data = server.get("/api/buckets", cookie).data();

            assertThat(data.findValuesAsText("name")).containsExactly("Holiday gifts", "Emergency fund");
            assertThat(data.get(0).get("balanceCents").asLong()).isEqualTo(15000);
            assertThat(data.get(1).get("balanceCents").asLong()).isZero();
        }

        @Test
        void anUnknownBucketIsNotFound() throws Exception {
            assertThat(server.get("/api/buckets/999", cookie).status()).isEqualTo(404);
            assertThat(server.get("/api/buckets/abc", cookie).status()).isEqualTo(404);
        }
    }

    @Nested
    class Changing {
        @Test
        void changesItsNameColorGoalAndNote() throws Exception {
            long id = bucket(checking, "Holiday gifts", "\"goalCents\":60000");

            Reply reply = server.put("/api/buckets/" + id,
                    "{\"name\":\"Gifts\",\"color\":\"#0F766E\",\"goalCents\":80000,\"note\":\"Birthdays too\"}", cookie);

            assertThat(reply.status()).isEqualTo(200);
            JsonNode bucket = reply.data().get("bucket");
            assertThat(bucket.get("name").asText()).isEqualTo("Gifts");
            assertThat(bucket.get("color").asText()).isEqualTo("#0f766e");
            assertThat(bucket.get("goalCents").asLong()).isEqualTo(80000);
            assertThat(bucket.get("note").asText()).isEqualTo("Birthdays too");
            // It stays in the account it was added to.
            assertThat(bucket.get("accountId").asLong()).isEqualTo(checking);
        }

        @Test
        void anArchivedBucketNoLongerHoldsAnythingBack() throws Exception {
            long id = bucket(checking, "Holiday gifts", "\"startingCents\":15000");

            Reply reply = server.put("/api/buckets/" + id, "{\"name\":\"Holiday gifts\",\"color\":\"#b45309\",\"archived\":true}", cookie);

            assertThat(reply.data().get("bucket").get("archived").asBoolean()).isTrue();
            assertThat(reply.data().get("bucket").get("balanceCents").asLong()).isZero();
        }

        @Test
        void aNameAlreadyTakenIsRefusedButItsOwnNameIsFine() throws Exception {
            bucket(checking, "Car repairs", "");
            long id = bucket(checking, "Holiday gifts", "");

            assertError(server.put("/api/buckets/" + id, "{\"name\":\"Car repairs\",\"color\":\"#b45309\"}", cookie),
                    409, "This account already has a bucket with that name. Please choose another.");
            assertThat(server.put("/api/buckets/" + id, "{\"name\":\"Holiday Gifts\",\"color\":\"#b45309\"}", cookie).status()).isEqualTo(200);
        }

        @Test
        void anUnknownBucketIsNotFound() throws Exception {
            assertThat(server.put("/api/buckets/999", "{\"name\":\"Gifts\",\"color\":\"#b45309\"}", cookie).status()).isEqualTo(404);
        }
    }

    @Nested
    class MovingMoneyByHand {
        long id;

        @BeforeEach
        void addABucket() throws Exception {
            id = bucket(checking, "Holiday gifts", "\"startingCents\":15000");
        }

        @Test
        void moneyCanBePutInAndTakenOut() throws Exception {
            Reply in = server.post("/api/buckets/" + id + "/changes",
                    "{\"date\":\"2026-10-01\",\"amountCents\":5000,\"note\":\"Birthday money\"}", cookie);
            assertThat(in.status()).isEqualTo(201);
            assertThat(in.data().get("bucket").get("balanceCents").asLong()).isEqualTo(20000);

            Reply out = server.post("/api/buckets/" + id + "/changes", "{\"date\":\"2026-10-01\",\"amountCents\":-8000}", cookie);
            assertThat(out.data().get("bucket").get("balanceCents").asLong()).isEqualTo(12000);
            assertThat(out.data().get("changes")).hasSize(3);
            // Newest first.
            assertThat(out.data().get("changes").get(0).get("amountCents").asLong()).isEqualTo(-8000);
        }

        @Test
        void aChangeDatedLaterDoesNotCountYet() throws Exception {
            Reply reply = server.post("/api/buckets/" + id + "/changes", "{\"date\":\"2026-10-20\",\"amountCents\":5000}", cookie);

            assertThat(reply.data().get("bucket").get("balanceCents").asLong()).isEqualTo(15000);
        }

        @Test
        void aChangeCanBeRemoved() throws Exception {
            long changeId = server.get("/api/buckets/" + id, cookie).data().get("changes").get(0).get("id").asLong();

            Reply reply = server.delete("/api/buckets/" + id + "/changes/" + changeId, cookie);

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.data().get("changes")).isEmpty();
            assertThat(reply.data().get("bucket").get("balanceCents").asLong()).isZero();
            assertThat(server.delete("/api/buckets/" + id + "/changes/" + changeId, cookie).status()).isEqualTo(404);
        }

        @Test
        void explainsWhatIsMissingOrWrong() throws Exception {
            String path = "/api/buckets/" + id + "/changes";

            assertError(server.post(path, "{\"amountCents\":500}", cookie), 400, "Please choose the date.");
            assertError(server.post(path, "{\"date\":\"2026-10-01\"}", cookie), 400, "The amount is missing. Please enter an amount.");
            assertError(server.post(path, "{\"date\":\"2026-10-01\",\"amountCents\":0}", cookie),
                    400, "Please enter how much to put in or take out.");
            assertThat(server.post("/api/buckets/999/changes", "{\"date\":\"2026-10-01\",\"amountCents\":500}", cookie).status())
                    .isEqualTo(404);
        }
    }

    @Nested
    class Deleting {
        @Test
        void deletesABucketWithItsChanges() throws Exception {
            long id = bucket(checking, "Holiday gifts", "\"startingCents\":15000");

            Reply reply = server.delete("/api/buckets/" + id, cookie);

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.data().get("deleted").asBoolean()).isTrue();
            assertThat(server.get("/api/buckets", cookie).data()).isEmpty();
            assertThat(server.delete("/api/buckets/" + id, cookie).status()).isEqualTo(404);
        }

        @Test
        void aBucketARecurringItemUsesCannotBeDeletedOrArchived() throws Exception {
            long id = bucket(checking, "Holiday gifts", "");
            created("/api/items", """
                    {"name":"Gift fund","kind":"bucket_move","amountCents":5000,"bucketId":%d,
                     "schedule":{"frequency":"every_pay_period","startDate":"2026-10-01"}}""".formatted(id));
            String message = "A recurring item still puts money in this bucket. Point that somewhere else first.";

            assertError(server.delete("/api/buckets/" + id, cookie), 409, message);
            assertError(server.put("/api/buckets/" + id, "{\"name\":\"Holiday gifts\",\"color\":\"#b45309\",\"archived\":true}", cookie),
                    409, message);
        }

        @Test
        void interestThatWentToADeletedBucketGoesBackToUnassigned() throws Exception {
            long id = bucket(savings, "Emergency fund", "");
            server.put("/api/accounts/" + savings + "/interest",
                    "{\"creditDay\":31,\"destination\":\"bucket\",\"destinationBucketId\":%d}".formatted(id), cookie);

            server.delete("/api/buckets/" + id, cookie);

            JsonNode interest = server.get("/api/accounts/" + savings, cookie).data().get("interest");
            assertThat(interest.get("destination").asText()).isEqualTo("unassigned");
            assertThat(interest.get("destinationBucketId").isNull()).isTrue();
        }

        @Test
        void deletingABucketCanBeUndone() throws Exception {
            long id = bucket(checking, "Holiday gifts", "\"startingCents\":15000");
            server.delete("/api/buckets/" + id, cookie);

            server.post("/api/undo", null, cookie);

            assertThat(server.get("/api/buckets/" + id, cookie).data().get("bucket").get("balanceCents").asLong()).isEqualTo(15000);
        }
    }
}
