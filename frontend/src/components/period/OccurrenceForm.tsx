import { HandCoins, Paperclip } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import { Link } from "react-router";
import { useAccounts } from "../../api/accounts";
import { type AttachmentOwner, useAttachments } from "../../api/attachments";
import { REIMBURSEMENT_LABELS } from "../../api/entries";
import { type Occurrence, type OccurrenceEdit, useEditOccurrence } from "../../api/periods";
import type { StatementBudget } from "../../api/statementUploads";
import { useItemReimbursement } from "../../api/reimbursements";
import { formatDay, todayIso } from "../../lib/dates";
import { centsToInput, parseDollars } from "../../lib/money";
import { Receipts } from "../files/Receipts";
import { StatementBudgetPanel, statementBudgetSummary } from "../statements/StatementBudgetPanel";
import { PaidBackPanel } from "../reimbursements/PaidBackPanel";
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
  /**
   * The day it is dated when ticked paid here, unless another is chosen: one inside the pay period it is opened from.
   * Left out (opened from Coming up, outside any period), today: the day it really is being paid.
   */
  paidDate?: string;
  /** For a budget a statement set: what it was budgeted, the charges spent on it and what is still to come. */
  statement?: StatementBudget;
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
export function OccurrenceForm({ occurrence, byHand, fromChecking, paidDate: paidDateWithin, statement, onClose }: OccurrenceFormProps) {
  const id = useId();
  const save = useEditOccurrence();
  const today = todayIso();
  const [amount, setAmount] = useState(centsToInput(occurrence.amountCents));
  const [dueDate, setDueDate] = useState(occurrence.dueDate);
  const [note, setNote] = useState(occurrence.note ?? "");
  const [paid, setPaid] = useState(occurrence.paid);
  const [paidDate, setPaidDate] = useState(occurrence.paidDate ?? paidDateWithin ?? today);
  const [code, setCode] = useState(occurrence.confirmationCode ?? "");
  const [check, setCheck] = useState(occurrence.checkNumber ?? "");
  const [payFrom, setPayFrom] = useState(occurrence.payFromAccountId);
  const accounts = useAccounts();
  // What it can be paid from just this once: open checking, savings and cards, never where it goes (Rachel, 2026-10-20).
  const payFromChoices = (accounts.data ?? []).filter(
    (account) =>
      account.id === occurrence.payFromAccountId ||
      (!account.archived && account.id !== occurrence.payToAccountId && ["checking", "savings", "credit_card"].includes(account.type)),
  );
  const chosenAccount = accounts.data?.find((account) => account.id === payFrom);
  // A check needs a checking account: the one chosen here, once the accounts are in.
  const checking = chosenAccount ? chosenAccount.type === "checking" : fromChecking;
  const [problem, setProblem] = useState<string>();
  // The receipts belong to this one time the item came due, named by the date its schedule gives it.
  const owner: AttachmentOwner = { type: "occurrence", itemId: occurrence.itemId, scheduledDate: occurrence.scheduledDate };
  const attached = useAttachments(owner);
  const [showingFiles, setShowingFiles] = useState(false);
  // A bill or budget paid out to someone can be paid back, this one time it comes due (Rachel, 2026-10-24). An invoice or a
  // planned pack is not a date of the schedule, so it has no flag of its own.
  const canBePaidBack = (occurrence.kind === "bill" || occurrence.kind === "budget") && occurrence.invoiceId == null && !occurrence.plannedPack;
  const reimbursement = useItemReimbursement(occurrence.itemId, canBePaidBack);
  const flagged = reimbursement.data?.payments.find((payment) => payment.scheduledDate === occurrence.scheduledDate);
  const [showingPaidBack, setShowingPaidBack] = useState(false);
  // The statement figures as they were when opened. The pay period reloads the amount and these figures separately
  // after still to come is saved, and for a moment they don't agree; kept here, the panel stays while it saves.
  const [openStatement, setOpenStatement] = useState<StatementBudget>();
  const showingStatement = openStatement !== undefined;

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
      if (checking && check.trim() !== (occurrence.checkNumber ?? "")) {
        edit.checkNumber = check.trim();
      }
    }
    if (payFrom !== occurrence.payFromAccountId) {
      edit.payFromAccountId = payFrom;
    }
    if (Object.keys(edit).length === 0) {
      onClose();
      return;
    }
    save.mutate({ occurrence, edit }, { onSuccess: onClose });
  };

  // Skipping this one time (Rachel, 2026-10-20: "the ability to delete an entry from pay period like if I'm just skipping it that month").
  // The item and its other dates stay; the pay period lists it under Skipped, with Put back. An invoice or a planned pack is
  // something to pay, not a date of the schedule, so it has no Skip.
  const skippable = occurrence.invoiceId == null && !occurrence.plannedPack;
  // One already skipped (listed on its item's page) is put back the same way.
  const skip = () => save.mutate({ occurrence, edit: { skipped: !occurrence.skipped } }, { onSuccess: onClose });

  const failure = problem ?? save.error?.message;
  const fileCount = attached.data?.attachments.length ?? 0;

  return (
    <Modal
      title={
        showingFiles
          ? `Receipts for ${occurrence.name}`
          : showingPaidBack
            ? `Paid back — ${occurrence.name}`
            : showingStatement
              ? `${occurrence.name} — from statements`
              : `Edit ${occurrence.name}`
      }
      icon={showingPaidBack ? HandCoins : KIND_ICONS[occurrence.kind]}
      onClose={onClose}
    >
      {/* The receipts take the form's place rather than opening over it, so there is one dialog and it
          still fits the screen. What was typed in the form waits, unsaved, until Back. */}
      {showingFiles && (
        <div className="space-y-4">
          <p className="text-muted">For this one, due {formatDay(occurrence.dueDate)}. Files are kept as soon as they are added.</p>
          <Receipts owner={owner} compact />
          <div className="flex justify-end">
            <button type="button" onClick={() => setShowingFiles(false)} className={BUTTON_SECONDARY}>
              Back
            </button>
          </div>
        </div>
      )}
      {showingPaidBack && <PaidBackPanel occurrence={occurrence} onBack={() => setShowingPaidBack(false)} />}
      {openStatement && <StatementBudgetPanel budget={openStatement} onBack={() => setOpenStatement(undefined)} onSaved={onClose} />}
      <form onSubmit={submit} hidden={showingFiles || showingPaidBack || showingStatement} className="space-y-4">
        <p className="text-muted">
          {occurrence.coversThrough
            ? `The invoice for visits through ${formatDay(occurrence.coversThrough)}, due ${formatDay(occurrence.dueDate)}.`
            : `Just this one, due ${formatDay(occurrence.dueDate)}. The others stay as they are.`}
        </p>

        <MoneyField label={occurrence.invoiceId != null ? "Amount billed" : "Amount this time"} value={amount} onChange={setAmount} required />
        {statement && (
          <button
            type="button"
            onClick={() => setOpenStatement(statement)}
            className="flex min-h-11 w-full items-center justify-between gap-2 rounded-lg border border-line bg-surface px-4 py-2 text-left"
          >
            <span>
              <span className="block font-semibold">
                From statements · {statement.charges.length === 1 ? "1 charge" : `${statement.charges.length} charges`}
              </span>
              <span className="block text-sm text-muted">{statementBudgetSummary(statement)}</span>
            </span>
            <span aria-hidden>›</span>
          </button>
        )}

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

            <div className={checking ? "grid grid-cols-2 gap-3" : undefined}>
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
              {checking && (
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

        {/* Side by side, on a phone too, so the form fits one screen with Skip this one. */}
        <div className={occurrence.kind === "bucket_move" ? undefined : "grid grid-cols-2 gap-3"}>
          {occurrence.kind !== "bucket_move" && (
            <div>
              <label htmlFor={`${id}-from`} className={LABEL}>
                Paid from, this one
              </label>
              <select id={`${id}-from`} value={payFrom} onChange={(event) => setPayFrom(Number(event.target.value))} className={INPUT}>
                {payFromChoices.length === 0 && <option value={payFrom}>Its usual account</option>}
                {payFromChoices.map((account) => (
                  <option key={account.id} value={account.id}>
                    {account.name}
                  </option>
                ))}
              </select>
            </div>
          )}
          <div>
            <label htmlFor={`${id}-note`} className={LABEL}>
              Note (optional)
            </label>
            <input id={`${id}-note`} value={note} onChange={(event) => setNote(event.target.value)} maxLength={NOTE_MAX} autoComplete="off" className={INPUT} />
          </div>
        </div>

        <div className="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
          <p>
            <Link to={`/recurring/${occurrence.itemId}`} className="font-semibold text-accent-ink underline">
              Change {occurrence.name} itself
            </Link>
            <span className="block text-sm text-muted">Its name, schedule, accounts, or its amount from now on.</span>
          </p>
          <div className="flex shrink-0 flex-wrap gap-2">
            {canBePaidBack && (
              <button
                type="button"
                onClick={() => setShowingPaidBack(true)}
                className="inline-flex min-h-11 items-center gap-2 rounded-lg border border-line bg-surface px-3 font-semibold"
              >
                <HandCoins aria-hidden className="size-4" />
                {flagged ? `Paid back · ${REIMBURSEMENT_LABELS[flagged.reimbursement.status]}` : "Paid back?"}
              </button>
            )}
            <button
              type="button"
              onClick={() => setShowingFiles(true)}
              className="inline-flex min-h-11 items-center gap-2 rounded-lg border border-line bg-surface px-3 font-semibold"
            >
              <Paperclip aria-hidden className="size-4" />
              {fileCount === 0 ? "Add a receipt" : fileCount === 1 ? "1 receipt" : `${fileCount} receipts`}
            </button>
          </div>
        </div>

        {failure && (
          <p role="alert" className="text-danger">
            {failure}
          </p>
        )}

        {/* On a phone, Save on top and the other two side by side under it, so the form still fits one screen. */}
        <div className="grid grid-cols-2 gap-2 sm:flex sm:justify-end">
          {skippable && (
            <button type="button" onClick={skip} disabled={save.isPending} className={`${BUTTON_SECONDARY} sm:mr-auto`}>
              {occurrence.skipped ? "Put back" : "Skip this one"}
            </button>
          )}
          <button type="button" onClick={onClose} className={`${BUTTON_SECONDARY} ${skippable ? "" : "col-span-2"}`}>
            Cancel
          </button>
          <button type="submit" disabled={save.isPending} className={`${BUTTON_PRIMARY} order-first col-span-2 sm:order-none`}>
            {save.isPending ? "Saving…" : "Save"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
