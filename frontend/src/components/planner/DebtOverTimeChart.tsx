import { Area, CartesianGrid, ComposedChart, Line, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { OwedPoint } from "../../api/planner";
import { formatMonth, yearTicks } from "../../lib/dates";
import { formatCents, shortDollars } from "../../lib/money";
import { CHART_TOOLTIP } from "../ui";

const YEAR = new Intl.DateTimeFormat("en-US", { year: "numeric", timeZone: "UTC" });

/** One month on the chart; `gap` is the two totals as a range, drawn as the shaded space between the lines. */
interface Month {
  at: number;
  with: number;
  without: number;
  gap: [number, number];
}

/** Months are plotted as UTC midnights, so the PC's time zone can never shift one. */
function toTime(iso: string): number {
  return Date.parse(`${iso}T00:00:00Z`);
}

/**
 * Debt over time (Rachel, 2026-10-14): everything owed on the debts in the payoff order, month by
 * month, with the extra (solid, in the accent color) and without it (dashed). The shaded space
 * between them is how much sooner the extra clears it. The sentence under it says the same in words.
 */
export function DebtOverTimeChart({
  points,
  debtFreeDate,
  debtFreeDateBefore,
}: {
  points: readonly OwedPoint[];
  debtFreeDate: string | null;
  debtFreeDateBefore: string | null;
}) {
  const first = points[0];
  if (first === undefined || first.withoutCents === 0) {
    return null;
  }
  const data: Month[] = points.map((point) => ({
    at: toTime(point.month),
    with: point.withCents,
    without: point.withoutCents,
    gap: [point.withCents, point.withoutCents],
  }));

  return (
    <figure className="mt-5 border-t border-line pt-4">
      <h3 className="font-semibold">Debt over time</h3>
      <p className="mb-2 flex flex-wrap gap-x-4 gap-y-1 text-sm text-muted" aria-hidden>
        <span className="flex items-center gap-2">
          <span className="inline-block h-0.5 w-6 bg-accent" />
          With the extra
        </span>
        <span className="flex items-center gap-2">
          <span className="inline-block w-6 border-t-2 border-dashed border-muted" />
          Without it
        </span>
      </p>
      <div data-amount className="h-56 w-full md:h-64">
        <ResponsiveContainer width="100%" height="100%">
          <ComposedChart data={data} margin={{ top: 8, right: 12, bottom: 0, left: 0 }} accessibilityLayer={false}>
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
              formatter={(value, name) => (name === "gap" ? null : [formatCents(Number(value)), name])}
              labelFormatter={(time) => formatMonth(new Date(Number(time)).toISOString().slice(0, 10))}
              {...CHART_TOOLTIP}
            />
            <Area dataKey="gap" name="gap" stroke="none" fill="var(--accent)" fillOpacity={0.15} isAnimationActive={false} tooltipType="none" />
            <Line name="Without the extra" dataKey="without" stroke="var(--text-muted)" strokeWidth={2} strokeDasharray="6 4" dot={false} isAnimationActive={false} />
            <Line name="With the extra" dataKey="with" stroke="var(--accent)" strokeWidth={2.5} dot={false} isAnimationActive={false} />
          </ComposedChart>
        </ResponsiveContainer>
      </div>
      <figcaption className="mt-2 text-sm text-muted">
        All of it together starts at <span data-amount className="font-semibold text-ink tabular-nums">{formatCents(first.withoutCents)}</span>.{" "}
        {debtFreeDate !== null ? (
          <>
            With the extra it is all paid off by <span className="font-semibold text-ink">{formatMonth(debtFreeDate)}</span>
            {debtFreeDateBefore !== null ? (
              <>
                ; without it, by <span className="font-semibold text-ink">{formatMonth(debtFreeDateBefore)}</span>.
              </>
            ) : (
              <>; without it, it is never paid off.</>
            )}
          </>
        ) : (
          <>Even with the extra, what is paid does not get ahead of the interest on every debt, so the lines do not reach zero.</>
        )}{" "}
        The shaded space is how much less is owed along the way.
      </figcaption>
    </figure>
  );
}
