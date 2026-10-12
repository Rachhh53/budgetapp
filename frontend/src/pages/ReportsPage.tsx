import { FileChartColumn, Paperclip, X } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import { Link } from "react-router";
import { type Account, useAccounts } from "../api/accounts";
import { type Category, type Tag, useCategories, useTags } from "../api/categories";
import { savedMessage } from "../api/client";
import { REIMBURSEMENT_LABELS, REIMBURSEMENT_STATUSES, type ReimbursementStatus, useNames } from "../api/entries";
import {
  NEW_REPORT,
  REPORT_GROUP_LABELS,
  REPORT_GROUPS,
  REPORT_INCLUDE_LABELS,
  REPORT_INCLUDES,
  REPORT_RANGE_LABELS,
  REPORT_RANGES,
  REPORT_SHOW_LABELS,
  REPORT_SHOWS,
  type Report,
  type ReportDefinition,
  type ReportGroupBy,
  type ReportRange,
  type ReportRow,
  type ReportShow,
  type SavedReport,
  useDeleteReport,
  useExportReport,
  useReport,
  useSavedReports,
  useSaveReport,
} from "../api/reports";
import { Amount, EstimateKey } from "../components/Amount";
import { Card } from "../components/Card";
import { categoryLabel } from "../components/categories/LabelFields";
import { EditButton } from "../components/EditButton";
import { ReimbursementBadge } from "../components/expenses/ReimbursementBadge";
import { Modal } from "../components/Modal";
import { Picker } from "../components/Picker";
import { ReportChart } from "../components/reports/ReportChart";
import { BUTTON_DANGER_OUTLINE, BUTTON_PRIMARY, BUTTON_SECONDARY, INPUT, LABEL, PAGE_TITLE } from "../components/ui";
import { formatDate } from "../lib/dates";
import { formatCents } from "../lib/money";

/** The picker's choice for a report not saved yet. Not a number, so it can never be a saved report's id. */
const NEW = "new";

/** A report with a great many lines lists the first of them; the exports always have every one. */
const MOST_LINES_SHOWN = 200;

const FILTER_KINDS = ["tag", "category", "account", "payee", "reimbursement"] as const;
type FilterKind = (typeof FILTER_KINDS)[number];

const FILTER_LABELS: Record<FilterKind, string> = {
  tag: "Tag",
  category: "Category",
  account: "Paid from",
  payee: "Paid to",
  reimbursement: "Reimbursement",
};

/** Everything a filter can be chosen from, and the names the chosen ones go by. */
interface Lists {
  tags: readonly Tag[];
  categories: readonly Category[];
  accounts: readonly Account[];
  payees: readonly string[];
}

/** One filter in force, as its chip says it: "Tag is Medical". */
interface Chip {
  kind: FilterKind;
  /** The tag's, category's or account's id, the payee's name, or the status. */
  value: string;
  label: string;
}

/**
 * Reports (design/reports.png): the ones saved, and a builder for what a report shows — its
 * dates, what it counts, its filters, how it is grouped. The figures follow the builder as it
 * changes; nothing is kept until Save. A report can be taken away as a CSV or a PDF, and the
 * PDF can carry every receipt.
 *
 * The saved ones are a picker at the top, not a column of their own (Rachel, 2026-10-12: beside
 * the app's own side bar the column took a large part of the screen), and a saved report opens
 * with its builder folded to one line, so its figures are what the screen shows first.
 */
export function ReportsPage() {
  const saved = useSavedReports();
  const tags = useTags();
  const categories = useCategories();
  const accounts = useAccounts();
  const names = useNames();
  const lists: Lists = {
    tags: tags.data ?? [],
    categories: categories.data ?? [],
    accounts: accounts.data ?? [],
    payees: names.data?.payees ?? [],
  };

  // The saved report on screen; `null` for one not saved yet.
  const [openId, setOpenId] = useState<number | null>(null);
  const [definition, setDefinition] = useState<ReportDefinition>(NEW_REPORT);
  const [naming, setNaming] = useState<"save" | "rename" | null>(null);
  // Whether the builder is unfolded: always for a new report, on request for a saved one.
  const [building, setBuilding] = useState(true);
  const open = saved.data?.find((report) => report.id === openId) ?? null;
  const changed = open === null || JSON.stringify(open.definition) !== JSON.stringify(definition);

  // A new report exactly as the screen opens on it: nothing in it to clear.
  const untouched = open === null && JSON.stringify(definition) === JSON.stringify(NEW_REPORT);
  // "Clear the form" asks once before it throws away what was chosen (Rachel, 2026-10-12).
  const [clearing, setClearing] = useState(false);

  const datesProblem = problemWithDates(definition);
  const report = useReport(datesProblem === null ? definition : null);
  const save = useSaveReport();
  const exporting = useExportReport();
  const name = open?.name ?? "New report";

  const show = (next: SavedReport | null) => {
    setOpenId(next?.id ?? null);
    setBuilding(next === null);
    setClearing(false);
    // A tag, category or account deleted since the report was saved is dropped from its filters.
    setDefinition(next === null ? NEW_REPORT : withoutMissing(next.definition, lists));
    save.reset();
    exporting.reset();
  };

  const exportAs = (format: "csv" | "pdf", withReceipts = false) =>
    exporting.mutate({ format, name: open?.name ?? "Report", definition, withReceipts });

  const saveButton = (
    <button
      type="button"
      disabled={datesProblem !== null || save.isPending || (open !== null && !changed)}
      onClick={() => (open ? save.mutate({ id: open.id, name: open.name, definition }) : setNaming("save"))}
      className={BUTTON_PRIMARY}
    >
      {save.isPending ? "Saving…" : open ? "Save changes" : "Save…"}
    </button>
  );

  return (
    <>
      <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between md:mb-6">
        <div>
          <p className="text-sm font-semibold tracking-wide text-muted uppercase">Your reports</p>
          <h1 className={PAGE_TITLE}>Reports</h1>
        </div>
      </div>

      <div className="space-y-4">
        <div className="min-w-0 space-y-4">
          <Card>
            <div className="flex flex-col gap-3">
              <h2 className="sr-only">{name}</h2>
              <div className="flex min-w-0 flex-wrap items-end gap-2">
                <div className="min-w-0 flex-1 sm:max-w-md">
                  <Picker
                    label="Report"
                    noun="report"
                    placeholder="Choose a report"
                    // "New report" is one of the choices, so there is always a way back to an empty one.
                    options={[
                      { id: NEW, label: "New report" },
                      ...(saved.data ?? []).map((report) => ({ id: String(report.id), label: report.name })),
                    ]}
                    value={openId === null ? NEW : String(openId)}
                    onChange={(chosen) => {
                      const next = saved.data?.find((report) => String(report.id) === chosen) ?? null;
                      // Choosing "New report" while on one changes nothing: clearing it is the button below, which asks first.
                      if (next !== null || open !== null) {
                        show(next);
                      }
                    }}
                  />
                </div>
                {open && (
                  <span className="flex min-h-11 items-center">
                    <EditButton name={`the name of ${open.name}`} onClick={() => setNaming("rename")} />
                  </span>
                )}
                {open && changed && <span className="flex min-h-11 items-center text-sm text-muted">· changed, not saved</span>}
              </div>
              {saved.isPending && <p className="text-muted">Loading your reports…</p>}
              {saved.error && (
                <p role="alert" className="text-danger">
                  {saved.error.message}
                </p>
              )}
              {saved.data?.length === 0 && (
                <p className="text-muted">None saved yet. Build one here, press Save, and it waits in this list for next time.</p>
              )}
              <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap">
                <button type="button" disabled={datesProblem !== null || exporting.isPending} onClick={() => exportAs("csv")} className={BUTTON_SECONDARY}>
                  Export CSV
                </button>
                <button type="button" disabled={datesProblem !== null || exporting.isPending} onClick={() => exportAs("pdf")} className={BUTTON_SECONDARY}>
                  Export PDF
                </button>
                <button
                  type="button"
                  disabled={datesProblem !== null || exporting.isPending}
                  onClick={() => exportAs("pdf", true)}
                  className={BUTTON_SECONDARY}
                >
                  Export PDF with receipts
                </button>
              </div>
            </div>
            {exporting.isPending && (
              <p role="status" className="mt-2 text-muted">
                Putting the file together…
              </p>
            )}
            {exporting.data && (
              <p role="status" className="mt-2 font-medium wrap-anywhere">
                {savedMessage(exporting.data)}
              </p>
            )}
            {exporting.error && (
              <p role="alert" className="mt-2 text-danger">
                {exporting.error.message}
              </p>
            )}

            {/* Dates that cannot be run yet are only put right in the builder, so it is never folded over them. */}
            {building || datesProblem !== null ? (
              <>
                <Builder definition={definition} onChange={setDefinition} lists={lists} datesProblem={datesProblem} />
                {/* Save sits under the choices it saves, where a form's Save always is. */}
                <div className="mt-4 flex flex-col gap-2 sm:flex-row sm:flex-wrap">
                  {saveButton}
                  {open && datesProblem === null && (
                    <button type="button" onClick={() => setBuilding(false)} className={BUTTON_SECONDARY}>
                      Hide these choices
                    </button>
                  )}
                  {open === null && !untouched && !clearing && (
                    <button type="button" onClick={() => setClearing(true)} className={BUTTON_SECONDARY}>
                      Clear the form
                    </button>
                  )}
                </div>
                {open === null && !untouched && clearing && (
                  <div role="group" aria-label="Clear the form?" className="mt-4 flex flex-col gap-2 sm:flex-row sm:items-center">
                    <p className="font-medium sm:mr-2">Clear everything chosen here and start again?</p>
                    <button type="button" onClick={() => show(null)} className={BUTTON_DANGER_OUTLINE}>
                      Yes, clear it
                    </button>
                    <button type="button" data-autofocus onClick={() => setClearing(false)} className={BUTTON_SECONDARY}>
                      Keep it
                    </button>
                  </div>
                )}
              </>
            ) : (
              <div className="mt-4 flex flex-col gap-2 border-t border-line pt-4 sm:flex-row sm:items-center sm:justify-between">
                <p>
                  <span className="text-muted">Shows </span>
                  {summaryOf(definition, lists)}
                </p>
                <div className="flex shrink-0 flex-col gap-2 sm:flex-row">
                  {/* Folded away there is only something to save when the report differs from what was saved. */}
                  {changed && saveButton}
                  <button type="button" onClick={() => setBuilding(true)} className={BUTTON_SECONDARY}>
                    Change what it shows
                  </button>
                </div>
              </div>
            )}
            {save.error && (
              <p role="alert" className="mt-2 text-danger">
                {save.error.message}
              </p>
            )}
          </Card>

          {datesProblem === null && report.isPending && <p className="text-muted">Working out the report…</p>}
          {report.isError && (
            <Card>
              <p role="alert" className="text-danger">
                {report.error.message}
              </p>
            </Card>
          )}
          {datesProblem === null && report.data && <Results report={report.data} show={definition.show} lists={lists} />}
        </div>
      </div>

      {naming && (
        <NameDialog
          report={naming === "rename" ? open : null}
          definition={naming === "rename" && open ? open.definition : definition}
          onSaved={(report) => setOpenId(report.id)}
          onDeleted={() => show(null)}
          onClose={() => setNaming(null)}
        />
      )}
    </>
  );
}

/** Custom dates need both ends, the right way round; anything else is always ready to run. */
function problemWithDates(definition: ReportDefinition): string | null {
  if (definition.range !== "custom") {
    return null;
  }
  if (definition.from === null || definition.to === null) {
    return "Choose the first and the last day to see this report.";
  }
  return definition.to < definition.from ? "The last day is before the first. Please check the two dates." : null;
}

/** `definition` without the filters whose tag, category or account is no longer there. */
function withoutMissing(definition: ReportDefinition, lists: Lists): ReportDefinition {
  return {
    ...definition,
    tagIds: definition.tagIds.filter((id) => lists.tags.some((tag) => tag.id === id)),
    categoryIds: definition.categoryIds.filter((id) => lists.categories.some((category) => category.id === id)),
    accountIds: definition.accountIds.filter((id) => lists.accounts.some((account) => account.id === id)),
  };
}

/** What a report shows, in a line, for a saved one whose builder is folded away: "This year · one-off spending · Tag is Medical · by month". */
function summaryOf(definition: ReportDefinition, lists: Lists): string {
  const dates =
    definition.range === "custom" && definition.from !== null && definition.to !== null
      ? `${formatDate(definition.from)} – ${formatDate(definition.to)}`
      : REPORT_RANGE_LABELS[definition.range];
  return [
    dates,
    definition.include.map((include) => REPORT_INCLUDE_LABELS[include].toLowerCase()).join(", "),
    ...chipsOf(definition, lists).map((chip) => `${FILTER_LABELS[chip.kind]} is ${chip.label}`),
    `by ${REPORT_GROUP_LABELS[definition.groupBy].toLowerCase()}`,
  ].join(" · ");
}

/** The builder: every choice is a dropdown, a checkbox or a picker (spec §1), and each change shows at once. */
function Builder({
  definition,
  onChange,
  lists,
  datesProblem,
}: {
  definition: ReportDefinition;
  onChange: (definition: ReportDefinition) => void;
  lists: Lists;
  datesProblem: string | null;
}) {
  const id = useId();
  const set = (change: Partial<ReportDefinition>) => onChange({ ...definition, ...change });
  const chips = chipsOf(definition, lists);

  return (
    <div className="mt-4 space-y-4 border-t border-line pt-4">
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <div>
          <label htmlFor={`${id}-range`} className={LABEL}>
            Dates
          </label>
          <select
            id={`${id}-range`}
            value={definition.range}
            onChange={(event) => {
              const range = event.target.value as ReportRange;
              set({ range, from: range === "custom" ? definition.from : null, to: range === "custom" ? definition.to : null });
            }}
            className={INPUT}
          >
            {REPORT_RANGES.map((range) => (
              <option key={range} value={range}>
                {REPORT_RANGE_LABELS[range]}
              </option>
            ))}
          </select>
        </div>
        {definition.range === "custom" && (
          <>
            <div>
              <label htmlFor={`${id}-from`} className={LABEL}>
                From
              </label>
              <input
                id={`${id}-from`}
                type="date"
                value={definition.from ?? ""}
                onChange={(event) => set({ from: event.target.value === "" ? null : event.target.value })}
                className={INPUT}
              />
            </div>
            <div>
              <label htmlFor={`${id}-to`} className={LABEL}>
                To
              </label>
              <input
                id={`${id}-to`}
                type="date"
                value={definition.to ?? ""}
                onChange={(event) => set({ to: event.target.value === "" ? null : event.target.value })}
                className={INPUT}
              />
            </div>
          </>
        )}
        <div>
          <label htmlFor={`${id}-group`} className={LABEL}>
            Group by
          </label>
          <select id={`${id}-group`} value={definition.groupBy} onChange={(event) => set({ groupBy: event.target.value as ReportGroupBy })} className={INPUT}>
            {REPORT_GROUPS.map((group) => (
              <option key={group} value={group}>
                {REPORT_GROUP_LABELS[group]}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor={`${id}-show`} className={LABEL}>
            Show
          </label>
          <select id={`${id}-show`} value={definition.show} onChange={(event) => set({ show: event.target.value as ReportShow })} className={INPUT}>
            {REPORT_SHOWS.map((show) => (
              <option key={show} value={show}>
                {REPORT_SHOW_LABELS[show]}
              </option>
            ))}
          </select>
        </div>
      </div>
      {datesProblem && (
        <p role="status" className="text-muted">
          {datesProblem}
        </p>
      )}

      <fieldset>
        <legend className={LABEL}>Include</legend>
        <div className="flex flex-col gap-x-6 sm:flex-row sm:flex-wrap">
          {REPORT_INCLUDES.map((include) => {
            const on = definition.include.includes(include);
            return (
              <label key={include} className="flex min-h-11 items-center gap-3">
                <input
                  type="checkbox"
                  checked={on}
                  // A report of nothing is not a report: the last one ticked stays ticked.
                  disabled={on && definition.include.length === 1}
                  onChange={() =>
                    set({ include: REPORT_INCLUDES.filter((other) => (other === include ? !on : definition.include.includes(other))) })
                  }
                  className="size-5 accent-(--accent)"
                />
                <span>{REPORT_INCLUDE_LABELS[include]}</span>
              </label>
            );
          })}
        </div>
        <p className="text-sm text-muted">Transfers and card payments are never counted: they move money that was counted when it was spent.</p>
      </fieldset>

      <div>
        <p className={LABEL}>Only where</p>
        {chips.length === 0 ? (
          <p className="text-muted">Everything is counted. Add a filter to narrow it.</p>
        ) : (
          <ul className="flex flex-wrap gap-2">
            {chips.map((chip) => (
              <li key={`${chip.kind}-${chip.value}`} className="flex items-center gap-1 rounded-full border border-line bg-bg py-1 pr-1 pl-3 text-sm font-semibold">
                <span className="wrap-break-word">
                  {FILTER_LABELS[chip.kind]} is {chip.label}
                </span>
                <button
                  type="button"
                  aria-label={`Remove filter: ${FILTER_LABELS[chip.kind]} is ${chip.label}`}
                  onClick={() => onChange(withoutChip(definition, chip))}
                  className="flex size-8 shrink-0 items-center justify-center rounded-full"
                >
                  <X aria-hidden className="size-4" />
                </button>
              </li>
            ))}
          </ul>
        )}
        <AddFilter definition={definition} onChange={onChange} lists={lists} />
      </div>
    </div>
  );
}

/** "+ Filter": what to filter by, then which one. Choosing the second adds the filter. */
function AddFilter({ definition, onChange, lists }: { definition: ReportDefinition; onChange: (definition: ReportDefinition) => void; lists: Lists }) {
  const id = useId();
  const [kind, setKind] = useState<FilterKind>("tag");
  const inForce = chipsOf(definition, lists).filter((chip) => chip.kind === kind);
  const options = choices(kind, lists).filter((option) => !inForce.some((chip) => chip.value === option.id));

  return (
    <div className="mt-3 grid grid-cols-1 gap-4 sm:grid-cols-[12rem_minmax(0,1fr)] xl:max-w-2xl">
      <div>
        <label htmlFor={`${id}-kind`} className={LABEL}>
          Add a filter by
        </label>
        <select id={`${id}-kind`} value={kind} onChange={(event) => setKind(event.target.value as FilterKind)} className={INPUT}>
          {FILTER_KINDS.map((option) => (
            <option key={option} value={option}>
              {FILTER_LABELS[option]}
            </option>
          ))}
        </select>
      </div>
      <Picker
        // A fresh picker for each kind, so what was typed for one does not carry to the next.
        key={kind}
        label={`${FILTER_LABELS[kind]} is`}
        noun={FILTER_LABELS[kind].toLowerCase()}
        placeholder={options.length === 0 ? "Nothing more to choose" : "Type to search"}
        options={options}
        value={null}
        onChange={(value) => value !== null && onChange(withChip(definition, kind, value))}
      />
    </div>
  );
}

function choices(kind: FilterKind, lists: Lists): { id: string; label: string; color?: string }[] {
  switch (kind) {
    case "tag":
      return lists.tags.map((tag) => ({ id: String(tag.id), label: tag.name, color: tag.color }));
    case "category":
      return lists.categories.map((category) => ({
        id: String(category.id),
        label: categoryLabel(category, lists.categories),
        color: category.color,
      }));
    case "account":
      return lists.accounts.map((account) => ({ id: String(account.id), label: account.name, color: account.color }));
    case "payee":
      return lists.payees.map((payee) => ({ id: payee, label: payee }));
    case "reimbursement":
      return REIMBURSEMENT_STATUSES.map((status) => ({ id: status, label: REIMBURSEMENT_LABELS[status] }));
  }
}

function chipsOf(definition: ReportDefinition, lists: Lists): Chip[] {
  const named = (kind: FilterKind, value: string): Chip => ({
    kind,
    value,
    label: choices(kind, lists).find((option) => option.id === value)?.label ?? value,
  });
  return [
    ...definition.tagIds.map((id) => named("tag", String(id))),
    ...definition.categoryIds.map((id) => named("category", String(id))),
    ...definition.accountIds.map((id) => named("account", String(id))),
    ...definition.payees.map((payee) => named("payee", payee)),
    ...definition.reimbursement.map((status) => named("reimbursement", status)),
  ];
}

function withChip(definition: ReportDefinition, kind: FilterKind, value: string): ReportDefinition {
  switch (kind) {
    case "tag":
      return { ...definition, tagIds: [...definition.tagIds, Number(value)] };
    case "category":
      return { ...definition, categoryIds: [...definition.categoryIds, Number(value)] };
    case "account":
      return { ...definition, accountIds: [...definition.accountIds, Number(value)] };
    case "payee":
      return { ...definition, payees: [...definition.payees, value] };
    case "reimbursement":
      return { ...definition, reimbursement: [...definition.reimbursement, value as ReimbursementStatus] };
  }
}

function withoutChip(definition: ReportDefinition, chip: Chip): ReportDefinition {
  switch (chip.kind) {
    case "tag":
      return { ...definition, tagIds: definition.tagIds.filter((id) => String(id) !== chip.value) };
    case "category":
      return { ...definition, categoryIds: definition.categoryIds.filter((id) => String(id) !== chip.value) };
    case "account":
      return { ...definition, accountIds: definition.accountIds.filter((id) => String(id) !== chip.value) };
    case "payee":
      return { ...definition, payees: definition.payees.filter((payee) => payee !== chip.value) };
    case "reimbursement":
      return { ...definition, reimbursement: definition.reimbursement.filter((status) => status !== chip.value) };
  }
}

/** What the report came to: the figures, the chart and table of its groups, and every line. */
function Results({ report, show, lists }: { report: Report; show: ReportShow; lists: Lists }) {
  const heading = `By ${REPORT_GROUP_LABELS[report.groupBy].toLowerCase()}`;
  const nothing = report.count === 0;

  return (
    <>
      {/* One card, like the figures at the top of Expenses and Accounts, and one row from a tablet up however
          many figures there are (Rachel, 2026-10-12: a card each was too big). Two across on a phone. */}
      <Card>
        <div className="grid grid-cols-2 gap-x-6 gap-y-3 md:auto-cols-fr md:grid-flow-col md:grid-cols-none">
          <Figure label="Total" cents={report.totalCents} main />
          {report.hasReimbursements && (
            <>
              <Figure label="Reimbursed" cents={report.reimbursedCents} />
              <Figure label="Submitted, waiting" cents={report.waitingCents} />
              <Figure label="Not submitted yet" cents={report.notSubmittedCents} />
            </>
          )}
          {report.estimatedCents > 0 && (
            <div className="min-w-0">
              <p className="text-sm text-muted">Estimated or still to come</p>
              <Amount cents={report.estimatedCents} estimate className="-ml-2 text-xl font-bold" />
            </div>
          )}
        </div>
        <p className="mt-2 text-sm text-muted">
          {report.count === 1 ? "1 line" : `${report.count} lines`} · {formatDate(report.from)} – {formatDate(report.to)}
        </p>
      </Card>

      {nothing ? (
        <Card>
          <p className="font-medium">Nothing was spent that matches this report.</p>
          <p className="mt-1 text-muted">Try other dates, include more, or remove a filter.</p>
        </Card>
      ) : (
        <div className={`grid grid-cols-1 gap-4 ${show === "chart_and_table" ? "xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]" : ""}`}>
          {show === "chart_and_table" && (
            <Card title={heading}>
              <ReportChart report={report} />
            </Card>
          )}
          <Card title="Table">
            <GroupTable report={report} />
          </Card>
        </div>
      )}

      {!nothing && <Lines rows={report.rows} lists={lists} />}
    </>
  );
}

function Figure({ label, cents, main = false }: { label: string; cents: number; main?: boolean }) {
  return (
    <div className="min-w-0">
      <p className="text-sm text-muted">{label}</p>
      <p data-amount className={`font-bold tabular-nums ${main ? "text-2xl" : "text-xl"}`}>
        {formatCents(cents)}
      </p>
    </div>
  );
}

/** Three narrow columns: it fits a phone as a table, so it stays one. */
function GroupTable({ report }: { report: Report }) {
  return (
    <table className="w-full">
      <thead>
        <tr className="border-b border-line text-left text-sm text-muted">
          <th scope="col" className="py-2 font-semibold">
            {REPORT_GROUP_LABELS[report.groupBy]}
          </th>
          <th scope="col" className="py-2 text-right font-semibold">
            Items
          </th>
          <th scope="col" className="py-2 text-right font-semibold">
            Total
          </th>
        </tr>
      </thead>
      <tbody>
        {report.groups.map((group) => (
          <tr key={group.key ?? ""} className="border-b border-line">
            <th scope="row" className="py-2 pr-2 text-left font-normal wrap-break-word">
              {group.label}
            </th>
            <td className="py-2 text-right tabular-nums">{group.count}</td>
            <td data-amount className="py-2 text-right font-semibold tabular-nums">
              {formatCents(group.totalCents)}
            </td>
          </tr>
        ))}
      </tbody>
      <tfoot>
        <tr>
          <th scope="row" className="py-2 text-left font-bold">
            Total
          </th>
          <td className="py-2 text-right font-bold tabular-nums">{report.count}</td>
          <td data-amount className="py-2 text-right font-bold tabular-nums">
            {formatCents(report.totalCents)}
          </td>
        </tr>
        {report.groupBy === "tag" && (
          <tr>
            <td colSpan={3} className="text-sm text-muted">
              Something with two tags is under both, and counted once in the total.
            </td>
          </tr>
        )}
      </tfoot>
    </table>
  );
}

/** Every line the report counted. Each opens what it is: the expense's page, or the recurring item's. */
function Lines({ rows, lists }: { rows: readonly ReportRow[]; lists: Lists }) {
  const shown = rows.slice(0, MOST_LINES_SHOWN);
  return (
    <Card title="Every line">
      <ul className="divide-y divide-line">
        {shown.map((row) => {
          const account = lists.accounts.find((candidate) => candidate.id === row.accountId)?.name ?? "an account that was removed";
          const files = row.fileCount === 1 ? "1 file" : `${row.fileCount} files`;
          return (
            <li key={row.entryId !== null ? `entry-${row.entryId}` : `item-${row.itemId}-${row.scheduledDate}`}>
              <Link
                to={row.entryId !== null ? `/expenses/${row.entryId}` : `/recurring/${row.itemId}`}
                className="-mx-2 flex min-h-11 cursor-pointer flex-col gap-1 rounded-lg px-2 py-2 hover:bg-bg/60 sm:flex-row sm:items-center sm:gap-3"
              >
                <span className="shrink-0 text-muted sm:w-28">{formatDate(row.date)}</span>
                <span className="min-w-0 flex-1">
                  <span className="block font-semibold sm:truncate">{row.payee ?? row.description}</span>
                  <span className="block text-sm text-muted sm:truncate">
                    {KIND_LABELS[row.kind]} · paid from {account}
                  </span>
                </span>
                <span className="flex shrink-0 items-center justify-between gap-3">
                  {row.reimbursement !== null && row.reimbursement !== "none" && <ReimbursementBadge status={row.reimbursement} />}
                  {row.fileCount > 0 && (
                    <span className="inline-flex items-center gap-1 text-muted tabular-nums" title={files}>
                      <Paperclip aria-hidden className="size-4" />
                      <span aria-hidden>{row.fileCount}</span>
                      <span className="sr-only">{files}</span>
                    </span>
                  )}
                  <Amount cents={row.amountCents} estimate={row.estimate} className="font-semibold" />
                </span>
              </Link>
            </li>
          );
        })}
      </ul>
      {rows.length > MOST_LINES_SHOWN && (
        <p className="mt-3 text-muted">
          The first {MOST_LINES_SHOWN} of {rows.length} lines. Export the report to have every one.
        </p>
      )}
      {shown.some((row) => row.estimate) && (
        <div className="mt-3">
          <EstimateKey />
        </div>
      )}
    </Card>
  );
}

const KIND_LABELS: Record<ReportRow["kind"], string> = { one_off: "One-off", bill: "Bill or expense", budget: "Budget" };

/**
 * Names a report: to save a new one, or to rename (or delete) one already saved. Deleting asks
 * nothing (spec §6): the report goes, and Undo brings it back.
 */
function NameDialog({
  report,
  definition,
  onSaved,
  onDeleted,
  onClose,
}: {
  /** The saved report being renamed; `null` to save a new one. */
  report: SavedReport | null;
  definition: ReportDefinition;
  onSaved: (report: SavedReport) => void;
  onDeleted: () => void;
  onClose: () => void;
}) {
  const id = useId();
  const [name, setName] = useState(report?.name ?? "");
  const save = useSaveReport();
  const remove = useDeleteReport();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    save.mutate(
      { id: report?.id, name, definition },
      {
        onSuccess: (saved) => {
          onSaved(saved);
          onClose();
        },
      },
    );
  };

  return (
    <Modal title={report ? `Rename ${report.name}` : "Save this report"} icon={FileChartColumn} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <div>
          <label htmlFor={id} className={LABEL}>
            Name
          </label>
          <input
            id={id}
            value={name}
            onChange={(event) => setName(event.target.value)}
            maxLength={80}
            required
            autoComplete="off"
            placeholder="e.g. Medical out-of-pocket 2026"
            className={INPUT}
          />
        </div>
        {(save.error ?? remove.error) && (
          <p role="alert" className="text-danger">
            {(save.error ?? remove.error)?.message}
          </p>
        )}
        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-between">
          {report ? (
            <button
              type="button"
              disabled={remove.isPending}
              onClick={() =>
                remove.mutate(report.id, {
                  onSuccess: () => {
                    onDeleted();
                    onClose();
                  },
                })
              }
              className={BUTTON_DANGER_OUTLINE}
            >
              {remove.isPending ? "Deleting…" : "Delete this report"}
            </button>
          ) : (
            <span />
          )}
          <div className="flex flex-col-reverse gap-2 sm:flex-row">
            <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
              Cancel
            </button>
            <button type="submit" disabled={save.isPending} className={BUTTON_PRIMARY}>
              {save.isPending ? "Saving…" : report ? "Save name" : "Save report"}
            </button>
          </div>
        </div>
      </form>
    </Modal>
  );
}
