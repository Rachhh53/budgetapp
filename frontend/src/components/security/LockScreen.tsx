import { useQueryClient } from "@tanstack/react-query";
import { LockKeyhole } from "lucide-react";
import { type FormEvent, useEffect, useId, useState } from "react";
import { SESSION_KEY, type SessionStatus, useResetPin, useUnlock } from "../../api/session";
import { PRIVACY_NOTE } from "../Sidebar";
import { BUTTON_LINK, BUTTON_PRIMARY, HINT, INPUT, LABEL } from "../ui";
import { NewPinFields, newPinProblem, PinInput } from "./PinFields";
import { RecoveryCodeNotice } from "./RecoveryCodeNotice";

/**
 * What is shown in place of the whole app while it is locked: the PIN box, and the way back
 * in with the recovery code. Nothing of the budget is on screen or in memory behind it.
 */
export function LockScreen({ status }: { status: SessionStatus }) {
  const [recovering, setRecovering] = useState(false);
  const wait = useWait(status.retryAfterSeconds);

  return (
    <main className="flex min-h-dvh flex-col items-center justify-center bg-bg p-4">
      <div className="w-full max-w-sm rounded-card border border-line bg-surface p-5 sm:p-6">
        <div className="mb-4 flex items-center gap-3">
          <span aria-hidden className="flex size-10 shrink-0 items-center justify-center rounded-full bg-accent text-on-accent">
            <LockKeyhole className="size-5" />
          </span>
          <h1 className="text-xl font-bold">{recovering ? "Reset your PIN" : "Budget is locked"}</h1>
        </div>
        {wait > 0 && (
          <p role="status" className="mb-4 rounded-lg border border-warn-line bg-warn p-3 text-warn-ink">
            That's a few tries that didn't match. Please wait {inWords(wait)}, then try again.
          </p>
        )}
        {recovering ? (
          <RecoveryForm waiting={wait > 0} onBack={() => setRecovering(false)} />
        ) : (
          <PinForm waiting={wait > 0} onForgot={() => setRecovering(true)} />
        )}
      </div>
      <p className="mt-4 text-sm text-muted">{PRIVACY_NOTE}</p>
    </main>
  );
}

function PinForm({ waiting, onForgot }: { waiting: boolean; onForgot: () => void }) {
  const [pin, setPin] = useState("");
  const unlock = useUnlock();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (pin !== "") {
      unlock.mutate(pin, { onError: () => setPin("") });
    }
  };

  return (
    <form onSubmit={submit} noValidate className="space-y-4">
      <PinInput label="PIN" value={pin} onChange={setPin} autoFocus />
      {/* While waiting, the notice above says what to do; the last "didn't match" would only repeat it. */}
      {unlock.isError && !waiting && (
        <p role="alert" className="text-danger">
          {unlock.error.message}
        </p>
      )}
      <button type="submit" disabled={waiting || unlock.isPending || pin === ""} className={`${BUTTON_PRIMARY} sm:w-full`}>
        {unlock.isPending ? "Unlocking…" : "Unlock"}
      </button>
      <button type="button" onClick={onForgot} className={`${BUTTON_LINK} w-full`}>
        I forgot my PIN
      </button>
    </form>
  );
}

function RecoveryForm({ waiting, onBack }: { waiting: boolean; onBack: () => void }) {
  const queryClient = useQueryClient();
  const codeId = useId();
  const [code, setCode] = useState("");
  const [pin, setPin] = useState("");
  const [again, setAgain] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const reset = useResetPin();

  if (reset.data) {
    // The app is unlocked already; it opens once the new code has been saved.
    const { recoveryCode, session } = reset.data;
    const open = () => queryClient.setQueryData(SESSION_KEY, session);
    return recoveryCode ? (
      <div className="space-y-4">
        <p>Your new PIN is set. The code you just used no longer works, so here is a new one.</p>
        <RecoveryCodeNotice code={recoveryCode} doneLabel="Open Budget" onDone={open} />
      </div>
    ) : (
      <button type="button" onClick={open} className={BUTTON_PRIMARY}>
        Open Budget
      </button>
    );
  }

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const wrong = code.trim() === "" ? "Please type your recovery code." : newPinProblem(pin, again);
    setProblem(wrong);
    if (!wrong) {
      reset.mutate({ recoveryCode: code, newPin: pin });
    }
  };

  return (
    <form onSubmit={submit} noValidate className="space-y-4">
      <div>
        <label htmlFor={codeId} className={LABEL}>
          Recovery code
        </label>
        <input
          id={codeId}
          data-autofocus
          autoFocus
          autoComplete="off"
          autoCapitalize="characters"
          spellCheck={false}
          maxLength={40}
          value={code}
          aria-describedby={`${codeId}-hint`}
          onChange={(event) => setCode(event.target.value)}
          className={`${INPUT} font-mono uppercase`}
        />
        <p id={`${codeId}-hint`} className={HINT}>
          The code you were given when you set your PIN, like K7QM-2XHP-9FTB-WRNC.
        </p>
      </div>
      <NewPinFields pin={pin} again={again} onPin={setPin} onAgain={setAgain} />
      {(problem ?? (reset.isError && !waiting ? reset.error.message : null)) && (
        <p role="alert" className="text-danger">
          {problem ?? reset.error?.message}
        </p>
      )}
      <button type="submit" disabled={waiting || reset.isPending} className={`${BUTTON_PRIMARY} sm:w-full`}>
        {reset.isPending ? "Checking…" : "Set new PIN and unlock"}
      </button>
      <button type="button" onClick={onBack} className={`${BUTTON_LINK} w-full`}>
        Back to the PIN
      </button>
      <p className="text-sm text-muted">
        Lost the code as well? Your data is safe. Close Budget and follow "If you forget your PIN" in the README to
        remove the PIN from this PC.
      </p>
    </form>
  );
}

/**
 * Counts the wait down a second at a time, and asks the backend again once it is over, so
 * the form opens up by itself.
 */
function useWait(retryAfterSeconds: number): number {
  const queryClient = useQueryClient();
  const [left, setLeft] = useState(retryAfterSeconds);

  useEffect(() => {
    setLeft(retryAfterSeconds);
    if (retryAfterSeconds <= 0) {
      return;
    }
    const endsAt = Date.now() + retryAfterSeconds * 1000;
    const timer = window.setInterval(() => {
      const remaining = Math.ceil((endsAt - Date.now()) / 1000);
      setLeft(Math.max(0, remaining));
      if (remaining <= 0) {
        window.clearInterval(timer);
        void queryClient.invalidateQueries({ queryKey: SESSION_KEY });
      }
    }, 1000);
    return () => window.clearInterval(timer);
  }, [retryAfterSeconds, queryClient]);

  return left;
}

/** "30 seconds", "1 minute", "15 minutes": rounded up, as the backend words it. */
function inWords(seconds: number): string {
  if (seconds < 60) {
    return `${seconds} ${seconds === 1 ? "second" : "seconds"}`;
  }
  const minutes = Math.ceil(seconds / 60);
  return `${minutes} ${minutes === 1 ? "minute" : "minutes"}`;
}
