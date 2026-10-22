import { Gift } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import type { Account } from "../../api/accounts";
import { type CardRewards, type Redemption, useDeleteRedemption, useSaveRedemption, useSaveRewards } from "../../api/cards";
import { todayIso } from "../../lib/dates";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { parseRate, rateToInput } from "../../lib/rates";
import { ConfirmDelete } from "../ConfirmDelete";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { Segmented } from "../Segmented";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

/** The most cash back a card is taken to give, in basis points: 20%, as the server allows. */
const MOST_REWARDS_BP = 2000;
const NOTE_MAX = 80;

export const MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];

type Use = "each_statement" | "build_up";
type Rule = "none" | "yearly" | "threshold";

const USE_OPTIONS: readonly { value: Use; label: string }[] = [
  { value: "each_statement", label: "Off each statement" },
  { value: "build_up", label: "Let them build up" },
];

/**
 * A card's rewards (spec §5.6): a cash-back rate on what is charged, and how it is used. Off each
 * statement, it comes off the balance at every close. Left to build up, Budget keeps the running
 * total, and it goes on as a statement credit by a rule (a month each year, or once it reaches an
 * amount) or by a redemption on the card's page (Rachel, 2026-10-20: "you should also be able to
 * select don't apply and they just accumulate and budget tracks that. I want to say when they apply").
 */
export function RewardsForm({ account, rewards, onClose }: { account: Account; rewards: CardRewards | null; onClose: () => void }) {
  const id = useId();
  const save = useSaveRewards(account.id);
  const [rate, setRate] = useState(rewards === null ? "" : rateToInput(rewards.rateBp));
  const [use, setUse] = useState<Use>(rewards?.buildUp ? "build_up" : "each_statement");
  const [rule, setRule] = useState<Rule>(rewards?.yearlyMonth != null ? "yearly" : rewards?.thresholdCents != null ? "threshold" : "none");
  const [month, setMonth] = useState(String(rewards?.yearlyMonth ?? 12));
  const [threshold, setThreshold] = useState(rewards?.thresholdCents == null ? "" : centsToInput(rewards.thresholdCents));
  const [start, setStart] = useState(rewards?.startCents == null ? "" : centsToInput(rewards.startCents));
  const [startDate, setStartDate] = useState(rewards?.startDate ?? todayIso());
  const [problem, setProblem] = useState<string>();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const typed = parseRate(rate);
    if (typed === null || typed < 1 || typed > MOST_REWARDS_BP) {
      setProblem("Please enter the cash back as a percentage from 0.01 to 20, like 1.50.");
      return;
    }
    const buildUp = use === "build_up";
    const thresholdCents = buildUp && rule === "threshold" ? parseDollars(threshold) : null;
    if (buildUp && rule === "threshold" && (thresholdCents === null || thresholdCents <= 0)) {
      setProblem("Please enter the amount they go on at, more than $0.00.");
      return;
    }
    const startCents = buildUp && start.trim() !== "" ? parseDollars(start) : null;
    if (buildUp && start.trim() !== "" && (startCents === null || startCents < 0)) {
      setProblem(AMOUNT_HELP);
      return;
    }
    if (startCents !== null && (startDate === "" || startDate > todayIso())) {
      setProblem("Please choose a day up to today for what has built up.");
      return;
    }
    setProblem(undefined);
    save.mutate(
      {
        rateBp: typed,
        buildUp,
        yearlyMonth: buildUp && rule === "yearly" ? Number(month) : null,
        thresholdCents,
        startCents,
        startDate: startCents === null ? null : startDate,
      },
      { onSuccess: onClose },
    );
  };

  const failure = problem ?? save.error?.message;
  return (
    <Modal title={`Rewards — ${account.name}`} icon={Gift} tone={account.color} onClose={onClose}>
      <form onSubmit={submit} noValidate className="space-y-4">
        <div>
          <label htmlFor={`${id}-rate`} className={LABEL}>
            Cash back on what is charged (%)
          </label>
          <input
            id={`${id}-rate`}
            value={rate}
            onChange={(event) => setRate(event.target.value)}
            inputMode="decimal"
            autoComplete="off"
            placeholder="1.50"
            className={INPUT}
          />
        </div>
        <Segmented legend="How they're used" options={USE_OPTIONS} value={use} onChange={setUse} />
        {use === "each_statement" ? (
          <p className={HINT}>
            All of it comes off the balance when each statement closes, so the payment after it is smaller. Type over a month that came out
            different under Charges and payments.
          </p>
        ) : (
          <>
            <div className="grid grid-cols-2 gap-x-4">
              <MoneyField label="Built up so far" value={start} onChange={setStart} hint="Optional, as the card shows it." />
              <div>
                <label htmlFor={`${id}-start-date`} className={LABEL}>
                  As of
                </label>
                <input
                  id={`${id}-start-date`}
                  type="date"
                  value={startDate}
                  max={todayIso()}
                  onChange={(event) => setStartDate(event.target.value)}
                  className={INPUT}
                />
              </div>
            </div>
            <div>
              <label htmlFor={`${id}-rule`} className={LABEL}>
                Use them all on their own
              </label>
              <div className="flex flex-col gap-2 sm:flex-row">
                <select id={`${id}-rule`} value={rule} onChange={(event) => setRule(event.target.value as Rule)} className={INPUT}>
                  <option value="none">Only when I redeem them</option>
                  <option value="yearly">Every year, in a month</option>
                  <option value="threshold">Once they reach an amount</option>
                </select>
                {rule === "yearly" && (
                  <select aria-label="Month" value={month} onChange={(event) => setMonth(event.target.value)} className={INPUT}>
                    {MONTHS.map((name, index) => (
                      <option key={name} value={String(index + 1)}>
                        {name}
                      </option>
                    ))}
                  </select>
                )}
                {rule === "threshold" && (
                  <div className="relative min-w-0 flex-1">
                    <span aria-hidden className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-muted">
                      $
                    </span>
                    <input
                      aria-label="Amount they go on at"
                      value={threshold}
                      onChange={(event) => setThreshold(event.target.value)}
                      inputMode="decimal"
                      autoComplete="off"
                      placeholder="50.00"
                      className={`${INPUT} pl-7 text-right tabular-nums`}
                    />
                  </div>
                )}
              </div>
              <p className={HINT}>
                Budget keeps the total. They go on as a statement credit at that statement's close, or when you redeem them on the card's
                page.
              </p>
            </div>
          </>
        )}
        {failure && (
          <p role="alert" className="text-danger">
            {failure}
          </p>
        )}
        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          {rewards !== null && (
            <button
              type="button"
              onClick={() =>
                save.mutate(
                  { rateBp: null, buildUp: false, yearlyMonth: null, thresholdCents: null, startCents: null, startDate: null },
                  { onSuccess: onClose },
                )
              }
              disabled={save.isPending}
              className={`${BUTTON_SECONDARY} sm:mr-auto`}
            >
              No rewards
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

/**
 * Redeems built-up rewards as a statement credit on a day: all built up by then, or a set amount
 * (never more than has built up). A future day is planned, and the projection counts it.
 */
export function RedemptionForm({
  account,
  redemption,
  builtUpCents,
  onClose,
}: {
  account: Account;
  redemption?: Redemption;
  builtUpCents: number;
  onClose: () => void;
}) {
  const id = useId();
  const save = useSaveRedemption(account.id, redemption?.id);
  const remove = useDeleteRedemption(account.id, redemption?.id ?? 0);
  const [date, setDate] = useState(redemption?.date ?? todayIso());
  const [all, setAll] = useState(redemption === undefined || redemption.amountCents === null);
  const [amount, setAmount] = useState(redemption?.amountCents == null ? "" : centsToInput(redemption.amountCents));
  const [note, setNote] = useState(redemption?.note ?? "");
  const [problem, setProblem] = useState<string>();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (date === "") {
      setProblem("Please choose the day the credit goes on.");
      return;
    }
    const amountCents = all ? null : parseDollars(amount);
    if (!all && (amountCents === null || amountCents <= 0)) {
      setProblem("Please enter how much to redeem, more than $0.00, or choose everything built up.");
      return;
    }
    setProblem(undefined);
    save.mutate({ date, amountCents, note: note.trim() === "" ? null : note.trim() }, { onSuccess: onClose });
  };

  const failure = problem ?? save.error?.message;
  return (
    <Modal title={redemption ? "Rewards redeemed" : "Redeem rewards"} icon={Gift} tone={account.color} onClose={onClose}>
      <form onSubmit={submit} noValidate className="space-y-4">
        <p className="text-muted">
          {builtUpCents === 0 ? "Nothing has built up yet." : `${formatCents(builtUpCents)} has built up as of today.`} A
          redemption goes on as a statement credit.
        </p>
        <div>
          <label htmlFor={`${id}-date`} className={LABEL}>
            Day it goes on
          </label>
          <input id={`${id}-date`} type="date" value={date} onChange={(event) => setDate(event.target.value)} className={INPUT} />
        </div>
        <Segmented
          legend="How much"
          options={[
            { value: "all", label: "Everything built up" },
            { value: "some", label: "A set amount" },
          ]}
          value={all ? "all" : "some"}
          onChange={(choice) => setAll(choice === "all")}
        />
        {!all && <MoneyField label="Amount" value={amount} onChange={setAmount} hint="Never more than has built up by then." />}
        <div>
          <label htmlFor={`${id}-note`} className={LABEL}>
            Note
          </label>
          <input
            id={`${id}-note`}
            value={note}
            onChange={(event) => setNote(event.target.value)}
            maxLength={NOTE_MAX}
            placeholder="Optional"
            className={INPUT}
          />
        </div>
        {failure && (
          <p role="alert" className="text-danger">
            {failure}
          </p>
        )}
        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          {redemption && (
            <div className="sm:mr-auto">
              <ConfirmDelete
                action="Delete…"
                question="Delete this redemption?"
                detail="Its credit comes off the projection. You can undo it straight after."
                confirmLabel="Delete it"
                pending={remove.isPending}
                error={remove.error?.message}
                onConfirm={() => remove.mutate(undefined, { onSuccess: onClose })}
              />
            </div>
          )}
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="submit" disabled={save.isPending} className={BUTTON_PRIMARY}>
            {save.isPending ? "Saving…" : redemption ? "Save" : "Redeem"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
