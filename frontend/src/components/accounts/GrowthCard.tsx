import { TrendingUp } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import { type AccountDetail, type Rate, useDeleteRate, useSaveRate } from "../../api/accounts";
import { addDays, formatDate, todayIso } from "../../lib/dates";
import { useDefaultDate } from "../../api/settings";
import { formatRate, parseRate, rateToInput } from "../../lib/rates";
import { Card } from "../Card";
import { EditButton } from "../EditButton";
import { FormSection } from "../FormSection";
import { Modal } from "../Modal";
import { DefaultDateHint } from "../settings/DefaultDateHint";
import { BUTTON_LINK, BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

const RATE_HELP = "Please enter a rate like 6.00.";

/** The rate in force today: the newest one that has already started. Rates arrive newest first. */
function currentRate(rates: readonly Rate[]): Rate | undefined {
  return rates.find((rate) => rate.effectiveDate <= todayIso());
}

/**
 * The growth an investment account is assumed to make (spec §5.5). It is the user's own guess,
 * used only to project the balance forward, and every sentence here says so: nothing about it
 * is a prediction, and what the account really gained comes from the balances entered.
 */
export function GrowthCard({ detail }: { detail: AccountDetail }) {
  const { account, rates } = detail;
  const [editing, setEditing] = useState(false);
  const remove = useDeleteRate(account.id);
  const current = currentRate(rates);

  return (
    <Card title="Assumed growth">
      {current ? (
        <>
          <p>
            <span className="text-2xl font-bold tabular-nums">{formatRate(current.rateBp)}</span>{" "}
            <span className="text-muted">a year, since {formatDate(current.effectiveDate)}</span>
          </p>
          <p className={HINT}>
            An assumption you chose, not a prediction. It is added at the end of each month to project the balance forward; entering the
            real balance replaces it. Gains and losses are only ever worked out from balances you entered.
          </p>
        </>
      ) : (
        <p className="text-muted">
          No growth is assumed, so the projection only adds what is put in. Add a yearly rate to see where the balance might go; it will
          always be labelled as your assumption.
        </p>
      )}

      <div className="mt-3 flex flex-wrap items-center gap-2">
        {current ? (
          <>
            <EditButton name="assumed growth" onClick={() => setEditing(true)} />
            {/* With a single rate there is no history list to remove it from. */}
            {rates.length === 1 && (
              <button
                type="button"
                disabled={remove.isPending}
                onClick={() => remove.mutate(current.id)}
                aria-label={`Remove the rate from ${formatDate(current.effectiveDate)}`}
                className={`${BUTTON_LINK} text-muted`}
              >
                Remove rate
              </button>
            )}
          </>
        ) : (
          <button type="button" onClick={() => setEditing(true)} className={BUTTON_SECONDARY}>
            Add an assumed rate
          </button>
        )}
      </div>

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

      {editing && <GrowthForm detail={detail} onClose={() => setEditing(false)} />}
    </Card>
  );
}

/** The assumed rate and the date it applies from. */
function GrowthForm({ detail, onClose }: { detail: AccountDetail; onClose: () => void }) {
  const id = useId();
  const { account, rates } = detail;
  const current = currentRate(rates);
  const saveRate = useSaveRate(account.id);
  const removeRate = useDeleteRate(account.id);
  const [rate, setRate] = useState(current ? rateToInput(current.rateBp) : "");
  const startOn = useDefaultDate();
  const [from, setFrom] = useState(current?.effectiveDate ?? startOn.date);
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
        // A new rate on a new date is a change of mind from then on: the old one stays, ending the
        // day before. The same rate on another date corrects when it began, so the old entry goes.
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
    <Modal title={`Assumed growth — ${account.name}`} icon={TrendingUp} tone={account.color} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <FormSection title="Rate">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <label htmlFor={`${id}-rate`} className={LABEL}>
                Growth a year (%)
              </label>
              <input
                id={`${id}-rate`}
                value={rate}
                onChange={(event) => setRate(event.target.value)}
                inputMode="decimal"
                autoComplete="off"
                placeholder="6.00"
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
              {!current && <DefaultDateHint value={from} />}
            </div>
          </div>
          <p className={HINT}>
            Your own guess at what this account grows by in a year. Nobody can know it, so keep it modest; you can change it at any
            time, and earlier months keep the rate they had.
          </p>
        </FormSection>

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
