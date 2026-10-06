import { type FormEvent, useId, useState } from "react";
import { type Holiday, useAddHoliday, useDeleteHoliday, useHolidays, useUpdateHoliday } from "../../api/holidays";
import { formatDate, weekdayName } from "../../lib/dates";
import { Card } from "../Card";
import { BUTTON_LINK, BUTTON_SECONDARY, INPUT, LABEL } from "../ui";

/**
 * Settings → Bank holidays. Paydays that land on one of these days move, following each
 * income source's weekend rule. US bank holidays are filled in on this PC; each can be
 * switched off, changed or removed, and the user can add their own.
 */
export function HolidaysCard() {
  const holidays = useHolidays();

  return (
    <Card title="Bank holidays">
      <p className="text-muted">
        A payday that lands on one of these days moves, the same way it does for a weekend. US bank holidays are filled
        in for you — untick any your bank or employer doesn't observe, or add your own.
      </p>

      {holidays.isPending && <p className="mt-3 text-muted">Loading holidays…</p>}
      {holidays.isError && (
        <p role="alert" className="mt-3 text-danger">
          {holidays.error.message}
        </p>
      )}
      {holidays.data?.length === 0 && <p className="mt-3 text-muted">No holidays coming up. Add one below.</p>}

      {holidays.data && holidays.data.length > 0 && (
        <ul className="mt-3 divide-y divide-line">
          {holidays.data.map((holiday) => (
            <HolidayRow key={holiday.id} holiday={holiday} />
          ))}
        </ul>
      )}

      <AddHoliday />
    </Card>
  );
}

function HolidayRow({ holiday }: { holiday: Holiday }) {
  const update = useUpdateHoliday();
  const remove = useDeleteHoliday();
  const [editing, setEditing] = useState(false);
  const busy = update.isPending || remove.isPending;
  const problem = update.error?.message ?? remove.error?.message;

  if (editing) {
    return (
      <li className="py-3">
        <HolidayFields
          submitLabel="Save"
          initial={holiday}
          pending={update.isPending}
          error={update.error?.message}
          onCancel={() => {
            update.reset();
            setEditing(false);
          }}
          onSubmit={(fields) =>
            update.mutate({ id: holiday.id, ...fields, enabled: holiday.enabled }, { onSuccess: () => setEditing(false) })
          }
        />
      </li>
    );
  }

  return (
    <li className="py-2">
      <div className="flex flex-col gap-1 sm:flex-row sm:items-center sm:gap-3">
        <label className="flex min-h-11 min-w-0 flex-1 items-center gap-3">
          <input
            type="checkbox"
            checked={holiday.enabled}
            disabled={busy}
            onChange={(event) =>
              update.mutate({ id: holiday.id, date: holiday.date, name: holiday.name, enabled: event.target.checked })
            }
            className="size-5 shrink-0 accent-(--accent)"
          />
          <span className="min-w-0">
            <span className={`block truncate font-medium ${holiday.enabled ? "" : "text-muted line-through"}`}>
              {holiday.name}
            </span>
            <span className="block text-sm text-muted">
              {weekdayName(holiday.date)}, {formatDate(holiday.date)}
              {holiday.source === "user" && " · added by you"}
              {!holiday.enabled && " · switched off"}
            </span>
          </span>
        </label>
        <div className="flex shrink-0 gap-1">
          <button
            type="button"
            disabled={busy}
            onClick={() => setEditing(true)}
            aria-label={`Change ${holiday.name}, ${formatDate(holiday.date)}`}
            className={BUTTON_LINK}
          >
            Change
          </button>
          <button
            type="button"
            disabled={busy}
            onClick={() => remove.mutate(holiday.id)}
            aria-label={`Remove ${holiday.name}, ${formatDate(holiday.date)}`}
            className={BUTTON_LINK}
          >
            Remove
          </button>
        </div>
      </div>
      {problem && (
        <p role="alert" className="text-sm text-danger">
          {problem}
        </p>
      )}
    </li>
  );
}

function AddHoliday() {
  const add = useAddHoliday();
  // Changing the key empties the fields after a holiday has been added.
  const [round, setRound] = useState(0);
  return (
    <div className="mt-4 border-t border-line pt-4">
      <h3 className="mb-2 font-semibold">Add a holiday</h3>
      <HolidayFields
        key={round}
        submitLabel="Add holiday"
        pending={add.isPending}
        error={add.error?.message}
        onSubmit={(fields) => add.mutate(fields, { onSuccess: () => setRound((current) => current + 1) })}
      />
    </div>
  );
}

interface HolidayFieldsProps {
  submitLabel: string;
  initial?: Pick<Holiday, "date" | "name">;
  pending: boolean;
  error?: string;
  onSubmit: (fields: { date: string; name: string }) => void;
  onCancel?: () => void;
}

/** The date and name fields, shared by adding a holiday and changing one. */
function HolidayFields({ submitLabel, initial, pending, error, onSubmit, onCancel }: HolidayFieldsProps) {
  const id = useId();
  const [date, setDate] = useState(initial?.date ?? "");
  const [name, setName] = useState(initial?.name ?? "");

  const submit = (event: FormEvent) => {
    event.preventDefault();
    onSubmit({ date, name });
  };

  return (
    <form onSubmit={submit} className="grid grid-cols-1 gap-3 sm:grid-cols-[11rem_1fr_auto] sm:items-end">
      <div>
        <label htmlFor={`${id}-date`} className={LABEL}>
          Date
        </label>
        <input
          id={`${id}-date`}
          type="date"
          value={date}
          onChange={(event) => setDate(event.target.value)}
          required
          className={INPUT}
        />
      </div>
      <div>
        <label htmlFor={`${id}-name`} className={LABEL}>
          Holiday name
        </label>
        <input
          id={`${id}-name`}
          value={name}
          onChange={(event) => setName(event.target.value)}
          maxLength={80}
          required
          autoComplete="off"
          className={INPUT}
        />
      </div>
      <div className="flex flex-col gap-2 sm:flex-row">
        <button type="submit" disabled={pending} className={BUTTON_SECONDARY}>
          {pending ? "Saving…" : submitLabel}
        </button>
        {onCancel && (
          <button type="button" onClick={onCancel} className={`${BUTTON_LINK} text-muted`}>
            Cancel
          </button>
        )}
      </div>
      {error && (
        <p role="alert" className="text-danger sm:col-span-3">
          {error}
        </p>
      )}
    </form>
  );
}
