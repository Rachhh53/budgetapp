import type { Account } from "../../api/accounts";
import type { Card, CardSettings, PaymentMode, Promo, PromoKind, StatementStatus } from "../../api/cards";
import { LAST_DAY } from "../../api/income";
import { formatDate, formatDay, ordinal } from "../../lib/dates";
import { formatCents } from "../../lib/money";
import { formatRate } from "../../lib/rates";

export const PAYMENT_MODE_LABELS: Record<PaymentMode, string> = {
  statement_balance: "Statement balance",
  full_balance: "Full balance",
  minimum: "Minimum payment",
  fixed: "A fixed amount",
};

/** What each way of paying means, for the form. */
export const PAYMENT_MODE_HELP: Record<PaymentMode, string> = {
  statement_balance: "What the last statement said, each month. Nothing is carried, so no interest.",
  full_balance: "Everything owed on the due day, including charges made since the statement.",
  minimum: "Only the statement's minimum. The rest is carried and charged interest.",
  fixed: "The same amount every month, whatever the statement says.",
};

export const PROMO_KIND_LABELS: Record<PromoKind, string> = {
  balance_transfer: "Balance transfer",
  purchase: "Purchase",
  other: "Something else",
};

export const STATEMENT_STATUS_LABELS: Record<StatementStatus, string> = {
  planned: "Planned",
  paid: "Paid",
  part_paid: "Paid in part",
  due: "To pay",
};

/** A day of the month in words: "the 20th", or "the last day" for 31. */
export function dayOfMonth(day: number): string {
  return day === LAST_DAY ? "the last day" : `the ${ordinal(day)}`;
}

/** The payment setting in a few words: "Statement balance", "Fixed $240.00". */
export function paymentSetting(settings: CardSettings): string {
  return settings.paymentMode === "fixed" && settings.fixedPaymentCents !== null
    ? `Fixed ${formatCents(settings.fixedPaymentCents)}`
    : PAYMENT_MODE_LABELS[settings.paymentMode];
}

/** How the payment is made and from where: "autopay · from Everyday Checking", or that none is scheduled. */
export function paymentSource(settings: CardSettings, accounts: readonly Account[]): string {
  if (settings.payFromAccountId === null) {
    return "no payment scheduled";
  }
  const from = accounts.find((account) => account.id === settings.payFromAccountId)?.name ?? "an account that was removed";
  return `${settings.autopay ? "autopay" : "paid by hand"} · from ${from}`;
}

/** The line under a card's name on the overview. */
export function cardSummary(card: Card): string {
  if (card.settings === null) {
    return "Statement and due days not set up yet";
  }
  return `${paymentSetting(card.settings)} · ${card.settings.autopay ? "autopay" : "paid by hand"} · due ${dayOfMonth(card.settings.dueDay)}`;
}

/** A promotional rate as offers are written: "0%" rather than "0.00%", and "1.99%" as it is. */
function promoRate(basisPoints: number): string {
  return basisPoints % 100 === 0 ? `${basisPoints / 100}%` : formatRate(basisPoints);
}

/** "0% ends Mar 15 · 162 days left": a promotion's countdown, always in words. */
export function promoCountdown(promo: Promo): string {
  const rate = promoRate(promo.rateBp);
  if (promo.daysLeft < 0) {
    return `${rate} ended ${formatDay(promo.endDate)}`;
  }
  const days = promo.daysLeft === 0 ? "ends today" : promo.daysLeft === 1 ? "1 day left" : `${promo.daysLeft} days left`;
  return `${rate} ends ${formatDay(promo.endDate)} · ${days}`;
}

/** What a promotion's banner says: when it ends, what follows, and how much is at stake. */
export function promoNotice(promo: Promo): string {
  const after = formatRate(promo.rateAfterEffectiveBp);
  const left = formatCents(promo.remainingCents);
  if (promo.warning === "ended") {
    return `The ${promoRate(promo.rateBp)} rate ended ${formatDate(promo.endDate)}. The ${left} still on it is now charged ${after}.`;
  }
  const base = `The ${promoRate(promo.rateBp)} rate ends ${formatDate(promo.endDate)}. After that, ${after} APR on what's left.`;
  if (promo.warning === "soon_deferred") {
    return `${base} This one is deferred interest: unless it is cleared by then, about ${formatCents(promo.deferredInterestCents)} of interest for the whole promotion is charged at once.`;
  }
  if (promo.warning === "soon") {
    return base;
  }
  return `${base} Warnings start ${formatDay(promo.warnFrom)}.`;
}
