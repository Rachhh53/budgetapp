import { Paperclip } from "lucide-react";
import type { AttachmentOwner } from "../../api/attachments";
import { Modal } from "../Modal";
import { BUTTON_PRIMARY } from "../ui";
import { Receipts } from "./Receipts";

/**
 * The receipts and documents of one thing, in a dialog: for a card statement, which has no
 * page of its own to keep them on. Everything in it saves as it is done, so it only closes.
 */
export function ReceiptsDialog({ title, owner, onClose }: { title: string; owner: AttachmentOwner; onClose: () => void }) {
  return (
    <Modal title={title} icon={Paperclip} onClose={onClose}>
      <div className="space-y-4">
        <Receipts owner={owner} compact />
        <div className="flex justify-end">
          <button type="button" onClick={onClose} className={BUTTON_PRIMARY}>
            Done
          </button>
        </div>
      </div>
    </Modal>
  );
}

/** "Files", with how many there are once there are any: the way in to a row's receipts. */
export function FilesButton({ count, name, onClick }: { count: number; name: string; onClick: () => void }) {
  const label = count === 0 ? `Add a file to ${name}` : `${count === 1 ? "1 file" : `${count} files`} on ${name}`;
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      title={label}
      className="inline-flex min-h-11 items-center gap-1 rounded-lg px-2 font-semibold text-accent-ink"
    >
      <Paperclip aria-hidden className="size-4" />
      {count === 0 ? "Add file" : count === 1 ? "1 file" : `${count} files`}
    </button>
  );
}
