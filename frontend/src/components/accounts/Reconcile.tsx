import { useState } from "react";
import { Link } from "react-router";
import type { Account } from "../../api/accounts";
import { api } from "../../api/client";
import type { EntryDetail } from "../../api/entries";
import { type AccountTransaction, useAccountTransactions } from "../../api/periods";
import { foundOccurrence } from "../../api/search";
import { addDays, formatDay } from "../../lib/dates";
import { formatCents } from "../../lib/money";
import { useAddItem } from "../add/AddItem";
import { FIND_PARAM } from "../period/PeriodSearch";
import { BUTTON_LINK, BUTTON_SECONDARY, HINT } from "../ui";

/** Accounts whose balance is what is owed: a charge raises it. */
const OWED = new Set(["credit_card", "loan"]);

/**
 * Where a balance or statement the user entered and the records behind it part ways (Rachel, 2026-10-21: "when I
 * enter a statement balance for an account and there is discrepancy … just make that visible - not like red but
 * maybe like the charges between the last time it matched and the actual statement … so I could adjust or add/delete
 * things if I wanted to reconcile"). A quiet line says by how much; it opens to everything that moved the account
 * since the records last matched, each opening to change or delete it, with a way to add what is missing.
 *
 * @param from            the first day to look through: the day the records last matched
 * @param through         the last day: the statement's close, or the day before a balance entered
 * @param recordsCents    what the records came to
 * @param enteredCents    what the statement or balance says
 */
export function Reconcile({
  account,
  from,
  through,
  recordsCents,
  enteredCents,
}: {
  account: Account;
  from: string;
  through: string;
  recordsCents: number;
  enteredCents: number;
}) {
  const [open, setOpen] = useState(false);
  const difference = enteredCents - recordsCents;
  if (difference === 0) {
    return null;
  }
  return (
    <div className="text-sm">
      <p data-amount className="text-muted">
        Your records came to {formatCents(recordsCents)}, {formatCents(Math.abs(difference))} {difference > 0 ? "less" : "more"} than
        what you entered.{" "}
        <button type="button" onClick={() => setOpen(!open)} aria-expanded={open} className={BUTTON_LINK}>
          {open ? "Hide what went in" : "See what went in"}
        </button>
      </p>
      {open && <WhatWentIn account={account} from={from} through={through} />}
    </div>
  );
}

function WhatWentIn({ account, from, through }: { account: Account; from: string; through: string }) {
  const lines = useAccountTransactions(account.id, from, through);
  const { add, editEntry } = useAddItem();
  const [problem, setProblem] = useState<string>();
  const shown = (lines.data ?? []).filter((line) => line.kind !== "balance");

  const openEntry = async (entryId: number) => {
    try {
      setProblem(undefined);
      editEntry((await api.get<EntryDetail>(`/api/entries/${entryId}`)).entry);
    } catch {
      setProblem("We couldn't open that just now. Please try again.");
    }
  };

  return (
    <div className="mt-2 rounded-lg border border-line p-2">
      <p className={HINT}>
        {formatDay(from)} – {formatDay(through)}, newest first. Open one to change or delete it.
      </p>
      {lines.isPending ? (
        <p className="py-2 text-muted">Loading…</p>
      ) : lines.isError ? (
        <p role="alert" className="py-2 text-danger">
          We couldn't load what went in just now. Please try again.
        </p>
      ) : shown.length === 0 ? (
        <p className="py-2 text-muted">Nothing went in or out in these days. Something may be missing.</p>
      ) : (
        <ul className="divide-y divide-line">
          {shown.map((line, index) => (
            <li key={`${line.date}-${line.label}-${index}`} className="flex min-h-11 items-center gap-2 py-1">
              <span className="w-14 shrink-0 text-muted">{formatDay(line.date)}</span>
              <span className="min-w-0 flex-1 truncate">
                <Opener line={line} onEntry={openEntry} />
              </span>
              <span data-amount className="shrink-0 tabular-nums">
                {line.amountCents < 0 ? "−" : "+"}
                {formatCents(Math.abs(line.amountCents))}
              </span>
            </li>
          ))}
        </ul>
      )}
      {problem && (
        <p role="alert" className="text-danger">
          {problem}
        </p>
      )}
      <button type="button" onClick={() => add("money_out", account.id)} className={`${BUTTON_SECONDARY} mt-2`}>
        {OWED.has(account.type) ? "+ Add a charge" : "+ Add something missing"}
      </button>
    </div>
  );
}

/** A line's name, as the way to change it: a one-off opens here; a recurring item or paycheck in its pay period. */
function Opener({ line, onEntry }: { line: AccountTransaction; onEntry: (entryId: number) => void }) {
  const source = line.source;
  if (source?.type === "entry" && source.entryId !== null) {
    const entryId = source.entryId;
    return (
      <button type="button" data-edit onClick={() => onEntry(entryId)} className="cursor-pointer text-left font-medium text-accent-ink underline">
        {line.label}
      </button>
    );
  }
  if (source?.periodStart) {
    const find =
      source.type === "occurrence" && source.itemId !== null && source.dueDate !== null
        ? `?${FIND_PARAM}=${foundOccurrence(source.itemId, source.dueDate)}`
        : "";
    return (
      <Link to={`/periods/${source.periodStart}${find}`} className="font-medium text-accent-ink underline">
        {line.label}
      </Link>
    );
  }
  return <span>{line.label}</span>;
}

/** A balance entered, as far as which day's movements it already holds. */
interface Moment {
  date: string;
  endOfDay: boolean;
}

/**
 * The days whose movements lie between two balances entered. A balance the day ended with already holds that day; one
 * it began with (every balance entered before 2026-10-30) leaves the day's movements to what follows it.
 */
export function between(before: Moment, after: Moment): { from: string; through: string } {
  return {
    from: before.endOfDay ? addDays(before.date, 1) : before.date,
    through: after.endOfDay ? after.date : addDays(after.date, -1),
  };
}
