import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from "recharts";
import type { CategorySpending } from "../../api/dashboard";
import { isHexColor } from "../../lib/color";
import { formatCents } from "../../lib/money";

/** A category's own color; spending with no category is drawn in the muted text color, which follows the theme. */
function colorOf(category: CategorySpending): string {
  return category.color !== null && isHexColor(category.color) ? category.color : "var(--text-muted)";
}

/** A share of the whole as a whole percent: "43%", or "under 1%" for a sliver that is still something. */
function share(cents: number, totalCents: number): string {
  if (totalCents <= 0) {
    return "0%";
  }
  const percent = Math.round((cents * 100) / totalCents);
  return percent === 0 && cents > 0 ? "under 1%" : `${percent}%`;
}

/**
 * Spending by category (design/dashboard.png): a ring with the total in the middle, and under it
 * every category with its share and amount. The list is the chart in words and figures: it names
 * each color, so nothing is told by color alone, and it is what a screen reader hears. Each
 * slice takes the color the user gave its category; a thin gap in the card's own color keeps two
 * neighbors of a similar color apart.
 */
export function SpendingDonut({ categories, totalCents }: { categories: readonly CategorySpending[]; totalCents: number }) {
  const data = categories.filter((category) => category.totalCents > 0).map((category) => ({ ...category, fill: colorOf(category) }));
  return (
    <figure>
      <div data-amount aria-hidden className="relative mx-auto h-56 w-full max-w-64">
        <ResponsiveContainer width="100%" height="100%">
          <PieChart accessibilityLayer={false}>
            <Pie
              data={data}
              dataKey="totalCents"
              nameKey="name"
              innerRadius="62%"
              outerRadius="100%"
              startAngle={90}
              endAngle={-270}
              stroke="var(--surface)"
              strokeWidth={2}
              isAnimationActive={false}
            >
              {data.map((slice) => (
                <Cell key={slice.categoryId ?? "none"} fill={slice.fill} />
              ))}
            </Pie>
            <Tooltip
              formatter={(value, name) => [`${formatCents(Number(value))} · ${share(Number(value), totalCents)}`, name]}
              contentStyle={{ backgroundColor: "var(--surface)", borderColor: "var(--border)", borderRadius: 8, color: "var(--text)" }}
              itemStyle={{ color: "var(--text)" }}
            />
          </PieChart>
        </ResponsiveContainer>
        <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
          <span className="text-sm text-muted">Total</span>
          <span className="text-xl font-bold tabular-nums">{formatCents(totalCents)}</span>
        </div>
      </div>
      <figcaption className="mt-4">
        <p className="sr-only">Total {formatCents(totalCents)}</p>
        <ul className="divide-y divide-line">
          {categories.map((category) => (
            <li key={category.categoryId ?? "none"} className="flex items-center gap-3 py-2">
              <span aria-hidden className="size-3 shrink-0 rounded-sm" style={{ backgroundColor: colorOf(category) }} />
              <span className="min-w-0 flex-1 wrap-break-word">{category.name}</span>
              <span className="shrink-0 text-muted tabular-nums">{share(category.totalCents, totalCents)}</span>
              <span data-amount className="w-24 shrink-0 text-right font-semibold tabular-nums">
                {formatCents(category.totalCents)}
              </span>
            </li>
          ))}
        </ul>
      </figcaption>
    </figure>
  );
}
