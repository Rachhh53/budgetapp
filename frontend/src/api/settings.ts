import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import { PAY_KEY } from "./income";

export const THEMES = ["system", "light", "dark"] as const;
export type Theme = (typeof THEMES)[number];

/** Settings as the backend returns them: every known key with its effective value. */
/** How far ahead pay periods and balances are projected, in months. */
export const PROJECTION_HORIZONS = ["6", "12", "18", "24", "36"] as const;
export type ProjectionHorizon = (typeof PROJECTION_HORIZONS)[number];

export interface Settings {
  theme: Theme;
  accent_color: string;
  projection_horizon_months: ProjectionHorizon;
}

const SETTINGS_KEY = ["settings"] as const;

export function useSettings() {
  return useQuery({ queryKey: SETTINGS_KEY, queryFn: () => api.get<Settings>("/api/settings") });
}

export function useUpdateSetting() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ key, value }: { key: keyof Settings; value: string }) =>
      api.put<Settings>(`/api/settings/${key}`, { value }),
    // The backend answers with the full, saved settings — use that rather than refetching.
    onSuccess: (saved, { key }) => {
      queryClient.setQueryData(SETTINGS_KEY, saved);
      if (key === "projection_horizon_months") {
        // Paychecks and pay periods are generated out to the horizon.
        return queryClient.invalidateQueries({ queryKey: PAY_KEY });
      }
    },
  });
}
