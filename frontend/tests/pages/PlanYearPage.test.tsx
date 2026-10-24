import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { IncomeSource } from "../../src/api/income";
import type { PlanDetail, PlanRow, PlanStepView, PlanSummary, PlansOverview } from "../../src/api/plans";
import { bodiesSentTo, calls, DEFAULT_SETTINGS, EMPTY_DASHBOARD, fakeBackend, NO_PLANS, renderApp } from "../helpers";

/** A planned paycheck that stays the same all year. */
const NO_RAISE = { raiseFrom: null, raiseBp: null, raisedGrossCents: null, raisedNetCents: null, raisedPaychecks: 0 };

const DRAFT: PlanSummary = {
  id: 7,
  year: 2027,
  status: "draft",
  basis: "last_12_trend",
  startsOn: "2027-01-01",
  endsOn: "2027-12-31",
  setup: false,
  suggestedAsOf: "2026-10-12",
  activatedOn: null,
};

function row(overrides: Partial<PlanRow> & Pick<PlanRow, "id" | "name">): PlanRow {
  return {
    kind: "item",
    step: "bills",
    itemKind: "bill",
    frequency: "monthly",
    last12Cents: null,
    trendBp: null,
    suggestedCents: 0,
    reason: "history",
    currentCents: 0,
    chosenCents: 0,
    yours: false,
    perOccurrenceCents: 0,
    occurrences: 12,
    perPaycheckCents: 0,
    savedUpFor: false,
    yearCents: 0,
    currentYearCents: 0,
    pay: null,
    historyMonths: null,
    ...overrides,
  };
}

const PAY = row({
  kind: "income",
  step: "income",
  id: 1,
  name: "Riverside Clinic",
  itemKind: null,
  frequency: "semi_monthly",
  last12Cents: 5_966_880,
  suggestedCents: 248_620,
  reason: "fixed",
  currentCents: 248_620,
  chosenCents: 248_620,
  perOccurrenceCents: 248_620,
  occurrences: 24,
  yearCents: 5_966_880,
  currentYearCents: 5_966_880,
  pay: { grossCents: 360_000, netCents: 248_620, netIsEstimate: false, deductions: [], match: null, planned: false, ...NO_RAISE },
});

/** The real income source behind the PAY line. The plan never sends anything to it. */
const CLINIC: IncomeSource = {
  id: 1,
  name: "Riverside Clinic",
  depositAccountId: 1,
  grossCents: 360_000,
  netCents: 248_620,
  netIsEstimate: false,
  primary: true,
  schedule: {
    frequency: "semi_monthly",
    day1: 1,
    day2: 15,
    startDate: "2026-01-01",
    endDate: null,
    weekendRule: "previous_business_day",
    manualDates: [],
  },
  deductions: [],
  match: null,
  matchFigures: null,
  payHistory: [],
};

const RENT = row({
  id: 11,
  name: "Rent",
  last12Cents: 1_845_000,
  trendBp: 330,
  suggestedCents: 1_905_900,
  currentCents: 1_860_000,
  chosenCents: 1_905_900,
  perOccurrenceCents: 158_825,
  yearCents: 1_905_900,
  currentYearCents: 1_860_000,
});

const CAR_LOAN = row({
  id: 12,
  name: "Car loan",
  itemKind: "transfer",
  reason: "fixed",
  suggestedCents: 494_400,
  currentCents: 494_400,
  chosenCents: 494_400,
  perOccurrenceCents: 41_200,
  yearCents: 494_400,
  currentYearCents: 494_400,
});

const PHONE = row({
  id: 13,
  name: "Phone",
  reason: "not_enough_history",
  suggestedCents: 102_000,
  currentCents: 102_000,
  chosenCents: 102_000,
  perOccurrenceCents: 8_500,
  yearCents: 102_000,
  currentYearCents: 102_000,
});

const CAR_INSURANCE = row({
  id: 14,
  name: "Car insurance",
  step: "annual",
  frequency: "yearly",
  last12Cents: 120_000,
  trendBp: 530,
  suggestedCents: 126_400,
  currentCents: 120_000,
  chosenCents: 126_400,
  perOccurrenceCents: 126_400,
  occurrences: 1,
  perPaycheckCents: 5_267,
  savedUpFor: true,
  yearCents: 126_400,
  currentYearCents: 120_000,
});

function stepView(step: PlanStepView["step"], rows: PlanRow[], overrides: Partial<PlanStepView> = {}): PlanStepView {
  const sum = (pick: (row: PlanRow) => number) => rows.reduce((total, each) => total + pick(each), 0);
  return {
    step,
    rows,
    last12Cents: rows.some((each) => each.last12Cents !== null) ? sum((each) => each.last12Cents ?? 0) : null,
    trendBp: null,
    suggestedCents: sum((each) => (each.kind === "income" ? each.suggestedCents * each.occurrences : each.suggestedCents)),
    chosenCents: sum((each) => each.yearCents),
    currentCents: sum((each) => each.currentYearCents),
    yours: rows.filter((each) => each.yours).length,
    changes: rows.filter((each) => each.chosenCents !== each.currentCents).length,
    ...overrides,
  };
}

function detail(plan: PlanSummary, bills: PlanRow[] = [RENT, CAR_LOAN, PHONE], overrides: Partial<PlanDetail> = {}): PlanDetail {
  const steps = [
    stepView("income", [PAY]),
    stepView("bills", bills, { trendBp: 330 }),
    stepView("annual", [CAR_INSURANCE]),
    stepView("budgets", []),
    stepView("saving", []),
  ];
  return {
    plan,
    historyFrom: "2025-10-01",
    historyTo: "2026-09-30",
    stale: false,
    paydays: 24,
    steps,
    incomeCents: steps[0]?.chosenCents ?? 0,
    outCents: steps.slice(1).reduce((total, step) => total + step.chosenCents, 0),
    ...overrides,
  };
}

const CAN_PLAN: PlansOverview = { ...NO_PLANS, canPlan: true, canSetUp: false, reminderDue: true };
const DRAFTING: PlansOverview = { ...CAN_PLAN, plans: [DRAFT], canPlan: false, reminderDue: false };

function withDraft(overrides: Parameters<typeof fakeBackend>[0] = {}) {
  return fakeBackend({
    "GET /api/plans": () => ({ status: 200, data: DRAFTING }),
    "GET /api/plans/7": () => ({ status: 200, data: detail(DRAFT) }),
    ...overrides,
  });
}

/**
 * A draft that remembers: it opens as `before`, and once `change` has been sent it answers with
 * `after`, to that request and to every look at the plan from then on, as the real backend does.
 */
function draftThatChanges(before: PlanDetail, change: string, after: PlanDetail, more: Parameters<typeof fakeBackend>[0] = {}) {
  let current = before;
  return withDraft({
    ...more,
    "GET /api/plans/7": () => ({ status: 200, data: current }),
    [change]: () => {
      current = after;
      return { status: 200, data: current, changed: true };
    },
  });
}

describe("Plan next year", () => {
  it("offers to plan the coming year and opens the new draft on its first step", async () => {
    const user = userEvent.setup();
    const fetchMock = fakeBackend({
      "GET /api/plans": () => ({ status: 200, data: CAN_PLAN }),
      "POST /api/plans": () => ({ status: 201, data: { id: 7 }, changed: true }),
      "GET /api/plans/7": () => ({ status: 200, data: detail(DRAFT) }),
    });
    renderApp("/plan");

    expect(await screen.findByText("Plan 2027 before it starts.")).toBeInTheDocument();
    expect(screen.getByText(/Nothing\s+changes until Jan 1, 2027/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Start the 2027 plan" }));

    expect(await screen.findByRole("heading", { level: 1, name: "Income & benefits for 2027" })).toBeInTheDocument();
    expect(bodiesSentTo(fetchMock, "POST /api/plans")).toEqual([{ kind: "next_year" }]);
    expect(screen.getByText("Plan 2027 · Draft · Step 1 of 6")).toBeInTheDocument();
  });

  it("lists the plans there are and leads back into the draft", async () => {
    withDraft();
    renderApp("/plan");

    expect(await screen.findByText("Your 2027 plan is a draft.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Continue the 2027 plan" })).toHaveAttribute("href", "/plan/7/income");
    const plans = screen.getByRole("region", { name: "Your plans" });
    expect(within(plans).getByRole("link", { name: /2027 · draft, starts Jan 1, 2027/ })).toHaveAttribute("href", "/plan/7/income");
  });

  it("says income comes first when there is none to follow", async () => {
    fakeBackend({ "GET /api/plans": () => ({ status: 200, data: { ...NO_PLANS, canSetUp: false } }) });
    renderApp("/plan");

    expect(await screen.findByText("A plan follows your paydays.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Add your income" })).toHaveAttribute("href", "/income");
  });

  it("says so kindly when the plans cannot be loaded", async () => {
    fakeBackend({ "GET /api/plans": () => ({ status: 500, error: "Something went wrong on our side. Your data is safe — please try again." }) });
    renderApp("/plan");

    expect(await screen.findByRole("alert")).toHaveTextContent("Your data is safe");
  });

  it("shows each bill's last 12 months, trend, suggestion and what the amount works out to", async () => {
    withDraft();
    renderApp("/plan/7/bills");

    expect(await screen.findByRole("heading", { level: 1, name: "Bills for 2027" })).toBeInTheDocument();
    expect(screen.getByText("Oct 2025 – Sep 2026")).toBeInTheDocument();
    // A plan changes nothing real, so a line does not lead to the real bill.
    expect(screen.queryByRole("link", { name: "Rent" })).not.toBeInTheDocument();
    const rent = screen.getByText("Rent").closest("li");
    expect(rent).not.toBeNull();
    const inRent = within(rent as HTMLElement);
    expect(inRent.getByText("$18,450.00")).toBeInTheDocument();
    expect(inRent.getByText("+3.30%")).toBeInTheDocument();
    expect(inRent.getByText("$19,059.00")).toBeInTheDocument();
    expect(inRent.getByRole("textbox", { name: "Your 2027 amount for Rent" })).toHaveValue("19059.00");
    expect(inRent.getByText("$1,588.25 a month")).toBeInTheDocument();
    // A loan payment is suggested unchanged, and says so; a new bill says why it has no history.
    expect(screen.getByText("Monthly · fixed payment")).toBeInTheDocument();
    expect(screen.getByText("Monthly · not enough history yet")).toBeInTheDocument();
    expect(screen.getByText("Total for 2027")).toBeInTheDocument();
  });

  it("saves a typed amount as the box is left and marks it as yours", async () => {
    const user = userEvent.setup();
    const typed = { ...RENT, chosenCents: 1_920_000, yours: true, perOccurrenceCents: 160_000, yearCents: 1_920_000 };
    const fetchMock = draftThatChanges(detail(DRAFT), "PUT /api/plans/7/amounts", detail(DRAFT, [typed, CAR_LOAN, PHONE]));
    renderApp("/plan/7/bills");

    const box = await screen.findByRole("textbox", { name: "Your 2027 amount for Rent" });
    await user.clear(box);
    await user.type(box, "19,200");
    await user.tab();

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/plans/7/amounts")).toEqual([{ kind: "item", id: 11, amountCents: 1_920_000 }]));
    expect(await screen.findByText("$1,600.00 a month")).toBeInTheDocument();
    expect(screen.getByText("Yours")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Accept all suggestions" })).toBeInTheDocument();
  });

  it("says how many times a year a line comes round when it is not every week or every month", async () => {
    // Weekly in two months of the year only, and $25 each time billed by use though billed monthly.
    const plowing = row({ id: 21, name: "Snow plowing", frequency: "weekly", perOccurrenceCents: 10_000, chosenCents: 86_667, yearCents: 86_667, occurrences: 9 });
    const lessons = row({ id: 22, name: "Pottery class", frequency: "monthly", perOccurrenceCents: 2_500, chosenCents: 130_000, yearCents: 130_000 });
    const weekly = row({ id: 23, name: "Groceries", frequency: "weekly", perOccurrenceCents: 15_000, chosenCents: 780_000, yearCents: 780_000, occurrences: 52 });
    withDraft({ "GET /api/plans/7": () => ({ status: 200, data: detail(DRAFT, [plowing, lessons, weekly]) }) });
    renderApp("/plan/7/bills");

    expect(await screen.findByText("$100.00 each time · 9 times a year")).toBeInTheDocument();
    expect(screen.getByText("$25.00 each time · 52 times a year")).toBeInTheDocument();
    expect(screen.getByText("$150.00 a week")).toBeInTheDocument();
  });

  it("works an amount out on the calculator and saves it", async () => {
    const user = userEvent.setup();
    const typed = { ...RENT, chosenCents: 1_920_000, yours: true, perOccurrenceCents: 160_000, yearCents: 1_920_000 };
    const fetchMock = draftThatChanges(detail(DRAFT), "PUT /api/plans/7/amounts", detail(DRAFT, [typed, CAR_LOAN, PHONE]));
    renderApp("/plan/7/bills");

    await user.click(await screen.findByRole("button", { name: "Calculator for Your 2027 amount for Rent" }));
    const calculator = within(screen.getByRole("group", { name: "Calculator for Your 2027 amount for Rent" }));
    // It starts from what the box holds, the suggestion of $19,059.00.
    await user.keyboard("+141");
    await user.click(calculator.getByRole("button", { name: "Use $19,200.00" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/plans/7/amounts")).toEqual([{ kind: "item", id: 11, amountCents: 1_920_000 }]));
    expect(screen.queryByRole("group", { name: "Calculator for Your 2027 amount for Rent" })).not.toBeInTheDocument();
  });

  it("opens on the amount set now and takes the suggestion when asked", async () => {
    const user = userEvent.setup();
    // What the backend sends now: a line not typed in holds what it will be on Jan 1, beside its suggestion.
    const setNow = { ...RENT, chosenCents: 1_860_000, yearCents: 1_860_000 };
    const taken = { ...RENT, yours: true };
    const fetchMock = draftThatChanges(detail(DRAFT, [setNow, CAR_LOAN, PHONE]), "PUT /api/plans/7/amounts", detail(DRAFT, [taken, CAR_LOAN, PHONE]));
    renderApp("/plan/7/bills");

    expect(await screen.findByRole("textbox", { name: "Your 2027 amount for Rent" })).toHaveValue("18600.00");
    // A fixed payment and a line already at its suggestion offer nothing.
    expect(screen.getAllByRole("button", { name: "Use suggestion" })).toHaveLength(1);
    await user.click(screen.getByRole("button", { name: "Use suggestion" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/plans/7/amounts")).toEqual([{ kind: "item", id: 11, amountCents: 1_905_900 }]));
    await waitFor(() => expect(screen.getByRole("textbox", { name: "Your 2027 amount for Rent" })).toHaveValue("19059.00"));
    expect(screen.getByText("Suggestion")).toBeInTheDocument();
  });

  it("goes back to the amount set now when asked", async () => {
    const user = userEvent.setup();
    const typed = { ...RENT, chosenCents: 1_920_000, yours: true, yearCents: 1_920_000 };
    const setNow = { ...RENT, chosenCents: 1_860_000, yearCents: 1_860_000 };
    const fetchMock = draftThatChanges(detail(DRAFT, [typed, CAR_LOAN, PHONE]), "PUT /api/plans/7/amounts", detail(DRAFT, [setNow, CAR_LOAN, PHONE]));
    renderApp("/plan/7/bills");

    expect(await screen.findByText("Yours")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Keep $18,600.00, the amount set now, for Rent" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/plans/7/amounts")).toEqual([{ kind: "item", id: 11, amountCents: null }]));
    await waitFor(() => expect(screen.getByRole("textbox", { name: "Your 2027 amount for Rent" })).toHaveValue("18600.00"));
    expect(screen.queryByText("Yours")).not.toBeInTheDocument();
  });

  it("does not save something that is not an amount, and says what would be", async () => {
    const user = userEvent.setup();
    const fetchMock = withDraft();
    renderApp("/plan/7/bills");

    const box = await screen.findByRole("textbox", { name: "Your 2027 amount for Rent" });
    await user.clear(box);
    await user.type(box, "lots");
    await user.tab();

    expect(await screen.findByText("Please enter an amount like 1,250.00.")).toBeInTheDocument();
    expect(calls(fetchMock)).not.toContain("PUT /api/plans/7/amounts");
  });

  it("works a typed sum out, as every amount field does", async () => {
    const user = userEvent.setup();
    const fetchMock = withDraft({ "PUT /api/plans/7/amounts": () => ({ status: 200, data: detail(DRAFT), changed: true }) });
    renderApp("/plan/7/bills");

    const box = await screen.findByRole("textbox", { name: "Your 2027 amount for Phone" });
    await user.clear(box);
    await user.type(box, "1000+200{Enter}");

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/plans/7/amounts")).toEqual([{ kind: "item", id: 13, amountCents: 120_000 }]));
  });

  it("changes what the suggestions are worked out from", async () => {
    const user = userEvent.setup();
    const fetchMock = withDraft({
      "PUT /api/plans/7": () => ({ status: 200, data: detail({ ...DRAFT, basis: "prior_year" }), changed: true }),
    });
    renderApp("/plan/7/bills");

    await user.click(await screen.findByRole("radio", { name: "2025 full year" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/plans/7")).toEqual([{ basis: "prior_year" }]));
    expect(screen.getByRole("radio", { name: "Last 12 + trend" })).toBeInTheDocument();
  });

  it("offers newer suggestions once a month has ended", async () => {
    const user = userEvent.setup();
    const fetchMock = withDraft({
      "GET /api/plans/7": () => ({ status: 200, data: detail(DRAFT, undefined, { stale: true }) }),
      "PUT /api/plans/7": () => ({ status: 200, data: detail(DRAFT), changed: true }),
    });
    renderApp("/plan/7/bills");

    await user.click(await screen.findByRole("button", { name: "Use the latest months" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/plans/7")).toEqual([{ basis: "last_12_trend", refresh: true }]));
  });

  it("takes a step's typed amounts out with Accept all suggestions", async () => {
    const user = userEvent.setup();
    const typed = { ...RENT, chosenCents: 1_920_000, yours: true, yearCents: 1_920_000 };
    const fetchMock = draftThatChanges(detail(DRAFT, [typed, CAR_LOAN, PHONE]), "POST /api/plans/7/accept-suggestions", detail(DRAFT));
    renderApp("/plan/7/bills");

    await user.click(await screen.findByRole("button", { name: "Accept all suggestions" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "POST /api/plans/7/accept-suggestions")).toEqual([{ step: "bills" }]));
    await waitFor(() => expect(screen.queryByRole("button", { name: "Accept all suggestions" })).not.toBeInTheDocument());
  });

  it("says what saving up for a yearly bill takes each paycheck", async () => {
    withDraft();
    renderApp("/plan/7/annual");

    expect(await screen.findByRole("heading", { level: 1, name: "Annual & save-ups for 2027" })).toBeInTheDocument();
    expect(screen.getByText("save $52.67 per paycheck")).toBeInTheDocument();
  });

  it("shows take-home pay for one paycheck and what it comes to over the year, with no way into the real income", async () => {
    withDraft();
    renderApp("/plan/7");

    expect(await screen.findByRole("button", { name: "Edit Riverside Clinic's pay in 2027" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Riverside Clinic" })).not.toBeInTheDocument();
    expect(screen.getByText("$59,668.80 a year · 24 paychecks")).toBeInTheDocument();
    expect(screen.queryByText(/Add it under/)).not.toBeInTheDocument();
  });

  it("plans next year's whole paycheck in the plan only, never in the real income", async () => {
    const user = userEvent.setup();
    const planned = {
      ...PAY,
      chosenCents: 260_000,
      yours: true,
      yearCents: 6_240_000,
      pay: { grossCents: 380_000, netCents: 260_000, netIsEstimate: true, deductions: [], match: null, planned: true, ...NO_RAISE },
    };
    const after = { ...detail(DRAFT), steps: detail(DRAFT).steps.map((step) => (step.step === "income" ? stepView("income", [planned]) : step)) };
    const fetchMock = draftThatChanges(detail(DRAFT), "PUT /api/plans/7/income/1/pay", after, {
      "GET /api/income-sources": () => ({ status: 200, data: [CLINIC] }),
    });
    renderApp("/plan/7");

    await user.click(await screen.findByRole("button", { name: "Edit Riverside Clinic's pay in 2027" }));
    const dialog = screen.getByRole("dialog", { name: "Riverside Clinic: pay in 2027" });
    expect(within(dialog).getByText(/Only your 2027 plan changes/)).toBeInTheDocument();
    // It opens on the pay as it stands, marked as an estimate: next year's pay is a guess until a stub says otherwise.
    expect(within(dialog).getByRole("checkbox", { name: "Estimate, not final yet" })).toBeChecked();
    const takeHome = within(dialog).getByRole("textbox", { name: "Take-home per paycheck" });
    expect(takeHome).toHaveValue("2486.20");
    await user.clear(takeHome);
    await user.type(takeHome, "2600");
    const gross = within(dialog).getByRole("textbox", { name: "Gross per paycheck (optional)" });
    await user.clear(gross);
    await user.type(gross, "3800");
    await user.click(within(dialog).getByRole("button", { name: "Next" }));
    // Whether it goes up during the year is asked next; left empty, the pay stays the same all year.
    expect(within(dialog).getByRole("textbox", { name: "Raise, %" })).toHaveValue("");
    await user.click(within(dialog).getByRole("button", { name: "Save to the 2027 plan" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "PUT /api/plans/7/income/1/pay")).toEqual([
      { grossCents: 380_000, netCents: 260_000, netIsEstimate: true, deductions: [], match: null, raise: null },
    ]);
    expect(calls(fetchMock).filter((call) => call.includes("/api/income-sources") && !call.startsWith("GET "))).toEqual([]);
    expect(await screen.findByText("Yours")).toBeInTheDocument();
  });

  it("plans a raise as a percent from a day partway through the year", async () => {
    const user = userEvent.setup();
    const raised = {
      ...PAY,
      yours: true,
      yearCents: 6_043_000,
      pay: {
        ...PAY.pay!,
        planned: true,
        netIsEstimate: true,
        raiseFrom: "2027-06-01",
        raiseBp: 300,
        raisedGrossCents: 370_800,
        raisedNetCents: 256_079,
        raisedPaychecks: 14,
      },
    };
    const after = { ...detail(DRAFT), steps: detail(DRAFT).steps.map((step) => (step.step === "income" ? stepView("income", [raised]) : step)) };
    const fetchMock = draftThatChanges(detail(DRAFT), "PUT /api/plans/7/income/1/pay", after, {
      "GET /api/income-sources": () => ({ status: 200, data: [CLINIC] }),
    });
    renderApp("/plan/7");

    await user.click(await screen.findByRole("button", { name: "Edit Riverside Clinic's pay in 2027" }));
    const dialog = screen.getByRole("dialog", { name: "Riverside Clinic: pay in 2027" });
    await user.click(within(dialog).getByRole("button", { name: "Next" }));
    await user.type(within(dialog).getByRole("textbox", { name: "Raise, %" }), "3");
    // A percent with no day is not enough.
    await user.click(within(dialog).getByRole("button", { name: "Save to the 2027 plan" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please choose the day the raise starts.");
    await user.type(within(dialog).getByLabelText("From"), "2027-06-01");
    expect(within(dialog).getByText(/From Jun 1, 2027: about/)).toHaveTextContent("From Jun 1, 2027: about $2,560.79 take-home per paycheck, $3,708.00 gross");
    await user.click(within(dialog).getByRole("button", { name: "Save to the 2027 plan" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "PUT /api/plans/7/income/1/pay")).toEqual([
      { grossCents: 360_000, netCents: 248_620, netIsEstimate: true, deductions: [], match: null, raise: { from: "2027-06-01", bp: 300 } },
    ]);
    // The line says what it goes up to, and from when.
    expect(await screen.findByText(/from Jun 1, 2027 · 3\.00% raise/)).toBeInTheDocument();
  });

  it("goes back to today's pay when asked", async () => {
    const user = userEvent.setup();
    const planned = { ...PAY, chosenCents: 260_000, yours: true, yearCents: 6_240_000 };
    const before = { ...detail(DRAFT), steps: detail(DRAFT).steps.map((step) => (step.step === "income" ? stepView("income", [planned]) : step)) };
    const fetchMock = draftThatChanges(before, "PUT /api/plans/7/amounts", detail(DRAFT));
    renderApp("/plan/7");

    await user.click(await screen.findByRole("button", { name: "Use today's pay" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/plans/7/amounts")).toEqual([{ kind: "income", id: 1, amountCents: null }]));
  });

  it("has words for a step with nothing in it", async () => {
    withDraft();
    renderApp("/plan/7/budgets");

    expect(await screen.findByText("No spending budgets.")).toBeInTheDocument();
    // Nothing in a plan leads off to change the real recurring items.
    expect(screen.queryByRole("link", { name: /Add it under/ })).not.toBeInTheDocument();
  });

  it("moves between the steps by address, so Back and a refresh keep the step", async () => {
    withDraft();
    renderApp("/plan/7/bills");

    const steps = await screen.findByRole("navigation", { name: "Steps" });
    expect(within(steps).getAllByRole("link")).toHaveLength(6);
    expect(within(steps).getByRole("link", { name: /Step 2\s*Bills/ })).toHaveAttribute("aria-current", "step");
    expect(within(steps).getByRole("link", { name: /Step 1 · done\s*Income & benefits/ })).toHaveAttribute("href", "/plan/7/income");
    expect(screen.getByRole("link", { name: "Next: Annual & save-ups →" })).toHaveAttribute("href", "/plan/7/annual");
    expect(screen.getByRole("link", { name: "← Income & benefits" })).toHaveAttribute("href", "/plan/7/income");
  });

  it("adds the year up on Review and lists what will change", async () => {
    withDraft();
    renderApp("/plan/7/review");

    expect(await screen.findByRole("heading", { level: 1, name: "Review for 2027" })).toBeInTheDocument();
    expect(screen.getByText("Take-home in 2027")).toBeInTheDocument();
    // 59,668.80 in; 19,059.00 + 4,944.00 + 1,020.00 + 1,264.00 out.
    expect(screen.getByText("$26,287.00")).toBeInTheDocument();
    expect(screen.getByText("Left over")).toBeInTheDocument();
    expect(screen.getByText("$33,381.80")).toBeInTheDocument();
    const changes = screen.getByRole("region", { name: "What changes on Jan 1, 2027" });
    expect(within(changes).getByText("Rent")).toBeInTheDocument();
    expect(within(changes).getByText("Car insurance")).toBeInTheDocument();
    expect(within(changes).queryByText("Phone")).not.toBeInTheDocument();
    expect(within(changes).getByText(/becomes active by itself/)).toBeInTheDocument();
  });

  it("asks before deleting a draft, and keeping it is the default", async () => {
    const user = userEvent.setup();
    const fetchMock = withDraft({ "DELETE /api/plans/7": () => ({ status: 200, data: { done: true }, changed: true }) });
    renderApp("/plan/7/review");

    await user.click(await screen.findByRole("button", { name: "Delete draft & start over…" }));
    const asking = screen.getByRole("alertdialog");
    expect(within(asking).getByText("Delete the 2027 draft?")).toBeInTheDocument();
    expect(within(asking).getByRole("button", { name: "Keep it" })).toHaveFocus();
    expect(calls(fetchMock)).not.toContain("DELETE /api/plans/7");

    await user.click(within(asking).getByRole("button", { name: "Yes, delete the draft" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "DELETE /api/plans/7")).toEqual([{ confirm: "delete-draft" }]));
    expect(await screen.findByRole("heading", { level: 1, name: "Plan next year" })).toBeInTheDocument();
  });

  it("shows a plan in use as it was set, with nothing left to type", async () => {
    const active: PlanSummary = { ...DRAFT, status: "active", activatedOn: "2027-01-01" };
    fakeBackend({
      "GET /api/plans": () => ({ status: 200, data: { ...CAN_PLAN, plans: [active] } }),
      "GET /api/plans/7": () => ({ status: 200, data: detail(active) }),
    });
    renderApp("/plan/7/bills");

    expect(await screen.findByText("Plan 2027 · Active · Step 2 of 6")).toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Delete draft & start over…" })).not.toBeInTheDocument();
    expect(screen.getByText(/In use since Jan 1, 2027/)).toBeInTheDocument();
  });

  it("says so when a plan cannot be found", async () => {
    fakeBackend();
    renderApp("/plan/99/bills");

    expect(await screen.findByText("We couldn't open that plan.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to Plan next year" })).toHaveAttribute("href", "/plan");
  });
});

describe("First-time setup", () => {
  const SETUP: PlanSummary = { ...DRAFT, id: 3, year: 2026, startsOn: "2026-01-01", endsOn: "2026-12-31", setup: true };
  const EMPTY_SETUP: PlanDetail = {
    plan: SETUP,
    historyFrom: "2025-10-01",
    historyTo: "2026-09-30",
    stale: false,
    paydays: 0,
    steps: [stepView("income", []), stepView("bills", []), stepView("annual", []), stepView("budgets", []), stepView("saving", [])],
    incomeCents: 0,
    outCents: 0,
  };

  it("is offered on the home screen of an app with nothing in it", async () => {
    fakeBackend();
    renderApp("/");

    expect(await screen.findByText("New here? Budget can walk you through setting up.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Set up Budget" })).toHaveAttribute("href", "/plan");
    // The plain way in is still there.
    expect(screen.getByRole("link", { name: "Add your income" })).toBeInTheDocument();
  });

  it("is not offered once the app has things in it", async () => {
    fakeBackend({ "GET /api/plans": () => ({ status: 200, data: { ...NO_PLANS, canSetUp: false } }) });
    renderApp("/");

    expect(await screen.findByText("No pay periods yet.")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Set up Budget" })).not.toBeInTheDocument();
  });

  it("starts from Plan next year and opens on the first step", async () => {
    const user = userEvent.setup();
    const fetchMock = fakeBackend({
      "POST /api/plans": () => ({ status: 201, data: { id: 3 }, changed: true }),
      "GET /api/plans/3": () => ({ status: 200, data: EMPTY_SETUP }),
    });
    renderApp("/plan");

    await user.click(await screen.findByRole("button", { name: "Set up Budget" }));

    expect(await screen.findByRole("heading", { level: 1, name: "Set up Budget: Income & benefits" })).toBeInTheDocument();
    expect(bodiesSentTo(fetchMock, "POST /api/plans")).toEqual([{ kind: "setup" }]);
    expect(await screen.findByText("Start with the account your paycheck lands in.")).toBeInTheDocument();
    // Income needs somewhere to land, so the account comes first.
    expect(screen.getByRole("button", { name: "+ Add income" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "+ Add an account" }));
    expect(await screen.findByRole("dialog")).toBeInTheDocument();
  });

  it("adds bills with the same form as everywhere else, once there is an account to pay from", async () => {
    fakeBackend({
      "GET /api/plans": () => ({ status: 200, data: { ...NO_PLANS, plans: [SETUP], canSetUp: false } }),
      "GET /api/plans/3": () => ({ status: 200, data: EMPTY_SETUP }),
    });
    renderApp("/plan/3/bills");

    expect(await screen.findByText("No bills in this step.")).toBeInTheDocument();
    expect(await screen.findByText("An item is paid from an account. Add one in step 1 first.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "+ Add a recurring item" })).toBeDisabled();
    expect(screen.queryByRole("radio", { name: "Last 12 + trend" })).not.toBeInTheDocument();
  });

  it("finishes on Review and goes to the pay periods", async () => {
    const user = userEvent.setup();
    const fetchMock = fakeBackend({
      "GET /api/plans": () => ({ status: 200, data: { ...NO_PLANS, plans: [SETUP], canSetUp: false } }),
      "GET /api/plans/3": () => ({ status: 200, data: EMPTY_SETUP }),
      "POST /api/plans/3/finish": () => ({ status: 200, data: { ...EMPTY_SETUP, plan: { ...SETUP, status: "active", activatedOn: "2026-10-12" } }, changed: true }),
    });
    renderApp("/plan/3/review");

    await user.click(await screen.findByRole("button", { name: "Finish setup" }));

    await waitFor(() => expect(calls(fetchMock)).toContain("POST /api/plans/3/finish"));
    expect(await screen.findByRole("heading", { level: 1, name: "Pay periods" })).toBeInTheDocument();
  });
});

describe("Plan next year, from the rest of the app", () => {
  it("is in the sidebar", async () => {
    fakeBackend();
    renderApp("/settings");

    const links = await screen.findAllByRole("link", { name: "Plan next year" });
    expect(links[0]).toHaveAttribute("href", "/plan");
  });

  it("is one step from Annual costs", async () => {
    fakeBackend();
    renderApp("/dashboard/annual");

    expect(await screen.findByRole("link", { name: "Plan next year →" })).toHaveAttribute("href", "/plan");
  });

  it("is suggested on the Dashboard from the reminder day, and announced when a plan takes effect", async () => {
    const attention = (kind: string, name: string, date: string) => ({
      kind,
      name,
      date,
      amountCents: null,
      goalCents: null,
      count: null,
      urgent: false,
      accountId: null,
      itemId: null,
      sourceId: null,
      stubId: null,
      periodStart: null,
    });
    fakeBackend({
      "GET /api/dashboard": () => ({
        status: 200,
        data: { ...EMPTY_DASHBOARD, attention: [attention("plan_reminder", "2027", "2027-01-01"), attention("plan_active", "2026", "2026-01-01")] },
      }),
    });
    renderApp("/dashboard");

    expect(await screen.findByRole("link", { name: /Time to plan 2027/ })).toHaveAttribute("href", "/plan");
    expect(screen.getByRole("link", { name: /Your 2026 plan is now active/ })).toHaveAttribute("href", "/plan");
  });

  it("lets the budget year start and the reminder day be chosen from lists", async () => {
    const user = userEvent.setup();
    const fetchMock = fakeBackend({
      "PUT /api/settings/budget_year_start": () => ({ status: 200, data: { ...DEFAULT_SETTINGS, budget_year_start: "07-01" }, changed: true }),
      "PUT /api/settings/plan_reminder": () => ({ status: 200, data: { ...DEFAULT_SETTINGS, plan_reminder: "10-15" }, changed: true }),
    });
    renderApp("/settings");

    await user.selectOptions(await screen.findByRole("combobox", { name: "Budget year starts: month" }), "July");
    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/settings/budget_year_start")).toEqual([{ value: "07-01" }]));

    await user.selectOptions(screen.getByRole("combobox", { name: "Remind me to plan next year: day" }), "15");
    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/settings/plan_reminder")).toEqual([{ value: "10-15" }]));
  });
});
