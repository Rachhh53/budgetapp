import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { todayIso } from "../lib/dates";
import { api } from "./client";
import { PAY_KEY } from "./income";

export const THEMES = ["system", "light", "dark"] as const;
export type Theme = (typeof THEMES)[number];

/** Settings as the backend returns them: every known key with its effective value. */
/** How far ahead pay periods and balances are projected, in months. */
export const PROJECTION_HORIZONS = ["6", "12", "18", "24", "36"] as const;
export type ProjectionHorizon = (typeof PROJECTION_HORIZONS)[number];

/** Minutes without activity before the app locks itself. */
export const AUTO_LOCK_MINUTES = ["1", "5", "15", "30", "60"] as const;
export type AutoLockMinutes = (typeof AUTO_LOCK_MINUTES)[number];

/** How long before a card's promotional rate ends the warnings start, in days. */
export const PROMO_WARNING_DAYS = ["30", "45", "60", "90", "120"] as const;
export type PromoWarningDays = (typeof PROMO_WARNING_DAYS)[number];

/** How many days ahead a bill paid by hand is pointed out as due soon. "0" turns it off. */
export const UNPAID_HIGHLIGHT_DAYS = ["0", "1", "2", "3", "5", "7", "10", "14"] as const;
export type UnpaidHighlightDays = (typeof UNPAID_HIGHLIGHT_DAYS)[number];

/** How many daily backups are kept. */
export const BACKUP_KEEP_COUNTS = ["7", "14", "30", "60", "90"] as const;
export type BackupKeepCount = (typeof BACKUP_KEEP_COUNTS)[number];

export interface Settings {
  theme: Theme;
  accent_color: string;
  projection_horizon_months: ProjectionHorizon;
  promo_warning_days: PromoWarningDays;
  auto_lock_minutes: AutoLockMinutes;
  start_amounts_hidden: "true" | "false";
  unpaid_highlight_days: UnpaidHighlightDays;
  /** Where backups are saved; empty until a folder is chosen. Changed only through Choose folder. */
  backup_folder: string;
  backup_keep_count: BackupKeepCount;
  /** The day forms for something new open on, as `YYYY-MM-DD`; empty means today. */
  default_date: string;
  /** The day the budget year starts, as `MM-DD`: when next year's plan takes effect. */
  budget_year_start: string;
  /** The day of the year, as `MM-DD`, from which planning next year is suggested. */
  plan_reminder: string;
  /** The network address phone access listens on; empty when it is off. Changed only under Phone access. */
  phone_access_address: string;
  /** Whether Budget starts in the tray when this PC's user signs in to Windows. Changed from this PC only. */
  start_with_windows: "true" | "false";
}

export const SETTINGS_KEY = ["settings"] as const;

/** `enabled` is false while the app is locked: settings sit behind the lock like everything else. */
export function useSettings(enabled = true) {
  return useQuery({ queryKey: SETTINGS_KEY, queryFn: () => api.get<Settings>("/api/settings"), enabled });
}

/**
 * The day a form for something new opens on: the one chosen in Settings, or today. `fromSetting`
 * says which, so a form can say why it did not open on today.
 *
 * Only for what a form starts with. Anything that asks what is past, due or still to come uses
 * `todayIso()`: the setting never moves the real day.
 */
export function useDefaultDate(): { date: string; fromSetting: boolean } {
  const chosen = useSettings().data?.default_date ?? "";
  const today = todayIso();
  // A day after today cannot be saved; one that somehow is (the PC's clock was turned back) is not followed.
  return chosen !== "" && chosen <= today ? { date: chosen, fromSetting: true } : { date: today, fromSetting: false };
}

export function useUpdateSetting() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ key, value }: { key: keyof Settings; value: string }) =>
      api.put<Settings>(`/api/settings/${key}`, { value }),
    // The backend answers with the full, saved settings — use that rather than refetching.
    onSuccess: (saved, { key }) => {
      queryClient.setQueryData(SETTINGS_KEY, saved);
      if (key === "projection_horizon_months" || key === "promo_warning_days" || key === "budget_year_start" || key === "plan_reminder") {
        // Paychecks and pay periods are generated out to the horizon, and a card's promo
        // warnings (on its page and on the pay periods they end in) start from the warning days.
        // Which year can be planned, and whether the Dashboard says so, follow the other two.
        return queryClient.invalidateQueries({ queryKey: PAY_KEY });
      }
    },
  });
}
