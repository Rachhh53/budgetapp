import { useId, useState } from "react";
import { Link } from "react-router";
import { type Account, useAccounts } from "../api/accounts";
import { type IncomeSource, useIncomeSources } from "../api/income";
import { Amount } from "../components/Amount";
import { Card } from "../components/Card";
import { EditButton } from "../components/EditButton";
import { IncomeSourceForm } from "../components/income/IncomeSourceForm";
import { describeSchedule } from "../components/income/schedule";
import { UpcomingPaychecks } from "../components/income/UpcomingPaychecks";
import { BUTTON_PRIMARY, PAGE_TITLE, ROW_LINK } from "../components/ui";

/** What the form is open for: a new source, an existing one, or closed. */
type FormState = { source?: IncomeSource } | null;

/**
 * Income (design/income.png), as an overview like the Accounts screen: every source on one
 * row with what a paycheck brings home, and the paychecks coming up. A row opens the source's
 * own page, where its deductions, employer match and pay history live. Pay stubs join this
 * screen in Phase 9.
 */
export function IncomePage() {
  const sources = useIncomeSources();
  const accounts = useAccounts();
  const [form, setForm] = useState<FormState>(null);
  const headingId = useId();

  const allAccounts = accounts.data ?? [];
  const needsAccount = accounts.data !== undefined && allAccounts.every((account) => account.archived);

  return (
    <>
      <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between md:mb-6">
        <div>
          <p className="text-sm font-semibold tracking-wide text-muted uppercase">Money coming in</p>
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
              <Link to="/accounts" className="font-semibold text-accent-ink underline">
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
          {sources.data && sources.data.length > 0 && (
            <section aria-labelledby={headingId} className="rounded-card border border-line bg-surface p-5 md:p-6">
              <h2 id={headingId} className="border-b border-line pb-3 text-lg font-semibold">
                Income sources
              </h2>
              <ul className="divide-y divide-line">
                {sources.data.map((source) => (
                  <SourceRow key={source.id} source={source} accounts={allAccounts} onEdit={() => setForm({ source })} />
                ))}
              </ul>
            </section>
          )}
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

/** One source on the overview: who pays, when, into which account, and what a paycheck brings home. */
function SourceRow({ source, accounts, onEdit }: { source: IncomeSource; accounts: Account[]; onEdit: () => void }) {
  const account = accounts.find((candidate) => candidate.id === source.depositAccountId)?.name ?? "an account that was removed";

  return (
    <li className="flex items-center gap-2">
      <Link to={`/income/${source.id}`} className={ROW_LINK}>
        <span className="min-w-0 flex-1">
          <span className="block font-semibold sm:truncate">{source.name}</span>
          <span className="block text-sm text-muted sm:truncate">
            {describeSchedule(source.schedule)} · into {account}
          </span>
          {/* In words, never by color alone: this is the source whose paydays set the pay periods. */}
          {source.primary && <span className="block text-sm font-semibold text-accent-ink">Primary · defines pay periods</span>}
        </span>
        <span className="shrink-0 text-right">
          <Amount cents={source.netCents} estimate={source.netIsEstimate} className="font-semibold" />
          {/* The short form on a phone, where the longer one squeezed the name into a sliver. */}
          <span className="block pr-2 text-sm text-muted">
            <span className="sm:hidden">take-home</span>
            <span className="hidden sm:inline">take-home a paycheck</span>
          </span>
        </span>
      </Link>
      <EditButton name={source.name} onClick={onEdit} />
    </li>
  );
}
