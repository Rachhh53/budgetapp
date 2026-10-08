import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ACCOUNTS_KEY } from "./accounts";
import { api } from "./client";
import { PAY_KEY } from "./income";

/**
 * Money set aside inside a checking or savings account. A bucket never changes the account's
 * balance; it lowers what is "available". Amounts are whole cents.
 */
export interface Bucket {
  id: number;
  accountId: number;
  name: string;
  color: string;
  /** `null` when the bucket has no goal. */
  goalCents: number | null;
  note: string | null;
  archived: boolean;
  /** The day it counts from: recurring moves, save-ups and interest reach it from then on. */
  openedOn: string;
  /** What it holds today; zero once archived. */
  balanceCents: number;
}

/** Money the user put into (+) or took out of (−) a bucket by hand. */
export interface BucketChange {
  id: number;
  bucketId: number;
  date: string;
  amountCents: number;
  note: string | null;
}

/** One bucket with what was moved in and out of it by hand, newest first. */
export interface BucketDetail {
  bucket: Bucket;
  changes: BucketChange[];
}

export interface BucketInput {
  /** The account it sits in. Chosen when adding; it cannot be changed afterwards. */
  accountId: number;
  name: string;
  color: string;
  goalCents: number | null;
  note: string | null;
  archived: boolean;
  /** When adding: what is already set aside, if anything. */
  startingCents?: number;
  /** The day it counts from: today or earlier. */
  openedOn: string;
}

export interface BucketChangeInput {
  date: string;
  /** Positive puts money in; negative takes it out. */
  amountCents: number;
  note: string | null;
}

export const BUCKETS_KEY = ["buckets"] as const;

export function useBuckets() {
  return useQuery({ queryKey: BUCKETS_KEY, queryFn: () => api.get<Bucket[]>("/api/buckets") });
}

export function useBucket(id: number) {
  return useQuery({ queryKey: [...BUCKETS_KEY, id], queryFn: () => api.get<BucketDetail>(`/api/buckets/${id}`) });
}

/** A bucket changes what is available in every pay period and on each account's chart. */
function useRefreshBuckets() {
  const queryClient = useQueryClient();
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: BUCKETS_KEY }),
      queryClient.invalidateQueries({ queryKey: PAY_KEY }),
      queryClient.invalidateQueries({ queryKey: ACCOUNTS_KEY }),
    ]);
}

/** Adds a bucket, or changes the one with `id`. */
export function useSaveBucket(id?: number) {
  const refresh = useRefreshBuckets();
  return useMutation({
    mutationFn: (input: BucketInput) =>
      id === undefined ? api.post<BucketDetail>("/api/buckets", input) : api.put<BucketDetail>(`/api/buckets/${id}`, input),
    onSuccess: refresh,
  });
}

export function useDeleteBucket(id: number) {
  const refresh = useRefreshBuckets();
  return useMutation({
    mutationFn: () => api.delete<{ deleted: boolean }>(`/api/buckets/${id}`),
    onSuccess: refresh,
  });
}

/** Puts money into, or takes it out of, a bucket by hand. */
export function useMoveBucketMoney(id: number) {
  const refresh = useRefreshBuckets();
  return useMutation({
    mutationFn: (input: BucketChangeInput) => api.post<BucketDetail>(`/api/buckets/${id}/changes`, input),
    onSuccess: refresh,
  });
}

export function useDeleteBucketChange(id: number) {
  const refresh = useRefreshBuckets();
  return useMutation({
    mutationFn: (changeId: number) => api.delete<BucketDetail>(`/api/buckets/${id}/changes/${changeId}`),
    onSuccess: refresh,
  });
}
