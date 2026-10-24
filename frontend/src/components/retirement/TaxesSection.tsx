import type { ReactNode } from "react";
import type { DrawdownYear, MedicareYear, RetirementScenario, RetirementTaxes, TaxFiguresView } from "../../api/retirement";
import type { TermId } from "../../help/terms";
import { formatCents } from "../../lib/money";
import { formatRate } from "../../lib/rates";
import { Card } from "../Card";
import { InfoTip } from "../help/InfoTip";
import { BUTTON_SECONDARY } from "../ui";

const WHOLE_DOLLARS = new Intl.NumberFormat("en-US", { style: "currency", currency: "USD", maximumFractionDigits: 0 });

/** Whole dollars for the long tables, where cents only crowd the columns. */
function dollars(cents: number): string {
  return WHOLE_DOLLARS.format(Math.round(cents / 100));
}

const AGE_FROM: Record<RetirementTaxes["ageFrom"], string> = {
  chosen: "the age you chose",
  earliest: "the earliest age that is enough",
  assumed: "as no age is chosen or enough by 100",
};

/**
 * Taxes and Medicare in retirement worked out by the rules (spec §5.12; Rachel, 2026-10-23): every way of taking money
 * out that was tried, best first; Medicare's premium at 65 as a sum that can be followed line by line; and each year's
 * withdrawals, taxes and premiums. Everything is in today's dollars. The tax share and Medicare a year stand in for the
 * flat rate and health from 65 in the figures above.
 */
export function TaxesSection({
  scenario,
  taxes,
  figures,
  onFigures,
}: {
  scenario: RetirementScenario;
  taxes: RetirementTaxes;
  figures: TaxFiguresView;
  onFigures: () => void;
}) {
  const chosen = taxes.strategies.find((outcome) => outcome.code === taxes.chosen) ?? taxes.strategies[0];
  const firstMedicare = taxes.years.find((year) => year.medicare.length > 0);
  const limits = scenario.taxSettings.filing === "joint" ? figures.joint.medicareFromCents : figures.single.medicareFromCents;
  // Every strategy is always tried; nothing to show only if none came back.
  if (chosen === undefined) {
    return null;
  }

  return (
    <Card
      title="Taxes & Medicare in retirement"
      tip="drawdownStrategy"
      action={
        <button type="button" onClick={onFigures} className={BUTTON_SECONDARY}>
          Tax figures
        </button>
      }
    >
      <p className="text-muted">
        Retiring at {taxes.retireAge}, {AGE_FROM[taxes.ageFrom]}, with <span data-amount>{dollars(taxes.traditionalCents)}</span> traditional,{" "}
        <span data-amount>{dollars(taxes.rothCents)}</span> Roth and <span data-amount>{dollars(taxes.taxableCents)}</span> taxable then.
      </p>

      <dl className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Figure label="Money comes from">
          <span className="block text-base leading-snug">{chosen.label}</span>
          <span className="block text-sm font-normal text-muted">{taxes.pinned ? "your choice, not the cheapest" : "the cheapest of 12 ways"}</span>
        </Figure>
        <Figure label="Tax over retirement" tip="retirementTax">
          {formatRate(taxes.taxShareBp)}
          <span className="block text-sm font-normal text-muted">of traditional money taken out; stands in for the flat rate</span>
        </Figure>
        <Figure label="Medicare a year from 65" tip="irmaa">
          <span data-amount>{formatCents(taxes.medicareCents)}</span>
          <span className="block text-sm font-normal text-muted">at today's premiums; stands in for health from 65</span>
        </Figure>
        <Figure label="Federal and state, all told">
          <span data-amount>{dollars(chosen.federalCents + chosen.stateCents)}</span>
          <span className="block text-sm font-normal text-muted">
            {chosen.runsOutAt === null ? (
              <>
                <span data-amount>{dollars(chosen.leftAfterTaxCents)}</span> left at 100, after tax
              </>
            ) : (
              `runs short at ${chosen.runsOutAt}`
            )}
          </span>
        </Figure>
      </dl>

      {firstMedicare && (
        <MedicareSum year={firstMedicare} limits={limits} fasterBp={scenario.healthInflationBp - scenario.inflationBp} />
      )}

      <h3 className="mt-6 mb-2 font-semibold">Every way tried, best first</h3>
      <StrategiesTable taxes={taxes} />

      <details className="mt-6">
        <summary className="min-h-11 cursor-pointer py-2 font-semibold">Year by year</summary>
        <YearsTable years={taxes.years} />
      </details>
    </Card>
  );
}

function Figure({ label, tip, children }: { label: string; tip?: TermId; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="flex items-center gap-1 text-muted">
        {label}
        {tip && <InfoTip term={tip} />}
      </dt>
      <dd className="text-2xl font-bold tabular-nums">{children}</dd>
    </div>
  );
}

/**
 * The first year on Medicare as a sum: the income two years before it goes by, the bracket that puts it in and where
 * the next one starts, then each part of the premium (spec §5.12). Shown for each person on Medicare that year.
 */
function MedicareSum({ year, limits, fasterBp }: { year: DrawdownYear; limits: number[]; fasterBp: number }) {
  return (
    <section aria-label="How Medicare is worked out" className="mt-6 rounded-lg bg-bg p-4">
      <h3 className="font-semibold">Medicare in {year.calendarYear}, how it's worked out</h3>
      {year.medicare.map((person, index) => (
        <MedicarePerson key={index} person={person} lookBackYear={year.calendarYear - 2} limits={limits} spouse={index > 0} />
      ))}
      {fasterBp > 0 && (
        <p className="mt-3 text-sm text-muted">
          In today's dollars, with premiums rising about {formatRate(fasterBp)} a year faster than prices, as health costs rise does. Medicare a year
          above is the same at today's premiums.
        </p>
      )}
    </section>
  );
}

function MedicarePerson({ person, lookBackYear, limits, spouse }: { person: MedicareYear; lookBackYear: number; limits: number[]; spouse: boolean }) {
  const next = limits[person.bracket + 1];
  const monthly = person.partBCents + person.partDCents + person.plansCents;
  return (
    <div className="mt-3">
      {spouse && <p className="font-medium">Your spouse, at {person.age}</p>}
      <p className="text-muted">
        Income in {lookBackYear}: <span data-amount>{formatCents(person.lookBackCents)}</span>.{" "}
        {person.bracket === 0 ? "That pays the standard premium" : `That is income bracket ${person.bracket} of ${limits.length - 1}`}
        {next === undefined ? "." : <>, up to <span data-amount>{formatCents(next)}</span>.</>}
      </p>
      <dl className="mt-2 grid max-w-md grid-cols-[1fr_auto] gap-x-4 gap-y-1 tabular-nums">
        <dt>Part B</dt>
        <dd data-amount className="text-right">
          {formatCents(person.partBCents)}
        </dd>
        <dt>Part D income-related amount</dt>
        <dd data-amount className="text-right">
          {formatCents(person.partDCents)}
        </dd>
        <dt>Your Part D plan and Medigap</dt>
        <dd data-amount className="text-right">
          {formatCents(person.plansCents)}
        </dd>
        <dt className="border-t border-line pt-1 font-semibold">A month</dt>
        <dd data-amount className="border-t border-line pt-1 text-right font-semibold">
          {formatCents(monthly)}
        </dd>
        <dt className="font-semibold">A year</dt>
        <dd data-amount className="text-right font-semibold">
          {formatCents(person.yearlyCents)}
        </dd>
      </dl>
    </div>
  );
}

/** Every strategy tried: a table on wider screens, a card each on a phone (§6). */
function StrategiesTable({ taxes }: { taxes: RetirementTaxes }) {
  const lasts = (runsOutAt: number | null) => (runsOutAt === null ? "Lasts to 100" : `Short at ${runsOutAt}`);
  return (
    <>
      <table className="hidden w-full text-left tabular-nums md:table">
        <thead className="text-sm text-muted">
          <tr className="border-b border-line">
            <th className="py-2 pr-3 font-medium">Way</th>
            <th className="py-2 pr-3 text-right font-medium">Federal</th>
            <th className="py-2 pr-3 text-right font-medium">State</th>
            <th className="py-2 pr-3 text-right font-medium">Medicare</th>
            <th className="py-2 pr-3 text-right font-medium">Of it, income-related</th>
            <th className="py-2 pr-3 font-medium">Money</th>
            <th className="py-2 text-right font-medium">Left at 100</th>
          </tr>
        </thead>
        <tbody>
          {taxes.strategies.map((outcome) => (
            <tr key={outcome.code} className={`border-b border-line ${outcome.code === taxes.chosen ? "bg-accent/10 font-semibold" : ""}`}>
              <td className="py-2 pr-3">
                {outcome.label}
                {outcome.code === taxes.chosen && <span className="ml-2 text-sm text-accent-ink">In use</span>}
              </td>
              <td data-amount className="py-2 pr-3 text-right">
                {dollars(outcome.federalCents)}
              </td>
              <td data-amount className="py-2 pr-3 text-right">
                {dollars(outcome.stateCents)}
              </td>
              <td data-amount className="py-2 pr-3 text-right">
                {dollars(outcome.medicareCents)}
              </td>
              <td data-amount className="py-2 pr-3 text-right">
                {dollars(outcome.surchargesCents)}
              </td>
              <td className="py-2 pr-3 whitespace-nowrap">{lasts(outcome.runsOutAt)}</td>
              <td data-amount className="py-2 text-right">
                {dollars(outcome.leftAfterTaxCents)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <ul className="space-y-2 md:hidden">
        {taxes.strategies.map((outcome) => (
          <li
            key={outcome.code}
            className={`rounded-lg border p-3 ${outcome.code === taxes.chosen ? "border-accent bg-accent/10" : "border-line"}`}
          >
            <p className="font-medium">
              {outcome.label}
              {outcome.code === taxes.chosen && <span className="ml-2 text-sm text-accent-ink">In use</span>}
            </p>
            <dl className="mt-1 grid grid-cols-2 gap-x-3 text-sm tabular-nums">
              <dt className="text-muted">Federal and state</dt>
              <dd data-amount className="text-right">
                {dollars(outcome.federalCents + outcome.stateCents)}
              </dd>
              <dt className="text-muted">Medicare</dt>
              <dd data-amount className="text-right">
                {dollars(outcome.medicareCents)}
              </dd>
              <dt className="text-muted">{lasts(outcome.runsOutAt)}</dt>
              <dd data-amount className="text-right">
                {dollars(outcome.leftAfterTaxCents)} left
              </dd>
            </dl>
          </li>
        ))}
      </ul>
    </>
  );
}

/** Each year of the strategy in use: a table on wider screens, a card a year on a phone (§6). */
function YearsTable({ years }: { years: DrawdownYear[] }) {
  const medicare = (year: DrawdownYear) => year.medicare.reduce((sum, person) => sum + person.yearlyCents, 0);
  const left = (year: DrawdownYear) => year.traditionalLeftCents + year.rothLeftCents + year.taxableLeftCents;
  return (
    <>
      <div className="hidden overflow-x-auto md:block">
        <table className="w-full text-left text-sm tabular-nums">
          <thead className="text-muted">
            <tr className="border-b border-line">
              <th className="py-2 pr-3 font-medium">Age</th>
              <th className="py-2 pr-3 text-right font-medium">Needs</th>
              <th className="py-2 pr-3 text-right font-medium">Other income</th>
              <th className="py-2 pr-3 text-right font-medium">Traditional</th>
              <th className="py-2 pr-3 text-right font-medium">Taxable</th>
              <th className="py-2 pr-3 text-right font-medium">Roth</th>
              <th className="py-2 pr-3 text-right font-medium">To Roth</th>
              <th className="py-2 pr-3 text-right font-medium">Income for Medicare</th>
              <th className="py-2 pr-3 text-right font-medium">Federal</th>
              <th className="py-2 pr-3 text-right font-medium">State</th>
              <th className="py-2 pr-3 text-right font-medium">Medicare</th>
              <th className="py-2 text-right font-medium">Left</th>
            </tr>
          </thead>
          <tbody>
            {years.map((year) => (
              <tr key={year.age} className="border-b border-line">
                <td className="py-1.5 pr-3">
                  {year.age}
                  <span className="block text-muted">{year.calendarYear}</span>
                </td>
                <Cell cents={year.needCents} />
                <Cell cents={year.otherIncomeCents} />
                <td data-amount className="py-1.5 pr-3 text-right">
                  {dollars(year.fromTraditionalCents)}
                  {year.requiredCents > 0 && <span className="block whitespace-nowrap text-muted">{dollars(year.requiredCents)} required</span>}
                </td>
                <Cell cents={year.fromTaxableCents} />
                <Cell cents={year.fromRothCents} />
                <Cell cents={year.toRothCents} />
                <Cell cents={year.magiCents} />
                <td data-amount className="py-1.5 pr-3 text-right">
                  {dollars(year.federalCents)}
                  {year.federalCents > 0 && <span className="block whitespace-nowrap text-muted">{formatRate(year.bracketBp)} bracket</span>}
                </td>
                <Cell cents={year.stateCents} />
                <Cell cents={medicare(year)} />
                <td data-amount className="py-1.5 text-right">
                  {dollars(left(year))}
                  {year.shortfallCents > 0 && <span className="block text-danger">{dollars(year.shortfallCents)} short</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <ul className="space-y-2 md:hidden">
        {years.map((year) => (
          <li key={year.age} className="rounded-lg border border-line p-3">
            <p className="font-medium">
              At {year.age} <span className="font-normal text-muted">· {year.calendarYear}</span>
            </p>
            <dl className="mt-1 grid grid-cols-2 gap-x-3 text-sm tabular-nums">
              <Row label="Needs" cents={year.needCents} />
              <Row label="Other income" cents={year.otherIncomeCents} />
              <Row label="From traditional" cents={year.fromTraditionalCents} />
              <Row label="From taxable" cents={year.fromTaxableCents} />
              <Row label="From Roth" cents={year.fromRothCents} />
              {year.toRothCents > 0 && <Row label="Moved to Roth" cents={year.toRothCents} />}
              <Row label="Taxes" cents={year.federalCents + year.stateCents} />
              <Row label="Medicare" cents={medicare(year)} />
              <Row label="Left" cents={left(year)} />
              {year.shortfallCents > 0 && <Row label="Short" cents={year.shortfallCents} />}
            </dl>
          </li>
        ))}
      </ul>
    </>
  );
}

function Cell({ cents }: { cents: number }) {
  return (
    <td data-amount className="py-1.5 pr-3 text-right">
      {dollars(cents)}
    </td>
  );
}

function Row({ label, cents }: { label: string; cents: number }) {
  return (
    <>
      <dt className="text-muted">{label}</dt>
      <dd data-amount className="text-right">
        {dollars(cents)}
      </dd>
    </>
  );
}
