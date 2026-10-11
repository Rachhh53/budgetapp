import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import { PAY_KEY } from "./income";

export const ACCOUNT_TYPES = ["checking", "savings", "credit_card", "investment", "loan"] as const;
export type AccountType = (typeof ACCOUNT_TYPES)[number];

/** Checking and savings: the accounts that can hold buckets and earn savings interest. */
export function holdsCash(type: AccountType): boolean {
  return type === "checking" || type === "savings";
}

/** A balance the user typed in for one day. Amounts are whole cents. */
export interface BalanceEntry {
  id: number;
  date: string;
  amountCents: number;
  note: string | null;
}

export interface Account {
  id: number;
  name: string;
  institution: string | null;
  type: AccountType;
  /** The user's own label, e.g. "401(k)" or "Mortgage". */
  subtypeLabel: string | null;
  color: string;
  archived: boolean;
  /** Warn when the balance is projected to dip below this; `null` for no warning. */
  lowBalanceThresholdCents: number | null;
  /** The most recent balance entered, or `null` if none yet. */
  latestBalance: BalanceEntry | null;
}

export const INTEREST_DESTINATIONS = ["unassigned", "bucket", "proportional"] as const;
export type InterestDestination = (typeof INTEREST_DESTINATIONS)[number];

/** When an account's interest is credited and where it goes. */
export interface InterestSettings {
  /** Day of the month, 1–31; 31 means the last day. */
  creditDay: number;
  destination: InterestDestination;
  /** Set exactly when `destination` is `bucket`. */
  destinationBucketId: number | null;
}

/** A rate in basis points (4.10% is 410) that applies from a date until the next one. */
export interface Rate {
  id: number;
  kind: "apy" | "growth" | "apr" | "loan_rate";
  rateBp: number;
  effectiveDate: string;
}

/** One account with everything entered for it; balances and rates newest first. */
export interface AccountDetail {
  account: Account;
  balances: BalanceEntry[];
  interest: InterestSettings;
  rates: Rate[];
}

export interface AccountInput {
  name: string;
  institution: string | null;
  type: AccountType;
  subtypeLabel: string | null;
  color: string;
  archived: boolean;
  lowBalanceThresholdCents: number | null;
}

export interface BalanceInput {
  date: string;
  amountCents: number;
  note: string | null;
}

export interface RateInput {
  rateBp: number;
  effectiveDate: string;
}

/** One account at the end of one pay period: a point on its chart. */
export interface AccountPoint {
  start: string;
  end: string;
  endingCents: number;
  lowestCents: number;
  /** The ending balance less what is in the account's buckets. */
  availableCents: number;
}

/** One balance entered, set against the one before it. The three figures are `null` for the first entry. */
export interface BalanceChange {
  entryId: number;
  date: string;
  amountCents: number;
  changeCents: number | null;
  /** What was put in between the two, less what was taken out. */
  depositsCents: number | null;
  /** The change that deposits do not explain: a gain when positive, a loss when negative. */
  growthCents: number | null;
}

/** How an account has done this year, from New Year or from its first entered balance if that is later. */
export interface YearSoFar {
  since: string;
  startCents: number;
  nowCents: number;
  changeCents: number;
  depositsCents: number;
  /** The rest of the change: interest, growth, and gains or losses. */
  growthCents: number;
}

export interface AccountPerformance {
  /** `null` for an account that is not added up at all. */
  thisYear: YearSoFar | null;
  /** Every balance entered, newest first. */
  balances: BalanceChange[];
}

export const ACCOUNTS_KEY = ["accounts"] as const;
const detailKey = (id: number) => [...ACCOUNTS_KEY, id] as const;

export function useAccounts() {
  return useQuery({ queryKey: ACCOUNTS_KEY, queryFn: () => api.get<Account[]>("/api/accounts") });
}

export function useAccount(id: number) {
  return useQuery({ queryKey: detailKey(id), queryFn: () => api.get<AccountDetail>(`/api/accounts/${id}`) });
}

/** The account at the end of each pay period from the current one through the horizon; empty until it has a balance. */
export function useAccountProjection(id: number) {
  return useQuery({
    queryKey: [...detailKey(id), "projection"],
    queryFn: () => api.get<AccountPoint[]>(`/api/accounts/${id}/projection`),
  });
}

/**
 * The account at the end of each pay period between two dates, for a chart told which dates to
 * show. Dates before today give what it held then, as the app works it out; dates past the
 * horizon run the projection on. Asked for only once there are dates (`undefined` waits), and
 * the last answer stays on screen while the next loads, so the chart does not blink.
 */
export function useAccountProjectionBetween(id: number, dates: { from: string; to: string } | undefined) {
  return useQuery({
    queryKey: [...detailKey(id), "projection", dates?.from, dates?.to],
    queryFn: () => api.get<AccountPoint[]>(`/api/accounts/${id}/projection?from=${dates?.from}&to=${dates?.to}`),
    enabled: dates !== undefined,
    placeholderData: keepPreviousData,
  });
}

/** This year so far, and each entered balance against the one before. Under PAY_KEY: anything that moves money changes it. */
export function useAccountPerformance(id: number) {
  return useQuery({
    queryKey: [...PAY_KEY, "performance", id],
    queryFn: () => api.get<AccountPerformance>(`/api/accounts/${id}/performance`),
  });
}

/** "Update balances": several accounts' balances for one day, saved together so one Undo takes them all back. */
export function useSaveBalances() {
  const refresh = useRefreshAccounts();
  return useMutation({
    mutationFn: (input: { date: string; balances: { accountId: number; amountCents: number }[] }) =>
      api.put<{ saved: number }>("/api/account-balances", input),
    onSuccess: refresh,
  });
}

/**
 * What is entered for an account (a balance, a rate, a warning amount) changes its own views
 * and what every pay period adds up to. All account keys start with ACCOUNTS_KEY.
 */
function useRefreshAccounts() {
  const queryClient = useQueryClient();
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: ACCOUNTS_KEY }),
      queryClient.invalidateQueries({ queryKey: PAY_KEY }),
    ]);
}

/** Creates an account, or changes the one with `id`. */
export function useSaveAccount(id?: number) {
  const refresh = useRefreshAccounts();
  return useMutation({
    mutationFn: (input: AccountInput) =>
      id === undefined
        ? api.post<AccountDetail>("/api/accounts", input)
        : api.put<AccountDetail>(`/api/accounts/${id}`, input),
    onSuccess: refresh,
  });
}

export function useDeleteAccount(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.delete<{ deleted: boolean }>(`/api/accounts/${id}`),
    onSuccess: () => {
      // Drop the deleted account's own views rather than refetching them into a 404.
      queryClient.removeQueries({ queryKey: detailKey(id) });
      return queryClient.invalidateQueries({ queryKey: ACCOUNTS_KEY, exact: true });
    },
  });
}

export function useSaveBalance(accountId: number) {
  const refresh = useRefreshAccounts();
  return useMutation({
    mutationFn: (input: BalanceInput) => api.post<AccountDetail>(`/api/accounts/${accountId}/balances`, input),
    onSuccess: refresh,
  });
}

export function useDeleteBalance(accountId: number) {
  const refresh = useRefreshAccounts();
  return useMutation({
    mutationFn: (entryId: number) => api.delete<AccountDetail>(`/api/accounts/${accountId}/balances/${entryId}`),
    onSuccess: refresh,
  });
}

export function useSaveInterest(accountId: number) {
  const refresh = useRefreshAccounts();
  return useMutation({
    mutationFn: (input: InterestSettings) => api.put<AccountDetail>(`/api/accounts/${accountId}/interest`, input),
    onSuccess: refresh,
  });
}

/** Records a rate from a date on. The same date again replaces it. */
export function useSaveRate(accountId: number) {
  const refresh = useRefreshAccounts();
  return useMutation({
    mutationFn: (input: RateInput) => api.post<AccountDetail>(`/api/accounts/${accountId}/rates`, input),
    onSuccess: refresh,
  });
}

/** As {@link useSaveRate}, for an account that did not exist when the form opened: one just added. */
export function useSaveRateFor() {
  const refresh = useRefreshAccounts();
  return useMutation({
    mutationFn: ({ accountId, ...input }: RateInput & { accountId: number }) =>
      api.post<AccountDetail>(`/api/accounts/${accountId}/rates`, input),
    onSuccess: refresh,
  });
}

export function useDeleteRate(accountId: number) {
  const refresh = useRefreshAccounts();
  return useMutation({
    mutationFn: (rateId: number) => api.delete<AccountDetail>(`/api/accounts/${accountId}/rates/${rateId}`),
    onSuccess: refresh,
  });
}
