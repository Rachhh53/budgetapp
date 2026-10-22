import { type Entry, useEntries } from "../../api/entries";
import { addDays, formatDay, todayIso } from "../../lib/dates";
import { formatCents } from "../../lib/money";

/** How far back, and ahead, to look for expenses that money might be paying back: two years. */
const PAYS_BACK_DAYS = 730;

/**
 * The choices for "Pays back an expense": expenses still owed, the one already chosen whatever is left on it, and
 * `also`, the expense the form was opened from. Shared by money coming in and money moved out of a bucket, which
 * both can pay yourself back for something (Rachel, 2026-10-21).
 *
 * @param enabled  false while the form can't pay anything back, so nothing is fetched
 * @param chosenId the expense already chosen, as the picker holds it
 */
export function useOwedExpenses(enabled: boolean, chosenId: string | null, also?: Entry) {
  const today = todayIso();
  const recent = useEntries(addDays(today, -PAYS_BACK_DAYS), addDays(today, PAYS_BACK_DAYS), enabled);
  return [...(recent.data ?? []), ...(also ? [also] : [])]
    .filter(
      (candidate, index, all) =>
        candidate.kind === "money_out" &&
        all.findIndex((other) => other.id === candidate.id) === index &&
        ((candidate.reimbursement?.owedCents ?? 0) > 0 || String(candidate.id) === chosenId),
    )
    .map((expense) => ({
      id: String(expense.id),
      label: `${formatDay(expense.date)} · ${expense.payee ?? expense.description} · ${formatCents(expense.amountCents)}`,
    }));
}
