import { Wallet } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import { type Account, useSaveBalances } from "../../api/accounts";
import { formatDate, todayIso } from "../../lib/dates";
import { formatCents, parseDollars } from "../../lib/money";
import { FormSection } from "../FormSection";
import { Modal } from "../Modal";
import { MoneyField } from "../MoneyField";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";
import { ACCOUNT_GROUPS, accountSubtitle } from "./labels";

/**
 * "Update balances" (design/accounts.png): the real numbers for several accounts on one day,
 * typed in one after another instead of opening each account in turn. Only the accounts
 * filled in are saved; the rest keep what they had. One Undo takes the whole lot back.
 */
export function UpdateBalancesForm({ accounts, onClose }: { accounts: readonly Account[]; onClose: () => void }) {
  const id = useId();
  const save = useSaveBalances();
  const today = todayIso();
  const [date, setDate] = useState(today);
  // What was typed for each account, by account id. An account left empty is left alone.
  const [typed, setTyped] = useState<Record<number, string>>({});
  const [problem, setProblem] = useState<string>();

  const open = accounts.filter((account) => !account.archived);
  const groups = ACCOUNT_GROUPS.map(({ title, types }) => ({ title, accounts: open.filter((account) => types.includes(account.type)) })).filter(
    (group) => group.accounts.length > 0,
  );

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const balances: { accountId: number; amountCents: number }[] = [];
    for (const account of open) {
      const text = (typed[account.id] ?? "").trim();
      if (text === "") {
        continue;
      }
      const amountCents = parseDollars(text);
      if (amountCents === null) {
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
          <label htmlFor={`${id}-date`} className={LABEL}>
            As of
          </label>
          <input
            id={`${id}-date`}
            type="date"
            value={date}
            max={today}
            onChange={(event) => setDate(event.target.value)}
            required
            className={INPUT}
          />
          <p className={HINT}>
            Today or earlier. Fill in the accounts you have the numbers for; any left empty keep what they had.
          </p>
        </div>

        {groups.map((group) => (
          <FormSection key={group.title} title={group.title}>
            {group.accounts.map((account) => (
              <MoneyField
                key={account.id}
                label={account.name}
                value={typed[account.id] ?? ""}
                onChange={(text) => setTyped((current) => ({ ...current, [account.id]: text }))}
                hint={[
                  accountSubtitle(account),
                  account.latestBalance
                    ? `last entered ${formatCents(account.latestBalance.amountCents)} on ${formatDate(account.latestBalance.date)}`
                    : "no balance entered yet",
                  (account.type === "credit_card" || account.type === "loan") && "enter what you owe",
                ]
                  .filter(Boolean)
                  .join(" · ")}
              />
            ))}
          </FormSection>
        ))}

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
