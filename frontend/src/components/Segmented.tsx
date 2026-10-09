import type { LucideIcon } from "lucide-react";
import { useId } from "react";
import { LABEL } from "./ui";

interface SegmentedProps<T extends string> {
  /** What is being chosen, e.g. "Type". */
  legend: string;
  /** With an `icon`, a segment shows it above its label, so the choices are told apart at a glance. */
  options: readonly { value: T; label: string; icon?: LucideIcon }[];
  value: T;
  onChange: (value: T) => void;
}

/**
 * A short either/or choice shown as a row of segments (the type switch in
 * design/recurring.png). Underneath it is a plain radio group, so the arrow keys move
 * between the choices and a screen reader announces it as one question. On a phone the
 * segments wrap two to a row rather than shrinking below a comfortable tap.
 */
export function Segmented<T extends string>({ legend, options, value, onChange }: SegmentedProps<T>) {
  const name = useId();
  return (
    <fieldset>
      <legend className={LABEL}>{legend}</legend>
      <div className="grid grid-cols-2 gap-1 rounded-lg bg-bg p-1 sm:auto-cols-fr sm:grid-flow-col sm:grid-cols-none">
        {options.map((option) => (
          <label
            key={option.value}
            className="group flex min-h-11 cursor-pointer flex-col items-center justify-center gap-1 rounded-md px-2 py-1.5 text-center text-sm font-medium text-muted has-checked:bg-surface has-checked:font-semibold has-checked:text-ink has-checked:shadow-sm has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-accent-ink"
          >
            <input
              type="radio"
              name={name}
              value={option.value}
              checked={option.value === value}
              onChange={() => onChange(option.value)}
              className="sr-only"
            />
            {option.icon && <option.icon aria-hidden className="size-5 group-has-checked:text-accent-ink" />}
            {option.label}
          </label>
        ))}
      </div>
    </fieldset>
  );
}
