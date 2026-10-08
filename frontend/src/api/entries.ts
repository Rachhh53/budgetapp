import { useMutation, useQueryClient } from "@tanstack/react-query";
import { CATEGORIES_KEY, TAGS_KEY } from "./categories";
import { api } from "./client";
import { PAY_KEY } from "./income";

export const ENTRY_KINDS = ["money_out", "money_in", "transfer"] as const;
export type EntryKind = (typeof ENTRY_KINDS)[number];

/** What can be set on a one-off entry. Amounts are whole cents, never negative. */
export interface EntryInput {
  kind: EntryKind;
  date: string;
  amountCents: number;
  estimate: boolean;
  description: string;
  payee: string | null;
  categoryId: number | null;
  /** The account it leaves (money out, transfer) or arrives in (money in). */
  accountId: number;
  /** Where a transfer goes; `null` otherwise. */
  toAccountId: number | null;
  note: string | null;
  tagIds: number[];
}

/** One-off money in, money out or a transfer. */
export interface Entry extends EntryInput {
  id: number;
}

/** An entry changes what its pay period adds up to. */
function useRefreshEntries() {
  const queryClient = useQueryClient();
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: PAY_KEY }),
      queryClient.invalidateQueries({ queryKey: CATEGORIES_KEY }),
      queryClient.invalidateQueries({ queryKey: TAGS_KEY }),
    ]);
}

/** Adds an entry, or changes the one with `id`. */
export function useSaveEntry(id?: number) {
  const refresh = useRefreshEntries();
  return useMutation({
    mutationFn: (input: EntryInput) =>
      id === undefined ? api.post<Entry>("/api/entries", input) : api.put<Entry>(`/api/entries/${id}`, input),
    onSuccess: refresh,
  });
}

/** Changes whichever entry it is handed: for editing a row in place. */
export function useEditEntry() {
  const refresh = useRefreshEntries();
  return useMutation({
    mutationFn: ({ id, ...input }: Entry) => api.put<Entry>(`/api/entries/${id}`, input),
    onSuccess: refresh,
  });
}

export function useDeleteEntry(id: number) {
  const refresh = useRefreshEntries();
  return useMutation({
    mutationFn: () => api.delete<{ deleted: boolean }>(`/api/entries/${id}`),
    onSuccess: refresh,
  });
}
