import { useState } from "react";
import { Link } from "react-router";
import { type Account, useAccounts } from "../api/accounts";
import { AccountForm } from "../components/accounts/AccountForm";
import { ACCOUNT_GROUPS, accountSubtitle } from "../components/accounts/labels";
import { Amount } from "../components/Amount";
import { Card } from "../components/Card";
import { EditButton } from "../components/EditButton";
import { BUTTON_PRIMARY, PAGE_TITLE } from "../components/ui";
import { formatDay } from "../lib/dates";

/**
 * Accounts (design/accounts.png): every account grouped by kind, each with the last balance
 * the user entered. Net worth, buckets, rates and home value join this screen in Phases 3–5.
 */
export function AccountsPage() {
  const accounts = useAccounts();
  const [adding, setAdding] = useState(false);
  // Kept as an id, so the open form always shows the account as it is now.
  const [editingId, setEditingId] = useState<number>();

  const all = accounts.data ?? [];
  const active = all.filter((account) => !account.archived);
  const archived = all.filter((account) => account.archived);
  const editing = all.find((account) => account.id === editingId);
  const groups = ACCOUNT_GROUPS.map(({ title, types }) => ({ title, accounts: active.filter((account) => types.includes(account.type)) })).filter(
    (group) => group.accounts.length > 0,
  );

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
          {groups.map((group, index) => (
            <AccountGroup
              key={group.title}
              title={group.title}
              accounts={group.accounts}
              onEdit={setEditingId}
              // A group left alone on the last row takes the whole width instead of sitting in half of it.
              wide={groups.length % 2 === 1 && index === groups.length - 1}
            />
          ))}
        </div>
      )}

      {archived.length > 0 && (
        <details className="mt-4 rounded-card border border-line bg-surface p-5">
          <summary className="min-h-11 cursor-pointer content-center font-semibold">
            Archived accounts ({archived.length})
          </summary>
          <ul className="mt-2 divide-y divide-line">
            {archived.map((account) => (
              <AccountRow key={account.id} account={account} onEdit={setEditingId} />
            ))}
          </ul>
        </details>
      )}

      {adding && <AccountForm accounts={all} onClose={() => setAdding(false)} />}
      {editing && <AccountForm account={editing} accounts={all} onClose={() => setEditingId(undefined)} />}
    </>
  );
}

function AccountGroup({
  title,
  accounts,
  onEdit,
  wide,
}: {
  title: string;
  accounts: Account[];
  onEdit: (id: number) => void;
  wide: boolean;
}) {
  const headingId = `group-${title.replace(/\W+/g, "-").toLowerCase()}`;
  // Only accounts with a balance count; adding in cents keeps the total exact.
  const total = accounts.reduce((sum, account) => sum + (account.latestBalance?.amountCents ?? 0), 0);
  return (
    <section aria-labelledby={headingId} className={`rounded-card border border-line bg-surface p-5 md:p-6 ${wide ? "lg:col-span-2" : ""}`}>
      <div className="flex items-baseline justify-between gap-3 border-b border-line pb-3">
        <h2 id={headingId} className="text-lg font-semibold">
          {title}
        </h2>
        <Amount cents={total} className="text-muted" />
      </div>
      <ul className="divide-y divide-line">
        {accounts.map((account) => (
          <AccountRow key={account.id} account={account} onEdit={onEdit} />
        ))}
      </ul>
    </section>
  );
}

function AccountRow({ account, onEdit }: { account: Account; onEdit: (id: number) => void }) {
  return (
    <li className="flex items-center gap-2">
      <Link to={`/accounts/${account.id}`} className="flex min-h-11 min-w-0 flex-1 items-center gap-3 py-3">
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
            <span className="text-sm text-muted">No balance entered · built from $0</span>
          )}
        </span>
      </Link>
      <EditButton name={account.name} onClick={() => onEdit(account.id)} labelled />
    </li>
  );
}
