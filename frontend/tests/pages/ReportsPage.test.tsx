import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Account } from "../../src/api/accounts";
import type { Tag } from "../../src/api/categories";
import { NEW_REPORT, type Report, type ReportDefinition, type SavedReport } from "../../src/api/reports";
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
});
