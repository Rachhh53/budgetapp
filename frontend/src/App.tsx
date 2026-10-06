import { Route, Routes } from "react-router";
import { useSettings } from "./api/settings";
import { AppShell } from "./components/AppShell";
import { NAV_ITEMS } from "./nav";
import { ComingSoonPage } from "./pages/ComingSoonPage";
import { SettingsPage } from "./pages/SettingsPage";
import { useApplyTheme } from "./theme/useApplyTheme";

export function App() {
  // Loaded here, above the routes, so the theme applies on every screen.
  useApplyTheme(useSettings().data);

  return (
    <Routes>
      <Route element={<AppShell />}>
        {NAV_ITEMS.map(({ path, label }) => (
          <Route
            key={path}
            path={path}
            element={path === "/settings" ? <SettingsPage /> : <ComingSoonPage title={label} />}
          />
        ))}
        <Route path="*" element={<ComingSoonPage title="We couldn't find that screen" />} />
      </Route>
    </Routes>
  );
}
