import { CartesianGrid, ComposedChart, Line, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { AgePoint } from "../../api/retirement";
import { formatCents, shortDollars } from "../../lib/money";
import { formatRate } from "../../lib/rates";
import { CHART_TOOLTIP } from "../ui";

/** An age every 5 years, so a phone's axis isn't crowded. */
function ageTicks(first: number, last: number): number[] {
  const ticks: number[] = [];
  for (let age = Math.ceil(first / 5) * 5; age <= last; age += 5) {
    ticks.push(age);
  }
  return ticks;
}

/**
 * Projected against needed by age (spec §5.11, §8), in today's dollars, the way Debt over time
 * draws its lines: what your savings would be at each age at the expected growth (solid, in the
 * accent color), at the lower and higher growth either side (thin and faded), and what retiring at
 * that age would take (dashed). Where the solid line is above, that age is enough, as long as the
 * years before 60 are covered too. The caption says the same in words.
 */
export function ByAgeChart({
  points,
  earliestAge,
  chosenAge,
  rates,
}: {
  points: readonly AgePoint[];
  earliestAge: number | null;
  chosenAge: number | null;
  rates: { low: number; expected: number; high: number };
}) {
  const first = points[0];
  const last = points.at(-1);
  if (first === undefined || last === undefined) {
    return null;
  }
  const data = points.map((point) => ({
    age: point.age,
    projected: point.projectedCents,
    low: point.projectedLowCents,
    high: point.projectedHighCents,
    needed: point.neededCents,
  }));

  return (
    <figure>
      <p className="mb-2 flex flex-wrap gap-x-4 gap-y-1 text-sm text-muted" aria-hidden>
        <span className="flex items-center gap-2">
          <span className="inline-block h-0.5 w-6 bg-accent" />
          Projected at {formatRate(rates.expected)}
        </span>
        <span className="flex items-center gap-2">
          <span className="inline-block h-px w-6 bg-accent opacity-50" />
          At {formatRate(rates.low)} and {formatRate(rates.high)}
        </span>
        <span className="flex items-center gap-2">
          <span className="inline-block w-6 border-t-2 border-dashed border-muted" />
          Needed to retire then
        </span>
      </p>
      <div data-amount className="h-56 w-full md:h-64">
        <ResponsiveContainer width="100%" height="100%">
          <ComposedChart data={data} margin={{ top: 8, right: 12, bottom: 0, left: 0 }} accessibilityLayer={false}>
            <CartesianGrid stroke="var(--border)" strokeDasharray="2 4" vertical={false} />
            <XAxis
              dataKey="age"
              type="number"
              domain={["dataMin", "dataMax"]}
              ticks={ageTicks(first.age, last.age)}
              tick={{ fill: "var(--text-muted)", fontSize: 12 }}
              stroke="var(--border)"
            />
            <YAxis tickFormatter={shortDollars} tick={{ fill: "var(--text-muted)", fontSize: 12 }} stroke="var(--border)" width={56} domain={[0, "auto"]} />
            <Tooltip
              formatter={(value, name) => [formatCents(Number(value)), name]}
              labelFormatter={(age) => `Age ${String(age)}`}
              {...CHART_TOOLTIP}
            />
            {chosenAge !== null && <ReferenceLine x={chosenAge} stroke="var(--text-muted)" strokeDasharray="2 2" />}
            <Line name="Needed" dataKey="needed" stroke="var(--text-muted)" strokeWidth={2} strokeDasharray="6 4" dot={false} isAnimationActive={false} />
            <Line name={`At ${formatRate(rates.high)}`} dataKey="high" stroke="var(--accent)" strokeOpacity={0.5} strokeWidth={1} dot={false} isAnimationActive={false} />
            <Line name={`At ${formatRate(rates.expected)}`} dataKey="projected" stroke="var(--accent)" strokeWidth={2.5} dot={false} isAnimationActive={false} />
            <Line name={`At ${formatRate(rates.low)}`} dataKey="low" stroke="var(--accent)" strokeOpacity={0.5} strokeWidth={1} dot={false} isAnimationActive={false} />
          </ComposedChart>
        </ResponsiveContainer>
      </div>
      <figcaption className="mt-2 text-sm text-muted">
        From age {first.age} to {last.age}, in today's dollars.{" "}
        {earliestAge !== null
          ? `At ${formatRate(rates.expected)} growth your savings are first enough at ${earliestAge}.`
          : `At ${formatRate(rates.expected)} growth your savings don't reach what's needed by 100.`}
        {chosenAge !== null && ` The thin line marks ${chosenAge}, the age you chose.`}
      </figcaption>
    </figure>
  );
}
