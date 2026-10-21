import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { Account, BalanceEntry } from "../../api/accounts";
import type { Loan } from "../../api/loans";
import { formatDate, todayIso, yearTicks } from "../../lib/dates";
import { formatCents, shortDollars } from "../../lib/money";
import { CHART_TOOLTIP } from "../ui";

const YEAR = new Intl.DateTimeFormat("en-US", { year: "numeric", timeZone: "UTC" });

/** One day on the chart. A series the day has no value for is left out. */
interface Day {
  at: number;
  entered?: number;
  projected?: number;
}

/** Calendar dates are plotted as UTC midnights, so the PC's time zone can never shift a day. */
function toTime(iso: string): number {
  return Date.parse(`${iso}T00:00:00Z`);
}

/** True when the loan has what the chart is drawn from: a balance today and payments still to come. */
export function hasLifetime(loan: Loan | undefined): loan is Loan {
  return loan !== undefined && loan.balanceCents !== null && loan.years.length > 0;
}

/**
 * A loan across its whole life: the balances the user entered as a solid line with dots, and
 * what is worked out from them as a dashed one, through today and on to the day it is paid off.
 * A pay period's worth of projection, as other accounts show, would say nothing about a loan
 * that runs for decades. The same story is told in words underneath.
 */
export function LoanLifetimeChart({ account, balances, loan }: { account: Account; balances: readonly BalanceEntry[]; loan: Loan }) {
  const last = loan.years.at(-1);
  if (loan.balanceCents === null || last === undefined) {
    return null;
  }
  const today = todayIso();
  const entered = [...balances].reverse();
  const first = entered[0];
  const latest = entered.at(-1);

  const days = new Map<number, Day>();
  const at = (iso: string): Day => {
    const time = toTime(iso);
    const day = days.get(time) ?? { at: time };
    days.set(time, day);
    return day;
  };
  for (const entry of entered) {
    at(entry.date).entered = entry.amountCents;
  }
  // The dashed line starts on the last balance entered, so the two lines meet.
  if (latest && latest.date <= today) {
    at(latest.date).projected = latest.amountCents;
  }
  at(today).projected = loan.balanceCents;
  for (const year of loan.years) {
    // The last year ends on the payoff date, when there is one, rather than on December 31.
    const end = year === last && loan.payoffDate ? loan.payoffDate : `${year.year}-12-31`;
    if (end > today) {
      at(end).projected = year.endingCents;
    }
  }
  const data = [...days.values()].sort((a, b) => a.at - b.at);

  return (
    <figure>
      <div data-amount className="h-56 w-full md:h-64">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={data} margin={{ top: 8, right: 12, bottom: 0, left: 0 }} accessibilityLayer={false}>
            <CartesianGrid stroke="var(--border)" strokeDasharray="2 4" vertical={false} />
            <XAxis
              dataKey="at"
              type="number"
              scale="time"
              domain={["dataMin", "dataMax"]}
              tickFormatter={(time: number) => YEAR.format(time)}
              tick={{ fill: "var(--text-muted)", fontSize: 12 }}
              stroke="var(--border)"
              ticks={yearTicks(data[0]?.at ?? 0, data.at(-1)?.at ?? 0)}
              minTickGap={16}
            />
            <YAxis tickFormatter={shortDollars} tick={{ fill: "var(--text-muted)", fontSize: 12 }} stroke="var(--border)" width={56} domain={[0, "auto"]} />
            <Tooltip
              formatter={(value, name) => [formatCents(Number(value)), name]}
              labelFormatter={(time) => formatDate(new Date(Number(time)).toISOString().slice(0, 10))}
              {...CHART_TOOLTIP}
            />
            <Line name="Entered by you" dataKey="entered" stroke={account.color} strokeWidth={2.5} dot={{ r: 3 }} connectNulls isAnimationActive={false} />
            <Line
              name="Worked out"
              dataKey="projected"
              stroke={account.color}
              strokeWidth={2}
              strokeDasharray="6 4"
              dot={false}
              connectNulls
              isAnimationActive={false}
            />
          </LineChart>
        </ResponsiveContainer>
      </div>
      <figcaption className="mt-2 text-sm text-muted">
        {first && (
          <>
            From <span data-amount className="font-semibold text-ink tabular-nums">{formatCents(first.amountCents)}</span> on{" "}
            {formatDate(first.date)} to{" "}
          </>
        )}
        <span data-amount className="font-semibold text-ink tabular-nums">{formatCents(loan.balanceCents)}</span> owed today
        {loan.payoffDate ? (
          <>
            , and paid off on <span className="font-semibold text-ink">{formatDate(loan.payoffDate)}</span>
          </>
        ) : (
          <>
            , with <span data-amount className="tabular-nums">{formatCents(last.endingCents)}</span> still owed at the end of {last.year}
          </>
        )}
        . Dots are balances you entered; the dashed line is worked out from your payment and rate.
      </figcaption>
    </figure>
  );
}
