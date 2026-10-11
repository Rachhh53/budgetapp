import { useEffect, useRef } from "react";

/** What counts as someone being at the window. */
const ACTIVITY = ["pointerdown", "pointermove", "keydown", "wheel", "touchstart"] as const;

/** How often the idle time is looked at. Short, so a PC waking from sleep locks at once. */
const CHECK_EVERY_MS = 5_000;

/** While someone is working, the backend is told so at least this often. */
const LONGEST_BETWEEN_KEEP_ALIVES_MS = 60_000;

/**
 * Auto-lock (spec §6): locks the app once nobody has touched the window for `minutes`.
 *
 * The backend runs the same clock, but it only sees requests: reading a long screen or
 * filling in a form sends none. So while there is activity here, `keepAlive` tells the
 * backend about it now and then; otherwise it would lock someone out mid-form.
 */
export function useAutoLock(minutes: number, enabled: boolean, lock: () => void, keepAlive: () => void) {
  // Refs, so a caller passing new functions on every render does not restart the timer.
  const lockRef = useRef(lock);
  const keepAliveRef = useRef(keepAlive);
  lockRef.current = lock;
  keepAliveRef.current = keepAlive;

  useEffect(() => {
    if (!enabled) {
      return;
    }
    const limit = minutes * 60_000;
    const keepAliveEvery = Math.min(LONGEST_BETWEEN_KEEP_ALIVES_MS, limit / 3);
    let lastActivity = Date.now();
    let lastKeepAlive = lastActivity;
    const noteActivity = () => {
      lastActivity = Date.now();
    };
    ACTIVITY.forEach((event) => window.addEventListener(event, noteActivity, { capture: true, passive: true }));

    const timer = window.setInterval(() => {
      const now = Date.now();
      if (now - lastActivity >= limit) {
        lockRef.current();
      } else if (lastActivity > lastKeepAlive && now - lastKeepAlive >= keepAliveEvery) {
        lastKeepAlive = now;
        keepAliveRef.current();
      }
    }, CHECK_EVERY_MS);

    return () => {
      window.clearInterval(timer);
      ACTIVITY.forEach((event) => window.removeEventListener(event, noteActivity, { capture: true }));
    };
  }, [minutes, enabled]);
}
