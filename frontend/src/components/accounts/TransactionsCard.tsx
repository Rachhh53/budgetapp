import { useId, useState } from "react";
import type { Account } from "../../api/accounts";
import { type AccountTransaction, useAccountTransactions } from "../../api/periods";
import { addDays, formatDate, todayIso } from "../../lib/dates";
import { formatCents } from "../../lib/money";
import { Card } from "../Card";
import { INPUT, LABEL } from "../ui";

/** What each kind of line is called. Anything new the backend sends falls back to its own word. */
const KIND_LABELS: Record<string, string> = {
  paycheck: "Paycheck",
  bill: "Bill",
  budget: "Budget",
  transfer: "Transfer",
  card_payment: "Card payment",
  money_in: "Money in",
  money_out: "Money out",
  interest: "Interest",
  balance: "Balance",
};

/** Roughly three months back and one ahead: recent history and what is about to happen. */
const DAYS_BACK = 90;
const DAYS_AHEAD = 30;

/**
 * Everything that moved one account between two dates, newest first: paychecks, recurring
 * items, one-off entries, transfers, interest, and the balances entered along the way.
 */
export function TransactionsCard({ account }: { account: Account }) {
  const id = useId();
  const today = todayIso();
  const [from, setFrom] = useState(addDays(today, -DAYS_BACK));
  const [to, setTo] = useState(addDays(today, DAYS_AHEAD));
  // A cleared or backwards pair of dates asks for nothing, and says why instead.
  const sensible = from !== "" && to !== "" && from <= to;
  const transactions = useAccountTransactions(account.id, sensible ? from : undefined, sensible ? to : undefined);

  return (
    <Card title="Transactions">
      <div className="mb-3 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:max-w-md">
        <div>
          <label htmlFor={`${id}-from`} className={LABEL}>
            From
          </label>
          <input id={`${id}-from`} type="date" value={from} onChange={(event) => setFrom(event.target.value)} className={INPUT} />
        </div>
        <div>
          <label htmlFor={`${id}-to`} className={LABEL}>
            To
          </label>
          <input id={`${id}-to`} type="date" value={to} onChange={(event) => setTo(event.target.value)} className={INPUT} />
        </div>
      </div>
      {!sensible && <p className="text-muted">Choose a From date that is on or before the To date to see what moved.</p>}
      {sensible && transactions.isPending && <p className="text-muted">Loading transactions…</p>}
      {transactions.isError && (
        <p role="alert" className="text-danger">
          {transactions.error.message}
        </p>
      )}
      {transactions.data && transactions.data.length === 0 && (
        <p className="text-muted">
          Nothing moved in or out of {account.name} between these dates. Try an earlier From date, or add a paycheck, a recurring item or a
          one-off entry.
        </p>
      )}
      {transactions.data && transactions.data.length > 0 && (
        <ul className="divide-y divide-line">
          {transactions.data.map((line, index) => (
            <TransactionRow key={`${line.date}-${line.kind}-${line.label}-${index}`} line={line} upcoming={line.date > today} />
          ))}
        </ul>
      )}
    </Card>
  );
}

function TransactionRow({ line, upcoming }: { line: AccountTransaction; upcoming: boolean }) {
  const entered = line.kind === "balance";
  // Words, not just a colour or a sign, say what state a line is in.
  const notes = [KIND_LABELS[line.kind] ?? line.kind, upcoming && "Upcoming", line.unpaid && "Not paid yet"].filter(Boolean).join(" · ");
  return (
    <li className="flex items-start justify-between gap-3 py-2">
      <span className="min-w-0">
        <span className="block break-words font-medium">{line.label}</span>
        <span className="block text-sm text-muted">
          {formatDate(line.date)} · {notes}
        </span>
      </span>
      <span data-amount className={`shrink-0 text-right tabular-nums ${entered ? "text-muted" : "font-semibold"}`}>
        {/* A balance entered is where the account stood, not something that moved it. */}
        {entered ? formatCents(line.amountCents) : `${line.amountCents > 0 ? "+" : ""}${formatCents(line.amountCents)}`}
      </span>
    </li>
  );
}
