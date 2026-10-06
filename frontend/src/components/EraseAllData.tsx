import { TriangleAlert } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useEraseAllData } from "../api/data";
import { Card } from "./Card";

/* Full-width on a phone, side by side from `sm` up; 44px touch targets. */
const BUTTON = "min-h-11 rounded-lg px-4 font-semibold disabled:opacity-60";

/**
 * Settings → Erase all data. Starts the app over with nothing in it.
 *
 * This is one of the few places that asks "are you sure?": it cannot be undone, and Undo
 * (which covers every ordinary edit) cannot bring it back. Cancelling is the default —
 * it gets the focus, so pressing Enter by reflex keeps the data.
 */
export function EraseAllData() {
  const [confirming, setConfirming] = useState(false);
  const erase = useEraseAllData();
  const keepButton = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (confirming) {
      keepButton.current?.focus();
    }
  }, [confirming]);

  const confirm = () => erase.mutate(undefined, { onSuccess: () => setConfirming(false) });

  return (
    <Card title="Erase all data">
      <p className="text-muted">
        Start over with a completely fresh app. Everything you've entered is permanently removed from this PC.
      </p>

      {erase.isSuccess && !confirming && (
        <p role="status" className="mt-3 font-medium">
          All done — your data has been erased and Budget is fresh again.
        </p>
      )}

      {!confirming && (
        <button
          type="button"
          onClick={() => {
            erase.reset();
            setConfirming(true);
          }}
          className={`${BUTTON} mt-4 w-full border border-danger text-danger sm:w-auto`}
        >
          Erase all data…
        </button>
      )}

      {confirming && (
        <div
          role="alertdialog"
          aria-labelledby="erase-title"
          aria-describedby="erase-detail"
          className="mt-4 rounded-lg border border-danger p-4"
        >
          <p id="erase-title" className="flex items-center gap-2 font-semibold">
            <TriangleAlert aria-hidden className="size-5 shrink-0 text-danger" />
            Erase everything? This can't be undone.
          </p>
          <ul id="erase-detail" className="mt-2 list-disc space-y-1 pl-5 text-muted">
            <li>All accounts, bills, income, receipts and settings will be gone for good.</li>
            <li>Backups kept inside the app are erased too.</li>
            <li>Backups you saved to another folder are not touched.</li>
          </ul>
          <div className="mt-4 flex flex-col gap-2 sm:flex-row">
            <button
              ref={keepButton}
              type="button"
              disabled={erase.isPending}
              onClick={() => setConfirming(false)}
              className={`${BUTTON} border border-line bg-surface`}
            >
              Keep my data
            </button>
            <button
              type="button"
              disabled={erase.isPending}
              onClick={confirm}
              className={`${BUTTON} bg-danger text-surface`}
            >
              {erase.isPending ? "Erasing…" : "Yes, erase everything"}
            </button>
          </div>
          {erase.isError && (
            <p role="alert" className="mt-3 text-danger">
              {erase.error.message}
            </p>
          )}
        </div>
      )}
    </Card>
  );
}
