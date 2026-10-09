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
  /** The day the goal is to be reached by; `null` when there is none. */
  goalDate: string | null;
  /** How it stands against its goal; `null` unless it has both a goal and a date for it. */
  goalProgress: GoalProgress | null;
}

/** Whether what is already scheduled reaches a bucket's goal by its date, and what it would take if not. */
export interface GoalProgress {
  /** What the bucket is projected to hold at the end of the goal date. */
  projectedCents: number;
  /** How much of the goal is still missing then; zero when it is on track. */
  shortCents: number;
  paydaysLeft: number;
  /** What to add every paycheck to close the gap; `null` when nothing is missing or no payday is left. */
  morePerPaycheckCents: number | null;
  /** The same, set aside monthly; `null` when nothing is missing or the date has passed. */
  morePerMonthCents: number | null;
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
  /** The day to reach the goal by; `null` for none. */
  goalDate: string | null;
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

/** A goal as it stands in a form, before it is saved. */
export interface GoalInMind {
  goalCents: number;
  goalDate: string;
  /** Count what is already scheduled into this bucket; leave out for one that does not exist yet. */
  bucketId?: number;
  /** What the bucket holds, or will start with. */
  heldCents: number;
}

/**
 * What a goal would take, worked out while it is still being typed, so the form can say how
 * much to set aside. Asks nothing until there is both a goal and a date.
 */
export function useGoalPace(goal: GoalInMind | undefined) {
  return useQuery({
    queryKey: [...BUCKETS_KEY, "goal-pace", goal],
    queryFn: () => {
      const query = new URLSearchParams({
        goalCents: String(goal!.goalCents),
        goalDate: goal!.goalDate,
        heldCents: String(goal!.heldCents),
        ...(goal!.bucketId === undefined ? {} : { bucketId: String(goal!.bucketId) }),
      });
      return api.get<GoalProgress>(`/api/goal-pace?${query}`);
    },
    enabled: goal !== undefined,
  });
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

/** As {@link useMoveBucketMoney}, for a bucket chosen at the moment of moving. */
export function useMoveMoneyInto() {
  const refresh = useRefreshBuckets();
  return useMutation({
    mutationFn: ({ bucketId, ...input }: BucketChangeInput & { bucketId: number }) =>
      api.post<BucketDetail>(`/api/buckets/${bucketId}/changes`, input),
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
