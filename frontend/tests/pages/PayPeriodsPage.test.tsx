import { screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { PayPeriods } from "../../src/api/income";
import { fakeBackend, renderApp } from "../helpers";

const PERIODS: PayPeriods = {
  primarySourceId: 1,
  periods: [
    {
      start: "2026-10-15",
      end: "2026-10-29",
      scheduledPayday: "2026-10-15",
      current: true,
      incomeCents: 280460,
      paychecks: [
        {
          incomeSourceId: 1,
          sourceName: "Northwind Health",
          scheduledDate: "2026-10-15",
          payDate: "2026-10-15",
          netCents: 248620,
          estimate: false,
          projectedCents: null,
        },
        {
          incomeSourceId: 2,
          sourceName: "Riverside Bakery",
          scheduledDate: "2026-10-23",
          payDate: "2026-10-23",
          netCents: 31840,
          estimate: true,
          projectedCents: null,
        },
      ],
    },
    {
      start: "2026-10-30",
      end: "2026-11-12",
      scheduledPayday: "2026-11-01",
      current: false,
      incomeCents: 248620,
      paychecks: [
        {
          incomeSourceId: 1,
          sourceName: "Northwind Health",
          scheduledDate: "2026-11-01",
          payDate: "2026-10-30",
          netCents: 248620,
          estimate: false,
          projectedCents: null,
        },
      ],
    },
  ],
};

describe("pay periods screen", () => {
  it("points to Income when there is no paycheck to build periods from", async () => {
    fakeBackend();
    renderApp("/");

    expect(await screen.findByText("No pay periods yet.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Add your income" })).toHaveAttribute("href", "/income");
  });

  it("explains an income source whose paydays have not started", async () => {
    fakeBackend({ "GET /api/pay-periods": () => ({ status: 200, data: { primarySourceId: 1, periods: [] } }) });
    renderApp("/");

    expect(await screen.findByText("Your pay periods haven't started yet.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Check your pay schedule" })).toHaveAttribute("href", "/income");
  });

  it("lists each period with the paychecks landing in it", async () => {
    fakeBackend({ "GET /api/pay-periods": () => ({ status: 200, data: PERIODS }) });
    renderApp("/");

    const current = await screen.findByRole("region", { name: "Oct 15, 2026 to Oct 29, 2026" });
    expect(within(current).getByRole("heading", { name: "Oct 15 – Oct 29, 2026" })).toBeInTheDocument();
    // "Current" is written out, not just shown by the accent border.
    expect(within(current).getByText("Current")).toBeInTheDocument();
    const paychecks = within(current).getAllByRole("listitem");
    expect(paychecks[0]).toHaveTextContent("Northwind Health");
    expect(paychecks[0]).toHaveTextContent("Thu Oct 15");
    expect(paychecks[1]).toHaveTextContent("$318.40 (estimate)");
    expect(within(current).getByText("Coming in").parentElement).toHaveTextContent("$2,804.60");

    const next = screen.getByRole("region", { name: "Oct 30, 2026 to Nov 12, 2026" });
    expect(within(next).queryByText("Current")).not.toBeInTheDocument();
    expect(next).toHaveTextContent("Fri Oct 30 · Nov 1 is a Sunday");
    expect(screen.getByText("= estimate")).toBeInTheDocument();
  });

  it("shows the backend's message when pay periods cannot be loaded", async () => {
    fakeBackend({ "GET /api/pay-periods": () => ({ status: 500, error: "Something went wrong on our side." }) });
    renderApp("/");

    expect(await screen.findByRole("alert")).toHaveTextContent("Something went wrong on our side.");
  });
});
