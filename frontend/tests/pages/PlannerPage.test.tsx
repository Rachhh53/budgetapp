import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it } from "vitest";
import type { PayoffDebt, PlannerComparison, PlannerFlag, PlannerOption } from "../../src/api/planner";
import { bodiesSentTo, calls, fakeBackend, NOTHING_TO_COMPARE, renderApp } from "../helpers";

/** One option; only what its kind uses is filled in. */
function option(fields: Partial<PlannerOption> & Pick<PlannerOption, "accountId" | "name" | "kind">): PlannerOption {
  return {
    color: "#0f766e",
    type: fields.kind === "debt" ? "loan" : fields.kind === "invest" ? "investment" : "savings",
    subtypeLabel: null,
    balanceCents: 0,
    rateBp: null,
    accountRateBp: null,
    problem: null,
    paymentCents: null,
    interestSavedCents: null,
    payoffDate: null,
    payoffDateBefore: null,
    monthsSooner: null,
    freedCents: null,
    paysOff: null,
    paysOffBefore: null,
    rateAfterBp: null,
    rateChangesOn: null,
    valueCents: null,
    putInCents: null,
    growthCents: null,
    matchCents: null,
    matchedPerMonthCents: null,
    matchPerMonthCents: null,
    ...fields,
  };
}

function debt(fields: Partial<PayoffDebt> & Pick<PayoffDebt, "accountId" | "name">): PayoffDebt {
  return {
    color: "#0f766e",
    balanceCents: 0,
    rateBp: null,
    rateAfterBp: null,
    rateChangesOn: null,
    payoffDate: null,
    payoffDateBefore: null,
    paysOff: true,
    paysOffBefore: true,
    planned: false,
    interestSavedCents: null,
    ...fields,
  };
}

function flag(kind: PlannerFlag["kind"], fields: Partial<PlannerFlag>): PlannerFlag {
  return {
    kind,
    name: "",
    accountName: "",
    accountId: null,
    sourceId: null,
    date: null,
    amountCents: null,
    matchCents: null,
    capBp: null,
    putInBp: null,
    perPaycheckCents: null,
    planned: false,
    urgent: false,
    ...fields,
  };
}

const CAR = option({
  accountId: 5,
  name: "Car loan",
  kind: "debt",
  color: "#b45309",
  balanceCents: 1_486_000,
  rateBp: 690,
  paymentCents: 41_200,
  interestSavedCents: 82_400,
  payoffDate: "2028-09-15",
  payoffDateBefore: "2030-03-15",
  monthsSooner: 18,
  freedCents: 71_200,
  paysOff: true,
  paysOffBefore: true,
});

const RETIREMENT = option({
  accountId: 3,
  name: "Brightwater 401(k)",
  kind: "invest",
  balanceCents: 4_200_000,
  rateBp: 700,
  accountRateBp: 700,
  valueCents: 2_621_000,
  putInCents: 1_800_000,
  growthCents: 425_000,
  matchCents: 396_000,
  matchedPerMonthCents: 13_200,
  matchPerMonthCents: 6_600,
});

const BROKERAGE = option({
  accountId: 4,
  name: "Brokerage",
  kind: "invest",
  balanceCents: 900_000,
  rateBp: 600,
  accountRateBp: 600,
  valueCents: 2_084_500,
  putInCents: 1_800_000,
  growthCents: 284_500,
  matchCents: 0,
  matchedPerMonthCents: 0,
  matchPerMonthCents: 0,
});

/** Nia's comparison on Friday 2026-10-09: $300 a month over five years. */
const COMPARISON: PlannerComparison = {
  today: "2026-10-09",
  extraCents: 30_000,
  years: 5,
  flags: [
    flag("unclaimed_match", {
      name: "Brightwater Health",
      accountName: "Brightwater 401(k)",
      accountId: 3,
      sourceId: 7,
      amountCents: 13_200,
      matchCents: 6_600,
      capBp: 600,
      putInBp: 417,
    }),
    flag("promo_ending", {
      name: "0% balance transfer",
      accountName: "Discover",
      accountId: 6,
      date: "2027-03-15",
      amountCents: 240_000,
      perPaycheckCents: 24_000,
      planned: true,
    }),
  ],
  options: [CAR, RETIREMENT, BROKERAGE],
  payoff: {
    order: "highest_rate",
    debts: [
      debt({ accountId: 6, name: "Discover", rateBp: 0, payoffDate: "2027-03-15", payoffDateBefore: "2027-03-15", planned: true }),
      debt({ accountId: 5, name: "Car loan", color: "#b45309", rateBp: 690, payoffDate: "2028-09-15", payoffDateBefore: "2030-03-15", interestSavedCents: 82_400 }),
      debt({ accountId: 8, name: "Mortgage", rateBp: 625, payoffDate: "2047-08-01", payoffDateBefore: "2050-10-01", interestSavedCents: 3_107_600 }),
    ],
    debtFreeDate: "2047-08-01",
    debtFreeDateBefore: "2050-10-01",
    interestSavedCents: 3_190_000,
    owedOverTime: [
      { month: "2026-10-01", withCents: 30_126_000, withoutCents: 30_126_000 },
      { month: "2026-11-01", withCents: 29_900_000, withoutCents: 29_960_000 },
      { month: "2047-08-01", withCents: 0, withoutCents: 4_200_000 },
      { month: "2050-10-01", withCents: 0, withoutCents: 0 },
    ],
  },
  hidden: [{ accountId: 2, name: "Rainy Day Savings", type: "savings" }],
};

function withComparison(comparison: PlannerComparison = COMPARISON, more: Parameters<typeof fakeBackend>[0] = {}) {
  return fakeBackend({ "POST /api/planner": () => ({ status: 200, data: comparison }), ...more });
}

describe("Debt & decisions", () => {
  // What was last asked is remembered on the device; no test should start from another's.
  beforeEach(() => window.localStorage.clear());

  it("is in the sidebar and opens on $300 a month over five years", async () => {
    const backend = withComparison();
    renderApp("/");

    await userEvent.click((await screen.findAllByRole("link", { name: "Debt & decisions" }))[0]!);

    expect(await screen.findByRole("heading", { level: 1, name: "Where should extra money go?" })).toBeInTheDocument();
    expect(screen.getByLabelText("Extra each month")).toHaveValue("300.00");
    expect(screen.getByLabelText("Compare over")).toHaveValue("5");
    await waitFor(() =>
      expect(bodiesSentTo(backend, "POST /api/planner")[0]).toEqual({ extraCents: 30_000, years: 5, order: "highest_rate", growthBp: {} }),
    );
    expect(screen.getByText(/Estimates for comparing options, not financial advice/)).toBeInTheDocument();
  });

  it("says what there is to know first, each line leading to where it is dealt with", async () => {
    withComparison();
    renderApp("/planner");

    const first = await screen.findByRole("region", { name: "Worth knowing first" });
    expect(first).toHaveTextContent(
      "You put in 4.17% of your pay and Brightwater Health matches up to 6.00%, so the next $132.00 a month into Brightwater 401(k) also brings $66.00 from your employer.",
    );
    expect(first).toHaveTextContent(
      "Discover: 0% balance transfer ends Mar 15, 2027 with $2,400.00 still on it. Its payoff is already planned, at $240.00 a paycheck.",
    );
    expect(within(first).getByRole("link", { name: "Open the income source" })).toHaveAttribute("href", "/income/7");
    expect(within(first).getByRole("link", { name: "Open the card" })).toHaveAttribute("href", "/accounts/6");
  });

  it("shows what the extra would do to a debt", async () => {
    withComparison();
    renderApp("/planner");

    const car = await screen.findByRole("region", { name: "Car loan" });
    expect(car).toHaveTextContent("Pay debt");
    expect(car).toHaveTextContent("$14,860.00 owed at 6.90%");
    expect(car).toHaveTextContent("$824.00 (estimate)you'd save in interest by putting the extra $300.00 a month here");
    expect(car).toHaveTextContent("Return6.90% guaranteed");
    expect(car).toHaveTextContent("Debt-free dateSep 2028 (was Mar 2030)");
    expect(car).toHaveTextContent("Sooner by1 yr 6 mo");
    expect(car).toHaveTextContent("Then $712.00 a month is free for the next goal.");
    expect(within(car).getByRole("link", { name: "Car loan" })).toHaveAttribute("href", "/accounts/5");
  });

  it("leads with what the extra would earn invested, with the match", async () => {
    withComparison();
    renderApp("/planner");

    const retirement = await screen.findByRole("region", { name: "Brightwater 401(k)" });
    expect(retirement).toHaveTextContent("Invest");
    // Growth $4,250.00 + match $3,960.00: what the extra gains, not the $18,000.00 put in coming back.
    expect(retirement).toHaveTextContent("$8,210.00 (estimate)you'd earn, with the match, by putting the extra $300.00 a month here for 5 years");
    expect(retirement).toHaveTextContent("Comes to after 5 years$26,210.00");
    expect(retirement).toHaveTextContent("Return7.00% assumed + match");
    expect(retirement).toHaveTextContent("You put in$18,000.00");
    expect(retirement).toHaveTextContent("Employer match$3,960.00");
    expect(retirement).toHaveTextContent("$132.00 of the $300.00 earns $66.00 of match each month.");
    // No match on the brokerage account, so none is mentioned.
    const brokerage = screen.getByRole("region", { name: "Brokerage" });
    expect(brokerage).not.toHaveTextContent("match");
    expect(brokerage).toHaveTextContent("Return6.00% assumed");
  });

  it("asks again with a growth rate typed on a card, without saving it anywhere", async () => {
    const backend = withComparison();
    renderApp("/planner");

    const brokerage = await screen.findByRole("region", { name: "Brokerage" });
    const rate = within(brokerage).getByLabelText("Assumed growth");
    expect(rate).toHaveValue("6.00");
    await userEvent.clear(rate);
    await userEvent.type(rate, "8{Enter}");

    await waitFor(() => expect(bodiesSentTo(backend, "POST /api/planner").at(-1)).toMatchObject({ growthBp: { 4: 800 } }));
    // Only the comparison was asked for: nothing was written to the account.
    expect(calls(backend).filter((call) => !call.startsWith("GET") && call !== "POST /api/planner")).toEqual([]);
  });

  it("asks kindly for a rate it can read", async () => {
    const backend = withComparison();
    renderApp("/planner");

    const brokerage = await screen.findByRole("region", { name: "Brokerage" });
    const rate = within(brokerage).getByLabelText("Assumed growth");
    await userEvent.clear(rate);
    await userEvent.type(rate, "lots{Enter}");

    expect(within(brokerage).getByRole("alert")).toHaveTextContent("Please enter a rate between 0 and 100, like 6.5.");
    expect(bodiesSentTo(backend, "POST /api/planner").every((body) => JSON.stringify(body).includes('"growthBp":{}'))).toBe(true);
  });

  it("asks again when the amount or the period changes, and remembers them", async () => {
    const backend = withComparison();
    const view = renderApp("/planner");

    const extra = await screen.findByLabelText("Extra each month");
    await userEvent.clear(extra);
    await userEvent.type(extra, "450");
    await userEvent.selectOptions(screen.getByLabelText("Compare over"), "10");

    await waitFor(() => expect(bodiesSentTo(backend, "POST /api/planner").at(-1)).toMatchObject({ extraCents: 45_000, years: 10 }));

    view.unmount();
    renderApp("/planner");
    expect(await screen.findByLabelText("Extra each month")).toHaveValue("450.00");
    expect(screen.getByLabelText("Compare over")).toHaveValue("10");
  });

  it("does not ask about something that is not an amount", async () => {
    const backend = withComparison();
    renderApp("/planner");

    const extra = await screen.findByLabelText("Extra each month");
    await userEvent.clear(extra);
    await userEvent.type(extra, "a lot");

    expect(await screen.findByText("Please enter an amount like 1,250.00.")).toBeInTheDocument();
    expect(bodiesSentTo(backend, "POST /api/planner").every((body) => (body as { extraCents: number }).extraCents === 30_000)).toBe(true);
  });

  it("lays the debts out in the order they are gone, in words", async () => {
    withComparison();
    renderApp("/planner");

    const payoff = await screen.findByRole("region", { name: "Payoff order with the extra $300.00" });
    expect(payoff).toHaveTextContent("$31,900.00 (estimate)less interest altogether, by paying the extra in this order");
    expect(payoff).toHaveTextContent("Debt-free by Aug 2047 (was Oct 2050).");
    const rows = within(payoff).getAllByRole("listitem");
    expect(rows.map((row) => row.textContent)).toEqual([
      expect.stringContaining("Discover0.00%Mar 2027 · already planned"),
      expect.stringContaining("Car loan6.90% · gone firstSep 2028 (was Mar 2030 · 1 yr 6 mo sooner) · $824.00 less interest"),
      expect.stringContaining("Mortgage6.25% · nextAug 2047 (was Oct 2050 · 3 yrs 2 mo sooner) · $31,076.00 less interest"),
    ]);
    // Debt over time, said in words beside the chart.
    expect(within(payoff).getByText(/Debt over time/)).toBeInTheDocument();
    expect(payoff).toHaveTextContent(
      "All of it together starts at $301,260.00. With the extra it is all paid off by Aug 2047; without it, by Oct 2050.",
    );
  });

  it("asks again in the other order", async () => {
    const backend = withComparison();
    renderApp("/planner");

    await userEvent.click(await screen.findByRole("radio", { name: "Smallest balance first" }));

    await waitFor(() => expect(bodiesSentTo(backend, "POST /api/planner").at(-1)).toMatchObject({ order: "smallest_balance" }));
  });

  it("leaves an account out and brings one back", async () => {
    const backend = withComparison(COMPARISON, {
      "PUT /api/planner/hidden/5": () => ({ status: 200, data: { done: true }, changed: true }),
      "DELETE /api/planner/hidden/2": () => ({ status: 200, data: { done: true }, changed: true }),
    });
    renderApp("/planner");

    await userEvent.click(await screen.findByRole("button", { name: "Leave out Car loan" }));
    await waitFor(() => expect(calls(backend)).toContain("PUT /api/planner/hidden/5"));
    // It can be taken back.
    expect(await screen.findByRole("button", { name: /Undo/ })).toBeInTheDocument();

    await userEvent.click(screen.getByText("Left out of the comparison (1)"));
    await userEvent.click(screen.getByRole("button", { name: "Bring back Rainy Day Savings" }));
    await waitFor(() => expect(calls(backend)).toContain("DELETE /api/planner/hidden/2"));
  });

  it("says what a debt needs before it can be compared, and when one is never paid off", async () => {
    withComparison({
      ...COMPARISON,
      flags: [],
      options: [
        option({ accountId: 9, name: "Student loan", kind: "debt", balanceCents: 1_200_000, problem: "no_payment" }),
        option({
          accountId: 6,
          name: "Discover",
          kind: "debt",
          type: "credit_card",
          balanceCents: 240_000,
          rateBp: 0,
          rateAfterBp: 2449,
          rateChangesOn: "2027-03-16",
          paymentCents: 2_500,
          payoffDate: "2027-06-01",
          freedCents: 32_500,
          paysOff: true,
          paysOffBefore: false,
        }),
      ],
    });
    renderApp("/planner");

    const student = await screen.findByRole("region", { name: "Student loan" });
    expect(student).toHaveTextContent("no rate entered, so compared at 0%");
    expect(within(student).getByRole("link", { name: "Enter it on the loan's page" })).toHaveAttribute("href", "/accounts/9");

    const discover = screen.getByRole("region", { name: "Discover" });
    expect(discover).toHaveTextContent("$2,400.00 owed at 0.00% until Mar 16, 2027, then 24.49%");
    expect(discover).toHaveTextContent(
      "At $25.00 a month this is never paid off: the interest is more than the payment. With the extra it is gone by Jun 2027.",
    );
  });

  it("says so when there is nothing to compare yet", async () => {
    fakeBackend({ "POST /api/planner": () => ({ status: 200, data: NOTHING_TO_COMPARE }) });
    renderApp("/planner");

    const empty = await screen.findByRole("region", { name: "Nothing to compare yet" });
    expect(within(empty).getByRole("link", { name: "Add an account" })).toHaveAttribute("href", "/accounts");
    expect(screen.queryByRole("region", { name: /Payoff order/ })).not.toBeInTheDocument();
  });

  it("shows the backend's own words when the comparison cannot be worked out", async () => {
    fakeBackend({ "POST /api/planner": () => ({ status: 500, error: "Something went wrong on our side. Your data is safe — please try again." }) });
    renderApp("/planner");

    expect(await screen.findByRole("alert")).toHaveTextContent("Something went wrong on our side. Your data is safe — please try again.");
  });
});
