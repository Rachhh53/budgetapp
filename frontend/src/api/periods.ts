import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import type { Entry } from "./entries";
import { PAY_KEY, type PayPeriods, type Paycheck } from "./income";
import type { ItemKind, Placement } from "./items";

/** One occurrence of a recurring item, with any "just this one" edit applied. */
export interface Occurrence {
  itemId: number;
  name: string;
  kind: ItemKind;
  /** The date the schedule names; identifies this occurrence when editing it. */
  scheduledDate: string;
  dueDate: string;
  /** First day of the pay period that pays for it. */
  periodStart: string | null;
  early: boolean;
  /** The day it counts on: the due date, brought inside the period that pays for it. */
  payBy: string;
  amountCents: number;
  estimate: boolean;
  /** What the app showed before the amount was typed over; `null` if untouched. */
  projectedCents: number | null;
  autopay: boolean;
  placement: Placement;
  categoryId: number | null;
  payFromAccountId: number;
  payToAccountId: number | null;
  paid: boolean;
  paidDate: string | null;
  confirmationCode: string | null;
  note: string | null;
  skipped: boolean;
}

/**
 * One account across one pay period: `ending = starting + in − out`. For a credit card or
 * loan the balance is what is owed, so `in` is what was charged and `out` what was paid off.
 */
export interface AccountPeriod {
  accountId: number;
  startingCents: number;
  /** The period's first day, or the day of a balance entered during it. */
  startingOn: string;
  /** True when the starting balance is one the user entered during the period. */
  entered: boolean;
  inCents: number;
  outCents: number;
  endingCents: number;
  /** The lowest end-of-day balance in the period, and the day it happens. */
  lowestCents: number;
  lowestOn: string;
}

/** Everything in one pay period. */
export interface PeriodDetail {
  start: string;
  end: string;
  scheduledPayday: string;
  current: boolean;
  primaryAccountId: number | null;
  paychecks: Paycheck[];
  /** Recurring items paid from this period, skipped ones included. */
  occurrences: Occurrence[];
  /** Unpaid items from an earlier period whose money still comes out of this one. */
  carriedOver: Occurrence[];
  /** One-off entries dated in this period. */
  entries: Entry[];
  /** Every account a balance is known for. */
  accounts: AccountPeriod[];
}

/** What a "just this one" edit can change. Only what is sent changes. */
export interface OccurrenceEdit {
  amountCents?: number;
  paid?: boolean;
  paidDate?: string;
  /** An empty code clears it. */
  confirmationCode?: string;
}

/** Everything in the pay period that opens on `start`. */
export function usePeriod(start: string | undefined) {
  return useQuery({
    queryKey: [...PAY_KEY, "period", start],
    queryFn: () => api.get<PeriodDetail>(`/api/pay-periods/${start}`),
    enabled: start !== undefined,
  });
}

/** The pay periods overlapping `from`–`to`, for the strip of ending balances around one period. */
export function usePayPeriodsBetween(from: string | undefined, to: string | undefined) {
  return useQuery({
    queryKey: [...PAY_KEY, "periods", from, to],
    queryFn: () => api.get<PayPeriods>(`/api/pay-periods?from=${from}&to=${to}`),
    enabled: from !== undefined && to !== undefined,
    // Keep the strip on screen while the next stretch loads, so moving between periods does not flicker.
    placeholderData: (previous) => previous,
  });
}

/** Changes one occurrence of a recurring item ("just this one"). */
export function useEditOccurrence() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ occurrence, edit }: { occurrence: Pick<Occurrence, "itemId" | "scheduledDate">; edit: OccurrenceEdit }) =>
      api.put<Occurrence>(`/api/items/${occurrence.itemId}/occurrences/${occurrence.scheduledDate}`, edit),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PAY_KEY }),
  });
}
