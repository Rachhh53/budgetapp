import { Link } from "react-router";
import type { PayoffDebt } from "../../api/planner";
import { formatDate, formatMonth, monthsBetween } from "../../lib/dates";
import { formatCents } from "../../lib/money";
import { formatRate } from "../../lib/rates";
import { duration } from "./OptionCard";

/** With nothing ever paid off there is still a line to draw against: ten years. */
const FALLBACK_MONTHS = 120;
/** About this many year marks along the top, however long the debts run. */
const AXIS_MARKS = 5;

/** What one debt's line says, in words: the bars only repeat it. */
function outcome(debt: PayoffDebt): string {
  if (debt.planned) {
    return `${debt.payoffDate === null ? "Payoff" : formatMonth(debt.payoffDate)} · already planned`;
  }
  if (!debt.paysOff || debt.payoffDate === null) {
    return "Not paid off: the interest is more than what is paid";
  }
  if (!debt.paysOffBefore || debt.payoffDateBefore === null) {
    return `${formatMonth(debt.payoffDate)} (never, without the extra)`;
  }
  if (debt.payoffDateBefore === debt.payoffDate) {
    return `${formatMonth(debt.payoffDate)} · no change`;
  }
  const sooner = monthsBetween(debt.payoffDate, debt.payoffDateBefore);
  return `${formatMonth(debt.payoffDate)} (was ${formatMonth(debt.payoffDateBefore)}${sooner > 0 ? ` · ${duration(sooner)} sooner` : ""})`;
}

function rateLine(debt: PayoffDebt, place: number): string {
  const rate =
    debt.rateBp === null
      ? "no rate entered"
      : debt.rateChangesOn !== null && debt.rateAfterBp !== null
        ? `${formatRate(debt.rateBp)} → ${formatRate(debt.rateAfterBp)} ${formatDate(debt.rateChangesOn)}`
        : formatRate(debt.rateBp);
  return debt.planned ? rate : `${rate} · ${place === 0 ? "gone first" : place === 1 ? "next" : "after that"}`;
}

/**
 * The payoff timeline (design/planner.png): for each debt, how long it runs with the extra and
 * without. The words on every row carry all of it; from `md` up two thin bars repeat them
 * against a shared scale of years. On a phone the words stand alone.
 *
 * The "with" bar wears the account's own color and the "without" bar a neutral, so which is
 * which never rests on telling two hues apart: the key names them, and "with" is always on top.
 */
export function PayoffTimeline({ debts, today, extraCents }: { debts: readonly PayoffDebt[]; today: string; extraCents: number }) {
  const ends = debts.flatMap((debt) => [debt.payoffDate, debt.payoffDateBefore]).filter((date): date is string => date !== null);
  const longest = Math.max(0, ...ends.map((date) => monthsBetween(today, date)));
  const span = longest === 0 ? FALLBACK_MONTHS : longest;
  /** How far along the scale a date is, as a share of it; a debt never paid off runs the whole way. */
  const share = (date: string | null, paysOff: boolean) =>
    `${Math.max(1, Math.min(100, !paysOff || date === null ? 100 : (monthsBetween(today, date) / span) * 100))}%`;

  const startYear = Number(today.slice(0, 4));
  const years = Math.max(1, Math.ceil(span / 12));
  const step = Math.max(1, Math.ceil(years / AXIS_MARKS));
  const marks: number[] = [];
  for (let year = 0; year <= years; year += step) {
    marks.push(year);
  }
  const extra = formatCents(extraCents);
  // Where each debt comes in the order they are gone; one already planned for is not part of that order.
  const inOrder = debts.filter((debt) => !debt.planned);

  return (
    <div>
      <div aria-hidden className="relative mb-1 hidden h-5 text-xs text-muted md:ml-56 md:block">
        {marks.map((year) => (
          <span key={year} className="absolute -translate-x-1/2 tabular-nums first:translate-x-0" style={{ left: `${Math.min(100, ((year * 12) / span) * 100)}%` }}>
            {startYear + year}
          </span>
        ))}
      </div>
      <ul className="divide-y divide-line md:divide-y-0">
        {debts.map((debt) => {
          const place = inOrder.indexOf(debt);
          const words = outcome(debt);
          return (
            <li key={debt.accountId} className="py-3 md:flex md:items-center md:gap-4 md:py-2">
              <div className="min-w-0 md:w-52 md:shrink-0">
                <Link to={`/accounts/${debt.accountId}`} className="cursor-pointer font-semibold wrap-break-word hover:underline">
                  {debt.name}
                </Link>
                <p className="text-sm text-muted">{rateLine(debt, place)}</p>
              </div>
              <div className="min-w-0 flex-1">
                <div data-amount className="hidden space-y-0.5 md:block" title={`${debt.name}: ${words}`}>
                  <div className="h-2.5 rounded-r" style={{ width: share(debt.payoffDate, debt.paysOff), backgroundColor: debt.color }} />
                  {!debt.planned && <div className="h-2.5 rounded-r bg-line" style={{ width: share(debt.payoffDateBefore, debt.paysOffBefore) }} />}
                </div>
                <p className="mt-1 text-sm font-semibold wrap-break-word">{words}</p>
              </div>
            </li>
          );
        })}
      </ul>
      <p aria-hidden className="mt-3 hidden flex-wrap items-center gap-x-4 gap-y-1 text-sm text-muted md:flex">
        <span className="flex items-center gap-2">
          <span className="h-2.5 w-5 rounded-r bg-ink" /> With the extra {extra} (in each account's color)
        </span>
        <span className="flex items-center gap-2">
          <span className="h-2.5 w-5 rounded-r bg-line" /> Without
        </span>
      </p>
    </div>
  );
}
