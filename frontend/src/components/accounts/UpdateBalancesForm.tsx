import { ChevronLeft, ChevronRight, Wallet } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import { type Account, useSaveBalances } from "../../api/accounts";
import { formatDate, todayIso } from "../../lib/dates";
import { formatCents, parseDollars } from "../../lib/money";
import { Modal } from "../Modal";
import { MoneyField } from "../MoneyField";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT } from "../ui";
import { ACCOUNT_GROUPS } from "./labels";

/** How many accounts are shown at once: as many as fit under the date without scrolling, on a laptop and on a phone. */
const PAGE_SIZE = 3;

/**
 * "Update balances" (design/accounts.png): the real numbers for several accounts on one day,
 * typed in one after another instead of opening each account in turn. Only the accounts
 * filled in are saved; the rest keep what they had. One Undo takes the whole lot back.
 *
 * The accounts come a few at a time, in the order of the Accounts screen, so the form never
 * needs scrolling however many there are. Save is there on every page and saves all of them.
 */
export function UpdateBalancesForm({ accounts, onClose }: { accounts: readonly Account[]; onClose: () => void }) {
  const id = useId();
  const save = useSaveBalances();
  const today = todayIso();
  const [date, setDate] = useState(today);
  // What was typed for each account, by account id. An account left empty is left alone.
  const [typed, setTyped] = useState<Record<number, string>>({});
  const [problem, setProblem] = useState<string>();
  const [page, setPage] = useState(0);

  // In the order of the Accounts screen: group by group.
  const open = ACCOUNT_GROUPS.flatMap(({ types }) => accounts.filter((account) => !account.archived && types.includes(account.type)));
  const pages = Math.max(1, Math.ceil(open.length / PAGE_SIZE));
  const first = page * PAGE_SIZE;
  const inView = open.slice(first, first + PAGE_SIZE);
  const groups = ACCOUNT_GROUPS.map(({ title, types }) => ({
    title,
    // A balance on a card or a loan is what is owed, entered as a plain amount.
    owed: types.includes("credit_card") || types.includes("loan"),
    accounts: inView.filter((account) => types.includes(account.type)),
  })).filter((group) => group.accounts.length > 0);
  const filled = open.filter((account) => (typed[account.id] ?? "").trim() !== "").length;

  const goTo = (target: number) => {
    setProblem(undefined);
    setPage(target);
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const balances: { accountId: number; amountCents: number }[] = [];
    for (const [position, account] of open.entries()) {
      const text = (typed[account.id] ?? "").trim();
      if (text === "") {
        continue;
      }
      const amountCents = parseDollars(text);
      if (amountCents === null) {
        // Bring the account into view, so what to put right is on the screen.
        setPage(Math.floor(position / PAGE_SIZE));
        setProblem(`Please enter an amount like 1,250.00 for ${account.name}, or leave it empty.`);
        return;
      }
      balances.push({ accountId: account.id, amountCents });
    }
    if (balances.length === 0) {
      setProblem("Please enter a balance for at least one account.");
      return;
    }
    setProblem(undefined);
    save.mutate({ date, balances }, { onSuccess: onClose });
  };

  const failure = problem ?? save.error?.message;

  return (
    <Modal title="Update balances" icon={Wallet} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <div>
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
            <label htmlFor={`${id}-date`} className="font-medium">
              As of
            </label>
            <input
              id={`${id}-date`}
              type="date"
              value={date}
              max={today}
              onChange={(event) => setDate(event.target.value)}
              required
              className={`${INPUT} min-w-0 flex-1`}
            />
          </div>
          <p className={HINT}>Accounts left empty keep what they had.</p>
        </div>

        {/* Lighter than the panels of other forms, and what was last entered sits beside each name: three accounts fit a page. */}
        {groups.map((group) => (
          <fieldset key={group.title} className="min-w-0 space-y-3">
            <legend className="float-left w-full text-xs font-bold tracking-wider text-muted uppercase">
              {group.title}
              {group.owed && <span className="font-normal tracking-normal normal-case"> · enter what you owe</span>}
            </legend>
            <div className="clear-both space-y-3">
              {group.accounts.map((account) => (
                <MoneyField
                  key={account.id}
                  label={account.name}
                  value={typed[account.id] ?? ""}
                  onChange={(text) => setTyped((current) => ({ ...current, [account.id]: text }))}
                  aside={
                    account.latestBalance
                      ? `${formatCents(account.latestBalance.amountCents)} · ${formatDate(account.latestBalance.date)}`
                      : "nothing entered yet"
                  }
                />
              ))}
            </div>
          </fieldset>
        ))}

        {pages > 1 && (
          <nav aria-label="More accounts" className="flex items-center justify-between gap-2">
            <button
              type="button"
              onClick={() => goTo(page - 1)}
              disabled={page === 0}
              aria-label="Previous accounts"
              className={`${BUTTON_SECONDARY} px-3 disabled:opacity-40`}
            >
              <ChevronLeft aria-hidden className="size-5" />
            </button>
            <p role="status" className="text-center text-sm text-muted">
              Accounts {first + 1}–{first + inView.length} of {open.length}
              {filled > 0 && ` · ${filled} filled in`}
            </p>
            <button
              type="button"
              onClick={() => goTo(page + 1)}
              disabled={page === pages - 1}
              aria-label="Next accounts"
              className={`${BUTTON_SECONDARY} px-3 disabled:opacity-40`}
            >
              <ChevronRight aria-hidden className="size-5" />
            </button>
          </nav>
        )}

        {failure && (
          <p role="alert" className="text-danger">
            {failure}
          </p>
        )}

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="submit" disabled={save.isPending} className={BUTTON_PRIMARY}>
            {save.isPending ? "Saving…" : "Save balances"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
