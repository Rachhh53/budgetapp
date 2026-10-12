import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Attention, Dashboard } from "../../src/api/dashboard";
import { calls, EMPTY_DASHBOARD, fakeBackend, renderApp } from "../helpers";

/** One line of the attention list; only what its kind uses is filled in. */
function attention(kind: Attention["kind"], fields: Partial<Attention>): Attention {
  return {
    kind,
    name: "",
    date: null,
    amountCents: null,
    goalCents: null,
    count: null,
    urgent: false,
    accountId: null,
    itemId: null,
    sourceId: null,
    stubId: null,
    periodStart: null,
    ...fields,
  };
}

/** Amara's dashboard on Friday 2026-10-09. */
const DASHBOARD: Dashboard = {
  today: "2026-10-09",
  range: "three_months",
  netWorth: { cents: 14_364_762, changeCents: 243_540, since: "2026-10-01" },
  lowestAhead: { cents: 41_207, periodStart: "2026-11-13", accountId: 1, accountName: "Everyday Checking", low: true },
  saved: { totalCents: 812_500, transfersCents: 380_000, contributionsCents: 337_500, matchCents: 95_000 },
  debt: { totalCents: 30_556_748, cardsCents: 240_000, loansCents: 30_316_748 },
  spending: {
    from: "2026-07-10",
    to: "2026-10-09",
    totalCents: 656_000,
    categories: [
      { categoryId: 1, name: "Housing", color: "#0f766e", totalCents: 465_000 },
      { categoryId: 2, name: "Food", color: "#15803d", totalCents: 191_000 },
      { categoryId: null, name: "No category", color: null, totalCents: 0 },
    ],
  },
  netWorthOverTime: [
    { date: "2026-08-31", netWorthCents: 13_900_000, projected: false },
    { date: "2026-09-30", netWorthCents: 14_121_222, projected: false },
    { date: "2026-10-09", netWorthCents: 14_364_762, projected: false },
    { date: "2026-10-31", netWorthCents: 14_500_000, projected: true },
    { date: "2027-10-31", netWorthCents: 15_800_000, projected: true },
  ],
  attention: [
    attention("past_due", { name: "Water", date: "2026-09-20", amountCents: 5_000, itemId: 4, periodStart: "2026-09-15" }),
    attention("low_balance", { name: "Everyday Checking", date: "2026-11-13", amountCents: 41_207, accountId: 1, periodStart: "2026-11-13" }),
    attention("promo_ending", { name: "Balance transfer", date: "2026-11-10", amountCents: 60_000, accountId: 3, urgent: true }),
    attention("due_soon", { name: "Rent", date: "2026-10-09", amountCents: 140_000, itemId: 2, periodStart: "2026-10-01" }),
    attention("stub_review", { name: "Brightwater Health", date: "2026-10-01", count: 1, sourceId: 5, stubId: 8 }),
    attention("goal_short", { name: "Holiday gifts", date: "2026-12-01", amountCents: 40_000, goalCents: 100_000, accountId: 2 }),
    attention("price_up", { name: "Electric", count: 910, amountCents: 14_600, itemId: 6 }),
    attention("balance_stale", { name: "Rainy Day Savings", date: "2026-08-15", count: 3, accountId: 2 }),
  ],
};

function withDashboard(dashboard: Dashboard = DASHBOARD) {
  return fakeBackend({ "GET /api/dashboard": () => ({ status: 200, data: dashboard }) });
}

describe("Dashboard", () => {
  it("has three tabs, each its own address, with Overview the one in view", async () => {
    withDashboard();
    renderApp("/dashboard");

    const tabs = await screen.findByRole("navigation", { name: "Dashboard" });
    expect(within(tabs).getAllByRole("link").map((link) => link.textContent)).toEqual(["Overview", "Reports", "Annual costs"]);
    expect(within(tabs).getByRole("link", { name: "Overview" })).toHaveAttribute("aria-current", "page");
    expect(within(tabs).getByRole("link", { name: "Reports" })).toHaveAttribute("href", "/dashboard/reports");
    expect(within(tabs).getByRole("link", { name: "Annual costs" })).toHaveAttribute("href", "/dashboard/annual");

    await userEvent.click(within(tabs).getByRole("link", { name: "Reports" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Reports" })).toBeInTheDocument();
    expect(within(screen.getByRole("navigation", { name: "Dashboard" })).getByRole("link", { name: "Reports" })).toHaveAttribute("aria-current", "page");
    // Still under Dashboard in the sidebar.
    expect(within(screen.getAllByRole("navigation", { name: "Main" })[0]!).getByRole("link", { name: "Dashboard" })).toHaveAttribute("aria-current", "page");
  });

  it("sends the old Reports and Annual costs addresses on to their tabs", async () => {
    withDashboard();
    renderApp("/annual");

    expect(await screen.findByRole("heading", { level: 1, name: "Annual costs" })).toBeInTheDocument();
    expect(within(screen.getByRole("navigation", { name: "Dashboard" })).getByRole("link", { name: "Annual costs" })).toHaveAttribute("aria-current", "page");
  });

  it("shows the four figures with what each is made of", async () => {
    withDashboard();
    renderApp("/dashboard");

    expect((await screen.findByText("Net worth")).parentElement).toHaveTextContent("$143,647.62+$2,435.40 since Oct 1");
    const lowest = screen.getByText("Lowest balance ahead").parentElement!;
    expect(lowest).toHaveTextContent("$412.07");
    // In words, not by color: it is under the warning set for the account.
    expect(lowest).toHaveTextContent("Under your warning · Nov 13 period · Everyday Checking");
    expect(screen.getByText("Saved & invested this year").parentElement).toHaveTextContent("$8,125.00transfers, paycheck contributions, employer match");
    expect(screen.getByText("Total debt").parentElement).toHaveTextContent("$305,567.48cards $2,400.00 · loans $303,167.48");
  });

  it("lists spending by category with each share and amount", async () => {
    withDashboard();
    renderApp("/dashboard");

    const card = await screen.findByRole("region", { name: "Spending by category" });
    const rows = within(card).getAllByRole("listitem");
    expect(rows.map((row) => row.textContent)).toEqual(["Housing71%$4,650.00", "Food29%$1,910.00", "No category0%$0.00"]);
    expect(within(card).getByText("Total $6,560.00")).toBeInTheDocument();
    expect(within(card).getByText(/Jul 10, 2026 – Oct 9, 2026/)).toBeInTheDocument();
  });

  it("asks again when the dates are changed, and says what a pay period covers", async () => {
    const fetchMock = fakeBackend({
      "GET /api/dashboard": () => ({ status: 200, data: DASHBOARD }),
      "GET /api/dashboard?range=period": () => ({
        status: 200,
        data: { ...DASHBOARD, range: "period", spending: { ...DASHBOARD.spending, from: "2026-10-01", to: "2026-10-14" } },
      }),
    });
    renderApp("/dashboard");

    const card = await screen.findByRole("region", { name: "Spending by category" });
    expect(within(card).getByRole("radio", { name: "Last 3 months" })).toBeChecked();
    await userEvent.click(within(card).getByRole("radio", { name: "This period" }));

    await waitFor(() => expect(calls(fetchMock)).toContain("GET /api/dashboard?range=period"));
    expect(await within(card).findByText(/Oct 1, 2026 – Oct 14, 2026, with what is still to come in it/)).toBeInTheDocument();
  });

  it("tells net worth over time in words under the chart", async () => {
    withDashboard();
    renderApp("/dashboard");

    const card = await screen.findByRole("region", { name: "Net worth over time" });
    expect(card).toHaveTextContent("It was $139,000.00 on Aug 31, 2026, is $143,647.62 today, and is projected to be $158,000.00 by Oct 31, 2027.");
    expect(card).toHaveTextContent("Solid: what Budget works out from the balances and values you entered. Dashed: the projection.");
  });

  it("puts each thing needing attention into words and links it to where it is dealt with", async () => {
    withDashboard();
    renderApp("/dashboard");

    const card = await screen.findByRole("region", { name: "Coming up & needs attention" });
    const lines = within(card).getAllByRole("link");
    expect(lines.map((line) => [line.textContent, line.getAttribute("href")])).toEqual([
      ["Past dueWater was due Sep 20, 2026$50.00 · paid by hand and not ticked off yet", "/periods/2026-09-15"],
      ["Low balanceEveryday Checking dips to $412.07In the pay period starting Nov 13, 2026, under the warning you set", "/periods/2026-11-13?account=1"],
      [
        "PromoBalance transfer ends Nov 10, 2026$600.00 is projected to be left on it. If it is, interest for the whole promotion is charged that day",
        "/accounts/3",
      ],
      ["Due soonRent is due today$1,400.00 · paid by hand", "/periods/2026-10-01"],
      ["Pay stubA Brightwater Health pay stub is waiting for review1 line to check", "/income/5/stubs/8"],
      ["GoalHoliday gifts is short of its goal$400.00 short of $1,000.00 by Dec 1, 2026, at what is scheduled", "/accounts/2"],
      ["PriceElectric is up 9.10% on last year$146.00 more over the year", "/recurring/6"],
      [
        "UpdateRainy Day Savings balance last entered Aug 15, 2026And 2 more accounts over a month old. Enter the real numbers whenever you want them",
        "/accounts",
      ],
    ]);
  });

  it("has kind words for a new app with nothing in it yet", async () => {
    fakeBackend();
    renderApp("/dashboard");

    expect(await screen.findByText("shows once a checking or savings account has a balance")).toBeInTheDocument();
    expect(screen.getByText("nothing owed on cards or loans")).toBeInTheDocument();
    expect(screen.getByText(/Nothing was spent between Jul 10, 2026 and Oct 9, 2026/)).toBeInTheDocument();
    expect(screen.getByText(/There is nothing to draw yet/)).toBeInTheDocument();
    expect(screen.getByText(/Nothing needs your attention right now/)).toBeInTheDocument();
    expect(EMPTY_DASHBOARD.attention).toEqual([]);
  });

  it("says so while it loads and when it cannot be worked out", async () => {
    fakeBackend({ "GET /api/dashboard": () => ({ status: 500, error: "Something went wrong on our side. Please try again." }) });
    renderApp("/dashboard");

    expect(await screen.findByRole("alert")).toHaveTextContent("Something went wrong on our side. Please try again.");
    expect(screen.queryByText("Net worth")).not.toBeInTheDocument();
  });
});
