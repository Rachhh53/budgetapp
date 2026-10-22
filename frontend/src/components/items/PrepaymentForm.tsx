import { Wallet } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import { useAccounts } from "../../api/accounts";
import { useAddPrepayment } from "../../api/paidAhead";
import { useDefaultDate } from "../../api/settings";
import { centsToInput, parseDollars } from "../../lib/money";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { Picker } from "../Picker";
import { DefaultDateHint } from "../settings/DefaultDateHint";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

/** The longest note the backend keeps on an entry. */
const NOTE_MAX = 500;

/**
 * "Record a payment" for something paid ahead (spec §4.6): what was paid, the day, and the account
 * it came out of. It is saved as a one-off, so it is spending on that day like any other, and its
 * sessions then use it up.
 */
export function PrepaymentForm({
  itemId,
  name,
  payFromAccountId,
  priceCents,
  onClose,
}: {
  itemId: number;
  name: string;
  /** Offered first: the account the item is paid from. */
  payFromAccountId: number;
  /** Bought as a pack: its price, filled in to start with; left out, the amount starts empty. */
  priceCents?: number | null;
  onClose: () => void;
}) {
  const id = useId();
  const add = useAddPrepayment(itemId);
  const accounts = useAccounts();
  const [amount, setAmount] = useState(priceCents != null ? centsToInput(priceCents) : "");
  const [date, setDate] = useState(useDefaultDate().date);
  const [accountId, setAccountId] = useState<string | null>(String(payFromAccountId));
  const [note, setNote] = useState("");
  const [problem, setProblem] = useState<string>();

  // Archived accounts are left out, unless the item is still paid from one.
  const options = (accounts.data ?? [])
    .filter((account) => !account.archived || account.id === payFromAccountId)
    .map((account) => ({ id: String(account.id), label: account.name, color: account.color }));

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const amountCents = parseDollars(amount);
    if (amountCents === null || amountCents <= 0) {
      setProblem(amountCents === 0 ? "Please enter what was paid, more than $0.00." : AMOUNT_HELP);
      return;
    }
    if (date === "" || accountId === null) {
      setProblem("Please fill in the day it was paid and the account it came out of.");
      return;
    }
    setProblem(undefined);
    add.mutate(
      { date, amountCents, accountId: Number(accountId), note: note.trim() === "" ? undefined : note.trim() },
      { onSuccess: onClose },
    );
  };

  const failure = problem ?? add.error?.message;

  return (
    <Modal title={`Record a payment for ${name}`} icon={Wallet} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <p className="text-muted">Each session uses up some of what you paid. It's saved as a one-off, so it counts as spending on the day you paid.</p>

        <MoneyField label="Amount paid" value={amount} onChange={setAmount} required />

        <div>
          <label htmlFor={`${id}-date`} className={LABEL}>
            Paid on
          </label>
          <input id={`${id}-date`} type="date" value={date} onChange={(event) => setDate(event.target.value)} className={INPUT} />
          <DefaultDateHint value={date} />
        </div>

        <Picker label="Paid from" noun="account" placeholder="Choose or type an account" options={options} value={accountId} onChange={setAccountId} />

        <div>
          <label htmlFor={`${id}-note`} className={LABEL}>
            Note (optional)
          </label>
          <input
            id={`${id}-note`}
            value={note}
            onChange={(event) => setNote(event.target.value)}
            maxLength={NOTE_MAX}
            autoComplete="off"
            aria-describedby={`${id}-note-hint`}
            className={INPUT}
          />
          <p id={`${id}-note-hint`} className={HINT}>
            Like "Fall term".
          </p>
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
            {add.isPending ? "Saving…" : "Save payment"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
