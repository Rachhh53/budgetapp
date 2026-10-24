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
  /** Social Security is taxed by its own formula (up to 85% of it). */
  socialSecurity: boolean;
  /** For anything else, how much of it is taxed income: a pension 10000, a disability benefit 0. */
  countedBp: number;
}

/**
 * How a scenario taxes retirement (spec §5.12). `flat` is the one rate on traditional money; `worked_out` takes money out
 * year by year in the order that costs least, by the tax rules and Medicare's premiums.
 */
export interface TaxSettings {
  mode: "flat" | "worked_out";
  filing: "single" | "joint";
  /** 1, or 2 with a spouse. */
  medicarePeople: number;
  stateTaxBp: number;
  /** How much of each dollar taken from taxable accounts is growth, taxed as a gain. */
  gainsShareBp: number;
  /** A month a person. */
  partDPlanCents: number;
  supplementCents: number;
  /** Income a year while working, which Medicare looks back to; `null` for the pay Budget knows. */
  workingMagiCents: number | null;
  /** Income typed for every year in retirement in place of the one worked out; `null` to work it out. */
  magiOverrideCents: number | null;
  /** `none`, `bracket:N` or `medicare:N`; `null` for the cheapest. */
  strategy: string | null;
}

export const FLAT_TAXES: TaxSettings = {
  mode: "flat",
  filing: "single",
  medicarePeople: 1,
  stateTaxBp: 0,
  gainsShareBp: 5000,
  partDPlanCents: 0,
  supplementCents: 0,
  workingMagiCents: null,
  magiOverrideCents: null,
  strategy: null,
};

/** One strategy tried, over the whole of retirement. */
export interface StrategyOutcome {
  code: string;
  label: string;
  federalCents: number;
  stateCents: number;
  medicareCents: number;
  /** Medicare's income-related amounts: the part of premiums income decides. */
  surchargesCents: number;
  /** The first age the money doesn't cover; `null` when it lasts. */
  runsOutAt: number | null;
  leftAfterTaxCents: number;
  shortfallCents: number;
}

/** One person's Medicare for a year: a month, unless it says a year. */
export interface MedicareYear {
  age: number;
  /** 0 the standard premium, then each income bracket. */
  bracket: number;
  /** The income of two years before that it went by. */
  lookBackCents: number;
  partBCents: number;
  partDCents: number;
  plansCents: number;
  yearlyCents: number;
}

/** One year of retirement, in today's dollars. */
export interface DrawdownYear {
  age: number;
  calendarYear: number;
  needCents: number;
  otherIncomeCents: number;
  fromTraditionalCents: number;
  requiredCents: number;
  fromTaxableCents: number;
  fromRothCents: number;
  toRothCents: number;
  magiCents: number;
  taxedSocialSecurityCents: number;
  federalCents: number;
  stateCents: number;
  bracketBp: number;
  medicare: MedicareYear[];
  shortfallCents: number;
  traditionalLeftCents: number;
  rothLeftCents: number;
  taxableLeftCents: number;
}

/** Retirement taxes and Medicare, worked out. */
export interface RetirementTaxes {
  retireAge: number;
  /** Where the age came from: the scenario's, the first that is enough, or 67. */
  ageFrom: "chosen" | "earliest" | "assumed";
  traditionalCents: number;
  rothCents: number;
  taxableCents: number;
  workingMagiCents: number;
  chosen: string;
  cheapest: string;
  pinned: boolean;
  /** Best first. */
  strategies: StrategyOutcome[];
  /** Tax over retirement as a share of traditional money taken out: it stands in for the flat rate. */
  taxShareBp: number;
  /** Medicare a year from 65 at today's premiums: it stands in for health from 65. */
  medicareCents: number;
  years: DrawdownYear[];
}

/** The figures for one way of filing. */
export interface FilingFigures {
  /** Where each bracket but the top one ends, as taxable income. */
  bracketTopsCents: number[];
  /** Each bracket's rate: one more than the tops. */
  bracketRatesBp: number[];
  standardDeductionCents: number;
  extraAt65Cents: number;
  seniorDeductionCents: number;
  seniorPhaseOutFromCents: number;
  zeroGainsUpToCents: number;
  fifteenGainsUpToCents: number;
  socialSecurityBaseCents: number;
  socialSecurityUpperCents: number;
  niitFromCents: number;
  /** Where each Medicare income bracket starts, 0 first. */
  medicareFromCents: number[];
}

/** The tax and Medicare figures in use: built in for their year, every one changeable. */
export interface TaxFiguresView {
  year: number;
  single: FilingFigures;
  joint: FilingFigures;
  seniorDeductionLastYear: number;
  seniorPhaseOutBp: number;
  niitBp: number;
  /** A month in each Medicare income bracket, the standard premium first. */
  partBCents: number[];
  partDCents: number[];
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
  taxes: TaxSettings;
  incomes: RetirementIncome[];
  oneOffs: RetirementOneOff[];
}

export interface RetirementScenario extends Omit<ScenarioInput, "taxes"> {
  id: number;
  taxSettings: TaxSettings;
  /** `null` until a birth date and an account to count are there. */
  outcome: RetirementOutcome | null;
  /** Worked out by the rules; `null` with a flat rate, or before anything can be worked out. */
  taxes: RetirementTaxes | null;
}

export interface RetirementView {
  today: string;
  birthDate: string | null;
  spouseBirthDate: string | null;
  /** Gross pay (take-home where there is none) over the coming year, from every income source. */
  payAYearCents: number;
  figures: TaxFiguresView;
  builtInFigures: boolean;
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
  taxes: FLAT_TAXES,
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

export function useSetSpouseBirthDate() {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: (spouseBirthDate: string | null) => api.put<{ done: boolean }>("/api/retirement/spouse-birth-date", { spouseBirthDate }),
    onSuccess: refresh,
  });
}

/** Keeps changed tax and Medicare figures; `null` goes back to the built-in ones. */
export function useSetTaxFigures() {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: (figures: TaxFiguresView | null) =>
      figures === null
        ? api.delete<{ done: boolean }>("/api/retirement/tax-figures")
        : api.put<{ done: boolean }>("/api/retirement/tax-figures", figures),
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
    taxes: scenario.taxSettings,
    incomes: scenario.incomes,
    oneOffs: scenario.oneOffs,
  };
}
