import { PiggyBank } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import type { Account } from "../../api/accounts";
import { type Bucket, useDeleteBucket, useGoalPace, useMoveBucketMoney, useSaveBucket } from "../../api/buckets";
import { usePayPeriods } from "../../api/income";
import { useItems, useSaveItem } from "../../api/items";
import { addDays, formatDate, todayIso } from "../../lib/dates";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { ColorPicker, firstUnusedColor } from "../ColorPicker";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { describeItemSchedule } from "../items/labels";
import { BucketHistory } from "./BucketHistory";
import { BUTTON_DANGER_OUTLINE, BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

/** What the history calls a move made by typing a new amount into the bucket's form. */
const ADJUSTED_NOTE = "Amount adjusted";

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
  const [goalDate, setGoalDate] = useState(bucket?.goalDate ?? "");
  const [starting, setStarting] = useState("");
  const [held, setHeld] = useState(bucket ? centsToInput(bucket.balanceCents) : "");
  // Only used for a bucket that exists; a new one takes its first amount as it is added.
  const adjust = useMoveBucketMoney(bucket?.id ?? 0);
  const [note, setNote] = useState(bucket?.note ?? "");
  const [archived, setArchived] = useState(bucket?.archived ?? false);
  const [chosenOpenedOn, setChosenOpenedOn] = useState<string | null>(bucket?.openedOn ?? null);
  const [problem, setProblem] = useState<string>();
  // A recurring move into the bucket, set up from here so it need not be found on another screen.
  const [everyPaycheck, setEveryPaycheck] = useState("");
  // Saving towards a date usually ends on it; untick to keep setting money aside afterwards.
  const [stopAtGoal, setStopAtGoal] = useState(true);
  const addMove = useSaveItem();
  // Set once a new bucket has been added, so trying again after a later step failed does not add it twice.
  const [createdId, setCreatedId] = useState<number>();
  const everyItem = useItems().data ?? [];
  const feeding = bucket
    ? everyItem.filter((item) => !item.archived && (item.bucketId === bucket.id || item.saveUpBucketId === bucket.id))
    : [];

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

  // What the goal being typed would take, so the form can say how much to set aside before anything is saved.
  const goalInMind = optionalCents(goal);
  const heldInMind = optionalCents(bucket ? held : starting);
  const pace = useGoalPace(
    typeof goalInMind === "number" && goalInMind > 0 && goalDate > todayIso() && heldInMind !== "invalid"
      ? { goalCents: goalInMind, goalDate, bucketId: bucket?.id, heldCents: heldInMind ?? 0 }
      : undefined,
  ).data;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const goalCents = optionalCents(goal);
    const startingCents = optionalCents(starting);
    // An empty "In this bucket" reads as nothing in it.
    const heldCents = bucket ? (optionalCents(held) ?? 0) : null;
    const moveCents = optionalCents(everyPaycheck);
    if (goalCents === "invalid" || startingCents === "invalid" || heldCents === "invalid" || moveCents === "invalid") {
      setProblem(AMOUNT_HELP);
      return;
    }
    setProblem(undefined);
    try {
      let bucketId = bucket?.id ?? createdId;
      if (bucketId === undefined || bucket) {
        const saved = await save.mutateAsync({
          accountId: account.id,
          name,
          color,
          goalCents,
          note: note.trim() === "" ? null : note,
          archived,
          openedOn,
          // A date only means something beside a goal.
          goalDate: goalCents !== null && goalCents > 0 && goalDate !== "" ? goalDate : null,
          ...(bucket ? {} : { startingCents: startingCents ?? 0 }),
        });
        bucketId = saved.bucket.id;
        if (!bucket) {
          setCreatedId(bucketId);
        }
      }
      // A bucket's amount is the sum of what moved in and out, so a new amount is recorded
      // as one more move, for the difference: the history keeps how it got there.
      if (bucket && heldCents !== null && !archived && heldCents !== bucket.balanceCents) {
        await adjust.mutateAsync({ date: todayIso(), amountCents: heldCents - bucket.balanceCents, note: ADJUSTED_NOTE });
      }
      if (moveCents !== null && moveCents > 0 && !archived) {
        await addMove.mutateAsync({
          name,
          kind: "bucket_move",
          note: null,
          archived: false,
          tagIds: [],
          amountCents: moveCents,
          amountMode: "per_occurrence",
          estimate: false,
          categoryId: null,
          payFromAccountId: account.id,
          payToAccountId: null,
          autopay: true,
          placement: "due_period",
          // From the next paycheck: the ones a goal's amount is shared over do not include the one in hand.
          // It ends on the goal date if asked to, and otherwise runs on.
          schedule: {
            frequency: "every_pay_period",
            interval: null,
            day1: null,
            day2: null,
            month: null,
            startDate: addDays(todayIso(), 1),
            endDate: stopAtGoal && canStopAtGoal ? goalDate : null,
            weekendRule: "none",
          },
          daysAfterPayday: 0,
          bucketId,
          saveUpBucketId: null,
        });
      }
      onClose();
    } catch {
      // The failed step's own message is shown below; the form stays open to try again.
    }
  };

  // Only a goal with a date still ahead gives the move somewhere to stop.
  const canStopAtGoal = goal.trim() !== "" && goalDate > todayIso();
  const saving = save.isPending || adjust.isPending || addMove.isPending;
  const failure = problem ?? save.error?.message ?? adjust.error?.message ?? addMove.error?.message;

  return (
    <Modal
      title={bucket ? `Edit ${bucket.name}` : `Add a bucket to ${account.name}`}
      icon={PiggyBank}
      tone={bucket?.color}
      onClose={onClose}
    >
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
          <div>
            <label htmlFor={`${id}-goal-date`} className={LABEL}>
              Goal date (optional)
            </label>
            <input
              id={`${id}-goal-date`}
              type="date"
              value={goalDate}
              onChange={(event) => setGoalDate(event.target.value)}
              className={INPUT}
            />
            <p className={HINT}>
              With a goal and a date, the bucket shows whether you are saving enough to get there, and how much more each paycheck or
              month it would take.
            </p>
          </div>
          {!bucket && (
            <MoneyField
              label="Already set aside (optional)"
              value={starting}
              onChange={setStarting}
              hint="What this bucket holds today."
            />
          )}
          {bucket && !bucket.archived && (
            <MoneyField
              label="In this bucket"
              value={held}
              onChange={setHeld}
              hint="What it holds today. Change it to set a new amount; the difference is recorded as an adjustment."
            />
          )}
        </div>

        {/* The answer to "how much do I need to save?", as soon as there is a goal and a date. */}
        {pace && typeof goalInMind === "number" && !archived && (
          <div role="status" className="rounded-lg border-l-4 border-accent bg-bg px-4 py-3">
            {pace.shortCents === 0 ? (
              <p>
                <span className="font-semibold">On track.</span> What it holds and what is already scheduled reach{" "}
                <span data-amount>{formatCents(goalInMind)}</span> by {formatDate(goalDate)}.
              </p>
            ) : (
              <>
                <p>
                  To have <span data-amount>{formatCents(goalInMind)}</span> by {formatDate(goalDate)}, set aside{" "}
                  {pace.morePerPaycheckCents !== null && (
                    <>
                      <span data-amount className="font-semibold tabular-nums">
                        {formatCents(pace.morePerPaycheckCents)}
                      </span>{" "}
                      every paycheck ({pace.paydaysLeft} {pace.paydaysLeft === 1 ? "paycheck" : "paychecks"} left), or{" "}
                    </>
                  )}
                  <span data-amount className="font-semibold tabular-nums">
                    {formatCents(pace.morePerMonthCents ?? pace.shortCents)}
                  </span>{" "}
                  a month{feeding.length > 0 ? ", on top of what is already scheduled" : ""}.
                </p>
                {pace.morePerPaycheckCents !== null && (
                  <button
                    type="button"
                    onClick={() => setEveryPaycheck(centsToInput(pace.morePerPaycheckCents ?? 0))}
                    className={`${BUTTON_SECONDARY} mt-2`}
                  >
                    Set aside <span data-amount>{formatCents(pace.morePerPaycheckCents)}</span> every paycheck
                  </button>
                )}
              </>
            )}
          </div>
        )}

        {!archived && (
          <div>
            {feeding.length > 0 && (
              <>
                <p className={LABEL}>Recurring into this bucket</p>
                <ul aria-label="Recurring into this bucket" className="mb-3 text-muted">
                  {feeding.map((item) => (
                    <li key={item.id}>
                      {item.name} ·{" "}
                      {item.saveUpBucketId === bucket?.id ? (
                        "saved up for every paycheck"
                      ) : (
                        <>
                          <span data-amount className="tabular-nums">
                            {formatCents(item.perOccurrenceCents)}
                          </span>{" "}
                          {describeItemSchedule(item.schedule).toLowerCase()}
                        </>
                      )}
                    </li>
                  ))}
                </ul>
              </>
            )}
            <MoneyField
              label={feeding.length > 0 ? "Set aside more every paycheck (optional)" : "Set aside every paycheck (optional)"}
              value={everyPaycheck}
              onChange={setEveryPaycheck}
              hint="Adds a recurring bucket move, starting with your next paycheck. Change or stop it under Recurring items."
            />
            {canStopAtGoal && everyPaycheck.trim() !== "" && (
              <label className="mt-1 flex min-h-11 items-center gap-3">
                <input
                  type="checkbox"
                  checked={stopAtGoal}
                  onChange={(event) => setStopAtGoal(event.target.checked)}
                  className="size-5 accent-(--accent)"
                />
                <span>Stop on the goal date ({formatDate(goalDate)})</span>
              </label>
            )}
          </div>
        )}

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
          <button type="submit" disabled={saving} className={BUTTON_PRIMARY}>
            {saving ? "Saving…" : bucket ? "Save changes" : "Add bucket"}
          </button>
        </div>
      </form>

      {bucket && <BucketHistory bucket={bucket} />}
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
