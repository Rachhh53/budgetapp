import { useEffect, useId, useState } from "react";
import { CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import {
  type DebtOrInvest,
  MOST_PLAN_YEARS,
  PAYOFF_ORDER_LABELS,
  type PayoffOrder,
  type PlanKind,
  type PlanOutcome,
  type PlanPoint,
  type PlanSettings,
} from "../../api/planner";
import { formatMonth, yearTicks } from "../../lib/dates";
import { formatCents, shortDollars } from "../../lib/money";
import { formatRate } from "../../lib/rates";
import { Amount } from "../Amount";
import { Card } from "../Card";
import { Segmented } from "../Segmented";
import { BUTTON_LINK, INPUT, LABEL } from "../ui";

/** How long after a slider stops moving the plans are asked for again, so a drag is one question and not fifty. */
const SLIDING_PAUSE_MS = 250;

/** The growth slider's top, unless the account's own rate is higher. */
const MOST_GROWTH_BP = 1500;
const GROWTH_STEP_BP = 25;
const SPLIT_STEP_BP = 500;

const YEAR = new Intl.DateTimeFormat("en-US", { year: "numeric", timeZone: "UTC" });

/**
 * Each plan's line: a color and a dash of its own, so which is which never rests on telling hues apart, and the key
 * and the cards say the same in words.
 */
const LINES: Record<
  PlanKind,
  {
    key: "investAllCents" | "payOffFirstCents" | "splitCents";
    invested: "investAllInvestedCents" | "payOffFirstInvestedCents" | "splitInvestedCents";
    owed: "investAllOwedCents" | "payOffFirstOwedCents" | "splitOwedCents";
    color: string;
    dash?: string;
    title: string;
  }
> = {
  invest_all: { key: "investAllCents", invested: "investAllInvestedCents", owed: "investAllOwedCents", color: "var(--estimate-text)", title: "Invest it all" },
  pay_off_first: {
    key: "payOffFirstCents",
    invested: "payOffFirstInvestedCents",
    owed: "payOffFirstOwedCents",
    color: "var(--accent)",
    dash: "8 4",
    title: "Pay off debt, then invest",
  },
  split: { key: "splitCents", invested: "splitInvestedCents", owed: "splitOwedCents", color: "var(--warn-text)", dash: "2 3", title: "Your split" },
};

/** A month on the chart: what is drawn, and the month as it is, for the tooltip to add up. */
interface ChartMonth {
  at: number;
  investAllCents: number;
  payOffFirstCents: number;
  splitCents: number;
  point: PlanPoint;
}

/** The chart either shows what each plan comes to, or how far each is from investing it all, where close lines part. */
const CHART_OPTIONS = [
  { value: "total", label: "What each comes to" },
  { value: "difference", label: "Difference from investing it all" },
] as const;
type ChartMode = (typeof CHART_OPTIONS)[number]["value"];

const FREED_OPTIONS = [
  { value: "moves", label: "Stays in the plan" },
  { value: "back", label: "Goes back to your budget" },
] as const;

function percent(bp: number): string {
  return `${Math.round(bp / 100)}%`;
}

function years(count: number): string {
  return count === 1 ? "1 year" : `${count} years`;
}

/** Months are plotted as UTC midnights, so the PC's time zone can never shift one. */
function toTime(iso: string): number {
  return Date.parse(`${iso}T00:00:00Z`);
}

/**
 * Pay off debt or invest? (spec §5.9; Rachel, 2026-10-23: "if a debt is paid after 8 years and I set the dropdown to
 * 10 then the investment is accruing for longer than the debt so it's not really apples to apples"; "maybe some
 * sliders. I want a lot of options"). Three plans spend the same each month and are measured on the same day: invest
 * it all, pay the debts off first and then invest, and a split chosen with a slider. Every choice moves the cards and
 * the chart; nothing is saved to the budget.
 */
export function DebtOrInvestCard({
  view,
  debts,
  extraCents,
  order,
  onChange,
}: {
  view: DebtOrInvest;
  /** The debts in the plans, with what each owes today, named under the chart so its starting point makes sense. */
  debts: readonly { name: string; balanceCents: number }[];
  extraCents: number;
  order: PayoffOrder;
  onChange: (settings: PlanSettings) => void;
}) {
  const id = useId();
  // The sliders move at once; the plans are asked for once they rest.
  const [split, setSplit] = useState(view.splitBp ?? 5000);
  const [growth, setGrowth] = useState(view.growthBp ?? 0);
  const [span, setSpan] = useState(view.years ?? 10);
  const [chart, setChart] = useState<ChartMode>("total");
  useEffect(() => setSplit(view.splitBp ?? 5000), [view.splitBp]);
  useEffect(() => setGrowth(view.growthBp ?? 0), [view.growthBp]);
  useEffect(() => setSpan(view.years ?? 10), [view.years]);
  useEffect(() => {
    if (split === view.splitBp && growth === view.growthBp && span === view.years) {
      return;
    }
    const waiting = window.setTimeout(() => onChange({ splitBp: split, growthBp: growth, years: span }), SLIDING_PAUSE_MS);
    return () => window.clearTimeout(waiting);
  }, [split, growth, span, view.splitBp, view.growthBp, view.years, onChange]);

  if (view.reason !== null) {
    return (
      <Card title="Pay off debt or invest?" tip="debtOrInvest">
        <p className="text-muted">
          {view.reason === "no_debts"
            ? "Once a loan with its monthly payment, or a card carrying a balance, is here, this weighs paying it off against investing, both measured on the same day."
            : "Once an investment or savings account is here (and not left out), this weighs paying debts off against investing in it."}
        </p>
      </Card>
    );
  }

  const choice = view.choices.find((candidate) => candidate.accountId === view.investAccountId);
  const growthTop = Math.max(MOST_GROWTH_BP, view.accountGrowthBp ?? 0, growth);
  const toDebts = Math.round((extraCents * split) / 10_000);
  const bestSplit = view.bestSplitBp;
  const plans = view.plans;
  const owedToday = view.owedTodayCents ?? 0;
  const data: ChartMonth[] = view.points.map((point) =>
    chart === "total"
      ? { at: toTime(point.month), investAllCents: point.investAllCents, payOffFirstCents: point.payOffFirstCents, splitCents: point.splitCents, point }
      : {
          at: toTime(point.month),
          investAllCents: 0,
          payOffFirstCents: point.payOffFirstCents - point.investAllCents,
          splitCents: point.splitCents - point.investAllCents,
          point,
        },
  );

  return (
    <Card title="Pay off debt or invest?" tip="debtOrInvest">
      <p className="text-muted">
        Every plan spends the same <span data-amount className="font-semibold text-ink">{formatCents(view.monthlyCents ?? 0)}</span> a month:
        your debts' payments and the extra. Each is measured after the same {years(view.years ?? span)} by how much better off it leaves you
        than today: the new money invested, with its growth, plus the debt paid down.
      </p>

      <div className="mt-4 grid grid-cols-1 gap-x-6 gap-y-4 md:grid-cols-2">
        <div>
          <label htmlFor={`${id}-into`} className={LABEL}>
            Invest in
          </label>
          <select
            id={`${id}-into`}
            value={view.investAccountId ?? ""}
            // A new account starts at its own growth.
            onChange={(event) => onChange({ investAccountId: Number(event.target.value), growthBp: undefined })}
            className={INPUT}
          >
            {view.choices.map((candidate) => (
              <option key={candidate.accountId} value={candidate.accountId}>
                {candidate.name}
                {candidate.growthBp !== null ? ` · ${formatRate(candidate.growthBp)}` : ""}
              </option>
            ))}
          </select>
        </div>
        <Slider
          id={`${id}-years`}
          label="Measured after"
          value={span}
          min={1}
          max={MOST_PLAN_YEARS}
          step={1}
          shown={years(span)}
          onChange={setSpan}
        />
        <div>
          <Slider
            id={`${id}-growth`}
            label={`Growth of ${choice?.name ?? "the investment"} a year`}
            value={growth}
            min={0}
            max={growthTop}
            step={GROWTH_STEP_BP}
            shown={formatRate(growth)}
            onChange={setGrowth}
          />
          {view.accountGrowthBp !== null && growth !== view.accountGrowthBp && (
            <button type="button" onClick={() => setGrowth(view.accountGrowthBp ?? 0)} className={`${BUTTON_LINK} -ml-2`}>
              Back to its own {formatRate(view.accountGrowthBp)}
            </button>
          )}
        </div>
        <div>
          <Slider
            id={`${id}-split`}
            label="Your split: to debts"
            value={split}
            min={0}
            max={10_000}
            step={SPLIT_STEP_BP}
            shown={`${percent(split)} to debts · ${percent(10_000 - split)} invested`}
            onChange={setSplit}
          />
          <p className="text-sm text-muted">
            <span data-amount>{formatCents(toDebts)}</span> of the extra to debts and <span data-amount>{formatCents(extraCents - toDebts)}</span>{" "}
            invested, and the same split of any payment a paid-off debt frees.
          </p>
        </div>
        <div className="md:col-span-2">
          <Segmented
            legend="When a debt is paid off, its payment"
            options={FREED_OPTIONS}
            value={view.freedMoves === false ? "back" : "moves"}
            onChange={(value) => onChange({ freedMoves: value === "moves" })}
          />
        </div>
      </div>

      <ul aria-label="The three plans" className="mt-5 grid grid-cols-1 gap-3 md:grid-cols-3">
        {plans.map((plan) => (
          <PlanTile key={plan.kind} plan={plan} best={plan.kind === view.best} span={view.years ?? span} order={order} />
        ))}
      </ul>

      {bestSplit !== null && (
        <p role="status" className="mt-4">
          Over {years(view.years ?? span)}, the most comes from putting <strong>{percent(bestSplit)}</strong> of the spare money to debts
          {bestSplit === 0 ? " (investing it all)" : bestSplit === 10_000 ? " (paying them off first)" : ""}.
          {bestSplit !== view.splitBp && view.bestSplitNetDiffCents !== null && view.bestSplitNetDiffCents > 0 && (
            <>
              {" "}
              That is <span data-amount className="font-semibold">{formatCents(view.bestSplitNetDiffCents)}</span> more than your split.{" "}
              <button type="button" onClick={() => setSplit(bestSplit)} className={`${BUTTON_LINK} -ml-1`}>
                Try {percent(bestSplit)}
              </button>
            </>
          )}
        </p>
      )}

      <figure className="mt-5 border-t border-line pt-4">
        <div className="mb-2 flex flex-col gap-2 lg:flex-row lg:items-end lg:justify-between">
          <h3 className="font-semibold">{chart === "total" ? "How much better off each plan leaves you, month by month" : "How far each plan is ahead of investing it all"}</h3>
          <div className="lg:w-[30rem] lg:shrink-0">
            <Segmented legend="Show" options={CHART_OPTIONS} value={chart} onChange={setChart} />
          </div>
        </div>
        <div className="mb-3 rounded-lg bg-bg p-3 text-sm">
          <p className="font-semibold">How to read this</p>
          {chart === "total" ? (
            <ul className="mt-1 list-disc space-y-1 pl-5">
              <li>Each line is one plan. Along the bottom are the years; up the side, how much better off than today that plan leaves you.</li>
              <li>
                Better off means the new money invested by then, with its growth{plans.some((plan) => plan.matchCents > 0) ? " and the match" : ""}, plus
                how much of these debts is paid down. Every line starts at $0 today.
              </li>
              <li>
                It isn't your net worth: what you already own (your home, savings, investments) isn't in it. The debts are{" "}
                {debts.map((debt, index) => (
                  <span key={`${debt.name}-${index}`}>
                    {index > 0 && (index === debts.length - 1 ? " and " : ", ")}
                    {debt.name} (<span data-amount>{formatCents(debt.balanceCents)}</span>)
                  </span>
                ))}
                , <span data-amount>{formatCents(owedToday)}</span> today.
              </li>
              <li>The higher line is ahead at that point. Where two lines cross, one plan overtakes the other. Point at a month to see how it adds up.</li>
            </ul>
          ) : (
            <ul className="mt-1 list-disc space-y-1 pl-5">
              <li>Investing it all is the flat line at $0, the plan the others are measured against.</li>
              <li>Above $0, a plan leaves you that much better off than investing it all at that point; below $0, that much worse off.</li>
              <li>Point at a month to see each plan's figures.</li>
            </ul>
          )}
        </div>
        <p className="mb-2 flex flex-wrap gap-x-4 gap-y-1 text-sm text-muted" aria-hidden>
          {plans.map((plan) => (
            <span key={plan.kind} className="flex items-center gap-2">
              <svg width="24" height="4" className="shrink-0">
                <line x1="0" y1="2" x2="24" y2="2" stroke={LINES[plan.kind].color} strokeWidth="2.5" strokeDasharray={LINES[plan.kind].dash} />
              </svg>
              {title(plan)}
            </span>
          ))}
        </p>
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
              <YAxis tickFormatter={shortDollars} tick={{ fill: "var(--text-muted)", fontSize: 12 }} stroke="var(--border)" width={60} />
              <ReferenceLine y={0} stroke="var(--text-muted)" />
              <Tooltip
                content={({ active, payload }) => {
                  const month = payload?.[0]?.payload as ChartMonth | undefined;
                  return active && month ? <MonthTip month={month} plans={plans} owedToday={owedToday} difference={chart === "difference"} /> : null;
                }}
              />
              {plans.map((plan) => (
                <Line
                  key={plan.kind}
                  name={title(plan)}
                  dataKey={LINES[plan.kind].key}
                  stroke={LINES[plan.kind].color}
                  strokeWidth={2.5}
                  strokeDasharray={LINES[plan.kind].dash}
                  dot={false}
                  isAnimationActive={false}
                />
              ))}
            </LineChart>
          </ResponsiveContainer>
        </div>
        <figcaption className="sr-only">
          {plans.map((plan) => `${title(plan)}: ${formatCents(plan.betterOffCents)} better off after ${years(view.years ?? span)}.`).join(" ")}
        </figcaption>
      </figure>
    </Card>
  );
}

/** A month, added up for each plan: what is invested, plus the debt paid down, makes how much better off. */
function MonthTip({ month, plans, owedToday, difference }: { month: ChartMonth; plans: PlanOutcome[]; owedToday: number; difference: boolean }) {
  const base = month.point.investAllCents;
  return (
    // Narrow on a phone, so it stays inside the chart there.
    <div className="w-56 rounded-lg border border-line bg-surface p-2 text-xs text-ink shadow-sm sm:w-auto sm:max-w-xs sm:p-3 sm:text-sm">
      <p className="font-semibold">{formatMonth(month.point.month)}</p>
      {plans.map((plan) => {
        const lines = LINES[plan.kind];
        const invested = month.point[lines.invested];
        const owed = month.point[lines.owed];
        const better = month.point[lines.key];
        return (
          <div key={plan.kind} className="mt-2">
            <p className="font-medium" style={{ color: lines.color }}>
              {title(plan)}
            </p>
            <p data-amount className="tabular-nums">
              {formatCents(invested)} invested + {formatCents(owedToday - owed)} paid down = <strong>{formatCents(better)}</strong> better off
            </p>
            <p data-amount className="text-muted tabular-nums">
              still owing {formatCents(owed)}
              {difference && plan.kind !== "invest_all" && ` · ${better >= base ? "ahead of" : "behind"} investing it all by ${formatCents(Math.abs(better - base))}`}
            </p>
          </div>
        );
      })}
    </div>
  );
}

function title(plan: PlanOutcome): string {
  return plan.kind === "split" ? `Your split: ${percent(plan.debtShareBp)} to debts` : LINES[plan.kind].title;
}

function PlanTile({ plan, best, span, order }: { plan: PlanOutcome; best: boolean; span: number; order: PayoffOrder }) {
  const how =
    plan.kind === "invest_all"
      ? "Debts stay on their own payments."
      : plan.kind === "pay_off_first"
        ? `${PAYOFF_ORDER_LABELS[order]}, then everything invested.`
        : "Both at once, then everything invested once the debts are gone.";
  return (
    <li className={`rounded-lg border p-4 ${best ? "border-accent ring-2 ring-accent" : "border-line"}`}>
      <p className="font-semibold">{title(plan)}</p>
      <p className="text-sm text-muted">{how}</p>
      {best && <p className="mt-1 text-sm font-semibold text-accent-ink">Comes to the most</p>}
      <p className="mt-3 text-sm text-muted">Better off after {years(span)} by</p>
      <Amount cents={plan.betterOffCents} estimate className="-ml-2 text-2xl font-bold" />
      <dl className="mt-2 grid grid-cols-[1fr_auto] gap-x-3 gap-y-1 text-sm tabular-nums">
        <dt className="text-muted">New money invested</dt>
        <dd data-amount className="text-right">
          {formatCents(plan.investedCents)}
        </dd>
        <dt className="pl-3 text-muted">put in</dt>
        <dd data-amount className="text-right">
          {formatCents(plan.putInCents)}
        </dd>
        <dt className="pl-3 text-muted">growth</dt>
        <dd data-amount className="text-right">
          {formatCents(plan.growthCents)}
        </dd>
        {plan.matchCents > 0 && (
          <>
            <dt className="pl-3 text-muted">employer match</dt>
            <dd data-amount className="text-right">
              {formatCents(plan.matchCents)}
            </dd>
          </>
        )}
        <dt className="text-muted">Debt paid down</dt>
        <dd data-amount className="text-right">
          {formatCents(plan.paidDownCents)}
        </dd>
        <dt className="text-muted">Still owed at the end</dt>
        <dd data-amount className="text-right">
          {formatCents(plan.owedCents)}
        </dd>
        <dt className="text-muted">Interest paid</dt>
        <dd data-amount className="text-right">
          {formatCents(plan.interestCents)}
        </dd>
        <dt className="text-muted">Debt-free</dt>
        <dd className="text-right">{plan.debtFreeDate === null ? "Not yet" : formatMonth(plan.debtFreeDate)}</dd>
      </dl>
    </li>
  );
}

/** A slider with what it is set to beside its label; the arrow keys move it a step at a time. */
function Slider({
  id,
  label,
  value,
  min,
  max,
  step,
  shown,
  onChange,
}: {
  id: string;
  label: string;
  value: number;
  min: number;
  max: number;
  step: number;
  shown: string;
  onChange: (value: number) => void;
}) {
  return (
    <div>
      <div className="flex flex-wrap items-baseline justify-between gap-x-3">
        <label htmlFor={id} className="font-medium">
          {label}
        </label>
        <span className="font-semibold tabular-nums">{shown}</span>
      </div>
      <input
        id={id}
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        aria-valuetext={shown}
        onChange={(event) => onChange(Number(event.target.value))}
        className="h-11 w-full cursor-pointer accent-(--accent)"
      />
    </div>
  );
}
