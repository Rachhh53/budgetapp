import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ACCOUNTS_KEY } from "./accounts";
import { BUCKETS_KEY } from "./buckets";
import { CATEGORIES_KEY, TAGS_KEY } from "./categories";
import { api } from "./client";
import { PAY_KEY } from "./income";

export const ENTRY_KINDS = ["money_out", "money_in", "transfer"] as const;
export type EntryKind = (typeof ENTRY_KINDS)[number];

/** Where an expense stands on being paid back, in the order it moves through them. */
export const REIMBURSEMENT_STATUSES = ["none", "to_submit", "submitted", "reimbursed"] as const;
export type ReimbursementStatus = (typeof REIMBURSEMENT_STATUSES)[number];

export const REIMBURSEMENT_LABELS: Record<ReimbursementStatus, string> = {
  none: "None",
  to_submit: "To submit",
  submitted: "Submitted",
  reimbursed: "Reimbursed",
};

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
  /** The check it was paid with, when it left a checking account that way; `null` otherwise. */
  checkNumber: string | null;
  tagIds: number[];
  /**
   * The buckets this touches. A bucket in the account the money leaves is where it comes out
   * of; a bucket in the account it arrives in is where part of it is set aside.
   */
  bucketSplits: BucketSplit[];
  /** Money going out: where it stands on being paid back. Left out, it stays as it was (none, when new). */
  reimbursementStatus?: ReimbursementStatus;
  /** Money coming in: the expense it pays back; `null` when it pays none back. */
  reimbursesEntryId: number | null;
}

/** Part of an entry set aside in, or paid out of, one bucket. */
export interface BucketSplit {
  bucketId: number;
  amountCents: number;
}

/** Where an expense stands on being paid back, with the figures that follow from the money that has come back. */
export interface Reimbursement {
  status: ReimbursementStatus;
  /** The day it was sent in; `null` when not said. */
  submittedOn: string | null;
  /** Who pays it back ("HSA", "Work"); `null` when not said. */
  to: string | null;
  /** What the money-in entries that pay it back add up to. */
  paidBackCents: number;
  /** How much of the expense counts as having come back. */
  reimbursedCents: number;
  /** How much is still to come, sent in or not. */
  owedCents: number;
}

/** One-off money in, money out or a transfer. */
export interface Entry extends Omit<EntryInput, "reimbursementStatus"> {
  id: number;
  /** Money going out only; `null` for money in and transfers. */
  reimbursement: Reimbursement | null;
  /** How many receipts and documents are attached. */
  fileCount: number;
}

/** One entry with what it is linked to. */
export interface EntryDetail {
  entry: Entry;
  /** An expense: the money that came in to pay it back, oldest first. */
  paybacks: Entry[];
  /** Money coming in: the expense it pays back; `null` when it pays none back. */
  reimburses: Entry | null;
}

/** Names typed before, for the pickers that offer them again. */
export interface Names {
  payees: string[];
  reimbursers: string[];
}

/**
 * One-off entries, and everything worked out from them (what is owed back, the years there
 * are, the names typed). Under the pay key, so whatever changes the pay periods refreshes these too.
 */
export const ENTRIES_KEY = [...PAY_KEY, "entries"] as const;

/** What the backend takes back for an entry: only what can be set, never what it worked out. */
export function toEntryInput(entry: Entry): EntryInput {
  return {
    kind: entry.kind,
    date: entry.date,
    amountCents: entry.amountCents,
    estimate: entry.estimate,
    description: entry.description,
    payee: entry.payee,
    categoryId: entry.categoryId,
    accountId: entry.accountId,
    toAccountId: entry.toAccountId,
    note: entry.note,
    checkNumber: entry.checkNumber,
    tagIds: entry.tagIds,
    bucketSplits: entry.bucketSplits,
    reimbursesEntryId: entry.reimbursesEntryId,
  };
}

/** Entries dated within `from`–`to`, oldest first. */
export function useEntries(from: string, to: string, enabled = true) {
  return useQuery({
    queryKey: [...ENTRIES_KEY, "between", from, to],
    queryFn: () => api.get<Entry[]>(`/api/entries?from=${from}&to=${to}`),
    enabled,
  });
}

export function useEntry(id: number) {
  return useQuery({
    queryKey: [...ENTRIES_KEY, "one", id],
    queryFn: () => api.get<EntryDetail>(`/api/entries/${id}`),
    enabled: Number.isInteger(id),
  });
}

/** The years that have one-off spending in them, newest first. */
export function useExpenseYears() {
  return useQuery({
    queryKey: [...ENTRIES_KEY, "years"],
    queryFn: async () => (await api.get<{ years: number[] }>("/api/expense-years")).years,
  });
}

export function useNames() {
  return useQuery({ queryKey: [...ENTRIES_KEY, "names"], queryFn: () => api.get<Names>("/api/payees") });
}

/** An entry changes what its pay period adds up to, each account's chart, and what its buckets hold. */
function useRefreshEntries() {
  const queryClient = useQueryClient();
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: PAY_KEY }),
      queryClient.invalidateQueries({ queryKey: ACCOUNTS_KEY }),
      queryClient.invalidateQueries({ queryKey: BUCKETS_KEY }),
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
    mutationFn: (entry: Entry) => api.put<Entry>(`/api/entries/${entry.id}`, toEntryInput(entry)),
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

/** Says where an expense stands on being paid back. */
export function useSetReimbursement(id: number) {
  const refresh = useRefreshEntries();
  return useMutation({
    mutationFn: (input: { status: ReimbursementStatus; submittedOn: string | null; to: string | null }) =>
      api.put<Entry>(`/api/entries/${id}/reimbursement`, input),
    onSuccess: refresh,
  });
}
