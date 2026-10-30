import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ACCOUNTS_KEY } from "./accounts";
import { BUCKETS_KEY } from "./buckets";
import { CATEGORIES_KEY, TAGS_KEY } from "./categories";
import { api } from "./client";
import { PAY_KEY, type WeekendRule } from "./income";

/** The kinds of recurring item. A bucket move sets money aside inside one account. */
/** How much a card payment pays: an amount of its own, or one that follows the card. */
export const CARD_PAYMENT_MODES = ["fixed", "statement_balance", "full_balance", "minimum"] as const;
export type CardPaymentMode = (typeof CARD_PAYMENT_MODES)[number];

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
  /** Months apart, for `every_n_months`; weeks apart, for `weekly` (1 or `null` for every week). */
  interval: number | null;
  /** Day of the month, 1–31; 31 means the last day. */
  day1: number | null;
  day2: number | null;
  /** 1–12, for `yearly`. */
  month: number | null;
  startDate: string;
  endDate: string | null;
  weekendRule: WeekendRule;
  /**
   * The months it runs in, 1–12 (snowplowing November to March, say); empty or left out, every
   * month. A date it names in any other month doesn't happen, and a yearly total is shared over the
   * dates in these months alone (spec §4.6).
   */
  months?: number[];
  /** Weekly or every two weeks: the days of the week, 1 (Monday) to 7 (Sunday); empty or left out, just the start date's day. */
  weekdays?: number[];
}

/** What the uses of something billed by use are counted per. */
export type UsesPer = "week" | "month";

/** What "this and all future" can change about an item. */
export interface ItemTerms {
  /** As entered: a yearly total when `amountMode` is `annual`. */
  amountCents: number;
  amountMode: AmountMode;
  estimate: boolean;
  /**
   * A bill whose schedule names visits (a weekly appointment, say): they build up on the pay period,
   * moving no money, until an invoice is entered for them, and the invoice is what is paid (spec §4.6).
   */
  billedLater?: boolean;
  /**
   * A bill paid up front (a course, say): each date in its schedule is a session that uses up some
   * of what was paid ahead, moving no money itself (spec §4.6).
   */
  paidAhead?: boolean;
  /** Paid ahead as a pack: how many sessions the amount (then the pack's price) buys; `null` for an amount per session. */
  packSessions?: number | null;
  /**
   * A pack paid on a schedule of its own (every 10 weeks, say); `null` for the next pack due when the
   * sessions use up the last. The item's own schedule stays its sessions (spec §4.6).
   */
  packSchedule?: ItemSchedule | null;
  /**
   * Billed by use (snowplowing, say): about how many times it is used, in hundredths (2.5 is 250); `null`
   * for any other bill. The amount is then what each time costs, and it is billed monthly on its schedule's
   * day for every use since the last bill, counting only the schedule's months (spec §4.6).
   */
  usesHundredths?: number | null;
  /** Billed by use: what the uses are counted per. */
  usesPer?: UsesPer | null;
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
  /** The bucket of the paying account this comes out of; `null` for money that is not in a bucket. */
  fromBucketId: number | null;
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
  /** The version it is, to remove it from the history. */
  versionId: number;
  effectiveFrom: string;
  perOccurrenceCents: number;
  perYearCents: number;
  /** The category it had; `null` for none. */
  categoryId: number | null;
}

/** A recurring item as it stands today, plus what it works out to. */
export interface Item extends ItemTerms {
  id: number;
  name: string;
  kind: ItemKind;
  note: string | null;
  archived: boolean;
  /** The day it was archived as of: it counts before it and is put away from it on; `null` when it is not archived. */
  archivedOn: string | null;
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
  /** The credit card whose own payment this is; `null` for any other item. */
  paymentOfCardId?: number | null;
  /** True when its amount is worked out from that card (its statement balance, say), so `amountCents` means nothing. */
  followsCard?: boolean;
  /** How much a card's own payment pays; `null` for any other item. */
  cardPaymentMode?: CardPaymentMode | null;
  /** True when its next dates count on from the day it was last paid, not its schedule's own. */
  countsFromPaid?: boolean;
  /** True when what is still to come is estimated from what the same month came to a year before. */
  fromLastYear?: boolean;
}

export interface ItemInput extends ItemTerms {
  name: string;
  kind: ItemKind;
  note: string | null;
  archived: boolean;
  /** With `archived`: the day it is archived as of, today or earlier. Left out, the day it had, or today. */
  archivedOn?: string | null;
  tagIds: number[];
  /** When changing an item: the change applies from this date on. */
  effectiveFrom?: string;
  /** A card payment: how much it pays. Anything but `fixed` follows the card, and the amount sent means nothing. */
  cardPaymentMode?: CardPaymentMode | null;
  /** Count its next dates on from the day it was last paid. Left out, as it was. */
  countsFromPaid?: boolean;
  /** A bill or budget: estimate what is still to come from the same month a year before. Left out, as it was. */
  fromLastYear?: boolean;
  /** When changing an item: the category reaches back over every earlier date too, not only from `effectiveFrom`. */
  categoryEveryDate?: boolean;
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

/** The archived item another was added in place of, back when a change of type could archive it and add a new one. */
export interface ReplacedItem {
  id: number;
  name: string;
  kind: ItemKind;
  archivedOn: string;
}

/** The archived item the item with `id` was added in place of, or `null` when there is none. */
export function useReplacedItem(id: number) {
  return useQuery({
    queryKey: [...ITEMS_KEY, id, "replaced"],
    queryFn: async () => (await api.get<{ replaced: ReplacedItem | null }>(`/api/items/${id}/replaced`)).replaced,
  });
}

/**
 * Makes the item with `id` and the archived one it was added in place of one item again, as if its type had been
 * changed in place. `onMerged` gets the id of the item that is left (the older one, which keeps its past) before the
 * items are fetched again: the refetch drops the item with `id`, and with it the page that asked, so a callback passed
 * to `mutate` would never run.
 */
export function useMergeReplaced(id: number, onMerged: (left: number) => void) {
  const refresh = useRefreshItems();
  return useMutation({
    mutationFn: async () => (await api.post<{ id: number }>(`/api/items/${id}/merge-replaced`, {})).id,
    onSuccess: (left) => {
      onMerged(left);
      return refresh();
    },
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
    archivedOn: item.archivedOn,
    tagIds: item.tagIds,
    amountCents: item.amountCents,
    amountMode: item.amountMode,
    estimate: item.estimate,
    // Left out, the backend reads these as off, so a quick change would quietly turn them off.
    billedLater: item.billedLater ?? false,
    paidAhead: item.paidAhead ?? false,
    packSessions: item.packSessions ?? null,
    packSchedule: item.packSchedule ?? null,
    usesHundredths: item.usesHundredths ?? null,
    usesPer: item.usesPer ?? null,
    countsFromPaid: item.countsFromPaid ?? false,
    fromLastYear: item.fromLastYear ?? false,
    categoryId: item.categoryId,
    payFromAccountId: item.payFromAccountId,
    payToAccountId: item.payToAccountId,
    autopay: item.autopay,
    placement: item.placement,
    schedule: item.schedule,
    daysAfterPayday: item.daysAfterPayday,
    bucketId: item.bucketId,
    saveUpBucketId: item.saveUpBucketId,
    fromBucketId: item.fromBucketId,
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

/** Removes one amount from an item's history; the one before it carries on through its dates. */
export function useRemoveVersion(itemId: number) {
  const refresh = useRefreshItems();
  return useMutation({
    mutationFn: (versionId: number) => api.delete<Item>(`/api/items/${itemId}/versions/${versionId}`),
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
