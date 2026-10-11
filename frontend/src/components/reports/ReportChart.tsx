import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { Report, ReportGroup } from "../../api/reports";
import { isHexColor } from "../../lib/color";
import { formatCents } from "../../lib/money";

/** With names down the side, more bars than this stop being readable; the table has them all. */
const MOST_NAMED_BARS = 12;

/** `2026-03` → `Mar`; with more than one year in view, `Mar '26`. */
function shortMonth(key: string, withYear: boolean): string {
  const [year = "", month = "1"] = key.split("-");
  const name = new Intl.DateTimeFormat("en-US", { month: "short", timeZone: "UTC" }).format(Date.UTC(2000, Number(month) - 1, 1));
  return withYear ? `${name} '${year.slice(2)}` : name;
}

/** `24000000` → `"$240k"`: short enough for an axis. */
function shortDollars(cents: number): string {
  const dollars = cents / 100;
  return Math.abs(dollars) >= 1000 ? `$${(dollars / 1000).toFixed(dollars % 1000 === 0 ? 0 : 1)}k` : `$${Math.round(dollars)}`;
}

/**
 * A report's groups as bars (design/reports.png). Time runs left to right as columns; anything
 * with a name (a category, a payee) is a bar beside its name, largest first, so the names can be
 * read. A bar takes the color the user gave its category, tag or account, and the accent
 * color otherwise. The table beside it says the same in figures.
 */
export function ReportChart({ report }: { report: Report }) {
  const overTime = report.groupBy === "month" || report.groupBy === "year";
  const severalYears = report.from.slice(0, 4) !== report.to.slice(0, 4);
  const groups = overTime ? report.groups : report.groups.slice(0, MOST_NAMED_BARS);
  const data = groups.map((group) => ({
    name: report.groupBy === "month" && group.key !== null ? shortMonth(group.key, severalYears) : group.label,
    label: group.label,
    total: group.totalCents,
    color: color(group),
  }));
  const tick = { fill: "var(--text-muted)", fontSize: 12 };
  const tooltip = (
    <Tooltip
      cursor={{ fill: "var(--bg)" }}
      formatter={(value) => [formatCents(Number(value)), "Spent"]}
      labelFormatter={(_, payload) => String(payload[0]?.payload?.label ?? "")}
      contentStyle={{ backgroundColor: "var(--surface)", borderColor: "var(--border)", borderRadius: 8, color: "var(--text)" }}
    />
  );

  return (
    <figure>
      {/* Tall enough for every named bar to keep a readable row of its own. */}
      <div data-amount className="w-full" style={{ height: overTime ? 280 : Math.max(160, data.length * 36 + 40) }}>
        <ResponsiveContainer width="100%" height="100%">
          {overTime ? (
            <BarChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }} accessibilityLayer={false} barCategoryGap="20%">
              <CartesianGrid stroke="var(--border)" strokeDasharray="2 4" vertical={false} />
              <XAxis dataKey="name" tick={tick} stroke="var(--border)" interval="preserveStartEnd" minTickGap={8} />
              <YAxis tickFormatter={shortDollars} tick={tick} stroke="var(--border)" width={52} />
              {tooltip}
              <Bar dataKey="total" radius={[4, 4, 0, 0]} maxBarSize={48} isAnimationActive={false}>
                {data.map((bar) => (
                  <Cell key={bar.label} fill={bar.color} />
                ))}
              </Bar>
            </BarChart>
          ) : (
            <BarChart data={data} layout="vertical" margin={{ top: 4, right: 12, bottom: 0, left: 0 }} accessibilityLayer={false} barCategoryGap="25%">
              <CartesianGrid stroke="var(--border)" strokeDasharray="2 4" horizontal={false} />
              <XAxis type="number" tickFormatter={shortDollars} tick={tick} stroke="var(--border)" />
              <YAxis type="category" dataKey="name" tick={tick} stroke="var(--border)" width={120} tickFormatter={clipped} />
              {tooltip}
              <Bar dataKey="total" radius={[0, 4, 4, 0]} maxBarSize={24} isAnimationActive={false}>
                {data.map((bar) => (
                  <Cell key={bar.label} fill={bar.color} />
                ))}
              </Bar>
            </BarChart>
          )}
        </ResponsiveContainer>
      </div>
      {!overTime && report.groups.length > MOST_NAMED_BARS && (
        <figcaption className="mt-2 text-sm text-muted">
          The {MOST_NAMED_BARS} largest of {report.groups.length}. Every one is in the table.
        </figcaption>
      )}
    </figure>
  );
}

/** The user's own color for a category, tag or account; the accent color for anything else. */
function color(group: ReportGroup): string {
  return group.color !== null && isHexColor(group.color) ? group.color : "var(--accent)";
}

/** A name short enough for the side of the chart; the tooltip and the table have it whole. */
function clipped(name: string): string {
  return name.length > 18 ? `${name.slice(0, 17)}…` : name;
}
