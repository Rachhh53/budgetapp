import { type LucideIcon, X } from "lucide-react";
import { type KeyboardEvent, type MouseEvent, type ReactNode, type SyntheticEvent, useEffect, useId, useRef, useState } from "react";
import { isHexColor, readableTextOn } from "../lib/color";
import { BUTTON_PRIMARY, BUTTON_SECONDARY } from "./ui";

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled])';

/** Choices that are not form fields, so they raise no change event: a picker's options and a color swatch. */
const CHOICE = '[role="option"], [role="group"] button';

/**
 * A dialog over the page, used for every add/edit form.
 *
 * On a phone it is a sheet that fills the width and rises from the bottom; from `sm` up it
 * is a centered panel. It never has a fixed width, and scrolls inside itself when the form
 * is taller than the screen, stopping short of the top and bottom edges. Tab stays inside it, and focus goes back to whatever opened it.
 *
 * It closes on Escape, on the X, and on a click anywhere outside it. If something in it was
 * changed and not saved, those ask first, so a stray click never throws work away. (A form's
 * own Cancel button is a deliberate "discard" and does not ask.)
 *
 * The header is a tinted strip with an icon for what the form is about, so one form is told
 * from another at a glance. `tone` gives the strip the color of the account or bucket being
 * edited; without it the strip takes the accent color. The title itself stays in the normal
 * text color on a light tint, so it is readable whatever color someone picked.
 */
export function Modal({
  title,
  onClose,
  icon: Icon,
  tone,
  children,
}: {
  title: string;
  onClose: () => void;
  /** A picture of what this form adds or changes. */
  icon?: LucideIcon;
  /** A hex color for the header, e.g. the color of the account being edited. */
  tone?: string;
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
  const color = tone !== undefined && isHexColor(tone) ? tone : "var(--accent)";
  const onColor = tone !== undefined && isHexColor(tone) ? readableTextOn(tone) : "var(--on-accent)";

  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    // Start on the first field, so the form can be filled in straight away.
    // A control marked as the place to start wins wherever it sits (the safe answer to a
    // question that asks first, say); otherwise the first field.
    const first =
      panel.current?.querySelector<HTMLElement>("[data-autofocus]") ??
      panel.current?.querySelector<HTMLElement>("input, select, textarea");
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
      className="fixed inset-0 z-30 flex items-end justify-center bg-black/50 sm:items-center sm:p-4"
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
        // Never the full height: a strip of the page stays in view above and below, so it reads as a dialog over the page.
        // The strip is kept thin, because a form has to fit a 768px-high laptop screen without scrolling.
        className="max-h-[calc(100dvh-2rem)] w-full overflow-y-auto sm:max-h-[calc(100dvh-3rem)] rounded-t-card bg-surface p-5 sm:max-w-xl sm:rounded-card sm:p-6"
      >
        <div
          // Reaches the panel's edges through its padding; a light tint, so the title keeps its contrast.
          className="-mx-5 -mt-5 mb-4 flex items-center justify-between gap-3 border-t-4 px-5 py-3 sm:-mx-6 sm:-mt-6 sm:px-6"
          style={{ borderTopColor: color, backgroundColor: `color-mix(in srgb, ${color} 12%, var(--surface))` }}
        >
          <div className="flex min-w-0 items-center gap-3">
            {Icon && (
              <span
                aria-hidden
                className="flex size-10 shrink-0 items-center justify-center rounded-full"
                style={{ backgroundColor: color, color: onColor }}
              >
                <Icon className="size-5" />
              </span>
            )}
            <h2 id={titleId} className="min-w-0 text-xl font-bold break-words">
              {title}
            </h2>
          </div>
          <button
            type="button"
            onClick={requestClose}
            aria-label="Close"
            className="-mr-2 flex size-11 shrink-0 items-center justify-center rounded-lg text-ink"
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
