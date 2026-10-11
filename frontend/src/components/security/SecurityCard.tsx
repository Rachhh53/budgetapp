import { KeyRound, LockKeyhole } from "lucide-react";
import { type FormEvent, type ReactNode, useState } from "react";
import { useNewRecoveryCode, useRemovePin, useSession } from "../../api/session";
import { Card } from "../Card";
import { Modal } from "../Modal";
import { BUTTON_DANGER_OUTLINE, BUTTON_PRIMARY, BUTTON_SECONDARY } from "../ui";
import { PinInput } from "./PinFields";
import { RecoveryCodeNotice } from "./RecoveryCodeNotice";
import { SetPinDialog } from "./SetPinDialog";

type Open = "set" | "change" | "remove" | "code" | null;

/**
 * Settings → Security: the PIN (set, change or remove it), a new recovery code, and, passed
 * in as `children`, the settings that go with them (lock after, start with amounts hidden).
 */
export function SecurityCard({ children }: { children: ReactNode }) {
  const session = useSession();
  const [open, setOpen] = useState<Open>(null);
  const pinSet = session.data?.pinSet ?? false;
  const close = () => setOpen(null);

  return (
    <Card title="Security">
      <div className="divide-y divide-line">
        <div className="flex flex-col gap-3 pb-4 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <p className="font-medium">PIN</p>
            <p className="text-sm text-muted">
              {pinSet
                ? "A PIN is set. Budget asks for it when it opens and after it has sat idle."
                : "No PIN is set, so Budget opens for anyone using this PC."}
            </p>
          </div>
          <div className="flex shrink-0 flex-col gap-2 sm:flex-row">
            {pinSet ? (
              <>
                <button type="button" onClick={() => setOpen("change")} className={BUTTON_SECONDARY}>
                  Change PIN
                </button>
                <button type="button" onClick={() => setOpen("remove")} className={BUTTON_DANGER_OUTLINE}>
                  Remove PIN
                </button>
              </>
            ) : (
              <button type="button" onClick={() => setOpen("set")} className={BUTTON_SECONDARY}>
                Set a PIN
              </button>
            )}
          </div>
        </div>

        {pinSet && (
          <div className="flex flex-col gap-3 py-4 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <p className="font-medium">Recovery code</p>
              <p className="text-sm text-muted">
                The way back in if you forget your PIN. Shown once when made, so keep it somewhere safe.
              </p>
            </div>
            <button type="button" onClick={() => setOpen("code")} className={`${BUTTON_SECONDARY} shrink-0`}>
              Make new code
            </button>
          </div>
        )}

        <div className="space-y-4 pt-4">{children}</div>
      </div>

      {(open === "set" || open === "change") && <SetPinDialog changing={open === "change"} onClose={close} />}
      {open === "remove" && <RemovePinDialog onClose={close} />}
      {open === "code" && <NewCodeDialog onClose={close} />}
    </Card>
  );
}

/** Removing the PIN asks for it first, so someone passing an unlocked window cannot take the lock off. */
function RemovePinDialog({ onClose }: { onClose: () => void }) {
  const [pin, setPin] = useState("");
  const remove = useRemovePin();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (pin !== "") {
      remove.mutate(pin, { onSuccess: onClose });
    }
  };

  return (
    <Modal title="Remove your PIN" icon={LockKeyhole} onClose={onClose}>
      <form onSubmit={submit} noValidate className="space-y-4">
        <p className="text-muted">
          Budget will open without asking, for anyone using this PC. Your recovery code stops working too; a new PIN
          comes with a new one.
        </p>
        <PinInput label="PIN you use now" value={pin} onChange={setPin} autoFocus />
        {remove.isError && (
          <p role="alert" className="text-danger">
            {remove.error.message}
          </p>
        )}
        <div className="flex flex-col gap-2 sm:flex-row sm:justify-end">
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Keep my PIN
          </button>
          <button type="submit" disabled={remove.isPending || pin === ""} className={BUTTON_DANGER_OUTLINE}>
            {remove.isPending ? "Removing…" : "Remove PIN"}
          </button>
        </div>
      </form>
    </Modal>
  );
}

/** A new recovery code: asks for the PIN, then shows the code once. The old one stops working. */
function NewCodeDialog({ onClose }: { onClose: () => void }) {
  const [pin, setPin] = useState("");
  const make = useNewRecoveryCode();

  if (make.data?.recoveryCode) {
    // Like a first PIN's code: closed only by saying it has been saved.
    return (
      <Modal title="Your new recovery code" icon={KeyRound} onClose={() => undefined}>
        <RecoveryCodeNotice code={make.data.recoveryCode} doneLabel="Done" onDone={onClose} />
      </Modal>
    );
  }

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (pin !== "") {
      make.mutate(pin);
    }
  };

  return (
    <Modal title="Make a new recovery code" icon={KeyRound} onClose={onClose}>
      <form onSubmit={submit} noValidate className="space-y-4">
        <p className="text-muted">The code you have now stops working as soon as the new one is made.</p>
        <PinInput label="Your PIN" value={pin} onChange={setPin} autoFocus />
        {make.isError && (
          <p role="alert" className="text-danger">
            {make.error.message}
          </p>
        )}
        <div className="flex flex-col gap-2 sm:flex-row sm:justify-end">
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="submit" disabled={make.isPending || pin === ""} className={BUTTON_PRIMARY}>
            {make.isPending ? "Making it…" : "Make new code"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
