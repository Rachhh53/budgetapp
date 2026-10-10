import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import {
  type BalanceChange,
  type BalanceEntry,
  holdsCash,
  useAccount,
  useAccountPerformance,
  useAccountProjection,
  useAccounts,
  useDeleteBalance,
} from "../api/accounts";
import { useIncomeSources } from "../api/income";
import { useAccountBalances } from "../api/periods";
import { TransactionsCard } from "../components/accounts/TransactionsCard";
import { useBuckets } from "../api/buckets";
import { AccountForm } from "../components/accounts/AccountForm";
import { BalanceForm } from "../components/accounts/BalanceForm";
import { GrowthCard } from "../components/accounts/GrowthCard";
import { InterestCard } from "../components/accounts/InterestCard";
import { accountSubtitle, TYPE_LABELS } from "../components/accounts/labels";
import { ProjectionChart } from "../components/accounts/ProjectionChart";
import { Amount } from "../components/Amount";
import { BucketsCard } from "../components/buckets/BucketsCard";
import { Card } from "../components/Card";
import { EditPageButton } from "../components/EditButton";
import { BUTTON_LINK, BUTTON_PRIMARY, DETAIL_HEADER, PAGE_TITLE } from "../components/ui";
import { formatDate, todayIso } from "../lib/dates";
import { formatCents } from "../lib/money";
import { formatRate } from "../lib/rates";

/* One row of "Balances you entered": stacked on a phone, columns from `md` up. */
const ROW = "grid grid-cols-2 gap-x-4 gap-y-1 py-3 md:grid-cols-[8rem_8rem_8rem_8rem_8rem_1fr_auto] md:items-center";

/** A change with its sign: "+$1,000.00", "−$250.00" as formatCents writes it, or a dash when there is nothing to compare. */
function signed(cents: number | null): string {
  return cents === null ? "—" : `${cents > 0 ? "+" : ""}${formatCents(cents)}`;
}

/**
 * Account detail (design/account-detail.png): the balance and where it is heading, how it has
 * changed this year, the balances the user entered with how much of each change was money put
 * in — and, for checking and savings, buckets and interest; for an investment, what each
 * paycheck puts in and the growth the user assumes.
 */
export function AccountDetailPage() {
  const id = Number(useParams().id);
  const navigate = useNavigate();
  const detail = useAccount(id);
  const accounts = useAccounts();
  const projection = useAccountProjection(id);
  const balancesToday = useAccountBalances();
  const buckets = useBuckets();
  const performance = useAccountPerformance(id);
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
          <Link to="/accounts" className="mt-2 inline-block font-semibold text-accent-ink underline">
            Back to all accounts
          </Link>
        </Card>
      </>
    );
  }

  const { account, balances } = detail.data;
  const latest = account.latestBalance;
  const today = balancesToday.data?.find((balance) => balance.accountId === account.id);
  const cash = holdsCash(account.type);
  const points = projection.data ?? [];
  const thisPeriod = points[0];
  const hasBuckets = (buckets.data ?? []).some((bucket) => bucket.accountId === account.id && !bucket.archived);
  const currentRate = detail.data.rates.find((rate) => rate.effectiveDate <= todayIso());
  const investment = account.type === "investment";
  const thisYear = performance.data?.thisYear ?? null;
  // Money put in is "paid off" on something owed, and "growth" there would be interest charged: other words.
  const owed = account.type === "credit_card" || account.type === "loan";

  return (
    <>
      <div className={DETAIL_HEADER}>
        <div className="min-w-0">
          <p className="text-sm text-muted">
            <Link to="/accounts" className="text-accent-ink underline">
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
          <EditPageButton label="Edit account" onClick={() => setEditing(true)} />
          <button type="button" onClick={() => setUpdating(true)} className={BUTTON_PRIMARY}>
            Update balance
          </button>
        </div>
      </div>

      <div className="space-y-4">
        {cash && today && today.balanceCents < 0 && (
          <p role="alert" className="rounded-card border-2 border-danger bg-surface p-4 text-lg font-semibold text-danger">
            Overdrawn: {account.name} is at {formatCents(today.balanceCents)} today.
          </p>
        )}
        <Card>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <div>
              <p className="text-muted">Balance</p>
              {today ? (
                <>
                  <p data-amount className="text-3xl font-bold tabular-nums">{formatCents(today.balanceCents)}</p>
                  <p className="text-muted">
                    today
                    {latest && ` · you entered ${formatCents(latest.amountCents)} on ${formatDate(latest.date)}`}
                  </p>
                </>
              ) : latest ? (
                <>
                  <p data-amount className="text-3xl font-bold tabular-nums">{formatCents(latest.amountCents)}</p>
                  <p className="text-muted">entered by you {formatDate(latest.date)}</p>
                </>
              ) : (
                <p className="mt-1 font-medium">No balance entered yet.</p>
              )}
            </div>
            {thisPeriod && (
              <Stat label="Projected" cents={thisPeriod.endingCents} note={`at the end of this pay period, ${formatDate(thisPeriod.end)}`} />
            )}
            {thisPeriod && hasBuckets && (
              <Stat label="Unallocated balance" cents={thisPeriod.availableCents} note="at the end of this pay period" />
            )}
            {thisYear && (
              <div>
                <p className="text-muted">Change this year</p>
                <p data-amount className="text-2xl font-bold tabular-nums">{signed(thisYear.changeCents)}</p>
                <p className="text-muted">
                  since {formatDate(thisYear.since)}
                  {!owed && (
                    <>
                      {" · "}
                      <span data-amount>{signed(thisYear.depositsCents)}</span> put in,{" "}
                      <span data-amount>{signed(thisYear.growthCents)}</span> {investment ? "growth" : "interest and the rest"}
                    </>
                  )}
                </p>
              </div>
            )}
            {(cash || investment) && currentRate && (
              <div>
                <p className="text-muted">{investment ? "Assumed growth" : "Rate"}</p>
                <p className="text-2xl font-bold tabular-nums">{formatRate(currentRate.rateBp)}</p>
                <p className="text-muted">
                  {investment ? "a year, your assumption" : "APY"} since {formatDate(currentRate.effectiveDate)}
                </p>
              </div>
            )}
          </div>
          {account.lowBalanceThresholdCents !== null && (
            <p className="mt-3 text-muted">
              Pay periods are flagged when this account is projected to dip below{" "}
              <span data-amount className="font-semibold text-ink tabular-nums">
                {formatCents(account.lowBalanceThresholdCents)}
              </span>
              .
            </p>
          )}
        </Card>

        {projection.isError && (
          <Card>
            <p role="alert" className="text-danger">
              {projection.error.message}
            </p>
          </Card>
        )}
        {latest && points.length > 0 && (
          <Card title="Where it's heading">
            <ProjectionChart account={account} balances={balances} points={points} showAvailable={hasBuckets} />
          </Card>
        )}

        {cash && <BucketsCard account={account} />}
        {cash && <InterestCard detail={detail.data} />}
        {investment && <ContributionsCard accountId={account.id} />}
        {investment && <GrowthCard detail={detail.data} />}

        <TransactionsCard account={account} />

        <Card title="Balances you entered">
          {balances.length === 0 ? (
            <p className="text-muted">
              Nothing here yet. Choose "Update balance" and type in what your bank shows — projections start from the
              latest number you enter.
            </p>
          ) : (
            <BalanceList accountId={account.id} balances={balances} changes={performance.data?.balances ?? []} owed={owed} />
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

/** One of the figures beside the balance. */
function Stat({ label, cents, note }: { label: string; cents: number; note: string }) {
  return (
    <div>
      <p className="text-muted">{label}</p>
      <p data-amount className="text-2xl font-bold tabular-nums">{formatCents(cents)}</p>
      <p className="text-muted">{note}</p>
    </div>
  );
}

/**
 * What each paycheck puts into an investment account: the deductions that name it and the
 * employer's match. They are set up on the Income screen, so this only lists them.
 */
function ContributionsCard({ accountId }: { accountId: number }) {
  const sources = useIncomeSources();
  const lines = (sources.data ?? []).flatMap((source) => [
    ...source.deductions
      .filter((deduction) => deduction.toAccountId === accountId && deduction.amountCents > 0)
      .map((deduction) => ({ key: `${source.id}-${deduction.name}`, label: `${deduction.name} · ${source.name}`, cents: deduction.amountCents })),
    ...(source.match?.toAccountId === accountId && source.matchFigures && source.matchFigures.perPaycheckCents > 0
      ? [{ key: `${source.id}-match`, label: `Employer match · ${source.name}`, cents: source.matchFigures.perPaycheckCents }]
      : []),
  ]);
  const total = lines.reduce((sum, line) => sum + line.cents, 0);
  return (
    <Card title="Going in every paycheck">
      {sources.isError && (
        <p role="alert" className="text-danger">
          {sources.error.message}
        </p>
      )}
      {sources.data && lines.length === 0 && (
        <p className="text-muted">
          Nothing from a paycheck goes into this account yet. On the{" "}
          <Link to="/income" className="font-semibold text-accent-ink underline">
            Income
          </Link>{" "}
          screen, add a deduction that goes into it, and your employer's match if there is one. Transfers you set up as recurring
          items count too.
        </p>
      )}
      {lines.length > 0 && (
        <>
          <ul className="divide-y divide-line">
            {lines.map((line) => (
              <li key={line.key} className="flex items-center justify-between gap-3 py-2">
                <span className="min-w-0">{line.label}</span>
                <Amount cents={line.cents} className="shrink-0 font-semibold" />
              </li>
            ))}
            <li className="flex items-center justify-between gap-3 py-2 font-semibold">
              <span>Each paycheck</span>
              <Amount cents={total} className="shrink-0" />
            </li>
          </ul>
          <p className="mt-1 text-sm text-muted">
            Taken out before your take-home pay, so it never passes through checking. Change it on the{" "}
            <Link to="/income" className="font-semibold text-accent-ink underline">
              Income
            </Link>{" "}
            screen.
          </p>
        </>
      )}
    </Card>
  );
}

function BalanceList({
  accountId,
  balances,
  changes,
  owed,
}: {
  accountId: number;
  balances: BalanceEntry[];
  /** Each balance set against the one before it; empty until that has loaded. */
  changes: readonly BalanceChange[];
  /** A card or loan: what moves it is charges and payments, so "put in" and "growth" do not apply. */
  owed: boolean;
}) {
  const remove = useDeleteBalance(accountId);
  return (
    <>
      <div aria-hidden className={`${ROW} hidden border-b border-line pt-0 text-sm font-semibold text-muted md:grid`}>
        <span>Date</span>
        <span className="text-right">Balance</span>
        <span className="text-right">Change</span>
        <span className="text-right">{owed ? "Charged less paid" : "Put in"}</span>
        <span className="text-right">{owed ? "The rest" : "Growth"}</span>
        <span>Note</span>
        <span />
      </div>
      <ul className="divide-y divide-line">
        {balances.map((entry, index) => {
          // Newest first, so the entry before this one in time is the next in the list.
          const previous = balances[index + 1];
          const change = previous ? entry.amountCents - previous.amountCents : null;
          const split = changes.find((candidate) => candidate.entryId === entry.id);
          return (
            <li key={entry.id} className={ROW}>
              <span className="font-medium">{formatDate(entry.date)}</span>
              <span className="text-right">
                <Amount cents={entry.amountCents} className="font-semibold" />
              </span>
              <span className="text-muted md:sr-only">Change since the entry before</span>
              <span data-amount className="pr-2 text-right tabular-nums">
                {signed(change)}
              </span>
              {/* Growth = change − what was put in (spec §5.5): only ever from balances that were entered. */}
              <span className="text-muted md:sr-only">{owed ? "Charged less paid" : "Put in since then"}</span>
              <span data-amount className="pr-2 text-right tabular-nums">
                {signed(split?.depositsCents ?? null)}
              </span>
              <span className="text-muted md:sr-only">{owed ? "The rest of the change" : "Growth: the change less what was put in"}</span>
              <span data-amount className="pr-2 text-right font-semibold tabular-nums">
                {signed(split?.growthCents ?? null)}
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
