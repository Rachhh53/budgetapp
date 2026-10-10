import { Delete } from "lucide-react";
import { type KeyboardEvent, useEffect, useReducer, useRef } from "react";
import { type CalcOp, calculate, formatCents, parseDollars } from "../lib/money";
import { BUTTON_PRIMARY, BUTTON_SECONDARY } from "./ui";

/** One sum that was worked out, as it is listed: "$300.00 ÷ 12 = $25.00". */
interface Step {
  from: number;
  op: CalcOp;
  by: string;
  result: number;
}

interface State {
  /** The running amount, in cents: where it started, or the last result. */
  cents: number;
  /** The sign waiting for its number. */
  op: CalcOp | null;
  /** The number being typed. */
  entry: string;
  steps: Step[];
  problem?: string;
}

type Action = { type: "digit"; digit: string } | { type: "op"; op: CalcOp } | { type: "equals" } | { type: "clear" } | { type: "backspace" };

const NOT_A_NUMBER = "That number can't be worked out. Please check it and try again.";
const DIVIDE_BY_NOTHING = "Nothing can be divided by zero. Please enter another number.";

/** Works out the sum that is waiting, if there is one; says why when it cannot be. */
function settle(state: State): State {
  if (state.entry === "") {
    return state;
  }
  if (state.op === null) {
    // A number typed with no sign before it starts again from that number.
    const typed = parseDollars(state.entry);
    return typed === null ? { ...state, problem: NOT_A_NUMBER } : { ...state, cents: typed, entry: "", problem: undefined };
  }
  const result = calculate(state.cents, state.op, state.entry);
  if (result === null) {
    return { ...state, problem: state.op === "÷" && Number(state.entry) === 0 ? DIVIDE_BY_NOTHING : NOT_A_NUMBER };
  }
  return {
    cents: result,
    op: null,
    entry: "",
    steps: [...state.steps, { from: state.cents, op: state.op, by: state.entry, result }],
    problem: undefined,
  };
}

function reduce(state: State, action: Action): State {
  switch (action.type) {
    case "digit": {
      // One decimal point, and no more decimals than can be worked with.
      const [, decimals] = `${state.entry}${action.digit}`.split(".");
      if ((action.digit === "." && state.entry.includes(".")) || (decimals !== undefined && decimals.length > 4)) {
        return state;
      }
      return { ...state, entry: state.entry + action.digit, problem: undefined };
    }
    case "op": {
      // Pressing a sign works out what came before it, so sums chain: 300 ÷ 12 × 2.
      const settled = settle(state);
      return settled.problem ? settled : { ...settled, op: action.op };
    }
    case "equals":
      return settle(state);
    case "backspace":
      return { ...state, entry: state.entry.slice(0, -1), problem: undefined };
    case "clear":
      // The sums already worked out stay listed: they are the record of how the amount was reached.
      return { ...state, cents: 0, op: null, entry: "", problem: undefined };
  }
}

const KEYS: readonly (readonly string[])[] = [
  ["7", "8", "9", "÷"],
  ["4", "5", "6", "×"],
  ["1", "2", "3", "−"],
  ["0", ".", "=", "+"],
];

const OP_NAMES: Record<CalcOp, string> = { "+": "plus", "−": "minus", "×": "times", "÷": "divided by" };
/** The keys of a keyboard that mean each sign. */
const TYPED_OPS: Record<string, CalcOp> = { "+": "+", "-": "−", "*": "×", x: "×", X: "×", "/": "÷" };

const KEY = "flex min-h-11 items-center justify-center rounded-lg border border-line bg-surface text-lg tabular-nums";

interface MoneyCalculatorProps {
  /** The field it works for, e.g. "Amount": names it for screen readers. */
  label: string;
  /** What the field holds when the calculator opens. */
  startCents: number;
  onUse: (cents: number) => void;
  onCancel: () => void;
}

/**
 * A small calculator for a money field. It starts from what the field holds; sums chain one
 * after another, and each one worked out since it opened stays listed. Nothing touches the
 * field until "Use" is pressed; Cancel (or Esc) leaves it exactly as it was.
 *
 * It sits in the page under its field rather than floating over it, so it stays put inside a
 * form and on a phone. Everything can be done from the keyboard as well as the keys shown.
 */
export function MoneyCalculator({ label, startCents, onUse, onCancel }: MoneyCalculatorProps) {
  const [state, dispatch] = useReducer(reduce, { cents: startCents, op: null, entry: "", steps: [] });
  const panel = useRef<HTMLDivElement>(null);

  useEffect(() => {
    panel.current?.focus();
  }, []);

  // What "Use" would put in the field: the sum still waiting is worked out first.
  const pending = settle(state);
  const usable = pending.problem === undefined;

  const use = () => {
    if (usable) {
      onUse(pending.cents);
    } else {
      dispatch({ type: "equals" });
    }
  };

  const onKeyDown = (event: KeyboardEvent) => {
    // Tab and the buttons' own Enter and Space are left alone.
    const onAButton = event.target instanceof HTMLButtonElement;
    if (/^[0-9.]$/.test(event.key)) {
      dispatch({ type: "digit", digit: event.key });
    } else if (event.key in TYPED_OPS) {
      dispatch({ type: "op", op: TYPED_OPS[event.key]! });
    } else if (event.key === "=" || (event.key === "Enter" && !onAButton)) {
      dispatch({ type: "equals" });
    } else if (event.key === "Backspace") {
      dispatch({ type: "backspace" });
    } else if (event.key === "Escape") {
      // Only the calculator closes, not the form it is in.
      event.stopPropagation();
      onCancel();
    } else {
      return;
    }
    event.preventDefault();
  };

  const press = (key: string) => {
    if (key === "=") {
      dispatch({ type: "equals" });
    } else if (key in OP_NAMES) {
      dispatch({ type: "op", op: key as CalcOp });
    } else {
      dispatch({ type: "digit", digit: key });
    }
  };

  return (
    <div
      ref={panel}
      role="group"
      aria-label={`Calculator for ${label}`}
      tabIndex={-1}
      onKeyDown={onKeyDown}
      className="mt-2 rounded-lg border border-line bg-bg p-3"
    >
      {state.steps.length > 0 && (
        <ol aria-label="Worked out so far" className="mb-2 space-y-0.5 text-right text-sm text-muted tabular-nums">
          {state.steps.map((step, index) => (
            <li key={index} data-amount>
              {formatCents(step.from)} {step.op} {step.by} = {formatCents(step.result)}
            </li>
          ))}
        </ol>
      )}
      <p aria-live="polite" data-amount className="rounded-lg bg-surface px-3 py-2 text-right text-xl font-semibold break-all tabular-nums">
        {state.op === null && state.entry !== "" ? state.entry : formatCents(state.cents)}
        {state.op !== null && ` ${state.op} ${state.entry}`}
      </p>
      {state.problem && (
        <p role="alert" className="mt-1 text-sm text-danger">
          {state.problem}
        </p>
      )}
      <div className="mt-2 grid grid-cols-4 gap-1.5">
        {KEYS.flat().map((key) => (
          <button
            key={key}
            type="button"
            onClick={() => press(key)}
            aria-label={key in OP_NAMES ? OP_NAMES[key as CalcOp] : key === "=" ? "equals" : key === "." ? "point" : key}
            className={`${KEY} ${/[0-9.]/.test(key) ? "" : "font-semibold text-accent-ink"}`}
          >
            {key}
          </button>
        ))}
        <button type="button" onClick={() => dispatch({ type: "clear" })} className={`${KEY} col-span-2 text-base`}>
          Clear
        </button>
        <button type="button" onClick={() => dispatch({ type: "backspace" })} aria-label="Delete the last digit" className={`${KEY} col-span-2`}>
          <Delete aria-hidden className="size-5" />
        </button>
      </div>
      <div className="mt-3 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <button type="button" onClick={onCancel} className={BUTTON_SECONDARY}>
          Cancel
        </button>
        <button type="button" onClick={use} className={BUTTON_PRIMARY}>
          {usable ? `Use ${formatCents(pending.cents)}` : "Work it out"}
        </button>
      </div>
    </div>
  );
}
