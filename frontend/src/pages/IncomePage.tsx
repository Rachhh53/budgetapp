import { type ReactNode, useState } from "react";
import { Link } from "react-router";
import { type Account, useAccounts } from "../api/accounts";
import { type IncomeSource, useIncomeSources } from "../api/income";
import { Amount } from "../components/Amount";
import { Card } from "../components/Card";
import { IncomeSourceForm } from "../components/income/IncomeSourceForm";
import { describeSchedule, WEEKEND_LABELS } from "../components/income/schedule";
import { UpcomingPaychecks } from "../components/income/UpcomingPaychecks";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, PAGE_TITLE } from "../components/ui";

/** What the form is open for: a new source, an existing one, or closed. */
type FormState = { source?: IncomeSource } | null;

/**
 * Income (design/income.png): each source with its schedule and where the money goes, and
 * the paychecks coming up. Pay stubs and employer match join this screen in Phases 4 and 9.
 */
export function IncomePage() {
  const sources = useIncomeSources();
  const accounts = useAccounts();
  const [form, setForm] = useState<FormState>(null);

  const allAccounts = accounts.data ?? [];
  const needsAccount = accounts.data !== undefined && allAccounts.every((account) => account.archived);

  return (
    <>
      <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between md:mb-6">
        <div>
          <p className="text-sm font-semibold tracking-wide text-muted uppercase">Setup</p>
          <h1 className={PAGE_TITLE}>Income</h1>
        </div>
        <button type="button" disabled={needsAccount} onClick={() => setForm({})} className={BUTTON_PRIMARY}>
          + Add income source
        </button>
      </div>

      {needsAccount && (
        <div className="mb-4">
          <Card>
            <p className="font-medium">Add an account first.</p>
            <p className="mt-1 text-muted">
              A paycheck needs somewhere to land.{" "}
              <Link to="/accounts" className="font-semibold text-accent underline">
                Add the account your pay is deposited to
              </Link>
              , then come back here.
            </p>
          </Card>
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <div className="space-y-4">
          {sources.isPending && <p className="text-muted">Loading your income…</p>}
          {sources.isError && (
            <p role="alert" className="text-danger">
              {sources.error.message}
            </p>
          )}
          {sources.data?.length === 0 && (
            <Card>
              <p className="font-medium">No income yet.</p>
              <p className="mt-1 text-muted">
                Add your main paycheck to get started. Its paydays set your pay periods — one view per paycheck.
              </p>
            </Card>
          )}
          {sources.data?.map((source) => (
            <SourceCard key={source.id} source={source} accounts={allAccounts} onEdit={() => setForm({ source })} />
          ))}
        </div>
        <UpcomingPaychecks />
      </div>

      {form && (
        <IncomeSourceForm
          source={form.source}
          isFirst={sources.data?.length === 0}
          accounts={allAccounts}
          onClose={() => setForm(null)}
        />
      )}
    </>
  );
}

function SourceCard({ source, accounts, onEdit }: { source: IncomeSource; accounts: Account[]; onEdit: () => void }) {
  const accountName = (id: number | null) => accounts.find((account) => account.id === id)?.name;

  return (
    <section
      aria-label={source.name}
      className={`rounded-card bg-surface p-5 md:p-6 ${source.primary ? "border-2 border-accent" : "border border-line"}`}
    >
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1">
          <h2 className="truncate text-xl font-bold">{source.name}</h2>
          {source.primary && (
            <span className="rounded-md bg-accent px-2 py-0.5 text-xs font-bold tracking-wide text-on-accent uppercase">
              Primary · defines pay periods
            </span>
          )}
        </div>
        <button type="button" onClick={onEdit} aria-label={`Edit ${source.name}`} className={BUTTON_SECONDARY}>
          Edit
        </button>
      </div>

      <dl className="mt-4 grid gap-4 sm:grid-cols-2 2xl:grid-cols-4">
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
          <h3 className="border-b border-line pb-2 font-semibold">Goes to your accounts</h3>
          <ul className="divide-y divide-line">
            {source.deductions.map((deduction, index) => (
              // Deductions have no id of their own; their order is their identity.
              <li key={index} className="flex items-center justify-between gap-3 py-2">
                <span className="min-w-0 truncate">
                  {deduction.name}
                  {deduction.toAccountId !== null && ` → ${accountName(deduction.toAccountId) ?? "an account that was removed"}`}
                </span>
                <Amount cents={deduction.amountCents} className="shrink-0 font-semibold" />
              </li>
            ))}
          </ul>
        </div>
      )}

      {source.netIsEstimate && (
        <p className="mt-4 text-sm text-muted">
          This amount stays an estimate until you type the real one into a paycheck.
        </p>
      )}
    </section>
  );
}

function Fact({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-sm text-muted">{term}</dt>
      <dd className="font-semibold">{children}</dd>
    </div>
  );
}
