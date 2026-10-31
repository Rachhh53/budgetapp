import { type FormEvent, useState } from "react";
import { Link } from "react-router";
import { type StatementBudget, useStillToComeOnPeriod } from "../../api/statementUploads";
import { formatDay } from "../../lib/dates";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { Amount } from "../Amount";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, HINT } from "../ui";

/**
 * "Budgeted $200.00 · spent $54.20 · still to come $0.00": what a budget date a statement set is made of, with what
 * other accounts' statements spent on it (as one-offs there) when there is any.
 */
export function statementBudgetSummary(budget: StatementBudget): string {
  const elsewhere = budget.elsewhereCents > 0 ? ` · ${formatCents(budget.elsewhereCents)} on other accounts` : "";
  return `Budgeted ${formatCents(budget.budgetedCents)} · spent ${formatCents(budget.actualCents)}${elsewhere} · still to come ${formatCents(budget.stillToComeCents)}`;
}

/**
 * A budget date a statement set, opened from the pay period (Rachel, 2026-10-30: "on pay period, hover on a budget shows
 * budgeted amount. maybe we can click into it to see the actual row level items"): what was budgeted, the charges that
 * were spent and what is still to come, which can be typed here and changes the amount straight away. It takes the
 * form's place in its dialog, so there is still one dialog and it fits the screen. Saving closes the dialog: the amount
 * is saved, and the form behind holds the amount from before (Rachel, 2026-10-30: "I don't I just wanted to save").
 */
export function StatementBudgetPanel({
  budget,
  onBack,
  onSaved,
}: {
  budget: StatementBudget;
  onBack: () => void;
  onSaved: () => void;
}) {
  const save = useStillToComeOnPeriod();
  const [still, setStill] = useState(centsToInput(budget.stillToComeCents));
  const [problem, setProblem] = useState<string>();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const cents = parseDollars(still);
    if (cents === null || cents < 0) {
      setProblem(AMOUNT_HELP);
      return;
    }
    setProblem(undefined);
    save.mutate({ itemId: budget.itemId, scheduledDate: budget.scheduledDate, cents }, { onSuccess: onSaved });
  };

  return (
    <form onSubmit={submit} className="space-y-3">
      <dl className="grid grid-cols-[1fr_auto] gap-x-4 gap-y-1">
        <dt className="text-muted">Budgeted</dt>
        <dd className="text-right">
          <Amount cents={budget.budgetedCents} />
        </dd>
        <dt className="text-muted">Spent, from statements</dt>
        <dd className="text-right">
          <Amount cents={budget.actualCents} />
        </dd>
        {budget.elsewhereCents > 0 && (
          <>
            <dt className="text-muted">Spent on other accounts, as one-offs there</dt>
            <dd className="text-right">
              <Amount cents={budget.elsewhereCents} />
            </dd>
          </>
        )}
        <dt className="text-muted">Still to come</dt>
        <dd className="text-right">
          <Amount cents={budget.stillToComeCents} />
        </dd>
      </dl>
      <ul aria-label="Charges" className="max-h-[35dvh] divide-y divide-line overflow-y-auto rounded-lg border border-line text-sm">
        {budget.charges.length === 0 && <li className="p-2 text-muted">No charges are on it.</li>}
        {budget.charges.map((charge) => (
          <li key={charge.id} className="flex flex-wrap items-baseline justify-between gap-x-3 px-2 py-1.5">
            <span className="min-w-0 wrap-anywhere">
              {formatDay(charge.txnDate)} · {charge.description}{" "}
              <Link to={`/accounts/${charge.accountId}/statements/${charge.uploadId}`} className="text-accent-ink underline">
                {charge.uploadName}
              </Link>
            </span>
            <Amount cents={charge.amountCents} />
          </li>
        ))}
      </ul>
      <MoneyField label="Still to come" value={still} onChange={setStill} error={problem ?? save.error?.message} required />
      <p className={HINT}>For days no statement covers yet. The amount becomes spent plus this as soon as you save.</p>
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <button type="button" onClick={onBack} className={BUTTON_SECONDARY}>
          Back
        </button>
        <button type="submit" disabled={save.isPending} className={BUTTON_PRIMARY}>
          {save.isPending ? "Saving…" : "Save still to come"}
        </button>
      </div>
    </form>
  );
}
