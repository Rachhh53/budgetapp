import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Account } from "../../src/api/accounts";
import type { IncomeSource, PayTerms } from "../../src/api/income";
import { todayIso } from "../../src/lib/dates";
import { bodiesSentTo, calls, fakeBackend, renderApp } from "../helpers";

const CHECKING: Account = {
  id: 1,
  name: "Everyday Checking",
  institution: "Coastal Credit Union",
  type: "checking",
  subtypeLabel: null,
  color: "#0f766e",
  archived: false, archivedOn: null,
  lowBalanceThresholdCents: null,
  latestBalance: null,
};

const START: PayTerms = {
  id: null,
  effectiveDate: null,
  grossCents: 360000,
  netCents: 241800,
  netIsEstimate: false,
  deductions: [],
  match: null,
  matchFigures: null,
  current: true,
};
const RAISE: PayTerms = { ...START, id: 7, effectiveDate: "2027-06-01", grossCents: 400000, netCents: 270000, current: false };

const SALARY: IncomeSource = {
  id: 1,
  name: "Brightwater Health",
  depositAccountId: 1,
  grossCents: 360000,
  netCents: 241800,
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
  payHistory: [START],
};
const WITH_RAISE: IncomeSource = { ...SALARY, payHistory: [START, RAISE] };

function withSalary(source: IncomeSource, overrides: Parameters<typeof fakeBackend>[0] = {}) {
  return fakeBackend({
    "GET /api/accounts": () => ({ status: 200, data: [CHECKING] }),
    "GET /api/income-sources": () => ({ status: 200, data: [source] }),
    "PUT /api/income-sources/1": () => ({ status: 200, data: source, changed: true }),
    ...overrides,
  });
}

/** Brings up one screen of the income form, which shows a group of fields at a time so it never scrolls. */
const show = (dialog: HTMLElement, group: string) =>
  userEvent.selectOptions(within(dialog).getByRole("combobox", { name: "What to change" }), group);

describe("a raise: pay that changes from a date", () => {
  it("records a pay change from a chosen date, leaving earlier paychecks alone", async () => {
    const fetchMock = withSalary(SALARY);
    renderApp("/income/1");

    await userEvent.click(await screen.findByRole("button", { name: "Record a pay change for Brightwater Health" }));
    // A form of its own: the date and the new pay, and nothing about the name or the paydays.
    const dialog = screen.getByRole("dialog", { name: "Record a pay change" });
    expect(within(dialog).queryByLabelText("Name")).not.toBeInTheDocument();
    expect(within(dialog).queryByRole("combobox", { name: "What to change" })).not.toBeInTheDocument();
    // Today to begin with; any day, past or future, can be chosen.
    expect(within(dialog).getByLabelText("Changes from")).toHaveValue(todayIso());
    fireEvent.change(within(dialog).getByLabelText("Changes from"), { target: { value: "2027-06-01" } });
    // The form opens on today's amounts, ready to be typed over.
    const takeHome = within(dialog).getByRole("textbox", { name: "Take-home per paycheck" });
    expect(takeHome).toHaveValue("2418.00");
    await userEvent.clear(takeHome);
    await userEvent.type(takeHome, "2700");
    // New deductions or a new match from that date are a step further, for those who need them.
    expect(within(dialog).getByRole("button", { name: "Deductions and match" })).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "Save pay change" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "PUT /api/income-sources/1")[0]).toMatchObject({ netCents: 270000, effectiveFrom: "2027-06-01" });
  });

  it("asks for the date a pay change starts from", async () => {
    const fetchMock = withSalary(SALARY);
    renderApp("/income/1");

    await userEvent.click(await screen.findByRole("button", { name: "Record a pay change for Brightwater Health" }));
    const dialog = screen.getByRole("dialog", { name: "Record a pay change" });
    fireEvent.change(within(dialog).getByLabelText("Changes from"), { target: { value: "" } });
    await userEvent.click(within(dialog).getByRole("button", { name: "Save pay change" }));

    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please choose the date your pay changes from.");
    expect(calls(fetchMock)).not.toContain("PUT /api/income-sources/1");
  });

  it("a plain edit is a correction, and says which paychecks it reaches", async () => {
    const fetchMock = withSalary(SALARY);
    renderApp("/income/1");

    await userEvent.click(await screen.findByRole("button", { name: "Edit income source" }));
    const dialog = screen.getByRole("dialog", { name: "Edit income source" });
    await show(dialog, "Pay");
    expect(within(dialog).getByText(/Saving corrects every paycheck, past and future\. For a raise/)).toHaveTextContent("Record a pay change");
    expect(within(dialog).queryByLabelText("Changes from")).not.toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const sent = bodiesSentTo(fetchMock, "PUT /api/income-sources/1")[0];
    expect(sent).not.toHaveProperty("effectiveFrom");
    expect(sent).not.toHaveProperty("originalAmounts");
  });

  it("does not ask when the amounts apply while adding a new source", async () => {
    fakeBackend({ "GET /api/accounts": () => ({ status: 200, data: [CHECKING] }) });
    renderApp("/income");

    await userEvent.click(await screen.findByRole("button", { name: "+ Add income source" }));

    const dialog = screen.getByRole("dialog", { name: "Add an income source" });
    await userEvent.type(within(dialog).getByLabelText("Name"), "Brightwater Health");
    await userEvent.type(within(dialog).getByRole("combobox", { name: "Deposited to" }), "every{Enter}");
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    expect(within(dialog).getByLabelText("Take-home per paycheck")).toBeInTheDocument();
    expect(within(dialog).queryByLabelText("Changes from")).not.toBeInTheDocument();
    expect(within(dialog).queryByText(/Saving corrects/)).not.toBeInTheDocument();
  });

  it("keeps pay history off the Income overview", async () => {
    withSalary(WITH_RAISE);
    renderApp("/income");

    await screen.findByRole("link", { name: /Brightwater Health/ });
    expect(screen.queryByRole("heading", { name: "Pay history" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Record a pay change/ })).not.toBeInTheDocument();
  });

  it("before any change of pay, the history has the starting amounts and says how to record a raise", async () => {
    withSalary(SALARY);
    renderApp("/income/1");

    const history = await screen.findByRole("region", { name: "Pay history" });
    expect(within(history).getAllByRole("listitem")).toHaveLength(1);
    expect(history).toHaveTextContent("From the start · in use now");
    expect(history).toHaveTextContent("No pay changes yet. When you get a raise, or your deductions or match change, choose Record a pay change");
  });

  it("lists the pay history newest first, saying what is in use and what is still to come", async () => {
    withSalary(WITH_RAISE);
    renderApp("/income/1");

    const history = await screen.findByRole("region", { name: "Pay history" });
    const rows = within(history).getAllByRole("listitem");
    expect(rows).toHaveLength(2);
    const [raise, start] = rows as [HTMLElement, HTMLElement];
    expect(raise).toHaveTextContent("From Jun 1, 2027 · still to come");
    expect(raise).toHaveTextContent("$4,000.00 gross · $2,700.00 take-home a paycheck");
    expect(start).toHaveTextContent("From the start · in use now");
    // Only a dated change can be removed; the amounts it started with are the source itself.
    expect(within(start).queryByRole("button", { name: /Remove/ })).not.toBeInTheDocument();
  });

  it("opens one entry of the history on its own amounts and saves back to that entry", async () => {
    const fetchMock = withSalary(WITH_RAISE);
    renderApp("/income/1");

    await userEvent.click(await screen.findByRole("button", { name: "Edit the pay from Jun 1, 2027" }));
    let dialog = screen.getByRole("dialog", { name: "Edit the pay from Jun 1, 2027" });
    expect(within(dialog).getByText(/The amounts from Jun 1, 2027 on/)).toBeInTheDocument();
    // Which entry it is has been decided already, and only its amounts are asked: pay, deductions, match.
    expect(within(dialog).queryByLabelText("Changes from")).not.toBeInTheDocument();
    expect(within(dialog).getAllByRole("radio").map((radio) => radio.parentElement?.textContent)).toEqual(["Pay", "Deductions", "Employer match"]);
    expect(within(dialog).getByRole("textbox", { name: "Take-home per paycheck" })).toHaveValue("2700.00");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "PUT /api/income-sources/1")[0]).toMatchObject({ netCents: 270000, effectiveFrom: "2027-06-01" });

    await userEvent.click(screen.getByRole("button", { name: "Edit the pay from the start" }));
    dialog = screen.getByRole("dialog", { name: "Edit the pay from the start" });
    expect(within(dialog).getByText(/The amounts this income started with/)).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/income-sources/1")).toHaveLength(2));
    expect(bodiesSentTo(fetchMock, "PUT /api/income-sources/1")[1]).toMatchObject({ netCents: 241800, originalAmounts: true });
  });

  it("removes a change of pay", async () => {
    const fetchMock = withSalary(WITH_RAISE, {
      "DELETE /api/income-sources/1/pay-changes/7": () => ({ status: 200, data: SALARY, changed: true }),
    });
    renderApp("/income/1");

    await userEvent.click(await screen.findByRole("button", { name: "Remove the pay change from Jun 1, 2027" }));

    await waitFor(() => expect(calls(fetchMock)).toContain("DELETE /api/income-sources/1/pay-changes/7"));
  });

  it("says so kindly when a change of pay cannot be removed", async () => {
    withSalary(WITH_RAISE, {
      "DELETE /api/income-sources/1/pay-changes/7": () => ({ status: 500, error: "Something went wrong on our side. Please try again." }),
    });
    renderApp("/income/1");

    await userEvent.click(await screen.findByRole("button", { name: "Remove the pay change from Jun 1, 2027" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Something went wrong on our side. Please try again.");
  });
});
