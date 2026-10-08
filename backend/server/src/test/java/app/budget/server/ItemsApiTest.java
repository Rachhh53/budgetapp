package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Recurring items over real HTTP against a temporary database. "Today" is Thursday 2026-10-01,
 * and the paycheck arrives on the 1st and 15th.
 */
class ItemsApiTest extends ApiTestBase {

    private static final String MONTHLY_5TH = "{\"frequency\":\"monthly\",\"day1\":5,\"startDate\":\"2026-01-01\"}";
    private static final String EVERY_PAYCHECK = "{\"frequency\":\"every_pay_period\",\"startDate\":\"2026-10-01\"}";

    long checking;
    long savings;
    long visa;

    @BeforeEach
    void addAccountsAndAPaycheck() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        savings = createAccount("Rainy Day Savings", "savings");
        visa = createAccount("Visa", "credit_card");
        createSalary(checking);
    }

    /** A bill paid from checking. {@code extra} is spliced into the JSON, e.g. {@code "\"autopay\":true"}. */
    private String bill(String name, long cents, String schedule, String extra) {
        return "{\"name\":\"%s\",\"kind\":\"bill\",\"amountCents\":%d,\"payFromAccountId\":%d,\"schedule\":%s%s}"
                .formatted(name, cents, checking, schedule, extra.isEmpty() ? "" : "," + extra);
    }

    private String carLoan() {
        return bill("Car loan", 41200, MONTHLY_5TH, "\"autopay\":true");
    }

    @Nested
    class Auth {
        @Test
        void everyItemRouteRequiresASession() throws Exception {
            long id = created("/api/items", carLoan());

            assertThat(server.get("/api/items", null).status()).isEqualTo(401);
            assertThat(server.post("/api/items", carLoan(), null).status()).isEqualTo(401);
            assertThat(server.get("/api/items/" + id, null).status()).isEqualTo(401);
            assertThat(server.put("/api/items/" + id, bill("Changed", 1, MONTHLY_5TH, ""), null).status()).isEqualTo(401);
            assertThat(server.delete("/api/items/" + id, null).status()).isEqualTo(401);
            assertThat(server.put("/api/items/" + id + "/occurrences/2026-10-05", "{\"paid\":true}", null).status()).isEqualTo(401);
            // Nothing was changed by any of those.
            JsonNode items = server.get("/api/items", cookie).data();
            assertThat(items).hasSize(1);
            assertThat(items.get(0).get("name").asText()).isEqualTo("Car loan");
        }
    }

    @Nested
    class Creating {
        @Test
        void addsABillAndWorksOutWhatItCostsAYearAndWhenItIsNext() throws Exception {
            Reply reply = server.post("/api/items", carLoan(), cookie);

            assertThat(reply.status()).isEqualTo(201);
            JsonNode item = reply.data();
            assertThat(item.get("name").asText()).isEqualTo("Car loan");
            assertThat(item.get("kind").asText()).isEqualTo("bill");
            assertThat(item.get("amountCents").asLong()).isEqualTo(41200);
            assertThat(item.get("amountMode").asText()).isEqualTo("per_occurrence");
            assertThat(item.get("placement").asText()).isEqualTo("due_period");
            assertThat(item.get("autopay").asBoolean()).isTrue();
            assertThat(item.get("estimate").asBoolean()).isFalse();
            assertThat(item.get("payFromAccountId").asLong()).isEqualTo(checking);
            assertThat(item.get("payToAccountId").isNull()).isTrue();
            assertThat(item.get("schedule").get("frequency").asText()).isEqualTo("monthly");
            assertThat(item.get("schedule").get("day1").asInt()).isEqualTo(5);
            assertThat(item.get("perOccurrenceCents").asLong()).isEqualTo(41200);
            assertThat(item.get("perYearCents").asLong()).isEqualTo(494400);
            assertThat(item.get("next").get("dueDate").asText()).isEqualTo("2026-10-05");
            assertThat(item.get("next").get("periodStart").asText()).isEqualTo("2026-10-01");
            assertThat(item.get("next").get("early").asBoolean()).isFalse();
            assertThat(item.get("history")).hasSize(1);
        }

        @Test
        void rentDueOnPaydayAndPaidByHandComesOutOfThePaycheckBefore() throws Exception {
            String rent = bill("Rent", 155000, "{\"frequency\":\"monthly\",\"day1\":1,\"startDate\":\"2026-01-01\"}", "");

            JsonNode next = server.post("/api/items", rent, cookie).data().get("next");

            assertThat(next.get("dueDate").asText()).isEqualTo("2026-10-01");
            assertThat(next.get("periodStart").asText()).isEqualTo("2026-09-15");
            assertThat(next.get("early").asBoolean()).isTrue();
        }

        @Test
        void theSameRentOnAutopayStaysInThePeriodItIsDueIn() throws Exception {
            String rent = bill("Rent", 155000, "{\"frequency\":\"monthly\",\"day1\":1,\"startDate\":\"2026-01-01\"}", "\"autopay\":true");

            JsonNode next = server.post("/api/items", rent, cookie).data().get("next");

            assertThat(next.get("periodStart").asText()).isEqualTo("2026-10-01");
            assertThat(next.get("early").asBoolean()).isFalse();
        }

        @Test
        void aBillPlacedOneEarlyIsPaidFromThePeriodBefore() throws Exception {
            String electric = bill("Electric", 14000, "{\"frequency\":\"monthly\",\"day1\":16,\"startDate\":\"2026-01-01\"}",
                    "\"placement\":\"one_early\",\"estimate\":true");

            JsonNode item = server.post("/api/items", electric, cookie).data();

            assertThat(item.get("estimate").asBoolean()).isTrue();
            assertThat(item.get("next").get("dueDate").asText()).isEqualTo("2026-10-16");
            assertThat(item.get("next").get("periodStart").asText()).isEqualTo("2026-10-01");
            assertThat(item.get("next").get("early").asBoolean()).isTrue();
        }

        @Test
        void aBudgetComesRoundEveryPaycheckOnTheDayItIsGiven() throws Exception {
            String groceries = "{\"name\":\"Groceries\",\"kind\":\"budget\",\"amountCents\":30000,\"payFromAccountId\":%d,\"schedule\":%s%s}";

            JsonNode onPayday = server.post("/api/items", groceries.formatted(checking, EVERY_PAYCHECK, ""), cookie).data();
            JsonNode threeDaysIn = server.post("/api/items",
                    groceries.formatted(checking, EVERY_PAYCHECK, ",\"daysAfterPayday\":3").replace("Groceries", "Gas"), cookie).data();

            // 24 paychecks a year.
            assertThat(onPayday.get("perYearCents").asLong()).isEqualTo(720000);
            assertThat(onPayday.get("next").get("dueDate").asText()).isEqualTo("2026-10-01");
            // A budget belongs to its own paycheck even though it is dated on payday.
            assertThat(onPayday.get("next").get("periodStart").asText()).isEqualTo("2026-10-01");
            assertThat(onPayday.get("next").get("early").asBoolean()).isFalse();
            assertThat(threeDaysIn.get("daysAfterPayday").asInt()).isEqualTo(3);
            assertThat(threeDaysIn.get("next").get("dueDate").asText()).isEqualTo("2026-10-04");
        }

        @Test
        void anAnnualAmountIsSharedOutOverTheSchedule() throws Exception {
            String insurance = bill("Car insurance", 120000,
                    "{\"frequency\":\"yearly\",\"month\":3,\"day1\":12,\"startDate\":\"2026-01-01\"}", "\"amountMode\":\"annual\"");
            String membership = bill("Gym", 120000, MONTHLY_5TH, "\"amountMode\":\"annual\"");

            JsonNode yearly = server.post("/api/items", insurance, cookie).data();
            JsonNode monthly = server.post("/api/items", membership, cookie).data();

            assertThat(yearly.get("amountMode").asText()).isEqualTo("annual");
            assertThat(yearly.get("perOccurrenceCents").asLong()).isEqualTo(120000);
            assertThat(yearly.get("perYearCents").asLong()).isEqualTo(120000);
            assertThat(yearly.get("next").get("dueDate").asText()).isEqualTo("2027-03-12");
            assertThat(monthly.get("perOccurrenceCents").asLong()).isEqualTo(10000);
            assertThat(monthly.get("perYearCents").asLong()).isEqualTo(120000);
        }

        @Test
        void aTransferAndACardPaymentNameWhereTheMoneyGoes() throws Exception {
            String transfer = """
                    {"name":"To savings","kind":"transfer","amountCents":20000,"payFromAccountId":%d,"payToAccountId":%d,
                     "autopay":true,"schedule":{"frequency":"monthly","day1":2,"startDate":"2026-01-01"}}"""
                    .formatted(checking, savings);
            String payment = """
                    {"name":"Visa payment","kind":"card_payment","amountCents":61248,"payFromAccountId":%d,"payToAccountId":%d,
                     "schedule":{"frequency":"monthly","day1":13,"startDate":"2026-01-01"}}"""
                    .formatted(checking, visa);

            assertThat(server.post("/api/items", transfer, cookie).data().get("payToAccountId").asLong()).isEqualTo(savings);
            assertThat(server.post("/api/items", payment, cookie).data().get("kind").asText()).isEqualTo("card_payment");
        }

        @Test
        void aBillCanBePaidFromACreditCard() throws Exception {
            String internet = """
                    {"name":"Internet","kind":"bill","amountCents":7000,"payFromAccountId":%d,"autopay":true,
                     "schedule":{"frequency":"monthly","day1":10,"startDate":"2026-01-01"}}""".formatted(visa);

            Reply reply = server.post("/api/items", internet, cookie);

            assertThat(reply.status()).isEqualTo(201);
            assertThat(reply.data().get("payFromAccountId").asLong()).isEqualTo(visa);
        }

        @Test
        void keepsItsCategoryTagsAndNote() throws Exception {
            long utilities = created("/api/categories", "{\"name\":\"Utilities\",\"color\":\"#b45309\"}");
            long tag = created("/api/tags", "{\"name\":\"Subscriptions\",\"color\":\"#475569\"}");

            JsonNode item = server.post("/api/items", bill("Phone", 8500, MONTHLY_5TH,
                    "\"categoryId\":%d,\"tagIds\":[%d],\"note\":\"Family plan\"".formatted(utilities, tag)), cookie).data();

            assertThat(item.get("categoryId").asLong()).isEqualTo(utilities);
            assertThat(item.get("tagIds").get(0).asLong()).isEqualTo(tag);
            assertThat(item.get("note").asText()).isEqualTo("Family plan");
        }

        @Test
        void explainsWhatIsMissingOrWrong() throws Exception {
            assertError(bill("", 100, MONTHLY_5TH, ""), "Please add a name for this item.");
            assertError(bill("Rent", 100, MONTHLY_5TH, "").replace("\"bill\"", "\"rent\""), "Please choose the type of item from the list.");
            assertError(bill("Rent", 100, MONTHLY_5TH, "").replace("\"bill\"", "\"bucket_move\""),
                    "Please choose the bucket this money goes into.");
            assertError(bill("Rent", -1, MONTHLY_5TH, ""), "The amount can't be less than zero.");
            assertError("{\"name\":\"Rent\",\"kind\":\"bill\",\"payFromAccountId\":%d,\"schedule\":%s}".formatted(checking, MONTHLY_5TH),
                    "The amount is missing. Please enter an amount.");
            assertError("{\"name\":\"Rent\",\"kind\":\"bill\",\"amountCents\":100,\"schedule\":%s}".formatted(MONTHLY_5TH),
                    "Please choose the account this is paid from.");
            assertError("{\"name\":\"Rent\",\"kind\":\"bill\",\"amountCents\":100,\"payFromAccountId\":999,\"schedule\":%s}".formatted(MONTHLY_5TH),
                    "Please choose the account this is paid from.");
            assertError("{\"name\":\"Rent\",\"kind\":\"bill\",\"amountCents\":100,\"payFromAccountId\":%d}".formatted(checking),
                    "Please choose how often it repeats.");
            assertError(bill("Rent", 100, MONTHLY_5TH, "\"categoryId\":999"), "Please choose the category from the list.");
            assertError(bill("Rent", 100, MONTHLY_5TH, "\"tagIds\":[999]"), "Please choose tags from the list.");
            assertError(bill("Rent", 100, MONTHLY_5TH, "\"placement\":\"late\""), "Please choose which paycheck pays it from the list.");
            assertError(bill("Rent", 100, MONTHLY_5TH, "\"amountMode\":\"weekly\""), "Please choose how the amount is entered from the list.");
            assertError(bill("Rent", 100, EVERY_PAYCHECK, "\"daysAfterPayday\":90"), "Please choose how many days after payday, from 0 to 60.");
        }

        @Test
        void explainsWhatIsWrongWithASchedule() throws Exception {
            assertError(bill("Rent", 100, "{\"frequency\":\"sometimes\",\"startDate\":\"2026-01-01\"}", ""),
                    "Please choose how often it repeats from the list.");
            assertError(bill("Rent", 100, "{\"frequency\":\"manual_dates\",\"startDate\":\"2026-01-01\"}", ""),
                    "Please choose how often it repeats from the list.");
            assertError(bill("Rent", 100, "{\"frequency\":\"monthly\",\"day1\":5}", ""), "Please choose the date it starts.");
            assertError(bill("Rent", 100, "{\"frequency\":\"monthly\",\"startDate\":\"2026-01-01\"}", ""),
                    "Please choose the day of the month from the list.");
            assertError(bill("Rent", 100, "{\"frequency\":\"monthly\",\"day1\":5,\"startDate\":\"2026-01-01\",\"endDate\":\"2025-12-31\"}", ""),
                    "The last date can't be before the first one.");
            assertError(bill("Rent", 100, "{\"frequency\":\"semi_monthly\",\"day1\":5,\"day2\":5,\"startDate\":\"2026-01-01\"}", ""),
                    "Please choose two different days of the month.");
            assertError(bill("Rent", 100, "{\"frequency\":\"every_n_months\",\"day1\":5,\"interval\":1,\"startDate\":\"2026-01-01\"}", ""),
                    "Please choose how many months apart, from 2 to 60.");
            assertError(bill("Rent", 100, "{\"frequency\":\"yearly\",\"day1\":5,\"month\":13,\"startDate\":\"2026-01-01\"}", ""),
                    "Please choose the month from the list.");
            assertError(bill("Rent", 100, "{\"frequency\":\"monthly\",\"day1\":5,\"startDate\":\"2026-01-01\",\"weekendRule\":\"skip\"}", ""),
                    "Please choose what happens on a weekend or holiday from the list.");
        }

        @Test
        void aTransferNeedsTwoDifferentAccountsAndACardPaymentNeedsACard() throws Exception {
            String moving = "{\"name\":\"Move\",\"kind\":\"%s\",\"amountCents\":100,\"payFromAccountId\":%d%s,\"schedule\":%s}";

            assertError(moving.formatted("transfer", checking, "", MONTHLY_5TH), "Please choose the account this money goes to.");
            assertError(moving.formatted("transfer", checking, ",\"payToAccountId\":" + checking, MONTHLY_5TH),
                    "Please choose two different accounts.");
            assertError(moving.formatted("card_payment", checking, "", MONTHLY_5TH), "Please choose the card this payment goes to.");
            assertError(moving.formatted("card_payment", checking, ",\"payToAccountId\":" + savings, MONTHLY_5TH),
                    "A card payment needs to go to one of your credit cards.");
        }

        private void assertError(String body, String message) throws Exception {
            Reply reply = server.post("/api/items", body, cookie);
            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.error()).isEqualTo(message);
        }
    }

    @Nested
    class Changing {
        @Test
        void aChangeAppliesFromItsDateAndKeepsThePriceHistory() throws Exception {
            long id = created("/api/items", carLoan());

            Reply reply = server.put("/api/items/" + id,
                    bill("Car loan", 43000, MONTHLY_5TH, "\"autopay\":true,\"effectiveFrom\":\"2026-11-01\""), cookie);

            assertThat(reply.status()).isEqualTo(200);
            // Today is still October, so the item shows what is in force now …
            assertThat(reply.data().get("amountCents").asLong()).isEqualTo(41200);
            // … and the history holds both prices.
            assertThat(reply.data().get("history").findValues("perOccurrenceCents").stream().map(JsonNode::asLong))
                    .containsExactly(41200L, 43000L);
            assertThat(reply.data().get("history").findValuesAsText("effectiveFrom")).containsExactly("2026-01-01", "2026-11-01");
        }

        @Test
        void aChangeFromTodayIsTheDefault() throws Exception {
            long id = created("/api/items", carLoan());

            JsonNode item = server.put("/api/items/" + id, bill("Car loan", 43000, MONTHLY_5TH, "\"autopay\":true"), cookie).data();

            assertThat(item.get("amountCents").asLong()).isEqualTo(43000);
            assertThat(item.get("history").findValuesAsText("effectiveFrom")).containsExactly("2026-01-01", "2026-10-01");
        }

        @Test
        void aLaterChangeReplacesAnyThatWereDueToStartAfterIt() throws Exception {
            long id = created("/api/items", carLoan());
            server.put("/api/items/" + id, bill("Car loan", 43000, MONTHLY_5TH, "\"autopay\":true,\"effectiveFrom\":\"2027-01-01\""), cookie);

            JsonNode item = server.put("/api/items/" + id,
                    bill("Car loan", 45000, MONTHLY_5TH, "\"autopay\":true,\"effectiveFrom\":\"2026-11-01\""), cookie).data();

            assertThat(item.get("history").findValues("perOccurrenceCents").stream().map(JsonNode::asLong))
                    .containsExactly(41200L, 45000L);
        }

        @Test
        void savingWithoutChangingTheTermsAddsNothingToTheHistory() throws Exception {
            long id = created("/api/items", carLoan());

            JsonNode item = server.put("/api/items/" + id, bill("Auto loan", 41200, MONTHLY_5TH, "\"autopay\":true"), cookie).data();

            assertThat(item.get("name").asText()).isEqualTo("Auto loan");
            assertThat(item.get("history")).hasSize(1);
        }

        @Test
        void anArchivedItemStopsComingUp() throws Exception {
            long id = created("/api/items", carLoan());

            JsonNode item = server.put("/api/items/" + id, bill("Car loan", 41200, MONTHLY_5TH, "\"autopay\":true,\"archived\":true"), cookie).data();

            assertThat(item.get("archived").asBoolean()).isTrue();
            assertThat(item.get("next").isNull()).isTrue();
        }

        @Test
        void validatesAChangeTheSameWayAsANewItem() throws Exception {
            long id = created("/api/items", carLoan());

            Reply reply = server.put("/api/items/" + id, bill("", 41200, MONTHLY_5TH, ""), cookie);

            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.error()).isEqualTo("Please add a name for this item.");
        }

        @Test
        void anUnknownItemIsNotFound() throws Exception {
            assertThat(server.get("/api/items/999", cookie).status()).isEqualTo(404);
            assertThat(server.put("/api/items/999", carLoan(), cookie).status()).isEqualTo(404);
            assertThat(server.delete("/api/items/999", cookie).status()).isEqualTo(404);
            assertThat(server.get("/api/items/abc", cookie).status()).isEqualTo(404);
        }

        @Test
        void deletingRemovesTheItemAndEverythingKeptForIt() throws Exception {
            long id = created("/api/items", carLoan());
            server.put("/api/items/" + id, bill("Car loan", 43000, MONTHLY_5TH, "\"effectiveFrom\":\"2026-11-01\""), cookie);
            server.put("/api/items/" + id + "/occurrences/2026-10-05", "{\"paid\":true}", cookie);

            Reply reply = server.delete("/api/items/" + id, cookie);

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.data().get("deleted").asBoolean()).isTrue();
            assertThat(server.get("/api/items", cookie).data()).isEmpty();
        }

        @Test
        void anAccountInUseByAnItemCannotBeDeleted() throws Exception {
            created("/api/items", carLoan());

            Reply reply = server.delete("/api/accounts/" + checking, cookie);

            assertThat(reply.status()).isEqualTo(409);
        }

        @Test
        void erasingAllDataRemovesItemsToo() throws Exception {
            created("/api/items", carLoan());

            server.send("DELETE", "/api/data", "{\"confirm\":\"erase-all-data\"}", Map.of("Cookie", cookie));

            assertThat(server.get("/api/items", server.unlock()).data()).isEmpty();
        }
    }

    @Nested
    class JustThisOne {
        long electric;

        @BeforeEach
        void addAnEstimatedBill() throws Exception {
            electric = created("/api/items", bill("Electric", 14000,
                    "{\"frequency\":\"monthly\",\"day1\":16,\"startDate\":\"2026-01-01\"}", "\"estimate\":true"));
        }

        private Reply edit(String date, String body) throws Exception {
            return server.put("/api/items/" + electric + "/occurrences/" + date, body, cookie);
        }

        @Test
        void typingOverOneAmountKeepsWhatWasProjectedAndLeavesTheOthersAlone() throws Exception {
            Reply reply = edit("2026-10-16", "{\"amountCents\":15250}");

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.data().get("amountCents").asLong()).isEqualTo(15250);
            assertThat(reply.data().get("projectedCents").asLong()).isEqualTo(14000);
            // A typed amount is the amount: no longer an estimate.
            assertThat(reply.data().get("estimate").asBoolean()).isFalse();
            // Typing over it again keeps the original projection, not the first edit.
            assertThat(edit("2026-10-16", "{\"amountCents\":15300}").data().get("projectedCents").asLong()).isEqualTo(14000);
            // The item itself, and its other occurrences, are unchanged.
            JsonNode item = server.get("/api/items/" + electric, cookie).data();
            assertThat(item.get("amountCents").asLong()).isEqualTo(14000);
            assertThat(edit("2026-11-16", "{\"note\":\"\"}").data().get("amountCents").asLong()).isEqualTo(14000);
        }

        @Test
        void markingPaidRecordsWhenAndTheConfirmationCode() throws Exception {
            JsonNode paid = edit("2026-10-16", "{\"paid\":true,\"paidDate\":\"2026-10-13\",\"confirmationCode\":\" VX93-2210 \"}").data();

            assertThat(paid.get("paid").asBoolean()).isTrue();
            assertThat(paid.get("paidDate").asText()).isEqualTo("2026-10-13");
            assertThat(paid.get("confirmationCode").asText()).isEqualTo("VX93-2210");
            // Paid means final.
            assertThat(paid.get("estimate").asBoolean()).isFalse();
            // A paid occurrence is no longer the next one to pay.
            assertThat(server.get("/api/items/" + electric, cookie).data().get("next").get("dueDate").asText()).isEqualTo("2026-11-16");
        }

        @Test
        void onlyWhatIsSentChangesAndAnEmptyCodeClearsIt() throws Exception {
            edit("2026-10-16", "{\"paid\":true,\"paidDate\":\"2026-10-13\",\"confirmationCode\":\"VX93-2210\",\"note\":\"Paid online\"}");

            JsonNode cleared = edit("2026-10-16", "{\"confirmationCode\":\"\"}").data();
            JsonNode unpaid = edit("2026-10-16", "{\"paid\":false}").data();

            assertThat(cleared.get("confirmationCode").isNull()).isTrue();
            assertThat(cleared.get("paid").asBoolean()).isTrue();
            assertThat(cleared.get("note").asText()).isEqualTo("Paid online");
            assertThat(unpaid.get("paid").asBoolean()).isFalse();
            assertThat(unpaid.get("paidDate").isNull()).isTrue();
            assertThat(unpaid.get("estimate").asBoolean()).isTrue();
        }

        @Test
        void oneOccurrenceCanBeMovedToAnotherDateOrPayPeriodOrSkipped() throws Exception {
            JsonNode moved = edit("2026-10-16", "{\"dueDate\":\"2026-10-20\"}").data();
            JsonNode earlier = edit("2026-10-16", "{\"periodStart\":\"2026-10-01\"}").data();
            JsonNode skipped = edit("2026-11-16", "{\"skipped\":true}").data();

            assertThat(moved.get("dueDate").asText()).isEqualTo("2026-10-20");
            assertThat(moved.get("scheduledDate").asText()).isEqualTo("2026-10-16");
            assertThat(moved.get("periodStart").asText()).isEqualTo("2026-10-15");
            assertThat(earlier.get("periodStart").asText()).isEqualTo("2026-10-01");
            // Counted inside the period that now pays for it.
            assertThat(earlier.get("payBy").asText()).isEqualTo("2026-10-14");
            assertThat(earlier.get("early").asBoolean()).isTrue();
            assertThat(skipped.get("skipped").asBoolean()).isTrue();
        }

        @Test
        void explainsWhatIsWrong() throws Exception {
            assertThat(edit("2026-10-16", "{\"amountCents\":-5}").error()).isEqualTo("The amount can't be less than zero.");
            assertThat(edit("2026-10-16", "{\"periodStart\":\"2026-10-02\"}").error()).isEqualTo("Please choose the pay period from the list.");
            assertThat(edit("2026-10-16", "not json").status()).isEqualTo(400);
        }

        @Test
        void aDateTheScheduleDoesNotNameIsNotFound() throws Exception {
            assertThat(edit("2026-10-17", "{\"paid\":true}").status()).isEqualTo(404);
            assertThat(edit("1999-10-16", "{\"paid\":true}").status()).isEqualTo(404);
            assertThat(server.put("/api/items/999/occurrences/2026-10-16", "{\"paid\":true}", cookie).status()).isEqualTo(404);
            assertThat(edit("someday", "{\"paid\":true}").status()).isEqualTo(400);
        }
    }
}
