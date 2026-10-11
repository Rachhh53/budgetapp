import { useId, useState } from "react";
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { Account, AccountPoint, BalanceEntry } from "../../api/accounts";
import { formatDate, todayIso } from "../../lib/dates";
import { formatCents } from "../../lib/money";
import { INPUT, LABEL } from "../ui";

const MS_PER_DAY = 86_400_000;
const MONTH_AND_YEAR = new Intl.DateTimeFormat("en-US", { month: "short", year: "2-digit", timeZone: "UTC" });
const MONTH_AND_DAY = new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", timeZone: "UTC" });

/** Below this many days across, the axis names days rather than months. */
const DAYS_SHOWN_BY_DAY = 120;

/**
 * Which dates the chart covers (decided 2026-10-11): everything, a stretch ahead from today
 * (the card is "Where it's heading"), or any two dates, which can start in the past.
 */
type Range = "all" | "30" | "60" | "90" | "365" | "custom";

const RANGES: readonly { value: Range; label: string }[] = [
  { value: "all", label: "Everything" },
  { value: "30", label: "Next 30 days" },
  { value: "60", label: "Next 60 days" },
  { value: "90", label: "Next 90 days" },
  { value: "365", label: "Next year" },
  { value: "custom", label: "Custom dates" },
];

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

/**
 * The days between two dates, both included. A projected line that crosses either edge is cut
 * there, at the value it has on the way, so it runs the full width instead of starting late.
 * Balances entered are never made up: only the ones inside the dates are kept.
 */
function between(data: readonly Day[], from: number, to: number): Day[] {
  const inside = new Map<number, Day>();
  for (const day of data) {
    if (day.at >= from && day.at <= to) {
      inside.set(day.at, { ...day });
    }
  }
  for (const series of ["projected", "available"] as const) {
    const known = data.filter((day) => day[series] !== undefined);
    for (const edge of [from, to]) {
      const before = known.filter((day) => day.at <= edge).at(-1);
      const after = known.find((day) => day.at >= edge);
      const start = before?.[series];
      const end = after?.[series];
      if (before === undefined || after === undefined || start === undefined || end === undefined) {
        // The line does not reach this edge.
        continue;
      }
      const share = after.at === before.at ? 0 : (edge - before.at) / (after.at - before.at);
      const day = inside.get(edge) ?? { at: edge };
      day[series] = Math.round(start + (end - start) * share);
      inside.set(edge, day);
    }
  }
  return [...inside.values()].sort((a, b) => a.at - b.at);
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
 *
 * It opens on everything there is. The list above it narrows that to a stretch ahead of
 * today, or to any two dates; the words underneath follow what is in view.
 */
export function ProjectionChart({ account, balances, points, showAvailable }: ProjectionChartProps) {
  const id = useId();
  const [range, setRange] = useState<Range>("all");
  // Custom dates: empty until chosen, when they start out as everything there is.
  const [customFrom, setCustomFrom] = useState("");
  const [customTo, setCustomTo] = useState("");
  const entered = [...balances].reverse();
  const latest = entered.at(-1);
  if (latest === undefined || points.length === 0) {
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
  const all = [...days.values()].sort((a, b) => a.at - b.at);
  const earliest = all[0]?.at ?? toTime(latest.date);
  const furthest = all.at(-1)?.at ?? earliest;

  const today = toTime(todayIso());
  const from = range === "all" ? earliest : range === "custom" ? toTime(customFrom || toIso(earliest)) : today;
  const to = range === "all" ? furthest : range === "custom" ? toTime(customTo || toIso(furthest)) : today + Number(range) * MS_PER_DAY;
  const backwards = range === "custom" && from > to;
  const data = range === "all" ? all : backwards ? [] : between(all, from, to);
  const byDay = (to - from) / MS_PER_DAY <= DAYS_SHOWN_BY_DAY;

  // What the words underneath are about: the pay periods that end inside the dates in view.
  const inView = points.filter((point) => {
    const day = toTime(point.end) + MS_PER_DAY;
    return day >= from && day <= to;
  });
  const last = inView.at(-1);
  const lowest = inView.reduce<AccountPoint | undefined>((low, point) => (low === undefined || point.lowestCents < low.lowestCents ? point : low), undefined);

  const chooseRange = (chosen: Range) => {
    if (chosen === "custom" && customFrom === "" && customTo === "") {
      setCustomFrom(toIso(earliest));
      setCustomTo(toIso(furthest));
    }
    setRange(chosen);
  };

  return (
    <figure>
      <div className="mb-3 flex flex-wrap items-end gap-3">
        <div className="min-w-40 flex-1 sm:max-w-56">
          <label htmlFor={`${id}-range`} className={LABEL}>
            Dates to show
          </label>
          <select id={`${id}-range`} value={range} onChange={(event) => chooseRange(event.target.value as Range)} className={INPUT}>
            {RANGES.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
        {range === "custom" && (
          // On a line of their own on a phone, so each date can be read. Any day from the first balance entered to the end of the projection.
          <div className="grid w-full min-w-0 grid-cols-2 gap-3 sm:w-auto sm:flex-1">
            <div>
              <label htmlFor={`${id}-from`} className={LABEL}>
                From
              </label>
              <input
                id={`${id}-from`}
                type="date"
                value={customFrom}
                min={toIso(earliest)}
                max={toIso(furthest)}
                onChange={(event) => setCustomFrom(event.target.value)}
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
                value={customTo}
                min={toIso(earliest)}
                max={toIso(furthest)}
                onChange={(event) => setCustomTo(event.target.value)}
                className={INPUT}
              />
            </div>
          </div>
        )}
      </div>
      {data.length < 2 ? (
        <p role="status" className="flex h-56 items-center justify-center rounded-lg bg-bg px-4 text-center text-muted md:h-64">
          {backwards
            ? "The From date is after the To date. Please swap them."
            : `There is nothing to draw between those dates. This account's chart runs from ${formatDate(toIso(earliest))} to ${formatDate(toIso(furthest))}.`}
        </p>
      ) : (
        <div data-amount className="h-56 w-full md:h-64">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={data} margin={{ top: 8, right: 12, bottom: 0, left: 0 }} accessibilityLayer={false}>
              <CartesianGrid stroke="var(--border)" strokeDasharray="2 4" vertical={false} />
              <XAxis
                dataKey="at"
                type="number"
                scale="time"
                domain={["dataMin", "dataMax"]}
                tickFormatter={(time: number) => (byDay ? MONTH_AND_DAY : MONTH_AND_YEAR).format(time)}
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
                  name="Unallocated balance"
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
      )}
      {last === undefined || lowest === undefined ? (
        <figcaption className="mt-2 text-sm text-muted">
          Solid: balances you entered. Dashed: the projection. No pay period ends between these dates, so there are no projected figures to
          give for them.
        </figcaption>
      ) : (
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
              unallocated, the dotted line)
            </>
          )}
          . Its lowest point on the way is{" "}
          <span data-amount className="font-semibold text-ink tabular-nums">
            {formatCents(lowest.lowestCents)}
          </span>
          , in the pay period that starts {formatDate(lowest.start)}.
        </figcaption>
      )}
    </figure>
  );
}
