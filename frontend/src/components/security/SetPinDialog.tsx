import { LockKeyhole } from "lucide-react";
import { type FormEvent, useState } from "react";
import { useSetPin } from "../../api/session";
import { Modal } from "../Modal";
import { BUTTON_PRIMARY, BUTTON_SECONDARY } from "../ui";
import { NewPinFields, newPinProblem, PinInput } from "./PinFields";
import { RecoveryCodeNotice } from "./RecoveryCodeNotice";

/**
 * Sets the first PIN, or changes it (which asks for the one in use). After a first PIN the
 * dialog turns into the recovery code, shown once; it then closes only by its own button,
 * so the code cannot be lost to a stray click outside.
 */
export function SetPinDialog({ changing, onClose }: { changing: boolean; onClose: () => void }) {
  const [currentPin, setCurrentPin] = useState("");
  const [pin, setPin] = useState("");
  const [again, setAgain] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const [recoveryCode, setRecoveryCode] = useState<string | null>(null);
  const save = useSetPin();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const wrong = changing && currentPin === "" ? "Please type the PIN you use now." : newPinProblem(pin, again);
    setProblem(wrong);
    if (wrong) {
      return;
    }
    save.mutate(
      { currentPin: changing ? currentPin : undefined, newPin: pin },
      { onSuccess: (saved) => (saved.recoveryCode ? setRecoveryCode(saved.recoveryCode) : onClose()) },
    );
  };

  if (recoveryCode) {
    return (
      <Modal title="Your PIN is set" icon={LockKeyhole} onClose={() => undefined}>
        <RecoveryCodeNotice code={recoveryCode} doneLabel="Done" onDone={onClose} />
      </Modal>
    );
  }

  return (
    <Modal title={changing ? "Change your PIN" : "Set a PIN"} icon={LockKeyhole} onClose={onClose}>
      <form onSubmit={submit} noValidate className="space-y-4">
        {!changing && (
          <p className="text-muted">
            Budget will ask for this PIN each time it opens, and after it has sat idle. It locks the app; it does not
            scramble the data on this PC.
          </p>
        )}
        {changing && <PinInput label="PIN you use now" value={currentPin} onChange={setCurrentPin} autoFocus />}
        <NewPinFields pin={pin} again={again} onPin={setPin} onAgain={setAgain} autoFocus={!changing} />
        {(problem ?? save.error?.message) && (
          <p role="alert" className="text-danger">
            {problem ?? save.error?.message}
          </p>
        )}
        <div className="flex flex-col gap-2 sm:flex-row sm:justify-end">
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="submit" disabled={save.isPending} className={BUTTON_PRIMARY}>
            {save.isPending ? "Saving…" : changing ? "Change PIN" : "Set PIN"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
