import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import { reloadEverything } from "./session";

/** The phrase the backend insists on, so a stray request can never erase anything. */
const CONFIRMATION = "erase-all-data";

/** Permanently erases everything and leaves a fresh app. Only call after the person has confirmed. */
export function useEraseAllData() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.delete<{ erased: boolean }>("/api/data", { confirm: CONFIRMATION }),
    // Everything cached came from the erased data; drop it and load the fresh state.
    onSuccess: () => reloadEverything(queryClient),
  });
}
