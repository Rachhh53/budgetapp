import { LockKeyhole } from "lucide-react";
import { useLock, useSession } from "../../api/session";

/**
 * "Lock now" (spec §6): puts the PIN back in front of the app straight away. Only there
 * when a PIN is set, since without one there is nothing to lock with.
 */
export function LockNowButton({ className }: { className: string }) {
  const session = useSession();
  const lock = useLock();
  if (!session.data?.pinSet) {
    return null;
  }
  return (
    <button type="button" disabled={lock.isPending} onClick={() => lock.mutate()} className={className}>
      <LockKeyhole aria-hidden className="size-4 shrink-0" />
      Lock now
    </button>
  );
}
