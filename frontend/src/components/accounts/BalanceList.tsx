import { useState } from "react";
import { type Account, type BalanceChange, type BalanceEntry, useDeleteBalance } from "../../api/accounts";
import { formatDate } from "../../lib/dates";
import { formatCents } from "../../lib/money";
import { Amount } from "../Amount";
import { EditButton } from "../EditButton";
import { BUTTON_LINK } from "../ui";
import { BalanceForm } from "./BalanceForm";
import { dayBefore, Reconcile } from "./Reconcile";

/* One row of "Balances you entered": stacked on a phone, columns from `md` up. */
const ROW = "grid grid-cols-2 gap-x-4 gap-y-1 py-3 md:grid-cols-[8rem_8rem_8rem_8rem_8rem_1fr_auto] md:items-center";

/** A change with its sign: "+$1,000.00", "−$250.00" as formatCents writes it, or a dash when there is nothing to compare. */
export function signed(cents: number | null): string {
  return cents === null ? "—" : `${cents > 0 ? "+" : ""}${formatCents(cents)}`;
}

/**
 * Every balance the user entered for an account, newest first, each set against the one before it. Each can be
 * changed or removed, and one that differs from what the records came to says so, to reconcile (Rachel, 2026-10-21:
 * "where's my recon and difference in accounts?").
 */
export function BalanceList({
  account,
  balances,
  changes,
  owed,
}: {
  account: Account;
  balances: BalanceEntry[];
  /** Each balance set against the one before it; empty until that has loaded. */
  changes: readonly BalanceChange[];
  /** A card or loan: what moves it is charges and payments, so "put in" and "growth" do not apply. */
  owed: boolean;
}) {
  const remove = useDeleteBalance(account.id);
  const [editing, setEditing] = useState<BalanceEntry>();
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
              <span className="col-span-2 flex items-center justify-end gap-1 md:col-span-1">
                <EditButton name={`the balance from ${formatDate(entry.date)}`} onClick={() => setEditing(entry)} />
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
              {previous && split?.recordsCents != null && (
                <div className="col-span-full">
                  <Reconcile
                    account={account}
                    from={previous.date}
                    through={dayBefore(entry.date)}
                    recordsCents={split.recordsCents}
                    enteredCents={entry.amountCents}
                  />
                </div>
              )}
            </li>
          );
        })}
      </ul>
      {remove.isError && (
        <p role="alert" className="mt-3 text-danger">
          {remove.error.message}
        </p>
      )}
      {editing && (
        <BalanceForm
          account={account}
          editing={{ entryId: editing.id, date: editing.date, amountCents: editing.amountCents, note: editing.note }}
          onClose={() => setEditing(undefined)}
        />
      )}
    </>
  );
}
