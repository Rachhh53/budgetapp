import { Eye, EyeOff } from "lucide-react";
import { useEffect, useSyncExternalStore } from "react";
import { BUTTON_SECONDARY } from "./ui";

const STORAGE_KEY = "budget.hideAmounts";
/** Set on the page's root; theme.css blurs every `[data-amount]` while it is there. */
const ATTRIBUTE = "data-hide-amounts";

/*
 * The choice lives outside React so the button (on one screen) and the app frame (which
 * applies it to every screen) share it. It is remembered on this device only: it is about
 * who is in the room, not a setting of the budget.
 */
const listeners = new Set<() => void>();
let hidden = readChoice();

function readChoice(): boolean {
  try {
    return window.localStorage.getItem(STORAGE_KEY) === "1";
  } catch {
    // Storage can be unavailable; hiding amounts then simply lasts until the window closes.
    return false;
  }
}

function setHidden(next: boolean) {
  hidden = next;
  try {
    window.localStorage.setItem(STORAGE_KEY, next ? "1" : "0");
  } catch {
    // See readChoice.
  }
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function useAmountsHidden(): boolean {
  return useSyncExternalStore(subscribe, () => hidden);
}

/** Keeps the page in step with the choice. Called once, by the frame around every screen. */
export function useApplyHideAmounts() {
  const amountsHidden = useAmountsHidden();
  useEffect(() => {
    document.documentElement.toggleAttribute(ATTRIBUTE, amountsHidden);
  }, [amountsHidden]);
}

/**
 * "Hide amounts" (spec §6): blurs every number on every screen, for when someone is looking
 * over a shoulder. A toggle button, so it reads as pressed while amounts are hidden.
 */
export function HideAmountsButton() {
  const amountsHidden = useAmountsHidden();
  const Icon = amountsHidden ? EyeOff : Eye;
  return (
    <button
      type="button"
      aria-pressed={amountsHidden}
      onClick={() => setHidden(!amountsHidden)}
      className={`${BUTTON_SECONDARY} flex items-center justify-center gap-2 ${amountsHidden ? "border-accent text-accent" : ""}`}
    >
      <Icon aria-hidden className="size-5" />
      Hide amounts
    </button>
  );
}
