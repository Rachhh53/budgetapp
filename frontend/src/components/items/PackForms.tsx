import { CalendarClock, Package } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import { type Item, type ItemSchedule, itemAsInput, useSaveItem } from "../../api/items";
import { useMovePack } from "../../api/paidAhead";
import { formatDate, todayIso } from "../../lib/dates";
import { Modal } from "../Modal";
import { Segmented } from "../Segmented";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

/** The most weeks or months apart a pack can be paid, as the backend allows. */
const MOST_APART = { weeks: 52, months: 60 } as const;

type Unit = keyof typeof MOST_APART;
type When = "as_used" | "scheduled";

const WHEN_OPTIONS: readonly { value: When; label: string }[] = [
  { value: "as_used", label: "When the sessions run out" },
  { value: "scheduled", label: "On its own schedule" },
];

const WHEN_HELP: Record<When, string> = {
  as_used: "Each pack is due on the first session the last one doesn't cover.",
  scheduled: "Each pack is due on these dates. You'll be warned if the sessions run out before the next one.",
};

/** A pack's own schedule as "every 10 weeks" or "every 3 months", with the date it counts from. */
export function describePackSchedule(schedule: ItemSchedule): string {
  const { unit, apart } = apartOf(schedule);
  const every = apart === 1 ? `every ${unit === "weeks" ? "week" : "month"}` : `every ${apart} ${unit}`;
  return `${every} from ${formatDate(schedule.startDate)}`;
}

/** How far apart a pack schedule's dates are, in the unit it was set in. */
function apartOf(schedule: ItemSchedule): { unit: Unit; apart: number } {
  if (schedule.frequency === "weekly") {
    return { unit: "weeks", apart: schedule.interval ?? 1 };
  }
  if (schedule.frequency === "biweekly") {
    return { unit: "weeks", apart: 2 };
  }
  return { unit: "months", apart: schedule.frequency === "every_n_months" ? (schedule.interval ?? 2) : 1 };
}

/** Roughly how many weeks a pack lasts at the item's sessions, to start the form near the right answer. */
function weeksAPackLasts(item: Item): number | null {
  const days = Math.max(1, item.schedule.weekdays?.length ?? 0);
  const perWeek =
    item.schedule.frequency === "weekly" ? days / (item.schedule.interval ?? 1) : item.schedule.frequency === "biweekly" ? days / 2 : null;
  return perWeek === null || item.packSessions == null ? null : Math.max(1, Math.round(item.packSessions / perWeek));
}

/**
 * When each pack of something paid ahead as a pack is paid (Rachel, 2026-10-20: "I want both options"): when the
 * sessions use up the last pack, or on a schedule of its own, every so many weeks or months from a date. Saved
 * from today, like any other change to the item.
 */
export function PackPlanForm({ item, nextPack, onClose }: { item: Item; nextPack: string | null; onClose: () => void }) {
  const id = useId();
  const save = useSaveItem(item.id);
  const current = item.packSchedule ?? null;
  const [when, setWhen] = useState<When>(current === null ? "as_used" : "scheduled");
  const started = current === null ? null : apartOf(current);
  const lasts = weeksAPackLasts(item);
  const [unit, setUnit] = useState<Unit>(started?.unit ?? (lasts !== null ? "weeks" : "months"));
  const [apart, setApart] = useState(String(started?.apart ?? lasts ?? 1));
  const [from, setFrom] = useState(current?.startDate ?? nextPack ?? todayIso());
  const [problem, setProblem] = useState<string>();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    let packSchedule: ItemSchedule | null = null;
    if (when === "scheduled") {
      const value = Number(apart);
      if (apart.trim() === "" || !Number.isInteger(value) || value < 1 || value > MOST_APART[unit]) {
        setProblem(`Please enter how many ${unit} apart, from 1 to ${MOST_APART[unit]}.`);
        return;
      }
      if (from === "") {
        setProblem("Please choose a day a pack is paid.");
        return;
      }
      packSchedule = scheduleFor(unit, value, from);
    }
    setProblem(undefined);
    save.mutate({ ...itemAsInput(item), packSchedule }, { onSuccess: onClose });
  };

  const failure = problem ?? save.error?.message;

  return (
    <Modal title={`When ${item.name} packs are paid`} icon={Package} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <div>
          <Segmented legend="The next pack is due" options={WHEN_OPTIONS} value={when} onChange={setWhen} />
          <p className={HINT}>{WHEN_HELP[when]}</p>
        </div>

        {when === "scheduled" && (
          <>
            <div className="grid grid-cols-2 items-end gap-4">
              <div>
                <label htmlFor={`${id}-apart`} className={LABEL}>
                  Every
                </label>
                <input
                  id={`${id}-apart`}
                  type="number"
                  inputMode="numeric"
                  min={1}
                  max={MOST_APART[unit]}
                  value={apart}
                  onChange={(event) => setApart(event.target.value)}
                  required
                  className={INPUT}
                />
              </div>
              <div>
                <label htmlFor={`${id}-unit`} className={LABEL}>
                  Weeks or months
                </label>
                <select id={`${id}-unit`} value={unit} onChange={(event) => setUnit(event.target.value as Unit)} className={INPUT}>
                  <option value="weeks">Weeks</option>
                  <option value="months">Months</option>
                </select>
              </div>
            </div>
            <div>
              <label htmlFor={`${id}-from`} className={LABEL}>
                A day a pack is paid
              </label>
              <input id={`${id}-from`} type="date" value={from} onChange={(event) => setFrom(event.target.value)} required className={INPUT} />
              <p className={HINT}>It repeats from this date. A payment you record pays the next one due.</p>
            </div>
          </>
        )}

        {failure && (
          <p role="alert" className="text-danger">
            {failure}
          </p>
        )}

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="submit" disabled={save.isPending} className={BUTTON_PRIMARY}>
            {save.isPending ? "Saving…" : "Save"}
          </button>
        </div>
      </form>
    </Modal>
  );
}

/** A pack schedule every `apart` weeks or months from `from`; a monthly one falls on that date's day of the month. */
function scheduleFor(unit: Unit, apart: number, from: string): ItemSchedule {
  const day = Number(from.slice(8, 10));
  const base = { day2: null, month: null, startDate: from, endDate: null, weekendRule: "none" as const, months: [], weekdays: [] };
  if (unit === "weeks") {
    return { ...base, frequency: "weekly", interval: apart, day1: null };
  }
  return apart === 1 ? { ...base, frequency: "monthly", interval: null, day1: day } : { ...base, frequency: "every_n_months", interval: apart, day1: day };
}

/**
 * Changes the day a planned pack is due (Rachel, 2026-10-20: "I also want the option to just change the date"),
 * or puts it back on the day Budget planned it for. Recording the payment is still what pays it.
 */
export function PackDateForm({
  item,
  planned,
  due,
  onClose,
}: {
  item: Pick<Item, "id" | "name">;
  /** The day Budget planned the pack for: what names it. */
  planned: string;
  /** The day it is due now: `planned`, or the day it was moved to. */
  due: string;
  onClose: () => void;
}) {
  const id = useId();
  const move = useMovePack(item.id);
  const [date, setDate] = useState(due);
  const moved = due !== planned;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (date === "") {
      return;
    }
    move.mutate({ planned, date: date === planned ? null : date }, { onSuccess: onClose });
  };

  return (
    <Modal title={`Next ${item.name} pack`} icon={CalendarClock} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <div>
          <label htmlFor={`${id}-date`} className={LABEL}>
            Due on
          </label>
          <input id={`${id}-date`} type="date" value={date} onChange={(event) => setDate(event.target.value)} required className={INPUT} />
          <p className={HINT}>
            {moved ? `Planned for ${formatDate(planned)}.` : "Only this pack moves."} It's still paid when you record the payment.
          </p>
        </div>

        {move.error && (
          <p role="alert" className="text-danger">
            {move.error.message}
          </p>
        )}

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          {moved && (
            <button
              type="button"
              onClick={() => move.mutate({ planned, date: null }, { onSuccess: onClose })}
              disabled={move.isPending}
              className={`${BUTTON_SECONDARY} sm:mr-auto`}
            >
              Put it back on {formatDate(planned)}
            </button>
          )}
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="submit" disabled={move.isPending} className={BUTTON_PRIMARY}>
            {move.isPending ? "Saving…" : "Save"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
