import { useState } from "react";
import { Link } from "react-router";
import { type Account, holdsCash, useAccounts } from "../api/accounts";
import { type Asset, type NetWorth, useAssets, useNetWorth } from "../api/assets";
import { type AccountBalance, useAccountBalances } from "../api/periods";
import { AccountForm } from "../components/accounts/AccountForm";
import { ACCOUNT_GROUPS, accountSubtitle } from "../components/accounts/labels";
import { UpdateBalancesForm } from "../components/accounts/UpdateBalancesForm";
import { Amount } from "../components/Amount";
import { AssetForm } from "../components/assets/AssetForm";
import { ASSET_KIND_LABELS } from "../components/assets/labels";
import { Card } from "../components/Card";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, PAGE_TITLE, ROW_LINK } from "../components/ui";
import { formatDay } from "../lib/dates";
import { formatCents } from "../lib/money";

const GROUP = "rounded-card border border-line bg-surface p-5 md:p-6";

/**
 * Accounts (design/accounts.png): net worth, everything owned and everything owed, then every
 * account grouped by kind and the homes and other assets, each with what it stands at today.
 */
export function AccountsPage() {
  const accounts = useAccounts();
  const assets = useAssets();
  const netWorth = useNetWorth();
  const [adding, setAdding] = useState(false);
  const [addingAsset, setAddingAsset] = useState(false);
  const [updating, setUpdating] = useState(false);

  const all = accounts.data ?? [];
  const active = all.filter((account) => !account.archived);
  const archived = all.filter((account) => account.archived);
  const groups = ACCOUNT_GROUPS.map(({ title, types }) => ({ title, accounts: active.filter((account) => types.includes(account.type)) })).filter(
    (group) => group.accounts.length > 0,
  );

  const everyAsset = assets.data ?? [];
  const owned = everyAsset.filter((asset) => !asset.archived);
  const archivedAssets = everyAsset.filter((asset) => asset.archived);
  const nothingYet = accounts.data !== undefined && assets.data !== undefined && all.length === 0 && everyAsset.length === 0;
  // The assets group is one more card in the grid, so it counts when deciding which card is left alone on the last row.
  const cards = groups.length + (owned.length > 0 ? 1 : 0);

  return (
    <>
      <div className="mb-4 flex flex-col gap-3 lg:flex-row lg:items-end lg:justify-between md:mb-6">
        <div>
          <p className="text-sm font-semibold tracking-wide text-muted uppercase">Everything you own and owe</p>
          <h1 className={PAGE_TITLE}>Accounts</h1>
        </div>
        <div className="flex flex-col gap-2 sm:flex-row">
          {active.length > 0 && (
            <button type="button" onClick={() => setUpdating(true)} className={BUTTON_SECONDARY}>
              Update balances
            </button>
          )}
          <button type="button" onClick={() => setAddingAsset(true)} className={BUTTON_SECONDARY}>
            + Add home or asset
          </button>
          <button type="button" onClick={() => setAdding(true)} className={BUTTON_PRIMARY}>
            + Add account
          </button>
        </div>
      </div>

      {accounts.isPending && <p className="text-muted">Loading your accounts…</p>}
      {accounts.isError && (
        <p role="alert" className="text-danger">
          {accounts.error.message}
        </p>
      )}
      {assets.isError && (
        <p role="alert" className="text-danger">
          {assets.error.message}
        </p>
      )}

      {nothingYet && (
        <Card>
          <p className="font-medium">No accounts yet.</p>
          <p className="mt-1 text-muted">
            Add the accounts you'd like to keep an eye on — checking, savings, cards, investments or loans — and a home or anything
            else you own. Nothing connects to your bank; you enter the balances yourself.
          </p>
        </Card>
      )}

      {!nothingYet && netWorth.isError && (
        <p role="alert" className="mb-4 text-danger">
          {netWorth.error.message}
        </p>
      )}
      {!nothingYet && netWorth.data && <NetWorthSummary worth={netWorth.data} />}

      {cards > 0 && (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          {groups.map((group, index) => (
            <AccountGroup
              key={group.title}
              title={group.title}
              accounts={group.accounts}
              // A group left alone on the last row takes the whole width instead of sitting in half of it.
              wide={cards % 2 === 1 && index === cards - 1}
            />
          ))}
          {owned.length > 0 && <AssetGroup assets={owned} wide={cards % 2 === 1} />}
        </div>
      )}

      {(archived.length > 0 || archivedAssets.length > 0) && (
        <details className="mt-4 rounded-card border border-line bg-surface p-5">
          <summary className="min-h-11 cursor-pointer content-center font-semibold">
            {archivedAssets.length === 0 ? "Archived accounts" : archived.length === 0 ? "Archived assets" : "Archived accounts and assets"} ({archived.length + archivedAssets.length})
          </summary>
          <ul className="mt-2 divide-y divide-line">
            {archived.map((account) => (
              <AccountRow key={account.id} account={account} today={undefined} />
            ))}
            {archivedAssets.map((asset) => (
              <AssetRow key={asset.id} asset={asset} />
            ))}
          </ul>
        </details>
      )}

      {adding && <AccountForm accounts={all} onClose={() => setAdding(false)} />}
      {updating && <UpdateBalancesForm accounts={all} onClose={() => setUpdating(false)} />}
      {addingAsset && <AssetForm assets={everyAsset} accounts={all} onClose={() => setAddingAsset(false)} />}
    </>
  );
}

/** Net worth with what it is made of, in words as well as figures so the sum can be followed. */
function NetWorthSummary({ worth }: { worth: NetWorth }) {
  return (
    <section aria-label="Net worth" className={`${GROUP} mb-4`}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <div>
          <p className="text-muted">Net worth</p>
          <p data-amount className="text-3xl font-bold tabular-nums">{formatCents(worth.netWorthCents)}</p>
          <p className="text-muted">today · what you own less what you owe</p>
        </div>
        <div>
          <p className="text-muted">You own</p>
          <p data-amount className="text-2xl font-bold tabular-nums">{formatCents(worth.assetsCents)}</p>
          <p className="text-muted">
            <span data-amount>{formatCents(worth.cashCents)}</span> cash · <span data-amount>{formatCents(worth.investmentsCents)}</span>{" "}
            invested · <span data-amount>{formatCents(worth.propertyCents)}</span> home & assets
          </p>
        </div>
        <div>
          <p className="text-muted">You owe</p>
          <p data-amount className="text-2xl font-bold tabular-nums">{formatCents(worth.liabilitiesCents)}</p>
          <p className="text-muted">
            <span data-amount>{formatCents(worth.cardsCents)}</span> on cards · <span data-amount>{formatCents(worth.loansCents)}</span> in
            loans
          </p>
        </div>
      </div>
    </section>
  );
}

function AccountGroup({
  title,
  accounts,
  wide,
}: {
  title: string;
  accounts: Account[];
  wide: boolean;
}) {
  const headingId = `group-${title.replace(/\W+/g, "-").toLowerCase()}`;
  const today = useAccountBalances().data;
  // What each account holds today; until that is known, the balance last entered for it.
  const held = (account: Account) => today?.find((balance) => balance.accountId === account.id);
  // Adding in cents keeps the total exact.
  const total = accounts.reduce((sum, account) => sum + (held(account)?.balanceCents ?? account.latestBalance?.amountCents ?? 0), 0);
  return (
    <section aria-labelledby={headingId} className={`${GROUP} ${wide ? "lg:col-span-2" : ""}`}>
      <div className="flex items-baseline justify-between gap-3 border-b border-line pb-3">
        <h2 id={headingId} className="text-lg font-semibold">
          {title}
        </h2>
        <Amount cents={total} className="text-muted" />
      </div>
      <ul className="divide-y divide-line">
        {accounts.map((account) => (
          <AccountRow key={account.id} account={account} today={held(account)} />
        ))}
      </ul>
    </section>
  );
}

/** One account on the overview. The row only opens the account's page; editing starts there. */
function AccountRow({ account, today }: { account: Account; today: AccountBalance | undefined }) {
  return (
    <li className="flex items-center gap-2">
      <Link to={`/accounts/${account.id}`} className={ROW_LINK}>
        <span aria-hidden className="size-3 shrink-0 rounded-full" style={{ backgroundColor: account.color }} />
        <span className="min-w-0 flex-1">
          <span className="block font-semibold sm:truncate">{account.name}</span>
          <span className="block text-sm text-muted sm:truncate">{accountSubtitle(account)}</span>
        </span>
        <span className="shrink-0 text-right">
          {today ? (
            <>
              <Amount cents={today.balanceCents} className="font-semibold" />
              {today.balanceCents < 0 && holdsCash(account.type) ? (
                <span className="block pr-2 text-sm font-semibold text-danger">Overdrawn today</span>
              ) : (
                <span className="block pr-2 text-sm text-muted">
                  {/* The mockup's "last entered" date: how fresh the real number behind today's figure is. */}
                  today
                  {/* On a phone there is no room for it beside the name; it is on the account's own page. */}
                  {account.latestBalance && <span className="hidden sm:inline"> · entered {formatDay(account.latestBalance.date)}</span>}
                </span>
              )}
            </>
          ) : account.latestBalance ? (
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

function AssetGroup({ assets, wide }: { assets: Asset[]; wide: boolean }) {
  const total = assets.reduce((sum, asset) => sum + (asset.valueCents ?? 0), 0);
  return (
    <section aria-labelledby="group-home-assets" className={`${GROUP} ${wide ? "lg:col-span-2" : ""}`}>
      <div className="flex items-baseline justify-between gap-3 border-b border-line pb-3">
        <h2 id="group-home-assets" className="text-lg font-semibold">
          Home & assets
        </h2>
        <Amount cents={total} className="text-muted" />
      </div>
      <ul className="divide-y divide-line">
        {assets.map((asset) => (
          <AssetRow key={asset.id} asset={asset} />
        ))}
      </ul>
    </section>
  );
}

function AssetRow({ asset }: { asset: Asset }) {
  return (
    <li className="flex items-center gap-2">
      <Link to={`/assets/${asset.id}`} className={ROW_LINK}>
        <span aria-hidden className="size-3 shrink-0 rounded-full" style={{ backgroundColor: asset.color }} />
        <span className="min-w-0 flex-1">
          <span className="block font-semibold sm:truncate">{asset.name}</span>
          <span className="block text-sm text-muted sm:truncate">
            {ASSET_KIND_LABELS[asset.kind]}
            {asset.linkedLoanAccountId !== null && asset.equityCents !== null && (
              <>
                {" · equity "}
                <span data-amount>{formatCents(asset.equityCents)}</span>
              </>
            )}
          </span>
        </span>
        <span className="shrink-0 text-right">
          {asset.valueCents !== null ? (
            <>
              <Amount cents={asset.valueCents} estimate={asset.estimate} className="font-semibold" />
              <span className="block pr-2 text-sm text-muted">
                {asset.latestValue ? `entered ${formatDay(asset.latestValue.date)}` : ""}
              </span>
            </>
          ) : (
            <span className="text-sm text-muted">No value yet</span>
          )}
        </span>
      </Link>
    </li>
  );
}
