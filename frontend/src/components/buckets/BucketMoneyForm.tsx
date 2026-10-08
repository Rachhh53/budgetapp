import { type FormEvent, useId, useState } from "react";
import { type Bucket, useBucket, useDeleteBucketChange, useMoveBucketMoney } from "../../api/buckets";
import { formatDate, todayIso } from "../../lib/dates";
import { formatCents, parseDollars } from "../../lib/money";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { Segmented } from "../Segmented";
import { BUTTON_LINK, BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

type Direction = "in" | "out";

const DIRECTIONS: readonly { value: Direction; label: string }[] = [
  { value: "in", label: "Put money in" },
  { value: "out", label: "Take money out" },
];

/**
 * Moves money into or out of a bucket by hand, and lists what was moved that way before.
 * Nothing leaves the account: only what counts as set aside changes.
 */
export function BucketMoneyForm({ bucket, onClose }: { bucket: Bucket; onClose: () => void }) {
  const id = useId();
  const move = useMoveBucketMoney(bucket.id);
  const [direction, setDirection] = useState<Direction>("in");
  const [amount, setAmount] = useState("");
  const [date, setDate] = useState(todayIso());
  const [note, setNote] = useState("");
  const [amountError, setAmountError] = useState<string>();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const cents = parseDollars(amount);
    if (cents === null || cents <= 0) {
      setAmountError(AMOUNT_HELP);
      return;
    }
    setAmountError(undefined);
    move.mutate(
      { date, amountCents: direction === "in" ? cents : -cents, note: note.trim() === "" ? null : note },
      { onSuccess: onClose },
    );
  };

  return (
    <Modal title={`${bucket.name} — move money`} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <p className="text-muted">
          Holds{" "}
          <span data-amount className="font-semibold text-ink tabular-nums">
            {formatCents(bucket.balanceCents)}
          </span>{" "}
          today.
        </p>
        <Segmented legend="What to do" options={DIRECTIONS} value={direction} onChange={setDirection} />
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <MoneyField label="Amount" value={amount} onChange={setAmount} error={amountError} required autoFocus />
          <div>
            <label htmlFor={`${id}-date`} className={LABEL}>
              Date
            </label>
            <input id={`${id}-date`} type="date" value={date} onChange={(event) => setDate(event.target.value)} required className={INPUT} />
          </div>
        </div>
        <div>
          <label htmlFor={`${id}-note`} className={LABEL}>
            Note (optional)
          </label>
          <input id={`${id}-note`} value={note} onChange={(event) => setNote(event.target.value)} maxLength={200} autoComplete="off" className={INPUT} />
          <p className={HINT}>The money stays in the account. Only how much of it is set aside changes.</p>
        </div>

        {move.isError && (
          <p role="alert" className="text-danger">
            {move.error.message}
          </p>
        )}

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="submit" disabled={move.isPending} className={BUTTON_PRIMARY}>
            {move.isPending ? "Saving…" : direction === "in" ? "Put it in" : "Take it out"}
          </button>
        </div>
      </form>

      <MovedByHand bucket={bucket} />
    </Modal>
  );
}

/** What was put in and taken out by hand, newest first, each of which can be removed again. */
function MovedByHand({ bucket }: { bucket: Bucket }) {
  const detail = useBucket(bucket.id);
  const remove = useDeleteBucketChange(bucket.id);
  return (
    <section aria-label="Moved by hand" className="mt-6 border-t border-line pt-4">
      <h3 className="font-semibold">Moved by hand</h3>
      {detail.isPending && <p className="mt-2 text-muted">Loading…</p>}
      {detail.isError && (
        <p role="alert" className="mt-2 text-danger">
          {detail.error.message}
        </p>
      )}
      {detail.data?.changes.length === 0 && (
        <p className="mt-2 text-muted">Nothing yet. Recurring moves, save-ups and interest are worked out for you and are not listed here.</p>
      )}
      {detail.data && detail.data.changes.length > 0 && (
        <ul className="mt-2 divide-y divide-line">
          {detail.data.changes.map((change) => (
            <li key={change.id} className="flex items-center gap-3 py-1">
              <div className="min-w-0 flex-1">
                <span className="font-medium">{formatDate(change.date)}</span>
                {change.note && <span className="block text-sm text-muted wrap-break-word">{change.note}</span>}
              </div>
              <span data-amount className="shrink-0 font-semibold tabular-nums">
                {change.amountCents > 0 ? "+" : "−"}
                {formatCents(Math.abs(change.amountCents))}
              </span>
              <button
                type="button"
                disabled={remove.isPending}
                onClick={() => remove.mutate(change.id)}
                aria-label={`Remove the ${formatCents(Math.abs(change.amountCents))} ${change.amountCents > 0 ? "put in" : "taken out"} on ${formatDate(change.date)}`}
                className={BUTTON_LINK}
              >
                Remove
              </button>
            </li>
          ))}
        </ul>
      )}
      {remove.isError && (
        <p role="alert" className="mt-2 text-danger">
          {remove.error.message}
        </p>
      )}
    </section>
  );
}
