import { ReceiptText } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import { type Unbilled, useAddInvoice } from "../../api/invoices";
import { useDefaultDate } from "../../api/settings";
import { formatDay } from "../../lib/dates";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { DefaultDateHint } from "../settings/DefaultDateHint";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

/** The longest note the backend keeps on an invoice. */
const NOTE_MAX = 500;

/**
 * "Mark as invoiced" (spec §4.6): the invoice for visits that have built up. It starts at what
 * the visits came to, and is changed to what the invoice really says. Once saved it is a bill to
 * pay on its due date, ticked off like any other.
 */
export function InvoiceForm({ unbilled, onClose }: { unbilled: Unbilled; onClose: () => void }) {
  const id = useId();
  const add = useAddInvoice(unbilled.itemId);
  const prefilled = centsToInput(unbilled.amountCents);
  const [amount, setAmount] = useState(prefilled);
  const [invoiceDate, setInvoiceDate] = useState(useDefaultDate().date);
  const [dueDate, setDueDate] = useState("");
  const [coversThrough, setCoversThrough] = useState(unbilled.through);
  const [note, setNote] = useState("");
  const [problem, setProblem] = useState<string>();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const amountCents = parseDollars(amount);
    if (amountCents === null || amountCents < 0) {
      setProblem(AMOUNT_HELP);
      return;
    }
    if (invoiceDate === "" || dueDate === "" || coversThrough === "") {
      setProblem("Please fill in the invoice date, when it is due, and the last visit it covers.");
      return;
    }
    setProblem(undefined);
    add.mutate(
      {
        invoiceDate,
        dueDate,
        coversThrough,
        // Left at what the visits came to, the backend adds up exactly the visits the dates cover.
        amountCents: amount === prefilled ? undefined : amountCents,
        note: note.trim() === "" ? undefined : note.trim(),
      },
      { onSuccess: onClose },
    );
  };

  const failure = problem ?? add.error?.message;
  const visits = unbilled.visits === 1 ? "1 visit" : `${unbilled.visits} visits`;

  return (
    <Modal title={`Mark ${unbilled.name} as invoiced`} icon={ReceiptText} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <p className="text-muted">
          {visits} from {formatDay(unbilled.since)} to {formatDay(unbilled.through)} came to{" "}
          <span data-amount className="font-semibold text-ink tabular-nums">
            {formatCents(unbilled.amountCents)}
          </span>
          . Enter the invoice as it reads; it becomes a bill to pay on its due date.
        </p>

        <MoneyField label="Amount billed" value={amount} onChange={setAmount} required />

        <div className="grid grid-cols-2 gap-3">
          <div>
            <label htmlFor={`${id}-dated`} className={LABEL}>
              Invoice date
            </label>
            <input id={`${id}-dated`} type="date" value={invoiceDate} onChange={(event) => setInvoiceDate(event.target.value)} className={INPUT} />
          </div>
          <div>
            <label htmlFor={`${id}-due`} className={LABEL}>
              Due on
            </label>
            <input id={`${id}-due`} type="date" value={dueDate} min={invoiceDate} onChange={(event) => setDueDate(event.target.value)} className={INPUT} />
          </div>
        </div>
        <DefaultDateHint value={invoiceDate} />

        <div>
          <label htmlFor={`${id}-covers`} className={LABEL}>
            Covers visits through
          </label>
          <input
            id={`${id}-covers`}
            type="date"
            value={coversThrough}
            min={unbilled.since}
            onChange={(event) => setCoversThrough(event.target.value)}
            aria-describedby={`${id}-covers-hint`}
            className={INPUT}
          />
          <p id={`${id}-covers-hint`} className={HINT}>
            Visits after this day stay on the pay period until the next invoice.
          </p>
        </div>

        <div>
          <label htmlFor={`${id}-note`} className={LABEL}>
            Note (optional)
          </label>
          <input id={`${id}-note`} value={note} onChange={(event) => setNote(event.target.value)} maxLength={NOTE_MAX} autoComplete="off" className={INPUT} />
        </div>

        {failure && (
          <p role="alert" className="text-danger">
            {failure}
          </p>
        )}

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="submit" disabled={add.isPending} className={BUTTON_PRIMARY}>
            {add.isPending ? "Saving…" : "Save invoice"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
