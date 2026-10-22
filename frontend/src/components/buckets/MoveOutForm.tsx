import { ArrowUpFromLine } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import { useAccounts } from "../../api/accounts";
import { type MoveOut, useBuckets, useDeleteBucketChange, useSaveMoveOut } from "../../api/buckets";
import { useCategories, useTags } from "../../api/categories";
import type { Entry } from "../../api/entries";
import { todayIso } from "../../lib/dates";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { CategoryField, TagsField } from "../categories/LabelFields";
import { useOwedExpenses } from "../add/owedExpenses";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { Picker } from "../Picker";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

const NOTE_MAX = 200;

/**
 * Moves money out of a bucket to money not in a bucket (Rachel, 2026-10-20: "I can't transfer within
 * an account from a bucket to general fund as income in pay period"), or changes or deletes such a
 * move. The account's balance stays as it is; what is available goes up, and the pay period lists it
 * under Income, with a category and tags like a one-off. It can pay yourself back for an expense, counted
 * toward what came back for it like money coming in (Rachel, 2026-10-21: "if I am reimbursing myself from a
 * bucket, this doesn't work").
 *
 * @param move     the move to change; left out to add one
 * @param date     the day a new one opens on
 * @param paysBack the expense a new one pays back, when opened from it: what is still owed is the likely amount
 */
export function MoveOutForm({
  move,
  date: startDate,
  paysBack,
  onClose,
}: {
  move?: MoveOut;
  date?: string;
  paysBack?: Entry;
  onClose: () => void;
}) {
  const id = useId();
  const buckets = useBuckets();
  const accounts = useAccounts();
  const categories = useCategories();
  const tags = useTags();
  const save = useSaveMoveOut();
  const [bucketId, setBucketId] = useState(move ? String(move.bucketId) : "");
  const remove = useDeleteBucketChange(Number(bucketId));
  const owed = paysBack?.reimbursement?.owedCents;
  const [amount, setAmount] = useState(move ? centsToInput(move.amountCents) : owed ? centsToInput(owed) : "");
  const [date, setDate] = useState(move?.date ?? startDate ?? todayIso());
  const [note, setNote] = useState(move?.note ?? (paysBack ? `Reimbursement: ${paysBack.payee ?? paysBack.description}` : ""));
  const [paysBackId, setPaysBackId] = useState<string | null>(
    move?.reimbursesEntryId != null ? String(move.reimbursesEntryId) : paysBack ? String(paysBack.id) : null,
  );
  const owedExpenses = useOwedExpenses(true, paysBackId, paysBack);
  const [categoryId, setCategoryId] = useState<number | null>(move?.categoryId ?? null);
  const [tagIds, setTagIds] = useState<number[]>(move?.tagIds ?? []);
  const [problem, setProblem] = useState<string>();

  // A move already saved stays in its bucket, even one archived since.
  const open = (buckets.data ?? []).filter((bucket) => !bucket.archived || bucket.id === move?.bucketId);
  const accountName = (accountId: number) => accounts.data?.find((account) => account.id === accountId)?.name ?? "An account";

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const amountCents = parseDollars(amount);
    if (bucketId === "") {
      setProblem("Please choose the bucket the money comes out of.");
      return;
    }
    if (amountCents === null || amountCents <= 0) {
      setProblem(amountCents === null ? AMOUNT_HELP : "Please enter how much to move out of the bucket, more than $0.00.");
      return;
    }
    if (date === "") {
      setProblem("Please choose the date.");
      return;
    }
    if (note.trim() === "") {
      setProblem("Please say what this is, like Back to spending.");
      return;
    }
    setProblem(undefined);
    save.mutate(
      {
        bucketId: Number(bucketId),
        changeId: move?.id,
        date,
        amountCents,
        note: note.trim(),
        categoryId,
        tagIds,
        reimbursesEntryId: paysBackId === null ? null : Number(paysBackId),
      },
      { onSuccess: onClose },
    );
  };

  return (
    <Modal title={move ? "Money out of a bucket" : "Move money out of a bucket"} icon={ArrowUpFromLine} onClose={onClose}>
      <form onSubmit={submit} noValidate className="space-y-4">
        {/* Amount and date sit side by side even on a phone, so the form fits one screen there (§6). */}
        <div className="grid grid-cols-2 gap-x-4 gap-y-3">
          <div className="col-span-2">
            <label htmlFor={`${id}-bucket`} className={LABEL}>
              Out of
            </label>
            <select
              id={`${id}-bucket`}
              value={bucketId}
              onChange={(event) => setBucketId(event.target.value)}
              disabled={move !== undefined}
              aria-describedby={`${id}-bucket-hint`}
              className={INPUT}
            >
              <option value="">Choose a bucket</option>
              {open.map((bucket) => (
                <option key={bucket.id} value={bucket.id}>
                  {accountName(bucket.accountId)} · {bucket.name} (holds {formatCents(bucket.balanceCents)} today)
                </option>
              ))}
            </select>
            <p id={`${id}-bucket-hint`} className={HINT}>
              The account's balance stays the same.
            </p>
          </div>
          <MoneyField label="Amount" value={amount} onChange={setAmount} />
          <div>
            <label htmlFor={`${id}-date`} className={LABEL}>
              Date
            </label>
            <input id={`${id}-date`} type="date" value={date} onChange={(event) => setDate(event.target.value)} className={INPUT} />
          </div>
          <div className="col-span-2">
            <label htmlFor={`${id}-note`} className={LABEL}>
              What is it
            </label>
            <input
              id={`${id}-note`}
              value={note}
              onChange={(event) => setNote(event.target.value)}
              maxLength={NOTE_MAX}
              placeholder="Back to spending"
              className={INPUT}
            />
          </div>
          <div className="col-span-2 sm:col-span-1">
            <CategoryField categories={categories.data ?? []} value={categoryId} onChange={setCategoryId} />
          </div>
          <div className="col-span-2 sm:col-span-1">
            <TagsField tags={tags.data ?? []} value={tagIds} onChange={setTagIds} />
          </div>
          {(owedExpenses.length > 0 || paysBackId !== null) && (
            <div className="col-span-2">
              <Picker
                label="Pays back an expense (optional)"
                noun="expense"
                placeholder="Choose the expense this pays back"
                options={owedExpenses}
                value={paysBackId}
                onChange={setPaysBackId}
                clearable
              />
            </div>
          )}
        </div>

        {(problem ?? save.error?.message ?? remove.error?.message) && (
          <p role="alert" className="text-danger">
            {problem ?? save.error?.message ?? remove.error?.message}
          </p>
        )}

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          {move && (
            <button
              type="button"
              onClick={() => remove.mutate(move.id, { onSuccess: onClose })}
              disabled={remove.isPending}
              className={`${BUTTON_SECONDARY} sm:mr-auto`}
            >
              Delete
            </button>
          )}
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="submit" disabled={save.isPending} className={BUTTON_PRIMARY}>
            {save.isPending ? "Saving…" : move ? "Save" : "Move it"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
