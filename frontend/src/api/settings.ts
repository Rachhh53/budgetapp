import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
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
}

export const SETTINGS_KEY = ["settings"] as const;

/** `enabled` is false while the app is locked: settings sit behind the lock like everything else. */
export function useSettings(enabled = true) {
  return useQuery({ queryKey: SETTINGS_KEY, queryFn: () => api.get<Settings>("/api/settings"), enabled });
}

export function useUpdateSetting() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ key, value }: { key: keyof Settings; value: string }) =>
      api.put<Settings>(`/api/settings/${key}`, { value }),
    // The backend answers with the full, saved settings — use that rather than refetching.
    onSuccess: (saved, { key }) => {
      queryClient.setQueryData(SETTINGS_KEY, saved);
      if (key === "projection_horizon_months" || key === "promo_warning_days") {
        // Paychecks and pay periods are generated out to the horizon, and a card's promo
        // warnings (on its page and on the pay periods they end in) start from the warning days.
        return queryClient.invalidateQueries({ queryKey: PAY_KEY });
      }
    },
  });
}
