import { useEffect } from "react";
import { useLocation } from "react-router";

/**
 * Where each screen of the sidebar was left, so going back to it within a few minutes returns to
 * the same place: the pay period you were on, the account you had open, the Reports tab
 * (Rachel, 2026-10-20: "when I am on a tab like pay periods and on a pay period and I go to another
 * tab and return, it should keep my state. maybe not forever, but within minutes"). Kept in memory
 * only, so it is gone once the app is closed; a place is only an address, never an amount.
 */

/** How long a screen remembers where it was left. */
export const REMEMBER_FOR_MS = 15 * 60 * 1000;

const left = new Map<string, { to: string; at: number }>();

/** The screen being shown now. */
let here: string | null = null;

/** The sidebar entry a page belongs to: a pay period is under Pay periods, an account's page under Accounts. */
export function sectionOf(pathname: string): string {
  if (pathname === "/" || pathname.startsWith("/periods/")) {
    return "/";
  }
  const first = pathname.split("/")[1] ?? "";
  return `/${first}`;
}

/** Notes where each screen is, as it changes. Used once, around every page. */
export function useRememberWhereLeft() {
  const { pathname, search } = useLocation();
  useEffect(() => {
    const now = Date.now();
    // The minutes count from leaving a screen, not from arriving: a long stay on one still comes back.
    const before = here === null ? undefined : left.get(here);
    if (here !== null && before !== undefined && here !== sectionOf(pathname)) {
      left.set(here, { ...before, at: now });
    }
    here = sectionOf(pathname);
    left.set(here, { to: pathname + search, at: now });
  }, [pathname, search]);
}

/**
 * Where a sidebar entry leads: back to where it was left a few minutes ago, or its own start.
 * From inside its own screen it leads to the start, so a second click still gets back to it.
 */
export function returnTo(section: string, currentPathname: string, now = Date.now()): string {
  if (sectionOf(currentPathname) === section) {
    return section;
  }
  const last = left.get(section);
  return last !== undefined && now - last.at <= REMEMBER_FOR_MS ? last.to : section;
}

/** Forgets every place left, so each test starts fresh. */
export function forgetWhereLeft() {
  left.clear();
  here = null;
}
