import { HandCoins } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import { useAccounts } from "../../api/accounts";
import { type Entry, type Reimbursement, useSetReimbursement } from "../../api/entries";
import { formatDate } from "../../lib/dates";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { Amount } from "../Amount";
import { Card } from "../Card";
import { EditButton } from "../EditButton";
import { Modal } from "../Modal";
import { ComesOutOfField } from "../reimbursements/ComesOutOfField";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

/**
 * How the rest of an expense being paid back is planned to come back: how much, into which account and about when
 * (Rachel, 2026-10-25: "there is no way to plan for the reimbursement in that account as well so I look short for the
 * year"; "partials are possible"). Budget counts it there as money coming in, until it is recorded as having come.
 */
export function ComingBackCard({ entry, reimbursement }: { entry: Entry; reimbursement: Reimbursement }) {
  const accounts = useAccounts();
  const [editing, setEditing] = useState(false);
  const into = accounts.data?.find((account) => account.id === reimbursement.intoAccountId);
  const from = accounts.data?.find((account) => account.id === reimbursement.fromAccountId);
  const part = reimbursement.amountCents < entry.amountCents;

  return (
    <Card title="Coming back" action={<EditButton name="what comes back" onClick={() => setEditing(true)} />}>
      <dl className="grid gap-4 sm:grid-cols-2">
        <div className="min-w-0">
          <dt className="text-sm text-muted">Comes back</dt>
          <dd className="mt-0.5 font-medium">
            <Amount cents={reimbursement.amountCents} className="-ml-2" />
            <span className="block text-sm text-muted">{part ? `of ${formatCents(entry.amountCents)}` : "all of it"}</span>
          </dd>
        </div>
        <div className="min-w-0">
          <dt className="text-sm text-muted">Into</dt>
          <dd className="mt-0.5 font-medium wrap-break-word">{into?.name ?? <span className="text-muted">Not planned</span>}</dd>
        </div>
        <div className="min-w-0">
          <dt className="text-sm text-muted">Comes out of</dt>
          <dd className="mt-0.5 font-medium wrap-break-word">
            {from?.name ?? <span className="text-muted">None of my accounts</span>}
          </dd>
        </div>
        <div className="min-w-0">
          <dt className="text-sm text-muted">About when</dt>
          <dd className="mt-0.5 font-medium">
            {reimbursement.expectedOn === null ? <span className="text-muted">Not planned</span> : formatDate(reimbursement.expectedOn)}
          </dd>
        </div>
      </dl>
      {reimbursement.owedCents > 0 && reimbursement.expectedOn !== null && (
        <p className="mt-3 text-sm text-muted">
          Budget counts <span data-amount>{formatCents(reimbursement.owedCents)}</span> still owed as coming in then, until you record it.
        </p>
      )}
      {editing && <ComingBackForm entry={entry} reimbursement={reimbursement} onClose={() => setEditing(false)} />}
    </Card>
  );
}

function ComingBackForm({ entry, reimbursement, onClose }: { entry: Entry; reimbursement: Reimbursement; onClose: () => void }) {
  const id = useId();
  const accounts = useAccounts();
  const save = useSetReimbursement(entry.id);
  const [amount, setAmount] = useState(centsToInput(reimbursement.amountCents));
  const [into, setInto] = useState(String(reimbursement.intoAccountId ?? entry.accountId));
  const [on, setOn] = useState(reimbursement.expectedOn ?? "");
  const [from, setFrom] = useState<number | null>(reimbursement.fromAccountId);
  const [problem, setProblem] = useState<string>();
  const choices = (accounts.data ?? []).filter(
    (account) => (!account.archived && ["checking", "savings", "credit_card"].includes(account.type)) || account.id === Number(into),
  );

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const amountCents = parseDollars(amount);
    if (amountCents === null || amountCents <= 0 || amountCents > entry.amountCents) {
      setProblem(amountCents === null ? AMOUNT_HELP : "Please enter how much comes back, more than $0.00 and no more than the expense.");
      return;
    }
    if (on === "") {
      setProblem("Please choose about when it comes back.");
      return;
    }
    setProblem(undefined);
    const { status, submittedOn, to } = reimbursement;
    save.mutate(
      { status, submittedOn, to, amountCents, intoAccountId: Number(into), expectedOn: on, fromAccountId: from === Number(into) ? null : from },
      { onSuccess: () => onClose() },
    );
  };

  const failure = problem ?? save.error?.message;

  return (
    <Modal title="What comes back" icon={HandCoins} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <MoneyField
          label="Comes back"
          value={amount}
          onChange={setAmount}
          hint={`The expense is ${formatCents(entry.amountCents)}. Less is fine, if only part is paid back.`}
          required
        />
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <div>
            <label htmlFor={`${id}-into`} className={LABEL}>
              Comes back into
            </label>
            <select id={`${id}-into`} value={into} onChange={(event) => setInto(event.target.value)} className={INPUT}>
              {choices.map((account) => (
                <option key={account.id} value={account.id}>
                  {account.name}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label htmlFor={`${id}-on`} className={LABEL}>
              About when
            </label>
            <input id={`${id}-on`} type="date" value={on} onChange={(event) => setOn(event.target.value)} required className={INPUT} />
          </div>
        </div>
        <ComesOutOfField value={from === Number(into) ? null : from} onChange={setFrom} intoAccountId={Number(into)} />
        <p className={HINT}>
          Budget plans it as money coming in that day, until you record it. Out of one of your accounts, like an FSA, it's a
          transfer from there.
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
