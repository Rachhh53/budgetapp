import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Account } from "../../src/api/accounts";
import type { IncomeSource, Paycheck } from "../../src/api/income";
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
  deductions: [{ name: "401(k)", amountCents: 15000, toAccountId: 2 }],
};
const BAKERY: IncomeSource = {
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

  it("shows each source with its schedule and where the money goes", async () => {
    withIncome();
    renderApp("/income");

    const salary = await screen.findByRole("region", { name: "Northwind Health" });
    expect(within(salary).getByText("Primary · defines pay periods")).toBeInTheDocument();
    expect(salary).toHaveTextContent("Twice a month · 1st & 15th");
    expect(salary).toHaveTextContent("Previous business day");
    expect(salary).toHaveTextContent("Everyday Checking");
    expect(salary).toHaveTextContent("$3,600.00 · $2,486.20");
    expect(salary).toHaveTextContent("401(k) → Retirement");
    expect(salary).toHaveTextContent("$150.00");

    const bakery = screen.getByRole("region", { name: "Riverside Bakery" });
    expect(within(bakery).queryByText(/Primary/)).not.toBeInTheDocument();
    expect(bakery).toHaveTextContent("Every 2 weeks · Fridays");
    // The varying amount is marked as an estimate in words, not just by its blue box.
    expect(bakery).toHaveTextContent("$318.40 (estimate)");
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
    await userEvent.selectOptions(within(dialog).getByLabelText("How often"), "Twice a month");
    await userEvent.selectOptions(within(dialog).getByLabelText("Second payday of the month"), "Last day");
    await userEvent.type(within(dialog).getByLabelText("Take-home per paycheck"), "2,486.20");
    await userEvent.type(within(dialog).getByLabelText("Gross per paycheck (optional)"), "3600");
    await userEvent.click(within(dialog).getByRole("button", { name: "+ Add a deduction" }));
    await userEvent.type(within(dialog).getByRole("combobox", { name: "Deduction 1" }), "401{Enter}");
    await userEvent.type(within(dialog).getByLabelText("Amount"), "150");
    await userEvent.type(within(dialog).getByRole("combobox", { name: "Goes into (optional)" }), "retire{Enter}");
    const primary = within(dialog).getByLabelText("This paycheck sets my pay periods");
    expect(primary).toBeChecked();
    expect(primary).toBeDisabled();
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
      },
    ]);
  });

  it("adds a second job paid every two weeks, as an estimate", async () => {
    const fetchMock = withIncome({ "POST /api/income-sources": () => ({ status: 201, data: BAKERY }) });
    renderApp("/income");
    await screen.findByRole("region", { name: "Northwind Health" });

    await userEvent.click(screen.getByRole("button", { name: "+ Add income source" }));
    const dialog = screen.getByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText("Name"), "Riverside Bakery");
    await userEvent.type(within(dialog).getByRole("combobox", { name: "Deposited to" }), "every{Enter}");
    await userEvent.selectOptions(within(dialog).getByLabelText("On a weekend or holiday"), "No change");
    await userEvent.type(within(dialog).getByLabelText("Take-home per paycheck"), "318.40");
    await userEvent.click(within(dialog).getByLabelText("Estimate, not final yet"));
    // A later source is not primary unless asked, and may be.
    expect(within(dialog).getByLabelText("This paycheck sets my pay periods")).toBeEnabled();
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
    await screen.findByRole("region", { name: "Northwind Health" });

    await userEvent.click(screen.getByRole("button", { name: "+ Add income source" }));
    const dialog = screen.getByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText("Name"), "Side work");
    await userEvent.type(within(dialog).getByLabelText("Take-home per paycheck"), "100");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add income source" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please choose the account this pay is deposited to.");

    await userEvent.type(within(dialog).getByRole("combobox", { name: "Deposited to" }), "every{Enter}");
    await userEvent.selectOptions(within(dialog).getByLabelText("How often"), "On dates I pick");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add income source" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please add at least two paydays");

    expect(calls(fetchMock)).not.toContain("POST /api/income-sources");
  });

  it("edits a source, keeping the primary one primary", async () => {
    const fetchMock = withIncome({ "PUT /api/income-sources/1": () => ({ status: 200, data: SALARY }) });
    renderApp("/income");

    await userEvent.click(await screen.findByRole("button", { name: "Edit Northwind Health" }));
    const dialog = screen.getByRole("dialog", { name: "Edit income source" });
    expect(within(dialog).getByLabelText("Take-home per paycheck")).toHaveValue("2486.20");
    expect(within(dialog).getByRole("combobox", { name: "Deposited to" })).toHaveValue("Everyday Checking");
    expect(within(dialog).getByLabelText("This paycheck sets my pay periods")).toBeDisabled();
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
    renderApp("/income");

    await userEvent.click(await screen.findByRole("button", { name: "Edit Northwind Health" }));
    await userEvent.click(screen.getByRole("button", { name: "Delete income source…" }));
    expect(screen.getByRole("button", { name: "Keep it" })).toHaveFocus();
    await userEvent.click(screen.getByRole("button", { name: "Yes, delete it" }));

    expect(await within(screen.getByRole("alertdialog")).findByRole("alert"))
      .toHaveTextContent("This source defines your pay periods.");
  });
});
