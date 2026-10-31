import { type FocusEvent, type KeyboardEvent, useEffect, useId, useRef, useState } from "react";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { Amount } from "../Amount";
import { AMOUNT_HELP } from "../MoneyField";

/** How far a typed-over amount reaches: only this occurrence, or this one and every later one. */
export type EditScope = "one" | "future";

interface InlineAmountProps {
  /** What the amount belongs to, e.g. "Electric": names the control for screen readers. */
  name: string;
  cents: number;
  estimate?: boolean;
  /** What the app showed before the amount was typed over; `null` if untouched. */
  projectedCents?: number | null;
  /** Said on hover in place of "Projected …", for an amount with more to say about where it came from. */
  peek?: string;
  /** Offer "This and all future" beside "Just this one". Leave out for something that happens once. */
  canChangeFuture?: boolean;
  /** The backend's message if the last save failed. */
  error?: string;
  onSave: (cents: number, scope: EditScope) => void;
}

/** How long a finger rests on an amount before its projected value shows. */
const LONG_PRESS_MS = 500;
const PEEK_MS = 2500;

/**
 * An amount that can be typed over where it stands (spec §6): click it to edit, Enter
 * saves, Esc cancels, and Tab saves and moves on. The box is the same size before, during
 * and after, so the column of amounts never shifts.
 *
 * For something that repeats, the edit asks how far it reaches — "Just this one" unless
 * told otherwise (spec §5.10). What the app projected before stays on record and shows
 * on hover or keyboard focus, and on a long press on a phone.
 */
export function InlineAmount({ name, cents, estimate = false, projectedCents = null, peek, canChangeFuture = false, error, onSave }: InlineAmountProps) {
  const id = useId();
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState("");
  const [scope, setScope] = useState<EditScope>("one");
  const [problem, setProblem] = useState<string>();
  const [peeking, setPeeking] = useState(false);
  const pressTimer = useRef<number | undefined>(undefined);
  // A long press must not also count as the click that opens the editor.
  const pressed = useRef(false);
  const button = useRef<HTMLButtonElement>(null);
  // After Enter or Esc the focus goes back to the amount, so the keyboard keeps its place.
  const returnFocus = useRef(false);

  useEffect(() => {
    if (!editing && returnFocus.current) {
      returnFocus.current = false;
      button.current?.focus();
    }
  }, [editing]);

  const wasProjected = projectedCents !== null && projectedCents !== cents;
  const said = peek ?? (wasProjected ? `Projected ${formatCents(projectedCents)}` : undefined);

  const start = () => {
    if (pressed.current) {
      pressed.current = false;
      return;
    }
    setText(centsToInput(cents));
    setScope("one");
    setProblem(undefined);
    setEditing(true);
  };

  const commit = () => {
    const typed = parseDollars(text);
    if (typed === null || typed < 0) {
      setProblem(AMOUNT_HELP);
      return;
    }
    setEditing(false);
    // Typing the same amount for this one occurrence changes nothing, so nothing is sent.
    if (typed !== cents || scope === "future") {
      onSave(typed, scope);
    }
  };

  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === "Enter") {
      event.preventDefault();
      returnFocus.current = true;
      commit();
    } else if (event.key === "Escape") {
      event.preventDefault();
      returnFocus.current = true;
      setEditing(false);
    }
  };

  /** Leaving the editor altogether (Tab past it, or a click elsewhere) saves, like Enter. */
  const onBlur = (event: FocusEvent<HTMLSpanElement>) => {
    if (!event.currentTarget.contains(event.relatedTarget)) {
      commit();
    }
  };

  const onPointerDown = (pointerType: string) => {
    if (pointerType !== "touch" || !wasProjected) {
      return;
    }
    pressTimer.current = window.setTimeout(() => {
      pressed.current = true;
      setPeeking(true);
      window.setTimeout(() => setPeeking(false), PEEK_MS);
    }, LONG_PRESS_MS);
  };

  if (editing) {
    return (
      <span className="relative inline-flex flex-col items-end" onBlur={onBlur}>
        <input
          autoFocus
          aria-label={`${name} amount`}
          aria-invalid={problem ? true : undefined}
          aria-describedby={problem ? `${id}-problem` : undefined}
          value={text}
          onChange={(event) => setText(event.target.value)}
          onFocus={(event) => event.target.select()}
          onKeyDown={onKeyDown}
          inputMode="decimal"
          autoComplete="off"
          className="w-28 rounded-md border border-accent bg-surface px-2 py-0.5 text-right tabular-nums"
        />
        {canChangeFuture && (
          <fieldset className="absolute top-full right-0 z-10 mt-1 w-max rounded-lg border border-line bg-surface p-2 text-left text-sm shadow-lg" onKeyDown={onKeyDown}>
            <legend className="sr-only">Change {name} for</legend>
            {SCOPES.map((option) => (
              <label key={option.value} className="flex min-h-9 items-center gap-2 px-1">
                <input
                  type="radio"
                  name={`${id}-scope`}
                  checked={scope === option.value}
                  onChange={() => setScope(option.value)}
                  className="size-4 accent-(--accent)"
                />
                {option.label}
              </label>
            ))}
          </fieldset>
        )}
        {problem && (
          <span id={`${id}-problem`} role="alert" className="absolute top-full right-0 z-20 mt-1 w-max max-w-64 rounded-lg bg-surface p-2 text-sm text-danger shadow-lg">
            {problem}
          </span>
        )}
      </span>
    );
  }

  return (
    <span className="group relative inline-flex flex-col items-end">
      <button
        ref={button}
        type="button"
        onClick={start}
        onPointerDown={(event) => onPointerDown(event.pointerType)}
        onPointerUp={() => window.clearTimeout(pressTimer.current)}
        onPointerLeave={() => window.clearTimeout(pressTimer.current)}
        // The long press is ours; without this a phone would open its own menu over it.
        onContextMenu={(event) => said !== undefined && event.preventDefault()}
        aria-label={`${name}: ${formatCents(cents)}${estimate ? ", estimate" : ""}${said ? `, ${said.toLowerCase()}` : ""}. Edit amount`}
        // Tall enough to tap on a phone; on a wide screen the row itself gives the height.
        className="flex min-h-11 items-center rounded-md hover:bg-bg lg:min-h-0"
      >
        <Amount cents={cents} estimate={estimate} className="w-28 font-semibold" />
      </button>
      {said && (
        <span
          aria-hidden
          data-amount
          // Beside the amount, not above it: the panel scrolls inside itself and would cut off
          // anything that reaches past its top edge.
          className={`pointer-events-none absolute top-1/2 right-full z-10 mr-2 w-max -translate-y-1/2 rounded-md bg-ink px-2 py-1 text-xs text-surface group-focus-within:block group-hover:block ${
            peeking ? "block" : "hidden"
          }`}
        >
          {said}
        </span>
      )}
      {error && (
        <span role="alert" className="text-sm text-danger">
          {error}
        </span>
      )}
    </span>
  );
}

const SCOPES: readonly { value: EditScope; label: string }[] = [
  { value: "one", label: "Just this one" },
  { value: "future", label: "This and all future" },
];
