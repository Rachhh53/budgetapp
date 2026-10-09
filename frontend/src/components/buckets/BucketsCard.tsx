import { useId, useState } from "react";
import { type Account, useAccounts } from "../../api/accounts";
import { type Bucket, useBuckets, useMoveMoneyInto } from "../../api/buckets";
import { useCategories, useTags } from "../../api/categories";
import { type Item, useItems, useSaveItem } from "../../api/items";
import { addDays, formatDate, todayIso } from "../../lib/dates";
import { formatCents } from "../../lib/money";
import { Card } from "../Card";
import { EditButton } from "../EditButton";
import { type BucketMovePreset, ItemForm } from "../items/ItemForm";
import { describeItemSchedule } from "../items/labels";
import { clickRowToEdit, ROW_OPENS } from "../rowEdit";
import { BUTTON_LINK, BUTTON_SECONDARY, INPUT, LABEL } from "../ui";
import { BucketForm } from "./BucketForm";

/**
 * The buckets of one checking or savings account (design/account-detail.png): what each holds
 * today, how far along its goal is, and how much of the balance is left over.
 */
export function BucketsCard({ account }: { account: Account }) {
  const buckets = useBuckets();
  const [adding, setAdding] = useState(false);
  // Kept as ids, so an open form always shows the bucket as it is now.
  const [editingId, setEditingId] = useState<number>();
  // The bucket a recurring move is being added to, with what the form starts out as.
  const [movingInto, setMovingInto] = useState<BucketMovePreset>();
  const items = useItems().data ?? [];
  const accounts = useAccounts().data ?? [];
  const categories = useCategories().data ?? [];
  const tags = useTags().data ?? [];

  const all = buckets.data ?? [];
  const mine = all.filter((bucket) => bucket.accountId === account.id);
  const open = mine.filter((bucket) => !bucket.archived);
  const archived = mine.filter((bucket) => bucket.archived);
  const setAside = open.reduce((sum, bucket) => sum + bucket.balanceCents, 0);
  const editing = mine.find((bucket) => bucket.id === editingId);
  // What the entered balance has outside every bucket: with the buckets, it always adds up to the balance.
  const notInABucket = account.latestBalance ? account.latestBalance.amountCents - setAside : null;

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
              <BucketLine
                key={bucket.id}
                bucket={bucket}
                feeding={items.filter((item) => !item.archived && (item.bucketId === bucket.id || item.saveUpBucketId === bucket.id))}
                onEdit={() => setEditingId(bucket.id)}
                onAddMove={() => setMovingInto(moveInto(bucket))}
              />
            ))}
          </ul>
          <dl className="mt-2 space-y-1 border-t border-line pt-3">
            <Total label="In buckets" cents={setAside} />
            {notInABucket !== null && <Total label="Not in a bucket" cents={notInABucket} />}
            {account.latestBalance && <Total label="Balance you entered" cents={account.latestBalance.amountCents} strong />}
          </dl>
          {/* It can still happen after the fact: the balance drops, or a schedule keeps filling a bucket. */}
          {notInABucket !== null && notInABucket > 0 && <PutTheRestIn buckets={open} cents={notInABucket} />}
          {notInABucket !== null && notInABucket < 0 && (
            <p role="status" className="mt-2 font-semibold text-danger">
              These buckets hold more than the account does. Edit a bucket to lower its amount, or update the balance if it has gone up.
            </p>
          )}
        </>
      )}
      {archived.length > 0 && (
        <ul className="mt-3 border-t border-line pt-2">
          {archived.map((bucket) => (
            <li key={bucket.id} onClick={clickRowToEdit} className={`flex items-center justify-between gap-3 ${ROW_OPENS}`}>
              <span className="min-w-0 text-muted wrap-break-word">{bucket.name} · Archived</span>
              <EditButton name={bucket.name} onClick={() => setEditingId(bucket.id)} labelled />
            </li>
          ))}
        </ul>
      )}
      <button type="button" onClick={() => setAdding(true)} className={`${BUTTON_SECONDARY} mt-3`}>
        Add a bucket
      </button>

      {adding && <BucketForm account={account} buckets={all} onClose={() => setAdding(false)} />}
      {editing && <BucketForm account={account} bucket={editing} buckets={all} onClose={() => setEditingId(undefined)} />}
      {movingInto && (
        <ItemForm
          preset={movingInto}
          title={`Add a recurring move into ${movingInto.name}`}
          accounts={accounts}
          categories={categories}
          tags={tags}
          onClose={() => setMovingInto(undefined)}
        />
      )}
    </Card>
  );
}

/**
 * What the recurring item form starts out as for a move into this bucket. When the bucket is
 * short of a goal with a date, it starts at what closing the gap takes every paycheck, from
 * the next payday (this one is not among those the amount was shared over) to the goal date.
 */
function moveInto(bucket: Bucket): BucketMovePreset {
  const suggested = bucket.goalProgress?.morePerPaycheckCents ?? null;
  return {
    bucketId: bucket.id,
    name: bucket.name,
    amountCents: suggested,
    startDate: suggested === null ? null : addDays(todayIso(), 1),
    endDate: suggested === null ? null : bucket.goalDate,
  };
}

function BucketLine({
  bucket,
  feeding,
  onEdit,
  onAddMove,
}: {
  bucket: Bucket;
  /** The recurring items that put money in this bucket. */
  feeding: readonly Item[];
  onEdit: () => void;
  onAddMove: () => void;
}) {
  const goal = bucket.goalCents;
  // Shown as at most full: a bucket can hold more than its goal.
  const reached = goal === null ? null : Math.max(0, Math.min(100, Math.round((bucket.balanceCents * 100) / goal)));
  return (
    <li onClick={clickRowToEdit} className={`py-3 ${ROW_OPENS}`}>
      <div className="flex flex-col gap-1 sm:flex-row sm:items-center sm:gap-3">
        <div className="flex min-w-0 flex-1 items-center gap-3">
          <span aria-hidden className="size-3 shrink-0 rounded-sm" style={{ backgroundColor: bucket.color }} />
          <div className="min-w-0 flex-1">
            <p className="font-medium wrap-break-word">{bucket.name}</p>
            {(goal !== null || bucket.note) && (
              <p data-amount className="text-sm text-muted tabular-nums">
                {[
                  goal === null ? null : `${reached}% of ${formatCents(goal)}${bucket.goalDate ? ` by ${formatDate(bucket.goalDate)}` : ""}`,
                  bucket.note,
                ]
                  .filter(Boolean)
                  .join(" · ")}
              </p>
            )}
          </div>
          <span data-amount className="shrink-0 text-lg font-semibold tabular-nums">
            {formatCents(bucket.balanceCents)}
          </span>
        </div>
        {/* One way in: the amount, the goal and the rest are all changed in the same form. */}
        <div className="flex shrink-0 flex-wrap gap-1 sm:justify-end">
          <button type="button" onClick={onAddMove} aria-label={`Add a recurring move into ${bucket.name}`} className={BUTTON_LINK}>
            Add a recurring move
          </button>
          <EditButton name={bucket.name} onClick={onEdit} labelled />
        </div>
      </div>
      {reached !== null && (
        // The percentage is in the text above; the bar only draws it.
        <div aria-hidden className="mt-1 h-2 overflow-hidden rounded-full bg-bg">
          <div className="h-full rounded-full" style={{ width: `${reached}%`, backgroundColor: bucket.color }} />
        </div>
      )}
      {/* Where its projection comes from: what already puts money in it on a schedule. */}
      {feeding.length > 0 && (
        <ul aria-label={`Recurring into ${bucket.name}`} className="mt-1 text-sm text-muted">
          {feeding.map((item) => (
            <li key={item.id}>
              {item.name} ·{" "}
              {item.saveUpBucketId === bucket.id ? (
                "saved up for every paycheck"
              ) : (
                <>
                  <span data-amount className="tabular-nums">
                    {formatCents(item.perOccurrenceCents)}
                  </span>{" "}
                  {describeItemSchedule(item.schedule).toLowerCase()}
                </>
              )}
            </li>
          ))}
        </ul>
      )}
      <GoalPaceNote bucket={bucket} />
    </li>
  );
}

/**
 * For an account where every dollar should have a job: puts whatever is not in a bucket yet
 * into one of them, in one step.
 */
function PutTheRestIn({ buckets, cents }: { buckets: readonly Bucket[]; cents: number }) {
  const id = useId();
  const move = useMoveMoneyInto();
  const [bucketId, setBucketId] = useState(buckets[0]?.id);
  // The chosen bucket can be archived or deleted while this is on screen.
  const chosen = buckets.find((bucket) => bucket.id === bucketId) ?? buckets[0];
  if (chosen === undefined) {
    return null;
  }
  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        move.mutate({ bucketId: chosen.id, date: todayIso(), amountCents: cents, note: "The rest of the balance" });
      }}
      className="mt-3 flex flex-col gap-2 sm:flex-row sm:items-end"
    >
      <div className="sm:flex-1">
        <label htmlFor={`${id}-rest`} className={LABEL}>
          Put the rest in a bucket
        </label>
        <select id={`${id}-rest`} value={chosen.id} onChange={(event) => setBucketId(Number(event.target.value))} className={INPUT}>
          {buckets.map((bucket) => (
            <option key={bucket.id} value={bucket.id}>
              {bucket.name}
            </option>
          ))}
        </select>
      </div>
      <button type="submit" disabled={move.isPending} className={BUTTON_SECONDARY}>
        {move.isPending ? "Saving…" : <>Put <span data-amount>{formatCents(cents)}</span> in</>}
      </button>
      {move.isError && (
        <p role="alert" className="text-danger sm:basis-full">
          {move.error.message}
        </p>
      )}
    </form>
  );
}

/**
 * For a bucket with a goal and a date: whether what is already scheduled gets there in time,
 * and if not, what it would take each paycheck or month, with a one-step way to start.
 * Said in words, never by color alone.
 */
function GoalPaceNote({ bucket }: { bucket: Bucket }) {
  const addMove = useSaveItem();
  const progress = bucket.goalProgress;
  if (progress === null || bucket.goalDate === null) {
    return null;
  }
  const by = formatDate(bucket.goalDate);
  if (progress.shortCents === 0) {
    return (
      <p className="mt-1 text-sm">
        <span className="font-semibold">On track.</span>{" "}
        <span className="text-muted">
          With what is scheduled it will hold <span data-amount className="tabular-nums">{formatCents(progress.projectedCents)}</span> by {by}.
        </span>
      </p>
    );
  }
  const perPaycheck = progress.morePerPaycheckCents;
  const perMonth = progress.morePerMonthCents;
  return (
    <div className="mt-1 text-sm">
      <p>
        <span className="font-semibold text-danger">
          Short by <span data-amount className="tabular-nums">{formatCents(progress.shortCents)}</span>.
        </span>{" "}
        <span className="text-muted">
          With what is scheduled it will hold <span data-amount className="tabular-nums">{formatCents(progress.projectedCents)}</span> by {by}.
        </span>
      </p>
      {perMonth === null ? (
        <p className="text-muted">That date has passed. Choose a later one to see what it would take.</p>
      ) : (
        <p>
          To get there, set aside{" "}
          {perPaycheck !== null && (
            <>
              <span data-amount className="font-semibold tabular-nums">{formatCents(perPaycheck)}</span> more every paycheck, or{" "}
            </>
          )}
          <span data-amount className="font-semibold tabular-nums">{formatCents(perMonth)}</span> more a month.
        </p>
      )}
      {perPaycheck !== null && (
        <button
          type="button"
          disabled={addMove.isPending}
          onClick={() =>
            addMove.mutate({
              name: `${bucket.name} goal`,
              kind: "bucket_move",
              note: null,
              archived: false,
              tagIds: [],
              amountCents: perPaycheck,
              amountMode: "per_occurrence",
              estimate: false,
              categoryId: null,
              payFromAccountId: bucket.accountId,
              payToAccountId: null,
              autopay: true,
              placement: "due_period",
              // From the next payday: this paycheck is not among the ones the amount was shared over.
              schedule: {
                frequency: "every_pay_period",
                interval: null,
                day1: null,
                day2: null,
                month: null,
                startDate: addDays(todayIso(), 1),
                endDate: bucket.goalDate,
                weekendRule: "none",
              },
              daysAfterPayday: 0,
              bucketId: bucket.id,
              saveUpBucketId: null,
            })
          }
          className={`${BUTTON_SECONDARY} mt-2`}
        >
          {addMove.isPending ? "Adding…" : <>Start saving <span data-amount>{formatCents(perPaycheck)}</span> every paycheck</>}
        </button>
      )}
      {addMove.isError && (
        <p role="alert" className="mt-1 text-danger">
          {addMove.error.message}
        </p>
      )}
    </div>
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
