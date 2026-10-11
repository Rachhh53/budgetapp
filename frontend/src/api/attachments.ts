import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import { PAY_KEY } from "./income";

/** A receipt or document attached to something. */
export interface Attachment {
  id: number;
  /** What the file was called when it was attached. */
  name: string;
  mime: "application/pdf" | "image/jpeg" | "image/png";
  sizeBytes: number;
  /** How many pages a PDF has; `null` for a picture. */
  pages: number | null;
}

export interface Attached {
  attachments: Attachment[];
  /** The largest file that can be attached, so the screen can say so before sending one. */
  maxBytes: number;
  maxFiles: number;
}

/** What a file is attached to. */
export type AttachmentOwner =
  | { type: "entry"; entryId: number }
  /** One time a recurring item came due, named by the date its schedule gives it. */
  | { type: "occurrence"; itemId: number; scheduledDate: string }
  | { type: "statement"; cardId: number; statementId: number };

/** Which bills and statements have files, so their rows can say so. */
export interface AttachmentCounts {
  occurrences: { itemId: number; scheduledDate: string; count: number }[];
  statements: { statementId: number; count: number }[];
}

/** Until the backend has said otherwise: its own default. */
export const DEFAULT_MAX_BYTES = 25 * 1024 * 1024;

/** The kinds of file the backend keeps, as a file chooser's `accept`. */
export const ACCEPTED_FILES = "application/pdf,image/jpeg,image/png,.pdf,.jpg,.jpeg,.png";

/** Under the pay key: an entry's file count, and a report's, follow what is attached. */
export const ATTACHMENTS_KEY = [...PAY_KEY, "attachments"] as const;

export function attachmentsPath(owner: AttachmentOwner): string {
  switch (owner.type) {
    case "entry":
      return `/api/entries/${owner.entryId}/attachments`;
    case "occurrence":
      return `/api/items/${owner.itemId}/occurrences/${owner.scheduledDate}/attachments`;
    case "statement":
      return `/api/cards/${owner.cardId}/statements/${owner.statementId}/attachments`;
  }
}

/** Where the picture of an attachment is: the picture itself, or one page of a PDF. */
export function previewUrl(attachment: Attachment, page = 1): string {
  return `/api/attachments/${attachment.id}/preview${attachment.pages === null ? "" : `?page=${page}`}`;
}

export function fileUrl(attachment: Attachment): string {
  return `/api/attachments/${attachment.id}/file`;
}

export function useAttachments(owner: AttachmentOwner) {
  const path = attachmentsPath(owner);
  return useQuery({ queryKey: [...ATTACHMENTS_KEY, path], queryFn: () => api.get<Attached>(path) });
}

export function useAttachmentCounts(from: string, to: string) {
  return useQuery({
    queryKey: [...ATTACHMENTS_KEY, "counts", from, to],
    queryFn: () => api.get<AttachmentCounts>(`/api/attachment-counts?from=${from}&to=${to}`),
  });
}

/** Attaches files to `owner`. All of them are kept, or none. */
export function useAttach(owner: AttachmentOwner) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (files: readonly File[]) => api.upload<Attached>(attachmentsPath(owner), files),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PAY_KEY }),
  });
}

/** Attaches files to an entry not known until the moment of sending: one that was only just added. */
export function useAttachToEntry() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ entryId, files }: { entryId: number; files: readonly File[] }) =>
      api.upload<Attached>(attachmentsPath({ type: "entry", entryId }), files),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PAY_KEY }),
  });
}

/** Takes a file off what it is attached to. Undo puts it back. */
export function useRemoveAttachment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.delete<Attached>(`/api/attachments/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PAY_KEY }),
  });
}

/** "182 KB", "1.4 MB": a file's size the way a file manager writes it. */
export function formatSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} bytes`;
  }
  if (bytes < 1024 * 1024) {
    return `${Math.round(bytes / 1024)} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * Looks over files before any are sent, so a problem is said at once and in words.
 * Returns what is wrong, or `null` when they can all go.
 */
export function problemWith(files: readonly File[], already: number, maxBytes: number, maxFiles: number): string | null {
  if (already + files.length > maxFiles) {
    return `That would be more than ${maxFiles} files here. Please remove one you no longer need first.`;
  }
  for (const file of files) {
    const known = /\.(pdf|jpe?g|png)$/i.test(file.name) || ["application/pdf", "image/jpeg", "image/png"].includes(file.type);
    if (!known) {
      return `Budget can keep PDFs and JPEG or PNG pictures. Please save ${file.name} as one of those and try again.`;
    }
    if (file.size > maxBytes) {
      return `${file.name} is larger than ${Math.floor(maxBytes / (1024 * 1024))} MB. Please choose a smaller one, or save it at a lower quality.`;
    }
  }
  return null;
}
