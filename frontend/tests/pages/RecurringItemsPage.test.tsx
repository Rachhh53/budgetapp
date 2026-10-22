import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { CostsByMonth } from "../../src/api/annual";
import type { Account } from "../../src/api/accounts";
import type { Bucket } from "../../src/api/buckets";
import type { Category, Tag } from "../../src/api/categories";
import type { Item } from "../../src/api/items";
import { describeItemSchedule, describeMonths } from "../../src/components/items/labels";
import { todayIso } from "../../src/lib/dates";
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
  latestBalance: null,
};
const SAVINGS: Account = { ...CHECKING, id: 2, name: "Rainy Day Savings", type: "savings", color: "#0369a1" };
const VISA: Account = { ...CHECKING, id: 3, name: "Visa", type: "credit_card", color: "#7e22ce" };

const HOUSING: Category = { id: 1, name: "Housing", color: "#0f766e", parentId: null, itemCount: 1, perYearCents: 1860000 };
const RENT_CATEGORY: Category = { ...HOUSING, id: 2, name: "Rent", parentId: 1 };
const SUBSCRIPTIONS: Tag = { id: 1, name: "Subscriptions", color: "#475569", itemCount: 0, perYearCents: 0 };

const RENT: Item = {
  id: 1,
  name: "Rent",
  kind: "bill",
  note: null,
  archived: false,
  createdOn: "2026-01-05",
  tagIds: [],
  amountCents: 155000,
  amountMode: "per_occurrence",
  estimate: false,
  categoryId: 2,
  payFromAccountId: 1,
  payToAccountId: null,
  autopay: false,
  placement: "due_period",
  schedule: {
    frequency: "monthly",
    interval: 1,
    day1: 1,
    day2: null,
    month: null,
    startDate: "2026-01-01",
    endDate: null,
    weekendRule: "none",
  },
  daysAfterPayday: 0,
  bucketId: null,
  saveUpBucketId: null,
  saveUpPerPaycheckCents: null,
  perOccurrenceCents: 155000,
  perYearCents: 1860000,
  // Due on payday and paid by hand, so the paycheck before pays for it.
  next: { scheduledDate: "2026-11-01", dueDate: "2026-11-01", periodStart: "2026-10-15", early: true },
  history: [{ versionId: 1, effectiveFrom: "2026-01-01", perOccurrenceCents: 155000, perYearCents: 1860000 }],
  paused: false,
  pausedUntil: null,
  fromBucketId: null,
};
const ELECTRIC: Item = {
  ...RENT,
  id: 2,
  name: "Electric",
  amountCents: 14000,
  estimate: true,
  categoryId: null,
  schedule: { ...RENT.schedule, day1: 16 },
  perOccurrenceCents: 14000,
  perYearCents: 168000,
  next: { scheduledDate: "2026-10-16", dueDate: "2026-10-16", periodStart: "2026-10-15", early: false },
};
const GROCERIES: Item = {
  ...RENT,
  id: 3,
  name: "Groceries",
  kind: "budget",
  amountCents: 30000,
  categoryId: null,
  schedule: { ...RENT.schedule, frequency: "every_pay_period", day1: null, startDate: "2026-10-01" },
  perOccurrenceCents: 30000,
  perYearCents: 720000,
  next: { scheduledDate: "2026-10-15", dueDate: "2026-10-15", periodStart: "2026-10-15", early: false },
};
const TO_SAVINGS: Item = {
  ...RENT,
  id: 4,
  name: "To savings",
  kind: "transfer",
  amountCents: 20000,
  categoryId: null,
  payToAccountId: 2,
  autopay: true,
  schedule: { ...RENT.schedule, day1: 2 },
  perOccurrenceCents: 20000,
  perYearCents: 240000,
  next: { scheduledDate: "2026-11-02", dueDate: "2026-11-02", periodStart: "2026-11-01", early: false },
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
  balanceCents: 15000,
};
const EMERGENCY: Bucket = { ...GIFTS, id: 8, accountId: 2, name: "Emergency fund", goalCents: null };

function withItems(items: Item[] = [ELECTRIC, GROCERIES, RENT, TO_SAVINGS], overrides: Parameters<typeof fakeBackend>[0] = {}) {
  return fakeBackend({
    "GET /api/accounts": () => ({ status: 200, data: [CHECKING, SAVINGS, VISA] }),
    "GET /api/categories": () => ({ status: 200, data: [HOUSING, RENT_CATEGORY] }),
    "GET /api/tags": () => ({ status: 200, data: [SUBSCRIPTIONS] }),
    "GET /api/items": () => ({ status: 200, data: items }),
    ...overrides,
  });
}

/** The table row (a stacked card on a phone) for one item. */
async function rowFor(name: string): Promise<HTMLElement> {
  const link = await screen.findByRole("link", { name });
  return link.closest<HTMLElement>('[role="row"]')!;
}

/** Goes on to the next screen of the add form, which asks for a new item a screen at a time. */
async function next(dialog: HTMLElement) {
  await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
}

/** Chooses an entry in a type-to-filter picker by typing its start and pressing Enter. */
async function pick(dialog: HTMLElement, label: string, text: string) {
  await userEvent.type(within(dialog).getByRole("combobox", { name: label }), `${text}{Enter}`);
}

describe("recurring items screen", () => {
  it("asks for an account before anything can be added", async () => {
    fakeBackend();
    renderApp("/recurring");

    expect(await screen.findByText("Add an account first.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Add the account you pay from" })).toHaveAttribute("href", "/accounts");
    expect(screen.getByRole("button", { name: "+ Add item" })).toBeDisabled();
  });

  it("invites the first item once there is an account", async () => {
    withItems([]);
    renderApp("/recurring");

    expect(await screen.findByText("No recurring items yet.")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "+ Add item" })).toBeEnabled());
  });

  it("says so kindly when the items can't be loaded", async () => {
    withItems([], { "GET /api/items": () => ({ status: 500, error: "Something went wrong on our side. Your data is safe — please try again." }) });
    renderApp("/recurring");

    expect(await screen.findByRole("alert")).toHaveTextContent("Your data is safe");
  });

  it("lists each item with its schedule, amounts, paying account, paycheck and next date", async () => {
    withItems();
    renderApp("/recurring");

    const rent = await rowFor("Rent");
    expect(rent).toHaveTextContent("Bill or expense · manual");
    expect(rent).toHaveTextContent("Monthly · 1st");
    expect(rent).toHaveTextContent("$1,550.00");
    expect(rent).toHaveTextContent("$18,600.00");
    expect(rent).toHaveTextContent("Everyday Checking");
    expect(rent).toHaveTextContent("1 early");
    expect(rent).toHaveTextContent("Nov 1");

    const electric = await rowFor("Electric");
    // Marked as an estimate in words, not just by its blue box.
    expect(electric).toHaveTextContent("$140.00 (estimate)");
    expect(electric).toHaveTextContent("Due period");
    expect(screen.getByText("= estimate")).toBeInTheDocument();

    const groceries = await rowFor("Groceries");
    expect(groceries).toHaveTextContent("Budget");
    expect(groceries).not.toHaveTextContent("manual");
    expect(groceries).toHaveTextContent("Every paycheck");
    expect(groceries).toHaveTextContent("Each period");

    const transfer = await rowFor("To savings");
    expect(transfer).toHaveTextContent("Transfer · autopay");
    expect(transfer).toHaveTextContent("Everyday Checking → Rainy Day Savings");
  });

  it("is a table to a screen reader, with a heading for every column", async () => {
    withItems();
    renderApp("/recurring");

    const table = await screen.findByRole("table", { name: "Recurring items" });
    expect(within(table).getAllByRole("columnheader").map((header) => header.textContent)).toEqual([
      "Name",
      "Type",
      "Schedule",
      "Amount",
      "Per year",
      "Paid from",
      "Lands in",
      "Next",
    ]);
    // A row does one thing, open the item's page: its name is the link, and there is no Edit button to compete with it.
    expect(within(table).getAllByRole("link").map((link) => link.getAttribute("href"))).toEqual([
      "/recurring/2",
      "/recurring/3",
      "/recurring/1",
      "/recurring/4",
    ]);
    expect(within(table).queryByRole("button", { name: /^Edit / })).not.toBeInTheDocument();
    // One header row and one row per item.
    expect(within(table).getAllByRole("row")).toHaveLength(5);
  });

  it("sorts by a column when its heading is pressed, and the other way when pressed again", async () => {
    withItems();
    renderApp("/recurring");

    const table = await screen.findByRole("table", { name: "Recurring items" });
    const names = () =>
      within(table)
        .getAllByRole("row")
        .slice(1)
        .map((row) => within(row).getAllByRole("cell")[0]!.textContent);
    const byName = names();
    expect(within(table).getByRole("columnheader", { name: "Name" })).toHaveAttribute("aria-sort", "ascending");

    await userEvent.click(within(table).getByRole("button", { name: "Amount" }));
    const amounts = [ELECTRIC, GROCERIES, RENT, TO_SAVINGS].sort((a, b) => a.perOccurrenceCents - b.perOccurrenceCents).map((item) => item.name);
    expect(names()).toEqual(amounts);
    expect(within(table).getByRole("columnheader", { name: "Amount" })).toHaveAttribute("aria-sort", "ascending");
    expect(within(table).getByRole("columnheader", { name: "Name" })).toHaveAttribute("aria-sort", "none");

    await userEvent.click(within(table).getByRole("button", { name: "Amount" }));
    expect(names()).toEqual([...amounts].reverse());
    expect(within(table).getByRole("columnheader", { name: "Amount" })).toHaveAttribute("aria-sort", "descending");

    await userEvent.click(within(table).getByRole("button", { name: "Name" }));
    expect(names()).toEqual(byName);
  });

  it("asks which bucket a bill comes out of when its account has buckets", async () => {
    const fetchMock = withItems([], {
      "GET /api/buckets": () => ({ status: 200, data: [GIFTS, EMERGENCY] }),
      "POST /api/items": () => ({ status: 201, data: ELECTRIC }),
    });
    renderApp("/recurring");

    await waitFor(() => expect(screen.getByRole("button", { name: "+ Add item" })).toBeEnabled());
    await userEvent.click(screen.getByRole("button", { name: "+ Add item" }));
    const dialog = screen.getByRole("dialog", { name: "Add a recurring item" });
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Name" }), "Streaming");
    await next(dialog);
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Amount each time" }), "15");
    await next(dialog);
    // Nothing to ask until an account with buckets is chosen.
    expect(within(dialog).queryByRole("combobox", { name: "Comes out of" })).not.toBeInTheDocument();
    await pick(dialog, "Paid from", "Every");
    const from = within(dialog).getByRole("combobox", { name: "Comes out of" });
    // Only that account's buckets are offered.
    expect(within(from).queryByRole("option", { name: /Emergency fund/ })).not.toBeInTheDocument();

    // It starts on money in no bucket (Rachel, 2026-10-20), and a bucket can be chosen instead.
    expect(from).toHaveDisplayValue("Money that is not in a bucket");

    await userEvent.selectOptions(from, within(from).getByRole("option", { name: /Holiday gifts/ }));
    expect(within(dialog).getByText(/Each time, the bucket and the account both go down/)).toBeInTheDocument();
    await next(dialog);
    await next(dialog);
    await userEvent.click(within(dialog).getByRole("button", { name: "Add item" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "POST /api/items")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "POST /api/items")[0]).toMatchObject({ name: "Streaming", payFromAccountId: 1, fromBucketId: 7 });
  });

  it("keeps where an existing item comes out of, and sends none for money not in a bucket", async () => {
    const fetchMock = withItems([RENT], {
      "GET /api/buckets": () => ({ status: 200, data: [GIFTS] }),
      "PUT /api/items/1": () => ({ status: 200, data: RENT }),
    });
    renderApp("/recurring/1");

    await userEvent.click(await screen.findByRole("button", { name: "Edit where it is paid from" }));
    const dialog = screen.getByRole("dialog", { name: "Edit Rent · Paid from" });
    // It was set up before buckets were asked about, so it stands as not from a bucket.
    expect(await within(dialog).findByRole("combobox", { name: "Comes out of" })).toHaveValue("none");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/items/1")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "PUT /api/items/1")[0]).toMatchObject({ fromBucketId: null });
  });

  it("pauses an item until a date or until it is turned back on, and resumes it", async () => {
    let rent: Item = RENT;
    const fetchMock = withItems([RENT], {
      "GET /api/items": () => ({ status: 200, data: [rent] }),
      "PUT /api/items/1/pause": (body) => {
        rent = { ...RENT, paused: true, pausedUntil: (body as { until?: string }).until ?? null, next: null };
        return { status: 200, data: rent, changed: true };
      },
      "DELETE /api/items/1/pause": () => {
        rent = RENT;
        return { status: 200, data: rent, changed: true };
      },
    });
    renderApp("/recurring/1");

    const card = await screen.findByRole("region", { name: "Pause" });
    await userEvent.click(within(card).getByRole("button", { name: "Pause until I turn it back on" }));

    expect(await within(card).findByText("Paused until you turn it back on.")).toBeInTheDocument();
    expect(bodiesSentTo(fetchMock, "PUT /api/items/1/pause")).toEqual([{}]);
    // Under the name, in words, and nothing is left on the calendar.
    expect(screen.getByText(/Bill or expense · manual · Monthly · 1st · Paused/)).toBeInTheDocument();
    expect(screen.getByText("paused, so nothing is scheduled")).toBeInTheDocument();

    await userEvent.click(within(card).getByRole("button", { name: "Resume now" }));
    const until = await within(card).findByLabelText("Start again on (optional)");
    await userEvent.type(until, "2999-12-01");
    await userEvent.click(within(card).getByRole("button", { name: "Pause until then" }));

    expect(await within(card).findByText("Paused. It starts again on Dec 1, 2999.")).toBeInTheDocument();
    expect(bodiesSentTo(fetchMock, "PUT /api/items/1/pause")[1]).toEqual({ until: "2999-12-01" });
    expect(screen.getByText(/Paused until Dec 1, 2999/)).toBeInTheDocument();
  });

  it("says in the list when an item is paused", async () => {
    withItems([{ ...RENT, paused: true, pausedUntil: "2999-12-01", next: null }]);
    renderApp("/recurring");

    expect(await rowFor("Rent")).toHaveTextContent("Paused until Dec 1");
  });

  it("filters by kind and counts each kind on its chip", async () => {
    withItems();
    renderApp("/recurring");

    await userEvent.click(await screen.findByRole("button", { name: "Bills & expenses · 2" }));

    expect(screen.getByRole("button", { name: "Bills & expenses · 2" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("link", { name: "Rent" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Groceries" })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "All · 4" }));
    expect(screen.getByRole("link", { name: "Groceries" })).toBeInTheDocument();
  });

  it("searches by name, account or category", async () => {
    withItems();
    renderApp("/recurring");
    const search = await screen.findByRole("searchbox", { name: "Search" });

    await userEvent.type(search, "rainy");
    expect(screen.getByRole("link", { name: "To savings" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Rent" })).not.toBeInTheDocument();

    await userEvent.clear(search);
    await userEvent.type(search, "zzz");
    expect(screen.getByText("Nothing matches that.")).toBeInTheDocument();
  });

  it("keeps items whose last date has gone by under Ended", async () => {
    withItems([{ ...ELECTRIC, schedule: { ...ELECTRIC.schedule, endDate: "2026-06-30" }, next: null }, RENT]);
    renderApp("/recurring");

    const table = await screen.findByRole("table", { name: "Recurring items" });
    expect(within(table).getAllByRole("row").slice(1)).toHaveLength(1);
    expect(screen.getByRole("button", { name: "All · 1" })).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Ended · 1" }));
    const rows = within(screen.getByRole("table", { name: "Recurring items" })).getAllByRole("row").slice(1);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveTextContent("Electric");
  });

  it("plans a last date in the schedule, and says so", async () => {
    const fetchMock = withItems([RENT], { "PUT /api/items/1": () => ({ status: 200, data: RENT }) });
    renderApp("/recurring/1");

    await userEvent.click(await screen.findByRole("button", { name: "Edit the schedule" }));
    const dialog = await screen.findByRole("dialog");
    const last = within(dialog).getByLabelText("Last date");
    fireEvent.change(last, { target: { value: "2025-06-30" } });
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("The last date can't be before the first one.");

    fireEvent.change(last, { target: { value: "2027-06-30" } });
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));
    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "PUT /api/items/1")).toEqual([
        expect.objectContaining({ schedule: expect.objectContaining({ endDate: "2027-06-30" }) }),
      ]),
    );
  });

  it("hides archived items until Archived is chosen, and counts them only there", async () => {
    withItems([{ ...ELECTRIC, archived: true, next: null }, RENT]);
    renderApp("/recurring");

    const table = await screen.findByRole("table", { name: "Recurring items" });
    expect(within(table).getAllByRole("row").slice(1)).toHaveLength(1);
    expect(screen.queryByRole("link", { name: "Electric" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "All · 1" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Bills & expenses · 1" })).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Archived · 1" }));
    const rows = within(screen.getByRole("table", { name: "Recurring items" })).getAllByRole("row").slice(1);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveTextContent("Electric");
    expect(rows[0]).toHaveTextContent("Archived");
    expect(rows[0]).toHaveTextContent("—");
    expect(screen.getByRole("link", { name: "Electric" })).toBeInTheDocument();
  });

  it("offers no Archived chip when nothing is archived, and says so when everything is", async () => {
    withItems([RENT]);
    const { unmount } = renderApp("/recurring");
    await screen.findByRole("button", { name: "All · 1" });
    expect(screen.queryByRole("button", { name: /^Archived/ })).not.toBeInTheDocument();
    unmount();

    withItems([{ ...ELECTRIC, archived: true, next: null }]);
    renderApp("/recurring");
    expect(await screen.findByText("Everything here is archived.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Archived · 1" })).toBeInTheDocument();
  });

  it("adds a bill", async () => {
    const fetchMock = withItems([], { "POST /api/items": () => ({ status: 201, data: ELECTRIC }) });
    renderApp("/recurring");

    await waitFor(() => expect(screen.getByRole("button", { name: "+ Add item" })).toBeEnabled());
    await userEvent.click(screen.getByRole("button", { name: "+ Add item" }));
    const dialog = screen.getByRole("dialog", { name: "Add a recurring item" });
    expect(within(dialog).getByRole("status")).toHaveTextContent("Step 1 of 5");
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Name" }), "Electric");
    await next(dialog);
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Amount each time" }), "140");
    await userEvent.click(within(dialog).getByRole("checkbox", { name: "It's an estimate" }));
    await next(dialog);
    await pick(dialog, "Paid from", "Every");
    // Going back keeps what was typed.
    await userEvent.click(within(dialog).getByRole("button", { name: "Back" }));
    expect(within(dialog).getByRole("textbox", { name: "Amount each time" })).toHaveValue("140");
    await next(dialog);
    expect(within(dialog).getByRole("combobox", { name: "Paid from" })).toHaveValue("Everyday Checking");
    // Which paycheck pays it is asked beside Autopay.
    await userEvent.click(within(dialog).getByRole("checkbox", { name: "Pay a period early" }));
    await next(dialog);
    await userEvent.selectOptions(within(dialog).getByRole("combobox", { name: "Day of the month" }), "16th");
    // Every month to start with.
    expect(within(dialog).getAllByRole("checkbox", { checked: true })).toHaveLength(12);
    // One named group at a time, and nothing is sent until the last screen.
    expect(within(dialog).getByRole("group", { name: "Schedule" })).toBeInTheDocument();
    expect(within(dialog).queryByRole("group", { name: "Amount" })).not.toBeInTheDocument();
    await next(dialog);
    expect(within(dialog).getByRole("status")).toHaveTextContent("Step 5 of 5");
    expect(within(dialog).getByRole("group", { name: "Tags and notes" })).toBeInTheDocument();
    expect(calls(fetchMock)).not.toContain("POST /api/items");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add item" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "POST /api/items")).toEqual([
      {
        name: "Electric",
        kind: "bill",
        note: null,
        archived: false,
        tagIds: [],
        amountCents: 14000,
        amountMode: "per_occurrence",
        estimate: true,
        billedLater: false,
        paidAhead: false,
        packSessions: null,
        packSchedule: null,
        usesHundredths: null,
        usesPer: null,
        categoryId: null,
        payFromAccountId: 1,
        payToAccountId: null,
        autopay: false,
        placement: "one_early",
        schedule: {
          frequency: "monthly",
          interval: null,
          day1: 16,
          day2: null,
          month: null,
          startDate: `${todayIso().slice(0, 4)}-01-01`,
          endDate: null,
          weekendRule: "none",
          months: [],
          weekdays: [],
        },
        daysAfterPayday: 0,
        bucketId: null,
        saveUpBucketId: null,
        fromBucketId: null,
      },
    ]);
  });

  it("adds a budget that comes round every paycheck, dated a few days after payday", async () => {
    const fetchMock = withItems([], { "POST /api/items": () => ({ status: 201, data: GROCERIES }) });
    renderApp("/recurring");

    await waitFor(() => expect(screen.getByRole("button", { name: "+ Add item" })).toBeEnabled());
    await userEvent.click(screen.getByRole("button", { name: "+ Add item" }));
    const dialog = screen.getByRole("dialog", { name: "Add a recurring item" });
    await userEvent.click(within(dialog).getByRole("radio", { name: "Budget" }));
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Name" }), "Groceries");
    await next(dialog);
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Amount each time" }), "300");
    await next(dialog);
    await pick(dialog, "Paid from", "Every");
    // A budget is not paid automatically, so it is not asked.
    expect(within(dialog).queryByRole("checkbox", { name: "Autopay" })).not.toBeInTheDocument();
    await next(dialog);
    await userEvent.selectOptions(within(dialog).getByRole("combobox", { name: "How often" }), "Every paycheck");
    await userEvent.selectOptions(within(dialog).getByRole("combobox", { name: "Dated" }), "3 days after payday");

    // Every paycheck's budget belongs to its own paycheck, so which one pays is not asked.
    await userEvent.click(within(dialog).getByRole("button", { name: "Back" }));
    expect(within(dialog).queryByRole("checkbox", { name: "Pay a period early" })).not.toBeInTheDocument();
    await next(dialog);

    await next(dialog);
    await userEvent.click(within(dialog).getByRole("button", { name: "Add item" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const [sent] = bodiesSentTo(fetchMock, "POST /api/items") as [Record<string, unknown>];
    expect(sent).toMatchObject({
      kind: "budget",
      amountCents: 30000,
      autopay: false,
      placement: "due_period",
      daysAfterPayday: 3,
      schedule: { frequency: "every_pay_period", startDate: todayIso(), day1: null },
    });
  });

  it("neither lists a bucket move nor offers one as a type: buckets are kept on their account", async () => {
    const fund: Item = { ...GROCERIES, id: 8, name: "Gift fund", kind: "bucket_move", bucketId: 7, categoryId: null };
    withItems([fund, RENT], { "GET /api/buckets": () => ({ status: 200, data: [GIFTS, EMERGENCY] }) });
    renderApp("/recurring");

    expect(await screen.findByRole("link", { name: "Rent" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Gift fund" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Bucket moves/ })).not.toBeInTheDocument();

    // Money reaches a bucket by a transfer that names it, so setting money aside is not a type of item.
    await waitFor(() => expect(screen.getByRole("button", { name: "+ Add item" })).toBeEnabled());
    await userEvent.click(screen.getByRole("button", { name: "+ Add item" }));
    const adding = screen.getByRole("dialog", { name: "Add a recurring item" });
    expect(within(adding).getAllByRole("radio").slice(0, 4).map((radio) => radio.closest("label")?.textContent)).toEqual([
      "Bill or expense",
      "Transfer",
      "Budget",
      "Card payment",
    ]);
    expect(within(adding).queryByRole("radio", { name: "Bucket move" })).not.toBeInTheDocument();
  });

  it("saves up for a bill in a bucket of the account it is paid from", async () => {
    const savedUpFor: Item = { ...ELECTRIC, saveUpBucketId: 7, saveUpPerPaycheckCents: 7000 };
    const fetchMock = withItems([savedUpFor], {
      "GET /api/buckets": () => ({ status: 200, data: [GIFTS, EMERGENCY] }),
      "PUT /api/items/2": () => ({ status: 200, data: savedUpFor }),
    });
    renderApp("/recurring/2");

    await userEvent.click(await screen.findByRole("button", { name: "Edit where it is paid from" }));
    const dialog = screen.getByRole("dialog", { name: "Edit Electric · Paid from" });
    const saveUp = within(dialog).getByRole("combobox", { name: "Save up for it in a bucket (optional)" });
    expect(saveUp).toHaveValue("Holiday gifts");
    expect(within(dialog).getByText(/The next paycheck sets aside/)).toHaveTextContent("$70.00");
    // Only the buckets of the account it is paid from are offered.
    await userEvent.click(saveUp);
    expect(within(dialog).queryByRole("option", { name: /Emergency fund/ })).not.toBeInTheDocument();

    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/items/2")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "PUT /api/items/2")[0]).toMatchObject({ kind: "bill", saveUpBucketId: 7, bucketId: null });
  });

  it("adds a bucket in place from the save-up picker, in the account the bill is paid from", async () => {
    let buckets = [EMERGENCY];
    const created = { ...GIFTS, id: 12, name: "Car insurance", goalCents: null, balanceCents: 0 };
    const fetchMock = withItems([ELECTRIC], {
      "GET /api/buckets": () => ({ status: 200, data: buckets }),
      "POST /api/buckets": () => {
        buckets = [...buckets, created];
        return { status: 201, data: { bucket: created, changes: [] }, changed: true };
      },
    });
    renderApp("/recurring/2");

    await userEvent.click(await screen.findByRole("button", { name: "Edit where it is paid from" }));
    const dialog = screen.getByRole("dialog", { name: "Edit Electric · Paid from" });
    // The account it is paid from has no bucket yet, and the option is there all the same.
    const saveUp = within(dialog).getByRole("combobox", { name: "Save up for it in a bucket (optional)" });
    await userEvent.type(saveUp, "Car insurance{Enter}");

    await waitFor(() => expect(saveUp).toHaveValue("Car insurance"));
    expect(bodiesSentTo(fetchMock, "POST /api/buckets")).toMatchObject([
      { accountId: ELECTRIC.payFromAccountId, name: "Car insurance", goalCents: null, startingCents: 0 },
    ]);
  });

  it("offers no More options on a bill paid from a card, which has no bucket to save up in", async () => {
    withItems([{ ...ELECTRIC, payFromAccountId: VISA.id }]);
    renderApp("/recurring/2");

    await userEvent.click(await screen.findByRole("button", { name: "Edit where it is paid from" }));
    const dialog = screen.getByRole("dialog", { name: "Edit Electric · Paid from" });

    // Nothing to choose there, so nothing is shown: an explanation alone is not an option.
    expect(within(dialog).queryByText("More options")).not.toBeInTheDocument();
    expect(within(dialog).queryByRole("combobox", { name: "Save up for it in a bucket (optional)" })).not.toBeInTheDocument();
  });

  it("adds a yearly bill entered as an annual amount", async () => {
    const fetchMock = withItems([], { "POST /api/items": () => ({ status: 201, data: RENT }) });
    renderApp("/recurring");

    await waitFor(() => expect(screen.getByRole("button", { name: "+ Add item" })).toBeEnabled());
    await userEvent.click(screen.getByRole("button", { name: "+ Add item" }));
    const dialog = screen.getByRole("dialog", { name: "Add a recurring item" });
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Name" }), "Car insurance");
    await next(dialog);
    await userEvent.click(within(dialog).getByRole("radio", { name: "Yearly total" }));
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Per year" }), "1,200");
    await next(dialog);
    await pick(dialog, "Paid from", "Every");
    await next(dialog);
    await userEvent.selectOptions(within(dialog).getByRole("combobox", { name: "How often" }), "Yearly");
    await userEvent.selectOptions(within(dialog).getByRole("combobox", { name: "Month" }), "March");
    await userEvent.selectOptions(within(dialog).getByRole("combobox", { name: "Day of the month" }), "12th");
    await next(dialog);
    await userEvent.click(within(dialog).getByRole("button", { name: "Add item" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const [sent] = bodiesSentTo(fetchMock, "POST /api/items") as [Record<string, unknown>];
    expect(sent).toMatchObject({
      amountCents: 120000,
      amountMode: "annual",
      schedule: { frequency: "yearly", month: 3, day1: 12 },
    });
  });

  it("asks where a transfer goes, and a card payment offers only cards", async () => {
    const fetchMock = withItems([], { "POST /api/items": () => ({ status: 201, data: TO_SAVINGS }) });
    renderApp("/recurring");

    await waitFor(() => expect(screen.getByRole("button", { name: "+ Add item" })).toBeEnabled());
    await userEvent.click(screen.getByRole("button", { name: "+ Add item" }));
    const dialog = screen.getByRole("dialog", { name: "Add a recurring item" });

    await userEvent.click(within(dialog).getByRole("radio", { name: "Card payment" }));
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Name" }), "To savings");
    await next(dialog);
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Amount each time" }), "200");
    await next(dialog);
    await userEvent.click(within(dialog).getByRole("combobox", { name: "Card" }));
    expect(within(within(dialog).getByRole("listbox", { name: "Card" })).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "VisaEnter",
    ]);

    await userEvent.click(within(dialog).getByRole("button", { name: "Back" }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Back" }));
    await userEvent.click(within(dialog).getByRole("radio", { name: "Transfer" }));
    await next(dialog);
    await next(dialog);
    await pick(dialog, "From", "Every");
    await next(dialog);
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please choose the account this money goes to.");

    await pick(dialog, "To", "Rainy");
    await next(dialog);
    await next(dialog);
    await userEvent.click(within(dialog).getByRole("button", { name: "Add item" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "POST /api/items")).toMatchObject([{ kind: "transfer", payFromAccountId: 1, payToAccountId: 2 }]);
  });

  it("explains what still needs attention instead of sending a half-filled form", async () => {
    const fetchMock = withItems([]);
    renderApp("/recurring");

    await waitFor(() => expect(screen.getByRole("button", { name: "+ Add item" })).toBeEnabled());
    await userEvent.click(screen.getByRole("button", { name: "+ Add item" }));
    const dialog = screen.getByRole("dialog", { name: "Add a recurring item" });
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Name" }), "Electric");
    await next(dialog);
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Amount each time" }), "lots");
    // Each screen is checked before the next one opens.
    await next(dialog);
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please enter an amount like 1,250.00.");
    expect(within(dialog).getByRole("status")).toHaveTextContent("Step 2 of 5");

    await userEvent.clear(within(dialog).getByRole("textbox", { name: "Amount each time" }));
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Amount each time" }), "140");
    await next(dialog);
    await next(dialog);
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please choose the account this is paid from.");
    expect(within(dialog).getByRole("status")).toHaveTextContent("Step 3 of 5");
    expect(calls(fetchMock).some((call) => call.startsWith("POST"))).toBe(false);
  });

  it("adds a new category and a new tag from their pickers without leaving the form", async () => {
    const created: Category = { id: 9, name: "Utilities", color: "#be185d", parentId: null, itemCount: 0, perYearCents: 0 };
    const newTag: Tag = { id: 7, name: "Home", color: "#0f766e", itemCount: 0, perYearCents: 0 };
    // Each list gains its new entry once it has been added, as the real backend's would.
    let categories = [HOUSING, RENT_CATEGORY];
    let tags = [SUBSCRIPTIONS];
    const fetchMock = withItems([], {
      "POST /api/categories": () => {
        categories = [...categories, created];
        return { status: 201, data: created };
      },
      "GET /api/categories": () => ({ status: 200, data: categories }),
      "POST /api/tags": () => {
        tags = [...tags, newTag];
        return { status: 201, data: newTag };
      },
      "GET /api/tags": () => ({ status: 200, data: tags }),
      "POST /api/items": () => ({ status: 201, data: ELECTRIC }),
    });
    renderApp("/recurring");

    await waitFor(() => expect(screen.getByRole("button", { name: "+ Add item" })).toBeEnabled());
    await userEvent.click(screen.getByRole("button", { name: "+ Add item" }));
    const dialog = screen.getByRole("dialog", { name: "Add a recurring item" });
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Name" }), "Electric");
    await next(dialog);
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Amount each time" }), "140");
    await next(dialog);
    await pick(dialog, "Paid from", "Every");
    await next(dialog);
    await next(dialog);

    await userEvent.type(within(dialog).getByRole("combobox", { name: "Category (optional)" }), "Utilities");
    await userEvent.click(within(dialog).getByRole("option", { name: 'Add "Utilities" as a new category' }));
    await waitFor(() => expect(within(dialog).getByRole("combobox", { name: "Category (optional)" })).toHaveValue("Utilities"));

    await pick(dialog, "Tags (optional)", "Subs");
    await userEvent.type(within(dialog).getByRole("combobox", { name: "Tags (optional)" }), "Home");
    await userEvent.click(within(dialog).getByRole("option", { name: 'Add "Home" as a new tag' }));
    expect(await within(dialog).findByRole("button", { name: "Remove tag Home" })).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "Remove tag Subscriptions" }));

    await userEvent.click(within(dialog).getByRole("button", { name: "Add item" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "POST /api/categories")).toMatchObject([{ name: "Utilities", parentId: null }]);
    expect(bodiesSentTo(fetchMock, "POST /api/tags")).toMatchObject([{ name: "Home" }]);
    expect(bodiesSentTo(fetchMock, "POST /api/items")).toMatchObject([{ categoryId: 9, tagIds: [7] }]);
  });

  it("removes an amount from its history, but never the only one", async () => {
    const twoPrices: Item = {
      ...RENT,
      history: [
        { versionId: 1, effectiveFrom: "2025-01-01", perOccurrenceCents: 148000, perYearCents: 1776000 },
        { versionId: 2, effectiveFrom: "2026-01-01", perOccurrenceCents: 155000, perYearCents: 1860000 },
      ],
    };
    const fetchMock = withItems([twoPrices], { "DELETE /api/items/1/versions/2": () => ({ status: 200, data: RENT, changed: true }) });
    renderApp("/recurring/1");

    const history = await screen.findByRole("region", { name: "Amount history" });
    await userEvent.click(within(history).getByRole("button", { name: "Remove the amount from Jan 1, 2026" }));

    await waitFor(() => expect(calls(fetchMock)).toContain("DELETE /api/items/1/versions/2"));
  });

  it("offers no Remove when an item has one amount", async () => {
    withItems([RENT]);
    renderApp("/recurring/1");

    const history = await screen.findByRole("region", { name: "Amount history" });
    expect(within(history).queryByRole("button", { name: /Remove/ })).not.toBeInTheDocument();
  });

  it("changes an item from a date on, with its amount history and date added on its page, not in the form", async () => {
    const twoPrices: Item = {
      ...RENT,
      history: [
        { versionId: 1, effectiveFrom: "2025-01-01", perOccurrenceCents: 148000, perYearCents: 1776000 },
        { versionId: 2, effectiveFrom: "2026-01-01", perOccurrenceCents: 155000, perYearCents: 1860000 },
      ],
    };
    const fetchMock = withItems([twoPrices], { "PUT /api/items/1": () => ({ status: 200, data: twoPrices }) });
    renderApp("/recurring/1");

    await userEvent.click(await screen.findByRole("button", { name: "Change the amount of Rent" }));
    const dialog = screen.getByRole("dialog", { name: "Edit Rent · Amount" });
    // Only the amount is asked about: the rest of the item is changed from its own cards.
    expect(within(dialog).queryByRole("combobox", { name: "Category (optional)" })).not.toBeInTheDocument();
    expect(within(dialog).queryByRole("combobox", { name: "Paid from" })).not.toBeInTheDocument();
    expect(within(dialog).queryByRole("textbox", { name: "Name" })).not.toBeInTheDocument();
    const history = screen.getByRole("region", { name: "Amount history" });
    expect(history).toHaveTextContent("From Jan 1, 2025");
    expect(history).toHaveTextContent("$17,760.00 a year");
    expect(within(dialog).queryByText("Amount history")).not.toBeInTheDocument();
    expect(within(dialog).queryByText("Pause")).not.toBeInTheDocument();

    const amount = within(dialog).getByRole("textbox", { name: "Amount each time" });
    expect(amount).toHaveValue("1550.00");
    await userEvent.clear(amount);
    await userEvent.type(amount, "1600");
    // The day it was added is stated, not asked; the change itself is dated today.
    expect(screen.getByText("Date added").nextElementSibling).toHaveTextContent("Jan 5, 2026");
    expect(within(dialog).queryByText("Date added")).not.toBeInTheDocument();
    expect(within(dialog).queryByLabelText(/apply.* from|date added/i)).not.toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "PUT /api/items/1")).toMatchObject([
      // Everything that was not shown goes back as it was.
      { name: "Rent", amountCents: 160000, categoryId: 2, payFromAccountId: 1, effectiveFrom: todayIso(), schedule: { startDate: "2026-01-01", day1: 1 } },
    ]);
  });

  it("warns that a change of type reaches back, and can archive the item and add a new one instead", async () => {
    const fetchMock = withItems(undefined, {
      "POST /api/items/2/replacement": () => ({ status: 201, data: { ...ELECTRIC, id: 9, kind: "budget" }, changed: true }),
    });
    renderApp("/recurring/2");

    await userEvent.click(await screen.findByRole("button", { name: "Edit item" }));
    const dialog = screen.getByRole("dialog", { name: "Edit Electric" });
    // Nothing is said until the type is actually different.
    expect(within(dialog).queryByRole("status")).not.toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("radio", { name: "Budget" }));
    const warning = within(dialog).getByRole("status");
    expect(warning).toHaveTextContent("Changing the type changes Electric all the way back to when it started.");
    expect(warning).toHaveTextContent("Its earlier dates will count as a budget too");

    await userEvent.click(within(warning).getByRole("button", { name: "Archive this and add a new item" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const sent = bodiesSentTo(fetchMock, "POST /api/items/2/replacement")[0] as Record<string, unknown>;
    expect(sent).toMatchObject({ name: "Electric", kind: "budget", archived: false });
    expect(sent).not.toHaveProperty("effectiveFrom");
    // The item itself was not changed.
    expect(bodiesSentTo(fetchMock, "PUT /api/items/2")).toEqual([]);
  });

  it("opens an item's page from a click anywhere on its row", async () => {
    withItems([RENT]);
    renderApp("/recurring");
    const row = await rowFor("Rent");

    // On plain text in the row, not on the name.
    await userEvent.click(within(row).getByText("Monthly · 1st"));

    expect(await screen.findByRole("heading", { level: 1, name: "Rent" })).toBeInTheDocument();
    // Nothing opened for editing: that is the page's own button.
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit item" })).toBeInTheDocument();
  });

  it("lays an item's page out like an account's: the figures first, then a card per topic", async () => {
    withItems([{ ...RENT, tagIds: [1], note: "Goes up every January" }, TO_SAVINGS]);
    renderApp("/recurring/1");

    expect(await screen.findByRole("heading", { level: 1, name: "Rent" })).toBeInTheDocument();
    expect(screen.getByText("/ Bill or expense", { exact: false })).toHaveTextContent("Recurring items / Bill or expense");
    expect(screen.getByText("Bill or expense · manual · Monthly · 1st")).toBeInTheDocument();

    expect(screen.getByText("Amount").parentElement).toHaveTextContent("$1,550.00each time");
    expect(screen.getByText("A year").parentElement).toHaveTextContent("$18,600.00");
    // Due on payday and paid by hand, so the paycheck before pays for it.
    expect(screen.getByText("Next date").parentElement).toHaveTextContent("Nov 1, 2026paid from the Oct 15, 2026 paycheck, one period early");
    expect(screen.getAllByText("Paid from")[0]!.parentElement).toHaveTextContent("Everyday Checkingpaid by hand");

    const schedule = screen.getByRole("region", { name: "Schedule" });
    expect(within(schedule).getByText("Starts").nextElementSibling).toHaveTextContent("Jan 1, 2026");
    const paidFrom = screen.getByRole("region", { name: "Where it's paid from" });
    expect(within(paidFrom).getByText("Paid from").nextElementSibling).toHaveTextContent("Everyday Checking");
    // Shown where it's edited: beside Autopay.
    expect(within(paidFrom).getByText("Which paycheck pays it").nextElementSibling).toHaveTextContent("The period it's due in");
    const details = screen.getByRole("region", { name: "Details" });
    expect(within(details).getByText("Category").nextElementSibling).toHaveTextContent("Housing › Rent");
    expect(within(details).getByText("Tags").nextElementSibling).toHaveTextContent("Subscriptions");
    expect(details).toHaveTextContent("Goes up every January");
    expect(screen.getByRole("region", { name: "Amount history" })).toHaveTextContent("From Jan 1, 2026");

    // Each card is changed from the card, as on an account's page.
    expect(within(schedule).getByRole("button", { name: "Edit the schedule" })).toBeInTheDocument();
    expect(within(paidFrom).getByRole("button", { name: "Edit where it is paid from" })).toBeInTheDocument();
    expect(within(details).getByRole("button", { name: "Edit the details" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Change the amount of Rent" })).toBeInTheDocument();
  });

  it("opens only the part of the item its Edit button is on", async () => {
    const fetchMock = withItems([RENT], { "PUT /api/items/1": () => ({ status: 200, data: RENT }) });
    renderApp("/recurring/1");

    // Edit item is the small one: what it is, archiving, deleting.
    await userEvent.click(await screen.findByRole("button", { name: "Edit item" }));
    const whole = screen.getByRole("dialog", { name: "Edit Rent" });
    expect(within(whole).getByRole("radio", { name: "Bill or expense" })).toBeChecked();
    expect(within(whole).getByRole("textbox", { name: "Name" })).toHaveValue("Rent");
    expect(within(whole).getByRole("checkbox", { name: "Archived" })).not.toBeChecked();
    expect(within(whole).getByRole("button", { name: "Delete item…" })).toBeInTheDocument();
    expect(within(whole).queryByRole("textbox", { name: "Amount each time" })).not.toBeInTheDocument();
    expect(within(whole).queryByRole("combobox", { name: "How often" })).not.toBeInTheDocument();
    // A transfer needs a second account, so choosing one brings the accounts with it.
    await userEvent.click(within(whole).getByRole("radio", { name: "Transfer" }));
    expect(within(whole).getByRole("combobox", { name: "To" })).toBeInTheDocument();
    await userEvent.click(within(whole).getByRole("button", { name: "Cancel" }));

    await userEvent.click(screen.getByRole("button", { name: "Edit the details" }));
    const details = screen.getByRole("dialog", { name: "Edit Rent · Details" });
    expect(within(details).getByRole("combobox", { name: "Category (optional)" })).toHaveValue("Housing › Rent");
    expect(within(details).queryByRole("textbox", { name: "Name" })).not.toBeInTheDocument();
    expect(within(details).queryByRole("checkbox", { name: "Archived" })).not.toBeInTheDocument();
    expect(within(details).queryByRole("button", { name: "Delete item…" })).not.toBeInTheDocument();
    await userEvent.type(within(details).getByRole("textbox", { name: "Note (optional)" }), "Goes up in January");
    await userEvent.click(within(details).getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    // The parts that were not shown are sent back as they were.
    expect(bodiesSentTo(fetchMock, "PUT /api/items/1")).toMatchObject([
      { name: "Rent", kind: "bill", note: "Goes up in January", amountCents: 155000, payFromAccountId: 1, schedule: { frequency: "monthly", day1: 1 } },
    ]);
  });

  it("opens a bucket move's account from an old link to it, never a page of its own", async () => {
    const fund: Item = { ...GROCERIES, id: 8, name: "Gift fund", kind: "bucket_move", bucketId: 7, categoryId: null, payFromAccountId: 1 };
    const fetchMock = withItems([fund], { "GET /api/buckets": () => ({ status: 200, data: [GIFTS] }) });
    renderApp("/recurring/8");

    await waitFor(() => expect(calls(fetchMock)).toContain("GET /api/accounts/1"));
    expect(screen.queryByRole("heading", { level: 1, name: "Gift fund" })).not.toBeInTheDocument();
  });

  it("says where a transfer goes on its page", async () => {
    withItems([TO_SAVINGS]);
    renderApp("/recurring/4");

    expect(await screen.findByRole("heading", { level: 1, name: "To savings" })).toBeInTheDocument();
    expect(screen.getAllByText("From")[0]!.parentElement).toHaveTextContent("Everyday Checkingto Rainy Day Savings");
    const accounts = screen.getByRole("region", { name: "From and to" });
    expect(within(accounts).getByText("To").nextElementSibling).toHaveTextContent("Rainy Day Savings");
  });

  it("says so kindly when the item is not there, with a way back", async () => {
    withItems([RENT]);
    renderApp("/recurring/99");

    expect(await screen.findByRole("alert")).toHaveTextContent("We couldn't find that item. It may have been removed.");
    expect(screen.getByRole("link", { name: "Back to all recurring items" })).toHaveAttribute("href", "/recurring");
  });

  it("offers no pause on an archived item, and says how to bring it back", async () => {
    withItems([{ ...RENT, archived: true, next: null }]);
    renderApp("/recurring/1");

    const card = await screen.findByRole("region", { name: "Pause" });
    expect(card).toHaveTextContent("This item is archived");
    expect(within(card).queryByRole("button")).not.toBeInTheDocument();
    expect(screen.getByText(/Bill or expense · manual · Monthly · 1st · Archived/)).toBeInTheDocument();
  });

  it("shows when an item starts, and saves an earlier start so it can be backdated", async () => {
    const fetchMock = withItems([GROCERIES], { "PUT /api/items/3": () => ({ status: 200, data: GROCERIES }) });
    renderApp("/recurring/3");

    await userEvent.click(await screen.findByRole("button", { name: "Edit the schedule" }));
    const dialog = screen.getByRole("dialog", { name: "Edit Groceries · Schedule" });
    // What was saved, not today: this is the item's own date and stays put between visits.
    const starts = within(dialog).getByLabelText("Starts");
    expect(starts).toHaveValue("2026-10-01");
    await userEvent.clear(starts);
    await userEvent.type(starts, "2026-09-01");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "PUT /api/items/3")).toMatchObject([{ schedule: { startDate: "2026-09-01" } }]);
  });

  // Rachel, 2026-10-20: "define number of weeks in addition to number of months… make all options possible with every x months".
  it("repeats every few weeks, or any number of months apart", async () => {
    const fetchMock = withItems([GROCERIES], { "PUT /api/items/3": () => ({ status: 200, data: GROCERIES }) });
    renderApp("/recurring/3");

    await userEvent.click(await screen.findByRole("button", { name: "Edit the schedule" }));
    let dialog = screen.getByRole("dialog", { name: "Edit Groceries · Schedule" });
    await userEvent.selectOptions(within(dialog).getByRole("combobox", { name: "How often" }), "Every few weeks");
    const weeks = within(dialog).getByRole("spinbutton", { name: "Weeks apart" });
    await userEvent.clear(weeks);
    await userEvent.type(weeks, "10");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "PUT /api/items/3")).toMatchObject([{ schedule: { frequency: "weekly", interval: 10 } }]);

    await userEvent.click(await screen.findByRole("button", { name: "Edit the schedule" }));
    dialog = screen.getByRole("dialog", { name: "Edit Groceries · Schedule" });
    await userEvent.selectOptions(within(dialog).getByRole("combobox", { name: "How often" }), "Every few months");
    const months = within(dialog).getByRole("spinbutton", { name: "Months apart" });
    // Any number the backend takes, not a short list.
    expect(months).toHaveAttribute("max", "60");
    await userEvent.clear(months);
    await userEvent.type(months, "7");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "PUT /api/items/3")[1]).toMatchObject({ schedule: { frequency: "every_n_months", interval: 7 } });
  });

  it("shows the backend's message when a change is refused", async () => {
    withItems([RENT], { "PUT /api/items/1": () => ({ status: 400, error: "Please choose the category from the list." }) });
    renderApp("/recurring/1");

    await userEvent.click(await screen.findByRole("button", { name: "Edit item" }));
    const dialog = screen.getByRole("dialog", { name: "Edit Rent" });
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("Please choose the category from the list.");
  });

  it("asks before deleting an item, with keeping it as the default", async () => {
    const fetchMock = withItems([RENT], { "DELETE /api/items/1": () => ({ status: 200, data: { deleted: true } }) });
    renderApp("/recurring/1");

    await userEvent.click(await screen.findByRole("button", { name: "Edit item" }));
    const dialog = screen.getByRole("dialog", { name: "Edit Rent" });
    await userEvent.click(within(dialog).getByRole("button", { name: "Delete item…" }));
    expect(within(dialog).getByRole("button", { name: "Keep it" })).toHaveFocus();
    expect(calls(fetchMock)).not.toContain("DELETE /api/items/1");

    await userEvent.click(within(dialog).getByRole("button", { name: "Yes, delete it" }));

    // Its page is gone with it, so the overview is where to land.
    expect(await screen.findByRole("heading", { level: 1, name: "Recurring items" })).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(calls(fetchMock)).toContain("DELETE /api/items/1");
  });
});

/** Electric's 2026: $140.00 a month, July typed over at $212.00, beside $133.00 a month the year before. */
const ELECTRIC_BY_MONTH: CostsByMonth = {
  year: 2026,
  months: Array.from({ length: 12 }, (_, index) => ({
    month: `2026-${String(index + 1).padStart(2, "0")}`,
    amountCents: index === 6 ? 21200 : 14000,
    beforeCents: 13300,
    toCome: index > 9,
  })),
  highest: { month: "2026-07", amountCents: 21200 },
  lowest: { month: "2026-01", amountCents: 14000 },
  totalCents: 175200,
  averageCents: 14600,
  budgetCents: 14000,
  budgetTooLow: true,
};

describe("A recurring item's year by month", () => {
  it("shows the highest and lowest month, the average, and says when the amount is below it", async () => {
    withItems([ELECTRIC], { "GET /api/items/2/by-month": () => ({ status: 200, data: ELECTRIC_BY_MONTH }) });
    renderApp("/recurring/2");

    const card = await screen.findByRole("region", { name: "By month" });
    expect((await within(card).findByText("Highest 2026")).parentElement).toHaveTextContent("Jul · $212.00");
    expect(within(card).getByText("Lowest 2026").parentElement).toHaveTextContent("Jan · $140.00");
    expect(within(card).getByText("Average").parentElement).toHaveTextContent("$146.00 / month");
    expect(within(card).getByText("Your amount now").parentElement).toHaveTextContent("$140.00 / month");
    expect(within(card).getByRole("status")).toHaveTextContent(
      "Your $140.00 a month is below this year's average of $146.00. Use Change the amount if it should be higher.",
    );
    // The lines are told apart in words, not only by how they are drawn.
    expect(card).toHaveTextContent("Solid: 2026 so far. Dotted: the months still to come, as budgeted. Grey dashes: 2025.");
  });

  it("says nothing about the amount when the year comes to it, and calls a budget a budget", async () => {
    withItems([GROCERIES], {
      "GET /api/items/3/by-month": () => ({
        status: 200,
        data: { ...ELECTRIC_BY_MONTH, averageCents: 14000, totalCents: 168000, budgetTooLow: false },
      }),
    });
    renderApp("/recurring/3");

    const card = await screen.findByRole("region", { name: "By month" });
    expect(await within(card).findByText("Your budget now")).toBeInTheDocument();
    expect(within(card).queryByRole("status")).not.toBeInTheDocument();
  });

  it("has words for a year with nothing in it yet, and is left off anything that is not a cost", async () => {
    const fetchMock = withItems([RENT, TO_SAVINGS]);
    renderApp("/recurring/1");

    expect(await screen.findByText(/Nothing falls in 2026 yet/)).toBeInTheDocument();

    cleanup();
    renderApp("/recurring/4");
    await screen.findByRole("heading", { level: 1, name: "To savings" });
    expect(screen.queryByRole("region", { name: "By month" })).not.toBeInTheDocument();
    expect(calls(fetchMock)).not.toContain("GET /api/items/4/by-month");
  });

  it("shows the backend's message when the months cannot be added up", async () => {
    withItems([ELECTRIC], { "GET /api/items/2/by-month": () => ({ status: 500, error: "Something went wrong on our side. Please try again." }) });
    renderApp("/recurring/2");

    const card = await screen.findByRole("region", { name: "By month" });
    expect(await within(card).findByRole("alert")).toHaveTextContent("Something went wrong on our side. Please try again.");
  });
});

describe("Where a recurring item's breadcrumb leads", () => {
  const COSTS = {
    basis: "calendar",
    windows: [
      { from: "2024-01-01", to: "2024-12-31", label: "2024" },
      { from: "2025-01-01", to: "2025-12-31", label: "2025" },
      { from: "2026-01-01", to: "2026-12-31", label: "2026" },
    ],
    rows: [
      { itemId: 2, name: "Electric", kind: "bill", categoryId: null, categoryName: null, color: null, totalsCents: [0, 159600, 174200], changeBp: 915, perMonthCents: 14517 },
    ],
    totalsCents: [0, 159600, 174200],
    changeBp: 915,
    perMonthCents: 14517,
    biggestIncrease: null,
    budgetedFrom: "2026-11-01",
  };

  it("goes back to Annual costs when the item was opened from there", async () => {
    withItems([ELECTRIC], { "GET /api/annual-costs": () => ({ status: 200, data: COSTS }) });
    renderApp("/dashboard/annual");

    const table = await screen.findByRole("region", { name: "Bills and budgets" });
    await userEvent.click(within(table).getByRole("link", { name: /Electric/ }));

    await screen.findByRole("heading", { level: 1, name: "Electric" });
    const back = screen.getByRole("link", { name: "Annual costs" });
    expect(back).toHaveAttribute("href", "/dashboard/annual");
    // In its place, not beside it: the header has one way back.
    expect(within(back.parentElement!).queryByRole("link", { name: "Recurring items" })).not.toBeInTheDocument();

    await userEvent.click(back);
    expect(await screen.findByRole("heading", { level: 1, name: "Annual costs" })).toBeInTheDocument();
  });

  it("goes to Recurring items when the item was opened any other way", async () => {
    withItems([ELECTRIC]);
    renderApp("/recurring/2");

    await screen.findByRole("heading", { level: 1, name: "Electric" });
    const header = screen.getByRole("heading", { level: 1, name: "Electric" }).parentElement!.parentElement!;
    expect(within(header).getByRole("link", { name: "Recurring items" })).toHaveAttribute("href", "/recurring");
    expect(within(header).queryByRole("link", { name: "Annual costs" })).not.toBeInTheDocument();
  });
});

describe("recurring item page: billed later", () => {
  const THERAPY: Item = { ...ELECTRIC, id: 9, name: "Therapy", estimate: false, billedLater: true };

  it("lists the visits waiting for an invoice and the invoices so far, and removes the newest", async () => {
    const fetchMock = withItems([THERAPY], {
      "GET /api/items/9/invoices": () => ({
        status: 200,
        data: {
          notBilled: [
            { scheduledDate: "2026-10-06", date: "2026-10-06", amountCents: 12000, skipped: false },
            { scheduledDate: "2026-10-13", date: "2026-10-13", amountCents: 12000, skipped: false },
          ],
          notBilledCents: 24000,
          skipped: [],
          upcoming: [],
          invoices: [
            {
              invoice: {
                id: 3, itemId: 9, invoiceDate: "2026-10-01", dueDate: "2026-10-20", coversThrough: "2026-09-30", amountCents: 61000,
                projectedCents: 60000, paid: false, paidDate: null, confirmationCode: null, checkNumber: null, note: null,
              },
              periodStart: "2026-10-15",
            },
          ],
        },
      }),
      "DELETE /api/items/9/invoices/3": () => ({ status: 200, data: { deleted: true }, changed: true }),
    });
    renderApp("/recurring/9");

    const card = await screen.findByRole("region", { name: "Billed later" });
    expect(await within(card).findByText(/2 visits up to today, coming to/)).toHaveTextContent("$240.00");
    expect(within(card).getByRole("button", { name: "Mark as invoiced" })).toBeInTheDocument();
    expect(within(card).getByRole("link", { name: /Visits through Sep 30, 2026, Due Oct 20: open its pay period/ })).toHaveAttribute(
      "href",
      "/periods/2026-10-15",
    );

    await userEvent.click(within(card).getByRole("button", { name: "Remove this invoice" }));
    await waitFor(() => expect(calls(fetchMock)).toContain("DELETE /api/items/9/invoices/3"));
  });

  it("skips a visit, puts a skipped one back, and skips one still to come", async () => {
    const visit = (date: string, skipped: boolean) => ({ scheduledDate: date, date, amountCents: 12000, skipped });
    const fetchMock = withItems([THERAPY], {
      "GET /api/items/9/invoices": () => ({
        status: 200,
        data: {
          notBilled: [visit("2026-10-06", false)],
          notBilledCents: 12000,
          skipped: [visit("2026-09-29", true)],
          upcoming: [visit("2026-10-20", false), visit("2026-10-27", false)],
          invoices: [],
        },
      }),
      "PUT /api/items/9/occurrences/2026-10-06": () => ({ status: 200, data: {}, changed: true }),
      "PUT /api/items/9/occurrences/2026-09-29": () => ({ status: 200, data: {}, changed: true }),
      "PUT /api/items/9/occurrences/2026-10-20": () => ({ status: 200, data: {}, changed: true }),
    });
    renderApp("/recurring/9");

    const card = await screen.findByRole("region", { name: "Billed later" });
    // The skipped one is listed, in words, in date order with the rest.
    expect(await within(card).findByText("Skipped")).toBeInTheDocument();
    expect(within(card).getByRole("heading", { name: "Coming up" })).toBeInTheDocument();

    await userEvent.click(within(card).getByRole("button", { name: "Skip the visit on Oct 6, 2026" }));
    await userEvent.click(within(card).getByRole("button", { name: "Put back the visit on Sep 29, 2026" }));
    await userEvent.click(within(card).getByRole("button", { name: "Skip the visit on Oct 20, 2026" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/items/9/occurrences/2026-10-20")).toEqual([{ skipped: true }]));
    expect(bodiesSentTo(fetchMock, "PUT /api/items/9/occurrences/2026-10-06")).toEqual([{ skipped: true }]);
    expect(bodiesSentTo(fetchMock, "PUT /api/items/9/occurrences/2026-09-29")).toEqual([{ skipped: false }]);
  });

  it("asks whether a bill is billed later, and sends it", async () => {
    const fetchMock = withItems([THERAPY], { "PUT /api/items/9": () => ({ status: 200, data: THERAPY }) });
    renderApp("/recurring/9");

    await userEvent.click(await screen.findByRole("button", { name: "Change the amount of Therapy" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByRole("radio", { name: "Billed later" })).toBeChecked();
    expect(within(dialog).getByRole("textbox", { name: "Each visit" })).toBeInTheDocument();
    // Added up visit by visit, so there is no yearly total to choose.
    expect(within(dialog).queryByRole("radio", { name: /Per year/ })).not.toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/items/9")).toEqual([expect.objectContaining({ billedLater: true })]));
  });
});

describe("recurring item page: paid ahead", () => {
  const COURSE: Item = { ...ELECTRIC, id: 11, name: "Pottery course", estimate: false, paidAhead: true };
  const session = (date: string, covered: boolean, skipped = false) => ({ scheduledDate: date, date, amountCents: 2500, skipped, covered });
  const STANDING = {
    paidCents: 10000,
    usedCents: 5000,
    leftCents: 5000,
    coversThrough: "2026-10-26",
    firstUncovered: "2026-11-02",
    used: [session("2026-10-12", true), session("2026-10-05", true, true), session("2026-09-28", true)],
    upcoming: [session("2026-10-19", true), session("2026-10-26", true), session("2026-11-02", false)],
    payments: [{ entryId: 41, date: "2026-09-25", amountCents: 10000, accountId: 1 }],
  };

  it("shows what is left, how far it lasts, the sessions and the payments", async () => {
    withItems([COURSE], { "GET /api/items/11/paid-ahead": () => ({ status: 200, data: STANDING }) });
    renderApp("/recurring/11");

    const card = await screen.findByRole("region", { name: "Paid ahead" });
    expect(await within(card).findByText("$50.00")).toBeInTheDocument();
    expect(card).toHaveTextContent("Covers through Oct 26, 2026. The session on Nov 2, 2026 needs another payment.");
    expect(within(card).getByText("Skipped")).toBeInTheDocument();
    expect(within(card).getByText("Not covered")).toBeInTheDocument();
    // A payment is the one-off it was saved as, opened to change or remove it.
    expect(within(card).getByRole("link", { name: "Payment on Sep 25, 2026: open it" })).toHaveAttribute("href", "/expenses/41");
  });

  it("shows every session since the start on asking, by month, each with its own Skip", async () => {
    // Weekly from Jan 5 to Oct 12: 41 sessions, newest first.
    const year = Array.from({ length: 41 }, (_, week) => {
      const day = new Date(Date.UTC(2026, 0, 5 + 7 * (40 - week)));
      return session(day.toISOString().slice(0, 10), true);
    });
    withItems([COURSE], { "GET /api/items/11/paid-ahead": () => ({ status: 200, data: { ...STANDING, used: year } }) });
    renderApp("/recurring/11");

    const card = await screen.findByRole("region", { name: "Paid ahead" });
    expect(await within(card).findByText("and 35 earlier, from Jan 5.")).toBeInTheDocument();
    expect(within(card).queryByRole("button", { name: "Skip the session on Jan 5, 2026" })).not.toBeInTheDocument();

    await userEvent.click(within(card).getByRole("button", { name: "Show all 41 sessions" }));

    expect(within(card).getByRole("heading", { name: "Jan 2026" })).toBeInTheDocument();
    expect(within(card).getByRole("button", { name: "Skip the session on Jan 5, 2026" })).toBeInTheDocument();
    await userEvent.click(within(card).getByRole("button", { name: "Show the latest only" }));
    expect(within(card).queryByRole("button", { name: "Skip the session on Jan 5, 2026" })).not.toBeInTheDocument();
  });

  it("records a payment from the account the item is paid from", async () => {
    const fetchMock = withItems([COURSE], {
      "GET /api/items/11/paid-ahead": () => ({ status: 200, data: STANDING }),
      "POST /api/items/11/paid-ahead/payments": () => ({ status: 201, data: STANDING, changed: true }),
    });
    renderApp("/recurring/11");

    const card = await screen.findByRole("region", { name: "Paid ahead" });
    await userEvent.click(await within(card).findByRole("button", { name: "Record a payment" }));
    const dialog = await screen.findByRole("dialog", { name: "Record a payment for Pottery course" });
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Amount paid" }), "75");
    fireEvent.change(within(dialog).getByLabelText("Paid on"), { target: { value: "2026-10-18" } });
    await userEvent.click(within(dialog).getByRole("button", { name: "Save payment" }));

    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "POST /api/items/11/paid-ahead/payments")).toEqual([
        { date: "2026-10-18", amountCents: 7500, accountId: COURSE.payFromAccountId },
      ]),
    );
  });

  it("refuses a payment of nothing, kindly", async () => {
    const fetchMock = withItems([COURSE], { "GET /api/items/11/paid-ahead": () => ({ status: 200, data: STANDING }) });
    renderApp("/recurring/11");

    await userEvent.click(await screen.findByRole("button", { name: "Record a payment" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Amount paid" }), "0");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save payment" }));

    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please enter what was paid, more than $0.00.");
    expect(calls(fetchMock).some((call) => call.startsWith("POST"))).toBe(false);
  });

  it("skips a session through its occurrence", async () => {
    const fetchMock = withItems([COURSE], {
      "GET /api/items/11/paid-ahead": () => ({ status: 200, data: STANDING }),
      "PUT /api/items/11/occurrences/2026-10-19": () => ({ status: 200, data: {}, changed: true }),
    });
    renderApp("/recurring/11");

    const card = await screen.findByRole("region", { name: "Paid ahead" });
    await userEvent.click(await within(card).findByRole("button", { name: "Skip the session on Oct 19, 2026" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/items/11/occurrences/2026-10-19")).toEqual([{ skipped: true }]));
  });

  it("says why it couldn't load", async () => {
    withItems([COURSE], { "GET /api/items/11/paid-ahead": () => ({ status: 500, error: "Something went wrong" }) });
    renderApp("/recurring/11");

    const card = await screen.findByRole("region", { name: "Paid ahead" });
    expect(await within(card).findByRole("alert")).toHaveTextContent("We couldn't load what was paid ahead just now.");
  });

  it("is paid ahead or billed later, never both, and keeps it on a quick change", async () => {
    const fetchMock = withItems([COURSE], {
      "GET /api/items/11/paid-ahead": () => ({ status: 200, data: STANDING }),
      "PUT /api/items/11": () => ({ status: 200, data: COURSE }),
    });
    renderApp("/recurring/11");

    await userEvent.click(await screen.findByRole("button", { name: "Change the amount of Pottery course" }));
    const dialog = await screen.findByRole("dialog");
    const paidAhead = within(dialog).getByRole("radio", { name: "Paid ahead" });
    expect(paidAhead).toBeChecked();
    expect(within(dialog).getByRole("textbox", { name: "Each session" })).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("radio", { name: "Billed later" }));
    expect(paidAhead).not.toBeChecked();
    await userEvent.click(paidAhead);
    expect(within(dialog).getByRole("radio", { name: "Billed later" })).not.toBeChecked();
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "PUT /api/items/11")).toEqual([
        expect.objectContaining({ paidAhead: true, billedLater: false, amountMode: "per_occurrence" }),
      ]),
    );
  });
});

describe("recurring item page: paid ahead as a pack, twice a week", () => {
  // Rachel, 2026-10-19: "I pay 1275 for a 20 session pack. I go twice per week."
  const CLASSES: Item = {
    ...ELECTRIC,
    id: 12,
    name: "Climbing classes",
    estimate: false,
    paidAhead: true,
    packSessions: 20,
    amountCents: 127500,
    schedule: { ...ELECTRIC.schedule, frequency: "weekly", day1: null, startDate: "2026-09-07", weekdays: [1, 4] },
  };
  const STANDING = {
    paidCents: 127500,
    usedCents: 51000,
    leftCents: 76500,
    coversThrough: "2026-11-12",
    firstUncovered: "2026-11-16",
    used: [],
    upcoming: [],
    payments: [],
    packSessions: 20,
    packPriceCents: 127500,
    nextPack: "2026-11-16",
    nextPackPlanned: "2026-11-16",
    shortFrom: null,
  };

  it("says the pack and when the next one is due, and fills in its price for the next payment", async () => {
    withItems([CLASSES], { "GET /api/items/12/paid-ahead": () => ({ status: 200, data: STANDING }) });
    renderApp("/recurring/12");

    const card = await screen.findByRole("region", { name: "Paid ahead" });
    expect(await within(card).findByText(/Pack of 20 for/)).toHaveTextContent("Pack of 20 for $1,275.00");
    expect(within(card).getByText("Packs paid").closest("div")).toHaveTextContent("when the sessions run out");
    expect(within(card).getByText("Next pack").closest("div")).toHaveTextContent("Nov 16, 2026");
    await userEvent.click(within(card).getByRole("button", { name: "Record a payment" }));
    const dialog = await screen.findByRole("dialog", { name: "Record a payment for Climbing classes" });
    expect(within(dialog).getByRole("textbox", { name: "Amount paid" })).toHaveValue("1275.00");
  });

  // Rachel, 2026-10-20: "I want both options."
  it("pays each pack on a schedule of its own", async () => {
    const fetchMock = withItems([CLASSES], {
      "GET /api/items/12/paid-ahead": () => ({ status: 200, data: STANDING }),
      "PUT /api/items/12": () => ({ status: 200, data: CLASSES, changed: true }),
    });
    renderApp("/recurring/12");

    const card = await screen.findByRole("region", { name: "Paid ahead" });
    await userEvent.click(await within(card).findByRole("button", { name: "Edit when packs are paid" }));
    const dialog = await screen.findByRole("dialog", { name: "When Climbing classes packs are paid" });
    await userEvent.click(within(dialog).getByRole("radio", { name: "On its own schedule" }));
    // Twenty sessions, twice a week: it starts at ten weeks.
    expect(within(dialog).getByRole("spinbutton", { name: "Every" })).toHaveValue(10);
    expect(within(dialog).getByRole("combobox", { name: "Weeks or months" })).toHaveValue("weeks");
    expect(within(dialog).getByLabelText("A day a pack is paid")).toHaveValue("2026-11-16");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "PUT /api/items/12")).toEqual([
        expect.objectContaining({
          packSessions: 20,
          packSchedule: expect.objectContaining({ frequency: "weekly", interval: 10, startDate: "2026-11-16" }),
          // The sessions stay as they were.
          schedule: expect.objectContaining({ weekdays: [1, 4] }),
        }),
      ]),
    );
  });

  it("says when packs on their own schedule come too late", async () => {
    const scheduled: Item = { ...CLASSES, packSchedule: { ...CLASSES.schedule, interval: 12, startDate: "2026-09-01", weekdays: [] } };
    withItems([scheduled], {
      "GET /api/items/12/paid-ahead": () => ({ status: 200, data: { ...STANDING, nextPack: "2026-11-24", nextPackPlanned: "2026-11-24", shortFrom: "2026-11-16" } }),
    });
    renderApp("/recurring/12");

    const card = await screen.findByRole("region", { name: "Paid ahead" });
    expect(await within(card).findByText("Packs paid")).toBeInTheDocument();
    expect(within(card).getByText("Packs paid").closest("div")).toHaveTextContent("every 12 weeks from Sep 1, 2026");
    expect(within(card).getByRole("status")).toHaveTextContent("The sessions run out on Nov 16, 2026, before the next pack.");
  });

  it("moves the next pack to another day, and back", async () => {
    const fetchMock = withItems([CLASSES], {
      "GET /api/items/12/paid-ahead": () => ({ status: 200, data: { ...STANDING, nextPack: "2026-11-20" } }),
      "PUT /api/items/12/paid-ahead/packs/2026-11-16": () => ({ status: 200, data: STANDING, changed: true }),
      "DELETE /api/items/12/paid-ahead/packs/2026-11-16": () => ({ status: 200, data: STANDING, changed: true }),
    });
    renderApp("/recurring/12");

    const card = await screen.findByRole("region", { name: "Paid ahead" });
    expect(await within(card).findByText("moved from Nov 16")).toBeInTheDocument();
    await userEvent.click(within(card).getByRole("button", { name: "Change date" }));
    let dialog = await screen.findByRole("dialog", { name: "Next Climbing classes pack" });
    fireEvent.change(within(dialog).getByLabelText("Due on"), { target: { value: "2026-11-23" } });
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/items/12/paid-ahead/packs/2026-11-16")).toEqual([{ date: "2026-11-23" }]));

    await userEvent.click(within(card).getByRole("button", { name: "Change date" }));
    dialog = await screen.findByRole("dialog", { name: "Next Climbing classes pack" });
    await userEvent.click(within(dialog).getByRole("button", { name: "Put it back on Nov 16, 2026" }));
    await waitFor(() => expect(calls(fetchMock)).toContain("DELETE /api/items/12/paid-ahead/packs/2026-11-16"));
  });

  it("works out a session's share of the pack and how long a pack lasts", async () => {
    withItems([CLASSES], { "GET /api/items/12/paid-ahead": () => ({ status: 200, data: STANDING }) });
    renderApp("/recurring/12");

    await userEvent.click(await screen.findByRole("button", { name: "Change the amount of Climbing classes" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText("$63.75 a session · a pack of 20 lasts about 10 weeks at its schedule.")).toBeInTheDocument();
  });

  it("opens with both days ticked and sends the days and pack size back", async () => {
    const fetchMock = withItems([CLASSES], {
      "GET /api/items/12/paid-ahead": () => ({ status: 200, data: STANDING }),
      "PUT /api/items/12": () => ({ status: 200, data: CLASSES }),
    });
    renderApp("/recurring/12");

    await userEvent.click(await screen.findByRole("button", { name: "Edit the schedule" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByRole("checkbox", { name: "Monday" })).toBeChecked();
    expect(within(dialog).getByRole("checkbox", { name: "Thursday" })).toBeChecked();
    await userEvent.click(within(dialog).getByRole("checkbox", { name: "Saturday" }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "PUT /api/items/12")).toEqual([
        expect.objectContaining({ packSessions: 20, schedule: expect.objectContaining({ weekdays: [1, 4, 6] }) }),
      ]),
    );
  });

  it("starts a weekly item on its start date's day, and keeps at least one day", async () => {
    const weekly: Item = { ...CLASSES, paidAhead: false, packSessions: null, schedule: { ...CLASSES.schedule, weekdays: [] } };
    const fetchMock = withItems([weekly], { "PUT /api/items/12": () => ({ status: 200, data: weekly }) });
    renderApp("/recurring/12");

    await userEvent.click(await screen.findByRole("button", { name: "Edit the schedule" }));
    const dialog = await screen.findByRole("dialog");
    // Sep 7, 2026 is a Monday.
    const monday = within(dialog).getByRole("checkbox", { name: "Monday" });
    expect(monday).toBeChecked();
    await userEvent.click(monday);
    expect(monday).toBeChecked();
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "PUT /api/items/12")).toEqual([
        expect.objectContaining({ schedule: expect.objectContaining({ weekdays: [1] }) }),
      ]),
    );
  });
});

describe("recurring item page: where it's paid from, from a chosen day", () => {
  // Rachel, 2026-10-19: "update a recurring bill from manual to autopay and back date that change".
  it("turns autopay on from a past day", async () => {
    const fetchMock = withItems([ELECTRIC], { "PUT /api/items/2": () => ({ status: 200, data: ELECTRIC }) });
    renderApp("/recurring/2");

    await userEvent.click(await screen.findByRole("button", { name: "Edit where it is paid from" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByLabelText("Changes from")).toHaveValue(todayIso());
    // The date says when, so the "from today" note is gone.
    expect(dialog).not.toHaveTextContent("Saved from today; earlier dates stay as they were.");
    await userEvent.click(within(dialog).getByRole("checkbox", { name: "Autopay" }));
    fireEvent.change(within(dialog).getByLabelText("Changes from"), { target: { value: "2026-08-01" } });
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "PUT /api/items/2")).toEqual([expect.objectContaining({ autopay: true, effectiveFrom: "2026-08-01" })]),
    );
  });

  it("asks for the day when it is cleared", async () => {
    const fetchMock = withItems([ELECTRIC]);
    renderApp("/recurring/2");

    await userEvent.click(await screen.findByRole("button", { name: "Edit where it is paid from" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText("Changes from"), { target: { value: "" } });
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please choose the day this change starts.");
    expect(calls(fetchMock).some((call) => call.startsWith("PUT"))).toBe(false);
  });
});

describe("recurring item page: the day a change starts", () => {
  it("changes the amount from a day she chooses, past or future", async () => {
    const fetchMock = withItems([ELECTRIC], { "PUT /api/items/2": () => ({ status: 200, data: ELECTRIC }) });
    renderApp("/recurring/2");

    await userEvent.click(await screen.findByRole("button", { name: "Change the amount of Electric" }));
    const dialog = await screen.findByRole("dialog");
    const changesFrom = within(dialog).getByLabelText("Changes from");
    expect(changesFrom).toHaveValue(todayIso());
    fireEvent.change(changesFrom, { target: { value: "2025-06-01" } });
    // A change set for later is replaced, and says so rather than surprising her.
    expect(dialog).toHaveTextContent("The change set for Jan 1, 2026 is replaced by this one.");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/items/2")).toEqual([expect.objectContaining({ effectiveFrom: "2025-06-01" })]));
  });

  it("needs a day", async () => {
    const fetchMock = withItems([ELECTRIC]);
    renderApp("/recurring/2");

    await userEvent.click(await screen.findByRole("button", { name: "Change the amount of Electric" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText("Changes from"), { target: { value: "" } });
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please choose the day this change starts.");
    expect(calls(fetchMock).some((call) => call.startsWith("PUT"))).toBe(false);
  });
});

describe("describing a schedule", () => {
  const weekly = { ...ELECTRIC.schedule, frequency: "weekly" as const, day1: null, startDate: "2026-09-07" };

  it("names every day of the week it falls on, and how many weeks apart", () => {
    expect(describeItemSchedule({ ...weekly, weekdays: [] })).toBe("Every week · Mondays");
    expect(describeItemSchedule({ ...weekly, weekdays: [1, 4] })).toBe("Every week · Mondays & Thursdays");
    expect(describeItemSchedule({ ...weekly, weekdays: [1, 3, 5] })).toBe("Every week · Mon, Wed & Fri");
    expect(describeItemSchedule({ ...weekly, interval: 10, weekdays: [] })).toBe("Every 10 weeks · Mondays");
  });
});

describe("seasonal schedules", () => {
  it("says the months as short as it can, carrying a run across the new year", () => {
    expect(describeMonths([11, 12, 1, 2, 3])).toBe("Nov–Mar");
    expect(describeMonths([6, 7, 8, 12])).toBe("Jun–Aug, Dec");
    expect(describeMonths([4])).toBe("Apr");
    expect(describeMonths([])).toBe("");
    expect(describeMonths([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12])).toBe("");
  });

  it("runs an item in only the months chosen, and says so on its page", async () => {
    const fetchMock = withItems([ELECTRIC], { "PUT /api/items/2": () => ({ status: 200, data: ELECTRIC }) });
    renderApp("/recurring/2");

    await userEvent.click(await screen.findByRole("button", { name: "Edit the schedule" }));
    const dialog = await screen.findByRole("dialog");
    // Every month is ticked to start with: summer is unticked.
    expect(within(dialog).getByRole("checkbox", { name: "July" })).toBeChecked();
    for (const month of ["April", "May", "June", "July", "August", "September", "October"]) {
      await userEvent.click(within(dialog).getByRole("checkbox", { name: month }));
    }
    expect(within(dialog).getByRole("checkbox", { name: "July" })).not.toBeChecked();
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "PUT /api/items/2")).toEqual([
        expect.objectContaining({ schedule: expect.objectContaining({ months: [1, 2, 3, 11, 12] }) }),
      ]),
    );
  });

  it("keeps at least one month, and sends every month as none chosen", async () => {
    const fetchMock = withItems([{ ...ELECTRIC, schedule: { ...ELECTRIC.schedule, months: [7] } }], {
      "PUT /api/items/2": () => ({ status: 200, data: ELECTRIC }),
    });
    renderApp("/recurring/2");

    await userEvent.click(await screen.findByRole("button", { name: "Edit the schedule" }));
    const dialog = await screen.findByRole("dialog");
    const july = within(dialog).getByRole("checkbox", { name: "July" });
    await userEvent.click(july);
    expect(july).toBeChecked();
    for (const month of ["January", "February", "March", "April", "May", "June", "August", "September", "October", "November", "December"]) {
      await userEvent.click(within(dialog).getByRole("checkbox", { name: month }));
    }
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "PUT /api/items/2")).toEqual([expect.objectContaining({ schedule: expect.objectContaining({ months: [] }) })]),
    );
  });

  it("shows a seasonal item's months with its schedule, and opens with them chosen", async () => {
    const plowing: Item = { ...ELECTRIC, schedule: { ...ELECTRIC.schedule, months: [1, 2, 3, 11, 12] } };
    withItems([plowing]);
    renderApp("/recurring/2");

    expect((await screen.findAllByText(/Monthly · 16th · Nov–Mar/)).length).toBeGreaterThan(0);
    await userEvent.click(screen.getByRole("button", { name: "Edit the schedule" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByRole("checkbox", { name: "November" })).toBeChecked();
    expect(within(dialog).getByRole("checkbox", { name: "June" })).not.toBeChecked();
  });
});

describe("recurring item page: billed by use", () => {
  it("bills each month for about so many times at so much each, and shows it", async () => {
    // Snowplowing: $45.00 a plow, about twice a week, billed on the 10th (Rachel, 2026-10-20).
    const PLOWING: Item = {
      ...ELECTRIC,
      id: 12,
      name: "Snow plowing",
      amountCents: 4500,
      estimate: true,
      usesHundredths: 200,
      usesPer: "week",
      perOccurrenceCents: 39000,
      perYearCents: 156000,
      schedule: { ...ELECTRIC.schedule, frequency: "monthly", day1: 10, months: [1, 2, 3, 12] },
    };
    const fetchMock = withItems([PLOWING], { "PUT /api/items/12": () => ({ status: 200, data: PLOWING }) });
    renderApp("/recurring/12");

    expect(await screen.findByText(/each time · about 2 a week, about/)).toBeInTheDocument();
    expect(screen.getByText((_, element) => element?.tagName === "P" && (element.textContent ?? "").startsWith("Bill or expense · billed by use"))).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Change the amount of Snow plowing" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByRole("radio", { name: "By use" })).toBeChecked();
    expect(within(dialog).getByRole("textbox", { name: "Each time" })).toHaveValue("45.00");
    // Always an estimate, so there is no box to tick.
    expect(within(dialog).queryByRole("checkbox", { name: "It's an estimate" })).not.toBeInTheDocument();
    const times = within(dialog).getByRole("spinbutton", { name: "How many times" });
    await userEvent.clear(times);
    await userEvent.type(times, "3");
    await userEvent.click(within(dialog).getByRole("radio", { name: "Month" }));
    expect(within(dialog).getByText(/About \$135\.00 in a full month/)).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "PUT /api/items/12")).toEqual([
        expect.objectContaining({ usesHundredths: 300, usesPer: "month", estimate: true, billedLater: false, paidAhead: false }),
      ]),
    );
  });
});

describe("recurring item page: coming up", () => {
  const next = (date: string, cents: number, skipped = false) => ({
    itemId: 1,
    name: "Rent",
    kind: "bill" as const,
    periodStart: null,
    early: false,
    payBy: date,
    estimate: false,
    projectedCents: null,
    autopay: false,
    placement: "due_period" as const,
    categoryId: null,
    payFromAccountId: 1,
    payToAccountId: null,
    paid: false,
    paidDate: null,
    confirmationCode: null,
    note: null,
    bucketId: null,
    saveUpBucketId: null,
    fromBucketId: null,
    checkNumber: null,
    scheduledDate: date,
    dueDate: date,
    amountCents: cents,
    skipped,
  });

  it("lists the next dates, and changes one just that once", async () => {
    const fetchMock = withItems([RENT], {
      "GET /api/items/1/upcoming": () => ({ status: 200, data: [next("2026-11-01", 155000), next("2026-12-01", 155000, true)] }),
      "PUT /api/items/1/occurrences/2026-11-01": () => ({ status: 200, data: next("2026-11-01", 160000), changed: true }),
    });
    renderApp("/recurring/1");

    const card = await screen.findByRole("region", { name: "Coming up" });
    expect(await within(card).findByText("Skipped")).toBeInTheDocument();
    await userEvent.click(within(card).getByRole("button", { name: "Change Rent on Nov 1, 2026" }));
    const dialog = await screen.findByRole("dialog", { name: "Edit Rent" });
    const amount = within(dialog).getByRole("textbox", { name: "Amount this time" });
    await userEvent.clear(amount);
    await userEvent.type(amount, "1600");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/items/1/occurrences/2026-11-01")).toEqual([{ amountCents: 160000 }]));
  });

  it("puts a skipped one back from its form", async () => {
    const fetchMock = withItems([RENT], {
      "GET /api/items/1/upcoming": () => ({ status: 200, data: [next("2026-12-01", 155000, true)] }),
      "PUT /api/items/1/occurrences/2026-12-01": () => ({ status: 200, data: next("2026-12-01", 155000), changed: true }),
    });
    renderApp("/recurring/1");

    await userEvent.click(await screen.findByRole("button", { name: "Change Rent on Dec 1, 2026" }));
    await userEvent.click(within(await screen.findByRole("dialog", { name: "Edit Rent" })).getByRole("button", { name: "Put back" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/items/1/occurrences/2026-12-01")).toEqual([{ skipped: false }]));
  });
});
