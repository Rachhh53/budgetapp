import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { Account, AccountPoint, BalanceEntry } from "../../api/accounts";
import { formatDate } from "../../lib/dates";
import { formatCents } from "../../lib/money";

const MS_PER_DAY = 86_400_000;
const MONTH_AND_YEAR = new Intl.DateTimeFormat("en-US", { month: "short", year: "2-digit", timeZone: "UTC" });

/** One day on the chart. A series the day has no value for is left out, so its line has a gap there. */
interface Day {
  /** The date as milliseconds, which is what a time axis is drawn from. */
  at: number;
  entered?: number;
  projected?: number;
  available?: number;
}

/** Calendar dates are plotted as UTC midnights, so the PC's time zone can never shift a day. */
function toTime(iso: string): number {
  return Date.parse(`${iso}T00:00:00Z`);
}

function toIso(time: number): string {
  return new Date(time).toISOString().slice(0, 10);
}

/** `1250000` → `"$12.5k"`: short enough for an axis. */
function shortDollars(cents: number): string {
  const dollars = cents / 100;
  return Math.abs(dollars) >= 1000 ? `$${(dollars / 1000).toFixed(1).replace(/\.0$/, "")}k` : `$${Math.round(dollars)}`;
}

interface ProjectionChartProps {
  account: Account;
  /** Balances the user entered, newest first. */
  balances: readonly BalanceEntry[];
  /** The account at the end of each pay period ahead. */
  points: readonly AccountPoint[];
  /** True when the account has buckets, so "available" is worth drawing as its own line. */
  showAvailable: boolean;
}

/**
 * An account over time (design/account-detail.png): the balances the user entered as a solid
 * line, and the projection ahead as a dashed one, so it is always clear which is which. The
 * same story is told in words underneath, for anyone who cannot read the chart.
 */
export function ProjectionChart({ account, balances, points, showAvailable }: ProjectionChartProps) {
  const entered = [...balances].reverse();
  const latest = entered.at(-1);
  const last = points.at(-1);
  if (latest === undefined || last === undefined) {
    return null;
  }

  const days = new Map<number, Day>();
  const at = (iso: string): Day => {
    const time = toTime(iso);
    const day = days.get(time) ?? { at: time };
    days.set(time, day);
    return day;
  };
  for (const balance of entered) {
    at(balance.date).entered = balance.amountCents;
  }
  // The projection starts from the newest balance entered, so the two lines meet there.
  at(latest.date).projected = latest.amountCents;
  for (const point of points) {
    // A period's ending balance is what the account holds as the next one begins.
    const day = at(toIso(toTime(point.end) + MS_PER_DAY));
    day.projected = point.endingCents;
    if (showAvailable) {
      day.available = point.availableCents;
    }
  }
  const data = [...days.values()].sort((a, b) => a.at - b.at);
  const lowest = points.reduce((low, point) => (point.lowestCents < low.lowestCents ? point : low));

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
              tickFormatter={(time: number) => MONTH_AND_YEAR.format(time)}
              tick={{ fill: "var(--text-muted)", fontSize: 12 }}
              stroke="var(--border)"
              minTickGap={32}
            />
            <YAxis
              tickFormatter={shortDollars}
              tick={{ fill: "var(--text-muted)", fontSize: 12 }}
              stroke="var(--border)"
              width={56}
              domain={["auto", "auto"]}
            />
            <Tooltip
              formatter={(value, name) => [formatCents(Number(value)), name]}
              labelFormatter={(time) => formatDate(toIso(Number(time)))}
              contentStyle={{ backgroundColor: "var(--surface)", borderColor: "var(--border)", borderRadius: 8, color: "var(--text)" }}
            />
            <Line name="Entered by you" dataKey="entered" stroke={account.color} strokeWidth={2.5} dot={{ r: 3 }} connectNulls isAnimationActive={false} />
            <Line
              name="Projected"
              dataKey="projected"
              stroke={account.color}
              strokeWidth={2}
              strokeDasharray="6 4"
              dot={false}
              connectNulls
              isAnimationActive={false}
            />
            {showAvailable && (
              <Line
                name="Available after buckets"
                dataKey="available"
                stroke="var(--text-muted)"
                strokeWidth={1.5}
                strokeDasharray="2 3"
                dot={false}
                connectNulls
                isAnimationActive={false}
              />
            )}
          </LineChart>
        </ResponsiveContainer>
      </div>
      <figcaption className="mt-2 text-sm text-muted">
        Solid: balances you entered. Dashed: the projection, which reaches{" "}
        <span data-amount className="font-semibold text-ink tabular-nums">
          {formatCents(last.endingCents)}
        </span>{" "}
        by {formatDate(last.end)}
        {showAvailable && (
          <>
            {" "}
            (
            <span data-amount className="tabular-nums">
              {formatCents(last.availableCents)}
            </span>{" "}
            available after buckets, the dotted line)
          </>
        )}
        . Its lowest point on the way is{" "}
        <span data-amount className="font-semibold text-ink tabular-nums">
          {formatCents(lowest.lowestCents)}
        </span>
        , in the pay period that starts {formatDate(lowest.start)}.
      </figcaption>
    </figure>
  );
}
