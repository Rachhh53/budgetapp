import { Wallet } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import { type Account, useDeleteBalance, useSaveBalance } from "../../api/accounts";
import { todayIso } from "../../lib/dates";
import { useDefaultDate } from "../../api/settings";
import { centsToInput, parseDollars } from "../../lib/money";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { DefaultDateHint } from "../settings/DefaultDateHint";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

/**
 * "The real number is X": records the balance an account actually had on a day.
 * Entering the same day again replaces what was there, which is how a typo is fixed.
 *
 * @param editing a balance already entered, to change: moved to another day, the one on its old day goes
 */
export function BalanceForm({
  account,
  editing,
  onClose,
}: {
  account: Account;
  editing?: { entryId: number; date: string; amountCents: number; note: string | null };
  onClose: () => void;
}) {
  const id = useId();
  const save = useSaveBalance(account.id);
  const remove = useDeleteBalance(account.id);
  const today = todayIso();
  const defaultDate = useDefaultDate().date;
  const [date, setDate] = useState(editing?.date ?? defaultDate);
  const [amount, setAmount] = useState(editing ? centsToInput(editing.amountCents) : "");
  const [note, setNote] = useState(editing?.note ?? "");
  const [amountError, setAmountError] = useState<string>();

  const owed = account.type === "credit_card" || account.type === "loan";

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const amountCents = parseDollars(amount);
    if (amountCents === null) {
      setAmountError(AMOUNT_HELP);
      return;
    }
    setAmountError(undefined);
    save.mutate(
      { date, amountCents, note: note.trim() || null },
      {
        onSuccess: () => {
          if (editing && editing.date !== date) {
            remove.mutate(editing.entryId, { onSuccess: onClose });
          } else {
            onClose();
          }
        },
      },
    );
  };

  return (
    <Modal title={`${editing ? "Change balance" : "Update balance"} — ${account.name}`} icon={Wallet} tone={account.color} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <MoneyField
          label={owed ? "Amount owed" : "Balance"}
          value={amount}
          onChange={setAmount}
          error={amountError}
          hint={owed ? "What the statement or website says you owe." : "What the bank's website or statement shows."}
          required
          hero
        />
        <div>
          <label htmlFor={`${id}-date`} className={LABEL}>
            As of
          </label>
          <input
            id={`${id}-date`}
            type="date"
            value={date}
            max={today}
            onChange={(event) => setDate(event.target.value)}
            required
            className={INPUT}
          />
          <p className={HINT}>Today or earlier. A day you already entered is replaced.</p>
          <DefaultDateHint value={date} />
        </div>
        <div>
          <label htmlFor={`${id}-note`} className={LABEL}>
            Note (optional)
          </label>
          <input
            id={`${id}-note`}
            value={note}
            onChange={(event) => setNote(event.target.value)}
            maxLength={200}
            autoComplete="off"
            className={INPUT}
          />
        </div>

        {(save.error ?? remove.error) && (
          <p role="alert" className="text-danger">
            {(save.error ?? remove.error)?.message}
          </p>
        )}

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="submit" disabled={save.isPending} className={BUTTON_PRIMARY}>
            {save.isPending ? "Saving…" : "Save balance"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
