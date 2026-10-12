import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";

/** The schedules a paycheck can follow. */
export const PAY_FREQUENCIES = ["weekly", "biweekly", "semi_monthly", "monthly", "manual_dates"] as const;
export type PayFrequency = (typeof PAY_FREQUENCIES)[number];

export const WEEKEND_RULES = ["previous_business_day", "next_business_day", "none"] as const;
export type WeekendRule = (typeof WEEKEND_RULES)[number];

/** Day-of-month value meaning "last day of the month". */
export const LAST_DAY = 31;

export interface Schedule {
  frequency: PayFrequency;
  /** Day of the month, 1–31; 31 means the last day. */
  day1: number | null;
  day2: number | null;
  startDate: string | null;
  endDate: string | null;
  weekendRule: WeekendRule;
  manualDates: string[];
}

export interface Deduction {
  name: string;
  /** What it takes out of one paycheck. For a share of pay, the backend works it out from the gross amount. */
  amountCents: number;
  /** `null` when the money does not go to one of the user's accounts. */
  toAccountId: number | null;
  /** Set when it was given as a share of gross pay (6% is 600); `null` for a dollar amount. */
  percentBp: number | null;
}

/** A deduction as it is sent: a dollar amount, or a share of gross pay, never both. */
export type DeductionInput =
  | { name: string; toAccountId: number | null; amountCents: number }
  | { name: string; toAccountId: number | null; percentBp: number };

export const MATCH_MODES = ["fixed", "formula"] as const;
export type MatchMode = (typeof MATCH_MODES)[number];

/** What the employer adds with each paycheck. Rates are basis points: 50% up to 6% of pay is 5000 and 600. */
export interface EmployerMatch {
  /** The investment account the match is paid into. */
  toAccountId: number;
  mode: MatchMode;
  /** The amount every paycheck; set exactly when `mode` is `fixed`. */
  fixedCents: number | null;
  /** The share of what is contributed that is matched; for `formula`. */
  matchBp: number | null;
  /** The share of gross pay beyond which contributions are not matched; for `formula`. */
  capBpOfGross: number | null;
  /**
   * There is no gross amount to check against, and the user confirmed that what they put in is
   * within that share, so all of it is matched. Always false once gross pay is entered.
   */
  contributionWithinCap: boolean;
}

/** What a match comes to per paycheck, worked out by the backend. */
export interface MatchFigures {
  perPaycheckCents: number;
  /** What the employer would add if contributions reached the cap. */
  mostPossibleCents: number;
  /** How much more to contribute per paycheck to earn all of it; zero at or over the cap. */
  contributeMoreCents: number;
}

/**
 * What a source's paychecks come to from a date on: the amounts it started with, or a later
 * change of pay (a raise). A paycheck uses the latest one dated on or before its payday.
 */
export interface PayTerms {
  /** The change of pay; `null` for the amounts the source started with. */
  id: number | null;
  /** The first day these apply; `null` for the amounts the source started with. */
  effectiveDate: string | null;
  grossCents: number | null;
  netCents: number;
  netIsEstimate: boolean;
  deductions: Deduction[];
  match: EmployerMatch | null;
  matchFigures: MatchFigures | null;
  /** These are the amounts in force today. */
  current: boolean;
}

/**
 * An income source. Its amounts (gross, net, deductions, match) are the ones in force today;
 * `payHistory` has every set of amounts it has had or will have, oldest first.
 */
export interface IncomeSource {
  id: number;
  name: string;
  depositAccountId: number;
  /** `null` when only take-home pay is tracked. */
  grossCents: number | null;
  netCents: number;
  netIsEstimate: boolean;
  /** The primary source's schedule defines the pay periods. */
  primary: boolean;
  schedule: Schedule;
  deductions: Deduction[];
  /** `null` when the employer adds nothing. */
  match: EmployerMatch | null;
  /** `null` without a match. */
  matchFigures: MatchFigures | null;
  payHistory: PayTerms[];
}

export type IncomeSourceInput = Omit<IncomeSource, "id" | "matchFigures" | "deductions" | "payHistory"> & {
  deductions: DeductionInput[];
  /**
   * Only when changing a source: the amounts are a change of pay from this day on (a raise),
   * and earlier paychecks keep what they had. Left out, the amounts in force today are corrected.
   */
  effectiveFrom?: string;
  /** Only when changing, without `effectiveFrom`: correct the amounts the source started with instead. */
  originalAmounts?: boolean;
};

/** Money a paycheck puts straight into another account: a deduction that names one, or the employer's match. */
export interface Contribution {
  toAccountId: number;
  label: string;
  kind: "contribution" | "employer_match";
  amountCents: number;
}

export interface Paycheck {
  incomeSourceId: number;
  sourceName: string;
  /** The account it lands in. */
  depositAccountId: number;
  /** The date the schedule names; identifies this paycheck when editing it. */
  scheduledDate: string;
  /** When it actually arrives, after any weekend or holiday move. */
  payDate: string;
  netCents: number;
  estimate: boolean;
  /** What the app showed before the amount was typed over; `null` if untouched. */
  projectedCents: number | null;
  /** What goes straight to other accounts with this paycheck, never passing through the deposit account. */
  contributions: Contribution[];
}

export interface PayPeriod {
  start: string;
  end: string;
  scheduledPayday: string;
  current: boolean;
  incomeCents: number;
  paychecks: Paycheck[];
  /** The primary paycheck's account at the end of the period; `null` until a balance is entered for it. */
  projectedEndingCents: number | null;
  /** That account's lowest end-of-day balance in the period. */
  lowestCents: number | null;
  /** The ending balance less what is in that account's buckets. */
  availableCents: number | null;
  /** True when that account dips below the low-balance amount the user set for it. */
  lowBalance: boolean;
  /** Promotional rates that end in this period with a balance still on them. */
  promosEnding?: PromoEnd[];
}

/** A card's promotional rate running out during a pay period with some of its balance still there. */
export interface PromoEnd {
  accountId: number;
  promoId: number;
  label: string;
  endDate: string;
  deferredInterest: boolean;
  /** What is projected to be left of it at the end of that pay period. */
  remainingCents: number;
}

export interface PayPeriods {
  /** `null` until an income source exists. */
  primarySourceId: number | null;
  /** The account the primary paycheck lands in. */
  primaryAccountId: number | null;
  periods: PayPeriod[];
}

export const INCOME_KEY = ["income-sources"] as const;
/** Everything worked out from schedules (paychecks, periods) lives under this key. */
export const PAY_KEY = ["pay"] as const;

export function useIncomeSources() {
  return useQuery({ queryKey: INCOME_KEY, queryFn: () => api.get<IncomeSource[]>("/api/income-sources") });
}

/** Paychecks from today to the projection horizon. */
export function usePaychecks() {
  return useQuery({ queryKey: [...PAY_KEY, "paychecks"], queryFn: () => api.get<Paycheck[]>("/api/paychecks") });
}

/** Pay periods from the current one to the projection horizon. */
export function usePayPeriods() {
  return useQuery({ queryKey: [...PAY_KEY, "periods"], queryFn: () => api.get<PayPeriods>("/api/pay-periods") });
}

function useRefreshIncome() {
  const queryClient = useQueryClient();
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: INCOME_KEY }),
      queryClient.invalidateQueries({ queryKey: PAY_KEY }),
    ]);
}

/** Creates an income source, or changes the one with `id`. */
export function useSaveIncomeSource(id?: number) {
  const refresh = useRefreshIncome();
  return useMutation({
    mutationFn: (input: IncomeSourceInput) =>
      id === undefined
        ? api.post<IncomeSource>("/api/income-sources", input)
        : api.put<IncomeSource>(`/api/income-sources/${id}`, input),
    onSuccess: refresh,
  });
}

/** "Pay periods follow": makes one source the primary one, leaving everything else about it alone. */
export function useMakePrimary() {
  const refresh = useRefreshIncome();
  return useMutation({
    mutationFn: (id: number) => api.put<IncomeSource>(`/api/income-sources/${id}/primary`, {}),
    onSuccess: refresh,
  });
}

export function useDeleteIncomeSource(id: number) {
  const refresh = useRefreshIncome();
  return useMutation({
    mutationFn: () => api.delete<{ done: boolean }>(`/api/income-sources/${id}`),
    onSuccess: refresh,
  });
}

/**
 * What a source brings home in this calendar year, counting each paycheck in the year it
 * arrives. Raises and typed-over paychecks are already in the figures.
 */
export interface IncomeThisYear {
  year: number;
  /** Take-home from the paychecks that have arrived, today's included. */
  soFarCents: number;
  paychecksSoFar: number;
  /** Take-home from every paycheck of the year, those still to come included. */
  wholeYearCents: number;
  paychecksInYear: number;
  /** At least one paycheck still to come is an estimate. */
  estimate: boolean;
  /** Gross pay of the paychecks that have arrived and have a gross amount entered. */
  grossSoFarCents: number;
  /** How many of them that is: fewer than `paychecksSoFar` when some have none. */
  grossPaychecksSoFar: number;
  /** Gross pay of every paycheck of the year that has a gross amount entered. */
  grossWholeYearCents: number;
  grossPaychecksInYear: number;
}

/** Kept under the pay key, so it follows every change to a schedule, an amount or a single paycheck. */
export function useIncomeThisYear(sourceId: number) {
  return useQuery({
    queryKey: [...PAY_KEY, "income-this-year", sourceId],
    queryFn: () => api.get<IncomeThisYear>(`/api/income-sources/${sourceId}/this-year`),
  });
}

/** Removes one change of pay, so the amounts before it carry on. */
export function useDeletePayChange(sourceId: number) {
  const refresh = useRefreshIncome();
  return useMutation({
    mutationFn: (changeId: number) => api.delete<IncomeSource>(`/api/income-sources/${sourceId}/pay-changes/${changeId}`),
    onSuccess: refresh,
  });
}

/** Types over the amount of a single paycheck ("just this one"). */
export function useEditPaycheck() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ paycheck, netCents }: { paycheck: Paycheck; netCents: number }) =>
      api.put<{ done: boolean }>(
        `/api/income-sources/${paycheck.incomeSourceId}/paychecks/${paycheck.scheduledDate}`,
        { netCents },
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PAY_KEY }),
  });
}
