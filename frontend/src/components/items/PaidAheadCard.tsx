import { useState } from "react";
import { Link } from "react-router";
import { useSkipVisit } from "../../api/invoices";
import type { Item } from "../../api/items";
import { type Prepayment, type Session, usePaidAhead } from "../../api/paidAhead";
import { formatDate, formatDay, formatMonth } from "../../lib/dates";
import { formatCents } from "../../lib/money";
import { Amount } from "../Amount";
import { Card } from "../Card";
import { EditButton } from "../EditButton";
import { BUTTON_LINK, BUTTON_PRIMARY } from "../ui";
import { describePackSchedule, PackDateForm, PackPlanForm } from "./PackForms";
import { PrepaymentForm } from "./PrepaymentForm";

/** Enough sessions to see the pattern; the rest are counted, not listed, so the card stays short. */
const SESSIONS_SHOWN = 6;

/**
 * Paid ahead, on the item's own page (spec §4.6; Rachel, 2026-10-19: "if I pay 100 today and I
 * attend a course that costs 25/week and I want to draw down on that each week"): what is left,
 * which session it lasts through, the sessions so far and the next few, each of which can be
 * skipped, then every payment made ahead. Running out only warns; nothing is planned to pay for it.
 */
export function PaidAheadCard({ item }: { item: Item }) {
  const standing = usePaidAhead(item.id);
  // A session is skipped exactly as a billed-later visit is: through its occurrence.
  const skip = useSkipVisit(item.id);
  const [paying, setPaying] = useState(false);
  const [planning, setPlanning] = useState(false);
  const [moving, setMoving] = useState(false);
  // Every session so far, to skip or put back any of them (Rachel, 2026-10-20: "I am trying to build from 1/1 so I have to see the whole year at least").
  const [showingAll, setShowingAll] = useState(false);

  if (standing.isError) {
    return (
      <Card title="Paid ahead" tip="paidAhead">
        <p role="alert">We couldn't load what was paid ahead just now. Please try again in a moment.</p>
      </Card>
    );
  }
  if (!standing.data) {
    return (
      <Card title="Paid ahead" tip="paidAhead">
        <p className="text-muted">Loading payments and sessions…</p>
      </Card>
    );
  }
  const {
    paidCents,
    leftCents,
    coversThrough,
    firstUncovered,
    used,
    upcoming,
    payments,
    packSessions,
    packPriceCents,
    nextPack,
    nextPackPlanned,
    shortFrom,
  } = standing.data;
  const sessionRow = (session: Session) => (
    <SessionRow
      key={session.scheduledDate}
      session={session}
      busy={skip.isPending && skip.variables?.scheduledDate === session.scheduledDate}
      onToggle={() => skip.mutate({ scheduledDate: session.scheduledDate, skipped: !session.skipped })}
    />
  );
  const shown = showingAll ? used : used.slice(0, SESSIONS_SHOWN);

  return (
    <Card title="Paid ahead" tip="paidAhead">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-muted">Left</p>
          <p data-amount className={`text-2xl font-bold tabular-nums ${leftCents < 0 ? "text-danger" : ""}`}>
            {leftCents < 0 ? `${formatCents(-leftCents)} short` : formatCents(leftCents)}
          </p>
          <p className="text-muted">{lasts(paidCents, coversThrough, firstUncovered)}</p>
          {packSessions != null && packPriceCents != null && (
            <p className="text-muted">
              Pack of {packSessions} for <span data-amount>{formatCents(packPriceCents)}</span>
            </p>
          )}
        </div>
        <button type="button" onClick={() => setPaying(true)} className={BUTTON_PRIMARY}>
          Record a payment
        </button>
      </div>

      {/* When packs are paid, and the next one's date, each with its own visible Edit (Rachel, 2026-10-20). */}
      {packSessions != null && (
        <dl className="mt-3 divide-y divide-line border-y border-line">
          <div className="flex min-h-11 items-center gap-3 py-1">
            <dt className="text-muted">Packs paid</dt>
            <dd className="min-w-0 flex-1 text-right">
              {item.packSchedule ? describePackSchedule(item.packSchedule) : "when the sessions run out"}
            </dd>
            <EditButton name="when packs are paid" onClick={() => setPlanning(true)} />
          </div>
          {nextPack != null && nextPackPlanned != null && (
            <div className="flex min-h-11 items-center gap-3 py-1">
              <dt className="text-muted">Next pack</dt>
              <dd className="min-w-0 flex-1 text-right">
                <span className="whitespace-nowrap">{formatDate(nextPack)}</span>
                {nextPack !== nextPackPlanned && <span className="block text-sm text-muted">moved from {formatDay(nextPackPlanned)}</span>}
              </dd>
              <button type="button" onClick={() => setMoving(true)} className={`${BUTTON_LINK} shrink-0`}>
                Change date
              </button>
            </div>
          )}
        </dl>
      )}
      {shortFrom != null && (
        <p role="status" className="mt-2 font-semibold text-danger">
          The sessions run out on {formatDate(shortFrom)}, before the next pack. Pay packs more often, or move the next one earlier.
        </p>
      )}

      <h3 className="mt-5 font-semibold">Sessions so far</h3>
      {shown.length === 0 ? (
        <p className="mt-1 text-muted">No sessions yet. The first is {upcoming[0] ? formatDate(upcoming[0].date) : "still to come"}.</p>
      ) : (
        <>
          {showingAll ? (
            byMonth(shown).map(([month, sessions]) => (
              <div key={month}>
                <h4 className="mt-3 text-sm font-semibold text-muted">{formatMonth(month)}</h4>
                <ul className="divide-y divide-line">{sessions.map(sessionRow)}</ul>
              </div>
            ))
          ) : (
            <ul className="mt-1 divide-y divide-line">{shown.map(sessionRow)}</ul>
          )}
          {used.length > SESSIONS_SHOWN && (
            <p className="mt-1 flex flex-wrap items-center gap-x-2 text-sm text-muted">
              {!showingAll && <span>and {used.length - SESSIONS_SHOWN} earlier, from {formatDay(used.at(-1)?.date ?? "")}.</span>}
              <button type="button" onClick={() => setShowingAll(!showingAll)} aria-expanded={showingAll} className={`${BUTTON_LINK} -ml-2`}>
                {showingAll ? "Show the latest only" : `Show all ${used.length} sessions`}
              </button>
            </p>
          )}
        </>
      )}

      {upcoming.length > 0 && (
        <>
          <h3 className="mt-5 font-semibold">Coming up</h3>
          <p className="mt-1 text-sm text-muted">Skip one ahead of time if it won't happen.</p>
          <ul className="mt-1 divide-y divide-line">{upcoming.map(sessionRow)}</ul>
        </>
      )}
      {skip.error && (
        <p role="alert" className="mt-2 text-danger">
          {skip.error.message}
        </p>
      )}

      <h3 className="mt-5 font-semibold">Payments</h3>
      {payments.length === 0 ? (
        <p className="mt-1 text-muted">No payments yet. Record what you paid ahead and each session uses some of it.</p>
      ) : (
        <ul className="mt-1 divide-y divide-line">
          {payments.map((payment) => (
            <PaymentRow key={payment.entryId} payment={payment} />
          ))}
        </ul>
      )}

      {paying && (
        <PrepaymentForm
          itemId={item.id}
          name={item.name}
          payFromAccountId={item.payFromAccountId}
          priceCents={packPriceCents}
          onClose={() => setPaying(false)}
        />
      )}
      {planning && <PackPlanForm item={item} nextPack={nextPack} onClose={() => setPlanning(false)} />}
      {moving && nextPack != null && nextPackPlanned != null && (
        <PackDateForm item={item} planned={nextPackPlanned} due={nextPack} onClose={() => setMoving(false)} />
      )}
    </Card>
  );
}

/** Sessions in the order given (newest first), under the month each falls in, so a whole year reads easily. */
function byMonth(sessions: Session[]): [string, Session[]][] {
  const months = new Map<string, Session[]>();
  for (const session of sessions) {
    const month = `${session.date.slice(0, 7)}-01`;
    months.set(month, [...(months.get(month) ?? []), session]);
  }
  return [...months];
}

/** How far what was paid goes, in words. */
function lasts(paidCents: number, coversThrough: string | null, firstUncovered: string | null): string {
  if (paidCents === 0) {
    return "Nothing paid ahead yet.";
  }
  if (firstUncovered === null) {
    return coversThrough === null ? "No sessions left to use it." : `Covers every session through ${formatDate(coversThrough)}.`;
  }
  return coversThrough === null
    ? `Not enough for the session on ${formatDate(firstUncovered)}.`
    : `Covers through ${formatDate(coversThrough)}. The session on ${formatDate(firstUncovered)} needs another payment.`;
}

/** One session with its own Skip, or Put back once skipped. Skipped and not covered are said in words, not by style alone. */
function SessionRow({ session, busy, onToggle }: { session: Session; busy: boolean; onToggle: () => void }) {
  return (
    <li className="flex items-center gap-3 py-0.5">
      <span className="min-w-0 flex-1">
        <span className={`whitespace-nowrap ${session.skipped ? "text-muted line-through" : ""}`}>{formatDate(session.date)}</span>
        {session.skipped ? (
          <span className="block text-sm font-semibold text-muted sm:ml-2 sm:inline">Skipped</span>
        ) : (
          !session.covered && <span className="block text-sm font-semibold text-danger sm:ml-2 sm:inline">Not covered</span>
        )}
      </span>
      {!session.skipped && <Amount cents={session.amountCents} />}
      <button
        type="button"
        onClick={onToggle}
        disabled={busy}
        aria-label={`${session.skipped ? "Put back" : "Skip"} the session on ${formatDate(session.date)}`}
        className={`${BUTTON_LINK} w-20 shrink-0 text-right`}
      >
        {session.skipped ? "Put back" : "Skip"}
      </button>
    </li>
  );
}

/** One payment: it opens the one-off it was saved as, where it is changed or removed. */
function PaymentRow({ payment }: { payment: Prepayment }) {
  return (
    <li>
      <Link
        to={`/expenses/${payment.entryId}`}
        className="-mx-2 flex min-h-11 cursor-pointer items-center gap-3 rounded-lg px-2 py-2 hover:bg-bg/60"
        aria-label={`Payment on ${formatDate(payment.date)}: open it`}
      >
        <span className="min-w-0 flex-1 font-medium">Paid {formatDay(payment.date)}</span>
        <Amount cents={payment.amountCents} />
      </Link>
    </li>
  );
}
