import { useMutation, useQuery } from "@tanstack/react-query";
import { api, type Saved, saveFile } from "./client";
import { PAY_KEY } from "./income";

/** Calendar years, or twelve whole months ending with last month. */
export const ANNUAL_BASES = ["calendar", "rolling"] as const;
export type AnnualBasis = (typeof ANNUAL_BASES)[number];

export const ANNUAL_BASIS_LABELS: Record<AnnualBasis, string> = { calendar: "Calendar years", rolling: "Rolling 12 months" };

export interface AnnualWindow {
  from: string;
  to: string;
  /** "2026", or "Oct 2025 – Sep 2026". */
  label: string;
}

/** One recurring bill or budget across the windows. */
export interface AnnualRow {
  itemId: number;
  name: string;
  kind: "bill" | "budget";
  categoryId: number | null;
  categoryName: string | null;
  color: string | null;
  /** One per window, oldest first. */
  totalsCents: number[];
  /** The newest window against the one before, in basis points (9.1% is 910); `null` with nothing to compare. */
  changeBp: number | null;
  perMonthCents: number;
}

export interface AnnualCosts {
  basis: AnnualBasis;
  windows: AnnualWindow[];
  /** Largest in the newest window first. */
  rows: AnnualRow[];
  totalsCents: number[];
  changeBp: number | null;
  perMonthCents: number;
  /** The item whose cost rose by the largest share; `null` when nothing rose. */
  biggestIncrease: { itemId: number; name: string; changeBp: number; increaseCents: number } | null;
  /** Calendar years: the first day of what is still to come this year, counted as budgeted; `null` otherwise. */
  budgetedFrom: string | null;
}

export interface CostMonth {
  /** `2026-07`. */
  month: string;
  amountCents: number;
  /** The same month the year before. */
  beforeCents: number;
  /** True for a month still to come: its amount is what is budgeted. */
  toCome: boolean;
}

/** One bill's or budget's year, month by month, beside the year before. */
export interface CostsByMonth {
  year: number;
  months: CostMonth[];
  highest: { month: string; amountCents: number } | null;
  lowest: { month: string; amountCents: number } | null;
  totalCents: number;
  /** The year shared over twelve months. */
  averageCents: number;
  /** What the item comes to a month at today's amount. */
  budgetCents: number;
  /** True when the year comes to more than today's amount would over a year. */
  budgetTooLow: boolean;
}

/** Under the pay key: the totals follow every bill and budget as it changes. */
export function useAnnualCosts(basis: AnnualBasis) {
  return useQuery({
    queryKey: [...PAY_KEY, "annual-costs", basis],
    queryFn: () => api.get<AnnualCosts>(`/api/annual-costs?basis=${basis}`),
    placeholderData: (previous) => previous,
  });
}

/** `enabled` is false for anything that is not a bill or a budget: nothing else is a cost. */
export function useCostsByMonth(itemId: number, enabled: boolean) {
  return useQuery({
    queryKey: [...PAY_KEY, "costs-by-month", itemId],
    queryFn: () => api.get<CostsByMonth>(`/api/items/${itemId}/by-month`),
    enabled,
  });
}

/**
 * Asks for the table as a CSV. From this PC the backend saves it in the Downloads folder and the
 * answer says what it is called; from anywhere else the file comes back and the browser saves it.
 */
export function useExportAnnualCosts() {
  return useMutation({
    mutationFn: async (basis: AnnualBasis): Promise<Saved | null> => {
      const handed = await api.handOver("/api/annual-costs/export/csv", { basis, save: true });
      if (handed.saved === null) {
        saveFile(handed.download);
      }
      return handed.saved;
    },
  });
}
