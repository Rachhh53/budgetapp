import { INPUT } from "../ui";

const MONTHS = [
  "January",
  "February",
  "March",
  "April",
  "May",
  "June",
  "July",
  "August",
  "September",
  "October",
  "November",
  "December",
] as const;

/** Days every year has: February stops at 28, so the day chosen exists in every year. */
const DAYS_IN_MONTH = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31] as const;

const two = (value: number) => String(value).padStart(2, "0");

interface YearDaySettingProps {
  id: string;
  label: string;
  hint: string;
  /** A day of every year as `MM-DD`, the way the setting is kept. */
  value: string;
  disabled: boolean;
  onChange: (value: string) => void;
}

/**
 * A setting that is a day of the year (the budget year's first day, the plan reminder): a month
 * and a day, each chosen from a list (spec §1: pickers, not free text). Changing the month keeps
 * the day where it can, and otherwise moves it to that month's last.
 */
export function YearDaySetting({ id, label, hint, value, disabled, onChange }: YearDaySettingProps) {
  const [month = 1, day = 1] = value.split("-").map(Number);
  const days = DAYS_IN_MONTH[month - 1] ?? 31;

  return (
    <div role="group" aria-labelledby={`${id}-label`} className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between sm:gap-4">
      <div className="min-w-0">
        <p id={`${id}-label`} className="font-medium">
          {label}
        </p>
        <p id={`${id}-hint`} className="text-sm text-muted">
          {hint}
        </p>
      </div>
      <div className="flex shrink-0 gap-2">
        <select
          id={`${id}-month`}
          aria-label={`${label}: month`}
          aria-describedby={`${id}-hint`}
          value={month}
          disabled={disabled}
          onChange={(event) => {
            const chosen = Number(event.target.value);
            onChange(`${two(chosen)}-${two(Math.min(day, DAYS_IN_MONTH[chosen - 1] ?? 31))}`);
          }}
          className={`${INPUT} sm:w-40`}
        >
          {MONTHS.map((name, index) => (
            <option key={name} value={index + 1}>
              {name}
            </option>
          ))}
        </select>
        <select
          id={`${id}-day`}
          aria-label={`${label}: day`}
          value={day}
          disabled={disabled}
          onChange={(event) => onChange(`${two(month)}-${two(Number(event.target.value))}`)}
          className={`${INPUT} w-24 shrink-0`}
        >
          {Array.from({ length: days }, (_, index) => index + 1).map((number) => (
            <option key={number} value={number}>
              {number}
            </option>
          ))}
        </select>
      </div>
    </div>
  );
}
