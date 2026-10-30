import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ACCOUNTS_KEY } from "./accounts";
import { BUCKETS_KEY, type MoveOut } from "./buckets";
import { api } from "./client";
import type { Entry, Reimbursement, ReimbursementStatus } from "./entries";
import { PAY_KEY } from "./income";
import { ITEMS_KEY } from "./items";
import type { TransactionSource } from "./periods";

/**
 * Paying back recurring payments (Rachel, 2026-10-24: "only payments I flag and a set amount defaulted to the payment
 * being flagged"). Under the pay key, so whatever changes the pay periods refreshes these too.
 */
export const REIMBURSEMENTS_KEY = [...PAY_KEY, "reimbursements"] as const;

/** How a recurring item is paid back, set once on the item. */
export interface ReimbursementSettings {
  itemId: number;
  /** Who pays it back ("HSA", "Work"); `null` when not said. */
  reimbursedBy: string | null;
  /** About how many days after each payment the money comes back. */
  daysAfter: number;
  /** The account it comes back into. */
  intoAccountId: number;
  /** The account it comes out of, like an FSA; `null` when it comes from outside (an insurer, work). */
  fromAccountId: number | null;
}

/** One payment of a recurring item flagged to be paid back. */
export interface FlaggedPayment {
  id: number;
  itemId: number;
  /** The date the item's schedule names, which identifies the payment. */
  scheduledDate: string;
  name: string;
  kind: "bill" | "budget";
  /** The day it was paid, once it is; until then the day it is due. */
  date: string;
  paid: boolean;
  /** What the payment itself is. */
  paymentCents: number;
  estimate: boolean;
  /** The account it is paid from. */
  accountId: number;
  categoryId: number | null;
  tagIds: number[];
  /** What is to come back. */
  amountCents: number;
  reimbursement: Reimbursement;
  /** The day what is still owed is planned to come back; `null` when the item has no way back set. */
  expectedOn: string | null;
  intoAccountId: number | null;
  /** The account it comes out of, like an FSA; `null` when it comes from outside. */
  fromAccountId: number | null;
  /** True when the payment was skipped after it was flagged. */
  skipped: boolean;
  fileCount: number;
}

/** A flagged payment with the money that came back for it. */
export interface FlaggedPaymentDetail {
  payment: FlaggedPayment;
  /** Money that came in to pay it back, oldest first. */
  paybacks: Entry[];
  /** Money moved out of a bucket to pay it back, oldest first. */
  movesOut: MoveOut[];
}

/** A pack paid ahead, still to buy, flagged to be paid back (Rachel, 2026-10-25: "Flag each pack"). */
export interface FlaggedPack {
  itemId: number;
  name: string;
  /** Its place in line: 1 is the next pack to buy. */
  packNumber: number;
  /** The day Budget planned it on, which names it. */
  plannedDate: string;
  /** The day it is due, moved or not. */
  dueDate: string;
  priceCents: number;
  /** What comes back, no more than the price. */
  amountCents: number;
  /** The due day plus the item's days after. */
  expectedOn: string;
  intoAccountId: number;
  /** The account it comes out of; `null` when it comes from outside. */
  fromAccountId: number | null;
}

/** An item's way back (`null` when none is set), its payments flagged, oldest first, and its packs flagged, next first. */
export interface ItemReimbursement {
  settings: ReimbursementSettings | null;
  payments: FlaggedPayment[];
  packs: FlaggedPack[];
}

/** Flagging a payment: what comes back, and the item's way back when it has none yet or it changes. */
export interface FlagInput {
  amountCents: number;
  reimbursedBy?: string | null;
  daysAfter?: number;
  intoAccountId?: number;
  /** The account this one comes out of, like an FSA; `null` from outside. Also the item's, when its way back is set here. */
  fromAccountId: number | null;
}

/** The page of one flagged payment: found by its item and the date its schedule names. */
export function paymentPath(itemId: number, scheduledDate: string): string {
  return `/recurring/${itemId}/payments/${scheduledDate}`;
}

/**
 * Where money planned to come back is recorded once it comes: the expense's page, the payment's page, or for a pack still
 * to buy, the item's page.
 */
export function moneyBackPath(source: TransactionSource): string | undefined {
  if (source.entryId !== null) {
    return `/expenses/${source.entryId}`;
  }
  if (source.itemId !== null) {
    return source.scheduledDate !== null ? paymentPath(source.itemId, source.scheduledDate) : `/recurring/${source.itemId}`;
  }
  return undefined;
}

const paymentUrl = (itemId: number, scheduledDate: string) => `/api/items/${itemId}/occurrences/${scheduledDate}/reimbursement`;

export function useItemReimbursement(itemId: number, enabled = true) {
  return useQuery({
    queryKey: [...REIMBURSEMENTS_KEY, "item", itemId],
    queryFn: () => api.get<ItemReimbursement>(`/api/items/${itemId}/reimbursement`),
    enabled: enabled && Number.isInteger(itemId),
  });
}

/** One flagged payment; a 404 means it is not flagged. */
export function usePaymentReimbursement(itemId: number, scheduledDate: string | undefined, enabled = true) {
  return useQuery({
    queryKey: [...REIMBURSEMENTS_KEY, "payment", itemId, scheduledDate],
    queryFn: () => api.get<FlaggedPaymentDetail>(paymentUrl(itemId, scheduledDate ?? "")),
    enabled: enabled && Number.isInteger(itemId) && scheduledDate !== undefined,
    retry: false,
  });
}

/** Every payment flagged in `year`, oldest first. */
export function usePaymentReimbursements(year: number, enabled = true) {
  return useQuery({
    queryKey: [...REIMBURSEMENTS_KEY, "year", year],
    queryFn: () => api.get<FlaggedPayment[]>(`/api/payment-reimbursements?year=${year}`),
    enabled,
  });
}

/** Money planned to come back changes the pay periods, the account it comes into, and the item's page. */
function useRefresh() {
  const queryClient = useQueryClient();
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: PAY_KEY }),
      queryClient.invalidateQueries({ queryKey: ACCOUNTS_KEY }),
      queryClient.invalidateQueries({ queryKey: BUCKETS_KEY }),
      queryClient.invalidateQueries({ queryKey: ITEMS_KEY }),
    ]);
}

export function useSaveReimbursementSettings(itemId: number) {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: (input: Omit<ReimbursementSettings, "itemId">) => api.put<ItemReimbursement>(`/api/items/${itemId}/reimbursement`, input),
    onSuccess: refresh,
  });
}

/** Flags a payment, or changes how much of it comes back. */
export function useFlagPayment(itemId: number, scheduledDate: string) {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: (input: FlagInput) => api.put<FlaggedPaymentDetail>(paymentUrl(itemId, scheduledDate), input),
    onSuccess: refresh,
  });
}

/** Nobody pays this payment back after all. */
export function useUnflagPayment(itemId: number, scheduledDate: string) {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: () => api.delete<{ deleted: boolean }>(paymentUrl(itemId, scheduledDate)),
    onSuccess: refresh,
  });
}

const packUrl = (itemId: number, plannedDate: string) => `/api/items/${itemId}/paid-ahead/packs/${plannedDate}/reimbursement`;

/** Flags a pack still to buy, or changes how much of it comes back. When it is bought, the flag goes onto the payment. */
export function useFlagPack(itemId: number, plannedDate: string) {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: (input: FlagInput) => api.put<ItemReimbursement>(packUrl(itemId, plannedDate), input),
    onSuccess: refresh,
  });
}

/** Nobody pays this pack back after all. */
export function useUnflagPack(itemId: number, plannedDate: string) {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: () => api.delete<ItemReimbursement>(packUrl(itemId, plannedDate)),
    onSuccess: refresh,
  });
}

/** Says where a flagged payment stands, as for a one-off expense. */
export function useSetPaymentStatus(itemId: number, scheduledDate: string) {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: (input: { status: Exclude<ReimbursementStatus, "none">; submittedOn: string | null; to: string | null }) =>
      api.put<FlaggedPaymentDetail>(`${paymentUrl(itemId, scheduledDate)}/status`, input),
    onSuccess: refresh,
  });
}
