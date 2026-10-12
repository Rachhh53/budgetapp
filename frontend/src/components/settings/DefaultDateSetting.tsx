import { useId, useState } from "react";
import { useUpdateSetting } from "../../api/settings";
import { formatDate, todayIso } from "../../lib/dates";
import { Segmented } from "../Segmented";
import { BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

type Choice = "today" | "chosen";

const CHOICES: readonly { value: Choice; label: string }[] = [
  { value: "today", label: "Today" },
  { value: "chosen", label: "A day I choose" },
];

/**
 * "New things start on" (Rachel, 2026-10-12): entering a year from its first day, every form would
 * otherwise open on today and have to be turned back by hand. One tap on Today carries on from the
 * present again.
 */
export function DefaultDateSetting({ value }: { value: string }) {
  const id = useId();
  const update = useUpdateSetting();
  const today = todayIso();
  // "A day I choose" can be picked before the day is: the date field then shows, still empty.
  const [choosing, setChoosing] = useState(false);
  const [typed, setTyped] = useState(value);
  const choice: Choice = value !== "" || choosing ? "chosen" : "today";
  const save = (date: string) => update.mutate({ key: "default_date", value: date });

  return (
    <div className="space-y-2">
      <Segmented
        legend="New things start on"
        options={CHOICES}
        value={choice}
        onChange={(next) => {
          setChoosing(next === "chosen");
          if (next === "today" && value !== "") {
            save("");
          }
        }}
      />
      {choice === "chosen" && (
        <div className="flex flex-col gap-2 sm:flex-row sm:items-end">
          <div className="sm:w-56">
            <label htmlFor={`${id}-date`} className={LABEL}>
              The day
            </label>
            <input
              id={`${id}-date`}
              type="date"
              value={typed}
              min="2000-01-01"
              max={today}
              onChange={(event) => setTyped(event.target.value)}
              className={INPUT}
            />
          </div>
          <button
            type="button"
            disabled={update.isPending || typed === "" || typed === value || typed > today}
            onClick={() => save(typed)}
            className={BUTTON_SECONDARY}
          >
            {update.isPending ? "Saving…" : "Use this day"}
          </button>
        </div>
      )}
      {typed > today && choice === "chosen" && (
        <p role="alert" className="text-sm text-danger">
          Please choose today or an earlier day.
        </p>
      )}
      <p className={HINT}>
        {value === ""
          ? "The date a form opens on when you add something: an expense, a balance, a bill, a paycheck, a bucket."
          : `Forms for something new open on ${formatDate(value)}. Each one says so, and the date can still be changed there.`}{" "}
        What is due, overdue or current always goes by the real day.
      </p>
    </div>
  );
}
