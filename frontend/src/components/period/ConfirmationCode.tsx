import { Pencil, Plus } from "lucide-react";
import { type KeyboardEvent, useEffect, useRef, useState } from "react";

const CODE_MAX = 80;

/** What the code is called in each place it is named. */
interface Wording {
  /** In labels read out: "Add confirmation code for Rent". */
  what: string;
  /** On the button that adds one. */
  add: string;
  /** Beside the code once it is there. */
  short: string;
}

const CONFIRMATION: Wording = { what: "confirmation code", add: "Add confirmation", short: "Conf #" };

/** For {@link ConfirmationCode}: the number of the check something was paid with. */
export const CHECK_NUMBER: Wording = { what: "check number", add: "Add check number", short: "Check #" };

/**
 * A bill's confirmation code (spec §6), or its check number: plain monospace text once
 * entered, with a pencil to change it; "+ Add confirmation" until then. Enter saves, Esc
 * cancels, and leaving the field saves too. Saving it empty clears the code.
 */
export function ConfirmationCode({
  name,
  code,
  onSave,
  wording = CONFIRMATION,
}: {
  name: string;
  code: string | null;
  onSave: (code: string) => void;
  wording?: Wording;
}) {
  const capitalised = wording.what.charAt(0).toUpperCase() + wording.what.slice(1);
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState("");
  const opener = useRef<HTMLButtonElement>(null);
  // After Enter or Esc the focus goes back to where it came from, so the keyboard keeps its place.
  const returnFocus = useRef(false);

  useEffect(() => {
    if (!editing && returnFocus.current) {
      returnFocus.current = false;
      opener.current?.focus();
    }
  }, [editing]);

  const start = () => {
    setText(code ?? "");
    setEditing(true);
  };

  const commit = () => {
    setEditing(false);
    if (text.trim() !== (code ?? "")) {
      onSave(text.trim());
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

  if (editing) {
    return (
      <input
        autoFocus
        aria-label={`${capitalised} for ${name}`}
        value={text}
        onChange={(event) => setText(event.target.value)}
        onKeyDown={onKeyDown}
        onBlur={commit}
        maxLength={CODE_MAX}
        autoComplete="off"
        placeholder={capitalised}
        className="min-h-11 w-full max-w-56 rounded-md border border-accent bg-surface px-2 font-mono text-sm lg:min-h-7"
      />
    );
  }

  if (code === null || code === "") {
    return (
      <button ref={opener} type="button" onClick={start} aria-label={`Add ${wording.what} for ${name}`} className="flex min-h-11 items-center gap-1 rounded-md text-sm font-semibold text-accent-ink lg:min-h-7">
        <Plus aria-hidden className="size-4" />
        {wording.add}
      </button>
    );
  }

  return (
    <span className="flex min-h-11 items-center gap-2 text-sm lg:min-h-7">
      <span className="text-muted">{wording.short}</span>
      <span className="font-mono break-all">{code}</span>
      <button
        ref={opener}
        type="button"
        onClick={start}
        aria-label={`Edit ${wording.what} for ${name}`}
        className="flex size-11 shrink-0 items-center justify-center rounded-md text-muted lg:size-7"
      >
        <Pencil aria-hidden className="size-4" />
      </button>
    </span>
  );
}
