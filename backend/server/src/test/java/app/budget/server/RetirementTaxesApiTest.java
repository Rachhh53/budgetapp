package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import app.budget.server.TestServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Taxes and Medicare in retirement worked out by the rules (spec §5.12; Rachel, 2026-10-23). "Today" is 2026-10-01;
 * born Oct 1, 1960, so 66 today, retiring at 67. A traditional IRA holds $800,000 and a brokerage account $200,000.
 */
class RetirementTaxesApiTest extends ApiTestBase {

    long checking;
    long ira;
    long brokerage;

    @BeforeEach
    void accounts() throws Exception {
        checking = createAccount("Everyday Checking", "checking");
        createSalary(checking);
        ira = createAccount("Traditional IRA", "investment");
        brokerage = createAccount("Brokerage", "investment");
        enterBalance(ira, "2026-10-01", 80_000_000);
        enterBalance(brokerage, "2026-10-01", 20_000_000);
        assertThat(server.put("/api/retirement/birth-date", "{\"birthDate\":\"1960-10-01\"}", cookie).status()).isEqualTo(200);
        server.put("/api/retirement/accounts/" + ira, "{\"taxType\":\"traditional\"}", cookie);
        server.put("/api/retirement/accounts/" + brokerage, "{\"taxType\":\"taxable\"}", cookie);
    }

    /** $60,000 a year from 67, Social Security of $30,000 from 67, a pension of $6,000 that isn't taxed. */
    private static String scenario(String taxes) {
        return """
                {"name":"Base","salaryCents":6000000,"inflationBp":300,"withdrawalBp":400,"taxBp":1500,"healthInflationBp":500,
                 "growthBp":600,"growthLowBp":400,"growthHighBp":800,"extraInto":"taxable","retireAge":67,"taxes":%s,
                 "incomes":[{"name":"Social Security","yearlyCents":3000000,"startAge":67,"socialSecurity":true},
                            {"name":"Disability pension","yearlyCents":600000,"startAge":67,"countedBp":0}]}""".formatted(taxes);
    }

    private static final String WORKED_OUT = """
            {"mode":"worked_out","filing":"single","medicarePeople":1,"stateTaxBp":400,"gainsShareBp":5000,
             "partDPlanCents":4000,"supplementCents":15000}""";

    private JsonNode onlyScenario() throws Exception {
        return server.get("/api/retirement", cookie).data().get("scenarios").get(0);
    }

    @Test
    void nothingWorksWithoutASession() throws Exception {
        assertThat(server.put("/api/retirement/spouse-birth-date", "{\"spouseBirthDate\":\"1962-01-01\"}", null).status()).isEqualTo(401);
        assertThat(server.put("/api/retirement/tax-figures", "{}", null).status()).isEqualTo(401);
        assertThat(server.send("DELETE", "/api/retirement/tax-figures", null, Map.of()).status()).isEqualTo(401);
    }

    @Test
    void aFlatScenarioIsWorkedOutAsBefore() throws Exception {
        created("/api/retirement/scenarios", scenario("null"));

        JsonNode scenario = onlyScenario();
        assertThat(scenario.get("taxSettings").get("mode").asText()).isEqualTo("flat");
        assertThat(scenario.get("taxes").isNull()).isTrue();
        assertThat(scenario.get("incomes").get(0).get("socialSecurity").asBoolean()).isTrue();
        assertThat(scenario.get("incomes").get(1).get("countedBp").asInt()).isZero();
    }

    @Test
    void workedOutItTriesEveryStrategyAndUsesTheCheapest() throws Exception {
        created("/api/retirement/scenarios", scenario(WORKED_OUT));

        JsonNode taxes = onlyScenario().get("taxes");
        assertThat(taxes.get("retireAge").asInt()).isEqualTo(67);
        assertThat(taxes.get("ageFrom").asText()).isEqualTo("chosen");
        assertThat(taxes.get("strategies")).hasSize(12);
        assertThat(taxes.get("chosen").asText()).isEqualTo(taxes.get("cheapest").asText());
        assertThat(taxes.get("pinned").asBoolean()).isFalse();
        // Best first: nothing after it leaves more without running out sooner.
        JsonNode best = taxes.get("strategies").get(0);
        assertThat(best.get("code").asText()).isEqualTo(taxes.get("chosen").asText());
        assertThat(best.get("label").asText()).isNotBlank();
        JsonNode first = taxes.get("years").get(0);
        assertThat(first.get("age").asInt()).isEqualTo(67);
        assertThat(first.get("calendarYear").asInt()).isEqualTo(2027);
        // Medicare from 65: Part B, the $40 plan and the $150 Medigap, a month.
        assertThat(first.get("medicare")).hasSize(1);
        assertThat(first.get("medicare").get(0).get("plansCents").asLong()).isPositive();
        assertThat(taxes.get("medicareCents").asLong()).isGreaterThanOrEqualTo((20_290L + 4_000 + 15_000) * 12);
        // The pay Budget knows stands in for income while working.
        assertThat(taxes.get("workingMagiCents").asLong()).isEqualTo(server.get("/api/retirement", cookie).data().get("payAYearCents").asLong());
        assertThat(taxes.get("taxShareBp").asInt()).isBetween(0, 6000);
    }

    @Test
    void aStrategyCanBeChosenInPlaceOfTheCheapest() throws Exception {
        created("/api/retirement/scenarios", scenario(WORKED_OUT.replace("}", ",\"strategy\":\"none\"}")));

        JsonNode taxes = onlyScenario().get("taxes");
        assertThat(taxes.get("chosen").asText()).isEqualTo("none");
        assertThat(taxes.get("pinned").asBoolean()).isEqualTo(!taxes.get("cheapest").asText().equals("none"));
    }

    @Test
    void settingsThatCannotBeRightAreRefused() throws Exception {
        assertThat(server.post("/api/retirement/scenarios", scenario(WORKED_OUT.replace("\"single\"", "\"head\"")), cookie).status())
                .isEqualTo(400);
        assertThat(server.post("/api/retirement/scenarios", scenario(WORKED_OUT.replace("}", ",\"strategy\":\"bracket:x\"}")), cookie).status())
                .isEqualTo(400);
        assertThat(server.post("/api/retirement/scenarios", scenario(WORKED_OUT.replace("\"medicarePeople\":1", "\"medicarePeople\":3")), cookie)
                .status()).isEqualTo(400);
        assertThat(server.post("/api/retirement/scenarios", scenario(WORKED_OUT).replace("\"countedBp\":0", "\"countedBp\":10001"), cookie)
                .status()).isEqualTo(400);
    }

    @Test
    void aSpouseBirthDateIsKeptWithYours() throws Exception {
        assertThat(server.put("/api/retirement/spouse-birth-date", "{\"spouseBirthDate\":\"1962-03-01\"}", cookie).status()).isEqualTo(200);
        assertThat(server.get("/api/retirement", cookie).data().get("spouseBirthDate").asText()).isEqualTo("1962-03-01");
        assertThat(server.put("/api/retirement/spouse-birth-date", "{\"spouseBirthDate\":\"2020-01-01\"}", cookie).status()).isEqualTo(400);

        assertThat(server.put("/api/retirement/spouse-birth-date", "{\"spouseBirthDate\":null}", cookie).status()).isEqualTo(200);
        assertThat(server.get("/api/retirement", cookie).data().get("spouseBirthDate").isNull()).isTrue();
    }

    @Test
    void theFiguresCanBeChangedAndPutBack() throws Exception {
        JsonNode figures = server.get("/api/retirement", cookie).data().get("figures");
        assertThat(server.get("/api/retirement", cookie).data().get("builtInFigures").asBoolean()).isTrue();
        assertThat(figures.get("partBCents").get(0).asLong()).isEqualTo(20_290);

        String changed = figures.toString().replace("[20290,", "[21000,");
        assertThat(server.put("/api/retirement/tax-figures", changed, cookie).status()).isEqualTo(200);
        JsonNode view = server.get("/api/retirement", cookie).data();
        assertThat(view.get("builtInFigures").asBoolean()).isFalse();
        assertThat(view.get("figures").get("partBCents").get(0).asLong()).isEqualTo(21_000);

        // Brackets out of order, or a Medicare bracket without its premium, are refused and change nothing.
        String unordered = figures.toString().replace("[1240000,5040000,", "[5040000,1240000,");
        assertThat(server.put("/api/retirement/tax-figures", unordered, cookie).status()).isEqualTo(400);
        String short_ = figures.toString().replace("[20290,", "[");
        assertThat(server.put("/api/retirement/tax-figures", short_, cookie).status()).isEqualTo(400);
        assertThat(server.get("/api/retirement", cookie).data().get("figures").get("partBCents").get(0).asLong()).isEqualTo(21_000);

        assertThat(server.send("DELETE", "/api/retirement/tax-figures", null, Map.of("Cookie", cookie)).status()).isEqualTo(200);
        assertThat(server.get("/api/retirement", cookie).data().get("builtInFigures").asBoolean()).isTrue();
    }
}
