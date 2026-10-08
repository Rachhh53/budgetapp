import { Pencil, Plus } from "lucide-react";
import { type KeyboardEvent, useEffect, useRef, useState } from "react";

const CODE_MAX = 80;

/**
 * A bill's confirmation code (spec §6): plain monospace text once entered, with a pencil to
 * change it; "+ Add confirmation" until then. Enter saves, Esc cancels, and leaving the
 * field saves too. Saving it empty clears the code.
 */
export function ConfirmationCode({ name, code, onSave }: { name: string; code: string | null; onSave: (code: string) => void }) {
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
        aria-label={`Confirmation code for ${name}`}
        value={text}
        onChange={(event) => setText(event.target.value)}
        onKeyDown={onKeyDown}
        onBlur={commit}
        maxLength={CODE_MAX}
        autoComplete="off"
        placeholder="Confirmation code"
        className="min-h-11 w-full max-w-56 rounded-md border border-accent bg-surface px-2 font-mono text-sm lg:min-h-7"
      />
    );
  }

  if (code === null || code === "") {
    return (
      <button ref={opener} type="button" onClick={start} aria-label={`Add confirmation code for ${name}`} className="flex min-h-11 items-center gap-1 rounded-md text-sm font-semibold text-accent-ink lg:min-h-7">
        <Plus aria-hidden className="size-4" />
        Add confirmation
      </button>
    );
  }

  return (
    <span className="flex min-h-11 items-center gap-2 text-sm lg:min-h-7">
      <span className="text-muted">Conf #</span>
      <span className="font-mono break-all">{code}</span>
      <button
        ref={opener}
        type="button"
        onClick={start}
        aria-label={`Edit confirmation code for ${name}`}
        className="flex size-11 shrink-0 items-center justify-center rounded-md text-muted lg:size-7"
      >
        <Pencil aria-hidden className="size-4" />
      </button>
    </span>
  );
}
