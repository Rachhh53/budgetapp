import { ArchiveRestore, TriangleAlert } from "lucide-react";
import { type FormEvent, type ReactNode, useId, useState } from "react";
import { type Backup, useBackUpNow, useBackups, useChooseBackupFolder, useRestoreBackup } from "../../api/backups";
import { formatDateTime } from "../../lib/dates";
import { Card } from "../Card";
import { Modal } from "../Modal";
import { BUTTON_DANGER_OUTLINE, BUTTON_LINK, BUTTON_PRIMARY, BUTTON_SECONDARY, LABEL } from "../ui";
import { FolderBrowserDialog } from "./FolderBrowserDialog";

/**
 * Settings → Backups (spec §7): where the daily backup goes, how many are kept (passed in as
 * `children`, so it sits with the other settings' controls), Back up now and Restore.
 */
export function BackupsCard({ children }: { children: ReactNode }) {
  const backups = useBackups();
  const backUp = useBackUpNow();
  const chooseFolder = useChooseBackupFolder();
  const [choosing, setChoosing] = useState(false);
  const [restoring, setRestoring] = useState(false);
  /** The backup just restored, so the card can say so once the dialog has closed. */
  const [restored, setRestored] = useState<Backup | null>(null);
  const status = backups.data;

  return (
    <Card title="Backups">
      {backups.isPending && <p className="text-muted">Looking for your backups…</p>}
      {backups.isError && (
        <p role="alert" className="text-danger">
          {backups.error.message}
        </p>
      )}

      {status && (
        <div className="space-y-4">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
            <div className="min-w-0">
              <p className="font-medium">Daily backup to</p>
              <p className="font-mono text-sm break-all">{status.folder}</p>
              {status.usingDefaultFolder && (
                <p className="mt-1 text-sm text-muted">
                  This is inside Budget's own data on this PC. Choose a folder on another drive, so a copy survives if
                  this one fails.
                </p>
              )}
            </div>
            <div className="flex shrink-0 flex-col gap-1 sm:items-end">
              <button
                type="button"
                onClick={() => {
                  chooseFolder.reset();
                  setChoosing(true);
                }}
                className={BUTTON_SECONDARY}
              >
                Choose folder…
              </button>
              {!status.usingDefaultFolder && (
                <button type="button" disabled={chooseFolder.isPending} onClick={() => chooseFolder.mutate(null)} className={BUTTON_LINK}>
                  Use Budget's own folder
                </button>
              )}
            </div>
          </div>

          {children}

          {status.lastDailyBackupFailed && (
            <p role="alert" className="flex items-start gap-2 rounded-lg border border-warn-line bg-warn p-3 text-warn-ink">
              <TriangleAlert aria-hidden className="mt-0.5 size-5 shrink-0" />
              Today's backup couldn't be saved. Please check that the folder is still there, or choose another one.
            </p>
          )}

          <p className="text-muted">
            {status.lastBackupAt
              ? `Last backup ${formatDateTime(status.lastBackupAt)}. Receipts and pay stubs are included.`
              : "No backups yet. One is made each day Budget is open."}{" "}
            A backup is a plain copy that your PIN does not lock, so keep it somewhere only you can reach.
          </p>

          {backUp.isSuccess && (
            <p role="status" className="font-medium">
              Backed up just now.
            </p>
          )}
          {restored && (
            <p role="status" className="font-medium">
              Restored. Budget now holds the backup from {formatDateTime(restored.createdAt)}.
            </p>
          )}
          {(backUp.isError || (chooseFolder.isError && !choosing)) && (
            <p role="alert" className="text-danger">
              {backUp.error?.message ?? chooseFolder.error?.message}
            </p>
          )}

          <div className="flex flex-col gap-2 sm:flex-row">
            <button
              type="button"
              disabled={backUp.isPending}
              onClick={() => {
                setRestored(null);
                backUp.mutate();
              }}
              className={BUTTON_PRIMARY}
            >
              {backUp.isPending ? "Backing up…" : "Back up now"}
            </button>
            <button
              type="button"
              disabled={status.backups.length === 0}
              onClick={() => {
                backUp.reset();
                setRestored(null);
                setRestoring(true);
              }}
              className={BUTTON_SECONDARY}
            >
              Restore…
            </button>
          </div>
        </div>
      )}

      {choosing && status && (
        <FolderBrowserDialog
          startAt={status.usingDefaultFolder ? null : status.folder}
          saving={chooseFolder.isPending}
          error={chooseFolder.error?.message}
          onChoose={(path) => chooseFolder.mutate(path, { onSuccess: () => setChoosing(false) })}
          onClose={() => setChoosing(false)}
        />
      )}
      {restoring && status && (
        <RestoreDialog
          backups={status.backups}
          onRestored={(backup) => {
            setRestored(backup);
            setRestoring(false);
          }}
          onClose={() => setRestoring(false)}
        />
      )}
    </Card>
  );
}

/**
 * Restore replaces everything, so it is one of the few places that asks first (spec §6).
 * Keeping the data is the default: it has the focus, so Enter by reflex changes nothing.
 */
function RestoreDialog({
  backups,
  onRestored,
  onClose,
}: {
  backups: readonly Backup[];
  onRestored: (backup: Backup) => void;
  onClose: () => void;
}) {
  const id = useId();
  const [name, setName] = useState(backups[0]?.name ?? "");
  const restore = useRestoreBackup();
  const chosen = backups.find((backup) => backup.name === name);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (chosen) {
      restore.mutate(chosen.name, { onSuccess: () => onRestored(chosen) });
    }
  };

  return (
    <Modal title="Restore a backup" icon={ArchiveRestore} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <div>
          <label htmlFor={id} className={LABEL}>
            Backup to restore
          </label>
          <select
            id={id}
            value={name}
            onChange={(event) => setName(event.target.value)}
            className="min-h-11 w-full rounded-lg border border-line bg-surface px-3"
          >
            {backups.map((backup) => (
              <option key={backup.name} value={backup.name}>
                {capitalized(formatDateTime(backup.createdAt))} · {formatSize(backup.sizeBytes)}
              </option>
            ))}
          </select>
        </div>
        <div className="rounded-lg border border-danger p-4">
          <p className="flex items-center gap-2 font-semibold">
            <TriangleAlert aria-hidden className="size-5 shrink-0 text-danger" />
            Everything in Budget now is replaced by this backup.
          </p>
          <ul className="mt-2 list-disc space-y-1 pl-5 text-muted">
            <li>Anything entered since that backup was made will be gone.</li>
            <li>A copy of what you have now is kept first, so this can be put right.</li>
            <li>Your PIN stays as it is today.</li>
          </ul>
        </div>
        {restore.isError && (
          <p role="alert" className="text-danger">
            {restore.error.message}
          </p>
        )}
        <div className="flex flex-col gap-2 sm:flex-row sm:justify-end">
          <button type="button" data-autofocus disabled={restore.isPending} onClick={onClose} className={BUTTON_SECONDARY}>
            Keep my current data
          </button>
          <button type="submit" disabled={restore.isPending || !chosen} className={BUTTON_DANGER_OUTLINE}>
            {restore.isPending ? "Restoring…" : "Restore this backup"}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function capitalized(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** `1536` → `"2 KB"`, `1300000` → `"1.2 MB"`: a rough size is all anyone needs here. */
function formatSize(bytes: number): string {
  const megabytes = bytes / (1024 * 1024);
  return megabytes >= 1 ? `${megabytes.toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;
}
