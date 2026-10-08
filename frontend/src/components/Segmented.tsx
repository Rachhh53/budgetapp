import { useId } from "react";
import { LABEL } from "./ui";

interface SegmentedProps<T extends string> {
  /** What is being chosen, e.g. "Type". */
  legend: string;
  options: readonly { value: T; label: string }[];
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
            className="flex min-h-11 cursor-pointer items-center justify-center rounded-md px-2 text-center text-sm font-medium text-muted has-checked:bg-surface has-checked:font-semibold has-checked:text-ink has-checked:shadow-sm has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-accent"
          >
            <input
              type="radio"
              name={name}
              value={option.value}
              checked={option.value === value}
              onChange={() => onChange(option.value)}
              className="sr-only"
            />
            {option.label}
          </label>
        ))}
      </div>
    </fieldset>
  );
}
