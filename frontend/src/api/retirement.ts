import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { AccountType } from "./accounts";
import { api } from "./client";

/**
 * Retirement planning (spec §4.12, §5.11): the accounts counted, and named scenarios worked out
 * against them. Amounts are cents in today's dollars; rates are basis points (3% is 300). The
 * figures are estimates for comparing scenarios, never advice.
 */

/** How an account's money comes out in retirement. */
export const TAX_TYPES = ["traditional", "roth", "taxable"] as const;
export type TaxType = (typeof TAX_TYPES)[number];

export const TAX_TYPE_LABELS: Record<TaxType, string> = {
  traditional: "Traditional (pre-tax)",
  roth: "Roth",
  taxable: "Taxable or savings",
};

/** An investment or savings account that could be counted. */
export interface RetirementAccount {
  id: number;
  name: string;
  color: string;
  type: AccountType;
  subtypeLabel: string | null;
  /** `null` when it isn't counted. */
  taxType: TaxType | null;
  balanceCents: number;
  /** What goes in over the coming year, less what comes out; it rises by each scenario's raise every year after. */
  yearlyInCents: number;
}

export interface RetirementIncome {
  name: string;
  yearlyCents: number;
  startAge: number;
}

/** Money in (+) or out (−) once, at an age. */
export interface RetirementOneOff {
  name: string;
  amountCents: number;
  age: number;
}

export interface AgePoint {
  age: number;
  /** At the expected growth, in today's dollars. */
  projectedCents: number;
  /** At the lower and higher growth, in today's dollars. */
  projectedLowCents: number;
  projectedHighCents: number;
  /** At the expected growth in that year's dollars, before inflation is taken off. */
  projectedThenCents: number;
  neededCents: number;
  enough: boolean;
}

/** Retiring before 60: how many of the years until then taxable money covers. */
export interface EarlyYears {
  years: number;
  covered: number;
  neededCents: number;
  taxableCents: number;
}

export interface RetirementTarget {
  age: number;
  neededCents: number;
  projectedCents: number;
  enough: boolean;
  shortfallCents: number;
  /** More to save a month; `null` when it's already enough or can't be reached. */
  extraMonthlyCents: number | null;
  extraPerPaycheckCents: number | null;
  reachable: boolean;
  /** Before 60: the extra is counted as taxable money, the only kind that bridges the years until then. */
  extraTaxable: boolean;
  /** `null` from 60 on. */
  early: EarlyYears | null;
}

export interface RetirementOutcome {
  /** `null` when no age up to 100 is enough at the expected growth. */
  earliestAge: number | null;
  /** The same at the lower and the higher growth. */
  earliestLowAge: number | null;
  earliestHighAge: number | null;
  ageNow: number;
  byAge: AgePoint[];
  /** `null` without a chosen age. */
  target: RetirementTarget | null;
}

/** What a scenario is made of: what is sent to save one. */
export interface ScenarioInput {
  name: string;
  salaryCents: number;
  inflationBp: number;
  withdrawalBp: number;
  /** On traditional money when it comes out. */
  taxBp: number;
  healthBeforeCents: number;
  healthAfterCents: number;
  healthInflationBp: number;
  /** Expected growth a year for every account, with a lower and a higher one beside it. */
  growthBp: number;
  growthLowBp: number;
  growthHighBp: number;
  /** How much more goes in each year than the year before; 0 for none. */
  raiseBp: number;
  extraMonthlyCents: number;
  extraInto: TaxType;
  retireAge: number | null;
  /** Shown side by side; at most three are. */
  compared: boolean;
  incomes: RetirementIncome[];
  oneOffs: RetirementOneOff[];
}

export interface RetirementScenario extends ScenarioInput {
  id: number;
  /** `null` until a birth date and an account to count are there. */
  outcome: RetirementOutcome | null;
}

export interface RetirementView {
  today: string;
  birthDate: string | null;
  /** What is still needed before anything can be worked out. */
  missing: "birth_date" | "accounts" | null;
  /** The primary paycheck's count in the coming year; `null` without one. */
  paychecksAYear: number | null;
  accounts: RetirementAccount[];
  /** Oldest first. */
  scenarios: RetirementScenario[];
}

/** The most scenarios kept, and how many sit side by side. */
export const MAX_SCENARIOS = 20;
export const MAX_COMPARED = 3;
export const MAX_INCOMES = 10;
export const MAX_ONE_OFFS = 20;
export const YOUNGEST = 18;
export const OLDEST = 100;

/** What a new scenario starts with (§4.12); the salary is always asked. */
export const SCENARIO_DEFAULTS: Omit<ScenarioInput, "name" | "salaryCents"> = {
  inflationBp: 300,
  withdrawalBp: 400,
  taxBp: 1500,
  healthBeforeCents: 0,
  healthAfterCents: 0,
  healthInflationBp: 500,
  growthBp: 600,
  growthLowBp: 400,
  growthHighBp: 800,
  raiseBp: 0,
  extraMonthlyCents: 0,
  extraInto: "traditional",
  retireAge: null,
  compared: false,
  incomes: [],
  oneOffs: [],
};

export const RETIREMENT_KEY = ["retirement"] as const;

export function useRetirement() {
  return useQuery({ queryKey: RETIREMENT_KEY, queryFn: () => api.get<RetirementView>("/api/retirement") });
}

function useRefresh() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: RETIREMENT_KEY });
}

export function useSetBirthDate() {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: (birthDate: string) => api.put<{ done: boolean }>("/api/retirement/birth-date", { birthDate }),
    onSuccess: refresh,
  });
}

/** Counts an account (or, with `null`, stops counting it) and says how its money is taxed. */
export function useSetTaxType() {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: ({ accountId, taxType }: { accountId: number; taxType: TaxType | null }) =>
      api.put<{ done: boolean }>(`/api/retirement/accounts/${accountId}`, { taxType }),
    onSuccess: refresh,
  });
}

export function useCreateScenario() {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: (input: ScenarioInput) => api.post<{ id: number }>("/api/retirement/scenarios", input),
    onSuccess: refresh,
  });
}

export function useSaveScenario(id: number) {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: (input: ScenarioInput) => api.put<{ done: boolean }>(`/api/retirement/scenarios/${id}`, input),
    onSuccess: refresh,
  });
}

export function useDeleteScenario(id: number) {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: () => api.delete<{ done: boolean }>(`/api/retirement/scenarios/${id}`),
    onSuccess: refresh,
  });
}

/** A scenario as the request that would save it unchanged, to change one thing about it. */
export function scenarioAsInput(scenario: RetirementScenario): ScenarioInput {
  return {
    name: scenario.name,
    salaryCents: scenario.salaryCents,
    inflationBp: scenario.inflationBp,
    withdrawalBp: scenario.withdrawalBp,
    taxBp: scenario.taxBp,
    healthBeforeCents: scenario.healthBeforeCents,
    healthAfterCents: scenario.healthAfterCents,
    healthInflationBp: scenario.healthInflationBp,
    growthBp: scenario.growthBp,
    growthLowBp: scenario.growthLowBp,
    growthHighBp: scenario.growthHighBp,
    raiseBp: scenario.raiseBp,
    extraMonthlyCents: scenario.extraMonthlyCents,
    extraInto: scenario.extraInto,
    retireAge: scenario.retireAge,
    compared: scenario.compared,
    incomes: scenario.incomes,
    oneOffs: scenario.oneOffs,
  };
}
