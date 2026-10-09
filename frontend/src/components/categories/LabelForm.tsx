import { Tag } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import { ColorPicker } from "../ColorPicker";
import { Modal } from "../Modal";
import { BUTTON_DANGER_OUTLINE, BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

/** What the form hands back: a name, a color and (for a category) the category it sits under. */
export interface LabelValues {
  name: string;
  color: string;
  parentId: number | null;
}

/** The part of a save or delete request the form needs to show progress and problems. */
interface Request {
  isPending: boolean;
  error: Error | null;
}

interface LabelFormProps {
  /** What is being edited, in the user's words: "category" or "tag". */
  noun: "category" | "tag";
  /** The values to start from. `id` is set when changing something that exists. */
  initial: LabelValues & { id?: number };
  /** Colors already in use, most recent first, offered ahead of the presets. */
  usedColors: readonly string[];
  /** Categories only: the top-level categories it could sit under. Leave out for a tag. */
  parents?: readonly { id: number; name: string }[];
  saving: Request;
  onSave: (values: LabelValues) => void;
  /** Given when changing something that exists, so it can also be deleted. */
  deleting?: Request;
  onDelete?: () => void;
  onClose: () => void;
}

/**
 * Add or change a category or a tag: the two differ only in that a category can sit under
 * another one. The color dot on the Categories & tags screen opens this, with the color
 * picker inside it.
 */
export function LabelForm({ noun, initial, usedColors, parents, saving, onSave, deleting, onDelete, onClose }: LabelFormProps) {
  const id = useId();
  const [name, setName] = useState(initial.name);
  const [color, setColor] = useState(initial.color);
  const [parentId, setParentId] = useState(initial.parentId);
  const existing = initial.id !== undefined;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    onSave({ name, color, parentId });
  };

  return (
    <Modal title={existing ? `Edit ${initial.name}` : `Add a ${noun}`} icon={Tag} tone={existing ? initial.color : undefined} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <div>
          <label htmlFor={`${id}-name`} className={LABEL}>
            Name
          </label>
          <input
            id={`${id}-name`}
            value={name}
            onChange={(event) => setName(event.target.value)}
            maxLength={60}
            required
            autoComplete="off"
            className={INPUT}
          />
        </div>

        {parents && (
          <div>
            <label htmlFor={`${id}-parent`} className={LABEL}>
              Inside
            </label>
            <select
              id={`${id}-parent`}
              value={parentId ?? ""}
              onChange={(event) => setParentId(event.target.value === "" ? null : Number(event.target.value))}
              className={INPUT}
            >
              <option value="">Nothing — it's a main category</option>
              {parents.map((parent) => (
                <option key={parent.id} value={parent.id}>
                  {parent.name}
                </option>
              ))}
            </select>
            <p className={HINT}>Choose a main category to make this a sub-category of it, like Rent inside Housing.</p>
          </div>
        )}

        <ColorPicker label="Color" value={color} onChange={setColor} recent={usedColors} />

        {saving.error && (
          <p role="alert" className="text-danger">
            {saving.error.message}
          </p>
        )}

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="submit" disabled={saving.isPending} className={BUTTON_PRIMARY}>
            {saving.isPending ? "Saving…" : existing ? "Save changes" : `Add ${noun}`}
          </button>
        </div>
      </form>

      {deleting && onDelete && (
        <div className="mt-6 border-t border-line pt-4">
          {/* No "are you sure?": nothing else is lost with it, and Undo brings it back (spec §6). */}
          <button type="button" onClick={onDelete} disabled={deleting.isPending} className={BUTTON_DANGER_OUTLINE}>
            {deleting.isPending ? "Deleting…" : `Delete ${noun}`}
          </button>
          <p className={HINT}>Only a {noun} that nothing uses can be deleted, so no item loses its {noun}.</p>
          {deleting.error && (
            <p role="alert" className="mt-2 text-danger">
              {deleting.error.message}
            </p>
          )}
        </div>
      )}
    </Modal>
  );
}
