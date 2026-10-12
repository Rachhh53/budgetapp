import { ChevronLeft, ChevronRight, Download, FileText, Image, Paperclip, Trash2, Upload } from "lucide-react";
import { type DragEvent, useId, useState } from "react";
import {
  ACCEPTED_FILES,
  type AttachmentOwner,
  DEFAULT_MAX_BYTES,
  formatSize,
  previewUrl,
  problemWith,
  useAttach,
  useAttachments,
  useRemoveAttachment,
  useSaveCopy,
} from "../../api/attachments";
import { savedMessage } from "../../api/client";
import { BUTTON_LINK } from "../ui";

/** Until the backend has said otherwise: its own limit on how many files one thing can have. */
const DEFAULT_MAX_FILES = 20;

/**
 * The receipts and documents attached to one thing (design/expenses.png): a preview of the
 * one chosen, the list, and a place to drop more. The same on an expense's page and in the
 * small dialogs for a bill and a card statement.
 *
 * Files go straight to the PC the app runs on and are never changed. Removing one takes it
 * off this thing at once, and Undo puts it back.
 *
 * @param compact for a dialog: a shorter preview and one file listed at a time, with a stepper
 *     through the rest, so everything stays on one screen however many there are
 */
export function Receipts({ owner, compact = false }: { owner: AttachmentOwner; compact?: boolean }) {
  const attached = useAttachments(owner);
  const attach = useAttach(owner);
  const remove = useRemoveAttachment();
  const saveCopy = useSaveCopy();
  const inputId = useId();
  const [chosenId, setChosenId] = useState<number>();
  const [problem, setProblem] = useState<string>();
  const [over, setOver] = useState(false);

  const files = attached.data?.attachments ?? [];
  // The one being looked at: the one picked, or the first until one is.
  const chosen = files.find((file) => file.id === chosenId) ?? files[0];
  const position = chosen ? files.indexOf(chosen) : 0;
  const listed = compact ? files.slice(position, position + 1) : files;

  const add = (picked: readonly File[]) => {
    if (picked.length === 0) {
      return;
    }
    const found = problemWith(
      picked,
      files.length,
      attached.data?.maxBytes ?? DEFAULT_MAX_BYTES,
      attached.data?.maxFiles ?? DEFAULT_MAX_FILES,
    );
    setProblem(found ?? undefined);
    if (found === null) {
      attach.mutate(picked, {
        // Show what was just added.
        onSuccess: (now) => setChosenId(now.attachments.at(-1)?.id),
      });
    }
  };

  const onDrop = (event: DragEvent) => {
    event.preventDefault();
    setOver(false);
    const dropped = [...event.dataTransfer.files];
    if (dropped.length === 0) {
      // Dragged out of a web page or another app, what arrives is a link to the document, not the
      // document. Budget never goes out to fetch anything, so say what will work instead.
      setProblem(
        "That came across as a link, not a file, so there was nothing to keep. Download it first, then drop it here from the browser's downloads list or from its folder, or choose it with browse.",
      );
      return;
    }
    add(dropped);
  };

  const failure = problem ?? attach.error?.message ?? remove.error?.message ?? saveCopy.error?.message;

  return (
    <div className="space-y-3">
      {attached.isPending && <p className="text-muted">Loading the files…</p>}
      {attached.isError && (
        <p role="alert" className="text-danger">
          {attached.error.message}
        </p>
      )}

      {chosen && (
        <FilePreview
          key={chosen.id}
          name={chosen.name}
          pages={chosen.pages}
          src={(page) => previewUrl(chosen, page)}
          size={compact ? "compact" : "full"}
        />
      )}

      {files.length > 0 && (
        <ul className="space-y-2">
          {listed.map((file) => (
            <li
              key={file.id}
              className={`flex items-center gap-1 rounded-lg border px-2 ${file.id === chosen?.id ? "border-accent bg-bg/60" : "border-line"}`}
            >
              <button
                type="button"
                onClick={() => setChosenId(file.id)}
                aria-pressed={file.id === chosen?.id}
                aria-label={`Show ${file.name}`}
                title={`Show ${file.name}`}
                className="flex min-h-11 min-w-0 flex-1 cursor-pointer items-center gap-2 text-left"
              >
                {file.pages === null ? (
                  <Image aria-hidden className="size-4 shrink-0 text-muted" />
                ) : (
                  <FileText aria-hidden className="size-4 shrink-0 text-muted" />
                )}
                <span className="min-w-0 flex-1 truncate font-medium">{file.name}</span>
                <span className="shrink-0 text-sm text-muted tabular-nums">{formatSize(file.sizeBytes)}</span>
              </button>
              <button
                type="button"
                onClick={() => saveCopy.mutate(file)}
                disabled={saveCopy.isPending}
                aria-label={`Save a copy of ${file.name}`}
                title={`Save a copy of ${file.name}`}
                className="inline-flex size-11 shrink-0 items-center justify-center rounded-md text-accent-ink disabled:opacity-60"
              >
                <Download aria-hidden className="size-4" />
              </button>
              <button
                type="button"
                onClick={() => remove.mutate(file.id)}
                disabled={remove.isPending}
                aria-label={`Remove ${file.name}`}
                title={`Remove ${file.name}`}
                className="inline-flex size-11 shrink-0 items-center justify-center rounded-md text-danger disabled:opacity-60"
              >
                <Trash2 aria-hidden className="size-4" />
              </button>
            </li>
          ))}
        </ul>
      )}
      {compact && files.length > 1 && (
        <div className="flex items-center justify-center gap-2">
          <button
            type="button"
            onClick={() => setChosenId(files[position - 1]?.id)}
            disabled={position === 0}
            aria-label="Previous file"
            className={`${BUTTON_LINK} inline-flex items-center disabled:opacity-40`}
          >
            <ChevronLeft aria-hidden className="size-5" />
          </button>
          <span aria-live="polite" className="text-sm text-muted tabular-nums">
            File {position + 1} of {files.length}
          </span>
          <button
            type="button"
            onClick={() => setChosenId(files[position + 1]?.id)}
            disabled={position === files.length - 1}
            aria-label="Next file"
            className={`${BUTTON_LINK} inline-flex items-center disabled:opacity-40`}
          >
            <ChevronRight aria-hidden className="size-5" />
          </button>
        </div>
      )}

      {attached.data && files.length === 0 && (
        <p className="flex items-center gap-2 text-muted">
          <Paperclip aria-hidden className="size-4 shrink-0" />
          Nothing attached yet. Add the receipt, the invoice, or the letter that came with it.
        </p>
      )}

      {/* A label, so the whole dashed box is the file chooser's button; dropping on it does the same. */}
      <label
        htmlFor={inputId}
        onDragOver={(event) => {
          event.preventDefault();
          setOver(true);
        }}
        onDragLeave={() => setOver(false)}
        onDrop={onDrop}
        className={`flex min-h-11 cursor-pointer items-center justify-center gap-2 rounded-lg border border-dashed px-3 py-2 text-center font-medium has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-accent-ink ${
          over ? "border-accent bg-bg" : "border-line"
        }`}
      >
        <Upload aria-hidden className="size-4 shrink-0" />
        {attach.isPending ? "Adding…" : "Drop files here or browse"}
        <input
          id={inputId}
          type="file"
          // Files are kept as they are added: a dialog this sits in has nothing unsaved because of it.
          data-saves-itself
          multiple
          accept={ACCEPTED_FILES}
          disabled={attach.isPending}
          onChange={(event) => {
            add([...(event.target.files ?? [])]);
            // So choosing the same file again (after removing it, say) is still a change.
            event.target.value = "";
          }}
          className="sr-only"
        />
      </label>

      {saveCopy.data && (
        <p role="status" className="font-medium wrap-anywhere">
          {savedMessage(saveCopy.data)}
        </p>
      )}
      {failure && (
        <p role="alert" className="text-danger">
          {failure}
        </p>
      )}
      <p className="text-sm text-muted">
        PDFs and JPEG or PNG pictures, copied into the app's folder on this PC and included in backups. A file you remove is
        deleted from this PC once Undo can no longer bring it back.
      </p>
    </div>
  );
}

/** How tall a preview may grow: short in a dialog, most of the window on a page, or the whole height of a column it has to itself. */
const PREVIEW_HEIGHTS = { compact: "max-h-[24dvh]", full: "max-h-[60dvh]", column: "max-h-[75dvh]" } as const;

/**
 * A kept file, drawn: a picture as it is, a PDF one page at a time. The backend draws each page,
 * so nothing inside the file is ever run by the window.
 *
 * @param pages how many pages a PDF has; `null` for a picture
 * @param src   where the picture of a page is
 */
export function FilePreview({
  name,
  pages: pageCount,
  src,
  size,
}: {
  name: string;
  pages: number | null;
  src: (page: number) => string;
  size: keyof typeof PREVIEW_HEIGHTS;
}) {
  const [page, setPage] = useState(1);
  const [failed, setFailed] = useState(false);
  const pages = pageCount ?? 1;

  return (
    <figure className="rounded-lg border border-line bg-bg/60 p-2">
      {failed ? (
        <p role="status" className="p-4 text-center text-muted">
          We couldn't show {name} here, but the file itself is safe. Save a copy to open it.
        </p>
      ) : (
        <img
          src={src(page)}
          alt={pages > 1 ? `${name}, page ${page} of ${pages}` : name}
          onError={() => setFailed(true)}
          // Shown whole, never cropped: a receipt's total is as likely at the bottom as the top.
          className={`mx-auto w-auto max-w-full rounded-md object-contain ${PREVIEW_HEIGHTS[size]}`}
        />
      )}
      {pages > 1 && (
        <figcaption className="mt-2 flex items-center justify-center gap-2">
          <button
            type="button"
            onClick={() => {
              setFailed(false);
              setPage(page - 1);
            }}
            disabled={page === 1}
            aria-label="Previous page"
            className={`${BUTTON_LINK} inline-flex items-center disabled:opacity-40`}
          >
            <ChevronLeft aria-hidden className="size-5" />
          </button>
          <span aria-live="polite" className="text-sm text-muted tabular-nums">
            Page {page} of {pages}
          </span>
          <button
            type="button"
            onClick={() => {
              setFailed(false);
              setPage(page + 1);
            }}
            disabled={page === pages}
            aria-label="Next page"
            className={`${BUTTON_LINK} inline-flex items-center disabled:opacity-40`}
          >
            <ChevronRight aria-hidden className="size-5" />
          </button>
        </figcaption>
      )}
    </figure>
  );
}
