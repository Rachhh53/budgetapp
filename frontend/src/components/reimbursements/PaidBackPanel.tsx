import { type FormEvent, useId, useState } from "react";
import { Link } from "react-router";
import { useAccounts } from "../../api/accounts";
import type { Occurrence } from "../../api/periods";
import {
  paymentPath,
  useFlagPack,
  useFlagPayment,
  useItemReimbursement,
  useUnflagPack,
  useUnflagPayment,
} from "../../api/reimbursements";
import { formatDate } from "../../lib/dates";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { summary } from "../expenses/ReimbursementCard";
import { ComesOutOfField } from "./ComesOutOfField";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { BUTTON_LINK, BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

/** About a month: what a first flagged payment's days after starts at. */
const USUAL_DAYS_AFTER = 30;

/**
 * Flagging one payment of a bill or budget to be paid back (Rachel, 2026-10-24: "only payments I flag and a set amount
 * defaulted to the payment being flagged"), in place of the payment's form, as its receipts are, so there is still one
 * dialog and it fits the screen. What comes back starts at the payment's amount. The first payment of an item flagged also
 * says where the money comes back into and about how many days after; from then on that is the item's, changed on its page.
 *
 * A pack paid ahead that is still to buy is flagged the same way (Rachel, 2026-10-25: "Flag each pack"; "partials are
 * possible"), up to its price: the money is planned back the item's days after it is due, and the flag goes onto the
 * payment once the pack is bought.
 */
export function PaidBackPanel({ occurrence, onBack }: { occurrence: Occurrence; onBack: () => void }) {
  const id = useId();
  const pack = occurrence.plannedPack === true;
  const item = useItemReimbursement(occurrence.itemId);
  const accounts = useAccounts();
  const flagPayment = useFlagPayment(occurrence.itemId, occurrence.scheduledDate);
  const unflagPayment = useUnflagPayment(occurrence.itemId, occurrence.scheduledDate);
  const flagPack = useFlagPack(occurrence.itemId, occurrence.scheduledDate);
  const unflagPack = useUnflagPack(occurrence.itemId, occurrence.scheduledDate);
  const flag = pack ? flagPack : flagPayment;
  const unflag = pack ? unflagPack : unflagPayment;
  // A pack is named by the day Budget planned it, its scheduled date.
  const flaggedPack = pack ? item.data?.packs.find((one) => one.plannedDate === occurrence.scheduledDate) : undefined;
  const flaggedPayment = pack ? undefined : item.data?.payments.find((payment) => payment.scheduledDate === occurrence.scheduledDate);
  const flagged = flaggedPack ?? flaggedPayment;
  const settings = item.data?.settings ?? null;
  const [amount, setAmount] = useState<string>();
  // The way back, asked for only while the item has none: it comes back into the account it was paid from, to begin with.
  const [into, setInto] = useState(String(occurrence.payFromAccountId));
  const [days, setDays] = useState(String(USUAL_DAYS_AFTER));
  const [by, setBy] = useState("");
  // Where this one comes out of: what it was flagged with, or the item's to begin with.
  const [from, setFrom] = useState<number | null>();
  const [problem, setProblem] = useState<string>();
  const choices = (accounts.data ?? []).filter((account) => !account.archived && ["checking", "savings", "credit_card"].includes(account.type));

  if (item.isPending) {
    return <p className="text-muted">Loading…</p>;
  }
  const shown = amount ?? centsToInput(flagged?.amountCents ?? occurrence.amountCents);
  const intoAccountId = settings === null ? Number(into) : settings.intoAccountId;
  const comesFrom = from !== undefined ? from : flagged ? flagged.fromAccountId : (settings?.fromAccountId ?? null);
  const fromAccountId = comesFrom === intoAccountId ? null : comesFrom;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const amountCents = parseDollars(shown);
    if (amountCents === null || amountCents <= 0) {
      setProblem(amountCents === null ? AMOUNT_HELP : "Please enter how much comes back, more than $0.00.");
      return;
    }
    if (pack && amountCents > occurrence.amountCents) {
      setProblem("Please enter how much comes back, more than $0.00 and no more than the pack.");
      return;
    }
    const daysAfter = Number(days);
    if (settings === null && (days.trim() === "" || !Number.isInteger(daysAfter) || daysAfter < 0 || daysAfter > 365)) {
      setProblem("Please enter about how many days after it comes back, from 0 to 365.");
      return;
    }
    setProblem(undefined);
    flag.mutate(
      settings === null
        ? { amountCents, intoAccountId: Number(into), daysAfter, reimbursedBy: by.trim() === "" ? null : by.trim(), fromAccountId }
        : { amountCents, fromAccountId },
      { onSuccess: () => onBack() },
    );
  };

  const failure = problem ?? flag.error?.message ?? unflag.error?.message ?? item.error?.message;

  return (
    <form onSubmit={submit} className="space-y-4">
      <p className="text-muted">
        {flaggedPayment
          ? summary(flaggedPayment.reimbursement)
          : flaggedPack
            ? `${formatCents(flaggedPack.amountCents)} planned back about ${formatDate(flaggedPack.expectedOn)}. When you record the payment for this pack, it goes with it.`
            : pack
              ? `The pack due ${formatDate(occurrence.dueDate)}. Budget plans the money coming back, and once you record the payment, Expenses & receipts tracks it.`
              : `This one, due ${formatDate(occurrence.dueDate)}. Budget plans the money coming back, and Expenses & receipts tracks it.`}
      </p>

      <MoneyField
        label="Comes back"
        value={shown}
        onChange={setAmount}
        hint={`The ${pack ? "pack" : "payment"} is ${formatCents(occurrence.amountCents)}.`}
        required
      />

      {settings === null ? (
        <>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label htmlFor={`${id}-into`} className={LABEL}>
                Comes back into
              </label>
              <select id={`${id}-into`} value={into} onChange={(event) => setInto(event.target.value)} className={INPUT}>
                {choices.map((account) => (
                  <option key={account.id} value={account.id}>
                    {account.name}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor={`${id}-days`} className={LABEL}>
                Days after it's paid
              </label>
              <input
                id={`${id}-days`}
                type="number"
                inputMode="numeric"
                min={0}
                max={365}
                value={days}
                onChange={(event) => setDays(event.target.value)}
                className={INPUT}
              />
            </div>
          </div>
          <div>
            <label htmlFor={`${id}-by`} className={LABEL}>
              Who pays it back (optional)
            </label>
            <input
              id={`${id}-by`}
              value={by}
              onChange={(event) => setBy(event.target.value)}
              maxLength={120}
              placeholder="e.g. HSA, work, insurance"
              autoComplete="off"
              className={INPUT}
            />
            <p className={HINT}>Set once for {occurrence.name}: every payment you flag comes back this way.</p>
          </div>
        </>
      ) : (
        <p className={HINT}>
          Comes back into {accounts.data?.find((account) => account.id === settings.intoAccountId)?.name ?? "an account"} about{" "}
          {settings.daysAfter} {settings.daysAfter === 1 ? "day" : "days"} after it's paid. That's changed on {occurrence.name}'s page.
        </p>
      )}

      <ComesOutOfField value={fromAccountId} onChange={setFrom} intoAccountId={intoAccountId} />

      {flagged && (
        <div className="flex flex-wrap items-center gap-2">
          {flaggedPayment && (
            <Link to={paymentPath(occurrence.itemId, occurrence.scheduledDate)} className="font-semibold text-accent-ink underline">
              See where it stands
            </Link>
          )}
          <button type="button" onClick={() => unflag.mutate(undefined, { onSuccess: () => onBack() })} disabled={unflag.isPending} className={BUTTON_LINK}>
            Not paid back after all
          </button>
        </div>
      )}

      {failure && (
        <p role="alert" className="text-danger">
          {failure}
        </p>
      )}

      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <button type="button" onClick={onBack} className={BUTTON_SECONDARY}>
          Back
        </button>
        <button type="submit" disabled={flag.isPending} className={BUTTON_PRIMARY}>
          {flag.isPending ? "Saving…" : flagged ? "Save" : "It'll be paid back"}
        </button>
      </div>
    </form>
  );
}
