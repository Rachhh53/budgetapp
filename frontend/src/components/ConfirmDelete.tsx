import { TriangleAlert } from "lucide-react";
import { useEffect, useId, useRef, useState } from "react";
import { BUTTON_DANGER, BUTTON_DANGER_OUTLINE, BUTTON_SECONDARY } from "./ui";

interface ConfirmDeleteProps {
  /** The button that starts it, e.g. "Delete account…". */
  action: string;
  /** The question, e.g. "Delete Everyday Checking?". */
  question: string;
  /** What will be lost. */
  detail: string;
  /** The button that goes ahead, e.g. "Yes, delete it". */
  confirmLabel: string;
  pending: boolean;
  /** The backend's message if deleting failed. */
  error?: string;
  onConfirm: () => void;
}

/**
 * Asks before deleting a whole account or income source — one of the few places the app
 * says "are you sure?" (spec §6). Keeping is the default: it gets the focus, so pressing
 * Enter by reflex deletes nothing.
 */
export function ConfirmDelete({ action, question, detail, confirmLabel, pending, error, onConfirm }: ConfirmDeleteProps) {
  const id = useId();
  const [confirming, setConfirming] = useState(false);
  const keepButton = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (confirming) {
      keepButton.current?.focus();
    }
  }, [confirming]);

  if (!confirming) {
    return (
      <button type="button" onClick={() => setConfirming(true)} className={BUTTON_DANGER_OUTLINE}>
        {action}
      </button>
    );
  }

  return (
    <div
      role="alertdialog"
      aria-labelledby={`${id}-question`}
      aria-describedby={`${id}-detail`}
      className="rounded-lg border border-danger p-4"
    >
      <p id={`${id}-question`} className="flex items-center gap-2 font-semibold">
        <TriangleAlert aria-hidden className="size-5 shrink-0 text-danger" />
        {question}
      </p>
      <p id={`${id}-detail`} className="mt-1 text-muted">
        {detail}
      </p>
      <div className="mt-3 flex flex-col gap-2 sm:flex-row">
        <button
          ref={keepButton}
          type="button"
          disabled={pending}
          onClick={() => setConfirming(false)}
          className={BUTTON_SECONDARY}
        >
          Keep it
        </button>
        <button type="button" disabled={pending} onClick={onConfirm} className={BUTTON_DANGER}>
          {pending ? "Deleting…" : confirmLabel}
        </button>
      </div>
      {error && (
        <p role="alert" className="mt-3 text-danger">
          {error}
        </p>
      )}
    </div>
  );
}
