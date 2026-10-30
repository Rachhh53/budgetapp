import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ACCOUNTS_KEY } from "./accounts";
import { BUCKETS_KEY, type MoveOut } from "./buckets";
import { api } from "./client";
import type { Entry } from "./entries";
import type { Unbilled } from "./invoices";
import type { RunningOut } from "./paidAhead";
import { PAY_KEY, type PayPeriods, type Paycheck, type PromoEnd } from "./income";
import { ITEMS_KEY } from "./items";
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
  /** The bucket a bucket move, or a transfer, puts this money in. */
  bucketId: number | null;
  /** The bucket this bill is saved up for in. */
  saveUpBucketId: number | null;
  /** The bucket of the paying account it comes out of; `null` for money not in a bucket. */
  fromBucketId: number | null;
  /**
   * An invoice for a billed-later item's visits, standing in for them as one bill to pay; `null` (or
   * left out) for anything the item's schedule names. It is changed through its own address.
   */
  invoiceId?: number | null;
  /** An invoice: the last visit it pays for. A planned pack: the last session it would cover. */
  coversThrough?: string | null;
  /**
   * The next pack of something paid ahead as a pack, planned by Budget on the first session nothing paid
   * covers. It is paid by recording the payment, never edited or ticked (spec §4.6).
   */
  plannedPack?: boolean;
  /** The check this one was paid with; `null` when none was written. */
  checkNumber: string | null;
}

/**
 * A balance entered during a pay period, shown in it as an adjustment to what Budget expected the account to hold
 * then. Editing it changes that balance; deleting it removes it.
 */
export interface BalanceAdjustment {
  entryId: number;
  date: string;
  /** The balance entered. */
  balanceCents: number;
  /** That less what the account was expected to hold as the day ended (or for an older balance, began), signed. */
  differenceCents: number;
  note: string | null;
  /** The balance entered before it, when the records last matched; `null` for none. */
  since: string | null;
  /** True when it is what its day ended with. */
  endOfDay: boolean;
  /** True when the balance entered before it is what its day ended with. */
  sinceEndOfDay: boolean;
}

/**
 * One account across one pay period: `ending = starting + in − out + adjusted`, and it starts with what the period
 * before ended on. For a credit card or loan the balance is what is owed, so `in` is what was charged and `out` what
 * was paid off.
 */
export interface AccountPeriod {
  accountId: number;
  startingCents: number;
  /** The period's first day, or the day of the first balance ever entered when it falls during the period. */
  startingOn: string;
  /** True when the starting balance is the first one ever entered. */
  entered: boolean;
  inCents: number;
  outCents: number;
  /** What balances entered during the period moved it, signed: the sum of `adjustments`. */
  adjustedCents: number;
  adjustments: BalanceAdjustment[];
  endingCents: number;
  /** The lowest end-of-day balance in the period, and the day it happens. */
  lowestCents: number;
  lowestOn: string;
  /** What the account's buckets hold at the end of the period. */
  bucketsCents: number;
  /** The ending balance less what is in buckets. */
  availableCents: number;
  /** What the buckets hold as the period starts: what they held at the end of the period before. */
  startingBucketsCents: number;
  /** The starting balance less `startingBucketsCents`: what the period starts with to spend. */
  startingAvailableCents: number;
  /** True when no balance was ever entered for the account, so it is added up from nothing. */
  builtFromZero: boolean;
  /** True when the user asked for a warning and the balance dips below it. */
  lowBalance: boolean;
}

/** One bucket across one pay period; `endingCents − startingCents` is what the period moved. */
export interface BucketPeriod {
  bucketId: number;
  accountId: number;
  startingCents: number;
  endingCents: number;
}

/** Why a bucket's balance moved. */
export type BucketMoveKind = "manual" | "bucket_move" | "transfer" | "split" | "spent" | "save_up" | "save_up_paid" | "interest";

/** Money going into (+) or out of (−) a bucket on a day. */
export interface BucketMove {
  bucketId: number;
  date: string;
  amountCents: number;
  kind: BucketMoveKind;
  /** The item's name, the entry's description, or the user's note. */
  label: string | null;
  itemId: number | null;
}

/** A month's estimated interest arriving in a checking or savings account, or a month's assumed growth in an investment. */
export interface InterestLine {
  accountId: number;
  date: string;
  amountCents: number;
  /** The APY it was worked out at, in basis points. */
  rateBp: number;
  /** False once the amount was typed over in the account's transactions. */
  estimate: boolean;
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
  /** Estimated interest counted in this period's figures. */
  interest: InterestLine[];
  /** Every account a balance is known for. */
  accounts: AccountPeriod[];
  /** Every open bucket. */
  buckets: BucketPeriod[];
  /** What moved those buckets during the period, earliest first. */
  bucketMoves: BucketMove[];
  /** Promotional rates that end in this period with a balance still on them. */
  promosEnding?: PromoEnd[];
  /** Which statement each card payment listed here was sized from, for those that pay a statement's balance or its minimum. */
  statementsPaid?: StatementPaid[];
  /** The current period only: visits of billed-later items that no invoice covers yet, up to today. */
  unbilled?: Unbilled[];
  /** Paid-ahead items whose money runs out in this period; on the current period, also any that already ran out. */
  paidAheadRunningOut?: RunningOut[];
  /** Money moved out of a bucket to money not in a bucket, dated in this period: listed under Income. */
  movesOut?: MoveOut[];
  /**
   * Money planned to come back in this period, for a flagged payment, a flagged pack still to buy or a one-off expense;
   * in the current one, also any due back before it that has not all come yet. Listed under Income (Rachel, 2026-10-24).
   */
  moneyBack?: MoneyBack[];
}

/** Money planned to come back. `source` names what it is for (see `TransactionSource`). */
export interface MoneyBack {
  /** What was paid, to name the line. */
  name: string;
  expectedOn: string;
  intoAccountId: number;
  /** What is still to come back. */
  owedCents: number;
  /** The account it comes out of, like an FSA; `null` when it comes from outside. */
  fromAccountId: number | null;
  source: TransactionSource;
}

/** The statement a card payment that follows its card was sized from. */
export interface StatementPaid {
  itemId: number;
  /** With `itemId`, the occurrence this is about. */
  scheduledDate: string;
  /** The day that statement closed, or will close. */
  closeDate: string;
  /** True when the statement is one the user entered; false for one the app projected. */
  entered: boolean;
}

/** What a "just this one" edit can change. Only what is sent changes. */
export interface OccurrenceEdit {
  amountCents?: number;
  /** Moves this one to another day; the item's schedule is untouched. */
  dueDate?: string;
  /** An empty note clears it. */
  note?: string;
  paid?: boolean;
  paidDate?: string;
  /** An empty code clears it. */
  confirmationCode?: string;
  /** An empty number clears it. Only for something paid from a checking account. */
  checkNumber?: string;
  /** Paid from another account just this once; the item's own account puts it back. */
  payFromAccountId?: number;
  /** Doesn't happen this time (left out of the period and its figures); false puts it back. */
  skipped?: boolean;
}

/** What one account holds (or owes) at the end of today, and what of that is outside its buckets. */
export interface AccountBalance {
  accountId: number;
  date: string;
  balanceCents: number;
  availableCents: number;
}

/**
 * What every account holds today: the last balance entered plus everything since. It moves
 * with anything that moves a pay period, so it lives under the same key.
 */
export function useAccountBalances() {
  return useQuery({
    queryKey: [...PAY_KEY, "account-balances"],
    queryFn: () => api.get<AccountBalance[]>("/api/account-balances"),
  });
}

/** One thing that moved an account's balance, or a balance entered for it. */
export interface AccountTransaction {
  date: string;
  label: string;
  /**
   * `paycheck`, `contribution`, `employer_match`, `interest`, `growth`, `balance`, what was worked out for a card
   * (`card_interest`, `deferred_interest`, `card_estimate`) or a loan (`loan_interest`, `escrow`), or the kind of the
   * recurring item or one-off entry.
   */
  kind: string;
  /** What it did to the balance, signed; for `balance`, the balance that was entered. */
  amountCents: number;
  /** Paid by hand and not ticked off yet. */
  unpaid: boolean;
  /** Interest typed over: what Budget had estimated. `null` for an estimate and for everything else. */
  estimatedCents: number | null;
  /** What it came from, to open it and change it; `null` for what Budget works out and for balances entered. */
  source: TransactionSource | null;
}

/** Where a line of an account's transactions came from. */
export interface TransactionSource {
  /**
   * `paid_back`: money planned to come back, for a one-off expense (`entryId`), a flagged recurring payment (`itemId` and
   * `scheduledDate`) or a flagged pack still to buy (`itemId` and its `dueDate` only).
   */
  type: "entry" | "occurrence" | "paycheck" | "paid_back";
  entryId: number | null;
  /** The recurring item's or the income source's id. */
  itemId: number | null;
  scheduledDate: string | null;
  /** An occurrence's due date, which its pay period row is found by. */
  dueDate: string | null;
  /** The pay period it is listed in. */
  periodStart: string | null;
}

/** The lines that are interest Budget works out, which can be typed over when the real figure is known (spec §5.4). */
/** A card's rewards at a statement close are typed over the same way (spec §5.6). */
export const TYPED_OVER_KINDS: ReadonlySet<string> = new Set(["interest", "growth", "card_interest", "loan_interest", "card_rewards"]);

/**
 * Typing over one interest line changes the account's balance from that day on, so everything
 * that follows the account (its pages, the pay periods, its buckets' share of interest) is asked again.
 */
function useRefreshAfterInterest() {
  const queryClient = useQueryClient();
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: ACCOUNTS_KEY }),
      queryClient.invalidateQueries({ queryKey: PAY_KEY }),
      queryClient.invalidateQueries({ queryKey: BUCKETS_KEY }),
    ]);
}

/** Types over one interest line of an account: the day it is added (for growth, any day of its month) and the real amount. */
export function useTypeOverInterest(accountId: number) {
  const refresh = useRefreshAfterInterest();
  return useMutation({
    mutationFn: ({ date, amountCents }: { date: string; amountCents: number }) =>
      api.put<{ done: boolean }>(`/api/accounts/${accountId}/transactions/interest/${date}`, { amountCents }),
    onSuccess: refresh,
  });
}

/** Puts Budget's estimate back on an interest line that was typed over. */
export function useUseEstimatedInterest(accountId: number) {
  const refresh = useRefreshAfterInterest();
  return useMutation({
    mutationFn: (date: string) => api.delete<{ done: boolean }>(`/api/accounts/${accountId}/transactions/interest/${date}`),
    onSuccess: refresh,
  });
}

/** What moved one account within `from`–`to`, newest first. */
export function useAccountTransactions(accountId: number, from: string | undefined, to: string | undefined) {
  return useQuery({
    queryKey: [...PAY_KEY, "transactions", accountId, from, to],
    queryFn: () => api.get<AccountTransaction[]>(`/api/accounts/${accountId}/transactions?from=${from}&to=${to}`),
    enabled: from !== undefined && to !== undefined,
    // Keep the list on screen while another stretch of dates loads.
    placeholderData: (previous) => previous,
  });
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
    mutationFn: ({ occurrence, edit }: { occurrence: Pick<Occurrence, "itemId" | "scheduledDate" | "invoiceId">; edit: OccurrenceEdit }) =>
      // An invoice is changed as an invoice; the same form and tick serve both.
      occurrence.invoiceId != null
        ? api.put<unknown>(`/api/items/${occurrence.itemId}/invoices/${occurrence.invoiceId}`, edit)
        : api.put<Occurrence>(`/api/items/${occurrence.itemId}/occurrences/${occurrence.scheduledDate}`, edit),
    // An item's page lists its coming dates too, and its next one.
    onSuccess: () => Promise.all([queryClient.invalidateQueries({ queryKey: PAY_KEY }), queryClient.invalidateQueries({ queryKey: ITEMS_KEY })]),
  });
}

/** An item's next few dates from today, skipped ones included, each to change just that once on its page. */
export function useUpcoming(itemId: number) {
  return useQuery({ queryKey: [...ITEMS_KEY, itemId, "upcoming"], queryFn: () => api.get<Occurrence[]>(`/api/items/${itemId}/upcoming`) });
}

/** Types over a card's rewards at one statement close: what really came off, never negative. */
export function useTypeOverRewards(accountId: number) {
  const refresh = useRefreshAfterInterest();
  return useMutation({
    mutationFn: ({ date, amountCents }: { date: string; amountCents: number }) =>
      api.put<{ done: boolean }>(`/api/accounts/${accountId}/transactions/rewards/${date}`, { amountCents }),
    onSuccess: refresh,
  });
}

/** Puts Budget's estimate back on a card's rewards that were typed over. */
export function useUseEstimatedRewards(accountId: number) {
  const refresh = useRefreshAfterInterest();
  return useMutation({
    mutationFn: (date: string) => api.delete<{ done: boolean }>(`/api/accounts/${accountId}/transactions/rewards/${date}`),
    onSuccess: refresh,
  });
}
