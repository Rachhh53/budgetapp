import {
  PROJECTION_HORIZONS,
  type Settings,
  type Theme,
  THEMES,
  useSettings,
  useUpdateSetting,
} from "../api/settings";
import { Card } from "../components/Card";
import { EraseAllData } from "../components/EraseAllData";
import { HolidaysCard } from "../components/settings/HolidaysCard";
import { PAGE_TITLE } from "../components/ui";

const THEME_LABELS: Record<Theme, string> = {
  system: "Follow Windows",
  light: "Light",
  dark: "Dark",
};

/**
 * Settings. So far: Appearance → Theme, the projection horizon, bank holidays and Erase all
 * data; the full screen (design/settings.png) arrives in Phase 6. Every value here is
 * picked from a list, never typed.
 */
export function SettingsPage() {
  const settings = useSettings();
  const update = useUpdateSetting();

  return (
    <>
      <h1 className={`${PAGE_TITLE} mb-4 md:mb-6`}>Settings</h1>
      <div className="space-y-4 md:space-y-6">
        {settings.isPending && <p className="text-muted">Loading your settings…</p>}
        {settings.isError && (
          <p role="alert" className="text-danger">
            {settings.error.message}
          </p>
        )}
        {update.isError && (
          <p role="alert" className="text-danger">
            {update.error.message}
          </p>
        )}

        {settings.data && (
          <>
            <Card title="Appearance">
              <SettingSelect
                id="theme"
                label="Theme"
                value={settings.data.theme}
                disabled={update.isPending}
                onChange={(value) => update.mutate({ key: "theme", value })}
                options={THEMES.map((theme) => ({ value: theme, label: THEME_LABELS[theme] }))}
              />
            </Card>
            <Card title="Pay periods & projections">
              <SettingSelect
                id="projection-horizon"
                label="Look ahead"
                hint="How far into the future pay periods and paychecks are worked out."
                value={settings.data.projection_horizon_months}
                disabled={update.isPending}
                onChange={(value) => update.mutate({ key: "projection_horizon_months", value })}
                options={PROJECTION_HORIZONS.map((months) => ({ value: months, label: `${months} months` }))}
              />
            </Card>
          </>
        )}

        <HolidaysCard />
        <EraseAllData />
      </div>
    </>
  );
}

interface SettingSelectProps {
  id: string;
  label: string;
  hint?: string;
  value: Settings[keyof Settings];
  disabled: boolean;
  onChange: (value: string) => void;
  options: readonly { value: string; label: string }[];
}

/** One setting chosen from a dropdown: label on the left, list on the right; stacked on a phone. */
function SettingSelect({ id, label, hint, value, disabled, onChange, options }: SettingSelectProps) {
  return (
    <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
      <div>
        <label htmlFor={id} className="font-medium">
          {label}
        </label>
        {hint && (
          <p id={`${id}-hint`} className="text-sm text-muted">
            {hint}
          </p>
        )}
      </div>
      <select
        id={id}
        value={value}
        disabled={disabled}
        aria-describedby={hint ? `${id}-hint` : undefined}
        onChange={(event) => onChange(event.target.value)}
        className="min-h-11 w-full rounded-lg border border-line bg-surface px-3 sm:w-56"
      >
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </div>
  );
}
