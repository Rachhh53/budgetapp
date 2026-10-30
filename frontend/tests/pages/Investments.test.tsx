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
  archived: false, archivedOn: null,
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
  latestBalance: { id: 31, date: "2026-10-01", amountCents: 4000000, note: null, endOfDay: true },
};

const RETIREMENT_DETAIL: AccountDetail = {
  account: RETIREMENT,
  balances: [
    { id: 31, date: "2026-10-01", amountCents: 4000000, note: null, endOfDay: true },
    { id: 30, date: "2026-09-01", amountCents: 3900000, note: null, endOfDay: true },
  ],
  interest: { creditDay: 31, destination: "unassigned", destinationBucketId: null },
  rates: [{ id: 4, kind: "growth", rateBp: 700, effectiveDate: "2026-01-01" }],
};

const PERFORMANCE: AccountPerformance = {
  thisYear: { since: "2026-09-01", startCents: 3900000, nowCents: 4022500, changeCents: 122500, depositsCents: 67500, growthCents: 55000 },
  balances: [
    { entryId: 31, date: "2026-10-01", amountCents: 4000000, changeCents: 100000, depositsCents: 45000, growthCents: 55000, recordsCents: null },
    { entryId: 30, date: "2026-09-01", amountCents: 3900000, changeCents: null, depositsCents: null, growthCents: null, recordsCents: null },
  ],
  // A month apart: too short to put as a rate a year.
  actualGrowth: { from: "2026-09-01", to: "2026-10-01", growthCents: 55000, assumedCents: 23100, yearlyRateBp: null },
};

const SALARY: IncomeSource = {
  payHistory: [],
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
  deductions: [{ name: "401(k)", amountCents: 15000, toAccountId: 2, percentBp: null }],
  match: { toAccountId: 2, mode: "formula", fixedCents: null, matchBp: 5000, capBpOfGross: 600, contributionWithinCap: false },
  matchFigures: { perPaycheckCents: 7500, mostPossibleCents: 10800, contributeMoreCents: 6600 },
};
const AT_THE_CAP: IncomeSource = {
  ...SALARY,
  deductions: [{ name: "401(k)", amountCents: 21600, toAccountId: 2, percentBp: null }],
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

/** Brings up one screen of the income form, which shows a group of fields at a time so it never scrolls. */
const show = (dialog: HTMLElement, group: string) =>
  userEvent.selectOptions(within(dialog).getByRole("combobox", { name: "What to change" }), group);

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
      ...PERFORMANCE,
      thisYear: { ...PERFORMANCE.thisYear!, nowCents: 3920000, changeCents: 20000, growthCents: -47500 },
      balances: [{ ...PERFORMANCE.balances[0]!, changeCents: 20000, growthCents: -25000 }, PERFORMANCE.balances[1]!],
    };
    withInvestments({ "GET /api/accounts/2/performance": () => ({ status: 200, data: down }) });
    renderApp("/accounts/2");

    const entered = await screen.findByRole("region", { name: "Balances you entered" });
    expect(await within(entered).findByText("-$250.00")).toBeInTheDocument();
  });
});

describe("investment account screen: actual growth", () => {
  /** The figure's own block: its label, the figure and the line under it. */
  const actualGrowth = async () => (await screen.findByText("Actual growth")).parentElement!;

  it("sets the rate it really grew at beside the one assumed", async () => {
    const sixMonths: AccountPerformance = {
      ...PERFORMANCE,
      actualGrowth: { from: "2026-04-01", to: "2026-10-01", growthCents: 162000, assumedCents: 131500, yearlyRateBp: 840 },
    };
    withInvestments({ "GET /api/accounts/2/performance": () => ({ status: 200, data: sixMonths }) });
    renderApp("/accounts/2");

    const figure = await actualGrowth();
    expect(figure).toHaveTextContent("+8.40%");
    expect(figure).toHaveTextContent(
      "a year, from the balances you entered Apr 1, 2026 to Oct 1, 2026 · +$1,620.00 growth, $1,315.00 assumed",
    );
    // The assumption stays beside it, as before.
    expect(screen.getByText("Assumed growth", { selector: "p" }).parentElement).toHaveTextContent("7.00%");
  });

  it("says a loss with its sign", async () => {
    const down: AccountPerformance = {
      ...PERFORMANCE,
      actualGrowth: { from: "2026-04-01", to: "2026-10-01", growthCents: -90000, assumedCents: 131500, yearlyRateBp: -450 },
    };
    withInvestments({ "GET /api/accounts/2/performance": () => ({ status: 200, data: down }) });
    renderApp("/accounts/2");

    const figure = await actualGrowth();
    expect(figure).toHaveTextContent("-4.50%");
    expect(figure).toHaveTextContent("-$900.00 growth");
  });

  it("gives the dollars alone for balances under three months apart, and says why", async () => {
    withInvestments();
    renderApp("/accounts/2");

    const figure = await actualGrowth();
    expect(figure).toHaveTextContent("+$550.00");
    expect(figure).toHaveTextContent("from the balances you entered Sep 1, 2026 to Oct 1, 2026, $231.00 assumed.");
    expect(figure).toHaveTextContent("Enter balances at least 3 months apart to see a yearly rate.");
    expect(figure).not.toHaveTextContent("%");
  });

  it("asks for another balance until there are two", async () => {
    withInvestments({ "GET /api/accounts/2/performance": () => ({ status: 200, data: { ...PERFORMANCE, actualGrowth: null } }) });
    renderApp("/accounts/2");

    expect(await actualGrowth()).toHaveTextContent("Enter another balance to see how it really grew.");
  });

  it("leaves out what was assumed when no growth is assumed", async () => {
    withInvestments({
      "GET /api/accounts/2": () => ({ status: 200, data: { ...RETIREMENT_DETAIL, rates: [] } }),
      "GET /api/accounts/2/performance": () => ({
        status: 200,
        data: { ...PERFORMANCE, actualGrowth: { ...PERFORMANCE.actualGrowth!, assumedCents: 0 } },
      }),
    });
    renderApp("/accounts/2");

    expect(await actualGrowth()).not.toHaveTextContent("assumed");
  });

  it("hides its dollar amounts with Hide amounts", async () => {
    withInvestments();
    renderApp("/accounts/2");

    const figure = await actualGrowth();
    const amounts = [...figure.querySelectorAll("[data-amount]")].map((amount) => amount.textContent);
    expect(amounts).toEqual(["+$550.00", "$231.00"]);
  });

  it("is not shown on a checking account", async () => {
    withInvestments({
      "GET /api/accounts/1": () => ({
        status: 200,
        data: { ...RETIREMENT_DETAIL, account: { ...CHECKING, latestBalance: RETIREMENT.latestBalance }, rates: [] },
      }),
      "GET /api/accounts/1/performance": () => ({ status: 200, data: { ...PERFORMANCE, actualGrowth: null } }),
    });
    renderApp("/accounts/1");

    expect(await screen.findByText("Change this year")).toBeInTheDocument();
    expect(screen.queryByText("Actual growth")).not.toBeInTheDocument();
  });
});

describe("income screen: employer match", () => {
  it("shows the match and, under the cap, what a little more would earn", async () => {
    withInvestments();
    renderApp("/income/1");

    const salary = await screen.findByRole("region", { name: "Employer match" });
    expect(salary).toHaveTextContent("50.00% of what you put in, up to 6.00% of gross pay → Brightwater 401(k)");
    const hint = within(salary).getByRole("status");
    expect(hint).toHaveTextContent("Under the cap.");
    expect(hint).toHaveTextContent("Putting in $66.00 more each paycheck would earn the whole match of $108.00: $33.00 more from your employer every time.");
  });

  it("says it cannot see the contribution, not that the match is unclaimed, when no deduction goes to the match's account", async () => {
    const unplaced: IncomeSource = {
      ...SALARY,
      deductions: SALARY.deductions.map((deduction) => ({ ...deduction, toAccountId: null })),
      matchFigures: { perPaycheckCents: 0, mostPossibleCents: 10800, contributeMoreCents: 21600 },
    };
    withInvestments({ "GET /api/income-sources": () => ({ status: 200, data: [unplaced] }) });
    renderApp("/income/1");

    const salary = await screen.findByRole("region", { name: "Employer match" });
    const hint = within(salary).getByRole("status");
    expect(hint).toHaveTextContent("Budget can't see what you put in. None of the deductions on this income go to Brightwater 401(k)");
    expect(salary).not.toHaveTextContent("Under the cap.");
  });

  it("says nothing more once the whole match is being earned", async () => {
    withInvestments({ "GET /api/income-sources": () => ({ status: 200, data: [AT_THE_CAP] }) });
    renderApp("/income/1");

    const salary = await screen.findByRole("region", { name: "Employer match" });
    expect(salary).toHaveTextContent("$108.00");
    expect(within(salary).queryByRole("status")).not.toBeInTheDocument();
  });

  it("sets up a match as a share of what is put in, paid into an investment account", async () => {
    const noMatch: IncomeSource = { ...SALARY, match: null, matchFigures: null };
    const fetchMock = withInvestments({
      "GET /api/income-sources": () => ({ status: 200, data: [noMatch] }),
      "PUT /api/income-sources/1": () => ({ status: 200, data: SALARY, changed: true }),
    });
    renderApp("/income/1");

    await userEvent.click(await screen.findByRole("button", { name: "Edit income source" }));
    const dialog = screen.getByRole("dialog", { name: "Edit income source" });
    await show(dialog, "Employer match");
    const match = within(dialog).getByRole("group", { name: "Employer match" });
    expect(within(match).getByLabelText("Does your employer add anything?")).toHaveValue("none");
    await userEvent.selectOptions(within(match).getByLabelText("Does your employer add anything?"), "A percentage of what I put in");
    // Checking is not offered: a match only goes into an investment account.
    await userEvent.type(within(match).getByRole("combobox", { name: "Paid into" }), "every{Enter}");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please choose the investment account the employer match is paid into.");

    await userEvent.clear(within(match).getByRole("combobox", { name: "Paid into" }));
    await userEvent.type(within(match).getByRole("combobox", { name: "Paid into" }), "bright{Enter}");
    await userEvent.type(within(match).getByLabelText("They match, %"), "50");
    await userEvent.type(within(match).getByLabelText("Up to, % of pay"), "6");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "PUT /api/income-sources/1")[0]).toMatchObject({
      grossCents: 360000,
      match: { toAccountId: 2, mode: "formula", fixedCents: null, matchBp: 5000, capBpOfGross: 600, contributionWithinCap: false },
    });
    // Gross pay is there to check against, so nobody is asked to confirm anything.
    expect(within(match).queryByRole("checkbox")).not.toBeInTheDocument();
  });

  it("sets up a match that is the same amount every paycheck, and takes one off again", async () => {
    const fetchMock = withInvestments({ "PUT /api/income-sources/1": () => ({ status: 200, data: SALARY, changed: true }) });
    renderApp("/income/1");

    await userEvent.click(await screen.findByRole("button", { name: "Edit income source" }));
    let dialog = screen.getByRole("dialog", { name: "Edit income source" });
    await show(dialog, "Employer match");
    let match = within(dialog).getByRole("group", { name: "Employer match" });
    // The form opens on the match as it is.
    expect(within(match).getByLabelText("Does your employer add anything?")).toHaveValue("formula");
    expect(within(match).getByLabelText("They match, %")).toHaveValue("50.00");
    await userEvent.selectOptions(within(match).getByLabelText("Does your employer add anything?"), "The same dollar amount every paycheck");
    await userEvent.type(within(match).getByRole("textbox", { name: "Match per paycheck" }), "75");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "PUT /api/income-sources/1")[0]).toMatchObject({
      match: { toAccountId: 2, mode: "fixed", fixedCents: 7500, matchBp: null, capBpOfGross: null },
    });

    await userEvent.click(screen.getByRole("button", { name: "Edit income source" }));
    dialog = screen.getByRole("dialog", { name: "Edit income source" });
    await show(dialog, "Employer match");
    match = within(dialog).getByRole("group", { name: "Employer match" });
    await userEvent.selectOptions(within(match).getByLabelText("Does your employer add anything?"), "No match");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/income-sources/1")).toHaveLength(2));
    expect(bodiesSentTo(fetchMock, "PUT /api/income-sources/1")[1]).toMatchObject({ match: null });
  });

  it("without gross pay, asks to confirm the contribution is within the matched share instead of guessing", async () => {
    const takeHomeOnly: IncomeSource = { ...SALARY, grossCents: null, match: null, matchFigures: null };
    const fetchMock = withInvestments({
      "GET /api/income-sources": () => ({ status: 200, data: [takeHomeOnly] }),
      "PUT /api/income-sources/1": () => ({ status: 200, data: takeHomeOnly, changed: true }),
    });
    renderApp("/income/1");

    await userEvent.click(await screen.findByRole("button", { name: "Edit income source" }));
    const dialog = screen.getByRole("dialog", { name: "Edit income source" });
    await show(dialog, "Employer match");
    const match = within(dialog).getByRole("group", { name: "Employer match" });
    await userEvent.selectOptions(within(match).getByLabelText("Does your employer add anything?"), "A percentage of what I put in");
    await userEvent.type(within(match).getByRole("combobox", { name: "Paid into" }), "bright{Enter}");
    await userEvent.type(within(match).getByLabelText("They match, %"), "50");
    await userEvent.type(within(match).getByLabelText("Up to, % of pay"), "6");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    expect(within(dialog).getByRole("alert")).toHaveTextContent(
      "Please fill in the gross amount per paycheck, or tick the box to confirm that what you put in is within the 6.00% that is matched.",
    );
    expect(calls(fetchMock)).not.toContain("PUT /api/income-sources/1");

    await userEvent.click(within(match).getByRole("checkbox", { name: "What I put in is within the 6% of my pay that is matched" }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "PUT /api/income-sources/1")[0]).toMatchObject({
      grossCents: null,
      match: { mode: "formula", matchBp: 5000, capBpOfGross: 600, contributionWithinCap: true },
    });
  });

  it("takes a contribution as a percent of gross pay, and says what that comes to", async () => {
    const fetchMock = withInvestments({ "PUT /api/income-sources/1": () => ({ status: 200, data: SALARY, changed: true }) });
    renderApp("/income/1");

    await userEvent.click(await screen.findByRole("button", { name: "Edit income source" }));
    const dialog = screen.getByRole("dialog", { name: "Edit income source" });
    await show(dialog, "Deductions");
    const deductions = within(dialog).getByRole("group", { name: "Deductions" });
    // It opens as the dollar amount it was saved as.
    expect(within(deductions).getByLabelText("Given as")).toHaveValue("dollars");
    await userEvent.selectOptions(within(deductions).getByLabelText("Given as"), "Percent");
    await userEvent.type(within(deductions).getByLabelText("Percent of gross pay"), "6");
    expect(within(deductions).getByText("About $216.00 a paycheck.")).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "PUT /api/income-sources/1")[0]).toMatchObject({
      deductions: [{ name: "401(k)", percentBp: 600, toAccountId: 2 }],
    });
  });

  it("asks for gross pay before taking a contribution as a percent of it", async () => {
    const takeHomeOnly: IncomeSource = { ...SALARY, grossCents: null, match: null, matchFigures: null };
    const fetchMock = withInvestments({ "GET /api/income-sources": () => ({ status: 200, data: [takeHomeOnly] }) });
    renderApp("/income/1");

    await userEvent.click(await screen.findByRole("button", { name: "Edit income source" }));
    const dialog = screen.getByRole("dialog", { name: "Edit income source" });
    await show(dialog, "Deductions");
    const deductions = within(dialog).getByRole("group", { name: "Deductions" });
    await userEvent.selectOptions(within(deductions).getByLabelText("Given as"), "Percent");
    await userEvent.type(within(deductions).getByLabelText("Percent of gross pay"), "6");
    expect(within(deductions).getByText("Fill in the gross amount per paycheck too, so this can be worked out.")).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    expect(within(dialog).getByRole("alert")).toHaveTextContent(
      "A deduction given as a percent of your pay needs the gross amount per paycheck. Please fill that in as well.",
    );
    expect(calls(fetchMock)).not.toContain("PUT /api/income-sources/1");
  });

  it("shows a contribution that is a percent of pay as one, and says when a match rests on a confirmation", async () => {
    const byPercent: IncomeSource = {
      ...SALARY,
      grossCents: null,
      deductions: [{ name: "401(k)", amountCents: 21600, toAccountId: 2, percentBp: 600 }],
      match: { toAccountId: 2, mode: "formula", fixedCents: null, matchBp: 10000, capBpOfGross: 600, contributionWithinCap: true },
      matchFigures: { perPaycheckCents: 21600, mostPossibleCents: 21600, contributeMoreCents: 0 },
    };
    withInvestments({ "GET /api/income-sources": () => ({ status: 200, data: [byPercent] }) });
    renderApp("/income/1");

    expect(await screen.findByText(/401\(k\) · 6\.00% of gross pay → Brightwater 401\(k\)/)).toBeInTheDocument();
    expect(screen.getByText(/takes your word that what you put in is within the 6\.00% of pay that is matched/)).toBeInTheDocument();
  });
});
