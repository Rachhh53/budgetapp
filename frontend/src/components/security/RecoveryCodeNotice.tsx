import { KeyRound } from "lucide-react";
import { useId, useState } from "react";
import { BUTTON_PRIMARY } from "../ui";

/**
 * Shows a new recovery code, the one time it is ever shown. The way on stays closed until
 * the person says they have saved it, because there is no second look.
 */
export function RecoveryCodeNotice({ code, doneLabel, onDone }: { code: string; doneLabel: string; onDone: () => void }) {
  const [saved, setSaved] = useState(false);
  const tickId = useId();
  return (
    <div className="space-y-4">
      <div className="rounded-lg border border-line bg-bg/60 p-4">
        <p className="flex items-center gap-2 font-semibold">
          <KeyRound aria-hidden className="size-5 shrink-0 text-accent-ink" />
          Your recovery code
        </p>
        {/* select-all: one click picks up the whole code for copying. */}
        <p data-testid="recovery-code" className="mt-3 font-mono text-xl font-semibold tracking-wider break-all select-all sm:text-2xl">
          {code}
        </p>
      </div>
      <p>
        Write it down and keep it somewhere safe, away from this PC. It is the way back in if you ever forget your
        PIN, and it is shown only this once.
      </p>
      <div className="flex min-h-11 items-center gap-3">
        <input
          id={tickId}
          type="checkbox"
          checked={saved}
          onChange={(event) => setSaved(event.target.checked)}
          className="size-5 shrink-0 accent-(--accent)"
        />
        <label htmlFor={tickId} className="font-medium">
          I've saved my recovery code
        </label>
      </div>
      <button type="button" disabled={!saved} onClick={onDone} className={BUTTON_PRIMARY}>
        {doneLabel}
      </button>
    </div>
  );
}
