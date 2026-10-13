import { useEffect, useId, useState } from "react";
import { Link } from "react-router";
import {
  PAYOFF_ORDER_LABELS,
  PAYOFF_ORDERS,
  type PayoffOrder,
  PLANNER_YEARS,
  type PlannerComparison,
  type PlannerYears,
  usePlanner,
  useSetPlannerHidden,
} from "../api/planner";
import { EstimateKey } from "../components/Amount";
import { Card } from "../components/Card";
import { AMOUNT_HELP, MoneyField } from "../components/MoneyField";
import { OptionCard } from "../components/planner/OptionCard";
import { PayoffTimeline } from "../components/planner/PayoffTimeline";
import { PlannerFlags } from "../components/planner/PlannerFlags";
import { Segmented } from "../components/Segmented";
import { BUTTON_LINK, INPUT, LABEL, PAGE_TITLE } from "../components/ui";
import { formatMonth } from "../lib/dates";
import { centsToInput, formatCents, parseDollars } from "../lib/money";

const ORDER_OPTIONS = PAYOFF_ORDERS.map((order) => ({ value: order, label: PAYOFF_ORDER_LABELS[order] }));

/** What the screen opens with the first time. */
const DEFAULT_EXTRA_CENTS = 30_000;
const DEFAULT_YEARS: PlannerYears = 5;

/** How long after the last keystroke the amount is asked about, so "300" is one question and not three. */
const TYPING_PAUSE_MS = 400;

const STORAGE_KEY = "budget.planner";

interface Remembered {
  extraCents: number;
  years: PlannerYears;
  order: PayoffOrder;
}

/**
 * The last amount, period and order, remembered on this device like Hide amounts. They are how
 * the screen is being looked at, not part of the budget, so they are not saved with it and
 * changing them is nothing to undo.
 */
function remembered(): Remembered {
  const fallback: Remembered = { extraCents: DEFAULT_EXTRA_CENTS, years: DEFAULT_YEARS, order: "highest_rate" };
  try {
    const kept: unknown = JSON.parse(window.localStorage.getItem(STORAGE_KEY) ?? "null");
    if (typeof kept !== "object" || kept === null) {
      return fallback;
    }
    const { extraCents, years, order } = kept as Record<string, unknown>;
    return {
      extraCents: typeof extraCents === "number" && Number.isSafeInteger(extraCents) && extraCents >= 0 ? extraCents : fallback.extraCents,
      years: PLANNER_YEARS.find((choice) => choice === years) ?? fallback.years,
      order: PAYOFF_ORDERS.find((choice) => choice === order) ?? fallback.order,
    };
  } catch {
    // Storage switched off, or something else's text under the key: start from the usual.
    return fallback;
  }
}

function remember(values: Remembered) {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(values));
  } catch {
    // Nothing is lost but the convenience.
  }
}

/**
 * Debt & decisions (design/planner.png, spec §5.9): where an extra amount each month could go.
 * Every debt and every account that could take it gets a card with what the extra would do
 * there, and the debts are laid out in the order they would be gone. Nothing here changes the
 * budget: it is a comparison, and says so.
 */
export function PlannerPage() {
  const [start] = useState(remembered);
  const [extraText, setExtraText] = useState(centsToInput(start.extraCents));
  const [extraCents, setExtraCents] = useState(start.extraCents);
  const [years, setYears] = useState<PlannerYears>(start.years);
  const [order, setOrder] = useState<PayoffOrder>(start.order);
  // Rates typed on the cards: for this visit only, and never saved to the accounts.
  const [growthBp, setGrowthBp] = useState<Record<number, number>>({});
  const yearsId = useId();

  const typedCents = parseDollars(extraText);
  const extraValid = typedCents !== null && typedCents >= 0;
  useEffect(() => {
    if (!extraValid || typedCents === extraCents) {
      return;
    }
    const waiting = window.setTimeout(() => setExtraCents(typedCents), TYPING_PAUSE_MS);
    return () => window.clearTimeout(waiting);
  }, [extraValid, typedCents, extraCents]);

  useEffect(() => remember({ extraCents, years, order }), [extraCents, years, order]);

  const planner = usePlanner({ extraCents, years, order, growthBp });
  const setHidden = useSetPlannerHidden();

  const assume = (accountId: number, rateBp: number | null) =>
    setGrowthBp((current) => {
      const next = { ...current };
      if (rateBp === null) {
        delete next[accountId];
      } else {
        next[accountId] = rateBp;
      }
      return next;
    });

  return (
    <>
      <div className="mb-4 flex flex-wrap items-end justify-between gap-3 md:mb-6">
        <div>
          <p className="text-sm font-semibold tracking-wide text-muted uppercase">Debt &amp; decisions</p>
          <h1 className={PAGE_TITLE}>Where should extra money go?</h1>
        </div>
        <EstimateKey />
      </div>

      <div className="space-y-4">
        <Card>
          <div className="flex flex-col gap-4 sm:flex-row sm:items-start">
            <div className="sm:w-56">
              <MoneyField
                label="Extra each month"
                value={extraText}
                onChange={setExtraText}
                error={extraText.trim() !== "" && !extraValid ? AMOUNT_HELP : undefined}
              />
            </div>
            <div className="sm:w-44">
              <label htmlFor={yearsId} className={LABEL}>
                Compare over
              </label>
              <select
                id={yearsId}
                value={years}
                onChange={(event) => setYears(PLANNER_YEARS.find((choice) => choice === Number(event.target.value)) ?? DEFAULT_YEARS)}
                className={INPUT}
              >
                {PLANNER_YEARS.map((choice) => (
                  <option key={choice} value={choice}>
                    {choice === 1 ? "1 year" : `${choice} years`}
                  </option>
                ))}
              </select>
            </div>
            <p className="text-sm text-muted sm:flex-1 sm:self-center sm:text-right">
              Uses your balances, rates and payments as they are today, and the growth you assume.
            </p>
          </div>
        </Card>

        {planner.isPending && <p className="text-muted">Working out your options…</p>}
        {planner.error && (
          <Card>
            <p role="alert" className="text-danger">
              {planner.error.message}
            </p>
          </Card>
        )}
        {planner.data && (
          <Comparison
            comparison={planner.data}
            order={order}
            onOrderChange={setOrder}
            onGrowthChange={assume}
            onHide={(accountId, hidden) => setHidden.mutate({ accountId, hidden })}
            hiding={setHidden.isPending}
            hideError={setHidden.error?.message}
          />
        )}

        <p className="text-sm text-muted">
          Estimates for comparing options, not financial advice. Growth rates are your own assumptions, and what a debt costs follows the
          balance, rate and payment entered for it.
        </p>
      </div>
    </>
  );
}

interface ComparisonProps {
  comparison: PlannerComparison;
  order: PayoffOrder;
  onOrderChange: (order: PayoffOrder) => void;
  onGrowthChange: (accountId: number, rateBp: number | null) => void;
  onHide: (accountId: number, hidden: boolean) => void;
  hiding: boolean;
  hideError: string | undefined;
}

function Comparison({ comparison, order, onOrderChange, onGrowthChange, onHide, hiding, hideError }: ComparisonProps) {
  const { options, payoff, hidden, flags, extraCents, years, today } = comparison;
  const nothingAtAll = options.length === 0 && hidden.length === 0 && payoff.debts.length === 0;
  return (
    <>
      <PlannerFlags flags={flags} />

      {hideError && (
        <Card>
          <p role="alert" className="text-danger">
            {hideError}
          </p>
        </Card>
      )}

      {nothingAtAll ? (
        <Card title="Nothing to compare yet">
          <p className="text-muted">
            Once you have a loan, a card carrying a balance, an investment account or a savings account, each one shows here with what extra
            money would do in it.{" "}
            <Link to="/accounts" className="cursor-pointer font-semibold text-accent-ink underline">
              Add an account
            </Link>
          </p>
        </Card>
      ) : options.length === 0 ? (
        <Card title="Everything is left out">
          <p className="text-muted">Every account that could take the extra is left out of the comparison. Bring one back below.</p>
        </Card>
      ) : (
        // One column on a phone, then as many as fit: the cards stay wide enough to read.
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4">
          {options.map((option) => (
            <OptionCard
              key={option.accountId}
              option={option}
              extraCents={extraCents}
              years={years}
              onGrowthChange={(rateBp) => onGrowthChange(option.accountId, rateBp)}
              onHide={() => onHide(option.accountId, true)}
              hiding={hiding}
            />
          ))}
        </div>
      )}

      {payoff.debts.length > 0 && (
        <Card title={`Payoff order with the extra ${formatCents(extraCents)}`}>
          <div className="mb-4 flex flex-col gap-3 lg:flex-row lg:items-end lg:justify-between">
            <p className="text-muted">
              The extra goes to one debt at a time. When it is gone, what was being paid into it rolls into the next.
              {payoff.debtFreeDate !== null && (
                <>
                  {" "}
                  Debt-free by <strong className="text-ink">{formatMonth(payoff.debtFreeDate)}</strong>
                  {payoff.debtFreeDateBefore !== null && payoff.debtFreeDateBefore !== payoff.debtFreeDate && (
                    <> (was {formatMonth(payoff.debtFreeDateBefore)})</>
                  )}
                  {payoff.interestSavedCents !== null && payoff.interestSavedCents > 0 && (
                    <>
                      , with <span data-amount>{formatCents(payoff.interestSavedCents)}</span> less interest
                    </>
                  )}
                  .
                </>
              )}
            </p>
            <div className="lg:w-96 lg:shrink-0">
              <Segmented legend="Pay off first" options={ORDER_OPTIONS} value={order} onChange={onOrderChange} />
            </div>
          </div>
          <PayoffTimeline debts={payoff.debts} today={today} extraCents={extraCents} />
        </Card>
      )}

      {hidden.length > 0 && (
        <details className="rounded-card border border-line bg-surface p-5 md:p-6">
          <summary className="cursor-pointer font-semibold">Left out of the comparison ({hidden.length})</summary>
          <ul className="mt-2 divide-y divide-line">
            {hidden.map((account) => (
              <li key={account.accountId} className="flex flex-wrap items-center justify-between gap-x-3">
                <span className="min-w-0 wrap-break-word">{account.name}</span>
                <button
                  type="button"
                  onClick={() => onHide(account.accountId, false)}
                  disabled={hiding}
                  aria-label={`Bring back ${account.name}`}
                  className={BUTTON_LINK}
                >
                  Bring back
                </button>
              </li>
            ))}
          </ul>
        </details>
      )}
    </>
  );
}
