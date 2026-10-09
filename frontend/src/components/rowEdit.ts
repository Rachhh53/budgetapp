import type { MouseEvent } from "react";

/** Things in a row that already do something of their own when clicked. */
const OWN_ACTION = 'button, a, input, select, textarea, label, [role="option"]';

/**
 * Lets a row be clicked anywhere to open it for editing: the click is handed to the row's own
 * Edit button (`EditButton`), so there is one action with two ways to reach it. A click on
 * something that has its own job (an amount, a checkbox, a link) is left to it. The Edit
 * button stays the way in for the keyboard and for screen readers.
 */
export function clickRowToEdit(event: MouseEvent<HTMLElement>) {
  if (event.target instanceof Element && event.target.closest(OWN_ACTION)) {
    return;
  }
  event.currentTarget.querySelector<HTMLElement>("[data-edit]")?.click();
}

/** Shows a row can be clicked, only when it has an Edit button to hand the click to. */
export const ROW_OPENS = "has-[[data-edit]]:cursor-pointer has-[[data-edit]]:hover:bg-bg/60";
