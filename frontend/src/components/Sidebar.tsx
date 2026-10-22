import { NavLink, useLocation } from "react-router";
import { returnTo, sectionOf } from "../lib/sectionMemory";
import { NAV_ITEMS } from "../nav";
import { LockNowButton } from "./security/LockNow";

export const PRIVACY_NOTE = "Data stored only on this PC";

/** The desktop navigation: always-dark column on the left, shown from the `md` breakpoint up. */
export function Sidebar() {
  const { pathname } = useLocation();
  return (
    <aside className="hidden w-60 shrink-0 flex-col bg-sidebar text-sidebar-ink md:flex">
      <p className="px-6 pt-6 pb-4 text-xl font-bold text-white">Budget</p>

      <nav aria-label="Main" className="flex-1 overflow-y-auto px-3">
        <ul className="space-y-1">
          {NAV_ITEMS.map(({ path, label, icon: Icon }) => (
            <li key={path}>
              {/* Back to where this screen was left a few minutes ago, if it was (lib/sectionMemory). */}
              <NavLink
                to={returnTo(path, pathname)}
                aria-current={sectionOf(pathname) === path ? "page" : undefined}
                className={`flex items-center gap-3 rounded-lg px-3 py-2.5 text-[0.95rem] ${
                  sectionOf(pathname) === path ? "bg-sidebar-active font-semibold text-white" : "hover:bg-sidebar-active/60"
                }`}
              >
                <Icon aria-hidden className="size-4 shrink-0" />
                {label}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>

      <div className="px-3 pt-3">
        <LockNowButton className="flex min-h-11 w-full items-center gap-3 rounded-lg border border-sidebar-active px-3 text-[0.95rem] hover:bg-sidebar-active/60 disabled:opacity-60" />
      </div>
      <p className="px-6 py-5 text-sm text-sidebar-muted">{PRIVACY_NOTE}</p>
    </aside>
  );
}
