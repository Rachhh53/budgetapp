/**
 * Class names shared by every form and button, so they stay consistent and every touch
 * target is at least 44px tall (min-h-11). Buttons are full-width on a phone and shrink
 * to their label from `sm` up.
 */
const BUTTON = "min-h-11 rounded-lg px-4 font-semibold disabled:opacity-60 w-full sm:w-auto";

export const BUTTON_PRIMARY = `${BUTTON} bg-accent text-on-accent`;
export const BUTTON_SECONDARY = `${BUTTON} border border-line bg-surface`;
export const BUTTON_DANGER = `${BUTTON} bg-danger text-surface`;
export const BUTTON_DANGER_OUTLINE = `${BUTTON} border border-danger text-danger`;

/** A quiet text-style button for small actions inside a row. */
export const BUTTON_LINK = "min-h-11 rounded-lg px-2 font-semibold text-accent-ink disabled:opacity-60";

export const INPUT = "min-h-11 w-full rounded-lg border border-line bg-surface px-3";
export const LABEL = "mb-1 block font-medium";
export const HINT = "mt-1 text-sm text-muted";

/** Page title, shared by every screen. */
export const PAGE_TITLE = "text-2xl font-bold md:text-3xl";

/**
 * The top of a detail page (an account, an asset, an income source, a recurring item): the way
 * back, the name and the page's buttons. From `sm` up it stays put while the page scrolls under
 * it, so the name and the way back are always in view. On a phone it scrolls away with the page:
 * there its buttons stack, and frozen it would take a quarter of the screen.
 *
 * The page scrolls inside `main`, which has padding, and a sticky element is held against the
 * inside of that padding, not the edge of the scroller. So each size carries the padding three
 * times, and all three must match `main` in `AppShell` (p-4, md:p-8, md:short:p-5): a negative
 * margin lifts the header over the padding, its own padding puts the words back where they were,
 * and a negative `top` of the same size holds it flush with the top once the page scrolls. Without
 * that last one it hangs a little way down, with the page showing above it.
 */
export const DETAIL_HEADER =
  "mb-4 flex flex-col gap-3 sm:sticky sm:-top-4 sm:z-10 sm:-mx-4 sm:-mt-4 sm:flex-row sm:items-end sm:justify-between sm:border-b sm:border-line sm:bg-bg sm:px-4 sm:pt-4 sm:pb-3 md:-top-8 md:-mx-8 md:-mt-8 md:mb-6 md:px-8 md:pt-8 md:short:-top-5 md:short:-mx-5 md:short:-mt-5 md:short:px-5 md:short:pt-5";

/**
 * A list row that opens its own page (an account, an asset, an income source). The hand cursor
 * and the highlight are set outright, since inside the desktop shell a link shows neither by
 * itself, and they are what says the row can be opened.
 */
export const ROW_LINK =
  "-mx-2 flex min-h-11 min-w-0 flex-1 cursor-pointer items-center gap-3 rounded-lg px-2 py-3 hover:bg-bg/60";

/**
 * Every chart's hover box: the card's surface with the page's own text color for the heading and
 * every line. Left to itself, recharts writes each line in the series' color or near-black, which
 * was unreadable on the dark theme's surface (Rachel, 2026-10-20).
 */
export const CHART_TOOLTIP = {
  contentStyle: { backgroundColor: "var(--surface)", borderColor: "var(--border)", borderRadius: 8, color: "var(--text)" },
  labelStyle: { color: "var(--text)", fontWeight: 600 },
  itemStyle: { color: "var(--text)" },
} as const;
