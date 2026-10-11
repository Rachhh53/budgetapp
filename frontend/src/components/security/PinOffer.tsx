import { LockKeyhole } from "lucide-react";
import { useState } from "react";
import { useDismissPinOffer, useSession } from "../../api/session";
import { Modal } from "../Modal";
import { BUTTON_PRIMARY, BUTTON_SECONDARY } from "../ui";
import { SetPinDialog } from "./SetPinDialog";

/**
 * The first-launch offer to lock the app with a PIN. A PIN is optional (decided 2026-10-11),
 * so this asks once and takes "Not now" for an answer; closing the dialog any other way
 * means the same. Settings has it from then on.
 */
export function PinOffer({ onDone }: { onDone: () => void }) {
  const [setting, setSetting] = useState(false);
  const pinSet = useSession().data?.pinSet ?? false;
  const dismiss = useDismissPinOffer();
  const notNow = () => dismiss.mutate(undefined, { onSuccess: onDone });

  if (setting) {
    // Once the PIN is saved the offer is answered. Backing out of the form before that is
    // "Not now" too: the question has been seen.
    return <SetPinDialog changing={false} onClose={pinSet ? onDone : notNow} />;
  }

  return (
    <Modal title="Lock Budget with a PIN?" icon={LockKeyhole} onClose={notNow}>
      <div className="space-y-4">
        <p>
          A PIN keeps your budget private from anyone else who uses this PC. Budget asks for it when it opens, and
          again after it has sat idle for a while.
        </p>
        <p className="text-muted">It's up to you, and you can set, change or remove it any time in Settings.</p>
        {dismiss.isError && (
          <p role="alert" className="text-danger">
            {dismiss.error.message}
          </p>
        )}
        <div className="flex flex-col gap-2 sm:flex-row sm:justify-end">
          <button type="button" disabled={dismiss.isPending} onClick={notNow} className={BUTTON_SECONDARY}>
            Not now
          </button>
          <button type="button" data-autofocus onClick={() => setSetting(true)} className={BUTTON_PRIMARY}>
            Set a PIN
          </button>
        </div>
      </div>
    </Modal>
  );
}
