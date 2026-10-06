package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** Holidays over real HTTP against a temporary database. "Today" is 2026-10-01. */
class HolidaysApiTest extends ApiTestBase {

    private static final String OWN_HOLIDAY = "{\"date\":\"2026-11-27\",\"name\":\"Company day off\"}";

    @Test
    void everyHolidayRouteRequiresASession() throws Exception {
        assertThat(server.get("/api/holidays", null).status()).isEqualTo(401);
        assertThat(server.post("/api/holidays", OWN_HOLIDAY, null).status()).isEqualTo(401);
        assertThat(server.put("/api/holidays/1", OWN_HOLIDAY, null).status()).isEqualTo(401);
        assertThat(server.delete("/api/holidays/1", null).status()).isEqualTo(401);
    }

    @Test
    void startsWithUsBankHolidaysFromTodayThroughNextYear() throws Exception {
        Reply reply = server.get("/api/holidays", cookie);

        assertThat(reply.status()).isEqualTo(200);
        // Four left in 2026 after Oct 1, and all eleven of 2027.
        assertThat(reply.data()).hasSize(15);
        JsonNode first = reply.data().get(0);
        assertThat(first.get("date").asText()).isEqualTo("2026-10-12");
        assertThat(first.get("name").asText()).isEqualTo("Indigenous Peoples' Day");
        assertThat(first.get("source").asText()).isEqualTo("us_bank");
        assertThat(first.get("enabled").asBoolean()).isTrue();
    }

    @Test
    void aRangeCanBeAskedFor() throws Exception {
        Reply reply = server.get("/api/holidays?from=2028-01-01&to=2028-12-31", cookie);

        assertThat(reply.data()).hasSize(11);
        assertThat(reply.data().get(0).get("date").asText()).isEqualTo("2028-01-01");
    }

    @Test
    void refusesARangeThatMakesNoSense() throws Exception {
        assertThat(server.get("/api/holidays?from=2027-01-01&to=2026-01-01", cookie).status()).isEqualTo(400);
        assertThat(server.get("/api/holidays?from=1900-01-01&to=1900-12-31", cookie).status()).isEqualTo(400);
        assertThat(server.get("/api/holidays?from=2026-01-01&to=2090-01-01", cookie).status()).isEqualTo(400);
        assertThat(server.get("/api/holidays?from=tomorrow", cookie).status()).isEqualTo(400);
    }

    @Test
    void oneCanBeSwitchedOffAndStaysOff() throws Exception {
        long id = server.get("/api/holidays", cookie).data().get(0).get("id").asLong();

        Reply reply = server.put("/api/holidays/" + id,
                "{\"date\":\"2026-10-12\",\"name\":\"Indigenous Peoples' Day\",\"enabled\":false}", cookie);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(server.get("/api/holidays", cookie).data().get(0).get("enabled").asBoolean()).isFalse();
    }

    @Test
    void oneThatWasRemovedIsNotPutBack() throws Exception {
        long id = server.get("/api/holidays", cookie).data().get(0).get("id").asLong();

        assertThat(server.delete("/api/holidays/" + id, cookie).status()).isEqualTo(200);

        JsonNode after = server.get("/api/holidays", cookie).data();
        assertThat(after).hasSize(14);
        assertThat(after.findValuesAsText("date")).doesNotContain("2026-10-12");
    }

    @Test
    void theUserCanAddTheirOwn() throws Exception {
        Reply created = server.post("/api/holidays", OWN_HOLIDAY, cookie);

        assertThat(created.status()).isEqualTo(201);
        JsonNode own = server.get("/api/holidays?from=2026-11-27&to=2026-11-27", cookie).data().get(0);
        assertThat(own.get("name").asText()).isEqualTo("Company day off");
        assertThat(own.get("source").asText()).isEqualTo("user");
    }

    @Test
    void twoHolidaysCannotShareADate() throws Exception {
        server.get("/api/holidays", cookie);
        server.post("/api/holidays", OWN_HOLIDAY, cookie);

        Reply duplicate = server.post("/api/holidays", OWN_HOLIDAY, cookie);
        Reply ontoBankHoliday = server.post("/api/holidays", "{\"date\":\"2026-11-26\",\"name\":\"Mine\"}", cookie);

        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.error()).isEqualTo("There's already a holiday on that date.");
        assertThat(ontoBankHoliday.status()).isEqualTo(409);
    }

    @Test
    void aHolidayOfTheUsersOwnIsKeptWhenBankHolidaysForThatYearArrive() throws Exception {
        // Added before 2026's bank holidays were ever listed, on Thanksgiving itself.
        server.post("/api/holidays", "{\"date\":\"2026-11-26\",\"name\":\"Friendsgiving\"}", cookie);

        JsonNode day = server.get("/api/holidays?from=2026-11-26&to=2026-11-26", cookie).data();

        assertThat(day).hasSize(1);
        assertThat(day.get(0).get("name").asText()).isEqualTo("Friendsgiving");
    }

    @Test
    void needsADateAndAName() throws Exception {
        assertThat(server.post("/api/holidays", "{\"name\":\"No date\"}", cookie).status()).isEqualTo(400);
        assertThat(server.post("/api/holidays", "{\"date\":\"2026-11-27\"}", cookie).status()).isEqualTo(400);
        assertThat(server.post("/api/holidays", "{\"date\":\"1850-01-01\",\"name\":\"Long ago\"}", cookie).status()).isEqualTo(400);
        assertThat(server.post("/api/holidays", "nope", cookie).status()).isEqualTo(400);
    }

    @Test
    void oneCanBeMovedButNotOntoAnother() throws Exception {
        JsonNode holidays = server.get("/api/holidays", cookie).data();
        long id = holidays.get(0).get("id").asLong();

        Reply moved = server.put("/api/holidays/" + id, "{\"date\":\"2026-10-13\",\"name\":\"Moved\"}", cookie);
        Reply clash = server.put("/api/holidays/" + id, "{\"date\":\"2026-11-11\",\"name\":\"Moved\"}", cookie);

        assertThat(moved.status()).isEqualTo(200);
        assertThat(clash.status()).isEqualTo(409);
        JsonNode first = server.get("/api/holidays", cookie).data().get(0);
        assertThat(first.get("date").asText()).isEqualTo("2026-10-13");
        assertThat(first.get("enabled").asBoolean()).isTrue();
    }

    @Test
    void aHolidayThatDoesNotExistIsNotFound() throws Exception {
        assertThat(server.put("/api/holidays/999", OWN_HOLIDAY, cookie).status()).isEqualTo(404);
        assertThat(server.delete("/api/holidays/999", cookie).status()).isEqualTo(404);
        assertThat(server.delete("/api/holidays/abc", cookie).status()).isEqualTo(404);
    }
}
