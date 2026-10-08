import { X } from "lucide-react";
import { type KeyboardEvent, type MouseEvent, type ReactNode, type SyntheticEvent, useEffect, useId, useRef, useState } from "react";
import { BUTTON_PRIMARY, BUTTON_SECONDARY } from "./ui";

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled])';

/** Choices that are not form fields, so they raise no change event: a picker's options and a color swatch. */
const CHOICE = '[role="option"], [role="group"] button';

/**
 * A dialog over the page, used for every add/edit form.
 *
 * On a phone it is a sheet that fills the width and rises from the bottom; from `sm` up it
 * is a centered panel. It never has a fixed width, and scrolls inside itself when the form
 * is taller than the screen. Tab stays inside it, and focus goes back to whatever opened it.
 *
 * It closes on Escape, on the X, and on a click anywhere outside it. If something in it was
 * changed and not saved, those ask first, so a stray click never throws work away. (A form's
 * own Cancel button is a deliberate "discard" and does not ask.)
 *
 * With `drawer`, wide screens get a full-height panel on the right instead (the edit drawer
 * in design/recurring.png), so the list behind it stays in view.
 */
export function Modal({
  title,
  onClose,
  drawer = false,
  children,
}: {
  title: string;
  onClose: () => void;
  drawer?: boolean;
  children: ReactNode;
}) {
  const titleId = useId();
  const panel = useRef<HTMLDivElement>(null);
  const keepEditing = useRef<HTMLButtonElement>(null);
  // A ref, not state: typing must not re-render the dialog on every key.
  const changed = useRef(false);
  // A press that starts inside (selecting text, say) and ends outside is not a click outside.
  const pressedOutside = useRef(false);
  const [asking, setAsking] = useState(false);

  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    // Start on the first field, so the form can be filled in straight away.
    const first = panel.current?.querySelector<HTMLElement>("[data-autofocus], input, select, textarea");
    (first ?? panel.current)?.focus();
    return () => opener?.focus();
  }, []);

  const wasAsking = useRef(false);
  useEffect(() => {
    if (asking) {
      keepEditing.current?.focus();
    } else if (wasAsking.current) {
      // The question's buttons are gone; keep focus in the dialog so the keyboard still works in it.
      panel.current?.focus();
    }
    wasAsking.current = asking;
  }, [asking]);

  const requestClose = () => {
    if (changed.current) {
      setAsking(true);
    } else {
      onClose();
    }
  };

  const noteChoice = (event: SyntheticEvent) => {
    if (event.target instanceof Element && event.target.closest(CHOICE)) {
      changed.current = true;
    }
  };

  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === "Escape") {
      event.stopPropagation();
      // A second Escape answers the question with the safe choice: keep editing.
      if (asking) {
        setAsking(false);
      } else {
        requestClose();
      }
      return;
    }
    if (event.key !== "Tab" || !panel.current) {
      return;
    }
    // Keep Tab inside the dialog: wrap from the last control to the first and back.
    const controls = [...panel.current.querySelectorAll<HTMLElement>(FOCUSABLE)];
    const first = controls[0];
    const last = controls[controls.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last?.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first?.focus();
    }
  };

  const onBackdropClick = (event: MouseEvent) => {
    if (event.target === event.currentTarget && pressedOutside.current) {
      requestClose();
    }
  };

  return (
    <div
      data-testid="modal-backdrop"
      onMouseDown={(event) => {
        pressedOutside.current = event.target === event.currentTarget;
      }}
      onClick={onBackdropClick}
      className={`fixed inset-0 z-30 flex items-end justify-center bg-black/50 sm:items-center sm:p-4 ${
        drawer ? "lg:items-stretch lg:justify-end lg:p-0" : ""
      }`}
    >
      <div
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        onKeyDown={onKeyDown}
        onChangeCapture={() => {
          changed.current = true;
        }}
        onClickCapture={noteChoice}
        className={`max-h-dvh w-full overflow-y-auto rounded-t-card bg-surface p-5 sm:max-w-xl sm:rounded-card sm:p-6 ${
          drawer ? "lg:max-w-lg lg:rounded-none" : ""
        }`}
      >
        <div className="mb-4 flex items-start justify-between gap-3">
          <h2 id={titleId} className="text-xl font-bold">
            {title}
          </h2>
          <button
            type="button"
            onClick={requestClose}
            aria-label="Close"
            className="-mt-2 -mr-2 flex size-11 shrink-0 items-center justify-center rounded-lg text-muted"
          >
            <X aria-hidden className="size-5" />
          </button>
        </div>
        {asking && (
          // Stays in view however far the form is scrolled.
          <div role="alert" className="sticky top-0 z-10 mb-4 rounded-lg border border-line bg-bg p-4 shadow-lg">
            <p className="font-semibold">You have changes that aren't saved.</p>
            <p className="text-muted">Close anyway and they are left out.</p>
            <div className="mt-3 flex flex-col gap-2 sm:flex-row">
              <button ref={keepEditing} type="button" onClick={() => setAsking(false)} className={BUTTON_PRIMARY}>
                Keep editing
              </button>
              <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
                Close without saving
              </button>
            </div>
          </div>
        )}
        {children}
      </div>
    </div>
  );
}
