import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Account } from "../../src/api/accounts";
import type { Bucket } from "../../src/api/buckets";
import type { Category } from "../../src/api/categories";
import type { Entry } from "../../src/api/entries";
import type { PayPeriod, PayPeriods, Paycheck } from "../../src/api/income";
import type { Item } from "../../src/api/items";
import type { Occurrence, PeriodDetail } from "../../src/api/periods";
import { addDays, todayIso } from "../../src/lib/dates";
import { bodiesSentTo, calls, DEFAULT_SETTINGS, fakeBackend, renderApp } from "../helpers";

const CHECKING: Account = {
  id: 1,
  name: "Everyday Checking",
  institution: null,
  type: "checking",
  subtypeLabel: null,
  color: "#0f766e",
  archived: false,
  lowBalanceThresholdCents: null,
  latestBalance: { id: 1, date: "2026-10-01", amountCents: 121438, note: null },
};
const SAVINGS: Account = { ...CHECKING, id: 2, name: "Rainy Day Savings", type: "savings" };
const VISA: Account = { ...CHECKING, id: 3, name: "Visa", type: "credit_card" };

const PETS: Category = { id: 1, name: "Pets", color: "#a16207", parentId: null, itemCount: 0, perYearCents: 0 };

const PAYCHECK: Paycheck = {
  incomeSourceId: 1,
  sourceName: "Northwind Health",
  depositAccountId: 1,
  scheduledDate: "2026-10-01",
  payDate: "2026-10-01",
  netCents: 248620,
  estimate: false,
  projectedCents: null,
  contributions: [],
};

const ELECTRIC: Occurrence = {
  itemId: 2,
  name: "Electric",
  kind: "bill",
  scheduledDate: "2026-10-16",
  dueDate: "2026-10-16",
  periodStart: "2026-10-01",
  early: true,
  payBy: "2026-10-14",
  amountCents: 14000,
  estimate: true,
  projectedCents: null,
  autopay: false,
  placement: "one_early",
  categoryId: null,
  payFromAccountId: 1,
  payToAccountId: null,
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
const VISA_PAYMENT: Occurrence = {
  ...ELECTRIC,
  itemId: 5,
  name: "Visa payment",
  kind: "card_payment",
  scheduledDate: "2026-10-13",
  dueDate: "2026-10-13",
  early: false,
  payBy: "2026-10-13",
  amountCents: 61248,
  estimate: false,
  placement: "due_period",
  payToAccountId: 3,
  paid: true,
  paidDate: "2026-10-07",
  confirmationCode: "VX93-2210",
};
const CAR_LOAN: Occurrence = {
  ...ELECTRIC,
  itemId: 6,
  name: "Car loan",
  scheduledDate: "2026-10-05",
  dueDate: "2026-10-05",
  early: false,
  payBy: "2026-10-05",
  amountCents: 41200,
  estimate: false,
  autopay: true,
  placement: "due_period",
};
const GROCERIES: Occurrence = {
  ...ELECTRIC,
  itemId: 3,
  name: "Groceries",
  kind: "budget",
  scheduledDate: "2026-10-01",
  dueDate: "2026-10-01",
  early: false,
  payBy: "2026-10-01",
  amountCents: 30000,
  estimate: false,
  placement: "due_period",
};

const GIFT_FUND: Occurrence = {
  ...ELECTRIC,
  itemId: 8,
  name: "Gift fund",
  kind: "bucket_move",
  scheduledDate: "2026-10-01",
  dueDate: "2026-10-01",
  early: false,
  payBy: "2026-10-01",
  amountCents: 5000,
  estimate: false,
  autopay: true,
  placement: "due_period",
  bucketId: 7,
};
const GIFTS: Bucket = {
  id: 7,
  accountId: 1,
  name: "Holiday gifts",
  color: "#b45309",
  goalCents: 60000,
  note: null,
  archived: false,
  openedOn: "2026-09-01",
  goalDate: null,
  stopAtGoal: false,
  goalProgress: null,
  balanceCents: 20000,
};

const VET: Entry = {
  id: 9,
  kind: "money_out",
  date: "2026-10-03",
  amountCents: 64000,
  estimate: false,
  description: "Vet emergency",
  payee: "Maple Street Vets",
  categoryId: 1,
  accountId: 1,
  toAccountId: null,
  note: null,
  checkNumber: null,
  tagIds: [],
  bucketSplits: [],
  reimbursement: { status: "none", submittedOn: null, to: null, paidBackCents: 0, reimbursedCents: 0, owedCents: 0 },
  reimbursesEntryId: null,
  fileCount: 0,
};
const BONUS: Entry = {
  ...VET,
  id: 10,
  kind: "money_in",
  date: "2026-10-09",
  amountCents: 150000,
  estimate: true,
  description: "Q3 bonus",
  payee: null,
  categoryId: null,
  reimbursement: null,
};

const ELECTRIC_ITEM: Item = {
  id: 2,
  name: "Electric",
  kind: "bill",
  note: null,
  archived: false,
  createdOn: "2026-01-05",
  tagIds: [],
  amountCents: 14000,
  amountMode: "per_occurrence",
  estimate: true,
  categoryId: null,
  payFromAccountId: 1,
  payToAccountId: null,
  autopay: false,
  placement: "one_early",
  schedule: { frequency: "monthly", interval: null, day1: 16, day2: null, month: null, startDate: "2026-01-01", endDate: null, weekendRule: "none" },
  daysAfterPayday: 0,
  bucketId: null,
  saveUpBucketId: null,
  saveUpPerPaycheckCents: null,
  perOccurrenceCents: 14000,
  perYearCents: 168000,
  next: { scheduledDate: "2026-10-16", dueDate: "2026-10-16", periodStart: "2026-10-01", early: true },
  history: [{ effectiveFrom: "2026-01-01", perOccurrenceCents: 14000, perYearCents: 168000 }],
  paused: false,
  pausedUntil: null,
  fromBucketId: null,
};

const OCTOBER: PeriodDetail = {
  start: "2026-10-01",
  end: "2026-10-14",
  scheduledPayday: "2026-10-01",
  current: true,
  primaryAccountId: 1,
  paychecks: [PAYCHECK],
  occurrences: [ELECTRIC, VISA_PAYMENT, CAR_LOAN, GROCERIES],
  carriedOver: [],
  entries: [VET, BONUS],
  interest: [],
  buckets: [],
  bucketMoves: [],
  accounts: [
    { accountId: 1, startingCents: 121438, startingOn: "2026-10-01", entered: true, inCents: 398620, outCents: 210448, endingCents: 309610, lowestCents: 31390, lowestOn: "2026-10-08", bucketsCents: 0, availableCents: 309610, lowBalance: false, builtFromZero: false },
    { accountId: 3, startingCents: 61248, startingOn: "2026-10-01", entered: true, inCents: 0, outCents: 61248, endingCents: 0, lowestCents: 0, lowestOn: "2026-10-13", bucketsCents: 0, availableCents: 0, lowBalance: false, builtFromZero: false },
  ],
};

function summary(start: string, end: string, endingCents: number | null, current = false): PayPeriod {
  return { start, end, scheduledPayday: start, current, incomeCents: 248620, paychecks: [], projectedEndingCents: endingCents, lowestCents: endingCents, availableCents: endingCents, lowBalance: false };
}

const PERIODS: PayPeriods = {
  primarySourceId: 1,
  primaryAccountId: 1,
  periods: [
    summary("2026-09-15", "2026-09-30", null),
    summary("2026-10-01", "2026-10-14", 309610, true),
    summary("2026-10-15", "2026-10-31", 412500),
  ],
};

const NOVEMBER_START: PeriodDetail = { ...OCTOBER, start: "2026-10-15", end: "2026-10-31", current: false, occurrences: [], entries: [], paychecks: [] };

function withPeriod(detail: PeriodDetail = OCTOBER, overrides: Parameters<typeof fakeBackend>[0] = {}) {
  return fakeBackend({
    "GET /api/accounts": () => ({ status: 200, data: [CHECKING, SAVINGS, VISA] }),
    "GET /api/categories": () => ({ status: 200, data: [PETS] }),
    "GET /api/items": () => ({ status: 200, data: [ELECTRIC_ITEM] }),
    "GET /api/pay-periods": () => ({ status: 200, data: PERIODS }),
    "GET /api/pay-periods/2026-10-01": () => ({ status: 200, data: detail }),
    "GET /api/pay-periods/2026-10-15": () => ({ status: 200, data: NOVEMBER_START }),
    ...overrides,
  });
}

/** The titled panel of the screen, once the period has loaded. */
async function panel(name: string): Promise<HTMLElement> {
  return screen.findByRole("region", { name });
}

describe("pay period screen: investments", () => {
  const RETIREMENT: Account = { ...CHECKING, id: 4, name: "Brightwater 401(k)", type: "investment" };
  const WITH_RETIREMENT: PeriodDetail = {
    ...OCTOBER,
    paychecks: [
      {
        ...PAYCHECK,
        contributions: [
          { toAccountId: 4, label: "401(k)", kind: "contribution", amountCents: 15000 },
          { toAccountId: 4, label: "Employer match", kind: "employer_match", amountCents: 7500 },
        ],
      },
    ],
    interest: [{ accountId: 4, date: "2026-10-14", amountCents: 22871, rateBp: 700 }],
    accounts: [
      ...OCTOBER.accounts,
      { accountId: 4, startingCents: 4000000, startingOn: "2026-10-01", entered: true, inCents: 45371, outCents: 0, endingCents: 4045371, lowestCents: 4000000, lowestOn: "2026-10-01", bucketsCents: 0, availableCents: 4045371, lowBalance: false, builtFromZero: false },
    ],
  };

  it("keeps an investment's assumed growth out of Income, and says on its tab how much is only assumed", async () => {
    withPeriod(WITH_RETIREMENT, { "GET /api/accounts": () => ({ status: 200, data: [CHECKING, SAVINGS, VISA, RETIREMENT] }) });
    renderApp();

    const income = await panel("Income");
    expect(within(income).queryByText("Interest")).not.toBeInTheDocument();
    expect(income).not.toHaveTextContent("$228.71");

    const accounts = await panel("Accounts & buckets");
    await userEvent.selectOptions(within(accounts).getByLabelText("Account"), "Brightwater 401(k)");
    // What really came in and what is only assumed are two lines, so "Coming in" is real money.
    expect(within(accounts).getByText("Coming in").parentElement).toHaveTextContent("+$225.00");
    expect(within(accounts).getByText("Assumed growth · not a real gain").parentElement).toHaveTextContent("+$228.71");
  });
});

describe("pay period screen", () => {
  it("points to Income when there is no paycheck to build periods from", async () => {
    fakeBackend();
    renderApp();

    expect(await screen.findByText("No pay periods yet.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Add your income" })).toHaveAttribute("href", "/income");
  });

  it("explains an income source whose paydays have not started", async () => {
    fakeBackend({ "GET /api/pay-periods": () => ({ status: 200, data: { primarySourceId: 1, primaryAccountId: 1, periods: [] } }) });
    renderApp();

    expect(await screen.findByText("Your pay periods haven't started yet.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Check your pay schedule" })).toHaveAttribute("href", "/income");
  });

  it("shows the backend's message when the period cannot be loaded", async () => {
    withPeriod(OCTOBER, {
      "GET /api/pay-periods/2026-10-01": () => ({ status: 500, error: "Something went wrong on our side. Your data is safe — please try again." }),
    });
    renderApp();

    expect(await screen.findByRole("alert")).toHaveTextContent("Your data is safe");
  });

  it("opens on the current period with its five figures", async () => {
    withPeriod();
    renderApp();

    expect(await screen.findByRole("heading", { level: 1, name: "Oct 1 – Oct 14, 2026" })).toBeInTheDocument();
    expect(screen.getByText("Current")).toBeInTheDocument();
    const figures = screen.getByText("Projected ending").closest("dl")!;
    expect(within(figures).getByText("Starting balance").closest("div")).toHaveTextContent("$1,214.38");
    expect(within(figures).getByText("Starting balance").closest("div")).toHaveTextContent("Everyday Checking · Oct 1 · set by you");
    expect(within(figures).getByText("Coming in").closest("div")).toHaveTextContent("+$3,986.20");
    // Hovering, focusing or tapping the figure lists what it is made of.
    const comingIn = within(figures).getByRole("group", { name: "What is coming in" });
    expect(comingIn).toHaveTextContent(/paycheck\$2,486\.20/);
    expect(comingIn.closest("[tabindex]")).toHaveAttribute("tabindex", "0");
    expect(within(figures).getByText("Going out").closest("div")).toHaveTextContent("−$2,104.48");
    expect(within(figures).getByText("Projected ending").closest("div")).toHaveTextContent("$3,096.10");
    expect(within(figures).getByText("Projected ending").closest("div")).toHaveTextContent("1,214.38 + 3,986.20 − 2,104.48");
    // Nothing is in buckets, so all of it is available; the lowest point is in the note.
    const available = within(figures).getByText("Available after buckets").closest("div");
    expect(available).toHaveTextContent("$3,096.10");
    expect(available).toHaveTextContent("$0.00 in buckets · lowest $313.90 on Oct 8");
  });

  it("sorts what is in the period into its panels", async () => {
    withPeriod();
    renderApp();

    const income = await panel("Income");
    expect(within(income).getByText("Northwind Health paycheck")).toBeInTheDocument();
    expect(within(income).getByRole("button", { name: "Edit Q3 bonus" })).toBeInTheDocument();

    const auto = await panel("Auto-pay & transfers");
    expect(within(auto).getByText("Car loan")).toBeInTheDocument();
    expect(within(auto).getByText("AUTO")).toBeInTheDocument();

    const budgets = await panel("Budgets");
    expect(within(budgets).getByText("Groceries")).toBeInTheDocument();
    expect(within(budgets).getByText("Total").closest("p")).toHaveTextContent("$300.00");

    const manual = await panel("Pay manually & one-offs");
    expect(within(manual).getByText("1 of 2 paid")).toBeInTheDocument();
    expect(within(manual).getByText("Due Oct 16 · pay by Oct 14 · Everyday Checking")).toBeInTheDocument();
    // The category is not on the line, where it would read like a bucket; it is on the dot, for hover and screen readers.
    expect(within(manual).getByText("One-off · Oct 3 · Everyday Checking")).toBeInTheDocument();
    expect(within(manual).getByTitle("Category: Pets")).toBeInTheDocument();
    // Paid is said in words and the code is plain text with a pencil to change it.
    expect(within(manual).getByText("✓ Paid Oct 7")).toBeInTheDocument();
    expect(within(manual).getByText("VX93-2210")).toBeInTheDocument();
    expect(within(manual).getByRole("button", { name: "Edit confirmation code for Visa payment" })).toBeInTheDocument();
  });

  it("says what to do when the paycheck's account has no balance yet", async () => {
    withPeriod({ ...OCTOBER, accounts: [] });
    renderApp();

    expect(await screen.findByText("No balance to start from yet.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "account's page" })).toHaveAttribute("href", "/accounts/1");
    expect(within(await panel("Accounts & buckets")).getByRole("link", { name: "Accounts" })).toHaveAttribute("href", "/accounts");
  });

  it("names an unpaid bill carried over from an earlier period", async () => {
    const rent: Occurrence = { ...ELECTRIC, itemId: 7, name: "Rent", scheduledDate: "2026-10-01", dueDate: "2026-10-01", periodStart: "2026-09-15", payBy: "2026-09-30", estimate: false };
    withPeriod({ ...OCTOBER, carriedOver: [rent] });
    renderApp();

    const manual = await panel("Pay manually & one-offs");
    expect(within(manual).getByText("Still unpaid from an earlier period")).toBeInTheDocument();
    expect(within(manual).getByText("1 of 3 paid")).toBeInTheDocument();
  });

  it("keeps the account chosen in Accounts & buckets when moving to another pay period", async () => {
    withPeriod();
    renderApp();

    const accounts = await panel("Accounts & buckets");
    await userEvent.selectOptions(within(accounts).getByLabelText("Account"), "Rainy Day Savings");
    // Every way to another period carries the choice along.
    expect(screen.getByRole("link", { name: "Next pay period, Oct 15 to Oct 31" })).toHaveAttribute("href", "/periods/2026-10-15?account=2");

    await userEvent.click(screen.getByRole("link", { name: "Next pay period, Oct 15 to Oct 31" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Oct 15 – Oct 31, 2026" })).toBeInTheDocument();
    expect(within(await panel("Accounts & buckets")).getByLabelText("Account")).toHaveDisplayValue("Rainy Day Savings");
  });

  it("warns at the top of the screen about a bill paid by hand that is past due", async () => {
    const rent: Occurrence = { ...ELECTRIC, itemId: 7, name: "Rent", scheduledDate: "2020-10-01", dueDate: "2020-10-01", periodStart: "2020-09-15", payBy: "2020-09-30", estimate: false };
    withPeriod({ ...OCTOBER, carriedOver: [rent] });
    renderApp();

    const warning = await screen.findByRole("alert", { name: "Past due" });
    expect(warning).toHaveTextContent("1 bill is past due");
    expect(warning).toHaveTextContent("Rent · $140.00 · was due Oct 1, 2020");
    // Its own row says so too, in words.
    const manual = await panel("Pay manually & one-offs");
    expect(within(manual).getByText("Past due")).toBeInTheDocument();
  });

  it("has no past-due warning for a bill that is paid, on autopay, or not due yet", async () => {
    const late: Occurrence = { ...ELECTRIC, itemId: 7, name: "Rent", scheduledDate: "2020-10-01", dueDate: "2020-10-01", estimate: false };
    withPeriod({
      ...OCTOBER,
      occurrences: [
        // Paid, though late: it says so on its row, and is no longer a warning.
        { ...late, paid: true, paidDate: "2020-10-09" },
        { ...late, itemId: 8, name: "Water", autopay: true },
        { ...late, itemId: 9, name: "Phone", scheduledDate: "2099-10-01", dueDate: "2099-10-01" },
      ],
      carriedOver: [],
    });
    renderApp();

    const manual = await panel("Pay manually & one-offs");
    expect(within(manual).getByText(/✓ Paid Oct 9/)).toHaveTextContent("✓ Paid Oct 9 · late");
    expect(screen.queryByRole("alert", { name: "Past due" })).not.toBeInTheDocument();
    expect(within(manual).queryByText("Past due")).not.toBeInTheDocument();
  });

  it("moves to the next period from the arrows, the strip and the arrow keys", async () => {
    withPeriod();
    renderApp();

    const strip = await screen.findByRole("navigation", { name: "Ending balance by period" });
    expect(within(strip).getByRole("link", { name: /^Oct 1\s*\$/ })).toHaveAttribute("aria-current", "page");
    expect(within(strip).getByRole("link", { name: /Oct 15/ })).toHaveTextContent("$4,125.00");
    expect(screen.getByRole("link", { name: "Previous pay period, Sep 15 to Sep 30" })).toHaveAttribute("href", "/periods/2026-09-15");

    await userEvent.click(screen.getByRole("link", { name: "Next pay period, Oct 15 to Oct 31" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Oct 15 – Oct 31, 2026" })).toBeInTheDocument();

    await userEvent.keyboard("{ArrowLeft}");
    expect(await screen.findByRole("heading", { level: 1, name: "Oct 1 – Oct 14, 2026" })).toBeInTheDocument();
  });

  it("shows each account on its own tab, in a card's own words", async () => {
    withPeriod();
    renderApp();

    const accounts = await panel("Accounts & buckets");
    expect(within(accounts).getByLabelText("Account")).toHaveDisplayValue("Everyday Checking");
    expect(within(accounts).getByText("Ending balance").closest("div")).toHaveTextContent("$3,096.10");
    // Savings has no balance entered yet: it still has a tab, which says so instead of going missing.
    await userEvent.selectOptions(within(accounts).getByLabelText("Account"), "Rainy Day Savings");
    expect(accounts).toHaveTextContent(
      "No balance was entered for Rainy Day Savings on or before Oct 14, so there is nothing to show for it in this pay period.",
    );
    expect(within(accounts).getByRole("link", { name: "Enter a balance" })).toHaveAttribute("href", "/accounts/2");

    await userEvent.selectOptions(within(accounts).getByLabelText("Account"), "Visa");

    expect(within(accounts).getByText("Owed at period end").closest("div")).toHaveTextContent("$0.00");
    expect(within(accounts).getByText("Paid off").closest("div")).toHaveTextContent("−$612.48");
  });

  it("says how each goal is going, and stops saving into a bucket that has reached its goal", async () => {
    const fund = {
      ...ELECTRIC_ITEM,
      id: 8,
      name: "Gift fund",
      kind: "bucket_move" as const,
      bucketId: 7,
      schedule: { ...ELECTRIC_ITEM.schedule, frequency: "every_pay_period" as const, endDate: null },
    };
    const short = {
      ...GIFTS,
      id: 9,
      name: "New bike",
      goalDate: "2099-12-01",
      goalProgress: { projectedCents: 40000, shortCents: 20000, paydaysLeft: 4, morePerPaycheckCents: 5000, morePerMonthCents: 10000 },
    };
    const fetchMock = withPeriod(
      {
        ...OCTOBER,
        end: "2099-10-14",
        buckets: [
          { bucketId: 7, accountId: 1, startingCents: 55000, endingCents: 60000 },
          { bucketId: 9, accountId: 1, startingCents: 15000, endingCents: 20000 },
        ],
      },
      {
        "GET /api/buckets": () => ({ status: 200, data: [GIFTS, short] }),
        "GET /api/items": () => ({ status: 200, data: [ELECTRIC_ITEM, fund] }),
        "PUT /api/items/8": () => ({ status: 200, data: fund, changed: true }),
      },
    );
    renderApp();

    const buckets = within(await panel("Accounts & buckets")).getByRole("region", { name: "Buckets in Everyday Checking" });
    const bike = within(buckets).getByText("New bike").closest("li")!;
    expect(bike).toHaveTextContent("Short $50.00 a paycheck for Dec 1, 2099");
    expect(within(bike).queryByRole("button", { name: /Stop saving/ })).not.toBeInTheDocument();

    const gifts = within(buckets).getByText("Holiday gifts").closest("li")!;
    expect(gifts).toHaveTextContent("Goal reached");
    await userEvent.click(within(gifts).getByRole("button", { name: "Stop saving into Holiday gifts" }));

    // The move into it ends with this pay period; nothing else about it changes.
    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "PUT /api/items/8")[0]).toMatchObject({
        name: "Gift fund",
        kind: "bucket_move",
        bucketId: 7,
        schedule: { frequency: "every_pay_period", endDate: "2099-10-14" },
      }),
    );
  });

  it("still shows an account in a pay period that ends before its first balance was entered", async () => {
    withPeriod({
      ...OCTOBER,
      accounts: [
        OCTOBER.accounts[0]!,
        { ...OCTOBER.accounts[1]!, startingCents: 61248, startingOn: "2026-10-20", entered: false, inCents: 0, outCents: 0, endingCents: 61248 },
      ],
    });
    renderApp();

    const accounts = await panel("Accounts & buckets");
    await userEvent.selectOptions(within(accounts).getByLabelText("Account"), "Visa");
    expect(within(accounts).getByText("First balance, entered Oct 20").closest("div")).toHaveTextContent("$612.48");
    expect(accounts).toHaveTextContent("Nothing is counted for Visa before the first balance you entered");
    // Nothing has been added up yet, so there are no in and out lines to mislead.
    expect(within(accounts).queryByText(/^Paid off/)).not.toBeInTheDocument();
  });

  it("warns loudly when an account goes overdrawn, and when its buckets hold more than it does", async () => {
    withPeriod({
      ...OCTOBER,
      accounts: [{ ...OCTOBER.accounts[0]!, lowestCents: -4200, endingCents: -1000 }, OCTOBER.accounts[1]!],
    });
    renderApp();

    const accounts = await panel("Accounts & buckets");
    expect(await within(accounts).findByRole("alert")).toHaveTextContent(
      "Overdrawn: Everyday Checking drops to -$42.00 on Oct 8 and ends this pay period at -$10.00.",
    );
  });

  it("shows an account with no balance entered like any other, with no extra label", async () => {
    withPeriod({
      ...OCTOBER,
      accounts: [OCTOBER.accounts[0]!, { ...OCTOBER.accounts[1]!, entered: false, builtFromZero: true }],
    });
    renderApp();

    const accounts = await panel("Accounts & buckets");
    await userEvent.selectOptions(within(accounts).getByLabelText("Account"), "Visa");
    expect(within(accounts).getByText("Owed Oct 1").closest("div")).toHaveTextContent("$612.48");
    expect(accounts).not.toHaveTextContent(/built from/i);
  });

  it("says which day an account counts from when its balance was entered part-way through", async () => {
    withPeriod({
      ...OCTOBER,
      accounts: [{ ...OCTOBER.accounts[0]!, startingOn: "2026-10-09", inCents: 0 }, OCTOBER.accounts[1]!],
    });
    renderApp();

    // What moved before that day is already in the balance that was typed in.
    const accounts = await panel("Accounts & buckets");
    expect(within(accounts).getByText("Coming in since Oct 9").closest("div")).toHaveTextContent("+$0.00");
    expect(within(accounts).getByText("Going out since Oct 9")).toBeInTheDocument();
  });

  it("shows what is set aside in buckets and what that leaves available", async () => {
    const withBuckets: PeriodDetail = {
      ...OCTOBER,
      occurrences: [...OCTOBER.occurrences, GIFT_FUND],
      accounts: [{ ...OCTOBER.accounts[0]!, bucketsCents: 20000, availableCents: 289610 }, OCTOBER.accounts[1]!],
      buckets: [{ bucketId: 7, accountId: 1, startingCents: 15000, endingCents: 20000 }],
      bucketMoves: [{ bucketId: 7, date: "2026-10-01", amountCents: 5000, kind: "bucket_move", label: "Gift fund", itemId: 8 }],
    };
    withPeriod(withBuckets, { "GET /api/buckets": () => ({ status: 200, data: [GIFTS] }) });
    renderApp();

    const figures = (await screen.findByText("Projected ending")).closest("dl")!;
    const available = within(figures).getByText("Available after buckets").closest("div");
    expect(available).toHaveTextContent("$2,896.10");
    expect(available).toHaveTextContent("$200.00 in buckets");

    const accounts = await panel("Accounts & buckets");
    expect(within(accounts).getByText("In buckets").closest("div")).toHaveTextContent("−$200.00");
    const buckets = within(accounts).getByRole("region", { name: "Buckets in Everyday Checking" });
    expect(within(buckets).getByText("Holiday gifts").closest("li")).toHaveTextContent("$200.00");
    // What the period moved, and how far along the goal is, in words.
    expect(within(buckets).getByText("+$50.00 this period · 33% of $600.00")).toBeInTheDocument();
    expect(within(buckets).getByRole("link", { name: "Manage" })).toHaveAttribute("href", "/accounts/1");

    // Setting money aside happens by itself, and says which bucket it goes into.
    const auto = await panel("Auto-pay & transfers");
    expect(within(auto).getByText("Oct 1 · Everyday Checking → Holiday gifts bucket")).toBeInTheDocument();
  });

  it("flags a low balance in words, on the figures, the strip and the account", async () => {
    const low: PeriodDetail = { ...OCTOBER, accounts: [{ ...OCTOBER.accounts[0]!, lowBalance: true }, OCTOBER.accounts[1]!] };
    withPeriod(low, {
      "GET /api/pay-periods": () => ({
        status: 200,
        data: { ...PERIODS, periods: PERIODS.periods.map((period) => ({ ...period, lowBalance: period.current })) },
      }),
    });
    renderApp();

    const figures = (await screen.findByText("Projected ending")).closest("dl")!;
    expect(within(figures).getByText("Available after buckets").closest("div")).toHaveTextContent(
      "Low balance: dips to $313.90 on Oct 8",
    );
    const strip = screen.getByRole("navigation", { name: "Ending balance by period" });
    expect(within(strip).getByRole("link", { name: /Oct 1\s*·\s*Low\s*balance/ })).toBeInTheDocument();
    expect(within(strip).getByRole("link", { name: /Oct 15/ })).not.toHaveTextContent("Low");
    expect(within(await panel("Accounts & buckets")).getByText("Lowest point · Oct 8 · low balance")).toBeInTheDocument();
  });

  it("lists estimated interest with the income, in an estimate box", async () => {
    withPeriod({ ...OCTOBER, interest: [{ accountId: 2, date: "2026-10-10", amountCents: 1677, rateBp: 410 }] });
    renderApp();

    const income = await panel("Income");
    const row = within(income).getByText("Interest").closest("li")!;
    expect(row).toHaveTextContent("Oct 10 → Rainy Day Savings · 4.10% APY");
    expect(row).toHaveTextContent("$16.77 (estimate)");
  });

  it("marks a bill paid with today's date, and unticks it again", async () => {
    const fetchMock = withPeriod(OCTOBER, {
      "PUT /api/items/2/occurrences/2026-10-16": () => ({ status: 200, data: { ...ELECTRIC, paid: true }, changed: true }),
      "PUT /api/items/5/occurrences/2026-10-13": () => ({ status: 200, data: { ...VISA_PAYMENT, paid: false }, changed: true }),
    });
    renderApp();

    await userEvent.click(await screen.findByRole("checkbox", { name: "Electric paid" }));
    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "PUT /api/items/2/occurrences/2026-10-16")).toEqual([{ paid: true, paidDate: todayIso() }]),
    );

    await userEvent.click(screen.getByRole("checkbox", { name: "Visa payment paid" }));
    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/items/5/occurrences/2026-10-13")).toEqual([{ paid: false }]));
  });

  it("keeps a check number for a bill paid from a checking account, and offers none for a card", async () => {
    const fetchMock = withPeriod(OCTOBER, {
      "PUT /api/items/2/occurrences/2026-10-16": () => ({ status: 200, data: { ...ELECTRIC, checkNumber: "1043" }, changed: true }),
    });
    renderApp();

    await userEvent.click(await screen.findByRole("button", { name: "Add check number for Electric" }));
    await userEvent.type(screen.getByRole("textbox", { name: "Check number for Electric" }), "1043{Enter}");

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/items/2/occurrences/2026-10-16")).toEqual([{ checkNumber: "1043" }]));
  });

  it("shows the check number a bill was paid with", async () => {
    withPeriod({ ...OCTOBER, occurrences: [{ ...ELECTRIC, checkNumber: "1043" }], entries: [{ ...VET, checkNumber: "1042" }] });
    renderApp();

    expect(await screen.findByRole("button", { name: "Edit check number for Electric" })).toBeInTheDocument();
    expect(screen.getByText("1043")).toBeInTheDocument();
    expect(screen.getByText("One-off · Oct 3 · Everyday Checking · Check #1042")).toBeInTheDocument();
  });

  it("adds a confirmation code where the bill stands", async () => {
    const fetchMock = withPeriod(OCTOBER, {
      "PUT /api/items/2/occurrences/2026-10-16": () => ({ status: 200, data: ELECTRIC, changed: true }),
    });
    renderApp();

    await userEvent.click(await screen.findByRole("button", { name: "Add confirmation code for Electric" }));
    await userEvent.type(screen.getByRole("textbox", { name: "Confirmation code for Electric" }), "EL-4471{Enter}");

    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "PUT /api/items/2/occurrences/2026-10-16")).toEqual([{ confirmationCode: "EL-4471" }]),
    );
  });

  it("types over one amount for just this one by default", async () => {
    const fetchMock = withPeriod(OCTOBER, {
      "PUT /api/items/2/occurrences/2026-10-16": () => ({ status: 200, data: ELECTRIC, changed: true }),
    });
    renderApp();

    await userEvent.click(await screen.findByRole("button", { name: "Electric: $140.00, estimate. Edit amount" }));
    const field = screen.getByRole("textbox", { name: "Electric amount" });
    expect(field).toHaveValue("140.00");
    expect(screen.getByRole("radio", { name: "Just this one" })).toBeChecked();
    await userEvent.clear(field);
    await userEvent.type(field, "152.30{Enter}");

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/items/2/occurrences/2026-10-16")).toEqual([{ amountCents: 15230 }]));
    expect(calls(fetchMock)).not.toContain("PUT /api/items/2");
  });

  it("changes this and all future occurrences when asked to", async () => {
    const fetchMock = withPeriod(OCTOBER, {
      "PUT /api/items/2": () => ({ status: 200, data: ELECTRIC_ITEM, changed: true }),
    });
    renderApp();

    await userEvent.click(await screen.findByRole("button", { name: "Electric: $140.00, estimate. Edit amount" }));
    const field = screen.getByRole("textbox", { name: "Electric amount" });
    await userEvent.clear(field);
    await userEvent.type(field, "155");
    await userEvent.click(screen.getByRole("radio", { name: "This and all future" }));
    await userEvent.keyboard("{Enter}");

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/items/2")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "PUT /api/items/2")[0]).toMatchObject({
      name: "Electric",
      amountCents: 15500,
      estimate: false,
      effectiveFrom: "2026-10-16",
    });
    expect(calls(fetchMock)).not.toContain("PUT /api/items/2/occurrences/2026-10-16");
  });

  it("leaves the amount alone on Escape, and asks again kindly for something that is not an amount", async () => {
    const fetchMock = withPeriod();
    renderApp();

    await userEvent.click(await screen.findByRole("button", { name: "Car loan: $412.00. Edit amount" }));
    await userEvent.clear(screen.getByRole("textbox", { name: "Car loan amount" }));
    await userEvent.type(screen.getByRole("textbox", { name: "Car loan amount" }), "lots{Enter}");
    expect(screen.getByRole("alert")).toHaveTextContent("Please enter an amount like 1,250.00.");

    await userEvent.keyboard("{Escape}");

    expect(screen.queryByRole("textbox", { name: "Car loan amount" })).not.toBeInTheDocument();
    // The keyboard keeps its place: focus is back on the amount.
    expect(screen.getByRole("button", { name: "Car loan: $412.00. Edit amount" })).toHaveFocus();
    expect(calls(fetchMock).filter((call) => call.startsWith("PUT"))).toEqual([]);
  });

  it("says what was projected before an amount was typed over", async () => {
    withPeriod({ ...OCTOBER, occurrences: [{ ...ELECTRIC, amountCents: 15230, estimate: false, projectedCents: 14000 }] });
    renderApp();

    expect(await screen.findByRole("button", { name: "Electric: $152.30, projected $140.00. Edit amount" })).toBeInTheDocument();
    expect(screen.getByText("Projected $140.00")).toBeInTheDocument();
  });

  it("types over a one-off, which stops it being an estimate, and a paycheck", async () => {
    const fetchMock = withPeriod(OCTOBER, {
      "PUT /api/entries/10": () => ({ status: 200, data: BONUS, changed: true }),
      "PUT /api/income-sources/1/paychecks/2026-10-01": () => ({ status: 200, data: { done: true }, changed: true }),
    });
    renderApp();

    await userEvent.click(await screen.findByRole("button", { name: "Q3 bonus: $1,500.00, estimate. Edit amount" }));
    await userEvent.clear(screen.getByRole("textbox", { name: "Q3 bonus amount" }));
    await userEvent.type(screen.getByRole("textbox", { name: "Q3 bonus amount" }), "1,620.50{Enter}");
    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/entries/10")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "PUT /api/entries/10")[0]).toMatchObject({ kind: "money_in", amountCents: 162050, estimate: false, description: "Q3 bonus" });
    // A one-off happens once, so there is no "all future" to ask about.
    expect(screen.queryByRole("radio", { name: "This and all future" })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Northwind Health paycheck: $2,486.20. Edit amount" }));
    await userEvent.clear(screen.getByRole("textbox", { name: "Northwind Health paycheck amount" }));
    await userEvent.type(screen.getByRole("textbox", { name: "Northwind Health paycheck amount" }), "2500{Enter}");
    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "PUT /api/income-sources/1/paychecks/2026-10-01")).toEqual([{ netCents: 250000 }]),
    );
  });

  it("opens a short form for that one time from a row's name, with the item itself a link away", async () => {
    const fetchMock = withPeriod(OCTOBER, {
      "PUT /api/items/2/occurrences/2026-10-16": () => ({ status: 200, data: { ...ELECTRIC, paid: true }, changed: true }),
    });
    renderApp();

    await userEvent.click(await screen.findByRole("button", { name: "Edit Electric" }));

    const dialog = screen.getByRole("dialog", { name: "Edit Electric" });
    expect(dialog).toHaveTextContent("Just this one, due Oct 16. The others stay as they are.");
    // Nothing about the item as a whole is asked here, so the form stays short.
    expect(within(dialog).queryByLabelText("Name")).not.toBeInTheDocument();
    expect(within(dialog).getByRole("link", { name: "Change Electric itself" })).toHaveAttribute("href", "/recurring/2");
    // The day it was paid can be chosen, which the row's tick cannot do.
    expect(within(dialog).getByLabelText("Paid on")).toBeDisabled();
    await userEvent.click(within(dialog).getByRole("checkbox", { name: "Paid" }));
    fireEvent.change(within(dialog).getByLabelText("Paid on"), { target: { value: "2026-10-09" } });
    await userEvent.type(within(dialog).getByLabelText("Confirmation code"), "EL-4471");
    // This one can be moved to another day, and have a note of its own.
    expect(within(dialog).getByLabelText("Due on")).toHaveValue("2026-10-16");
    fireEvent.change(within(dialog).getByLabelText("Due on"), { target: { value: "2026-10-19" } });
    await userEvent.type(within(dialog).getByLabelText("Note (optional)"), "Budget billing starts");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    // Only what was changed is sent: the amount was left alone.
    expect(bodiesSentTo(fetchMock, "PUT /api/items/2/occurrences/2026-10-16")).toEqual([
      { dueDate: "2026-10-19", note: "Budget billing starts", paid: true, paidDate: "2026-10-09", confirmationCode: "EL-4471" },
    ]);
  });

  it("asks for an amount that reads as one in that form, and sends nothing when nothing changed", async () => {
    const fetchMock = withPeriod();
    renderApp();

    await userEvent.click(await screen.findByRole("button", { name: "Edit Electric" }));
    const dialog = screen.getByRole("dialog", { name: "Edit Electric" });
    const amount = within(dialog).getByRole("textbox", { name: "Amount this time" });
    const before = (amount as HTMLInputElement).value;
    await userEvent.clear(amount);
    await userEvent.type(amount, "about ninety");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please enter an amount like 1,250.00.");

    await userEvent.clear(amount);
    await userEvent.type(amount, before);
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(calls(fetchMock).some((call) => call.startsWith("PUT /api/items"))).toBe(false);
  });

  it("blurs every amount while Hide amounts is on", async () => {
    withPeriod();
    renderApp();

    const toggle = await screen.findByRole("button", { name: "Hide amounts" });
    expect(toggle).toHaveAttribute("aria-pressed", "false");
    await userEvent.click(toggle);

    expect(toggle).toHaveAttribute("aria-pressed", "true");
    expect(document.documentElement).toHaveAttribute("data-hide-amounts");

    await userEvent.click(toggle);
    expect(document.documentElement).not.toHaveAttribute("data-hide-amounts");
  });
});

describe("+ Add item", () => {
  it("offers the four kinds from its menu, with the keyboard", async () => {
    withPeriod();
    renderApp();

    await userEvent.click(await screen.findByRole("button", { name: "Add item" }));

    const menu = screen.getByRole("menu", { name: "Add item" });
    const items = within(menu).getAllByRole("menuitem");
    expect(items.map((item) => item.textContent)).toEqual([
      expect.stringContaining("Money out"),
      expect.stringContaining("Money in"),
      expect.stringContaining("Transfer"),
      expect.stringContaining("Recurring item"),
      expect.stringContaining("Upload a pay stub or receipt…"),
    ]);
    expect(items[0]).toHaveFocus();
    await userEvent.keyboard("{ArrowDown}");
    expect(items[1]).toHaveFocus();

    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add item" })).toHaveFocus();
  });

  it("adds money out to the period on screen and previews the new ending", async () => {
    const fetchMock = withPeriod(OCTOBER, {
      "POST /api/entries": () => ({ status: 201, data: { ...VET, id: 11 }, changed: true }),
    });
    renderApp();

    await userEvent.click(await screen.findByRole("button", { name: "Add item" }));
    await userEvent.click(screen.getByRole("menuitem", { name: /Money out/ }));

    const dialog = screen.getByRole("dialog", { name: "Add to Oct 1 – Oct 14 pay period" });
    expect(within(dialog).getByRole("radio", { name: "Money out" })).toBeChecked();
    // A screen at a time, so the dialog never outgrows the window: the amount and what it is come first.
    expect(within(dialog).getByRole("status")).toHaveTextContent("Step 1 of 2");
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Amount" }), "85");
    await userEvent.type(within(dialog).getByRole("textbox", { name: "What is it" }), "Birthday gift for Mom");
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    // What is being added has been chosen: the switch steps aside for the second screen.
    expect(within(dialog).queryByRole("radio", { name: "Money out" })).not.toBeInTheDocument();
    // The account the paycheck lands in is offered first.
    expect(within(dialog).getByRole("combobox", { name: "Paid from" })).toHaveValue("Everyday Checking");
    // Paid from a checking account, so a check number can be kept with it.
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Check number (optional)" }), "1042");
    const date = within(dialog).getByLabelText("Date");
    await userEvent.clear(date);
    await userEvent.type(date, "2026-10-09");

    expect(within(dialog).getByText(/Projected ending for Everyday Checking this period becomes/)).toHaveTextContent("$3,011.10");

    await userEvent.click(within(dialog).getByRole("button", { name: "Add" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "POST /api/entries")).toEqual([
      {
        kind: "money_out",
        date: "2026-10-09",
        amountCents: 8500,
        estimate: false,
        description: "Birthday gift for Mom",
        payee: null,
        categoryId: null,
        accountId: 1,
        toAccountId: null,
        note: null,
        checkNumber: "1042",
        tagIds: [],
        bucketSplits: [],
        reimbursesEntryId: null,
      },
    ]);
  });

  it("sets part of money coming in aside in a bucket of the account it lands in", async () => {
    const fetchMock = withPeriod(OCTOBER, {
      "GET /api/buckets": () => ({ status: 200, data: [GIFTS] }),
      "POST /api/entries": () => ({ status: 201, data: BONUS, changed: true }),
    });
    renderApp();

    // The shortcut belongs to the loaded screen: wait for it, as someone at the keyboard would.
    await screen.findByRole("heading", { level: 1, name: "Oct 1 – Oct 14, 2026" });
    await userEvent.keyboard("{Control>}i{/Control}");
    const dialog = await screen.findByRole("dialog", { name: "Add to Oct 1 – Oct 14 pay period" });
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Amount" }), "1,500");
    await userEvent.type(within(dialog).getByRole("textbox", { name: "What is it" }), "Q3 bonus");
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    // Setting aside is the last of the screens behind "More options".
    await userEvent.click(within(dialog).getByRole("button", { name: "More options" }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    await userEvent.click(within(dialog).getByRole("checkbox", { name: "Set some aside in a bucket" }));
    const gifts = within(dialog).getByRole("textbox", { name: "Holiday gifts" });

    // More than the bonus itself cannot be set aside; nothing is sent.
    await userEvent.type(gifts, "2000");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("That sets aside more than the amount itself.");
    expect(calls(fetchMock)).not.toContain("POST /api/entries");

    await userEvent.clear(gifts);
    await userEvent.type(gifts, "500");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "POST /api/entries")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "POST /api/entries")[0]).toMatchObject({
      kind: "money_in",
      amountCents: 150000,
      accountId: 1,
      bucketSplits: [{ bucketId: 7, amountCents: 50000 }],
    });
  });

  it("says which bucket a recurring item is paid out of", async () => {
    withPeriod(
      { ...OCTOBER, occurrences: [{ ...ELECTRIC, fromBucketId: 7 }] },
      { "GET /api/buckets": () => ({ status: 200, data: [GIFTS] }) },
    );
    renderApp();

    expect(await screen.findByText(/Everyday Checking · Holiday gifts bucket/)).toBeInTheDocument();
  });

  it("asks where money going out comes from when the account has buckets", async () => {
    const fetchMock = withPeriod(OCTOBER, {
      "GET /api/buckets": () => ({ status: 200, data: [GIFTS] }),
      "POST /api/entries": () => ({ status: 201, data: VET, changed: true }),
    });
    renderApp();

    // The shortcut belongs to the loaded screen: wait for it, as someone at the keyboard would.
    await screen.findByRole("heading", { level: 1, name: "Oct 1 – Oct 14, 2026" });
    await userEvent.keyboard("{Control>}e{/Control}");
    const dialog = await screen.findByRole("dialog", { name: "Add to Oct 1 – Oct 14 pay period" });
    // Setting aside is for money arriving; money leaving says where it comes from instead.
    expect(within(dialog).queryByRole("checkbox", { name: "Set some aside in a bucket" })).not.toBeInTheDocument();
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Amount" }), "85");
    await userEvent.type(within(dialog).getByRole("textbox", { name: "What is it" }), "Birthday gift for Mom");
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    const from = await within(dialog).findByRole("combobox", { name: "Comes out of" });

    // Nothing is assumed: it has to be said, and nothing is sent until it is.
    await userEvent.click(within(dialog).getByRole("button", { name: "Add" }));
    expect(calls(fetchMock)).not.toContain("POST /api/entries");

    await userEvent.selectOptions(from, "Holiday gifts (holds $200.00 today)");
    expect(within(dialog).getByText("The bucket and the account both go down by this amount.")).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "Add" }));
    await waitFor(() => expect(bodiesSentTo(fetchMock, "POST /api/entries")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "POST /api/entries")[0]).toMatchObject({
      kind: "money_out",
      amountCents: 8500,
      bucketSplits: [{ bucketId: 7, amountCents: 8500 }],
    });
  });

  it("takes nothing out of a bucket when the money is not in one", async () => {
    const fetchMock = withPeriod(OCTOBER, {
      "GET /api/buckets": () => ({ status: 200, data: [GIFTS] }),
      "POST /api/entries": () => ({ status: 201, data: VET, changed: true }),
    });
    renderApp();

    // The shortcut belongs to the loaded screen: wait for it, as someone at the keyboard would.
    await screen.findByRole("heading", { level: 1, name: "Oct 1 – Oct 14, 2026" });
    await userEvent.keyboard("{Control>}e{/Control}");
    const dialog = await screen.findByRole("dialog", { name: "Add to Oct 1 – Oct 14 pay period" });
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Amount" }), "85");
    await userEvent.type(within(dialog).getByRole("textbox", { name: "What is it" }), "Vet");
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    await userEvent.selectOptions(await within(dialog).findByRole("combobox", { name: "Comes out of" }), "Money that is not in a bucket");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "POST /api/entries")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "POST /api/entries")[0]).toMatchObject({ kind: "money_out", bucketSplits: [] });
  });

  it("switches kind inside the one form: money in is an estimate, a transfer needs two accounts", async () => {
    const fetchMock = withPeriod(OCTOBER, {
      "POST /api/entries": () => ({ status: 201, data: BONUS, changed: true }),
    });
    renderApp();

    // The shortcut belongs to the loaded screen: wait for it, as someone at the keyboard would.
    await screen.findByRole("heading", { level: 1, name: "Oct 1 – Oct 14, 2026" });
    await userEvent.keyboard("{Control>}i{/Control}");
    const dialog = await screen.findByRole("dialog", { name: "Add to Oct 1 – Oct 14 pay period" });
    expect(within(dialog).getByRole("radio", { name: "Money in" })).toBeChecked();
    // Stepped like the recurring item form, the amount first and large.
    expect(within(dialog).getByRole("group", { name: "Amount" })).toBeInTheDocument();
    expect(within(dialog).getByRole("textbox", { name: "Amount" })).toHaveClass("text-2xl");

    await userEvent.click(within(dialog).getByRole("radio", { name: "Transfer" }));
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Amount" }), "200");
    await userEvent.type(within(dialog).getByRole("textbox", { name: "What is it" }), "Top up savings");
    await userEvent.click(within(dialog).getByRole("checkbox", { name: "Estimate, not final yet" }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    expect(within(dialog).getByRole("group", { name: "When, from and to" })).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "Add" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please choose the account this money goes to.");

    await userEvent.type(within(dialog).getByRole("combobox", { name: "To" }), "Rainy{Enter}");
    // Back keeps everything typed, and brings the switch back with it.
    await userEvent.click(within(dialog).getByRole("button", { name: "Back" }));
    expect(within(dialog).getByRole("radio", { name: "Transfer" })).toBeChecked();
    expect(within(dialog).getByRole("textbox", { name: "Amount" })).toHaveValue("200");
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Add" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "POST /api/entries")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "POST /api/entries")[0]).toMatchObject({
      kind: "transfer",
      amountCents: 20000,
      estimate: true,
      accountId: 1,
      toAccountId: 2,
    });
  });

  it("shows the recurring item form under Recurring", async () => {
    withPeriod();
    renderApp();

    await screen.findByRole("button", { name: "Add item" });
    await userEvent.keyboard("{Control>}n{/Control}");
    await userEvent.click(await screen.findByRole("menuitem", { name: /Recurring item/ }));

    const dialog = screen.getByRole("dialog", { name: "Add to Oct 1 – Oct 14 pay period" });
    expect(within(dialog).getByRole("radio", { name: "Recurring" })).toBeChecked();
    // A screen at a time, so the dialog never outgrows the window: what it is comes first.
    expect(within(dialog).getByRole("status")).toHaveTextContent("Step 1 of 5");
    expect(within(dialog).getByRole("group", { name: "What it is" })).toBeInTheDocument();
    expect(within(dialog).queryByRole("group", { name: "Schedule" })).not.toBeInTheDocument();
  });

  it("opens Ctrl+N straight on the form on a screen without the button", async () => {
    withPeriod();
    renderApp("/categories");

    await screen.findByRole("heading", { level: 1, name: "Categories & tags" });
    await userEvent.keyboard("{Control>}n{/Control}");

    expect(await screen.findByRole("dialog", { name: "Add an item" })).toBeInTheDocument();
  });

  it("changes and deletes a one-off from its name, without asking twice", async () => {
    const fetchMock = withPeriod(OCTOBER, {
      "PUT /api/entries/9": () => ({ status: 200, data: VET, changed: true }),
      "DELETE /api/entries/9": () => ({ status: 200, data: { deleted: true }, changed: true }),
    });
    renderApp();

    await userEvent.click(await screen.findByRole("button", { name: "Edit Vet emergency" }));
    let dialog = screen.getByRole("dialog", { name: "Edit Vet emergency" });
    // A one-off stays a one-off.
    expect(within(dialog).queryByRole("radio", { name: "Recurring" })).not.toBeInTheDocument();
    const amount = within(dialog).getByRole("textbox", { name: "Amount" });
    await userEvent.clear(amount);
    await userEvent.type(amount, "655.25");
    // One group of fields at a time, so the dialog never scrolls; Save is on every one and sends them all.
    await userEvent.selectOptions(within(dialog).getByRole("combobox", { name: "What to change" }), "When and where");
    expect(within(dialog).queryByRole("textbox", { name: "Amount" })).not.toBeInTheDocument();
    // Who it was paid to is a picker of the names typed before, not free text.
    expect(within(dialog).getByRole("combobox", { name: "Paid to (optional)" })).toHaveValue("Maple Street Vets");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/entries/9")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "PUT /api/entries/9")[0]).toMatchObject({ amountCents: 65525, payee: "Maple Street Vets", categoryId: 1 });

    await userEvent.click(await screen.findByRole("button", { name: "Edit Vet emergency" }));
    dialog = screen.getByRole("dialog", { name: "Edit Vet emergency" });
    await userEvent.selectOptions(within(dialog).getByRole("combobox", { name: "What to change" }), "Delete");
    await userEvent.click(within(dialog).getByRole("button", { name: "Delete this one-off" }));

    await waitFor(() => expect(calls(fetchMock)).toContain("DELETE /api/entries/9"));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });
});

describe("undo", () => {
  it("offers Undo after a change and takes it back", async () => {
    const fetchMock = withPeriod(OCTOBER, {
      "PUT /api/items/2/occurrences/2026-10-16": () => ({ status: 200, data: { ...ELECTRIC, paid: true }, changed: true }),
      "POST /api/undo": () => ({ status: 200, data: { undone: true, available: false } }),
    });
    renderApp();

    await userEvent.click(await screen.findByRole("checkbox", { name: "Electric paid" }));
    expect(await screen.findByText("Saved.")).toBeInTheDocument();
    const before = calls(fetchMock).filter((call) => call === "GET /api/pay-periods/2026-10-01").length;

    await userEvent.click(screen.getByRole("button", { name: /Undo/ }));

    expect(await screen.findByText("Undone.")).toBeInTheDocument();
    expect(calls(fetchMock)).toContain("POST /api/undo");
    // Everything on screen is fetched again, since an undo can touch anything.
    await waitFor(() =>
      expect(calls(fetchMock).filter((call) => call === "GET /api/pay-periods/2026-10-01").length).toBeGreaterThan(before),
    );
  });

  it("does not offer Undo for a request that changed nothing", async () => {
    withPeriod(OCTOBER, {
      "PUT /api/items/2/occurrences/2026-10-16": () => ({ status: 200, data: ELECTRIC }),
    });
    renderApp();

    await userEvent.click(await screen.findByRole("checkbox", { name: "Electric paid" }));

    await waitFor(() => expect(screen.getByRole("checkbox", { name: "Electric paid" })).toBeEnabled());
    expect(screen.queryByText("Saved.")).not.toBeInTheDocument();
  });

  it("undoes with Ctrl+Z, and says so when there is nothing left", async () => {
    const fetchMock = withPeriod(OCTOBER, {
      "POST /api/undo": () => ({ status: 200, data: { undone: false, available: false } }),
    });
    renderApp();

    await screen.findByRole("heading", { level: 1, name: "Oct 1 – Oct 14, 2026" });
    await userEvent.keyboard("{Control>}z{/Control}");

    expect(await screen.findByText("There's nothing left to undo.")).toBeInTheDocument();
    expect(calls(fetchMock)).toContain("POST /api/undo");
  });

  it("undoes with Ctrl+Z straight after ticking a bill, while the tick box still has the focus", async () => {
    const fetchMock = withPeriod(OCTOBER, {
      "PUT /api/items/2/occurrences/2026-10-16": () => ({ status: 200, data: { ...ELECTRIC, paid: true }, changed: true }),
      "POST /api/undo": () => ({ status: 200, data: { undone: true, available: false } }),
    });
    renderApp();

    await userEvent.click(await screen.findByRole("checkbox", { name: "Electric paid" }));
    await screen.findByText("Saved.");
    await userEvent.keyboard("{Control>}z{/Control}");

    expect(await screen.findByText("Undone.")).toBeInTheDocument();
    expect(calls(fetchMock)).toContain("POST /api/undo");
  });

  it("leaves Ctrl+Z alone while typing in a field", async () => {
    const fetchMock = withPeriod();
    renderApp();

    await userEvent.click(await screen.findByRole("button", { name: "Car loan: $412.00. Edit amount" }));
    await userEvent.keyboard("{Control>}z{/Control}");

    expect(calls(fetchMock)).not.toContain("POST /api/undo");
  });

  it("shows the backend's message when an undo fails", async () => {
    withPeriod(OCTOBER, {
      "POST /api/undo": () => ({ status: 500, error: "Something went wrong on our side. Your data is safe — please try again." }),
    });
    renderApp();

    await screen.findByRole("heading", { level: 1, name: "Oct 1 – Oct 14, 2026" });
    await userEvent.keyboard("{Control>}z{/Control}");

    expect(await screen.findByText(/Your data is safe/)).toBeInTheDocument();
  });
});

describe("bills due soon", () => {
  /** Electric, paid by hand, due `ahead` days from today, with the setting at `days`. */
  function electricDueIn(ahead: number, days: string, bill: Partial<Occurrence> = {}) {
    const due = addDays(todayIso(), ahead);
    return withPeriod(
      { ...OCTOBER, occurrences: [{ ...ELECTRIC, dueDate: due, payBy: due, ...bill }] },
      { "GET /api/settings": () => ({ status: 200, data: { ...DEFAULT_SETTINGS, unpaid_highlight_days: days } }) },
    );
  }

  it("says so in words on a bill paid by hand that falls due within the days chosen in Settings", async () => {
    electricDueIn(2, "3");
    renderApp();

    const manual = await panel("Pay manually & one-offs");
    expect(await within(manual).findByText("Due in 2 days")).toBeInTheDocument();
  });

  it("says today and tomorrow by name", async () => {
    electricDueIn(0, "3");
    const { unmount } = renderApp();
    expect(await within(await panel("Pay manually & one-offs")).findByText("Due today")).toBeInTheDocument();
    unmount();

    electricDueIn(1, "3");
    renderApp();
    expect(await within(await panel("Pay manually & one-offs")).findByText("Due tomorrow")).toBeInTheDocument();
  });

  it("leaves alone a bill further off, one already paid, and every bill when the setting is off", async () => {
    for (const [ahead, days, bill] of [
      [5, "3", {}],
      [2, "3", { paid: true, paidDate: todayIso() }],
      [2, "0", {}],
    ] as const) {
      electricDueIn(ahead, days, bill);
      const { unmount } = renderApp();

      const manual = await panel("Pay manually & one-offs");
      await within(manual).findByRole("checkbox", { name: "Electric paid" });
      expect(within(manual).queryByText(/^Due (today|tomorrow|in \d+ days)$/)).not.toBeInTheDocument();
      unmount();
    }
  });
});
