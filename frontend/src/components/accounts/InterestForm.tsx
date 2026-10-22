import { Percent } from "lucide-react";
import { type FormEvent, useState } from "react";
import type { Account } from "../../api/accounts";
import {
  type AccountTransaction,
  useTypeOverInterest,
  useTypeOverRewards,
  useUseEstimatedInterest,
  useUseEstimatedRewards,
} from "../../api/periods";
import { formatDate, formatMonth } from "../../lib/dates";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { BUTTON_LINK, BUTTON_PRIMARY, BUTTON_SECONDARY } from "../ui";

/**
 * Types over one interest line in an account's transactions when the real figure is known
 * (Rachel, 2026-10-19: "edit an interest amount in an account if it's wrong"). The amount simply
 * becomes the amount, for that line only; later months are still worked out from the rate, on the
 * balance it leaves. An investment's growth is added daily but listed by month, so its month is
 * what is typed over.
 */
export function InterestForm({
  account,
  line,
  upcoming,
  onClose,
}: {
  account: Account;
  line: AccountTransaction;
  /** Still to come. Only one typed over before that was refused opens, and only to put the estimate back. */
  upcoming: boolean;
  onClose: () => void;
}) {
  // A card's rewards at a statement close are typed over the same way, through their own route (spec §5.6).
  const rewards = line.kind === "card_rewards";
  const interestSave = useTypeOverInterest(account.id);
  const interestPutBack = useUseEstimatedInterest(account.id);
  const rewardsSave = useTypeOverRewards(account.id);
  const rewardsPutBack = useUseEstimatedRewards(account.id);
  const save = rewards ? rewardsSave : interestSave;
  const putBack = rewards ? rewardsPutBack : interestPutBack;
  const growth = line.kind === "growth";
  const typedOver = line.estimatedCents !== null;
  // Interest is always an amount added; on a card or a loan it is what is owed that grows. Rewards are what came off.
  const [amount, setAmount] = useState(centsToInput(Math.abs(line.amountCents)));
  const [amountError, setAmountError] = useState<string>();
  const when = growth ? formatMonth(line.date) : formatDate(line.date);
  const owed = account.type === "credit_card" || account.type === "loan";
  const what = rewards ? "Rewards" : growth ? "Growth" : "Interest";

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const amountCents = parseDollars(amount);
    if (amountCents === null || amountCents < 0) {
      setAmountError(AMOUNT_HELP);
      return;
    }
    setAmountError(undefined);
    save.mutate({ date: line.date, amountCents }, { onSuccess: onClose });
  };

  const failure = save.error?.message ?? putBack.error?.message;
  if (upcoming) {
    return (
      <Modal title={`${what} · ${when}`} icon={Percent} tone={account.color} onClose={onClose}>
        <div className="space-y-4">
          <p>
            This {rewards ? "statement's rewards are" : growth ? "month's growth is" : "interest is"} still to come, so it shouldn't be set in stone. Put Budget's estimate back
            {line.estimatedCents !== null && (
              <>
                {" "}
                (<span data-amount>{formatCents(Math.abs(line.estimatedCents))}</span> when it was typed over)
              </>
            )}{" "}
            and it will follow your balance and every change before it again.
          </p>
          {failure && (
            <p role="alert" className="text-danger">
              {failure}
            </p>
          )}
          <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
              Cancel
            </button>
            <button
              type="button"
              disabled={putBack.isPending}
              onClick={() => putBack.mutate(line.date, { onSuccess: onClose })}
              className={BUTTON_PRIMARY}
            >
              Use Budget's estimate
            </button>
          </div>
        </div>
      </Modal>
    );
  }
  return (
    <Modal title={`${what} · ${when}`} icon={Percent} tone={account.color} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <MoneyField
          label={rewards ? "Rewards that came off" : growth ? `Growth in ${when}` : owed ? "Interest charged" : "Interest paid"}
          value={amount}
          onChange={setAmount}
          error={amountError}
          hint={
            rewards
              ? "What the statement says came off as rewards."
              : growth
                ? "What the account really grew by that month, apart from anything put in or taken out."
                : owed
                  ? "What the statement says was charged."
                  : "What the bank's statement or website says was paid."
          }
          required
          hero
        />
        <p className="text-sm text-muted">
          {typedOver ? (
            <>
              Budget estimated <span data-amount>{formatCents(Math.abs(line.estimatedCents ?? 0))}</span>.{" "}
            </>
          ) : (
            rewards ? "Budget worked this out from the rate and what was charged. " : "Budget worked this out from the balance and the rate. "
          )}
          Only this {growth ? "month" : "one"} changes; later months are still worked out, from the balance it leaves.
        </p>

        {failure && (
          <p role="alert" className="text-danger">
            {failure}
          </p>
        )}

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:items-center sm:justify-end">
          {typedOver && (
            <button
              type="button"
              disabled={putBack.isPending}
              onClick={() => putBack.mutate(line.date, { onSuccess: onClose })}
              className={`${BUTTON_LINK} sm:mr-auto`}
            >
              Use Budget's estimate
            </button>
          )}
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="submit" disabled={save.isPending} className={BUTTON_PRIMARY}>
            {save.isPending ? "Saving…" : "Save"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
