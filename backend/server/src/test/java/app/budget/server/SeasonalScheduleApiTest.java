package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Seasonal schedules (spec §4.6; Rachel, 2026-10-19: snowplowing "several times per week in the winter
 * and not at all in the summer"). "Today" is 2026-10-01; pay periods are the 1st–14th and the 15th–end.
 */
class SeasonalScheduleApiTest extends ApiTestBase {

    private static final String WINTER = "[11,12,1,2,3]";

    long checking;

    @BeforeEach
    void addChecking() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        createSalary(checking);
    }

    /** $900 of plowing a winter, Mondays, entered as a yearly total. */
    private String plowing(String months) {
        return """
                {"name":"Snowplowing","kind":"bill","amountCents":90000,"amountMode":"annual","payFromAccountId":%d,
                 "schedule":{"frequency":"weekly","startDate":"2026-01-05","weekendRule":"none","months":%s}}"""
                .formatted(checking, months);
    }

    private List<String> dueIn(String periodStart, String name) throws Exception {
        List<String> due = new ArrayList<>();
        for (JsonNode occurrence : server.get("/api/pay-periods/" + periodStart, cookie).data().get("occurrences")) {
            if (occurrence.get("name").asText().equals(name)) {
                due.add(occurrence.get("dueDate").asText());
            }
        }
        return due;
    }

    @Test
    void addingOneRequiresASession() throws Exception {
        assertThat(server.post("/api/items", plowing(WINTER), null).status()).isEqualTo(401);
    }

    @Test
    void nothingHappensOutsideItsMonthsAndAYearlyTotalIsSharedOverItsOwnDates() throws Exception {
        long id = created("/api/items", plowing(WINTER));

        JsonNode item = server.get("/api/items/" + id, cookie).data();
        assertThat(item.get("schedule").get("months").toString()).isEqualTo("[1,2,3,11,12]");
        // 52 × 5 / 12 ≈ 21.67 Mondays a winter.
        assertThat(item.get("perOccurrenceCents").asLong()).isEqualTo(4154);
        assertThat(item.get("perYearCents").asLong()).isEqualTo(90000);
        assertThat(item.get("next").get("dueDate").asText()).isEqualTo("2026-11-02");

        assertThat(dueIn("2026-10-01", "Snowplowing")).isEmpty();
        assertThat(dueIn("2026-10-15", "Snowplowing")).isEmpty();
        assertThat(dueIn("2026-11-01", "Snowplowing")).containsExactly("2026-11-02", "2026-11-09");
    }

    @Test
    void anEveryPaycheckItemFollowsThePaychecksMonth() throws Exception {
        long id = created("/api/items", """
                {"name":"Winter fund","kind":"bill","amountCents":5000,"payFromAccountId":%d,
                 "schedule":{"frequency":"every_pay_period","startDate":"2026-01-01","weekendRule":"none","months":%s}}"""
                .formatted(checking, WINTER));

        assertThat(dueIn("2026-10-15", "Winter fund")).isEmpty();
        assertThat(dueIn("2026-11-01", "Winter fund")).hasSize(1);
        assertThat(server.get("/api/items/" + id, cookie).data().get("perYearCents").asLong()).isEqualTo(50000);
    }

    @Test
    void everyMonthAgainClearsIt() throws Exception {
        long id = created("/api/items", plowing(WINTER));

        Reply cleared = server.put("/api/items/" + id, plowing("[]"), cookie);

        assertThat(cleared.status()).isEqualTo(200);
        assertThat(cleared.data().get("schedule").get("months")).isEmpty();
        assertThat(dueIn("2026-10-15", "Snowplowing")).containsExactly("2026-10-19", "2026-10-26");
        // All twelve chosen is the same as every month.
        assertThat(server.put("/api/items/" + id, plowing("[1,2,3,4,5,6,7,8,9,10,11,12]"), cookie).data()
                .get("schedule").get("months")).isEmpty();
    }

    @Test
    void refusesMonthsThatCannotBeRight() throws Exception {
        Reply nonsense = server.post("/api/items", plowing("[0,13]"), cookie);
        assertThat(nonsense.status()).isEqualTo(400);
        assertThat(nonsense.error()).isEqualTo("Please choose the months from the list.");

        Reply once = server.post("/api/items", """
                {"name":"Gutter cleaning","kind":"bill","amountCents":20000,"payFromAccountId":%d,
                 "schedule":{"frequency":"one_time","startDate":"2026-11-10","months":[11]}}""".formatted(checking), cookie);
        assertThat(once.status()).isEqualTo(400);
        assertThat(once.error()).isEqualTo("Something that happens once can't be limited to some months. Please choose every month.");

        Reply yearly = server.post("/api/items", """
                {"name":"Snow tires","kind":"bill","amountCents":60000,"payFromAccountId":%d,
                 "schedule":{"frequency":"yearly","month":4,"day1":15,"startDate":"2026-01-01","months":[11,12]}}""".formatted(checking), cookie);
        assertThat(yearly.status()).isEqualTo(400);
        assertThat(yearly.error()).isEqualTo("Something yearly has to run in the month it falls in. Please include April.");
    }
}
