import { useId } from "react";
import { useDefaultDate } from "../api/settings";
import { todayIso } from "../lib/dates";
import { DefaultDateHint } from "./settings/DefaultDateHint";
import { HINT, INPUT, LABEL } from "./ui";

/**
 * Archived, and as of which day (Rachel, 2026-10-22: "the previous activity needs to remain"; "this should be true for
 * all things that can be archived"). Before that day the account, item, bucket or asset counts as it always did, in
 * every pay period and total; from that day on it is put away. Ticked, the day opens on the default day chosen in
 * Settings, or today; one already archived keeps its own day.
 */
export function ArchivedField({
  archived,
  archivedOn,
  onChange,
  hint,
  earliest,
}: {
  archived: boolean;
  /** The day it is archived as of; `null` until it is archived. */
  archivedOn: string | null;
  onChange: (archived: boolean, archivedOn: string | null) => void;
  /** What archiving it does, said in a line under the box. */
  hint: string;
  /** A day it cannot be archived before (a bucket's first day). */
  earliest?: string;
}) {
  const id = useId();
  const startOn = useDefaultDate();
  const today = todayIso();
  const opensOn = earliest !== undefined && startOn.date < earliest ? earliest : startOn.date;
  return (
    <div>
      <label className="flex min-h-11 items-center gap-3">
        <input
          type="checkbox"
          checked={archived}
          onChange={(event) => onChange(event.target.checked, event.target.checked ? (archivedOn ?? opensOn) : null)}
          className="size-5 accent-(--accent)"
        />
        <span className="font-medium">Archived</span>
      </label>
      {archived && (
        <div className="mt-2">
          <label htmlFor={`${id}-on`} className={LABEL}>
            Archived as of
          </label>
          <input
            id={`${id}-on`}
            type="date"
            value={archivedOn ?? ""}
            min={earliest}
            max={today}
            onChange={(event) => onChange(true, event.target.value === "" ? null : event.target.value)}
            className={INPUT}
          />
          <DefaultDateHint value={archivedOn ?? ""} openedOn={opensOn === startOn.date ? undefined : opensOn} />
        </div>
      )}
      <p className={HINT}>{hint}</p>
    </div>
  );
}
