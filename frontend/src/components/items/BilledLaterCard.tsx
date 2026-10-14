import { Trash2 } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router";
import { type InvoiceView, type Unbilled, useBilling, useDeleteInvoice } from "../../api/invoices";
import type { Item } from "../../api/items";
import { formatDate, formatDay } from "../../lib/dates";
import { formatCents } from "../../lib/money";
import { Amount } from "../Amount";
import { Card } from "../Card";
import { InvoiceForm } from "../period/InvoiceForm";
import { BUTTON_PRIMARY } from "../ui";

/** Enough visits to see the pattern; the rest are counted, not listed, so the card stays short. */
const VISITS_SHOWN = 6;

/**
 * Billed later, on the item's own page (spec §4.6): the visits waiting for an invoice, with
 * **Mark as invoiced**, then every invoice entered, newest first, each opening the pay period that
 * pays it. Only the newest can be removed, which leaves its visits waiting again.
 */
export function BilledLaterCard({ item }: { item: Item }) {
  const billing = useBilling(item.id);
  const remove = useDeleteInvoice(item.id);
  const [invoicing, setInvoicing] = useState(false);

  if (billing.isError) {
    return (
      <Card title="Billed later">
        <p role="alert">We couldn't load the visits and invoices just now. Please try again in a moment.</p>
      </Card>
    );
  }
  if (!billing.data) {
    return (
      <Card title="Billed later">
        <p className="text-muted">Loading visits and invoices…</p>
      </Card>
    );
  }
  const { notBilled, notBilledCents, invoices } = billing.data;
  const first = notBilled[0];
  const last = notBilled.at(-1);
  const waiting: Unbilled | undefined =
    first && last
      ? {
          itemId: item.id,
          name: item.name,
          payFromAccountId: item.payFromAccountId,
          visits: notBilled.length,
          amountCents: notBilledCents,
          since: first.date,
          through: last.date,
        }
      : undefined;

  return (
    <Card title="Billed later">
      <h3 className="font-semibold">Not billed yet</h3>
      {waiting === undefined ? (
        <p className="mt-1 text-muted">Nothing is waiting: every visit up to today is on an invoice.</p>
      ) : (
        <>
          <p className="mt-1 text-muted">
            {waiting.visits === 1 ? "1 visit" : `${waiting.visits} visits`} up to today, coming to{" "}
            <span data-amount className="font-semibold text-ink tabular-nums">
              {formatCents(notBilledCents)}
            </span>
            . They stay on the current pay period until you mark them invoiced.
          </p>
          <ul className="mt-2 divide-y divide-line">
            {notBilled.slice(-VISITS_SHOWN).map((visit) => (
              <li key={visit.scheduledDate} className="flex items-center justify-between gap-3 py-1.5">
                <span>{formatDate(visit.date)}</span>
                <Amount cents={visit.amountCents} estimate />
              </li>
            ))}
          </ul>
          {notBilled.length > VISITS_SHOWN && (
            <p className="mt-1 text-sm text-muted">and {notBilled.length - VISITS_SHOWN} earlier, from {formatDay(waiting.since)}.</p>
          )}
          <button type="button" onClick={() => setInvoicing(true)} className={`${BUTTON_PRIMARY} mt-3`}>
            Mark as invoiced
          </button>
        </>
      )}

      <h3 className="mt-5 font-semibold">Invoices</h3>
      {invoices.length === 0 ? (
        <p className="mt-1 text-muted">No invoices yet. When one arrives, mark the visits as invoiced and it becomes a bill to pay.</p>
      ) : (
        <ul className="mt-1 divide-y divide-line">
          {invoices.map((view, index) => (
            <InvoiceRow
              key={view.invoice.id}
              view={view}
              newest={index === 0}
              removing={remove.isPending}
              onRemove={() => remove.mutate(view.invoice.id)}
            />
          ))}
        </ul>
      )}
      {remove.error && (
        <p role="alert" className="mt-2 text-danger">
          {remove.error.message}
        </p>
      )}

      {invoicing && waiting && <InvoiceForm unbilled={waiting} onClose={() => setInvoicing(false)} />}
    </Card>
  );
}

/** One invoice: it opens the pay period that pays it, where it is ticked off. */
function InvoiceRow({ view, newest, removing, onRemove }: { view: InvoiceView; newest: boolean; removing: boolean; onRemove: () => void }) {
  const { invoice, periodStart } = view;
  const status = invoice.paid ? (invoice.paidDate ? `Paid ${formatDay(invoice.paidDate)}` : "Paid") : `Due ${formatDay(invoice.dueDate)}`;
  const words = (
    <>
      <span className="block font-medium">Visits through {formatDate(invoice.coversThrough)}</span>
      <span className="block text-sm text-muted">
        Dated {formatDay(invoice.invoiceDate)} · {status}
      </span>
    </>
  );
  return (
    <li className="flex items-center gap-3 py-2">
      {periodStart === null ? (
        <div className="min-w-0 flex-1">{words}</div>
      ) : (
        <Link
          to={`/periods/${periodStart}`}
          className="-mx-2 min-w-0 flex-1 cursor-pointer rounded-md px-2 py-1 hover:bg-bg/60"
          aria-label={`Visits through ${formatDate(invoice.coversThrough)}, ${status}: open its pay period`}
        >
          {words}
        </Link>
      )}
      <Amount cents={invoice.amountCents} />
      {newest && (
        <button
          type="button"
          onClick={onRemove}
          disabled={removing}
          title="Remove this invoice"
          aria-label="Remove this invoice"
          className="inline-flex size-11 shrink-0 items-center justify-center rounded-lg text-muted hover:bg-bg hover:text-danger"
        >
          <Trash2 aria-hidden className="size-4" />
        </button>
      )}
    </li>
  );
}
