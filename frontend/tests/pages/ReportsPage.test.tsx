import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Account } from "../../src/api/accounts";
import type { Tag } from "../../src/api/categories";
import type { IncomeSource } from "../../src/api/income";
import {
  type IncomeReport,
  NEW_INCOME_REPORT,
  NEW_PROJECTED_REPORT,
  NEW_REPORT,
  type ProjectedReport,
  type Report,
  type ReportDefinition,
  type SavedReport,
} from "../../src/api/reports";
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
const OUT_OF_POCKET: Tag = { id: 1, name: "Medical – out of pocket", color: "#be185d", itemCount: 0, perYearCents: 0 };

const MEDICAL_DEFINITION: ReportDefinition = { ...NEW_REPORT, tagIds: [1] };
const MEDICAL: SavedReport = { id: 4, name: "Medical out-of-pocket 2026", definition: MEDICAL_DEFINITION };

const REPORT: Report = {
  kind: "spending",
  from: "2026-01-01",
  to: "2026-12-31",
  groupBy: "month",
  groups: [
    { key: "2026-01", label: "January 2026", color: null, count: 1, totalCents: 12000 },
    { key: "2026-02", label: "February 2026", color: null, count: 0, totalCents: 0 },
    { key: "2026-09", label: "September 2026", color: null, count: 2, totalCents: 10860 },
  ],
  rows: [
    {
      kind: "one_off",
      entryId: 7,
      itemId: null,
      scheduledDate: null,
      date: "2026-01-20",
      description: "Visit",
      payee: "Urgent care",
      categoryId: null,
      tagIds: [1],
      accountId: 1,
      amountCents: 12000,
      estimate: false,
      unpaid: false,
      reimbursement: "reimbursed",
      reimbursedCents: 12000,
      owedCents: 0,
      fileCount: 0,
    },
    {
      kind: "one_off",
      entryId: 9,
      itemId: null,
      scheduledDate: null,
      date: "2026-09-28",
      description: "Copay plus lab work",
      payee: "Valley Pediatrics",
      categoryId: null,
      tagIds: [1],
      accountId: 1,
      amountCents: 8500,
      estimate: false,
      unpaid: false,
      reimbursement: "submitted",
      reimbursedCents: 6000,
      owedCents: 2500,
      fileCount: 2,
    },
    {
      kind: "bill",
      entryId: null,
      itemId: 3,
      scheduledDate: "2026-12-05",
      date: "2026-12-05",
      description: "Health insurance",
      payee: "Health insurance",
      categoryId: null,
      tagIds: [1],
      accountId: 1,
      amountCents: 2360,
      estimate: true,
      unpaid: true,
      reimbursement: null,
      reimbursedCents: 0,
      owedCents: 0,
      fileCount: 0,
    },
  ],
  count: 3,
  totalCents: 22860,
  reimbursedCents: 18000,
  waitingCents: 2500,
  notSubmittedCents: 0,
  estimatedCents: 2360,
  hasReimbursements: true,
};

// Only what the screen reads of an income source: its id and its name.
const BRIGHTWATER = { id: 2, name: "Brightwater Health" } as IncomeSource;

const INCOME: IncomeReport = {
  kind: "income",
  from: "2026-01-01",
  to: "2026-12-31",
  groupBy: "month",
  groups: [
    {
      key: "2026-03",
      label: "March 2026",
      color: null,
      count: 4,
      grossCents: 720000,
      taxesCents: 176360,
      deductionsCents: 46400,
      takeHomeCents: 497240,
      oneOffsCents: 50000,
      interestCents: 0,
      matchCents: 10000,
      receivedCents: 547240,
    },
  ],
  rows: [
    {
      kind: "paycheck",
      date: "2026-03-01",
      description: "Brightwater Health paycheck",
      sourceId: 2,
      accountId: 1,
      amountCents: 248620,
      grossCents: 360000,
      taxesCents: 88180,
      deductionsCents: 23200,
      fromStub: true,
      estimate: false,
    },
    {
      kind: "match",
      date: "2026-03-01",
      description: "Employer match · Brightwater Health",
      sourceId: 2,
      accountId: 9,
      amountCents: 5000,
      grossCents: null,
      taxesCents: null,
      deductionsCents: null,
      fromStub: false,
      estimate: false,
    },
    {
      kind: "one_off",
      date: "2026-03-10",
      description: "Tax refund",
      sourceId: null,
      accountId: 1,
      amountCents: 50000,
      grossCents: null,
      taxesCents: null,
      deductionsCents: null,
      fromStub: false,
      estimate: false,
    },
    {
      kind: "paycheck",
      date: "2026-03-15",
      description: "Brightwater Health paycheck",
      sourceId: 2,
      accountId: 1,
      amountCents: 248620,
      grossCents: null,
      taxesCents: null,
      deductionsCents: null,
      fromStub: false,
      estimate: true,
    },
  ],
  count: 4,
  grossCents: 720000,
  taxesCents: 176360,
  deductionsCents: 46400,
  takeHomeCents: 497240,
  oneOffsCents: 50000,
  interestCents: 0,
  matchCents: 10000,
  receivedCents: 547240,
  estimatedCents: 248620,
  paychecksWithoutGross: 1,
};

/** As {@link withReports}, with an income source, and an income report answering whatever asks for one. */
function withIncome(overrides: Parameters<typeof fakeBackend>[0] = {}) {
  return withReports({
    "GET /api/income-sources": () => ({ status: 200, data: [BRIGHTWATER] }),
    "POST /api/reports/run": (body) => ({
      status: 200,
      data: (body as { definition: ReportDefinition }).definition.kind === "income" ? INCOME : REPORT,
    }),
    ...overrides,
  });
}

function withReports(overrides: Parameters<typeof fakeBackend>[0] = {}) {
  return fakeBackend({
    "GET /api/accounts": () => ({ status: 200, data: [CHECKING] }),
    "GET /api/tags": () => ({ status: 200, data: [OUT_OF_POCKET] }),
    "GET /api/payees": () => ({ status: 200, data: { payees: ["Urgent care", "Valley Pediatrics"], reimbursers: [] } }),
    "GET /api/reports": () => ({ status: 200, data: [MEDICAL] }),
    "POST /api/reports/run": () => ({ status: 200, data: REPORT }),
    ...overrides,
  });
}

/** Chooses the saved report from the picker at the top, once the list of them has arrived. */
async function openSaved() {
  await userEvent.click(await screen.findByRole("combobox", { name: "Report" }));
  await userEvent.click(await screen.findByRole("option", { name: "Medical out-of-pocket 2026" }));
}

/** The definition the screen last asked the backend to run. */
function lastRun(fetchMock: ReturnType<typeof fakeBackend>): ReportDefinition {
  const runs = bodiesSentTo(fetchMock, "POST /api/reports/run") as { definition: ReportDefinition }[];
  return runs[runs.length - 1]!.definition;
}

describe("Reports", () => {
  it("opens on a new report of this year's one-off spending by month", async () => {
    const fetchMock = withReports({ "GET /api/reports": () => ({ status: 200, data: [] }) });
    renderApp("/reports");

    expect(await screen.findByRole("heading", { level: 2, name: "New report" })).toBeInTheDocument();
    expect(await screen.findByText(/None saved yet/)).toBeInTheDocument();
    await waitFor(() => expect(bodiesSentTo(fetchMock, "POST /api/reports/run")).toHaveLength(1));
    expect(lastRun(fetchMock)).toEqual(NEW_REPORT);
    expect(screen.getByRole("combobox", { name: "Report" })).toHaveValue("New report");
    // Nothing chosen yet, so nothing to clear.
    expect(screen.queryByRole("button", { name: "Clear the form" })).not.toBeInTheDocument();

    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Group by" }), "payee");
    // Clearing asks first, and "Keep it" leaves everything as it was.
    await userEvent.click(screen.getByRole("button", { name: "Clear the form" }));
    await userEvent.click(screen.getByRole("button", { name: "Keep it" }));
    expect(screen.getByRole("combobox", { name: "Group by" })).toHaveValue("payee");

    await userEvent.click(screen.getByRole("button", { name: "Clear the form" }));
    await userEvent.click(within(screen.getByRole("group", { name: "Clear the form?" })).getByRole("button", { name: "Yes, clear it" }));
    expect(screen.getByRole("combobox", { name: "Group by" })).toHaveValue("month");
    expect(screen.queryByRole("button", { name: "Clear the form" })).not.toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: "One-off spending" })).toBeChecked();
    // The last thing included cannot be taken out: a report of nothing is not a report.
    expect(screen.getByRole("checkbox", { name: "One-off spending" })).toBeDisabled();
    expect(screen.getByText("Everything is counted. Add a filter to narrow it.")).toBeInTheDocument();
  });

  it("shows the totals, what has come back, the groups and every line", async () => {
    withReports();
    renderApp("/reports");

    expect((await screen.findByText("Total", { selector: "p" })).parentElement).toHaveTextContent("$228.60");
    expect(screen.getByText(/3 lines · Jan 1, 2026 – Dec 31, 2026/)).toBeInTheDocument();
    expect(screen.getByText("Reimbursed", { selector: "p" }).parentElement).toHaveTextContent("$180.00");
    expect(screen.getByText("Submitted, waiting").parentElement).toHaveTextContent("$25.00");
    expect(screen.getByText("Not submitted yet").parentElement).toHaveTextContent("$0.00");
    expect(screen.getByText("Estimated or still to come").parentElement).toHaveTextContent("$23.60");

    const table = within(screen.getByRole("region", { name: "Table" })).getByRole("table");
    expect(within(table).getByRole("row", { name: "September 2026 2 $108.60" })).toBeInTheDocument();
    expect(within(table).getByRole("row", { name: "February 2026 0 $0.00" })).toBeInTheDocument();
    expect(within(table).getByRole("row", { name: "Total 3 $228.60" })).toBeInTheDocument();

    const lines = within(screen.getByRole("region", { name: "Every line" })).getAllByRole("link");
    expect(lines).toHaveLength(3);
    // A one-off opens its expense page; a bill opens its recurring item.
    expect(lines[1]).toHaveAttribute("href", "/expenses/9");
    expect(lines[1]).toHaveTextContent("Valley Pediatrics");
    expect(lines[1]).toHaveTextContent("Submitted");
    expect(lines[1]).toHaveTextContent("2 files");
    expect(lines[2]).toHaveAttribute("href", "/recurring/3");
    expect(lines[2]).toHaveTextContent("(estimate)");
  });

  it("leaves out the reimbursement figures when nothing in the report is paid back", async () => {
    withReports({
      "POST /api/reports/run": () => ({ status: 200, data: { ...REPORT, hasReimbursements: false, estimatedCents: 0 } }),
    });
    renderApp("/reports");

    await screen.findByText("Total", { selector: "p" });
    expect(screen.queryByText("Submitted, waiting")).not.toBeInTheDocument();
    expect(screen.queryByText("Estimated or still to come")).not.toBeInTheDocument();
  });

  it("says so when nothing matches, with what to try", async () => {
    withReports({
      "POST /api/reports/run": () => ({
        status: 200,
        data: { ...REPORT, groups: [], rows: [], count: 0, totalCents: 0, hasReimbursements: false, estimatedCents: 0 },
      }),
    });
    renderApp("/reports");

    expect(await screen.findByText("Nothing was spent that matches this report.")).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Every line" })).not.toBeInTheDocument();
  });

  it("runs the report again as the builder changes", async () => {
    const fetchMock = withReports();
    renderApp("/reports");
    await screen.findByText("Total", { selector: "p" });

    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Group by" }), "category");
    await userEvent.click(screen.getByRole("checkbox", { name: "Bills & expenses" }));
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Show" }), "table");

    await waitFor(() => expect(lastRun(fetchMock)).toMatchObject({ groupBy: "category", include: ["one_offs", "bills"], show: "table" }));
    expect(screen.queryByRole("region", { name: /^By / })).not.toBeInTheDocument();
  });

  it("adds a filter from a picker and takes it away again", async () => {
    const fetchMock = withReports();
    renderApp("/reports");
    await screen.findByText("Total", { selector: "p" });

    await userEvent.click(screen.getByRole("combobox", { name: "Tag is" }));
    await userEvent.click(await screen.findByRole("option", { name: "Medical – out of pocket" }));
    await waitFor(() => expect(lastRun(fetchMock).tagIds).toEqual([1]));
    expect(screen.getByText("Tag is Medical – out of pocket")).toBeInTheDocument();

    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Add a filter by" }), "payee");
    await userEvent.click(screen.getByRole("combobox", { name: "Paid to is" }));
    await userEvent.click(await screen.findByRole("option", { name: "Valley Pediatrics" }));
    await waitFor(() => expect(lastRun(fetchMock).payees).toEqual(["Valley Pediatrics"]));

    await userEvent.click(screen.getByRole("button", { name: "Remove filter: Tag is Medical – out of pocket" }));
    await waitFor(() => expect(lastRun(fetchMock).tagIds).toEqual([]));
    expect(lastRun(fetchMock).payees).toEqual(["Valley Pediatrics"]);
  });

  it("waits for both custom dates, and says which way round they go", async () => {
    const fetchMock = withReports();
    renderApp("/reports");
    await screen.findByText("Total", { selector: "p" });
    const runsBefore = bodiesSentTo(fetchMock, "POST /api/reports/run").length;

    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Dates" }), "custom");
    expect(screen.getByText("Choose the first and the last day to see this report.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Export CSV" })).toBeDisabled();
    expect(screen.queryByText("Total", { selector: "p" })).not.toBeInTheDocument();

    await userEvent.type(screen.getByLabelText("From"), "2026-05-01");
    await userEvent.type(screen.getByLabelText("To"), "2026-04-01");
    expect(screen.getByText("The last day is before the first. Please check the two dates.")).toBeInTheDocument();
    expect(bodiesSentTo(fetchMock, "POST /api/reports/run")).toHaveLength(runsBefore);

    await userEvent.clear(screen.getByLabelText("To"));
    await userEvent.type(screen.getByLabelText("To"), "2026-06-30");
    await waitFor(() => expect(lastRun(fetchMock)).toMatchObject({ range: "custom", from: "2026-05-01", to: "2026-06-30" }));
  });

  it("opens a saved report, says when it has been changed, and saves the change", async () => {
    const changedReport = { ...MEDICAL, definition: { ...MEDICAL_DEFINITION, groupBy: "payee" } };
    const fetchMock = withReports({ "PUT /api/reports/4": () => ({ status: 200, data: changedReport, changed: true }) });
    renderApp("/reports");

    await openSaved();
    expect(screen.getByRole("heading", { level: 2, name: "Medical out-of-pocket 2026" })).toBeInTheDocument();
    await waitFor(() => expect(lastRun(fetchMock).tagIds).toEqual([1]));
    // A saved report opens on its figures: what it shows is one line, and the choices behind it are folded away.
    expect(screen.getByText("This year · one-off spending · Tag is Medical – out of pocket · by month")).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Group by" })).not.toBeInTheDocument();
    // As saved: nothing to save yet, so no Save is offered beside the line.
    expect(screen.queryByRole("button", { name: "Save changes" })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Change what it shows" }));
    // Save sits under the choices, and has nothing to do until one of them changes.
    expect(screen.getByRole("button", { name: "Save changes" })).toBeDisabled();
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Group by" }), "payee");
    await userEvent.click(screen.getByRole("button", { name: "Hide these choices" }));
    expect(screen.getByText("This year · one-off spending · Tag is Medical – out of pocket · by payee")).toBeInTheDocument();
    expect(screen.getByText("· changed, not saved")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/reports/4")).toHaveLength(1));
    expect(bodiesSentTo(fetchMock, "PUT /api/reports/4")[0]).toEqual({
      name: "Medical out-of-pocket 2026",
      definition: { ...MEDICAL_DEFINITION, groupBy: "payee" },
    });
  });

  it("goes from a saved report to a new one by choosing New report", async () => {
    const fetchMock = withReports();
    renderApp("/reports");
    await openSaved();
    await waitFor(() => expect(lastRun(fetchMock).tagIds).toEqual([1]));

    await userEvent.click(screen.getByRole("combobox", { name: "Report" }));
    await userEvent.click(await screen.findByRole("option", { name: "New report" }));

    expect(screen.getByRole("heading", { level: 2, name: "New report" })).toBeInTheDocument();
    await waitFor(() => expect(lastRun(fetchMock)).toEqual(NEW_REPORT));
    // A new report opens with its choices showing.
    expect(screen.getByRole("combobox", { name: "Group by" })).toBeInTheDocument();
  });

  it("drops a filter whose tag has been deleted since the report was saved", async () => {
    const stale: SavedReport = { ...MEDICAL, definition: { ...MEDICAL_DEFINITION, tagIds: [1, 99] } };
    const fetchMock = withReports({ "GET /api/reports": () => ({ status: 200, data: [stale] }) });
    renderApp("/reports");

    await openSaved();

    await waitFor(() => expect(lastRun(fetchMock).tagIds).toEqual([1]));
    expect(screen.getByText("· changed, not saved")).toBeInTheDocument();
  });

  it("asks for a name to save a new report under", async () => {
    const fetchMock = withReports({
      "POST /api/reports": () => ({ status: 201, data: { id: 5, name: "Everything this year", definition: NEW_REPORT }, changed: true }),
    });
    renderApp("/reports");
    await screen.findByText("Total", { selector: "p" });

    await userEvent.click(screen.getByRole("button", { name: "Save…" }));
    const dialog = screen.getByRole("dialog", { name: "Save this report" });
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Name" }), "Everything this year");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save report" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "POST /api/reports")).toEqual([{ name: "Everything this year", definition: NEW_REPORT }]);
  });

  it("shows why a name was refused and keeps the dialog open", async () => {
    withReports({
      "POST /api/reports": () => ({ status: 409, error: "There is already a report called that. Please choose another name." }),
    });
    renderApp("/reports");
    await screen.findByText("Total", { selector: "p" });

    await userEvent.click(screen.getByRole("button", { name: "Save…" }));
    const dialog = screen.getByRole("dialog", { name: "Save this report" });
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Name" }), "Medical out-of-pocket 2026");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save report" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("There is already a report called that.");
  });

  it("renames a saved report, and deletes one without asking twice", async () => {
    const fetchMock = withReports({
      "PUT /api/reports/4": () => ({ status: 200, data: { ...MEDICAL, name: "Medical" }, changed: true }),
      "DELETE /api/reports/4": () => ({ status: 200, data: { deleted: true }, changed: true }),
    });
    renderApp("/reports");
    await openSaved();

    await userEvent.click(screen.getByRole("button", { name: "Edit the name of Medical out-of-pocket 2026" }));
    let dialog = screen.getByRole("dialog", { name: "Rename Medical out-of-pocket 2026" });
    const name = within(dialog).getByRole("textbox", { name: "Name" });
    await userEvent.clear(name);
    await userEvent.type(name, "Medical");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save name" }));
    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/reports/4")).toEqual([{ name: "Medical", definition: MEDICAL_DEFINITION }]));

    await userEvent.click(await screen.findByRole("button", { name: "Edit the name of Medical out-of-pocket 2026" }));
    dialog = screen.getByRole("dialog", { name: "Rename Medical out-of-pocket 2026" });
    await userEvent.click(within(dialog).getByRole("button", { name: "Delete this report" }));

    await waitFor(() => expect(calls(fetchMock)).toContain("DELETE /api/reports/4"));
    // Back on a new report, with nothing more asked.
    expect(await screen.findByRole("heading", { level: 2, name: "New report" })).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  describe("exporting", () => {
    const createObjectURL = vi.fn(() => "blob:report");
    const revokeObjectURL = vi.fn();
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click");

    beforeEach(() => {
      // jsdom has neither; the file is handed over through them.
      URL.createObjectURL = createObjectURL;
      URL.revokeObjectURL = revokeObjectURL;
      click.mockImplementation(() => undefined);
    });
    afterEach(() => {
      createObjectURL.mockClear();
      click.mockReset();
    });

    it("says where an export was saved when Budget saved it on this PC", async () => {
      const fetchMock = withReports({
        "POST /api/reports/export/csv": () => ({
          status: 200,
          data: { saved: { fileName: "Medical out-of-pocket 2026 (2).csv", folder: "C:\\Users\\Amara\\Downloads" } },
        }),
      });
      renderApp("/reports");
      await openSaved();

      await userEvent.click(screen.getByRole("button", { name: "Export CSV" }));

      // The desktop window saves a file without a word, so the page says it: where, and under what name.
      expect(await screen.findByText("Saved in C:\\Users\\Amara\\Downloads as Medical out-of-pocket 2026 (2).csv.")).toBeInTheDocument();
      expect(bodiesSentTo(fetchMock, "POST /api/reports/export/csv")).toEqual([
        { name: "Medical out-of-pocket 2026", definition: MEDICAL_DEFINITION, save: true },
      ]);
      // Nothing was handed to the browser to download.
      expect(click).not.toHaveBeenCalled();
    });

    it("hands the file to the browser when it comes back as a file, as it does to a phone", async () => {
      const fetchMock = withReports({ "POST /api/reports/export/csv": () => ({ status: 200, file: "Date,Description" }) });
      renderApp("/reports");
      await openSaved();

      await userEvent.click(screen.getByRole("button", { name: "Export CSV" }));

      await waitFor(() => expect(click).toHaveBeenCalledTimes(1));
      expect(bodiesSentTo(fetchMock, "POST /api/reports/export/csv")).toEqual([
        { name: "Medical out-of-pocket 2026", definition: MEDICAL_DEFINITION, save: true },
      ]);
      expect(createObjectURL).toHaveBeenCalledTimes(1);
    });

    it("asks for the PDF with or without receipts", async () => {
      const fetchMock = withReports({ "POST /api/reports/export/pdf": () => ({ status: 200, file: "%PDF" }) });
      renderApp("/reports");
      await screen.findByText("Total", { selector: "p" });

      await userEvent.click(screen.getByRole("button", { name: "Export PDF with receipts" }));
      await waitFor(() => expect(click).toHaveBeenCalledTimes(1));
      await userEvent.click(screen.getByRole("button", { name: "Export PDF" }));
      await waitFor(() => expect(click).toHaveBeenCalledTimes(2));

      expect(bodiesSentTo(fetchMock, "POST /api/reports/export/pdf")).toEqual([
        { name: "Report", definition: NEW_REPORT, withReceipts: true, save: true },
        { name: "Report", definition: NEW_REPORT, withReceipts: false, save: true },
      ]);
    });

    it("says why an export could not be made", async () => {
      withReports({
        "POST /api/reports/export/pdf": () => ({ status: 413, error: "This report has 412 receipts, which is more than one PDF can hold (300)." }),
      });
      renderApp("/reports");
      await screen.findByText("Total", { selector: "p" });

      await userEvent.click(screen.getByRole("button", { name: "Export PDF with receipts" }));

      expect(await screen.findByRole("alert")).toHaveTextContent("more than one PDF can hold");
      expect(click).not.toHaveBeenCalled();
    });
  });

  it("says kindly when the report cannot be worked out", async () => {
    withReports({ "POST /api/reports/run": () => ({ status: 500, error: "Something went wrong on our side. Your data is safe — please try again." }) });
    renderApp("/reports");

    expect(await screen.findByRole("alert")).toHaveTextContent("Your data is safe");
  });

  describe("of income", () => {
    it("switches the builder to income, keeping the dates and starting the rest again", async () => {
      const fetchMock = withIncome();
      renderApp("/reports");
      await screen.findByText("Total", { selector: "p" });
      await userEvent.selectOptions(screen.getByRole("combobox", { name: "Dates" }), "last_year");

      await userEvent.click(screen.getByRole("radio", { name: "Income" }));

      await waitFor(() => expect(lastRun(fetchMock)).toEqual({ ...NEW_INCOME_REPORT, range: "last_year" }));
      for (const name of ["Paychecks", "One-off money in", "Employer match", "Interest"]) {
        expect(screen.getByRole("checkbox", { name })).toBeChecked();
      }
      expect(screen.queryByRole("checkbox", { name: "Bills & expenses" })).not.toBeInTheDocument();
      expect(screen.getByText(/Money that pays back an expense is never counted/)).toBeInTheDocument();
      // Income has no receipts to add to a PDF.
      expect(screen.queryByRole("button", { name: "Export PDF with receipts" })).not.toBeInTheDocument();
      // Only what income can be narrowed by is offered.
      const kinds = within(screen.getByRole("combobox", { name: "Add a filter by" })).getAllByRole("option");
      expect(kinds.map((option) => option.textContent)).toEqual(["Income source", "Landed in"]);

      await userEvent.selectOptions(screen.getByRole("combobox", { name: "Group by" }), "pay_period");
      await userEvent.click(screen.getByRole("checkbox", { name: "Interest" }));
      await userEvent.click(screen.getByRole("combobox", { name: "Income source is" }));
      await userEvent.click(await screen.findByRole("option", { name: "Brightwater Health" }));

      await waitFor(() =>
        expect(lastRun(fetchMock)).toEqual({
          ...NEW_INCOME_REPORT,
          range: "last_year",
          incomeGroupBy: "pay_period",
          incomeInclude: ["paychecks", "one_offs", "match"],
          sourceIds: [2],
        }),
      );
      expect(screen.getByRole("button", { name: "Remove filter: Income source is Brightwater Health" })).toBeInTheDocument();

      // Clearing an income report leaves a new income report, not a spending one.
      await userEvent.click(screen.getByRole("button", { name: "Clear the form" }));
      await userEvent.click(screen.getByRole("button", { name: "Yes, clear it" }));
      expect(screen.getByRole("radio", { name: "Income" })).toBeChecked();
      await waitFor(() => expect(lastRun(fetchMock)).toEqual(NEW_INCOME_REPORT));

      // And back: a spending report as new.
      await userEvent.click(screen.getByRole("radio", { name: "Spending" }));
      await waitFor(() => expect(lastRun(fetchMock)).toEqual(NEW_REPORT));
    });

    it("shows gross, what came out and what came home, with the match beside the total", async () => {
      withIncome();
      renderApp("/reports");
      await userEvent.click(await screen.findByRole("radio", { name: "Income" }));

      expect((await screen.findByText("Received", { selector: "p" })).parentElement).toHaveTextContent("$5,472.40");
      expect(screen.getByText("Gross pay").parentElement).toHaveTextContent("$7,200.00");
      expect(screen.getByText("Taxes", { selector: "p" }).parentElement).toHaveTextContent("$1,763.60");
      expect(screen.getByText("Other deductions", { selector: "p" }).parentElement).toHaveTextContent("$464.00");
      expect(screen.getByText("Employer match", { selector: "p" }).parentElement).toHaveTextContent("$100.00");
      expect(screen.getByText("Estimated or still to come").parentElement).toHaveTextContent("$2,486.20");
      expect(screen.getByText(/An employer's match is beside it, not in it\./)).toBeInTheDocument();
      expect(screen.getByText(/1 paycheck has no gross amount entered, so it is in the take-home/)).toBeInTheDocument();

      const table = within(screen.getByRole("region", { name: "Table" })).getByRole("table");
      expect(within(table).getAllByRole("columnheader").map((header) => header.textContent)).toEqual([
        "Month", "Gross", "Taxes", "Other deductions", "Take-home", "Other money in", "Employer match", "Received",
      ]);
      expect(
        within(table).getByRole("row", { name: "March 2026 $7,200.00 $1,763.60 $464.00 $4,972.40 $500.00 $100.00 $5,472.40" }),
      ).toBeInTheDocument();
      expect(within(table).getByRole("row", { name: /^Total \$7,200\.00 .* \$5,472\.40$/ })).toBeInTheDocument();

      const lines = within(within(screen.getByRole("region", { name: "Every line" })).getAllByRole("list")[0]!).getAllByRole("listitem");
      expect(lines).toHaveLength(4);
      expect(lines[0]).toHaveTextContent("Paycheck · landed in Everyday Checking · from a pay stub");
      expect(lines[0]).toHaveTextContent("Gross $3,600.00 · taxes $881.80 · other deductions $232.00");
      expect(lines[1]).toHaveTextContent("Employer match · into an account that was removed · not in the total");
      expect(lines[2]).toHaveTextContent("One-off money in · landed in Everyday Checking");
      expect(lines[3]).toHaveTextContent("$2,486.20 (estimate)");
    });

    it("leaves out the columns an income report has nothing for", async () => {
      withIncome({
        "POST /api/reports/run": () => ({
          status: 200,
          data: {
            ...INCOME,
            groups: [{ ...INCOME.groups[0]!, grossCents: 0, taxesCents: 0, deductionsCents: 0, takeHomeCents: 0, matchCents: 0, receivedCents: 50000 }],
            rows: [INCOME.rows[2]!],
            count: 1,
            grossCents: 0,
            taxesCents: 0,
            deductionsCents: 0,
            takeHomeCents: 0,
            matchCents: 0,
            receivedCents: 50000,
            estimatedCents: 0,
            paychecksWithoutGross: 0,
          },
        }),
      });
      renderApp("/reports");

      const table = within(await screen.findByRole("region", { name: "Table" })).getByRole("table");
      expect(within(table).getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["Month", "Other money in", "Received"]);
      expect(screen.queryByText("Employer match", { selector: "p" })).not.toBeInTheDocument();
    });

    it("says so when no income matches", async () => {
      withIncome({
        "POST /api/reports/run": () => ({
          status: 200,
          data: { ...INCOME, groups: [], rows: [], count: 0, receivedCents: 0, matchCents: 0, estimatedCents: 0, paychecksWithoutGross: 0 },
        }),
      });
      renderApp("/reports");

      expect(await screen.findByText("No income matches this report.")).toBeInTheDocument();
      expect(screen.queryByRole("region", { name: "Every line" })).not.toBeInTheDocument();
    });

    it("opens a saved income report folded, saying what it shows, and exports it", async () => {
      const saved: SavedReport = { id: 6, name: "Brightwater 2026", definition: { ...NEW_INCOME_REPORT, sourceIds: [2], incomeGroupBy: "source" } };
      const fetchMock = withIncome({
        "GET /api/reports": () => ({ status: 200, data: [saved] }),
        "POST /api/reports/export/csv": () => ({ status: 200, data: { saved: { fileName: "Brightwater 2026.csv", folder: "C:\\Users\\amara\\Downloads" } } }),
      });
      renderApp("/reports");

      await userEvent.click(await screen.findByRole("combobox", { name: "Report" }));
      await userEvent.click(await screen.findByRole("option", { name: "Brightwater 2026" }));

      expect(
        await screen.findByText("This year · income from paychecks, one-off money in, employer match, interest · Income source is Brightwater Health · by income source"),
      ).toBeInTheDocument();
      await userEvent.click(screen.getByRole("button", { name: "Export CSV" }));
      await waitFor(() =>
        expect(bodiesSentTo(fetchMock, "POST /api/reports/export/csv")).toEqual([{ name: "Brightwater 2026", definition: saved.definition, save: true }]),
      );
    });
  });
});

/** This year: an electric bill typed over in July, groceries as planned, and one paycheck that paid more. */
const PROJECTED: ProjectedReport = {
  kind: "projected_vs_actual",
  from: "2026-01-01",
  to: "2026-12-31",
  groupBy: "month",
  spending: {
    groups: [
      { key: "2026-07", label: "July 2026", color: null, count: 2, changed: 1, projectedCents: 44000, actualCents: 51200, differenceCents: 7200 },
      { key: "2026-08", label: "August 2026", color: null, count: 0, changed: 0, projectedCents: 0, actualCents: 0, differenceCents: 0 },
    ],
    count: 2,
    changed: 1,
    projectedCents: 44000,
    actualCents: 51200,
    differenceCents: 7200,
  },
  paychecks: {
    groups: [
      { key: "2026-08", label: "August 2026", color: null, count: 1, changed: 1, projectedCents: 248620, actualCents: 240000, differenceCents: -8620 },
    ],
    count: 1,
    changed: 1,
    projectedCents: 248620,
    actualCents: 240000,
    differenceCents: -8620,
  },
  rows: [
    {
      kind: "budget",
      itemId: 3,
      sourceId: null,
      scheduledDate: "2026-07-01",
      date: "2026-07-01",
      description: "Groceries",
      categoryId: null,
      accountId: 1,
      projectedCents: 30000,
      actualCents: 30000,
      differenceCents: 0,
      changed: false,
      toCome: false,
    },
    {
      kind: "bill",
      itemId: 2,
      sourceId: null,
      scheduledDate: "2026-07-16",
      date: "2026-07-16",
      description: "Electric",
      categoryId: null,
      accountId: 1,
      projectedCents: 14000,
      actualCents: 21200,
      differenceCents: 7200,
      changed: true,
      toCome: false,
    },
    {
      kind: "paycheck",
      itemId: null,
      sourceId: 2,
      scheduledDate: "2026-08-15",
      date: "2026-08-15",
      description: "Brightwater Health paycheck",
      categoryId: null,
      accountId: 1,
      projectedCents: 248620,
      actualCents: 240000,
      differenceCents: -8620,
      changed: true,
      toCome: false,
    },
  ],
};

describe("Projected vs. actual reports", () => {
  function withProjected(overrides: Parameters<typeof fakeBackend>[0] = {}) {
    return withReports({
      "GET /api/reports": () => ({ status: 200, data: [] }),
      "POST /api/reports/run": (body) => ({
        status: 200,
        data: (body as { definition: ReportDefinition }).definition.kind === "projected_vs_actual" ? PROJECTED : REPORT,
      }),
      ...overrides,
    });
  }

  it("is the third kind of report, with its own choices in place of the spending ones", async () => {
    const fetchMock = withProjected();
    renderApp("/dashboard/reports");

    await userEvent.click(await screen.findByRole("radio", { name: "Projected vs. actual" }));

    await waitFor(() => expect(lastRun(fetchMock)).toEqual(NEW_PROJECTED_REPORT));
    expect(screen.getByRole("checkbox", { name: "Bills & expenses" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Budgets" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Paychecks" })).toBeChecked();
    expect(screen.queryByRole("checkbox", { name: "One-off spending" })).not.toBeInTheDocument();
    // A comparison is read in a table: there is no chart to choose, and no receipts to add.
    expect(screen.queryByRole("combobox", { name: "Show" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Export PDF with receipts" })).not.toBeInTheDocument();
    expect(within(screen.getByRole("combobox", { name: "Group by" })).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "Month",
      "Year",
      "Category",
      "Item",
    ]);

    await userEvent.click(screen.getByRole("checkbox", { name: "Only what was typed over" }));
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Group by" }), "item");
    await waitFor(() => expect(lastRun(fetchMock)).toEqual({ ...NEW_PROJECTED_REPORT, changedOnly: true, compareGroupBy: "item" }));
  });

  it("sets spending and paychecks out apart, each with what was projected, what it was and the difference", async () => {
    withProjected();
    renderApp("/dashboard/reports");
    await userEvent.click(await screen.findByRole("radio", { name: "Projected vs. actual" }));

    const spending = await screen.findByRole("region", { name: "Spending" });
    expect(within(spending).getByText("Projected", { selector: "p" }).parentElement).toHaveTextContent("$440.00");
    expect(within(spending).getByText("Actual", { selector: "p" }).parentElement).toHaveTextContent("$512.00");
    expect(within(spending).getByText("Difference", { selector: "p" }).parentElement).toHaveTextContent("+$72.00");
    // Said in words as well as by its sign.
    expect(within(spending).getByText(/\$72\.00 more than projected · 1 of 2 amounts was typed over/)).toBeInTheDocument();
    expect(within(spending).getByRole("row", { name: "July 2026 1 of 2 $440.00 $512.00 +$72.00" })).toBeInTheDocument();
    expect(within(spending).getByRole("row", { name: "Total 1 of 2 $440.00 $512.00 +$72.00" })).toBeInTheDocument();

    const paychecks = screen.getByRole("region", { name: "Paychecks" });
    expect(within(paychecks).getByText(/\$86\.20 less than projected · 1 of 1 amount was typed over/)).toBeInTheDocument();
    expect(within(paychecks).getByRole("row", { name: "August 2026 1 of 1 $2,486.20 $2,400.00 -$86.20" })).toBeInTheDocument();
  });

  it("lists every line, opening a bill's or budget's item, and saying what was projected only where it differs", async () => {
    withProjected();
    renderApp("/dashboard/reports");
    await userEvent.click(await screen.findByRole("radio", { name: "Projected vs. actual" }));

    const lines = within(await screen.findByRole("region", { name: "Every line" })).getAllByRole("listitem");
    expect(lines).toHaveLength(3);
    expect(lines[0]).toHaveTextContent("GroceriesBudget · paid from Everyday CheckingAs projected$300.00");
    expect(within(lines[1]!).getByRole("link")).toHaveAttribute("href", "/recurring/2");
    expect(lines[1]).toHaveTextContent("Projected $140.00 · +$72.00$212.00");
    // A paycheck has no page of its own to open.
    expect(within(lines[2]!).queryByRole("link")).not.toBeInTheDocument();
    expect(lines[2]).toHaveTextContent("Paycheck · landed in Everyday CheckingProjected $2,486.20 · -$86.20$2,400.00");
  });

  it("says so when nothing matches", async () => {
    withProjected({
      "POST /api/reports/run": () => ({
        status: 200,
        data: {
          ...PROJECTED,
          rows: [],
          spending: { ...PROJECTED.spending, groups: [], count: 0, changed: 0 },
          paychecks: { ...PROJECTED.paychecks, groups: [], count: 0, changed: 0 },
        },
      }),
    });
    renderApp("/dashboard/reports");
    await userEvent.click(await screen.findByRole("radio", { name: "Projected vs. actual" }));

    expect(await screen.findByText("Nothing matches this report.")).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Every line" })).not.toBeInTheDocument();
  });

  it("opens a saved one folded, saying what it shows", async () => {
    const saved: SavedReport = { id: 9, name: "What changed", definition: { ...NEW_PROJECTED_REPORT, changedOnly: true, compareGroupBy: "item" } };
    withProjected({ "GET /api/reports": () => ({ status: 200, data: [saved] }) });
    renderApp("/dashboard/reports");

    await userEvent.click(await screen.findByRole("combobox", { name: "Report" }));
    await userEvent.click(await screen.findByRole("option", { name: "What changed" }));

    expect(
      await screen.findByText("This year · projected vs. actual for bills & expenses, budgets, paychecks, only what was typed over · by item"),
    ).toBeInTheDocument();
  });
});
