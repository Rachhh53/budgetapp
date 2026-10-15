import { Info } from "lucide-react";
import { type ReactNode, useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import { type TermId, TERMS } from "../../help/terms";

/** Never wider than this, and never closer to a screen edge than the page's own gutter. */
const WIDTH = 288;
const GUTTER = 16;

/**
 * An ⓘ beside a word that says what it means (`help/terms.ts`). It opens on a click or tap and
 * from the keyboard, unlike a hover `title`, which a phone never shows; Escape, a click elsewhere
 * or scrolling closes it. It is placed against the screen, not its card, so a card that clips
 * what overflows it (or the edge of a phone) never cuts it off. `children` adds more under the meaning,
 * such as what a figure is made of.
 */
export function InfoTip({ term, className = "", children }: { term: TermId; className?: string; children?: ReactNode }) {
  const { term: name, about } = TERMS[term];
  const id = useId();
  const button = useRef<HTMLButtonElement>(null);
  const tip = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const [place, setPlace] = useState<{ left: number; top: number; above: boolean } | null>(null);

  useLayoutEffect(() => {
    if (!open || !button.current) {
      setPlace(null);
      return;
    }
    const at = button.current.getBoundingClientRect();
    const width = Math.min(WIDTH, window.innerWidth - GUTTER * 2);
    const left = Math.min(Math.max(at.left + at.width / 2 - width / 2, GUTTER), window.innerWidth - GUTTER - width);
    const height = tip.current?.offsetHeight ?? 0;
    // Below the ⓘ, unless that runs off the bottom and there is room above.
    const above = at.bottom + 8 + height > window.innerHeight && at.top - 8 - height > 0;
    setPlace({ left, top: above ? at.top - 8 - height : at.bottom + 8, above });
  }, [open]);

  useEffect(() => {
    if (!open) {
      return;
    }
    const close = () => setOpen(false);
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        // Only the tip closes, not a dialog it sits in.
        event.stopPropagation();
        setOpen(false);
        button.current?.focus();
      }
    };
    const onPointer = (event: PointerEvent) => {
      if (!(event.target instanceof Node) || (!button.current?.contains(event.target) && !tip.current?.contains(event.target))) {
        setOpen(false);
      }
    };
    window.addEventListener("keydown", onKey, true);
    window.addEventListener("pointerdown", onPointer);
    window.addEventListener("scroll", close, true);
    window.addEventListener("resize", close);
    return () => {
      window.removeEventListener("keydown", onKey, true);
      window.removeEventListener("pointerdown", onPointer);
      window.removeEventListener("scroll", close, true);
      window.removeEventListener("resize", close);
    };
  }, [open]);

  return (
    <>
      <button
        ref={button}
        type="button"
        aria-label={`What "${name}" means`}
        aria-expanded={open}
        aria-controls={open ? id : undefined}
        onClick={(event) => {
          // A tip inside a row that opens on a click shows the tip, not the row.
          event.stopPropagation();
          setOpen((was) => !was);
        }}
        // Small to look at, but a full-size target: the padding is pulled back with a negative margin.
        className={`-m-[15px] inline-flex size-11 shrink-0 cursor-help items-center justify-center rounded-full align-middle text-accent-ink hover:text-ink focus-visible:outline-2 focus-visible:outline-accent-ink ${className}`}
      >
        <Info aria-hidden className="size-3.5" />
      </button>
      {open && (
        <div
          ref={tip}
          id={id}
          role="note"
          aria-label={name}
          onClick={(event) => event.stopPropagation()}
          style={{
            left: place?.left ?? 0,
            top: place?.top ?? 0,
            width: Math.min(WIDTH, window.innerWidth - GUTTER * 2),
            visibility: place ? "visible" : "hidden",
          }}
          // Solid accent with its own text color, like the main buttons, so it stands apart from the cards it
          // opens over in light and dark alike (Rachel, 2026-10-14: "tooltips blend in too much").
          className="fixed z-50 rounded-lg bg-accent p-3 text-left text-sm font-normal text-on-accent normal-case shadow-xl shadow-black/40"
        >
          <p className="font-semibold">{name}</p>
          <p className="mt-1">{about}</p>
          {children && <div className="mt-2">{children}</div>}
        </div>
      )}
    </>
  );
}
