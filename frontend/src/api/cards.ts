import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type Account, ACCOUNTS_KEY, type Rate } from "./accounts";
import { api } from "./client";
import { PAY_KEY } from "./income";
import { CARD_PAYMENT_MODES, type CardPaymentMode, ITEMS_KEY } from "./items";

/** How a card's own payment is sized. */
export const PAYMENT_MODES = CARD_PAYMENT_MODES;
export type PaymentMode = CardPaymentMode;

/**
 * How a card bills and is paid. Days are days of the month, 1–31, where 31 means the last day.
 * The dates and the estimate are the card's own; how it is paid is a copy of its recurring payment,
 * which is where that is set.
 */
export interface CardSettings {
  accountId: number;
  statementCloseDay: number;
  dueDay: number;
  paymentMode: PaymentMode;
  /** That payment's amount when `paymentMode` is `fixed`. */
  fixedPaymentCents: number | null;
  autopay: boolean;
  /** The account the payment comes out of; `null` while the card has no recurring payment. */
  payFromAccountId: number | null;
  /** The recurring item that is this card's own payment; `null` when there is none. */
  paymentItemId: number | null;
  /** For a card whose charges are not entered one by one: what a statement month's spending comes to. */
  monthlyEstimateCents: number | null;
}

export interface CardSettingsInput {
  statementCloseDay: number;
  dueDay: number;
  monthlyEstimateCents: number | null;
}

export const PROMO_KINDS = ["balance_transfer", "purchase", "other"] as const;
export type PromoKind = (typeof PROMO_KINDS)[number];

/** `soon_deferred` is the stronger one: interest for the whole promotion would be charged at once. */
export type PromoWarning = "none" | "soon" | "soon_deferred" | "ended";

/** A promotional rate on part of a card's balance, with where it stands today. Rates are basis points. */
export interface Promo {
  id: number;
  label: string;
  kind: PromoKind;
  rateBp: number;
  startDate: string;
  /** The last day the promotional rate applies. */
  endDate: string;
  /** The amount the rate is on; `null` when it is on everything the card owes, new charges included. */
  originalBalanceCents: number | null;
  /** The rate on what is left afterwards; `null` for the card's regular APR. */
  rateAfterBp: number | null;
  deferredInterest: boolean;
  /** What is left on the rate; for one on everything, all that is owed. */
  remainingCents: number;
  /** How much of the amount it started with is gone, 0–100; `null` for one on everything. */
  paidPercent: number | null;
  /** Days until its last day; negative once it has ended. */
  daysLeft: number;
  warning: PromoWarning;
  /** The day warnings start. */
  warnFrom: string;
  /** Paydays still to come before it ends. */
  paydaysLeft: number;
  /** What clearing it takes from each of those; `null` with no payday left. */
  perPaycheckCents: number | null;
  /** The recurring payment the payoff helper added; `null` when none. */
  payoffItemId: number | null;
  /** The rate on what is left once it ends. */
  rateAfterEffectiveBp: number;
  /** What today's remaining balance would cost a month at that rate. */
  monthlyInterestIfLeftCents: number;
  /** What would be charged at once if it ended with today's balance; zero unless deferred. */
  deferredInterestCents: number;
}

export interface PromoInput {
  label: string;
  kind: PromoKind;
  rateBp: number;
  startDate: string;
  endDate: string;
  /** The amount the rate is on; `null` for everything the card owes. */
  originalBalanceCents: number | null;
  rateAfterBp: number | null;
  deferredInterest: boolean;
}

export type StatementStatus = "planned" | "paid" | "part_paid" | "due";

export interface CardStatement {
  /** `null` for the next statement, which is only projected. */
  id: number | null;
  closeDate: string;
  balanceCents: number;
  minimumDueCents: number | null;
  dueDate: string;
  /** True when these are the numbers the user entered. */
  entered: boolean;
  /** What has been paid toward it since it closed. */
  paidCents: number;
  status: StatementStatus;
}

export interface StatementInput {
  closeDate: string;
  balanceCents: number;
  minimumDueCents: number | null;
  dueDate: string | null;
}

/** The next payment into a card that is still to be made. */
export interface NextPayment {
  itemId: number;
  name: string;
  scheduledDate: string;
  date: string;
  amountCents: number;
  estimate: boolean;
  autopay: boolean;
  payFromAccountId: number;
}

/** A card on the Credit cards screen. */
export interface Card {
  account: Account;
  /** What is owed today; `null` for an archived card nothing is known about. */
  balanceCents: number | null;
  /** `null` until the card's statement and due days are entered. */
  settings: CardSettings | null;
  /** The regular APR in force today, in basis points; `null` until one is entered. */
  aprBp: number | null;
  nextPayment: NextPayment | null;
  /** The promotion that ends soonest with a balance still on it. */
  promo: Promo | null;
}

/** One card with everything about it. */
export interface CardDetail {
  card: Card;
  promos: Promo[];
  /** The next statement (projected) first, then the ones entered, newest first. */
  statements: CardStatement[];
  /** The regular APR over time, newest first. */
  rates: Rate[];
  /** When the statement being built closes, and falls due; `null` without settings. */
  nextCloseDate: string | null;
  nextDueDate: string | null;
  /** How its minimum payment is estimated until a statement with the real one is entered. */
  minimum: MinimumRule;
  /** Cash back on what is charged, in basis points (1.5% is 150); `null` for none. */
  rewardsBp: number | null;
  /** How its rewards are used; `null` for none. */
  rewards: CardRewards | null;
}

/** A card's rewards: off each statement, or left to build up and used by a rule or a redemption. */
export interface CardRewards {
  rateBp: number;
  /** False when they come off each statement close on their own. */
  buildUp: boolean;
  /** Built up: every year, all of it goes on at the statement closing in this month (1–12). */
  yearlyMonth: number | null;
  /** Built up: all of it goes on once at least this has built up. */
  thresholdCents: number | null;
  /** Built up: what the card said had built up on `startDate`. */
  startCents: number | null;
  startDate: string | null;
  /** Built up: what has built up as of today. */
  builtUpCents: number;
  /** Built up: the next statement credit in the year ahead, by a rule or a redemption. */
  nextCreditDate: string | null;
  nextCreditCents: number | null;
  redemptions: Redemption[];
}

/** A statement credit asked for from built-up rewards. */
export interface Redemption {
  id: number;
  date: string;
  /** `null` for everything built up by then. */
  amountCents: number | null;
  note: string | null;
  /** What it comes to: never more than had built up by then. */
  creditedCents: number;
}

/** What is sent to set a card's rewards. */
export interface RewardsInput {
  /** `null` takes its rewards away. */
  rateBp: number | null;
  buildUp: boolean;
  yearlyMonth: number | null;
  thresholdCents: number | null;
  startCents: number | null;
  startDate: string | null;
}

export interface RedemptionInput {
  date: string;
  amountCents: number | null;
  note: string | null;
}

/** How a card's minimum payment is estimated: a share of the statement balance, never less than a floor. */
export interface MinimumRule {
  /** The share of the statement balance, in basis points (1% is 100). */
  shareBp: number;
  /** The least it comes to, when the balance is at least that much. */
  floorCents: number;
  /** True when that month's interest is added to the share. */
  plusInterest: boolean;
}

// Under PAY_KEY: a card's balance, next payment and promotions follow everything that moves money.
export const CARDS_KEY = [...PAY_KEY, "cards"] as const;
const detailKey = (id: number) => [...CARDS_KEY, id] as const;

export function useCards() {
  return useQuery({ queryKey: CARDS_KEY, queryFn: () => api.get<Card[]>("/api/cards") });
}

export function useCard(id: number) {
  return useQuery({ queryKey: detailKey(id), queryFn: () => api.get<CardDetail>(`/api/cards/${id}`) });
}

/**
 * What is set on a card changes its own views, every pay period, and (through the payment it
 * keeps in step, or the payoff it adds) the recurring items.
 */
function useRefresh() {
  const queryClient = useQueryClient();
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: PAY_KEY }),
      queryClient.invalidateQueries({ queryKey: ACCOUNTS_KEY }),
      queryClient.invalidateQueries({ queryKey: ITEMS_KEY }),
    ]);
}

export function useSaveCardSettings(cardId: number) {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: (input: CardSettingsInput) => api.put<CardDetail>(`/api/cards/${cardId}/settings`, input),
    onSuccess: refresh,
  });
}

/** Changes how the card's minimum payment is estimated. */
export function useSaveMinimumRule(cardId: number) {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: (input: MinimumRule) => api.put<CardDetail>(`/api/cards/${cardId}/minimum`, input),
    onSuccess: refresh,
  });
}

/** Records a statement as it arrived. The same closing date again replaces it. */
export function useSaveStatement(cardId: number) {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: (input: StatementInput) => api.post<CardDetail>(`/api/cards/${cardId}/statements`, input),
    onSuccess: refresh,
  });
}

export function useDeleteStatement(cardId: number) {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: (statementId: number) => api.delete<CardDetail>(`/api/cards/${cardId}/statements/${statementId}`),
    onSuccess: refresh,
  });
}

/** Adds a promotion, or changes the one with `promoId`. */
export function useSavePromo(cardId: number, promoId?: number) {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: (input: PromoInput) =>
      promoId === undefined
        ? api.post<CardDetail>(`/api/cards/${cardId}/promos`, input)
        : api.put<CardDetail>(`/api/cards/${cardId}/promos/${promoId}`, input),
    onSuccess: refresh,
  });
}

export function useDeletePromo(cardId: number) {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: (promoId: number) => api.delete<CardDetail>(`/api/cards/${cardId}/promos/${promoId}`),
    onSuccess: refresh,
  });
}

/** The payoff helper's one click: a payment every paycheck until the promotion ends. */
export function useAddPayoff(cardId: number) {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: (promoId: number) => api.post<CardDetail>(`/api/cards/${cardId}/promos/${promoId}/payoff`, {}),
    onSuccess: refresh,
  });
}

/** Sets a card's cash back and how it's used, or with a `null` rate takes its rewards away (spec §5.6). */
export function useSaveRewards(cardId: number) {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: (input: RewardsInput) => api.put<CardDetail>(`/api/cards/${cardId}/rewards`, input),
    onSuccess: refresh,
  });
}

/** Adds a statement credit from built-up rewards, or with `redemptionId` changes one. */
export function useSaveRedemption(cardId: number, redemptionId?: number) {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: (input: RedemptionInput) =>
      redemptionId === undefined
        ? api.post<unknown>(`/api/cards/${cardId}/rewards/redemptions`, input)
        : api.put<unknown>(`/api/cards/${cardId}/rewards/redemptions/${redemptionId}`, input),
    onSuccess: refresh,
  });
}

export function useDeleteRedemption(cardId: number, redemptionId: number) {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: () => api.delete<CardDetail>(`/api/cards/${cardId}/rewards/redemptions/${redemptionId}`),
    onSuccess: refresh,
  });
}
