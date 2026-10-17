import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import { SETTINGS_KEY } from "./settings";

/** One of this PC's networks, as Windows names it. */
export interface Network {
  /** This PC's address on it, e.g. `100.101.102.103`. */
  address: string;
  /** Its name as Windows shows it, e.g. "Tailscale" or "Wi-Fi". */
  name: string;
  /** True when it looks like a VPN tunnel; false for Wi-Fi or a network cable, which aren't encrypted. */
  tunnel: boolean;
}

export type PhoneAccessProblem = "none" | "network_not_connected" | "port_in_use";

export interface PhoneAccessStatus {
  on: boolean;
  /** The address it listens on; empty when it is off. */
  address: string;
  /** What to open on the phone, e.g. `http://100.101.102.103:4873/`; empty when it is off. */
  url: string;
  /** True when a phone can reach Budget right now. */
  listening: boolean;
  /** Why it can't, when it is on but not listening. */
  problem: PhoneAccessProblem;
}

export interface PhoneAccessView {
  status: PhoneAccessStatus;
  /** True on this PC: only there are the networks listed and can phone access be changed. */
  fromThisPc: boolean;
  /** Phone access needs a PIN. */
  pinSet: boolean;
  /** This PC's networks, VPNs first; empty when asked from another device. */
  networks: Network[];
}

export const PHONE_ACCESS_KEY = ["phone-access"] as const;

export function usePhoneAccess() {
  return useQuery({
    queryKey: PHONE_ACCESS_KEY,
    queryFn: () => api.get<PhoneAccessView>("/api/phone-access"),
    // Networks come and go outside the app (a VPN started, the Wi-Fi changed): ask again now and then.
    refetchInterval: 15_000,
  });
}

/** Turns phone access on at `address`, moves it there, or turns it off with `null`. */
export function useChangePhoneAccess() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (address: string | null) => api.put<PhoneAccessView>("/api/phone-access", { address }),
    onSuccess: (view) => {
      queryClient.setQueryData(PHONE_ACCESS_KEY, view);
      // The address is one of the settings too.
      return queryClient.invalidateQueries({ queryKey: SETTINGS_KEY });
    },
  });
}
