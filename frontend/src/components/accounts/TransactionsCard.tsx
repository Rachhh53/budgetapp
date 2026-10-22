import { useId, useState } from "react";
import type { Account } from "../../api/accounts";
import { type AccountTransaction, TYPED_OVER_KINDS, useAccountTransactions } from "../../api/periods";
import { addDays, formatDate, todayIso } from "../../lib/dates";
import { formatCents } from "../../lib/money";
import { EditName } from "../EditButton";
import { clickRowToEdit, ROW_OPENS } from "../rowEdit";
import { BUTTON_SECONDARY, INPUT, LABEL } from "../ui";
import { InterestForm } from "./InterestForm";

/** What each kind of line is called. Anything new the backend sends falls back to its own word. */
const KIND_LABELS: Record<string, string> = {
  paycheck: "Paycheck",
  bill: "Bill or expense",
  budget: "Budget",
  transfer: "Transfer",
  card_payment: "Card payment",
  money_in: "Money in",
  money_out: "Money out",
  interest: "Interest",
  contribution: "From a paycheck",
  employer_match: "Employer match",
  growth: "Assumed, not actual",
  card_interest: "Estimate",
  deferred_interest: "Estimate",
  card_estimate: "Estimate",
  card_rewards: "Estimate",
  loan_interest: "Interest",
  escrow: "Part of your payment that does not lower the loan",
  loan_payment: "Payment, before a recurring item paid it",
  balance: "Balance",
};

/** A month ahead, so what is about to happen shows with the year so far. */
const DAYS_AHEAD = 30;

/** Few enough lines that a page fits on a screen without scrolling as well as paging. */
const PAGE_SIZE = 8;

/**
 * Everything that moved one account between two dates, newest first: paychecks, recurring
 * items, one-off entries, transfers, interest, and the balances entered along the way. A year
 * of them is long, so the card folds away and the list comes a page at a time.
 */
export function TransactionsCard({ account, title = "Transactions" }: { account: Account; title?: string }) {
  const id = useId();
  const today = todayIso();
  // The whole year so far to begin with; the list follows the dates as soon as they change.
  const [from, setFrom] = useState(`${today.slice(0, 4)}-01-01`);
  const [to, setTo] = useState(addDays(today, DAYS_AHEAD));
  const [page, setPage] = useState(0);
  // The interest line open to be typed over.
  const [editing, setEditing] = useState<AccountTransaction>();
  // A cleared or backwards pair of dates asks for nothing, and says why instead.
  const sensible = from !== "" && to !== "" && from <= to;
  const transactions = useAccountTransactions(account.id, sensible ? from : undefined, sensible ? to : undefined);

  const lines = transactions.data ?? [];
  const pages = Math.max(1, Math.ceil(lines.length / PAGE_SIZE));
  // Other dates can leave fewer pages than the one that was open.
  const shown = Math.min(page, pages - 1);
  const first = shown * PAGE_SIZE;
  const onPage = lines.slice(first, first + PAGE_SIZE);

  return (
    <section aria-labelledby={`${id}-title`} className="rounded-card border border-line bg-surface p-5 md:p-6">
      <details open>
        <summary className="flex min-h-11 cursor-pointer items-center">
          <h2 id={`${id}-title`} className="text-lg font-semibold">
            {title}
          </h2>
        </summary>
        <div className="mt-3 mb-3 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:max-w-md">
          <div>
            <label htmlFor={`${id}-from`} className={LABEL}>
              From
            </label>
            <input
              id={`${id}-from`}
              type="date"
              value={from}
              onChange={(event) => {
                setFrom(event.target.value);
                setPage(0);
              }}
              className={INPUT}
            />
          </div>
          <div>
            <label htmlFor={`${id}-to`} className={LABEL}>
              To
            </label>
            <input
              id={`${id}-to`}
              type="date"
              value={to}
              onChange={(event) => {
                setTo(event.target.value);
                setPage(0);
              }}
              className={INPUT}
            />
          </div>
        </div>
        {!sensible && <p className="text-muted">Choose a From date that is on or before the To date to see what moved.</p>}
        {sensible && transactions.isPending && <p className="text-muted">Loading transactions…</p>}
        {transactions.isError && (
          <p role="alert" className="text-danger">
            {transactions.error.message}
          </p>
        )}
        {transactions.data && lines.length === 0 && (
          <p className="text-muted">
            Nothing moved in or out of {account.name} between these dates. Try an earlier From date, or add a paycheck, a recurring item or
            a one-off entry.
          </p>
        )}
        {onPage.length > 0 && (
          <>
            <ul className="divide-y divide-line">
              {onPage.map((line, index) => (
                <TransactionRow
                  key={`${line.date}-${line.kind}-${line.label}-${first + index}`}
                  line={line}
                  upcoming={line.date > today}
                  onEdit={canTypeOver(line, today) ? () => setEditing(line) : undefined}
                />
              ))}
            </ul>
            <nav aria-label="Transaction pages" className="mt-3 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
              <p aria-live="polite" className="text-sm text-muted">
                {first + 1}–{first + onPage.length} of {lines.length}, newest first
                {pages > 1 && ` · page ${shown + 1} of ${pages}`}
              </p>
              {pages > 1 && (
                <div className="flex gap-2">
                  <button type="button" onClick={() => setPage(shown - 1)} disabled={shown === 0} className={BUTTON_SECONDARY}>
                    Newer
                  </button>
                  <button type="button" onClick={() => setPage(shown + 1)} disabled={shown === pages - 1} className={BUTTON_SECONDARY}>
                    Older
                  </button>
                </div>
              )}
            </nav>
          </>
        )}
      </details>
      {editing && <InterestForm account={account} line={editing} upcoming={editing.date > today} onClose={() => setEditing(undefined)} />}
    </section>
  );
}

/**
 * Whether a line is interest that can be typed over. Interest still to come has no real figure
 * yet, so it stays worked out and follows every change before it (Rachel, 2026-10-19: "it's
 * upcoming it shouldn't be set in stone"); growth is typed over a whole month, once the month is
 * over. A line typed over before that rule still opens, so its estimate can be put back.
 */
function canTypeOver(line: AccountTransaction, today: string): boolean {
  if (!TYPED_OVER_KINDS.has(line.kind)) {
    return false;
  }
  if (line.estimatedCents !== null) {
    return true;
  }
  // A growth line is the month's days within the dates shown, dated the last of them: only one on the month's last day is the whole month.
  const wholeMonth = line.kind !== "growth" || addDays(line.date, 1).slice(8) === "01";
  return wholeMonth && line.date <= today;
}

/**
 * One line. Interest Budget worked out opens to be typed over, the way every row that opens a
 * form does: its name is the button, and a click anywhere on the row is handed to it.
 */
function TransactionRow({ line, upcoming, onEdit }: { line: AccountTransaction; upcoming: boolean; onEdit?: () => void }) {
  const entered = line.kind === "balance";
  const typedOver = line.estimatedCents !== null;
  // Words, not just a colour or a sign, say what state a line is in. Interest typed over is no longer an estimate.
  const kind = typedOver ? "Typed over" : (KIND_LABELS[line.kind] ?? line.kind);
  const notes = [kind, upcoming && "Upcoming", line.unpaid && "Not paid yet"].filter(Boolean).join(" · ");
  return (
    <li onClick={clickRowToEdit} className={`-mx-2 flex items-start justify-between gap-3 rounded-md px-2 py-2 ${ROW_OPENS}`}>
      <span className="min-w-0">
        {onEdit ? (
          <EditName name={line.label} onClick={onEdit} />
        ) : (
          <span className="block break-words font-medium">{line.label}</span>
        )}
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
