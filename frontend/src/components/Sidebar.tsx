import { NavLink } from "react-router";
import { NAV_ITEMS } from "../nav";

export const PRIVACY_NOTE = "Data stored only on this PC";

/** The desktop navigation: always-dark column on the left, shown from the `md` breakpoint up. */
export function Sidebar() {
  return (
    <aside className="hidden w-60 shrink-0 flex-col bg-sidebar text-sidebar-ink md:flex">
      <p className="px-6 pt-6 pb-4 text-xl font-bold text-white">Budget</p>

      <nav aria-label="Main" className="flex-1 overflow-y-auto px-3">
        <ul className="space-y-1">
          {NAV_ITEMS.map(({ path, label, icon: Icon }) => (
            <li key={path}>
              <NavLink
                to={path}
                end={path === "/"}
                className={({ isActive }) =>
                  `flex items-center gap-3 rounded-lg px-3 py-2.5 text-[0.95rem] ${
                    isActive ? "bg-sidebar-active font-semibold text-white" : "hover:bg-sidebar-active/60"
                  }`
                }
              >
                <Icon aria-hidden className="size-4 shrink-0" />
                {label}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>

      <p className="px-6 py-5 text-sm text-sidebar-muted">{PRIVACY_NOTE}</p>
    </aside>
  );
}
