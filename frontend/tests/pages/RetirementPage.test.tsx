import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { AgePoint, RetirementAccount, RetirementScenario, RetirementView } from "../../src/api/retirement";
import { SCENARIO_DEFAULTS } from "../../src/api/retirement";
import { bodiesSentTo, fakeBackend, renderApp } from "../helpers";

const IRA: RetirementAccount = {
  id: 7,
  name: "Fidelity IRA",
  color: "#7c3aed",
  type: "investment",
  subtypeLabel: "IRA",
  taxType: "traditional",
  balanceCents: 8_500_000,
  yearlyInCents: 650_000,
};

const SAVINGS: RetirementAccount = {
  id: 9,
  name: "High-yield savings",
  color: "#0f766e",
  type: "savings",
  subtypeLabel: null,
  taxType: null,
  balanceCents: 1_200_000,
  yearlyInCents: 0,
};

/** Ages 40 to 100: projected climbs past needed at 58. */
const BY_AGE: AgePoint[] = Array.from({ length: 61 }, (_, at) => {
  const age = 40 + at;
  const projectedCents = at * 4_000_000;
  return {
    age,
    projectedCents,
    projectedLowCents: at * 3_000_000,
    projectedHighCents: at * 5_000_000,
    projectedThenCents: at * 6_000_000,
    neededCents: 72_000_000,
    enough: age >= 58,
  };
});

function scenario(fields: Partial<RetirementScenario> & Pick<RetirementScenario, "id" | "name">): RetirementScenario {
  return {
    ...SCENARIO_DEFAULTS,
    salaryCents: 6_000_000,
    outcome: { earliestAge: 58, earliestLowAge: 62, earliestHighAge: 55, ageNow: 40, byAge: BY_AGE, target: null },
    ...fields,
  };
}

const AT_55 = scenario({
  id: 1,
  name: "Retire at 55",
  retireAge: 55,
  compared: true,
  incomes: [{ name: "Social Security", yearlyCents: 2_400_000, startAge: 67 }],
  outcome: {
    earliestAge: 58,
    earliestLowAge: 62,
    earliestHighAge: 55,
    ageNow: 40,
    byAge: BY_AGE,
    target: {
      age: 55,
      neededCents: 72_000_000,
      projectedCents: 60_000_000,
      enough: false,
      shortfallCents: 12_000_000,
      extraMonthlyCents: 41_250,
      extraPerPaycheckCents: 19_038,
      reachable: true,
      extraTaxable: true,
      early: { years: 5, covered: 2, neededCents: 30_000_000, taxableCents: 12_000_000 },
    },
  },
});

const AT_65 = scenario({ id: 2, name: "Retire at 65", retireAge: 65, compared: true });

const VIEW: RetirementView = {
  today: "2026-10-20",
  birthDate: "1986-03-14",
  missing: null,
  paychecksAYear: 26,
  accounts: [IRA, SAVINGS],
  scenarios: [AT_55, AT_65],
};

function withRetirement(view: RetirementView = VIEW) {
  return fakeBackend({
    "GET /api/retirement": () => ({ status: 200, data: view }),
    "PUT /api/retirement/birth-date": () => ({ status: 200, data: { done: true }, changed: true }),
    "PUT /api/retirement/accounts/9": () => ({ status: 200, data: { done: true }, changed: true }),
    "POST /api/retirement/scenarios": () => ({ status: 201, data: { id: 3 }, changed: true }),
    "PUT /api/retirement/scenarios/1": () => ({ status: 200, data: { done: true }, changed: true }),
  });
}

describe("Retirement", () => {
  it("is in the sidebar, and asks for a birth date before anything is worked out", async () => {
    const backend = withRetirement({ ...VIEW, birthDate: null, missing: "birth_date", scenarios: [] });
    renderApp("/");

    await userEvent.click((await screen.findAllByRole("link", { name: "Retirement" }))[0]!);

    expect(await screen.findByRole("heading", { level: 1, name: "When can I retire?" })).toBeInTheDocument();
    expect(screen.getByText(/In today's dollars: estimates for comparing, not advice/)).toBeInTheDocument();
    const first = screen.getByRole("region", { name: "Before Budget can work it out" });
    await userEvent.click(within(first).getByRole("button", { name: "Add birth date" }));
    const dialog = await screen.findByRole("dialog", { name: "Your birth date" });
    await userEvent.type(within(dialog).getByLabelText("Birth date"), "1986-03-14");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(bodiesSentTo(backend, "PUT /api/retirement/birth-date")).toEqual([{ birthDate: "1986-03-14" }]));
    expect(screen.getByRole("button", { name: "Add your first scenario" })).toBeInTheDocument();
  });

  it("counts an account by how its money is taxed, or stops counting it", async () => {
    const backend = withRetirement();
    renderApp("/retirement");

    const counted = await screen.findByRole("region", { name: "Accounts counted" });
    expect(counted).toHaveTextContent("Fidelity IRABalance today$85,000.00Goes in a year$6,500.00");
    expect(within(counted).getByLabelText("How Fidelity IRA is counted")).toHaveValue("traditional");
    await userEvent.selectOptions(within(counted).getByLabelText("How High-yield savings is counted"), "taxable");

    await waitFor(() => expect(bodiesSentTo(backend, "PUT /api/retirement/accounts/9")).toEqual([{ taxType: "taxable" }]));
  });

  it("shows the chosen scenario: the earliest age, what its own age takes, and the years before 60", async () => {
    withRetirement();
    renderApp("/retirement");

    expect(await screen.findByText(/^You can retire at/)).toBeInTheDocument();
    expect(screen.getByText("at 6.00% growth · you're 40")).toBeInTheDocument();
    // In the figures, and under each scenario side by side.
    expect(screen.getAllByText("62 at 4.00% · 55 at 8.00%")).toHaveLength(3);
    expect(screen.getByText(/^Projected then/).parentElement).toHaveTextContent(
      "$600,000.00$900,000.00 in that year's dollars$450,000.00 to $750,000.00 at 4.00%–8.00%",
    );
    expect(screen.getByText("For age 55").parentElement).toHaveTextContent(
      "$412.50more a month · $190.38 a paycheck · $120,000.00 short · saved as taxable money",
    );
    expect(screen.getByRole("status")).toHaveTextContent("Taxable and savings money covers 2 of the 5 years before 60.");
    expect(screen.getByRole("region", { name: "By age" })).toHaveTextContent("At 6.00% growth your savings are first enough at 58. The thin line marks 55");
    expect(screen.getByRole("region", { name: "Other income" })).toHaveTextContent("Social Securityfrom age 67$24,000.00 a year");
  });

  it("compares scenarios side by side, and each opens as the chosen one", async () => {
    withRetirement();
    renderApp("/retirement");

    const compare = await screen.findByRole("region", { name: "Compare scenarios" });
    expect(within(compare).getByRole("button", { name: /Retire at 55/ })).toHaveTextContent("$412.50 more a month for 55");
    await userEvent.click(within(compare).getByRole("button", { name: /^Retire at 65/ }));

    expect(within(compare).getByRole("button", { name: /^Retire at 65/ })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByLabelText("Scenario")).toHaveValue("2");
    expect(screen.getByText("Set Retire at under Goal to see what it takes.")).toBeInTheDocument();
  });

  it("copies a scenario to try a change without losing it", async () => {
    const backend = withRetirement();
    renderApp("/retirement");

    await userEvent.click(await screen.findByRole("button", { name: "Copy Retire at 55" }));

    await waitFor(() => expect(bodiesSentTo(backend, "POST /api/retirement/scenarios")).toHaveLength(1));
    const { outcome: _, id: __, ...asSaved } = AT_55;
    // Two are compared already, so the copy makes three.
    expect(bodiesSentTo(backend, "POST /api/retirement/scenarios")[0]).toEqual({ ...asSaved, name: "Retire at 55 (copy)", compared: true });
  });

  it("adds a scenario with the usual rates, asking only its goal", async () => {
    const backend = withRetirement();
    renderApp("/retirement");

    await userEvent.click(await screen.findByRole("button", { name: "+ New scenario" }));
    const dialog = await screen.findByRole("dialog", { name: "New scenario" });
    await userEvent.type(within(dialog).getByLabelText("Name"), "Part-time at 60");
    await userEvent.type(within(dialog).getByLabelText("Salary a year in retirement"), "45000");
    await userEvent.type(within(dialog).getByLabelText("Retire at"), "60");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add scenario" }));

    await waitFor(() =>
      expect(bodiesSentTo(backend, "POST /api/retirement/scenarios")).toEqual([
        { ...SCENARIO_DEFAULTS, name: "Part-time at 60", salaryCents: 4_500_000, retireAge: 60, compared: true },
      ]),
    );
  });

  it("adds a one-off to the scenario, money out with a minus sign, and says what to fix first", async () => {
    const backend = withRetirement();
    renderApp("/retirement");

    await userEvent.click(await screen.findByRole("button", { name: "+ Add a one-off" }));
    const dialog = await screen.findByRole("dialog", { name: "One-off amount" });
    await userEvent.type(within(dialog).getByLabelText("Name"), "New roof");
    await userEvent.type(within(dialog).getByLabelText("Amount"), "-15000");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please enter the age it happens, between 18 and 100.");

    await userEvent.type(within(dialog).getByLabelText("At age"), "62");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(bodiesSentTo(backend, "PUT /api/retirement/scenarios/1")).toHaveLength(1));
    expect(bodiesSentTo(backend, "PUT /api/retirement/scenarios/1")[0]).toMatchObject({
      name: "Retire at 55",
      incomes: AT_55.incomes,
      oneOffs: [{ name: "New roof", amountCents: -1_500_000, age: 62 }],
    });
  });

  it("removes a kind of other income, keeping everything else", async () => {
    const backend = withRetirement();
    renderApp("/retirement");

    await userEvent.click(await screen.findByRole("button", { name: /Edit Social Security/ }));
    const dialog = await screen.findByRole("dialog", { name: "Social Security" });
    expect(within(dialog).getByLabelText("Starts at age")).toHaveValue("67");
    await userEvent.click(within(dialog).getByRole("button", { name: "Remove" }));

    await waitFor(() => expect(bodiesSentTo(backend, "PUT /api/retirement/scenarios/1")).toHaveLength(1));
    expect(bodiesSentTo(backend, "PUT /api/retirement/scenarios/1")[0]).toMatchObject({ retireAge: 55, incomes: [], oneOffs: [] });
  });

  it("sets the lower, expected and higher growth and a raise, keeping the lower at or below the expected", async () => {
    const backend = withRetirement();
    renderApp("/retirement");

    const card = await screen.findByRole("region", { name: "Growth & saving" });
    expect(card).toHaveTextContent("Growth a year6.00%lower 4.00% · higher 8.00%Raise a yearNone");
    await userEvent.click(within(card).getByRole("button", { name: "Edit growth and saving" }));
    const dialog = await screen.findByRole("dialog", { name: "Retire at 55 · Growth & saving" });
    await userEvent.clear(within(dialog).getByLabelText("Lower growth (%)"));
    await userEvent.type(within(dialog).getByLabelText("Lower growth (%)"), "7");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please enter a lower growth from 0.00 up to the expected 6.00, like 4.00.");

    await userEvent.clear(within(dialog).getByLabelText("Lower growth (%)"));
    await userEvent.type(within(dialog).getByLabelText("Lower growth (%)"), "3");
    await userEvent.clear(within(dialog).getByLabelText("Higher growth (%)"));
    await userEvent.type(within(dialog).getByLabelText("Higher growth (%)"), "9");
    await userEvent.clear(within(dialog).getByLabelText("Raise a year (%)"));
    await userEvent.type(within(dialog).getByLabelText("Raise a year (%)"), "3");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(bodiesSentTo(backend, "PUT /api/retirement/scenarios/1")).toHaveLength(1));
    expect(bodiesSentTo(backend, "PUT /api/retirement/scenarios/1")[0]).toMatchObject({
      growthBp: 600,
      growthLowBp: 300,
      growthHighBp: 900,
      raiseBp: 300,
    });
  });

  it("says what each figure means with a tip", async () => {
    withRetirement();
    renderApp("/retirement");

    await screen.findByText(/^You can retire at/);
    await userEvent.click(screen.getAllByRole("button", { name: /Lower, expected and higher growth/ })[0]!);

    expect(await screen.findByText(/Expected is your best guess/)).toBeInTheDocument();
  });

  it("says so kindly when the plan can't be loaded", async () => {
    fakeBackend({ "GET /api/retirement": () => ({ status: 500, error: "Something went wrong." }) });
    renderApp("/retirement");

    expect(await screen.findByRole("alert")).toHaveTextContent("We couldn't load your retirement plan just now.");
  });
});
