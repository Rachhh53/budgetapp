import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";

export const THEMES = ["system", "light", "dark"] as const;
export type Theme = (typeof THEMES)[number];

/** Settings as the backend returns them: every known key with its effective value. */
export interface Settings {
  theme: Theme;
  accent_color: string;
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
    onSuccess: (saved) => queryClient.setQueryData(SETTINGS_KEY, saved),
  });
}
