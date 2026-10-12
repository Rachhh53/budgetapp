import { type CSSProperties, type ReactNode, useId, useState } from "react";
import { Link } from "react-router";
import { ANNUAL_BASES, ANNUAL_BASIS_LABELS, type AnnualBasis, type AnnualCosts, useAnnualCosts, useExportAnnualCosts } from "../api/annual";
import { savedMessage } from "../api/client";
import { Card } from "../components/Card";
import { DashboardTabs } from "../components/dashboard/DashboardTabs";
import { KIND_LABELS } from "../components/items/labels";
import { signedCents } from "../components/reports/ProjectedResults";
import { Segmented } from "../components/Segmented";
import { BUTTON_SECONDARY, PAGE_TITLE } from "../components/ui";
import { formatDay } from "../lib/dates";
import { formatCents } from "../lib/money";
import { formatChange } from "../lib/rates";

const BASIS_OPTIONS = ANNUAL_BASES.map((basis) => ({ value: basis, label: ANNUAL_BASIS_LABELS[basis] }));

/**
 * A row that opens its recurring item: the hand cursor and the highlight say so, as on every list (see `ROW_LINK`).
 * From `md` it is one line of columns; below that, the name over a label and a figure to a line.
 */
const ROW =
  "-mx-2 grid min-h-11 cursor-pointer grid-cols-[minmax(0,1fr)_auto] gap-x-4 gap-y-1 rounded-lg px-2 py-3 hover:bg-bg/60 md:grid-cols-(--columns) md:items-center md:gap-x-3";
const HEAD = "hidden border-b border-line pb-2 text-sm font-semibold text-muted md:grid md:grid-cols-(--columns) md:gap-x-3";
/** A figure's name, said beside it on a phone, where there is no row of headings above. */
const SAID = "text-muted md:hidden";
const FIGURE = "text-right tabular-nums";

/**
 * Annual costs (design/annual.png), as an overview like Accounts: what every recurring bill and
 * budget comes to a year, set beside the years before, by calendar year or by twelve rolling
 * months. A row opens the item's own page, where its month-by-month history lives (Rachel,
 * 2026-10-12: on the item's page, not a panel beside the table).
 */
export function AnnualCostsPage() {
  const [basis, setBasis] = useState<AnnualBasis>("calendar");
  const costs = useAnnualCosts(basis);
  const exporting = useExportAnnualCosts();

  return (
    <>
      <DashboardTabs />
      <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between md:mb-6">
        <div>
          <p className="text-sm font-semibold tracking-wide text-muted uppercase">Year over year</p>
          <h1 className={PAGE_TITLE}>Annual costs</h1>
        </div>
        <button
          type="button"
          disabled={exporting.isPending || costs.data === undefined || costs.data.rows.length === 0}
          onClick={() => exporting.mutate(basis)}
          className={BUTTON_SECONDARY}
        >
          Export CSV
        </button>
      </div>
      {exporting.isPending && (
        <p role="status" className="mb-4 text-muted">
          Putting the file together…
        </p>
      )}
      {exporting.data && (
        <p role="status" className="mb-4 font-medium wrap-anywhere">
          {savedMessage(exporting.data)}
        </p>
      )}
      {exporting.error && (
        <p role="alert" className="mb-4 text-danger">
          {exporting.error.message}
        </p>
      )}

      <div className="space-y-4">
        <Card>
          <div className="sm:max-w-sm">
            <Segmented legend="Add up by" options={BASIS_OPTIONS} value={basis} onChange={setBasis} />
          </div>
          {costs.data && <Figures costs={costs.data} />}
        </Card>

        {costs.isPending && <p className="text-muted">Adding up your bills and budgets…</p>}
        {costs.error && (
          <Card>
            <p role="alert" className="text-danger">
              {costs.error.message}
            </p>
          </Card>
        )}
        {costs.data && <CostsTable costs={costs.data} />}
      </div>
    </>
  );
}

/** The windows worth a column: one with nothing in it is left out, but the newest always shows. */
function shownWindows(costs: AnnualCosts): number[] {
  return costs.windows.map((_, index) => index).filter((index) => index === costs.windows.length - 1 || costs.totalsCents[index] !== 0);
}

function Figures({ costs }: { costs: AnnualCosts }) {
  const last = costs.windows.length - 1;
  const newest = costs.windows[last];
  const before = costs.windows[last - 1];
  const total = costs.totalsCents[last] ?? 0;
  const totalBefore = costs.totalsCents[last - 1] ?? 0;
  const biggest = costs.biggestIncrease;
  if (newest === undefined) {
    return null;
  }
  return (
    <div className="mt-4 grid grid-cols-1 gap-x-6 gap-y-4 sm:grid-cols-3">
      <Figure label={costs.basis === "calendar" ? `${newest.label} total` : "Last 12 months"} value={formatCents(total)}>
        {costs.basis === "rolling"
          ? newest.label
          : costs.budgetedFrom === null
            ? "what was spent"
            : `spent through ${formatDay(lastDayBefore(costs.budgetedFrom))} · budgeted after`}
      </Figure>
      {before !== undefined && costs.changeBp !== null ? (
        <Figure label={`vs ${before.label}`} value={`${signedCents(total - totalBefore)} · ${formatChange(costs.changeBp)}`}>
          {before.label} total <span data-amount>{formatCents(totalBefore)}</span>
        </Figure>
      ) : (
        <Figure label="vs the year before" value="—">
          nothing earlier to compare with yet
        </Figure>
      )}
      {biggest === null ? (
        <Figure label="Biggest increase" value="—">
          nothing costs more than it did
        </Figure>
      ) : (
        <Figure label="Biggest increase" value={`${biggest.name} ${formatChange(biggest.changeBp)}`}>
          <span data-amount>{formatCents(biggest.increaseCents)}</span> more than {before?.label ?? "before"}
        </Figure>
      )}
    </div>
  );
}

/** The day before `iso`: the last day of what has already been spent. */
function lastDayBefore(iso: string): string {
  const [year = 0, month = 1, day = 1] = iso.split("-").map(Number);
  const date = new Date(year, month - 1, day - 1);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

function Figure({ label, value, children }: { label: string; value: string; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <p className="text-muted">{label}</p>
      <p data-amount className="text-2xl font-bold tabular-nums wrap-break-word">
        {value}
      </p>
      <p className="text-sm text-muted wrap-break-word">{children}</p>
    </div>
  );
}

function CostsTable({ costs }: { costs: AnnualCosts }) {
  const headingId = useId();
  const shown = shownWindows(costs);
  const newest = costs.windows.length - 1;
  // One column for the name, one for each window shown, then the change and the monthly figure.
  const columns = { "--columns": `minmax(0,1.6fr) repeat(${shown.length}, minmax(0,1fr)) 5.5rem 7rem` } as CSSProperties;
  const change = (changeBp: number | null) => (changeBp === null ? "new" : formatChange(changeBp));

  return (
    <section aria-labelledby={headingId} className="rounded-card border border-line bg-surface p-5 md:p-6">
      <h2 id={headingId} className="mb-3 text-lg font-semibold">
        Bills and budgets
      </h2>
      {costs.rows.length === 0 ? (
        <>
          <p className="font-medium">No bills or budgets yet.</p>
          <p className="mt-1 text-muted">
            <Link to="/recurring" className="font-semibold text-accent-ink underline">
              Add a recurring bill or budget
            </Link>{" "}
            and what it comes to a year shows here.
          </p>
        </>
      ) : (
        <div style={columns}>
          <div className={HEAD}>
            <span>Item</span>
            {shown.map((index) => (
              <span key={index} className="text-right">
                {costs.windows[index]?.label}
              </span>
            ))}
            <span className="text-right">Change</span>
            <span className="text-right">Per month</span>
          </div>
          <ul className="divide-y divide-line">
            {costs.rows.map((row) => (
              <li key={row.itemId}>
                <Link to={`/recurring/${row.itemId}`} className={ROW}>
                  <span className="col-span-2 min-w-0 md:col-span-1">
                    <span className="block font-semibold wrap-break-word">{row.name}</span>
                    <span className="block text-sm text-muted wrap-break-word">
                      {KIND_LABELS[row.kind]}
                      {row.categoryName !== null && ` · ${row.categoryName}`}
                    </span>
                  </span>
                  {shown.map((index) => (
                    <Cell key={index} said={costs.windows[index]?.label ?? ""} strong={index === newest}>
                      {formatCents(row.totalsCents[index] ?? 0)}
                    </Cell>
                  ))}
                  <Cell said="Change">{change(row.changeBp)}</Cell>
                  <Cell said="Per month">{formatCents(row.perMonthCents)}</Cell>
                </Link>
              </li>
            ))}
          </ul>
          <div className="grid grid-cols-[minmax(0,1fr)_auto] gap-x-4 gap-y-1 border-t border-line pt-3 font-bold md:grid-cols-(--columns) md:items-center md:gap-x-3">
            <span className="col-span-2 md:col-span-1">Total</span>
            {shown.map((index) => (
              <Cell key={index} said={costs.windows[index]?.label ?? ""}>
                {formatCents(costs.totalsCents[index] ?? 0)}
              </Cell>
            ))}
            <Cell said="Change">{change(costs.changeBp)}</Cell>
            <Cell said="Per month">{formatCents(costs.perMonthCents)}</Cell>
          </div>
          <p className="mt-3 text-sm text-muted">
            Each amount is what it actually was: what a bill was paid at, and for a budget what you typed over it. Anything untouched counts as
            planned. Open a row for its month-by-month history. Archived items are left out.
          </p>
        </div>
      )}
    </section>
  );
}

/** One figure of a row: on a phone its name sits to its left; from `md` the headings above say it. */
function Cell({ said, strong = false, children }: { said: string; strong?: boolean; children: ReactNode }) {
  return (
    <>
      <span className={SAID}>{said}</span>
      <span data-amount className={`${FIGURE} ${strong ? "font-semibold" : ""}`}>
        {children}
      </span>
    </>
  );
}
