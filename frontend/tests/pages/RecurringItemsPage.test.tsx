import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Account } from "../../src/api/accounts";
import type { Bucket } from "../../src/api/buckets";
import type { Category, Tag } from "../../src/api/categories";
import type { Item } from "../../src/api/items";
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
  history: [{ effectiveFrom: "2026-01-01", perOccurrenceCents: 155000, perYearCents: 1860000 }],
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
  const button = await screen.findByRole("button", { name: `Edit ${name}` });
  return button.closest<HTMLElement>('[role="row"]')!;
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
    expect(rent).toHaveTextContent("Bill · manual");
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
      "Edit",
    ]);
    // Being able to change an item is never left to be discovered: every row says so.
    expect(within(table).getAllByRole("button", { name: /^Edit / })).toHaveLength(4);
    expect(within(table).getAllByText("Edit", { selector: "button span" })).toHaveLength(4);
    // One header row and one row per item.
    expect(within(table).getAllByRole("row")).toHaveLength(5);
  });

  it("filters by kind and counts each kind on its chip", async () => {
    withItems();
    renderApp("/recurring");

    await userEvent.click(await screen.findByRole("button", { name: "Bills · 2" }));

    expect(screen.getByRole("button", { name: "Bills · 2" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "Edit Rent" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit Groceries" })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "All · 4" }));
    expect(screen.getByRole("button", { name: "Edit Groceries" })).toBeInTheDocument();
  });

  it("searches by name, account or category", async () => {
    withItems();
    renderApp("/recurring");
    const search = await screen.findByRole("searchbox", { name: "Search" });

    await userEvent.type(search, "rainy");
    expect(screen.getByRole("button", { name: "Edit To savings" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit Rent" })).not.toBeInTheDocument();

    await userEvent.clear(search);
    await userEvent.type(search, "zzz");
    expect(screen.getByText("Nothing matches that.")).toBeInTheDocument();
  });

  it("shows an archived item last and labelled", async () => {
    withItems([{ ...ELECTRIC, archived: true, next: null }, RENT]);
    renderApp("/recurring");

    const table = await screen.findByRole("table", { name: "Recurring items" });
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows[0]).toHaveTextContent("Rent");
    expect(rows[1]).toHaveTextContent("Electric");
    expect(rows[1]).toHaveTextContent("Archived");
    expect(rows[1]).toHaveTextContent("—");
  });

  it("adds a bill", async () => {
    const fetchMock = withItems([], { "POST /api/items": () => ({ status: 201, data: ELECTRIC }) });
    renderApp("/recurring");

    await waitFor(() => expect(screen.getByRole("button", { name: "+ Add item" })).toBeEnabled());
    await userEvent.click(screen.getByRole("button", { name: "+ Add item" }));
    const dialog = screen.getByRole("dialog", { name: "Add a recurring item" });
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Name" }), "Electric");
    await pick(dialog, "Paid from", "Every");
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Amount each time" }), "140");
    await userEvent.click(within(dialog).getByRole("checkbox", { name: "It's an estimate" }));
    await userEvent.selectOptions(within(dialog).getByRole("combobox", { name: "Day of the month" }), "16th");
    await userEvent.click(within(dialog).getByRole("radio", { name: "One period early" }));
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
        },
        daysAfterPayday: 0,
        bucketId: null,
        saveUpBucketId: null,
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
    await pick(dialog, "Paid from", "Every");
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Amount each time" }), "300");
    await userEvent.selectOptions(within(dialog).getByRole("combobox", { name: "How often" }), "Every paycheck");
    await userEvent.selectOptions(within(dialog).getByRole("combobox", { name: "Dated" }), "3 days after payday");

    // A budget is not paid automatically and belongs to its own paycheck, so neither is asked.
    expect(within(dialog).queryByRole("checkbox", { name: "Autopay" })).not.toBeInTheDocument();
    expect(within(dialog).queryByRole("radio", { name: "One period early" })).not.toBeInTheDocument();

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

  it("adds a bucket move: money set aside in a bucket every paycheck", async () => {
    const fetchMock = withItems([], {
      "GET /api/buckets": () => ({ status: 200, data: [GIFTS, EMERGENCY] }),
      "POST /api/items": () => ({ status: 201, data: GROCERIES }),
    });
    renderApp("/recurring");

    await waitFor(() => expect(screen.getByRole("button", { name: "+ Add item" })).toBeEnabled());
    await userEvent.click(screen.getByRole("button", { name: "+ Add item" }));
    const dialog = screen.getByRole("dialog", { name: "Add a recurring item" });
    await userEvent.click(within(dialog).getByRole("radio", { name: "Bucket move" }));

    // The bucket says which account it is in, and nothing has to be paid by anyone.
    expect(within(dialog).queryByRole("combobox", { name: "Paid from" })).not.toBeInTheDocument();
    expect(within(dialog).queryByRole("checkbox", { name: "Autopay" })).not.toBeInTheDocument();
    expect(within(dialog).getByRole("combobox", { name: "How often" })).toHaveValue("every_pay_period");

    await userEvent.type(within(dialog).getByRole("textbox", { name: "Name" }), "Gift fund");
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Amount each time" }), "50");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add item" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please choose the bucket this money goes into.");

    await pick(dialog, "Into bucket", "Holiday");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add item" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const [sent] = bodiesSentTo(fetchMock, "POST /api/items") as [Record<string, unknown>];
    expect(sent).toMatchObject({
      kind: "bucket_move",
      amountCents: 5000,
      bucketId: 7,
      payFromAccountId: 1,
      payToAccountId: null,
      autopay: true,
      saveUpBucketId: null,
      schedule: { frequency: "every_pay_period" },
    });
  });

  it("saves up for a bill in a bucket of the account it is paid from", async () => {
    const savedUpFor: Item = { ...ELECTRIC, saveUpBucketId: 7, saveUpPerPaycheckCents: 7000 };
    const fetchMock = withItems([savedUpFor], {
      "GET /api/buckets": () => ({ status: 200, data: [GIFTS, EMERGENCY] }),
      "PUT /api/items/2": () => ({ status: 200, data: savedUpFor }),
    });
    renderApp("/recurring");

    await userEvent.click(await screen.findByRole("button", { name: "Edit Electric" }));
    const dialog = screen.getByRole("dialog", { name: "Edit Electric" });
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
    renderApp("/recurring");

    await userEvent.click(await screen.findByRole("button", { name: "Edit Electric" }));
    const dialog = screen.getByRole("dialog", { name: "Edit Electric" });
    // The account it is paid from has no bucket yet, and the option is there all the same.
    const saveUp = within(dialog).getByRole("combobox", { name: "Save up for it in a bucket (optional)" });
    await userEvent.type(saveUp, "Car insurance{Enter}");

    await waitFor(() => expect(saveUp).toHaveValue("Car insurance"));
    expect(bodiesSentTo(fetchMock, "POST /api/buckets")).toMatchObject([
      { accountId: ELECTRIC.payFromAccountId, name: "Car insurance", goalCents: null, startingCents: 0 },
    ]);
  });

  it("says why a bill paid from a card cannot be saved up for", async () => {
    withItems([{ ...ELECTRIC, payFromAccountId: VISA.id }]);
    renderApp("/recurring");

    await userEvent.click(await screen.findByRole("button", { name: "Edit Electric" }));
    const dialog = screen.getByRole("dialog", { name: "Edit Electric" });

    expect(within(dialog).getByText(/Buckets live in checking and savings accounts/)).toBeInTheDocument();
    expect(within(dialog).queryByRole("combobox", { name: "Save up for it in a bucket (optional)" })).not.toBeInTheDocument();
  });

  it("adds a yearly bill entered as an annual amount", async () => {
    const fetchMock = withItems([], { "POST /api/items": () => ({ status: 201, data: RENT }) });
    renderApp("/recurring");

    await waitFor(() => expect(screen.getByRole("button", { name: "+ Add item" })).toBeEnabled());
    await userEvent.click(screen.getByRole("button", { name: "+ Add item" }));
    const dialog = screen.getByRole("dialog", { name: "Add a recurring item" });
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Name" }), "Car insurance");
    await pick(dialog, "Paid from", "Every");
    await userEvent.click(within(dialog).getByRole("radio", { name: "Yearly total" }));
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Per year" }), "1,200");
    await userEvent.selectOptions(within(dialog).getByRole("combobox", { name: "How often" }), "Yearly");
    await userEvent.selectOptions(within(dialog).getByRole("combobox", { name: "Month" }), "March");
    await userEvent.selectOptions(within(dialog).getByRole("combobox", { name: "Day of the month" }), "12th");
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
    await userEvent.click(within(dialog).getByRole("combobox", { name: "Card" }));
    expect(within(within(dialog).getByRole("listbox", { name: "Card" })).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "VisaEnter",
    ]);

    await userEvent.click(within(dialog).getByRole("radio", { name: "Transfer" }));
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Name" }), "To savings");
    await pick(dialog, "From", "Every");
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Amount each time" }), "200");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add item" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please choose the account this money goes to.");

    await pick(dialog, "To", "Rainy");
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
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Amount each time" }), "lots");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add item" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please choose the account this is paid from.");

    await pick(dialog, "Paid from", "Every");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add item" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please enter an amount like 1,250.00.");
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
    await pick(dialog, "Paid from", "Every");
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Amount each time" }), "140");

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

  it("changes an item from a date on and shows its price history", async () => {
    const twoPrices: Item = {
      ...RENT,
      history: [
        { effectiveFrom: "2025-01-01", perOccurrenceCents: 148000, perYearCents: 1776000 },
        { effectiveFrom: "2026-01-01", perOccurrenceCents: 155000, perYearCents: 1860000 },
      ],
    };
    const fetchMock = withItems([twoPrices], { "PUT /api/items/1": () => ({ status: 200, data: twoPrices }) });
    renderApp("/recurring");

    await userEvent.click(await screen.findByRole("button", { name: "Edit Rent" }));
    const dialog = screen.getByRole("dialog", { name: "Edit Rent" });
    expect(within(dialog).getByRole("combobox", { name: "Category (optional)" })).toHaveValue("Housing › Rent");
    expect(within(dialog).getByRole("combobox", { name: "Paid from" })).toHaveValue("Everyday Checking");
    const history = within(dialog).getByRole("region", { name: "Price history" });
    expect(history).toHaveTextContent("From Jan 1, 2025");
    expect(history).toHaveTextContent("$17,760.00 a year");

    const amount = within(dialog).getByRole("textbox", { name: "Amount each time" });
    expect(amount).toHaveValue("1550.00");
    await userEvent.clear(amount);
    await userEvent.type(amount, "1600");
    // The day it was added is stated, not asked; the change itself is dated today.
    expect(within(dialog).getByText("Date added").nextElementSibling).toHaveTextContent("Jan 5, 2026");
    expect(within(dialog).queryByLabelText(/apply.* from|date added/i)).not.toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "PUT /api/items/1")).toMatchObject([
      { name: "Rent", amountCents: 160000, effectiveFrom: todayIso(), schedule: { startDate: "2026-01-01", day1: 1 } },
    ]);
  });

  it("shows when an item starts, and saves an earlier start so it can be backdated", async () => {
    const fetchMock = withItems([GROCERIES], { "PUT /api/items/3": () => ({ status: 200, data: GROCERIES }) });
    renderApp("/recurring");

    await userEvent.click(await screen.findByRole("button", { name: "Edit Groceries" }));
    const dialog = screen.getByRole("dialog", { name: "Edit Groceries" });
    // What was saved, not today: this is the item's own date and stays put between visits.
    const starts = within(dialog).getByLabelText("Starts");
    expect(starts).toHaveValue("2026-10-01");
    await userEvent.clear(starts);
    await userEvent.type(starts, "2026-09-01");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "PUT /api/items/3")).toMatchObject([{ schedule: { startDate: "2026-09-01" } }]);
  });

  it("shows the backend's message when a change is refused", async () => {
    withItems([RENT], { "PUT /api/items/1": () => ({ status: 400, error: "Please choose the category from the list." }) });
    renderApp("/recurring");

    await userEvent.click(await screen.findByRole("button", { name: "Edit Rent" }));
    const dialog = screen.getByRole("dialog", { name: "Edit Rent" });
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("Please choose the category from the list.");
  });

  it("asks before deleting an item, with keeping it as the default", async () => {
    const fetchMock = withItems([RENT], { "DELETE /api/items/1": () => ({ status: 200, data: { deleted: true } }) });
    renderApp("/recurring");

    await userEvent.click(await screen.findByRole("button", { name: "Edit Rent" }));
    const dialog = screen.getByRole("dialog", { name: "Edit Rent" });
    await userEvent.click(within(dialog).getByRole("button", { name: "Delete item…" }));
    expect(within(dialog).getByRole("button", { name: "Keep it" })).toHaveFocus();
    expect(calls(fetchMock)).not.toContain("DELETE /api/items/1");

    await userEvent.click(within(dialog).getByRole("button", { name: "Yes, delete it" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(calls(fetchMock)).toContain("DELETE /api/items/1");
  });
});
