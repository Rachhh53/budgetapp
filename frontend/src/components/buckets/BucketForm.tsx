import { PiggyBank } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import type { Account } from "../../api/accounts";
import {
  type Bucket,
  useBucketHeldOn,
  useDeleteBucket,
  useGoalPace,
  useMoveMoneyInto,
  useSaveBucket,
  useSetBucketHeld,
} from "../../api/buckets";
import { usePayPeriods } from "../../api/income";
import { type Item, itemAsInput, useDeleteItem, useItems, useSaveItem } from "../../api/items";
import { formatDate, todayIso } from "../../lib/dates";
import { useDefaultDate } from "../../api/settings";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { ColorPicker, firstUnusedColor } from "../ColorPicker";
import { ConfirmDelete } from "../ConfirmDelete";
import { type FormGroup, type PartProblem, useFormParts } from "../FormParts";
import { FormSection } from "../FormSection";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { BucketHistory } from "./BucketHistory";
import { DefaultDateHint } from "../settings/DefaultDateHint";
import { BUTTON_DANGER_OUTLINE, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";
import { describeItemSchedule } from "../items/labels";

/** What the history calls the amount a new bucket was given, when that is dated earlier than today. */
const STARTING_NOTE = "Starting amount";

interface BucketFormProps {
  /** The account the bucket sits in. */
  account: Account;
  /** The bucket being changed; leave out to add a new one. */
  bucket?: Bucket;
  /** Every bucket, for the recently used colors. */
  buckets: readonly Bucket[];
  /** The screen to open on, when changing one: History from its row's History button. */
  startAt?: BucketPart;
  onClose: () => void;
}

/** The parts of the form. Each is a screen of its own: on a phone none of them leaves room for another. */
export type BucketPart = "what" | "goal" | "held" | "dates" | "history" | "moves" | "away";

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

function BucketFields({ account, bucket, buckets, items, startAt, onClose }: BucketFormProps & { items: readonly Item[] }) {
  const id = useId();
  const save = useSaveBucket(bucket?.id);
  // Archiving has its own button beside Delete; saving the form leaves it as it is.
  const archived = bucket?.archived ?? false;

  // Everything recurring that puts money in this bucket: transfers into it, and save-ups.
  const feeding = bucket
    ? items.filter((item) => !item.archived && (item.bucketId === bucket.id || item.saveUpBucketId === bucket.id))
    : [];
  // A bucket has no recurring amount of its own any more (Rachel, 2026-10-19: money reaches a bucket by a transfer
  // that names it, or by typing what it holds). Any set up before still run until stopped here, on the account.
  const running = feeding.filter(
    (item) => item.kind === "bucket_move" && (item.schedule.endDate === null || item.schedule.endDate > todayIso()),
  );

  // Newest buckets first, so the colors offered first are the ones used most recently.
  const usedColors = [...buckets].reverse().map((existing) => existing.color);
  const [name, setName] = useState(bucket?.name ?? "");
  const [color, setColor] = useState(bucket?.color ?? firstUnusedColor(usedColors));
  const [goal, setGoal] = useState(bucket?.goalCents != null ? centsToInput(bucket.goalCents) : "");
  const [goalDate, setGoalDate] = useState(bucket?.goalDate ?? "");
  // Saving towards something usually ends when it is reached; untick to keep setting money aside.
  const [stopAtGoal, setStopAtGoal] = useState(bucket?.stopAtGoal ?? true);
  const startOn = useDefaultDate();
  const [starting, setStarting] = useState("");
  // What was typed into "In this bucket"; until then, the field shows what the bucket held on the chosen day.
  const [typedHeld, setTypedHeld] = useState<string | null>(null);
  // The day the amount typed here is true for: today, or earlier to build the balance up from then.
  // A new bucket's first amount follows the day new things start on; a change to one that exists is made today.
  const [heldOn, setHeldOn] = useState(bucket ? todayIso() : startOn.date);
  // What it held at the end of that day. Anything typed is measured against this, not today's balance, so
  // everything scheduled after the day builds on it (Rachel, 2026-10-20: a Jan 1 amount had landed as today's).
  const heldThen = useBucketHeldOn(bucket?.id, heldOn).data?.heldCents ?? (heldOn === todayIso() ? bucket?.balanceCents : undefined);
  const held = typedHeld ?? (heldThen === undefined ? "" : centsToInput(heldThen));
  const setHeldAmount = useSetBucketHeld(bucket?.id ?? 0);
  const adjust = useMoveMoneyInto();
  // Set once a backdated starting amount has gone in, so trying again after a later step failed does not add it twice.
  const [startingPosted, setStartingPosted] = useState(false);
  const [note, setNote] = useState(bucket?.note ?? "");
  const [chosenOpenedOn, setChosenOpenedOn] = useState<string | null>(bucket?.openedOn ?? null);

  // An archived bucket is not asked what it holds.
  const groups: FormGroup<BucketPart>[] = [
    { label: "Name, color and note", parts: ["what"] },
    { label: "Goal", parts: ["goal"] },
    ...(archived ? [] : ([{ label: "What it holds", parts: ["held"] }] as const)),
    { label: "Dates", parts: ["dates"] },
    ...(bucket ? [{ label: "History", parts: ["history"] } as const] : []),
    ...(bucket && running.length > 0 ? [{ label: "Still setting aside", parts: ["moves"] } as const] : []),
    ...(bucket ? [{ label: archived ? "Bring back or delete" : "Archive or delete", parts: ["away"] } as const] : []),
  ];
  const parts = useFormParts<BucketPart>({ groups, adding: !bucket, required: QUESTIONS, startAt });
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
  const typedInMind = optionalCents(bucket ? held : starting);
  // The pace counts from today's balance, so an amount for an earlier day counts as the difference it makes.
  const heldInMind =
    bucket && typeof typedInMind === "number" && heldThen !== undefined ? typedInMind - heldThen + bucket.balanceCents : typedInMind;
  const pace = useGoalPace(
    hasGoal && goalDate > todayIso() && heldInMind !== "invalid"
      ? { goalCents: goalInMind, goalDate, bucketId: bucket?.id, heldCents: heldInMind ?? 0 }
      : undefined,
  ).data;
  // What the goal takes is on top of anything already going in.
  const more = feeding.length > 0 ? "more " : "";

  /** The first thing that cannot be saved as it is, and the screen it is on. */
  const check = (): PartProblem<BucketPart> | null => {
    const wrong = (
      [
        ["goal", goal],
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
    if (goalCents === "invalid" || startingCents === "invalid" || heldCents === "invalid") {
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
      // An amount typed for a day is kept as that amount, so it still holds when something before it changes.
      if (bucket && heldCents !== null && !archived && typedHeld !== null && heldCents !== heldThen) {
        await setHeldAmount.mutateAsync({ date: heldOn, amountCents: heldCents });
      }
      if (!bucket && heldOn !== todayIso() && startingCents !== null && startingCents > 0 && !startingPosted) {
        await adjust.mutateAsync({ bucketId, date: heldOn, amountCents: startingCents, note: STARTING_NOTE });
        setStartingPosted(true);
      }
      onClose();
    } catch {
      // The failed step's own message is shown below; the form stays open to try again.
    }
  };

  const saving = save.isPending || adjust.isPending || setHeldAmount.isPending;
  const failure = parts.problem ?? save.error?.message ?? adjust.error?.message ?? setHeldAmount.error?.message;

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
          {/* The answer to "how much do I need to save?", as soon as there is a goal and a date: what a transfer into it takes. */}
          {pace && hasGoal ? (
            <div role="status" className="rounded-lg border-l-4 border-accent bg-surface px-4 py-2">
              {pace.shortCents === 0 ? (
                <p>
                  <span className="font-semibold">On track</span> for <span data-amount>{formatCents(goalInMind)}</span> by {formatDate(goalDate)}.
                </p>
              ) : (
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
                  {more}a month. A recurring transfer into {account.name} that names this bucket puts it here.
                </p>
              )}
            </div>
          ) : (
            <p className={HINT}>With both, the app works out how much to put in each time.</p>
          )}
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

      {!archived && parts.shows("held") && (
        <FormSection title={bucket ? "What it holds" : "What it already holds"}>
          {bucket ? (
            <MoneyField
              label="In this bucket"
              value={held}
              onChange={setTypedHeld}
              hint="What it held at the end of the day below. Change it to set a new amount; what came in and went out after that day is added on top."
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
              The day that amount {bucket ? "is true for" : "went in"}. Today unless you choose an earlier day, to build the balance up from then.
            </p>
            {!bucket && <DefaultDateHint value={heldOn} />}
          </div>
        </FormSection>
      )}

      {parts.shows("dates") && (
        <FormSection title="Dates">
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
      {bucket && parts.shows("moves") && (
        <FormSection title="Still setting aside">
          <p className={HINT}>
            These still set money aside in this bucket on their own. Stop them when you like: a transfer into {account.name} that names
            this bucket does the same job.
          </p>
          {running.map((move) => (
            <StopOrDeleteMove key={move.id} move={move} bucket={bucket} />
          ))}
        </FormSection>
      )}
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
 * One amount still set aside in the bucket on its own, from before buckets were filled only by
 * transfers: what it is, with Stop (ends it today and keeps what it already put in) and Delete (takes
 * that out too). Once stopped or deleted it leaves the list. The bucket stays either way.
 */
function StopOrDeleteMove({ move, bucket }: { move: Item; bucket: Bucket }) {
  const today = todayIso();
  const stop = useSaveItem(move.id);
  const remove = useDeleteItem(move.id);
  // One that has not started yet has nothing to keep, so it can only be deleted.
  const started = move.schedule.startDate <= today;
  return (
    <div className="rounded-lg border border-line px-3 py-2">
      <p className="font-medium">
        <span data-amount className="tabular-nums">
          {formatCents(move.perOccurrenceCents)}
        </span>{" "}
        {describeItemSchedule(move.schedule).toLowerCase()}
      </p>
      <div className="mt-2 flex flex-col gap-2 sm:flex-row">
        {started && (
          <button
            type="button"
            onClick={() => stop.mutate({ ...itemAsInput(move), schedule: { ...move.schedule, endDate: today }, effectiveFrom: today })}
            disabled={stop.isPending}
            className={BUTTON_SECONDARY}
          >
            {stop.isPending ? "Stopping…" : "Stop it from today"}
          </button>
        )}
        <ConfirmDelete
          action="Delete it…"
          question={`Delete ${move.name}?`}
          detail={`Everything it set aside in ${bucket.name} goes too, as if it never ran. To keep that, stop it instead. You can undo this straight afterwards.`}
          confirmLabel="Yes, delete it"
          pending={remove.isPending}
          error={remove.error?.message}
          onConfirm={() => remove.mutate()}
        />
      </div>
      {stop.error && (
        <p role="alert" className="text-danger">
          {stop.error.message}
        </p>
      )}
    </div>
  );
}

/**
 * The two ways to put a bucket away, side by side. Archiving is as of a day: the bucket keeps everything before it in
 * every pay period, lets go of whatever it still holds that day, and can be brought back or given another day here
 * (Rachel, 2026-10-22: "the archive was as of a certain date- the previous activity needs to remain"). Deleting
 * removes it. No "are you sure?" for either (spec §6): Undo brings it back.
 */
function ArchiveOrDelete({ bucket, onDone }: { bucket: Bucket; onDone: () => void }) {
  const id = useId();
  const remove = useDeleteBucket(bucket.id);
  const save = useSaveBucket(bucket.id);
  const startOn = useDefaultDate();
  const opensOn = startOn.date < bucket.openedOn ? bucket.openedOn : startOn.date;
  const [day, setDay] = useState(bucket.archivedOn ?? opensOn);
  const busy = remove.isPending || save.isPending;
  const failure = remove.error?.message ?? save.error?.message;
  const send = (archived: boolean) =>
    save.mutate(
      {
        accountId: bucket.accountId,
        name: bucket.name,
        color: bucket.color,
        goalCents: bucket.goalCents,
        note: bucket.note,
        archived,
        archivedOn: archived ? day : null,
        openedOn: bucket.openedOn,
        goalDate: bucket.goalDate,
      },
      { onSuccess: onDone },
    );
  return (
    <FormSection title={bucket.archived ? "Bring back or delete" : "Archive or delete"}>
      <div>
        <label htmlFor={`${id}-archived-on`} className={LABEL}>
          {bucket.archived ? "Archived as of" : "Archive as of"}
        </label>
        <input
          id={`${id}-archived-on`}
          type="date"
          value={day}
          min={bucket.openedOn}
          max={todayIso()}
          onChange={(event) => setDay(event.target.value)}
          className={INPUT}
        />
        {!bucket.archived && <DefaultDateHint value={day} openedOn={opensOn === startOn.date ? undefined : opensOn} />}
      </div>
      <div className="flex flex-col gap-2 sm:flex-row">
        {bucket.archived && (
          <button type="button" onClick={() => send(true)} disabled={busy || day === "" || day === bucket.archivedOn} className={BUTTON_SECONDARY}>
            {save.isPending ? "Saving…" : "Save the date"}
          </button>
        )}
        <button type="button" onClick={() => send(!bucket.archived)} disabled={busy || (!bucket.archived && day === "")} className={BUTTON_SECONDARY}>
          {save.isPending ? "Saving…" : bucket.archived ? "Bring this bucket back" : "Archive this bucket"}
        </button>
        <button type="button" onClick={() => remove.mutate(undefined, { onSuccess: onDone })} disabled={busy} className={BUTTON_DANGER_OUTLINE}>
          {remove.isPending ? "Deleting…" : "Delete this bucket"}
        </button>
      </div>
      <p className={HINT}>
        {bucket.archived
          ? "Archived: it keeps everything before that day and sets nothing aside from then on. Bring it back to use it again, or delete it for good."
          : "Archiving keeps everything before that day, in every pay period, and stops it setting anything aside from then on. Deleting removes it."}{" "}
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
