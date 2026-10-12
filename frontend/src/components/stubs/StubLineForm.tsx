import { ReceiptText } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import type { Account } from "../../api/accounts";
import type { StubLine, StubLineInput } from "../../api/payStubs";
import { centsToInput, parseDollars } from "../../lib/money";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { Picker } from "../Picker";
import { BUTTON_DANGER_OUTLINE, BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";
import { goesToAnAccount, kindOf, LINE_KINDS, type LineKind, partsOf } from "./labels";

interface StubLineFormProps {
  /** The line being changed; leave out to add one the reader missed. */
  line?: StubLine;
  accounts: readonly Account[];
  pending: boolean;
  error?: string;
  onSave: (line: StubLineInput) => void;
  /** Takes the line off the stub; only for one that is there. */
  onRemove?: () => void;
  onClose: () => void;
}

/**
 * One line of a stub in review: what it is called, its amounts, and what it is tracked as.
 * Saving a line says it has been looked at, so it stops being pointed out.
 */
export function StubLineForm({ line, accounts, pending, error, onSave, onRemove, onClose }: StubLineFormProps) {
  const id = useId();
  const [label, setLabel] = useState(line?.label ?? "");
  const [amount, setAmount] = useState(line ? centsToInput(line.amountCents) : "");
  const [ytd, setYtd] = useState(line?.ytdCents != null ? centsToInput(line.ytdCents) : "");
  const [kind, setKind] = useState<LineKind>(line ? kindOf(line) : "pre_tax:benefit");
  const [accountId, setAccountId] = useState<string | null>(line?.accountId != null ? String(line.accountId) : null);
  const [problem, setProblem] = useState<string>();

  const { section, tracksAs } = partsOf(kind);
  // An employer's match is retirement money: it can only go into an investment account.
  const offered = accounts.filter(
    (account) => (!account.archived || String(account.id) === accountId) && (tracksAs !== "match" || account.type === "investment"),
  );
  const options = offered.map((account) => ({ id: String(account.id), label: account.name, color: account.color }));
  const chosen = options.some((option) => option.id === accountId) ? accountId : null;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const amountCents = parseDollars(amount);
    // Only earnings can be less than zero: a correction that takes pay back.
    if (amountCents === null || (amountCents < 0 && section !== "earnings")) {
      setProblem(AMOUNT_HELP);
      return;
    }
    const ytdCents = ytd.trim() === "" ? null : parseDollars(ytd);
    if (ytd.trim() !== "" && ytdCents === null) {
      setProblem("Please enter the year to date as an amount like 1,250.00, or leave it empty.");
      return;
    }
    setProblem(undefined);
    onSave({
      section,
      tracksAs,
      label: label.trim(),
      amountCents,
      ytdCents,
      accountId: goesToAnAccount(tracksAs) && chosen !== null ? Number(chosen) : null,
      sure: true,
    });
  };

  const failure = problem ?? error;
  return (
    <Modal title={line ? `Edit ${line.label}` : "Add a line"} icon={ReceiptText} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <div>
          <label htmlFor={`${id}-label`} className={LABEL}>
            Line on the stub
          </label>
          <input
            id={`${id}-label`}
            value={label}
            onChange={(event) => setLabel(event.target.value)}
            maxLength={80}
            required
            autoComplete="off"
            placeholder="e.g. Dental"
            className={INPUT}
          />
        </div>
        <div className="grid grid-cols-2 gap-4">
          <MoneyField label="This stub" value={amount} onChange={setAmount} required />
          <MoneyField label="Year to date" value={ytd} onChange={setYtd} />
        </div>
        <div>
          <label htmlFor={`${id}-kind`} className={LABEL}>
            Tracks as
          </label>
          <select id={`${id}-kind`} value={kind} onChange={(event) => setKind(event.target.value as LineKind)} className={INPUT}>
            {LINE_KINDS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
        {goesToAnAccount(tracksAs) && (
          <div>
            <Picker
              label={tracksAs === "match" ? "Paid into" : "Goes to"}
              noun="account"
              placeholder="Choose or type an account"
              options={options}
              value={chosen}
              onChange={setAccountId}
              clearable
            />
            <p className={HINT}>
              {tracksAs === "match"
                ? "The investment account your employer pays into. Left empty, the match is not followed."
                : "The account this money lands in, so its balance grows with each paycheck. Left empty, it is only listed."}
            </p>
          </div>
        )}

        {failure && (
          <p role="alert" className="text-danger">
            {failure}
          </p>
        )}

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:items-center sm:justify-end">
          {onRemove && (
            <button type="button" onClick={onRemove} disabled={pending} className={`${BUTTON_DANGER_OUTLINE} sm:mr-auto`}>
              Remove line
            </button>
          )}
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="submit" disabled={pending} className={BUTTON_PRIMARY}>
            {pending ? "Saving…" : line ? "Save line" : "Add line"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
