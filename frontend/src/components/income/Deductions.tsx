import { ChevronLeft, ChevronRight } from "lucide-react";
import { type ReactNode, useId } from "react";
import type { Deduction, DeductionInput } from "../../api/income";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { parseRate, rateToInput } from "../../lib/rates";
import { MoneyField } from "../MoneyField";
import { Picker, TextPicker } from "../Picker";
import { BUTTON_LINK, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

/*
 * What is taken out of a paycheck, as the income form and the one-off paycheck form both ask for
 * it: one deduction at a time, each a name, dollars or a percent of gross pay, and the account it
 * goes into. Shared so the two forms read and check deductions the same way.
 */

const DEDUCTION_SUGGESTIONS = ["401(k)", "Roth 401(k)", "403(b)", "HSA", "FSA", "Pension"] as const;

/** Nobody can have more than all of their pay taken out. */
const MAX_SHARE_BP = 10_000;

export const GROSS_NEEDED_FOR_PERCENT =
  "A deduction given as a percent of your pay needs the gross amount per paycheck. Please fill that in as well.";

/** How a deduction is given: dollars a paycheck, or a share of gross pay. */
export type DeductionUnit = "dollars" | "percent";

export interface DeductionRow {
  /** Stable key for React; never sent to the backend. */
  key: number;
  name: string;
  unit: DeductionUnit;
  /** The dollars typed, when `unit` is dollars. */
  amount: string;
  /** The percent of gross pay typed, when `unit` is percent. */
  percent: string;
  toAccountId: string | null;
}

/** The form's rows for deductions already saved. */
export function deductionRows(deductions: readonly Deduction[]): DeductionRow[] {
  return deductions.map((deduction, index) => ({
    key: index,
    name: deduction.name,
    unit: deduction.percentBp === null ? "dollars" : "percent",
    amount: deduction.percentBp === null ? centsToInput(deduction.amountCents) : "",
    percent: deduction.percentBp === null ? "" : rateToInput(deduction.percentBp),
    toAccountId: deduction.toAccountId === null ? null : String(deduction.toAccountId),
  }));
}

/** A new, empty row after the last. */
export function withNewRow(rows: readonly DeductionRow[]): DeductionRow[] {
  return [
    ...rows,
    { key: Math.max(-1, ...rows.map((row) => row.key)) + 1, name: "", unit: "dollars", amount: "", percent: "", toAccountId: null },
  ];
}

/**
 * The rows as a request, or what is wrong and with which one. `grossCents` is the gross pay
 * typed, which a share of pay needs.
 */
export function parseDeductions(
  rows: readonly DeductionRow[],
  grossCents: number | null,
): { deductions: DeductionInput[] } | { problem: string; at: number } {
  const deductions: DeductionInput[] = [];
  for (const [at, row] of rows.entries()) {
    const toAccountId = row.toAccountId === null ? null : Number(row.toAccountId);
    if (row.unit === "percent") {
      const percentBp = parseRate(row.percent);
      if (row.name.trim() === "" || percentBp === null || percentBp > MAX_SHARE_BP) {
        return { problem: "Each deduction needs a name and a percent of your pay like 6.", at };
      }
      if (grossCents === null) {
        return { problem: GROSS_NEEDED_FOR_PERCENT, at };
      }
      deductions.push({ name: row.name, percentBp, toAccountId });
      continue;
    }
    const amountCents = parseDollars(row.amount);
    if (row.name.trim() === "" || amountCents === null) {
      return { problem: "Each deduction needs a name and an amount like 150.00.", at };
    }
    deductions.push({ name: row.name, amountCents, toAccountId });
  }
  return { deductions };
}

/**
 * The deductions, one at a time: each asks four things, and the form never scrolls however many
 * there are. `at` is the one in view.
 */
export function DeductionsEditor({
  rows,
  onRowsChange,
  at,
  onAtChange,
  accountOptions,
  grossCents,
  emptyText,
}: {
  rows: readonly DeductionRow[];
  onRowsChange: (rows: DeductionRow[]) => void;
  at: number;
  onAtChange: (at: number) => void;
  accountOptions: readonly { id: string; label: string; color: string }[];
  /** The gross pay typed so far, for what a percent comes to. */
  grossCents: number | null;
  /** Said when there are none yet. */
  emptyText: string;
}) {
  const id = useId();
  const shown = Math.min(at, rows.length - 1);
  const deduction = rows[shown];
  const update = (key: number, change: Partial<DeductionRow>) =>
    onRowsChange(rows.map((row) => (row.key === key ? { ...row, ...change } : row)));
  const add = () => {
    onRowsChange(withNewRow(rows));
    onAtChange(rows.length);
  };

  if (deduction === undefined) {
    return (
      <>
        <p className="text-muted">{emptyText}</p>
        <button type="button" onClick={add} className={BUTTON_SECONDARY}>
          + Add a deduction
        </button>
      </>
    );
  }
  return (
    <div key={deduction.key} className="space-y-3">
      <div className="flex items-center justify-between gap-2">
        <p className="font-semibold">
          Deduction {shown + 1} of {rows.length}
        </p>
        {rows.length > 1 && (
          <Pager
            label="deduction"
            onPrevious={shown === 0 ? undefined : () => onAtChange(shown - 1)}
            onNext={shown === rows.length - 1 ? undefined : () => onAtChange(shown + 1)}
          />
        )}
      </div>
      <div className="grid grid-cols-[minmax(0,1fr)_7.5rem] gap-3">
        <TextPicker
          label="Name"
          noun="deduction"
          placeholder="e.g. 401(k)"
          suggestions={DEDUCTION_SUGGESTIONS}
          value={deduction.name}
          onChange={(text) => update(deduction.key, { name: text })}
        />
        <div>
          <label htmlFor={`${id}-deduction-unit`} className={LABEL}>
            Given as
          </label>
          <select
            id={`${id}-deduction-unit`}
            value={deduction.unit}
            onChange={(event) => update(deduction.key, { unit: event.target.value as DeductionUnit })}
            className={INPUT}
          >
            <option value="dollars">Dollars</option>
            <option value="percent">Percent</option>
          </select>
        </div>
      </div>
      {deduction.unit === "dollars" ? (
        <MoneyField label="Amount" value={deduction.amount} onChange={(text) => update(deduction.key, { amount: text })} />
      ) : (
        <div>
          <label htmlFor={`${id}-deduction-percent`} className={LABEL}>
            Percent of gross pay
          </label>
          <input
            id={`${id}-deduction-percent`}
            value={deduction.percent}
            onChange={(event) => update(deduction.key, { percent: event.target.value })}
            inputMode="decimal"
            autoComplete="off"
            placeholder="e.g. 6"
            className={`${INPUT} tabular-nums`}
          />
          <p className={HINT}>{percentHint(deduction.percent, grossCents)}</p>
        </div>
      )}
      <Picker
        label="Goes into (optional)"
        noun="account"
        placeholder="Not one of my accounts"
        options={accountOptions}
        value={deduction.toAccountId}
        onChange={(accountId) => update(deduction.key, { toAccountId: accountId })}
        clearable
      />
      <div className="-mx-2 flex flex-wrap justify-between gap-x-2">
        <button type="button" onClick={() => onRowsChange(rows.filter((row) => row.key !== deduction.key))} className={BUTTON_LINK}>
          Remove this deduction
        </button>
        <button type="button" onClick={add} className={BUTTON_LINK}>
          + Add another
        </button>
      </div>
    </div>
  );
}

/**
 * One screen of a form shown a screen at a time. While stepping through, it says what it asks;
 * when it was chosen by name at the top of the form, that name is enough, and is kept for
 * screen readers only.
 */
export function Screen({ title, named, children }: { title: string; named: boolean; children: ReactNode }) {
  return (
    <fieldset className="min-w-0">
      <legend className={named ? "float-left mb-3 w-full text-lg font-semibold" : "sr-only"}>{title}</legend>
      <div className="clear-both space-y-4">{children}</div>
    </fieldset>
  );
}

/** Steps through a list shown a few at a time: the one before, and the one after. */
export function Pager({
  label,
  status,
  onPrevious,
  onNext,
}: {
  label: string;
  status?: string;
  onPrevious?: () => void;
  onNext?: () => void;
}) {
  const arrow = "flex size-11 shrink-0 items-center justify-center rounded-lg border border-line disabled:opacity-40";
  return (
    <div className="flex items-center gap-2">
      {status && (
        <p role="status" className="text-sm text-muted tabular-nums">
          {status}
        </p>
      )}
      <button type="button" onClick={onPrevious} disabled={!onPrevious} aria-label={`Previous ${label}`} className={arrow}>
        <ChevronLeft aria-hidden className="size-5" />
      </button>
      <button type="button" onClick={onNext} disabled={!onNext} aria-label={`Next ${label}`} className={arrow}>
        <ChevronRight aria-hidden className="size-5" />
      </button>
    </div>
  );
}

/** Under a percent deduction: what it comes to a paycheck, or what is still needed to say. */
function percentHint(percent: string, grossCents: number | null): string {
  if (grossCents === null) {
    return "Fill in the gross amount per paycheck too, so this can be worked out.";
  }
  const percentBp = parseRate(percent);
  if (percentBp === null) {
    return "Of the gross amount per paycheck.";
  }
  // The backend's figure is the one that counts; this is the same sum, shown while typing.
  return `About ${formatCents(Math.round((grossCents * percentBp) / 10_000))} a paycheck.`;
}
