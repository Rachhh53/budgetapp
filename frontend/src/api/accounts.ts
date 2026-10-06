import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";

export const ACCOUNT_TYPES = ["checking", "savings", "credit_card", "investment", "loan"] as const;
export type AccountType = (typeof ACCOUNT_TYPES)[number];

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
  /** The most recent balance entered, or `null` if none yet. */
  latestBalance: BalanceEntry | null;
}

/** One account with every balance entered for it, newest first. */
export interface AccountDetail {
  account: Account;
  balances: BalanceEntry[];
}

export interface AccountInput {
  name: string;
  institution: string | null;
  type: AccountType;
  subtypeLabel: string | null;
  color: string;
  archived: boolean;
}

export interface BalanceInput {
  date: string;
  amountCents: number;
  note: string | null;
}

export const ACCOUNTS_KEY = ["accounts"] as const;
const detailKey = (id: number) => [...ACCOUNTS_KEY, id] as const;

export function useAccounts() {
  return useQuery({ queryKey: ACCOUNTS_KEY, queryFn: () => api.get<Account[]>("/api/accounts") });
}

export function useAccount(id: number) {
  return useQuery({ queryKey: detailKey(id), queryFn: () => api.get<AccountDetail>(`/api/accounts/${id}`) });
}

/** Creates an account, or changes the one with `id`. */
export function useSaveAccount(id?: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: AccountInput) =>
      id === undefined
        ? api.post<AccountDetail>("/api/accounts", input)
        : api.put<AccountDetail>(`/api/accounts/${id}`, input),
    // Covers the list and every detail view, whose keys all start with ACCOUNTS_KEY.
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ACCOUNTS_KEY }),
  });
}

export function useDeleteAccount(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.delete<{ deleted: boolean }>(`/api/accounts/${id}`),
    onSuccess: () => {
      // Drop the deleted account's own view rather than refetching it into a 404.
      queryClient.removeQueries({ queryKey: detailKey(id) });
      return queryClient.invalidateQueries({ queryKey: ACCOUNTS_KEY, exact: true });
    },
  });
}

export function useSaveBalance(accountId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: BalanceInput) => api.post<AccountDetail>(`/api/accounts/${accountId}/balances`, input),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ACCOUNTS_KEY }),
  });
}

export function useDeleteBalance(accountId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (entryId: number) => api.delete<AccountDetail>(`/api/accounts/${accountId}/balances/${entryId}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ACCOUNTS_KEY }),
  });
}
