import { Link } from "react-router";
import { useDefaultDate } from "../../api/settings";
import { formatDate } from "../../lib/dates";
import { HINT } from "../ui";

/**
 * Under a date that opened on the day chosen in Settings and not on today: says so, and where to
 * change it, so a form dated months back is never a surprise. Shows nothing once the date has been
 * changed, or while forms open on today.
 *
 * `openedOn` is for a date worked out from that day and not the day itself (a card's last statement
 * before it): the day the field opened on.
 */
export function DefaultDateHint({ value, openedOn }: { value: string; openedOn?: string }) {
  const starting = useDefaultDate();
  if (!starting.fromSetting || value !== (openedOn ?? starting.date)) {
    return null;
  }
  const settings = (
    <Link to="/settings" className="font-semibold text-accent-ink underline">
      Settings
    </Link>
  );
  return openedOn !== undefined && openedOn !== starting.date ? (
    <p className={HINT}>
      Opened on {formatDate(openedOn)}, going by {formatDate(starting.date)}, the day chosen in {settings}, not by today.
    </p>
  ) : (
    <p className={HINT}>
      Opened on {formatDate(starting.date)}, the day chosen in {settings}, not today.
    </p>
  );
}
