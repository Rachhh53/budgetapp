import { type FormEvent, useId, useState } from "react";
import type { Account } from "../../api/accounts";
import { type Bucket, useDeleteBucket, useSaveBucket } from "../../api/buckets";
import { usePayPeriods } from "../../api/income";
import { todayIso } from "../../lib/dates";
import { centsToInput, parseDollars } from "../../lib/money";
import { ColorPicker, firstUnusedColor } from "../ColorPicker";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { BUTTON_DANGER_OUTLINE, BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

interface BucketFormProps {
  /** The account the bucket sits in. */
  account: Account;
  /** The bucket being changed; leave out to add a new one. */
  bucket?: Bucket;
  /** Every bucket, for the recently used colors. */
  buckets: readonly Bucket[];
  onClose: () => void;
}

/** Add or change a bucket: money set aside inside one checking or savings account. */
export function BucketForm({ account, bucket, buckets, onClose }: BucketFormProps) {
  const id = useId();
  const save = useSaveBucket(bucket?.id);

  // Newest buckets first, so the colors offered first are the ones used most recently.
  const usedColors = [...buckets].reverse().map((existing) => existing.color);
  const [name, setName] = useState(bucket?.name ?? "");
  const [color, setColor] = useState(bucket?.color ?? firstUnusedColor(usedColors));
  const [goal, setGoal] = useState(bucket?.goalCents != null ? centsToInput(bucket.goalCents) : "");
  const [starting, setStarting] = useState("");
  const [note, setNote] = useState(bucket?.note ?? "");
  const [archived, setArchived] = useState(bucket?.archived ?? false);
  const [chosenOpenedOn, setChosenOpenedOn] = useState<string | null>(bucket?.openedOn ?? null);
  const [problem, setProblem] = useState<string>();

  // A new bucket counts from this paycheck's payday unless told otherwise, so a move set up
  // for "every paycheck" includes the paycheck in hand instead of silently waiting for the next.
  const currentPeriod = usePayPeriods().data?.periods.find((period) => period.current);
  const openedOn = chosenOpenedOn ?? currentPeriod?.start ?? todayIso();

  /** An optional amount: empty is "none", anything else has to read as zero or more. */
  const optionalCents = (text: string): number | null | "invalid" => {
    if (text.trim() === "") {
      return null;
    }
    const cents = parseDollars(text);
    return cents === null || cents < 0 ? "invalid" : cents;
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const goalCents = optionalCents(goal);
    const startingCents = optionalCents(starting);
    if (goalCents === "invalid" || startingCents === "invalid") {
      setProblem(AMOUNT_HELP);
      return;
    }
    setProblem(undefined);
    save.mutate(
      {
        accountId: account.id,
        name,
        color,
        goalCents,
        note: note.trim() === "" ? null : note,
        archived,
        openedOn,
        ...(bucket ? {} : { startingCents: startingCents ?? 0 }),
      },
      { onSuccess: onClose },
    );
  };

  const failure = problem ?? save.error?.message;

  return (
    <Modal title={bucket ? `Edit ${bucket.name}` : `Add a bucket to ${account.name}`} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <div>
          <label htmlFor={`${id}-name`} className={LABEL}>
            Name
          </label>
          <input
            id={`${id}-name`}
            value={name}
            onChange={(event) => setName(event.target.value)}
            maxLength={60}
            required
            autoComplete="off"
            placeholder="e.g. Holiday gifts"
            className={INPUT}
          />
        </div>

        <ColorPicker label="Color" value={color} onChange={setColor} recent={usedColors} />

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <MoneyField label="Goal (optional)" value={goal} onChange={setGoal} hint="What you are working towards, if anything." />
          {!bucket && (
            <MoneyField
              label="Already set aside (optional)"
              value={starting}
              onChange={setStarting}
              hint="What this bucket holds today."
            />
          )}
        </div>

        <div>
          <label htmlFor={`${id}-opened`} className={LABEL}>
            Counts from
          </label>
          <input
            id={`${id}-opened`}
            type="date"
            value={openedOn}
            max={todayIso()}
            onChange={(event) => setChosenOpenedOn(event.target.value)}
            required
            className={INPUT}
          />
          <p className={HINT}>
            Recurring bucket moves, save-ups and interest reach this bucket from this day on. Choose an earlier day to bring in ones
            dated before it.
          </p>
        </div>

        <div>
          <label htmlFor={`${id}-note`} className={LABEL}>
            Note (optional)
          </label>
          <input id={`${id}-note`} value={note} onChange={(event) => setNote(event.target.value)} maxLength={200} autoComplete="off" className={INPUT} />
        </div>

        {bucket && (
          <div>
            <label className="flex min-h-11 items-center gap-3">
              <input
                type="checkbox"
                checked={archived}
                onChange={(event) => setArchived(event.target.checked)}
                className="size-5 accent-(--accent)"
              />
              <span className="font-medium">Archived</span>
            </label>
            <p className={HINT}>An archived bucket no longer sets anything aside: what it held counts as available again.</p>
          </div>
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
            {save.isPending ? "Saving…" : bucket ? "Save changes" : "Add bucket"}
          </button>
        </div>
      </form>

      {bucket && <DeleteBucket bucket={bucket} onDeleted={onClose} />}
    </Modal>
  );
}

/** No "are you sure?" for a bucket (spec §6): it is deleted, and Undo brings it back. */
function DeleteBucket({ bucket, onDeleted }: { bucket: Bucket; onDeleted: () => void }) {
  const remove = useDeleteBucket(bucket.id);
  return (
    <div className="mt-6 border-t border-line pt-4">
      <button
        type="button"
        onClick={() => remove.mutate(undefined, { onSuccess: onDeleted })}
        disabled={remove.isPending}
        className={BUTTON_DANGER_OUTLINE}
      >
        {remove.isPending ? "Deleting…" : "Delete this bucket"}
      </button>
      <p className={HINT}>The money stays in the account; it just stops being set aside.</p>
      {remove.error && (
        <p role="alert" className="mt-2 text-danger">
          {remove.error.message}
        </p>
      )}
    </div>
  );
}
