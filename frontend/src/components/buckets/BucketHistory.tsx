import { type Bucket, useBucket, useDeleteBucketChange } from "../../api/buckets";
import { formatDate } from "../../lib/dates";
import { formatCents } from "../../lib/money";
import { BUTTON_LINK } from "../ui";

/**
 * What was put into and taken out of a bucket by hand, newest first: its starting amount and
 * every time its amount was changed in the Edit form. Each can be removed again.
 */
export function BucketHistory({ bucket }: { bucket: Bucket }) {
  const detail = useBucket(bucket.id);
  const remove = useDeleteBucketChange(bucket.id);
  return (
    <section aria-label="Changed by hand" className="mt-6 border-t border-line pt-4">
      <h3 className="font-semibold">Changed by hand</h3>
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
