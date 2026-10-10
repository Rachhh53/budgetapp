import { Calculator } from "lucide-react";
import { useEffect, useId, useRef, useState } from "react";
import { centsToInput, isSum, parseDollars } from "../lib/money";
import { MoneyCalculator } from "./MoneyCalculator";
import { HINT, INPUT, LABEL } from "./ui";

interface MoneyFieldProps {
  label: string;
  /** The text as typed; the form turns it into cents with `parseDollars` when it is saved. */
  value: string;
  onChange: (text: string) => void;
  hint?: string;
  /** Shown under the field when what was typed is not an amount. */
  error?: string;
  required?: boolean;
  /** Start here when the form opens, so an amount can be typed straight away. */
  autoFocus?: boolean;
  /** The form's main figure: shown large, so it is the first thing read. */
  hero?: boolean;
}

export const AMOUNT_HELP = "Please enter an amount like 1,250.00.";

/**
 * A dollar amount field: right-aligned tabular digits, with the numeric keypad on a phone.
 *
 * A sum can be typed straight in ("4005+3751") and is worked out on leaving the field. The
 * button beside it opens a calculator for anything more, and for a phone, whose number pad
 * has no plus key.
 */
export function MoneyField({ label, value, onChange, hint, error, required, autoFocus, hero }: MoneyFieldProps) {
  const id = useId();
  const [calculating, setCalculating] = useState(false);
  const opener = useRef<HTMLButtonElement>(null);
  // When the calculator closes, the keyboard goes back to the button that opened it.
  const returnFocus = useRef(false);

  useEffect(() => {
    if (!calculating && returnFocus.current) {
      returnFocus.current = false;
      opener.current?.focus();
    }
  }, [calculating]);

  const close = () => {
    returnFocus.current = true;
    setCalculating(false);
  };

  /** A sum left in the field becomes its answer, so what is saved is what is seen. */
  const workOutSum = () => {
    const cents = isSum(value) ? parseDollars(value) : null;
    if (cents !== null) {
      onChange(centsToInput(cents));
    }
  };

  return (
    <div>
      <label htmlFor={id} className={LABEL}>
        {label}
      </label>
      <div className="flex gap-2">
        <div className="relative min-w-0 flex-1">
          <span aria-hidden className={`pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-muted ${hero ? "text-2xl" : ""}`}>
            $
          </span>
          <input
            id={id}
            value={value}
            onChange={(event) => onChange(event.target.value)}
            onBlur={workOutSum}
            inputMode="decimal"
            autoComplete="off"
            required={required}
            // Both: the attribute covers a form that appears later, the marker tells the dialog where to start.
            autoFocus={autoFocus}
            data-autofocus={autoFocus ? true : undefined}
            aria-invalid={error ? true : undefined}
            aria-describedby={error ? `${id}-error` : hint ? `${id}-hint` : undefined}
            className={`${INPUT} text-right tabular-nums ${hero ? "min-h-14 pl-9 text-2xl font-semibold" : "pl-7"}`}
          />
        </div>
        <button
          ref={opener}
          type="button"
          onClick={() => setCalculating((open) => !open)}
          aria-label={`Calculator for ${label}`}
          aria-expanded={calculating}
          title="Calculator"
          className={`flex w-11 shrink-0 items-center justify-center rounded-lg border border-line bg-surface text-muted ${hero ? "min-h-14" : "min-h-11"}`}
        >
          <Calculator aria-hidden className="size-5" />
        </button>
      </div>
      {calculating && (
        <MoneyCalculator
          label={label}
          // An empty or half-typed field starts the calculator from nothing.
          startCents={parseDollars(value) ?? 0}
          onUse={(cents) => {
            onChange(centsToInput(cents));
            close();
          }}
          onCancel={close}
        />
      )}
      {error ? (
        <p id={`${id}-error`} role="alert" className="mt-1 text-sm text-danger">
          {error}
        </p>
      ) : (
        hint && (
          <p id={`${id}-hint`} className={HINT}>
            {hint}
          </p>
        )
      )}
    </div>
  );
}
