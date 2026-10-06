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
  amountCents: number;
  /** `null` when the money does not go to one of the user's accounts. */
  toAccountId: number | null;
}

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
}

export type IncomeSourceInput = Omit<IncomeSource, "id">;

export interface Paycheck {
  incomeSourceId: number;
  sourceName: string;
  /** The date the schedule names; identifies this paycheck when editing it. */
  scheduledDate: string;
  /** When it actually arrives, after any weekend or holiday move. */
  payDate: string;
  netCents: number;
  estimate: boolean;
  /** What the app showed before the amount was typed over; `null` if untouched. */
  projectedCents: number | null;
}

export interface PayPeriod {
  start: string;
  end: string;
  scheduledPayday: string;
  current: boolean;
  incomeCents: number;
  paychecks: Paycheck[];
}

export interface PayPeriods {
  /** `null` until an income source exists. */
  primarySourceId: number | null;
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

export function useDeleteIncomeSource(id: number) {
  const refresh = useRefreshIncome();
  return useMutation({
    mutationFn: () => api.delete<{ done: boolean }>(`/api/income-sources/${id}`),
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
