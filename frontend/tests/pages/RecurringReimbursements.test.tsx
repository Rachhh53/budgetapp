import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Account } from "../../src/api/accounts";
import type { FlaggedPayment } from "../../src/api/reimbursements";
import { bodiesSentTo, fakeBackend, renderApp } from "../helpers";

/** Paying back recurring payments (Rachel, 2026-10-24: "only payments I flag and a set amount defaulted to the payment being flagged"). */

const CHECKING: Account = {
  id: 1,
  name: "Everyday Checking",
  institution: null,
  type: "checking",
  subtypeLabel: null,
  color: "#0f766e",
  archived: false,
  archivedOn: null,
  lowBalanceThresholdCents: null,
  latestBalance: { id: 1, date: "2026-09-01", amountCents: 500000, note: null },
};
const HSA: Account = { ...CHECKING, id: 2, name: "Health Savings", type: "savings", latestBalance: null };

const FLAGGED: FlaggedPayment = {
  id: 3,
  itemId: 7,
  scheduledDate: "2026-09-15",
  name: "Therapy with Dr. Okafor",
  kind: "bill",
  date: "2026-09-16",
  paid: true,
  paymentCents: 15000,
  estimate: false,
  accountId: 1,
  categoryId: null,
  tagIds: [],
  amountCents: 12000,
  reimbursement: { status: "to_submit", submittedOn: null, to: "HSA", paidBackCents: 0, reimbursedCents: 0, owedCents: 12000, amountCents: 12000, intoAccountId: 2, expectedOn: "2026-09-30", fromAccountId: null },
  expectedOn: "2026-09-30",
  intoAccountId: 2,
  fromAccountId: null,
  skipped: false,
  fileCount: 0,
};

function withTherapy(overrides: Parameters<typeof fakeBackend>[0] = {}) {
  return fakeBackend({
    "GET /api/accounts": () => ({ status: 200, data: [CHECKING, HSA] }),
    ...overrides,
  });
}

describe("paying back recurring payments", () => {
  it("moves a flagged payment along on its own page, like an expense", async () => {
    const fetchMock = withTherapy({
      "GET /api/items/7/occurrences/2026-09-15/reimbursement": () => ({ status: 200, data: { payment: FLAGGED, paybacks: [], movesOut: [] } }),
      "PUT /api/items/7/occurrences/2026-09-15/reimbursement/status": () => ({ status: 200, data: { payment: FLAGGED, paybacks: [], movesOut: [] } }),
    });
    renderApp("/recurring/7/payments/2026-09-15");

    expect(await screen.findByRole("heading", { level: 1, name: "Therapy with Dr. Okafor" })).toBeInTheDocument();
    expect(screen.getByText("planned for Sep 30, 2026, into Health Savings")).toBeInTheDocument();
    expect(screen.getByText("part of it")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Mark as submitted" }));

    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "PUT /api/items/7/occurrences/2026-09-15/reimbursement/status")[0]).toMatchObject({
        status: "submitted",
        to: "HSA",
      }),
    );
  });

  it("says so when a payment is not flagged", async () => {
    withTherapy();
    renderApp("/recurring/7/payments/2026-09-15");

    expect(await screen.findByRole("alert")).toHaveTextContent("This payment isn't flagged to be paid back.");
  });

  it("lists flagged payments with expenses, opening the payment's page", async () => {
    withTherapy({
      "GET /api/payment-reimbursements": () => ({ status: 200, data: [FLAGGED] }),
    });
    renderApp("/expenses");

    const row = await screen.findByRole("link", { name: /Therapy with Dr. Okafor/ });
    expect(row).toHaveAttribute("href", "/recurring/7/payments/2026-09-15");
    expect(row).toHaveTextContent("Recurring payment");
    expect(row).toHaveTextContent("To submit");
  });
});
