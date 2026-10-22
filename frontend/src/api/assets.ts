import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import { PAY_KEY } from "./income";

export const ASSET_KINDS = ["home", "vehicle", "other"] as const;
export type AssetKind = (typeof ASSET_KINDS)[number];

/** A value entered for an asset on one day. Amounts are whole cents. */
export interface AssetValue {
  id: number;
  date: string;
  valueCents: number;
}

/** Something owned that is not an account: a home, a vehicle, anything else of value. */
export interface Asset {
  id: number;
  name: string;
  kind: AssetKind;
  color: string;
  /** The yearly rate its value is assumed to change by, in basis points: above zero it gains, below zero it loses; `null` to hold its value. */
  appreciationBp: number | null;
  /** The loan against it, for equity; `null` when it is owned outright. */
  linkedLoanAccountId: number | null;
  archived: boolean;
  /** The day it was archived as of: it counts before it and is put away from it on; `null` when it is not archived. */
  archivedOn: string | null;
  /** The newest value entered; `null` until there is one. */
  latestValue: AssetValue | null;
  /** What it is taken to be worth today; `null` until a value is entered. */
  valueCents: number | null;
  /** True when today's value was worked out from the latest entry by the assumed rate, up or down. */
  estimate: boolean;
  /** What is still owed on the loan against it; `null` without a loan. */
  owedCents: number | null;
  /** The value less what is owed. */
  equityCents: number | null;
}

/** One asset with every value entered for it, newest first. */
export interface AssetDetail {
  asset: Asset;
  values: AssetValue[];
}

export interface AssetInput {
  name: string;
  kind: AssetKind;
  color: string;
  appreciationBp: number | null;
  linkedLoanAccountId: number | null;
  archived: boolean;
  /** With `archived`: the day it is archived as of, today or earlier. Left out, the day it had, or today. */
  archivedOn?: string | null;
  /** Only read when adding: the asset's first value, and the day it is from. */
  valueCents?: number | null;
  valueDate?: string | null;
}

/** Net worth today and what it is made of. Every part is a positive amount. */
export interface NetWorth {
  date: string;
  netWorthCents: number;
  assetsCents: number;
  liabilitiesCents: number;
  cashCents: number;
  investmentsCents: number;
  propertyCents: number;
  cardsCents: number;
  loansCents: number;
}

// Under PAY_KEY, so anything that moves money or a balance refreshes them: an asset's equity
// follows its loan, and net worth follows every account.
export const ASSETS_KEY = [...PAY_KEY, "assets"] as const;
const detailKey = (id: number) => [...ASSETS_KEY, id] as const;

export function useAssets() {
  return useQuery({ queryKey: ASSETS_KEY, queryFn: () => api.get<Asset[]>("/api/assets") });
}

export function useAsset(id: number) {
  return useQuery({ queryKey: detailKey(id), queryFn: () => api.get<AssetDetail>(`/api/assets/${id}`) });
}

export function useNetWorth() {
  return useQuery({ queryKey: [...PAY_KEY, "net-worth"], queryFn: () => api.get<NetWorth>("/api/net-worth") });
}

function useRefresh() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: PAY_KEY });
}

/** Adds an asset, or changes the one with `id`. */
export function useSaveAsset(id?: number) {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: (input: AssetInput) =>
      id === undefined ? api.post<AssetDetail>("/api/assets", input) : api.put<AssetDetail>(`/api/assets/${id}`, input),
    onSuccess: refresh,
  });
}

export function useDeleteAsset(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.delete<{ deleted: boolean }>(`/api/assets/${id}`),
    onSuccess: () => {
      // Drop the deleted asset's own view rather than refetching it into a 404.
      queryClient.removeQueries({ queryKey: detailKey(id) });
      return queryClient.invalidateQueries({ queryKey: PAY_KEY });
    },
  });
}

/** Records what the asset was worth on a day. The same day again replaces it. */
export function useSaveAssetValue(assetId: number) {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: (input: { date: string; valueCents: number }) => api.post<AssetDetail>(`/api/assets/${assetId}/values`, input),
    onSuccess: refresh,
  });
}

export function useDeleteAssetValue(assetId: number) {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: (valueId: number) => api.delete<AssetDetail>(`/api/assets/${assetId}/values/${valueId}`),
    onSuccess: refresh,
  });
}
