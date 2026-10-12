import { Percent } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import {
  type AccountDetail,
  INTEREST_DESTINATIONS,
  type InterestDestination,
  type Rate,
  useDeleteRate,
  useSaveInterest,
  useSaveRate,
} from "../../api/accounts";
import { useBuckets } from "../../api/buckets";
import { LAST_DAY } from "../../api/income";
import { addDays, formatDate, ordinal, todayIso } from "../../lib/dates";
import { useDefaultDate } from "../../api/settings";
import { formatRate, parseRate, rateToInput } from "../../lib/rates";
import { Card } from "../Card";
import { DaySelect } from "../DaySelect";
import { EditButton } from "../EditButton";
import { FormSection } from "../FormSection";
import { Modal } from "../Modal";
import { Picker } from "../Picker";
import { DefaultDateHint } from "../settings/DefaultDateHint";
import { BUTTON_LINK, BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

const DESTINATION_LABELS: Record<InterestDestination, string> = {
  unassigned: "Not into a bucket",
  bucket: "All into one bucket",
  proportional: "Shared by what each bucket holds",
};

const RATE_HELP = "Please enter a rate like 4.10.";

/** The rate in force today: the newest one that has already started. Rates arrive newest first. */
function currentRate(rates: readonly Rate[]): Rate | undefined {
  return rates.find((rate) => rate.effectiveDate <= todayIso());
}

/**
 * Savings interest for one checking or savings account (design/account-detail.png): what it
 * earns, when it is credited and where it goes, with one Edit for all of it. A rate that
 * starts on a new date leaves the old one in the history, ending the day before.
 */
export function InterestCard({ detail }: { detail: AccountDetail }) {
  const { account, rates, interest } = detail;
  const [editing, setEditing] = useState(false);
  const remove = useDeleteRate(account.id);
  const buckets = useBuckets().data ?? [];
  const current = currentRate(rates);
  const intoBucket = buckets.find((bucket) => bucket.id === interest.destinationBucketId);

  return (
    <Card title="Interest">
      {current ? (
        <>
          <dl className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-3">
            <div>
              <dt className="text-sm text-muted">Rate</dt>
              <dd>
                <span className="font-semibold tabular-nums">{formatRate(current.rateBp)} APY</span>{" "}
                <span className="text-muted">since {formatDate(current.effectiveDate)}</span>
              </dd>
            </div>
            <div>
              <dt className="text-sm text-muted">Credited on</dt>
              <dd>{interest.creditDay === LAST_DAY ? "The last day of the month" : `The ${ordinal(interest.creditDay)} of the month`}</dd>
            </div>
            <div>
              <dt className="text-sm text-muted">Interest goes</dt>
              <dd>{interest.destination === "bucket" && intoBucket ? `Into ${intoBucket.name}` : DESTINATION_LABELS[interest.destination]}</dd>
            </div>
          </dl>
          <p className={HINT}>Each month's interest is projected as an estimate; entering the real balance replaces it.</p>
        </>
      ) : (
        <p className="text-muted">Enter this account's APY and each month's interest is projected for you, as an estimate.</p>
      )}

      <div className="mt-3 flex flex-wrap items-center gap-2">
        {current ? (
          <>
            <EditButton name="interest" onClick={() => setEditing(true)} />
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
            Add interest rate
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
          {remove.error && (
            <p role="alert" className="mt-2 text-danger">
              {remove.error.message}
            </p>
          )}
        </section>
      )}

      {editing && <InterestForm detail={detail} onClose={() => setEditing(false)} />}
    </Card>
  );
}

/** The rate, the day it is credited and where it goes, saved together. */
function InterestForm({ detail, onClose }: { detail: AccountDetail; onClose: () => void }) {
  const id = useId();
  const { account, interest, rates } = detail;
  const current = currentRate(rates);
  const saveRate = useSaveRate(account.id);
  const removeRate = useDeleteRate(account.id);
  const saveSettings = useSaveInterest(account.id);

  const [rate, setRate] = useState(current ? rateToInput(current.rateBp) : "");
  const startOn = useDefaultDate();
  const [from, setFrom] = useState(current?.effectiveDate ?? startOn.date);
  const [creditDay, setCreditDay] = useState(interest.creditDay);
  const [destination, setDestination] = useState<InterestDestination>(interest.destination);
  const [bucketId, setBucketId] = useState<string | null>(
    interest.destinationBucketId === null ? null : String(interest.destinationBucketId),
  );
  const [problem, setProblem] = useState<string>();

  const bucketOptions = (useBuckets().data ?? [])
    .filter((bucket) => bucket.accountId === account.id && !bucket.archived)
    .map((bucket) => ({ id: String(bucket.id), label: bucket.name, color: bucket.color }));
  // Without buckets there is nowhere else for interest to go.
  const choices =
    bucketOptions.length === 0 ? INTEREST_DESTINATIONS.filter((option) => option === destination || option === "unassigned") : INTEREST_DESTINATIONS;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const rateBp = parseRate(rate);
    if (rateBp === null) {
      setProblem(RATE_HELP);
      return;
    }
    if (destination === "bucket" && bucketId === null) {
      setProblem("Please choose the bucket the interest goes into.");
      return;
    }
    setProblem(undefined);
    const rateChanged = current === undefined || rateBp !== current.rateBp;
    const dateChanged = current === undefined || from !== current.effectiveDate;
    try {
      if (rateChanged || dateChanged) {
        // A new rate on a new date is a rate change: the old one stays, ending the day before.
        // The same rate on another date is a correction of when it began, so the old entry goes.
        await saveRate.mutateAsync({ rateBp, effectiveDate: from });
        if (current !== undefined && !rateChanged) {
          await removeRate.mutateAsync(current.id);
        }
      }
      await saveSettings.mutateAsync({ creditDay, destination, destinationBucketId: destination === "bucket" ? Number(bucketId) : null });
      onClose();
    } catch {
      // The failed step's own message is shown below; the form stays open to try again.
    }
  };

  const saving = saveRate.isPending || removeRate.isPending || saveSettings.isPending;
  const failure = problem ?? saveRate.error?.message ?? removeRate.error?.message ?? saveSettings.error?.message;

  return (
    <Modal title={`Edit interest — ${account.name}`} icon={Percent} tone={account.color} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <FormSection title="Rate">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <label htmlFor={`${id}-rate`} className={LABEL}>
                APY (%)
              </label>
              <input
                id={`${id}-rate`}
                value={rate}
                onChange={(event) => setRate(event.target.value)}
                inputMode="decimal"
                autoComplete="off"
                placeholder="4.10"
                required
                // The form's main figure, sized like the amount at the top of the other forms.
                className={`${INPUT} min-h-14 text-2xl font-semibold tabular-nums`}
              />
            </div>
            <div>
              <label htmlFor={`${id}-from`} className={LABEL}>
                Rate applies from
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
            When the bank changes the rate, enter the new one with the date it starts. The old rate stays in the history, ending the day
            before, and earlier months keep it.
          </p>
        </FormSection>

        <FormSection title="When and where it goes">
          <DaySelect id={`${id}-day`} label="Credited on" value={creditDay} onChange={setCreditDay} />
          <div>
            <label htmlFor={`${id}-destination`} className={LABEL}>
              Interest goes
            </label>
            <select
              id={`${id}-destination`}
              value={destination}
              onChange={(event) => setDestination(event.target.value as InterestDestination)}
              className={INPUT}
            >
              {choices.map((option) => (
                <option key={option} value={option}>
                  {DESTINATION_LABELS[option]}
                </option>
              ))}
            </select>
            {bucketOptions.length === 0 && <p className={HINT}>Add a bucket to this account to send interest into one.</p>}
          </div>
          {destination === "bucket" && (
            <Picker label="Bucket" noun="bucket" placeholder="Choose or type a bucket" options={bucketOptions} value={bucketId} onChange={setBucketId} />
          )}
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
