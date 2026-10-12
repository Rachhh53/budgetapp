import { useLocation } from "react-router";

/** A screen to go back to: where its link leads and what it is called. */
export interface CameFrom {
  to: string;
  label: string;
}

/**
 * The Dashboard's tabs open pages that belong to other screens (a recurring item, an expense,
 * an account). Each link carries where it was followed from, so the page's breadcrumb leads
 * back there and not to the screen the page usually sits under.
 */
const TABS = {
  overview: { to: "/dashboard", label: "Dashboard" },
  reports: { to: "/dashboard/reports", label: "Reports" },
  annual: { to: "/dashboard/annual", label: "Annual costs" },
} as const satisfies Record<string, CameFrom>;

/** What a link from one of the Dashboard's tabs passes as its `state`. */
export function cameFrom(tab: keyof typeof TABS): { cameFrom: CameFrom } {
  return { cameFrom: TABS[tab] };
}

/**
 * Where a detail page's breadcrumb leads: the tab its link was followed from, or `usual` when it
 * was opened any other way (its own list, a typed address, a refresh in the installed app's
 * window keeps the state; a new window does not).
 */
export function useCameFrom(usual: CameFrom): CameFrom {
  const state: unknown = useLocation().state;
  const from = typeof state === "object" && state !== null && "cameFrom" in state ? state.cameFrom : null;
  // Only the tabs above are ever followed back to: state is whatever a link put there.
  return Object.values(TABS).find((tab) => typeof from === "object" && from !== null && "to" in from && from.to === tab.to) ?? usual;
}
