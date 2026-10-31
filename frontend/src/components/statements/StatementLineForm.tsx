import { ChevronLeft, ChevronRight, ReceiptText } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import type { Category, Tag } from "../../api/categories";
import { type LineKind, type StatementLine, useChangeStatementLine } from "../../api/statementUploads";
import { formatDate } from "../../lib/dates";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { CategoryField, TagsField } from "../categories/LabelFields";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

export const KIND_LABELS: Record<LineKind, string> = {
  charge: "Spending",
  refund: "A refund",
  payment: "A payment to the card",
  transfer: "A transfer or deposit",
};

/**
 * One statement line: what it is, whether it is left out, and the category and tags it takes if it is added as a one-off
 * (remembered for its payee, and given to the payee's other lines with nothing on them yet; Rachel, 2026-10-30: "one offs
 * are probably the only ones that even need category/tag"). What was read (the day, the words, the amount) can be put
 * right under Fix what was read, a second step in place of the other fields (a CSV or OFX line is rarely wrong), so the
 * form fits one screen either way.
 */
export function StatementLineForm({
  uploadId,
  line,
  categories,
  tags,
  onFiled,
  onClose,
}: {
  uploadId: number;
  line: StatementLine;
  categories: readonly Category[];
  tags: readonly Tag[];
  /** Told how many other lines from the payee were filed the same way. */
  onFiled: (alsoFiled: number) => void;
  onClose: () => void;
}) {
  const id = useId();
  const change = useChangeStatementLine(uploadId);
  const [categoryId, setCategoryId] = useState(line.categoryId);
  const [tagIds, setTagIds] = useState<number[]>(line.tagIds);
  const [kind, setKind] = useState(line.kind);
  const [leftOut, setLeftOut] = useState(line.leftOut);
  const [txnDate, setTxnDate] = useState(line.txnDate);
  const [description, setDescription] = useState(line.description);
  // Typed as spent; money back has a minus.
  const [amount, setAmount] = useState(centsToInput(line.amountCents));
  const [amountError, setAmountError] = useState<string>();
  const [fixing, setFixing] = useState(false);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const amountCents = parseDollars(amount);
    if (amountCents === null) {
      setFixing(true);
      setAmountError(AMOUNT_HELP);
      return;
    }
    setAmountError(undefined);
    change.mutate(
      {
        lineId: line.id,
        input: { categoryId, tagIds, kind, leftOut, txnDate, description: description.trim(), amountCents },
      },
      {
        onSuccess: ({ alsoFiled }) => {
          onFiled(alsoFiled);
          onClose();
        },
      },
    );
  };

  return (
    <Modal title={`${line.description} · ${formatCents(line.amountCents)}`} icon={ReceiptText} onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        {fixing ? (
          <FixWhatWasRead
            id={id}
            txnDate={txnDate}
            setTxnDate={setTxnDate}
            amount={amount}
            setAmount={setAmount}
            amountError={amountError}
            description={description}
            setDescription={setDescription}
            onBack={() => setFixing(false)}
          />
        ) : (
          <>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <div>
                <label htmlFor={`${id}-kind`} className={LABEL}>
                  What it is
                </label>
                <select id={`${id}-kind`} value={kind} onChange={(event) => setKind(event.target.value as LineKind)} className={INPUT}>
                  {(Object.keys(KIND_LABELS) as LineKind[]).map((choice) => (
                    <option key={choice} value={choice}>
                      {KIND_LABELS[choice]}
                    </option>
                  ))}
                </select>
              </div>
              <label className="flex min-h-11 items-center gap-2 self-end font-medium">
                <input type="checkbox" checked={leftOut} onChange={(event) => setLeftOut(event.target.checked)} className="size-5" />
                Leave it out
              </label>
            </div>
            <CategoryField categories={categories} value={categoryId} onChange={setCategoryId} />
            <TagsField tags={tags} value={tagIds} onChange={setTagIds} />
            <p className={HINT}>Only a one-off uses these. Remembered for this payee: its other charges and the next statement get the same.</p>
            {line.duplicateOf !== null && (
              <p className={HINT}>An earlier statement of this account has this line too, so it is counted there, not here.</p>
            )}
            <button type="button" onClick={() => setFixing(true)} className="flex min-h-11 w-full items-center justify-between gap-2 rounded-lg border border-line bg-surface px-4 text-left font-semibold">
              Fix what was read · {formatDate(txnDate)}
              <ChevronRight aria-hidden className="size-4" />
            </button>
          </>
        )}
        {change.error && (
          <p role="alert" className="text-danger">
            {change.error.message}
          </p>
        )}
        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="submit" disabled={change.isPending} className={BUTTON_PRIMARY}>
            {change.isPending ? "Saving…" : "Save line"}
          </button>
        </div>
      </form>
    </Modal>
  );
}

/** The second step: the day, amount and words as read, to put right. Back returns to filing; nothing is saved until Save line. */
function FixWhatWasRead({
  id,
  txnDate,
  setTxnDate,
  amount,
  setAmount,
  amountError,
  description,
  setDescription,
  onBack,
}: {
  id: string;
  txnDate: string;
  setTxnDate: (date: string) => void;
  amount: string;
  setAmount: (amount: string) => void;
  amountError: string | undefined;
  description: string;
  setDescription: (description: string) => void;
  onBack: () => void;
}) {
  return (
    <>
      <button type="button" onClick={onBack} className="inline-flex min-h-11 items-center gap-1 font-semibold text-accent-ink">
        <ChevronLeft aria-hidden className="size-4" />
        Back
      </button>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <div>
          <label htmlFor={`${id}-date`} className={LABEL}>
            Day spent
          </label>
          <input id={`${id}-date`} type="date" value={txnDate} onChange={(event) => setTxnDate(event.target.value)} required className={INPUT} />
        </div>
        <MoneyField label="Amount (money back with a minus)" value={amount} onChange={setAmount} error={amountError} required />
        <div className="sm:col-span-2">
          <label htmlFor={`${id}-description`} className={LABEL}>
            Description
          </label>
          <input
            id={`${id}-description`}
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            maxLength={120}
            required
            autoComplete="off"
            className={INPUT}
          />
        </div>
      </div>
    </>
  );
}
