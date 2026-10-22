import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Account, AccountDetail } from "../../src/api/accounts";
import type { Item } from "../../src/api/items";
import type { Loan, LoanSettings } from "../../src/api/loans";
import { bodiesSentTo, calls, fakeBackend, renderApp } from "../helpers";

const MORTGAGE: Account = {
  id: 4,
  name: "Home mortgage",
  institution: "Coastal Credit Union",
  type: "loan",
  subtypeLabel: "Mortgage",
  color: "#b45309",
  archived: false,
  lowBalanceThresholdCents: null,
  latestBalance: { id: 21, date: "2026-10-01", amountCents: 24000000, note: null },
};
const DETAIL: AccountDetail = {
  account: MORTGAGE,
  balances: [{ id: 21, date: "2026-10-01", amountCents: 24000000, note: null }],
  interest: { creditDay: 31, destination: "unassigned", destinationBucketId: null },
  rates: [{ id: 3, kind: "loan_rate", rateBp: 625, effectiveDate: "2024-06-01" }] as AccountDetail["rates"],
};

const SETTINGS: LoanSettings = {
  accountId: 4,
  originalPrincipalCents: 25000000,
  termMonths: 360,
  startDate: "2024-06-01",
  paymentCents: 198000,
  paymentDay: 1,
  escrowInsuranceCents: 12000,
  escrowTaxesCents: 32000,
  paymentItemId: null,
};
const LOAN: Loan = {
  settings: SETTINGS,
  rateBp: 625,
  balanceCents: 24000000,
  standardPaymentCents: 153929,
  nextPayment: { date: "2026-11-01", paymentCents: 198000, interestCents: 125000, principalCents: 29000, escrowCents: 44000 },
  payoffDate: "2054-06-01",
  interestRemainingCents: 27500000,
  paysOff: true,
  paidBy: [],
  years: [
    { year: 2026, interestCents: 249800, principalCents: 58200, endingCents: 23941800 },
    { year: 2027, interestCents: 1487000, principalCents: 361000, endingCents: 23580800 },
  ],
};

const CHECKING: Account = { ...MORTGAGE, id: 1, name: "Everyday Checking", type: "checking", subtypeLabel: null, color: "#0f766e" };

/** The mortgage as she already pays it: a monthly bill from checking. */
const MORTGAGE_BILL: Item = {
  id: 9,
  name: "Mortgage",
  kind: "bill",
  note: null,
  archived: false,
  createdOn: "2026-01-05",
  tagIds: [],
  amountCents: 198000,
  amountMode: "per_occurrence",
  estimate: false,
  categoryId: null,
  payFromAccountId: 1,
  payToAccountId: null,
  autopay: true,
  placement: "due_period",
  schedule: { frequency: "monthly", interval: 1, day1: 1, day2: null, month: null, startDate: "2026-01-01", endDate: null, weekendRule: "none" },
  daysAfterPayday: 0,
  bucketId: null,
  saveUpBucketId: null,
  saveUpPerPaycheckCents: null,
  perOccurrenceCents: 198000,
  perYearCents: 2376000,
  next: { scheduledDate: "2026-11-01", dueDate: "2026-11-01", periodStart: "2026-10-15", early: false },
  history: [{ versionId: 1, effectiveFrom: "2026-01-01", perOccurrenceCents: 198000, perYearCents: 2376000 }],
  paused: false,
  pausedUntil: null,
  fromBucketId: null,
};

function withLoan(loan: Loan | undefined, overrides: Parameters<typeof fakeBackend>[0] = {}) {
  return fakeBackend({
    "GET /api/accounts": () => ({ status: 200, data: [MORTGAGE] }),
    "GET /api/accounts/4": () => ({ status: 200, data: DETAIL }),
    ...(loan ? { "GET /api/accounts/4/loan": () => ({ status: 200, data: loan }) } : {}),
    ...overrides,
  });
}

describe("a loan's page", () => {
  it("shares the next payment out and says when the loan is paid off", async () => {
    withLoan(LOAN);
    renderApp("/accounts/4");

    const payment = within(await screen.findByRole("region", { name: "Monthly payment" }));
    expect(await payment.findByText("a month, on the 1st")).toBeInTheDocument();
    expect(payment.getAllByText("Principal")[0]!.closest("li")).toHaveTextContent("$290.00");
    expect(payment.getAllByText("Interest")[0]!.closest("li")).toHaveTextContent("$1,250.00");
    expect(payment.getByText("Insurance and taxes").closest("li")).toHaveTextContent("$440.00");
    expect(payment.getByText("Jun 1, 2054")).toBeInTheDocument();
    expect(payment.getByText(/never lower the loan/)).toHaveTextContent("$120.00 for insurance and $320.00 for taxes");
    expect(payment.getAllByRole("listitem").filter((row) => /^20\d\d/.test(row.textContent ?? ""))).toHaveLength(2);

    const terms = within(screen.getByRole("region", { name: "Loan terms" }));
    expect(terms.getByText("30 years")).toBeInTheDocument();
    expect(terms.getByText(/those terms work out to/)).toHaveTextContent("At 6.25%, those terms work out to $1,539.29 a month");
    expect(within(screen.getByRole("region", { name: "Interest rate" })).getByText("6.25%")).toBeInTheDocument();
  });

  it("shows where the loan is heading over its whole life, to the day it is paid off", async () => {
    withLoan(LOAN);
    renderApp("/accounts/4");

    const heading = within(await screen.findByRole("region", { name: "Where it's heading" }));
    expect(await heading.findByText(/paid off on/)).toHaveTextContent(
      "From $240,000.00 on Oct 1, 2026 to $240,000.00 owed today, and paid off on Jun 1, 2054.",
    );
  });

  it("warns in words when the payment doesn't cover the interest", async () => {
    withLoan({ ...LOAN, paysOff: false, payoffDate: null, interestRemainingCents: null, years: [] });
    renderApp("/accounts/4");

    expect(await screen.findByRole("alert")).toHaveTextContent("This payment does not cover a month's interest");
  });

  it("asks for the payment first, and checks that escrow fits inside it", async () => {
    const fetchMock = withLoan(undefined, { "PUT /api/accounts/4/loan": () => ({ status: 200, data: LOAN, changed: true }) });
    renderApp("/accounts/4");

    await userEvent.click(await screen.findByRole("button", { name: "Add the payment" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Whole payment a month" }), "1980");
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Of that, insurance (optional)" }), "2000");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("together they can't be more than it");

    await userEvent.clear(within(dialog).getByRole("textbox", { name: "Of that, insurance (optional)" }));
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Of that, insurance (optional)" }), "120");
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Of that, taxes (optional)" }), "320");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/accounts/4/loan")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "PUT /api/accounts/4/loan")[0]).toEqual({
      originalPrincipalCents: null,
      termMonths: null,
      startDate: null,
      paymentCents: 198000,
      paymentDay: 1,
      escrowInsuranceCents: 12000,
      escrowTaxesCents: 32000,
      paymentItemId: null,
    });
  });

  it("changes the terms without touching the payment", async () => {
    const fetchMock = withLoan(LOAN, { "PUT /api/accounts/4/loan": () => ({ status: 200, data: LOAN, changed: true }) });
    renderApp("/accounts/4");

    await userEvent.click(await screen.findByRole("button", { name: "Edit loan terms" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.clear(within(dialog).getByLabelText("Term: years"));
    await userEvent.type(within(dialog).getByLabelText("Term: years"), "15");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/accounts/4/loan")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "PUT /api/accounts/4/loan")[0]).toMatchObject({ termMonths: 180, paymentCents: 198000, escrowTaxesCents: 32000 });
  });

  it("says when nothing pays the loan yet, and links the bill that already does", async () => {
    const fetchMock = withLoan(LOAN, {
      "GET /api/accounts": () => ({ status: 200, data: [MORTGAGE, CHECKING] }),
      "GET /api/items": () => ({ status: 200, data: [MORTGAGE_BILL] }),
      "PUT /api/accounts/4/loan": () => ({ status: 200, data: LOAN, changed: true }),
    });
    renderApp("/accounts/4");

    const payment = within(await screen.findByRole("region", { name: "Monthly payment" }));
    expect(await payment.findByText("No recurring item pays this yet.")).toBeInTheDocument();
    await userEvent.click(payment.getByRole("button", { name: "Link a recurring item you already have" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByRole("combobox", { name: "Recurring item that pays it (optional)" }), "Mortgage{Enter}");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/accounts/4/loan")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "PUT /api/accounts/4/loan")[0]).toMatchObject({ paymentItemId: 9, paymentCents: 198000 });
  });

  it("adds the payment as a recurring item from the loan's page", async () => {
    const fetchMock = withLoan(LOAN, {
      "GET /api/accounts": () => ({ status: 200, data: [MORTGAGE, CHECKING] }),
      "POST /api/accounts/4/loan/payment": () => ({ status: 201, data: LOAN, changed: true }),
    });
    renderApp("/accounts/4");

    const payment = within(await screen.findByRole("region", { name: "Monthly payment" }));
    await userEvent.click(await payment.findByRole("button", { name: "Add it as a recurring payment" }));
    const dialog = await screen.findByRole("dialog");
    // Without an account there is nothing to take it from.
    await userEvent.click(within(dialog).getByRole("button", { name: "Add recurring payment" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("Please choose the account this payment comes out of.");
    expect(calls(fetchMock)).not.toContain("POST /api/accounts/4/loan/payment");

    await userEvent.type(within(dialog).getByRole("combobox", { name: "Paid from" }), "Everyday{Enter}");
    await userEvent.click(within(dialog).getByRole("checkbox", { name: /Autopay/ }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Add recurring payment" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "POST /api/accounts/4/loan/payment")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "POST /api/accounts/4/loan/payment")[0]).toEqual({ payFromAccountId: 1, autopay: true });
  });

  it("names the recurring item that pays it", async () => {
    withLoan({ ...LOAN, settings: { ...SETTINGS, paymentItemId: 9 }, paidBy: [{ itemId: 9, name: "Mortgage", linked: true }] });
    renderApp("/accounts/4");

    const payment = within(await screen.findByRole("region", { name: "Monthly payment" }));
    expect(await payment.findByRole("link", { name: "Mortgage" })).toHaveAttribute("href", "/recurring/9");
    expect(payment.queryByText("No recurring item pays this yet.")).not.toBeInTheDocument();
  });

  it("says so kindly when the loan's figures can't be loaded", async () => {
    withLoan(undefined, { "GET /api/accounts/4/loan": () => ({ status: 500, error: "Something went wrong on our side. Please try again." }) });
    renderApp("/accounts/4");

    const payment = within(await screen.findByRole("region", { name: "Monthly payment" }));
    expect(await payment.findByRole("alert")).toHaveTextContent("Something went wrong on our side. Please try again.");
  });
});
