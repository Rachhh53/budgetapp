import { CalendarSearch } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import { api } from "../../api/client";
import type { PayPeriods } from "../../api/income";
import { formatDate } from "../../lib/dates";
import { Modal } from "../Modal";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

/**
 * Jumps to the pay period a chosen day falls in (Rachel, 2026-10-20: "add a date selector on the
 * pay periods tab. scrolling back is hard"), rather than stepping back one period at a time.
 *
 * @param onGo the start of the period found, to open it
 */
export function GoToDate({ shownStart, onGo }: { shownStart: string; onGo: (start: string) => void }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        aria-label="Go to a date"
        title="Go to a date"
        className="flex size-11 shrink-0 items-center justify-center rounded-lg border border-line bg-surface"
      >
        <CalendarSearch aria-hidden className="size-5" />
      </button>
      {open && (
        <GoToDateForm
          shownStart={shownStart}
          onClose={() => setOpen(false)}
          onGo={(start) => {
            setOpen(false);
            onGo(start);
          }}
        />
      )}
    </>
  );
}

function GoToDateForm({ shownStart, onClose, onGo }: { shownStart: string; onClose: () => void; onGo: (start: string) => void }) {
  const id = useId();
  const [date, setDate] = useState(shownStart);
  const [looking, setLooking] = useState(false);
  const [problem, setProblem] = useState<string>();

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (date === "") {
      setProblem("Please choose a day.");
      return;
    }
    setLooking(true);
    setProblem(undefined);
    try {
      const found = await api.get<PayPeriods>(`/api/pay-periods?from=${date}&to=${date}`);
      const period = found.periods.find((candidate) => candidate.start <= date && date <= candidate.end);
      if (period) {
        onGo(period.start);
      } else {
        setProblem(`There's no pay period on ${formatDate(date)}. Pay periods start with your primary income's first payday.`);
      }
    } catch (error) {
      setProblem(error instanceof Error ? error.message : "We couldn't look that up just now. Please try again in a moment.");
    } finally {
      setLooking(false);
    }
  };

  return (
    <Modal title="Go to a date" icon={CalendarSearch} onClose={onClose}>
      <form onSubmit={submit} noValidate className="space-y-4">
        <div>
          <label htmlFor={`${id}-date`} className={LABEL}>
            Day
          </label>
          <input id={`${id}-date`} type="date" value={date} onChange={(event) => setDate(event.target.value)} className={INPUT} />
          <p className={HINT}>Opens the pay period that day falls in.</p>
        </div>
        {problem && (
          <p role="alert" className="text-danger">
            {problem}
          </p>
        )}
        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="submit" disabled={looking} className={BUTTON_PRIMARY}>
            {looking ? "Looking…" : "Go"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
