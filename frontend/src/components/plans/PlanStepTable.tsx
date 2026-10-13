import { type CSSProperties, type ReactNode, useEffect, useId, useState } from "react";
import { Link } from "react-router";
import type { PlanDetail, PlanRow, PlanStepView } from "../../api/plans";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { formatChange } from "../../lib/rates";
import { BUTTON_LINK, INPUT } from "../ui";
import { describeRow, STEP_EMPTY, worksOutTo } from "./labels";

/** From `md` one line of columns; below that, the name over a label and a figure to a line. */
const ROW = "grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-1 py-3 md:grid-cols-(--columns) md:gap-x-3";
const HEAD = "hidden border-b border-line pb-2 text-sm font-semibold text-muted md:grid md:grid-cols-(--columns) md:gap-x-3";
/** A figure's name, said beside it on a phone, where there is no row of headings above. */
const SAID = "text-muted md:hidden";
const FIGURE = "text-right tabular-nums";

interface Column {
  heading: string;
  /** How wide it is from `md`, as a grid track. */
  track: string;
  cell: (row: PlanRow) => ReactNode;
  total: (step: PlanStepView) => ReactNode;
}

interface PlanStepTableProps {
  plan: PlanDetail;
  step: PlanStepView;
  /** Set while the plan is a draft of a coming year: amounts can then be typed. */
  onAmount?: (row: PlanRow, amountCents: number | null) => void;
  saving: boolean;
}

/**
 * One step's lines (design/plan-year.png): what each came to, what is suggested, the amount for
 * the plan's year and what that works out to. Which figures lead up to the amount depends on the
 * step: a bill or budget has a history and a trend, pay and transfers only what they are now.
 */
export function PlanStepTable({ plan, step, onAmount, saving }: PlanStepTableProps) {
  const { year, setup } = plan.plan;
  const income = step.step === "income";
  const costs = step.step === "bills" || step.step === "annual" || step.step === "budgets";

  const leading: Column[] = setup
    ? []
    : costs
      ? [
          {
            heading: "Last 12 months",
            track: "minmax(0,1fr)",
            cell: (row) => (row.last12Cents === null ? "—" : formatCents(row.last12Cents)),
            total: (view) => (view.last12Cents === null ? "—" : formatCents(view.last12Cents)),
          },
          {
            heading: "Trend",
            track: "4.5rem",
            cell: (row) => (row.reason === "fixed" ? "fixed" : row.trendBp === null ? "—" : formatChange(row.trendBp)),
            total: (view) => (view.trendBp === null ? "—" : formatChange(view.trendBp)),
          },
          {
            heading: `Suggested ${year}`,
            track: "minmax(0,1fr)",
            cell: (row) => formatCents(row.suggestedCents),
            total: (view) => formatCents(view.suggestedCents),
          },
        ]
      : income
        ? [
            {
              heading: "Last 12 months",
              track: "minmax(0,1fr)",
              cell: (row) => (row.last12Cents === null ? "—" : formatCents(row.last12Cents)),
              total: (view) => (view.last12Cents === null ? "—" : formatCents(view.last12Cents)),
            },
            {
              heading: "A paycheck now",
              track: "minmax(0,1fr)",
              cell: (row) => formatCents(row.currentCents),
              total: () => "",
            },
          ]
        : [
            {
              heading: "A year now",
              track: "minmax(0,1fr)",
              cell: (row) => formatCents(row.currentCents),
              total: (view) => formatCents(view.currentCents),
            },
          ];

  const amountHeading = setup ? "A year" : income ? `${year} take-home, a paycheck` : `Your ${year} amount`;
  // The name, what leads up to the amount, the amount, then what it works out to.
  const columns = {
    "--columns": `minmax(0,1.5fr) ${leading.map((column) => column.track).join(" ")} ${onAmount ? "10.5rem" : "minmax(0,1fr)"} minmax(0,1.3fr)`,
  } as CSSProperties;

  if (step.rows.length === 0) {
    return <p className="font-medium">{STEP_EMPTY[step.step]}</p>;
  }

  return (
    <div style={columns}>
      <div className={HEAD}>
        <span>{income ? "Income" : "Item"}</span>
        {leading.map((column) => (
          <span key={column.heading} className="text-right">
            {column.heading}
          </span>
        ))}
        <span className="text-right">{amountHeading}</span>
        <span>Works out to</span>
      </div>
      <ul className="divide-y divide-line">
        {step.rows.map((row) => (
          <li key={`${row.kind}-${row.id}`} className={ROW}>
            <span className="col-span-2 min-w-0 md:col-span-1">
              <Link
                to={row.kind === "income" ? `/income/${row.id}` : `/recurring/${row.id}`}
                className="font-semibold text-accent-ink underline wrap-break-word"
              >
                {row.name}
              </Link>
              <span className="block text-sm text-muted wrap-break-word">{describeRow(row)}</span>
            </span>
            {leading.map((column) => (
              <Cell key={column.heading} said={column.heading}>
                {column.cell(row)}
              </Cell>
            ))}
            {onAmount ? (
              <PlanAmount row={row} label={`${amountHeading} for ${row.name}`} said={amountHeading} saving={saving} onAmount={onAmount} />
            ) : (
              <Cell said={amountHeading} strong>
                {formatCents(row.chosenCents)}
                {row.yours && <span className="ml-2 text-xs font-bold tracking-wide text-accent-ink uppercase">Yours</span>}
              </Cell>
            )}
            <span className={SAID}>Works out to</span>
            <span data-amount className="text-right text-muted tabular-nums md:text-left">
              {worksOutTo(row)}
            </span>
          </li>
        ))}
      </ul>
      <div className={`${ROW} border-t border-line font-bold`}>
        <span className="col-span-2 md:col-span-1">Total for {year}</span>
        {leading.map((column) => (
          <Cell key={column.heading} said={column.heading}>
            {column.total(step)}
          </Cell>
        ))}
        <Cell said={income ? "Take-home for the year" : amountHeading}>{formatCents(step.chosenCents)}</Cell>
        <span className={SAID}>Works out to</span>
        <span data-amount className="text-right tabular-nums md:text-left">
          {plan.paydays > 0 && `${formatCents(Math.round(step.chosenCents / plan.paydays))} per paycheck`}
        </span>
      </div>
    </div>
  );
}

/** One figure of a row: on a phone its name sits to its left; from `md` the headings above say it. */
function Cell({ said, strong = false, children }: { said: string; strong?: boolean; children: ReactNode }) {
  return (
    <>
      <span className={SAID}>{said}</span>
      <span data-amount className={`${FIGURE} ${strong ? "font-semibold" : ""}`}>
        {children}
      </span>
    </>
  );
}

interface PlanAmountProps {
  row: PlanRow;
  /** The field's full name for a screen reader: "Your 2027 amount for Rent". */
  label: string;
  /** Its short name, shown beside it on a phone. */
  said: string;
  saving: boolean;
  onAmount: (row: PlanRow, amountCents: number | null) => void;
}

/**
 * The amount box of one line. It opens on the suggestion; typing over it makes the amount
 * "yours", saved as the box is left (or on Enter). Emptying it, or "Use suggestion", goes back.
 * A sum can be typed, as in every amount field.
 */
function PlanAmount({ row, label, said, saving, onAmount }: PlanAmountProps) {
  const id = useId();
  const [text, setText] = useState(centsToInput(row.chosenCents));
  const [invalid, setInvalid] = useState(false);

  // The plan's amount can change underneath: another basis, or the suggestions accepted.
  useEffect(() => {
    setText(centsToInput(row.chosenCents));
    setInvalid(false);
  }, [row.chosenCents]);

  const commit = () => {
    if (text.trim() === "") {
      setText(centsToInput(row.suggestedCents));
      setInvalid(false);
      if (row.yours) {
        onAmount(row, null);
      }
      return;
    }
    const cents = parseDollars(text);
    if (cents === null || cents < 0) {
      setInvalid(true);
      return;
    }
    setInvalid(false);
    setText(centsToInput(cents));
    if (cents !== row.chosenCents) {
      onAmount(row, cents);
    }
  };

  return (
    <>
      <label htmlFor={id} className={SAID}>
        {said}
      </label>
      <div className="w-36 justify-self-end md:w-full">
        <div className="relative">
          <span aria-hidden className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-muted">
            $
          </span>
          <input
            id={id}
            aria-label={label}
            value={text}
            onChange={(event) => setText(event.target.value)}
            onBlur={commit}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.currentTarget.blur();
              } else if (event.key === "Escape") {
                setText(centsToInput(row.chosenCents));
                setInvalid(false);
              }
            }}
            inputMode="decimal"
            autoComplete="off"
            aria-invalid={invalid ? true : undefined}
            aria-describedby={invalid ? `${id}-error` : row.yours ? `${id}-yours` : undefined}
            className={`${INPUT} pl-7 text-right font-semibold tabular-nums ${row.yours ? "border-2 border-accent" : ""}`}
          />
        </div>
        {invalid ? (
          <p id={`${id}-error`} role="alert" className="mt-1 text-sm text-danger">
            Please enter an amount like 1,250.00.
          </p>
        ) : (
          row.yours && (
            <p id={`${id}-yours`} className="flex items-center justify-end gap-1 text-sm">
              <span className="text-xs font-bold tracking-wide text-accent-ink uppercase">Yours</span>
              <button type="button" disabled={saving} onClick={() => onAmount(row, null)} className={`${BUTTON_LINK} text-sm`}>
                Use suggestion
              </button>
            </p>
          )
        )}
      </div>
    </>
  );
}
