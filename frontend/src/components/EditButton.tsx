import { Pencil } from "lucide-react";
import { BUTTON_SECONDARY } from "./ui";

interface EditButtonProps {
  /** What it opens, for screen readers and the tooltip: "Edit Electric". */
  name: string;
  onClick: () => void;
  /** Show the word "Edit" beside the pencil. Left off on list rows (Recurring items, the pay period), where the pencil alone is enough. */
  labelled?: boolean;
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
 * The visible way to open something for editing. Anything that can be opened and changed
 * shows one of these, so nobody has to discover that a name is clickable.
 */
export function EditButton({ name, onClick, labelled = false }: EditButtonProps) {
  return (
    <button
      type="button"
      // Marks it as the row's way in, so a click anywhere on the row can be handed to it (see rowEdit).
      data-edit
      onClick={onClick}
      aria-label={`Edit ${name}`}
      title={`Edit ${name}`}
      className={
        labelled
          ? "inline-flex min-h-11 shrink-0 items-center gap-1.5 rounded-lg px-2 font-semibold text-accent-ink"
          : // Full touch size on a phone; smaller on a wide screen, where rows are kept short and a pointer is exact.
            "inline-flex size-11 shrink-0 items-center justify-center rounded-md text-accent-ink lg:-my-0.5 lg:size-6"
      }
    >
      <Pencil aria-hidden className="size-4" />
      {labelled && <span>Edit</span>}
    </button>
  );
}
