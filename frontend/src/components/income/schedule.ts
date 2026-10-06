import { LAST_DAY, type Paycheck, type PayFrequency, type Schedule, type WeekendRule } from "../../api/income";
import { formatDay, isWeekend, ordinal, weekdayName } from "../../lib/dates";

export const FREQUENCY_LABELS: Record<PayFrequency, string> = {
  weekly: "Every week",
  biweekly: "Every 2 weeks",
  semi_monthly: "Twice a month",
  monthly: "Once a month",
  manual_dates: "On dates I pick",
};

export const WEEKEND_LABELS: Record<WeekendRule, string> = {
  previous_business_day: "Previous business day",
  next_business_day: "Next business day",
  none: "No change",
};

/** `1` → "1st"; `31` → "last day", since shorter months fall back to their last day anyway. */
export function dayLabel(day: number): string {
  return day >= LAST_DAY ? "last day" : ordinal(day);
}

/** A schedule in a few words, e.g. "Twice a month · 1st & 15th". */
export function describeSchedule(schedule: Schedule): string {
  const frequency = FREQUENCY_LABELS[schedule.frequency];
  switch (schedule.frequency) {
    case "weekly":
    case "biweekly":
      return schedule.startDate ? `${frequency} · ${weekdayName(schedule.startDate)}s` : frequency;
    case "semi_monthly":
      return `${frequency} · ${dayLabel(schedule.day1 ?? 1)} & ${dayLabel(schedule.day2 ?? 15)}`;
    case "monthly":
      return `${frequency} · ${dayLabel(schedule.day1 ?? 1)}`;
    case "manual_dates":
      return `${schedule.manualDates.length} dates you picked`;
  }
}

/**
 * Why a paycheck arrives on a different day than scheduled, e.g. "Nov 1 is a Sunday".
 * `null` when it was not moved.
 */
export function movedReason(paycheck: Pick<Paycheck, "scheduledDate" | "payDate">): string | null {
  if (paycheck.scheduledDate === paycheck.payDate) {
    return null;
  }
  const day = formatDay(paycheck.scheduledDate);
  // A payday only moves off a weekend or a holiday, so a weekday here means a holiday.
  return isWeekend(paycheck.scheduledDate) ? `${day} is a ${weekdayName(paycheck.scheduledDate)}` : `${day} is a holiday`;
}
