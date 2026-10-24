import { Calculator, Pencil } from "lucide-react";
import { type CSSProperties, type ReactNode, useEffect, useId, useRef, useState } from "react";
import { Link } from "react-router";
import type { PlanDetail, PlanRow, PlanStepView } from "../../api/plans";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { formatDate } from "../../lib/dates";
import { formatChange, formatRate } from "../../lib/rates";
import { MoneyCalculator } from "../MoneyCalculator";
import { BUTTON_LINK, INPUT } from "../ui";
import { describeRow, STEP_EMPTY, worksOutTo } from "./labels";

/**
 * From `md` one line of columns; below that, the name over a label and a figure to a line. The label keeps at least
 * 8rem on a phone, so a long figure ("$59,668.80 a year · 24 paychecks") wraps instead of squeezing it to a word a line.
 */
const ROW = "grid grid-cols-[minmax(8rem,1fr)_auto] items-center gap-x-4 gap-y-1 py-3 md:grid-cols-(--columns) md:gap-x-3";
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
  /** Set with `onAmount`: opens an income line's whole paycheck for the plan's year. */
  onEditPay?: (row: PlanRow) => void;
  saving: boolean;
}

/**
 * One step's lines (design/plan-year.png): what each came to, what is suggested, the amount for
 * the plan's year and what that works out to. Which figures lead up to the amount depends on the
 * step: a bill or budget has a history and a trend, pay and transfers only what they are now.
 *
 * A plan of next year changes nothing real, so its lines do not lead to the real income source or
 * item (Rachel, 2026-10-22: "I wanted to change my income for NEXT YEAR AND IT CHANGED IT FOR THIS
 * YEAR"): a paycheck is changed in the plan's own form, an item in its amount box. Only first-time
 * setup, which is the budget itself, links to them.
 */
export function PlanStepTable({ plan, step, onAmount, onEditPay, saving }: PlanStepTableProps) {
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
            cell: (row) =>
              row.last12Cents === null ? (
                "—"
              ) : row.historyMonths !== null ? (
                // Fewer months than twelve: what they came to, and how many, so the suggestion beside it adds up.
                <>
                  {formatCents(row.last12Cents)}
                  <span className="block text-xs text-muted">in {row.historyMonths} months</span>
                </>
              ) : (
                formatCents(row.last12Cents)
              ),
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
  // The line whose calculator is open, and what it starts from: one at a time, across the whole line, as every money
  // field has one (Rachel, 2026-10-23: "why no calculator on planning inputs").
  const [calculating, setCalculating] = useState<{ key: string; startCents: number } | null>(null);
  // The name, what leads up to the amount, the amount, then what it works out to.
  const columns = {
    "--columns": `minmax(0,1.5fr) ${leading.map((column) => column.track).join(" ")} ${onAmount ? "13rem" : "minmax(0,1fr)"} minmax(0,1.3fr)`,
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
              {setup ? (
                <Link
                  to={row.kind === "income" ? `/income/${row.id}` : `/recurring/${row.id}`}
                  className="font-semibold text-accent-ink underline wrap-break-word"
                >
                  {row.name}
                </Link>
              ) : (
                <span className="font-semibold wrap-break-word">{row.name}</span>
              )}
              <span className="block text-sm text-muted wrap-break-word">{describeRow(row)}</span>
            </span>
            {leading.map((column) => (
              <Cell key={column.heading} said={column.heading}>
                {column.cell(row)}
              </Cell>
            ))}
            {onAmount && onEditPay && row.kind === "income" ? (
              <PlanPaycheck row={row} year={year} said={amountHeading} saving={saving} onEdit={onEditPay} onAmount={onAmount} />
            ) : onAmount ? (
              <PlanAmount
                row={row}
                label={`${amountHeading} for ${row.name}`}
                said={amountHeading}
                saving={saving}
                onAmount={onAmount}
                calculating={calculating?.key === `${row.kind}-${row.id}`}
                onCalculate={(startCents) =>
                  setCalculating((open) => (open?.key === `${row.kind}-${row.id}` ? null : { key: `${row.kind}-${row.id}`, startCents }))
                }
              />
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
            {onAmount && calculating?.key === `${row.kind}-${row.id}` && (
              <div className="col-span-full w-full max-w-sm justify-self-end">
                <MoneyCalculator
                  label={`${amountHeading} for ${row.name}`}
                  startCents={calculating.startCents}
                  onUse={(cents) => {
                    setCalculating(null);
                    if (cents !== row.chosenCents) {
                      onAmount(row, cents);
                    }
                  }}
                  onCancel={() => setCalculating(null)}
                />
              </div>
            )}
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

interface PlanPaycheckProps {
  row: PlanRow;
  year: number;
  /** Its short name, shown beside it on a phone. */
  said: string;
  saving: boolean;
  onEdit: (row: PlanRow) => void;
  onAmount: (row: PlanRow, amountCents: number | null) => void;
}

/**
 * An income line's take-home in a draft, with a visible Edit that opens the whole paycheck for the
 * plan's year (gross, deductions, the match), kept with the plan only.
 */
function PlanPaycheck({ row, year, said, saving, onEdit, onAmount }: PlanPaycheckProps) {
  return (
    <>
      <span className={SAID}>{said}</span>
      <div className="justify-self-end text-right md:w-full">
        <p data-amount className="font-semibold tabular-nums">
          {formatCents(row.chosenCents)}
          {row.yours && <span className="ml-2 text-xs font-bold tracking-wide text-accent-ink uppercase">Yours</span>}
        </p>
        {row.pay?.raiseFrom != null && row.pay.raiseBp != null && row.pay.raisedNetCents != null && (
          <p className="text-sm text-muted">
            <span data-amount className="tabular-nums">
              {formatCents(row.pay.raisedNetCents)}
            </span>{" "}
            from {formatDate(row.pay.raiseFrom)} · {formatRate(row.pay.raiseBp)} raise
          </p>
        )}
        <p className="flex flex-wrap items-center justify-end gap-x-3 text-sm">
          <button
            type="button"
            disabled={saving}
            onClick={() => onEdit(row)}
            aria-label={`Edit ${row.name}'s pay in ${year}`}
            className={`${BUTTON_LINK} inline-flex items-center gap-1 text-sm`}
          >
            <Pencil aria-hidden className="size-3.5" />
            Edit
          </button>
          {row.yours && (
            <button type="button" disabled={saving} onClick={() => onAmount(row, null)} className={`${BUTTON_LINK} text-sm`}>
              Use today's pay
            </button>
          )}
        </p>
      </div>
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
  /** True while this line's calculator is open, under the line. */
  calculating: boolean;
  /** Opens or closes it, starting from what the box holds. */
  onCalculate: (startCents: number) => void;
}

/**
 * The amount box of one line. It opens on what the item will be on the plan's first day (Rachel,
 * 2026-10-23: "if I have amounts that are starting in recurring item or will continue on 1/1 then
 * those should be preloaded"); "Use suggestion" takes the one worked out from history. Typing over
 * it makes the amount "yours", saved as the box is left (or on Enter). Emptying it, or "Keep", goes
 * back to the amount set now. A sum can be typed, as in every amount field.
 */
function PlanAmount({ row, label, said, saving, onAmount, calculating, onCalculate }: PlanAmountProps) {
  const id = useId();
  const [text, setText] = useState(centsToInput(row.chosenCents));
  const [invalid, setInvalid] = useState(false);
  const opener = useRef<HTMLButtonElement>(null);
  const wasCalculating = useRef(false);

  // When the calculator closes, the keyboard goes back to the button that opened it.
  useEffect(() => {
    if (wasCalculating.current && !calculating) {
      opener.current?.focus();
    }
    wasCalculating.current = calculating;
  }, [calculating]);

  // The plan's amount can change underneath: another basis, or the suggestions accepted.
  useEffect(() => {
    setText(centsToInput(row.chosenCents));
    setInvalid(false);
  }, [row.chosenCents]);

  // A suggestion is offered while the box holds something else; a fixed amount has none of its own.
  const offersSuggestion = row.reason !== "fixed" && row.chosenCents !== row.suggestedCents;

  const commit = () => {
    if (text.trim() === "") {
      setText(centsToInput(row.currentCents));
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
      {/* On a phone the box and its calculator take a line of their own under its name: beside the name they are wider
          than the card. */}
      <label htmlFor={id} className={`${SAID} col-span-2`}>
        {said}
      </label>
      <div className="col-span-2 w-full md:col-span-1">
        <div className="flex gap-1.5">
          <div className="relative min-w-0 flex-1">
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
          <button
            ref={opener}
            type="button"
            onClick={() => onCalculate(parseDollars(text) ?? row.chosenCents)}
            aria-label={`Calculator for ${label}`}
            aria-expanded={calculating}
            title="Calculator"
            className="flex min-h-11 w-11 shrink-0 items-center justify-center rounded-lg border border-line bg-surface text-muted"
          >
            <Calculator aria-hidden className="size-5" />
          </button>
        </div>
        {invalid ? (
          <p id={`${id}-error`} role="alert" className="mt-1 text-sm text-danger">
            Please enter an amount like 1,250.00.
          </p>
        ) : (
          (row.yours || offersSuggestion) && (
            // One above the other, each on one line: side by side in the box's width, "Use suggestion" broke in two and
            // left "Yours" hanging beside it (Rachel, 2026-10-23: "the yours and use suggestion buttons are very weird").
            <p id={`${id}-yours`} className="mt-1 flex flex-col items-end text-sm">
              {row.yours && (
                <span className="text-xs font-bold tracking-wide text-accent-ink uppercase">
                  {row.chosenCents === row.suggestedCents ? "Suggestion" : "Yours"}
                </span>
              )}
              {offersSuggestion && (
                <button
                  type="button"
                  disabled={saving}
                  onClick={() => onAmount(row, row.suggestedCents)}
                  className={`${BUTTON_LINK} text-sm whitespace-nowrap`}
                >
                  Use suggestion
                </button>
              )}
              {row.yours && (
                <button
                  type="button"
                  disabled={saving}
                  onClick={() => onAmount(row, null)}
                  aria-label={`Keep ${formatCents(row.currentCents)}, the amount set now, for ${row.name}`}
                  className={`${BUTTON_LINK} text-sm whitespace-nowrap`}
                >
                  Keep {formatCents(row.currentCents)}
                </button>
              )}
            </p>
          )
        )}
      </div>
    </>
  );
}
