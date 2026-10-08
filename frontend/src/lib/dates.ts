/**
 * Dates travel as `YYYY-MM-DD` text: plain calendar days with no time and no time zone.
 * They are only turned into a `Date` here, at local midnight, to format them — parsing the
 * text with `new Date("2026-10-01")` would read it as UTC and can show the day before.
 */

const MONTH_DAY = new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric" });
const MONTH_DAY_YEAR = new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", year: "numeric" });
const WEEKDAY_SHORT = new Intl.DateTimeFormat("en-US", { weekday: "short" });
const WEEKDAY_LONG = new Intl.DateTimeFormat("en-US", { weekday: "long" });

function toDate(iso: string): Date {
  const [year = 0, month = 1, day = 1] = iso.split("-").map(Number);
  return new Date(year, month - 1, day);
}

/** `"2026-10-02"` → `"Oct 2"` */
export function formatDay(iso: string): string {
  return MONTH_DAY.format(toDate(iso));
}

/** `"2026-10-02"` → `"Oct 2, 2026"` */
export function formatDate(iso: string): string {
  return MONTH_DAY_YEAR.format(toDate(iso));
}

/** `"2026-10-30"` → `"Fri Oct 30"` */
export function formatWeekdayDay(iso: string): string {
  const date = toDate(iso);
  return `${WEEKDAY_SHORT.format(date)} ${MONTH_DAY.format(date)}`;
}

/** `"2026-11-01"` → `"Sunday"` */
export function weekdayName(iso: string): string {
  return WEEKDAY_LONG.format(toDate(iso));
}

export function isWeekend(iso: string): boolean {
  const day = toDate(iso).getDay();
  return day === 0 || day === 6;
}

function toIso(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${date.getFullYear()}-${month}-${day}`;
}

/** `("2026-10-30", 3)` → `"2026-11-02"`; a negative count goes back. */
export function addDays(iso: string, days: number): string {
  const date = toDate(iso);
  date.setDate(date.getDate() + days);
  return toIso(date);
}

/** Today's date on this device, as `YYYY-MM-DD`. */
export function todayIso(): string {
  return toIso(new Date());
}

/** `1` → `"1st"`, `22` → `"22nd"` */
export function ordinal(day: number): string {
  const lastTwo = day % 100;
  if (lastTwo >= 11 && lastTwo <= 13) {
    return `${day}th`;
  }
  return `${day}${["th", "st", "nd", "rd"][day % 10] ?? "th"}`;
}
