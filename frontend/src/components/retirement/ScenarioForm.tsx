import { Sunset } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import {
  MAX_COMPARED,
  OLDEST,
  type RetirementScenario,
  SCENARIO_DEFAULTS,
  type ScenarioInput,
  TAX_TYPE_LABELS,
  type TaxSettings,
  TAX_TYPES,
  type TaxType,
  YOUNGEST,
  scenarioAsInput,
  useCreateScenario,
  useDeleteScenario,
  useSaveScenario,
} from "../../api/retirement";
import type { TermId } from "../../help/terms";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { parseRate, rateToInput } from "../../lib/rates";
import { ConfirmDelete } from "../ConfirmDelete";
import { FormSection } from "../FormSection";
import { InfoTip } from "../help/InfoTip";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { Segmented } from "../Segmented";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

/**
 * The parts of a scenario, each opened from the card that shows it, so no form is taller than
 * the window. A new scenario asks the goal only; the rest start at their defaults. `medicare` is
 * only for a scenario whose taxes are worked out by the rules (spec §5.12).
 */
export type ScenarioPart = "goal" | "health" | "medicare" | "growth";

const TITLES: Record<ScenarioPart, string> = {
  goal: "Goal",
  health: "Taxes & health",
  medicare: "Medicare & income",
  growth: "Growth & saving",
};

const MODE_OPTIONS: { value: TaxSettings["mode"]; label: string }[] = [
  { value: "flat", label: "A flat rate" },
  { value: "worked_out", label: "By the rules" },
];


/** The strategy select's value for the cheapest, whichever that turns out to be. */
const CHEAPEST = "";

const NAME_MAX = 80;

const INTO_OPTIONS = TAX_TYPES.map((type) => ({ value: type, label: type === "taxable" ? "Taxable" : TAX_TYPE_LABELS[type].replace(" (pre-tax)", "") }));

/** A typed percentage within limits, in basis points; `null` when it isn't one. */
function rateWithin(text: string, least: number, most: number): number | null {
  const rate = parseRate(text);
  return rate === null || rate < least || rate > most ? null : rate;
}

/** A typed whole age within the plan, or `null` when it isn't one. */
export function ageWithin(text: string): number | null {
  if (!/^\d{1,3}$/.test(text.trim())) {
    return null;
  }
  const age = Number(text.trim());
  return age < YOUNGEST || age > OLDEST ? null : age;
}

/**
 * Adds a scenario (`scenario` left out) or changes one part of it. Everything not on screen is sent
 * as it was, so a part only ever changes what it shows.
 *
 * @param comparedCount how many are side by side now: a new one joins them while there is room
 * @param onSaved       a new scenario's id, so the page can show it
 */
export function ScenarioForm({
  scenario,
  part,
  suggestedName,
  comparedCount,
  payAYearCents,
  onClose,
  onSaved,
}: {
  scenario?: RetirementScenario;
  part: ScenarioPart;
  suggestedName?: string;
  comparedCount: number;
  /** The pay Budget knows over the coming year, shown as what income while working is when left empty. */
  payAYearCents?: number;
  onClose: () => void;
  onSaved?: (id: number) => void;
}) {
  const id = useId();
  const create = useCreateScenario();
  const save = useSaveScenario(scenario?.id ?? 0);
  const remove = useDeleteScenario(scenario?.id ?? 0);
  const base: ScenarioInput = scenario
    ? scenarioAsInput(scenario)
    : { ...SCENARIO_DEFAULTS, name: suggestedName ?? "", salaryCents: 0, compared: comparedCount < MAX_COMPARED };

  const [name, setName] = useState(base.name);
  const [salary, setSalary] = useState(scenario ? centsToInput(base.salaryCents) : "");
  const [retireAge, setRetireAge] = useState(base.retireAge === null ? "" : String(base.retireAge));
  const [withdrawal, setWithdrawal] = useState(rateToInput(base.withdrawalBp));
  const [inflation, setInflation] = useState(rateToInput(base.inflationBp));
  const [compared, setCompared] = useState(base.compared);
  const [tax, setTax] = useState(rateToInput(base.taxBp));
  const [healthBefore, setHealthBefore] = useState(centsToInput(base.healthBeforeCents));
  const [healthAfter, setHealthAfter] = useState(centsToInput(base.healthAfterCents));
  const [healthInflation, setHealthInflation] = useState(rateToInput(base.healthInflationBp));
  const [growth, setGrowth] = useState(rateToInput(base.growthBp));
  const [growthLow, setGrowthLow] = useState(rateToInput(base.growthLowBp));
  const [growthHigh, setGrowthHigh] = useState(rateToInput(base.growthHighBp));
  const [raise, setRaise] = useState(rateToInput(base.raiseBp));
  const [extra, setExtra] = useState(centsToInput(base.extraMonthlyCents));
  const [extraInto, setExtraInto] = useState<TaxType>(base.extraInto);
  const taxes = base.taxes;
  const [mode, setMode] = useState(taxes.mode);
  const [filing, setFiling] = useState(taxes.filing);
  const [people, setPeople] = useState(String(taxes.medicarePeople));
  const [stateTax, setStateTax] = useState(rateToInput(taxes.stateTaxBp));
  const [gainsShare, setGainsShare] = useState(rateToInput(taxes.gainsShareBp));
  const [partD, setPartD] = useState(centsToInput(taxes.partDPlanCents));
  const [supplement, setSupplement] = useState(centsToInput(taxes.supplementCents));
  const [workingMagi, setWorkingMagi] = useState(taxes.workingMagiCents === null ? "" : centsToInput(taxes.workingMagiCents));
  const [magiOverride, setMagiOverride] = useState(taxes.magiOverrideCents === null ? "" : centsToInput(taxes.magiOverrideCents));
  const [strategy, setStrategy] = useState(taxes.strategy ?? CHEAPEST);
  const [problem, setProblem] = useState<string>();
  // The ways tried last time, best first, to choose from; none until the scenario is worked out by the rules.
  const strategies = scenario?.taxes?.strategies ?? [];
  // Room for this one beside the others: always when it is already there.
  const roomToCompare = base.compared || comparedCount < MAX_COMPARED;

  /** What is on screen as the request, or what to put right first. */
  const toInput = (): ScenarioInput | string => {
    if (part === "goal") {
      const salaryCents = parseDollars(salary);
      const age = retireAge.trim() === "" ? null : ageWithin(retireAge);
      const withdrawalBp = rateWithin(withdrawal, 100, 1000);
      const inflationBp = rateWithin(inflation, 0, 2000);
      if (name.trim() === "") {
        return "Please add a name for this scenario.";
      }
      if (salaryCents === null || salaryCents <= 0) {
        return salaryCents === 0 ? "Please enter the salary you'd like a year in retirement, more than $0.00." : AMOUNT_HELP;
      }
      if (retireAge.trim() !== "" && age === null) {
        return `Please enter an age to retire between ${YOUNGEST} and ${OLDEST}, or leave it empty.`;
      }
      if (withdrawalBp === null) {
        return "Please enter a withdrawal rate between 1% and 10% a year, like 4.00.";
      }
      if (inflationBp === null) {
        return "Please enter inflation between 0% and 20% a year, like 3.00.";
      }
      return { ...base, name: name.trim(), salaryCents, retireAge: age, withdrawalBp, inflationBp, compared: compared && roomToCompare };
    }
    if (part === "health") {
      const before = parseDollars(healthBefore);
      const healthInflationBp = rateWithin(healthInflation, 0, 2000);
      if (before === null || before < 0) {
        return AMOUNT_HELP;
      }
      if (healthInflationBp === null) {
        return "Please enter how fast health costs rise between 0% and 20% a year, like 5.00.";
      }
      if (mode === "flat") {
        const taxBp = rateWithin(tax, 0, 6000);
        const after = parseDollars(healthAfter);
        if (taxBp === null) {
          return "Please enter a tax rate in retirement between 0% and 60%, like 15.00.";
        }
        if (after === null || after < 0) {
          return AMOUNT_HELP;
        }
        return { ...base, taxBp, healthBeforeCents: before, healthAfterCents: after, healthInflationBp, taxes: { ...taxes, mode } };
      }
      // Worked out, the flat rate and health from 65 are kept as they were: the rules and Medicare stand in for them.
      const stateTaxBp = rateWithin(stateTax, 0, 2000);
      const gainsShareBp = rateWithin(gainsShare, 0, 10_000);
      if (stateTaxBp === null) {
        return "Please enter a state income tax between 0% and 20%, or 0.00 for none.";
      }
      if (gainsShareBp === null) {
        return "Please enter how much of taxable withdrawals is growth, from 0% to 100%, like 50.00.";
      }
      const medicarePeople = Number(people);
      return {
        ...base,
        healthBeforeCents: before,
        healthInflationBp,
        taxes: { ...taxes, mode, filing, medicarePeople, stateTaxBp, gainsShareBp },
      };
    }
    if (part === "medicare") {
      const partDPlanCents = parseDollars(partD);
      const supplementCents = parseDollars(supplement);
      // Left empty, each of these is worked out instead.
      const workingMagiCents = workingMagi.trim() === "" ? null : parseDollars(workingMagi);
      const magiOverrideCents = magiOverride.trim() === "" ? null : parseDollars(magiOverride);
      if (partDPlanCents === null || partDPlanCents < 0 || supplementCents === null || supplementCents < 0) {
        return AMOUNT_HELP;
      }
      const badWorking = workingMagi.trim() !== "" && (workingMagiCents === null || workingMagiCents < 0);
      const badOverride = magiOverride.trim() !== "" && (magiOverrideCents === null || magiOverrideCents < 0);
      if (badWorking || badOverride) {
        return "Please enter an amount a year, like 85,000.00, or leave it empty to have it worked out.";
      }
      return {
        ...base,
        taxes: { ...taxes, partDPlanCents, supplementCents, workingMagiCents, magiOverrideCents, strategy: strategy === CHEAPEST ? null : strategy },
      };
    }
    const growthBp = rateWithin(growth, 0, 10_000);
    const growthLowBp = rateWithin(growthLow, 0, 10_000);
    const growthHighBp = rateWithin(growthHigh, 0, 10_000);
    const raiseBp = rateWithin(raise, 0, 2000);
    const extraCents = parseDollars(extra);
    if (growthBp === null) {
      return "Please enter the expected growth between 0% and 100% a year, like 6.00.";
    }
    if (growthLowBp === null || growthLowBp > growthBp) {
      return `Please enter a lower growth from 0.00 up to the expected ${rateToInput(growthBp)}, like 4.00.`;
    }
    if (growthHighBp === null || growthHighBp < growthBp) {
      return `Please enter a higher growth from the expected ${rateToInput(growthBp)} up to 100.00, like 8.00.`;
    }
    if (raiseBp === null) {
      return "Please enter a raise between 0% and 20% a year, or 0.00 for none.";
    }
    if (extraCents === null || extraCents < 0) {
      return AMOUNT_HELP;
    }
    return { ...base, growthBp, growthLowBp, growthHighBp, raiseBp, extraMonthlyCents: extraCents, extraInto };
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const input = toInput();
    if (typeof input === "string") {
      setProblem(input);
      return;
    }
    setProblem(undefined);
    try {
      if (scenario) {
        await save.mutateAsync(input);
      } else {
        const made = await create.mutateAsync(input);
        onSaved?.(made.id);
      }
      onClose();
    } catch {
      // The request's own message is shown below; the form stays open to try again.
    }
  };

  const saving = create.isPending || save.isPending;
  const failure = problem ?? create.error?.message ?? save.error?.message;
  const title = scenario ? `${scenario.name} · ${TITLES[part]}` : "New scenario";

  return (
    <Modal title={title} icon={Sunset} onClose={onClose}>
      <form onSubmit={submit} noValidate className="space-y-4">
        {part === "goal" && (
          <FormSection title="Goal">
            {/* Two to a row even on a phone, for the short fields, so the form fits one screen there (§6). */}
            <div className="grid grid-cols-2 gap-x-4 gap-y-3">
              <div className="col-span-2">
                <label htmlFor={`${id}-name`} className={LABEL}>
                  Name
                </label>
                <input
                  id={`${id}-name`}
                  value={name}
                  onChange={(event) => setName(event.target.value)}
                  maxLength={NAME_MAX}
                  placeholder="Retire at 60"
                  className={INPUT}
                />
              </div>
              <div className="col-span-2 sm:col-span-1">
                <MoneyField label="Salary a year in retirement" value={salary} onChange={setSalary} hint="In today's dollars." tip="retirementSalary" />
              </div>
              <div>
                <div className="mb-1 flex items-center gap-1">
                  <label htmlFor={`${id}-age`} className="font-medium">
                    Retire at
                  </label>
                  <InfoTip term="retireAt" />
                </div>
                <input
                  id={`${id}-age`}
                  value={retireAge}
                  onChange={(event) => setRetireAge(event.target.value)}
                  inputMode="numeric"
                  placeholder="62"
                  aria-describedby={`${id}-age-hint`}
                  className={INPUT}
                />
                <p id={`${id}-age-hint`} className={HINT}>
                  Optional.
                </p>
              </div>
              <RateField id={`${id}-withdrawal`} label="Withdrawal (%)" value={withdrawal} onChange={setWithdrawal} hint="Of savings a year; 4.00 is the 4% rule." tip="withdrawalRate" />
              <RateField id={`${id}-inflation`} label="Inflation (%)" value={inflation} onChange={setInflation} hint="A year." tip="retirementInflation" />
              {/* Level with Inflation's box, beside it rather than under it. */}
              <label className="flex min-h-11 items-center gap-3 self-start mt-7">
                <input
                  type="checkbox"
                  checked={compared && roomToCompare}
                  disabled={!roomToCompare}
                  onChange={(event) => setCompared(event.target.checked)}
                  className="size-5 shrink-0 accent-(--accent)"
                />
                <span>{roomToCompare ? "Show side by side" : `Show side by side (${MAX_COMPARED} already are)`}</span>
              </label>
            </div>
          </FormSection>
        )}

        {part === "health" && (
          <FormSection title="Taxes & health">
            <Segmented legend="How taxes are worked out" options={MODE_OPTIONS} value={mode} onChange={setMode} tip="taxMode" />
            {mode === "flat" ? (
              // Two to a row even on a phone, for the short fields, so the form fits one screen there (§6).
              <div className="grid grid-cols-2 gap-x-4 gap-y-3">
                <RateField id={`${id}-tax`} label="Tax (%)" value={tax} onChange={setTax} hint="On traditional money." tip="retirementTax" />
                <RateField id={`${id}-health-inflation`} label="Health costs rise (%)" value={healthInflation} onChange={setHealthInflation} hint="A year." tip="retirementHealth" />
                <div className="col-span-2 sm:col-span-1">
                  <MoneyField label="Health insurance a year before 65" value={healthBefore} onChange={setHealthBefore} hint="0.00 if not counted." tip="healthBefore65" />
                </div>
                <div className="col-span-2 sm:col-span-1">
                  <MoneyField label="Health costs a year from 65" value={healthAfter} onChange={setHealthAfter} hint="Medicare premiums and the rest." tip="healthFrom65" />
                </div>
              </div>
            ) : (
              // Two to a row even on a phone, for the short fields, so the form fits one screen there (§6).
              <div className="grid grid-cols-2 gap-x-4 gap-y-3">
                {/* Selects rather than segments: side by side they fit a phone, and the form one screen there (§6). */}
                <div>
                  <label htmlFor={`${id}-filing`} className={LABEL}>
                    Filing
                  </label>
                  <select id={`${id}-filing`} value={filing} onChange={(event) => setFiling(event.target.value as TaxSettings["filing"])} className={INPUT}>
                    <option value="single">Single</option>
                    <option value="joint">Married, jointly</option>
                  </select>
                </div>
                <div>
                  <div className="mb-1 flex items-center gap-1">
                    <label htmlFor={`${id}-people`} className="font-medium">
                      Medicare for
                    </label>
                    <InfoTip term="medicarePeople" />
                  </div>
                  <select id={`${id}-people`} value={people} onChange={(event) => setPeople(event.target.value)} className={INPUT}>
                    <option value="1">Just me</option>
                    <option value="2">Me and my spouse</option>
                  </select>
                </div>
                <RateField id={`${id}-state`} label="State tax (%)" value={stateTax} onChange={setStateTax} hint="0.00 for none." tip="stateTax" />
                <RateField id={`${id}-gains`} label="Gains share (%)" value={gainsShare} onChange={setGainsShare} hint="Of taxable money." tip="gainsShare" />
                <RateField id={`${id}-health-inflation`} label="Health costs rise (%)" value={healthInflation} onChange={setHealthInflation} hint="A year." tip="retirementHealth" />
                <MoneyField label="Insurance a year before 65" value={healthBefore} onChange={setHealthBefore} hint="Medicare from 65." tip="healthBefore65" />
              </div>
            )}
          </FormSection>
        )}

        {part === "medicare" && (
          <FormSection title="Medicare & income">
            <div className="grid grid-cols-2 gap-x-4 gap-y-3">
              <MoneyField label="Part D plan a month" value={partD} onChange={setPartD} hint="A person." tip="partDPlan" />
              <MoneyField label="Medigap or Advantage" value={supplement} onChange={setSupplement} hint="A month, a person." tip="medigap" />
              <div className="col-span-2 sm:col-span-1">
                <MoneyField
                  label="Income while working"
                  value={workingMagi}
                  onChange={setWorkingMagi}
                  hint={payAYearCents === undefined ? "A year. Empty for the pay Budget knows." : `A year. Empty for ${formatCents(payAYearCents)}, the pay Budget knows.`}
                  tip="workingMagi"
                />
              </div>
              <div className="col-span-2 sm:col-span-1">
                <MoneyField label="Income in retirement" value={magiOverride} onChange={setMagiOverride} hint="A year. Empty to work it out." tip="magiOverride" />
              </div>
              <div className="col-span-2">
                <div className="mb-1 flex items-center gap-1">
                  <label htmlFor={`${id}-strategy`} className="font-medium">
                    Where the money comes from
                  </label>
                  <InfoTip term="drawdownStrategy" />
                </div>
                <select id={`${id}-strategy`} value={strategy} onChange={(event) => setStrategy(event.target.value)} className={INPUT}>
                  <option value={CHEAPEST}>The cheapest, whichever it is</option>
                  {strategies.map((outcome) => (
                    <option key={outcome.code} value={outcome.code}>
                      {outcome.label}
                    </option>
                  ))}
                </select>
              </div>
            </div>
          </FormSection>
        )}

        {part === "growth" && (
          <FormSection title="Growth & saving">
            {/* One heading over three short fields, three across even on a phone, so the form fits one screen (§6). */}
            <fieldset aria-describedby={`${id}-growth-hint`}>
              <legend className="mb-1 flex items-center gap-1 font-medium">
                Growth a year (%)
                <InfoTip term="growthRange" />
              </legend>
              <div className="grid grid-cols-3 gap-x-3">
                <ShortRate id={`${id}-growth-low`} label="Lower" value={growthLow} onChange={setGrowthLow} />
                <ShortRate id={`${id}-growth`} label="Expected" value={growth} onChange={setGrowth} />
                <ShortRate id={`${id}-growth-high`} label="Higher" value={growthHigh} onChange={setGrowthHigh} />
              </div>
              <p id={`${id}-growth-hint`} className={HINT}>
                A bad run, your best guess and a good run, compounding monthly, before inflation.
              </p>
            </fieldset>
            <RateField id={`${id}-raise`} label="Raise a year (%)" value={raise} onChange={setRaise} hint="0.00 for none." tip="yearlyRaise" />
            <MoneyField label="Extra saved a month" value={extra} onChange={setExtra} hint="On top of what already goes in." tip="extraSaved" />
            <Segmented legend="Extra goes into" options={INTO_OPTIONS} value={extraInto} onChange={setExtraInto} dense />
          </FormSection>
        )}

        {failure && (
          <p role="alert" className="text-danger">
            {failure}
          </p>
        )}

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          {scenario && part === "goal" && (
            <div className="sm:mr-auto">
              <ConfirmDelete
                action="Delete scenario…"
                question={`Delete ${scenario.name}?`}
                detail="Its other income and one-offs go with it. You can undo it straight after."
                confirmLabel="Delete it"
                pending={remove.isPending}
                error={remove.error?.message}
                onConfirm={() => remove.mutate(undefined, { onSuccess: onClose })}
              />
            </div>
          )}
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="submit" disabled={saving} className={BUTTON_PRIMARY}>
            {saving ? "Saving…" : scenario ? "Save" : "Add scenario"}
          </button>
        </div>
      </form>
    </Modal>
  );
}

/** One of the three growth rates: a short visible label under the group's heading, a full name for screen readers. */
function ShortRate({ id, label, value, onChange }: { id: string; label: string; value: string; onChange: (text: string) => void }) {
  return (
    <div>
      <label htmlFor={id} className="mb-1 block text-sm text-muted">
        {label}
        <span className="sr-only"> growth (%)</span>
      </label>
      <input
        id={id}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        inputMode="decimal"
        autoComplete="off"
        className={`${INPUT} tabular-nums`}
      />
    </div>
  );
}

/** A percentage typed as text, like 4.00. */
export function RateField({
  id,
  label,
  value,
  onChange,
  hint,
  tip,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (text: string) => void;
  hint: string;
  /** An ⓘ beside the label, outside it so the field's name stays just the label. */
  tip?: TermId;
}) {
  return (
    <div>
      {tip ? (
        <div className="mb-1 flex items-center gap-1">
          <label htmlFor={id} className="font-medium">
            {label}
          </label>
          <InfoTip term={tip} />
        </div>
      ) : (
        <label htmlFor={id} className={LABEL}>
          {label}
        </label>
      )}
      <input
        id={id}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        inputMode="decimal"
        autoComplete="off"
        aria-describedby={`${id}-hint`}
        className={`${INPUT} tabular-nums`}
      />
      <p id={`${id}-hint`} className={HINT}>
        {hint}
      </p>
    </div>
  );
}
