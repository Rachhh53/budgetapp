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
 * A list row that opens its own page (an account, an asset, an income source). The hand cursor
 * and the highlight are set outright, since inside the desktop shell a link shows neither by
 * itself, and they are what says the row can be opened.
 */
export const ROW_LINK =
  "-mx-2 flex min-h-11 min-w-0 flex-1 cursor-pointer items-center gap-3 rounded-lg px-2 py-3 hover:bg-bg/60";
