import { formatCents } from "../lib/money";

/**
 * A money amount: right-aligned, tabular digits, and the same box whether or not it is an
 * estimate, so columns of amounts stay lined up. Estimates get the light blue fill
 * (spec §1); the words "estimate" are added for screen readers, since color alone is not enough.
 */
export function Amount({ cents, estimate = false, className = "" }: { cents: number; estimate?: boolean; className?: string }) {
  return (
    <span
      data-amount
      className={`inline-block rounded-md border px-2 py-0.5 text-right tabular-nums ${
        estimate ? "border-estimate-line bg-estimate text-estimate-ink" : "border-transparent"
      } ${className}`}
    >
      {formatCents(cents)}
      {estimate && <span className="sr-only"> (estimate)</span>}
    </span>
  );
}

/** The small key shown wherever estimates appear. */
export function EstimateKey() {
  return (
    <p className="flex shrink-0 items-center gap-2 text-sm whitespace-nowrap text-muted">
      <span aria-hidden className="rounded-md border border-estimate-line bg-estimate px-2 py-0.5 tabular-nums text-estimate-ink">
        $0.00
      </span>
      = estimate
    </p>
  );
}
