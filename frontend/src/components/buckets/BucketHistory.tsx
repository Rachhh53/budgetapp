import { useState } from "react";
import { type Bucket, useBucket, useDeleteBucketChange } from "../../api/buckets";
import { formatDate } from "../../lib/dates";
import { formatCents } from "../../lib/money";
import { BUTTON_LINK } from "../ui";

/** How many are shown at once: the list is a screen of the bucket's form, which never scrolls. */
const PAGE_SIZE = 4;

/**
 * What was done to a bucket by hand, newest first: its starting amount, money put in or taken out, and every amount
 * set for a day in the Edit form ("Set to $3,310.10"), which it holds at the end of that day whatever changes before
 * it. Each can be removed again. A few are shown at a time, with Older and Newer for the rest. Reached from the
 * History button on the bucket's row (Rachel, 2026-10-20: "I can't see the bucket balance history").
 */
export function BucketHistory({ bucket }: { bucket: Bucket }) {
  const detail = useBucket(bucket.id);
  const remove = useDeleteBucketChange(bucket.id);
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
          {inView.map((change) => (
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
          ))}
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
