import { Clock, TriangleAlert } from "lucide-react";
import { useState } from "react";
import { Link, useNavigate } from "react-router";
import { useCameFrom } from "../lib/cameFrom";
import { type Account, type AccountDetail, useAccountPerformance, useAccountProjection, useAccounts } from "../api/accounts";
import { Reconcile } from "../components/accounts/Reconcile";
import { useAttachmentCounts } from "../api/attachments";
import { useCategories, useTags } from "../api/categories";
import {
  type CardRewards,
  type CardStatement,
  type Promo,
  type Redemption,
  useAddPayoff,
  useCard,
  useDeletePromo,
  useDeleteStatement,
} from "../api/cards";
import { AccountForm } from "../components/accounts/AccountForm";
import { BalanceForm } from "../components/accounts/BalanceForm";
import { BalanceList } from "../components/accounts/BalanceList";
import { ProjectionChart } from "../components/accounts/ProjectionChart";
import { RateCard } from "../components/accounts/RateCard";
import { TransactionsCard } from "../components/accounts/TransactionsCard";
import { useAddItem } from "../components/add/AddItem";
import { Amount } from "../components/Amount";
import { Card } from "../components/Card";
import { BillingForm, MinimumForm, PromoForm, StatementForm } from "../components/cards/CardForms";
import { MONTHS, RedemptionForm, RewardsForm } from "../components/cards/RewardsForms";
import {
  dayOfMonth,
  minimumRule,
  PROMO_KIND_LABELS,
  promoCountdown,
  promoNotice,
  STATEMENT_STATUS_LABELS,
} from "../components/cards/labels";
import { EditButton, EditPageButton } from "../components/EditButton";
import { FilesButton, ReceiptsDialog } from "../components/files/ReceiptsDialog";
import { ItemForm } from "../components/items/ItemForm";
import { BUTTON_LINK, BUTTON_PRIMARY, BUTTON_SECONDARY, DETAIL_HEADER, HINT, PAGE_TITLE } from "../components/ui";
import { formatDate, formatDay, todayIso } from "../lib/dates";
import { formatCents } from "../lib/money";
import { formatRate } from "../lib/rates";
import { HelpLink } from "../components/help/HelpLink";

/* One statement: stacked on a phone, columns from `md` up. */
const STATEMENT_ROW = "grid grid-cols-2 gap-x-4 gap-y-1 py-3 md:grid-cols-[9rem_8rem_8rem_8rem_1fr_auto] md:items-center";

/** The pop-up form that is open, if any. One at a time, each short enough for the screen. */
type Open =
  | { form: "account" | "balance" | "billing" | "minimum" | "payment" | "rewards" }
  | { form: "statement"; statement?: CardStatement }
  | { form: "promo"; promo?: Promo }
  | { form: "redemption"; redemption?: Redemption };

/**
 * One credit card (design/cards.png): what is owed and how it is paid, its promotional rates
 * with their countdown, the payoff helper, what happens if a promotion is not cleared, its
 * statements and its charges. One card per topic, each with its own way in.
 *
 * How the card is paid is not set here. Its payment is a recurring item, changed on that item's
 * page like any other; this page shows the next one and links to it, or offers to add it.
 *
 * It is the card's account page: Accounts opens it for a credit card in place of the plain one,
 * so a card has one page, with the chart and entered balances every account has.
 */
export function CardDetailPage({ detail: asAccount }: { detail: AccountDetail }) {
  const back = useCameFrom({ to: "/accounts", label: "Accounts" });
  const id = asAccount.account.id;
  const navigate = useNavigate();
  const detail = useCard(id);
  const accounts = useAccounts();
  const categories = useCategories();
  const tags = useTags();
  const projection = useAccountProjection(id);
  const performance = useAccountPerformance(id);
  const { add } = useAddItem();
  const [open, setOpen] = useState<Open | null>(null);
  const close = () => setOpen(null);

  if (detail.isPending) {
    return <p className="text-muted">Loading this card…</p>;
  }
  if (detail.isError) {
    return (
      <>
        <h1 className={`${PAGE_TITLE} mb-4`}>{asAccount.account.name}</h1>
        <Card>
          <p role="alert" className="font-medium">
            {detail.error.message}
          </p>
          <Link to="/accounts" className="mt-2 inline-block font-semibold text-accent-ink underline">
            Back to all accounts
          </Link>
        </Card>
      </>
    );
  }

  const { card, promos, statements, rates, nextCloseDate, nextDueDate, minimum, rewards } = detail.data;
  const { account, settings } = card;
  const everyAccount = accounts.data ?? [];
  const points = projection.data ?? [];
  // A promotion's "rate afterwards" is nearly always the card's regular APR: offered for it, never assumed.
  const promoWithRateAfter = promos.find((promo) => promo.rateAfterBp !== null);
  const afterPromo =
    promoWithRateAfter?.rateAfterBp != null
      ? {
          rateBp: promoWithRateAfter.rateAfterBp,
          why: `You entered ${formatRate(promoWithRateAfter.rateAfterBp)} as the rate after ${promoWithRateAfter.label}. If that is this card's regular APR, use it here too.`,
        }
      : undefined;
  // Only promotions that still carry a balance are worth a warning or a plan to clear them.
  const withBalance = promos.filter((promo) => promo.remainingCents > 0);
  const flagged = withBalance.filter((promo) => promo.warning !== "none");

  return (
    <>
      <div className={DETAIL_HEADER}>
        <div className="min-w-0">
          <p className="text-sm text-muted">
            <Link to={back.to} className="text-accent-ink underline">
              {back.label}
            </Link>{" "}
            / Credit card
          </p>
          <div className="flex min-w-0 items-center gap-1">
            <h1 className={`${PAGE_TITLE} flex items-center gap-3`}>
              <span aria-hidden className="size-4 shrink-0 rounded-full" style={{ backgroundColor: account.color }} />
              <span className="truncate">{account.name}</span>
            </h1>
            <HelpLink />
          </div>
          <p className="text-muted">
            {settings
              ? `Closes on ${dayOfMonth(settings.statementCloseDay)} · due on ${dayOfMonth(settings.dueDay)}`
              : "Statement and due days not set up yet"}
            {account.archived && " · Archived"}
          </p>
        </div>
        <div className="flex flex-col gap-2 sm:flex-row">
          <EditPageButton label="Edit account" onClick={() => setOpen({ form: "account" })} />
          {/* Its statements are added from their own card below; the header keeps the same buttons, in the same places, as every account. */}
          <button type="button" onClick={() => add("money_out", account.id)} className={BUTTON_SECONDARY}>
            + Charge
          </button>
          <button type="button" onClick={() => setOpen({ form: "balance" })} className={BUTTON_PRIMARY}>
            Update balance
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
            </div>
            <div className="min-w-0">
              <p className="text-muted">Next payment</p>
              {card.nextPayment ? (
                <>
                  <Amount cents={card.nextPayment.amountCents} estimate={card.nextPayment.estimate} className="-ml-2 text-2xl font-bold" />
                  <p className="text-muted">
                    {formatDate(card.nextPayment.date)} · {card.nextPayment.autopay ? "by autopay" : "paid by hand"}
                  </p>
                  <Link to={`/recurring/${card.nextPayment.itemId}`} className="font-semibold break-words text-accent-ink underline">
                    {card.nextPayment.name}
                  </Link>
                </>
              ) : settings?.paymentItemId != null ? (
                <>
                  <p className="mt-1 text-muted">Nothing is scheduled.</p>
                  <Link to={`/recurring/${settings.paymentItemId}`} className="font-semibold text-accent-ink underline">
                    See its recurring payment
                  </Link>
                </>
              ) : (
                <>
                  <p className="mt-1 text-muted">No recurring payment yet.</p>
                  <button type="button" onClick={() => setOpen({ form: "payment" })} className={BUTTON_LINK}>
                    Add it as a recurring payment
                  </button>
                </>
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
        </Card>

        {account.latestBalance && points.length > 0 && (
          <Card title="Where it's heading">
            <ProjectionChart account={account} balances={asAccount.balances} points={points} showAvailable={false} />
          </Card>
        )}

        <PromosCard
          account={account}
          promos={promos}
          onAdd={() => setOpen({ form: "promo" })}
          onEdit={(promo) => setOpen({ form: "promo", promo })}
        />
        {withBalance.length > 0 && <PayoffCard account={account} promos={withBalance} />}
        {withBalance.length > 0 && <IfNotPaidCard promos={withBalance} />}

        <StatementsCard
          account={account}
          statements={statements}
          onAdd={() => setOpen({ form: "statement" })}
          onEdit={(statement) => setOpen({ form: "statement", statement })}
        />

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
                  : `About ${formatCents(settings.monthlyEstimateCents)} of spending each statement month in all. Charges you enter count toward it; the rest is estimated until the real statement is entered.`}
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

        {/* Kept with the card's dates, and used only once they are in: nothing is projected before then. */}
        {settings && (
          <Card
            title="Minimum payment estimate"
            action={<EditButton name="minimum payment estimate" onClick={() => setOpen({ form: "minimum" })} />}
          >
            <p className="font-semibold">{minimumRule(minimum)}</p>
            <p className={HINT}>
              How this card's minimum is worked out until you enter a statement with the real one. Card companies differ, so change it to
              match yours.
            </p>
          </Card>
        )}

        {/* Rewards are earned at a statement close, so like the minimum they wait for the card's dates (spec §5.6). */}
        {settings && (
          <Card title="Rewards" action={<EditButton name="rewards" onClick={() => setOpen({ form: "rewards" })} />}>
            {rewards === null ? (
              <p className="text-muted">
                No rewards set. If this card gives cash back, add its rate: it can come off each statement, or build up until you use it.
              </p>
            ) : !rewards.buildUp ? (
              <>
                <p className="font-semibold">{formatRate(rewards.rateBp)} cash back on what is charged</p>
                <p className={HINT}>
                  All of it comes off the balance when each statement closes, so the payment after it is smaller. Type over a month that came
                  out different under Charges and payments below.
                </p>
              </>
            ) : (
              <BuiltUpRewards rewards={rewards} onRedeem={(redemption) => setOpen({ form: "redemption", redemption })} />
            )}
          </Card>
        )}

        <RateCard
          account={account}
          rates={rates}
          title="Regular APR"
          unit="a year"
          about="Charged each month on what was left unpaid from the statement before. Paying the statement in full means no interest."
          missing="No regular APR entered yet. It is the card's everyday rate, separate from any promo rate. Without one, a balance that is carried is projected with no interest."
          suggestion={afterPromo}
        />

        <TransactionsCard account={account} title="Charges and payments" />
        <Card title="Balances you entered" tip="enteredBalance">
          {asAccount.balances.length === 0 ? (
            <p className="text-muted">
              Nothing here yet. Choose "Update balance" and type in what the card shows as owed. Projections start from the latest number
              you enter, or from the latest statement.
            </p>
          ) : (
            <BalanceList account={account} balances={asAccount.balances} changes={performance.data?.balances ?? []} owed />
          )}
        </Card>
      </div>

      {open?.form === "account" && (
        <AccountForm account={account} accounts={everyAccount} onClose={close} onDeleted={() => navigate("/accounts")} />
      )}
      {open?.form === "balance" && <BalanceForm account={account} onClose={close} />}
      {open?.form === "billing" && <BillingForm account={account} settings={settings} onClose={close} />}
      {open?.form === "payment" && (
        <ItemForm
          accounts={everyAccount}
          categories={categories.data ?? []}
          tags={tags.data ?? []}
          starting={{
            kind: "card_payment",
            name: `${account.name} payment`,
            payToAccountId: account.id,
            day1: settings?.dueDay,
            // With the card's dates in, it can follow the statement; without them it needs an amount.
            cardPaymentMode: settings ? "statement_balance" : "fixed",
          }}
          onClose={close}
        />
      )}
      {open?.form === "minimum" && <MinimumForm account={account} rule={minimum} onClose={close} />}
      {open?.form === "rewards" && <RewardsForm account={account} rewards={rewards} onClose={close} />}
      {open?.form === "redemption" && (
        <RedemptionForm account={account} redemption={open.redemption} builtUpCents={rewards?.builtUpCents ?? 0} onClose={close} />
      )}
      {open?.form === "statement" && <StatementForm account={account} settings={settings} statement={open.statement} onClose={close} />}
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
              {promo.originalBalanceCents === null || promo.paidPercent === null ? (
                // On the whole card there is no starting amount to be a share of, so no bar: only what is on the rate today.
                <p className="mt-1 text-sm text-muted">
                  <span data-amount className="font-semibold text-ink tabular-nums">{formatCents(promo.remainingCents)}</span> on this rate
                  today · everything on the card
                </p>
              ) : (
                <>
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
                </>
              )}
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
function StatementsCard({
  account,
  statements,
  onAdd,
  onEdit,
}: {
  account: Account;
  statements: readonly CardStatement[];
  onAdd: () => void;
  onEdit: (statement: CardStatement) => void;
}) {
  const remove = useDeleteStatement(account.id);
  // Which statements have their PDF (or anything else) attached; the dates asked for do not matter to statements.
  const today = todayIso();
  const counts = useAttachmentCounts(today, today);
  const [filesOf, setFilesOf] = useState<CardStatement>();
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
                <span className="col-span-2 flex items-center justify-end gap-1 md:col-span-1">
                  {statement.id !== null && (
                    <EditButton name={`the statement that closed ${formatDate(statement.closeDate)}`} onClick={() => onEdit(statement)} />
                  )}
                  {statement.id !== null && (
                    <FilesButton
                      count={counts.data?.statements.find((files) => files.statementId === statement.id)?.count ?? 0}
                      name={`the statement that closed ${formatDate(statement.closeDate)}`}
                      onClick={() => setFilesOf(statement)}
                    />
                  )}
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
                {statement.recordsCents !== null && statement.reconcileFrom !== null && (
                  <div className="col-span-full">
                    <Reconcile
                      account={account}
                      from={statement.reconcileFrom}
                      through={statement.closeDate}
                      recordsCents={statement.recordsCents}
                      enteredCents={statement.balanceCents}
                    />
                  </div>
                )}
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
      {filesOf?.id != null && (
        <ReceiptsDialog
          title={`Statement closed ${formatDate(filesOf.closeDate)}`}
          owner={{ type: "statement", cardId: account.id, statementId: filesOf.id }}
          onClose={() => setFilesOf(undefined)}
        />
      )}
    </Card>
  );
}

/**
 * Rewards left to build up: the rate, what has built up today, how they're used on their own, the next
 * credit, and each redemption with its pencil, plus Redeem (Rachel, 2026-10-20).
 */
function BuiltUpRewards({ rewards, onRedeem }: { rewards: CardRewards; onRedeem: (redemption?: Redemption) => void }) {
  const rule =
    rewards.yearlyMonth !== null
      ? `All of it goes on every ${MONTHS[rewards.yearlyMonth - 1]}, at that statement's close.`
      : rewards.thresholdCents !== null
        ? `All of it goes on once it reaches ${formatCents(rewards.thresholdCents)}.`
        : "They build up until you redeem them.";
  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-muted">Built up today</p>
          <Amount cents={rewards.builtUpCents} className="-ml-2 text-2xl font-bold" />
          <p className="text-muted">{formatRate(rewards.rateBp)} cash back on what is charged</p>
        </div>
        <button type="button" onClick={() => onRedeem()} className={BUTTON_SECONDARY}>
          Redeem
        </button>
      </div>
      <p className="mt-2">{rule}</p>
      {rewards.nextCreditDate !== null && rewards.nextCreditCents !== null && (
        <p className="text-muted">
          Next credit: <span data-amount>{formatCents(rewards.nextCreditCents)}</span> on {formatDate(rewards.nextCreditDate)}
        </p>
      )}
      {rewards.redemptions.length > 0 && (
        <ul className="mt-3 divide-y divide-line border-t border-line">
          {rewards.redemptions.map((redemption) => (
            <li key={redemption.id} className="flex min-h-11 items-center gap-3 py-1">
              <span className="min-w-0 flex-1">
                <span className="block font-medium">{formatDate(redemption.date)}</span>
                <span className="block text-sm text-muted">
                  {redemption.amountCents === null ? "Everything built up" : "A set amount"}
                  {redemption.note && ` · ${redemption.note}`}
                </span>
              </span>
              <span data-amount className="shrink-0 tabular-nums">
                {formatCents(redemption.creditedCents)}
              </span>
              <EditButton name={`redemption on ${formatDate(redemption.date)}`} onClick={() => onRedeem(redemption)} />
            </li>
          ))}
        </ul>
      )}
      {rewards.startCents !== null && rewards.startDate !== null && (
        <p className={`${HINT} mt-2`}>
          Counting from <span data-amount>{formatCents(rewards.startCents)}</span> built up on {formatDate(rewards.startDate)}. Statements you
          enter already hold their own rewards, so update this when the card shows a different total.
        </p>
      )}
    </>
  );
}
