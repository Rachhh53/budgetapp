import { PiggyBank } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import type { Account } from "../../api/accounts";
import { type Bucket, useDeleteBucket, useGoalPace, useMoveMoneyInto, useSaveBucket } from "../../api/buckets";
import { usePayPeriods } from "../../api/income";
import { type Item, itemAsInput, type ItemSchedule, useItems, useSaveItem } from "../../api/items";
import { formatDate, todayIso } from "../../lib/dates";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { ColorPicker, firstUnusedColor } from "../ColorPicker";
import { DaySelect } from "../DaySelect";
import { FormSection } from "../FormSection";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { describeItemSchedule } from "../items/labels";
import { PauseItem } from "../items/PauseItem";
import { BucketHistory } from "./BucketHistory";
import { BUTTON_DANGER_OUTLINE, BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

/** What the history calls the amount a new bucket was given, when that is dated earlier than today. */
const STARTING_NOTE = "Starting amount";

/** What the history calls a move made by typing a new amount into the bucket's form. */
const ADJUSTED_NOTE = "Amount adjusted";

/** How often a bucket's own recurring amount can go in. Anything fancier is set up as a recurring item. */
type Cadence = "every_pay_period" | "monthly";

interface BucketFormProps {
  /** The account the bucket sits in. */
  account: Account;
  /** The bucket being changed; leave out to add a new one. */
  bucket?: Bucket;
  /** Every bucket, for the recently used colors. */
  buckets: readonly Bucket[];
  onClose: () => void;
}

/**
 * Add or change a bucket: money set aside inside one checking or savings account. This is the
 * one place a savings goal is set up, in three questions: what it is for, how much by when,
 * and how much goes in each time. The recurring amount belongs to the bucket: it is added,
 * changed and stopped here, not on the Recurring items screen.
 */
export function BucketForm(props: BucketFormProps) {
  const { account, bucket, onClose } = props;
  const items = useItems();
  return (
    <Modal
      title={bucket ? `Edit ${bucket.name}` : `Add a bucket to ${account.name}`}
      icon={PiggyBank}
      tone={bucket?.color}
      onClose={onClose}
    >
      {/* The form starts out from the bucket's recurring amount, so it waits for the items. If they cannot be read it opens without one. */}
      {items.isPending ? <p className="text-muted">Loading…</p> : <BucketFields {...props} items={items.data ?? []} />}
    </Modal>
  );
}

function BucketFields({ account, bucket, buckets, items, onClose }: BucketFormProps & { items: readonly Item[] }) {
  const id = useId();
  const save = useSaveBucket(bucket?.id);

  // Everything recurring that puts money in this bucket. Its own amount is the first bucket move
  // still running on a cadence this form offers; anything else is listed, and changed where it lives.
  const feeding = bucket
    ? items.filter((item) => !item.archived && (item.bucketId === bucket.id || item.saveUpBucketId === bucket.id))
    : [];
  const own = feeding.find(
    (item) =>
      item.kind === "bucket_move" &&
      item.bucketId === bucket?.id &&
      (item.schedule.frequency === "every_pay_period" || item.schedule.frequency === "monthly") &&
      (item.schedule.endDate === null || item.schedule.endDate > todayIso()),
  );
  const others = feeding.filter((item) => item !== own);

  // Newest buckets first, so the colors offered first are the ones used most recently.
  const usedColors = [...buckets].reverse().map((existing) => existing.color);
  const [name, setName] = useState(bucket?.name ?? "");
  const [color, setColor] = useState(bucket?.color ?? firstUnusedColor(usedColors));
  const [goal, setGoal] = useState(bucket?.goalCents != null ? centsToInput(bucket.goalCents) : "");
  const [goalDate, setGoalDate] = useState(bucket?.goalDate ?? "");
  // Saving towards something usually ends when it is reached; untick to keep setting money aside.
  const [stopAtGoal, setStopAtGoal] = useState(bucket?.stopAtGoal ?? true);
  const [amount, setAmount] = useState(own ? centsToInput(own.amountCents) : "");
  const [cadence, setCadence] = useState<Cadence>(own?.schedule.frequency === "monthly" ? "monthly" : "every_pay_period");
  const [monthDay, setMonthDay] = useState(own?.schedule.day1 ?? 1);
  // When the recurring amount begins. Today unless told otherwise; an earlier day builds up what it would already hold.
  const [startsOn, setStartsOn] = useState(own?.schedule.startDate ?? todayIso());
  const [starting, setStarting] = useState("");
  const [held, setHeld] = useState(bucket ? centsToInput(bucket.balanceCents) : "");
  // The day the amount typed here is true for: today, or earlier to build the balance up from then.
  const [heldOn, setHeldOn] = useState(todayIso());
  const adjust = useMoveMoneyInto();
  // Set once a backdated starting amount has gone in, so trying again after a later step failed does not add it twice.
  const [startingPosted, setStartingPosted] = useState(false);
  const addMove = useSaveItem();
  const changeMove = useSaveItem(own?.id);
  const [note, setNote] = useState(bucket?.note ?? "");
  // Archiving has its own button beside Delete; saving the form leaves it as it is.
  const archived = bucket?.archived ?? false;
  const [chosenOpenedOn, setChosenOpenedOn] = useState<string | null>(bucket?.openedOn ?? null);
  const [problem, setProblem] = useState<string>();
  // Set once a new bucket has been added, so trying again after a later step failed does not add it twice.
  const [createdId, setCreatedId] = useState<number>();

  // A new bucket counts from this paycheck's payday unless told otherwise.
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
  const hasGoal = typeof goalInMind === "number" && goalInMind > 0;
  const heldInMind = optionalCents(bucket ? held : starting);
  const pace = useGoalPace(
    hasGoal && goalDate > todayIso() && heldInMind !== "invalid"
      ? { goalCents: goalInMind, goalDate, bucketId: bucket?.id, heldCents: heldInMind ?? 0 }
      : undefined,
  ).data;
  // "More" is on top of what already goes in: on the same cadence, the amount to type is the two together.
  const alreadyEach = (wanted: Cadence) => (own && own.schedule.frequency === wanted ? own.amountCents : 0);
  const use = (wanted: Cadence, moreCents: number) => {
    setCadence(wanted);
    setAmount(centsToInput(alreadyEach(wanted) + moreCents));
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const goalCents = optionalCents(goal);
    const startingCents = optionalCents(starting);
    // An empty "In this bucket" reads as nothing in it.
    const heldCents = bucket ? (optionalCents(held) ?? 0) : null;
    const moveCents = optionalCents(amount);
    if (goalCents === "invalid" || startingCents === "invalid" || heldCents === "invalid" || moveCents === "invalid") {
      setProblem(AMOUNT_HELP);
      return;
    }
    setProblem(undefined);
    const goalSet = goalCents !== null && goalCents > 0;
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
          // A date, and stopping at the goal, only mean something beside a goal.
          goalDate: goalSet && goalDate !== "" ? goalDate : null,
          stopAtGoal: goalSet && stopAtGoal,
          // Dated today it goes in with the bucket; an earlier day is recorded as its own dated amount below.
          ...(bucket ? {} : { startingCents: heldOn === todayIso() ? (startingCents ?? 0) : 0 }),
        });
        bucketId = saved.bucket.id;
        if (!bucket) {
          setCreatedId(bucketId);
        }
      }
      // A bucket's amount is the sum of what moved in and out, so a new amount is recorded
      // as one more move, for the difference: the history keeps how it got there.
      if (bucket && heldCents !== null && !archived && heldCents !== bucket.balanceCents) {
        await adjust.mutateAsync({ bucketId: bucket.id, date: heldOn, amountCents: heldCents - bucket.balanceCents, note: ADJUSTED_NOTE });
      }
      if (!bucket && heldOn !== todayIso() && startingCents !== null && startingCents > 0 && !startingPosted) {
        await adjust.mutateAsync({ bucketId, date: heldOn, amountCents: startingCents, note: STARTING_NOTE });
        setStartingPosted(true);
      }
      const wanted = moveCents !== null && moveCents > 0;
      const timing: Pick<ItemSchedule, "frequency" | "interval" | "day1" | "day2" | "month"> = {
        frequency: cadence,
        interval: null,
        day1: cadence === "monthly" ? monthDay : null,
        day2: null,
        month: null,
      };
      if (own && !wanted) {
        // Emptied: the recurring amount stops here. What it already put in stays in the bucket.
        await changeMove.mutateAsync({
          ...itemAsInput(own),
          schedule: { ...own.schedule, endDate: todayIso() },
          effectiveFrom: todayIso(),
        });
      } else if (own && wanted) {
        const same =
          own.amountCents === moveCents &&
          own.schedule.frequency === cadence &&
          (cadence !== "monthly" || own.schedule.day1 === monthDay) &&
          own.schedule.startDate === startsOn;
        if (!same) {
          await changeMove.mutateAsync({
            ...itemAsInput(own),
            amountCents: moveCents,
            amountMode: "per_occurrence",
            // A different start reaches back (or forward) to that day; the backend handles the history.
            schedule: { ...own.schedule, ...timing, startDate: startsOn },
            effectiveFrom: todayIso(),
          });
        }
      } else if (wanted && !archived) {
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
          // From the day chosen: today counts for the paycheck in hand, and an earlier day fills in
          // what it would have set aside since (the bucket then counts from that day too).
          schedule: { ...timing, startDate: startsOn, endDate: null, weekendRule: "none" },
          daysAfterPayday: 0,
          bucketId,
          saveUpBucketId: null,
          fromBucketId: null,
        });
      }
      onClose();
    } catch {
      // The failed step's own message is shown below; the form stays open to try again.
    }
  };

  const saving = save.isPending || adjust.isPending || addMove.isPending || changeMove.isPending;
  const failure =
    problem ?? save.error?.message ?? adjust.error?.message ?? addMove.error?.message ?? changeMove.error?.message;

  return (
    <>
      <form onSubmit={submit} className="space-y-4">
        <FormSection title="What are you saving for?">
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
        </FormSection>

        <FormSection title="How much, by when?">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <MoneyField label="Goal (optional)" value={goal} onChange={setGoal} />
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
            </div>
          </div>
          <p className={HINT}>With both, the app works out how much to set aside each time.</p>
        </FormSection>

        {!archived && (
          <FormSection title="How much each time?">
            {/* The answer to "how much do I need to save?", as soon as there is a goal and a date. */}
            {pace && hasGoal && (
              <div role="status" className="rounded-lg border-l-4 border-accent bg-surface px-4 py-3">
                {pace.shortCents === 0 ? (
                  <p>
                    <span className="font-semibold">On track.</span> What it holds and what is already going in reach{" "}
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
                      a month{feeding.length > 0 ? ", on top of what already goes in" : ""}.
                    </p>
                    <div className="mt-2 flex flex-col gap-2 sm:flex-row">
                      {pace.morePerPaycheckCents !== null && (
                        <button type="button" onClick={() => use("every_pay_period", pace.morePerPaycheckCents ?? 0)} className={BUTTON_SECONDARY}>
                          Use <span data-amount>{formatCents(alreadyEach("every_pay_period") + pace.morePerPaycheckCents)}</span> every paycheck
                        </button>
                      )}
                      {pace.morePerMonthCents !== null && (
                        <button type="button" onClick={() => use("monthly", pace.morePerMonthCents ?? 0)} className={BUTTON_SECONDARY}>
                          Use <span data-amount>{formatCents(alreadyEach("monthly") + pace.morePerMonthCents)}</span> a month
                        </button>
                      )}
                    </div>
                  </>
                )}
              </div>
            )}

            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <MoneyField label="Set aside (optional)" value={amount} onChange={setAmount} />
              <div>
                <label htmlFor={`${id}-cadence`} className={LABEL}>
                  How often
                </label>
                <select id={`${id}-cadence`} value={cadence} onChange={(event) => setCadence(event.target.value as Cadence)} className={INPUT}>
                  <option value="every_pay_period">Every paycheck</option>
                  <option value="monthly">Every month</option>
                </select>
              </div>
              {cadence === "monthly" && <DaySelect id={`${id}-day`} label="Day of the month" value={monthDay} onChange={setMonthDay} />}
              <div>
                <label htmlFor={`${id}-starts`} className={LABEL}>
                  Starting
                </label>
                <input
                  id={`${id}-starts`}
                  type="date"
                  value={startsOn}
                  onChange={(event) => setStartsOn(event.target.value)}
                  required={amount.trim() !== ""}
                  className={INPUT}
                />
              </div>
            </div>
            <p className={HINT}>
              {own
                ? "Change the amount to save more or less from now on. Empty it to stop."
                : "The money stays in this account; it is set aside, so less shows as available."}{" "}
              Choose an earlier starting day to build up what it would already have set aside since then.
            </p>

            {hasGoal && (
              <label className="flex min-h-11 items-center gap-3">
                <input
                  type="checkbox"
                  checked={stopAtGoal}
                  onChange={(event) => setStopAtGoal(event.target.checked)}
                  className="size-5 accent-(--accent)"
                />
                <span>Stop when the goal is reached</span>
              </label>
            )}

            {others.length > 0 && (
              <div>
                <p className={LABEL}>Also going into this bucket</p>
                <ul aria-label="Also going into this bucket" className="text-muted">
                  {others.map((item) => (
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
                <p className={HINT}>These are changed under Recurring items.</p>
              </div>
            )}
          </FormSection>
        )}
        {/* The bucket's own recurring amount can be stopped for a while without ending it. */}
        {!archived && own && <PauseItem item={own} />}

        {/* Everything a savings goal does not need to ask, kept out of the way until wanted. */}
        <details className="rounded-lg border border-line px-4 py-1">
          <summary className="min-h-11 cursor-pointer content-center font-medium">More options</summary>
          <div className="space-y-4 pb-3">
            {!bucket && (
              <MoneyField
                label="Already set aside (optional)"
                value={starting}
                onChange={setStarting}
                hint="What this bucket already holds."
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
            {!bucket?.archived && (
              <div>
                <label htmlFor={`${id}-held-on`} className={LABEL}>
                  As of
                </label>
                <input
                  id={`${id}-held-on`}
                  type="date"
                  value={heldOn}
                  onChange={(event) => setHeldOn(event.target.value)}
                  required
                  className={INPUT}
                />
                <p className={HINT}>
                  The day that amount {bucket ? "changes" : "went in"}. Today unless you choose an earlier day, to build the balance up from then.
                </p>
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
                Recurring amounts, save-ups and interest reach this bucket from this day on. Choose an earlier day to bring in ones
                dated before it.
              </p>
            </div>

            <div>
              <label htmlFor={`${id}-note`} className={LABEL}>
                Note (optional)
              </label>
              <input id={`${id}-note`} value={note} onChange={(event) => setNote(event.target.value)} maxLength={200} autoComplete="off" className={INPUT} />
            </div>

          </div>
        </details>

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
      {bucket && <ArchiveOrDelete bucket={bucket} onDone={onClose} />}
    </>
  );
}

/**
 * The two ways to put a bucket away, side by side. Archiving keeps it and its history, and can
 * be undone here; deleting removes it. No "are you sure?" for either (spec §6): Undo brings it back.
 */
function ArchiveOrDelete({ bucket, onDone }: { bucket: Bucket; onDone: () => void }) {
  const remove = useDeleteBucket(bucket.id);
  const save = useSaveBucket(bucket.id);
  const busy = remove.isPending || save.isPending;
  const failure = remove.error?.message ?? save.error?.message;
  return (
    <div className="mt-6 border-t border-line pt-4">
      <div className="flex flex-col gap-2 sm:flex-row">
        <button
          type="button"
          onClick={() =>
            save.mutate(
              {
                accountId: bucket.accountId,
                name: bucket.name,
                color: bucket.color,
                goalCents: bucket.goalCents,
                note: bucket.note,
                archived: !bucket.archived,
                openedOn: bucket.openedOn,
                goalDate: bucket.goalDate,
              },
              { onSuccess: onDone },
            )
          }
          disabled={busy}
          className={BUTTON_SECONDARY}
        >
          {save.isPending ? "Saving…" : bucket.archived ? "Bring this bucket back" : "Archive this bucket"}
        </button>
        <button type="button" onClick={() => remove.mutate(undefined, { onSuccess: onDone })} disabled={busy} className={BUTTON_DANGER_OUTLINE}>
          {remove.isPending ? "Deleting…" : "Delete this bucket"}
        </button>
      </div>
      <p className={HINT}>
        {bucket.archived
          ? "Archived: it sets nothing aside. Bring it back to use it again, or delete it for good."
          : "Archiving keeps the bucket and its history but stops it setting anything aside. Deleting removes it."}{" "}
        Either way the money stays in the account.
      </p>
      {failure && (
        <p role="alert" className="mt-2 text-danger">
          {failure}
        </p>
      )}
    </div>
  );
}
