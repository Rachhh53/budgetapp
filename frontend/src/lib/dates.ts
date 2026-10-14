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

const MONTH_YEAR = new Intl.DateTimeFormat("en-US", { month: "short", year: "numeric" });

/** `"2028-09-15"` → `"Sep 2028"`: for a date years off, where the day says nothing. */
export function formatMonth(iso: string): string {
  return MONTH_YEAR.format(toDate(iso));
}

/** Whole calendar months from one date's month to another's; negative when `to` is earlier. */
export function monthsBetween(from: string, to: string): number {
  const [fromYear = 0, fromMonth = 1] = from.split("-").map(Number);
  const [toYear = 0, toMonth = 1] = to.split("-").map(Number);
  return (toYear - fromYear) * 12 + (toMonth - fromMonth);
}

const TIME = new Intl.DateTimeFormat("en-US", { hour: "numeric", minute: "2-digit" });

/**
 * A moment on this PC's clock, as the backend writes it (`"2026-10-02T07:02:00"`, no time
 * zone) → `"today at 7:02 AM"`, `"yesterday at 7:02 AM"` or `"Oct 2, 2026 at 7:02 AM"`.
 */
export function formatDateTime(local: string): string {
  const [day = "", time = "00:00"] = local.split("T");
  const [hour = 0, minute = 0] = time.split(":").map(Number);
  const moment = toDate(day);
  moment.setHours(hour, minute);
  const today = todayIso();
  const when = day === today ? "today" : day === addDays(today, -1) ? "yesterday" : formatDate(day);
  return `${when} at ${TIME.format(moment)}`;
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
