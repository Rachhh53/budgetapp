import { Outlet } from "react-router";
import { PhoneNav } from "./PhoneNav";
import { Sidebar } from "./Sidebar";

/**
 * The frame around every screen: sidebar on desktop, bottom tabs on a phone.
 * The same React app serves both, so the layout switches on width alone.
 */
export function AppShell() {
  return (
    <div className="flex h-dvh">
      <Sidebar />
      {/* pb-14 keeps content clear of the phone tab bar; min-w-0 lets wide content shrink instead of overflowing. */}
      <main className="min-w-0 flex-1 overflow-y-auto p-4 pb-18 md:p-8 md:pb-8">
        <Outlet />
      </main>
      <PhoneNav />
    </div>
  );
}
