import { useId } from "react";
import { Bar, BarChart, CartesianGrid, Cell, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { type PeriodSpending, useAccountSpending } from "../../api/accounts";
import { type Category, useCategories } from "../../api/categories";
import { formatDate, todayIso } from "../../lib/dates";
import { formatCents, shortDollars } from "../../lib/money";

/** How many categories get a stack of their own; the rest are added together, so the bars stay readable on a phone. */
const MOST_NAMED = 5;

/** Bars for pay periods still to come are paler: those amounts are what is scheduled, not what happened. */
const SCHEDULED_OPACITY = 0.4;

const OTHER_KEY = "other";
const NONE_KEY = "none";

const MONTH_AND_DAY = new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", timeZone: "UTC" });

/**
 * One stack of every bar: a category, everything else added together, or what has no category. Everything else is
 * striped rather than given a color of its own, so it can never be mistaken for one of the categories' colors.
 */
interface Stack {
  key: string;
  label: string;
  color: string;
  striped?: boolean;
}

/** The stripes "Everything else" is drawn with, in the chart and in its key. */
function Stripes({ id }: { id: string }) {
  return (
    <pattern id={id} width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
      <rect width="6" height="6" fill="var(--surface)" />
      <rect width="3" height="6" fill="var(--text-muted)" />
    </pattern>
  );
}

/** A stack's little square in the key and the tooltip. */
function Swatch({ stack, stripesId, className }: { stack: Stack; stripesId: string; className: string }) {
  return stack.striped ? (
    <svg aria-hidden className={`${className} shrink-0 rounded-sm`} viewBox="0 0 12 12">
      <rect width="12" height="12" fill={`url(#${stripesId})`} />
    </svg>
  ) : (
    <span aria-hidden className={`${className} shrink-0 rounded-sm`} style={{ backgroundColor: stack.color }} />
  );
}

/** One bar: a pay period, with what each stack came to in it. */
type Row = { start: string; end: string; happened: boolean; total: number } & Record<string, number | string | boolean>;

/** A sub-category counts toward the category above it, so the stacks are the categories you see on Categories & tags. */
function topLevel(id: number, byId: Map<number, Category>): Category | undefined {
  const category = byId.get(id);
  return category?.parentId != null ? (byId.get(category.parentId) ?? category) : category;
}

/**
 * The stacks, biggest first across the periods shown: the five biggest categories by name, then the rest together, then
 * what has no category (transfers and card payments often have none, and can be large, so they are never folded in).
 */
function stacksFor(periods: readonly PeriodSpending[], byId: Map<number, Category>): { stacks: Stack[]; keyOf: (id: number | null) => string } {
  const totals = new Map<number, number>();
  let none = 0;
  for (const period of periods) {
    for (const share of period.categories) {
      const category = share.categoryId === null ? undefined : topLevel(share.categoryId, byId);
      if (category === undefined) {
        none += share.cents;
      } else {
        totals.set(category.id, (totals.get(category.id) ?? 0) + share.cents);
      }
    }
  }
  const ranked = [...totals.entries()].filter(([, cents]) => cents > 0).sort((a, b) => b[1] - a[1]);
  // With just one more than fits, it is named too: "Everything else" for a single category says less than its name.
  const named = new Set(ranked.slice(0, ranked.length === MOST_NAMED + 1 ? MOST_NAMED + 1 : MOST_NAMED).map(([id]) => id));
  const stacks: Stack[] = ranked
    .filter(([id]) => named.has(id))
    .map(([id]) => ({ key: `c${id}`, label: byId.get(id)?.name ?? "Category", color: byId.get(id)?.color ?? "var(--accent)" }));
  if (ranked.length > named.size) {
    stacks.push({ key: OTHER_KEY, label: "Everything else", color: "var(--text-muted)", striped: true });
  }
  if (none > 0) {
    stacks.push({ key: NONE_KEY, label: "No category", color: "var(--text-muted)" });
  }
  const keyOf = (id: number | null) => {
    const category = id === null ? undefined : topLevel(id, byId);
    return category === undefined ? NONE_KEY : named.has(category.id) ? `c${category.id}` : OTHER_KEY;
  };
  return { stacks, keyOf };
}

interface TipProps {
  active?: boolean;
  payload?: readonly { payload?: Row }[];
  stacks: readonly Stack[];
  stripesId: string;
}

/**
 * The chart's words for a bank account and for a card (Rachel, 2026-10-24: "it needs to be on THOSE too"): what leaves a
 * bank account goes out; what is put on a card is charged.
 */
const WORDS = {
  out: {
    title: "Going out each pay period",
    none: "Nothing goes out of this account between these dates.",
    loading: "Loading what went out…",
    happened: "What went out",
    scheduled: "Scheduled to go out",
    pastAverage: "went out each pay period",
    futureAverage: "is scheduled to go out each pay period",
    counts: "Everything that left the account counts, transfers and card payments too.",
  },
  charged: {
    title: "Charged each pay period",
    none: "Nothing is charged to this card between these dates.",
    loading: "Loading what was charged…",
    happened: "What was charged",
    scheduled: "Scheduled to be charged",
    pastAverage: "was charged each pay period",
    futureAverage: "is scheduled to be charged each pay period",
    counts: "Everything charged to the card counts, its interest too; payments toward it don't.",
  },
};

type Words = (typeof WORDS)["out"];

/** Pointing at a bar: its pay period, whether it happened or is scheduled, and each category with the total. */
function PeriodTip({ active, payload, stacks, stripesId, words }: TipProps & { words: Words }) {
  const row = payload?.[0]?.payload;
  if (!active || row === undefined) {
    return null;
  }
  const shares = stacks.filter((stack) => Number(row[stack.key] ?? 0) > 0);
  return (
    <div className="w-56 rounded-lg border border-line bg-surface p-3 text-xs text-ink shadow-md sm:w-64 sm:text-sm">
      <p className="font-semibold">
        {formatDate(row.start)} – {formatDate(row.end)}
      </p>
      <p className="text-muted">{row.happened ? words.happened : words.scheduled}</p>
      <ul className="mt-1 space-y-0.5">
        {shares.map((stack) => (
          <li key={stack.key} className="flex items-center justify-between gap-3">
            <span className="flex min-w-0 items-center gap-1.5">
              <Swatch stack={stack} stripesId={stripesId} className="size-2.5" />
              <span className="truncate">{stack.label}</span>
            </span>
            <span className="tabular-nums">{formatCents(Number(row[stack.key]))}</span>
          </li>
        ))}
      </ul>
      <p className="mt-1 flex justify-between gap-3 border-t border-line pt-1 font-semibold">
        <span>Total</span>
        <span className="tabular-nums">{formatCents(row.total)}</span>
      </p>
    </div>
  );
}

/**
 * What went out of a checking or savings account, or was charged to a card, each pay period, as bars stacked by category, with a dashed line at the
 * average (Rachel, 2026-10-24: "an average spend line on the graph"; she chose bars under the balance chart, left as it
 * is, split by category, counting everything that goes out). It covers the same dates as the chart above it. The
 * average is of the pay periods that are over, so it is what really went out; until one is, it is what is scheduled.
 */
export function SpendingChart({ accountId, from, to, charged = false }: { accountId: number; from: string; to: string; charged?: boolean }) {
  const words = charged ? WORDS.charged : WORDS.out;
  const stripesId = `${useId()}-stripes`;
  const spending = useAccountSpending(accountId, { from, to });
  const categories = useCategories();
  const today = todayIso();

  if (spending.isError) {
    return (
      <p role="alert" className="mt-4 text-sm text-danger">
        {spending.error.message}
      </p>
    );
  }
  if (spending.data === undefined || categories.data === undefined) {
    return <p className="mt-4 text-sm text-muted">{words.loading}</p>;
  }
  const periods = spending.data;
  const byId = new Map(categories.data.map((category) => [category.id, category]));
  const { stacks, keyOf } = stacksFor(periods, byId);
  const rows: Row[] = periods.map((period) => {
    const row: Row = { start: period.start, end: period.end, happened: period.end < today, total: period.outCents };
    for (const share of period.categories) {
      const key = keyOf(share.categoryId);
      row[key] = Number(row[key] ?? 0) + share.cents;
    }
    return row;
  });
  const over = rows.filter((row) => row.happened);
  const counted = over.length > 0 ? over : rows;
  const average = counted.length === 0 ? 0 : Math.round(counted.reduce((sum, row) => sum + row.total, 0) / counted.length);
  const biggest = stacks.find((stack) => stack.key !== OTHER_KEY && stack.key !== NONE_KEY);
  const biggestAverage = biggest === undefined ? 0 : Math.round(counted.reduce((sum, row) => sum + Number(row[biggest.key] ?? 0), 0) / counted.length);

  return (
    <section aria-label={words.title} className="mt-5 border-t border-line pt-4">
      <h3 className="font-semibold">{words.title}</h3>
      {rows.length === 0 || rows.every((row) => row.total === 0) ? (
        <p className="mt-2 text-sm text-muted">{words.none}</p>
      ) : (
        <>
          <div data-amount className="mt-2 h-48 w-full md:h-56">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={rows} margin={{ top: 8, right: 12, bottom: 0, left: 0 }} accessibilityLayer={false} barCategoryGap="15%">
                <defs>
                  <Stripes id={stripesId} />
                </defs>
                <CartesianGrid stroke="var(--border)" strokeDasharray="2 4" vertical={false} />
                <XAxis
                  dataKey="start"
                  tickFormatter={(start: string) => MONTH_AND_DAY.format(Date.parse(`${start}T00:00:00Z`))}
                  tick={{ fill: "var(--text-muted)", fontSize: 12 }}
                  stroke="var(--border)"
                  minTickGap={24}
                />
                <YAxis tickFormatter={shortDollars} tick={{ fill: "var(--text-muted)", fontSize: 12 }} stroke="var(--border)" width={56} />
                <Tooltip cursor={{ fill: "var(--bg)" }} content={<PeriodTip stacks={stacks} stripesId={stripesId} words={words} />} />
                {stacks.map((stack) => (
                  <Bar key={stack.key} dataKey={stack.key} name={stack.label} stackId="out" fill={stack.striped ? `url(#${stripesId})` : stack.color} maxBarSize={40} isAnimationActive={false}>
                    {rows.map((row) => (
                      <Cell key={row.start} fillOpacity={row.happened ? 1 : SCHEDULED_OPACITY} />
                    ))}
                  </Bar>
                ))}
                <ReferenceLine y={average} stroke="var(--text)" strokeWidth={1.5} strokeDasharray="6 4" ifOverflow="extendDomain" />
              </BarChart>
            </ResponsiveContainer>
          </div>
          {/* The key is a list of its own rather than the chart's legend, so it wraps on a phone. */}
          <ul aria-label="Categories" className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-sm">
            {stacks.map((stack) => (
              <li key={stack.key} className="flex items-center gap-1.5">
                <Swatch stack={stack} stripesId={stripesId} className="size-3" />
                {stack.label}
              </li>
            ))}
            <li className="flex items-center gap-1.5">
              <span aria-hidden className="w-4 border-t-2 border-dashed border-ink" />
              Average
            </li>
          </ul>
          <p className="mt-2 text-sm text-muted">
            {over.length > 0 ? (
              <>
                On average{" "}
                <span data-amount className="font-semibold text-ink tabular-nums">
                  {formatCents(average)}
                </span>{" "}
                {words.pastAverage}, over the {over.length === 1 ? "pay period" : `${over.length} pay periods`} shown that{" "}
                {over.length === 1 ? "is" : "are"} over (the dashed line)
              </>
            ) : (
              <>
                About{" "}
                <span data-amount className="font-semibold text-ink tabular-nums">
                  {formatCents(average)}
                </span>{" "}
                {words.futureAverage} (the dashed line). None of the pay periods shown is over yet
              </>
            )}
            {biggest !== undefined && (
              <>
                ; the biggest category is {biggest.label}, at <span data-amount className="tabular-nums">{formatCents(biggestAverage)}</span> a pay
                period
              </>
            )}
            . {words.counts} Paler bars are pay periods still to come:
            what is scheduled, not what happened.
          </p>
        </>
      )}
    </section>
  );
}
