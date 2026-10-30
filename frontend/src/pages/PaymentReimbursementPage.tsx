import { HandCoins } from "lucide-react";
import { type FormEvent, useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { useAccounts } from "../api/accounts";
import { type FlaggedPayment, useFlagPayment, usePaymentReimbursement, useSetPaymentStatus, useUnflagPayment } from "../api/reimbursements";
import { Amount } from "../components/Amount";
import { Card } from "../components/Card";
import { EditButton } from "../components/EditButton";
import { ReimbursementCard } from "../components/expenses/ReimbursementCard";
import { Receipts } from "../components/files/Receipts";
import { HelpLink } from "../components/help/HelpLink";
import { Modal } from "../components/Modal";
import { AMOUNT_HELP, MoneyField } from "../components/MoneyField";
import { PeriodLink } from "../components/period/PeriodLink";
import { ComesOutOfField } from "../components/reimbursements/ComesOutOfField";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, DETAIL_HEADER, PAGE_TITLE } from "../components/ui";
import { formatDate } from "../lib/dates";
import { centsToInput, formatCents, parseDollars } from "../lib/money";

/**
 * One recurring payment flagged to be paid back (Rachel, 2026-10-24: "same as one-off expenses"), laid out like an
 * expense's page: the figures first, then where paying it back stands and the money that came back for it, and the
 * payment's receipts. Found by its item and the date its schedule names.
 */
export function PaymentReimbursementPage() {
  const params = useParams();
  const itemId = Number(params.id);
  const scheduledDate = params.date;
  const detail = usePaymentReimbursement(itemId, scheduledDate);
  const navigate = useNavigate();
  const status = useSetPaymentStatus(itemId, scheduledDate ?? "");
  const unflag = useUnflagPayment(itemId, scheduledDate ?? "");

  if (detail.isPending) {
    return <p className="text-muted">Loading…</p>;
  }
  if (!detail.data) {
    return (
      <>
        <h1 className={`${PAGE_TITLE} mb-4`}>Paid back</h1>
        <Card>
          <p role="alert" className="font-medium">
            {detail.error && detail.error.message !== "We couldn't find that."
              ? detail.error.message
              : "This payment isn't flagged to be paid back. It may have been taken off."}
          </p>
          <Link to={`/recurring/${itemId}`} className="mt-2 inline-block font-semibold text-accent-ink underline">
            Back to the recurring item
          </Link>
        </Card>
      </>
    );
  }

  const { payment, paybacks, movesOut } = detail.data;

  return (
    <>
      <div className={DETAIL_HEADER}>
        <div className="min-w-0">
          <p className="text-sm text-muted">
            <Link to={`/recurring/${payment.itemId}`} className="text-accent-ink underline">
              {payment.name}
            </Link>{" "}
            / Paid back
          </p>
          <div className="flex min-w-0 items-center gap-1">
            <h1 className={`${PAGE_TITLE} min-w-0 truncate`}>{payment.name}</h1>
            <HelpLink />
          </div>
          <p className="text-muted">
            {payment.paid ? `Paid ${formatDate(payment.date)}` : `Due ${formatDate(payment.date)}`} · a recurring payment
          </p>
        </div>
        <div className="flex shrink-0 flex-col gap-2 whitespace-nowrap sm:flex-row">
          <PeriodLink date={payment.date} />
        </div>
      </div>

      <div className="space-y-4">
        <Figures payment={payment} />

        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          <ReimbursementCard
            reimbursement={payment.reimbursement}
            target={payment}
            paybacks={paybacks}
            movesOut={movesOut}
            // The card hands over all of where it stands; a flagged payment is never "none", that is taking the flag off.
            onSet={(change) => {
              if (change.status !== undefined && change.status !== "none") {
                status.mutate({ status: change.status, submittedOn: change.submittedOn ?? null, to: change.to ?? null });
              }
            }}
            // Nobody pays it back after all: the flag comes off, and with it this page.
            onNotPaidBack={() => unflag.mutate(undefined, { onSuccess: () => void navigate(`/recurring/${payment.itemId}`) })}
            saving={status.isPending || unflag.isPending}
            error={status.error?.message ?? unflag.error?.message}
          />
          <Card title="Receipts & documents">
            <Receipts owner={{ type: "occurrence", itemId: payment.itemId, scheduledDate: payment.scheduledDate }} />
          </Card>
        </div>
      </div>
    </>
  );
}

/** What the payment was, what is to come back, what has, and when the rest is planned to. */
function Figures({ payment }: { payment: FlaggedPayment }) {
  const accounts = useAccounts();
  const [changing, setChanging] = useState(false);
  const name = (id: number | null) => accounts.data?.find((account) => account.id === id)?.name ?? "an account that was removed";
  const { reimbursedCents, owedCents, status } = payment.reimbursement;

  return (
    <Card>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <div>
          <p className="text-muted">The payment</p>
          <Amount cents={payment.paymentCents} estimate={payment.estimate} className="-ml-2 text-3xl font-bold" />
          <p className="text-muted">
            paid from {name(payment.accountId)}
            {payment.skipped && " · skipped, so nothing will be paid"}
          </p>
        </div>
        <div>
          <div className="flex items-center justify-between gap-2">
            <p className="text-muted">To come back</p>
            <EditButton name="what comes back" onClick={() => setChanging(true)} />
          </div>
          <p data-amount className="text-2xl font-bold tabular-nums">
            {formatCents(payment.amountCents)}
          </p>
          <p className="text-muted">{payment.amountCents === payment.paymentCents ? "all of it" : "part of it"}</p>
        </div>
        <div>
          <p className="text-muted">Paid back so far</p>
          <p data-amount className="text-2xl font-bold tabular-nums">
            {formatCents(reimbursedCents)}
          </p>
          <p className="text-muted">{status === "reimbursed" && payment.reimbursement.paidBackCents === 0 ? "marked as reimbursed" : "has come back"}</p>
        </div>
        <div>
          <p className="text-muted">Still owed to you</p>
          <p data-amount className="text-2xl font-bold tabular-nums">
            {formatCents(owedCents)}
          </p>
          <p className="text-muted">
            {owedCents === 0
              ? "nothing more to come"
              : payment.expectedOn === null
                ? "not planned: the item has no account to come back into"
                : `planned for ${formatDate(payment.expectedOn)}, into ${name(payment.intoAccountId)}${
                    payment.fromAccountId === null ? "" : `, out of ${name(payment.fromAccountId)}`
                  }`}
          </p>
        </div>
      </div>
      {changing && <ChangeAmount payment={payment} onClose={() => setChanging(false)} />}
    </Card>
  );
}

/**
 * Changes how much of the payment comes back (the payment's amount when it was flagged, unless changed here) and where it
 * comes out of: the item's to begin with.
 */
function ChangeAmount({ payment, onClose }: { payment: FlaggedPayment; onClose: () => void }) {
  const save = useFlagPayment(payment.itemId, payment.scheduledDate);
  const [amount, setAmount] = useState(centsToInput(payment.amountCents));
  const [from, setFrom] = useState<number | null>(payment.fromAccountId);
  const [problem, setProblem] = useState<string>();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const amountCents = parseDollars(amount);
    if (amountCents === null || amountCents <= 0) {
      setProblem(amountCents === null ? AMOUNT_HELP : "Please enter how much comes back, more than $0.00.");
      return;
    }
    setProblem(undefined);
    save.mutate({ amountCents, fromAccountId: from }, { onSuccess: onClose });
  };

  return (
    <Modal title={`What comes back — ${payment.name}`} icon={HandCoins} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <MoneyField
          label="Comes back"
          value={amount}
          onChange={setAmount}
          hint={`The payment was ${formatCents(payment.paymentCents)}. Enter less when only part of it is paid back.`}
          required
          hero
        />
        <ComesOutOfField value={from} onChange={setFrom} intoAccountId={payment.intoAccountId} />
        {(problem ?? save.error?.message) && (
          <p role="alert" className="text-danger">
            {problem ?? save.error?.message}
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
