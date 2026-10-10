import { type ReactNode, useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { type Account, useAccounts } from "../api/accounts";
import { type IncomeSource, type PayTerms, useDeletePayChange, useIncomeSources } from "../api/income";
import { Amount } from "../components/Amount";
import { Card } from "../components/Card";
import { EditButton } from "../components/EditButton";
import { IncomeSourceForm } from "../components/income/IncomeSourceForm";
import { describeSchedule, WEEKEND_LABELS } from "../components/income/schedule";
import { UpcomingPaychecks } from "../components/income/UpcomingPaychecks";
import { BUTTON_LINK, BUTTON_PRIMARY, BUTTON_SECONDARY, PAGE_TITLE } from "../components/ui";
import { formatDate } from "../lib/dates";
import { formatCents } from "../lib/money";
import { formatRate } from "../lib/rates";

/** What the form is open for: the source as it is today, one entry of its pay history, or a new change of pay. */
type FormState = { terms?: PayTerms; payChange?: boolean } | null;

/**
 * One income source: its schedule, what each paycheck comes to and where it goes, the
 * employer's match with a nudge when some of it is going unclaimed, its pay history, and the
 * paychecks coming up. The Income screen is the overview; this is where the detail lives.
 */
export function IncomeDetailPage() {
  const id = Number(useParams().id);
  const navigate = useNavigate();
  const sources = useIncomeSources();
  const accounts = useAccounts();
  const [form, setForm] = useState<FormState>(null);

  if (sources.isPending) {
    return <p className="text-muted">Loading…</p>;
  }
  const source = sources.data?.find((candidate) => candidate.id === id);
  if (sources.isError || !source) {
    return (
      <>
        <h1 className={`${PAGE_TITLE} mb-4`}>Income</h1>
        <Card>
          <p role="alert" className="font-medium">
            {sources.isError ? sources.error.message : "We couldn't find that income source. It may have been removed."}
          </p>
          <Link to="/income" className="mt-2 inline-block font-semibold text-accent-ink underline">
            Back to all income
          </Link>
        </Card>
      </>
    );
  }

  const allAccounts = accounts.data ?? [];

  return (
    <>
      <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between md:mb-6">
        <div className="min-w-0">
          <p className="text-sm text-muted">
            <Link to="/income" className="text-accent-ink underline">
              Income
            </Link>{" "}
            / Income source
          </p>
          <h1 className={`${PAGE_TITLE} truncate`}>{source.name}</h1>
          <p className="text-muted">
            {source.primary ? "Primary · its paydays define your pay periods" : "Its paydays do not change your pay periods"}
          </p>
        </div>
        <div className="flex shrink-0 flex-col gap-2 whitespace-nowrap sm:flex-row">
          <button type="button" onClick={() => setForm({})} aria-label={`Edit ${source.name}`} className={BUTTON_SECONDARY}>
            Edit
          </button>
          <button
            type="button"
            onClick={() => setForm({ payChange: true })}
            aria-label={`Record a pay change for ${source.name}`}
            className={BUTTON_PRIMARY}
          >
            Record a pay change
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <div className="space-y-4">
          <SourceCard source={source} accounts={allAccounts} />
          <PayHistory source={source} onEditTerms={(terms) => setForm({ terms })} />
        </div>
        <UpcomingPaychecks sourceId={source.id} />
      </div>

      {form && (
        <IncomeSourceForm
          source={source}
          terms={form.terms}
          startAsPayChange={form.payChange}
          isFirst={false}
          accounts={allAccounts}
          onClose={() => setForm(null)}
          onDeleted={() => navigate("/income")}
        />
      )}
    </>
  );
}

function SourceCard({ source, accounts }: { source: IncomeSource; accounts: Account[] }) {
  const accountName = (id: number | null) => accounts.find((account) => account.id === id)?.name;

  return (
    <section
      aria-label={source.name}
      className={`rounded-card bg-surface p-5 md:p-6 ${source.primary ? "border-2 border-accent" : "border border-line"}`}
    >
      <dl className="grid gap-4 sm:grid-cols-2 2xl:grid-cols-4">
        <Fact term="Schedule">{describeSchedule(source.schedule)}</Fact>
        <Fact term="Weekend or holiday">{WEEKEND_LABELS[source.schedule.weekendRule]}</Fact>
        <Fact term="Deposits to">{accountName(source.depositAccountId) ?? "An account that was removed"}</Fact>
        <Fact term={source.grossCents === null ? "Net per paycheck" : "Gross · net per paycheck"}>
          {source.grossCents !== null && (
            <>
              <Amount cents={source.grossCents} className="-ml-2" /> ·{" "}
            </>
          )}
          <Amount cents={source.netCents} estimate={source.netIsEstimate} className={source.grossCents === null ? "-ml-2" : ""} />
        </Fact>
      </dl>

      {source.deductions.length > 0 && (
        <div className="mt-4">
          <h2 className="border-b border-line pb-2 font-semibold">Goes to your accounts</h2>
          <ul className="divide-y divide-line">
            {source.deductions.map((deduction, index) => (
              // Deductions have no id of their own; their order is their identity.
              <li key={index} className="flex items-center justify-between gap-3 py-2">
                <span className="min-w-0">
                  {deduction.name}
                  {deduction.percentBp !== null && ` · ${formatRate(deduction.percentBp)} of gross pay`}
                  {deduction.toAccountId !== null && ` → ${accountName(deduction.toAccountId) ?? "an account that was removed"}`}
                </span>
                <Amount cents={deduction.amountCents} className="shrink-0 font-semibold" />
              </li>
            ))}
          </ul>
        </div>
      )}

      {source.match && (
        <div className="mt-4">
          <h2 className="border-b border-line pb-2 font-semibold">Employer match</h2>
          <div className="flex items-center justify-between gap-3 py-2">
            <span className="min-w-0">
              {source.match.mode === "fixed"
                ? "The same amount every paycheck"
                : `${formatRate(source.match.matchBp ?? 0)} of what you put in, up to ${formatRate(source.match.capBpOfGross ?? 0)} of gross pay`}
              {` → ${accountName(source.match.toAccountId) ?? "an account that was removed"}`}
            </span>
            {source.matchFigures && <Amount cents={source.matchFigures.perPaycheckCents} className="shrink-0 font-semibold" />}
          </div>
          {source.matchFigures === null && (
            <p className="text-sm text-muted">Add the gross amount per paycheck and the match can be worked out.</p>
          )}
          {source.match.contributionWithinCap && (
            <p className="text-sm text-muted">
              No gross amount is entered, so this takes your word that what you put in is within the{" "}
              {formatRate(source.match.capBpOfGross ?? 0)} of pay that is matched. Add the gross amount and it is checked for you.
            </p>
          )}
          {/* Said in words with the amounts: money the employer would add that is not being claimed. */}
          {source.matchFigures && source.matchFigures.contributeMoreCents > 0 && (
            <p role="status" className="rounded-lg border-l-4 border-accent bg-bg px-4 py-3">
              <span className="font-semibold">Under the cap.</span> Putting in{" "}
              <span data-amount className="font-semibold tabular-nums">
                {formatCents(source.matchFigures.contributeMoreCents)}
              </span>{" "}
              more each paycheck would earn the whole match of{" "}
              <span data-amount className="font-semibold tabular-nums">
                {formatCents(source.matchFigures.mostPossibleCents)}
              </span>
              :{" "}
              <span data-amount className="font-semibold tabular-nums">
                {formatCents(source.matchFigures.mostPossibleCents - source.matchFigures.perPaycheckCents)}
              </span>{" "}
              more from your employer every time.
            </p>
          )}
        </div>
      )}

      {source.netIsEstimate && (
        <p className="mt-4 text-sm text-muted">This amount stays an estimate until you type the real one into a paycheck.</p>
      )}
    </section>
  );
}

/** Every set of amounts the source has had or will have, newest first, each with its own Edit. */
function PayHistory({ source, onEditTerms }: { source: IncomeSource; onEditTerms: (terms: PayTerms) => void }) {
  const remove = useDeletePayChange(source.id);
  return (
    <Card title="Pay history">
      <ul className="divide-y divide-line">
        {[...source.payHistory].reverse().map((entry) => {
          const from = entry.effectiveDate === null ? "the start" : formatDate(entry.effectiveDate);
          return (
            <li key={entry.id ?? "start"} className="flex flex-col gap-1 py-2 sm:flex-row sm:items-center sm:justify-between sm:gap-3">
              <span className="min-w-0">
                <span className="font-medium">From {from}</span>
                {/* Said in words, never by color alone. */}
                <span className="text-sm text-muted">{entry.current ? " · in use now" : isAhead(entry, source) ? " · still to come" : ""}</span>
                <span className="block text-sm text-muted">
                  {entry.grossCents !== null && (
                    <>
                      <span data-amount>{formatCents(entry.grossCents)}</span> gross ·{" "}
                    </>
                  )}
                  <span data-amount>{formatCents(entry.netCents)}</span> take-home a paycheck
                </span>
              </span>
              <span className="flex shrink-0 items-center gap-1">
                <EditButton name={`the pay from ${from}`} onClick={() => onEditTerms(entry)} labelled />
                {entry.id !== null && (
                  <button
                    type="button"
                    disabled={remove.isPending}
                    onClick={() => entry.id !== null && remove.mutate(entry.id)}
                    aria-label={`Remove the pay change from ${from}`}
                    className={BUTTON_LINK}
                  >
                    Remove
                  </button>
                )}
              </span>
            </li>
          );
        })}
      </ul>
      {remove.isError && (
        <p role="alert" className="text-danger">
          {remove.error.message}
        </p>
      )}
      <p className="mt-2 text-sm text-muted">
        {source.payHistory.length < 2
          ? "No pay changes yet. When you get a raise, or your deductions or match change, choose Record a pay change: paychecks before that date keep these amounts."
          : "Each paycheck uses the newest amounts dated on or before its payday. Removing a change lets the amounts before it carry on."}
      </p>
    </Card>
  );
}

/** A change of pay that has not taken effect yet: it is dated after the one in force today. */
function isAhead(entry: PayTerms, source: IncomeSource): boolean {
  const current = source.payHistory.findIndex((candidate) => candidate.current);
  return source.payHistory.indexOf(entry) > current;
}

function Fact({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div>
      <dt className="text-sm text-muted">{term}</dt>
      <dd className="font-semibold">{children}</dd>
    </div>
  );
}
