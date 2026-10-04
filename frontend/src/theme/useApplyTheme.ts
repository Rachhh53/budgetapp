import { useEffect, useSyncExternalStore } from "react";
import type { Settings } from "../api/settings";
import { isHexColor, readableTextOn } from "../lib/color";

const DARK_QUERY = "(prefers-color-scheme: dark)";

function subscribeToSystemTheme(onChange: () => void): () => void {
  const query = window.matchMedia(DARK_QUERY);
  query.addEventListener("change", onChange);
  return () => query.removeEventListener("change", onChange);
}

/** Whether Windows is currently in dark mode; updates live when the user switches. */
function useSystemPrefersDark(): boolean {
  return useSyncExternalStore(subscribeToSystemTheme, () => window.matchMedia(DARK_QUERY).matches);
}

/**
 * Applies the user's appearance settings to the page by setting `data-theme` and the
 * accent variables on the root element. theme.css does the rest.
 */
export function useApplyTheme(settings: Settings | undefined): void {
  const systemPrefersDark = useSystemPrefersDark();
  // Until settings arrive, follow Windows so a dark-mode user is never flashed with white.
  const theme = settings?.theme ?? "system";
  const accent = settings?.accent_color;

  useEffect(() => {
    const resolved = theme === "system" ? (systemPrefersDark ? "dark" : "light") : theme;
    document.documentElement.dataset.theme = resolved;
  }, [theme, systemPrefersDark]);

  useEffect(() => {
    if (accent === undefined || !isHexColor(accent)) {
      return;
    }
    const root = document.documentElement.style;
    root.setProperty("--accent", accent);
    root.setProperty("--on-accent", readableTextOn(accent));
  }, [accent]);
}
