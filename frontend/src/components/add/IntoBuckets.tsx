import { X } from "lucide-react";
import type { Bucket } from "../../api/buckets";
import { formatCents } from "../../lib/money";
import { BUTTON_LINK, HINT, INPUT } from "../ui";

/** Money not put in any bucket: the first line's choice when nothing is set aside. */
export const NOT_IN_A_BUCKET = "none";

/** One line of where arriving money goes: a bucket's id (or {@link NOT_IN_A_BUCKET}) and, past the first, its amount as typed. */
export interface IntoLine {
  bucket: string;
  amount: string;
}

interface IntoBucketsProps {
  id: string;
  /** The open buckets of the account the money arrives in. */
  buckets: readonly Bucket[];
  lines: readonly IntoLine[];
  onChange: (lines: IntoLine[]) => void;
  /** What arrives, in cents; `null` while the amount isn't a valid one yet. */
  amountCents: number | null;
  /** What the lines past the first add up to, in cents, as the form reads them. */
  splitCents: number;
}

/**
 * Where money arriving in an account with buckets goes, on the same screen as the accounts (Rachel, 2026-10-20:
 * "this belongs on the screen where the accounts are being selected and it should default to the whole amount").
 * The first line takes the whole amount, or whatever the others leave; "Add another bucket" splits it, so one
 * transfer or paycheck can fill several buckets.
 */
export function IntoBuckets({ id, buckets, lines, onChange, amountCents, splitCents }: IntoBucketsProps) {
  const chosen = new Set(lines.map((line) => line.bucket));
  const unused = buckets.filter((bucket) => !chosen.has(String(bucket.id)));
  const rest = amountCents === null ? null : amountCents - splitCents;
  const change = (index: number, line: Partial<IntoLine>) =>
    onChange(lines.map((current, at) => (at === index ? { ...current, ...line } : current)));
  const label = (bucket: Bucket) => `${bucket.name} (holds ${formatCents(bucket.balanceCents)} today)`;

  return (
    <fieldset className="space-y-2 sm:col-span-2">
      <legend className="mb-1 font-medium">Goes into</legend>
      {lines.map((line, index) => {
        // Each line offers its own bucket and those no other line has; only the first can be money in no bucket.
        const offered = buckets.filter((bucket) => String(bucket.id) === line.bucket || !chosen.has(String(bucket.id)));
        return (
          <div key={index} className="flex items-center gap-2">
            <div className="min-w-0 flex-1">
              <select
                id={`${id}-into-${index}`}
                aria-label={index === 0 ? "Goes into" : `Bucket ${index + 1}`}
                value={line.bucket}
                onChange={(event) => change(index, { bucket: event.target.value })}
                className={INPUT}
              >
                {index === 0 && <option value={NOT_IN_A_BUCKET}>Money that is not in a bucket</option>}
                {offered.map((bucket) => (
                  <option key={bucket.id} value={bucket.id}>
                    {label(bucket)}
                  </option>
                ))}
              </select>
            </div>
            {index === 0 ? (
              <p className="w-24 shrink-0 text-right text-sm text-muted tabular-nums">
                {/* The amount itself, so it can be checked without going back (Rachel, 2026-10-20: "at least show the amount"). */}
                {rest === null ? (lines.length === 1 ? "all of it" : "the rest") : <span data-amount>{formatCents(Math.max(0, rest))}</span>}
              </p>
            ) : (
              <>
                <div className="w-24 shrink-0">
                  <input
                    aria-label={`Amount into bucket ${index + 1}`}
                    inputMode="decimal"
                    autoComplete="off"
                    value={line.amount}
                    onChange={(event) => change(index, { amount: event.target.value })}
                    placeholder="0.00"
                    className={`${INPUT} text-right tabular-nums`}
                  />
                </div>
                <button
                  type="button"
                  onClick={() => onChange(lines.filter((_, at) => at !== index))}
                  aria-label={`Remove bucket ${index + 1}`}
                  className="inline-flex size-11 shrink-0 items-center justify-center rounded-lg text-muted hover:bg-bg"
                >
                  <X aria-hidden className="size-4" />
                </button>
              </>
            )}
          </div>
        );
      })}
      {unused.length > 0 && (
        <button
          type="button"
          onClick={() => onChange([...lines, { bucket: String(unused[0]!.id), amount: "" }])}
          className={BUTTON_LINK}
        >
          Add another bucket
        </button>
      )}
      {lines.length === 1 && <p className={HINT}>To split it, add another bucket and say how much goes there.</p>}
    </fieldset>
  );
}
