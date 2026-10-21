import { ArrowLeftRight, CreditCard, type LucideIcon, PiggyBank, Receipt, ShoppingBasket } from "lucide-react";
import { LAST_DAY } from "../../api/income";
import type { Item, ItemFrequency, ItemKind, ItemSchedule } from "../../api/items";
import { addDays, formatDate, ordinal, todayIso, weekdayName } from "../../lib/dates";

/**
 * "Bill or expense" (Rachel, 2026-10-12): regular spending nobody sends a bill for, a class or a
 * subscription, works exactly like a bill, and someone looking for "expense" has to find it.
 */
export const KIND_LABELS: Record<ItemKind, string> = {
  bill: "Bill or expense",
  transfer: "Transfer",
  budget: "Budget",
  card_payment: "Card payment",
  bucket_move: "Bucket move",
};

/**
 * What each kind is and when to pick it, shown under the Type choice (Rachel, 2026-10-12: a budget
 * and a bill look alike in the list, and the form did not say which one to choose).
 */
export const KIND_HELP: Record<ItemKind, string> = {
  bill: "A bill, or regular spending nobody bills you for: a subscription, a class, a haircut.",
  budget:
    "Money you plan to spend a little at a time, with no single payment to tick off: groceries, gas, eating out. Pick a bill or expense instead when it is one payment on a known day.",
  transfer: "Money moved between two of your own accounts: into savings, or a loan's payment.",
  card_payment: "What you pay towards a credit card. It can follow the card's statement, or be an amount you set.",
  bucket_move: "Money set aside in a bucket. It stays in its account.",
};

/** A picture for each kind, used wherever the kinds are chosen between or a form is about one. */
export const KIND_ICONS: Record<ItemKind, LucideIcon> = {
  bill: Receipt,
  transfer: ArrowLeftRight,
  budget: ShoppingBasket,
  card_payment: CreditCard,
  bucket_move: PiggyBank,
};

/** Plural, for the filter chips. */
export const KIND_PLURALS: Record<ItemKind, string> = {
  bill: "Bills & expenses",
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
  const season = describeMonths(schedule.months ?? []);
  const repeat = season === "" ? describeRepeat(schedule) : `${describeRepeat(schedule)} · ${season}`;
  // Its last date, when it stops: "until Mar 31, 2027", or "ended" once that has gone by.
  if (schedule.endDate == null || schedule.frequency === "one_time") {
    return repeat;
  }
  return `${repeat} · ${schedule.endDate < todayIso() ? "ended" : "until"} ${formatDate(schedule.endDate)}`;
}

/**
 * The months a seasonal schedule runs in, as short as it can be said: "Nov–Mar", "Jun–Aug, Dec",
 * "Jan". A run carries on across the new year. Every month, or none chosen, is "".
 */
export function describeMonths(months: readonly number[]): string {
  const chosen = new Set(months.filter((month) => month >= 1 && month <= 12));
  if (chosen.size === 0 || chosen.size === 12) {
    return "";
  }
  const short = (month: number) => (MONTH_NAMES[month - 1] ?? "").slice(0, 3);
  const after = (month: number) => (month % 12) + 1;
  const before = (month: number) => ((month + 10) % 12) + 1;
  // Each run starts at a chosen month whose month before was not chosen; January first, so the order reads as a year.
  const runs: string[] = [];
  for (let start = 1; start <= 12; start++) {
    if (!chosen.has(start) || chosen.has(before(start))) {
      continue;
    }
    let end = start;
    while (chosen.has(after(end))) {
      end = after(end);
    }
    runs.push(end === start ? short(start) : `${short(start)}–${short(end)}`);
  }
  return runs.join(", ");
}

/** A Monday, from which the day `n` (1 Monday … 7 Sunday) is counted. */
const A_MONDAY = "2026-10-19";

/** The days of the week it falls on: "Mondays", "Mondays & Thursdays", "Mon, Wed & Fri". Just the start's day when none are chosen. */
function weekdaysOf(schedule: ItemSchedule): string {
  const days = [...new Set(schedule.weekdays ?? [])].filter((day) => day >= 1 && day <= 7).sort((a, b) => a - b);
  if (days.length === 0) {
    return `${weekdayName(schedule.startDate)}s`;
  }
  const names = days.map((day) => weekdayName(addDays(A_MONDAY, day - 1)));
  // Two or more full names do not fit a row on a phone, so three or more are shortened.
  const shown = days.length <= 2 ? names.map((name) => `${name}s`) : names.map((name) => name.slice(0, 3));
  return shown.length === 1 ? shown[0]! : `${shown.slice(0, -1).join(", ")} & ${shown[shown.length - 1]}`;
}

/** How often it repeats, ignoring its months. */
function describeRepeat(schedule: ItemSchedule): string {
  switch (schedule.frequency) {
    case "every_pay_period":
      return ITEM_FREQUENCY_LABELS.every_pay_period;
    case "weekly":
      return `${(schedule.interval ?? 1) > 1 ? `Every ${schedule.interval} weeks` : ITEM_FREQUENCY_LABELS.weekly} · ${weekdaysOf(schedule)}`;
    case "biweekly":
      return `${ITEM_FREQUENCY_LABELS.biweekly} · ${weekdaysOf(schedule)}`;
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

/** "Bill · autopay", "Bill · manual", "Bill · billed later", "Bill · paid ahead", "Budget": the kind, and how it gets paid where that applies. */
export function describeKind(item: Pick<Item, "kind" | "autopay" | "billedLater" | "paidAhead" | "usesHundredths">): string {
  // A budget is spent, and a bucket move just happens: neither is "paid" by anyone.
  if (item.kind === "budget" || item.kind === "bucket_move") {
    return KIND_LABELS[item.kind];
  }
  const how = item.billedLater
    ? "billed later"
    : item.paidAhead
      ? "paid ahead"
      : item.usesHundredths != null
        ? "billed by use"
        : item.autopay
          ? "autopay"
          : "manual";
  return `${KIND_LABELS[item.kind]} · ${how}`;
}

/** Which paycheck pays for it, in the table's words. */
export function describeLandsIn(item: Pick<Item, "schedule" | "placement" | "next">): string {
  if (item.schedule.frequency === "every_pay_period") {
    return "Each period";
  }
  // "1 early" covers both reasons: placed one early, or due on payday and paid by hand.
  return item.placement === "one_early" || item.next?.early ? "1 early" : "Due period";
}
