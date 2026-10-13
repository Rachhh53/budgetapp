import { PLAN_STEPS, type PlanBasis, type PlanRow, type PlanStatus, type PlanStep, type PlanSummary } from "../../api/plans";
import { formatDate } from "../../lib/dates";
import { formatCents } from "../../lib/money";

/** The wizard's six steps (design/plan-year.png): the five that hold amounts, then the one that adds them up. */
export const WIZARD_STEPS = [...PLAN_STEPS, "review"] as const;
export type WizardStep = (typeof WIZARD_STEPS)[number];

export const STEP_LABELS: Record<WizardStep, string> = {
  income: "Income & benefits",
  bills: "Bills",
  annual: "Annual & save-ups",
  budgets: "Spending budgets",
  saving: "Saving & investing",
  review: "Review",
};

/** What a step holds, said once at its top. */
export const STEP_HELP: Record<PlanStep, string> = {
  income: "What each paycheck brings home. It stays as it is unless you expect a raise, or a change in what is taken out.",
  bills: "Bills that come round through the year: rent, utilities, subscriptions, loan payments.",
  annual: "Bills that come once or a few times a year, and anything you save up for a little each paycheck.",
  budgets: "Money you plan to spend a little at a time: groceries, gas, eating out.",
  saving: "Money you move on purpose: transfers to savings and investments, and money set aside in buckets.",
};

/** What to say when a step has nothing in it. */
export const STEP_EMPTY: Record<PlanStep, string> = {
  income: "No income yet.",
  bills: "No bills in this step.",
  annual: "Nothing yearly or saved up for.",
  budgets: "No spending budgets.",
  saving: "No transfers or bucket moves.",
};

export const STATUS_LABELS: Record<PlanStatus, string> = { draft: "Draft", active: "Active", closed: "Closed" };

const MONTH_YEAR = new Intl.DateTimeFormat("en-US", { month: "short", year: "numeric" });

/** `"2025-10-01"` → `"Oct 2025"` */
export function monthOf(iso: string): string {
  const [year = 0, month = 1] = iso.split("-").map(Number);
  return MONTH_YEAR.format(new Date(year, month - 1, 1));
}

/** "2027 · draft, starts Jan 1, 2027", "2026 · active": a plan in a line. */
export function describePlan(plan: PlanSummary): string {
  if (plan.setup && plan.status === "draft") {
    return `${plan.year} · setting up`;
  }
  return plan.status === "draft"
    ? `${plan.year} · draft, starts ${formatDate(plan.startsOn)}`
    : `${plan.year} · ${STATUS_LABELS[plan.status].toLowerCase()}`;
}

/** The three bases as the switch names them; the last one names the year it means. */
export function basisLabel(basis: PlanBasis, plan: PlanSummary): string {
  switch (basis) {
    case "last_12":
      return "Last 12 months";
    case "last_12_trend":
      return "Last 12 + trend";
    case "prior_year":
      return `${Number(plan.suggestedAsOf.slice(0, 4)) - 1} full year`;
  }
}

const FREQUENCY_WORDS: Record<string, string> = {
  every_pay_period: "Every paycheck",
  weekly: "Every week",
  biweekly: "Every 2 weeks",
  semi_monthly: "Twice a month",
  monthly: "Monthly",
  every_n_months: "Every few months",
  yearly: "Yearly",
  manual_dates: "On dates you chose",
};

/** "Monthly · fixed payment", "Yearly · not enough history yet": the line under a name. */
export function describeRow(row: PlanRow): string {
  const parts = [FREQUENCY_WORDS[row.frequency] ?? "Recurring"];
  if (row.kind === "item" && row.reason === "fixed") {
    parts.push(row.step === "bills" ? "fixed payment" : "your choice");
  }
  if (row.reason === "not_enough_history") {
    parts.push("not enough history yet");
  }
  return parts.join(" · ");
}

/** "$1,600.00 a month", "save $52.67 per paycheck", "$62,400.00 a year · 24 paychecks": what an amount comes to. */
export function worksOutTo(row: PlanRow): string {
  if (row.kind === "income") {
    return `${formatCents(row.yearCents)} a year · ${row.occurrences} ${row.occurrences === 1 ? "paycheck" : "paychecks"}`;
  }
  if (row.savedUpFor || row.step === "annual") {
    return `save ${formatCents(row.perPaycheckCents)} per paycheck`;
  }
  const each = formatCents(row.perOccurrenceCents);
  switch (row.frequency) {
    case "monthly":
      return `${each} a month`;
    case "every_pay_period":
      return `${each} per paycheck`;
    case "weekly":
      return `${each} a week`;
    case "biweekly":
      return `${each} every 2 weeks`;
    case "semi_monthly":
      return `${each} twice a month`;
    default:
      return `${each} each time · ${row.occurrences} a year`;
  }
}
