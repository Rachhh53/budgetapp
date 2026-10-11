import { CornerLeftUp, Folder, FolderOpen, FolderPlus } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import { useCreateFolder, useFolders } from "../../api/backups";
import { Modal } from "../Modal";
import { BUTTON_LINK, BUTTON_PRIMARY, BUTTON_SECONDARY, INPUT, LABEL } from "../ui";

/**
 * "Choose folder…": walks this PC's folders a level at a time and picks one for backups.
 *
 * The app's window is a web page talking to the backend, so there is no Windows folder
 * dialog to open; this is the same idea, built from the folder names the backend lists
 * (decided 2026-10-11). Nothing is typed but the name of a new folder.
 */
export function FolderBrowserDialog({
  startAt,
  saving,
  error,
  onChoose,
  onClose,
}: {
  /** The folder to open on; null opens at the top, on the drives. */
  startAt: string | null;
  saving: boolean;
  error?: string;
  onChoose: (path: string) => void;
  onClose: () => void;
}) {
  const [path, setPath] = useState(startAt);
  const [naming, setNaming] = useState(false);
  const listing = useFolders(path);
  const here = listing.data;
  const atTop = path === null;

  const open = (next: string | null) => {
    setNaming(false);
    setPath(next);
  };

  return (
    <Modal title="Choose a backup folder" icon={FolderOpen} onClose={onClose}>
      <div className="space-y-3">
        <div>
          <p className="text-sm font-medium text-muted">{atTop ? "Start with" : "Looking in"}</p>
          <p className="font-mono text-sm break-all">{atTop ? "This PC" : path}</p>
        </div>

        {/* A fixed height, so the dialog is the same size in every folder; a long list scrolls inside it. */}
        <div className="h-48 overflow-y-auto rounded-lg border border-line" aria-busy={listing.isPending}>
          {listing.isPending && <p className="p-3 text-muted">Opening…</p>}
          {listing.isError && (
            <p role="alert" className="p-3 text-danger">
              {listing.error.message}
            </p>
          )}
          {here && (
            <ul aria-label="Folders">
              {!atTop && (
                <li>
                  <button type="button" onClick={() => open(here.parent)} className={ROW}>
                    <CornerLeftUp aria-hidden className="size-4 shrink-0 text-muted" />
                    Up a level
                  </button>
                </li>
              )}
              {here.folders.map((folder) => (
                <li key={folder.path}>
                  <button type="button" onClick={() => open(folder.path)} className={ROW}>
                    <Folder aria-hidden className="size-4 shrink-0 text-accent-ink" />
                    <span className="min-w-0 break-all">{folder.name}</span>
                  </button>
                </li>
              ))}
              {here.folders.length === 0 && <li className="p-3 text-muted">No folders in here. This one can still be chosen.</li>}
            </ul>
          )}
        </div>

        {!atTop && here && !here.writable && (
          <p className="text-sm text-muted">Budget can't save files in this folder. Please open another one.</p>
        )}
        {atTop && <p className="text-sm text-muted">Open a drive or folder to choose it.</p>}

        {!atTop && here?.writable && path !== null && (
          naming ? (
            <NewFolder parent={path} onMade={open} onCancel={() => setNaming(false)} />
          ) : (
            <button type="button" onClick={() => setNaming(true)} className={`${BUTTON_LINK} flex items-center gap-2 px-0`}>
              <FolderPlus aria-hidden className="size-4 shrink-0" />
              New folder in here
            </button>
          )
        )}

        {error && (
          <p role="alert" className="text-danger">
            {error}
          </p>
        )}
        <div className="flex flex-col gap-2 sm:flex-row sm:justify-end">
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button
            type="button"
            disabled={atTop || !here?.writable || saving}
            onClick={() => path !== null && onChoose(path)}
            className={BUTTON_PRIMARY}
          >
            {saving ? "Saving…" : "Save backups here"}
          </button>
        </div>
      </div>
    </Modal>
  );
}

/* One folder in the list: the whole row opens it; 44px tall for a finger. */
const ROW = "flex min-h-11 w-full cursor-pointer items-center gap-3 px-3 text-left hover:bg-bg/60";

/** Names a new folder inside `parent`, makes it, and opens it. */
function NewFolder({ parent, onMade, onCancel }: { parent: string; onMade: (path: string) => void; onCancel: () => void }) {
  const id = useId();
  const [name, setName] = useState("Budget backups");
  const create = useCreateFolder();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    // The dialog is inside the Settings page, not a form of its own: keep Enter from reaching anything else.
    event.stopPropagation();
    if (name.trim() !== "") {
      create.mutate({ parent, name }, { onSuccess: (made) => made.path !== null && onMade(made.path) });
    }
  };

  return (
    <form onSubmit={submit} noValidate>
      <label htmlFor={id} className={LABEL}>
        Name of the new folder
      </label>
      <div className="flex flex-col gap-2 sm:flex-row">
        <input
          id={id}
          autoFocus
          maxLength={100}
          value={name}
          onChange={(event) => setName(event.target.value)}
          className={INPUT}
        />
        <button type="submit" disabled={create.isPending || name.trim() === ""} className={`${BUTTON_SECONDARY} shrink-0`}>
          {create.isPending ? "Making it…" : "Make folder"}
        </button>
        <button type="button" onClick={onCancel} className={`${BUTTON_LINK} shrink-0`}>
          Never mind
        </button>
      </div>
      {create.isError && (
        <p role="alert" className="mt-1 text-danger">
          {create.error.message}
        </p>
      )}
    </form>
  );
}
