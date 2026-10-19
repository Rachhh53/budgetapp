import { Ellipsis, X } from "lucide-react";
import { useEffect, useState } from "react";
import { NavLink, useLocation } from "react-router";
import { NAV_ITEMS } from "../nav";
import { LockNowButton } from "./security/LockNow";
import { PRIVACY_NOTE } from "./Sidebar";

const PRIMARY = NAV_ITEMS.filter((item) => item.primaryOnPhone);
const MORE = NAV_ITEMS.filter((item) => !item.primaryOnPhone);

/* 44px minimum touch targets throughout (min-h-11 = 2.75rem). */
const TAB = "flex min-h-11 flex-1 flex-col items-center justify-center gap-0.5 px-1 py-1.5 text-xs";

/**
 * Phone navigation, shown below the `md` breakpoint: a bottom tab bar with the three most
 * used screens, and a "More" sheet listing the rest.
 */
export function PhoneNav() {
  const [moreOpen, setMoreOpen] = useState(false);
  const { pathname } = useLocation();
  const onMoreScreen = MORE.some((item) => item.path === pathname);

  // Choosing a screen from the sheet should also put the sheet away.
  useEffect(() => setMoreOpen(false), [pathname]);

  return (
    <div className="md:hidden">
      {moreOpen && (
        <div
          id="more-screens"
          role="dialog"
          aria-label="More screens"
          className="fixed inset-x-0 top-0 bottom-[calc(3.5rem+env(safe-area-inset-bottom))] z-10 overflow-y-auto bg-surface p-4 pt-[max(1rem,env(safe-area-inset-top))]"
        >
          <ul className="space-y-1">
            {MORE.map(({ path, label, icon: Icon }) => (
              <li key={path}>
                <NavLink
                  to={path}
                  className={({ isActive }) =>
                    `flex min-h-11 items-center gap-3 rounded-lg px-3 text-base ${
                      isActive ? "bg-bg font-semibold text-accent-ink" : "text-ink"
                    }`
                  }
                >
                  <Icon aria-hidden className="size-5 shrink-0" />
                  {label}
                </NavLink>
              </li>
            ))}
          </ul>
          <div className="pt-4">
            <LockNowButton className="flex min-h-11 w-full items-center gap-3 rounded-lg border border-line px-3 text-base disabled:opacity-60" />
          </div>
          <p className="px-3 pt-6 text-sm text-muted">{PRIVACY_NOTE}</p>
        </div>
      )}

      <nav
        aria-label="Main"
        // On an iPhone from the Home Screen the bar sits above the home bar, not under it.
        className="fixed inset-x-0 bottom-0 z-20 flex h-[calc(3.5rem+env(safe-area-inset-bottom))] border-t border-line bg-surface pb-[env(safe-area-inset-bottom)]"
      >
        {PRIMARY.map(({ path, label, icon: Icon }) => (
          <NavLink
            key={path}
            to={path}
            end={path === "/"}
            className={({ isActive }) =>
              `${TAB} ${isActive && !moreOpen ? "font-semibold text-accent-ink" : "text-muted"}`
            }
          >
            <Icon aria-hidden className="size-5" />
            {label}
          </NavLink>
        ))}
        <button
          type="button"
          aria-expanded={moreOpen}
          aria-controls="more-screens"
          onClick={() => setMoreOpen((open) => !open)}
          className={`${TAB} ${moreOpen || onMoreScreen ? "font-semibold text-accent-ink" : "text-muted"}`}
        >
          {moreOpen ? <X aria-hidden className="size-5" /> : <Ellipsis aria-hidden className="size-5" />}
          {moreOpen ? "Close" : "More"}
        </button>
      </nav>
    </div>
  );
}
