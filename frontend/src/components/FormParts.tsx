import { type FormEvent, type ReactNode, useEffect, useId, useRef, useState } from "react";
import { Segmented } from "./Segmented";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, INPUT, LABEL } from "./ui";

/** Up to this many groups are a row of segments; more than that would not fit a phone, so they are a list. */
const MOST_SEGMENTS = 3;

/** A screenful of a form: the parts shown together, and the name they go by. */
export interface FormGroup<P extends string> {
  /** Short, for the switch at the top of an edit form: "Details", "Schedule". */
  label: string;
  parts: readonly P[];
}

/** Something still to put right, and the part of the form it is in. */
export interface PartProblem<P extends string> {
  part: P;
  problem: string;
}

/**
 * Keeps a long form to one screen at a time (decided 2026-10-10: "modal forms must fit on one
 * screen or you need to find another solution"). The form stays one form, with one Save;
 * only what is in view changes:
 *
 * - **Adding** goes through the groups in order, with Back and Next and a "Step 2 of 3"
 *   line. Each screen is checked before the next opens, and nothing is sent until the last.
 *   With `required`, only the first so many are steps: the last of them can save, and the
 *   rest are reached with "More options", for what most people leave as it is.
 * - **Changing** shows one group at a time, chosen at the top (a row of segments, or a list
 *   when there are more than three), and Save is always there: whatever a group does not
 *   show is sent back as it was.
 * - **Changing one group** (`only`), for a form opened from the card that shows that group:
 *   just that group, with no switch.
 *
 * The form keeps everything typed in its own state, so moving between screens loses nothing.
 * Give the form element `ref`: Next and Back put the keyboard on the first field of the screen
 * they open, as opening the dialog does.
 */
export function useFormParts<P extends string>({
  groups,
  adding,
  required,
  moreLabel = "More options",
  only,
  startAt,
}: {
  groups: readonly FormGroup<P>[];
  adding: boolean;
  /** When adding: how many of the groups are steps everyone goes through. Leave out for all of them. */
  required?: number;
  /** What the groups after the required ones are called, on the button that opens them. */
  moreLabel?: string;
  /** A part of the one group to show; leave out for the whole form. */
  only?: P;
  /** When changing: a part of the group to open on, for a form reached from that part. The others stay a choice away. */
  startAt?: P;
}) {
  const [index, setIndex] = useState(() => {
    const opensOn = only ?? (adding ? undefined : startAt);
    return Math.max(0, opensOn === undefined ? 0 : groups.findIndex((group) => group.parts.includes(opensOn)));
  });
  const [problem, setProblem] = useState<string>();
  // The groups can change while the form is open (a kind of account that has no warnings, say).
  const current = Math.min(index, groups.length - 1);
  const isLast = current === groups.length - 1;
  const fixed = !adding && only !== undefined;
  const steps = Math.min(required ?? groups.length, groups.length);
  // Save is offered on the last step everyone goes through, and at the very end.
  const finishes = isLast || current === steps - 1;
  const switchId = useId();

  const form = useRef<HTMLFormElement>(null);
  // Set by Next and Back only: the switch on an edit form keeps the focus it was given.
  const stepped = useRef(false);
  useEffect(() => {
    if (stepped.current) {
      stepped.current = false;
      form.current?.querySelector<HTMLElement>("input, select, textarea, button")?.focus();
    }
  }, [current]);

  const goTo = (target: number) => {
    setProblem(undefined);
    stepped.current = adding && target !== current;
    setIndex(target);
  };

  return {
    ref: form,
    /** Which screen is in view, counted from 0, for whatever frames the form and makes room for it. */
    step: current,
    /** Whether this part is on the screen in view. */
    shows: (part: P) => groups[current]?.parts.includes(part) ?? false,
    problem,
    setProblem,
    /** The label of the group in view, for a dialog title such as "Edit Acme Corp · Schedule". */
    label: groups[current]?.label,
    fixed,

    /**
     * The form's submit handler. `check` looks over the whole form and names the first thing
     * wrong, if any; `save` sends it.
     */
    submit: (event: FormEvent, check: () => PartProblem<P> | null, save: () => void) => {
      event.preventDefault();
      const found = check();
      const where = found ? groups.findIndex((group) => group.parts.includes(found.part)) : -1;
      if (adding) {
        // On the way through, only what this screen asks is held against it; the rest is still to come.
        if (found && (finishes || where === current)) {
          goTo(where === -1 ? current : where);
          setProblem(found.problem);
          return;
        }
        if (!finishes) {
          goTo(current + 1);
          return;
        }
      } else if (found) {
        // Show the screen the problem is on, unless the form is held to one group.
        if (!fixed && where !== -1) {
          setIndex(where);
        }
        setProblem(found.problem);
        return;
      }
      setProblem(undefined);
      save();
    },

    /** What goes at the top of the form: where the person is (adding) or the switch between groups (changing). */
    header: (): ReactNode => {
      if (groups.length < 2 || fixed) {
        return null;
      }
      if (adding) {
        // A single step needs no counting.
        if (current < steps && steps === 1) {
          return null;
        }
        return (
          <p role="status" className="text-sm font-semibold text-muted">
            {current < steps ? `Step ${current + 1} of ${steps}` : `${moreLabel} · ${current - steps + 1} of ${groups.length - steps}`}
          </p>
        );
      }
      if (groups.length > MOST_SEGMENTS) {
        return (
          <div>
            <label htmlFor={switchId} className={LABEL}>
              What to change
            </label>
            <select id={switchId} value={current} onChange={(event) => goTo(Number(event.target.value))} className={INPUT}>
              {groups.map((group, position) => (
                <option key={group.label} value={position}>
                  {group.label}
                </option>
              ))}
            </select>
          </div>
        );
      }
      return (
        <Segmented
          legend="What to change"
          dense
          options={groups.map((group, position) => ({ value: String(position), label: group.label }))}
          value={String(current)}
          onChange={(position) => goTo(Number(position))}
        />
      );
    },

    /** Cancel or Back on one side, Next or Save on the other. */
    buttons: ({
      onClose,
      pending,
      saveLabel,
      cancelLabel = "Cancel",
    }: {
      onClose: () => void;
      pending: boolean;
      saveLabel: string;
      cancelLabel?: string;
    }): ReactNode => (
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        {adding && current > 0 ? (
          <button type="button" onClick={() => goTo(current - 1)} className={BUTTON_SECONDARY}>
            Back
          </button>
        ) : (
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            {cancelLabel}
          </button>
        )}
        {adding && finishes && !isLast && (
          <button type="button" onClick={() => goTo(current + 1)} className={BUTTON_SECONDARY}>
            {moreLabel}
          </button>
        )}
        <button type="submit" disabled={pending} className={BUTTON_PRIMARY}>
          {pending ? "Saving…" : adding && !finishes ? "Next" : saveLabel}
        </button>
      </div>
    ),
  };
}
