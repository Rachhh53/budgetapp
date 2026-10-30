import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Account, AccountDetail } from "../../src/api/accounts";
import type { Category, Tag } from "../../src/api/categories";
import type { PeriodChanges, StatementChanges, StatementLine, StatementUpload } from "../../src/api/statementUploads";
import { bodiesSentTo, calls, fakeBackend, renderApp } from "../helpers";

/**
 * Statement uploads (Rachel, 2026-10-25): a statement is read, its charges filed, and each category's or tag's total
 * changes only the item chosen for it, replacing or adding. Every statement here is made up.
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
    countsToward: null,
    oneOffEntryId: null,
    periodStart: "2026-09-01",
    newPayee: false,
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
    line(11, "KROGER #123", 8000, { categoryId: 3 }),
    line(12, "CAFE LUNA", 450, { txnDate: "2026-09-16", periodStart: "2026-09-15", newPayee: true }),
    line(13, "PAYMENT THANK YOU", -50000, { kind: "payment", leftOut: true }),
  ],
  toCheck: 0,
  skippedRows: [],
};

const FOOD = {
  itemId: 21,
  name: "Food for Amara's house",
  kind: "budget" as const,
  categoryId: 3,
  tagIds: [],
  dates: [{ scheduledDate: "2026-09-01", countsOn: "2026-09-01", currentCents: 20000, estimate: false }],
};

function period(start: string, end: string, more: Partial<PeriodChanges> = {}): PeriodChanges {
  return {
    start,
    end,
    current: false,
    coverage: { from: start, to: end, partly: false },
    groups: [],
    items: [{ ...FOOD, dates: [{ ...FOOD.dates[0]!, scheduledDate: start, countsOn: start }] }],
    changes: [],
    undecided: [],
    unmatched: [],
    upToDate: true,
    ...more,
  };
}

const GROCERY_TOTAL = {
  type: "category" as const,
  id: 3,
  name: "Groceries",
  color: "#15803d",
  totalCents: 8000,
  lineIds: [11],
  target: null,
  hintItemId: null,
};

const COUNTED = [
  { id: 11, uploadId: 5, uploadName: "september.ofx", txnDate: "2026-09-02", description: "KROGER #123", amountCents: 8000, categoryId: 3, tagIds: [], countsToward: null },
  { id: 12, uploadId: 5, uploadName: "september.ofx", txnDate: "2026-09-16", description: "CAFE LUNA", amountCents: 450, categoryId: null, tagIds: [], countsToward: null },
];

/** Nothing chosen yet: Groceries totals $80.00 in the first period, and Cafe Luna is filed under nothing. */
const UNCHOSEN: StatementChanges = {
  uploadId: 5,
  accountId: 1,
  periods: [period("2026-09-01", "2026-09-14", { groups: [GROCERY_TOTAL] }), period("2026-09-15", "2026-09-30", { unmatched: [12] })],
  outside: [],
  lines: COUNTED,
};

/** Groceries pointed at Food, replacing it: $200.00 becomes $80.00. */
const CHOSEN: StatementChanges = {
  ...UNCHOSEN,
  periods: [
    period("2026-09-01", "2026-09-14", {
      groups: [{ ...GROCERY_TOTAL, target: { itemId: 21, mode: "replace" } }],
      changes: [{ itemId: 21, scheduledDate: "2026-09-01", currentCents: 20000, newCents: 8000, lineIds: [11] }],
      upToDate: false,
    }),
    period("2026-09-15", "2026-09-30", { unmatched: [12], coverage: { from: "2026-09-15", to: "2026-09-22", partly: true } }),
  ],
};

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

  it("lists every charge by pay period, saying which are not filed and which are left out", async () => {
    statementBackend();
    renderApp("/accounts/1/statements/5");

    const first = await screen.findByRole("region", { name: "Pay period from Sep 1, 2026" });
    expect(within(first).getByText("KROGER #123")).toBeInTheDocument();
    expect(within(first).getByText("A payment to the card · left out")).toBeInTheDocument();
    const second = screen.getByRole("region", { name: "Pay period from Sep 15, 2026" });
    expect(within(second).getByText("CAFE LUNA")).toBeInTheDocument();
    expect(within(second).getByText("Not filed yet")).toBeInTheDocument();
    expect(screen.getByText(/One charge is from a payee Budget hasn't seen yet/)).toBeInTheDocument();
  });

  it("files a new payee's charge, putting right what was read on a second step of the same form", async () => {
    const fetchMock = statementBackend(UNCHOSEN, {
      "PUT /api/statement-uploads/5/lines/12": () => ({ status: 200, data: { upload: UPLOAD, alsoFiled: 2 }, changed: true }),
    });
    renderApp("/accounts/1/statements/5");

    await userEvent.click(await screen.findByRole("button", { name: "Edit CAFE LUNA" }));
    const dialog = screen.getByRole("dialog", { name: /CAFE LUNA/ });
    await userEvent.type(within(dialog).getByRole("combobox", { name: "Category (optional)" }), "Groceries{Enter}");

    // What was read is a step of its own, so the form never grows past one screen.
    expect(within(dialog).queryByLabelText("Day spent")).not.toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: /Fix what was read/ }));
    expect(within(dialog).queryByRole("combobox", { name: "Category (optional)" })).not.toBeInTheDocument();
    await userEvent.clear(within(dialog).getByLabelText("Amount (money back with a minus)"));
    await userEvent.type(within(dialog).getByLabelText("Amount (money back with a minus)"), "5.40");
    await userEvent.click(within(dialog).getByRole("button", { name: "Back to category and tags" }));
    expect(within(dialog).getByRole("combobox", { name: "Category (optional)" })).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "Save line" }));

    expect(await screen.findByText("Also filed 2 other charges from the same payee.")).toBeInTheDocument();
    expect(bodiesSentTo(fetchMock, "PUT /api/statement-uploads/5/lines/12")).toEqual([
      expect.objectContaining({ categoryId: 3, amountCents: 540, txnDate: "2026-09-16", description: "CAFE LUNA", leftOut: false }),
    ]);
  });

  it("puts a left-out line back", async () => {
    const fetchMock = statementBackend(UNCHOSEN, {
      "PUT /api/statement-uploads/5/lines/13": () => ({ status: 200, data: { upload: UPLOAD, alsoFiled: 0 }, changed: true }),
    });
    renderApp("/accounts/1/statements/5");

    await userEvent.click(await screen.findByRole("button", { name: "Put back" }));
    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/statement-uploads/5/lines/13")).toEqual([
      { categoryId: null, tagIds: [], leftOut: false, countsToward: null },
    ]));
  });

  it("chooses nothing by itself: a total changes an item only once one is picked under Replaces", async () => {
    let answer = UNCHOSEN;
    const fetchMock = statementBackend(UNCHOSEN, {
      "GET /api/statement-uploads/5/changes": () => ({ status: 200, data: answer }),
      "PUT /api/statement-uploads/5/targets": () => {
        answer = CHOSEN;
        return { status: 200, data: CHOSEN, changed: true };
      },
    });
    renderApp("/accounts/1/statements/5");

    const replaces = (await screen.findAllByLabelText("Replaces"))[0]!;
    expect(replaces).toHaveValue("");
    expect(screen.getByRole("button", { name: "Apply…" })).toBeDisabled();

    await userEvent.selectOptions(replaces, "21");
    expect(bodiesSentTo(fetchMock, "PUT /api/statement-uploads/5/targets")).toEqual([
      { periodStart: "2026-09-01", groupType: "category", groupId: 3, itemId: 21, mode: "replace" },
    ]);
    expect(await screen.findByText("becomes")).toBeInTheDocument();
    expect(screen.getByText("$200.00")).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Replace" })).toBeChecked();

    await userEvent.click(screen.getByRole("radio", { name: "Add" }));
    expect(bodiesSentTo(fetchMock, "PUT /api/statement-uploads/5/targets")[1]).toMatchObject({ itemId: 21, mode: "add" });
  });

  it("offers the item chosen last time as a suggestion with Use", async () => {
    const hinted: StatementChanges = {
      ...UNCHOSEN,
      periods: [period("2026-09-01", "2026-09-14", { groups: [{ ...GROCERY_TOTAL, hintItemId: 21 }] })],
    };
    const fetchMock = statementBackend(hinted, {
      "PUT /api/statement-uploads/5/targets": () => ({ status: 200, data: CHOSEN, changed: true }),
    });
    renderApp("/accounts/1/statements/5");

    expect(await screen.findByText(/Last time: Food for Amara's house/)).toBeInTheDocument();
    expect(screen.getByLabelText("Replaces")).toHaveValue("");
    await userEvent.click(screen.getByRole("button", { name: "Use" }));
    expect(bodiesSentTo(fetchMock, "PUT /api/statement-uploads/5/targets")).toEqual([
      { periodStart: "2026-09-01", groupType: "category", groupId: 3, itemId: 21, mode: "replace" },
    ]);
  });

  it("applies the chosen pay periods, leaving one only partly covered unticked", async () => {
    const partlyToo: StatementChanges = {
      ...CHOSEN,
      periods: [
        CHOSEN.periods[0]!,
        { ...CHOSEN.periods[1]!, upToDate: false, changes: [{ itemId: 21, scheduledDate: "2026-09-15", currentCents: 20000, newCents: 450, lineIds: [12] }] },
      ],
    };
    const fetchMock = statementBackend(partlyToo, {
      "POST /api/statement-uploads/5/apply": () => ({ status: 200, data: { amountsChanged: 1, oneOffsAdded: 0, changes: partlyToo }, changed: true }),
    });
    renderApp("/accounts/1/statements/5");

    await userEvent.click(await screen.findByRole("button", { name: "Apply…" }));
    const dialog = await screen.findByRole("dialog", { name: "Apply the statement" });
    const boxes = within(dialog).getAllByRole("checkbox");
    expect(boxes[0]).toBeChecked();
    expect(boxes[1]).not.toBeChecked();
    expect(within(dialog).getByText(/partly covered, to Sep 22/)).toBeInTheDocument();

    await userEvent.click(within(dialog).getByRole("button", { name: "Apply" }));
    await waitFor(() => expect(bodiesSentTo(fetchMock, "POST /api/statement-uploads/5/apply")).toEqual([{ periods: ["2026-09-01"], oneOffLineIds: [] }]));
  });

  it("adds charges nothing takes as one-offs when asked", async () => {
    const fetchMock = statementBackend(UNCHOSEN, {
      "POST /api/statement-uploads/5/apply": () => ({ status: 200, data: { amountsChanged: 0, oneOffsAdded: 1, changes: UNCHOSEN }, changed: true }),
    });
    renderApp("/accounts/1/statements/5");

    const notMatched = await screen.findByRole("region", { name: "Not matched" });
    expect(within(notMatched).getByRole("button", { name: "Add as one-offs" })).toBeDisabled();
    await userEvent.click(within(notMatched).getByRole("checkbox"));
    await userEvent.click(within(notMatched).getByRole("button", { name: "Add 1 as a one-off" }));
    await waitFor(() => expect(bodiesSentTo(fetchMock, "POST /api/statement-uploads/5/apply")).toEqual([{ periods: [], oneOffLineIds: [12] }]));
    expect(await screen.findByText("Added 1 one-off.")).toBeInTheDocument();
  });

  it("asks which item a charge two items claim counts toward", async () => {
    const trip = { ...FOOD, itemId: 22, name: "Trip money" };
    const claimed: StatementChanges = {
      ...UNCHOSEN,
      lines: [{ ...COUNTED[0]!, tagIds: [9] }],
      periods: [
        period("2026-09-01", "2026-09-14", {
          items: [FOOD, trip],
          groups: [
            { ...GROCERY_TOTAL, target: { itemId: 21, mode: "replace" } },
            { type: "tag", id: 9, name: "Vacation", color: "#475569", totalCents: 8000, lineIds: [11], target: { itemId: 22, mode: "replace" }, hintItemId: null },
          ],
          undecided: [11],
          upToDate: false,
        }),
      ],
    };
    const fetchMock = statementBackend(claimed, {
      "PUT /api/statement-uploads/5/lines/11": () => ({ status: 200, data: { upload: UPLOAD, alsoFiled: 0 }, changed: true }),
    });
    renderApp("/accounts/1/statements/5");

    expect(await screen.findByText("Which item should these count toward?")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Vacation → Trip money" }));
    expect(bodiesSentTo(fetchMock, "PUT /api/statement-uploads/5/lines/11")).toEqual([
      { categoryId: 3, tagIds: [9], countsToward: { type: "tag", id: 9 } },
    ]);
  });
});
