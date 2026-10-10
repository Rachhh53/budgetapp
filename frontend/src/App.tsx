import type { ReactNode } from "react";
import { Route, Routes } from "react-router";
import { useSettings } from "./api/settings";
import { AppShell } from "./components/AppShell";
import { NAV_ITEMS } from "./nav";
import { AccountDetailPage } from "./pages/AccountDetailPage";
import { AccountsPage } from "./pages/AccountsPage";
import { AssetDetailPage } from "./pages/AssetDetailPage";
import { CategoriesPage } from "./pages/CategoriesPage";
import { ComingSoonPage } from "./pages/ComingSoonPage";
import { IncomeDetailPage } from "./pages/IncomeDetailPage";
import { IncomePage } from "./pages/IncomePage";
import { PayPeriodPage } from "./pages/PayPeriodPage";
import { RecurringItemDetailPage } from "./pages/RecurringItemDetailPage";
import { RecurringItemsPage } from "./pages/RecurringItemsPage";
import { SettingsPage } from "./pages/SettingsPage";
import { useApplyTheme } from "./theme/useApplyTheme";

/** The screens built so far, by path. Every other sidebar entry shows "on its way". */
const PAGES: Record<string, ReactNode> = {
  "/": <PayPeriodPage />,
  "/recurring": <RecurringItemsPage />,
  "/accounts": <AccountsPage />,
  "/income": <IncomePage />,
  "/categories": <CategoriesPage />,
  "/settings": <SettingsPage />,
};

export function App() {
  // Loaded here, above the routes, so the theme applies on every screen.
  useApplyTheme(useSettings().data);

  return (
    <Routes>
      <Route element={<AppShell />}>
        {NAV_ITEMS.map(({ path, label }) => (
          <Route key={path} path={path} element={PAGES[path] ?? <ComingSoonPage title={label} />} />
        ))}
        <Route path="/periods/:start" element={<PayPeriodPage />} />
        <Route path="/accounts/:id" element={<AccountDetailPage />} />
        <Route path="/assets/:id" element={<AssetDetailPage />} />
        <Route path="/income/:id" element={<IncomeDetailPage />} />
        <Route path="/recurring/:id" element={<RecurringItemDetailPage />} />
        <Route path="*" element={<ComingSoonPage title="We couldn't find that screen" />} />
      </Route>
    </Routes>
  );
}
