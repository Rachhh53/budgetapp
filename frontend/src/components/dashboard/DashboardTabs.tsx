import { NavLink } from "react-router";

/** The screens gathered under Dashboard (Rachel, 2026-10-12: reports belong with it), in the order of their tabs. */
export const DASHBOARD_TABS = [
  { path: "/dashboard", label: "Overview" },
  { path: "/dashboard/reports", label: "Reports" },
  { path: "/dashboard/annual", label: "Annual costs" },
] as const;

/**
 * The tabs across the top of the Dashboard's three screens. They are links, each with its own
 * address, so Back and a bookmark land where they should; the one in view is marked by an
 * underline and bold type, and told to screen readers as the current page.
 */
export function DashboardTabs() {
  return (
    <nav aria-label="Dashboard" className="mb-4 border-b border-line md:mb-6">
      <ul className="-mb-px flex gap-1 sm:gap-2">
        {DASHBOARD_TABS.map(({ path, label }) => (
          <li key={path} className="min-w-0 flex-1 sm:flex-none">
            <NavLink
              to={path}
              end
              className={({ isActive }) =>
                `flex min-h-11 cursor-pointer items-center justify-center border-b-2 px-2 text-center sm:px-4 ${
                  isActive ? "border-accent font-semibold text-ink" : "border-transparent text-muted hover:border-line hover:text-ink"
                }`
              }
            >
              {label}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  );
}
