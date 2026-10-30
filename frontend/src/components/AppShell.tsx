import { Outlet } from "react-router";
import { useRememberWhereLeft } from "../lib/sectionMemory";
import { AddItemProvider } from "./add/AddItem";
import { useApplyHideAmounts } from "./HideAmounts";
import { PhoneNav } from "./PhoneNav";
import { Sidebar } from "./Sidebar";
import { UndoToast } from "./UndoToast";

/**
 * The frame around every screen: sidebar on desktop, bottom tabs on a phone.
 * The same React app serves both, so the layout switches on width alone.
 *
 * What belongs to every screen lives here too: the + Add item form and its shortcuts, the
 * Undo toast, and whether amounts are hidden.
 */
export function AppShell() {
  useApplyHideAmounts();
  useRememberWhereLeft();
  return (
    <AddItemProvider>
      <div className="flex h-dvh">
        <Sidebar />
        {/* pb-14 keeps content clear of the phone tab bar; min-w-0 lets wide content shrink instead of overflowing. */}
        {/* relative: visually hidden text is absolutely positioned, and without this it is placed against
            the window, which then grows a second scroll bar beside this one. */}
        {/* On an iPhone from the Home Screen, also clear of the notch above and the home bar below. */}
        {/* overflow-x-hidden: scrolling up and down would otherwise let it be dragged sideways too; what is
            wider than a phone (the pay period strip, a table) scrolls inside its own box instead. */}
        <main className="relative min-w-0 flex-1 overflow-x-hidden overflow-y-auto p-4 pt-[max(1rem,env(safe-area-inset-top))] pb-[calc(4.5rem+env(safe-area-inset-bottom))] md:p-8 md:pb-8 md:short:p-5">
          <Outlet />
        </main>
        <PhoneNav />
        <UndoToast />
      </div>
    </AddItemProvider>
  );
}
