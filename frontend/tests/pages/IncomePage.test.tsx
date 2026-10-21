import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Account } from "../../src/api/accounts";
import type { IncomeSource, OneOffPaycheck, Paycheck } from "../../src/api/income";
import { todayIso } from "../../src/lib/dates";
import { bodiesSentTo, calls, fakeBackend, renderApp } from "../helpers";

const CHECKING: Account = {
  id: 1,
  name: "Everyday Checking",
  institution: null,
  type: "checking",
  subtypeLabel: null,
  color: "#0f766e",
  archived: false,
  lowBalanceThresholdCents: null,
  latestBalance: null,
};
const RETIREMENT: Account = { ...CHECKING, id: 2, name: "Retirement", type: "investment", color: "#7e22ce" };

const SALARY: IncomeSource = {
  payHistory: [],
  id: 1,
  name: "Northwind Health",
  depositAccountId: 1,
  grossCents: 360000,
  netCents: 248620,
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
  deductions: [{ name: "401(k)", amountCents: 15000, toAccountId: 2, percentBp: null }],
  match: null,
  matchFigures: null,
};
const BAKERY: IncomeSource = {
  payHistory: [],
  id: 2,
  name: "Riverside Bakery",
  depositAccountId: 1,
  grossCents: null,
  netCents: 31840,
  netIsEstimate: true,
  primary: false,
  schedule: {
    frequency: "biweekly",
    day1: null,
    day2: null,
    startDate: "2026-11-06",
    endDate: null,
    weekendRule: "none",
    manualDates: [],
  },
  deductions: [],
  match: null,
  matchFigures: null,
};

const PAYCHECKS: Paycheck[] = [
  {
    incomeSourceId: 1,
    sourceName: "Northwind Health",
    depositAccountId: 1,
    scheduledDate: "2026-11-01",
    payDate: "2026-10-30",
    netCents: 248620,
    estimate: false,
    projectedCents: null,
    contributions: [],
    grossCents: null,
    deductions: [],
    oneOffId: null,
    name: null,
  },
  {
    incomeSourceId: 2,
    sourceName: "Riverside Bakery",
    depositAccountId: 1,
    scheduledDate: "2026-11-06",
    payDate: "2026-11-06",
    netCents: 31840,
    estimate: true,
    projectedCents: null,
    contributions: [],
    grossCents: null,
    deductions: [],
    oneOffId: null,
    name: null,
  },
  {
    incomeSourceId: 1,
    sourceName: "Northwind Health",
    depositAccountId: 1,
    scheduledDate: "2026-11-26",
    payDate: "2026-11-25",
    netCents: 248620,
    estimate: false,
    projectedCents: null,
    contributions: [],
    grossCents: null,
    deductions: [],
    oneOffId: null,
    name: null,
  },
];

function withIncome(overrides: Parameters<typeof fakeBackend>[0] = {}) {
  return fakeBackend({
    "GET /api/accounts": () => ({ status: 200, data: [CHECKING, RETIREMENT] }),
    "GET /api/income-sources": () => ({ status: 200, data: [SALARY, BAKERY] }),
    "GET /api/paychecks": () => ({ status: 200, data: PAYCHECKS }),
    ...overrides,
  });
}

/** Brings up one screen of the income form, which shows a group of fields at a time so it never scrolls. */
const show = (dialog: HTMLElement, group: string) =>
  userEvent.selectOptions(within(dialog).getByRole("combobox", { name: "What to change" }), group);

describe("income screen", () => {
  it("asks for an account before any income can be added", async () => {
    fakeBackend();
    renderApp("/income");

    expect(await screen.findByText("Add an account first.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Add the account your pay is deposited to" })).toHaveAttribute("href", "/accounts");
    expect(screen.getByRole("button", { name: "+ Add income source" })).toBeDisabled();
  });

  it("invites the first paycheck once there is an account", async () => {
    fakeBackend({ "GET /api/accounts": () => ({ status: 200, data: [CHECKING] }) });
    renderApp("/income");

    expect(await screen.findByText("No income yet.")).toBeInTheDocument();
    expect(await screen.findByText(/Paychecks will show up here/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "+ Add income source" })).toBeEnabled());
  });

  it("is an overview: each source on one row that opens its own page", async () => {
    withIncome();
    renderApp("/income");

    const sources = await screen.findByRole("region", { name: "Income sources" });
    const salary = within(sources).getByRole("link", { name: /Northwind Health/ });
    expect(salary).toHaveAttribute("href", "/income/1");
    // The desktop shell gives a link no hand cursor by itself, so the row asks for one.
    expect(salary).toHaveClass("cursor-pointer");
    expect(salary).toHaveTextContent("Twice a month · 1st & 15th · into Everyday Checking");
    expect(salary).toHaveTextContent("Primary · defines pay periods");
    expect(salary).toHaveTextContent("$2,486.20");
    expect(salary).toHaveTextContent("take-home a paycheck");
    // The detail stays on the source's page: no deductions, match or pay history here.
    expect(sources).not.toHaveTextContent("401(k)");
    expect(screen.queryByText("Pay history")).not.toBeInTheDocument();

    const bakery = within(sources).getByRole("link", { name: /Riverside Bakery/ });
    expect(bakery).not.toHaveTextContent("Primary");
    expect(bakery).toHaveTextContent("Every 2 weeks · Fridays");
    // The varying amount is marked as an estimate in words, not just by its blue box.
    expect(bakery).toHaveTextContent("$318.40 (estimate)");
    // A row does one thing, open the source's page: editing starts there, so no button sits on the row.
    expect(within(sources).queryByRole("button")).not.toBeInTheDocument();
  });

  it("shows one source's schedule and where the money goes on its own page", async () => {
    withIncome();
    renderApp("/income/1");

    expect(await screen.findByRole("heading", { level: 1, name: "Northwind Health" })).toBeInTheDocument();
    expect(screen.getByText("Primary · its paydays define your pay periods")).toBeInTheDocument();
    // The way back to the overview sits above the name (the sidebar has its own Income link too).
    expect(screen.getAllByRole("link", { name: "Income" }).every((link) => link.getAttribute("href") === "/income")).toBe(true);
    // Laid out like an account's page: a card per topic.
    const schedule = screen.getByRole("region", { name: "Schedule" });
    expect(schedule).toHaveTextContent("Twice a month · 1st & 15th");
    expect(schedule).toHaveTextContent("Previous business day");
    expect(schedule).toHaveTextContent("Everyday Checking");
    const out = screen.getByRole("region", { name: "Coming out of every paycheck" });
    expect(out).toHaveTextContent("401(k) → Retirement");
    expect(out).toHaveTextContent("Each paycheck$150.00");
    // Nothing set up is said in words, with where to add it.
    expect(screen.getByRole("region", { name: "Employer match" })).toHaveTextContent("No employer match is set up.");
    // Only this source's paychecks are listed beside it.
    const upcoming = screen.getByRole("region", { name: "Upcoming paychecks" });
    await within(upcoming).findAllByRole("listitem");
    expect(upcoming).not.toHaveTextContent("Riverside Bakery");
  });

  it("edits each card from its own pencil, opening the form on that card's screen", async () => {
    withIncome();
    renderApp("/income/1");
    await screen.findByRole("heading", { level: 1, name: "Northwind Health" });

    for (const [card, pencil, screenName] of [
      ["Schedule", "Edit schedule", "Paydays"],
      ["Coming out of every paycheck", "Edit what comes out of every paycheck", "Deductions"],
      ["Employer match", "Edit employer match", "Employer match"],
    ] as const) {
      await userEvent.click(within(screen.getByRole("region", { name: card })).getByRole("button", { name: pencil }));
      const dialog = await screen.findByRole("dialog");
      const chosen = within(dialog).getByRole("combobox", { name: "What to change" }) as HTMLSelectElement;
      expect(chosen.selectedOptions[0]).toHaveTextContent(screenName);
      await userEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    }
  });

  it("opens a source's page on its figures: a paycheck, and what the year adds up to", async () => {
    withIncome({
      "GET /api/income-sources/1/this-year": () => ({
        status: 200,
        data: { year: 2026, soFarCents: 4723780, paychecksSoFar: 19, wholeYearCents: 5966880, paychecksInYear: 24, estimate: false, grossSoFarCents: 6840000, grossPaychecksSoFar: 19, grossWholeYearCents: 2160000, grossPaychecksInYear: 6 },
      }),
    });
    renderApp("/income/1");

    await screen.findByRole("heading", { level: 1, name: "Northwind Health" });
    const main = screen.getByRole("main");
    expect(main).toHaveTextContent("Take-home$2,486.20a paycheck");
    expect(main).toHaveTextContent("Gross$3,600.00a paycheck, before anything comes out");
    expect(await within(main).findByText("take-home from 19 paychecks in 2026")).toBeInTheDocument();
    expect(main).toHaveTextContent("So far this year$47,237.80");
    expect(main).toHaveTextContent("Whole year$59,668.80take-home expected from 24 paychecks in 2026");
    // The year's gross, as figures of its own; it says so when only some paychecks have a gross amount.
    expect(main).toHaveTextContent("Gross so far this year$68,400.00before anything comes out, from 19 paychecks in 2026");
    expect(main).toHaveTextContent("Gross whole year$21,600.00before anything comes out, from 6 of 24 paychecks in 2026; the others have no gross amount entered");
  });

  it("says so kindly when the year cannot be added up, and still shows the rest", async () => {
    withIncome({
      "GET /api/income-sources/1/this-year": () => ({ status: 500, error: "Something went wrong on our side. Please try again." }),
    });
    renderApp("/income/1");

    expect(await screen.findByRole("alert")).toHaveTextContent("Something went wrong on our side. Please try again.");
    expect(screen.getByRole("region", { name: "Schedule" })).toBeInTheDocument();
  });

  it("says so kindly when the income source is not there", async () => {
    withIncome();
    renderApp("/income/999");

    expect(await screen.findByRole("alert")).toHaveTextContent("We couldn't find that income source. It may have been removed.");
    expect(screen.getByRole("link", { name: "Back to all income" })).toHaveAttribute("href", "/income");
  });

  it("lists upcoming paychecks and says why one was moved", async () => {
    withIncome();
    renderApp("/income");

    const upcoming = await screen.findByRole("region", { name: "Upcoming paychecks" });
    const rows = await within(upcoming).findAllByRole("listitem");
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent("Fri Oct 30");
    expect(rows[0]).toHaveTextContent("Nov 1 is a Sunday");
    expect(rows[1]).toHaveTextContent("Fri Nov 6");
    expect(rows[1]).not.toHaveTextContent(" is a ");
    // Thanksgiving is a Thursday, so only a holiday can explain the move.
    expect(rows[2]).toHaveTextContent("Nov 26 is a holiday");
    expect(within(upcoming).getByText("= estimate")).toBeInTheDocument();
  });

  it("types over one paycheck's amount", async () => {
    let paychecks = PAYCHECKS;
    const fetchMock = withIncome({
      "GET /api/paychecks": () => ({ status: 200, data: paychecks }),
      "PUT /api/income-sources/2/paychecks/2026-11-06": () => {
        paychecks = paychecks.map((paycheck) =>
          paycheck.incomeSourceId === 2 ? { ...paycheck, netCents: 29015, estimate: false, projectedCents: 31840 } : paycheck,
        );
        return { status: 200, data: { done: true } };
      },
    });
    renderApp("/income");

    await userEvent.click(await screen.findByRole("button", { name: "Change the amount for Riverside Bakery, Nov 6" }));
    const amount = screen.getByRole("textbox", { name: "Amount for Riverside Bakery, Nov 6" });
    expect(amount).toHaveValue("318.40");
    await userEvent.clear(amount);
    await userEvent.type(amount, "290.15{Enter}");

    const edited = await screen.findByRole("button", { name: "Change the amount for Riverside Bakery, Nov 6" });
    expect(bodiesSentTo(fetchMock, "PUT /api/income-sources/2/paychecks/2026-11-06")).toEqual([{ netCents: 29015 }]);
    await waitFor(() => expect(edited).toHaveTextContent("$290.15"));
    expect(edited).not.toHaveTextContent("estimate");
    // What the app projected before is still there on hover.
    expect(edited).toHaveAttribute("title", "Projected: $318.40");
  });

  it("leaves a paycheck alone when the edit is cancelled with Escape", async () => {
    const fetchMock = withIncome();
    renderApp("/income");

    await userEvent.click(await screen.findByRole("button", { name: "Change the amount for Riverside Bakery, Nov 6" }));
    await userEvent.type(screen.getByRole("textbox", { name: "Amount for Riverside Bakery, Nov 6" }), "5{Escape}");

    expect(screen.queryByRole("textbox", { name: /Amount for/ })).not.toBeInTheDocument();
    expect(calls(fetchMock).some((call) => call.startsWith("PUT"))).toBe(false);
  });

  it("adds the first income source, which always sets the pay periods", async () => {
    const fetchMock = fakeBackend({
      "GET /api/accounts": () => ({ status: 200, data: [CHECKING, RETIREMENT] }),
      "POST /api/income-sources": () => ({ status: 201, data: SALARY }),
    });
    renderApp("/income");
    await screen.findByText("No income yet.");

    await userEvent.click(screen.getByRole("button", { name: "+ Add income source" }));
    const dialog = screen.getByRole("dialog", { name: "Add an income source" });
    await userEvent.type(within(dialog).getByLabelText("Name"), "Northwind Health");
    await userEvent.type(within(dialog).getByRole("combobox", { name: "Deposited to" }), "every{Enter}");
    const primary = within(dialog).getByLabelText("This paycheck sets my pay periods");
    expect(primary).toBeChecked();
    expect(primary).toBeDisabled();
    // Three steps, a screen each: who pays, when, how much.
    expect(within(dialog).getByRole("status")).toHaveTextContent("Step 1 of 3");
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    expect(within(dialog).getByRole("group", { name: "When you're paid" })).toBeInTheDocument();
    await userEvent.selectOptions(within(dialog).getByLabelText("How often"), "Twice a month");
    await userEvent.selectOptions(within(dialog).getByLabelText("Second payday of the month"), "Last day");
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    await userEvent.type(within(dialog).getByLabelText("Take-home per paycheck"), "2,486.20");
    await userEvent.type(within(dialog).getByLabelText("Gross per paycheck (optional)"), "3600");
    // It could be added here; deductions and a match are for those who have them.
    expect(within(dialog).getByRole("button", { name: "Add income source" })).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "Deductions and match" }));
    expect(within(dialog).getByRole("status")).toHaveTextContent("Deductions and match · 1 of 2");
    await userEvent.click(within(dialog).getByRole("button", { name: "+ Add a deduction" }));
    expect(within(dialog).getByText("Deduction 1 of 1")).toBeInTheDocument();
    await userEvent.type(within(dialog).getByRole("combobox", { name: "Name" }), "401{Enter}");
    await userEvent.type(within(dialog).getByLabelText("Amount"), "150");
    await userEvent.type(within(dialog).getByRole("combobox", { name: "Goes into (optional)" }), "retire{Enter}");
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    expect(within(dialog).getByLabelText("Does your employer add anything?")).toHaveValue("none");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add income source" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "POST /api/income-sources")).toEqual([
      {
        name: "Northwind Health",
        depositAccountId: 1,
        grossCents: 360000,
        netCents: 248620,
        netIsEstimate: false,
        primary: true,
        schedule: {
          frequency: "semi_monthly",
          day1: 1,
          day2: 31,
          // Fixed days of the month start from January, so the current pay period exists.
          startDate: `${todayIso().slice(0, 4)}-01-01`,
          endDate: null,
          weekendRule: "previous_business_day",
          manualDates: [],
        },
        deductions: [{ name: "401(k)", amountCents: 15000, toAccountId: 2 }],
        match: null,
      },
    ]);
  });

  it("adds a second job paid every two weeks, as an estimate", async () => {
    const fetchMock = withIncome({ "POST /api/income-sources": () => ({ status: 201, data: BAKERY }) });
    renderApp("/income");
    await screen.findByRole("link", { name: /Northwind Health/ });

    await userEvent.click(screen.getByRole("button", { name: "+ Add income source" }));
    const dialog = screen.getByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText("Name"), "Riverside Bakery");
    await userEvent.type(within(dialog).getByRole("combobox", { name: "Deposited to" }), "every{Enter}");
    // A later source is not primary unless asked, and may be.
    expect(within(dialog).getByLabelText("This paycheck sets my pay periods")).toBeEnabled();
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    await userEvent.selectOptions(within(dialog).getByLabelText("On a weekend or holiday"), "No change");
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    await userEvent.type(within(dialog).getByLabelText("Take-home per paycheck"), "318.40");
    await userEvent.click(within(dialog).getByLabelText("Estimate, not final yet"));
    await userEvent.click(within(dialog).getByRole("button", { name: "Add income source" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "POST /api/income-sources")[0]).toMatchObject({
      name: "Riverside Bakery",
      grossCents: null,
      netCents: 31840,
      netIsEstimate: true,
      primary: false,
      schedule: { frequency: "biweekly", day1: null, day2: null, startDate: todayIso(), weekendRule: "none" },
      deductions: [],
    });
  });

  it("explains what is missing before sending anything", async () => {
    const fetchMock = withIncome();
    renderApp("/income");
    await screen.findByRole("link", { name: /Northwind Health/ });

    await userEvent.click(screen.getByRole("button", { name: "+ Add income source" }));
    const dialog = screen.getByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText("Name"), "Side work");
    // Each step is checked before the next opens, so what is missing is on the screen in view.
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please choose the account this pay is deposited to.");
    expect(within(dialog).getByRole("status")).toHaveTextContent("Step 1 of 3");

    await userEvent.type(within(dialog).getByRole("combobox", { name: "Deposited to" }), "every{Enter}");
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    await userEvent.selectOptions(within(dialog).getByLabelText("How often"), "On dates I pick");
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please add at least two paydays");
    expect(within(dialog).getByRole("status")).toHaveTextContent("Step 2 of 3");

    expect(calls(fetchMock)).not.toContain("POST /api/income-sources");
  });

  it("edits a source, keeping the primary one primary", async () => {
    const fetchMock = withIncome({ "PUT /api/income-sources/1": () => ({ status: 200, data: SALARY }) });
    renderApp("/income/1");

    await userEvent.click(await screen.findByRole("button", { name: "Edit income source" }));
    const dialog = screen.getByRole("dialog", { name: "Edit income source" });
    // One screen at a time, chosen at the top; Save sends all of them.
    const change = within(dialog).getByRole("combobox", { name: "What to change" });
    expect(within(change).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "Name and account",
      "Paydays",
      "Pay",
      "Deductions",
      "Employer match",
      "Delete",
    ]);
    expect(within(dialog).getByRole("combobox", { name: "Deposited to" })).toHaveValue("Everyday Checking");
    expect(within(dialog).getByLabelText("This paycheck sets my pay periods")).toBeDisabled();
    await show(dialog, "Pay");
    expect(within(dialog).getByLabelText("Take-home per paycheck")).toHaveValue("2486.20");
    await show(dialog, "Paydays");
    await userEvent.selectOptions(within(dialog).getByLabelText("First payday of the month"), "5th");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "PUT /api/income-sources/1")[0]).toMatchObject({
      primary: true,
      schedule: { frequency: "semi_monthly", day1: 5, day2: 15, startDate: "2026-01-01" },
      deductions: [{ name: "401(k)", amountCents: 15000, toAccountId: 2 }],
    });
  });

  it("shows the backend's reason when a source cannot be deleted", async () => {
    withIncome({
      "DELETE /api/income-sources/1": () => ({ status: 409, error: "This source defines your pay periods." }),
    });
    renderApp("/income/1");

    await userEvent.click(await screen.findByRole("button", { name: "Edit income source" }));
    await show(screen.getByRole("dialog", { name: "Edit income source" }), "Delete");
    await userEvent.click(screen.getByRole("button", { name: "Delete income source…" }));
    expect(screen.getByRole("button", { name: "Keep it" })).toHaveFocus();
    await userEvent.click(screen.getByRole("button", { name: "Yes, delete it" }));

    expect(await within(screen.getByRole("alertdialog")).findByRole("alert"))
      .toHaveTextContent("This source defines your pay periods.");
  });
});

const Q3_BONUS: OneOffPaycheck = {
  id: 4,
  incomeSourceId: 1,
  payDate: "2026-09-30",
  name: "Q3 bonus",
  grossCents: 200000,
  netCents: 131200,
  deductions: [{ name: "401(k)", amountCents: 12000, toAccountId: 2, percentBp: null }],
  matched: true,
  matchCents: 6000,
  payStubId: null,
};
const MATCHED: IncomeSource = {
  ...SALARY,
  match: { toAccountId: 2, mode: "formula", fixedCents: null, matchBp: 5000, capBpOfGross: 600, contributionWithinCap: false },
  matchFigures: { perPaycheckCents: 7500, mostPossibleCents: 10800, contributeMoreCents: 3300 },
};

describe("one-off paychecks", () => {
  it("says what one is for while there are none", async () => {
    withIncome();
    renderApp("/income/1");

    const card = await screen.findByRole("region", { name: "One-off paychecks" });
    expect(await within(card).findByText(/^None yet\. A bonus/)).toBeInTheDocument();
  });

  it("lists each with its day, take-home and match, and a pencil of its own", async () => {
    withIncome({ "GET /api/income-sources/1/one-off-paychecks": () => ({ status: 200, data: [Q3_BONUS] }) });
    renderApp("/income/1");

    const card = await screen.findByRole("region", { name: "One-off paychecks" });
    const row = await within(card).findByRole("listitem");
    expect(row).toHaveTextContent("Q3 bonus");
    expect(row).toHaveTextContent("Sep 30, 2026 · $1,312.00 take-home · $60.00 match");
    expect(within(row).getByRole("button", { name: "Edit Q3 bonus" })).toBeInTheDocument();
  });

  it("adds a bonus, starting from the regular paycheck's deductions", async () => {
    const fetchMock = withIncome({
      "GET /api/income-sources": () => ({ status: 200, data: [MATCHED, BAKERY] }),
      "POST /api/income-sources/1/one-off-paychecks": () => ({ status: 201, data: Q3_BONUS }),
    });
    renderApp("/income/1");

    const card = await screen.findByRole("region", { name: "One-off paychecks" });
    await userEvent.click(within(card).getByRole("button", { name: "+ Add" }));
    const dialog = screen.getByRole("dialog", { name: "Add a one-off paycheck" });
    expect(within(dialog).getByRole("status")).toHaveTextContent("Step 1 of 4");
    await userEvent.type(within(dialog).getByLabelText("Name"), "Q3 bonus");
    const paidOn = within(dialog).getByLabelText("Paid on");
    await userEvent.clear(paidOn);
    await userEvent.type(paidOn, "2026-09-30");
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    await userEvent.type(within(dialog).getByLabelText("Take-home"), "1312");
    await userEvent.type(within(dialog).getByLabelText("Gross (optional)"), "2000");
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    // A copy of the regular paycheck's, to be looked at rather than saved unseen.
    expect(within(dialog).getByText(/^Copied from your regular paycheck\./)).toBeInTheDocument();
    const amount = within(dialog).getByLabelText("Amount");
    expect(amount).toHaveValue("150.00");
    await userEvent.clear(amount);
    await userEvent.type(amount, "120");
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    // The source's own rule, on by default.
    expect(within(dialog).getByLabelText("Add the employer's match")).toBeChecked();
    expect(dialog).toHaveTextContent("By Northwind Health's rule: 50.00% of what you put in, up to 6.00% of this paycheck's gross.");
    // The gross was given, so there is no warning about a missing one.
    expect(dialog).not.toHaveTextContent("Without a gross amount");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add paycheck" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "POST /api/income-sources/1/one-off-paychecks")).toEqual([
      {
        payDate: "2026-09-30",
        name: "Q3 bonus",
        grossCents: 200000,
        netCents: 131200,
        deductions: [{ name: "401(k)", amountCents: 12000, toAccountId: 2 }],
        matched: true,
      },
    ]);
  });

  it("asks for a name before going on", async () => {
    const fetchMock = withIncome();
    renderApp("/income/1");

    const card = await screen.findByRole("region", { name: "One-off paychecks" });
    await userEvent.click(within(card).getByRole("button", { name: "+ Add" }));
    const dialog = screen.getByRole("dialog", { name: "Add a one-off paycheck" });
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    expect(within(dialog).getByRole("status")).toHaveTextContent("Step 1 of 4");
    expect(calls(fetchMock).some((call) => call.startsWith("POST "))).toBe(false);
  });

  it("says there is no match to add when the source has none", async () => {
    withIncome({
      "GET /api/income-sources/1/one-off-paychecks": () => ({ status: 200, data: [{ ...Q3_BONUS, matched: false, matchCents: null }] }),
    });
    renderApp("/income/1");

    await userEvent.click(await screen.findByRole("button", { name: "Edit Q3 bonus" }));
    const dialog = screen.getByRole("dialog", { name: "Edit Q3 bonus" });
    await show(dialog, "Employer match");
    expect(dialog).toHaveTextContent("Northwind Health has no employer match set up, so nothing is added to this paycheck.");
    expect(within(dialog).queryByRole("checkbox")).not.toBeInTheDocument();
  });

  it("switches the match off for one bonus", async () => {
    const fetchMock = withIncome({
      "GET /api/income-sources": () => ({ status: 200, data: [MATCHED, BAKERY] }),
      "GET /api/income-sources/1/one-off-paychecks": () => ({ status: 200, data: [Q3_BONUS] }),
      "PUT /api/income-sources/1/one-off-paychecks/4": () => ({ status: 200, data: { ...Q3_BONUS, matched: false, matchCents: null } }),
    });
    renderApp("/income/1");

    await userEvent.click(await screen.findByRole("button", { name: "Edit Q3 bonus" }));
    const dialog = screen.getByRole("dialog", { name: "Edit Q3 bonus" });
    const change = within(dialog).getByRole("combobox", { name: "What to change" });
    expect(within(change).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "Name and date",
      "Pay",
      "Deductions",
      "Employer match",
      "Delete",
    ]);
    await show(dialog, "Employer match");
    expect(dialog).toHaveTextContent("As saved, that comes to $60.00.");
    await userEvent.click(within(dialog).getByLabelText("Add the employer's match"));
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "PUT /api/income-sources/1/one-off-paychecks/4")).toEqual([
      {
        payDate: "2026-09-30",
        name: "Q3 bonus",
        grossCents: 200000,
        netCents: 131200,
        deductions: [{ name: "401(k)", amountCents: 12000, toAccountId: 2 }],
        matched: false,
      },
    ]);
  });

  it("deletes one, asking first", async () => {
    const fetchMock = withIncome({
      "GET /api/income-sources/1/one-off-paychecks": () => ({ status: 200, data: [Q3_BONUS] }),
      "DELETE /api/income-sources/1/one-off-paychecks/4": () => ({ status: 200, data: null }),
    });
    renderApp("/income/1");

    await userEvent.click(await screen.findByRole("button", { name: "Edit Q3 bonus" }));
    await show(screen.getByRole("dialog", { name: "Edit Q3 bonus" }), "Delete");
    await userEvent.click(screen.getByRole("button", { name: "Delete this paycheck…" }));
    expect(screen.getByRole("alertdialog")).toHaveTextContent("Delete Q3 bonus from Sep 30, 2026?");
    await userEvent.click(screen.getByRole("button", { name: "Yes, delete it" }));

    await waitFor(() => expect(calls(fetchMock)).toContain("DELETE /api/income-sources/1/one-off-paychecks/4"));
  });

  it("shows a one-off among the upcoming paychecks by its name, without typing over its amount", async () => {
    const bonus: Paycheck = { ...PAYCHECKS[0]!, scheduledDate: "2026-10-30", oneOffId: 4, name: "Q3 bonus", netCents: 131200 };
    withIncome({ "GET /api/paychecks": () => ({ status: 200, data: [bonus, PAYCHECKS[0]!] }) });
    renderApp("/income/1");

    const upcoming = await screen.findByRole("region", { name: "Upcoming paychecks" });
    const [first, second] = await within(upcoming).findAllByRole("listitem");
    expect(first).toHaveTextContent("Q3 bonus · Northwind Health");
    expect(within(first!).queryByRole("button")).not.toBeInTheDocument();
    expect(within(second!).getByRole("button", { name: /^Change the amount for Northwind Health,/ })).toBeInTheDocument();
  });
});
