import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Account } from "../../src/api/accounts";
import type { Card, CardDetail, CardSettings, Promo } from "../../src/api/cards";
import type { PayPeriod, PayPeriods } from "../../src/api/income";
import type { Item } from "../../src/api/items";
import type { PeriodDetail } from "../../src/api/periods";
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
  latestBalance: { id: 1, date: "2026-10-01", amountCents: 500000, note: null },
};
const VISA: Account = { ...CHECKING, id: 3, name: "Harbor Visa", type: "credit_card", color: "#7e22ce" };

const SETTINGS: CardSettings = {
  accountId: 3,
  statementCloseDay: 20,
  dueDay: 15,
  paymentMode: "statement_balance",
  fixedPaymentCents: null,
  autopay: true,
  payFromAccountId: 1,
  paymentItemId: 5,
  monthlyEstimateCents: null,
};

/** $2,400 left of a 0% balance transfer, ten paydays before it ends: $240.00 a paycheck. */
const TRANSFER: Promo = {
  id: 9,
  label: "Balance transfer",
  kind: "balance_transfer",
  rateBp: 0,
  startDate: "2026-03-15",
  endDate: "2027-03-15",
  originalBalanceCents: 300000,
  rateAfterBp: null,
  deferredInterest: false,
  remainingCents: 240000,
  paidPercent: 20,
  daysLeft: 156,
  warning: "none",
  warnFrom: "2027-01-14",
  paydaysLeft: 10,
  perPaycheckCents: 24000,
  payoffItemId: null,
  rateAfterEffectiveBp: 2449,
  monthlyInterestIfLeftCents: 4898,
  deferredInterestCents: 0,
};
const ENDING_SOON: Promo = { ...TRANSFER, daysLeft: 40, endDate: "2026-11-19", warning: "soon", warnFrom: "2026-09-20", paydaysLeft: 3, perPaycheckCents: 80000 };
const DEFERRED: Promo = { ...ENDING_SOON, deferredInterest: true, warning: "soon_deferred", deferredInterestCents: 58776 };

const CARD: Card = {
  account: VISA,
  balanceCents: 312500,
  settings: SETTINGS,
  aprBp: 2449,
  nextPayment: { itemId: 5, name: "Harbor Visa payment", scheduledDate: "2026-10-15", date: "2026-10-15", amountCents: 72500, estimate: false, autopay: true, payFromAccountId: 1 },
  promo: TRANSFER,
};

function detailOf(card: Card, promos: Promo[] = card.promo ? [card.promo] : []): CardDetail {
  return {
    card,
    promos,
    statements: [
      { id: null, closeDate: "2026-10-20", balanceCents: 312500, minimumDueCents: 3125, dueDate: "2026-11-15", entered: false, paidCents: 0, status: "planned" },
      { id: 4, closeDate: "2026-09-20", balanceCents: 72500, minimumDueCents: 2500, dueDate: "2026-10-15", entered: true, paidCents: 0, status: "due" },
    ],
    rates: [{ id: 2, kind: "apr", rateBp: 2449, effectiveDate: "2026-01-01" }] as CardDetail["rates"],
    nextCloseDate: "2026-10-20",
    nextDueDate: "2026-11-15",
  };
}

function withCard(card: Card, overrides: Parameters<typeof fakeBackend>[0] = {}, promos?: Promo[]) {
  return fakeBackend({
    "GET /api/accounts": () => ({ status: 200, data: [CHECKING, card.account] }),
    "GET /api/cards": () => ({ status: 200, data: [card] }),
    "GET /api/cards/3": () => ({ status: 200, data: detailOf(card, promos) }),
    ...overrides,
  });
}

describe("credit cards screen", () => {
  it("invites the first card when there are none", async () => {
    fakeBackend();
    renderApp("/cards");

    expect(await screen.findByText("No credit cards yet.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "+ Add card" }));
    // The account form opens already set to a credit card.
    expect(within(await screen.findByRole("dialog")).getByLabelText("Kind of account")).toHaveValue("credit_card");
  });

  it("says so kindly when the cards can't be loaded", async () => {
    fakeBackend({ "GET /api/cards": () => ({ status: 500, error: "Something went wrong on our side. Please try again." }) });
    renderApp("/cards");

    expect(await screen.findByRole("alert")).toHaveTextContent("Something went wrong on our side. Please try again.");
  });

  it("shows what is owed, the next payment and each card with its promo countdown in words", async () => {
    withCard(CARD);
    renderApp("/cards");

    const row = (await screen.findByRole("link", { name: /Harbor Visa/ })).closest("li")!;
    expect(row).toHaveTextContent("Statement balance · autopay · due the 15th");
    expect(row).toHaveTextContent("0% ends Mar 15 · 156 days left");
    expect(row).toHaveTextContent("$3,125.00");
    expect(screen.getByText("today, across 1 card")).toBeInTheDocument();
    expect(screen.getByText("Harbor Visa · Oct 15 · autopay")).toBeInTheDocument();
    expect(screen.getByText("first to end: Mar 15")).toBeInTheDocument();
    // The row is the way in: it opens the card's own page.
    expect(screen.getByRole("link", { name: /Harbor Visa/ })).toHaveAttribute("href", "/cards/3");
  });

  it("says in words when a promotion is close to its end", async () => {
    withCard({ ...CARD, promo: DEFERRED });
    renderApp("/cards");

    const row = (await screen.findByRole("link", { name: /Harbor Visa/ })).closest("li")!;
    expect(row).toHaveTextContent("40 days left · deferred interest");
  });
});

describe("a card's page", () => {
  it("shows the balance, payment setting, statements and the promo's progress", async () => {
    withCard(CARD);
    renderApp("/cards/3");

    expect(await screen.findByRole("heading", { level: 1, name: "Harbor Visa" })).toBeInTheDocument();
    expect(screen.getByText("Closes on the 20th · due on the 15th")).toBeInTheDocument();
    expect(screen.getByText("autopay · from Everyday Checking")).toBeInTheDocument();

    const promos = within(screen.getByRole("region", { name: "Promo rates" }));
    expect(promos.getByText(/0% ends Mar 15 · 156 days left/)).toBeInTheDocument();
    expect(promos.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "20");
    expect(promos.getByText(/20% paid/)).toHaveTextContent("$2,400.00 left of $3,000.00");

    const statements = within(screen.getByRole("region", { name: "Statements" })).getAllByRole("listitem");
    expect(statements[0]).toHaveTextContent("projected");
    expect(statements[0]).toHaveTextContent("Planned");
    expect(statements[1]).toHaveTextContent("Due Oct 15, 2026 · To pay");
    // Far from its end, a promotion has no warning banner.
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("warns in words before a promotion ends, more strongly when interest is deferred", async () => {
    withCard({ ...CARD, promo: DEFERRED }, {}, [DEFERRED]);
    renderApp("/cards/3");

    const banner = await screen.findByRole("alert");
    expect(banner).toHaveTextContent("Balance transfer: 0% ends Nov 19 · 40 days left");
    expect(banner).toHaveTextContent("After that, 24.49% APR on what's left.");
    expect(banner).toHaveTextContent("about $587.76 of interest for the whole promotion is charged at once");
    expect(within(screen.getByRole("region", { name: "If it's not paid off" })).getByText(/would cost about/)).toHaveTextContent("$48.98 a month");
  });

  it("adds the payoff as a recurring payment in one click", async () => {
    const fetchMock = withCard(CARD, {
      "POST /api/cards/3/promos/9/payoff": () => ({ status: 201, data: detailOf(CARD), changed: true }),
    });
    renderApp("/cards/3");

    const helper = within(await screen.findByRole("region", { name: "Payoff helper" }));
    expect(helper.getByText(/from each of the 10 paychecks/)).toHaveTextContent("$240.00 from each of the 10 paychecks before Mar 15, 2027 clears the $2,400.00 left.");
    await userEvent.click(helper.getByRole("button", { name: "Add as a recurring payment" }));

    await waitFor(() => expect(calls(fetchMock)).toContain("POST /api/cards/3/promos/9/payoff"));
  });

  it("says the payoff is already a recurring payment instead of offering it twice", async () => {
    const added = { ...TRANSFER, payoffItemId: 12 };
    withCard({ ...CARD, promo: added }, {}, [added]);
    renderApp("/cards/3");

    const helper = within(await screen.findByRole("region", { name: "Payoff helper" }));
    expect(helper.getByRole("link", { name: "recurring payment" })).toHaveAttribute("href", "/recurring/12");
    expect(helper.queryByRole("button", { name: "Add as a recurring payment" })).not.toBeInTheDocument();
  });

  it("asks for the statement and due days before the payment setting", async () => {
    const fetchMock = withCard(
      { ...CARD, settings: null, nextPayment: null, promo: null },
      { "PUT /api/cards/3/settings": () => ({ status: 200, data: detailOf(CARD), changed: true }) },
    );
    renderApp("/cards/3");

    expect(await screen.findByText("Set the statement and due dates first; then choose how this card is paid.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Set the dates" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/cards/3/settings")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "PUT /api/cards/3/settings")[0]).toMatchObject({
      statementCloseDay: 20,
      dueDay: 15,
      paymentMode: "statement_balance",
      payFromAccountId: null,
      monthlyEstimateCents: null,
    });
  });

  it("changes how the card is paid, asking for the amount when it is fixed", async () => {
    const fetchMock = withCard(CARD, { "PUT /api/cards/3/settings": () => ({ status: 200, data: detailOf(CARD), changed: true }) });
    renderApp("/cards/3");

    await userEvent.click(await screen.findByRole("button", { name: "Edit payment setting" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.selectOptions(within(dialog).getByLabelText("What you pay each month"), "fixed");
    // Nothing a month is not a payment.
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Amount each month" }), "0");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("Please enter the amount you pay each month, like 240.00.");

    await userEvent.type(within(dialog).getByRole("textbox", { name: "Amount each month" }), "{Backspace}240");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/cards/3/settings")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "PUT /api/cards/3/settings")[0]).toMatchObject({ paymentMode: "fixed", fixedPaymentCents: 24000, payFromAccountId: 1, autopay: true });
  });

  it("adds a promo rate in two short steps", async () => {
    const fetchMock = withCard({ ...CARD, promo: null }, { "POST /api/cards/3/promos": () => ({ status: 201, data: detailOf(CARD), changed: true }) }, []);
    renderApp("/cards/3");

    await userEvent.click(await screen.findByRole("button", { name: "+ Add a promo rate" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText("Name"), "Balance transfer");
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Balance it started with" }), "3000");
    await userEvent.clear(within(dialog).getByLabelText("Started on"));
    await userEvent.type(within(dialog).getByLabelText("Started on"), "2026-03-15");
    await userEvent.type(within(dialog).getByLabelText("Rate ends on"), "2027-03-15");
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    await userEvent.click(await within(dialog).findByLabelText("Deferred interest"));
    await userEvent.click(within(dialog).getByRole("button", { name: "Add promo rate" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "POST /api/cards/3/promos")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "POST /api/cards/3/promos")[0]).toEqual({
      label: "Balance transfer",
      kind: "balance_transfer",
      rateBp: 0,
      startDate: "2026-03-15",
      endDate: "2027-03-15",
      originalBalanceCents: 300000,
      rateAfterBp: null,
      deferredInterest: true,
    });
  });

  it("removes a statement that was entered, and offers nothing to remove on the projected one", async () => {
    const fetchMock = withCard(CARD, { "DELETE /api/cards/3/statements/4": () => ({ status: 200, data: detailOf(CARD), changed: true }) });
    renderApp("/cards/3");

    const statements = within(await screen.findByRole("region", { name: "Statements" }));
    expect(statements.getAllByRole("button", { name: /^Remove the statement/ })).toHaveLength(1);
    await userEvent.click(statements.getByRole("button", { name: "Remove the statement that closed Sep 20, 2026" }));
    await waitFor(() => expect(calls(fetchMock)).toContain("DELETE /api/cards/3/statements/4"));
  });

  it("says so kindly when the card can't be found", async () => {
    fakeBackend();
    renderApp("/cards/3");

    expect(await screen.findByRole("alert")).toHaveTextContent("We couldn't find that.");
    expect(screen.getByRole("link", { name: "Back to all cards" })).toHaveAttribute("href", "/cards");
  });
});

describe("a card on the pay period screen", () => {
  const summary = (start: string, end: string, current: boolean, promosEnding: PayPeriod["promosEnding"] = []): PayPeriod => ({
    start,
    end,
    scheduledPayday: start,
    current,
    incomeCents: 248620,
    paychecks: [],
    projectedEndingCents: 300000,
    lowestCents: 300000,
    availableCents: 300000,
    lowBalance: false,
    promosEnding,
  });
  const ENDING = { accountId: 3, promoId: 9, label: "Balance transfer", endDate: "2026-10-12", deferredInterest: true, remainingCents: 240000 };
  const PERIODS: PayPeriods = {
    primarySourceId: 1,
    primaryAccountId: 1,
    periods: [summary("2026-10-01", "2026-10-14", true, [ENDING]), summary("2026-10-15", "2026-10-31", false)],
  };
  const OCTOBER: PeriodDetail = {
    start: "2026-10-01",
    end: "2026-10-14",
    scheduledPayday: "2026-10-01",
    current: true,
    primaryAccountId: 1,
    paychecks: [],
    occurrences: [],
    carriedOver: [],
    entries: [],
    interest: [],
    buckets: [],
    bucketMoves: [],
    accounts: [
      { accountId: 1, startingCents: 500000, startingOn: "2026-10-01", entered: true, inCents: 0, outCents: 0, endingCents: 500000, lowestCents: 500000, lowestOn: "2026-10-01", bucketsCents: 0, availableCents: 500000, lowBalance: false, builtFromZero: false },
      { accountId: 3, startingCents: 312500, startingOn: "2026-10-01", entered: true, inCents: 0, outCents: 0, endingCents: 312500, lowestCents: 312500, lowestOn: "2026-10-01", bucketsCents: 0, availableCents: 312500, lowBalance: false, builtFromZero: false },
    ],
    promosEnding: [ENDING],
  };

  it("flags the period a promotion ends in, on the strip and in the cards panel", async () => {
    withCard(CARD, {
      "GET /api/pay-periods": () => ({ status: 200, data: PERIODS }),
      "GET /api/pay-periods/2026-10-01": () => ({ status: 200, data: OCTOBER }),
    });
    renderApp("/");

    const strip = within(await screen.findByRole("navigation", { name: "Ending balance by period" })).getAllByRole("listitem");
    expect(strip[0]).toHaveTextContent("Oct 1 · Promo rate ends");
    expect(strip[1]).not.toHaveTextContent("Promo");

    const panel = within(await screen.findByRole("region", { name: "Credit cards" }));
    expect(panel.getByRole("link", { name: "Harbor Visa" })).toHaveAttribute("href", "/cards/3");
    expect(panel.getByText("No payment this period")).toBeInTheDocument();
    expect(panel.getByText(/Balance transfer ends Oct 12/)).toHaveTextContent("Balance transfer ends Oct 12 with $2,400.00 left · deferred interest");
  });
});

describe("a card's own payment among the recurring items", () => {
  const PAYMENT: Item = {
    id: 5,
    name: "Harbor Visa payment",
    kind: "card_payment",
    note: null,
    archived: false,
    createdOn: "2026-10-01",
    tagIds: [],
    amountCents: 0,
    amountMode: "per_occurrence",
    estimate: true,
    categoryId: null,
    payFromAccountId: 1,
    payToAccountId: 3,
    autopay: true,
    placement: "due_period",
    schedule: { frequency: "monthly", interval: 1, day1: 15, day2: null, month: null, startDate: "2026-10-01", endDate: null, weekendRule: "none" },
    daysAfterPayday: 0,
    bucketId: null,
    saveUpBucketId: null,
    saveUpPerPaycheckCents: null,
    perOccurrenceCents: 0,
    perYearCents: 0,
    next: { scheduledDate: "2026-10-15", dueDate: "2026-10-15", periodStart: "2026-10-15", early: false },
    history: [{ effectiveFrom: "2026-10-01", perOccurrenceCents: 0, perYearCents: 0 }],
    paused: false,
    pausedUntil: null,
    fromBucketId: null,
    paymentOfCardId: 3,
    followsCard: true,
  };

  it("says it follows the card instead of showing an amount, and is edited on the card's page", async () => {
    withCard(CARD, { "GET /api/items": () => ({ status: 200, data: [PAYMENT] }) });
    renderApp("/recurring");

    const row = (await screen.findByRole("link", { name: "Harbor Visa payment" })).closest<HTMLElement>('[role="row"]')!;
    expect(row).toHaveTextContent("Follows the card");
    expect(row).not.toHaveTextContent("$0.00");

    await userEvent.click(screen.getByRole("link", { name: "Harbor Visa payment" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Harbor Visa payment" })).toBeInTheDocument();
    expect(screen.getByText("worked out from the card's payment setting for each statement")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit item" })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Edit on the card's page" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Harbor Visa" })).toBeInTheDocument();
  });
});
