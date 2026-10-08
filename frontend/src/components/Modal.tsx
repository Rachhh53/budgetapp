import { X } from "lucide-react";
import { type KeyboardEvent, type ReactNode, useEffect, useId, useRef } from "react";

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled])';

/**
 * A dialog over the page, used for every add/edit form.
 *
 * On a phone it is a sheet that fills the width and rises from the bottom; from `sm` up it
 * is a centered panel. It never has a fixed width, and scrolls inside itself when the form
 * is taller than the screen. Escape closes it, Tab stays inside it, and focus goes back to
 * whatever opened it.
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

  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    // Start on the first field, so the form can be filled in straight away.
    const first = panel.current?.querySelector<HTMLElement>("[data-autofocus], input, select, textarea");
    (first ?? panel.current)?.focus();
    return () => opener?.focus();
  }, []);

  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === "Escape") {
      event.stopPropagation();
      onClose();
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

  return (
    <div
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
            onClick={onClose}
            aria-label="Close"
            className="-mt-2 -mr-2 flex size-11 shrink-0 items-center justify-center rounded-lg text-muted"
          >
            <X aria-hidden className="size-5" />
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}
