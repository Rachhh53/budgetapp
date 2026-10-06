import { useState } from "react";
import { Link } from "react-router";
import { type Account, useAccounts } from "../api/accounts";
import { AccountForm } from "../components/accounts/AccountForm";
import { ACCOUNT_GROUPS, accountSubtitle } from "../components/accounts/labels";
import { Amount } from "../components/Amount";
import { Card } from "../components/Card";
import { BUTTON_PRIMARY, PAGE_TITLE } from "../components/ui";
import { formatDay } from "../lib/dates";

/**
 * Accounts (design/accounts.png): every account grouped by kind, each with the last balance
 * the user entered. Net worth, buckets, rates and home value join this screen in Phases 3–5.
 */
export function AccountsPage() {
  const accounts = useAccounts();
  const [adding, setAdding] = useState(false);

  const all = accounts.data ?? [];
  const active = all.filter((account) => !account.archived);
  const archived = all.filter((account) => account.archived);

  return (
    <>
      <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between md:mb-6">
        <div>
          <p className="text-sm font-semibold tracking-wide text-muted uppercase">Everything you own and owe</p>
          <h1 className={PAGE_TITLE}>Accounts</h1>
        </div>
        <button type="button" onClick={() => setAdding(true)} className={BUTTON_PRIMARY}>
          + Add account
        </button>
      </div>

      {accounts.isPending && <p className="text-muted">Loading your accounts…</p>}
      {accounts.isError && (
        <p role="alert" className="text-danger">
          {accounts.error.message}
        </p>
      )}

      {accounts.data && all.length === 0 && (
        <Card>
          <p className="font-medium">No accounts yet.</p>
          <p className="mt-1 text-muted">
            Add the accounts you'd like to keep an eye on — checking, savings, cards, investments or loans. Nothing
            connects to your bank; you enter the balances yourself.
          </p>
        </Card>
      )}

      {active.length > 0 && (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          {ACCOUNT_GROUPS.map(({ title, types }) => {
            const group = active.filter((account) => types.includes(account.type));
            return group.length > 0 && <AccountGroup key={title} title={title} accounts={group} />;
          })}
        </div>
      )}

      {archived.length > 0 && (
        <details className="mt-4 rounded-card border border-line bg-surface p-5">
          <summary className="min-h-11 cursor-pointer content-center font-semibold">
            Archived accounts ({archived.length})
          </summary>
          <ul className="mt-2 divide-y divide-line">
            {archived.map((account) => (
              <AccountRow key={account.id} account={account} />
            ))}
          </ul>
        </details>
      )}

      {adding && <AccountForm accounts={all} onClose={() => setAdding(false)} />}
    </>
  );
}

function AccountGroup({ title, accounts }: { title: string; accounts: Account[] }) {
  const headingId = `group-${title.replace(/\W+/g, "-").toLowerCase()}`;
  // Only accounts with a balance count; adding in cents keeps the total exact.
  const total = accounts.reduce((sum, account) => sum + (account.latestBalance?.amountCents ?? 0), 0);
  return (
    <section aria-labelledby={headingId} className="rounded-card border border-line bg-surface p-5 md:p-6">
      <div className="flex items-baseline justify-between gap-3 border-b border-line pb-3">
        <h2 id={headingId} className="text-lg font-semibold">
          {title}
        </h2>
        <Amount cents={total} className="text-muted" />
      </div>
      <ul className="divide-y divide-line">
        {accounts.map((account) => (
          <AccountRow key={account.id} account={account} />
        ))}
      </ul>
    </section>
  );
}

function AccountRow({ account }: { account: Account }) {
  return (
    <li>
      <Link to={`/accounts/${account.id}`} className="flex min-h-11 items-center gap-3 py-3">
        <span aria-hidden className="size-3 shrink-0 rounded-full" style={{ backgroundColor: account.color }} />
        <span className="min-w-0 flex-1">
          <span className="block truncate font-semibold">{account.name}</span>
          <span className="block truncate text-sm text-muted">{accountSubtitle(account)}</span>
        </span>
        <span className="shrink-0 text-right">
          {account.latestBalance ? (
            <>
              <Amount cents={account.latestBalance.amountCents} className="font-semibold" />
              <span className="block pr-2 text-sm text-muted">entered {formatDay(account.latestBalance.date)}</span>
            </>
          ) : (
            <span className="text-sm text-muted">No balance yet</span>
          )}
        </span>
      </Link>
    </li>
  );
}
