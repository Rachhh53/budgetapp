import { Percent } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import { type Account, type Rate, useDeleteRate, useSaveRate } from "../../api/accounts";
import { addDays, formatDate, todayIso } from "../../lib/dates";
import { formatRate, parseRate, rateToInput } from "../../lib/rates";
import { Card } from "../Card";
import { EditButton } from "../EditButton";
import { Modal } from "../Modal";
import { BUTTON_LINK, BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

const RATE_HELP = "Please enter a rate like 6.25.";

interface RateCardProps {
  account: Account;
  /** This account's rates, newest first. */
  rates: readonly Rate[];
  /** The card's heading: "Regular APR", "Interest rate". */
  title: string;
  /** What follows the figure: "a year". */
  unit: string;
  /** What the rate does, once one is entered. */
  about: string;
  /** What to say, and why to add one, while there is none. */
  missing: string;
}

/** The rate in force today: the newest one that has already started. Rates arrive newest first. */
function currentRate(rates: readonly Rate[]): Rate | undefined {
  return rates.find((rate) => rate.effectiveDate <= todayIso());
}

/**
 * The rate a credit card or loan charges, with its history. A rate applies from its date until
 * the next one, so a change of rate never rewrites the months before it.
 */
export function RateCard({ account, rates, title, unit, about, missing }: RateCardProps) {
  const [editing, setEditing] = useState(false);
  const remove = useDeleteRate(account.id);
  const current = currentRate(rates);

  return (
    <Card
      title={title}
      action={current ? <EditButton name={title.toLowerCase()} onClick={() => setEditing(true)} /> : undefined}
    >
      {current ? (
        <>
          <p>
            <span className="text-2xl font-bold tabular-nums">{formatRate(current.rateBp)}</span>{" "}
            <span className="text-muted">
              {unit}, since {formatDate(current.effectiveDate)}
            </span>
          </p>
          <p className={HINT}>{about}</p>
        </>
      ) : (
        <>
          <p className="text-muted">{missing}</p>
          <button type="button" onClick={() => setEditing(true)} className={`${BUTTON_SECONDARY} mt-3`}>
            Add the rate
          </button>
        </>
      )}

      {rates.length > 1 && (
        <section aria-label="Rate history" className="mt-4 border-t border-line pt-4">
          <h3 className="font-semibold">Rate history</h3>
          <ul className="mt-1 divide-y divide-line">
            {rates.map((entry, index) => {
              // Newest first, so the one before it in the list is the one that replaced it.
              const next = rates[index - 1];
              const span = next
                ? `${formatDate(entry.effectiveDate)} to ${formatDate(addDays(next.effectiveDate, -1))}`
                : `from ${formatDate(entry.effectiveDate)}`;
              return (
                <li key={entry.id} className="flex items-center justify-between gap-3">
                  <span>
                    <span className="font-semibold tabular-nums">{formatRate(entry.rateBp)}</span> <span className="text-muted">{span}</span>
                  </span>
                  <button
                    type="button"
                    disabled={remove.isPending}
                    onClick={() => remove.mutate(entry.id)}
                    aria-label={`Remove the rate from ${formatDate(entry.effectiveDate)}`}
                    className={BUTTON_LINK}
                  >
                    Remove
                  </button>
                </li>
              );
            })}
          </ul>
        </section>
      )}
      {remove.error && (
        <p role="alert" className="mt-2 text-danger">
          {remove.error.message}
        </p>
      )}

      {editing && <RateForm account={account} title={title} current={current} onClose={() => setEditing(false)} />}
    </Card>
  );
}

/** The rate and the date it applies from. */
function RateForm({ account, title, current, onClose }: { account: Account; title: string; current: Rate | undefined; onClose: () => void }) {
  const id = useId();
  const saveRate = useSaveRate(account.id);
  const removeRate = useDeleteRate(account.id);
  const [rate, setRate] = useState(current ? rateToInput(current.rateBp) : "");
  const [from, setFrom] = useState(current?.effectiveDate ?? todayIso());
  const [problem, setProblem] = useState<string>();

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const rateBp = parseRate(rate);
    if (rateBp === null) {
      setProblem(RATE_HELP);
      return;
    }
    setProblem(undefined);
    const rateChanged = current === undefined || rateBp !== current.rateBp;
    const dateChanged = current === undefined || from !== current.effectiveDate;
    try {
      if (rateChanged || dateChanged) {
        // A new rate on a new date is a change from then on: the old one stays, ending the day
        // before. The same rate on another date corrects when it began, so the old entry goes.
        await saveRate.mutateAsync({ rateBp, effectiveDate: from });
        if (current !== undefined && !rateChanged) {
          await removeRate.mutateAsync(current.id);
        }
      }
      onClose();
    } catch {
      // The failed step's own message is shown below; the form stays open to try again.
    }
  };

  const saving = saveRate.isPending || removeRate.isPending;
  const failure = problem ?? saveRate.error?.message ?? removeRate.error?.message;

  return (
    <Modal title={`${title} — ${account.name}`} icon={Percent} tone={account.color} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <div>
            <label htmlFor={`${id}-rate`} className={LABEL}>
              Rate a year (%)
            </label>
            <input
              id={`${id}-rate`}
              value={rate}
              onChange={(event) => setRate(event.target.value)}
              inputMode="decimal"
              autoComplete="off"
              placeholder="e.g. 24.49"
              required
              className={`${INPUT} min-h-14 text-2xl font-semibold tabular-nums`}
            />
          </div>
          <div>
            <label htmlFor={`${id}-from`} className={LABEL}>
              Applies from
            </label>
            <input
              id={`${id}-from`}
              type="date"
              value={from}
              onChange={(event) => setFrom(event.target.value)}
              required
              className={`${INPUT} min-h-14`}
            />
          </div>
        </div>
        <p className={HINT}>
          The rate on your statement. When it changes, enter the new one with the date it starts: earlier months keep the rate they had.
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
          <button type="submit" disabled={saving} className={BUTTON_PRIMARY}>
            {saving ? "Saving…" : "Save"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
