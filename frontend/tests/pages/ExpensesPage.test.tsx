import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { Account } from "../../src/api/accounts";
import type { Attached } from "../../src/api/attachments";
import type { Bucket } from "../../src/api/buckets";
import type { Tag } from "../../src/api/categories";
import type { Entry } from "../../src/api/entries";
import { todayIso } from "../../src/lib/dates";
import { bodiesSentTo, calls, fakeBackend, renderApp } from "../helpers";

const YEAR = todayIso().slice(0, 4);

const CHECKING: Account = {
  id: 1,
  name: "Everyday Checking",
  institution: null,
  type: "checking",
  subtypeLabel: null,
  color: "#0f766e",
  archived: false, archivedOn: null,
  lowBalanceThresholdCents: null,
  latestBalance: null,
};
const VISA: Account = { ...CHECKING, id: 2, name: "Visa", type: "credit_card", color: "#1d4ed8" };

const OUT_OF_POCKET: Tag = { id: 1, name: "Medical – out of pocket", color: "#be185d", itemCount: 0, perYearCents: 0 };
const HOME_REPAIR: Tag = { id: 2, name: "Home repair", color: "#a16207", itemCount: 0, perYearCents: 0 };

const NOT_REPAID = { status: "none", submittedOn: null, to: null, paidBackCents: 0, reimbursedCents: 0, owedCents: 0, amountCents: 0, intoAccountId: null, expectedOn: null, fromAccountId: null } as const;

/** Sent in, with $60.00 of $85.00 back so far. */
const PEDS: Entry = {
  id: 9,
  kind: "money_out",
  date: `${YEAR}-09-28`,
  amountCents: 8500,
  estimate: false,
  description: "Copay plus lab work",
  payee: "Valley Pediatrics",
  categoryId: null,
  accountId: 2,
  toAccountId: null,
  note: "Zadie's check-up",
  checkNumber: null,
  tagIds: [1],
  bucketSplits: [],
  reimbursement: { status: "submitted", submittedOn: `${YEAR}-09-29`, to: "HSA", paidBackCents: 6000, reimbursedCents: 6000, owedCents: 2500, amountCents: 8500, intoAccountId: null, expectedOn: null, fromAccountId: null },
  reimbursesEntryId: null,
  reimbursesPaymentId: null,
  fileCount: 2,
};
const ROOF: Entry = {
  ...PEDS,
  id: 10,
  date: `${YEAR}-03-17`,
  amountCents: 41000,
  description: "Patched the roof",
  payee: null,
  accountId: 1,
  note: null,
  tagIds: [2],
  reimbursement: NOT_REPAID,
  fileCount: 0,
};
const HSA_PAYOUT: Entry = {
  ...PEDS,
  id: 11,
  kind: "money_in",
  date: `${YEAR}-09-30`,
  amountCents: 6000,
  description: "HSA payout",
  payee: null,
  accountId: 1,
  note: null,
  tagIds: [],
  reimbursement: null,
  reimbursesEntryId: 9,
  fileCount: 0,
};

const INVOICE = { id: 5, name: "invoice-valley-peds.pdf", mime: "application/pdf", sizeBytes: 186368, pages: 2 } as const;
const PHOTO = { id: 6, name: "receipt.jpg", mime: "image/jpeg", sizeBytes: 98304, pages: null } as const;
const TWO_FILES: Attached = { attachments: [INVOICE, PHOTO], maxBytes: 25 * 1024 * 1024, maxFiles: 20 };

function withExpenses(overrides: Parameters<typeof fakeBackend>[0] = {}) {
  return fakeBackend({
    "GET /api/accounts": () => ({ status: 200, data: [CHECKING, VISA] }),
    "GET /api/tags": () => ({ status: 200, data: [OUT_OF_POCKET, HOME_REPAIR] }),
    "GET /api/entries": () => ({ status: 200, data: [ROOF, PEDS, HSA_PAYOUT] }),
    "GET /api/expense-years": () => ({ status: 200, data: { years: [Number(YEAR), 2024] } }),
    "GET /api/payees": () => ({ status: 200, data: { payees: ["Valley Pediatrics"], reimbursers: ["HSA", "Work"] } }),
    "GET /api/entries/9": () => ({ status: 200, data: { entry: PEDS, paybacks: [HSA_PAYOUT], reimburses: null, movesOut: [] } }),
    "GET /api/entries/9/attachments": () => ({ status: 200, data: TWO_FILES }),
    ...overrides,
  });
}

describe("Expenses & receipts", () => {
  it("says what the screen is for before anything is entered", async () => {
    fakeBackend({
      "GET /api/accounts": () => ({ status: 200, data: [CHECKING] }),
      "GET /api/entries": () => ({ status: 200, data: [] }),
      "GET /api/expense-years": () => ({ status: 200, data: { years: [] } }),
    });
    renderApp("/expenses");

    expect(await screen.findByText(`No one-off spending in ${YEAR} yet.`)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "+ Add expense" })).toBeEnabled();
  });

  it("asks for an account first when there is none to pay from", async () => {
    fakeBackend({ "GET /api/entries": () => ({ status: 200, data: [] }), "GET /api/expense-years": () => ({ status: 200, data: { years: [] } }) });
    renderApp("/expenses");

    expect(await screen.findByText("Add an account first.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "+ Add expense" })).toBeDisabled();
  });

  it("lists the year's one-off spending, newest first, with what is still owed", async () => {
    const fetchMock = withExpenses();
    renderApp("/expenses");

    const list = await screen.findByRole("region", { name: `Expenses in ${YEAR}` });
    const rows = await within(list).findAllByRole("link");
    // Money coming in is not an expense: only the two that went out are listed.
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("Valley Pediatrics");
    expect(rows[0]).toHaveTextContent("$85.00");
    expect(rows[0]).toHaveTextContent("Submitted");
    expect(rows[0]).toHaveTextContent("2 files");
    expect(rows[0]).toHaveTextContent("Visa");
    expect(rows[0]).toHaveAttribute("href", "/expenses/9");
    expect(rows[1]).toHaveTextContent("Patched the roof");
    expect(rows[1]).toHaveTextContent("None");

    expect(screen.getByText("2 expenses")).toBeInTheDocument();
    expect(screen.getByText("$495.00")).toBeInTheDocument();
    expect(screen.getByText("Still owed to you").parentElement).toHaveTextContent("$25.00");
    expect(screen.getByText("Reimbursed", { selector: "p" }).parentElement).toHaveTextContent("$60.00");
    // The whole year was asked for.
    expect(calls(fetchMock)).toContain(`GET /api/entries?from=${YEAR}-01-01&to=${YEAR}-12-31`);
  });

  it("narrows by tag and by where the reimbursement stands", async () => {
    withExpenses();
    renderApp("/expenses");

    const tagFilter = await screen.findByRole("group", { name: "Show expenses with tag" });
    await userEvent.click(within(tagFilter).getByRole("button", { name: "Home repair" }));

    const list = screen.getByRole("region", { name: `Expenses in ${YEAR}` });
    expect(within(list).getAllByRole("link")).toHaveLength(1);
    expect(within(list).getByRole("link")).toHaveTextContent("Patched the roof");
    expect(screen.getByText("1 expense")).toBeInTheDocument();
    expect(within(tagFilter).getByRole("button", { name: "Home repair" })).toHaveAttribute("aria-pressed", "true");

    // Nothing tagged Home repair was sent in to anyone.
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Reimbursement" }), "submitted");
    expect(within(list).queryByRole("link")).not.toBeInTheDocument();
    expect(screen.getByText(/Nothing in \d+ matches what is chosen above/)).toBeInTheDocument();

    await userEvent.click(within(tagFilter).getByRole("button", { name: "All" }));
    expect(within(list).getByRole("link")).toHaveTextContent("Valley Pediatrics");
  });

  it("narrows by category, sub-categories included, and then offers only the tags left", async () => {
    const HOUSING = { id: 20, name: "Housing", color: "#0f766e", parentId: null, itemCount: 0, perYearCents: 0 };
    const REPAIRS = { ...HOUSING, id: 21, name: "Repairs", parentId: 20 };
    const HEALTH = { ...HOUSING, id: 30, name: "Health" };
    withExpenses({
      "GET /api/categories": () => ({ status: 200, data: [HOUSING, REPAIRS, HEALTH] }),
      "GET /api/entries": () => ({ status: 200, data: [{ ...ROOF, categoryId: 21 }, { ...PEDS, categoryId: 30 }, HSA_PAYOUT] }),
    });
    renderApp("/expenses");

    const category = await screen.findByRole("combobox", { name: "Category" });
    expect(within(category).getAllByRole("option").map((option) => option.textContent)).toEqual(["Any", "Health", "Housing", "Housing › Repairs"]);
    await userEvent.selectOptions(category, "Housing");

    const list = screen.getByRole("region", { name: `Expenses in ${YEAR}` });
    expect(within(list).getAllByRole("link")).toHaveLength(1);
    expect(within(list).getByRole("link")).toHaveTextContent("Patched the roof");
    const tagFilter = screen.getByRole("group", { name: "Show expenses with tag" });
    expect(within(tagFilter).queryByRole("button", { name: "Medical – out of pocket" })).not.toBeInTheDocument();
    expect(within(tagFilter).getByRole("button", { name: "Home repair" })).toBeInTheDocument();
  });

  it("shows another year when one is chosen", async () => {
    const fetchMock = withExpenses();
    renderApp("/expenses");

    await screen.findByRole("option", { name: "2024" });
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Year" }), "2024");

    await waitFor(() => expect(calls(fetchMock)).toContain("GET /api/entries?from=2024-01-01&to=2024-12-31"));
    expect(screen.getByRole("heading", { name: "Expenses in 2024" })).toBeInTheDocument();
  });

  it("says kindly when the expenses cannot be loaded", async () => {
    withExpenses({ "GET /api/entries": () => ({ status: 500, error: "Something went wrong on our side. Your data is safe — please try again." }) });
    renderApp("/expenses");

    expect(await screen.findByRole("alert")).toHaveTextContent("Your data is safe");
  });

  it("adds an expense that will be paid back", async () => {
    const fetchMock = withExpenses({ "POST /api/entries": () => ({ status: 201, data: { ...PEDS, id: 12 }, changed: true }) });
    renderApp("/expenses");

    await userEvent.click(await screen.findByRole("button", { name: "+ Add expense" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(await within(dialog).findByRole("textbox", { name: "Amount" }), "95.20");
    await userEvent.type(within(dialog).getByRole("textbox", { name: "What is it" }), "Prescription");
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    await userEvent.click(within(dialog).getByRole("combobox", { name: "Paid from" }));
    await userEvent.click(await within(dialog).findByRole("option", { name: "Everyday Checking" }));
    // Who it was paid to, its tags, its receipt and whether it comes back wait behind "More options".
    await userEvent.click(within(dialog).getByRole("button", { name: "More options" }));
    expect(within(dialog).getByRole("combobox", { name: "Paid to (optional)" })).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    expect(within(dialog).getByLabelText("Receipt or document (optional)")).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("checkbox", { name: "I'll be paid back for this" }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Add" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "POST /api/entries")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "POST /api/entries")[0]).toMatchObject({
      kind: "money_out",
      amountCents: 9520,
      accountId: 1,
      reimbursementStatus: "to_submit",
      reimbursesEntryId: null,
      reimbursesPaymentId: null,
    });
  });

  it("takes a receipt along with a new expense, and attaches it once the expense is in", async () => {
    const fetchMock = withExpenses({
      "POST /api/entries": () => ({ status: 201, data: { ...PEDS, id: 12 }, changed: true }),
      "POST /api/entries/12/attachments": () => ({ status: 201, data: TWO_FILES, changed: true }),
    });
    renderApp("/expenses");

    await userEvent.click(await screen.findByRole("button", { name: "+ Add expense" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(await within(dialog).findByRole("textbox", { name: "Amount" }), "95.20");
    await userEvent.type(within(dialog).getByRole("textbox", { name: "What is it" }), "Prescription");
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    await userEvent.click(within(dialog).getByRole("combobox", { name: "Paid from" }));
    await userEvent.click(await within(dialog).findByRole("option", { name: "Everyday Checking" }));
    await userEvent.click(within(dialog).getByRole("button", { name: "More options" }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    await userEvent.upload(
      within(dialog).getByLabelText("Receipt or document (optional)"),
      new File(["%PDF-1.7"], "pharmacy.pdf", { type: "application/pdf" }),
    );
    // Said in words, because the chooser itself forgets when the screen is left.
    expect(within(dialog).getByText(/Chosen: pharmacy.pdf/)).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "Add" }));

    await waitFor(() => expect(calls(fetchMock)).toContain("POST /api/entries/12/attachments"));
    expect(calls(fetchMock).indexOf("POST /api/entries")).toBeLessThan(calls(fetchMock).indexOf("POST /api/entries/12/attachments"));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });
});

describe("One expense", () => {
  it("opens with the amount, where its reimbursement stands, its details and its receipts", async () => {
    withExpenses();
    renderApp("/expenses/9");

    expect(await screen.findByRole("heading", { level: 1, name: "Valley Pediatrics" })).toBeInTheDocument();
    expect(screen.getByText(/paid from Visa, so it adds to what is owed there/)).toBeInTheDocument();
    expect(screen.getByText("Paid back so far").parentElement).toHaveTextContent("$60.00");
    expect(screen.getByText("Still owed to you").parentElement).toHaveTextContent("$25.00");

    const reimbursement = screen.getByRole("region", { name: "Reimbursement" });
    // Three stops in a line, shown and not clicked: it is on the second, and each says in words where it is.
    const steps = within(within(reimbursement).getByRole("list", { name: "How far along it is" })).getAllByRole("listitem");
    expect(steps.map((step) => step.textContent)).toEqual([
      "To submitDone",
      expect.stringMatching(/^SubmittedSep 29, \d+$/),
      "ReimbursedNot yet",
    ]);
    expect(steps[1]).toHaveAttribute("aria-current", "step");
    expect(within(reimbursement).queryByRole("radio")).not.toBeInTheDocument();
    expect(within(reimbursement).getByText(/Submitted to HSA on Sep 29, \d+ · \$25.00 still to come\./)).toBeInTheDocument();
    expect(within(reimbursement).getByRole("button", { name: "Edit HSA payout" })).toBeInTheDocument();

    const details = screen.getByRole("region", { name: "Details" });
    expect(within(details).getByText("Medical – out of pocket")).toBeInTheDocument();
    expect(within(details).getByText("Zadie's check-up")).toBeInTheDocument();

    const receipts = screen.getByRole("region", { name: "Receipts & documents" });
    expect(await within(receipts).findByRole("button", { name: "Show invoice-valley-peds.pdf" })).toBeInTheDocument();
    expect(within(receipts).getByText("182 KB")).toBeInTheDocument();
    // The first file is shown, a page at a time.
    expect(within(receipts).getByRole("img", { name: "invoice-valley-peds.pdf, page 1 of 2" })).toHaveAttribute(
      "src",
      "/api/attachments/5/preview?page=1",
    );
    expect(within(receipts).getByRole("button", { name: "Save a copy of receipt.jpg" })).toBeInTheDocument();
  });

  it("pays an expense back out of a bucket, and lists it with the money that came back", async () => {
    const medical: Bucket = {
      id: 7,
      accountId: 1,
      name: "Medical",
      color: "#b45309",
      goalCents: null,
      note: null,
      archived: false, archivedOn: null,
      openedOn: "2026-01-01",
      goalDate: null,
      stopAtGoal: false,
      goalProgress: null,
      balanceCents: 30000,
    };
    const earlier = {
      id: 31,
      bucketId: 7,
      accountId: 1,
      date: `${YEAR}-01-05`,
      amountCents: 1000,
      note: "From the medical fund",
      categoryId: null,
      tagIds: [],
      reimbursesEntryId: 9,
    };
    const fetchMock = withExpenses({
      "GET /api/buckets": () => ({ status: 200, data: [medical] }),
      "GET /api/entries/9": () => ({ status: 200, data: { entry: PEDS, paybacks: [HSA_PAYOUT], reimburses: null, movesOut: [earlier] } }),
      "POST /api/buckets/7/moves-out": () => ({ status: 201, data: { id: 32 }, changed: true }),
    });
    renderApp("/expenses/9");

    const reimbursement = await screen.findByRole("region", { name: "Reimbursement" });
    expect(within(reimbursement).getByRole("button", { name: "Edit From the medical fund" })).toHaveTextContent("Out of Medical");
    await userEvent.click(within(reimbursement).getByRole("button", { name: "+ Pay back from a bucket" }));

    // Opened from the expense: what is still owed, named for it, and paying it back.
    const dialog = await screen.findByRole("dialog", { name: "Move money out of a bucket" });
    expect(within(dialog).getByRole("textbox", { name: "Amount" })).toHaveValue("25.00");
    expect(within(dialog).getByLabelText("What is it")).toHaveValue("Reimbursement: Valley Pediatrics");
    await userEvent.selectOptions(within(dialog).getByLabelText("Out of"), "Everyday Checking · Medical (holds $300.00 today)");
    await userEvent.click(within(dialog).getByRole("button", { name: "Move it" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "POST /api/buckets/7/moves-out")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "POST /api/buckets/7/moves-out")[0]).toMatchObject({
      amountCents: 2500,
      note: "Reimbursement: Valley Pediatrics",
      reimbursesEntryId: 9,
    });
  });

  it("saves a copy of a receipt and says where Budget put it", async () => {
    const fetchMock = withExpenses({
      "POST /api/attachments/6/copy": () => ({
        status: 200,
        data: { saved: { fileName: "receipt (2).jpg", folder: "C:\\Users\\Amara\\Downloads" } },
      }),
    });
    renderApp("/expenses/9");

    await userEvent.click(await screen.findByRole("button", { name: "Save a copy of receipt.jpg" }));

    expect(await screen.findByText("Saved in C:\\Users\\Amara\\Downloads as receipt (2).jpg.")).toBeInTheDocument();
    expect(calls(fetchMock)).toContain("POST /api/attachments/6/copy");
  });

  it("downloads the copy in the browser when Budget cannot save it itself, as on a phone", async () => {
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    withExpenses({ "POST /api/attachments/6/copy": () => ({ status: 200, data: { saved: null } }) });
    renderApp("/expenses/9");

    await userEvent.click(await screen.findByRole("button", { name: "Save a copy of receipt.jpg" }));

    await waitFor(() => expect(click).toHaveBeenCalledTimes(1));
    const link = click.mock.contexts[0] as HTMLAnchorElement;
    expect(link.getAttribute("href")).toBe("/api/attachments/6/file");
    expect(link.download).toBe("receipt.jpg");
    click.mockRestore();
  });

  it("pages through a PDF and switches to another file", async () => {
    withExpenses();
    renderApp("/expenses/9");

    const receipts = await screen.findByRole("region", { name: "Receipts & documents" });
    await userEvent.click(await within(receipts).findByRole("button", { name: "Next page" }));
    expect(within(receipts).getByRole("img", { name: "invoice-valley-peds.pdf, page 2 of 2" })).toHaveAttribute(
      "src",
      "/api/attachments/5/preview?page=2",
    );
    expect(within(receipts).getByRole("button", { name: "Next page" })).toBeDisabled();

    await userEvent.click(within(receipts).getByRole("button", { name: "Show receipt.jpg" }));
    expect(within(receipts).getByRole("img", { name: "receipt.jpg" })).toHaveAttribute("src", "/api/attachments/6/preview");
  });

  it("saves where the reimbursement stands the moment it is changed", async () => {
    const fetchMock = withExpenses({ "PUT /api/entries/9/reimbursement": () => ({ status: 200, data: PEDS, changed: true }) });
    renderApp("/expenses/9");

    // One button, for the next thing to do.
    await userEvent.click(await screen.findByRole("button", { name: "Mark as reimbursed" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/entries/9/reimbursement")).toHaveLength(1));
    // The day it was sent in and who to are kept.
    expect(bodiesSentTo(fetchMock, "PUT /api/entries/9/reimbursement")[0]).toEqual({
      status: "reimbursed",
      submittedOn: `${YEAR}-09-29`,
      to: "HSA",
    });
  });

  it("saves the day it was sent in once the field is left, not while it is typed", async () => {
    // Rachel, 2026-10-20: "when I choose day and type 16 it keeps setting to 06".
    const fetchMock = withExpenses({ "PUT /api/entries/9/reimbursement": () => ({ status: 200, data: PEDS, changed: true }) });
    renderApp("/expenses/9");

    const sentIn = await screen.findByLabelText("Sent in on (optional)");
    fireEvent.change(sentIn, { target: { value: `${YEAR}-10-01` } });
    fireEvent.change(sentIn, { target: { value: `${YEAR}-10-16` } });
    expect(calls(fetchMock)).not.toContain("PUT /api/entries/9/reimbursement");
    expect(sentIn).toHaveValue(`${YEAR}-10-16`);

    fireEvent.blur(sentIn);
    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "PUT /api/entries/9/reimbursement")).toEqual([
        { status: "submitted", submittedOn: `${YEAR}-10-16`, to: "HSA" },
      ]),
    );
  });

  it("links to the pay period the expense falls in", async () => {
    const fetchMock = withExpenses({
      "GET /api/pay-periods": () => ({
        status: 200,
        data: {
          primarySourceId: 1,
          primaryAccountId: 1,
          periods: [
            { start: `${YEAR}-09-01`, end: `${YEAR}-09-14` },
            { start: `${YEAR}-09-15`, end: `${YEAR}-09-30` },
          ],
        },
      }),
    });
    renderApp("/expenses/9");

    const link = await screen.findByRole("link", { name: "See its pay period" });
    expect(link).toHaveAttribute("href", `/periods/${YEAR}-09-15`);
    // Asked for the weeks up to the expense's own day, which is as far as its period can reach back.
    expect(calls(fetchMock).some((call) => call.startsWith("GET /api/pay-periods"))).toBe(true);
  });

  it("offers no pay period when none holds the expense's date", async () => {
    withExpenses();
    renderApp("/expenses/9");

    await screen.findByRole("heading", { level: 1, name: "Valley Pediatrics" });
    expect(screen.queryByRole("link", { name: "See its pay period" })).not.toBeInTheDocument();
  });

  it("opens the Details card's pencil on that card's own fields, and saves them", async () => {
    const fetchMock = withExpenses({ "PUT /api/entries/9": () => ({ status: 200, data: PEDS, changed: true }) });
    renderApp("/expenses/9");

    const details = await screen.findByRole("region", { name: "Details" });
    await userEvent.click(within(details).getByRole("button", { name: "Edit details" }));

    const dialog = await screen.findByRole("dialog");
    // What the card shows is what the form opens on: category, tags and note.
    expect(within(dialog).getByRole("combobox", { name: "What to change" })).toHaveDisplayValue("Details");
    expect(within(dialog).getByRole("combobox", { name: "Tags (optional)" })).toBeInTheDocument();
    expect(within(dialog).getByRole("combobox", { name: "Category (optional)" })).toBeInTheDocument();
    expect(within(dialog).queryByRole("textbox", { name: "Amount" })).not.toBeInTheDocument();
    // Past the first screen there is no switch between money out, money in and transfer.
    expect(within(dialog).queryByRole("radio", { name: "Money out" })).not.toBeInTheDocument();

    await userEvent.type(within(dialog).getByRole("textbox", { name: "Note (optional)" }), "Asked for an itemised bill");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/entries/9")).toHaveLength(1));
    // Everything the form did not show goes back as it was.
    expect(bodiesSentTo(fetchMock, "PUT /api/entries/9")[0]).toMatchObject({
      amountCents: PEDS.amountCents,
      payee: PEDS.payee,
      tagIds: PEDS.tagIds,
      note: expect.stringContaining("Asked for an itemised bill"),
    });
  });

  it("opens Edit expense at the top on the amount", async () => {
    withExpenses();
    renderApp("/expenses/9");

    await userEvent.click(await screen.findByRole("button", { name: "Edit expense" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByRole("combobox", { name: "What to change" })).toHaveDisplayValue("Amount");
    expect(within(dialog).getByRole("textbox", { name: "Amount" })).toBeInTheDocument();
  });

  it("keeps the ways back behind Change", async () => {
    const fetchMock = withExpenses({ "PUT /api/entries/9/reimbursement": () => ({ status: 200, data: PEDS, changed: true }) });
    renderApp("/expenses/9");

    const change = await screen.findByRole("button", { name: "Change" });
    expect(screen.queryByRole("button", { name: "Back to To submit" })).not.toBeInTheDocument();
    await userEvent.click(change);
    expect(change).toHaveAttribute("aria-expanded", "true");
    await userEvent.click(screen.getByRole("button", { name: "Back to To submit" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/entries/9/reimbursement")).toHaveLength(1));
    // Not sent in after all, so the day it was sent in goes too.
    expect(bodiesSentTo(fetchMock, "PUT /api/entries/9/reimbursement")[0]).toEqual({ status: "to_submit", submittedOn: null, to: "HSA" });

    await userEvent.click(screen.getByRole("button", { name: "Change" }));
    await userEvent.click(screen.getByRole("button", { name: "I won't be paid back for this" }));
    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/entries/9/reimbursement")).toHaveLength(2));
    expect(bodiesSentTo(fetchMock, "PUT /api/entries/9/reimbursement")[1]).toMatchObject({ status: "none" });
  });

  it("offers to start tracking an expense nobody was expected to pay back", async () => {
    const untracked: Entry = { ...PEDS, reimbursement: { ...NOT_REPAID } };
    const fetchMock = withExpenses({
      "GET /api/entries/9": () => ({ status: 200, data: { entry: untracked, paybacks: [], reimburses: null, movesOut: [] } }),
      "PUT /api/entries/9/reimbursement": () => ({ status: 200, data: untracked, changed: true }),
    });
    renderApp("/expenses/9");

    expect(await screen.findByText("Nobody pays this back.")).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "How far along it is" })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "I'll be paid back for this" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/entries/9/reimbursement")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "PUT /api/entries/9/reimbursement")[0]).toMatchObject({ status: "to_submit" });
  });

  // Rachel, 2026-10-25: "there is no way to plan for the reimbursement in that account"; "partials are possible".
  it("plans part of an expense coming back into another account on another day", async () => {
    const waiting: Entry = {
      ...PEDS,
      reimbursement: { ...NOT_REPAID, status: "to_submit", owedCents: 8500, amountCents: 8500, intoAccountId: 1, expectedOn: `${YEAR}-10-28`, fromAccountId: null },
    };
    const fetchMock = withExpenses({
      "GET /api/entries/9": () => ({ status: 200, data: { entry: waiting, paybacks: [], reimburses: null, movesOut: [] } }),
      "PUT /api/entries/9/reimbursement": () => ({ status: 200, data: waiting, changed: true }),
    });
    renderApp("/expenses/9");

    const card = await screen.findByRole("region", { name: "Coming back" });
    expect(card).toHaveTextContent("all of it");
    expect(card).toHaveTextContent("Everyday Checking");
    await userEvent.click(within(card).getByRole("button", { name: "Edit what comes back" }));
    const dialog = await screen.findByRole("dialog", { name: "What comes back" });
    const amount = within(dialog).getByRole("textbox", { name: "Comes back" });
    await userEvent.clear(amount);
    await userEvent.type(amount, "60");
    fireEvent.change(within(dialog).getByLabelText("About when"), { target: { value: `${YEAR}-11-02` } });
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "PUT /api/entries/9/reimbursement")).toEqual([
        { status: "to_submit", submittedOn: null, to: null, amountCents: 6000, intoAccountId: 1, expectedOn: `${YEAR}-11-02`, fromAccountId: null },
      ]),
    );
  });

  it("marking something sent in with no day given means today", async () => {
    const waiting: Entry = { ...PEDS, reimbursement: { ...NOT_REPAID, status: "to_submit", owedCents: 8500 } };
    const fetchMock = withExpenses({
      "GET /api/entries/9": () => ({ status: 200, data: { entry: waiting, paybacks: [], reimburses: null, movesOut: [] } }),
      "PUT /api/entries/9/reimbursement": () => ({ status: 200, data: waiting, changed: true }),
    });
    renderApp("/expenses/9");

    expect(await screen.findByText("Still to send in · $85.00 to ask for.")).toBeInTheDocument();
    expect(screen.getByRole("listitem", { current: "step" })).toHaveTextContent("To submitNot sent in yet");
    await userEvent.click(screen.getByRole("button", { name: "Mark as submitted" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/entries/9/reimbursement")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "PUT /api/entries/9/reimbursement")[0]).toEqual({ status: "submitted", submittedOn: todayIso(), to: null });
  });

  it("adds the money that came back, already pointing at this expense for what is still owed", async () => {
    const fetchMock = withExpenses({ "POST /api/entries": () => ({ status: 201, data: HSA_PAYOUT, changed: true }) });
    renderApp("/expenses/9");

    await userEvent.click(await screen.findByRole("button", { name: "+ Add money that came back" }));
    const dialog = await screen.findByRole("dialog");
    expect(await within(dialog).findByRole("textbox", { name: "Amount" })).toHaveValue("25.00");
    expect(within(dialog).getByRole("textbox", { name: "What is it" })).toHaveValue("Reimbursement: Valley Pediatrics");
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    await userEvent.click(within(dialog).getByRole("combobox", { name: "Into" }));
    await userEvent.click(await within(dialog).findByRole("option", { name: "Everyday Checking" }));
    // The expense is already chosen; it can be seen, and changed, under "More options".
    await userEvent.click(within(dialog).getByRole("button", { name: "More options" }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    const paysBack = within(dialog).getByRole<HTMLInputElement>("combobox", { name: "Pays back an expense (optional)" });
    expect(paysBack.value).toContain("Valley Pediatrics");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "POST /api/entries")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "POST /api/entries")[0]).toMatchObject({ kind: "money_in", amountCents: 2500, reimbursesEntryId: 9 });
  });

  // Rachel, 2026-10-25: "there is not option to take it out of the fsa"; she chose a transfer, from the same card.
  it("records money paid back out of the FSA as a transfer into the account it comes back into", async () => {
    const fsa: Account = { ...CHECKING, id: 5, name: "Flexible Spending", type: "savings" };
    const waiting: Entry = {
      ...PEDS,
      reimbursement: { ...NOT_REPAID, status: "submitted", owedCents: 8500, amountCents: 8500, intoAccountId: 1, expectedOn: `${YEAR}-10-28`, fromAccountId: 5 },
    };
    const fetchMock = withExpenses({
      "GET /api/accounts": () => ({ status: 200, data: [CHECKING, VISA, fsa] }),
      "GET /api/entries/9": () => ({ status: 200, data: { entry: waiting, paybacks: [], reimburses: null, movesOut: [] } }),
      "POST /api/entries": () => ({ status: 201, data: HSA_PAYOUT, changed: true }),
    });
    renderApp("/expenses/9");

    expect(await screen.findByRole("region", { name: "Coming back" })).toHaveTextContent("Flexible Spending");
    await userEvent.click(screen.getByRole("button", { name: "+ Add money that came back" }));
    const dialog = await screen.findByRole("dialog");
    expect(await within(dialog).findByRole("textbox", { name: "Amount" })).toHaveValue("85.00");
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    expect(within(dialog).getByRole<HTMLInputElement>("combobox", { name: "From" }).value).toBe("Flexible Spending");
    expect(within(dialog).getByRole<HTMLInputElement>("combobox", { name: "To" }).value).toBe("Everyday Checking");
    await userEvent.click(within(dialog).getByRole("button", { name: "More options" }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Add" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "POST /api/entries")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "POST /api/entries")[0]).toMatchObject({
      kind: "transfer",
      accountId: 5,
      toAccountId: 1,
      amountCents: 8500,
      reimbursesEntryId: 9,
    });
  });

  it("says what to do when what was dropped is a link from a web page, not a file", async () => {
    const fetchMock = withExpenses({ "POST /api/entries/9/attachments": () => ({ status: 201, data: TWO_FILES, changed: true }) });
    renderApp("/expenses/9");

    const zone = await screen.findByText("Drop files here or browse");
    fireEvent.drop(zone, { dataTransfer: { files: [], types: ["text/uri-list"] } });

    expect(await screen.findByRole("alert")).toHaveTextContent("That came across as a link, not a file");
    expect(calls(fetchMock)).not.toContain("POST /api/entries/9/attachments");

    // A real file dropped in the same place is kept.
    fireEvent.drop(zone, { dataTransfer: { files: [new File(["%PDF-1.7"], "eob.pdf", { type: "application/pdf" })] } });
    await waitFor(() => expect(calls(fetchMock)).toContain("POST /api/entries/9/attachments"));
  });

  it("attaches a file, and refuses one it cannot keep before sending anything", async () => {
    const fetchMock = withExpenses({ "POST /api/entries/9/attachments": () => ({ status: 201, data: TWO_FILES, changed: true }) });
    renderApp("/expenses/9");

    const chooser = await screen.findByLabelText("Drop files here or browse");
    // applyAccept off: the point is that a file the chooser let through is still checked.
    await userEvent.upload(chooser, new File(["notes"], "notes.txt", { type: "text/plain" }), { applyAccept: false });
    expect(await screen.findByRole("alert")).toHaveTextContent("Budget can keep PDFs and JPEG or PNG pictures");
    expect(calls(fetchMock)).not.toContain("POST /api/entries/9/attachments");

    await userEvent.upload(chooser, new File(["%PDF-1.7"], "explanation-of-benefits.pdf", { type: "application/pdf" }));
    await waitFor(() => expect(calls(fetchMock)).toContain("POST /api/entries/9/attachments"));
    const sent = fetchMock.mock.calls.find(([path, init]) => path === "/api/entries/9/attachments" && init?.method === "POST")?.[1]?.body;
    expect(sent).toBeInstanceOf(FormData);
    expect(((sent as FormData).get("file") as File).name).toBe("explanation-of-benefits.pdf");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("removes a file without asking, since Undo brings it back", async () => {
    const fetchMock = withExpenses({
      "DELETE /api/attachments/6": () => ({ status: 200, data: { ...TWO_FILES, attachments: [INVOICE] }, changed: true }),
    });
    renderApp("/expenses/9");

    await userEvent.click(await screen.findByRole("button", { name: "Remove receipt.jpg" }));

    await waitFor(() => expect(calls(fetchMock)).toContain("DELETE /api/attachments/6"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("says so when the expense is not there", async () => {
    withExpenses();
    renderApp("/expenses/404");

    expect(await screen.findByRole("alert")).toHaveTextContent("We couldn't find that expense. It may have been removed.");
    expect(screen.getByRole("link", { name: "Back to all expenses" })).toHaveAttribute("href", "/expenses");
  });
});
