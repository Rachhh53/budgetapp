import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ACCOUNTS_KEY } from "./accounts";
import { BUCKETS_KEY } from "./buckets";
import { CATEGORIES_KEY, TAGS_KEY } from "./categories";
import { api } from "./client";
import { PAY_KEY, type WeekendRule } from "./income";

/** The kinds of recurring item. A bucket move sets money aside inside one account. */
export const ITEM_KINDS = ["bill", "transfer", "budget", "card_payment", "bucket_move"] as const;
export type ItemKind = (typeof ITEM_KINDS)[number];

export type AmountMode = "per_occurrence" | "annual";
export type Placement = "due_period" | "one_early";

/** The schedules a recurring item can follow. */
export const ITEM_FREQUENCIES = [
  "every_pay_period",
  "weekly",
  "biweekly",
  "semi_monthly",
  "monthly",
  "every_n_months",
  "yearly",
  "one_time",
] as const;
export type ItemFrequency = (typeof ITEM_FREQUENCIES)[number];

export interface ItemSchedule {
  frequency: ItemFrequency;
  /** Months apart, for `every_n_months`. */
  interval: number | null;
  /** Day of the month, 1–31; 31 means the last day. */
  day1: number | null;
  day2: number | null;
  /** 1–12, for `yearly`. */
  month: number | null;
  startDate: string;
  endDate: string | null;
  weekendRule: WeekendRule;
}

/** What "this and all future" can change about an item. */
export interface ItemTerms {
  /** As entered: a yearly total when `amountMode` is `annual`. */
  amountCents: number;
  amountMode: AmountMode;
  estimate: boolean;
  categoryId: number | null;
  payFromAccountId: number;
  /** Where a transfer or card payment goes; `null` otherwise. */
  payToAccountId: number | null;
  autopay: boolean;
  placement: Placement;
  schedule: ItemSchedule;
  /** For `every_pay_period`: how many days into the period it is dated. 0 is payday. */
  daysAfterPayday: number;
  /** The bucket a bucket move, or a transfer, puts its money in; `null` otherwise. */
  bucketId: number | null;
  /** The bucket a share of a bill is set aside in every paycheck; `null` when it is not saved up for. */
  saveUpBucketId: number | null;
}

/** The next occurrence still to pay. */
export interface NextOccurrence {
  scheduledDate: string;
  dueDate: string;
  /** First day of the pay period that pays for it; `null` when no period is known. */
  periodStart: string | null;
  /** True when it is paid from a period before the one it falls due in. */
  early: boolean;
}

/** One version of an item, for its price history. */
export interface PricePoint {
  effectiveFrom: string;
  perOccurrenceCents: number;
  perYearCents: number;
}

/** A recurring item as it stands today, plus what it works out to. */
export interface Item extends ItemTerms {
  id: number;
  name: string;
  kind: ItemKind;
  note: string | null;
  archived: boolean;
  /** The day it was added. Shown, never changed. */
  createdOn: string;
  tagIds: number[];
  perOccurrenceCents: number;
  perYearCents: number;
  /** What the next paycheck sets aside for it; `null` when it is not saved up for, or is fully saved. */
  saveUpPerPaycheckCents: number | null;
  /** `null` when nothing is due in the coming year. */
  next: NextOccurrence | null;
  /** Every version, oldest first. */
  history: PricePoint[];
  /** True while it is paused today: nothing it is scheduled for happens. */
  paused: boolean;
  /** The first day it runs again; `null` when not paused, or paused until turned back on. */
  pausedUntil: string | null;
}

export interface ItemInput extends ItemTerms {
  name: string;
  kind: ItemKind;
  note: string | null;
  archived: boolean;
  tagIds: number[];
  /** When changing an item: the change applies from this date on. */
  effectiveFrom?: string;
}

export const ITEMS_KEY = ["items"] as const;

export function useItems() {
  return useQuery({ queryKey: ITEMS_KEY, queryFn: () => api.get<Item[]>("/api/items") });
}

/**
 * An item changes what pay periods add up to, each account's chart, what buckets hold, and
 * how much each category and tag is used.
 */
function useRefreshItems() {
  const queryClient = useQueryClient();
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: ITEMS_KEY }),
      queryClient.invalidateQueries({ queryKey: PAY_KEY }),
      queryClient.invalidateQueries({ queryKey: ACCOUNTS_KEY }),
      queryClient.invalidateQueries({ queryKey: BUCKETS_KEY }),
      queryClient.invalidateQueries({ queryKey: CATEGORIES_KEY }),
      queryClient.invalidateQueries({ queryKey: TAGS_KEY }),
    ]);
}

/** Creates an item, or changes the one with `id` from `effectiveFrom` on. */
export function useSaveItem(id?: number) {
  const refresh = useRefreshItems();
  return useMutation({
    mutationFn: (input: ItemInput) =>
      id === undefined ? api.post<Item>("/api/items", input) : api.put<Item>(`/api/items/${id}`, input),
    onSuccess: refresh,
  });
}

/**
 * Archives the item with `id` and adds what is sent as a new item that starts today: for
 * turning an item into another kind without changing what it was before.
 */
export function useReplaceItem(id: number | undefined) {
  const refresh = useRefreshItems();
  return useMutation({
    mutationFn: (input: ItemInput) => api.post<Item>(`/api/items/${id}/replacement`, input),
    onSuccess: refresh,
  });
}

/** Pauses the item with `id` from today: until `until`, or until it is resumed when that is `null`. */
export function usePauseItem(id: number) {
  const refresh = useRefreshItems();
  return useMutation({
    mutationFn: (until: string | null) => api.put<Item>(`/api/items/${id}/pause`, until === null ? {} : { until }),
    onSuccess: refresh,
  });
}

/** Lets the paused item with `id` run again from today. */
export function useResumeItem(id: number) {
  const refresh = useRefreshItems();
  return useMutation({
    mutationFn: () => api.delete<Item>(`/api/items/${id}/pause`),
    onSuccess: refresh,
  });
}

/** An item as the request that would save it unchanged, to change one thing about it. */
export function itemAsInput(item: Item): ItemInput {
  return {
    name: item.name,
    kind: item.kind,
    note: item.note,
    archived: item.archived,
    tagIds: item.tagIds,
    amountCents: item.amountCents,
    amountMode: item.amountMode,
    estimate: item.estimate,
    categoryId: item.categoryId,
    payFromAccountId: item.payFromAccountId,
    payToAccountId: item.payToAccountId,
    autopay: item.autopay,
    placement: item.placement,
    schedule: item.schedule,
    daysAfterPayday: item.daysAfterPayday,
    bucketId: item.bucketId,
    saveUpBucketId: item.saveUpBucketId,
  };
}

/**
 * Ends recurring items on a day: nothing is scheduled after it, and everything up to it stays
 * as it was. Used to stop saving into a bucket that has reached its goal.
 */
export function useEndItems() {
  const refresh = useRefreshItems();
  return useMutation({
    mutationFn: ({ items, endDate, effectiveFrom }: { items: readonly Item[]; endDate: string; effectiveFrom: string }) =>
      Promise.all(
        items.map((item) =>
          api.put<Item>(`/api/items/${item.id}`, { ...itemAsInput(item), schedule: { ...item.schedule, endDate }, effectiveFrom }),
        ),
      ),
    onSuccess: refresh,
  });
}

/**
 * Changes an item's amount from one occurrence on ("this and all future"), leaving
 * everything else about it as it is. Earlier occurrences keep what they had.
 */
export function useChangeItemAmount() {
  const refresh = useRefreshItems();
  return useMutation({
    mutationFn: ({ item, amountCents, effectiveFrom }: { item: Item; amountCents: number; effectiveFrom: string }) => {
      // A typed amount is the amount: it stops being an estimate (spec §1).
      const input: ItemInput = { ...itemAsInput(item), amountCents, estimate: false, effectiveFrom };
      return api.put<Item>(`/api/items/${item.id}`, input);
    },
    onSuccess: refresh,
  });
}

export function useDeleteItem(id: number) {
  const refresh = useRefreshItems();
  return useMutation({
    mutationFn: () => api.delete<{ deleted: boolean }>(`/api/items/${id}`),
    onSuccess: refresh,
  });
}
