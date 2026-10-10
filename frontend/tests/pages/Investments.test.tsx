import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Account, AccountDetail, AccountPerformance } from "../../src/api/accounts";
import type { IncomeSource } from "../../src/api/income";
import { bodiesSentTo, calls, fakeBackend, renderApp } from "../helpers";

const CHECKING: Account = {
  id: 1,
  name: "Everyday Checking",
  institution: "Coastal Credit Union",
  type: "checking",
  subtypeLabel: null,
  color: "#0f766e",
  archived: false,
  lowBalanceThresholdCents: null,
  latestBalance: null,
};
const RETIREMENT: Account = {
  ...CHECKING,
  id: 2,
  name: "Brightwater 401(k)",
  institution: "Harbor Investments",
  type: "investment",
  subtypeLabel: "401(k)",
  color: "#7e22ce",
  latestBalance: { id: 31, date: "2026-10-01", amountCents: 4000000, note: null },
};

const RETIREMENT_DETAIL: AccountDetail = {
  account: RETIREMENT,
  balances: [
    { id: 31, date: "2026-10-01", amountCents: 4000000, note: null },
    { id: 30, date: "2026-09-01", amountCents: 3900000, note: null },
  ],
  interest: { creditDay: 31, destination: "unassigned", destinationBucketId: null },
  rates: [{ id: 4, kind: "growth", rateBp: 700, effectiveDate: "2026-01-01" }],
};

const PERFORMANCE: AccountPerformance = {
  thisYear: { since: "2026-09-01", startCents: 3900000, nowCents: 4022500, changeCents: 122500, depositsCents: 67500, growthCents: 55000 },
  balances: [
    { entryId: 31, date: "2026-10-01", amountCents: 4000000, changeCents: 100000, depositsCents: 45000, growthCents: 55000 },
    { entryId: 30, date: "2026-09-01", amountCents: 3900000, changeCents: null, depositsCents: null, growthCents: null },
  ],
};

const SALARY: IncomeSource = {
  id: 1,
  name: "Brightwater Health",
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
  match: { toAccountId: 2, mode: "formula", fixedCents: null, matchBp: 5000, capBpOfGross: 600 },
  matchFigures: { perPaycheckCents: 7500, mostPossibleCents: 10800, contributeMoreCents: 6600 },
};
const AT_THE_CAP: IncomeSource = {
  ...SALARY,
  deductions: [{ name: "401(k)", amountCents: 21600, toAccountId: 2 }],
  matchFigures: { perPaycheckCents: 10800, mostPossibleCents: 10800, contributeMoreCents: 0 },
};

function withInvestments(overrides: Parameters<typeof fakeBackend>[0] = {}) {
  return fakeBackend({
    "GET /api/accounts": () => ({ status: 200, data: [CHECKING, RETIREMENT] }),
    "GET /api/accounts/2": () => ({ status: 200, data: RETIREMENT_DETAIL }),
    "GET /api/accounts/2/performance": () => ({ status: 200, data: PERFORMANCE }),
    "GET /api/income-sources": () => ({ status: 200, data: [SALARY] }),
    ...overrides,
  });
}

describe("investment account screen", () => {
  it("shows the change this year, split into what was put in and growth", async () => {
    withInvestments();
    renderApp("/accounts/2");

    expect(await screen.findByText("Change this year")).toBeInTheDocument();
    expect(screen.getByText("+$1,225.00")).toBeInTheDocument();
    expect(screen.getByText(/since Sep 1, 2026/)).toHaveTextContent("+$675.00 put in, +$550.00 growth");
  });

  it("lists what every paycheck puts in, the employer's match included", async () => {
    withInvestments();
    renderApp("/accounts/2");

    const going = await screen.findByRole("region", { name: "Going in every paycheck" });
    const rows = await within(going).findAllByRole("listitem");
    expect(rows[0]).toHaveTextContent("401(k) · Brightwater Health");
    expect(rows[0]).toHaveTextContent("$150.00");
    expect(rows[1]).toHaveTextContent("Employer match · Brightwater Health");
    expect(rows[1]).toHaveTextContent("$75.00");
    expect(rows[2]).toHaveTextContent("Each paycheck");
    expect(rows[2]).toHaveTextContent("$225.00");
    expect(within(going).getByRole("link", { name: "Income" })).toHaveAttribute("href", "/income");
  });

  it("points to Income when nothing from a paycheck goes in yet", async () => {
    withInvestments({ "GET /api/income-sources": () => ({ status: 200, data: [] }) });
    renderApp("/accounts/2");

    const going = await screen.findByRole("region", { name: "Going in every paycheck" });
    expect(await within(going).findByText(/Nothing from a paycheck goes into this account yet/)).toBeInTheDocument();
  });

  it("always calls the growth rate an assumption, never a prediction", async () => {
    withInvestments();
    renderApp("/accounts/2");

    const growth = await screen.findByRole("region", { name: "Assumed growth" });
    expect(within(growth).getByText("7.00%")).toBeInTheDocument();
    expect(growth).toHaveTextContent("a year, since Jan 1, 2026");
    expect(growth).toHaveTextContent("An assumption you chose, not a prediction.");
    expect(growth).toHaveTextContent("Gains and losses are only ever worked out from balances you entered.");
    // An investment has no buckets or savings interest.
    expect(screen.queryByRole("region", { name: "Interest" })).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Buckets" })).not.toBeInTheDocument();
  });

  it("invites a rate when none is assumed", async () => {
    withInvestments({ "GET /api/accounts/2": () => ({ status: 200, data: { ...RETIREMENT_DETAIL, rates: [] } }) });
    renderApp("/accounts/2");

    const growth = await screen.findByRole("region", { name: "Assumed growth" });
    expect(growth).toHaveTextContent("No growth is assumed, so the projection only adds what is put in.");
    expect(within(growth).getByRole("button", { name: "Add an assumed rate" })).toBeInTheDocument();
  });

  it("changes the assumed rate from a new date, keeping the old one in the history", async () => {
    const fetchMock = withInvestments({
      "POST /api/accounts/2/rates": () => ({ status: 201, data: RETIREMENT_DETAIL, changed: true }),
    });
    renderApp("/accounts/2");

    const growth = await screen.findByRole("region", { name: "Assumed growth" });
    await userEvent.click(within(growth).getByRole("button", { name: "Edit assumed growth" }));
    const dialog = screen.getByRole("dialog", { name: "Assumed growth — Brightwater 401(k)" });
    const rate = within(dialog).getByLabelText("Growth a year (%)");
    expect(rate).toHaveValue("7.00");
    await userEvent.clear(rate);
    await userEvent.type(rate, "5.5");
    const from = within(dialog).getByLabelText("Applies from");
    await userEvent.clear(from);
    await userEvent.type(from, "2026-10-01");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "POST /api/accounts/2/rates")).toEqual([{ rateBp: 550, effectiveDate: "2026-10-01" }]);
    // A different rate is a change of mind, not a correction: the old entry is not removed.
    expect(calls(fetchMock)).not.toContain("DELETE /api/accounts/2/rates/4");
  });

  it("asks again kindly for a rate that is not a rate", async () => {
    const fetchMock = withInvestments();
    renderApp("/accounts/2");

    const growth = await screen.findByRole("region", { name: "Assumed growth" });
    await userEvent.click(within(growth).getByRole("button", { name: "Edit assumed growth" }));
    const dialog = screen.getByRole("dialog");
    await userEvent.clear(within(dialog).getByLabelText("Growth a year (%)"));
    await userEvent.type(within(dialog).getByLabelText("Growth a year (%)"), "seven");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please enter a rate like 6.00.");
    expect(calls(fetchMock)).not.toContain("POST /api/accounts/2/rates");
  });

  it("sets each balance against the one before: change, what was put in, and growth", async () => {
    withInvestments();
    renderApp("/accounts/2");

    const entered = await screen.findByRole("region", { name: "Balances you entered" });
    await within(entered).findByText("+$550.00");
    const rows = within(entered).getAllByRole("listitem");
    expect(rows[0]).toHaveTextContent("Oct 1, 2026");
    expect(rows[0]).toHaveTextContent("+$1,000.00");
    expect(rows[0]).toHaveTextContent("+$450.00");
    expect(rows[0]).toHaveTextContent("Growth: the change less what was put in");
    expect(rows[0]).toHaveTextContent("+$550.00");
    // The first balance has nothing before it to be set against.
    expect(rows[1]).not.toHaveTextContent("+");
  });

  it("shows a loss as a loss", async () => {
    const down: AccountPerformance = {
      thisYear: { ...PERFORMANCE.thisYear!, nowCents: 3920000, changeCents: 20000, growthCents: -47500 },
      balances: [{ ...PERFORMANCE.balances[0]!, changeCents: 20000, growthCents: -25000 }, PERFORMANCE.balances[1]!],
    };
    withInvestments({ "GET /api/accounts/2/performance": () => ({ status: 200, data: down }) });
    renderApp("/accounts/2");

    const entered = await screen.findByRole("region", { name: "Balances you entered" });
    expect(await within(entered).findByText("-$250.00")).toBeInTheDocument();
  });
});

describe("income screen: employer match", () => {
  it("shows the match and, under the cap, what a little more would earn", async () => {
    withInvestments();
    renderApp("/income");

    const salary = await screen.findByRole("region", { name: "Brightwater Health" });
    expect(salary).toHaveTextContent("50.00% of what you put in, up to 6.00% of gross pay → Brightwater 401(k)");
    const hint = within(salary).getByRole("status");
    expect(hint).toHaveTextContent("Under the cap.");
    expect(hint).toHaveTextContent("Putting in $66.00 more each paycheck would earn the whole match of $108.00: $33.00 more from your employer every time.");
  });

  it("says nothing more once the whole match is being earned", async () => {
    withInvestments({ "GET /api/income-sources": () => ({ status: 200, data: [AT_THE_CAP] }) });
    renderApp("/income");

    const salary = await screen.findByRole("region", { name: "Brightwater Health" });
    expect(salary).toHaveTextContent("$108.00");
    expect(within(salary).queryByRole("status")).not.toBeInTheDocument();
  });

  it("sets up a match as a share of what is put in, paid into an investment account", async () => {
    const noMatch: IncomeSource = { ...SALARY, match: null, matchFigures: null };
    const fetchMock = withInvestments({
      "GET /api/income-sources": () => ({ status: 200, data: [noMatch] }),
      "PUT /api/income-sources/1": () => ({ status: 200, data: SALARY, changed: true }),
    });
    renderApp("/income");

    await userEvent.click(await screen.findByRole("button", { name: "Edit Brightwater Health" }));
    const dialog = screen.getByRole("dialog", { name: "Edit income source" });
    const match = within(dialog).getByRole("group", { name: "Employer match" });
    expect(within(match).getByLabelText("Does your employer add anything?")).toHaveValue("none");
    await userEvent.selectOptions(within(match).getByLabelText("Does your employer add anything?"), "A share of what I put in");
    // Checking is not offered: a match only goes into an investment account.
    await userEvent.type(within(match).getByRole("combobox", { name: "Paid into" }), "every{Enter}");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please choose the investment account the employer match is paid into.");

    await userEvent.clear(within(match).getByRole("combobox", { name: "Paid into" }));
    await userEvent.type(within(match).getByRole("combobox", { name: "Paid into" }), "bright{Enter}");
    await userEvent.type(within(match).getByLabelText("They match (%)"), "50");
    await userEvent.type(within(match).getByLabelText("Up to (% of gross pay)"), "6");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "PUT /api/income-sources/1")[0]).toMatchObject({
      grossCents: 360000,
      match: { toAccountId: 2, mode: "formula", fixedCents: null, matchBp: 5000, capBpOfGross: 600 },
    });
  });

  it("sets up a match that is the same amount every paycheck, and takes one off again", async () => {
    const fetchMock = withInvestments({ "PUT /api/income-sources/1": () => ({ status: 200, data: SALARY, changed: true }) });
    renderApp("/income");

    await userEvent.click(await screen.findByRole("button", { name: "Edit Brightwater Health" }));
    let dialog = screen.getByRole("dialog", { name: "Edit income source" });
    let match = within(dialog).getByRole("group", { name: "Employer match" });
    // The form opens on the match as it is.
    expect(within(match).getByLabelText("Does your employer add anything?")).toHaveValue("formula");
    expect(within(match).getByLabelText("They match (%)")).toHaveValue("50.00");
    await userEvent.selectOptions(within(match).getByLabelText("Does your employer add anything?"), "The same amount every paycheck");
    await userEvent.type(within(match).getByRole("textbox", { name: "Match per paycheck" }), "75");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "PUT /api/income-sources/1")[0]).toMatchObject({
      match: { toAccountId: 2, mode: "fixed", fixedCents: 7500, matchBp: null, capBpOfGross: null },
    });

    await userEvent.click(screen.getByRole("button", { name: "Edit Brightwater Health" }));
    dialog = screen.getByRole("dialog", { name: "Edit income source" });
    match = within(dialog).getByRole("group", { name: "Employer match" });
    await userEvent.selectOptions(within(match).getByLabelText("Does your employer add anything?"), "No match");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/income-sources/1")).toHaveLength(2));
    expect(bodiesSentTo(fetchMock, "PUT /api/income-sources/1")[1]).toMatchObject({ match: null });
  });

  it("needs the gross amount before a match can be worked out from pay", async () => {
    const takeHomeOnly: IncomeSource = { ...SALARY, grossCents: null, match: null, matchFigures: null };
    const fetchMock = withInvestments({ "GET /api/income-sources": () => ({ status: 200, data: [takeHomeOnly] }) });
    renderApp("/income");

    await userEvent.click(await screen.findByRole("button", { name: "Edit Brightwater Health" }));
    const dialog = screen.getByRole("dialog", { name: "Edit income source" });
    const match = within(dialog).getByRole("group", { name: "Employer match" });
    await userEvent.selectOptions(within(match).getByLabelText("Does your employer add anything?"), "A share of what I put in");
    await userEvent.type(within(match).getByRole("combobox", { name: "Paid into" }), "bright{Enter}");
    await userEvent.type(within(match).getByLabelText("They match (%)"), "50");
    await userEvent.type(within(match).getByLabelText("Up to (% of gross pay)"), "6");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    expect(within(dialog).getByRole("alert")).toHaveTextContent(
      "A match worked out from your pay needs the gross amount of a paycheck. Please fill that in as well.",
    );
    expect(calls(fetchMock)).not.toContain("PUT /api/income-sources/1");
  });
});
