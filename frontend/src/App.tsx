import { useQueryClient } from "@tanstack/react-query";
import { type ReactNode, useEffect, useRef, useState } from "react";
import { Navigate, Route, Routes, useParams } from "react-router";
import { onLocked, setPinRequired } from "./api/client";
import { forgetLoadedData, SESSION_KEY, type SessionStatus, useLock, useSession } from "./api/session";
import { useSettings } from "./api/settings";
import { AppShell } from "./components/AppShell";
import { hideAmountsNow } from "./components/HideAmounts";
import { LockScreen } from "./components/security/LockScreen";
import { PinOffer } from "./components/security/PinOffer";
import { useAutoLock } from "./components/security/useAutoLock";
import { NAV_ITEMS } from "./nav";
import { AccountDetailPage } from "./pages/AccountDetailPage";
import { AccountsPage } from "./pages/AccountsPage";
import { AssetDetailPage } from "./pages/AssetDetailPage";
import { CategoriesPage } from "./pages/CategoriesPage";
import { ComingSoonPage } from "./pages/ComingSoonPage";
import { ExpenseDetailPage } from "./pages/ExpenseDetailPage";
import { ExpensesPage } from "./pages/ExpensesPage";
import { IncomeDetailPage } from "./pages/IncomeDetailPage";
import { IncomePage } from "./pages/IncomePage";
import { PayPeriodPage } from "./pages/PayPeriodPage";
import { RecurringItemDetailPage } from "./pages/RecurringItemDetailPage";
import { RecurringItemsPage } from "./pages/RecurringItemsPage";
import { ReportsPage } from "./pages/ReportsPage";
import { SettingsPage } from "./pages/SettingsPage";
import { useApplyTheme } from "./theme/useApplyTheme";

/** The screens built so far, by path. Every other sidebar entry shows "on its way". */
const PAGES: Record<string, ReactNode> = {
  "/": <PayPeriodPage />,
  "/recurring": <RecurringItemsPage />,
  "/accounts": <AccountsPage />,
  "/income": <IncomePage />,
  "/expenses": <ExpensesPage />,
  "/reports": <ReportsPage />,
  "/categories": <CategoriesPage />,
  "/settings": <SettingsPage />,
};

/** Until the setting has loaded; the same as its default on the backend. */
const DEFAULT_AUTO_LOCK_MINUTES = 15;

/**
 * The lock in front of everything: nothing of the app is drawn, and nothing of the budget
 * is asked for, until the backend has said whether a PIN stands in the way.
 */
export function App() {
  const queryClient = useQueryClient();
  const session = useSession();
  const locked = session.data !== undefined && session.data.pinSet && !session.data.unlocked;
  // Loaded here, above the routes, so the theme applies on every screen, the lock screen too
  // (from what was loaded before it locked; on a cold start it follows Windows).
  const settings = useSettings((session.data !== undefined && !locked) || session.isError);
  useApplyTheme(settings.data);

  const pinSet = session.data?.pinSet ?? false;
  useEffect(() => setPinRequired(pinSet), [pinSet]);

  // A request found the session gone (it ran out on the backend): show the lock.
  useEffect(
    () =>
      onLocked(() => {
        forgetLoadedData(queryClient);
        queryClient.setQueryData(SESSION_KEY, (status: SessionStatus | undefined) =>
          status ? { ...status, unlocked: false, pinSet: true } : status,
        );
        void queryClient.invalidateQueries({ queryKey: SESSION_KEY });
      }),
    [queryClient],
  );

  // Held open here rather than by the backend's flag: setting a PIN answers the offer at once,
  // but its dialog still has the recovery code to show.
  const [offering, setOffering] = useState(false);
  const offerPin = session.data?.offerPin ?? false;
  useEffect(() => {
    if (offerPin) {
      setOffering(true);
    }
  }, [offerPin]);

  // "Start with amounts hidden": once each time the app opens or is unlocked, not on every
  // change of the setting, so pressing Hide amounts to look at a number is not undone.
  const startHidden = settings.data?.start_amounts_hidden;
  const appliedStartHidden = useRef(false);
  useEffect(() => {
    if (locked) {
      appliedStartHidden.current = false;
    } else if (startHidden !== undefined && !appliedStartHidden.current) {
      appliedStartHidden.current = true;
      if (startHidden === "true") {
        hideAmountsNow();
      }
    }
  }, [locked, startHidden]);

  const lock = useLock();
  useAutoLock(
    Number(settings.data?.auto_lock_minutes ?? DEFAULT_AUTO_LOCK_MINUTES),
    pinSet && !locked,
    () => lock.mutate(),
    () => void queryClient.invalidateQueries({ queryKey: SESSION_KEY }),
  );

  if (session.isPending) {
    return (
      <div aria-busy="true" className="h-dvh bg-bg">
        <p role="status" className="sr-only">
          Opening Budget…
        </p>
      </div>
    );
  }
  if (locked && session.data) {
    return <LockScreen status={session.data} />;
  }
  // If the backend could not be asked at all, the screens say so themselves.
  return (
    <>
      <Screens />
      {offering && <PinOffer onDone={() => setOffering(false)} />}
    </>
  );
}

function Screens() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        {NAV_ITEMS.map(({ path, label }) => (
          <Route key={path} path={path} element={PAGES[path] ?? <ComingSoonPage title={label} />} />
        ))}
        <Route path="/periods/:start" element={<PayPeriodPage />} />
        <Route path="/accounts/:id" element={<AccountDetailPage />} />
        {/* Cards live under Accounts; an address that names a card still lands on its page. */}
        <Route path="/cards" element={<Navigate to="/accounts" replace />} />
        <Route path="/cards/:id" element={<CardRedirect />} />
        <Route path="/assets/:id" element={<AssetDetailPage />} />
        <Route path="/income/:id" element={<IncomeDetailPage />} />
        <Route path="/expenses/:id" element={<ExpenseDetailPage />} />
        <Route path="/recurring/:id" element={<RecurringItemDetailPage />} />
        <Route path="*" element={<ComingSoonPage title="We couldn't find that screen" />} />
      </Route>
    </Routes>
  );
}

/** A card's old address, sent on to its page under Accounts. */
function CardRedirect() {
  const { id } = useParams();
  return <Navigate to={`/accounts/${id}`} replace />;
}
