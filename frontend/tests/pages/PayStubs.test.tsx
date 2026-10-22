import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Account } from "../../src/api/accounts";
import type { IncomeSource } from "../../src/api/income";
import type { PayStub, PayStubSummary, StubLine, StubRead } from "../../src/api/payStubs";
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
const RETIREMENT: Account = { ...CHECKING, id: 2, name: "Retirement", type: "investment", color: "#7e22ce" };

const SALARY: IncomeSource = {
  payHistory: [],
  id: 1,
  name: "Northwind Health",
  depositAccountId: 1,
  grossCents: 360000,
  netCents: 248620,
  netIsEstimate: false,
  primary: true,
  schedule: {
    frequency: "semi_monthly",
    day1: 1,
    day2: 15,
    startDate: "2026-01-01",
    endDate: null,
    weekendRule: "previous_business_day",
    manualDates: [],
  },
  deductions: [],
  match: null,
  matchFigures: null,
};

function line(id: number, section: StubLine["section"], tracksAs: StubLine["tracksAs"], label: string, amountCents: number, more: Partial<StubLine> = {}): StubLine {
  return { id, section, tracksAs, label, amountCents, ytdCents: amountCents * 19, accountId: null, sure: true, ...more };
}

/** A made-up stub that adds up: 3,600.00 less 1,113.80 taken out is the 2,486.20 paid. */
const LINES: StubLine[] = [
  line(1, "earnings", "gross", "Regular pay", 360000),
  line(2, "pre_tax", "contribution", "401(k)", 18000, { accountId: 2 }),
  line(3, "pre_tax", "benefit", "Medical", 9500),
  line(4, "tax", "tax", "Federal income tax", 52880),
  line(5, "tax", "tax", "Social Security", 22320),
  line(6, "after_tax", "benefit", "Parking", 8680, { sure: false }),
  line(7, "net", "net", "Net pay", 248620),
  line(8, "employer", "match", "401(k) match", 10800, { accountId: 2 }),
];

const STUB: PayStub = {
  id: 7,
  incomeSourceId: 1,
  sourceName: "Northwind Health",
  name: "northwind-2026-10-01.pdf",
  payDate: "2026-10-01",
  status: "review",
  applied: null,
  pages: 1,
  sizeBytes: 48211,
  lines: LINES,
  totals: {
    grossCents: 360000,
    preTaxCents: 27500,
    taxesCents: 75200,
    afterTaxCents: 8680,
    deductionsCents: 111380,
    netCents: 248620,
    employerCents: 10800,
    differenceCents: 0,
    balanced: true,
  },
  toCheck: 1,
  paycheck: { scheduledDate: "2026-10-01", payDate: "2026-10-01", netCents: 248620 },
  wouldSet: {
    grossCents: 360000,
    netCents: 248620,
    deductions: [
      { name: "401(k)", amountCents: 18000, toAccountId: 2, percentBp: null },
      { name: "Medical", amountCents: 9500, toAccountId: null, percentBp: null },
      { name: "Parking", amountCents: 8680, toAccountId: null, percentBp: null },
    ],
    match: null,
  },
};

const SAVED: PayStub = { ...STUB, status: "saved", applied: "correct", toCheck: 0, lines: LINES.map((each) => ({ ...each, sure: true })) };

const SUMMARY: PayStubSummary = { id: 7, name: STUB.name, payDate: "2026-10-01", status: "review", applied: null, grossCents: 360000, netCents: 248620, toCheck: 1 };

const pdf = (name = "northwind-2026-10-15.pdf") => new File(["%PDF-1.7"], name, { type: "application/pdf" });

function withStub(stub: PayStub = STUB, overrides: Parameters<typeof fakeBackend>[0] = {}) {
  return fakeBackend({
    "GET /api/accounts": () => ({ status: 200, data: [CHECKING, RETIREMENT] }),
    "GET /api/income-sources": () => ({ status: 200, data: [SALARY] }),
    "GET /api/pay-stubs/7": () => ({ status: 200, data: stub }),
    ...overrides,
  });
}

describe("pay stub page", () => {
  it("shows every line under its part of the stub, with what it is tracked as and whether it adds up", async () => {
    withStub();
    renderApp("/income/1/stubs/7");

    expect(await screen.findByRole("heading", { name: /^Review pay stub/ })).toBeInTheDocument();
    expect(screen.getByText("Nothing changes until you save it.")).toBeInTheDocument();
    const banner = screen.getAllByRole("status").find((status) => status.textContent?.includes("Checks out"));
    expect(banner).toHaveTextContent("$3,600.00 − $1,113.80 = $2,486.20");
    expect(banner).toHaveTextContent("One line was hard to read, so please check it.");

    // Plain rows, as on the stub: nothing is a box until a line is opened.
    expect(screen.queryByRole("textbox", { name: "401(k) this stub" })).not.toBeInTheDocument();
    const earnings = screen.getByRole("region", { name: "Earnings" });
    expect(within(earnings).getByText("Earnings · part of gross pay")).toBeInTheDocument();
    const beforeTax = screen.getByRole("region", { name: "Before tax" });
    expect(within(beforeTax).getByText("Contribution → Retirement")).toBeInTheDocument();
    expect(within(beforeTax).getByText("Insurance, benefit or fee")).toBeInTheDocument();
    expect(within(beforeTax).getByText("$180.00")).toBeInTheDocument();
    expect(within(screen.getByRole("region", { name: "Taxes" })).getAllByRole("listitem")).toHaveLength(2);
    expect(within(screen.getByRole("region", { name: "Paid to you" })).getByText("Paycheck → Everyday Checking")).toBeInTheDocument();
    expect(within(screen.getByRole("region", { name: "From employer" })).getByText("Match → Retirement")).toBeInTheDocument();
    // The one line the reader was unsure of says so, and says why and what to do about it.
    const parking = within(screen.getByRole("region", { name: "After tax" })).getByRole("listitem");
    expect(parking).toHaveTextContent("Check");
    expect(parking).toHaveTextContent("Budget couldn't tell from the stub what this line is, so it made its best guess.");
    expect(within(parking).getByRole("button", { name: "Parking looks right" })).toBeInTheDocument();
    expect(within(beforeTax).queryByText("Check")).not.toBeInTheDocument();
    expect(screen.getByRole("img", { name: /northwind-2026-10-01\.pdf/ })).toHaveAttribute("src", "/api/pay-stubs/7/preview?page=1");
  });

  it("says in a plain sum what is off when too much is counted as taken out", async () => {
    // The net pay read a second time, as a deduction: everything is "taken out" and nothing is left.
    withStub({ ...STUB, totals: { ...STUB.totals, deductionsCents: 360000, differenceCents: -248620, balanced: false } });
    renderApp("/income/1/stubs/7");

    const banner = (await screen.findByText("These lines don't add up to your net pay yet.")).closest("div");
    expect(banner).toHaveTextContent("Gross pay $3,600.00 minus $3,600.00 taken out (before tax, taxes and after tax) leaves $0.00, but the stub says you were paid $2,486.20.");
    expect(banner).toHaveTextContent("So $2,486.20 too much is counted as taken out. Look for a line below that isn't really a deduction");
  });

  it("says when something is missing from what is taken out", async () => {
    withStub({ ...STUB, totals: { ...STUB.totals, deductionsCents: 102700, differenceCents: 8680, balanced: false } });
    renderApp("/income/1/stubs/7");

    const banner = (await screen.findByText("These lines don't add up to your net pay yet.")).closest("div");
    expect(banner).toHaveTextContent("So $86.80 is missing from what is taken out.");
  });

  it("enlarges the stub's preview in place", async () => {
    withStub();
    renderApp("/income/1/stubs/7");

    const page = await screen.findByRole("img", { name: /northwind-2026-10-01\.pdf/ });
    expect(screen.getByRole("button", { name: "Zoom out" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Zoom in" }));
    await userEvent.click(screen.getByRole("button", { name: "Zoom in" }));
    expect(page).toHaveStyle({ width: "200%" });

    await userEvent.click(screen.getByRole("button", { name: "Zoomed to 200%. Show the whole page" }));
    expect(page).not.toHaveStyle({ width: "200%" });
  });

  it("opens a line where it stands to change it, and sends every line back", async () => {
    const fetchMock = withStub(STUB, { "PUT /api/pay-stubs/7": () => ({ status: 200, data: STUB }) });
    renderApp("/income/1/stubs/7");

    await userEvent.click(await screen.findByRole("button", { name: "Edit Parking" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    const amount = screen.getByRole("textbox", { name: "Parking this stub" });
    expect(amount).toHaveValue("86.80");
    // Only the one line is open.
    expect(screen.queryByRole("textbox", { name: "Medical this stub" })).not.toBeInTheDocument();
    await userEvent.clear(amount);
    await userEvent.type(amount, "88.80{Enter}");

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/pay-stubs/7")).toHaveLength(1));
    const [sent] = bodiesSentTo(fetchMock, "PUT /api/pay-stubs/7") as [{ payDate: string; lines: StubLine[] }];
    expect(sent.payDate).toBe("2026-10-01");
    expect(sent.lines).toHaveLength(8);
    // Changing a line says it has been looked at; lines go back without their ids.
    expect(sent.lines[5]).toEqual({ section: "after_tax", tracksAs: "benefit", label: "Parking", amountCents: 8880, ytdCents: 164920, accountId: null, sure: true });

    await userEvent.click(screen.getByRole("button", { name: "Done" }));
    expect(screen.queryByRole("textbox", { name: "Parking this stub" })).not.toBeInTheDocument();
  });

  it("renames a line, changes what it tracks as and where it goes, and says a hard-to-read one looks right", async () => {
    const fetchMock = withStub(STUB, { "PUT /api/pay-stubs/7": () => ({ status: 200, data: STUB }) });
    renderApp("/income/1/stubs/7");

    await userEvent.click(await screen.findByRole("button", { name: "Edit Medical" }));
    const name = screen.getByRole("textbox", { name: "Name of Medical" });
    await userEvent.clear(name);
    await userEvent.type(name, "Medical and dental");
    await userEvent.tab();
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "What Medical tracks as" }), "pre_tax:contribution");
    await userEvent.click(screen.getByRole("button", { name: "Done" }));

    await userEvent.click(screen.getByRole("button", { name: "Edit 401(k)" }));
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Account 401(k) goes to" }), "");
    await userEvent.click(screen.getByRole("button", { name: "Done" }));

    await userEvent.click(screen.getByRole("button", { name: "Parking looks right" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/pay-stubs/7")).toHaveLength(4));
    const [renamed, retracked, unfiled, checked] = bodiesSentTo(fetchMock, "PUT /api/pay-stubs/7") as { lines: StubLine[] }[];
    expect(renamed?.lines[2]).toMatchObject({ label: "Medical and dental", amountCents: 9500 });
    expect(retracked?.lines[2]).toMatchObject({ section: "pre_tax", tracksAs: "contribution", accountId: null });
    expect(unfiled?.lines[1]).toMatchObject({ label: "401(k)", accountId: null });
    expect(checked?.lines[5]).toMatchObject({ label: "Parking", amountCents: 8680, sure: true });
  });

  it("only offers investment accounts for an employer's match", async () => {
    withStub();
    renderApp("/income/1/stubs/7");

    await userEvent.click(await screen.findByRole("button", { name: "Edit 401(k) match" }));
    const paidInto = screen.getByRole("combobox", { name: "Account 401(k) match is paid into" });
    expect(within(paidInto).getAllByRole("option").map((option) => option.textContent)).toEqual(["No account (only listed)", "Retirement"]);
    await userEvent.click(screen.getByRole("button", { name: "Done" }));

    // A tax or a benefit goes to no account of hers, so there is nothing to choose.
    await userEvent.click(screen.getByRole("button", { name: "Edit Medical" }));
    expect(screen.queryByRole("combobox", { name: /Account Medical/ })).not.toBeInTheDocument();
  });

  it("takes a line off the stub from its row, and adds one the reader missed, without a pop-up", async () => {
    const fetchMock = withStub(STUB, { "PUT /api/pay-stubs/7": () => ({ status: 200, data: STUB }) });
    renderApp("/income/1/stubs/7");

    await userEvent.click(await screen.findByRole("button", { name: "Remove Medical" }));
    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/pay-stubs/7")).toHaveLength(1));
    // The bin is its own button: it did not also open the row.
    expect(screen.queryByRole("textbox", { name: "Medical this stub" })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Add a line" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    const added = screen.getByRole("group", { name: "New line" });
    await userEvent.click(within(added).getByRole("button", { name: "Add line" }));
    expect(within(added).getByRole("alert")).toHaveTextContent("Please give the line a name, as it reads on the stub.");
    await userEvent.type(within(added).getByLabelText("Line on the stub"), "HSA");
    await userEvent.type(within(added).getByLabelText("This stub"), "50");
    await userEvent.selectOptions(within(added).getByRole("combobox", { name: "What the new line tracks as" }), "pre_tax:contribution");
    await userEvent.selectOptions(within(added).getByRole("combobox", { name: "Account the new line goes to" }), "Retirement");
    await userEvent.click(within(added).getByRole("button", { name: "Add line" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/pay-stubs/7")).toHaveLength(2));
    await waitFor(() => expect(screen.queryByRole("group", { name: "New line" })).not.toBeInTheDocument());
    const [removed, withNew] = bodiesSentTo(fetchMock, "PUT /api/pay-stubs/7") as { lines: StubLine[] }[];
    expect(removed?.lines.map((each) => each.label)).not.toContain("Medical");
    expect(removed?.lines).toHaveLength(7);
    expect(withNew?.lines.at(-1)).toEqual({ section: "pre_tax", tracksAs: "contribution", label: "HSA", amountCents: 5000, ytdCents: null, accountId: 2, sure: true });
  });

  it("says so beside the box when what was typed is not an amount or a name, and sends nothing", async () => {
    const fetchMock = withStub();
    renderApp("/income/1/stubs/7");

    await userEvent.click(await screen.findByRole("button", { name: "Edit Medical" }));
    const amount = screen.getByRole("textbox", { name: "Medical this stub" });
    await userEvent.clear(amount);
    await userEvent.type(amount, "ninety{Enter}");
    expect(screen.getByRole("alert")).toHaveTextContent("Please enter an amount like 1,250.00.");
    // Escape puts back what was there, and leaves the line open.
    await userEvent.type(amount, "{Escape}");
    expect(amount).toHaveValue("95.00");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();

    const name = screen.getByRole("textbox", { name: "Name of Medical" });
    await userEvent.clear(name);
    await userEvent.tab();
    expect(screen.getByRole("alert")).toHaveTextContent("A line needs a name.");
    expect(calls(fetchMock).some((call) => call.startsWith("PUT "))).toBe(false);
  });

  it("puts a line back and says so kindly when a change cannot be saved", async () => {
    withStub(STUB, { "PUT /api/pay-stubs/7": () => ({ status: 400, error: "That amount is more than a stub can hold." }) });
    renderApp("/income/1/stubs/7");

    await userEvent.click(await screen.findByRole("button", { name: "Edit Medical" }));
    const amount = screen.getByRole("textbox", { name: "Medical this stub" });
    await userEvent.clear(amount);
    await userEvent.type(amount, "99{Enter}");

    expect(await screen.findByRole("alert")).toHaveTextContent("That amount is more than a stub can hold.");
    expect(amount).toHaveValue("95.00");
  });

  it("asks what saving should change, choosing nothing for you", async () => {
    const fetchMock = withStub(STUB, { "POST /api/pay-stubs/7/save": () => ({ status: 200, data: SAVED, changed: true }) });
    renderApp("/income/1/stubs/7");

    await userEvent.click(await screen.findByRole("button", { name: "Save stub…" }));
    const dialog = screen.getByRole("dialog", { name: "Save this stub" });
    const choices = within(dialog).getAllByRole("radio");
    expect(choices).toHaveLength(5);
    choices.forEach((choice) => expect(choice).not.toBeChecked());
    expect(dialog).toHaveTextContent("From this stub: take-home $2,486.20, gross $3,600.00, with 3 deductions.");

    await userEvent.click(within(dialog).getByRole("button", { name: "Save stub" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Please choose what this stub should change.");
    expect(calls(fetchMock)).not.toContain("POST /api/pay-stubs/7/save");

    await userEvent.click(within(dialog).getByRole("radio", { name: /^My pay changed/ }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Save stub" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "POST /api/pay-stubs/7/save")).toEqual([{ apply: "pay_change" }]));
    // Back on the income's own page.
    expect(await screen.findByRole("heading", { name: "Northwind Health" })).toBeInTheDocument();
  });

  it("saves a bonus stub as a one-off paycheck with the name given", async () => {
    const fetchMock = withStub(STUB, { "POST /api/pay-stubs/7/save": () => ({ status: 200, data: { ...SAVED, applied: "one_off" } }) });
    renderApp("/income/1/stubs/7");

    await userEvent.click(await screen.findByRole("button", { name: "Save stub…" }));
    const dialog = screen.getByRole("dialog", { name: "Save this stub" });
    // The name is only asked for once a one-off is chosen.
    expect(within(dialog).queryByLabelText("Name (optional)")).not.toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("radio", { name: /^A one-off paycheck \(bonus\)/ }));
    expect(within(dialog).getByLabelText("Name (optional)")).toHaveAttribute("placeholder", "Bonus");
    await userEvent.type(within(dialog).getByLabelText("Name (optional)"), "  Q3 bonus ");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save stub" }));

    await waitFor(() =>
      expect(bodiesSentTo(fetchMock, "POST /api/pay-stubs/7/save")).toEqual([{ apply: "one_off", name: "Q3 bonus" }]),
    );
  });

  it("leaves a one-off's name to the backend when none is typed", async () => {
    const fetchMock = withStub(STUB, { "POST /api/pay-stubs/7/save": () => ({ status: 200, data: { ...SAVED, applied: "one_off" } }) });
    renderApp("/income/1/stubs/7");

    await userEvent.click(await screen.findByRole("button", { name: "Save stub…" }));
    const dialog = screen.getByRole("dialog", { name: "Save this stub" });
    await userEvent.click(within(dialog).getByRole("radio", { name: /^A one-off paycheck \(bonus\)/ }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Save stub" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "POST /api/pay-stubs/7/save")).toEqual([{ apply: "one_off" }]));
  });

  it("opens on just this paycheck when the stub was added from a paycheck's row", async () => {
    withStub();
    renderApp("/income/1/stubs/7?apply=paycheck");

    await userEvent.click(await screen.findByRole("button", { name: "Save stub…" }));
    expect(screen.getByRole("radio", { name: /Just this paycheck/ })).toBeChecked();
  });

  it("cannot be just one paycheck when none is near the stub's date", async () => {
    withStub({ ...STUB, paycheck: null });
    renderApp("/income/1/stubs/7?apply=paycheck");

    expect(await screen.findByText("No paycheck from Northwind Health is within a few days of this date.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Save stub…" }));
    const only = screen.getByRole("radio", { name: /Just this paycheck/ });
    expect(only).toBeDisabled();
    expect(only).not.toBeChecked();
  });

  it("asks for the pay date when the stub did not give one", async () => {
    const fetchMock = withStub({ ...STUB, payDate: null, paycheck: null }, { "PUT /api/pay-stubs/7": () => ({ status: 200, data: STUB }) });
    renderApp("/income/1/stubs/7");

    expect(await screen.findByText("We couldn't find the pay date on the stub. Please fill it in.")).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Paid on"), "2026-10-01");

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/pay-stubs/7")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "PUT /api/pay-stubs/7")[0]).toMatchObject({ payDate: "2026-10-01" });
  });

  it("discards a stub in review and goes back to the income", async () => {
    const fetchMock = withStub(STUB, { "DELETE /api/pay-stubs/7": () => ({ status: 200, data: { done: true }, changed: true }) });
    renderApp("/income/1/stubs/7");

    await userEvent.click(await screen.findByRole("button", { name: "Discard" }));

    await waitFor(() => expect(calls(fetchMock)).toContain("DELETE /api/pay-stubs/7"));
    expect(await screen.findByRole("heading", { name: "Northwind Health" })).toBeInTheDocument();
  });

  it("shows a saved stub as it was kept, with nothing to edit", async () => {
    withStub(SAVED);
    renderApp("/income/1/stubs/7");

    expect(await screen.findByRole("heading", { name: /^Pay stub/ })).toBeInTheDocument();
    expect(screen.getByText(/Saved · corrected the amounts\./)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Remove stub" })).toBeInTheDocument();
    // As plain text, with what each line was tracked as.
    expect(within(screen.getByRole("region", { name: "Before tax" })).getByText("Contribution → Retirement")).toBeInTheDocument();
    expect(within(screen.getByRole("region", { name: "Paid to you" })).getByText("Paycheck → Everyday Checking")).toBeInTheDocument();
    expect(within(screen.getByRole("region", { name: "From employer" })).getByText("Match → Retirement")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save stub…" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit Parking" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Remove Parking" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add a line" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Paid on")).not.toBeInTheDocument();
  });

  it("opens a saved stub again to be changed, and offers the same choice when it is saved once more", async () => {
    let current: PayStub = SAVED;
    const fetchMock = withStub(SAVED, {
      "GET /api/pay-stubs/7": () => ({ status: 200, data: current }),
      "POST /api/pay-stubs/7/reopen": () => {
        current = { ...SAVED, status: "review", applied: null };
        return { status: 200, data: current, changed: true };
      },
    });
    renderApp("/income/1/stubs/7");

    expect(await screen.findByText(/Choose Edit stub to change it; what it changed stays until you save it again./)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Edit stub" }));

    expect(await screen.findByRole("heading", { name: /^Review pay stub/ })).toBeInTheDocument();
    expect(calls(fetchMock)).toContain("POST /api/pay-stubs/7/reopen");
    // Every line can be changed again.
    expect(screen.getByRole("button", { name: "Edit Parking" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Remove Parking" })).toBeInTheDocument();
    // It corrected the amounts the first time, so that is where the save dialog starts.
    await userEvent.click(screen.getByRole("button", { name: "Save stub…" }));
    expect(screen.getByRole("radio", { name: /Correct Northwind Health's amounts/ })).toBeChecked();
  });

  it("says so when no pay lines could be found", async () => {
    withStub({ ...STUB, lines: [], toCheck: 0, wouldSet: null });
    renderApp("/income/1/stubs/7");

    expect(await screen.findByText("We couldn't find any pay lines in this PDF.")).toBeInTheDocument();
    expect(screen.getByText("No lines yet.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Save stub…" }));
    // Nothing to take a take-home amount from, so only keeping it is on offer.
    expect(screen.getByRole("radio", { name: /Correct Northwind Health's amounts/ })).toBeDisabled();
    expect(screen.getByRole("radio", { name: /Don't change anything/ })).toBeEnabled();
  });

  it("says so kindly when the stub is not there, or belongs to another income", async () => {
    withStub(STUB, { "GET /api/pay-stubs/9": () => ({ status: 404, error: "We couldn't find that." }) });
    const first = renderApp("/income/1/stubs/9");
    expect(await screen.findByRole("alert")).toHaveTextContent("We couldn't find that pay stub. It may have been removed.");
    first.unmount();

    renderApp("/income/2/stubs/7");
    expect(await screen.findByRole("alert")).toHaveTextContent("We couldn't find that pay stub. It may have been removed.");
  });
});

describe("pay stubs on an income source", () => {
  it("invites the first stub, and says where taxes and benefits will come from", async () => {
    withStub();
    renderApp("/income/1");

    const stubs = await screen.findByRole("region", { name: "Pay stubs" });
    expect(await within(stubs).findByText(/^No pay stubs yet\./)).toBeInTheDocument();
    expect(within(stubs).getByLabelText("Drop a pay stub here or browse")).toBeInTheDocument();
    expect(within(screen.getByRole("region", { name: "Taxes & benefits" })).getByText(/^These come from your pay stubs\./)).toBeInTheDocument();
  });

  it("lists the stubs, each opening its page, and the newest saved one's taxes and benefits", async () => {
    withStub(SAVED, {
      "GET /api/income-sources/1/pay-stubs": () => ({
        status: 200,
        data: [
          { ...SUMMARY, id: 8, payDate: "2026-10-15" },
          { ...SUMMARY, status: "saved", applied: "correct", toCheck: 0 },
        ],
      }),
    });
    renderApp("/income/1");

    const stubs = await screen.findByRole("region", { name: "Pay stubs" });
    const rows = await within(stubs).findAllByRole("link");
    expect(rows.map((row) => row.getAttribute("href"))).toEqual(["/income/1/stubs/8", "/income/1/stubs/7"]);
    expect(rows[0]).toHaveTextContent("To review · 1 line to check");
    expect(rows[1]).toHaveTextContent("Corrected the amounts");
    expect(rows[1]).toHaveTextContent("$2,486.20");

    const taxes = screen.getByRole("region", { name: "Taxes & benefits" });
    expect(await within(taxes).findByText("Federal income tax")).toBeInTheDocument();
    // Taxes and benefits only: the 401(k) is a contribution, followed with its account.
    expect(within(taxes).queryByText("401(k)")).not.toBeInTheDocument();
    const total = within(taxes).getByText("Each paycheck").closest("li");
    expect(total).toHaveTextContent("$933.80");
  });

  it("adds a stub and opens it to be looked over", async () => {
    const fetchMock = withStub(STUB, { "POST /api/income-sources/1/pay-stubs": () => ({ status: 201, data: STUB, changed: true }) });
    renderApp("/income/1");

    const stubs = await screen.findByRole("region", { name: "Pay stubs" });
    await userEvent.upload(within(stubs).getByLabelText("Drop a pay stub here or browse"), pdf());

    expect(await screen.findByRole("heading", { name: /^Review pay stub/ })).toBeInTheDocument();
    expect(calls(fetchMock)).toContain("POST /api/income-sources/1/pay-stubs");
  });

  it("turns away a file that is not a PDF without sending it", async () => {
    const fetchMock = withStub();
    renderApp("/income/1");

    const stubs = await screen.findByRole("region", { name: "Pay stubs" });
    await userEvent.upload(within(stubs).getByLabelText("Drop a pay stub here or browse"), new File(["photo"], "stub.jpg", { type: "image/jpeg" }), {
      applyAccept: false,
    });

    expect(within(stubs).getByRole("alert")).toHaveTextContent("A pay stub needs to be a PDF");
    expect(calls(fetchMock)).not.toContain("POST /api/income-sources/1/pay-stubs");
  });

  it("says what the backend says when a stub is a scan", async () => {
    const scan = "This PDF is a picture of a pay stub, and Budget can't read pictures yet. A PDF downloaded from your payroll site will work.";
    withStub(STUB, { "POST /api/income-sources/1/pay-stubs": () => ({ status: 422, error: scan }) });
    renderApp("/income/1");

    const stubs = await screen.findByRole("region", { name: "Pay stubs" });
    await userEvent.upload(within(stubs).getByLabelText("Drop a pay stub here or browse"), pdf("scan.pdf"));

    expect(await within(stubs).findByRole("alert")).toHaveTextContent(scan);
  });
});

describe("a new income source from a pay stub", () => {
  const READ: StubRead = { payDate: "2026-10-01", lines: LINES.map((each) => ({ ...each, id: null })), totals: STUB.totals };

  it("fills in the pay from the stub, then keeps the stub with the new source and opens it", async () => {
    const fetchMock = fakeBackend({
      "GET /api/accounts": () => ({ status: 200, data: [CHECKING, RETIREMENT] }),
      "POST /api/pay-stubs/read": () => ({ status: 200, data: READ }),
      "POST /api/income-sources": () => ({ status: 201, data: SALARY }),
      "POST /api/income-sources/1/pay-stubs": () => ({ status: 201, data: STUB, changed: true }),
      "GET /api/pay-stubs/7": () => ({ status: 200, data: STUB }),
    });
    renderApp("/income");
    await screen.findByText("No income yet.");

    await userEvent.click(screen.getByRole("button", { name: "+ Add income source" }));
    const dialog = screen.getByRole("dialog", { name: "Add an income source" });
    await userEvent.type(within(dialog).getByLabelText("Name"), "Northwind Health");
    await userEvent.type(within(dialog).getByRole("combobox", { name: "Deposited to" }), "every{Enter}");
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    await userEvent.upload(within(dialog).getByLabelText("Fill these in from a pay stub (PDF)"), pdf("northwind-2026-10-01.pdf"));

    await waitFor(() => expect(within(dialog).getByLabelText("Take-home per paycheck")).toHaveValue("2486.20"));
    expect(within(dialog).getByLabelText("Gross per paycheck (optional)")).toHaveValue("3600.00");
    expect(dialog).toHaveTextContent("Filled in from northwind-2026-10-01.pdf, read on this PC.");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add income source" }));

    expect(await screen.findByRole("heading", { name: /^Review pay stub/ })).toBeInTheDocument();
    expect(bodiesSentTo(fetchMock, "POST /api/income-sources")[0]).toMatchObject({
      netCents: 248620,
      grossCents: 360000,
      netIsEstimate: false,
      // What comes out before and after tax; taxes stay on the stub.
      deductions: [
        { name: "401(k)", amountCents: 18000, toAccountId: null },
        { name: "Medical", amountCents: 9500, toAccountId: null },
        { name: "Parking", amountCents: 8680, toAccountId: null },
      ],
    });
    expect(calls(fetchMock)).toContain("POST /api/income-sources/1/pay-stubs");
  });

  it("says the income was added when only its stub could not be kept", async () => {
    fakeBackend({
      "GET /api/accounts": () => ({ status: 200, data: [CHECKING, RETIREMENT] }),
      "POST /api/pay-stubs/read": () => ({ status: 200, data: READ }),
      "POST /api/income-sources": () => ({ status: 201, data: SALARY }),
      "POST /api/income-sources/1/pay-stubs": () => ({ status: 500, error: "Something went wrong on our side. Please try again." }),
    });
    renderApp("/income");
    await screen.findByText("No income yet.");

    await userEvent.click(screen.getByRole("button", { name: "+ Add income source" }));
    const dialog = screen.getByRole("dialog", { name: "Add an income source" });
    await userEvent.type(within(dialog).getByLabelText("Name"), "Northwind Health");
    await userEvent.type(within(dialog).getByRole("combobox", { name: "Deposited to" }), "every{Enter}");
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    await userEvent.upload(within(dialog).getByLabelText("Fill these in from a pay stub (PDF)"), pdf());
    await waitFor(() => expect(within(dialog).getByLabelText("Take-home per paycheck")).toHaveValue("2486.20"));
    await userEvent.click(within(dialog).getByRole("button", { name: "Add income source" }));

    expect(await within(dialog).findByText(/Northwind Health was added, but its pay stub could not be kept/)).toBeInTheDocument();
    expect(within(dialog).getByText("You can add the stub again from Northwind Health's page, under Pay stubs.")).toBeInTheDocument();
  });
});
