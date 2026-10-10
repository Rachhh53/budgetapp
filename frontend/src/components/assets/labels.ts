import { Car, Gem, House, type LucideIcon } from "lucide-react";
import type { AssetKind } from "../../api/assets";

export const ASSET_KIND_LABELS: Record<AssetKind, string> = {
  home: "Home",
  vehicle: "Vehicle",
  other: "Something else",
};

export const ASSET_KIND_ICONS: Record<AssetKind, LucideIcon> = {
  home: House,
  vehicle: Car,
  other: Gem,
};
