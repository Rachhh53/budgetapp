import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { AnnualCosts } from "../../src/api/annual";
import { bodiesSentTo, calls, fakeBackend, renderApp } from "../helpers";

const COSTS: AnnualCosts = {
  basis: "calendar",
  windows: [
    { from: "2024-01-01", to: "2024-12-31", label: "2024" },
    { from: "2025-01-01", to: "2025-12-31", label: "2025" },
    { from: "2026-01-01", to: "2026-12-31", label: "2026" },
  ],
  rows: [
    {
      itemId: 1,
      name: "Rent",
      kind: "bill",
      categoryId: 1,
      categoryName: "Housing",
      color: "#0f766e",
      totalsCents: [0, 1_800_000, 1_860_000],
      changeBp: 333,
      perMonthCents: 155_000,
    },
    {
      itemId: 2,
      name: "Electric",
      kind: "bill",
      categoryId: null,
      categoryName: null,
      color: null,
      totalsCents: [0, 159_600, 174_200],
      changeBp: 915,
      perMonthCents: 14_517,
    },
    {
      itemId: 3,
      name: "Groceries",
      kind: "budget",
      categoryId: null,
      categoryName: null,
      color: null,
      totalsCents: [0, 0, 720_000],
      changeBp: null,
      perMonthCents: 60_000,
    },
  ],
  totalsCents: [0, 1_959_600, 2_754_200],
  changeBp: 4055,
  perMonthCents: 229_517,
  biggestIncrease: { itemId: 2, name: "Electric", changeBp: 915, increaseCents: 14_600 },
  budgetedFrom: "2026-11-01",
};

const ROLLING: AnnualCosts = {
  ...COSTS,
  basis: "rolling",
  windows: [
    { from: "2023-10-01", to: "2024-09-30", label: "Oct 2023 – Sep 2024" },
    { from: "2024-10-01", to: "2025-09-30", label: "Oct 2024 – Sep 2025" },
    { from: "2025-10-01", to: "2026-09-30", label: "Oct 2025 – Sep 2026" },
  ],
  budgetedFrom: null,
};

function withCosts(overrides: Parameters<typeof fakeBackend>[0] = {}) {
  return fakeBackend({
    "GET /api/annual-costs": () => ({ status: 200, data: COSTS }),
    "GET /api/annual-costs?basis=rolling": () => ({ status: 200, data: ROLLING }),
    ...overrides,
  });
}

describe("Annual costs", () => {
  it("shows this year's total, how it compares, and the biggest increase", async () => {
    withCosts();
    renderApp("/dashboard/annual");

    expect((await screen.findByText("2026 total")).parentElement).toHaveTextContent("$27,542.00spent through Oct 31 · budgeted after");
    expect(screen.getByText("vs 2025").parentElement).toHaveTextContent("+$7,946.00 · +40.55%2025 total $19,596.00");
    expect(screen.getByText("Biggest increase").parentElement).toHaveTextContent("Electric +9.15%$146.00 more than 2025");
  });

  it("lists every bill and budget with a figure per year, leaving out a year with nothing in it", async () => {
    withCosts();
    renderApp("/dashboard/annual");

    const table = await screen.findByRole("region", { name: "Bills and budgets" });
    const rows = within(table).getAllByRole("link");
    expect(rows).toHaveLength(3);
    // Each figure is said with its name, which is what a phone shows beside it; 2024 holds nothing, so it has no column.
    expect(rows[0]).toHaveTextContent("RentBill or expense · Housing2025$18,000.002026$18,600.00Change+3.33%Per month$1,550.00");
    expect(rows[0]).not.toHaveTextContent("2024");
    // Nothing earlier to compare a new budget with.
    expect(rows[2]).toHaveTextContent("GroceriesBudget2025$0.002026$7,200.00ChangenewPer month$600.00");
    expect(table).toHaveTextContent("Total2025$19,596.002026$27,542.00Change+40.55%Per month$2,295.17");
  });

  it("opens the recurring item's own page from a row", async () => {
    withCosts();
    renderApp("/dashboard/annual");

    const table = await screen.findByRole("region", { name: "Bills and budgets" });
    expect(within(table).getByRole("link", { name: /Electric/ })).toHaveAttribute("href", "/recurring/2");
  });

  it("switches to rolling twelve months and asks for them", async () => {
    const fetchMock = withCosts();
    renderApp("/dashboard/annual");

    await userEvent.click(await screen.findByRole("radio", { name: "Rolling 12 months" }));

    await waitFor(() => expect(calls(fetchMock)).toContain("GET /api/annual-costs?basis=rolling"));
    expect((await screen.findByText("Last 12 months")).parentElement).toHaveTextContent("Oct 2025 – Sep 2026");
    expect(screen.getByText("vs Oct 2024 – Sep 2025")).toBeInTheDocument();
  });

  it("exports the table as it is shown and says where the file went", async () => {
    const fetchMock = withCosts({
      "POST /api/annual-costs/export/csv": () => ({ status: 200, data: { saved: { fileName: "Annual costs.csv", folder: "C:\\Users\\Amara\\Downloads" } } }),
    });
    renderApp("/dashboard/annual");

    await userEvent.click(await screen.findByRole("radio", { name: "Rolling 12 months" }));
    await screen.findByText("Last 12 months");
    await userEvent.click(screen.getByRole("button", { name: "Export CSV" }));

    expect(await screen.findByText("Saved in C:\\Users\\Amara\\Downloads as Annual costs.csv.")).toBeInTheDocument();
    expect(bodiesSentTo(fetchMock, "POST /api/annual-costs/export/csv")).toEqual([{ basis: "rolling", save: true }]);
  });

  it("says how to start when there are no bills or budgets, and has nothing to export", async () => {
    fakeBackend();
    renderApp("/dashboard/annual");

    expect(await screen.findByText("No bills or budgets yet.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Add a recurring bill or budget" })).toHaveAttribute("href", "/recurring");
    expect(screen.getByRole("button", { name: "Export CSV" })).toBeDisabled();
    expect(screen.getByText("nothing earlier to compare with yet")).toBeInTheDocument();
    expect(screen.getByText("nothing costs more than it did")).toBeInTheDocument();
  });

  it("shows the backend's message when the costs cannot be added up", async () => {
    fakeBackend({ "GET /api/annual-costs": () => ({ status: 500, error: "Something went wrong on our side. Please try again." }) });
    renderApp("/dashboard/annual");

    expect(await screen.findByRole("alert")).toHaveTextContent("Something went wrong on our side. Please try again.");
  });
});
