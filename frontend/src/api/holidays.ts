import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import { PAY_KEY } from "./income";

export interface Holiday {
  id: number;
  date: string;
  name: string;
  /** `us_bank` holidays are filled in by the app; `user` ones were added by hand. */
  source: "us_bank" | "user";
  /** Switched-off holidays are kept in the list but no longer move paydays. */
  enabled: boolean;
}

export interface HolidayInput {
  date: string;
  name: string;
  enabled?: boolean;
}

const HOLIDAYS_KEY = ["holidays"] as const;

/** Holidays from today to the end of next year. */
export function useHolidays() {
  return useQuery({ queryKey: HOLIDAYS_KEY, queryFn: () => api.get<Holiday[]>("/api/holidays") });
}

function useRefreshHolidays() {
  const queryClient = useQueryClient();
  // Holidays move paydays, so paychecks and pay periods are stale too.
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: HOLIDAYS_KEY }),
      queryClient.invalidateQueries({ queryKey: PAY_KEY }),
    ]);
}

export function useAddHoliday() {
  const refresh = useRefreshHolidays();
  return useMutation({
    mutationFn: (input: HolidayInput) => api.post<{ saved: boolean }>("/api/holidays", input),
    onSuccess: refresh,
  });
}

export function useUpdateHoliday() {
  const refresh = useRefreshHolidays();
  return useMutation({
    mutationFn: ({ id, ...input }: HolidayInput & { id: number }) =>
      api.put<{ saved: boolean }>(`/api/holidays/${id}`, input),
    onSuccess: refresh,
  });
}

export function useDeleteHoliday() {
  const refresh = useRefreshHolidays();
  return useMutation({
    mutationFn: (id: number) => api.delete<{ saved: boolean }>(`/api/holidays/${id}`),
    onSuccess: refresh,
  });
}
