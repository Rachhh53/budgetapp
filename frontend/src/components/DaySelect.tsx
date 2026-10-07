import { LAST_DAY } from "../api/income";
import { ordinal } from "../lib/dates";
import { INPUT, LABEL } from "./ui";

/** Days of the month on offer. "Last day" covers the 31st: shorter months fall back to it. */
const DAY_CHOICES: readonly { value: number; label: string }[] = [
  ...Array.from({ length: 30 }, (_, index) => ({ value: index + 1, label: ordinal(index + 1) })),
  { value: LAST_DAY, label: "Last day" },
];

/** A day of the month, chosen from a list rather than typed. */
export function DaySelect({ id, label, value, onChange }: { id: string; label: string; value: number; onChange: (day: number) => void }) {
  return (
    <div>
      <label htmlFor={id} className={LABEL}>
        {label}
      </label>
      <select id={id} value={value} onChange={(event) => onChange(Number(event.target.value))} className={INPUT}>
        {DAY_CHOICES.map((day) => (
          <option key={day.value} value={day.value}>
            {day.label}
          </option>
        ))}
      </select>
    </div>
  );
}
