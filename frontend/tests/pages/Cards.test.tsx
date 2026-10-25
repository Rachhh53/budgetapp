import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Account, AccountDetail } from "../../src/api/accounts";
import type { Card, CardDetail, CardRewards, CardSettings, Promo } from "../../src/api/cards";
import type { PayPeriod, PayPeriods } from "../../src/api/income";
import type { Item } from "../../src/api/items";
import type { Occurrence, PeriodDetail } from "../../src/api/periods";
import { bodiesSentTo, calls, DEFAULT_SETTINGS, fakeBackend, renderApp } from "../helpers";

const CHECKING: Account = {
  id: 1,
  name: "Everyday Checking",
  institution: null,
  type: "checking",
  subtypeLabel: null,
  color: "#0f766e",
  archived: false, archivedOn: null,
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

/** Rewards that come off each statement, with nothing built up. */
const NO_BUILD_UP: CardRewards = {
  rateBp: 150,
  buildUp: false,
  yearlyMonth: null,
  thresholdCents: null,
  startCents: null,
  startDate: null,
  builtUpCents: 0,
  nextCreditDate: null,
  nextCreditCents: null,
  redemptions: [],
};

function detailOf(card: Card, promos: Promo[] = card.promo ? [card.promo] : []): CardDetail {
  return {
    card,
    promos,
    statements: [
      { id: null, closeDate: "2026-10-20", balanceCents: 312500, minimumDueCents: 3125, dueDate: "2026-11-15", entered: false, paidCents: 0, status: "planned", recordsCents: null, reconcileFrom: null },
      { id: 4, closeDate: "2026-09-20", balanceCents: 72500, minimumDueCents: 2500, dueDate: "2026-10-15", entered: true, paidCents: 0, status: "due", recordsCents: null, reconcileFrom: null },
    ],
    rates: [{ id: 2, kind: "apr", rateBp: 2449, effectiveDate: "2026-01-01" }] as CardDetail["rates"],
    nextCloseDate: "2026-10-20",
    nextDueDate: "2026-11-15",
    minimum: { shareBp: 100, floorCents: 2500, plusInterest: true },
    rewardsBp: null,
    rewards: null,
  };
}

/** The card as the account it is: what every account's page starts from. */
function asAccount(account: Account): AccountDetail {
  return {
    account,
    balances: account.latestBalance ? [account.latestBalance] : [],
    interest: { creditDay: 31, destination: "unassigned", destinationBucketId: null },
    rates: [],
  };
}

function withCard(card: Card, overrides: Parameters<typeof fakeBackend>[0] = {}, promos?: Promo[]) {
  return fakeBackend({
    "GET /api/accounts": () => ({ status: 200, data: [CHECKING, card.account] }),
    "GET /api/cards": () => ({ status: 200, data: [card] }),
    "GET /api/accounts/3": () => ({ status: 200, data: asAccount(card.account) }),
    "GET /api/cards/3": () => ({ status: 200, data: detailOf(card, promos) }),
    ...overrides,
  });
}

describe("credit cards on the Accounts screen", () => {
  it("lists a card like every other account: a row that opens its page, with nothing extra on it", async () => {
    withCard(CARD);
    renderApp("/accounts");

    const group = within(await screen.findByRole("region", { name: "Credit cards" }));
    const link = await group.findByRole("link", { name: /Harbor Visa/ });
    // The row is the way in: it opens the card's own page.
    expect(link).toHaveAttribute("href", "/accounts/3");
    // How it is paid and its promotions are on that page, not on the row: no other group's rows carry more.
    expect(link).not.toHaveTextContent("Statement balance");
    expect(link).not.toHaveTextContent("0% ends");
    expect(group.queryByText("Next payment")).not.toBeInTheDocument();
    expect(group.queryByText("On a promo rate")).not.toBeInTheDocument();
  });

  it("has no screen of its own: there is no sidebar entry, and a card's address lands on its page", async () => {
    withCard(CARD);
    renderApp("/cards/3");

    expect(await screen.findByRole("heading", { level: 1, name: "Harbor Visa" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Accounts", current: false })).toHaveAttribute("href", "/accounts");
    expect(screen.queryByRole("link", { name: "Credit cards" })).not.toBeInTheDocument();
  });
});

describe("a card's page", () => {
  it("shows the balance, the next payment, statements and the promo's progress", async () => {
    withCard(CARD, {
      "GET /api/accounts/3/projection": () => ({
        status: 200,
        data: [{ start: "2026-10-01", end: "2026-10-14", endingCents: 72500, lowestCents: 72500, availableCents: 72500 }],
      }),
      "GET /api/accounts/3/spending": () => ({
        status: 200,
        data: [{ start: "2026-10-01", end: "2026-10-14", outCents: 4200, categories: [{ categoryId: null, cents: 4200 }] }],
      }),
    });
    renderApp("/accounts/3");

    expect(await screen.findByRole("heading", { level: 1, name: "Harbor Visa" })).toBeInTheDocument();
    expect(screen.getByText("Closes on the 20th · due on the 15th")).toBeInTheDocument();
    // The payment is shown, and set where it lives: on its recurring item's page.
    expect(screen.getByText("Oct 15, 2026 · by autopay")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Harbor Visa payment" })).toHaveAttribute("href", "/recurring/5");
    expect(screen.queryByText("Payment setting")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit payment setting" })).not.toBeInTheDocument();

    const promos = within(screen.getByRole("region", { name: "Promo rates" }));
    expect(promos.getByText(/0% ends Mar 15 · 156 days left/)).toBeInTheDocument();
    expect(promos.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "20");
    expect(promos.getByText(/20% paid/)).toHaveTextContent("$2,400.00 left of $3,000.00");

    const statements = within(screen.getByRole("region", { name: "Statements" })).getAllByRole("listitem");
    expect(statements[0]).toHaveTextContent("projected");
    expect(statements[0]).toHaveTextContent("Planned");
    expect(statements[1]).toHaveTextContent("Due Oct 15, 2026 · To pay");
    // Statements entered are real numbers, drawn solid on the chart like balances entered.
    const caption = await screen.findByText(/Solid: balances and statements you entered/);
    // What is owed: the most it comes to is worth knowing, not its low point.
    expect(caption).toHaveTextContent("The most it owes at the end of a pay period is $725.00, on Oct 14, 2026.");
    expect(caption).not.toHaveTextContent("lowest point");
    // What is charged to the card each pay period, in a card's words (Rachel, 2026-10-24: "it needs to be on THOSE too").
    const charged = await screen.findByRole("region", { name: "Charged each pay period" });
    expect(charged).toHaveTextContent("On average $42.00 was charged each pay period");
    expect(charged).toHaveTextContent("payments toward it don't");
    // Far from its end, a promotion has no warning banner.
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("draws the chart for a card with statements entered and no balance entered", async () => {
    withCard({ ...CARD, account: { ...CARD.account, latestBalance: null } }, {
      "GET /api/accounts/3/projection": () => ({
        status: 200,
        data: [{ start: "2026-10-01", end: "2026-10-14", endingCents: 72500, lowestCents: 72500, availableCents: 72500 }],
      }),
    });
    renderApp("/accounts/3");

    expect(await screen.findByRole("region", { name: "Where it's heading" })).toHaveTextContent("Solid: balances and statements you entered");
  });

  it("says quietly when a statement and the records differ, and opens what went in to fix it", async () => {
    const detail = detailOf(CARD);
    detail.statements[1] = { ...detail.statements[1]!, recordsCents: 71700, reconcileFrom: "2026-08-21" };
    const lunch = {
      date: "2026-09-02",
      label: "Lunch",
      kind: "money_out",
      amountCents: 4000,
      unpaid: false,
      estimatedCents: null,
      source: { type: "entry", entryId: 12, itemId: null, scheduledDate: null, dueDate: null, periodStart: "2026-09-01" },
    };
    withCard(CARD, {
      "GET /api/cards/3": () => ({ status: 200, data: detail }),
      "GET /api/accounts/3/transactions": () => ({ status: 200, data: [lunch] }),
    });
    renderApp("/accounts/3");

    const statements = within(await screen.findByRole("region", { name: "Statements" }));
    // In words, not red: by how much, and which way.
    expect(statements.getByText(/Your records came to \$717.00, \$8.00 less than what you entered\./)).toBeInTheDocument();
    await userEvent.click(statements.getByRole("button", { name: "See what went in" }));

    expect(await statements.findByRole("button", { name: "Lunch" })).toBeInTheDocument();
    expect(statements.getByText("Aug 21 – Sep 20, newest first. Open one to change or delete it.")).toBeInTheDocument();
    expect(statements.getByRole("button", { name: "+ Add a charge" })).toBeInTheDocument();
  });

  it("warns in words before a promotion ends, more strongly when interest is deferred", async () => {
    withCard({ ...CARD, promo: DEFERRED }, {}, [DEFERRED]);
    renderApp("/accounts/3");

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
    renderApp("/accounts/3");

    const helper = within(await screen.findByRole("region", { name: "Payoff helper" }));
    expect(helper.getByText(/from each of the 10 paychecks/)).toHaveTextContent("$240.00 from each of the 10 paychecks before Mar 15, 2027 clears the $2,400.00 left.");
    await userEvent.click(helper.getByRole("button", { name: "Add as a recurring payment" }));

    await waitFor(() => expect(calls(fetchMock)).toContain("POST /api/cards/3/promos/9/payoff"));
  });

  it("says the payoff is already a recurring payment instead of offering it twice", async () => {
    const added = { ...TRANSFER, payoffItemId: 12 };
    withCard({ ...CARD, promo: added }, {}, [added]);
    renderApp("/accounts/3");

    const helper = within(await screen.findByRole("region", { name: "Payoff helper" }));
    expect(helper.getByRole("link", { name: "recurring payment" })).toHaveAttribute("href", "/recurring/12");
    expect(helper.queryByRole("button", { name: "Add as a recurring payment" })).not.toBeInTheDocument();
  });

  it("saves the statement and due days, and nothing about how the card is paid", async () => {
    const fetchMock = withCard(
      { ...CARD, settings: null, nextPayment: null, promo: null },
      { "PUT /api/cards/3/settings": () => ({ status: 200, data: detailOf(CARD), changed: true }) },
    );
    renderApp("/accounts/3");

    await userEvent.click(await screen.findByRole("button", { name: "Set the dates" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/cards/3/settings")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "PUT /api/cards/3/settings")[0]).toEqual({ statementCloseDay: 20, dueDay: 15, monthlyEstimateCents: null });
  });

  it("offers to add the card's payment as a recurring item, already pointed at the card", async () => {
    withCard({ ...CARD, settings: { ...SETTINGS, paymentItemId: null, payFromAccountId: null }, nextPayment: null });
    renderApp("/accounts/3");

    expect(await screen.findByText("No recurring payment yet.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Add it as a recurring payment" }));

    const dialog = await screen.findByRole("dialog", { name: "Add a recurring item" });
    expect(within(dialog).getByRole("radio", { name: "Card payment" })).toBeChecked();
    expect(within(dialog).getByLabelText("Name")).toHaveValue("Harbor Visa payment");
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    // With the card's dates in, it starts out following the statement, so there is no amount to type.
    expect(await within(dialog).findByLabelText("How much it pays")).toHaveValue("statement_balance");
    expect(within(dialog).queryByRole("textbox", { name: "Amount each time" })).not.toBeInTheDocument();
  });

  it("says how the minimum payment is estimated, and lets it be changed to match the card", async () => {
    const fetchMock = withCard(CARD, { "PUT /api/cards/3/minimum": () => ({ status: 200, data: detailOf(CARD), changed: true }) });
    renderApp("/accounts/3");

    const section = within(await screen.findByRole("region", { name: "Minimum payment estimate" }));
    expect(section.getByText("1% of the statement balance plus that month's interest, and at least $25.00")).toBeInTheDocument();
    await userEvent.click(section.getByRole("button", { name: "Edit minimum payment estimate" }));

    const dialog = await screen.findByRole("dialog");
    const share = within(dialog).getByLabelText("Share of the statement balance (%)");
    expect(share).toHaveValue("1.00");
    // More than the whole balance is not a share of it.
    await userEvent.clear(share);
    await userEvent.type(share, "150");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("Please enter the share of the balance as a percentage from 0 to 100, like 1.00.");

    await userEvent.clear(share);
    await userEvent.type(share, "2");
    await userEvent.clear(within(dialog).getByRole("textbox", { name: "At least" }));
    await userEvent.type(within(dialog).getByRole("textbox", { name: "At least" }), "35");
    await userEvent.click(within(dialog).getByRole("checkbox", { name: "Plus that month's interest" }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/cards/3/minimum")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "PUT /api/cards/3/minimum")[0]).toEqual({ shareBp: 200, floorCents: 3500, plusInterest: false });
  });

  it("sets the card's cash back, which comes off at each statement close, and takes it away again", async () => {
    const fetchMock = withCard(CARD, { "PUT /api/cards/3/rewards": () => ({ status: 200, data: detailOf(CARD), changed: true }) });
    renderApp("/accounts/3");

    const section = within(await screen.findByRole("region", { name: "Rewards" }));
    expect(section.getByText(/No rewards set/)).toBeInTheDocument();
    await userEvent.click(section.getByRole("button", { name: "Edit rewards" }));
    const dialog = await screen.findByRole("dialog", { name: "Rewards — Harbor Visa" });
    const rate = within(dialog).getByLabelText("Cash back on what is charged (%)");
    await userEvent.type(rate, "25");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please enter the cash back as a percentage from 0.01 to 20, like 1.50.");

    await userEvent.clear(rate);
    await userEvent.type(rate, "1.5");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/cards/3/rewards")).toEqual([
      { rateBp: 150, buildUp: false, yearlyMonth: null, thresholdCents: null, startCents: null, startDate: null },
    ]));
  });

  it("shows a card's rewards and can take them away", async () => {
    const fetchMock = withCard(CARD, {
      "GET /api/cards/3": () => ({ status: 200, data: { ...detailOf(CARD), rewardsBp: 150, rewards: { ...NO_BUILD_UP, rateBp: 150 } } }),
      "PUT /api/cards/3/rewards": () => ({ status: 200, data: detailOf(CARD), changed: true }),
    });
    renderApp("/accounts/3");

    const section = within(await screen.findByRole("region", { name: "Rewards" }));
    expect(section.getByText("1.50% cash back on what is charged")).toBeInTheDocument();
    await userEvent.click(section.getByRole("button", { name: "Edit rewards" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByLabelText("Cash back on what is charged (%)")).toHaveValue("1.50");
    await userEvent.click(within(dialog).getByRole("button", { name: "No rewards" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/cards/3/rewards")).toEqual([
      { rateBp: null, buildUp: false, yearlyMonth: null, thresholdCents: null, startCents: null, startDate: null },
    ]));
  });

  it("lets rewards build up, used every year in a month, from what the card says has built up", async () => {
    const fetchMock = withCard(CARD, {
      "GET /api/cards/3": () => ({ status: 200, data: { ...detailOf(CARD), rewardsBp: 150, rewards: NO_BUILD_UP } }),
      "PUT /api/cards/3/rewards": () => ({ status: 200, data: detailOf(CARD), changed: true }),
    });
    renderApp("/accounts/3");

    const section = within(await screen.findByRole("region", { name: "Rewards" }));
    await userEvent.click(section.getByRole("button", { name: "Edit rewards" }));
    const dialog = await screen.findByRole("dialog", { name: "Rewards — Harbor Visa" });
    await userEvent.click(within(dialog).getByRole("radio", { name: "Let them build up" }));
    await userEvent.type(within(dialog).getByLabelText("Built up so far"), "42.10");
    await userEvent.clear(within(dialog).getByLabelText("As of"));
    await userEvent.type(within(dialog).getByLabelText("As of"), "2026-10-01");
    await userEvent.selectOptions(within(dialog).getByLabelText("Use them all on their own"), "yearly");
    await userEvent.selectOptions(within(dialog).getByLabelText("Month"), "11");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "PUT /api/cards/3/rewards")).toEqual([
        { rateBp: 150, buildUp: true, yearlyMonth: 11, thresholdCents: null, startCents: 4210, startDate: "2026-10-01" },
      ]),
    );
  });

  it("shows what has built up and redeems it as a statement credit", async () => {
    const built: CardRewards = {
      ...NO_BUILD_UP,
      buildUp: true,
      thresholdCents: 5000,
      builtUpCents: 3210,
      nextCreditDate: "2026-12-20",
      nextCreditCents: 5240,
      redemptions: [{ id: 4, date: "2026-11-02", amountCents: 1000, note: "Gift", creditedCents: 1000 }],
    };
    const fetchMock = withCard(CARD, {
      "GET /api/cards/3": () => ({ status: 200, data: { ...detailOf(CARD), rewardsBp: 150, rewards: built } }),
      "POST /api/cards/3/rewards/redemptions": () => ({ status: 201, data: { id: 5 }, changed: true }),
    });
    renderApp("/accounts/3");

    const region = await screen.findByRole("region", { name: "Rewards" });
    expect(region).toHaveTextContent("Built up today$32.10");
    expect(region).toHaveTextContent("All of it goes on once it reaches $50.00.");
    expect(region).toHaveTextContent("Next credit: $52.40 on Dec 20, 2026");
    expect(region).toHaveTextContent("Nov 2, 2026A set amount · Gift$10.00");
    await userEvent.click(within(region).getByRole("button", { name: "Redeem" }));
    const dialog = await screen.findByRole("dialog", { name: "Redeem rewards" });
    expect(dialog).toHaveTextContent("$32.10 has built up as of today.");
    await userEvent.clear(within(dialog).getByLabelText("Day it goes on"));
    await userEvent.type(within(dialog).getByLabelText("Day it goes on"), "2026-11-15");
    await userEvent.click(within(dialog).getByRole("button", { name: "Redeem" }));

    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "POST /api/cards/3/rewards/redemptions")).toEqual([{ date: "2026-11-15", amountCents: null, note: null }]),
    );
  });

  it("adds a promo rate on everything the card owes, with no amount and no deferred interest", async () => {
    const fetchMock = withCard({ ...CARD, promo: null }, { "POST /api/cards/3/promos": () => ({ status: 201, data: detailOf(CARD), changed: true }) }, []);
    renderApp("/accounts/3");

    await userEvent.click(await screen.findByRole("button", { name: "+ Add a promo rate" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText("Name"), "Intro rate");
    await userEvent.clear(within(dialog).getByLabelText("Started on"));
    await userEvent.type(within(dialog).getByLabelText("Started on"), "2026-10-01");
    await userEvent.type(within(dialog).getByLabelText("Rate ends on"), "2027-10-01");
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    // Deferred interest is charged on a set amount, so it is not offered here.
    expect(within(dialog).queryByLabelText("Deferred interest")).not.toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "Add promo rate" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "POST /api/cards/3/promos")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "POST /api/cards/3/promos")[0]).toMatchObject({
      label: "Intro rate",
      originalBalanceCents: null,
      deferredInterest: false,
    });
  });

  it("shows a promo on everything without a progress bar, since it has no starting amount", async () => {
    const everything: Promo = { ...TRANSFER, label: "Intro rate", originalBalanceCents: null, paidPercent: null, remainingCents: 312500 };
    withCard({ ...CARD, promo: everything }, {}, [everything]);
    renderApp("/accounts/3");

    const promos = within(await screen.findByRole("region", { name: "Promo rates" }));
    expect(promos.getByText(/on this rate/)).toHaveTextContent("$3,125.00 on this rate today · everything on the card");
    expect(promos.queryByRole("progressbar")).not.toBeInTheDocument();
  });

  it("adds a promo rate in two short steps", async () => {
    const fetchMock = withCard({ ...CARD, promo: null }, { "POST /api/cards/3/promos": () => ({ status: 201, data: detailOf(CARD), changed: true }) }, []);
    renderApp("/accounts/3");

    await userEvent.click(await screen.findByRole("button", { name: "+ Add a promo rate" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText("Name"), "Balance transfer");
    // A new promo starts as "everything on the card", which asks for no amount; a balance transfer is a set amount.
    expect(within(dialog).getByRole("radio", { name: "Everything on the card" })).toBeChecked();
    expect(within(dialog).queryByRole("textbox", { name: "Amount on this rate" })).not.toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("radio", { name: "A set amount" }));
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Amount on this rate" }), "3000");
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

  it("offers a promo's rate afterwards as the regular APR while the card has none", async () => {
    const after = { ...TRANSFER, rateAfterBp: 2449 };
    const fetchMock = fakeBackend({
      "GET /api/accounts": () => ({ status: 200, data: [CHECKING, VISA] }),
      "GET /api/accounts/3": () => ({ status: 200, data: asAccount(VISA) }),
      "GET /api/cards/3": () => ({ status: 200, data: { ...detailOf({ ...CARD, aprBp: null, promo: after }, [after]), rates: [] } }),
      "POST /api/accounts/3/rates": () => ({ status: 201, data: asAccount(VISA), changed: true }),
    });
    renderApp("/accounts/3");

    const apr = within(await screen.findByRole("region", { name: "Regular APR" }));
    expect(apr.getByText(/You entered 24.49% as the rate after Balance transfer/)).toBeInTheDocument();
    await userEvent.click(apr.getByRole("button", { name: "Use 24.49% as the Regular APR" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "POST /api/accounts/3/rates")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "POST /api/accounts/3/rates")[0]).toMatchObject({ rateBp: 2449 });
  });

  it("removes a statement that was entered, and offers nothing to remove on the projected one", async () => {
    const fetchMock = withCard(CARD, { "DELETE /api/cards/3/statements/4": () => ({ status: 200, data: detailOf(CARD), changed: true }) });
    renderApp("/accounts/3");

    const statements = within(await screen.findByRole("region", { name: "Statements" }));
    expect(statements.getAllByRole("button", { name: /^Remove the statement/ })).toHaveLength(1);
    await userEvent.click(statements.getByRole("button", { name: "Remove the statement that closed Sep 20, 2026" }));
    await waitFor(() => expect(calls(fetchMock)).toContain("DELETE /api/cards/3/statements/4"));
  });

  it("opens a new statement on the one closing in the month of the day chosen in Settings", async () => {
    // Rachel, 2026-10-22: "add a statement in account is defaulting to 2025 when my default date everywhere else is
    // 01012026". Closing on the 20th, Jan 1 opened it on Dec 20.
    withCard(CARD, { "GET /api/settings": () => ({ status: 200, data: { ...DEFAULT_SETTINGS, default_date: "2026-01-01" } }) });
    renderApp("/accounts/3");

    await userEvent.click(await screen.findByRole("button", { name: "+ Add a statement" }));
    const dialog = screen.getByRole("dialog", { name: "Add a statement — Harbor Visa" });
    expect(within(dialog).getByLabelText("Closed on")).toHaveValue("2026-01-20");
  });

  it("changes a statement that was entered, and offers nothing to change on the projected one", async () => {
    const fetchMock = withCard(CARD, { "PUT /api/cards/3/statements/4": () => ({ status: 200, data: detailOf(CARD), changed: true }) });
    renderApp("/accounts/3");

    // Rachel, 2026-10-21: "I want the ability to edit basically everything I enter. namely statements".
    const statements = within(await screen.findByRole("region", { name: "Statements" }));
    expect(statements.getAllByRole("button", { name: /^Edit the statement/ })).toHaveLength(1);
    await userEvent.click(statements.getByRole("button", { name: "Edit the statement that closed Sep 20, 2026" }));
    const dialog = screen.getByRole("dialog", { name: "Change statement — Harbor Visa" });
    const balance = within(dialog).getByRole("textbox", { name: "Statement balance" });
    expect(balance).toHaveValue("725.00");
    await userEvent.clear(balance);
    await userEvent.type(balance, "730");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save statement" }));

    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "PUT /api/cards/3/statements/4")).toEqual([
        { closeDate: "2026-09-20", balanceCents: 73000, minimumDueCents: 2500, dueDate: "2026-10-15" },
      ]),
    );
  });

  it("says so kindly when the card can't be found", async () => {
    withCard(CARD, { "GET /api/cards/3": () => ({ status: 404, error: "We couldn't find that." }) });
    renderApp("/accounts/3");

    expect(await screen.findByRole("alert")).toHaveTextContent("We couldn't find that.");
    expect(screen.getByRole("link", { name: "Back to all accounts" })).toHaveAttribute("href", "/accounts");
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
      { accountId: 1, startingCents: 500000, startingBucketsCents: 0, startingAvailableCents: 500000, startingOn: "2026-10-01", entered: true, inCents: 0, outCents: 0, adjustedCents: 0, adjustments: [], endingCents: 500000, lowestCents: 500000, lowestOn: "2026-10-01", bucketsCents: 0, availableCents: 500000, lowBalance: false, builtFromZero: false },
      { accountId: 3, startingCents: 312500, startingBucketsCents: 0, startingAvailableCents: 312500, startingOn: "2026-10-01", entered: true, inCents: 0, outCents: 0, adjustedCents: 0, adjustments: [], endingCents: 312500, lowestCents: 312500, lowestOn: "2026-10-01", bucketsCents: 0, availableCents: 312500, lowBalance: false, builtFromZero: false },
    ],
    promosEnding: [ENDING],
  };

  it("flags the period a promotion ends in, on the strip and with the card in Accounts & buckets, with no panel of its own", async () => {
    withCard(CARD, {
      "GET /api/pay-periods": () => ({ status: 200, data: PERIODS }),
      "GET /api/pay-periods/2026-10-01": () => ({ status: 200, data: OCTOBER }),
    });
    renderApp("/");

    const strip = within(await screen.findByRole("navigation", { name: "Ending balance by period" })).getAllByRole("listitem");
    expect(strip[0]).toHaveTextContent("Oct 1 · Promo rate ends");
    expect(strip[1]).not.toHaveTextContent("Promo");

    // A card is an account like the rest: one entry in Accounts & buckets, and nowhere else on the screen.
    expect(screen.queryByRole("region", { name: "Credit cards" })).not.toBeInTheDocument();
    const panel = within(await screen.findByRole("region", { name: "Accounts & buckets" }));
    // The warning belongs to the card, so it shows only while the card is the account chosen.
    expect(panel.queryByText(/Balance transfer ends Oct 12/)).not.toBeInTheDocument();
    await userEvent.selectOptions(panel.getByLabelText("Account"), "Harbor Visa");
    expect(panel.getByText("Owed at period end")).toBeInTheDocument();
    expect(panel.getByText(/Balance transfer ends Oct 12/)).toHaveTextContent("Balance transfer ends Oct 12 with $2,400.00 left · deferred interest");
  });
});

/** The card's own payment as a recurring item: it follows the statement balance, so it has no amount of its own. */
const PAYMENT: Item = (() => {
  const item: Item = {
    id: 5,
    name: "Harbor Visa payment",
    kind: "card_payment",
    note: null,
    archived: false, archivedOn: null,
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
    history: [{ versionId: 1, effectiveFrom: "2026-10-01", perOccurrenceCents: 0, perYearCents: 0, categoryId: null }],
    paused: false,
    pausedUntil: null,
    fromBucketId: null,
    paymentOfCardId: 3,
    followsCard: true,
    cardPaymentMode: "statement_balance",
  };
  return item;
})();

describe("a card payment on the pay period screen", () => {
  const occurrence: Occurrence = {
    itemId: 5,
    name: "Harbor Visa payment",
    kind: "card_payment",
    scheduledDate: "2026-10-15",
    dueDate: "2026-10-15",
    periodStart: "2026-10-15",
    early: false,
    payBy: "2026-10-15",
    amountCents: 9982,
    estimate: true,
    projectedCents: null,
    autopay: true,
    placement: "due_period",
    categoryId: null,
    payFromAccountId: 1,
    payToAccountId: 3,
    paid: false,
    paidDate: null,
    confirmationCode: null,
    note: null,
    skipped: false,
    bucketId: null,
    saveUpBucketId: null,
    fromBucketId: null,
    checkNumber: null,
  };
  const period = (statementsPaid: PeriodDetail["statementsPaid"]): PeriodDetail => ({
    start: "2026-10-15",
    end: "2026-10-29",
    scheduledPayday: "2026-10-15",
    current: true,
    primaryAccountId: 1,
    paychecks: [],
    occurrences: [occurrence],
    carriedOver: [],
    entries: [],
    interest: [],
    buckets: [],
    bucketMoves: [],
    accounts: [],
    promosEnding: [],
    statementsPaid,
  });
  const PERIODS: PayPeriods = {
    primarySourceId: 1,
    primaryAccountId: 1,
    periods: [
      {
        start: "2026-10-15",
        end: "2026-10-29",
        scheduledPayday: "2026-10-15",
        current: true,
        incomeCents: 248620,
        paychecks: [],
        projectedEndingCents: 300000,
        lowestCents: 300000,
        availableCents: 300000,
        lowBalance: false,
        promosEnding: [],
      },
    ],
  };
  const show = (statementsPaid: PeriodDetail["statementsPaid"]) => {
    withCard(CARD, {
      "GET /api/items": () => ({ status: 200, data: [{ ...PAYMENT, cardPaymentMode: "minimum" }] }),
      "GET /api/pay-periods": () => ({ status: 200, data: PERIODS }),
      "GET /api/pay-periods/2026-10-15": () => ({ status: 200, data: period(statementsPaid) }),
    });
    renderApp("/");
  };

  it("says an estimate is for a statement that has not closed yet", async () => {
    show([{ itemId: 5, scheduledDate: "2026-10-15", closeDate: "2099-10-20", entered: false }]);

    const panel = within(await screen.findByRole("region", { name: "Auto-pay & transfers" }));
    expect(await panel.findByText("Minimum payment, estimated for the statement closing Oct 20")).toBeInTheDocument();
  });

  it("says which entered statement a real amount comes from", async () => {
    show([{ itemId: 5, scheduledDate: "2026-10-15", closeDate: "2026-09-20", entered: true }]);

    const panel = within(await screen.findByRole("region", { name: "Auto-pay & transfers" }));
    expect(await panel.findByText("Minimum payment on the statement that closed Sep 20")).toBeInTheDocument();
  });

  it("says nothing about a statement for a payment that follows none", async () => {
    show([]);

    const panel = within(await screen.findByRole("region", { name: "Auto-pay & transfers" }));
    expect(await panel.findByRole("button", { name: "Edit Harbor Visa payment" })).toBeInTheDocument();
    expect(panel.queryByText(/statement/)).not.toBeInTheDocument();
  });
});

describe("a card's own payment among the recurring items", () => {
  it("says how it follows the card instead of showing an amount, and is changed on its own page like any item", async () => {
    const fetchMock = withCard(CARD, {
      "GET /api/items": () => ({ status: 200, data: [PAYMENT] }),
      "PUT /api/items/5": () => ({ status: 200, data: PAYMENT, changed: true }),
    });
    renderApp("/recurring");

    const row = (await screen.findByRole("link", { name: "Harbor Visa payment" })).closest<HTMLElement>('[role="row"]')!;
    expect(row).toHaveTextContent("Follows the card");
    expect(row).not.toHaveTextContent("$0.00");

    await userEvent.click(screen.getByRole("link", { name: "Harbor Visa payment" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Harbor Visa payment" })).toBeInTheDocument();
    expect(screen.getByText("Statement balance")).toBeInTheDocument();
    expect(screen.getByText("worked out from the card each time")).toBeInTheDocument();
    // Every card of the page has its Edit, as on any other item: nothing sends her to the card's page.
    expect(screen.getByRole("button", { name: "Edit item" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit the schedule" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit where it is paid from" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit on the card's page" })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Change the amount of Harbor Visa payment" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByLabelText("How much it pays")).toHaveValue("statement_balance");
    expect(within(dialog).queryByRole("textbox", { name: "Amount each time" })).not.toBeInTheDocument();
    await userEvent.selectOptions(within(dialog).getByLabelText("How much it pays"), "fixed");
    await userEvent.clear(within(dialog).getByRole("textbox", { name: "Amount each time" }));
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Amount each time" }), "240");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/items/5")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "PUT /api/items/5")[0]).toMatchObject({
      kind: "card_payment",
      cardPaymentMode: "fixed",
      amountCents: 24000,
      payToAccountId: 3,
    });
  });
});
