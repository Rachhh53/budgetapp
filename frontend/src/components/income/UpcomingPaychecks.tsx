import { type FormEvent, useState } from "react";
import { type Paycheck, paycheckKey, useEditPaycheck, usePaychecks } from "../../api/income";
import { formatDay, formatWeekdayDay } from "../../lib/dates";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { Amount, EstimateKey } from "../Amount";
import { Card } from "../Card";
import { AMOUNT_HELP } from "../MoneyField";
import { BUTTON_LINK, INPUT } from "../ui";
import { movedReason } from "./schedule";

const SHOWN = 8;

/**
 * The next few paychecks from every source, with the reason whenever a weekend or holiday
 * moved one. Any amount can be typed over — "just this one" — and stops being an estimate
 * once it has been.
 */
export function UpcomingPaychecks({ sourceId }: { /** Only this source's paychecks; every source's when left out. */ sourceId?: number }) {
  const paychecks = usePaychecks();
  const upcoming = (paychecks.data ?? [])
    .filter((paycheck) => sourceId === undefined || paycheck.incomeSourceId === sourceId)
    .slice(0, SHOWN);

  return (
    <Card title="Upcoming paychecks">
      {paychecks.isPending && <p className="text-muted">Loading your paychecks…</p>}
      {paychecks.isError && (
        <p role="alert" className="text-danger">
          {paychecks.error.message}
        </p>
      )}
      {paychecks.data && upcoming.length === 0 && (
        <p className="text-muted">Paychecks will show up here once you add an income source.</p>
      )}
      {upcoming.length > 0 && (
        <>
          <ul className="divide-y divide-line">
            {upcoming.map((paycheck) => (
              <PaycheckRow key={paycheckKey(paycheck)} paycheck={paycheck} />
            ))}
          </ul>
          <div className="mt-3 space-y-1">
            {upcoming.some((paycheck) => paycheck.estimate) && <EstimateKey />}
            <p className="text-sm text-muted">Select an amount to change just that paycheck.</p>
          </div>
        </>
      )}
    </Card>
  );
}

function PaycheckRow({ paycheck }: { paycheck: Paycheck }) {
  const edit = useEditPaycheck();
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState("");
  const [problem, setProblem] = useState<string>();

  const reason = movedReason(paycheck);
  // A one-off goes by its own name, with its source.
  const shownName = paycheck.name === null ? paycheck.sourceName : `${paycheck.name} · ${paycheck.sourceName}`;
  const description = `${shownName}, ${formatDay(paycheck.payDate)}`;

  const start = () => {
    setText(centsToInput(paycheck.netCents));
    setProblem(undefined);
    edit.reset();
    setEditing(true);
  };

  const save = (event: FormEvent) => {
    event.preventDefault();
    const netCents = parseDollars(text);
    if (netCents === null || netCents < 0) {
      setProblem(AMOUNT_HELP);
      return;
    }
    edit.mutate({ paycheck, netCents }, { onSuccess: () => setEditing(false) });
  };

  return (
    <li className="py-3">
      <div className="flex items-center gap-3">
        <div className="w-24 shrink-0">
          <p className="font-semibold">{formatWeekdayDay(paycheck.payDate)}</p>
          {reason && <p className="text-sm text-muted">{reason}</p>}
        </div>
        {/* Wraps rather than truncates, so the whole name stays readable on a phone. */}
        <p className="min-w-0 flex-1 wrap-anywhere">{shownName}</p>
        {/* A one-off is changed on its own form, from the income source; only a scheduled paycheck is typed over here. */}
        {paycheck.oneOffId !== null && <Amount cents={paycheck.netCents} className="shrink-0 font-semibold" />}
        {paycheck.oneOffId === null && !editing && (
          <button
            type="button"
            onClick={start}
            aria-label={`Change the amount for ${description}`}
            // Hovering shows what the app projected before this paycheck was typed over.
            title={paycheck.projectedCents === null ? undefined : `Projected: ${formatCents(paycheck.projectedCents)}`}
            className="min-h-11 shrink-0 rounded-lg font-semibold"
          >
            <Amount cents={paycheck.netCents} estimate={paycheck.estimate} />
          </button>
        )}
      </div>

      {editing && (
        <form onSubmit={save} className="mt-2 flex flex-wrap items-center justify-end gap-2">
          <input
            autoFocus
            aria-label={`Amount for ${description}`}
            value={text}
            onChange={(event) => setText(event.target.value)}
            onKeyDown={(event) => event.key === "Escape" && setEditing(false)}
            inputMode="decimal"
            autoComplete="off"
            className={`${INPUT} w-32 text-right tabular-nums`}
          />
          <button type="submit" disabled={edit.isPending} className={BUTTON_LINK}>
            {edit.isPending ? "Saving…" : "Save"}
          </button>
          <button type="button" onClick={() => setEditing(false)} className={`${BUTTON_LINK} text-muted`}>
            Cancel
          </button>
          {(problem ?? edit.error?.message) && (
            <p role="alert" className="w-full text-right text-sm text-danger">
              {problem ?? edit.error?.message}
            </p>
          )}
        </form>
      )}
    </li>
  );
}
