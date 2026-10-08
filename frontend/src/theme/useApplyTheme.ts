import { useEffect, useSyncExternalStore } from "react";
import type { Settings } from "../api/settings";
import { accentForText, isHexColor, readableTextOn } from "../lib/color";

const DARK_QUERY = "(prefers-color-scheme: dark)";

const DEFAULT_ACCENT = "#0f766e";

/**
 * What accent-colored text is drawn on in each theme: --surface and --bg. Keep in step
 * with theme.css; they are repeated here because the readable accent has to be worked out.
 */
const TEXT_BACKGROUNDS = {
  light: ["#ffffff", "#f1f3f5"],
  dark: ["#1a202b", "#11151c"],
} as const;

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

  const resolved = theme === "system" ? (systemPrefersDark ? "dark" : "light") : theme;

  useEffect(() => {
    document.documentElement.dataset.theme = resolved;
  }, [resolved]);

  // Text in the accent color (links, small actions) must read on this theme's backgrounds,
  // whatever accent was chosen: the default teal is too dark for the dark theme as it is.
  useEffect(() => {
    const chosen = accent !== undefined && isHexColor(accent) ? accent : DEFAULT_ACCENT;
    document.documentElement.style.setProperty("--accent-text", accentForText(chosen, TEXT_BACKGROUNDS[resolved]));
  }, [accent, resolved]);

  useEffect(() => {
    if (accent === undefined || !isHexColor(accent)) {
      return;
    }
    const root = document.documentElement.style;
    root.setProperty("--accent", accent);
    root.setProperty("--on-accent", readableTextOn(accent));
  }, [accent]);
}
