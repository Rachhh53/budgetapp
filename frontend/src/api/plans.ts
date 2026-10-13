import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import { PAY_KEY } from "./income";
import type { ItemKind } from "./items";

/** What a plan's suggestions are worked out from (spec §5.8). */
export const PLAN_BASES = ["last_12", "last_12_trend", "prior_year"] as const;
export type PlanBasis = (typeof PLAN_BASES)[number];

/** The steps that hold amounts, in order. Review, the sixth, only adds them up. */
export const PLAN_STEPS = ["income", "bills", "annual", "budgets", "saving"] as const;
export type PlanStep = (typeof PLAN_STEPS)[number];

export type PlanStatus = "draft" | "active" | "closed";

export interface PlanSummary {
  id: number;
  /** The year its first day is in, which is what it is called. */
  year: number;
  status: PlanStatus;
  basis: PlanBasis;
  startsOn: string;
  endsOn: string;
  /** True for the plan made by first-time setup, of the year already under way. */
  setup: boolean;
  suggestedAsOf: string;
  activatedOn: string | null;
}

export interface PlansOverview {
  /** The latest year first. */
  plans: PlanSummary[];
  nextYear: number;
  nextStartsOn: string;
  /** True when next year's plan can be started. */
  canPlan: boolean;
  /** True in an app with nothing in it yet, where first-time setup is offered. */
  canSetUp: boolean;
  reminderDue: boolean;
}

/**
 * One line of a step. For an item the amounts are totals for the plan's year; for income they are
 * take-home pay for one paycheck, with `yearCents` beside them for adding up.
 */
export interface PlanRow {
  kind: "item" | "income";
  step: PlanStep;
  id: number;
  name: string;
  itemKind: ItemKind | null;
  frequency: string;
  /** What it came to in the 12 months the suggestions look at; `null` without that much history. */
  last12Cents: number | null;
  /** Those 12 months against the 12 before, in basis points (3.3% is 330); `null` with nothing to compare. */
  trendBp: number | null;
  suggestedCents: number;
  reason: "history" | "fixed" | "not_enough_history";
  /** What it comes to at the amount already set for the plan's first day. */
  currentCents: number;
  /** The plan's amount: what was typed, or else the suggestion. */
  chosenCents: number;
  /** True when the amount was typed in. */
  yours: boolean;
  /** The plan's amount for one bill, one budget line, one transfer or one paycheck. */
  perOccurrenceCents: number;
  /** How many of those the plan's year holds. */
  occurrences: number;
  /** An item's amount shared over the year's paydays: what saving up for it takes. */
  perPaycheckCents: number;
  savedUpFor: boolean;
  yearCents: number;
  currentYearCents: number;
}

export interface PlanStepView {
  step: PlanStep;
  rows: PlanRow[];
  last12Cents: number | null;
  trendBp: number | null;
  suggestedCents: number;
  /** The step's amounts over the plan's year. */
  chosenCents: number;
  currentCents: number;
  /** How many amounts were typed in. */
  yours: number;
  /** How many differ from what is set now. */
  changes: number;
}

export interface PlanDetail {
  plan: PlanSummary;
  /** The 12 months the suggestions look at. */
  historyFrom: string;
  historyTo: string;
  /** True when a month has ended since the suggestions were worked out. */
  stale: boolean;
  paydays: number;
  steps: PlanStepView[];
  /** Take-home pay over the plan's year. */
  incomeCents: number;
  /** Bills, annual costs, budgets and saving over the plan's year. */
  outCents: number;
}

/** What Plan next year would suggest for one bill or budget. */
export interface ItemSuggestion {
  year: number;
  basis: PlanBasis;
  perYearCents: number;
  perMonthCents: number;
  reason: PlanRow["reason"];
  trendBp: number | null;
}

const PLANS_KEY = [...PAY_KEY, "plans"] as const;
const planKey = (id: number) => [...PAY_KEY, "plan", id] as const;

/** Under the pay key: a plan's lines are the items and paychecks, so it follows every change to them. */
export function usePlans() {
  return useQuery({ queryKey: PLANS_KEY, queryFn: () => api.get<PlansOverview>("/api/plans") });
}

export function usePlan(id: number) {
  return useQuery({
    queryKey: planKey(id),
    queryFn: () => api.get<PlanDetail>(`/api/plans/${id}`),
    enabled: Number.isInteger(id),
    // A typed amount is answered with the whole plan, so what was there stays up until it arrives.
    placeholderData: (previous) => previous,
  });
}

/** `enabled` is false for anything that is not a bill or a budget: nothing else has a price to suggest. */
export function useItemSuggestion(itemId: number, enabled: boolean) {
  return useQuery({
    queryKey: [...PAY_KEY, "plan-suggestion", itemId],
    queryFn: () => api.get<{ suggestion: ItemSuggestion | null }>(`/api/items/${itemId}/plan-suggestion`),
    enabled,
  });
}

/** Starts next year's plan, or first-time setup, and answers with the new plan's id. */
export function useStartPlan() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (kind: "next_year" | "setup") => api.post<{ id: number }>("/api/plans", { kind }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PAY_KEY }),
  });
}

/** Every change to a draft answers with the plan as it now stands. */
function usePlanChange<Input>(id: number, send: (input: Input) => Promise<PlanDetail>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: send,
    onSuccess: (plan) => {
      queryClient.setQueryData(planKey(id), plan);
      return queryClient.invalidateQueries({ queryKey: PAY_KEY });
    },
  });
}

export function useSetPlanBasis(id: number) {
  return usePlanChange(id, (input: { basis: PlanBasis; refresh?: boolean }) => api.put<PlanDetail>(`/api/plans/${id}`, input));
}

/** `amountCents` of `null` takes a typed amount out, so the line follows its suggestion again. */
export function useSetPlanAmount(id: number) {
  return usePlanChange(id, (input: { kind: PlanRow["kind"]; id: number; amountCents: number | null }) =>
    api.put<PlanDetail>(`/api/plans/${id}/amounts`, input),
  );
}

/** Takes the typed amounts out of one step. */
export function useAcceptSuggestions(id: number) {
  return usePlanChange(id, (step: PlanStep) => api.post<PlanDetail>(`/api/plans/${id}/accept-suggestions`, { step }));
}

/** Ends first-time setup. */
export function useFinishSetup(id: number) {
  return usePlanChange<void>(id, () => api.post<PlanDetail>(`/api/plans/${id}/finish`, {}));
}

export function useDeleteDraft(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.delete<{ done: boolean }>(`/api/plans/${id}`, { confirm: "delete-draft" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PAY_KEY }),
  });
}
