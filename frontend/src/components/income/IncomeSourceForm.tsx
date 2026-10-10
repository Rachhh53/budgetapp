import { Banknote, Trash2 } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import type { Account } from "../../api/accounts";
import {
  type IncomeSource,
  type IncomeSourceInput,
  type MatchMode,
  PAY_FREQUENCIES,
  type PayFrequency,
  useDeleteIncomeSource,
  useSaveIncomeSource,
  WEEKEND_RULES,
  type WeekendRule,
} from "../../api/income";
import { todayIso } from "../../lib/dates";
import { centsToInput, parseDollars } from "../../lib/money";
import { parseRate, rateToInput } from "../../lib/rates";
import { ConfirmDelete } from "../ConfirmDelete";
import { DaySelect } from "../DaySelect";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { Picker, TextPicker } from "../Picker";
import { Segmented } from "../Segmented";
import { BUTTON_LINK, BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";
import { FREQUENCY_LABELS, WEEKEND_LABELS } from "./schedule";

const DEDUCTION_SUGGESTIONS = ["401(k)", "Roth 401(k)", "403(b)", "HSA", "FSA", "Pension"] as const;

/** Whether the employer adds anything, and how it is worked out. */
type MatchChoice = "none" | MatchMode;

const MATCH_OPTIONS: readonly { value: MatchChoice; label: string }[] = [
  { value: "none", label: "No match" },
  { value: "fixed", label: "Same amount" },
  { value: "formula", label: "Share of what I put in" },
];

/** The most that can be matched, as the backend takes it: 1,000% of a contribution, up to all of gross pay. */
const MAX_MATCH_BP = 100_000;
const MAX_CAP_BP = 10_000;

interface DeductionRow {
  /** Stable key for React; never sent to the backend. */
  key: number;
  name: string;
  amount: string;
  toAccountId: string | null;
}

interface IncomeSourceFormProps {
  /** The source being changed; leave out to add a new one. */
  source?: IncomeSource;
  /** Whether any income source exists yet. The first one always sets the pay periods. */
  isFirst: boolean;
  accounts: readonly Account[];
  onClose: () => void;
}

/** Add or change an income source: who pays, when, how much, and where it goes. */
export function IncomeSourceForm({ source, isFirst, accounts, onClose }: IncomeSourceFormProps) {
  const id = useId();
  const save = useSaveIncomeSource(source?.id);
  const schedule = source?.schedule;

  const [name, setName] = useState(source?.name ?? "");
  const [depositAccountId, setDepositAccountId] = useState<string | null>(
    source ? String(source.depositAccountId) : null,
  );
  const [frequency, setFrequency] = useState<PayFrequency>(schedule?.frequency ?? "biweekly");
  const [day1, setDay1] = useState(schedule?.day1 ?? 1);
  const [day2, setDay2] = useState(schedule?.day2 ?? 15);
  const [startDate, setStartDate] = useState(schedule?.startDate ?? todayIso());
  const [manualDates, setManualDates] = useState<string[]>(
    schedule && schedule.manualDates.length > 0 ? schedule.manualDates : ["", ""],
  );
  const [weekendRule, setWeekendRule] = useState<WeekendRule>(schedule?.weekendRule ?? "previous_business_day");
  const [net, setNet] = useState(source ? centsToInput(source.netCents) : "");
  const [netIsEstimate, setNetIsEstimate] = useState(source?.netIsEstimate ?? false);
  const [gross, setGross] = useState(source?.grossCents != null ? centsToInput(source.grossCents) : "");
  // The first source, and the current primary, must stay primary: something has to set the pay periods.
  const primaryLocked = isFirst || source?.primary === true;
  const [primary, setPrimary] = useState(primaryLocked);
  const [deductions, setDeductions] = useState<DeductionRow[]>(
    (source?.deductions ?? []).map((deduction, index) => ({
      key: index,
      name: deduction.name,
      amount: centsToInput(deduction.amountCents),
      toAccountId: deduction.toAccountId === null ? null : String(deduction.toAccountId),
    })),
  );
  const match = source?.match ?? null;
  const [matchChoice, setMatchChoice] = useState<MatchChoice>(match?.mode ?? "none");
  const [matchAccountId, setMatchAccountId] = useState<string | null>(match ? String(match.toAccountId) : null);
  const [matchFixed, setMatchFixed] = useState(match?.fixedCents != null ? centsToInput(match.fixedCents) : "");
  const [matchPercent, setMatchPercent] = useState(match?.matchBp != null ? rateToInput(match.matchBp) : "");
  const [capPercent, setCapPercent] = useState(match?.capBpOfGross != null ? rateToInput(match.capBpOfGross) : "");
  const [problem, setProblem] = useState<string>();

  // Archived accounts are left out, unless this source already points at one.
  const inUse = new Set([depositAccountId, matchAccountId, ...deductions.map((deduction) => deduction.toAccountId)]);
  const offered = accounts.filter((account) => !account.archived || inUse.has(String(account.id)));
  const accountOptions = offered.map((account) => ({ id: String(account.id), label: account.name, color: account.color }));
  // A match is retirement money: it can only go into an investment account.
  const investmentOptions = offered
    .filter((account) => account.type === "investment")
    .map((account) => ({ id: String(account.id), label: account.name, color: account.color }));

  const updateDeduction = (key: number, change: Partial<DeductionRow>) =>
    setDeductions((rows) => rows.map((row) => (row.key === key ? { ...row, ...change } : row)));

  /** Turns the form into a request, or explains what still needs attention. */
  const toInput = (): IncomeSourceInput | string => {
    if (depositAccountId === null) {
      return "Please choose the account this pay is deposited to.";
    }
    const netCents = parseDollars(net);
    const grossCents = gross.trim() === "" ? null : parseDollars(gross);
    if (netCents === null || (gross.trim() !== "" && grossCents === null)) {
      return AMOUNT_HELP;
    }
    const pickedDates = manualDates.filter((date) => date !== "");
    if (frequency === "manual_dates" && pickedDates.length < 2) {
      return "Please add at least two paydays, so there's a pay period between them.";
    }
    const parsedDeductions = [];
    for (const deduction of deductions) {
      const amountCents = parseDollars(deduction.amount);
      if (deduction.name.trim() === "" || amountCents === null) {
        return "Each deduction needs a name and an amount like 150.00.";
      }
      parsedDeductions.push({
        name: deduction.name,
        amountCents,
        toAccountId: deduction.toAccountId === null ? null : Number(deduction.toAccountId),
      });
    }
    let matchInput: IncomeSourceInput["match"] = null;
    if (matchChoice !== "none") {
      if (matchAccountId === null) {
        return "Please choose the investment account the employer match is paid into.";
      }
      if (matchChoice === "fixed") {
        const fixedCents = parseDollars(matchFixed);
        if (fixedCents === null || fixedCents < 0) {
          return "Please enter the match as an amount like 75.00.";
        }
        matchInput = { toAccountId: Number(matchAccountId), mode: "fixed", fixedCents, matchBp: null, capBpOfGross: null };
      } else {
        const matchBp = parseRate(matchPercent);
        const capBpOfGross = parseRate(capPercent);
        if (matchBp === null || matchBp > MAX_MATCH_BP) {
          return "Please enter how much of what you put in is matched, as a percentage like 50.";
        }
        if (capBpOfGross === null || capBpOfGross > MAX_CAP_BP) {
          return "Please enter how much of your pay is matched up to, as a percentage like 6.";
        }
        if (grossCents === null) {
          return "A match worked out from your pay needs the gross amount of a paycheck. Please fill that in as well.";
        }
        matchInput = { toAccountId: Number(matchAccountId), mode: "formula", fixedCents: null, matchBp, capBpOfGross };
      }
    }
    const byDayOfMonth = frequency === "semi_monthly" || frequency === "monthly";
    return {
      name,
      depositAccountId: Number(depositAccountId),
      grossCents,
      netCents,
      netIsEstimate,
      primary,
      schedule: {
        frequency,
        day1: byDayOfMonth ? day1 : null,
        day2: frequency === "semi_monthly" ? day2 : null,
        // Paydays on fixed days of the month need no anchor; start from January so the
        // pay period we are in right now already exists.
        startDate: byDayOfMonth ? (schedule?.startDate ?? `${todayIso().slice(0, 4)}-01-01`) : startDate,
        endDate: schedule?.endDate ?? null,
        weekendRule,
        manualDates: frequency === "manual_dates" ? pickedDates : [],
      },
      deductions: parsedDeductions,
      match: matchInput,
    };
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const input = toInput();
    if (typeof input === "string") {
      setProblem(input);
      return;
    }
    setProblem(undefined);
    save.mutate(input, { onSuccess: onClose });
  };

  return (
    <Modal title={source ? "Edit income source" : "Add an income source"} icon={Banknote} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <div>
          <label htmlFor={`${id}-name`} className={LABEL}>
            Name
          </label>
          <input
            id={`${id}-name`}
            value={name}
            onChange={(event) => setName(event.target.value)}
            maxLength={80}
            required
            autoComplete="off"
            placeholder="Employer or where the money comes from"
            className={INPUT}
          />
        </div>

        <Picker
          label="Deposited to"
          noun="account"
          placeholder="Choose or type an account"
          options={accountOptions}
          value={depositAccountId}
          onChange={setDepositAccountId}
        />

        <fieldset className="space-y-4 rounded-lg border border-line p-4">
          <legend className="px-1 font-semibold">When you're paid</legend>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <label htmlFor={`${id}-frequency`} className={LABEL}>
                How often
              </label>
              <select
                id={`${id}-frequency`}
                value={frequency}
                onChange={(event) => setFrequency(event.target.value as PayFrequency)}
                className={INPUT}
              >
                {PAY_FREQUENCIES.map((option) => (
                  <option key={option} value={option}>
                    {FREQUENCY_LABELS[option]}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor={`${id}-weekend`} className={LABEL}>
                On a weekend or holiday
              </label>
              <select
                id={`${id}-weekend`}
                value={weekendRule}
                onChange={(event) => setWeekendRule(event.target.value as WeekendRule)}
                className={INPUT}
              >
                {WEEKEND_RULES.map((option) => (
                  <option key={option} value={option}>
                    {WEEKEND_LABELS[option]}
                  </option>
                ))}
              </select>
            </div>
          </div>

          {(frequency === "weekly" || frequency === "biweekly") && (
            <div>
              <label htmlFor={`${id}-start`} className={LABEL}>
                A payday to count from
              </label>
              <input
                id={`${id}-start`}
                type="date"
                value={startDate}
                onChange={(event) => setStartDate(event.target.value)}
                required
                className={INPUT}
              />
              <p className={HINT}>Any real payday works — your most recent one is ideal. Later paydays repeat from it.</p>
            </div>
          )}

          {(frequency === "semi_monthly" || frequency === "monthly") && (
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <DaySelect
                id={`${id}-day1`}
                label={frequency === "monthly" ? "Day of the month" : "First payday of the month"}
                value={day1}
                onChange={setDay1}
              />
              {frequency === "semi_monthly" && (
                <DaySelect id={`${id}-day2`} label="Second payday of the month" value={day2} onChange={setDay2} />
              )}
            </div>
          )}

          {frequency === "manual_dates" && (
            <div>
              <p className={LABEL}>Paydays</p>
              <ul className="space-y-2">
                {manualDates.map((date, index) => (
                  // Rows have no identity beyond their position; the list only grows or shrinks at a known index.
                  <li key={index} className="flex gap-2">
                    <input
                      type="date"
                      aria-label={`Payday ${index + 1}`}
                      value={date}
                      onChange={(event) =>
                        setManualDates((dates) => dates.map((old, at) => (at === index ? event.target.value : old)))
                      }
                      className={INPUT}
                    />
                    <button
                      type="button"
                      aria-label={`Remove payday ${index + 1}`}
                      disabled={manualDates.length <= 2}
                      onClick={() => setManualDates((dates) => dates.filter((_, at) => at !== index))}
                      className="flex size-11 shrink-0 items-center justify-center rounded-lg border border-line disabled:opacity-40"
                    >
                      <Trash2 aria-hidden className="size-4" />
                    </button>
                  </li>
                ))}
              </ul>
              <button type="button" onClick={() => setManualDates((dates) => [...dates, ""])} className={BUTTON_LINK}>
                + Add a payday
              </button>
            </div>
          )}
        </fieldset>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <div>
            <MoneyField label="Take-home per paycheck" value={net} onChange={setNet} required />
            <label className="mt-1 flex min-h-11 items-center gap-3">
              <input
                type="checkbox"
                checked={netIsEstimate}
                onChange={(event) => setNetIsEstimate(event.target.checked)}
                className="size-5 accent-(--accent)"
              />
              <span>Estimate, not final yet</span>
            </label>
          </div>
          <MoneyField label="Gross per paycheck (optional)" value={gross} onChange={setGross} hint="Before taxes and deductions." />
        </div>

        <fieldset className="space-y-3 rounded-lg border border-line p-4">
          <legend className="px-1 font-semibold">Goes to your accounts</legend>
          <p className="text-sm text-muted">
            Money taken out of each paycheck before it reaches you, like a 401(k) or HSA contribution.
          </p>
          {deductions.map((deduction, index) => (
            <div key={deduction.key} className="space-y-3 rounded-lg bg-bg p-3">
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <TextPicker
                  label={`Deduction ${index + 1}`}
                  noun="deduction"
                  placeholder="e.g. 401(k)"
                  suggestions={DEDUCTION_SUGGESTIONS}
                  value={deduction.name}
                  onChange={(text) => updateDeduction(deduction.key, { name: text })}
                />
                <MoneyField
                  label="Amount"
                  value={deduction.amount}
                  onChange={(text) => updateDeduction(deduction.key, { amount: text })}
                />
              </div>
              <Picker
                label="Goes into (optional)"
                noun="account"
                placeholder="Not one of my accounts"
                options={accountOptions}
                value={deduction.toAccountId}
                onChange={(accountId) => updateDeduction(deduction.key, { toAccountId: accountId })}
                clearable
              />
              <button
                type="button"
                onClick={() => setDeductions((rows) => rows.filter((row) => row.key !== deduction.key))}
                className={BUTTON_LINK}
              >
                Remove deduction {index + 1}
              </button>
            </div>
          ))}
          <button
            type="button"
            onClick={() =>
              setDeductions((rows) => [
                ...rows,
                { key: Math.max(-1, ...rows.map((row) => row.key)) + 1, name: "", amount: "", toAccountId: null },
              ])
            }
            className={BUTTON_LINK}
          >
            + Add a deduction
          </button>
        </fieldset>

        <fieldset className="space-y-3 rounded-lg border border-line p-4">
          <legend className="px-1 font-semibold">Employer match</legend>
          <p className="text-sm text-muted">What your employer adds to a retirement account with each paycheck, on top of what you put in.</p>
          <Segmented legend="Does your employer add anything?" options={MATCH_OPTIONS} value={matchChoice} onChange={setMatchChoice} />
          {matchChoice !== "none" && (
            <div>
              <Picker
                label="Paid into"
                noun="investment account"
                placeholder="Choose or type an investment account"
                options={investmentOptions}
                value={matchAccountId}
                onChange={setMatchAccountId}
              />
              {investmentOptions.length === 0 && (
                <p className={HINT}>
                  There is no investment account yet. Add one on the Accounts screen first, with a label such as 401(k).
                </p>
              )}
            </div>
          )}
          {matchChoice === "fixed" && <MoneyField label="Match per paycheck" value={matchFixed} onChange={setMatchFixed} />}
          {matchChoice === "formula" && (
            <>
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <div>
                  <label htmlFor={`${id}-match-percent`} className={LABEL}>
                    They match (%)
                  </label>
                  <input
                    id={`${id}-match-percent`}
                    value={matchPercent}
                    onChange={(event) => setMatchPercent(event.target.value)}
                    inputMode="decimal"
                    autoComplete="off"
                    placeholder="e.g. 50"
                    className={`${INPUT} tabular-nums`}
                  />
                  <p className={HINT}>Of what you put in.</p>
                </div>
                <div>
                  <label htmlFor={`${id}-match-cap`} className={LABEL}>
                    Up to (% of gross pay)
                  </label>
                  <input
                    id={`${id}-match-cap`}
                    value={capPercent}
                    onChange={(event) => setCapPercent(event.target.value)}
                    inputMode="decimal"
                    autoComplete="off"
                    placeholder="e.g. 6"
                    className={`${INPUT} tabular-nums`}
                  />
                  <p className={HINT}>Contributions beyond this are not matched.</p>
                </div>
              </div>
              <p className={HINT}>
                "50% up to 6%" is 50 and 6. What you put in is whatever the deductions above send to the same account, and the gross
                amount per paycheck is needed to work it out.
              </p>
            </>
          )}
        </fieldset>

        <div>
          <label className="flex min-h-11 items-center gap-3">
            <input
              type="checkbox"
              checked={primary}
              disabled={primaryLocked}
              onChange={(event) => setPrimary(event.target.checked)}
              className="size-5 accent-(--accent)"
            />
            <span className="font-medium">This paycheck sets my pay periods</span>
          </label>
          <p className={HINT}>
            {isFirst
              ? "Your first income source always sets your pay periods. You can change that once you add another."
              : source?.primary
                ? "To hand this over, edit another income source and tick this box there."
                : "Each pay period runs from one of these paydays to the day before the next."}
          </p>
        </div>

        {(problem ?? save.error?.message) && (
          <p role="alert" className="text-danger">
            {problem ?? save.error?.message}
          </p>
        )}

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="submit" disabled={save.isPending} className={BUTTON_PRIMARY}>
            {save.isPending ? "Saving…" : source ? "Save changes" : "Add income source"}
          </button>
        </div>
      </form>

      {source && (
        <div className="mt-6 border-t border-line pt-4">
          <DeleteSource source={source} onDeleted={onClose} />
        </div>
      )}
    </Modal>
  );
}

function DeleteSource({ source, onDeleted }: { source: IncomeSource; onDeleted: () => void }) {
  const remove = useDeleteIncomeSource(source.id);
  return (
    <ConfirmDelete
      action="Delete income source…"
      question={`Delete ${source.name}?`}
      detail="Its schedule, deductions and any paycheck amounts you typed in go with it. You can undo this straight afterwards."
      confirmLabel="Yes, delete it"
      pending={remove.isPending}
      error={remove.error?.message}
      onConfirm={() => remove.mutate(undefined, { onSuccess: onDeleted })}
    />
  );
}
