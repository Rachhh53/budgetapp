import { useId } from "react";
import { Link } from "react-router";
import { useIncomeSources, useMakePrimary } from "../api/income";
import { useSession } from "../api/session";
import {
  AUTO_LOCK_MINUTES,
  BACKUP_KEEP_COUNTS,
  PROJECTION_HORIZONS,
  PROMO_WARNING_DAYS,
  type Settings,
  type Theme,
  THEMES,
  UNPAID_HIGHLIGHT_DAYS,
  useSettings,
  useUpdateSetting,
} from "../api/settings";
import { Card } from "../components/Card";
import { ColorPicker } from "../components/ColorPicker";
import { EraseAllData } from "../components/EraseAllData";
import { SecurityCard } from "../components/security/SecurityCard";
import { BackupsCard } from "../components/settings/BackupsCard";
import { DefaultDateSetting } from "../components/settings/DefaultDateSetting";
import { YearDaySetting } from "../components/settings/YearDaySetting";
import { HolidaysCard } from "../components/settings/HolidaysCard";
import { PAGE_TITLE } from "../components/ui";
import { HelpLink } from "../components/help/HelpLink";

/** The system the window runs on, by name, for "Follow …"; Windows unless it is plainly a Mac. */
const SYSTEM_NAME = /Mac/i.test(navigator.userAgent) ? "macOS" : "Windows";

const THEME_LABELS: Record<Theme, string> = {
  system: `Follow ${SYSTEM_NAME}`,
  light: "Light",
  dark: "Dark",
};

const SELECT = "min-h-11 w-full rounded-lg border border-line bg-surface px-3 sm:w-56";
/** A setting's name on the left and its control on the right; stacked on a phone. */
const ROW = "flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between";

/**
 * Settings (design/settings.png). Every value is picked from a list or ticked, never typed,
 * and takes effect as soon as it is chosen. Only settings that do something today are here:
 * the budget year and the plan reminder arrive with Plan next year, phone access with its
 * own phase (decided 2026-10-11).
 */
export function SettingsPage() {
  const settings = useSettings();
  const update = useUpdateSetting();
  const pinSet = useSession().data?.pinSet ?? false;
  const choose = (key: keyof Settings) => (value: string) => update.mutate({ key, value });
  const data = settings.data;

  return (
    <>
      <div className="flex min-w-0 items-center gap-1 mb-4 md:mb-6">
        <h1 className={PAGE_TITLE}>Settings</h1>
        <HelpLink />
      </div>
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

        {/* Two columns of cards once there is room for them; one on a phone and a narrow window. */}
        <div className="grid items-start gap-4 md:gap-6 xl:grid-cols-2">
          <div className="space-y-4 md:space-y-6">
            {data && (
              <>
                <Card title="Appearance">
                  <div className="space-y-4">
                    <SettingSelect
                      id="theme"
                      label="Theme"
                      value={data.theme}
                      disabled={update.isPending}
                      onChange={choose("theme")}
                      options={THEMES.map((theme) => ({ value: theme, label: THEME_LABELS[theme] }))}
                    />
                    <div>
                      <ColorPicker label="Accent color" value={data.accent_color} onChange={choose("accent_color")} />
                      <p className="mt-1 text-sm text-muted">Used for buttons, highlights and the current pay period.</p>
                    </div>
                  </div>
                </Card>

                <Card title="Pay periods & projections">
                  <div className="space-y-4">
                    <PayPeriodsFollow />
                    <SettingSelect
                      id="projection-horizon"
                      label="Look ahead"
                      hint="How far into the future pay periods and paychecks are worked out."
                      value={data.projection_horizon_months}
                      disabled={update.isPending}
                      onChange={choose("projection_horizon_months")}
                      options={PROJECTION_HORIZONS.map((months) => ({ value: months, label: `${months} months` }))}
                    />
                    <DefaultDateSetting value={data.default_date} />
                    <YearDaySetting
                      id="budget-year-start"
                      label="Budget year starts"
                      hint="The day next year's plan takes effect. A plan already drawn up keeps the day it was made for."
                      value={data.budget_year_start}
                      disabled={update.isPending}
                      onChange={choose("budget_year_start")}
                    />
                    <YearDaySetting
                      id="plan-reminder"
                      label="Remind me to plan next year"
                      hint="From this day the Dashboard suggests planning, until next year has a plan."
                      value={data.plan_reminder}
                      disabled={update.isPending}
                      onChange={choose("plan_reminder")}
                    />
                  </div>
                </Card>

                <Card title="Warnings">
                  <div className="space-y-4">
                    <SettingSelect
                      id="promo-warning"
                      label="Warn before a card promo ends"
                      hint="How early the warning starts on a card with a balance still on its promotional rate."
                      value={data.promo_warning_days}
                      disabled={update.isPending}
                      onChange={choose("promo_warning_days")}
                      options={PROMO_WARNING_DAYS.map((days) => ({ value: days, label: `${days} days before` }))}
                    />
                    <SettingSelect
                      id="unpaid-highlight"
                      label="Highlight unpaid bills"
                      hint="Bills you pay by hand say when they are due soon, on their pay period."
                      value={data.unpaid_highlight_days}
                      disabled={update.isPending}
                      onChange={choose("unpaid_highlight_days")}
                      options={UNPAID_HIGHLIGHT_DAYS.map((days) => ({
                        value: days,
                        label: days === "0" ? "Don't highlight" : days === "1" ? "1 day before" : `${days} days before`,
                      }))}
                    />
                    <div>
                      <p className="font-medium">Low balance warning</p>
                      <p className="text-sm text-muted">
                        Set for each checking or savings account, on{" "}
                        <Link to="/accounts" className="font-semibold text-accent-ink underline">
                          the account's own page
                        </Link>
                        .
                      </p>
                    </div>
                  </div>
                </Card>
              </>
            )}
          </div>

          <div className="space-y-4 md:space-y-6">
            {data && (
              <SecurityCard>
                <SettingSelect
                  id="auto-lock"
                  label="Lock after"
                  hint={
                    pinSet
                      ? "How long Budget can sit untouched before it asks for the PIN again."
                      : "How long Budget can sit untouched before it locks. Takes effect once a PIN is set."
                  }
                  value={data.auto_lock_minutes}
                  disabled={update.isPending}
                  onChange={choose("auto_lock_minutes")}
                  options={AUTO_LOCK_MINUTES.map((minutes) => ({
                    value: minutes,
                    label: minutes === "1" ? "1 minute" : `${minutes} minutes`,
                  }))}
                />
                <SettingTick
                  label="Start with amounts hidden"
                  hint="Every time Budget opens or is unlocked. Hide amounts shows them again."
                  checked={data.start_amounts_hidden === "true"}
                  disabled={update.isPending}
                  onChange={(checked) => choose("start_amounts_hidden")(checked ? "true" : "false")}
                />
              </SecurityCard>
            )}
            {data && (
              <BackupsCard>
                <SettingSelect
                  id="backup-keep"
                  label="Keep the last"
                  hint="Older backups are removed as new ones are made."
                  value={data.backup_keep_count}
                  disabled={update.isPending}
                  onChange={choose("backup_keep_count")}
                  options={BACKUP_KEEP_COUNTS.map((count) => ({ value: count, label: `${count} backups` }))}
                />
              </BackupsCard>
            )}
            <HolidaysCard />
          </div>
        </div>

        <EraseAllData />
      </div>
    </>
  );
}

/**
 * "Pay periods follow": which paycheck's paydays start each pay period. That is the primary
 * income source, so choosing here is the same as making a source primary on the Income screen.
 */
function PayPeriodsFollow() {
  const sources = useIncomeSources();
  const makePrimary = useMakePrimary();
  const primary = sources.data?.find((source) => source.primary);

  if (sources.isError) {
    return (
      <p role="alert" className="text-danger">
        {sources.error.message}
      </p>
    );
  }
  if (sources.data && sources.data.length === 0) {
    return (
      <div>
        <p className="font-medium">Pay periods follow</p>
        <p className="text-sm text-muted">
          Nothing yet. Add a paycheck on the{" "}
          <Link to="/income" className="font-semibold text-accent-ink underline">
            Income screen
          </Link>{" "}
          and its paydays will start each pay period.
        </p>
      </div>
    );
  }
  return (
    <>
      <SettingSelect
        id="pay-periods-follow"
        label="Pay periods follow"
        hint="The paycheck whose paydays start each pay period."
        value={primary ? String(primary.id) : ""}
        disabled={sources.isPending || makePrimary.isPending}
        onChange={(id) => makePrimary.mutate(Number(id))}
        options={(sources.data ?? []).map((source) => ({ value: String(source.id), label: source.name }))}
      />
      {makePrimary.isError && (
        <p role="alert" className="text-danger">
          {makePrimary.error.message}
        </p>
      )}
    </>
  );
}

interface SettingSelectProps {
  id: string;
  label: string;
  hint?: string;
  value: string;
  disabled: boolean;
  onChange: (value: string) => void;
  options: readonly { value: string; label: string }[];
}

/** One setting chosen from a dropdown: label on the left, list on the right; stacked on a phone. */
function SettingSelect({ id, label, hint, value, disabled, onChange, options }: SettingSelectProps) {
  return (
    <div className={ROW}>
      <div className="min-w-0">
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
        className={`${SELECT} shrink-0`}
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

/** One setting that is on or off. The whole row is the label, so it is an easy target on a phone. */
function SettingTick({
  label,
  hint,
  checked,
  disabled,
  onChange,
}: {
  label: string;
  hint: string;
  checked: boolean;
  disabled: boolean;
  onChange: (checked: boolean) => void;
}) {
  const id = useId();
  return (
    <div className="flex min-h-11 items-start gap-3">
      <input
        id={id}
        type="checkbox"
        checked={checked}
        disabled={disabled}
        aria-describedby={`${id}-hint`}
        onChange={(event) => onChange(event.target.checked)}
        className="mt-1 size-5 shrink-0 accent-(--accent)"
      />
      <div>
        <label htmlFor={id} className="font-medium">
          {label}
        </label>
        <p id={`${id}-hint`} className="text-sm text-muted">
          {hint}
        </p>
      </div>
    </div>
  );
}
