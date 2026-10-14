import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { AccountType } from "./accounts";
import { api } from "./client";
import { PAY_KEY } from "./income";

/** How long the comparison runs, in years: the choices the backend takes. */
export const PLANNER_YEARS = [1, 3, 5, 10, 20, 30] as const;
export type PlannerYears = (typeof PLANNER_YEARS)[number];

/** Which debt the extra goes to first. */
export const PAYOFF_ORDERS = ["highest_rate", "smallest_balance"] as const;
export type PayoffOrder = (typeof PAYOFF_ORDERS)[number];

export const PAYOFF_ORDER_LABELS: Record<PayoffOrder, string> = {
  highest_rate: "Highest rate first",
  smallest_balance: "Smallest balance first",
};

/** Something worth knowing before choosing. Which fields say something depends on `kind` (see `PlannerService.Flag`). */
export interface PlannerFlag {
  kind: "unclaimed_match" | "promo_ending";
  /** The income source, or the promotion. */
  name: string;
  accountName: string;
  accountId: number | null;
  sourceId: number | null;
  date: string | null;
  /** More a month that would still be matched, or what is left on the promotion. */
  amountCents: number | null;
  /** What that much more would bring from the employer each month. */
  matchCents: number | null;
  capBp: number | null;
  putInBp: number | null;
  perPaycheckCents: number | null;
  /** A payoff for the promotion is already among the recurring payments. */
  planned: boolean;
  /** Deferred interest. */
  urgent: boolean;
}

/** One place the extra could go. A debt fills the debt figures, an account to invest or save in the others. */
export interface PlannerOption {
  accountId: number;
  name: string;
  color: string;
  type: AccountType;
  subtypeLabel: string | null;
  kind: "debt" | "invest" | "save";
  balanceCents: number;
  /** A debt's rate today, or the yearly rate the value is worked out at; `null` when none is known (taken as 0%). */
  rateBp: number | null;
  /** The account's own assumed growth or APY; `null` when it has none. */
  accountRateBp: number | null;
  /** `no_payment`: a loan with no monthly payment entered cannot be compared yet. */
  problem: "no_payment" | null;
  paymentCents: number | null;
  /** `null` when the debt is never paid off without the extra, so there is nothing to compare with. */
  interestSavedCents: number | null;
  payoffDate: string | null;
  payoffDateBefore: string | null;
  monthsSooner: number | null;
  freedCents: number | null;
  paysOff: boolean | null;
  paysOffBefore: boolean | null;
  rateAfterBp: number | null;
  rateChangesOn: string | null;
  valueCents: number | null;
  putInCents: number | null;
  growthCents: number | null;
  matchCents: number | null;
  matchedPerMonthCents: number | null;
  matchPerMonthCents: number | null;
}

export interface PayoffDebt {
  accountId: number;
  name: string;
  color: string;
  balanceCents: number;
  rateBp: number | null;
  rateAfterBp: number | null;
  rateChangesOn: string | null;
  payoffDate: string | null;
  payoffDateBefore: string | null;
  paysOff: boolean;
  paysOffBefore: boolean;
  /** A promotional balance with its payoff already planned: it takes none of the extra. */
  planned: boolean;
}

export interface PlannerComparison {
  today: string;
  extraCents: number;
  years: number;
  flags: PlannerFlag[];
  options: PlannerOption[];
  payoff: {
    order: PayoffOrder;
    /** In the order they are gone. */
    debts: PayoffDebt[];
    debtFreeDate: string | null;
    debtFreeDateBefore: string | null;
    interestSavedCents: number | null;
  };
  hidden: { accountId: number; name: string; type: AccountType }[];
}

export interface PlannerInput {
  extraCents: number;
  years: PlannerYears;
  order: PayoffOrder;
  /** A yearly rate to assume for an account in place of its own, by account id. Never saved. */
  growthBp: Record<number, number>;
}

/**
 * The comparison, worked out by the backend. It is asked with a POST because of what it sends,
 * but it is a look-up: nothing is saved. Under the pay key, since every figure follows the
 * balances, rates and payments the other screens change.
 */
export function usePlanner(input: PlannerInput) {
  return useQuery({
    queryKey: [...PAY_KEY, "planner", input],
    queryFn: () => api.post<PlannerComparison>("/api/planner", input),
    // The cards stay on screen while another amount is worked out, instead of blinking away.
    placeholderData: (previous) => previous,
  });
}

/** Leaves an account out of the comparison, or brings it back. Undo takes either back. */
export function useSetPlannerHidden() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ accountId, hidden }: { accountId: number; hidden: boolean }) =>
      hidden
        ? api.put<{ done: boolean }>(`/api/planner/hidden/${accountId}`, {})
        : api.delete<{ done: boolean }>(`/api/planner/hidden/${accountId}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [...PAY_KEY, "planner"] }),
  });
}
