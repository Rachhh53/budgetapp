import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { api, direct } from "./client";
import { SETTINGS_KEY } from "./settings";

/** Whether the app is open, and what opening it takes. Asked without a session, so it never fails as locked. */
export interface SessionStatus {
  unlocked: boolean;
  /** True when a PIN is needed to unlock. */
  pinSet: boolean;
  /** After too many wrong tries: how long until the next one is looked at. 0 when there is no wait. */
  retryAfterSeconds: number;
  /** True while the first-launch offer to set a PIN has not been answered. */
  offerPin: boolean;
}

/** The answer to setting a PIN or making a new recovery code. */
export interface PinSaved {
  /** The code to write down, sent this once; null when it has not changed. */
  recoveryCode: string | null;
  session: SessionStatus;
}

export const SESSION_KEY = ["session"] as const;

/** A PIN is 4 to 8 digits. Kept as text: it may start with zeros. */
export const PIN_PATTERN = /^\d{4,8}$/;
export const PIN_HINT = "A PIN is 4 to 8 digits.";

export function useSession() {
  return useQuery({
    queryKey: SESSION_KEY,
    queryFn: () => direct.get<SessionStatus>("/api/session"),
    // Asked once; every change to it afterwards is written into the cache by the calls below.
    staleTime: Infinity,
    retry: false,
    // If the backend could not be asked, the app is drawn anyway (its screens say what is wrong).
    // The Lock now button there asks too; without this its mounting would ask again, the app
    // would be taken down while it waited, and the two would go round for ever.
    retryOnMount: false,
  });
}

/**
 * Forgets everything that was loaded while unlocked, so nothing of the budget stays in the
 * window's memory behind the lock screen. Settings are kept: they hold no money, and the lock
 * screen keeps the theme and accent color that way.
 */
export function forgetLoadedData(queryClient: QueryClient) {
  const kept: readonly unknown[] = [SESSION_KEY[0], SETTINGS_KEY[0]];
  queryClient.removeQueries({ predicate: (query) => !kept.includes(query.queryKey[0]) });
}

/**
 * After the data has been replaced (a restore, or erasing it all): forgets everything loaded
 * for screens that are not in view, and loads afresh what the screen in view is showing.
 * That screen is refreshed in place rather than emptied first, so it stays put, and with it
 * the message saying what just happened.
 */
export function reloadEverything(queryClient: QueryClient) {
  queryClient.removeQueries({ type: "inactive" });
  return queryClient.invalidateQueries();
}

/** Unlocks with the PIN. A wrong one fails with the backend's own kind message. */
export function useUnlock() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (pin: string) => direct.post<SessionStatus>("/api/session", { pin }),
    onSuccess: (status) => queryClient.setQueryData(SESSION_KEY, status),
    // A wrong PIN may have started a wait; ask how long.
    onError: () => queryClient.invalidateQueries({ queryKey: SESSION_KEY }),
  });
}

/** "Lock now", and what auto-lock does when the window has sat idle. */
export function useLock() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => direct.delete<SessionStatus>("/api/session"),
    onSettled: () => {
      forgetLoadedData(queryClient);
      // Locked either way: if the call failed, the session had already run out.
      queryClient.setQueryData(SESSION_KEY, (status: SessionStatus | undefined) =>
        status ? { ...status, unlocked: false } : status,
      );
      return queryClient.invalidateQueries({ queryKey: SESSION_KEY });
    },
  });
}

/** Sets the first PIN (no `currentPin`) or changes it. */
export function useSetPin() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (pins: { currentPin?: string; newPin: string }) => api.put<PinSaved>("/api/pin", pins),
    onSuccess: (saved) => queryClient.setQueryData(SESSION_KEY, saved.session),
  });
}

export function useRemovePin() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (currentPin: string) => api.delete<SessionStatus>("/api/pin", { currentPin }),
    onSuccess: (status) => queryClient.setQueryData(SESSION_KEY, status),
  });
}

/** Replaces the recovery code; the old one stops working. */
export function useNewRecoveryCode() {
  return useMutation({
    mutationFn: (currentPin: string) => api.post<PinSaved>("/api/pin/recovery-code", { currentPin }),
  });
}

/** "Not now" on the first-launch offer: it is not made again. */
export function useDismissPinOffer() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.delete<SessionStatus>("/api/pin/offer"),
    onSuccess: (status) => queryClient.setQueryData(SESSION_KEY, status),
  });
}

/**
 * A forgotten PIN: the recovery code sets a new one and unlocks. The answer carries a new
 * recovery code, which the lock screen shows before letting the app open — so the session
 * status is left for the caller to write, once that code has been saved.
 */
export function useResetPin() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (reset: { recoveryCode: string; newPin: string }) => direct.post<PinSaved>("/api/pin/recovery", reset),
    onError: () => queryClient.invalidateQueries({ queryKey: SESSION_KEY }),
  });
}
