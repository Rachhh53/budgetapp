import { Clock, TriangleAlert } from "lucide-react";
import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { type Account, useAccounts } from "../api/accounts";
import {
  type CardStatement,
  type Promo,
  useAddPayoff,
  useCard,
  useDeletePromo,
  useDeleteStatement,
} from "../api/cards";
import { AccountForm } from "../components/accounts/AccountForm";
import { BalanceForm } from "../components/accounts/BalanceForm";
import { RateCard } from "../components/accounts/RateCard";
import { TransactionsCard } from "../components/accounts/TransactionsCard";
import { useAddItem } from "../components/add/AddItem";
import { Amount } from "../components/Amount";
import { Card } from "../components/Card";
import { BillingForm, PaymentForm, PromoForm, StatementForm } from "../components/cards/CardForms";
import {
  dayOfMonth,
  paymentSetting,
  paymentSource,
  PROMO_KIND_LABELS,
  promoCountdown,
  promoNotice,
  STATEMENT_STATUS_LABELS,
} from "../components/cards/labels";
import { EditButton, EditPageButton } from "../components/EditButton";
import { BUTTON_LINK, BUTTON_PRIMARY, BUTTON_SECONDARY, DETAIL_HEADER, HINT, PAGE_TITLE } from "../components/ui";
import { formatDate, formatDay } from "../lib/dates";
import { formatCents } from "../lib/money";
import { formatRate } from "../lib/rates";

/* One statement: stacked on a phone, columns from `md` up. */
const STATEMENT_ROW = "grid grid-cols-2 gap-x-4 gap-y-1 py-3 md:grid-cols-[9rem_8rem_8rem_8rem_1fr_auto] md:items-center";

/** The pop-up form that is open, if any. One at a time, each short enough for the screen. */
type Open =
  | { form: "account" | "balance" | "billing" | "payment" | "statement" }
  | { form: "promo"; promo?: Promo };

/**
 * One credit card (design/cards.png): what is owed and how it is paid, its promotional rates
 * with their countdown, the payoff helper, what happens if a promotion is not cleared, its
 * statements and its charges. One card per topic, each with its own way in.
 */
export function CardDetailPage() {
  const id = Number(useParams().id);
  const navigate = useNavigate();
  const detail = useCard(id);
  const accounts = useAccounts();
  const { add } = useAddItem();
  const [open, setOpen] = useState<Open | null>(null);
  const close = () => setOpen(null);

  if (detail.isPending) {
    return <p className="text-muted">Loading this card…</p>;
  }
  if (detail.isError) {
    return (
      <>
        <h1 className={`${PAGE_TITLE} mb-4`}>Credit cards</h1>
        <Card>
          <p role="alert" className="font-medium">
            {detail.error.message}
          </p>
          <Link to="/cards" className="mt-2 inline-block font-semibold text-accent-ink underline">
            Back to all cards
          </Link>
        </Card>
      </>
    );
  }

  const { card, promos, statements, rates, nextCloseDate, nextDueDate } = detail.data;
  const { account, settings } = card;
  const everyAccount = accounts.data ?? [];
  // Only promotions that still carry a balance are worth a warning or a plan to clear them.
  const withBalance = promos.filter((promo) => promo.remainingCents > 0);
  const flagged = withBalance.filter((promo) => promo.warning !== "none");

  return (
    <>
      <div className={DETAIL_HEADER}>
        <div className="min-w-0">
          <p className="text-sm text-muted">
            <Link to="/cards" className="text-accent-ink underline">
              Credit cards
            </Link>{" "}
            / Card
          </p>
          <h1 className={`${PAGE_TITLE} flex items-center gap-3`}>
            <span aria-hidden className="size-4 shrink-0 rounded-full" style={{ backgroundColor: account.color }} />
            <span className="truncate">{account.name}</span>
          </h1>
          <p className="text-muted">
            {settings
              ? `Closes on ${dayOfMonth(settings.statementCloseDay)} · due on ${dayOfMonth(settings.dueDay)}`
              : "Statement and due days not set up yet"}
            {account.archived && " · Archived"}
          </p>
        </div>
        <div className="flex flex-col gap-2 sm:flex-row">
          <EditPageButton label="Edit account" onClick={() => setOpen({ form: "account" })} />
          <button type="button" onClick={() => setOpen({ form: "statement" })} className={BUTTON_SECONDARY}>
            + Statement
          </button>
          <button type="button" onClick={() => add("money_out", account.id)} className={BUTTON_PRIMARY}>
            + Charge
          </button>
        </div>
      </div>

      <div className="space-y-4">
        {flagged.map((promo) => (
          <PromoBanner key={promo.id} promo={promo} />
        ))}

        <Card>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <div>
              <p className="text-muted">Balance</p>
              {card.balanceCents === null ? (
                <p className="mt-1 font-medium">No balance entered yet.</p>
              ) : (
                <>
                  <p data-amount className="text-3xl font-bold tabular-nums">{formatCents(card.balanceCents)}</p>
                  <p className="text-muted">owed today</p>
                </>
              )}
              <button type="button" onClick={() => setOpen({ form: "balance" })} className={`${BUTTON_LINK} -ml-2`}>
                Update balance
              </button>
            </div>
            <div>
              <p className="text-muted">Payment setting</p>
              {settings ? (
                <>
                  <p className="text-2xl font-bold">{paymentSetting(settings)}</p>
                  <p className="text-muted">{paymentSource(settings, everyAccount)}</p>
                </>
              ) : (
                <p className="mt-1 text-muted">Not set up yet.</p>
              )}
            </div>
            <div>
              <p className="text-muted">Next statement</p>
              {nextCloseDate && nextDueDate ? (
                <>
                  <p className="text-2xl font-bold">closes {formatDay(nextCloseDate)}</p>
                  <p className="text-muted">due {formatDate(nextDueDate)}</p>
                </>
              ) : (
                <p className="mt-1 text-muted">Add the statement and due days below.</p>
              )}
            </div>
            <div>
              <p className="text-muted">Regular APR</p>
              {card.aprBp === null ? (
                <p className="mt-1 text-muted">Not entered yet.</p>
              ) : (
                <>
                  <p className="text-2xl font-bold tabular-nums">{formatRate(card.aprBp)}</p>
                  <p className="text-muted">a year, on what is carried</p>
                </>
              )}
            </div>
          </div>
          {card.nextPayment && (
            <p className="mt-3 text-muted">
              Next payment:{" "}
              <Amount cents={card.nextPayment.amountCents} estimate={card.nextPayment.estimate} className="font-semibold text-ink" /> on{" "}
              {formatDate(card.nextPayment.date)}, {card.nextPayment.autopay ? "by autopay" : "paid by hand"}.
            </p>
          )}
        </Card>

        <PromosCard
          account={account}
          promos={promos}
          onAdd={() => setOpen({ form: "promo" })}
          onEdit={(promo) => setOpen({ form: "promo", promo })}
        />
        {withBalance.length > 0 && <PayoffCard account={account} promos={withBalance} />}
        {withBalance.length > 0 && <IfNotPaidCard promos={withBalance} />}

        <StatementsCard account={account} statements={statements} onAdd={() => setOpen({ form: "statement" })} />

        <Card
          title="Statement and due dates"
          action={settings ? <EditButton name="statement and due dates" onClick={() => setOpen({ form: "billing" })} /> : undefined}
        >
          {settings ? (
            <>
              <p>
                Closes on <span className="font-semibold">{dayOfMonth(settings.statementCloseDay)}</span> of each month · payment due on{" "}
                <span className="font-semibold">{dayOfMonth(settings.dueDay)}</span>
              </p>
              <p className={HINT}>
                {settings.monthlyEstimateCents === null
                  ? "Charges are added as they happen. No monthly estimate."
                  : `About ${formatCents(settings.monthlyEstimateCents)} of spending is estimated each statement month, until the real statement is entered.`}
              </p>
            </>
          ) : (
            <>
              <p className="text-muted">
                Tell the app when this card's statement closes and when its payment is due, so payments and interest land in the right
                pay period.
              </p>
              <button type="button" onClick={() => setOpen({ form: "billing" })} className={`${BUTTON_SECONDARY} mt-3`}>
                Set the dates
              </button>
            </>
          )}
        </Card>

        <Card
          title="Payment setting"
          action={settings ? <EditButton name="payment setting" onClick={() => setOpen({ form: "payment" })} /> : undefined}
        >
          {settings ? (
            <>
              <p>
                <span className="font-semibold">{paymentSetting(settings)}</span> · {paymentSource(settings, everyAccount)}
              </p>
              <p className={HINT}>
                {settings.paymentItemId === null ? (
                  "Choose the account it is paid from to see this payment in each pay period."
                ) : (
                  <>
                    This payment is one of your{" "}
                    <Link to={`/recurring/${settings.paymentItemId}`} className="font-semibold text-accent-ink underline">
                      recurring items
                    </Link>
                    , kept in step with what is set here.
                  </>
                )}
              </p>
            </>
          ) : (
            <p className="text-muted">Set the statement and due dates first; then choose how this card is paid.</p>
          )}
        </Card>

        <RateCard
          account={account}
          rates={rates}
          title="Regular APR"
          unit="a year"
          about="Charged each month on what was left unpaid from the statement before. Paying the statement in full means no interest."
          missing="No APR entered yet. Without one, a balance that is carried is projected with no interest."
        />

        <TransactionsCard account={account} title="Charges and payments" />
      </div>

      {open?.form === "account" && (
        <AccountForm account={account} accounts={everyAccount} onClose={close} onDeleted={() => navigate("/cards")} />
      )}
      {open?.form === "balance" && <BalanceForm account={account} onClose={close} />}
      {open?.form === "billing" && <BillingForm account={account} settings={settings} onClose={close} />}
      {open?.form === "payment" && settings && <PaymentForm account={account} settings={settings} accounts={everyAccount} onClose={close} />}
      {open?.form === "statement" && <StatementForm account={account} settings={settings} onClose={close} />}
      {open?.form === "promo" && <PromoForm account={account} promo={open.promo} onClose={close} />}
    </>
  );
}

/**
 * A promotion that is close to its end, or past it, with a balance still on it. Said in words
 * with an icon, so the warning never rests on its color; the deferred kind gets the stronger one.
 */
function PromoBanner({ promo }: { promo: Promo }) {
  const strong = promo.warning === "soon_deferred" || promo.warning === "ended";
  const Icon = strong ? TriangleAlert : Clock;
  return (
    <div role="alert" className={`flex gap-3 rounded-card border bg-warn p-4 text-warn-ink ${strong ? "border-2" : ""} border-warn-line`}>
      <Icon aria-hidden className="mt-0.5 size-6 shrink-0" />
      <div className="min-w-0">
        <p className="text-lg font-semibold">
          {promo.label}: {promoCountdown(promo)}
        </p>
        <p>{promoNotice(promo)}</p>
      </div>
    </div>
  );
}

/** Every promotional rate on the card, with how much of it is gone and how long it has left. */
function PromosCard({
  account,
  promos,
  onAdd,
  onEdit,
}: {
  account: Account;
  promos: readonly Promo[];
  onAdd: () => void;
  onEdit: (promo: Promo) => void;
}) {
  const remove = useDeletePromo(account.id);
  return (
    <Card title="Promo rates">
      {promos.length === 0 ? (
        <p className="text-muted">
          No promotional rate on this card. If part of the balance is on an offer, like 0% on a balance transfer until a date, add it here
          for a countdown and a warning before it ends.
        </p>
      ) : (
        <ul className="divide-y divide-line">
          {promos.map((promo) => (
            <li key={promo.id} className="py-3 first:pt-0">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <p className="font-semibold">{promo.label}</p>
                  <p className="text-sm text-muted">
                    {PROMO_KIND_LABELS[promo.kind]} · {promoCountdown(promo)}
                    {promo.deferredInterest && " · deferred interest"}
                  </p>
                </div>
                <div className="flex shrink-0 items-center gap-1">
                  <EditButton name={promo.label} onClick={() => onEdit(promo)} />
                  <button
                    type="button"
                    disabled={remove.isPending}
                    onClick={() => remove.mutate(promo.id)}
                    aria-label={`Remove ${promo.label}`}
                    className={BUTTON_LINK}
                  >
                    Remove
                  </button>
                </div>
              </div>
              <div
                role="progressbar"
                aria-label={`${promo.label}: paid off`}
                aria-valuemin={0}
                aria-valuemax={100}
                aria-valuenow={promo.paidPercent}
                className="mt-2 h-3 overflow-hidden rounded-full bg-bg"
              >
                <div className="h-full rounded-full bg-accent" style={{ width: `${promo.paidPercent}%` }} />
              </div>
              <p className="mt-1 text-sm text-muted">
                <span data-amount className="font-semibold text-ink tabular-nums">{formatCents(promo.remainingCents)}</span> left of{" "}
                <span data-amount className="tabular-nums">{formatCents(promo.originalBalanceCents)}</span> · {promo.paidPercent}% paid
              </p>
            </li>
          ))}
        </ul>
      )}
      {remove.error && (
        <p role="alert" className="mt-2 text-danger">
          {remove.error.message}
        </p>
      )}
      <button type="button" onClick={onAdd} className={`${BUTTON_SECONDARY} mt-3`}>
        + Add a promo rate
      </button>
    </Card>
  );
}

/**
 * The payoff helper: what clearing each promotion before it ends takes from every paycheck,
 * and one click to make that a recurring payment.
 */
function PayoffCard({ account, promos }: { account: Account; promos: readonly Promo[] }) {
  const addPayoff = useAddPayoff(account.id);
  return (
    <Card title="Payoff helper">
      <ul className="divide-y divide-line">
        {promos.map((promo) => (
          <li key={promo.id} className="flex flex-col gap-3 py-3 first:pt-0 sm:flex-row sm:items-center sm:justify-between">
            <div className="min-w-0">
              <p className="font-semibold">{promo.label}</p>
              {promo.perPaycheckCents === null ? (
                <p className="text-muted">
                  {promo.daysLeft < 0
                    ? "This promotion has ended, so there is no deadline left to plan for."
                    : "No payday is left before it ends. Paying what you can now keeps the interest down."}
                </p>
              ) : (
                <p className="text-muted">
                  <span data-amount className="text-lg font-bold text-ink tabular-nums">{formatCents(promo.perPaycheckCents)}</span> from each
                  of the {promo.paydaysLeft === 1 ? "1 paycheck" : `${promo.paydaysLeft} paychecks`} before {formatDate(promo.endDate)} clears
                  the {formatCents(promo.remainingCents)} left.
                </p>
              )}
            </div>
            {promo.payoffItemId !== null ? (
              <p className="shrink-0 font-semibold">
                Added as a{" "}
                <Link to={`/recurring/${promo.payoffItemId}`} className="text-accent-ink underline">
                  recurring payment
                </Link>
              </p>
            ) : (
              promo.perPaycheckCents !== null && (
                <button
                  type="button"
                  disabled={addPayoff.isPending}
                  onClick={() => addPayoff.mutate(promo.id)}
                  className={`${BUTTON_PRIMARY} shrink-0`}
                >
                  {addPayoff.isPending ? "Adding…" : "Add as a recurring payment"}
                </button>
              )
            )}
          </li>
        ))}
      </ul>
      {addPayoff.error && (
        <p role="alert" className="mt-2 text-danger">
          {addPayoff.error.message}
        </p>
      )}
      <p className={HINT}>
        The payment runs every paycheck until the promotion ends, from the account this card is paid from, or the one your main
        paycheck lands in when none is chosen. You can change or stop it any time under Recurring items.
      </p>
    </Card>
  );
}

/** What each promotion costs if its balance is still there when it ends. */
function IfNotPaidCard({ promos }: { promos: readonly Promo[] }) {
  return (
    <Card title="If it's not paid off">
      <ul className="divide-y divide-line">
        {promos.map((promo) => (
          <li key={promo.id} className="py-3 first:pt-0">
            <p className="font-semibold">{promo.label}</p>
            <p className="text-muted">
              At {formatRate(promo.rateAfterEffectiveBp)} a year, the {formatCents(promo.remainingCents)} on it today would cost about{" "}
              <span data-amount className="font-semibold text-ink tabular-nums">{formatCents(promo.monthlyInterestIfLeftCents)}</span> a month
              in interest.
            </p>
            {promo.deferredInterest && promo.deferredInterestCents > 0 && (
              <p className="mt-1 flex items-start gap-2 font-medium text-warn-ink">
                <TriangleAlert aria-hidden className="mt-0.5 size-5 shrink-0" />
                <span>
                  Deferred interest: about {formatCents(promo.deferredInterestCents)} for the whole promotion would also be charged at once
                  on {formatDate(promo.endDate)}.
                </span>
              </p>
            )}
          </li>
        ))}
      </ul>
      <p className={HINT}>Worked out from today's balance and the rates you entered. Your card's own figures may differ a little.</p>
    </Card>
  );
}

/** The next statement as projected, then the ones entered as they arrived. */
function StatementsCard({ account, statements, onAdd }: { account: Account; statements: readonly CardStatement[]; onAdd: () => void }) {
  const remove = useDeleteStatement(account.id);
  return (
    <Card title="Statements">
      {statements.length === 0 ? (
        <p className="text-muted">
          No statements yet. When one arrives, add its balance: the real number takes over from the estimate, and the payment follows it.
        </p>
      ) : (
        <>
          <div aria-hidden className={`${STATEMENT_ROW} hidden border-b border-line pt-0 text-sm font-semibold text-muted md:grid`}>
            <span>Closed</span>
            <span className="text-right">Balance</span>
            <span className="text-right">Minimum</span>
            <span className="text-right">Paid</span>
            <span>Due</span>
            <span />
          </div>
          <ul className="divide-y divide-line">
            {statements.map((statement) => (
              <li key={statement.id ?? `planned-${statement.closeDate}`} className={STATEMENT_ROW}>
                <span className="font-medium">
                  {formatDate(statement.closeDate)}
                  {!statement.entered && <span className="block text-sm font-normal text-muted">projected</span>}
                </span>
                <span className="text-right">
                  <Amount cents={statement.balanceCents} estimate={!statement.entered} className="font-semibold" />
                </span>
                <span className="text-muted md:sr-only">Minimum payment</span>
                <span data-amount className="pr-2 text-right tabular-nums">
                  {statement.minimumDueCents === null ? "—" : formatCents(statement.minimumDueCents)}
                </span>
                <span className="text-muted md:sr-only">Paid toward it</span>
                <span data-amount className="pr-2 text-right tabular-nums">
                  {formatCents(statement.paidCents)}
                </span>
                <span className="col-span-2 md:col-span-1">
                  Due {formatDate(statement.dueDate)} · <span className="font-semibold">{STATEMENT_STATUS_LABELS[statement.status]}</span>
                </span>
                <span className="col-span-2 text-right md:col-span-1">
                  {statement.id !== null && (
                    <button
                      type="button"
                      disabled={remove.isPending}
                      onClick={() => remove.mutate(statement.id as number)}
                      aria-label={`Remove the statement that closed ${formatDate(statement.closeDate)}`}
                      className={BUTTON_LINK}
                    >
                      Remove
                    </button>
                  )}
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
      {remove.error && (
        <p role="alert" className="mt-2 text-danger">
          {remove.error.message}
        </p>
      )}
      <button type="button" onClick={onAdd} className={`${BUTTON_SECONDARY} mt-3`}>
        + Add a statement
      </button>
    </Card>
  );
}
