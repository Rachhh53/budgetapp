import { Pencil } from "lucide-react";

interface EditButtonProps {
  /** What it opens, for screen readers and the tooltip: "Edit Electric". */
  name: string;
  onClick: () => void;
  /** Show the word "Edit" beside the pencil. Leave off only where a row has no room for it. */
  labelled?: boolean;
}

/**
 * The visible way to open something for editing. Anything that can be opened and changed
 * shows one of these, so nobody has to discover that a name is clickable.
 */
export function EditButton({ name, onClick, labelled = false }: EditButtonProps) {
  return (
    <button
      type="button"
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
