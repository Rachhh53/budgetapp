import { type ReactNode, useId, useState } from "react";
import {
  MAX_COMPARED,
  MAX_INCOMES,
  MAX_ONE_OFFS,
  MAX_SCENARIOS,
  type RetirementAccount,
  type RetirementOutcome,
  type RetirementScenario,
  type RetirementView,
  TAX_TYPE_LABELS,
  TAX_TYPES,
  type TaxType,
  scenarioAsInput,
  useCreateScenario,
  useRetirement,
  useSetTaxType,
} from "../api/retirement";
import { Amount } from "../components/Amount";
import { Card } from "../components/Card";
import { EditButton } from "../components/EditButton";
import { HelpLink } from "../components/help/HelpLink";
import { InfoTip } from "../components/help/InfoTip";
import { ByAgeChart } from "../components/retirement/ByAgeChart";
import { BirthDateForm, IncomeForm, OneOffForm, SpouseBirthDateForm } from "../components/retirement/EntryForms";
import { FiguresForm } from "../components/retirement/FiguresForm";
import { type ScenarioPart, ScenarioForm } from "../components/retirement/ScenarioForm";
import { TaxesSection } from "../components/retirement/TaxesSection";
import { BUTTON_LINK, BUTTON_PRIMARY, BUTTON_SECONDARY, INPUT, LABEL, PAGE_TITLE } from "../components/ui";
import type { TermId } from "../help/terms";
import { formatDate } from "../lib/dates";
import { formatCents } from "../lib/money";
import { formatRate } from "../lib/rates";

/** What is open over the page: one part of a scenario, a new one, an entry of a list, or the birth date. */
type Open =
  | { kind: "part"; part: ScenarioPart }
  | { kind: "new" }
  | { kind: "income"; index?: number }
  | { kind: "oneOff"; index?: number }
  | { kind: "birthDate" }
  | { kind: "spouseBirthDate" }
  | { kind: "figures" }
  | null;

const NAME_MAX = 80;
const NOT_COUNTED = "";

/**
 * Retirement (spec §4.12, §5.11, §8; Rachel, 2026-10-19): the age your savings would be enough,
 * or what more to save for an age you choose, across named scenarios. The ones marked to compare
 * sit side by side at the top and each opens its own; under the chosen one, its figures, a chart
 * by age and a card per topic with its own pencil. The accounts counted and the birth date are
 * shared by every scenario. Everything is in today's dollars, an estimate for comparing, never advice.
 */
export function RetirementPage() {
  const retirement = useRetirement();
  const create = useCreateScenario();
  const [chosenId, setChosenId] = useState<number | null>(null);
  const [open, setOpen] = useState<Open>(null);
  const pickerId = useId();

  if (retirement.isPending) {
    return <p className="text-muted">Loading your retirement plan…</p>;
  }
  if (retirement.isError) {
    return (
      <>
        <h1 className={`${PAGE_TITLE} mb-4`}>Retirement</h1>
        <Card>
          <p role="alert">We couldn't load your retirement plan just now. Please try again in a moment.</p>
        </Card>
      </>
    );
  }

  const view = retirement.data;
  const { scenarios } = view;
  const chosen = scenarios.find((scenario) => scenario.id === chosenId) ?? scenarios[0];
  const comparedCount = scenarios.filter((scenario) => scenario.compared).length;
  const compared = scenarios.filter((scenario) => scenario.compared);
  const full = scenarios.length >= MAX_SCENARIOS;

  /** A copy to change one thing without losing the original; it joins the comparison while there is room. */
  const copy = (scenario: RetirementScenario) => {
    const name = `${scenario.name} (copy)`.slice(0, NAME_MAX);
    create.mutate(
      { ...scenarioAsInput(scenario), name, compared: comparedCount < MAX_COMPARED },
      { onSuccess: (made) => setChosenId(made.id) },
    );
  };

  return (
    <>
      <div className="mb-4 flex flex-wrap items-end justify-between gap-3 md:mb-6">
        <div>
          <p className="text-sm font-semibold tracking-wide text-muted uppercase">Retirement</p>
          <div className="flex min-w-0 items-center gap-1">
            <h1 className={PAGE_TITLE}>When can I retire?</h1>
            <HelpLink />
          </div>
          <p className="text-muted">
            In today's dollars: estimates for comparing, not advice. <InfoTip term="todaysDollars" className="align-middle" />
          </p>
        </div>
        <button type="button" onClick={() => setOpen({ kind: "new" })} disabled={full} className={BUTTON_PRIMARY}>
          + New scenario
        </button>
      </div>

      <div className="space-y-4">
        {full && <p className="text-muted">You have {MAX_SCENARIOS} scenarios, the most Budget keeps. Delete one to add another.</p>}
        {view.missing !== null && <FirstSteps view={view} onBirthDate={() => setOpen({ kind: "birthDate" })} />}

        {scenarios.length === 0 ? (
          <Card title="Scenarios">
            <p className="text-muted">
              A scenario is one way retirement could go: the salary you'd like a year, an age to retire, and what-ifs like saving more or a
              bad market. Start with one; copy it to try changes.
            </p>
            <button type="button" onClick={() => setOpen({ kind: "new" })} className={`${BUTTON_PRIMARY} mt-3`}>
              Add your first scenario
            </button>
          </Card>
        ) : (
          <>
            {compared.length > 1 && <Compare scenarios={compared} chosenId={chosen?.id} onChoose={setChosenId} />}

            {chosen && (
              <>
                <div className="flex flex-wrap items-end gap-3">
                  {scenarios.length > 1 && (
                    <div className="min-w-0 flex-1 sm:max-w-xs">
                      <label htmlFor={pickerId} className={LABEL}>
                        Scenario
                      </label>
                      <select id={pickerId} value={chosen.id} onChange={(event) => setChosenId(Number(event.target.value))} className={INPUT}>
                        {scenarios.map((scenario) => (
                          <option key={scenario.id} value={scenario.id}>
                            {scenario.name}
                          </option>
                        ))}
                      </select>
                    </div>
                  )}
                  {scenarios.length === 1 && <h2 className="min-w-0 flex-1 truncate text-xl font-semibold">{chosen.name}</h2>}
                  <button
                    type="button"
                    onClick={() => copy(chosen)}
                    disabled={full || create.isPending}
                    aria-label={`Copy ${chosen.name}`}
                    className={BUTTON_SECONDARY}
                  >
                    {create.isPending ? "Copying…" : "Copy"}
                  </button>
                </div>
                {create.error && (
                  <p role="alert" className="text-danger">
                    {create.error.message}
                  </p>
                )}

                <Figures scenario={chosen} paychecksAYear={view.paychecksAYear} />

                {chosen.outcome && (
                  <Card title="By age" tip="byAgeChart">
                    <ByAgeChart
                      points={chosen.outcome.byAge}
                      earliestAge={chosen.outcome.earliestAge}
                      chosenAge={chosen.retireAge}
                      rates={{ low: chosen.growthLowBp, expected: chosen.growthBp, high: chosen.growthHighBp }}
                    />
                  </Card>
                )}

                <ScenarioCards scenario={chosen} payAYearCents={view.payAYearCents} onOpen={setOpen} />

                {chosen.taxes && (
                  <TaxesSection scenario={chosen} taxes={chosen.taxes} figures={view.figures} onFigures={() => setOpen({ kind: "figures" })} />
                )}
              </>
            )}
          </>
        )}

        <AccountsCounted
          view={view}
          onBirthDate={() => setOpen({ kind: "birthDate" })}
          onSpouseBirthDate={() => setOpen({ kind: "spouseBirthDate" })}
        />
      </div>

      {open?.kind === "new" && (
        <ScenarioForm
          part="goal"
          suggestedName={scenarios.length === 0 ? "My plan" : ""}
          comparedCount={comparedCount}
          onClose={() => setOpen(null)}
          onSaved={setChosenId}
        />
      )}
      {open?.kind === "part" && chosen && (
        <ScenarioForm
          scenario={chosen}
          part={open.part}
          comparedCount={comparedCount}
          payAYearCents={view.payAYearCents}
          onClose={() => setOpen(null)}
        />
      )}
      {open?.kind === "income" && chosen && <IncomeForm scenario={chosen} index={open.index} onClose={() => setOpen(null)} />}
      {open?.kind === "oneOff" && chosen && <OneOffForm scenario={chosen} index={open.index} onClose={() => setOpen(null)} />}
      {open?.kind === "birthDate" && <BirthDateForm birthDate={view.birthDate} onClose={() => setOpen(null)} />}
      {open?.kind === "spouseBirthDate" && <SpouseBirthDateForm spouseBirthDate={view.spouseBirthDate} onClose={() => setOpen(null)} />}
      {open?.kind === "figures" && <FiguresForm figures={view.figures} builtIn={view.builtInFigures} onClose={() => setOpen(null)} />}
    </>
  );
}

/** What is still needed before anything can be worked out, as the next thing to do. */
function FirstSteps({ view, onBirthDate }: { view: RetirementView; onBirthDate: () => void }) {
  return (
    <Card title="Before Budget can work it out">
      <ol className="list-decimal space-y-2 pl-5">
        <li>
          {view.birthDate === null ? (
            <>
              Add your birth date, so every age counts from it.{" "}
              <button type="button" onClick={onBirthDate} className={BUTTON_LINK}>
                Add birth date
              </button>
            </>
          ) : (
            <>Birth date added.</>
          )}
        </li>
        <li>Under Accounts counted below, choose how each retirement or savings account's money is taxed.</li>
      </ol>
    </Card>
  );
}

/** The scenarios marked to compare, side by side; each opens as the chosen one below. */
function Compare({ scenarios, chosenId, onChoose }: { scenarios: RetirementScenario[]; chosenId?: number; onChoose: (id: number) => void }) {
  return (
    <section aria-label="Compare scenarios" className="grid grid-cols-1 gap-3 md:grid-cols-3">
      {scenarios.map((scenario) => {
        const outcome = scenario.outcome;
        const at = ageShown(scenario);
        const point = outcome && at !== null ? outcome.byAge.find((candidate) => candidate.age === at) : undefined;
        const chosen = scenario.id === chosenId;
        return (
          <button
            key={scenario.id}
            type="button"
            onClick={() => onChoose(scenario.id)}
            aria-pressed={chosen}
            className={`cursor-pointer rounded-card border bg-surface p-4 text-left hover:bg-bg/60 ${chosen ? "border-accent ring-2 ring-accent" : "border-line"}`}
          >
            <span className="block truncate font-semibold">{scenario.name}</span>
            {outcome === null ? (
              <span className="mt-1 block text-sm text-muted">Needs a birth date and an account counted.</span>
            ) : (
              <>
                <span className="mt-1 block text-2xl font-bold">
                  {outcome.earliestAge === null ? "Not by 100" : `Retire at ${outcome.earliestAge}`}
                </span>
                <span className="block text-sm text-muted">
                  {ageWords(outcome.earliestLowAge)} at {formatRate(scenario.growthLowBp)} · {ageWords(outcome.earliestHighAge)} at{" "}
                  {formatRate(scenario.growthHighBp)}
                </span>
                {point && (
                  <span className="mt-1 block text-sm text-muted">
                    At {point.age}: needed <span data-amount>{formatCents(point.neededCents)}</span>, projected{" "}
                    <span data-amount>{formatCents(point.projectedCents)}</span>
                  </span>
                )}
                {outcome.target && (
                  <span className="mt-1 block text-sm font-semibold">
                    {outcome.target.enough ? `On track for ${outcome.target.age}` : extraWords(outcome.target.extraMonthlyCents, outcome.target.age)}
                  </span>
                )}
              </>
            )}
          </button>
        );
      })}
    </section>
  );
}

/** The age a scenario's figures are for: the one chosen, or else the earliest that is enough. */
function ageShown(scenario: RetirementScenario): number | null {
  return scenario.retireAge ?? scenario.outcome?.earliestAge ?? null;
}

function extraWords(extraMonthlyCents: number | null, age: number): string {
  return extraMonthlyCents === null ? `Out of reach for ${age}` : `${formatCents(extraMonthlyCents)} more a month for ${age}`;
}

/** The chosen scenario's figures: the earliest age, needed and projected then, and for its own age what it takes. */
function Figures({ scenario, paychecksAYear }: { scenario: RetirementScenario; paychecksAYear: number | null }) {
  const outcome = scenario.outcome;
  if (outcome === null) {
    return (
      <Card>
        <p className="text-muted">Add your birth date and count an account below, and {scenario.name} is worked out here.</p>
      </Card>
    );
  }
  const at = ageShown(scenario);
  const point = at === null ? undefined : outcome.byAge.find((candidate) => candidate.age === at);
  const thenNote = at === null ? "no age is enough by 100" : scenario.retireAge !== null ? `at ${at}, the age you chose` : `at ${at}`;

  return (
    <Card>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <div>
          <p className="flex items-center gap-1 text-muted">
            You can retire at
            <InfoTip term="earliestRetirement" />
          </p>
          <p className="text-3xl font-bold">{outcome.earliestAge ?? "Not by 100"}</p>
          <p className="text-muted">
            at {formatRate(scenario.growthBp)} growth · you're {outcome.ageNow}
          </p>
          <p className="text-muted">
            {ageWords(outcome.earliestLowAge)} at {formatRate(scenario.growthLowBp)} · {ageWords(outcome.earliestHighAge)} at{" "}
            {formatRate(scenario.growthHighBp)}
          </p>
        </div>
        <Stat label="Needed then" cents={point?.neededCents} tip="neededThen">
          {thenNote}
        </Stat>
        <Stat label="Projected then" cents={point?.projectedCents} tip="projectedSavings">
          {point && (
            <>
              <span data-amount>{formatCents(point.projectedThenCents)}</span> in that year's dollars
              <br />
              <span data-amount>{formatCents(point.projectedLowCents)}</span> to <span data-amount>{formatCents(point.projectedHighCents)}</span>{" "}
              at {formatRate(scenario.growthLowBp)}–{formatRate(scenario.growthHighBp)}
            </>
          )}
          {!point && "savings, after tax on traditional money"}
        </Stat>
        <TargetFigure outcome={outcome} paychecksAYear={paychecksAYear} />
      </div>
      {outcome.target?.early && outcome.target.early.covered < outcome.target.early.years && (
        <p role="status" className="mt-4 font-semibold text-danger">
          Taxable and savings money covers {outcome.target.early.covered} of the {outcome.target.early.years} years before 60. IRA and 401(k)
          money usually has a 10% penalty before 59½.
        </p>
      )}
    </Card>
  );
}

/** For the age chosen: on track, or short and what more a month and a paycheck would make it enough. */
function TargetFigure({ outcome, paychecksAYear }: { outcome: RetirementOutcome; paychecksAYear: number | null }) {
  const target = outcome.target;
  if (target === null) {
    return (
      <div>
        <p className="flex items-center gap-1 text-muted">
          For an age you choose
          <InfoTip term="forAge" />
        </p>
        <p className="mt-1 font-medium">Set Retire at under Goal to see what it takes.</p>
      </div>
    );
  }
  if (target.enough) {
    return (
      <div>
        <p className="flex items-center gap-1 text-muted">
          For age {target.age}
          <InfoTip term="forAge" />
        </p>
        <p className="text-3xl font-bold">On track</p>
        <p className="text-muted">what you save now is enough</p>
      </div>
    );
  }
  return (
    <div>
      <p className="flex items-center gap-1 text-muted">
        For age {target.age}
        <InfoTip term="forAge" />
      </p>
      {target.extraMonthlyCents === null ? (
        <p className="mt-1 font-medium">More than this plan can reach.</p>
      ) : (
        <Amount cents={target.extraMonthlyCents} className="-ml-2 text-3xl font-bold" />
      )}
      <p className="text-muted">
        {target.extraMonthlyCents !== null && "more a month"}
        {target.extraPerPaycheckCents !== null && paychecksAYear !== null && (
          <>
            {" · "}
            <span data-amount>{formatCents(target.extraPerPaycheckCents)}</span> a paycheck
          </>
        )}
        {" · "}
        <span data-amount>{formatCents(target.shortfallCents)}</span> short
        {target.extraTaxable && " · saved as taxable money, the only kind that bridges the years before 60"}
      </p>
    </div>
  );
}

/** An age, or that none is enough by 100. */
function ageWords(age: number | null): string {
  return age === null ? "not by 100" : String(age);
}

function Stat({ label, cents, tip, children }: { label: string; cents: number | undefined; tip: TermId; children: ReactNode }) {
  return (
    <div>
      <p className="flex items-center gap-1 text-muted">
        {label}
        <InfoTip term={tip} />
      </p>
      <p data-amount className="text-2xl font-bold tabular-nums">
        {cents === undefined ? "—" : formatCents(cents)}
      </p>
      <p className="text-muted">{children}</p>
    </div>
  );
}

/** A card per topic of the chosen scenario, each with its own pencil (§6). */
function ScenarioCards({ scenario, payAYearCents, onOpen }: { scenario: RetirementScenario; payAYearCents: number; onOpen: (open: Open) => void }) {
  const part = (name: ScenarioPart) => () => onOpen({ kind: "part", part: name });
  const settings = scenario.taxSettings;
  const workedOut = settings.mode === "worked_out";
  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
      <Card title="Goal" action={<EditButton name="goal" onClick={part("goal")} />}>
        <dl className="grid grid-cols-2 gap-4">
          <Fact term="Salary a year" tip="retirementSalary">
            <span data-amount>{formatCents(scenario.salaryCents)}</span>
          </Fact>
          <Fact term="Retire at" tip="retireAt">{scenario.retireAge ?? "Not chosen"}</Fact>
          <Fact term="Withdrawal rate" tip="withdrawalRate">
            {formatRate(scenario.withdrawalBp)}
          </Fact>
          <Fact term="Inflation" tip="retirementInflation">
            {formatRate(scenario.inflationBp)} a year
          </Fact>
        </dl>
      </Card>

      <Card title="Taxes & health" action={<EditButton name="taxes and health" onClick={part("health")} />}>
        <dl className="grid grid-cols-2 gap-4">
          <Fact term="Taxes" tip="taxMode">
            {workedOut ? (
              <>
                By the rules
                <span className="block text-sm font-normal text-muted">
                  {settings.filing === "joint" ? "married, jointly" : "single"} · state {formatRate(settings.stateTaxBp)}
                </span>
              </>
            ) : (
              <>{formatRate(scenario.taxBp)} on traditional</>
            )}
          </Fact>
          <Fact term="Health costs rise" tip="retirementHealth">
            {formatRate(scenario.healthInflationBp)} a year
          </Fact>
          <Fact term="Before 65" tip="healthBefore65">
            {scenario.healthBeforeCents === 0 ? "Not counted" : <span data-amount>{formatCents(scenario.healthBeforeCents)} a year</span>}
          </Fact>
          {workedOut ? (
            <Fact term="Growth in taxable withdrawals" tip="gainsShare">
              {formatRate(settings.gainsShareBp)}
            </Fact>
          ) : (
            <Fact term="From 65" tip="healthFrom65">
              {scenario.healthAfterCents === 0 ? "Not counted" : <span data-amount>{formatCents(scenario.healthAfterCents)} a year</span>}
            </Fact>
          )}
        </dl>
      </Card>

      {workedOut && (
        <Card title="Medicare & income" action={<EditButton name="Medicare and income" onClick={part("medicare")} />}>
          <dl className="grid grid-cols-2 gap-4">
            <Fact term="Medicare for" tip="medicarePeople">
              {settings.medicarePeople === 2 ? "You and your spouse" : "You"}
            </Fact>
            <Fact term="Plans a month, each" tip="medigap">
              <span data-amount>{formatCents(settings.partDPlanCents + settings.supplementCents)}</span>
              <span className="block text-sm font-normal text-muted">
                Part D <span data-amount>{formatCents(settings.partDPlanCents)}</span> · Medigap <span data-amount>{formatCents(settings.supplementCents)}</span>
              </span>
            </Fact>
            <Fact term="Income while working" tip="workingMagi">
              <span data-amount>{formatCents(settings.workingMagiCents ?? payAYearCents)}</span>
              {settings.workingMagiCents === null && <span className="block text-sm font-normal text-muted">the pay Budget knows</span>}
            </Fact>
            <Fact term="Income in retirement" tip="magiOverride">
              {settings.magiOverrideCents === null ? "Worked out" : <span data-amount>{formatCents(settings.magiOverrideCents)} a year</span>}
            </Fact>
          </dl>
        </Card>
      )}

      <Card title="Growth & saving" action={<EditButton name="growth and saving" onClick={part("growth")} />}>
        <dl className="grid grid-cols-2 gap-4">
          <Fact term="Growth a year" tip="growthRange">
            {formatRate(scenario.growthBp)}
            <span className="block text-sm font-normal text-muted">
              lower {formatRate(scenario.growthLowBp)} · higher {formatRate(scenario.growthHighBp)}
            </span>
          </Fact>
          <Fact term="Raise a year" tip="yearlyRaise">
            {scenario.raiseBp === 0 ? "None" : formatRate(scenario.raiseBp)}
          </Fact>
          <Fact term="Extra saved a month" tip="extraSaved">
            {scenario.extraMonthlyCents === 0 ? (
              "None"
            ) : (
              <>
                <span data-amount>{formatCents(scenario.extraMonthlyCents)}</span> into {TAX_TYPE_LABELS[scenario.extraInto].toLowerCase()}
              </>
            )}
          </Fact>
        </dl>
      </Card>

      <Card title="Other income" tip="otherIncome">
        {scenario.incomes.length === 0 ? (
          <p className="text-muted">Social Security, a pension: money that starts at an age and takes some of the need away.</p>
        ) : (
          <ul className="divide-y divide-line">
            {scenario.incomes.map((income, index) => (
              <li key={`${income.name}-${index}`} className="flex min-h-11 items-center gap-3 py-1">
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-medium">{income.name}</span>
                  <span className="block text-sm text-muted">from age {income.startAge}</span>
                </span>
                <span data-amount className="shrink-0 tabular-nums">
                  {formatCents(income.yearlyCents)} a year
                </span>
                <EditButton name={income.name} onClick={() => onOpen({ kind: "income", index })} />
              </li>
            ))}
          </ul>
        )}
        {scenario.incomes.length < MAX_INCOMES && (
          <button type="button" onClick={() => onOpen({ kind: "income" })} className={`${BUTTON_LINK} mt-2 -ml-2`}>
            + Add other income
          </button>
        )}
      </Card>

      <Card title="One-offs" tip="retirementOneOff">
        {scenario.oneOffs.length === 0 ? (
          <p className="text-muted">Money in or out once at an age, like selling a house or a new roof.</p>
        ) : (
          <ul className="divide-y divide-line">
            {scenario.oneOffs.map((oneOff, index) => (
              <li key={`${oneOff.name}-${index}`} className="flex min-h-11 items-center gap-3 py-1">
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-medium">{oneOff.name}</span>
                  <span className="block text-sm text-muted">at age {oneOff.age}</span>
                </span>
                <Amount cents={oneOff.amountCents} className="shrink-0" />
                <EditButton name={oneOff.name} onClick={() => onOpen({ kind: "oneOff", index })} />
              </li>
            ))}
          </ul>
        )}
        {scenario.oneOffs.length < MAX_ONE_OFFS && (
          <button type="button" onClick={() => onOpen({ kind: "oneOff" })} className={`${BUTTON_LINK} mt-2 -ml-2`}>
            + Add a one-off
          </button>
        )}
      </Card>
    </div>
  );
}

function Fact({ term, tip, children }: { term: string; tip?: TermId; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="flex items-center gap-1 text-sm text-muted">
        {term}
        {tip && <InfoTip term={tip} />}
      </dt>
      <dd className="font-medium">{children}</dd>
    </div>
  );
}

/** Shared by every scenario: the birth date, and how each investment or savings account is counted. */
function AccountsCounted({
  view,
  onBirthDate,
  onSpouseBirthDate,
}: {
  view: RetirementView;
  onBirthDate: () => void;
  onSpouseBirthDate: () => void;
}) {
  return (
    <Card title="Accounts counted" tip="retirementTaxType">
      <div className="flex min-h-11 items-center gap-3 border-b border-line pb-2">
        <span className="min-w-0 flex-1">
          <span className="block text-sm text-muted">Birth date</span>
          <span className="block font-medium">{view.birthDate === null ? "Not added yet" : formatDate(view.birthDate)}</span>
        </span>
        <EditButton name="birth date" onClick={onBirthDate} />
      </div>
      <div className="flex min-h-11 items-center gap-3 border-b border-line py-2">
        <span className="min-w-0 flex-1">
          <span className="block text-sm text-muted">Spouse's birth date</span>
          <span className="block font-medium">{view.spouseBirthDate === null ? "None" : formatDate(view.spouseBirthDate)}</span>
        </span>
        {/* Kept with yours, so it can only be added once yours is there. */}
        {view.birthDate !== null && <EditButton name="spouse's birth date" onClick={onSpouseBirthDate} />}
      </div>
      {view.accounts.length === 0 ? (
        <p className="mt-3 text-muted">Add an investment or savings account under Accounts, and it can be counted here.</p>
      ) : (
        <ul className="divide-y divide-line">
          {view.accounts.map((account) => (
            <AccountRow key={account.id} account={account} />
          ))}
        </ul>
      )}
      <p className="mt-3 text-sm text-muted">
        Only investment and savings accounts are listed. Each grows at the scenario's growth, not its own assumed growth or APY. Goes in a
        year is what your budget puts into it over the coming year, rising by the scenario's raise every year after. Traditional money counts
        after the scenario's tax; Roth and taxable count in full.
      </p>
    </Card>
  );
}

function AccountRow({ account }: { account: RetirementAccount }) {
  const id = useId();
  const setTaxType = useSetTaxType();
  return (
    <li className="flex flex-col gap-2 py-3 sm:flex-row sm:items-center sm:gap-4">
      <span className="flex min-w-0 flex-1 items-center gap-2">
        <span aria-hidden className="size-3 shrink-0 rounded-full" style={{ backgroundColor: account.color }} />
        <span className="min-w-0">
          <span className="block truncate font-medium">{account.name}</span>
        </span>
      </span>
      {/* Labelled figures rather than a muted line, so what goes in a year can be found and checked (Rachel, 2026-10-20). */}
      <dl className="grid grid-cols-2 gap-x-4 sm:w-72">
        <div>
          <dt className="text-sm text-muted">Balance today</dt>
          <dd data-amount className="font-medium tabular-nums">
            {formatCents(account.balanceCents)}
          </dd>
        </div>
        <div>
          <dt className="flex items-center gap-1 text-sm text-muted">
            Goes in a year
            <InfoTip term="goesInAYear" />
          </dt>
          <dd data-amount className="font-medium tabular-nums">
            {formatCents(account.yearlyInCents)}
          </dd>
        </div>
      </dl>
      <div className="sm:w-56">
        <label htmlFor={id} className="sr-only">
          How {account.name} is counted
        </label>
        <select
          id={id}
          value={account.taxType ?? NOT_COUNTED}
          disabled={setTaxType.isPending}
          onChange={(event) =>
            setTaxType.mutate({ accountId: account.id, taxType: event.target.value === NOT_COUNTED ? null : (event.target.value as TaxType) })
          }
          className={INPUT}
        >
          <option value={NOT_COUNTED}>Not counted</option>
          {TAX_TYPES.map((type) => (
            <option key={type} value={type}>
              {TAX_TYPE_LABELS[type]}
            </option>
          ))}
        </select>
        {setTaxType.error && (
          <p role="alert" className="mt-1 text-sm text-danger">
            {setTaxType.error.message}
          </p>
        )}
      </div>
    </li>
  );
}
