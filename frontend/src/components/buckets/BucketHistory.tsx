import { type KeyboardEvent, useId, useState } from "react";
import { type Bucket, type BucketChange, useBucket, useDeleteBucketChange, useUpdateBucketChange } from "../../api/buckets";
import { formatDate } from "../../lib/dates";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { EditButton } from "../EditButton";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { BUTTON_LINK, BUTTON_PRIMARY, BUTTON_SECONDARY, INPUT, LABEL } from "../ui";

/** How many are shown at once: the list is a screen of the bucket's form, which never scrolls. */
const PAGE_SIZE = 4;

/**
 * What was done to a bucket by hand, newest first: its starting amount, money put in or taken out, and every amount
 * set for a day in the Edit form ("Set to $3,310.10"), which it holds at the end of that day whatever changes before
 * it. Each can be changed (Rachel, 2026-10-21: "I want the ability to edit basically everything I enter") or removed
 * again; money moved out to spend is changed from the pay period, where it is listed. A few are shown at a time, with Older and Newer for the rest. Reached from the
 * History button on the bucket's row (Rachel, 2026-10-20: "I can't see the bucket balance history").
 */
export function BucketHistory({ bucket }: { bucket: Bucket }) {
  const detail = useBucket(bucket.id);
  const remove = useDeleteBucketChange(bucket.id);
  const [editingId, setEditingId] = useState<number>();
  const [chosenPage, setChosenPage] = useState(0);
  const changes = detail.data?.changes ?? [];
  const pages = Math.max(1, Math.ceil(changes.length / PAGE_SIZE));
  // Removing the last one on a page leaves that page empty, so step back to one that has some.
  const page = Math.min(chosenPage, pages - 1);
  const first = page * PAGE_SIZE;
  const inView = changes.slice(first, first + PAGE_SIZE);
  return (
    <section aria-label="History">
      <h3 className="font-semibold">History</h3>
      {detail.isPending && <p className="mt-2 text-muted">Loading…</p>}
      {detail.isError && (
        <p role="alert" className="mt-2 text-danger">
          {detail.error.message}
        </p>
      )}
      {detail.data?.changes.length === 0 && (
        <p className="mt-2 text-muted">Nothing yet. Transfers into it, save-ups and interest are worked out for you and are not listed here.</p>
      )}
      {inView.length > 0 && (
        <ul className="mt-2 divide-y divide-line">
          {inView.map((change) =>
            change.id === editingId ? (
              <ChangeForm key={change.id} bucketId={bucket.id} change={change} onDone={() => setEditingId(undefined)} />
            ) : (
              <li key={change.id} className="flex items-center gap-3 py-1">
                <div className="min-w-0 flex-1">
                  <span className="font-medium">{formatDate(change.date)}</span>
                  {change.note && <span className="block truncate text-sm text-muted">{change.note}</span>}
                </div>
                <span data-amount className="shrink-0 font-semibold tabular-nums">
                  {change.setToCents !== null ? (
                    `Set to ${formatCents(change.setToCents)}`
                  ) : (
                    <>
                      {change.amountCents > 0 ? "+" : "−"}
                      {formatCents(Math.abs(change.amountCents))}
                    </>
                  )}
                </span>
                {!change.movedOut && (
                  <EditButton
                    name={change.setToCents !== null ? `the amount set on ${formatDate(change.date)}` : `the change on ${formatDate(change.date)}`}
                    onClick={() => setEditingId(change.id)}
                  />
                )}
                <button
                  type="button"
                  disabled={remove.isPending}
                  onClick={() => remove.mutate(change.id)}
                  aria-label={
                    change.setToCents !== null
                      ? `Remove the amount set to ${formatCents(change.setToCents)} on ${formatDate(change.date)}`
                      : `Remove the ${formatCents(Math.abs(change.amountCents))} ${change.amountCents > 0 ? "put in" : "taken out"} on ${formatDate(change.date)}`
                  }
                  className={BUTTON_LINK}
                >
                  Remove
                </button>
              </li>
            ),
          )}
        </ul>
      )}
      {pages > 1 && (
        <nav aria-label="More changes" className="flex items-center justify-between gap-2 border-t border-line">
          <button type="button" onClick={() => setChosenPage(page - 1)} disabled={page === 0} className={`${BUTTON_LINK} disabled:opacity-40`}>
            Newer
          </button>
          <p role="status" className="text-sm text-muted">
            {first + 1}–{first + inView.length} of {changes.length}
          </p>
          <button type="button" onClick={() => setChosenPage(page + 1)} disabled={page === pages - 1} className={`${BUTTON_LINK} disabled:opacity-40`}>
            Older
          </button>
        </nav>
      )}
      {remove.isError && (
        <p role="alert" className="mt-2 text-danger">
          {remove.error.message}
        </p>
      )}
    </section>
  );
}

/**
 * One change, open to edit in its row: its date and amount, and the note for money put in or taken out. Money put in
 * stays put in and money taken out stays taken out; an amount set is what the bucket holds at the end of the day.
 *
 * Not a <form>: History sits inside the bucket's own form, and a form inside a form is invalid HTML whose submit would
 * also reach the outer one. Enter in a field saves this row alone.
 */
function ChangeForm({ bucketId, change, onDone }: { bucketId: number; change: BucketChange; onDone: () => void }) {
  const id = useId();
  const save = useUpdateBucketChange(bucketId);
  const set = change.setToCents !== null;
  const [date, setDate] = useState(change.date);
  const [amount, setAmount] = useState(centsToInput(set ? (change.setToCents ?? 0) : Math.abs(change.amountCents)));
  const [note, setNote] = useState(change.note ?? "");
  const [problem, setProblem] = useState<string>();

  const submit = () => {
    const cents = parseDollars(amount);
    if (cents === null || cents < 0 || (!set && cents === 0)) {
      setProblem(cents === null ? AMOUNT_HELP : "Please enter how much, more than $0.00.");
      return;
    }
    if (date === "") {
      setProblem("Please choose the date.");
      return;
    }
    setProblem(undefined);
    save.mutate(
      { changeId: change.id, date, amountCents: set ? cents : change.amountCents < 0 ? -cents : cents, note: set ? change.note : note.trim() || null },
      { onSuccess: onDone },
    );
  };

  const onEnter = (event: KeyboardEvent) => {
    if (event.key === "Enter" && event.target instanceof HTMLInputElement) {
      event.preventDefault();
      submit();
    }
  };

  const label = set ? "Set to" : change.amountCents > 0 ? "Put in" : "Taken out";
  return (
    <li className="py-2">
      <div role="group" onKeyDown={onEnter} className="grid grid-cols-2 gap-x-3 gap-y-2" aria-label={`Change ${formatDate(change.date)}`}>
        <MoneyField label={label} value={amount} onChange={setAmount} />
        <div>
          <label htmlFor={`${id}-date`} className={LABEL}>
            Date
          </label>
          <input id={`${id}-date`} type="date" value={date} onChange={(event) => setDate(event.target.value)} className={INPUT} />
        </div>
        {!set && (
          <div className="col-span-2">
            <label htmlFor={`${id}-note`} className={LABEL}>
              Note (optional)
            </label>
            <input id={`${id}-note`} value={note} onChange={(event) => setNote(event.target.value)} maxLength={200} className={INPUT} />
          </div>
        )}
        {(problem ?? save.error?.message) && (
          <p role="alert" className="col-span-2 text-danger">
            {problem ?? save.error?.message}
          </p>
        )}
        <div className="col-span-2 flex justify-end gap-2">
          <button type="button" onClick={onDone} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="button" onClick={submit} disabled={save.isPending} className={BUTTON_PRIMARY}>
            {save.isPending ? "Saving…" : "Save"}
          </button>
        </div>
      </div>
    </li>
  );
}
