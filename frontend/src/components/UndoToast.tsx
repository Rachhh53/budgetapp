import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Undo2 } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { api, onDataChanged } from "../api/client";

interface Undone {
  /** False when there was nothing to undo. */
  undone: boolean;
  /** True when an earlier change can be undone next. */
  available: boolean;
}

type Notice = { kind: "saved" } | { kind: "undone" } | { kind: "nothing" } | { kind: "failed"; message: string };

/** Long enough to read and reach for; Ctrl+Z still works after it has gone. */
const SAVED_MS = 8000;
const RESULT_MS = 4000;

/** Where Ctrl+Z belongs to the text being typed. A ticked checkbox is not typing, so Undo works straight after one. */
const TYPING = "input:not([type='checkbox']):not([type='radio']), textarea, [contenteditable='true']";

/**
 * The Undo toast (spec §6): it appears after every change, from any screen, and Ctrl+Z does
 * the same thing. The backend records each change, so undoing is one request, after which
 * everything on screen is fetched again.
 *
 * Ctrl+Z is left alone while typing in a field, where it means "undo my typing".
 */
export function UndoToast() {
  const queryClient = useQueryClient();
  const [notice, setNotice] = useState<Notice | null>(null);

  const undo = useMutation({
    mutationFn: () => api.post<Undone>("/api/undo", undefined),
    onSuccess: async (result) => {
      setNotice({ kind: result.undone ? "undone" : "nothing" });
      // An undo can touch anything, so everything is asked for again.
      await queryClient.invalidateQueries();
    },
    onError: (error) => setNotice({ kind: "failed", message: error.message }),
  });
  const { mutate, isPending } = undo;

  const undoNow = useCallback(() => {
    if (!isPending) {
      mutate();
    }
  }, [mutate, isPending]);

  useEffect(() => onDataChanged(() => setNotice({ kind: "saved" })), []);

  useEffect(() => {
    if (notice === null) {
      return;
    }
    const timer = window.setTimeout(() => setNotice(null), notice.kind === "saved" ? SAVED_MS : RESULT_MS);
    return () => window.clearTimeout(timer);
  }, [notice]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey) || event.shiftKey || event.altKey || event.key.toLowerCase() !== "z") {
        return;
      }
      if (event.target instanceof Element && event.target.closest(TYPING)) {
        return;
      }
      event.preventDefault();
      undoNow();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [undoNow]);

  return (
    // Always in the page, so screen readers announce what appears in it. Sits above the phone tab bar.
    <div aria-live="polite" className="pointer-events-none fixed inset-x-0 bottom-18 z-40 flex justify-center px-4 md:bottom-6">
      {notice && (
        <div className="pointer-events-auto flex min-h-11 items-center gap-3 rounded-lg bg-ink py-1 pr-1 pl-4 text-surface shadow-lg">
          <span>{message(notice)}</span>
          {notice.kind === "saved" && (
            <button
              type="button"
              onClick={undoNow}
              disabled={isPending}
              className="flex min-h-11 items-center gap-2 rounded-md px-3 font-semibold underline disabled:opacity-60"
            >
              <Undo2 aria-hidden className="size-4" />
              Undo
              <kbd className="hidden rounded border border-current px-1 text-xs font-normal no-underline opacity-80 md:inline">Ctrl+Z</kbd>
            </button>
          )}
          {notice.kind !== "saved" && <span className="pr-3" />}
        </div>
      )}
    </div>
  );
}

function message(notice: Notice): string {
  switch (notice.kind) {
    case "saved":
      return "Saved.";
    case "undone":
      return "Undone.";
    case "nothing":
      return "There's nothing left to undo.";
    case "failed":
      return notice.message;
  }
}
