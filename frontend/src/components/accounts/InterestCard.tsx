import { type FormEvent, useId, useState } from "react";
import {
  type Account,
  type AccountDetail,
  INTEREST_DESTINATIONS,
  type InterestDestination,
  useDeleteRate,
  useSaveInterest,
  useSaveRate,
} from "../../api/accounts";
import { useBuckets } from "../../api/buckets";
import { formatDate, todayIso } from "../../lib/dates";
import { formatRate, parseRate } from "../../lib/rates";
import { Card } from "../Card";
import { DaySelect } from "../DaySelect";
import { Picker } from "../Picker";
import { BUTTON_LINK, BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

const DESTINATION_LABELS: Record<InterestDestination, string> = {
  unassigned: "Not into a bucket",
  bucket: "All into one bucket",
  proportional: "Shared by what each bucket holds",
};

const RATE_HELP = "Please enter a rate like 4.10.";

/**
 * Savings interest for one checking or savings account (design/account-detail.png): the APY
 * and how it has changed, the day interest is credited, and where it goes. With a rate
 * entered, each month's interest is projected as an estimate.
 */
export function InterestCard({ detail }: { detail: AccountDetail }) {
  const { account, rates } = detail;
  const current = rates.find((rate) => rate.effectiveDate <= todayIso());
  return (
    <Card title="Interest">
      <p className="text-muted">
        {current ? (
          <>
            Earning <span className="font-semibold text-ink tabular-nums">{formatRate(current.rateBp)} APY</span>. Each month's
            interest is projected as an estimate; entering the real balance replaces it.
          </>
        ) : (
          "Enter this account's APY and each month's interest is projected for you, as an estimate."
        )}
      </p>
      <div className="mt-4 grid grid-cols-1 gap-6 lg:grid-cols-2">
        <RateHistory account={account} rates={rates} />
        {/* Keyed so the form starts over from what was saved, here or by an undo. */}
        <InterestSettingsForm key={JSON.stringify(detail.interest)} detail={detail} />
      </div>
    </Card>
  );
}

function RateHistory({ account, rates }: { account: Account; rates: AccountDetail["rates"] }) {
  const id = useId();
  const save = useSaveRate(account.id);
  const remove = useDeleteRate(account.id);
  const [rate, setRate] = useState("");
  const [from, setFrom] = useState(todayIso());
  const [problem, setProblem] = useState<string>();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const rateBp = parseRate(rate);
    if (rateBp === null) {
      setProblem(RATE_HELP);
      return;
    }
    setProblem(undefined);
    save.mutate({ rateBp, effectiveDate: from }, { onSuccess: () => setRate("") });
  };

  const failure = problem ?? save.error?.message ?? remove.error?.message;

  return (
    <section aria-labelledby={`${id}-title`}>
      <h3 id={`${id}-title`} className="font-semibold">
        Rate history
      </h3>
      {rates.length === 0 ? (
        <p className="mt-1 text-muted">No rate entered yet.</p>
      ) : (
        <ul className="mt-1 divide-y divide-line">
          {rates.map((entry) => (
            <li key={entry.id} className="flex items-center justify-between gap-3">
              <span>
                <span className="font-semibold tabular-nums">{formatRate(entry.rateBp)}</span>{" "}
                <span className="text-muted">from {formatDate(entry.effectiveDate)}</span>
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
          ))}
        </ul>
      )}

      <form onSubmit={submit} className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-[1fr_1fr_auto] sm:items-end">
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
            aria-invalid={problem !== undefined}
            className={`${INPUT} tabular-nums`}
          />
        </div>
        <div>
          <label htmlFor={`${id}-from`} className={LABEL}>
            From
          </label>
          <input id={`${id}-from`} type="date" value={from} onChange={(event) => setFrom(event.target.value)} required className={INPUT} />
        </div>
        <button type="submit" disabled={save.isPending} className={BUTTON_SECONDARY}>
          {save.isPending ? "Saving…" : "Add rate"}
        </button>
      </form>
      <p className={HINT}>When the bank changes the rate, add the new one with the date it starts. Earlier months keep the old rate.</p>
      {failure && (
        <p role="alert" className="mt-2 text-danger">
          {failure}
        </p>
      )}
    </section>
  );
}

function InterestSettingsForm({ detail }: { detail: AccountDetail }) {
  const id = useId();
  const { account, interest } = detail;
  const save = useSaveInterest(account.id);
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
  const choices = bucketOptions.length === 0 ? INTEREST_DESTINATIONS.filter((option) => option === destination || option === "unassigned") : INTEREST_DESTINATIONS;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (destination === "bucket" && bucketId === null) {
      setProblem("Please choose the bucket the interest goes into.");
      return;
    }
    setProblem(undefined);
    save.mutate({ creditDay, destination, destinationBucketId: destination === "bucket" ? Number(bucketId) : null });
  };

  const failure = problem ?? save.error?.message;

  return (
    <form onSubmit={submit} aria-labelledby={`${id}-title`} className="space-y-3">
      <h3 id={`${id}-title`} className="font-semibold">
        When and where
      </h3>
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
      {failure && (
        <p role="alert" className="text-danger">
          {failure}
        </p>
      )}
      <button type="submit" disabled={save.isPending} className={BUTTON_PRIMARY}>
        {save.isPending ? "Saving…" : "Save"}
      </button>
    </form>
  );
}
