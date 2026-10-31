import { Tags } from "lucide-react";
import { type FormEvent, useState } from "react";
import type { Category, Tag } from "../../api/categories";
import { type StatementLine, useFileStatementLines } from "../../api/statementUploads";
import { formatCents } from "../../lib/money";
import { CategoryField, TagsField } from "../categories/LabelFields";
import { Modal } from "../Modal";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, HINT } from "../ui";

/**
 * One category and the same tags for every ticked charge (Rachel, 2026-10-30: "I can't just mass categorize and tag
 * them"). It starts from what they already share, so a category they all have stays unless changed; each payee
 * remembers the choice for the next statement, as one charge's own form does. Save and add as one-offs also adds them all
 * as one-offs on Apply (Rachel, 2026-10-30: "when I select multiple to add categorie and tags I should also be able to
 * then add them all as one offs"), taking them off any budget or match, as Add as a one-off does for one.
 */
export function FilingDialog({
  uploadId,
  lines,
  categories,
  tags,
  onClose,
}: {
  uploadId: number;
  lines: readonly StatementLine[];
  categories: readonly Category[];
  tags: readonly Tag[];
  onClose: (done: boolean) => void;
}) {
  const file = useFileStatementLines(uploadId);
  const first = lines[0];
  const [categoryId, setCategoryId] = useState<number | null>(
    first !== undefined && lines.every((line) => line.categoryId === first.categoryId) ? first.categoryId : null,
  );
  const [tagIds, setTagIds] = useState<number[]>(first === undefined ? [] : first.tagIds.filter((tagId) => lines.every((line) => line.tagIds.includes(tagId))));
  const total = lines.reduce((sum, line) => sum + line.amountCents, 0);
  const save = (oneOffs: boolean) =>
    file.mutate({ lineIds: lines.map((line) => line.id), categoryId, tagIds, oneOffs }, { onSuccess: () => onClose(true) });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    save(false);
  };
  return (
    <Modal title={`${lines.length} ${lines.length === 1 ? "charge" : "charges"} · ${formatCents(total)}`} icon={Tags} onClose={() => onClose(false)}>
      <form onSubmit={submit} className="space-y-3">
        <CategoryField categories={categories} value={categoryId} onChange={setCategoryId} />
        <TagsField tags={tags} value={tagIds} onChange={setTagIds} />
        <p className={HINT}>Every ticked charge gets exactly these, in place of what it had. Remembered for each payee for the next statement.</p>
        {file.error && (
          <p role="alert" className="text-danger">
            {file.error.message}
          </p>
        )}
        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <button type="button" onClick={() => onClose(false)} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="button" disabled={file.isPending} onClick={() => save(true)} className={BUTTON_SECONDARY}>
            Save and add as one-offs
          </button>
          <button type="submit" disabled={file.isPending} className={BUTTON_PRIMARY}>
            {file.isPending ? "Saving…" : "Save"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
