import {
  CalendarRange,
  ChartPie,
  CreditCard,
  FileChartColumn,
  Landmark,
  LayoutDashboard,
  type LucideIcon,
  NotebookPen,
  Receipt,
  Repeat,
  Scale,
  Settings,
  Tags,
  Wallet,
} from "lucide-react";

export interface NavItem {
  path: string;
  label: string;
  icon: LucideIcon;
  /** Shown in the phone's bottom bar; everything else lives behind "More". */
  primaryOnPhone?: boolean;
}

/** Every screen, in sidebar order (spec §8). */
export const NAV_ITEMS: readonly NavItem[] = [
  { path: "/", label: "Pay periods", icon: CalendarRange, primaryOnPhone: true },
  { path: "/dashboard", label: "Dashboard", icon: LayoutDashboard, primaryOnPhone: true },
  { path: "/recurring", label: "Recurring items", icon: Repeat },
  { path: "/accounts", label: "Accounts", icon: Landmark, primaryOnPhone: true },
  { path: "/cards", label: "Credit cards", icon: CreditCard },
  { path: "/income", label: "Income", icon: Wallet },
  { path: "/expenses", label: "Expenses & receipts", icon: Receipt },
  { path: "/reports", label: "Reports", icon: FileChartColumn },
  { path: "/annual", label: "Annual costs", icon: ChartPie },
  { path: "/plan", label: "Plan next year", icon: NotebookPen },
  { path: "/planner", label: "Debt & decisions", icon: Scale },
  { path: "/categories", label: "Categories & tags", icon: Tags },
  { path: "/settings", label: "Settings", icon: Settings },
];
