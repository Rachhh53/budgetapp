import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, type Saved, saveFile } from "./client";
import type { ReimbursementStatus } from "./entries";
import { PAY_KEY } from "./income";

export const REPORT_RANGES = ["this_year", "last_year", "last_12_months", "last_90_days", "custom"] as const;
export type ReportRange = (typeof REPORT_RANGES)[number];

export const REPORT_RANGE_LABELS: Record<ReportRange, string> = {
  this_year: "This year",
  last_year: "Last year",
  last_12_months: "Last 12 months",
  last_90_days: "Last 90 days",
  custom: "Custom dates",
};

/** What a report is of: one of these, never a mix (Rachel, 2026-10-12). */
export const REPORT_KINDS = ["spending", "income", "projected_vs_actual"] as const;
export type ReportKind = (typeof REPORT_KINDS)[number];

export const REPORT_KIND_LABELS: Record<ReportKind, string> = {
  spending: "Spending",
  income: "Income",
  projected_vs_actual: "Projected vs. actual",
};

/** What kinds of spending a report counts. Transfers and card payments move money; they are not spending. */
export const REPORT_INCLUDES = ["one_offs", "bills", "budgets"] as const;
export type ReportInclude = (typeof REPORT_INCLUDES)[number];

export const REPORT_INCLUDE_LABELS: Record<ReportInclude, string> = {
  one_offs: "One-off spending",
  bills: "Bills & expenses",
  budgets: "Budgets",
};

export const REPORT_GROUPS = ["month", "year", "category", "tag", "payee", "account"] as const;
export type ReportGroupBy = (typeof REPORT_GROUPS)[number];

export const REPORT_GROUP_LABELS: Record<ReportGroupBy, string> = {
  month: "Month",
  year: "Year",
  category: "Category",
  tag: "Tag",
  payee: "Payee",
  account: "Account",
};

/** What counts as income in an income report. Money that pays back an expense never does. */
export const INCOME_INCLUDES = ["paychecks", "one_offs", "match", "interest"] as const;
export type IncomeInclude = (typeof INCOME_INCLUDES)[number];

export const INCOME_INCLUDE_LABELS: Record<IncomeInclude, string> = {
  paychecks: "Paychecks",
  one_offs: "One-off money in",
  match: "Employer match",
  interest: "Interest",
};

export const INCOME_GROUPS = ["month", "year", "source", "account", "pay_period"] as const;
export type IncomeGroupBy = (typeof INCOME_GROUPS)[number];

export const INCOME_GROUP_LABELS: Record<IncomeGroupBy, string> = {
  month: "Month",
  year: "Year",
  source: "Income source",
  account: "Account it landed in",
  pay_period: "Pay period",
};

/**
 * What a projected vs. actual report sets side by side: the things whose amount Budget works out
 * and that can be typed over. A one-off is entered as what it was, so it has no projection.
 */
export const COMPARE_INCLUDES = ["bills", "budgets", "paychecks"] as const;
export type CompareInclude = (typeof COMPARE_INCLUDES)[number];

export const COMPARE_INCLUDE_LABELS: Record<CompareInclude, string> = {
  bills: "Bills & expenses",
  budgets: "Budgets",
  paychecks: "Paychecks",
};

export const COMPARE_GROUPS = ["month", "year", "category", "item"] as const;
export type CompareGroupBy = (typeof COMPARE_GROUPS)[number];

export const COMPARE_GROUP_LABELS: Record<CompareGroupBy, string> = {
  month: "Month",
  year: "Year",
  category: "Category",
  item: "Item",
};

export const REPORT_SHOWS = ["chart_and_table", "table"] as const;
export type ReportShow = (typeof REPORT_SHOWS)[number];

export const REPORT_SHOW_LABELS: Record<ReportShow, string> = {
  chart_and_table: "Bar chart + table",
  table: "Table only",
};

/**
 * What a report asks for. Within one filter any of the choices matches; across filters all
 * must. An empty filter lets everything through.
 */
export interface ReportDefinition {
  range: ReportRange;
  /** First and last day, for `custom` only; `null` otherwise. */
  from: string | null;
  to: string | null;
  include: ReportInclude[];
  tagIds: number[];
  /** A category chosen here also stands for the categories under it. */
  categoryIds: number[];
  accountIds: number[];
  payees: string[];
  reimbursement: ReimbursementStatus[];
  groupBy: ReportGroupBy;
  show: ReportShow;
  /** For an income report the spending-only parts above (include, tags, categories, payees, reimbursement, groupBy) say nothing. */
  kind: ReportKind;
  /** For an income report: what counts. Never empty then. */
  incomeInclude: IncomeInclude[];
  /** For an income report: only these income sources; what has no source is then left out. */
  sourceIds: number[];
  incomeGroupBy: IncomeGroupBy;
  /** For a projected vs. actual report: what to compare. Never empty then. */
  compareInclude: CompareInclude[];
  compareGroupBy: CompareGroupBy;
  /** For a projected vs. actual report: leave out whatever came to exactly what was projected. */
  changedOnly: boolean;
}

export interface SavedReport {
  id: number;
  name: string;
  definition: ReportDefinition;
}

/** One thing spent, as a report lists it. */
export interface ReportRow {
  kind: "one_off" | "bill" | "budget";
  /** The one-off entry it is; `null` for a recurring item. */
  entryId: number | null;
  /** The recurring item it is one time of, and the date its schedule names; `null` for a one-off. */
  itemId: number | null;
  scheduledDate: string | null;
  /** The day it counts on: the day a bill was paid once it has been, its due date until then. */
  date: string;
  description: string;
  payee: string | null;
  categoryId: number | null;
  tagIds: number[];
  accountId: number;
  amountCents: number;
  /** True while the amount is not final, or the day has not come yet. */
  estimate: boolean;
  /** True while its day has not come and it has not been marked paid. */
  unpaid: boolean;
  /** Where a one-off expense stands; `null` for a bill or a budget. */
  reimbursement: ReimbursementStatus | null;
  reimbursedCents: number;
  owedCents: number;
  fileCount: number;
}

export interface ReportGroup {
  /** `2026-03`, `2026`, an id, or a payee's name; `null` for lines with no category, tag or payee. */
  key: string | null;
  /** Ready to show: "March 2026", a category's name, "No category". */
  label: string;
  /** The category's, tag's or account's color; `null` for anything else. */
  color: string | null;
  count: number;
  totalCents: number;
}

/** A spending report that was run. */
export interface Report {
  kind: "spending";
  from: string;
  to: string;
  groupBy: ReportGroupBy;
  groups: ReportGroup[];
  /** Every line counted, oldest first. */
  rows: ReportRow[];
  /** How many lines there are. By tag a line can be in several groups, so this is not always their sum. */
  count: number;
  totalCents: number;
  reimbursedCents: number;
  waitingCents: number;
  notSubmittedCents: number;
  /** How much of the total is an estimate, or dated after today. */
  estimatedCents: number;
  /** False when nothing in it is, or was, to be paid back: the three figures then say nothing. */
  hasReimbursements: boolean;
}

/** One thing that came in, as an income report lists it. */
export interface IncomeRow {
  kind: "paycheck" | "one_off" | "interest" | "match";
  date: string;
  description: string;
  /** The income source of a paycheck or a match; `null` for anything else. */
  sourceId: number | null;
  /** The account it landed in. */
  accountId: number;
  /** The take-home, the money in, the interest or the match. */
  amountCents: number;
  /** A paycheck's gross pay; `null` when not known, and for anything else. */
  grossCents: number | null;
  taxesCents: number | null;
  deductionsCents: number | null;
  /** True when a paycheck's figures are a saved pay stub's. */
  fromStub: boolean;
  estimate: boolean;
}

export interface IncomeGroup {
  /** `2026-03`, `2026`, an id, or a pay period's first day; `null` for what has no source or period. */
  key: string | null;
  label: string;
  /** The account's color; `null` for anything else. */
  color: string | null;
  count: number;
  grossCents: number;
  taxesCents: number;
  deductionsCents: number;
  takeHomeCents: number;
  oneOffsCents: number;
  interestCents: number;
  /** What an employer added: beside what was received, not in it. */
  matchCents: number;
  /** Take-home, one-offs and interest together. */
  receivedCents: number;
}

/** An income report that was run. */
export interface IncomeReport {
  kind: "income";
  from: string;
  to: string;
  groupBy: IncomeGroupBy;
  groups: IncomeGroup[];
  /** Every line counted, oldest first. */
  rows: IncomeRow[];
  count: number;
  grossCents: number;
  taxesCents: number;
  deductionsCents: number;
  takeHomeCents: number;
  oneOffsCents: number;
  interestCents: number;
  matchCents: number;
  receivedCents: number;
  /** How much of what was received is an estimate, or dated after today. */
  estimatedCents: number;
  /** Paychecks with no gross amount to count: the gross and taxes are then of fewer paychecks than the take-home. */
  paychecksWithoutGross: number;
}

/** One amount, as projected and as it was. */
export interface ProjectedRow {
  kind: "bill" | "budget" | "paycheck";
  /** The recurring item it is one time of; `null` for a paycheck. */
  itemId: number | null;
  /** The income source of a paycheck; `null` for anything else. */
  sourceId: number | null;
  scheduledDate: string;
  date: string;
  description: string;
  categoryId: number | null;
  /** The account it is paid from, or the one a paycheck lands in. */
  accountId: number;
  /** What Budget showed before the amount was typed over; the same as `actualCents` when it never was. */
  projectedCents: number;
  actualCents: number;
  /** What it was, less what was projected. */
  differenceCents: number;
  changed: boolean;
  /** True while its day has not come (and a bill has not been marked paid). */
  toCome: boolean;
}

export interface ProjectedGroup {
  key: string | null;
  label: string;
  /** The category's color; `null` for anything else. */
  color: string | null;
  count: number;
  /** How many of its lines came to something other than projected. */
  changed: number;
  projectedCents: number;
  actualCents: number;
  differenceCents: number;
}

/** Money going out, or paychecks coming in: never added to each other. */
export interface ProjectedSide {
  groups: ProjectedGroup[];
  count: number;
  changed: number;
  projectedCents: number;
  actualCents: number;
  differenceCents: number;
}

/** A projected vs. actual report that was run. */
export interface ProjectedReport {
  kind: "projected_vs_actual";
  from: string;
  to: string;
  groupBy: CompareGroupBy;
  spending: ProjectedSide;
  paychecks: ProjectedSide;
  /** Every line counted, oldest first. */
  rows: ProjectedRow[];
}

/** What a new report starts as: this year's one-off spending, month by month. */
export const NEW_REPORT: ReportDefinition = {
  range: "this_year",
  from: null,
  to: null,
  include: ["one_offs"],
  tagIds: [],
  categoryIds: [],
  accountIds: [],
  payees: [],
  reimbursement: [],
  groupBy: "month",
  show: "chart_and_table",
  kind: "spending",
  incomeInclude: [],
  sourceIds: [],
  incomeGroupBy: "month",
  compareInclude: [],
  compareGroupBy: "month",
  changedOnly: false,
};

/** What a new income report starts as: this year's paychecks and other money in, month by month. */
export const NEW_INCOME_REPORT: ReportDefinition = {
  ...NEW_REPORT,
  include: [],
  kind: "income",
  incomeInclude: ["paychecks", "one_offs", "match", "interest"],
};

/** What a new projected vs. actual report starts as: this year's bills, budgets and paychecks, month by month. */
export const NEW_PROJECTED_REPORT: ReportDefinition = {
  ...NEW_REPORT,
  include: [],
  kind: "projected_vs_actual",
  compareInclude: ["bills", "budgets", "paychecks"],
};

/** Under the pay key: a report follows every entry, bill and receipt it counts. */
export const REPORTS_KEY = [...PAY_KEY, "reports"] as const;
const SAVED_KEY = ["saved-reports"] as const;

export function useSavedReports() {
  return useQuery({ queryKey: SAVED_KEY, queryFn: () => api.get<SavedReport[]>("/api/reports") });
}

/** Runs `definition`; with `null`, waits (dates half chosen, say). */
export function useReport(definition: ReportDefinition | null) {
  return useQuery({
    queryKey: [...REPORTS_KEY, definition],
    queryFn: () => api.post<Report | IncomeReport | ProjectedReport>("/api/reports/run", { definition }),
    enabled: definition !== null,
    // The figures on screen stay while a changed report is worked out, instead of blinking away.
    placeholderData: (previous) => previous,
  });
}

/** Saves a new report, or the one with `id` under its (possibly new) name. */
export function useSaveReport() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, name, definition }: { id?: number; name: string; definition: ReportDefinition }) =>
      id === undefined
        ? api.post<SavedReport>("/api/reports", { name, definition })
        : api.put<SavedReport>(`/api/reports/${id}`, { name, definition }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: SAVED_KEY }),
  });
}

export function useDeleteReport() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.delete<{ deleted: boolean }>(`/api/reports/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: SAVED_KEY }),
  });
}

/**
 * Asks for the report as a file. From this PC the backend saves it in the Downloads folder and the
 * answer says what it is called; from anywhere else the file comes back and the browser saves it.
 */
export function useExportReport() {
  return useMutation({
    mutationFn: async ({ format, name, definition, withReceipts }: {
      format: "csv" | "pdf";
      name: string;
      definition: ReportDefinition;
      withReceipts?: boolean;
    }): Promise<Saved | null> => {
      const handed = await api.handOver(
        `/api/reports/export/${format}`,
        format === "pdf" ? { name, definition, withReceipts: withReceipts ?? false, save: true } : { name, definition, save: true },
      );
      if (handed.saved === null) {
        saveFile(handed.download);
      }
      return handed.saved;
    },
  });
}
