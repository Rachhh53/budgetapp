import { useId } from "react";
import { PIN_HINT, PIN_PATTERN } from "../../api/session";
import { HINT, INPUT, LABEL } from "../ui";

/**
 * One PIN box. The PIN is hidden as it is typed, the phone shows its number pad, and anything
 * that is not a digit is simply left out, so there is nothing to get wrong but the PIN itself.
 */
export function PinInput({
  label,
  value,
  onChange,
  hint,
  autoFocus,
}: {
  label: string;
  value: string;
  onChange: (pin: string) => void;
  hint?: string;
  autoFocus?: boolean;
}) {
  const id = useId();
  return (
    <div>
      <label htmlFor={id} className={LABEL}>
        {label}
      </label>
      <input
        id={id}
        type="password"
        inputMode="numeric"
        // Not a password for the browser to remember or fill in.
        autoComplete="off"
        maxLength={8}
        value={value}
        // Both: the attribute for a page of its own (the lock screen), the marker for a dialog, which
        // focuses its first marked field itself.
        autoFocus={autoFocus}
        data-autofocus={autoFocus ? true : undefined}
        aria-describedby={hint ? `${id}-hint` : undefined}
        onChange={(event) => onChange(event.target.value.replace(/\D/g, ""))}
        className={`${INPUT} text-lg tracking-[0.3em]`}
      />
      {hint && (
        <p id={`${id}-hint`} className={HINT}>
          {hint}
        </p>
      )}
    </div>
  );
}

/** What is wrong with a new PIN typed twice, in words for the person; null when it is fine. */
export function newPinProblem(pin: string, again: string): string | null {
  if (!PIN_PATTERN.test(pin)) {
    return PIN_HINT;
  }
  if (pin !== again) {
    return "Those two PINs are different. Please type the same PIN in both boxes.";
  }
  return null;
}

/** A new PIN, typed twice so a slip of the finger cannot lock anyone out. */
export function NewPinFields({
  pin,
  again,
  onPin,
  onAgain,
  autoFocus,
}: {
  pin: string;
  again: string;
  onPin: (pin: string) => void;
  onAgain: (pin: string) => void;
  autoFocus?: boolean;
}) {
  return (
    <>
      <PinInput label="New PIN" value={pin} onChange={onPin} hint={PIN_HINT} autoFocus={autoFocus} />
      <PinInput label="New PIN again" value={again} onChange={onAgain} />
    </>
  );
}
