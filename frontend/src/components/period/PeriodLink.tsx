import { CalendarDays } from "lucide-react";
import { Link } from "react-router";
import { usePayPeriodsBetween } from "../../api/periods";
import { addDays, formatDay } from "../../lib/dates";
import { BUTTON_SECONDARY } from "../ui";

/** Longer than any pay period, so looking this far back from a day always reaches the period that holds it. */
const LONGEST_PERIOD_DAYS = 45;

/**
 * The way to the pay period this falls in (Rachel, 2026-10-12), to see it among everything else
 * that came in and went out then. Not there when no pay period holds its date: before the first
 * paycheck, say, or while no income has been set up.
 */
export function PeriodLink({ date }: { date: string }) {
  const periods = usePayPeriodsBetween(addDays(date, -LONGEST_PERIOD_DAYS), date);
  const period = periods.data?.periods.find((candidate) => candidate.start <= date && date <= candidate.end);
  if (period === undefined) {
    return null;
  }
  return (
    <Link
      to={`/periods/${period.start}`}
      title={`Open the ${formatDay(period.start)} – ${formatDay(period.end)} pay period`}
      className={`${BUTTON_SECONDARY} inline-flex items-center justify-center gap-2`}
    >
      <CalendarDays aria-hidden className="size-4" />
      See its pay period
    </Link>
  );
}
