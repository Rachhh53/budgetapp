import { type Theme, THEMES, useSettings, useUpdateSetting } from "../api/settings";
import { Card } from "../components/Card";

const THEME_LABELS: Record<Theme, string> = {
  system: "Follow Windows",
  light: "Light",
  dark: "Dark",
};

/**
 * Settings. Phase 0 has Appearance → Theme only; the full screen (design/settings.png)
 * arrives in Phase 6. Every value here is picked from a list, never typed.
 */
export function SettingsPage() {
  const settings = useSettings();
  const update = useUpdateSetting();

  return (
    <>
      <h1 className="mb-4 text-2xl font-bold md:mb-6 md:text-3xl">Settings</h1>
      <Card title="Appearance">
        {settings.isPending && <p className="text-muted">Loading your settings…</p>}
        {settings.isError && (
          <p role="alert" className="text-danger">
            {settings.error.message}
          </p>
        )}
        {settings.data && (
          <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
            <label htmlFor="theme" className="font-medium">
              Theme
            </label>
            <select
              id="theme"
              value={settings.data.theme}
              disabled={update.isPending}
              onChange={(event) => update.mutate({ key: "theme", value: event.target.value })}
              className="min-h-11 w-full rounded-lg border border-line bg-surface px-3 sm:w-56"
            >
              {THEMES.map((theme) => (
                <option key={theme} value={theme}>
                  {THEME_LABELS[theme]}
                </option>
              ))}
            </select>
          </div>
        )}
        {update.isError && (
          <p role="alert" className="mt-3 text-danger">
            {update.error.message}
          </p>
        )}
      </Card>
    </>
  );
}
