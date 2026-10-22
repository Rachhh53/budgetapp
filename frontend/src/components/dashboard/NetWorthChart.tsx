import { CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { WorthPoint } from "../../api/dashboard";
import { formatDate } from "../../lib/dates";
import { formatCents } from "../../lib/money";
import { CHART_TOOLTIP } from "../ui";

const MONTH_AND_YEAR = new Intl.DateTimeFormat("en-US", { month: "short", year: "2-digit", timeZone: "UTC" });

/** Calendar dates are plotted as UTC midnights, so the PC's time zone can never shift a day. */
function toTime(iso: string): number {
  return Date.parse(`${iso}T00:00:00Z`);
}

/** `14364762` → `"$143.6k"`: short enough for an axis. */
function shortDollars(cents: number): string {
  const dollars = cents / 100;
  return Math.abs(dollars) >= 1000 ? `$${(dollars / 1000).toFixed(1).replace(/\.0$/, "")}k` : `$${Math.round(dollars)}`;
}

/**
 * Net worth over time (design/dashboard.png): solid up to today, from the balances and values
 * entered and everything that moved since; dashed from today on, the projection. The two meet at
 * today, which a faint line marks. The same story is told in words underneath.
 */
export function NetWorthChart({ points, today }: { points: readonly WorthPoint[]; today: string }) {
  const first = points[0];
  const last = points.at(-1);
  const now = points.find((point) => point.date === today);
  if (first === undefined || last === undefined || points.length < 2) {
    return (
      <p className="text-muted">
        There is nothing to draw yet. Enter a balance for an account, or a value for something you own, and the line starts there.
      </p>
    );
  }

  const data = points.map((point) => ({
    at: toTime(point.date),
    // Today belongs to both lines, so they join without a gap.
    soFar: point.projected ? undefined : point.netWorthCents,
    ahead: point.projected || point.date === today ? point.netWorthCents : undefined,
  }));

  return (
    <figure>
      <div data-amount className="h-56 w-full md:h-64 lg:h-96">
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
              width={60}
              domain={["auto", "auto"]}
            />
            <Tooltip
              formatter={(value, name) => [formatCents(Number(value)), name]}
              labelFormatter={(time) => formatDate(new Date(Number(time)).toISOString().slice(0, 10))}
              {...CHART_TOOLTIP}
            />
            <ReferenceLine x={toTime(today)} stroke="var(--border)" />
            <Line name="So far" dataKey="soFar" stroke="var(--accent)" strokeWidth={2} dot={false} connectNulls isAnimationActive={false} />
            <Line
              name="Projected"
              dataKey="ahead"
              stroke="var(--accent)"
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
        Solid: what Budget works out from the balances and values you entered. Dashed: the projection. It was{" "}
        <span data-amount className="font-semibold text-ink tabular-nums">
          {formatCents(first.netWorthCents)}
        </span>{" "}
        on {formatDate(first.date)}
        {now !== undefined && (
          <>
            , is{" "}
            <span data-amount className="font-semibold text-ink tabular-nums">
              {formatCents(now.netWorthCents)}
            </span>{" "}
            today
          </>
        )}
        {last.projected && (
          <>
            , and is projected to be{" "}
            <span data-amount className="font-semibold text-ink tabular-nums">
              {formatCents(last.netWorthCents)}
            </span>{" "}
            by {formatDate(last.date)}
          </>
        )}
        .
      </figcaption>
    </figure>
  );
}
