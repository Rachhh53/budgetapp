package app.budget.server.retirement;

import app.budget.engine.retirement.RetirementPlanner.TaxType;
import app.budget.server.accounts.Account;
import app.budget.server.accounts.AccountsRepository;
import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.income.PayCalendar;
import app.budget.server.retirement.RetirementRepository.Fields;
import app.budget.server.retirement.RetirementRepository.Income;
import app.budget.server.retirement.RetirementRepository.OneOff;
import app.budget.server.retirement.RetirementRepository.Taxes;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Retirement planning (spec §4.12, §5.11, §5.12). {@code GET /api/retirement} is the accounts and every
 * scenario worked out; {@code PUT /api/retirement/birth-date}, {@code /spouse-birth-date},
 * {@code /accounts/{id}} and {@code PUT}/{@code DELETE /tax-figures} set what all scenarios share;
 * {@code POST}, {@code PUT} and {@code DELETE /api/retirement/scenarios[/{id}]} keep the scenarios.
 */
public final class RetirementRoutes {

    public static final int MAX_SCENARIOS = 20;
    public static final int MAX_COMPARED = 3;
    public static final int MAX_INCOMES = 10;
    public static final int MAX_ONE_OFFS = 20;
    public static final int NAME_MAX = 80;
    public static final int YOUNGEST = 18;
    public static final int OLDEST = 100;

    /** The defaults a scenario starts with when one is left out (§4.12). */
    static final int INFLATION_BP = 300;
    static final int WITHDRAWAL_BP = 400;
    static final int TAX_BP = 1500;
    static final int HEALTH_INFLATION_BP = 500;
    /** Expected growth, with a slower and a faster one beside it (Rachel, 2026-10-20: "6%, 4% and 8%"). */
    static final int GROWTH_BP = 600;
    static final int GROWTH_LOW_BP = 400;
    static final int GROWTH_HIGH_BP = 800;
    static final int MOST_GROWTH_BP = 10_000;
    static final int MOST_RAISE_BP = 2000;
    /** Half of each dollar taken from a taxable account is growth, to start with. */
    static final int GAINS_SHARE_BP = 5000;
    static final int MOST_STATE_TAX_BP = 2000;

    private final RetirementService service;
    private final RetirementRepository retirement;
    private final AccountsRepository accounts;
    private final PayCalendar pay;

    public RetirementRoutes(RetirementService service, RetirementRepository retirement, AccountsRepository accounts, PayCalendar pay) {
        this.service = service;
        this.retirement = retirement;
        this.accounts = accounts;
        this.pay = pay;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/retirement", ctx -> ApiResponse.ok(ctx, service.view()));
        routes.put("/api/retirement/birth-date", this::setBirthDate);
        routes.put("/api/retirement/spouse-birth-date", this::setSpouseBirthDate);
        routes.put("/api/retirement/tax-figures", this::setFigures);
        routes.delete("/api/retirement/tax-figures", this::resetFigures);
        routes.put("/api/retirement/accounts/{id}", this::setTaxType);
        routes.post("/api/retirement/scenarios", this::create);
        routes.put("/api/retirement/scenarios/{id}", this::update);
        routes.delete("/api/retirement/scenarios/{id}", this::delete);
    }

    public record BirthDateInput(LocalDate birthDate) {}

    /** @param taxType {@code traditional}, {@code roth} or {@code taxable}; {@code null} to stop counting it */
    public record TaxTypeInput(String taxType) {}

    /** @param spouseBirthDate {@code null} for no spouse */
    public record SpouseInput(LocalDate spouseBirthDate) {}

    /**
     * @param socialSecurity true for Social Security, taxed by its own formula
     * @param countedBp      for anything else, how much is taxed income; left out, all of it
     */
    public record IncomeInput(String name, Long yearlyCents, Integer startAge, Boolean socialSecurity, Integer countedBp) {}

    public record OneOffInput(String name, Long amountCents, Integer age) {}

    /** A scenario. Rates are basis points (3% is 300); amounts cents in today's dollars. Left out, a rate takes its default. */
    public record ScenarioInput(
            String name,
            Long salaryCents,
            Integer inflationBp,
            Integer withdrawalBp,
            Integer taxBp,
            Long healthBeforeCents,
            Long healthAfterCents,
            Integer healthInflationBp,
            Integer growthBp,
            Integer growthLowBp,
            Integer growthHighBp,
            Integer raiseBp,
            Long extraMonthlyCents,
            String extraInto,
            Integer retireAge,
            Boolean compared,
            TaxesInput taxes,
            List<IncomeInput> incomes,
            List<OneOffInput> oneOffs) {}

    /**
     * How a scenario taxes retirement; left out, the flat rate as before.
     *
     * @param mode    {@code flat} or {@code worked_out}
     * @param filing  {@code single} or {@code joint}
     * @param strategy {@code none}, {@code bracket:N} or {@code medicare:N}; left out for the cheapest
     */
    public record TaxesInput(
            String mode,
            String filing,
            Integer medicarePeople,
            Integer stateTaxBp,
            Integer gainsShareBp,
            Long partDPlanCents,
            Long supplementCents,
            Long workingMagiCents,
            Long magiOverrideCents,
            String strategy) {}

    public record Created(long id) {}

    public record Done(boolean done) {}

    private void setBirthDate(Context ctx) {
        BirthDateInput input = Inputs.body(ctx, BirthDateInput.class);
        LocalDate latest = pay.today().minusYears(YOUNGEST);
        if (input.birthDate() == null || input.birthDate().isAfter(latest) || input.birthDate().isBefore(pay.today().minusYears(OLDEST))) {
            throw ApiException.badRequest("Please enter a birth date between " + OLDEST + " and " + YOUNGEST + " years ago.");
        }
        retirement.setBirthDate(input.birthDate());
        ApiResponse.ok(ctx, new Done(true));
    }

    private void setSpouseBirthDate(Context ctx) {
        SpouseInput input = Inputs.body(ctx, SpouseInput.class);
        LocalDate date = input.spouseBirthDate();
        if (date != null && (date.isAfter(pay.today().minusYears(YOUNGEST)) || date.isBefore(pay.today().minusYears(OLDEST)))) {
            throw ApiException.badRequest("Please enter a spouse's birth date between " + OLDEST + " and " + YOUNGEST + " years ago.");
        }
        if (!retirement.setSpouseBirthDate(date)) {
            throw ApiException.conflict("Please enter your own birth date first.");
        }
        ApiResponse.ok(ctx, new Done(true));
    }

    private void setFigures(Context ctx) {
        service.setFigures(Inputs.body(ctx, FiguresView.class));
        ApiResponse.ok(ctx, new Done(true));
    }

    /** Back to the built-in figures. */
    private void resetFigures(Context ctx) {
        service.setFigures(null);
        ApiResponse.ok(ctx, new Done(true));
    }

    private void setTaxType(Context ctx) {
        Account account = accounts.find(Inputs.id(ctx, "id")).orElseThrow(ApiException::notFound);
        if (!RetirementService.countable(account)) {
            throw ApiException.badRequest("Only investment and savings accounts can be counted for retirement.");
        }
        TaxTypeInput input = Inputs.body(ctx, TaxTypeInput.class);
        String taxType = input.taxType() == null ? null : Inputs.code(Inputs.choice(TaxType.class, input.taxType(), "how its money is taxed"));
        retirement.setTaxType(account.id(), taxType);
        ApiResponse.ok(ctx, new Done(true));
    }

    private void create(Context ctx) {
        if (retirement.count() >= MAX_SCENARIOS) {
            throw ApiException.conflict("You have " + MAX_SCENARIOS + " scenarios, the most Budget keeps. Please delete one you no longer need first.");
        }
        Fields fields = fields(Inputs.body(ctx, ScenarioInput.class), null);
        ApiResponse.send(ctx, 201, new Created(retirement.create(fields)));
    }

    private void update(Context ctx) {
        long id = Inputs.id(ctx, "id");
        retirement.find(id).orElseThrow(ApiException::notFound);
        Fields fields = fields(Inputs.body(ctx, ScenarioInput.class), id);
        retirement.update(id, fields);
        ApiResponse.ok(ctx, new Done(true));
    }

    private void delete(Context ctx) {
        if (!retirement.delete(Inputs.id(ctx, "id"))) {
            throw ApiException.notFound();
        }
        ApiResponse.ok(ctx, new Done(true));
    }

    /** @param id the scenario being changed; {@code null} for a new one */
    private Fields fields(ScenarioInput input, Long id) {
        String name = Inputs.text(input.name(), "a name for this scenario", NAME_MAX);
        long salary = Inputs.amount(input.salaryCents(), "The salary in retirement");
        if (salary == 0) {
            throw ApiException.badRequest("Please enter the salary you'd like a year in retirement, more than $0.00.");
        }
        int inflation = rate(input.inflationBp(), INFLATION_BP, 0, 2000, "Please enter inflation between 0% and 20% a year.");
        int withdrawal = rate(input.withdrawalBp(), WITHDRAWAL_BP, 100, 1000, "Please enter a withdrawal rate between 1% and 10% a year.");
        int tax = rate(input.taxBp(), TAX_BP, 0, 6000, "Please enter a tax rate in retirement between 0% and 60%.");
        int healthInflation = rate(input.healthInflationBp(), HEALTH_INFLATION_BP, 0, 2000,
                "Please enter how fast health costs rise between 0% and 20% a year.");
        int growth = rate(input.growthBp(), GROWTH_BP, 0, MOST_GROWTH_BP, "Please enter the expected growth between 0% and 100% a year.");
        int growthLow = rate(input.growthLowBp(), GROWTH_LOW_BP, 0, MOST_GROWTH_BP, "Please enter the lower growth between 0% and 100% a year.");
        int growthHigh = rate(input.growthHighBp(), GROWTH_HIGH_BP, 0, MOST_GROWTH_BP,
                "Please enter the higher growth between 0% and 100% a year.");
        if (growthLow > growth) {
            throw ApiException.badRequest("Please enter a lower growth at or below the expected " + percent(growth) + ".");
        }
        if (growthHigh < growth) {
            throw ApiException.badRequest("Please enter a higher growth at or above the expected " + percent(growth) + ".");
        }
        int raise = rate(input.raiseBp(), 0, 0, MOST_RAISE_BP, "Please enter a raise between 0% and 20% a year.");
        long healthBefore = input.healthBeforeCents() == null ? 0 : Inputs.amount(input.healthBeforeCents(), "Health insurance before 65");
        long healthAfter = input.healthAfterCents() == null ? 0 : Inputs.amount(input.healthAfterCents(), "Health costs from 65");
        long extra = input.extraMonthlyCents() == null ? 0 : Inputs.amount(input.extraMonthlyCents(), "The extra saved a month");
        String extraInto = Inputs.code(input.extraInto() == null
                ? TaxType.TRADITIONAL
                : Inputs.choice(TaxType.class, input.extraInto(), "where the extra goes"));
        Integer retireAge = input.retireAge() == null ? null : age(input.retireAge(), "Please enter an age to retire between 18 and 100.");
        boolean compared = Boolean.TRUE.equals(input.compared());
        if (compared && retirement.comparedCount(id) >= MAX_COMPARED) {
            throw ApiException.conflict("Three scenarios are already side by side. Please take one out of the comparison first.");
        }
        return new Fields(name, salary, inflation, withdrawal, tax, healthBefore, healthAfter, healthInflation, growth, growthLow, growthHigh,
                raise, extra, extraInto, retireAge, compared, taxes(input.taxes()), incomes(input.incomes()), oneOffs(input.oneOffs()));
    }

    private static Taxes taxes(TaxesInput input) {
        if (input == null) {
            return Taxes.FLAT;
        }
        String mode = input.mode() == null ? "flat" : input.mode();
        if (!mode.equals("flat") && !mode.equals("worked_out")) {
            throw ApiException.badRequest("Please choose a flat tax rate, or to work taxes out by the rules.");
        }
        String filing = input.filing() == null ? "single" : input.filing();
        if (!filing.equals("single") && !filing.equals("joint")) {
            throw ApiException.badRequest("Please choose filing alone or married filing jointly.");
        }
        int people = input.medicarePeople() == null ? 1 : input.medicarePeople();
        if (people != 1 && people != 2) {
            throw ApiException.badRequest("Please say whether Medicare is for one person or two.");
        }
        int state = rate(input.stateTaxBp(), 0, 0, MOST_STATE_TAX_BP, "Please enter a state income tax between 0% and 20%.");
        int gains = rate(input.gainsShareBp(), GAINS_SHARE_BP, 0, 10_000, "Please enter how much of taxable withdrawals is growth, 0% to 100%.");
        long partD = input.partDPlanCents() == null ? 0 : Inputs.amount(input.partDPlanCents(), "A Part D plan a month");
        long supplement = input.supplementCents() == null ? 0 : Inputs.amount(input.supplementCents(), "Medigap or Advantage a month");
        Long working = input.workingMagiCents() == null ? null : Inputs.amount(input.workingMagiCents(), "Income while working");
        Long override = input.magiOverrideCents() == null ? null : Inputs.amount(input.magiOverrideCents(), "Income in retirement");
        String strategy = input.strategy();
        if (strategy != null && RetirementService.strategy(strategy) == null) {
            throw ApiException.badRequest("Please choose one of the ways to take money out, or the cheapest.");
        }
        return new Taxes(mode, filing, people, state, gains, partD, supplement, working, override, strategy);
    }

    /** Basis points as a percentage with two places, the way the form shows them: 600 is "6.00%". */
    private static String percent(int bp) {
        return String.format(Locale.ROOT, "%d.%02d%%", bp / 100, bp % 100);
    }

    private static List<Income> incomes(List<IncomeInput> inputs) {
        if (inputs == null) {
            return List.of();
        }
        if (inputs.size() > MAX_INCOMES) {
            throw ApiException.badRequest("A scenario can have up to " + MAX_INCOMES + " kinds of other income.");
        }
        List<Income> incomes = new ArrayList<>();
        for (IncomeInput input : inputs) {
            if (input == null) {
                throw ApiException.badRequest("Please fill in each kind of other income, or remove it.");
            }
            String name = Inputs.text(input.name(), "a name for each kind of other income", NAME_MAX);
            long yearly = Inputs.amount(input.yearlyCents(), "The yearly amount of " + name);
            if (yearly == 0) {
                throw ApiException.badRequest("Please enter what " + name + " brings a year, more than $0.00.");
            }
            int startAge = age(input.startAge(), "Please enter the age " + name + " starts, between 18 and 100.");
            boolean socialSecurity = Boolean.TRUE.equals(input.socialSecurity());
            int counted = rate(input.countedBp(), 10_000, 0, 10_000, "Please enter how much of " + name + " is taxed income, 0% to 100%.");
            incomes.add(new Income(name, yearly, startAge, socialSecurity, counted));
        }
        return incomes;
    }

    private static List<OneOff> oneOffs(List<OneOffInput> inputs) {
        if (inputs == null) {
            return List.of();
        }
        if (inputs.size() > MAX_ONE_OFFS) {
            throw ApiException.badRequest("A scenario can have up to " + MAX_ONE_OFFS + " one-off amounts.");
        }
        List<OneOff> oneOffs = new ArrayList<>();
        for (OneOffInput input : inputs) {
            if (input == null) {
                throw ApiException.badRequest("Please fill in each one-off amount, or remove it.");
            }
            String name = Inputs.text(input.name(), "a name for each one-off amount", NAME_MAX);
            long amount = Inputs.signedAmount(input.amountCents(), "The amount of " + name);
            if (amount == 0) {
                throw ApiException.badRequest("Please enter an amount for " + name + ": money in, or money out with a minus sign.");
            }
            int age = age(input.age(), "Please enter the age for " + name + ", between 18 and 100.");
            oneOffs.add(new OneOff(name, amount, age));
        }
        return oneOffs;
    }

    private static int rate(Integer value, int fallback, int least, int most, String message) {
        int rate = value == null ? fallback : value;
        if (rate < least || rate > most) {
            throw ApiException.badRequest(message);
        }
        return rate;
    }

    private static int age(Integer value, String message) {
        if (value == null || value < YOUNGEST || value > OLDEST) {
            throw ApiException.badRequest(message);
        }
        return value;
    }
}
