import { EyeOff } from "lucide-react";
import { type ReactNode, useEffect, useId, useState } from "react";
import { Link } from "react-router";
import type { PlannerOption } from "../../api/planner";
import { formatDate, formatMonth } from "../../lib/dates";
import { formatCents } from "../../lib/money";
import { formatRate, parseRate, rateToInput } from "../../lib/rates";
import { Amount } from "../Amount";
import { BUTTON_LINK } from "../ui";

/** The most the backend takes as an assumed rate: 100% a year. */
const MAX_GROWTH_BP = 10_000;

const EYEBROW: Record<PlannerOption["kind"], string> = { debt: "Pay debt", invest: "Invest", save: "Save" };

interface OptionCardProps {
  option: PlannerOption;
  extraCents: number;
  years: number;
  /** Called with a rate to assume for this comparison, or `null` to go back to the account's own. */
  onGrowthChange: (rateBp: number | null) => void;
  onHide: () => void;
  hiding: boolean;
}

/**
 * One place the extra could go (design/planner.png): a debt it would pay down, or an account it
 * would grow in. Every figure is an estimate, so each sits in the estimate box. The account's
 * own color marks the card; its name opens the account.
 */
export function OptionCard({ option, extraCents, years, onGrowthChange, onHide, hiding }: OptionCardProps) {
  const headingId = useId();
  return (
    <section
      aria-labelledby={headingId}
      className="flex min-w-0 flex-col rounded-card border border-line border-t-4 bg-surface p-5"
      style={{ borderTopColor: option.color }}
    >
      <p className="text-sm font-semibold tracking-wide text-muted uppercase">{EYEBROW[option.kind]}</p>
      <h3 id={headingId} className="text-lg font-semibold wrap-break-word">
        <Link to={`/accounts/${option.accountId}`} className="cursor-pointer hover:underline">
          {option.name}
        </Link>
      </h3>
      <div className="flex-1">
        {option.kind === "debt" ? (
          <Debt option={option} extraCents={extraCents} />
        ) : (
          <Growing option={option} extraCents={extraCents} years={years} onGrowthChange={onGrowthChange} />
        )}
      </div>
      <button
        type="button"
        onClick={onHide}
        disabled={hiding}
        aria-label={`Leave out ${option.name}`}
        className={`${BUTTON_LINK} mt-2 flex items-center gap-1.5 self-start px-0 text-sm`}
      >
        <EyeOff aria-hidden className="size-4" />
        Leave out
      </button>
    </section>
  );
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-wrap items-baseline justify-between gap-x-3 py-1">
      <dt className="text-muted">{label}</dt>
      <dd className="text-right font-semibold">{children}</dd>
    </div>
  );
}

function Headline({ cents, children }: { cents: number; children: ReactNode }) {
  return (
    <div className="my-3">
      <Amount cents={cents} estimate className="text-2xl font-bold" />
      <p className="mt-1 text-sm text-muted">{children}</p>
    </div>
  );
}

function Debt({ option, extraCents }: { option: PlannerOption; extraCents: number }) {
  const owed = (
    <p className="text-sm text-muted">
      <span data-amount>{formatCents(option.balanceCents)}</span> owed
      {option.rateBp === null ? " · no rate entered, so compared at 0%" : ` at ${formatRate(option.rateBp)}`}
      {option.rateChangesOn !== null && option.rateAfterBp !== null && (
        <>
          {" "}
          until {formatDate(option.rateChangesOn)}, then {formatRate(option.rateAfterBp)}
        </>
      )}
    </p>
  );
  if (option.problem === "no_payment") {
    return (
      <>
        {owed}
        <p className="mt-3">
          Budget needs this loan's monthly payment before it can work out what extra would do.{" "}
          <Link to={`/accounts/${option.accountId}`} className="cursor-pointer font-semibold text-accent-ink underline">
            Enter it on the loan's page
          </Link>
        </p>
      </>
    );
  }
  const payment = <span data-amount>{formatCents(option.paymentCents ?? 0)}</span>;
  return (
    <>
      {owed}
      {option.interestSavedCents !== null ? (
        <Headline cents={option.interestSavedCents}>
          you'd save in interest by putting the extra <span data-amount>{formatCents(extraCents)}</span> a month here
        </Headline>
      ) : option.paysOff ? (
        <p className="my-3">
          At {payment} a month this is never paid off: the interest is more than the payment. With the extra it is gone by{" "}
          <strong>{option.payoffDate === null ? "then" : formatMonth(option.payoffDate)}</strong>.
        </p>
      ) : (
        <p className="my-3">
          Even with the extra, what is paid each month does not get ahead of the interest, so no payoff date can be given.
        </p>
      )}
      <dl className="border-t border-line pt-2 text-sm">
        <Row label="Return">{option.rateBp === null ? "—" : `${formatRate(option.rateAfterBp ?? option.rateBp)} guaranteed`}</Row>
        <Row label="Paid each month now">{payment}</Row>
        {option.payoffDate !== null && (
          <Row label="Debt-free date">
            {formatMonth(option.payoffDate)}
            {option.payoffDateBefore !== null && option.payoffDateBefore !== option.payoffDate && <> (was {formatMonth(option.payoffDateBefore)})</>}
          </Row>
        )}
        {option.monthsSooner !== null && option.monthsSooner > 0 && <Row label="Sooner by">{duration(option.monthsSooner)}</Row>}
      </dl>
      {option.payoffDate !== null && option.freedCents !== null && extraCents > 0 && (
        <p className="mt-2 text-sm text-muted">
          Then <span data-amount>{formatCents(option.freedCents)}</span> a month is free for the next goal.
        </p>
      )}
    </>
  );
}

/** `38` → `"3 yrs 2 mo"`. */
export function duration(months: number): string {
  const years = Math.floor(months / 12);
  const rest = months % 12;
  const yearPart = years === 0 ? "" : years === 1 ? "1 yr" : `${years} yrs`;
  const monthPart = rest === 0 ? "" : `${rest} mo`;
  return [yearPart, monthPart].filter((part) => part !== "").join(" ") || "0 mo";
}

function Growing({
  option,
  extraCents,
  years,
  onGrowthChange,
}: {
  option: PlannerOption;
  extraCents: number;
  years: number;
  onGrowthChange: (rateBp: number | null) => void;
}) {
  const inputId = useId();
  const [typed, setTyped] = useState(option.rateBp === null ? "" : rateToInput(option.rateBp));
  const [invalid, setInvalid] = useState(false);
  // The rate in the box follows the answer: Undo, or a new rate on the account, changes it from outside.
  useEffect(() => {
    setTyped(option.rateBp === null ? "" : rateToInput(option.rateBp));
    setInvalid(false);
  }, [option.rateBp]);

  const commit = () => {
    if (typed.trim() === "") {
      setInvalid(false);
      onGrowthChange(null);
      return;
    }
    const rateBp = parseRate(typed);
    if (rateBp === null || rateBp > MAX_GROWTH_BP) {
      setInvalid(true);
      return;
    }
    setInvalid(false);
    onGrowthChange(rateBp === option.accountRateBp ? null : rateBp);
  };

  const rateName = option.kind === "invest" ? "Assumed growth" : "Assumed APY";
  const changed = option.rateBp !== option.accountRateBp;
  const span = years === 1 ? "1 year" : `${years} years`;
  const matched = (option.matchPerMonthCents ?? 0) > 0;
  // What the extra gains, not what it comes to: the rest is only the money put in coming back
  // (Rachel, 2026-10-14: each card should say "how much potential you'd earn").
  const earnedCents = (option.growthCents ?? 0) + (option.matchCents ?? 0);
  return (
    <>
      <div className="mt-1 flex flex-wrap items-center gap-2 text-sm">
        <label htmlFor={inputId} className="text-muted">
          {rateName}
        </label>
        <input
          id={inputId}
          inputMode="decimal"
          value={typed}
          onChange={(event) => setTyped(event.target.value)}
          onBlur={commit}
          onKeyDown={(event) => {
            if (event.key === "Enter") {
              commit();
            }
          }}
          aria-invalid={invalid}
          aria-describedby={invalid ? `${inputId}-error` : undefined}
          // As wide as a rate, not the card: "% a year" stays on its line.
          className="min-h-11 w-20 rounded-lg border border-line bg-surface px-3 text-right tabular-nums"
        />
        <span className="text-muted">% a year</span>
      </div>
      {invalid && (
        <p id={`${inputId}-error`} role="alert" className="mt-1 text-sm text-danger">
          Please enter a rate between 0 and 100, like 6.5.
        </p>
      )}
      {changed && option.accountRateBp !== null && (
        <button type="button" onClick={() => onGrowthChange(null)} className={`${BUTTON_LINK} px-0 text-sm`}>
          Use the account's {formatRate(option.accountRateBp)}
        </button>
      )}
      {option.rateBp === null && (
        <p className="mt-1 text-sm text-muted">This account has no rate yet, so nothing is added for growth. Type one to try it.</p>
      )}
      <Headline cents={earnedCents}>
        you'd earn{matched ? ", with the match," : ""} by putting the extra <span data-amount>{formatCents(extraCents)}</span> a month here for {span}
      </Headline>
      <dl className="border-t border-line pt-2 text-sm">
        <Row label="Return">
          {option.rateBp === null ? "—" : `${formatRate(option.rateBp)} assumed`}
          {matched && " + match"}
        </Row>
        <Row label="You put in">
          <span data-amount>{formatCents(option.putInCents ?? 0)}</span>
        </Row>
        {matched && (
          <Row label="Employer match">
            <span data-amount>{formatCents(option.matchCents ?? 0)}</span>
          </Row>
        )}
        <Row label={option.kind === "invest" ? "Assumed growth" : "Interest"}>
          <span data-amount>{formatCents(option.growthCents ?? 0)}</span>
        </Row>
        <Row label={`Comes to after ${span}`}>
          <span data-amount>{formatCents(option.valueCents ?? 0)}</span>
        </Row>
      </dl>
      {matched && (
        <p className="mt-2 text-sm text-muted">
          <span data-amount>{formatCents(option.matchedPerMonthCents ?? 0)}</span> of the <span data-amount>{formatCents(extraCents)}</span> earns{" "}
          <span data-amount>{formatCents(option.matchPerMonthCents ?? 0)}</span> of match each month.
        </p>
      )}
    </>
  );
}
