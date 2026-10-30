import { type Entry, useEntries } from "../../api/entries";
import { type FlaggedPayment, usePaymentReimbursements } from "../../api/reimbursements";
import { addDays, formatDay, todayIso } from "../../lib/dates";
import { formatCents } from "../../lib/money";

/** How far back, and ahead, to look for expenses that money might be paying back: two years. */
const PAYS_BACK_DAYS = 730;

/**
 * What money coming back can pay back: a one-off expense, or a recurring payment flagged to be paid back (Rachel,
 * 2026-10-24: "same as one-off expenses").
 */
export type PaysBackTarget = Entry | FlaggedPayment;

export function isFlaggedPayment(target: PaysBackTarget): target is FlaggedPayment {
  return "scheduledDate" in target;
}

/** The picker's value for each, so an expense and a flagged payment with the same id are never taken for each other. */
export function choiceOf(reimbursesEntryId: number | null, reimbursesPaymentId: number | null): string | null {
  return reimbursesEntryId !== null ? `expense-${reimbursesEntryId}` : reimbursesPaymentId !== null ? `payment-${reimbursesPaymentId}` : null;
}

export function choiceFor(target: PaysBackTarget): string {
  return isFlaggedPayment(target) ? `payment-${target.id}` : `expense-${target.id}`;
}

/** What the picker's value says is paid back, as the backend takes it. */
export function paysBackIds(choice: string | null): { reimbursesEntryId: number | null; reimbursesPaymentId: number | null } {
  const [kind, id] = choice?.split("-") ?? [];
  return {
    reimbursesEntryId: kind === "expense" ? Number(id) : null,
    reimbursesPaymentId: kind === "payment" ? Number(id) : null,
  };
}

/** The account its money is planned to come back into; `null` when not planned. */
export function intoOf(target: PaysBackTarget): number | null {
  return isFlaggedPayment(target) ? target.intoAccountId : (target.reimbursement?.intoAccountId ?? null);
}

/** The account its money comes out of, like an FSA; `null` when it comes from outside. */
export function fromOf(target: PaysBackTarget): number | null {
  return isFlaggedPayment(target) ? target.fromAccountId : (target.reimbursement?.fromAccountId ?? null);
}

/** What it is called, for the money's description: "Reimbursement: {this}". */
export function targetName(target: PaysBackTarget): string {
  return isFlaggedPayment(target) ? target.name : (target.payee ?? target.description);
}

/** What is still owed on it, the likely amount of the money coming back; undefined when nothing is tracked. */
export function owedOn(target: PaysBackTarget | undefined): number | undefined {
  return target?.reimbursement?.owedCents;
}

/**
 * The choices for "Pays back an expense": expenses and flagged recurring payments still owed, the one already chosen
 * whatever is left on it, and `also`, the one the form was opened from. Shared by money coming in and money moved out of a
 * bucket, which both can pay yourself back for something (Rachel, 2026-10-21).
 *
 * @param enabled  false while the form can't pay anything back, so nothing is fetched
 * @param chosen   the choice already made, as the picker holds it
 */
export function useOwedExpenses(enabled: boolean, chosen: string | null, also?: PaysBackTarget) {
  const today = todayIso();
  const year = Number(today.slice(0, 4));
  const recent = useEntries(addDays(today, -PAYS_BACK_DAYS), addDays(today, PAYS_BACK_DAYS), enabled);
  // Flagged payments are listed by year: this one and the one before, as far back as expenses are looked for.
  const thisYear = usePaymentReimbursements(year, enabled);
  const lastYear = usePaymentReimbursements(year - 1, enabled);
  const targets: PaysBackTarget[] = [
    ...(recent.data ?? []).filter((entry) => entry.kind === "money_out"),
    ...(lastYear.data ?? []),
    ...(thisYear.data ?? []),
    ...(also ? [also] : []),
  ];
  return targets
    .filter(
      (target, index, all) =>
        all.findIndex((other) => choiceFor(other) === choiceFor(target)) === index &&
        ((owedOn(target) ?? 0) > 0 || choiceFor(target) === chosen),
    )
    .map((target) => ({
      id: choiceFor(target),
      label: `${formatDay(target.date)} · ${targetName(target)} · ${formatCents(target.amountCents)}`,
    }));
}
