import { useState } from "react";
import { type Account, useDeleteBalance } from "../../api/accounts";
import type { BalanceAdjustment } from "../../api/periods";
import { formatDay } from "../../lib/dates";
import { formatCents } from "../../lib/money";
import { BalanceForm } from "../accounts/BalanceForm";
import { dayBefore, Reconcile } from "../accounts/Reconcile";
import { EditButton } from "../EditButton";
import { BUTTON_LINK } from "../ui";

/**
 * The balances entered for an account during a pay period, each as the adjustment it makes: the period starts with
 * what the one before ended on, and a balance entered part way through (or on its first day) moves it to the real
 * number (Rachel, 2026-10-21: "previous pay period available after buckets always equals next period starting
 * available", "that adjustment should be editable and deletable"). Edit changes the balance entered; Delete removes
 * it, and the projection then carries on without it.
 */
export function BalanceAdjustments({ account, adjustments }: { account: Account; adjustments: readonly BalanceAdjustment[] }) {
  const remove = useDeleteBalance(account.id);
  const [editing, setEditing] = useState<BalanceAdjustment>();
  if (adjustments.length === 0) {
    return null;
  }
  return (
    <>
      {adjustments.map((adjustment) => (
        <div key={adjustment.entryId} className="flex flex-wrap items-center justify-between gap-x-2 py-1.5">
          <dt className="min-w-0 text-muted">
            Balance adjusted
            <span data-amount className="block text-sm">
              {formatDay(adjustment.date)} · you entered {formatCents(adjustment.balanceCents)}
            </span>
          </dt>
          <dd className="flex shrink-0 items-center gap-1">
            <span data-amount className="tabular-nums">
              {adjustment.differenceCents < 0 ? "−" : "+"}
              {formatCents(Math.abs(adjustment.differenceCents))}
            </span>
            <EditButton name={`the balance entered ${formatDay(adjustment.date)}`} onClick={() => setEditing(adjustment)} />
            <button
              type="button"
              disabled={remove.isPending}
              onClick={() => remove.mutate(adjustment.entryId)}
              aria-label={`Delete the balance entered ${formatDay(adjustment.date)}`}
              className={BUTTON_LINK}
            >
              Delete
            </button>
          </dd>
          {adjustment.since !== null && (
            <dd className="w-full">
              <Reconcile
                account={account}
                from={adjustment.since}
                through={dayBefore(adjustment.date)}
                recordsCents={adjustment.balanceCents - adjustment.differenceCents}
                enteredCents={adjustment.balanceCents}
              />
            </dd>
          )}
        </div>
      ))}
      {remove.isError && (
        <p role="alert" className="py-1.5 text-danger">
          {remove.error.message}
        </p>
      )}
      {editing && (
        <BalanceForm
          account={account}
          editing={{ entryId: editing.entryId, date: editing.date, amountCents: editing.balanceCents, note: editing.note }}
          onClose={() => setEditing(undefined)}
        />
      )}
    </>
  );
}
