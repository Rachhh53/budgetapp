import { useId } from "react";
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
}

export const AMOUNT_HELP = "Please enter an amount like 1,250.00.";

/** A dollar amount field: right-aligned tabular digits, with the numeric keypad on a phone. */
export function MoneyField({ label, value, onChange, hint, error, required, autoFocus }: MoneyFieldProps) {
  const id = useId();
  return (
    <div>
      <label htmlFor={id} className={LABEL}>
        {label}
      </label>
      <div className="relative">
        <span aria-hidden className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-muted">
          $
        </span>
        <input
          id={id}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          inputMode="decimal"
          autoComplete="off"
          required={required}
          // Both: the attribute covers a form that appears later, the marker tells the dialog where to start.
          autoFocus={autoFocus}
          data-autofocus={autoFocus ? true : undefined}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? `${id}-error` : hint ? `${id}-hint` : undefined}
          className={`${INPUT} pl-7 text-right tabular-nums`}
        />
      </div>
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
