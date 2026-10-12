import { Banknote } from "lucide-react";
import { type FormEvent, useState } from "react";
import { Link } from "react-router";
import { type Paycheck, useEditPaycheck } from "../../api/income";
import { formatDay } from "../../lib/dates";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { StubUpload } from "../stubs/StubUpload";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, HINT } from "../ui";

/**
 * What opens from a paycheck's row on a pay period: this one paycheck. Its take-home can be
 * typed over, and its stub added (spec Phase 8: hourly pay differs every time, so a stub is
 * added to the paycheck it is for). What also goes to other accounts with it is listed; that,
 * like everything else about the income, is changed on the income's own page.
 */
export function PaycheckForm({
  paycheck,
  accountName,
  onClose,
}: {
  paycheck: Paycheck;
  accountName: (accountId: number) => string;
  onClose: () => void;
}) {
  const edit = useEditPaycheck();
  const [amount, setAmount] = useState(centsToInput(paycheck.netCents));
  const [problem, setProblem] = useState<string>();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const netCents = parseDollars(amount);
    if (netCents === null || netCents < 0) {
      setProblem(AMOUNT_HELP);
      return;
    }
    setProblem(undefined);
    // An amount left alone stays the income's own figure, estimate or not.
    if (netCents === paycheck.netCents) {
      onClose();
      return;
    }
    edit.mutate({ paycheck, netCents }, { onSuccess: onClose });
  };

  const failure = problem ?? edit.error?.message;
  return (
    <Modal title={`${paycheck.sourceName} paycheck · ${formatDay(paycheck.payDate)}`} icon={Banknote} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <MoneyField
          label="Take-home"
          value={amount}
          onChange={setAmount}
          hint={
            paycheck.projectedCents !== null
              ? `Typed over; it was ${formatCents(paycheck.projectedCents)}.`
              : `Just this paycheck, into ${accountName(paycheck.depositAccountId)}.`
          }
          required
          hero
        />

        {paycheck.contributions.length > 0 && (
          <div>
            <p className="font-medium">Also with this paycheck</p>
            <ul className="text-sm text-muted">
              {paycheck.contributions.map((contribution, index) => (
                <li key={index} className="flex justify-between gap-3">
                  <span className="min-w-0 truncate">
                    {contribution.label} → {accountName(contribution.toAccountId)}
                  </span>
                  <span data-amount className="shrink-0 tabular-nums">
                    {formatCents(contribution.amountCents)}
                  </span>
                </li>
              ))}
            </ul>
          </div>
        )}

        <div>
          <StubUpload sourceId={paycheck.incomeSourceId} apply="paycheck" label="Add this paycheck's stub (PDF)" onUploaded={onClose} />
          <p className={HINT}>
            Budget reads it on this PC and shows you every line before anything changes. For the schedule, deductions or a raise,{" "}
            <Link to={`/income/${paycheck.incomeSourceId}`} className="font-semibold text-accent-ink underline">
              open {paycheck.sourceName}
            </Link>
            .
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
          <button type="submit" disabled={edit.isPending} className={BUTTON_PRIMARY}>
            {edit.isPending ? "Saving…" : "Save"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
