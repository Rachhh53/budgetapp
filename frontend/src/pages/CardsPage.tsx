import { Clock, TriangleAlert } from "lucide-react";
import { useId, useState } from "react";
import { Link } from "react-router";
import { useAccounts } from "../api/accounts";
import { type Card as CreditCard, type Promo, useCards } from "../api/cards";
import { AccountForm } from "../components/accounts/AccountForm";
import { Amount } from "../components/Amount";
import { Card } from "../components/Card";
import { cardSummary, promoCountdown } from "../components/cards/labels";
import { BUTTON_PRIMARY, PAGE_TITLE, ROW_LINK } from "../components/ui";
import { formatDay } from "../lib/dates";
import { formatCents } from "../lib/money";

/**
 * Credit cards (design/cards.png), as an overview like Accounts and Income: what is owed in
 * all, the next payment, what sits on promotional rates, then every card on one row. A row
 * opens the card's own page, where its statements, promotions and payoff helper live.
 */
export function CardsPage() {
  const cards = useCards();
  const accounts = useAccounts();
  const [adding, setAdding] = useState(false);
  const headingId = useId();

  const all = cards.data ?? [];
  const open = all.filter((card) => !card.account.archived);
  const archived = all.filter((card) => card.account.archived);

  return (
    <>
      <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between md:mb-6">
        <div>
          <p className="text-sm font-semibold tracking-wide text-muted uppercase">Debts you pay monthly</p>
          <h1 className={PAGE_TITLE}>Credit cards</h1>
        </div>
        <button type="button" onClick={() => setAdding(true)} className={BUTTON_PRIMARY}>
          + Add card
        </button>
      </div>

      {cards.isPending && <p className="text-muted">Loading your cards…</p>}
      {cards.isError && (
        <p role="alert" className="text-danger">
          {cards.error.message}
        </p>
      )}

      {cards.data && all.length === 0 && (
        <Card>
          <p className="font-medium">No credit cards yet.</p>
          <p className="mt-1 text-muted">
            Add a card to see what it will cost each pay period: its payment, any interest, and a countdown on a 0% offer so it never
            runs out unnoticed. Nothing connects to your bank; you enter the balance yourself.
          </p>
        </Card>
      )}

      {open.length > 0 && (
        <div className="space-y-4">
          <Summary cards={open} />
          <section aria-labelledby={headingId} className="rounded-card border border-line bg-surface p-5 md:p-6">
            <h2 id={headingId} className="border-b border-line pb-3 text-lg font-semibold">
              Your cards
            </h2>
            <ul className="divide-y divide-line">
              {open.map((card) => (
                <CardRow key={card.account.id} card={card} />
              ))}
            </ul>
          </section>
        </div>
      )}

      {archived.length > 0 && (
        <details className="mt-4 rounded-card border border-line bg-surface p-5">
          <summary className="min-h-11 cursor-pointer content-center font-semibold">Archived cards ({archived.length})</summary>
          <ul className="mt-2 divide-y divide-line">
            {archived.map((card) => (
              <CardRow key={card.account.id} card={card} />
            ))}
          </ul>
        </details>
      )}

      {adding && <AccountForm accounts={accounts.data ?? []} initialType="credit_card" onClose={() => setAdding(false)} />}
    </>
  );
}

/** The figures across the top: everything owed, the payment that comes first, and what is on a promotional rate. */
function Summary({ cards }: { cards: readonly CreditCard[] }) {
  // Adding in cents keeps the totals exact.
  const owed = cards.reduce((sum, card) => sum + (card.balanceCents ?? 0), 0);
  const next = cards
    .flatMap((card) => (card.nextPayment ? [{ card, payment: card.nextPayment }] : []))
    .sort((a, b) => a.payment.date.localeCompare(b.payment.date))[0];
  const onPromo = cards.flatMap((card) => (card.promo ? [card.promo] : []));
  const promoCents = onPromo.reduce((sum, promo) => sum + promo.remainingCents, 0);
  const soonest = [...onPromo].sort((a, b) => a.endDate.localeCompare(b.endDate))[0];

  return (
    <Card>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <div>
          <p className="text-muted">Owed on cards</p>
          <p data-amount className="text-3xl font-bold tabular-nums">{formatCents(owed)}</p>
          <p className="text-muted">today, across {cards.length === 1 ? "1 card" : `${cards.length} cards`}</p>
        </div>
        <div>
          <p className="text-muted">Next payment</p>
          {next ? (
            <>
              <Amount cents={next.payment.amountCents} estimate={next.payment.estimate} className="-ml-2 text-2xl font-bold" />
              <p className="text-muted">
                {next.card.account.name} · {formatDay(next.payment.date)} · {next.payment.autopay ? "autopay" : "paid by hand"}
              </p>
            </>
          ) : (
            <p className="mt-1 text-muted">None scheduled in the next two months.</p>
          )}
        </div>
        <div>
          <p className="text-muted">On a promo rate</p>
          {soonest ? (
            <>
              <p data-amount className="text-2xl font-bold tabular-nums">{formatCents(promoCents)}</p>
              <p className="text-muted">first to end: {formatDay(soonest.endDate)}</p>
            </>
          ) : (
            <p className="mt-1 text-muted">Nothing on a promotional rate.</p>
          )}
        </div>
      </div>
    </Card>
  );
}

/** One card on the overview. The row only opens the card's page; editing starts there. */
function CardRow({ card }: { card: CreditCard }) {
  const { account } = card;
  return (
    <li className="flex items-center gap-2">
      <Link to={`/cards/${account.id}`} className={ROW_LINK}>
        <span aria-hidden className="size-3 shrink-0 rounded-full" style={{ backgroundColor: account.color }} />
        <span className="min-w-0 flex-1">
          <span className="block font-semibold sm:truncate">{account.name}</span>
          <span className="block text-sm text-muted sm:truncate">{cardSummary(card)}</span>
          {card.promo && <PromoChip promo={card.promo} />}
        </span>
        <span className="shrink-0 text-right">
          {card.balanceCents === null ? (
            <span className="text-sm text-muted">No balance yet</span>
          ) : (
            <>
              <Amount cents={card.balanceCents} className="font-semibold" />
              <span className="block pr-2 text-sm text-muted">owed today</span>
            </>
          )}
        </span>
      </Link>
    </li>
  );
}

/**
 * A promotion's countdown on a card's row. Always in words, with an icon as well as a color
 * when its end is close, so the warning never rests on color alone.
 */
export function PromoChip({ promo }: { promo: Promo }) {
  const urgent = promo.warning !== "none";
  const strong = promo.warning === "soon_deferred" || promo.warning === "ended";
  const Icon = strong ? TriangleAlert : Clock;
  return (
    <span
      className={`mt-1 inline-flex max-w-full items-center gap-1.5 rounded-md border px-2 py-0.5 text-sm font-semibold ${
        urgent ? "border-warn-line bg-warn text-warn-ink" : "border-line text-muted"
      }`}
    >
      <Icon aria-hidden className="size-3.5 shrink-0" />
      <span className="min-w-0">
        {promoCountdown(promo)}
        {promo.warning === "soon" && " · ending soon"}
        {promo.warning === "soon_deferred" && " · deferred interest"}
        {promo.warning === "ended" && " · balance left"}
      </span>
    </span>
  );
}
