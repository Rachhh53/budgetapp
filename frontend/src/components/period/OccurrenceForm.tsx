import { type FormEvent, useId, useState } from "react";
import { Link } from "react-router";
import { type Occurrence, type OccurrenceEdit, useEditOccurrence } from "../../api/periods";
import { formatDay, todayIso } from "../../lib/dates";
import { centsToInput, parseDollars } from "../../lib/money";
import { KIND_ICONS } from "../items/labels";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, INPUT, LABEL } from "../ui";

/** The longest note the backend keeps on one occurrence. */
const NOTE_MAX = 500;

interface OccurrenceFormProps {
  occurrence: Occurrence;
  /** Paid by hand, so it can be ticked off, with the day and a confirmation code. Auto-pay and budgets only have an amount. */
  byHand: boolean;
  /** Paid from a checking account, so a check may have been written for it. */
  fromChecking: boolean;
  onClose: () => void;
}

/**
 * What opens from a row of a pay period: this one time the item comes round, and nothing else.
 * Its amount, the day it is due, whether it was paid and when, the code or check it was paid
 * with, and a note — what the row lets you change in place, and what it has no room for.
 *
 * The item itself (its name, schedule, accounts, the amount from now on) is changed on its own
 * page, a card at a time, which this links to. That keeps the form short enough never to scroll.
 */
export function OccurrenceForm({ occurrence, byHand, fromChecking, onClose }: OccurrenceFormProps) {
  const id = useId();
  const save = useEditOccurrence();
  const today = todayIso();
  const [amount, setAmount] = useState(centsToInput(occurrence.amountCents));
  const [dueDate, setDueDate] = useState(occurrence.dueDate);
  const [note, setNote] = useState(occurrence.note ?? "");
  const [paid, setPaid] = useState(occurrence.paid);
  const [paidDate, setPaidDate] = useState(occurrence.paidDate ?? today);
  const [code, setCode] = useState(occurrence.confirmationCode ?? "");
  const [check, setCheck] = useState(occurrence.checkNumber ?? "");
  const [problem, setProblem] = useState<string>();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const amountCents = parseDollars(amount);
    if (amountCents === null || amountCents < 0) {
      setProblem(AMOUNT_HELP);
      return;
    }
    if (dueDate === "") {
      setProblem("Please choose the day this one is due.");
      return;
    }
    if (byHand && paid && paidDate === "") {
      setProblem("Please choose the day it was paid.");
      return;
    }
    setProblem(undefined);
    // Only what was changed is sent, so an amount left alone stays the app's own figure.
    const edit: OccurrenceEdit = {};
    if (amountCents !== occurrence.amountCents) {
      edit.amountCents = amountCents;
    }
    if (dueDate !== occurrence.dueDate) {
      edit.dueDate = dueDate;
    }
    if (note.trim() !== (occurrence.note ?? "")) {
      edit.note = note.trim();
    }
    if (byHand) {
      if (paid !== occurrence.paid || (paid && paidDate !== occurrence.paidDate)) {
        edit.paid = paid;
        if (paid) {
          edit.paidDate = paidDate;
        }
      }
      if (code.trim() !== (occurrence.confirmationCode ?? "")) {
        edit.confirmationCode = code.trim();
      }
      if (fromChecking && check.trim() !== (occurrence.checkNumber ?? "")) {
        edit.checkNumber = check.trim();
      }
    }
    if (Object.keys(edit).length === 0) {
      onClose();
      return;
    }
    save.mutate({ occurrence, edit }, { onSuccess: onClose });
  };

  const failure = problem ?? save.error?.message;

  return (
    <Modal title={`Edit ${occurrence.name}`} icon={KIND_ICONS[occurrence.kind]} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <p className="text-muted">Just this one, due {formatDay(occurrence.dueDate)}. The others stay as they are.</p>

        <MoneyField label="Amount this time" value={amount} onChange={setAmount} required />

        {/* Side by side on a phone too, so the form stays one screen: the day it is due, and the day it was paid. */}
        <div className={byHand ? "grid grid-cols-2 gap-3" : undefined}>
          <div>
            <label htmlFor={`${id}-due`} className={LABEL}>
              Due on
            </label>
            <input id={`${id}-due`} type="date" value={dueDate} onChange={(event) => setDueDate(event.target.value)} className={INPUT} />
          </div>
          {byHand && (
            <div>
              {/* The tick is the label's place; the date under it is for when it is ticked. */}
              <label className="mb-1 flex items-center gap-2 font-medium">
                <input type="checkbox" checked={paid} onChange={(event) => setPaid(event.target.checked)} className="size-5 accent-(--accent)" />
                Paid
              </label>
              <input
                type="date"
                aria-label="Paid on"
                value={paid ? paidDate : ""}
                max={today}
                disabled={!paid}
                onChange={(event) => setPaidDate(event.target.value)}
                className={`${INPUT} disabled:opacity-50`}
              />
            </div>
          )}
        </div>

        {byHand && (
          <>

            <div className={fromChecking ? "grid grid-cols-2 gap-3" : undefined}>
              <div>
                <label htmlFor={`${id}-code`} className={LABEL}>
                  Confirmation code
                </label>
                <input
                  id={`${id}-code`}
                  value={code}
                  onChange={(event) => setCode(event.target.value)}
                  maxLength={60}
                  autoComplete="off"
                  className={`${INPUT} font-mono`}
                />
              </div>
              {fromChecking && (
                <div>
                  <label htmlFor={`${id}-check`} className={LABEL}>
                    Check number
                  </label>
                  <input
                    id={`${id}-check`}
                    value={check}
                    onChange={(event) => setCheck(event.target.value)}
                    maxLength={60}
                    inputMode="numeric"
                    autoComplete="off"
                    className={`${INPUT} font-mono`}
                  />
                </div>
              )}
            </div>
          </>
        )}

        <div>
          <label htmlFor={`${id}-note`} className={LABEL}>
            Note (optional)
          </label>
          <input id={`${id}-note`} value={note} onChange={(event) => setNote(event.target.value)} maxLength={NOTE_MAX} autoComplete="off" className={INPUT} />
        </div>

        <p>
          <Link to={`/recurring/${occurrence.itemId}`} className="font-semibold text-accent-ink underline">
            Change {occurrence.name} itself
          </Link>
          <span className="block text-sm text-muted">Its name, schedule, accounts, or its amount from now on.</span>
        </p>

        {failure && (
          <p role="alert" className="text-danger">
            {failure}
          </p>
        )}

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="submit" disabled={save.isPending} className={BUTTON_PRIMARY}>
            {save.isPending ? "Saving…" : "Save"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
