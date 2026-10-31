import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Account, AccountDetail } from "../../src/api/accounts";
import type { Category, Tag } from "../../src/api/categories";
import type {
  BudgetDateChange,
  Matchable,
  PeriodChanges,
  StatementChanges,
  StatementLine,
  StatementUpload,
} from "../../src/api/statementUploads";
import { bodiesSentTo, calls, fakeBackend, renderApp } from "../helpers";

/**
 * Statement uploads (Rachel, 2026-10-25, made simpler 2026-10-30): each charge is put on a budget, said to be something
 * already in Budget, or added as a one-off, and nothing changes in the pay periods until Apply. A budget becomes what was
 * spent plus what is still to come, and the rows are checked against the statement's balances. Every statement here is
 * made up.
 */

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
  latestBalance: null,
};
const DETAIL: AccountDetail = {
  account: CHECKING,
  balances: [],
  interest: { creditDay: 31, destination: "unassigned", destinationBucketId: null },
  rates: [],
};

const GROCERIES: Category = { id: 3, name: "Groceries", color: "#15803d", parentId: null, itemCount: 1, perYearCents: 0 };
const VACATION: Tag = { id: 9, name: "Vacation", color: "#475569" } as Tag;

function line(id: number, description: string, amountCents: number, more: Partial<StatementLine> = {}): StatementLine {
  return {
    id,
    txnDate: "2026-09-02",
    postedDate: null,
    description,
    amountCents,
    kind: "charge",
    leftOut: false,
    sure: true,
    duplicateOf: null,
    categoryId: null,
    tagIds: [],
    oneOffEntryId: null,
    addOneOff: false,
    periodStart: "2026-09-01",
    matched: null,
    budgetItemId: null,
    suggestedItemId: null,
    ...more,
  };
}

const UPLOAD: StatementUpload = {
  id: 5,
  accountId: 1,
  accountName: "Everyday Checking",
  onCard: false,
  name: "september.ofx",
  format: "ofx",
  from: "2026-09-01",
  to: "2026-09-30",
  appliedAt: null,
  pages: null,
  lines: [
    line(11, "KROGER #123", 5420),
    line(14, "KROGER #456", 2580, { txnDate: "2026-09-09" }),
    line(12, "CAFE LUNA", 450, { txnDate: "2026-09-16", periodStart: "2026-09-15" }),
    line(13, "PAYMENT THANK YOU", -50000, { kind: "payment", leftOut: true }),
  ],
  toCheck: 0,
  skippedRows: [],
  check: { previousCents: null, newCents: null, statementChargesCents: null, statementCreditsCents: null, spentCents: 8450, backCents: 50000, expectedNewCents: null, differenceCents: null },
};

/** A copy of the upload with one line changed. */
function withLine(lineId: number, more: Partial<StatementLine>, upload: StatementUpload = UPLOAD): StatementUpload {
  return { ...upload, lines: upload.lines.map((each) => (each.id === lineId ? { ...each, ...more } : each)) };
}

const FOOD = { itemId: 21, name: "Food for Amara's house", accountId: 1, accountName: "Everyday Checking" };
const FUEL = { itemId: 22, name: "Fuel", accountId: 1, accountName: "Everyday Checking" };
/** A budget paid from another account; a charge here can still go on it (Rachel, 2026-10-30). */
const GIFTS = { itemId: 23, name: "Gifts for Tomás", accountId: 2, accountName: "Discover" };

function period(start: string, end: string, more: Partial<PeriodChanges> = {}): PeriodChanges {
  return {
    start,
    end,
    current: false,
    coverage: { from: start, to: end, partly: false },
    budgets: [FOOD, FUEL],
    changes: [],
    matchable: [],
    matches: [],
    oneOffs: [],
    restores: 0,
    open: [],
    upToDate: true,
    ...more,
  };
}

/** Nothing chosen yet. */
const UNCHOSEN: StatementChanges = {
  uploadId: 5,
  accountId: 1,
  periods: [period("2026-09-01", "2026-09-14"), period("2026-09-15", "2026-09-30")],
  outside: [],
  lines: [],
};

/** Both Krogers on Food: $200.00 becomes $80.00, every day of it covered. */
const FOOD_SEP_1: BudgetDateChange = {
  itemId: 21,
  name: "Food for Amara's house",
  accountId: 1,
  accountName: "Everyday Checking",
  scheduledDate: "2026-09-01",
  from: "2026-09-01",
  to: "2026-09-14",
  covered: "fully",
  coveredThrough: null,
  currentCents: 20000,
  budgetedCents: 20000,
  actualCents: 8000,
  stillToComeCents: 0,
  stillTyped: false,
  newCents: 8000,
  lineIds: [11, 14],
  elsewhere: [],
};
const ON_FOOD: StatementChanges = {
  ...UNCHOSEN,
  periods: [period("2026-09-01", "2026-09-14", { changes: [FOOD_SEP_1], upToDate: false }), period("2026-09-15", "2026-09-30")],
  lines: [
    { id: 11, uploadId: 5, uploadName: "september.ofx", accountId: 1, txnDate: "2026-09-02", description: "KROGER #123", amountCents: 5420 },
    { id: 14, uploadId: 5, uploadName: "september.ofx", accountId: 1, txnDate: "2026-09-09", description: "KROGER #456", amountCents: 2580 },
  ],
};

/** The first pay period with the other account's budget among its choices. */
function withGifts(changes: StatementChanges, more: Partial<PeriodChanges> = {}): StatementChanges {
  return {
    ...changes,
    periods: [period("2026-09-01", "2026-09-14", { budgets: [FOOD, FUEL, GIFTS], ...more }), period("2026-09-15", "2026-09-30")],
  };
}

function statementBackend(changes: StatementChanges = UNCHOSEN, extra: Parameters<typeof fakeBackend>[0] = {}) {
  return fakeBackend({
    "GET /api/accounts": () => ({ status: 200, data: [CHECKING] }),
    "GET /api/accounts/1": () => ({ status: 200, data: DETAIL }),
    "GET /api/categories": () => ({ status: 200, data: [GROCERIES] }),
    "GET /api/tags": () => ({ status: 200, data: [VACATION] }),
    "GET /api/statement-uploads/5": () => ({ status: 200, data: UPLOAD }),
    "GET /api/statement-uploads/5/changes": () => ({ status: 200, data: changes }),
    ...extra,
  });
}

/** The row of a charge in Charges. */
async function row(description: string): Promise<HTMLElement> {
  const charges = await screen.findByRole("region", { name: "Charges" });
  return (await within(charges).findByText(description)).closest("li")!;
}

describe("Statement uploads", () => {
  it("adds an OFX statement from the account's page and opens it", async () => {
    const fetchMock = statementBackend(UNCHOSEN, {
      "POST /api/accounts/1/statement-uploads": () => ({ status: 201, data: UPLOAD, changed: true }),
    });
    renderApp("/accounts/1");

    const card = await screen.findByRole("region", { name: "Statement uploads" });
    await userEvent.upload(within(card).getByLabelText("Drop a statement here or browse"), new File(["OFXHEADER:100"], "september.ofx"));

    expect(await screen.findByRole("heading", { name: /Statement · Sep 1 – Sep 30, 2026/ })).toBeInTheDocument();
    expect(calls(fetchMock)).toContain("POST /api/accounts/1/statement-uploads");
    // An OFX file says what each column is by itself: nothing is asked first.
    expect(calls(fetchMock)).not.toContain("POST /api/accounts/1/statement-uploads/columns");
  });

  it("asks which column is which for a bank's first CSV, reading a line back before adding it", async () => {
    const fetchMock = statementBackend(UNCHOSEN, {
      "POST /api/accounts/1/statement-uploads/columns": () => ({
        status: 200,
        data: {
          headers: ["Date", "Description", "Amount"],
          sample: [["09/02/2026", "KROGER #123", "-80.00"]],
          guess: {
            hasHeader: true,
            dateColumn: 0,
            postedColumn: null,
            descriptionColumn: 1,
            amountColumn: 2,
            debitColumn: null,
            creditColumn: null,
            dateOrder: "mdy",
            chargesNegative: true,
          },
          known: false,
        },
      }),
      "POST /api/accounts/1/statement-uploads": () => ({ status: 201, data: UPLOAD, changed: true }),
    });
    renderApp("/accounts/1");

    await userEvent.upload(await screen.findByLabelText("Drop a statement here or browse"), new File(["Date,Description,Amount"], "september.csv"));
    const dialog = await screen.findByRole("dialog", { name: "Which column is which" });
    expect(within(dialog).getByText("09/02/2026 · KROGER #123 · $80.00 spent")).toBeInTheDocument();

    // Saying spending is shown without a minus reads the same line as money back.
    await userEvent.selectOptions(within(dialog).getByLabelText("Spending shows"), "positive");
    expect(within(dialog).getByText("09/02/2026 · KROGER #123 · $80.00 back")).toBeInTheDocument();
    await userEvent.selectOptions(within(dialog).getByLabelText("Spending shows"), "negative");

    await userEvent.click(within(dialog).getByRole("button", { name: "Read the statement" }));
    expect(await screen.findByRole("heading", { name: /Statement ·/ })).toBeInTheDocument();
    const sent = fetchMock.mock.calls.find(([path, init]) => path === "/api/accounts/1/statement-uploads" && init?.method === "POST");
    const form = sent?.[1]?.body as FormData;
    expect(JSON.parse(String(form.get("layout")))).toMatchObject({ dateColumn: 0, descriptionColumn: 1, amountColumn: 2, chargesNegative: true });
  });

  it("searches the charges and what changes at once, by payee, amount or budget", async () => {
    // Rachel, 2026-10-30: "add a search feature for both charges and what changes - it can be one search that searches both".
    statementBackend(ON_FOOD, {
      "GET /api/statement-uploads/5": () => ({ status: 200, data: withLine(14, { budgetItemId: 21 }, withLine(11, { budgetItemId: 21 })) }),
    });
    renderApp("/accounts/1/statements/5");
    const charges = await screen.findByRole("region", { name: "Charges" });
    const changes = await screen.findByRole("region", { name: "What changes" });
    const search = screen.getByRole("searchbox", { name: "Search" });

    await userEvent.type(search, "cafe");
    expect(within(charges).getByText("CAFE LUNA")).toBeInTheDocument();
    expect(within(charges).queryByText("KROGER #123")).not.toBeInTheDocument();
    expect(within(changes).getByText('Nothing here matches "cafe".')).toBeInTheDocument();

    // An amount, typed as money, finds the charge and the budget it is on.
    await userEvent.clear(search);
    await userEvent.type(search, "$25.80");
    expect(within(charges).getByText("KROGER #456")).toBeInTheDocument();
    expect(within(charges).queryByText("KROGER #123")).not.toBeInTheDocument();
    expect(within(changes).getByText("Food for Amara's house")).toBeInTheDocument();

    // A budget's name finds it on both sides.
    await userEvent.clear(search);
    await userEvent.type(search, "amara");
    expect(within(charges).getByText("KROGER #123")).toBeInTheDocument();
    expect(within(charges).queryByText("CAFE LUNA")).not.toBeInTheDocument();
    expect(within(changes).getByText("Food for Amara's house")).toBeInTheDocument();

    await userEvent.clear(search);
    await userEvent.type(search, "zzz");
    expect(within(charges).getByText('No charges match "zzz".')).toBeInTheDocument();
  });

  it("lists every charge by pay period, saying which are left out and how many aren't put anywhere yet", async () => {
    statementBackend();
    renderApp("/accounts/1/statements/5");

    const first = await screen.findByRole("region", { name: "Pay period from Sep 1, 2026" });
    expect(within(first).getByText("KROGER #123")).toBeInTheDocument();
    expect(within(first).getByText("A payment to the card · left out")).toBeInTheDocument();
    const second = screen.getByRole("region", { name: "Pay period from Sep 15, 2026" });
    expect(within(second).getByText("CAFE LUNA")).toBeInTheDocument();
    expect(screen.getByText(/3 charges aren't put anywhere yet/)).toBeInTheDocument();
    // Nothing chosen, nothing to apply.
    expect(screen.getByRole("button", { name: "Apply…" })).toBeDisabled();
  });

  it("puts several ticked charges on a budget at once, changing nothing until Apply", async () => {
    let current = UPLOAD;
    const fetchMock = statementBackend(UNCHOSEN, {
      "GET /api/statement-uploads/5": () => ({ status: 200, data: current }),
      "PUT /api/statement-uploads/5/budget": () => {
        current = withLine(14, { budgetItemId: 21 }, withLine(11, { budgetItemId: 21 }));
        return { status: 200, data: current, changed: true };
      },
    });
    renderApp("/accounts/1/statements/5");

    await userEvent.click(within(await row("KROGER #123")).getByRole("checkbox", { name: "Tick KROGER #123" }));
    await userEvent.click(within(await row("KROGER #456")).getByRole("checkbox", { name: "Tick KROGER #456" }));
    await userEvent.click(screen.getByRole("button", { name: "Put 2 on a budget…" }));
    const dialog = screen.getByRole("dialog", { name: "2 charges · $80.00" });
    // The dialog covers the rows, so it says when they were spent and which pay period that is (Rachel, 2026-10-30).
    expect(within(dialog).getByText("Spent Sep 2 – Sep 9 · in the pay period from Sep 1")).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "Food for Amara's house" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/statement-uploads/5/budget")).toEqual([{ lineIds: [11, 14], itemId: 21 }]));
    expect(await within(await row("KROGER #123")).findByText("On Food for Amara's house")).toBeInTheDocument();
    expect(calls(fetchMock)).not.toContain("POST /api/statement-uploads/5/apply");
  });

  it("puts charges on a budget paid from another account, which keeps its total and takes them as a one-off here", async () => {
    let current = UPLOAD;
    const gifts: BudgetDateChange = {
      ...FOOD_SEP_1,
      itemId: 23,
      name: "Gifts for Tomás",
      accountId: 2,
      accountName: "Discover",
      covered: "none",
      currentCents: 30000,
      budgetedCents: 30000,
      actualCents: 0,
      stillToComeCents: 22000,
      newCents: 22000,
      lineIds: [11, 14],
      elsewhere: [{ accountId: 1, accountName: "Everyday Checking", cents: 8000 }],
    };
    const fetchMock = statementBackend(UNCHOSEN, {
      "GET /api/statement-uploads/5": () => ({ status: 200, data: current }),
      "GET /api/statement-uploads/5/changes": () => ({
        status: 200,
        data: current === UPLOAD ? withGifts(UNCHOSEN) : withGifts(UNCHOSEN, { changes: [gifts], upToDate: false }),
      }),
      "PUT /api/statement-uploads/5/budget": () => {
        current = withLine(14, { budgetItemId: 23 }, withLine(11, { budgetItemId: 23 }));
        return { status: 200, data: current, changed: true };
      },
    });
    renderApp("/accounts/1/statements/5");

    await userEvent.click(within(await row("KROGER #123")).getByRole("checkbox", { name: "Tick KROGER #123" }));
    await userEvent.click(within(await row("KROGER #456")).getByRole("checkbox", { name: "Tick KROGER #456" }));
    await userEvent.click(screen.getByRole("button", { name: "Put 2 on a budget…" }));
    const dialog = screen.getByRole("dialog", { name: "2 charges · $80.00" });
    // This account's budgets first, then the other account's, saying which pays it.
    expect(within(dialog).getAllByRole("listitem").map((item) => item.textContent)).toEqual([
      "Food for Amara's house",
      "Fuel",
      "Gifts for TomásPaid from Discover",
    ]);
    await userEvent.click(within(dialog).getByRole("button", { name: /Gifts for Tomás/ }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/statement-uploads/5/budget")).toEqual([{ lineIds: [11, 14], itemId: 23 }]));
    expect(await within(await row("KROGER #123")).findByText("On Gifts for Tomás · paid from Discover")).toBeInTheDocument();
    const changes = await screen.findByRole("region", { name: "What changes" });
    const row23 = (await within(changes).findByText("Gifts for Tomás · paid from Discover")).closest("li")!;
    expect(row23).toHaveTextContent("Now $300.00");
    expect(row23).toHaveTextContent("Becomes $220.00");
    expect(row23).toHaveTextContent("no Discover statement covers it yet");
    expect(row23).toHaveTextContent("$80.00 as a one-off on Everyday Checking");
  });

  it("gives several ticked charges one category and the same tags at once", async () => {
    let current = withLine(11, { categoryId: 3 }, withLine(14, { categoryId: 3 }));
    const fetchMock = statementBackend(UNCHOSEN, {
      "GET /api/statement-uploads/5": () => ({ status: 200, data: current }),
      "PUT /api/statement-uploads/5/filing": () => {
        current = withLine(11, { categoryId: 3, tagIds: [9] }, withLine(14, { categoryId: 3, tagIds: [9] }));
        return { status: 200, data: current, changed: true };
      },
    });
    renderApp("/accounts/1/statements/5");

    await userEvent.click(within(await row("KROGER #123")).getByRole("checkbox", { name: "Tick KROGER #123" }));
    await userEvent.click(within(await row("KROGER #456")).getByRole("checkbox", { name: "Tick KROGER #456" }));
    await userEvent.click(screen.getByRole("button", { name: "Category and tags…" }));
    const dialog = screen.getByRole("dialog", { name: "2 charges · $80.00" });
    await userEvent.type(within(dialog).getByRole("combobox", { name: "Tags (optional)" }), "Vac");
    await userEvent.click(within(dialog).getByRole("option", { name: "Vacation" }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    // Groceries, which both already had, stays without being chosen again.
    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/statement-uploads/5/filing")).toEqual([{ lineIds: [11, 14], categoryId: 3, tagIds: [9], oneOffs: false }]));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.queryByRole("button", { name: "Category and tags…" })).not.toBeInTheDocument();
  });

  it("gives ticked charges a category and tags and adds them all as one-offs on Apply", async () => {
    let current = UPLOAD;
    const fetchMock = statementBackend(UNCHOSEN, {
      "GET /api/statement-uploads/5": () => ({ status: 200, data: current }),
      "PUT /api/statement-uploads/5/filing": () => {
        current = withLine(11, { categoryId: 3, addOneOff: true }, withLine(14, { categoryId: 3, addOneOff: true }));
        return { status: 200, data: current, changed: true };
      },
    });
    renderApp("/accounts/1/statements/5");

    await userEvent.click(within(await row("KROGER #123")).getByRole("checkbox", { name: "Tick KROGER #123" }));
    await userEvent.click(within(await row("KROGER #456")).getByRole("checkbox", { name: "Tick KROGER #456" }));
    await userEvent.click(screen.getByRole("button", { name: "Category and tags…" }));
    const dialog = screen.getByRole("dialog", { name: "2 charges · $80.00" });
    await userEvent.type(within(dialog).getByRole("combobox", { name: "Category (optional)" }), "Groc");
    await userEvent.click(within(dialog).getByRole("option", { name: "Groceries" }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Save and add as one-offs" }));

    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "PUT /api/statement-uploads/5/filing")).toEqual([{ lineIds: [11, 14], categoryId: 3, tagIds: [], oneOffs: true }]),
    );
    expect(calls(fetchMock)).not.toContain("POST /api/statement-uploads/5/apply");
  });

  it("offers the budget a payee went on last time, with Use, and takes a charge off again", async () => {
    let current = withLine(11, { suggestedItemId: 21 });
    const fetchMock = statementBackend(UNCHOSEN, {
      "GET /api/statement-uploads/5": () => ({ status: 200, data: current }),
      "PUT /api/statement-uploads/5/budget": (body) => {
        const itemId = (body as { itemId: number | null }).itemId;
        current = withLine(11, { budgetItemId: itemId, suggestedItemId: itemId === null ? 21 : null });
        return { status: 200, data: current, changed: true };
      },
    });
    renderApp("/accounts/1/statements/5");

    const kroger = await row("KROGER #123");
    expect(within(kroger).getByText("Food for Amara's house last time")).toBeInTheDocument();
    await userEvent.click(within(kroger).getByRole("button", { name: "Use" }));
    await userEvent.click(await within(await row("KROGER #123")).findByRole("button", { name: "Take off" }));
    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "PUT /api/statement-uploads/5/budget")).toEqual([
        { lineIds: [11], itemId: 21 },
        { lineIds: [11], itemId: null },
      ]),
    );
  });

  it("shows each budget date as now and what it becomes, spent plus still to come, with its charges", async () => {
    const partly: BudgetDateChange = {
      ...FOOD_SEP_1,
      covered: "partly",
      coveredThrough: "2026-09-07",
      actualCents: 5420,
      stillToComeCents: 14580,
      newCents: 20000,
      lineIds: [11],
    };
    const fetchMock = statementBackend(
      { ...ON_FOOD, periods: [period("2026-09-01", "2026-09-14", { changes: [partly], upToDate: false }), ON_FOOD.periods[1]!] },
      { "PUT /api/statement-uploads/5/still-to-come": () => ({ status: 200, data: ON_FOOD, changed: true }) },
    );
    renderApp("/accounts/1/statements/5");

    const changes = await screen.findByRole("region", { name: "What changes" });
    const food = (await within(changes).findByText("Food for Amara's house")).closest("li")!;
    expect(food).toHaveTextContent("Now $200.00");
    expect(food).toHaveTextContent("Becomes $200.00");
    expect(food).toHaveTextContent("the statements cover it to Sep 7");
    expect(food).toHaveTextContent("Spent $54.20 + still to come $145.80");
    await userEvent.click(within(food).getByRole("button", { name: "1 charge" }));
    expect(within(food).getByText(/KROGER #123/)).toBeInTheDocument();

    await userEvent.click(within(food).getByRole("button", { name: "Edit still to come for Food for Amara's house" }));
    const dialog = screen.getByRole("dialog", { name: /still to come/ });
    await userEvent.clear(within(dialog).getByLabelText("Still to come"));
    await userEvent.type(within(dialog).getByLabelText("Still to come"), "60");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "PUT /api/statement-uploads/5/still-to-come")).toEqual([
        { itemId: 21, scheduledDate: "2026-09-01", cents: 6000 },
      ]),
    );
  });

  it("matches a charge to a bill near its pay period, same amount first, and lets go of it", async () => {
    const water: Matchable = {
      itemId: 31,
      scheduledDate: "2026-09-15",
      entryId: null,
      name: "Water bill",
      date: "2026-09-15",
      amountCents: 6000,
      paid: false,
      periodStart: "2026-09-15",
      matchedLineId: null,
    };
    const gym: Matchable = { ...water, itemId: 32, name: "Gym", scheduledDate: "2026-09-05", date: "2026-09-05", periodStart: "2026-09-01", amountCents: 5420 };
    const taken: Matchable = { ...water, itemId: 33, name: "Phone", matchedLineId: 99 };
    const offered: StatementChanges = { ...UNCHOSEN, periods: [period("2026-09-01", "2026-09-14", { matchable: [water, gym, taken] }), UNCHOSEN.periods[1]!] };
    // The page reads the upload again after each change, so the backend keeps what it was told.
    let current: StatementUpload = UPLOAD;
    const fetchMock = statementBackend(offered, {
      "GET /api/statement-uploads/5": () => ({ status: 200, data: current }),
      "PUT /api/statement-uploads/5/lines/11/match": () => {
        current = withLine(11, { matched: { itemId: 32, scheduledDate: "2026-09-05", entryId: null, applied: false } });
        return { status: 200, data: current, changed: true };
      },
      "DELETE /api/statement-uploads/5/lines/11/match": () => {
        current = UPLOAD;
        return { status: 200, data: current, changed: true };
      },
    });
    renderApp("/accounts/1/statements/5");

    await userEvent.click(within(await row("KROGER #123")).getByRole("button", { name: "Already in Budget…" }));
    const dialog = await screen.findByRole("dialog", { name: /KROGER #123/ });
    expect(within(dialog).getByText("Spent Sep 2 · in the pay period from Sep 1")).toBeInTheDocument();
    const choices = within(within(dialog).getByRole("list")).getAllByRole("button");
    // The same amount comes first, one another charge already has isn't offered, and one from the next pay period says so.
    expect(choices.map((button) => button.textContent)).toEqual([expect.stringContaining("Gym"), expect.stringContaining("Water bill")]);
    expect(choices[1]).toHaveTextContent("next pay period");

    await userEvent.click(choices[0]!);
    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "PUT /api/statement-uploads/5/lines/11/match")).toEqual([{ itemId: 32, scheduledDate: "2026-09-05" }]),
    );
    const matched = (await screen.findByText(/Is Gym/)).closest("li")!;
    expect(matched).toHaveTextContent("ticked paid on Apply");
    await userEvent.click(within(matched).getByRole("button", { name: "Unmatch" }));
    await waitFor(() => expect(calls(fetchMock)).toContain("DELETE /api/statement-uploads/5/lines/11/match"));
  });

  it("chooses a charge to add as a one-off on Apply, labelling its category and tags, and takes that back", async () => {
    const produce: Category = { id: 4, name: "Produce", color: "#65a30d", parentId: 3, itemCount: 0, perYearCents: 0 };
    let current = withLine(12, { categoryId: 4, tagIds: [9] });
    const fetchMock = statementBackend(UNCHOSEN, {
      "GET /api/categories": () => ({ status: 200, data: [GROCERIES, produce] }),
      "GET /api/statement-uploads/5": () => ({ status: 200, data: current }),
      "POST /api/statement-uploads/5/lines/12/one-off": () => {
        current = withLine(12, { addOneOff: true }, current);
        return { status: 200, data: current, changed: true };
      },
      "DELETE /api/statement-uploads/5/lines/12/one-off": () => {
        current = withLine(12, { addOneOff: false }, current);
        return { status: 200, data: current, changed: true };
      },
    });
    renderApp("/accounts/1/statements/5");

    await userEvent.click(within(await row("CAFE LUNA")).getByRole("button", { name: "Add as a one-off" }));
    const cafe = await row("CAFE LUNA");
    expect(await within(cafe).findByText("A one-off, added on Apply")).toBeInTheDocument();
    // A one-off is what uses a category and tags: each labelled, the category with what it sits under.
    expect(within(cafe).getByText("Category")).toBeInTheDocument();
    expect(within(cafe).getByText("Groceries › Produce")).toBeInTheDocument();
    expect(within(cafe).getByText("Tag")).toBeInTheDocument();
    expect(calls(fetchMock)).not.toContain("POST /api/statement-uploads/5/apply");

    await userEvent.click(within(cafe).getByRole("button", { name: "Don't add" }));
    await waitFor(() => expect(calls(fetchMock)).toContain("DELETE /api/statement-uploads/5/lines/12/one-off"));
  });

  it("sets what the statement says against the rows Budget read, with the missing records between them", async () => {
    let current: StatementUpload = { ...UPLOAD, onCard: true };
    const fetchMock = statementBackend(UNCHOSEN, {
      "GET /api/statement-uploads/5": () => ({ status: 200, data: current }),
      "PUT /api/statement-uploads/5/balances": () => {
        current = {
          ...UPLOAD,
          onCard: true,
          check: {
            previousCents: 100000,
            newCents: 140000,
            statementChargesCents: 9000,
            statementCreditsCents: 50000,
            spentCents: 8450,
            backCents: 50000,
            expectedNewCents: 141550,
            differenceCents: -1550,
          },
        };
        return { status: 200, data: current, changed: true };
      },
    });
    renderApp("/accounts/1/statements/5");

    // Rachel, 2026-10-30: "rows come to should be a summation of every row you extracted added together".
    const card = await screen.findByRole("region", { name: "Check against the statement" });
    expect(within(card).getByText("Rows added up").nextElementSibling).toHaveTextContent("$415.50");
    expect(within(card).getByText("Beginning balance").nextElementSibling).toHaveTextContent("—");
    expect(within(card).getByText("Missing records").nextElementSibling).toHaveTextContent("—");
    expect(within(card).queryByText(/Statement says owed/)).not.toBeInTheDocument();
    expect(within(card).getByRole("button", { name: 'What "Check against the statement" means' })).toBeInTheDocument();

    await userEvent.click(within(card).getByRole("button", { name: "Edit what the statement says" }));
    const dialog = screen.getByRole("dialog", { name: "What the statement says" });
    await userEvent.type(within(dialog).getByLabelText("Beginning balance"), "1000");
    await userEvent.type(within(dialog).getByLabelText("Statement balance"), "1400");
    await userEvent.type(within(dialog).getByLabelText("Total charges"), "90");
    await userEvent.type(within(dialog).getByLabelText("Payments and credits"), "500");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "PUT /api/statement-uploads/5/balances")).toEqual([
        { previousCents: 100000, newCents: 140000, chargesCents: 9000, creditsCents: 50000 },
      ]),
    );
    expect(await within(card).findByText("A row may be missing or misread.")).toBeInTheDocument();
    expect(within(card).getByText("Missing records").nextElementSibling).toHaveTextContent("$15.50");
    // Each total of the rows beside the statement's own, said in words.
    expect(within(card).getByText("Off by $5.50")).toBeInTheDocument();
    expect(within(card).getByText("Matches the statement")).toBeInTheDocument();
  });

  it("says when the rows add up", async () => {
    statementBackend(UNCHOSEN, {
      "GET /api/statement-uploads/5": () => ({
        status: 200,
        data: {
          ...UPLOAD,
          check: {
            previousCents: 100000,
            newCents: 141550,
            statementChargesCents: null,
            statementCreditsCents: null,
            spentCents: 8450, backCents: 50000, expectedNewCents: 141550, differenceCents: 0 },
        },
      }),
    });
    renderApp("/accounts/1/statements/5");

    expect(await screen.findByText("Every row was read.")).toBeInTheDocument();
  });

  it("fixes what was read on a second step of the line form", async () => {
    const fetchMock = statementBackend(UNCHOSEN, {
      "PUT /api/statement-uploads/5/lines/12": () => ({ status: 200, data: { upload: UPLOAD, alsoFiled: 0 }, changed: true }),
    });
    renderApp("/accounts/1/statements/5");

    await userEvent.click(await screen.findByRole("button", { name: "Edit CAFE LUNA" }));
    const dialog = screen.getByRole("dialog", { name: /CAFE LUNA/ });
    expect(within(dialog).queryByLabelText("Day spent")).not.toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: /Fix what was read/ }));
    await userEvent.clear(within(dialog).getByLabelText("Amount (money back with a minus)"));
    await userEvent.type(within(dialog).getByLabelText("Amount (money back with a minus)"), "5.40");
    await userEvent.click(within(dialog).getByRole("button", { name: "Back" }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Save line" }));

    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "PUT /api/statement-uploads/5/lines/12")).toEqual([
        expect.objectContaining({ amountCents: 540, txnDate: "2026-09-16", description: "CAFE LUNA", leftOut: false }),
      ]),
    );
  });

  it("puts a left-out line back", async () => {
    const fetchMock = statementBackend(UNCHOSEN, {
      "PUT /api/statement-uploads/5/lines/13": () => ({ status: 200, data: { upload: UPLOAD, alsoFiled: 0 }, changed: true }),
    });
    renderApp("/accounts/1/statements/5");

    await userEvent.click(await screen.findByRole("button", { name: "Put back" }));
    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/statement-uploads/5/lines/13")).toEqual([{ categoryId: null, tagIds: [], leftOut: false }]));
  });

  it("applies every pay period with something to do", async () => {
    const fetchMock = statementBackend(ON_FOOD, {
      "POST /api/statement-uploads/5/apply": () => ({ status: 200, data: { amountsChanged: 1, changes: UNCHOSEN }, changed: true }),
    });
    renderApp("/accounts/1/statements/5");

    await userEvent.click(await screen.findByRole("button", { name: "Apply…" }));
    const dialog = await screen.findByRole("dialog", { name: "Apply the statement" });
    expect(within(dialog).getByText("1 budget changes")).toBeInTheDocument();
    expect(within(dialog).getByRole("checkbox")).toBeChecked();
    await userEvent.click(within(dialog).getByRole("button", { name: "Apply" }));
    await waitFor(() => expect(bodiesSentTo(fetchMock, "POST /api/statement-uploads/5/apply")).toEqual([{ periods: ["2026-09-01"] }]));
  });
});
