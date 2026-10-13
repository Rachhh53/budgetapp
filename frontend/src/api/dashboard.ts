import { useQuery } from "@tanstack/react-query";
import { api } from "./client";
import { PAY_KEY } from "./income";

/** The dates "Spending by category" covers. */
export const DASHBOARD_RANGES = ["period", "three_months", "year"] as const;
export type DashboardRange = (typeof DASHBOARD_RANGES)[number];

export const DASHBOARD_RANGE_LABELS: Record<DashboardRange, string> = {
  period: "This period",
  three_months: "Last 3 months",
  year: "This year",
};

/** One thing coming up or wanting attention. Which fields say something depends on `kind` (see `DashboardService.Attention`). */
export interface Attention {
  kind: "past_due" | "due_soon" | "low_balance" | "promo_ending" | "stub_review" | "goal_short" | "price_up" | "balance_stale";
  name: string;
  date: string | null;
  amountCents: number | null;
  goalCents: number | null;
  /** Lines to check on a stub, a rise in basis points, or how many accounts: by `kind`. */
  count: number | null;
  /** A promotion with deferred interest: the stronger warning. */
  urgent: boolean;
  accountId: number | null;
  itemId: number | null;
  sourceId: number | null;
  stubId: number | null;
  periodStart: string | null;
}

export interface CategorySpending {
  /** `null` for spending with no category. */
  categoryId: number | null;
  name: string;
  color: string | null;
  totalCents: number;
}

export interface WorthPoint {
  date: string;
  netWorthCents: number;
  /** True for a day after today. */
  projected: boolean;
}

export interface Dashboard {
  today: string;
  range: DashboardRange;
  /** `changeCents` is since the end of last month; `null`, with `since`, when that is not known. */
  netWorth: { cents: number; changeCents: number | null; since: string | null };
  /** The lowest any checking or savings account is projected to go; `null` until one has a balance. */
  lowestAhead: { cents: number; periodStart: string; accountId: number; accountName: string; low: boolean } | null;
  saved: { totalCents: number; transfersCents: number; contributionsCents: number; matchCents: number };
  debt: { totalCents: number; cardsCents: number; loansCents: number };
  /** Top-level categories, largest first; a sub-category counts under the one above it. */
  spending: { from: string; to: string; totalCents: number; categories: CategorySpending[] };
  netWorthOverTime: WorthPoint[];
  /** Most pressing first. */
  attention: Attention[];
}

/** Under the pay key: every figure here follows the balances, bills and paychecks the other screens change. */
export function useDashboard(range: DashboardRange) {
  return useQuery({
    queryKey: [...PAY_KEY, "dashboard", range],
    queryFn: () => api.get<Dashboard>(`/api/dashboard?range=${range}`),
    // The figures stay on screen while another range is worked out, instead of blinking away.
    placeholderData: (previous) => previous,
  });
}
