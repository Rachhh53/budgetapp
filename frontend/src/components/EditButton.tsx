import { Pencil } from "lucide-react";
import { BUTTON_SECONDARY } from "./ui";

interface EditButtonProps {
  /** What it opens, for screen readers and the tooltip: "Edit Electric". */
  name: string;
  onClick: () => void;
}

/**
 * The Edit button at the top of a detail page (an account, an asset, an income source): the
 * same pencil and the same shape on every one, saying what it edits ("Edit account"). The rows
 * of an overview only open the page; editing starts here.
 */
export function EditPageButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} className={`${BUTTON_SECONDARY} inline-flex items-center justify-center gap-2`}>
      <Pencil aria-hidden className="size-4" />
      {label}
    </button>
  );
}

/**
 * The visible way to open a card, or a line on one, for editing.
 *
 * A row in a list has no pencil (see `EditName`): every list row in the app opens the same way,
 * by clicking or tapping it, whether that goes to a page or opens a form.
 *
 * Always the pencil alone, on a row or on a card: what it edits is in its tooltip and its name
 * for screen readers. Only the button at the top of a detail page spells it out (`EditPageButton`).
 */
export function EditButton({ name, onClick }: EditButtonProps) {
  return (
    <button
      type="button"
      // Marks it as the row's way in, so a click anywhere on the row can be handed to it (see rowEdit).
      data-edit
      onClick={onClick}
      aria-label={`Edit ${name}`}
      title={`Edit ${name}`}
      // Full touch size on a phone; smaller on a wide screen, where rows are kept short and a pointer is exact.
      className="inline-flex size-11 shrink-0 items-center justify-center rounded-md text-accent-ink lg:-my-0.5 lg:size-6"
    >
      <Pencil aria-hidden className="size-4" />
    </button>
  );
}

/**
 * A list row's name as its way in, for a row that opens a form in place (a pay period's rows).
 * It looks like the name it is: the row shows it can be opened the way every list row does, with
 * the highlight and the hand cursor, and a click anywhere on the row is handed to this button
 * (see rowEdit). Being a real button, it is also how the keyboard and a screen reader open the row.
 */
export function EditName({ name, onClick }: EditButtonProps) {
  return (
    <button
      type="button"
      data-edit
      onClick={onClick}
      aria-label={`Edit ${name}`}
      title={`Edit ${name}`}
      className="cursor-pointer rounded-sm text-left font-medium wrap-break-word"
    >
      {name}
    </button>
  );
}
