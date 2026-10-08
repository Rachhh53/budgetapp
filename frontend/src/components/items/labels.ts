import { LAST_DAY } from "../../api/income";
import type { Item, ItemFrequency, ItemKind, ItemSchedule } from "../../api/items";
import { ordinal, weekdayName } from "../../lib/dates";

export const KIND_LABELS: Record<ItemKind, string> = {
  bill: "Bill",
  transfer: "Transfer",
  budget: "Budget",
  card_payment: "Card payment",
  bucket_move: "Bucket move",
};

/** Plural, for the filter chips. */
export const KIND_PLURALS: Record<ItemKind, string> = {
  bill: "Bills",
  transfer: "Transfers",
  budget: "Budgets",
  card_payment: "Card payments",
  bucket_move: "Bucket moves",
};

export const ITEM_FREQUENCY_LABELS: Record<ItemFrequency, string> = {
  every_pay_period: "Every paycheck",
  weekly: "Every week",
  biweekly: "Every 2 weeks",
  semi_monthly: "Twice a month",
  monthly: "Monthly",
  every_n_months: "Every few months",
  yearly: "Yearly",
  one_time: "One time",
};

export const MONTH_NAMES = [
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

/** `1` → "1st"; `31` → "last day", since shorter months fall back to their last day anyway. */
function dayLabel(day: number | null): string {
  return day === null ? "" : day >= LAST_DAY ? "last day" : ordinal(day);
}

/** A schedule in a few words, e.g. "Monthly · 16th" or "Yearly · Mar 12". */
export function describeItemSchedule(schedule: ItemSchedule): string {
  switch (schedule.frequency) {
    case "every_pay_period":
      return ITEM_FREQUENCY_LABELS.every_pay_period;
    case "weekly":
    case "biweekly":
      return `${ITEM_FREQUENCY_LABELS[schedule.frequency]} · ${weekdayName(schedule.startDate)}s`;
    case "semi_monthly":
      return `Twice a month · ${dayLabel(schedule.day1)} & ${dayLabel(schedule.day2)}`;
    case "monthly":
      return `Monthly · ${dayLabel(schedule.day1)}`;
    case "every_n_months":
      return `Every ${schedule.interval ?? 2} months · ${dayLabel(schedule.day1)}`;
    case "yearly":
      return `Yearly · ${(MONTH_NAMES[(schedule.month ?? 1) - 1] ?? "").slice(0, 3)} ${schedule.day1 ?? 1}`;
    case "one_time":
      return "One time";
  }
}

/** "Bill · autopay", "Bill · manual", "Budget": the kind, and how it gets paid where that applies. */
export function describeKind(item: Pick<Item, "kind" | "autopay">): string {
  // A budget is spent, and a bucket move just happens: neither is "paid" by anyone.
  return item.kind === "budget" || item.kind === "bucket_move"
    ? KIND_LABELS[item.kind]
    : `${KIND_LABELS[item.kind]} · ${item.autopay ? "autopay" : "manual"}`;
}

/** Which paycheck pays for it, in the table's words. */
export function describeLandsIn(item: Pick<Item, "schedule" | "placement" | "next">): string {
  if (item.schedule.frequency === "every_pay_period") {
    return "Each period";
  }
  // "1 early" covers both reasons: placed one early, or due on payday and paid by hand.
  return item.placement === "one_early" || item.next?.early ? "1 early" : "Due period";
}
