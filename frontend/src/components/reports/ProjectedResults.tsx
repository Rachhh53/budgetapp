import { Link } from "react-router";
import type { Account } from "../../api/accounts";
import { COMPARE_GROUP_LABELS, type ProjectedReport, type ProjectedRow, type ProjectedSide } from "../../api/reports";
import { formatDate } from "../../lib/dates";
import { formatCents } from "../../lib/money";
import { Amount, EstimateKey } from "../Amount";
import { Card } from "../Card";

/** A report with a great many lines lists the first of them; the exports always have every one. */
const MOST_LINES_SHOWN = 200;

const KIND_LABELS: Record<ProjectedRow["kind"], string> = { bill: "Bill or expense", budget: "Budget", paycheck: "Paycheck" };

/** A difference with its sign said: "+$12.50" for more than projected, a minus sign for less, "$0.00" for none. */
export function signedCents(cents: number): string {
  return cents > 0 ? `+${formatCents(cents)}` : formatCents(cents);
}

/**
 * What a projected vs. actual report came to (spec §5.10). Spending and paychecks each get their
 * own figures and table: a bill that cost more and a paycheck that paid more are opposite news,
 * so they are never added together. A difference is always said with its sign and in words,
 * never by color.
 */
export function ProjectedResults({ report, accounts }: { report: ProjectedReport; accounts: readonly Account[] }) {
  if (report.rows.length === 0) {
    return (
      <Card>
        <p className="font-medium">Nothing matches this report.</p>
        <p className="mt-1 text-muted">Try other dates, compare more, or remove a filter.</p>
      </Card>
    );
  }
  return (
    <>
      {report.spending.count > 0 && <Side title="Spending" side={report.spending} report={report} />}
      {report.paychecks.count > 0 && <Side title="Paychecks" side={report.paychecks} report={report} />}
      <Lines rows={report.rows} accounts={accounts} />
    </>
  );
}

/** How a side came out against its projection, as a sentence. */
function verdict(side: ProjectedSide): string {
  if (side.differenceCents === 0) {
    return "Came to what was projected";
  }
  return `${formatCents(Math.abs(side.differenceCents))} ${side.differenceCents > 0 ? "more" : "less"} than projected`;
}

function Side({ title, side, report }: { title: string; side: ProjectedSide; report: ProjectedReport }) {
  const amounts = side.count === 1 ? "amount" : "amounts";
  const typedOver = `${side.changed} of ${side.count} ${amounts} ${side.changed === 1 ? "was" : "were"} typed over`;
  return (
    <Card title={title}>
      <div className="grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-3">
        <Figure label="Projected" value={formatCents(side.projectedCents)} />
        <Figure label="Actual" value={formatCents(side.actualCents)} main />
        <Figure label="Difference" value={signedCents(side.differenceCents)} />
      </div>
      <p className="mt-2 text-sm text-muted">
        {verdict(side)} · {typedOver} · {formatDate(report.from)} – {formatDate(report.to)}
      </p>

      {/* Four columns of figures: a table from `md` up, a small stack for each group below it. */}
      <table className="mt-4 hidden w-full md:table">
        <thead>
          <tr className="border-b border-line text-left text-sm text-muted">
            <th scope="col" className="py-2 font-semibold">
              {COMPARE_GROUP_LABELS[report.groupBy]}
            </th>
            <th scope="col" className="py-2 pl-3 text-right font-semibold">
              Typed over
            </th>
            <th scope="col" className="py-2 pl-3 text-right font-semibold">
              Projected
            </th>
            <th scope="col" className="py-2 pl-3 text-right font-semibold">
              Actual
            </th>
            <th scope="col" className="py-2 pl-3 text-right font-semibold">
              Difference
            </th>
          </tr>
        </thead>
        <tbody>
          {side.groups.map((group) => (
            <tr key={group.key ?? ""} className="border-b border-line">
              <th scope="row" className="py-2 pr-2 text-left font-normal wrap-break-word">
                {group.label}
              </th>
              <td className="py-2 pl-3 text-right tabular-nums">
                {group.changed} of {group.count}
              </td>
              <td data-amount className="py-2 pl-3 text-right tabular-nums">
                {formatCents(group.projectedCents)}
              </td>
              <td data-amount className="py-2 pl-3 text-right font-semibold tabular-nums">
                {formatCents(group.actualCents)}
              </td>
              <td data-amount className="py-2 pl-3 text-right tabular-nums">
                {signedCents(group.differenceCents)}
              </td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <th scope="row" className="py-2 text-left font-bold">
              Total
            </th>
            <td className="py-2 pl-3 text-right font-bold tabular-nums">
              {side.changed} of {side.count}
            </td>
            <td data-amount className="py-2 pl-3 text-right font-bold tabular-nums">
              {formatCents(side.projectedCents)}
            </td>
            <td data-amount className="py-2 pl-3 text-right font-bold tabular-nums">
              {formatCents(side.actualCents)}
            </td>
            <td data-amount className="py-2 pl-3 text-right font-bold tabular-nums">
              {signedCents(side.differenceCents)}
            </td>
          </tr>
        </tfoot>
      </table>

      <ul className="mt-4 divide-y divide-line md:hidden">
        {side.groups.map((group) => (
          <li key={group.key ?? ""} className="py-3 first:pt-0 last:pb-0">
            <p className="font-semibold wrap-break-word">{group.label}</p>
            <dl className="mt-1 grid grid-cols-[minmax(0,1fr)_auto] gap-x-4 gap-y-1">
              <dt className="text-muted">Typed over</dt>
              <dd className="text-right tabular-nums">
                {group.changed} of {group.count}
              </dd>
              <dt className="text-muted">Projected</dt>
              <dd data-amount className="text-right tabular-nums">
                {formatCents(group.projectedCents)}
              </dd>
              <dt className="text-muted">Actual</dt>
              <dd data-amount className="text-right font-semibold tabular-nums">
                {formatCents(group.actualCents)}
              </dd>
              <dt className="text-muted">Difference</dt>
              <dd data-amount className="text-right tabular-nums">
                {signedCents(group.differenceCents)}
              </dd>
            </dl>
          </li>
        ))}
      </ul>
    </Card>
  );
}

function Figure({ label, value, main = false }: { label: string; value: string; main?: boolean }) {
  return (
    <div className="min-w-0">
      <p className="text-sm text-muted">{label}</p>
      <p data-amount className={`font-bold tabular-nums ${main ? "text-2xl" : "text-xl"}`}>
        {value}
      </p>
    </div>
  );
}

/** Every line the report counted. A bill or a budget opens its recurring item; a paycheck opens nothing. */
function Lines({ rows, accounts }: { rows: readonly ProjectedRow[]; accounts: readonly Account[] }) {
  const shown = rows.slice(0, MOST_LINES_SHOWN);
  return (
    <Card title="Every line">
      <ul className="divide-y divide-line">
        {shown.map((row) => {
          const account = accounts.find((candidate) => candidate.id === row.accountId)?.name ?? "an account that was removed";
          const body = (
            <>
              <span className="shrink-0 text-muted sm:w-28">{formatDate(row.date)}</span>
              <span className="min-w-0 flex-1">
                <span className="block font-semibold wrap-break-word">{row.description}</span>
                <span className="block text-sm text-muted">
                  {KIND_LABELS[row.kind]} · {row.kind === "paycheck" ? "landed in" : "paid from"} {account}
                  {row.toCome && " · still to come"}
                </span>
                <span data-amount className="block text-sm text-muted">
                  {row.changed
                    ? `Projected ${formatCents(row.projectedCents)} · ${signedCents(row.differenceCents)}`
                    : "As projected"}
                </span>
              </span>
              <span className="flex shrink-0 justify-end">
                <Amount cents={row.actualCents} estimate={row.toCome} className="font-semibold" />
              </span>
            </>
          );
          const key = `${row.kind}-${row.itemId ?? row.sourceId}-${row.scheduledDate}`;
          return (
            <li key={key}>
              {row.itemId !== null ? (
                <Link
                  to={`/recurring/${row.itemId}`}
                  className="-mx-2 flex min-h-11 cursor-pointer flex-col gap-1 rounded-lg px-2 py-2 hover:bg-bg/60 sm:flex-row sm:items-center sm:gap-3"
                >
                  {body}
                </Link>
              ) : (
                <div className="flex min-h-11 flex-col gap-1 py-2 sm:flex-row sm:items-center sm:gap-3">{body}</div>
              )}
            </li>
          );
        })}
      </ul>
      {rows.length > MOST_LINES_SHOWN && (
        <p className="mt-3 text-muted">
          The first {MOST_LINES_SHOWN} of {rows.length} lines. Export the report to have every one.
        </p>
      )}
      {shown.some((row) => row.toCome) && (
        <div className="mt-3">
          <EstimateKey />
        </div>
      )}
    </Card>
  );
}
