import {
  CalendarClock,
  FileText,
  type LucideIcon,
  Percent,
  RefreshCw,
  Target,
  TrendingDown,
  TrendingUp,
  TriangleAlert,
} from "lucide-react";
import { type ReactNode, useState } from "react";
import { Link } from "react-router";
import { cameFrom } from "../lib/cameFrom";
import { type Attention, DASHBOARD_RANGE_LABELS, DASHBOARD_RANGES, type Dashboard, type DashboardRange, useDashboard } from "../api/dashboard";
import { Card } from "../components/Card";
import { DashboardTabs } from "../components/dashboard/DashboardTabs";
import { NetWorthChart } from "../components/dashboard/NetWorthChart";
import { SpendingDonut } from "../components/dashboard/SpendingDonut";
import { signedCents } from "../components/reports/ProjectedResults";
import { Segmented } from "../components/Segmented";
import { PAGE_TITLE } from "../components/ui";
import { formatDate, formatDay } from "../lib/dates";
import { formatCents } from "../lib/money";
import { formatRate } from "../lib/rates";

const RANGE_OPTIONS = DASHBOARD_RANGES.map((range) => ({ value: range, label: DASHBOARD_RANGE_LABELS[range] }));

/**
 * The dashboard (design/dashboard.png): where things stand, in four figures, a ring of spending
 * by category, net worth over time and a list of what is coming up or wants attention. Nothing
 * is entered here: every number is one another screen shows, and each line of the list opens
 * the screen where it is dealt with.
 */
export function DashboardPage() {
  const [range, setRange] = useState<DashboardRange>("three_months");
  const dashboard = useDashboard(range);

  return (
    <>
      <DashboardTabs />
      <div className="mb-4 md:mb-6">
        <p className="text-sm font-semibold tracking-wide text-muted uppercase">Overview</p>
        <h1 className={PAGE_TITLE}>Dashboard</h1>
      </div>

      {dashboard.isPending && <p className="text-muted">Adding everything up…</p>}
      {dashboard.error && (
        <Card>
          <p role="alert" className="text-danger">
            {dashboard.error.message}
          </p>
        </Card>
      )}
      {dashboard.data && (
        <div className="space-y-4">
          <Figures dashboard={dashboard.data} />
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2 2xl:grid-cols-3">
            <Card title="Spending by category">
              <div className="mb-4">
                <Segmented legend="Dates" options={RANGE_OPTIONS} value={range} onChange={setRange} dense />
              </div>
              {dashboard.data.spending.categories.length === 0 ? (
                <p className="text-muted">
                  Nothing was spent between {formatDate(dashboard.data.spending.from)} and {formatDate(dashboard.data.spending.to)}. Bills,
                  budgets and one-off spending show here once there are some.
                </p>
              ) : (
                <>
                  <SpendingDonut categories={dashboard.data.spending.categories} totalCents={dashboard.data.spending.totalCents} />
                  <p className="mt-3 text-sm text-muted">
                    Bills, budgets and one-off spending, {formatDate(dashboard.data.spending.from)} – {formatDate(dashboard.data.spending.to)}
                    {range === "period" && ", with what is still to come in it"}. Transfers and card payments are not spending.
                  </p>
                </>
              )}
            </Card>
            <Card title="Net worth over time">
              <NetWorthChart points={dashboard.data.netWorthOverTime} today={dashboard.data.today} />
            </Card>
            <div className="lg:col-span-2 2xl:col-span-1">
              <Card title="Coming up & needs attention">
                <AttentionList items={dashboard.data.attention} today={dashboard.data.today} />
              </Card>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

/** The four figures, in one card: two across on a phone, four from `lg` up. */
function Figures({ dashboard }: { dashboard: Dashboard }) {
  const { netWorth, lowestAhead, saved, debt } = dashboard;
  const savedParts = [
    saved.transfersCents !== 0 && "transfers",
    saved.contributionsCents !== 0 && "paycheck contributions",
    saved.matchCents !== 0 && "employer match",
  ].filter((part): part is string => part !== false);
  return (
    <Card>
      <div className="grid grid-cols-1 gap-x-6 gap-y-4 sm:grid-cols-2 lg:grid-cols-4">
        <Figure label="Net worth" cents={netWorth.cents}>
          {netWorth.changeCents !== null && netWorth.since !== null ? (
            <>
              <span data-amount>{signedCents(netWorth.changeCents)}</span> since {formatDay(netWorth.since)}
            </>
          ) : (
            "today"
          )}
        </Figure>
        {lowestAhead === null ? (
          <div className="min-w-0">
            <p className="text-muted">Lowest balance ahead</p>
            <p className="text-2xl font-bold">—</p>
            <p className="text-sm text-muted">shows once a checking or savings account has a balance</p>
          </div>
        ) : (
          <Figure label="Lowest balance ahead" cents={lowestAhead.cents}>
            {/* Said in words, never by color alone. */}
            {lowestAhead.low && <span className="font-semibold text-ink">Under your warning · </span>}
            {formatDay(lowestAhead.periodStart)} period · {lowestAhead.accountName}
          </Figure>
        )}
        <Figure label="Saved & invested this year" cents={saved.totalCents}>
          {savedParts.length === 0 ? "transfers, paycheck contributions and employer match" : savedParts.join(", ")}
        </Figure>
        <Figure label="Total debt" cents={debt.totalCents}>
          {debt.totalCents === 0 ? (
            "nothing owed on cards or loans"
          ) : (
            <>
              cards <span data-amount>{formatCents(debt.cardsCents)}</span> · loans <span data-amount>{formatCents(debt.loansCents)}</span>
            </>
          )}
        </Figure>
      </div>
    </Card>
  );
}

function Figure({ label, cents, children }: { label: string; cents: number; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <p className="text-muted">{label}</p>
      <p data-amount className="text-2xl font-bold tabular-nums">
        {formatCents(cents)}
      </p>
      <p className="text-sm text-muted wrap-break-word">{children}</p>
    </div>
  );
}

/** One line of the list, put into words: a short tag for what kind of thing it is, what it says, and where it leads. */
interface Line {
  tag: string;
  icon: LucideIcon;
  title: ReactNode;
  detail: ReactNode;
  to: string;
}

function money(cents: number | null): ReactNode {
  return <span data-amount>{formatCents(cents ?? 0)}</span>;
}

function lineOf(item: Attention, today: string): Line {
  const date = item.date === null ? "" : formatDate(item.date);
  switch (item.kind) {
    case "past_due":
      return {
        tag: "Past due",
        icon: TriangleAlert,
        title: `${item.name} was due ${date}`,
        detail: <>{money(item.amountCents)} · paid by hand and not ticked off yet</>,
        to: `/periods/${item.periodStart}`,
      };
    case "due_soon":
      return {
        tag: "Due soon",
        icon: CalendarClock,
        title: `${item.name} is due ${item.date === today ? "today" : date}`,
        detail: <>{money(item.amountCents)} · paid by hand</>,
        to: `/periods/${item.periodStart}`,
      };
    case "low_balance":
      return {
        tag: "Low balance",
        icon: TrendingDown,
        title: <>{item.name} dips to {money(item.amountCents)}</>,
        detail: `In the pay period starting ${date}, under the warning you set`,
        to: `/periods/${item.periodStart}?account=${item.accountId}`,
      };
    case "promo_ending":
      return {
        tag: "Promo",
        icon: Percent,
        title: `${item.name} ends ${date}`,
        detail: (
          <>
            {money(item.amountCents)} is projected to be left on it
            {item.urgent && ". If it is, interest for the whole promotion is charged that day"}
          </>
        ),
        to: `/accounts/${item.accountId}`,
      };
    case "stub_review":
      return {
        tag: "Pay stub",
        icon: FileText,
        title: `A ${item.name} pay stub is waiting for review`,
        detail:
          item.count === 0 ? "Every line was read; it only needs saving" : item.count === 1 ? "1 line to check" : `${item.count} lines to check`,
        to: `/income/${item.sourceId}/stubs/${item.stubId}`,
      };
    case "goal_short":
      return {
        tag: "Goal",
        icon: Target,
        title: `${item.name} is short of its goal`,
        detail: (
          <>
            {money(item.amountCents)} short of {money(item.goalCents)} by {date}, at what is scheduled
          </>
        ),
        to: `/accounts/${item.accountId}`,
      };
    case "price_up":
      return {
        tag: "Price",
        icon: TrendingUp,
        title: `${item.name} is up ${formatRate(item.count ?? 0)} on last year`,
        detail: <>{money(item.amountCents)} more over the year</>,
        to: `/recurring/${item.itemId}`,
      };
    case "balance_stale": {
      const others = (item.count ?? 1) - 1;
      return {
        tag: "Update",
        icon: RefreshCw,
        title: `${item.name} balance last entered ${date}`,
        detail:
          others === 0
            ? "Enter the real number whenever you want one"
            : `And ${others === 1 ? "1 more account" : `${others} more accounts`} over a month old. Enter the real numbers whenever you want them`,
        to: "/accounts",
      };
    }
  }
}

/** Each line opens the screen where it is dealt with: the whole row is the link, with the hand cursor and a highlight. */
function AttentionList({ items, today }: { items: readonly Attention[]; today: string }) {
  if (items.length === 0) {
    return <p className="text-muted">Nothing needs your attention right now. Bills due, low balances and promotions ending show here.</p>;
  }
  return (
    <ul className="divide-y divide-line">
      {items.map((item, index) => {
        const line = lineOf(item, today);
        return (
          // Two bills can share a name and a day; nothing but its place tells them apart.
          <li key={index}>
            <Link to={line.to} state={cameFrom("overview")} className="-mx-2 flex min-h-11 cursor-pointer items-start gap-3 rounded-lg px-2 py-3 hover:bg-bg/60">
              <span className="flex w-28 shrink-0 items-center gap-1.5 rounded-md bg-bg px-2 py-1 text-xs font-semibold tracking-wide uppercase">
                <line.icon aria-hidden className="size-4 shrink-0" />
                {line.tag}
              </span>
              <span className="min-w-0 flex-1">
                <span className="block font-semibold wrap-break-word">{line.title}</span>
                <span className="block text-sm text-muted wrap-break-word">{line.detail}</span>
              </span>
            </Link>
          </li>
        );
      })}
    </ul>
  );
}
