import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { type BalanceEntry, useAccount, useAccounts, useDeleteBalance } from "../api/accounts";
import { AccountForm } from "../components/accounts/AccountForm";
import { BalanceForm } from "../components/accounts/BalanceForm";
import { accountSubtitle, TYPE_LABELS } from "../components/accounts/labels";
import { Amount } from "../components/Amount";
import { Card } from "../components/Card";
import { BUTTON_LINK, BUTTON_PRIMARY, BUTTON_SECONDARY, PAGE_TITLE } from "../components/ui";
import { formatDate } from "../lib/dates";
import { formatCents } from "../lib/money";

/* One row of "Balances you entered": stacked on a phone, columns from `md` up. */
const ROW = "grid grid-cols-2 gap-x-4 gap-y-1 py-3 md:grid-cols-[9rem_9rem_9rem_1fr_auto] md:items-center";

/**
 * Account detail (design/account-detail.png). Phase 1 has the balance and the balances the
 * user entered; the chart, projection, buckets, interest and rate history arrive in Phase 3.
 */
export function AccountDetailPage() {
  const id = Number(useParams().id);
  const navigate = useNavigate();
  const detail = useAccount(id);
  const accounts = useAccounts();
  const [editing, setEditing] = useState(false);
  const [updating, setUpdating] = useState(false);

  if (detail.isPending) {
    return <p className="text-muted">Loading this account…</p>;
  }
  if (detail.isError) {
    return (
      <>
        <h1 className={`${PAGE_TITLE} mb-4`}>Accounts</h1>
        <Card>
          <p role="alert" className="font-medium">
            {detail.error.message}
          </p>
          <Link to="/accounts" className="mt-2 inline-block font-semibold text-accent underline">
            Back to all accounts
          </Link>
        </Card>
      </>
    );
  }

  const { account, balances } = detail.data;
  const latest = account.latestBalance;

  return (
    <>
      <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between md:mb-6">
        <div className="min-w-0">
          <p className="text-sm text-muted">
            <Link to="/accounts" className="text-accent underline">
              Accounts
            </Link>{" "}
            / {TYPE_LABELS[account.type]}
          </p>
          <h1 className={`${PAGE_TITLE} flex items-center gap-3`}>
            <span aria-hidden className="size-4 shrink-0 rounded-full" style={{ backgroundColor: account.color }} />
            <span className="truncate">{account.name}</span>
          </h1>
          <p className="text-muted">
            {accountSubtitle(account)}
            {account.archived && " · Archived"}
          </p>
        </div>
        <div className="flex flex-col gap-2 sm:flex-row">
          <button type="button" onClick={() => setEditing(true)} className={BUTTON_SECONDARY}>
            Edit account
          </button>
          <button type="button" onClick={() => setUpdating(true)} className={BUTTON_PRIMARY}>
            Update balance
          </button>
        </div>
      </div>

      <div className="space-y-4">
        <Card>
          <p className="text-muted">Balance</p>
          {latest ? (
            <>
              <p className="text-3xl font-bold tabular-nums">{formatCents(latest.amountCents)}</p>
              <p className="text-muted">entered by you {formatDate(latest.date)}</p>
            </>
          ) : (
            <p className="mt-1 font-medium">No balance entered yet.</p>
          )}
        </Card>

        <Card title="Balances you entered">
          {balances.length === 0 ? (
            <p className="text-muted">
              Nothing here yet. Choose "Update balance" and type in what your bank shows — projections start from the
              latest number you enter.
            </p>
          ) : (
            <BalanceList accountId={account.id} balances={balances} />
          )}
        </Card>
      </div>

      {editing && (
        <AccountForm
          account={account}
          accounts={accounts.data ?? []}
          onClose={() => setEditing(false)}
          onDeleted={() => navigate("/accounts")}
        />
      )}
      {updating && <BalanceForm account={account} onClose={() => setUpdating(false)} />}
    </>
  );
}

function BalanceList({ accountId, balances }: { accountId: number; balances: BalanceEntry[] }) {
  const remove = useDeleteBalance(accountId);
  return (
    <>
      <div aria-hidden className={`${ROW} hidden border-b border-line pt-0 text-sm font-semibold text-muted md:grid`}>
        <span>Date</span>
        <span className="text-right">Balance</span>
        <span className="text-right">Change</span>
        <span>Note</span>
        <span />
      </div>
      <ul className="divide-y divide-line">
        {balances.map((entry, index) => {
          // Newest first, so the entry before this one in time is the next in the list.
          const previous = balances[index + 1];
          const change = previous ? entry.amountCents - previous.amountCents : null;
          return (
            <li key={entry.id} className={ROW}>
              <span className="font-medium">{formatDate(entry.date)}</span>
              <span className="text-right">
                <Amount cents={entry.amountCents} className="font-semibold" />
              </span>
              <span className="text-muted md:sr-only">Change since the entry before</span>
              <span className="pr-2 text-right tabular-nums">
                {change === null ? "—" : `${change > 0 ? "+" : ""}${formatCents(change)}`}
              </span>
              <span className="col-span-2 min-w-0 text-muted md:col-span-1">{entry.note}</span>
              <span className="col-span-2 text-right md:col-span-1">
                <button
                  type="button"
                  disabled={remove.isPending}
                  onClick={() => remove.mutate(entry.id)}
                  aria-label={`Remove the balance from ${formatDate(entry.date)}`}
                  className={BUTTON_LINK}
                >
                  Remove
                </button>
              </span>
            </li>
          );
        })}
      </ul>
      {remove.isError && (
        <p role="alert" className="mt-3 text-danger">
          {remove.error.message}
        </p>
      )}
    </>
  );
}
