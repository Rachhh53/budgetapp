import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ACCOUNTS_KEY } from "./accounts";
import { BUCKETS_KEY } from "./buckets";
import { api } from "./client";
import { PAY_KEY } from "./income";

/**
 * Paid ahead (spec §4.6): money paid for something before it is used, like a course paid for up
 * front. Each session in the item's schedule uses up some of it; no money moves on a session day.
 * A payment made ahead is a one-off entry of money going out, changed or removed as that one-off.
 */

export interface Session {
  /** The day its schedule names, which is how it is skipped or put back. */
  scheduledDate: string;
  date: string;
  amountCents: number;
  /** It did not (or will not) happen, and uses nothing. */
  skipped: boolean;
  /** What was paid ahead covers it in full. */
  covered: boolean;
}

export interface Prepayment {
  /** The one-off entry that is this payment. */
  entryId: number;
  date: string;
  amountCents: number;
  accountId: number;
}

export interface PaidAheadStanding {
  paidCents: number;
  /** What the sessions up to today came to. */
  usedCents: number;
  /** What is still there; negative once sessions went past what was paid. */
  leftCents: number;
  /** The last session it covers; `null` when none. */
  coversThrough: string | null;
  /** The first session it doesn't cover; `null` when it covers every one ahead. */
  firstUncovered: string | null;
  /** Up to today, newest first, skipped ones included. */
  used: Session[];
  /** The next few after today, skipped ones included. */
  upcoming: Session[];
  /** Newest first. */
  payments: Prepayment[];
  /** Bought as a pack: how many sessions it has; `null` for a price per session. */
  packSessions: number | null;
  /** Bought as a pack: its price today; `null` otherwise. */
  packPriceCents: number | null;
  /** Bought as a pack: the day the next one is due, planned as a bill; `null` otherwise or when nothing is ahead. */
  nextPack: string | null;
  /** The day Budget planned that pack for, which names it for moving it; the same as `nextPack` unless it was moved. */
  nextPackPlanned: string | null;
  /** A pack on its own schedule: the first session the packs paid by then don't cover; `null` when they always do. */
  shortFrom: string | null;
}

/** A paid-ahead item whose money runs out in a pay period: shown there as a warning, never as a bill. */
export interface RunningOut {
  itemId: number;
  name: string;
  leftCents: number;
  coversThrough: string | null;
  firstUncovered: string;
  /** Offered first for another payment. */
  payFromAccountId: number;
}

export interface PrepaymentInput {
  date: string;
  amountCents: number;
  /** Left out, the account the item is paid from. */
  accountId?: number;
  note?: string;
}

const paidAheadKey = (itemId: number) => [...PAY_KEY, "paid-ahead", itemId];

export function usePaidAhead(itemId: number) {
  return useQuery({
    queryKey: paidAheadKey(itemId),
    queryFn: () => api.get<PaidAheadStanding>(`/api/items/${itemId}/paid-ahead`),
  });
}

/**
 * Moves a planned pack to another day, or back to the day it was planned for with `date: null`
 * (Rachel, 2026-10-20: "the option to just change the date"). The pack is named by its planned day.
 */
export function useMovePack(itemId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ planned, date }: { planned: string; date: string | null }) =>
      date === null
        ? api.delete<PaidAheadStanding>(`/api/items/${itemId}/paid-ahead/packs/${planned}`)
        : api.put<PaidAheadStanding>(`/api/items/${itemId}/paid-ahead/packs/${planned}`, { date }),
    // A pack is a bill in a pay period, so every period and balance is asked again.
    onSuccess: () => Promise.all([queryClient.invalidateQueries({ queryKey: PAY_KEY }), queryClient.invalidateQueries({ queryKey: ACCOUNTS_KEY })]),
  });
}

/** Records a payment made ahead. It is spending from an account, so balances and buckets are asked again too. */
export function useAddPrepayment(itemId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: PrepaymentInput) => api.post<PaidAheadStanding>(`/api/items/${itemId}/paid-ahead/payments`, input),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: PAY_KEY }),
        queryClient.invalidateQueries({ queryKey: ACCOUNTS_KEY }),
        queryClient.invalidateQueries({ queryKey: BUCKETS_KEY }),
      ]),
  });
}
