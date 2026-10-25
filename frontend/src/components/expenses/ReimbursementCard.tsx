import { CircleCheck, Clock, type LucideIcon, Minus, Send } from "lucide-react";
import { useId, useState } from "react";
import type { MoveOut } from "../../api/buckets";
import { useBuckets } from "../../api/buckets";
import { type Entry, REIMBURSEMENT_LABELS, type Reimbursement, type ReimbursementStatus, useNames } from "../../api/entries";
import { formatDate, todayIso } from "../../lib/dates";
import { formatCents } from "../../lib/money";
import { useAddItem } from "../add/AddItem";
import { fromOf, type PaysBackTarget } from "../add/owedExpenses";
import { Amount } from "../Amount";
import { Card } from "../Card";
import { TextPicker } from "../Picker";
import { clickRowToEdit, ROW_OPENS } from "../rowEdit";
import { BUTTON_LINK, BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

const STATUS_ICONS: Record<ReimbursementStatus, LucideIcon> = {
  none: Minus,
  to_submit: Send,
  submitted: Clock,
  reimbursed: CircleCheck,
};

/** A change to where it stands; whatever is left out stays as it is. */
export type ReimbursementChange = Partial<{ status: ReimbursementStatus; submittedOn: string | null; to: string | null }>;

/**
 * Where something stands on being paid back: a one-off expense, or a recurring payment flagged to be paid back (Rachel,
 * 2026-10-24: "same as one-off expenses"), on its own page. Each control saves as it is changed, like a paid checkbox, and
 * Undo takes any of it back: there is nothing here to forget to save.
 *
 * @param target         what is paid back, for the forms that record money coming back for it
 * @param onSet          saves a change to where it stands
 * @param onNotPaidBack  nobody pays it back after all
 */
export function ReimbursementCard({
  reimbursement,
  target,
  paybacks,
  movesOut,
  onSet,
  onNotPaidBack,
  saving,
  error,
}: {
  reimbursement: Reimbursement;
  target: PaysBackTarget;
  paybacks: readonly Entry[];
  movesOut: readonly MoveOut[];
  onSet: (change: ReimbursementChange) => void;
  onNotPaidBack: () => void;
  saving: boolean;
  error: string | undefined;
}) {
  const names = useNames();
  const { add, editEntry, moveOut } = useAddItem();
  const buckets = useBuckets();
  const openBuckets = (buckets.data ?? []).filter((bucket) => !bucket.archived);
  const bucketName = (bucketId: number) => buckets.data?.find((bucket) => bucket.id === bucketId)?.name ?? "a bucket";
  // Money that came in and money moved out of a bucket for it, one list by date.
  const cameBack = [
    ...paybacks.map((payback) => ({
      key: `entry-${payback.id}`,
      date: payback.date,
      what: payback.description,
      from: null as string | null,
      cents: payback.amountCents,
      estimate: payback.estimate,
      open: () => editEntry(payback),
    })),
    ...movesOut.map((move) => ({
      key: `bucket-${move.id}`,
      date: move.date,
      what: move.note,
      from: `Out of ${bucketName(move.bucketId)}`,
      cents: move.amountCents,
      estimate: false,
      open: () => moveOut(move),
    })),
  ].sort((a, b) => a.date.localeCompare(b.date));
  const dateId = useId();
  const { status, submittedOn, to } = reimbursement;
  const sent = status === "submitted" || status === "reimbursed";
  // The day sent in, as it is being typed; undefined when it isn't.
  const [sentOn, setSentOn] = useState<string>();

  const set = (change: ReimbursementChange) => onSet({ status, submittedOn, to, ...change });
  // The ways back, and out, are a click further away than the way forward.
  const [changing, setChanging] = useState(false);
  const step = (change: ReimbursementChange) => {
    setChanging(false);
    set(change);
  };

  return (
    <Card title="Reimbursement">
      <div className="space-y-4">
        {status === "none" ? (
          <>
            <p className="text-muted">{summary(reimbursement)}</p>
            <button type="button" onClick={() => set({ status: "to_submit" })} disabled={saving} className={BUTTON_SECONDARY}>
              I'll be paid back for this
            </button>
          </>
        ) : (
          <>
            {/* How far along it is, to look at; the one button under it is the next thing to do. */}
            <Steps reimbursement={reimbursement} />
            <p className="text-muted">{summary(reimbursement)}</p>
            <div className="flex flex-wrap items-center gap-2">
              {status === "to_submit" && (
                // Marking it sent in means today; the day can be changed below.
                <button
                  type="button"
                  onClick={() => set({ status: "submitted", submittedOn: submittedOn ?? todayIso() })}
                  disabled={saving}
                  className={BUTTON_PRIMARY}
                >
                  Mark as submitted
                </button>
              )}
              {status === "submitted" && (
                <button type="button" onClick={() => set({ status: "reimbursed" })} disabled={saving} className={BUTTON_PRIMARY}>
                  Mark as reimbursed
                </button>
              )}
              <button type="button" onClick={() => setChanging(!changing)} aria-expanded={changing} className={BUTTON_LINK}>
                Change
              </button>
            </div>
            {changing && (
              <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap">
                {status === "submitted" && (
                  <button type="button" onClick={() => step({ status: "to_submit", submittedOn: null })} disabled={saving} className={BUTTON_SECONDARY}>
                    Back to To submit
                  </button>
                )}
                {status === "reimbursed" && (
                  <button type="button" onClick={() => step({ status: "submitted" })} disabled={saving} className={BUTTON_SECONDARY}>
                    Back to Submitted
                  </button>
                )}
                <button
                  type="button"
                  onClick={() => {
                    setChanging(false);
                    onNotPaidBack();
                  }}
                  disabled={saving}
                  className={BUTTON_SECONDARY}
                >
                  I won't be paid back for this
                </button>
              </div>
            )}
          </>
        )}

        {status !== "none" && (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <TextPicker
              label="Who pays it back (optional)"
              noun="name"
              placeholder="e.g. HSA, work, insurance"
              suggestions={names.data?.reimbursers ?? []}
              value={to ?? ""}
              onChange={(text) => set({ to: text === "" ? null : text })}
            />
            {sent && (
              <div>
                <label htmlFor={dateId} className={LABEL}>
                  Sent in on (optional)
                </label>
                <input
                  id={dateId}
                  type="date"
                  value={sentOn ?? submittedOn ?? ""}
                  // Kept here while it is typed and saved on leaving the field: saved on every key, each save
                  // came back into the field mid-typing and started the day over (Rachel, 2026-10-20: "when I
                  // choose day and type 16 it keeps setting to 06"). Picking from the calendar is one change.
                  onChange={(event) => setSentOn(event.target.value)}
                  onBlur={() => {
                    if (sentOn !== undefined && sentOn !== (submittedOn ?? "")) {
                      set({ submittedOn: sentOn === "" ? null : sentOn });
                    }
                    setSentOn(undefined);
                  }}
                  className={INPUT}
                />
              </div>
            )}
          </div>
        )}
        {error && (
          <p role="alert" className="text-danger">
            {error}
          </p>
        )}

        {(status !== "none" || cameBack.length > 0) && (
          <div className="border-t border-line pt-3">
            <h3 className="font-semibold">Money that came back</h3>
            {cameBack.length === 0 ? (
              <p className={HINT}>
                Nothing yet. When it arrives, or you pay yourself back from a bucket, add it here and it is counted against this
                {"scheduledDate" in target ? " payment" : " expense"}.
              </p>
            ) : (
              <ul className="divide-y divide-line">
                {cameBack.map((back) => (
                  <li key={back.key} onClick={clickRowToEdit} className={`-mx-2 flex min-h-11 items-center gap-3 rounded-lg px-2 py-1 ${ROW_OPENS}`}>
                    <span className="shrink-0 text-muted">{formatDate(back.date)}</span>
                    <button
                      type="button"
                      data-edit
                      onClick={back.open}
                      aria-label={`Edit ${back.what}`}
                      title={`Edit ${back.what}`}
                      className="min-w-0 flex-1 cursor-pointer truncate rounded-sm text-left font-medium"
                    >
                      {back.what}
                      {back.from && <span className="block truncate text-sm font-normal text-muted">{back.from}</span>}
                    </button>
                    <Amount cents={back.cents} estimate={back.estimate} className="shrink-0 font-semibold" />
                  </li>
                ))}
              </ul>
            )}
            <div className="mt-3 flex flex-wrap gap-2">
              {/* Out of one of her own accounts, like an FSA, it is recorded as a transfer from there. */}
              <button
                type="button"
                onClick={() => {
                  const from = fromOf(target);
                  if (from === null) {
                    add("money_in", undefined, target);
                  } else {
                    add("transfer", from, target);
                  }
                }}
                className={BUTTON_SECONDARY}
              >
                + Add money that came back
              </button>
              {openBuckets.length > 0 && (
                <button type="button" onClick={() => moveOut(undefined, target)} className={BUTTON_SECONDARY}>
                  + Pay back from a bucket
                </button>
              )}
            </div>
          </div>
        )}
      </div>
    </Card>
  );
}

/** The three stops on the way to being paid back, in order. */
const STEPS = ["to_submit", "submitted", "reimbursed"] as const satisfies readonly ReimbursementStatus[];

/**
 * How far along being paid back it is, as a line of three stops (Rachel, 2026-10-12: a row of
 * choices to click "look like tabs", and did not say which one it was on). Only shown, never
 * clicked. Each stop has its icon and says in words whether it is done, so nothing rests on color.
 */
function Steps({ reimbursement }: { reimbursement: Reimbursement }) {
  const at = STEPS.findIndex((step) => step === reimbursement.status);
  const under = (step: (typeof STEPS)[number], position: number): string => {
    if (position > at) {
      return "Not yet";
    }
    switch (step) {
      case "to_submit":
        return position === at ? "Not sent in yet" : "Done";
      case "submitted":
        return reimbursement.submittedOn === null ? "Sent in" : formatDate(reimbursement.submittedOn);
      case "reimbursed":
        return "Paid back";
    }
  };
  return (
    <ol aria-label="How far along it is" className="grid grid-cols-3">
      {STEPS.map((step, position) => {
        const Icon = STATUS_ICONS[step];
        const reached = position <= at;
        return (
          <li key={step} aria-current={position === at ? "step" : undefined} className="relative flex min-w-0 flex-col items-center text-center">
            {/* The line from the stop before, drawn behind the two circles it joins. */}
            {position > 0 && <span aria-hidden className={`absolute top-5 right-1/2 h-0.5 w-full ${reached ? "bg-accent" : "bg-line"}`} />}
            <span
              className={`relative flex size-10 items-center justify-center rounded-full border-2 ${
                reached ? "border-accent bg-accent text-on-accent" : "border-line bg-surface text-muted"
              } ${position === at ? "ring-2 ring-accent ring-offset-2 ring-offset-surface" : ""}`}
            >
              <Icon aria-hidden className="size-5" />
            </span>
            <span className={`mt-2 text-sm ${position === at ? "font-bold" : "font-medium"} ${reached ? "" : "text-muted"}`}>
              {REIMBURSEMENT_LABELS[step]}
            </span>
            <span className="text-sm text-muted">{under(step, position)}</span>
          </li>
        );
      })}
    </ol>
  );
}

/** Where it stands, in a sentence: "Submitted to HSA on Sep 29, 2026 · $25.00 still to come." */
export function summary({ status, submittedOn, to, owedCents, paidBackCents }: Reimbursement): string {
  const whom = to === null ? "" : ` to ${to}`;
  const when = submittedOn === null ? "" : ` on ${formatDate(submittedOn)}`;
  switch (status) {
    case "none":
      return paidBackCents > 0
        ? `Nobody is expected to pay this back, though ${formatCents(paidBackCents)} has come in for it.`
        : "Nobody pays this back.";
    case "to_submit":
      return `Still to send in${whom} · ${formatCents(owedCents)} to ask for.`;
    case "submitted":
      return `Submitted${whom}${when} · ${owedCents > 0 ? `${formatCents(owedCents)} still to come` : "all of it has come back"}.`;
    case "reimbursed":
      return `Reimbursed${to === null ? "" : ` by ${to}`}${submittedOn === null ? "" : ` · sent in on ${formatDate(submittedOn)}`}.`;
  }
}
