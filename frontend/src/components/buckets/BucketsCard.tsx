import { useState } from "react";
import type { Account } from "../../api/accounts";
import { type Bucket, useBuckets } from "../../api/buckets";
import { formatCents } from "../../lib/money";
import { Card } from "../Card";
import { BUTTON_LINK, BUTTON_SECONDARY } from "../ui";
import { BucketForm } from "./BucketForm";
import { BucketMoneyForm } from "./BucketMoneyForm";

/**
 * The buckets of one checking or savings account (design/account-detail.png): what each holds
 * today, how far along its goal is, and how much of the balance is left over.
 */
export function BucketsCard({ account }: { account: Account }) {
  const buckets = useBuckets();
  const [adding, setAdding] = useState(false);
  // Kept as ids, so an open form always shows the bucket as it is now.
  const [editingId, setEditingId] = useState<number>();
  const [movingId, setMovingId] = useState<number>();

  const all = buckets.data ?? [];
  const mine = all.filter((bucket) => bucket.accountId === account.id);
  const open = mine.filter((bucket) => !bucket.archived);
  const archived = mine.filter((bucket) => bucket.archived);
  const setAside = open.reduce((sum, bucket) => sum + bucket.balanceCents, 0);
  const editing = mine.find((bucket) => bucket.id === editingId);
  const moving = mine.find((bucket) => bucket.id === movingId);

  return (
    <Card title="Buckets">
      {buckets.isPending && <p className="text-muted">Loading buckets…</p>}
      {buckets.isError && (
        <p role="alert" className="text-danger">
          {buckets.error.message}
        </p>
      )}
      {buckets.data && open.length === 0 && (
        <p className="text-muted">
          No buckets yet. A bucket sets part of this account aside for something — holiday gifts, car repairs, an emergency
          fund — without moving the money anywhere.
        </p>
      )}
      {open.length > 0 && (
        <>
          <ul className="divide-y divide-line">
            {open.map((bucket) => (
              <BucketLine key={bucket.id} bucket={bucket} onEdit={() => setEditingId(bucket.id)} onMove={() => setMovingId(bucket.id)} />
            ))}
          </ul>
          <dl className="mt-2 space-y-1 border-t border-line pt-3">
            <Total label="Set aside in buckets" cents={setAside} />
            {account.latestBalance && (
              <Total label="Left available, from the balance you entered" cents={account.latestBalance.amountCents - setAside} strong />
            )}
          </dl>
          {/* It can still happen after the fact: the balance drops, or a schedule keeps filling a bucket. */}
          {account.latestBalance && setAside > account.latestBalance.amountCents && (
            <p role="status" className="mt-2 font-semibold text-danger">
              These buckets hold more than the account does. Take some out of a bucket, or update the balance if it has gone up.
            </p>
          )}
        </>
      )}
      {archived.length > 0 && (
        <ul className="mt-3 border-t border-line pt-2">
          {archived.map((bucket) => (
            <li key={bucket.id} className="flex items-center justify-between gap-3">
              <span className="min-w-0 text-muted wrap-break-word">{bucket.name} · Archived</span>
              <button type="button" onClick={() => setEditingId(bucket.id)} aria-label={`Edit ${bucket.name}`} className={BUTTON_LINK}>
                Edit
              </button>
            </li>
          ))}
        </ul>
      )}
      <button type="button" onClick={() => setAdding(true)} className={`${BUTTON_SECONDARY} mt-3`}>
        Add a bucket
      </button>

      {adding && <BucketForm account={account} buckets={all} onClose={() => setAdding(false)} />}
      {editing && <BucketForm account={account} bucket={editing} buckets={all} onClose={() => setEditingId(undefined)} />}
      {moving && <BucketMoneyForm bucket={moving} onClose={() => setMovingId(undefined)} />}
    </Card>
  );
}

function BucketLine({ bucket, onEdit, onMove }: { bucket: Bucket; onEdit: () => void; onMove: () => void }) {
  const goal = bucket.goalCents;
  // Shown as at most full: a bucket can hold more than its goal.
  const reached = goal === null ? null : Math.max(0, Math.min(100, Math.round((bucket.balanceCents * 100) / goal)));
  return (
    <li className="py-3">
      <div className="flex flex-col gap-1 sm:flex-row sm:items-center sm:gap-3">
        <div className="flex min-w-0 flex-1 items-center gap-3">
          <span aria-hidden className="size-3 shrink-0 rounded-sm" style={{ backgroundColor: bucket.color }} />
          <div className="min-w-0 flex-1">
            <p className="font-medium wrap-break-word">{bucket.name}</p>
            {(goal !== null || bucket.note) && (
              <p data-amount className="text-sm text-muted tabular-nums">
                {[goal === null ? null : `${reached}% of ${formatCents(goal)}`, bucket.note].filter(Boolean).join(" · ")}
              </p>
            )}
          </div>
          <span data-amount className="shrink-0 text-lg font-semibold tabular-nums">
            {formatCents(bucket.balanceCents)}
          </span>
        </div>
        <div className="flex shrink-0 gap-1 sm:justify-end">
          <button type="button" onClick={onMove} aria-label={`Move money in or out of ${bucket.name}`} className={BUTTON_LINK}>
            Move money
          </button>
          <button type="button" onClick={onEdit} aria-label={`Edit ${bucket.name}`} className={BUTTON_LINK}>
            Edit
          </button>
        </div>
      </div>
      {reached !== null && (
        // The percentage is in the text above; the bar only draws it.
        <div aria-hidden className="mt-1 h-2 overflow-hidden rounded-full bg-bg">
          <div className="h-full rounded-full" style={{ width: `${reached}%`, backgroundColor: bucket.color }} />
        </div>
      )}
    </li>
  );
}

function Total({ label, cents, strong = false }: { label: string; cents: number; strong?: boolean }) {
  return (
    <div className={`flex items-baseline justify-between gap-3 ${strong ? "font-semibold" : ""}`}>
      <dt className={strong ? "" : "text-muted"}>{label}</dt>
      <dd data-amount className="shrink-0 tabular-nums">
        {formatCents(cents)}
      </dd>
    </div>
  );
}
