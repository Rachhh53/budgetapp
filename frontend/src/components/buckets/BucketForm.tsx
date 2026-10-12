import { PiggyBank } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import type { Account } from "../../api/accounts";
import { type Bucket, useDeleteBucket, useGoalPace, useMoveMoneyInto, useSaveBucket } from "../../api/buckets";
import { usePayPeriods } from "../../api/income";
import { type Item, itemAsInput, type ItemSchedule, useItems, useSaveItem } from "../../api/items";
import { formatDate, todayIso } from "../../lib/dates";
import { useDefaultDate } from "../../api/settings";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { ColorPicker, firstUnusedColor } from "../ColorPicker";
import { DaySelect } from "../DaySelect";
import { type FormGroup, type PartProblem, useFormParts } from "../FormParts";
import { FormSection } from "../FormSection";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { PauseItem } from "../items/PauseItem";
import { BucketHistory } from "./BucketHistory";
import { DefaultDateHint } from "../settings/DefaultDateHint";
import { BUTTON_DANGER_OUTLINE, BUTTON_LINK, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

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
  /** Opened from the bucket's recurring amount (on the Recurring items screen): start on how much goes in each time. */
  forAmount?: boolean;
  onClose: () => void;
}

/** The parts of the form. Each is a screen of its own: on a phone none of them leaves room for another. */
type BucketPart = "what" | "goal" | "each" | "held" | "dates" | "history" | "pause" | "away";

/** When adding, the first three are the questions everyone is asked; the rest wait behind "More options". */
const QUESTIONS = 3;

/**
 * Add or change a bucket: money set aside inside one checking or savings account. This is the
 * one place a savings goal is set up, in three questions: what it is for, how much by when,
 * and how much goes in each time. The recurring amount belongs to the bucket: it is added,
 * changed and stopped here, not on the Recurring items screen.
 *
 * The form is shown a screen at a time (see `useFormParts`), so it never needs scrolling.
 * Whatever else puts money in the bucket is listed on its row of the Buckets card, not here.
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

function BucketFields({ account, bucket, buckets, items, forAmount, onClose }: BucketFormProps & { items: readonly Item[] }) {
  const id = useId();
  const save = useSaveBucket(bucket?.id);
  // Archiving has its own button beside Delete; saving the form leaves it as it is.
  const archived = bucket?.archived ?? false;

  // Everything recurring that puts money in this bucket. Its own amount is the first bucket move
  // still running on a cadence this form offers; anything else is changed where it lives.
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
  const startOn = useDefaultDate();
  // When the recurring amount begins: the day new things start on (today, unless Settings says another).
  // An earlier day builds up what it would already hold.
  const [startsOn, setStartsOn] = useState(own?.schedule.startDate ?? startOn.date);
  const [starting, setStarting] = useState("");
  const [held, setHeld] = useState(bucket ? centsToInput(bucket.balanceCents) : "");
  // The day the amount typed here is true for: today, or earlier to build the balance up from then.
  // A new bucket's first amount follows the day new things start on; a change to one that exists is made today.
  const [heldOn, setHeldOn] = useState(bucket ? todayIso() : startOn.date);
  const adjust = useMoveMoneyInto();
  // Set once a backdated starting amount has gone in, so trying again after a later step failed does not add it twice.
  const [startingPosted, setStartingPosted] = useState(false);
  const addMove = useSaveItem();
  const changeMove = useSaveItem(own?.id);
  const [note, setNote] = useState(bucket?.note ?? "");
  const [chosenOpenedOn, setChosenOpenedOn] = useState<string | null>(bucket?.openedOn ?? null);

  // An archived bucket sets nothing aside, so it is not asked how much or what it holds; only a running amount can be paused.
  const groups: FormGroup<BucketPart>[] = [
    { label: "Name, color and note", parts: ["what"] },
    { label: "Goal", parts: ["goal"] },
    ...(archived ? [] : ([{ label: "How much each time", parts: ["each"] }, { label: "What it holds", parts: ["held"] }] as const)),
    { label: "Dates", parts: ["dates"] },
    ...(bucket ? [{ label: "Changed by hand", parts: ["history"] } as const] : []),
    ...(bucket && !archived && own ? [{ label: "Pause", parts: ["pause"] } as const] : []),
    ...(bucket ? [{ label: archived ? "Bring back or delete" : "Archive or delete", parts: ["away"] } as const] : []),
  ];
  const parts = useFormParts<BucketPart>({ groups, adding: !bucket, required: QUESTIONS, startAt: forAmount ? "each" : undefined });
  // Set once a new bucket has been added, so trying again after a later step failed does not add it twice.
  const [createdId, setCreatedId] = useState<number>();

  // A new bucket counts from this paycheck's payday unless told otherwise.
  const currentPeriod = usePayPeriods().data?.periods.find((period) => period.current);
  // The day chosen in Settings comes first: entering a year from its start, the bucket should count from then.
  const openedOn = chosenOpenedOn ?? (startOn.fromSetting ? startOn.date : (currentPeriod?.start ?? todayIso()));

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
  // What the goal takes is on top of anything already going in.
  const more = feeding.length > 0 ? "more " : "";

  /** The first thing that cannot be saved as it is, and the screen it is on. */
  const check = (): PartProblem<BucketPart> | null => {
    const wrong = (
      [
        ["goal", goal],
        ["each", archived ? "" : amount],
        ["held", bucket ? held : starting],
      ] as const
    ).find(([, text]) => optionalCents(text) === "invalid");
    if (wrong) {
      return { part: wrong[0], problem: AMOUNT_HELP };
    }
    // The dates start out filled in, and are on screens of their own, so the browser cannot ask for them.
    if (!archived && heldOn === "") {
      return { part: "held", problem: "Please choose the day that amount is as of." };
    }
    if (!archived && amount.trim() !== "" && startsOn === "") {
      return { part: "dates", problem: "Please choose the day setting aside starts." };
    }
    if (openedOn === "") {
      return { part: "dates", problem: "Please choose the day this bucket counts from." };
    }
    return null;
  };

  const send = async () => {
    const goalCents = optionalCents(goal);
    const startingCents = optionalCents(starting);
    // An empty "In this bucket" reads as nothing in it.
    const heldCents = bucket ? (optionalCents(held) ?? 0) : null;
    const moveCents = optionalCents(amount);
    if (goalCents === "invalid" || startingCents === "invalid" || heldCents === "invalid" || moveCents === "invalid") {
      // `check` has already turned these away; this is for the compiler.
      return;
    }
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
    parts.problem ?? save.error?.message ?? adjust.error?.message ?? addMove.error?.message ?? changeMove.error?.message;

  return (
    <form ref={parts.ref} onSubmit={(event: FormEvent) => parts.submit(event, check, () => void send())} className="space-y-4">
      {parts.header()}

      {parts.shows("what") && (
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
          <div>
            <label htmlFor={`${id}-note`} className={LABEL}>
              Note (optional)
            </label>
            <input id={`${id}-note`} value={note} onChange={(event) => setNote(event.target.value)} maxLength={200} autoComplete="off" className={INPUT} />
          </div>
        </FormSection>
      )}

      {parts.shows("goal") && (
        <FormSection title="How much, by when?">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <MoneyField label="Goal (optional)" value={goal} onChange={setGoal} />
            <div>
              <label htmlFor={`${id}-goal-date`} className={LABEL}>
                Goal date (optional)
              </label>
              <input id={`${id}-goal-date`} type="date" value={goalDate} onChange={(event) => setGoalDate(event.target.value)} className={INPUT} />
            </div>
          </div>
          <p className={HINT}>With both, the app works out how much to set aside each time.</p>
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
        </FormSection>
      )}

      {!archived && parts.shows("each") && (
        <FormSection title="How much each time?">
          {/* The answer to "how much do I need to save?", as soon as there is a goal and a date. Kept short: it shares the screen with the fields it fills in. */}
          {pace && hasGoal && (
            <div role="status" className="rounded-lg border-l-4 border-accent bg-surface px-4 py-2">
              {pace.shortCents === 0 ? (
                <p>
                  <span className="font-semibold">On track</span> for <span data-amount>{formatCents(goalInMind)}</span> by {formatDate(goalDate)}.
                </p>
              ) : (
                <>
                  <p>
                    <span data-amount>{formatCents(goalInMind)}</span> by {formatDate(goalDate)} takes{" "}
                    {pace.morePerPaycheckCents !== null && (
                      <>
                        <span data-amount className="font-semibold tabular-nums">
                          {formatCents(pace.morePerPaycheckCents)}
                        </span>{" "}
                        {more}every paycheck ({pace.paydaysLeft} left), or{" "}
                      </>
                    )}
                    <span data-amount className="font-semibold tabular-nums">
                      {formatCents(pace.morePerMonthCents ?? pace.shortCents)}
                    </span>{" "}
                    {more}a month.
                  </p>
                  <div className="-mx-2 flex flex-wrap gap-x-2">
                    {pace.morePerPaycheckCents !== null && (
                      <button type="button" onClick={() => use("every_pay_period", pace.morePerPaycheckCents ?? 0)} className={BUTTON_LINK}>
                        Use <span data-amount>{formatCents(alreadyEach("every_pay_period") + pace.morePerPaycheckCents)}</span> every paycheck
                      </button>
                    )}
                    {pace.morePerMonthCents !== null && (
                      <button type="button" onClick={() => use("monthly", pace.morePerMonthCents ?? 0)} className={BUTTON_LINK}>
                        Use <span data-amount>{formatCents(alreadyEach("monthly") + pace.morePerMonthCents)}</span> a month
                      </button>
                    )}
                  </div>
                </>
              )}
            </div>
          )}

          <MoneyField
            label="Set aside (optional)"
            value={amount}
            onChange={setAmount}
            hint={own ? "Change it to save more or less from now on. Empty it to stop." : "It stays in this account, set aside, so less shows as available."}
          />
          {/* Side by side on a phone too: two short lists, and the screen has no height to spare. */}
          <div className="grid grid-cols-2 gap-4">
            <div className={cadence === "monthly" ? undefined : "col-span-2"}>
              <label htmlFor={`${id}-cadence`} className={LABEL}>
                How often
              </label>
              <select id={`${id}-cadence`} value={cadence} onChange={(event) => setCadence(event.target.value as Cadence)} className={INPUT}>
                <option value="every_pay_period">Every paycheck</option>
                <option value="monthly">Every month</option>
              </select>
            </div>
            {cadence === "monthly" && <DaySelect id={`${id}-day`} label="Day of the month" value={monthDay} onChange={setMonthDay} />}
          </div>
        </FormSection>
      )}

      {!archived && parts.shows("held") && (
        <FormSection title={bucket ? "What it holds" : "What it already holds"}>
          {bucket ? (
            <MoneyField
              label="In this bucket"
              value={held}
              onChange={setHeld}
              hint="What it holds today. Change it to set a new amount; the difference is recorded as an adjustment."
            />
          ) : (
            <MoneyField label="Already set aside (optional)" value={starting} onChange={setStarting} hint="What this bucket already holds." />
          )}
          <div>
            <label htmlFor={`${id}-held-on`} className={LABEL}>
              As of
            </label>
            <input id={`${id}-held-on`} type="date" value={heldOn} onChange={(event) => setHeldOn(event.target.value)} className={INPUT} />
            <p className={HINT}>
              The day that amount {bucket ? "changes" : "went in"}. Today unless you choose an earlier day, to build the balance up from then.
            </p>
            {!bucket && <DefaultDateHint value={heldOn} />}
          </div>
        </FormSection>
      )}

      {parts.shows("dates") && (
        <FormSection title="Dates">
          {!archived && (
            <div>
              <label htmlFor={`${id}-starts`} className={LABEL}>
                Setting aside starts
              </label>
              <input id={`${id}-starts`} type="date" value={startsOn} onChange={(event) => setStartsOn(event.target.value)} className={INPUT} />
              <p className={HINT}>For the amount set aside each time. An earlier day builds up what it would have set aside since then.</p>
              {!own && <DefaultDateHint value={startsOn} />}
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
              className={INPUT}
            />
            <p className={HINT}>
              Recurring amounts, save-ups and interest reach this bucket from this day on. Choose an earlier day to bring in ones dated before it.
            </p>
            {!bucket && <DefaultDateHint value={openedOn} />}
          </div>
        </FormSection>
      )}

      {/* These three act straight away, on their own: nothing else typed in the form is saved by them. */}
      {bucket && parts.shows("history") && <BucketHistory bucket={bucket} />}
      {!archived && own && parts.shows("pause") && <PauseItem item={own} />}
      {bucket && parts.shows("away") && <ArchiveOrDelete bucket={bucket} onDone={onClose} />}

      {failure && (
        <p role="alert" className="text-danger">
          {failure}
        </p>
      )}

      {parts.buttons({ onClose, pending: saving, saveLabel: bucket ? "Save changes" : "Add bucket" })}
    </form>
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
    <FormSection title={bucket.archived ? "Bring back or delete" : "Archive or delete"}>
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
        <p role="alert" className="text-danger">
          {failure}
        </p>
      )}
    </FormSection>
  );
}
