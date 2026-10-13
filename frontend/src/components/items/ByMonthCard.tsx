import type { ReactNode } from "react";
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { type CostsByMonth, useCostsByMonth } from "../../api/annual";
import type { Item } from "../../api/items";
import { formatCents } from "../../lib/money";
import { Card } from "../Card";

const MONTH_SHORT = new Intl.DateTimeFormat("en-US", { month: "short", timeZone: "UTC" });
const MONTH_LONG = new Intl.DateTimeFormat("en-US", { month: "long", timeZone: "UTC" });

/** `2026-07` → `Jul` or `July`. */
function monthName(key: string, format: Intl.DateTimeFormat): string {
  const [, month = "1"] = key.split("-");
  return format.format(Date.UTC(2000, Number(month) - 1, 1));
}

/** `24000` → `"$240"`: short enough for an axis. */
function shortDollars(cents: number): string {
  const dollars = cents / 100;
  return Math.abs(dollars) >= 1000 ? `$${(dollars / 1000).toFixed(1).replace(/\.0$/, "")}k` : `$${Math.round(dollars)}`;
}

/**
 * A bill's or budget's year, month by month (design/annual.png), on its own page (Rachel,
 * 2026-10-12). This year is the solid line up to this month and dotted for the months still to
 * come, which hold what is budgeted; last year is the grey dashed line behind it. Under the
 * chart: the highest and lowest month, the average, what today's amount comes to a month, and a
 * plain sentence when the year is coming to more than that.
 */
export function ByMonthCard({ item }: { item: Item }) {
  const costs = useCostsByMonth(item.id, item.kind === "bill" || item.kind === "budget");
  if (item.kind !== "bill" && item.kind !== "budget") {
    return null;
  }
  return (
    <Card title="By month">
      {costs.isPending && <p className="text-muted">Adding up each month…</p>}
      {costs.error && (
        <p role="alert" className="text-danger">
          {costs.error.message}
        </p>
      )}
      {costs.data && <Year costs={costs.data} item={item} />}
    </Card>
  );
}

function Year({ costs, item }: { costs: CostsByMonth; item: Item }) {
  if (costs.highest === null || costs.lowest === null) {
    return <p className="text-muted">Nothing falls in {costs.year} yet. Once it does, each month shows here beside the year before.</p>;
  }
  const hasLastYear = costs.months.some((month) => month.beforeCents !== 0);
  const lastSpent = costs.months.filter((month) => !month.toCome).at(-1);
  const data = costs.months.map((month) => ({
    name: monthName(month.month, MONTH_SHORT),
    label: `${monthName(month.month, MONTH_LONG)} ${costs.year}`,
    spent: month.toCome ? undefined : month.amountCents,
    // The last month spent belongs to both lines, so they join without a gap.
    budgeted: month.toCome || month === lastSpent ? month.amountCents : undefined,
    before: hasLastYear ? month.beforeCents : undefined,
  }));
  const what = item.kind === "budget" ? "budget" : "amount";

  return (
    <figure>
      <div data-amount className="h-56 w-full">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={data} margin={{ top: 8, right: 12, bottom: 0, left: 0 }} accessibilityLayer={false}>
            <CartesianGrid stroke="var(--border)" strokeDasharray="2 4" vertical={false} />
            <XAxis dataKey="name" tick={{ fill: "var(--text-muted)", fontSize: 12 }} stroke="var(--border)" interval="preserveStartEnd" minTickGap={8} />
            <YAxis tickFormatter={shortDollars} tick={{ fill: "var(--text-muted)", fontSize: 12 }} stroke="var(--border)" width={52} domain={[0, "auto"]} />
            <Tooltip
              formatter={(value, name) => [formatCents(Number(value)), name]}
              labelFormatter={(_, payload) => String(payload[0]?.payload?.label ?? "")}
              contentStyle={{ backgroundColor: "var(--surface)", borderColor: "var(--border)", borderRadius: 8, color: "var(--text)" }}
              itemStyle={{ color: "var(--text)" }}
            />
            {hasLastYear && (
              <Line
                name={String(costs.year - 1)}
                dataKey="before"
                stroke="var(--text-muted)"
                strokeWidth={2}
                strokeDasharray="6 4"
                dot={false}
                isAnimationActive={false}
              />
            )}
            <Line name={`${costs.year}, budgeted`} dataKey="budgeted" stroke="var(--accent)" strokeWidth={2} strokeDasharray="2 4" dot={false} isAnimationActive={false} />
            <Line name={String(costs.year)} dataKey="spent" stroke="var(--accent)" strokeWidth={2} dot={{ r: 3 }} isAnimationActive={false} />
          </LineChart>
        </ResponsiveContainer>
      </div>
      <figcaption>
        <p className="mt-2 text-sm text-muted">
          Solid: {costs.year} so far. Dotted: the months still to come, as budgeted.{hasLastYear && ` Grey dashes: ${costs.year - 1}.`}
        </p>
        <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-3">
          <Fact term={`Highest ${costs.year}`}>
            {monthName(costs.highest.month, MONTH_SHORT)} · <span data-amount>{formatCents(costs.highest.amountCents)}</span>
          </Fact>
          <Fact term={`Lowest ${costs.year}`}>
            {monthName(costs.lowest.month, MONTH_SHORT)} · <span data-amount>{formatCents(costs.lowest.amountCents)}</span>
          </Fact>
          <Fact term="Average">
            <span data-amount>{formatCents(costs.averageCents)}</span> / month
          </Fact>
          <Fact term={`Your ${what} now`}>
            <span data-amount>{formatCents(costs.budgetCents)}</span> / month
          </Fact>
        </dl>
        {costs.budgetTooLow && (
          <p role="status" className="mt-3 rounded-lg border border-warn-line bg-warn px-3 py-2 text-warn-ink">
            Your <span data-amount>{formatCents(costs.budgetCents)}</span> a month is below this year's average of{" "}
            <span data-amount>{formatCents(costs.averageCents)}</span>. Use Change the amount if it should be higher.
          </p>
        )}
      </figcaption>
    </figure>
  );
}

function Fact({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-sm text-muted">{term}</dt>
      <dd className="font-semibold tabular-nums wrap-break-word">{children}</dd>
    </div>
  );
}
